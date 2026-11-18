package org.ragai.service;

import jakarta.annotation.PostConstruct;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 知识库初始化：把 PDF 文档切片 → 向量化 → 分批写入 VectorStore。
 * <p>
 * 不使用 Spring AI 自带的 PagePdfDocumentReader：其版面坐标排序 Comparator 不满足 Java TimSort 合约，
 * 复杂 PDF（双栏/表格/OCR 扫描件）会抛 "Comparison method violates its general contract"。
 * 这里改用 PDFBox 原生 PDFTextStripper 按文本流顺序抽全文，从源头规避坏排序。
 * <p>
 * 由于 DashScope text-embedding-v3 官方限制单次批量 ≤10，写入时按批切分。
 */
@Component
public class RagInitializer {

    private static final Logger log = LoggerFactory.getLogger(RagInitializer.class);

    // 统一强制 PDFBox 使用磁盘文件缓存而非主内存，避免大 PDF 加载时内存暴涨。
    // 兼容 PDFBox 2.x ~ 3.1.x 各小版本不同命名的 System Property。
    static {
        final String FALSE = "false";
        final String FILE = "file";
        setProp("pdfbox.useMainMemory", FALSE);
        setProp("pdfbox.memory.useMainMemory", FALSE);
        setProp("org.apache.pdfbox.useMainMemory", FALSE);
        setProp("pdfbox.scratchFile.useMainMemory", FALSE);
        setProp("pdfbox.stream.cache", FILE);
        setProp("pdfbox.memory.usage", "tempfile");
        setProp("pdfbox.memory.mainMemoryBytes", String.valueOf(128L * 1024 * 1024));
        setProp("pdfbox.mainMemoryUsageBytes", String.valueOf(128L * 1024 * 1024));

        long maxMemBytes = Runtime.getRuntime().maxMemory();
        long maxMemMB = maxMemBytes / (1024 * 1024);
        String pdfboxVer = "UNKNOWN";
        try {
            Package p = PDDocument.class.getPackage();
            if (p != null && p.getImplementationVersion() != null) pdfboxVer = p.getImplementationVersion();
        } catch (Throwable ignore) {
        }
        if (maxMemMB < 2048) {
            log.warn("[RagInitializer] ⚠️ JVM 堆 < 2GB（当前 " + maxMemMB + " MB），超大扫描 PDF 可能发生 Full GC。");
            log.info("[RagInitializer]    建议在 Run Configuration 的 VM options 中加入 -Xmx2g。");
        }
    }

    private static void setProp(String key, String val) {
        try {
            System.setProperty(key, val);
        } catch (Throwable ignore) {
        }
    }

    /**
     * DashScope Embedding 单次批量上限（官方硬限制，不要调大）
     */
    private static final int DASHSCOPE_EMBEDDING_MAX_BATCH = 10;

    private final dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore vectorStore;
    private final dev.langchain4j.model.embedding.EmbeddingModel embeddingModel;

    private final Bm25IndexService bm25IndexService;
    /**
     * PGVector JDBC：用于「PDF 已入库」去重（PGVector 未启用时为 null）
     */
    private final org.springframework.jdbc.core.JdbcTemplate pgJdbc;
    /**
     * 512 字/块、句子优先装桶（V4，2026-11-18 上生产）。块文本是原文逐字子串，刀口优先落句末。
     * ⚠️ 历史：迁移前 {@code TokenTextSplitter.withChunkSize(512)} 是 512 <b>token</b>（501 块），
     * 2026-11-10 换 {@code DocumentSplitters.recursive(512, 0)} 是 512 <b>字符</b>（961 块），
     * 逐文件对账见 {@code scratch/block_count_delta.out}（旧 501 块仍留在 {@code public.vector_store} 里）。
     * V4 离线对照见 {@code scratch/splitter_fix_compare.out}：块尾中断 80.6%→6.9%，块数约 +15%。
     * ⚠️ 换切法必须 bump {@link #EXTRACT_PIPELINE_VERSION} 才会真重切（sameFile 早退条件含版本号）；
     * bump 与全库重灌放在同一次付费操作里做，避免新旧两代块混在一张表里。
     */
    private final DocumentSplitter splitter = new SentenceFirstSplitter(512);

    @Value("${app.pdf-path:D:/ai-docs}")
    private String pdfPath;

    // 判重用的表名跟随 app.pgvector.*，写死表名会让探测失效后静默追加重复块
    @Value("${app.pgvector.schema-name:public}")
    private String pgSchema = "public";

    @Value("${app.pgvector.lc-table-name:lc_document_embeddings}")
    private String pgTable = "lc_document_embeddings";

    // ========== OCR 兜底配置（纯图片扫描 PDF 用） ==========
    @Value("${app.ocr.enabled:true}")
    private boolean ocrEnabled;

    @Value("${app.ocr.tesseract-path:D:/Program Files/Tesseract-OCR/tesseract.exe}")
    private String ocrTesseractPath;

    @Value("${app.ocr.tessdata-dir:D:/Program Files/Tesseract-OCR/tessdata}")
    private String ocrTessdataDir;

    @Value("${app.ocr.language:chi_sim+eng}")
    private String ocrLanguage;

    @Value("${app.ocr.dpi:300}")
    private int ocrDpi;

    @Value("${app.ocr.page-timeout-seconds:120}")
    private int ocrPageTimeoutSec;

    public RagInitializer(
            @Autowired(required = false) dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore vectorStore,
            @Autowired(required = false)
            @org.springframework.beans.factory.annotation.Qualifier("pgVectorJdbcTemplate")
            org.springframework.jdbc.core.JdbcTemplate pgJdbc,
            Bm25IndexService bm25IndexService,
            dev.langchain4j.model.embedding.EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.pgJdbc = pgJdbc;
        this.bm25IndexService = bm25IndexService;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 启动期增量对账：只补建向量表有但 bm25_docs 缺失的 source，清理已删除文件的残留行。
     * 库未变时零写入，不再每次启动都 TRUNCATE 全表重建。
     * 关键词倒排表是自建的两张表，向量库写入不会自动带上它。
     * 入库后不刷新，新丢进来的 PDF 就是「意思搜得到、词搜不到」——混合检索的关键词这条腿是瞎的。
     */
    private void reconcileKeywordIndex() {
        if (bm25IndexService == null || !bm25IndexService.isAvailable()) return;
        log.info("[RagInitializer] 启动期 BM25 增量对账："
                + bm25IndexService.reconcileAll());
    }

    /**
     * 只重建这一份文件的倒排行，代价跟它的块数成正比。
     * 热监听走这条：以前每份 PDF 入库都 TRUNCATE 整张表重插一遍，两个毛病——
     * ① 代价跟**全库块数**成正比（501 块 1.6~1.8s 无感，库堆大就成了每次入库的固定重税）；
     * ② TRUNCATE 让**所有**文件的关键词行在重插完成前都不在表里，这个空窗同样跟全库长度一起涨；
     * 按份刷只让**这一份**短暂缺行。
     * 启动期对账走 {@link #reconcileKeywordIndex()}，只补建缺失 source，不再全量 TRUNCATE。
     */
    private void refreshKeywordIndexForSource(File pdf, String why) {
        if (bm25IndexService == null || !bm25IndexService.isAvailable()) return;
        log.info("[RagInitializer] 刷新 BM25 关键词索引（" + why + "）："
                + bm25IndexService.refreshSource(sourceKeys(pdf)));
    }

    // 子进程注册表接口 + AutoCloseable 包装，用于超时时终止遗留的 tesseract.exe 子进程
    @FunctionalInterface
    private interface ProcessRegistrar {
        void register(Process p);
    }

    private static final ProcessRegistrar NOOP_REGISTRAR = p -> {
    };

    private static final class PdfProcessRegistry implements ProcessRegistrar, AutoCloseable {
        private final ProcessRegistrar delegate;

        PdfProcessRegistry(ProcessRegistrar delegate) {
            this.delegate = delegate == null ? NOOP_REGISTRAR : delegate;
        }

        @Override
        public void register(Process p) {
            delegate.register(p);
        }

        @Override
        public void close() {
        }
    }

    // OCR 配置有效性（运行时一次性判定，缓存结果）
    private transient Boolean ocrAvailableCache = null;
    private transient String effectiveTessdataDir = null;
    /**
     * OCR 诊断原文（启动时打印的那行）。/rag/diag 要能回答「OCR 到底就绪没、tessdata 用的哪个目录」。
     */
    private transient String ocrDiagLine = null;
    /**
     * 本轮启动的入库流水：扫描/跳过/新灌/失败/chunks/批次。/rag/diag 的「入库历史」数据来源。
     */
    private volatile Map<String, Object> lastBootIngest = null;

    /**
     * 块过滤累计计数：进程生命周期内所有入库（启动批量 + 热监听 + 手动）的累计值。
     * 只统计真正走过过滤闸门的块，供 /rag/diag 的 block_filter 段核对过滤效果。
     */
    private final java.util.concurrent.atomic.AtomicLong chunkFilterOriginal =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong chunkFilterDroppedLowInfo =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong chunkFilterDroppedWithinFile =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong chunkFilterDroppedCrossFile =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong chunkFilterKept =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 块级增量复用计数：重灌时命中旧 chunk_hash 而复用旧 embedding 的块数，以及新调模型的块数。
     * 进程内累计，供 /rag/diag 核对增量效果。
     */
    private final java.util.concurrent.atomic.AtomicLong chunkReuseOld =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong chunkReuseNew =
            new java.util.concurrent.atomic.AtomicLong();

    // WatchService 相关：目录热监听
    private volatile WatchService watchService;
    private final Map<Path, Long> pendingFileStableTime = new ConcurrentHashMap<>();

    private boolean isOcrAvailable() {
        if (ocrAvailableCache != null) return ocrAvailableCache;
        if (!ocrEnabled) {
            ocrAvailableCache = false;
            return false;
        }
        boolean ok = false;
        StringBuilder diag = new StringBuilder(512);
        diag.append("[RagInitializer:OCR 诊断] ");
        String finalTessDir = ocrTessdataDir;
        try {
            File exe = new File(ocrTesseractPath);
            if (!exe.exists() || !exe.isFile()) {
                diag.append("❌ tesseract.exe 不存在: ").append(ocrTesseractPath)
                        .append("，请检查 application.yml 的 app.ocr.tesseract-path");
            } else {
                // 智能 tessdata 目录选择：① 配置目录 → ② 项目自带语言包 → ③ 用户主目录 → ④ 兜底复制到 D:/tessdata
                List<File> candidateDirs = new ArrayList<>();
                File configuredDir = new File(ocrTessdataDir);
                candidateDirs.add(configuredDir);
                candidateDirs.add(new File("D:/AI/mkbAI/.diag-tmp/dl"));
                candidateDirs.add(new File(System.getProperty("user.home"), "tessdata"));

                File bestDir = null;
                int bestDirIndex = -1;
                String missingLangs = null;
                for (int i = 0; i < candidateDirs.size(); i++) {
                    File candDir = candidateDirs.get(i);
                    if (!candDir.exists() || !candDir.isDirectory()) continue;
                    boolean allOk = true;
                    StringBuilder missSb = new StringBuilder();
                    for (String langPart : ocrLanguage.replaceAll("\\+", ",").split(",")) {
                        String l = langPart.trim();
                        if (l.isEmpty()) continue;
                        File td = new File(candDir, l + ".traineddata");
                        if (!td.exists()) {
                            allOk = false;
                            missSb.append(l).append(" ");
                        }
                    }
                    if (allOk) {
                        bestDir = candDir;
                        bestDirIndex = i;
                        break;
                    }
                    if (missingLangs == null) missingLangs = missSb.toString().trim();
                }

                // 最后兜底：把项目里的 chi_sim.traineddata 复制到 D:/tessdata
                if (bestDir == null) {
                    File srcTd = new File("D:/AI/mkbAI/.diag-tmp/dl/chi_sim.traineddata");
                    if (srcTd.exists()) {
                        File dstDir = new File("D:/tessdata");
                        try {
                            if (!dstDir.exists()) dstDir.mkdirs();
                            File dstTd = new File(dstDir, "chi_sim.traineddata");
                            if (!dstTd.exists()) Files.copy(srcTd.toPath(), dstTd.toPath());
                            boolean hasEng = new File(dstDir, "eng.traineddata").exists();
                            if (!hasEng) {
                                File engSrc = new File("D:/Program Files/Tesseract-OCR/tessdata/eng.traineddata");
                                if (engSrc.exists())
                                    Files.copy(engSrc.toPath(), new File(dstDir, "eng.traineddata").toPath());
                                hasEng = new File(dstDir, "eng.traineddata").exists();
                            }
                            boolean allOk = true;
                            for (String langPart : ocrLanguage.replaceAll("\\+", ",").split(",")) {
                                String l = langPart.trim();
                                if (l.isEmpty()) continue;
                                if (!new File(dstDir, l + ".traineddata").exists()) allOk = false;
                            }
                            if (allOk) {
                                bestDir = dstDir;
                                bestDirIndex = -2;
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                }

                if (bestDir == null) {
                    File tessDir = configuredDir;
                    if (!tessDir.exists() || !tessDir.isDirectory()) {
                        diag.append("❌ tessdata 目录不存在: ").append(ocrTessdataDir);
                    } else {
                        diag.append("❌ 缺少语言包: ").append(missingLangs == null ? "(未知)" : missingLangs)
                                .append(" ，已尝试目录：");
                        for (File cd : candidateDirs) diag.append(cd.getAbsolutePath()).append("; ");
                    }
                } else {
                    finalTessDir = bestDir.getAbsolutePath();
                    ok = true;
                    boolean usedConfigured = (bestDirIndex == 0) || (bestDir == configuredDir);
                    if (!usedConfigured) {
                        try {
                            usedConfigured = configuredDir.exists() && configuredDir.isDirectory()
                                    && configuredDir.getCanonicalFile().equals(bestDir.getCanonicalFile());
                        } catch (Throwable ignore) {
                        }
                    }
                    diag.append("✅ Tesseract 就绪：exe=").append(exe.getAbsolutePath())
                            .append("\n       tessdata（")
                            .append(usedConfigured ? ("配置路径命中：" + ocrTessdataDir)
                                    : (bestDirIndex == -2 ? "兜底自动复制到 D:/tessdata" : "自动 fallback #" + bestDirIndex + " " + bestDir.getAbsolutePath()))
                            .append("）=\n              ").append(finalTessDir)
                            .append("\n       lang=").append(ocrLanguage)
                            .append("，dpi=").append(ocrDpi)
                            .append("，单页超时=").append(ocrPageTimeoutSec).append("s");
                }
            }
        } catch (Throwable th) {
            diag.append("❌ OCR 初始化异常: ").append(th.getClass().getSimpleName()).append(": ").append(th.getMessage());
        }
        log.info("");
        log.info(diag.toString());
        if (!ok) {
            log.info("  👉 不启用 OCR 兜底，纯图片扫描 PDF 将被跳过（不影响文字型 PDF 正常入库）。");
        }
        log.info("");
        effectiveTessdataDir = finalTessDir;
        ocrAvailableCache = ok;
        ocrDiagLine = diag.toString();
        return ok;
    }

    /**
     * 单个 PDF 入库结果（给 init() 汇总用）
     */
    private static final class IngestResult {
        final int chunks;
        final int batches;
        final boolean ok;
        final boolean skipped;

        IngestResult(int chunks, int batches, boolean ok, boolean skipped) {
            this.chunks = chunks;
            this.batches = batches;
            this.ok = ok;
            this.skipped = skipped;
        }

        static IngestResult skipped(int chunks) {
            return new IngestResult(chunks, 0, true, true);
        }

        static IngestResult ok(int chunks, int batches) {
            return new IngestResult(chunks, batches, true, false);
        }

        static IngestResult fail() {
            return new IngestResult(0, 0, false, false);
        }
    }

    private static final long PER_PDF_MIN_TIMEOUT_MILLIS = 120_000L;
    private static final long PER_PDF_OCR_MS_PER_PAGE = 15_000L;
    private static final long PER_PDF_BASE_MILLIS = 60_000L;

    /**
     * 把单个 PDF 做 提取→切片→Embedding→写入 VectorStore。
     * 已入库且文件字节没变的 PDF 直接跳过（probeStoredVersion 的 file_hash 快通道）。
     *
     * @param file      目标 file 文件
     * @param logPrefix 日志前缀（启动时用 "  "，热监听用 "  [热监听]"）
     */
    private IngestResult ingestOneFile(File file, String logPrefix) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".pdf")) {
            return ingestOnePdf(file, logPrefix);
        }
        return ingestOneLightweight(file, logPrefix);
    }

    /**
     * 非 PDF 文档的轻量入库：Markdown / Word / Excel。
     * 不需要 OCR，直接读文本 → 切片 → 写入。版本号解析、metadata 与 PDF 分支保持一致。
     */
    private IngestResult ingestOneLightweight(File file, String logPrefix) {
        String currentFileHash = hashFileQuietly(file);
        StoredVersion stored;
        try {
            stored = probeStoredVersion(file);
        } catch (RuntimeException ex) {
            log.warn(logPrefix + "⛔ " + file.getName() + " 无法确认库内已入库版本（" + ex.getMessage()
                    + "），本次不写入 —— 盲目追加会让同一文件出现两套 chunk_index");
            return IngestResult.fail();
        }
        if (stored != null && stored.sameFile(currentFileHash)) {
            log.warn(logPrefix + "⏭️  跳过：" + file.getName()
                    + "（文件字节未变化，已完整入库 " + stored.chunkCount + " chunks）");
            return IngestResult.skipped(stored.chunkCount);
        }

        log.info(logPrefix + "📥 开始处理：" + file.getName() + " (" + formatSize(file.length()) + ") ...");
        long startMs = System.currentTimeMillis();

        String fullText;
        try {
            fullText = extractLightweightText(file);
        } catch (Throwable t) {
            log.warn(logPrefix + "❌ 文本抽取失败：" + t.getMessage());
            return IngestResult.fail();
        }

        if (fullText == null || fullText.isBlank()) {
            log.warn(logPrefix + "    ↪ [内容QC] ⚠️ 抽取结果为空，跳过");
            return IngestResult.fail();
        }

        String cleaned = sanitizeExtractedText(fullText);
        int len = len(cleaned);
        long nonBlank = 0;
        if (cleaned != null) {
            for (int i = 0; i < cleaned.length(); i++) {
                if (!Character.isWhitespace(cleaned.charAt(i))) nonBlank++;
            }
        }
        if (nonBlank < 20) {
            log.warn(logPrefix + "    ↪ [内容QC] ⚠️ 有效字数仅 " + nonBlank + "，跳过");
            return IngestResult.fail();
        }

        String metaSource;
        try {
            metaSource = file.getCanonicalPath();
        } catch (IOException ignore) {
            metaSource = file.getAbsolutePath();
        }
        dev.langchain4j.data.document.Metadata meta = new dev.langchain4j.data.document.Metadata();
        meta.put("source", metaSource);
        meta.put("file_name", file.getName());
        meta.put("page_count", 0);
        meta.put("text_length", len);
        meta.put("non_blank_length", nonBlank);
        meta.put("file_hash", nullToEmpty(currentFileHash));
        meta.put("content_hash", nullToEmpty(sha256Hex(cleaned)));
        meta.put("extract_version", EXTRACT_PIPELINE_VERSION);
        meta.put("format", extensionOf(file));
        String[] verInfo = parseVersionFromFileName(file.getName());
        meta.put("version", verInfo[0]);
        meta.put("version_raw", verInfo[1]);
        meta.put("doc_type", parseDocTypeFromFileName(file.getName()));

        Document doc = Document.from(cleaned, meta);
        List<dev.langchain4j.data.segment.TextSegment> chunks = splitter.split(doc);
        if (chunks == null) chunks = Collections.emptyList();
        chunks = filterAndDeduplicateChunks(chunks, sourceKeys(file), logPrefix);

        int batches = 0;
        if (!chunks.isEmpty()) {
            try {
                String currentContentHash = nullToEmpty(
                        chunks.get(0).metadata().getString("content_hash"));
                if (stored != null && stored.sameContent(currentContentHash, chunks.size())) {
                    refreshStoredFileHash(file, currentFileHash);
                    log.warn(logPrefix + "⏭️  跳过：" + file.getName()
                            + "（文件有变动但抽取正文一致，" + chunks.size() + " chunks 无需重灌）");
                    return IngestResult.skipped(stored.chunkCount);
                }
                Map<String, StoredChunk> oldChunksByHash = stored != null
                        ? loadStoredChunksByHash(file) : Collections.emptyMap();
                if (stored != null) {
                    int removed = deleteStoredSourceRows(file);
                    if (removed <= 0) {
                        log.warn(logPrefix + "⛔ " + file.getName() + " 旧块清理未成功（删除 " + removed
                                + " 条），已中止重灌 —— 继续写入会让库里出现同一文件的两套 chunk_index");
                        return IngestResult.fail();
                    }
                    log.info(logPrefix + "🔄 重新入库：" + file.getName()
                            + "（正文与库内版本不同，已删除旧块 " + removed + " 条）");
                }
                batches = writeChunksInBatchesWithReuse(chunks, chunks.size(), oldChunksByHash, logPrefix);
                bm25IndexService.refreshSource(sourceKeys(file));
            } catch (Throwable t) {
                log.warn(logPrefix + "❌ 向量化写入失败：" + t.getMessage());
                return IngestResult.fail();
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        log.info(logPrefix + "✅ " + file.getName() + " → " + chunks.size() + " chunks, "
                + batches + " batches, " + elapsed + "ms");
        return IngestResult.ok(chunks.size(), batches);
    }

    private static String extensionOf(File f) {
        String name = f.getName().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }

    /**
     * 轻量格式文本抽取：Markdown / Word(docx) / Excel(xlsx/xls)。
     */
    private String extractLightweightText(File file) throws IOException {
        String ext = extensionOf(file);
        return switch (ext) {
            case "md", "markdown" -> extractMarkdown(file);
            case "docx" -> extractDocx(file);
            case "xlsx", "xls" -> extractExcel(file);
            default -> throw new IOException("不支持的格式：." + ext);
        };
    }

    private String extractMarkdown(File file) throws IOException {
        String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        try {
            com.vladsch.flexmark.parser.Parser parser = com.vladsch.flexmark.parser.Parser.builder().build();
            com.vladsch.flexmark.util.ast.Document flexDoc = parser.parse(raw);
            com.vladsch.flexmark.html.HtmlRenderer renderer = com.vladsch.flexmark.html.HtmlRenderer.builder().build();
            String html = renderer.render(flexDoc);
            return html.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ")
                    .replaceAll("\\s+", " ").trim();
        } catch (Throwable t) {
            return raw.replaceAll("[#*_`>\\-]", " ").replaceAll("\\s+", " ").trim();
        }
    }

    private String extractDocx(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(new FileInputStream(file))) {
            for (org.apache.poi.xwpf.usermodel.IBodyElement el : doc.getBodyElements()) {
                if (el instanceof org.apache.poi.xwpf.usermodel.XWPFParagraph p) {
                    sb.append(p.getText()).append("\n");
                } else if (el instanceof org.apache.poi.xwpf.usermodel.XWPFTable t) {
                    for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : t.getRows()) {
                        for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                            sb.append(cell.getText()).append(" | ");
                        }
                        sb.append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    private String extractExcel(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(file)) {
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(si);
                if (sheet == null) continue;
                sb.append("[").append(sheet.getSheetName()).append("]\n");
                for (org.apache.poi.ss.usermodel.Row row : sheet) {
                    boolean hasAny = false;
                    StringBuilder line = new StringBuilder();
                    for (org.apache.poi.ss.usermodel.Cell cell : row) {
                        String v = "";
                        if (cell != null) {
                            switch (cell.getCellType()) {
                                case STRING -> v = cell.getStringCellValue();
                                case NUMERIC -> v = String.valueOf(cell.getNumericCellValue());
                                case BOOLEAN -> v = String.valueOf(cell.getBooleanCellValue());
                                case FORMULA -> {
                                    try {
                                        v = cell.getStringCellValue();
                                    } catch (Throwable t2) {
                                        v = String.valueOf(cell.getNumericCellValue());
                                    }
                                }
                                default -> {
                                }
                            }
                        }
                        if (!v.isBlank()) {
                            hasAny = true;
                            line.append(v).append(" | ");
                        }
                    }
                    if (hasAny) sb.append(line).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private IngestResult ingestOnePdf(File pdf, String logPrefix) {
        String currentFileHash = hashFileQuietly(pdf);
        StoredVersion stored;
        try {
            stored = probeStoredVersion(pdf);
        } catch (RuntimeException ex) {
            log.warn(logPrefix + "⛔ " + pdf.getName() + " 无法确认库内已入库版本（" + ex.getMessage()
                    + "），本次不写入 —— 盲目追加会让同一文件出现两套 chunk_index");
            return IngestResult.fail();
        }
        if (stored != null && stored.sameFile(currentFileHash)) {
            log.warn(logPrefix + "⏭️  跳过：" + pdf.getName()
                    + "（文件字节未变化，已完整入库 " + stored.chunkCount + " chunks）");
            return IngestResult.skipped(stored.chunkCount);
        }

        log.info(logPrefix + "📥 开始处理：" + pdf.getName() + " (" + formatSize(pdf.length()) + ") ...");
        long startMs = System.currentTimeMillis();
        List<dev.langchain4j.data.segment.TextSegment> chunks = Collections.emptyList();

        long thisTimeoutMs = PER_PDF_MIN_TIMEOUT_MILLIS;
        int pageCount = 0;
        try (PDDocument probe = Loader.loadPDF(pdf)) {
            pageCount = probe.getNumberOfPages();
        } catch (Throwable ignore) {
        }
        if (pageCount > 0) {
            long ocrBased = PER_PDF_BASE_MILLIS + (long) pageCount * PER_PDF_OCR_MS_PER_PAGE;
            thisTimeoutMs = Math.max(PER_PDF_MIN_TIMEOUT_MILLIS, ocrBased);
        }
        final long finalTimeoutMs = thisTimeoutMs;
        final Set<Process> childProcesses = Collections.synchronizedSet(java.util.Collections.newSetFromMap(new IdentityHashMap<>()));

        try {
            final FutureTask<List<dev.langchain4j.data.segment.TextSegment>> ft = new FutureTask<>(() -> {
                List<dev.langchain4j.data.segment.TextSegment> result;
                ProcessRegistrar registrarLambda = proc -> {
                    if (proc != null) childProcesses.add(proc);
                };
                try (PdfProcessRegistry reg = new PdfProcessRegistry(registrarLambda)) {
                    result = readAndSplitOnePdf(pdf, reg);
                }
                return result;
            });
            Thread t = new Thread(ft, "rag-pdf-" + pdf.getName().replaceAll("[^A-Za-z0-9]", "_"));
            t.setDaemon(true);
            t.start();
            try {
                chunks = ft.get(finalTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException toe) {
                ft.cancel(true);
                t.interrupt();
                int killed = 0;
                synchronized (childProcesses) {
                    for (Process p : childProcesses) {
                        try {
                            p.descendants().forEach(ph -> {
                                try {
                                    ph.destroyForcibly();
                                } catch (Throwable ignore) {
                                }
                            });
                            p.destroyForcibly();
                            killed++;
                        } catch (Throwable ignore) {
                        }
                    }
                    childProcesses.clear();
                }
                long secs = finalTimeoutMs / 1000;
                throw new IOException("PDF 提取超时（>" + secs + " 秒，页数=" + pageCount + "，已强制杀掉 " + killed + " 个 OCR 子进程），已跳过。");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                ft.cancel(true);
                throw ie;
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                if (cause instanceof Exception e) throw e;
                throw new IOException(cause);
            }
            if (chunks == null) chunks = Collections.emptyList();
            chunks = filterAndDeduplicateChunks(chunks, sourceKeys(pdf), logPrefix);

            long tookSec = (System.currentTimeMillis() - startMs + 500) / 1000;
            if (chunks.isEmpty()) {
                log.info(logPrefix + "⚪ " + pdf.getName() + "（用时" + tookSec + "s）无有效文本内容，跳过");
                return IngestResult.ok(0, 0);
            }

            String currentContentHash = nullToEmpty(
                    chunks.get(0).metadata().getString("content_hash"));
            if (stored != null && stored.sameContent(currentContentHash, chunks.size())) {
                refreshStoredFileHash(pdf, currentFileHash);
                log.warn(logPrefix + "⏭️  跳过：" + pdf.getName()
                        + "（文件有变动但抽取正文一致，" + chunks.size() + " chunks 无需重灌）");
                return IngestResult.skipped(stored.chunkCount);
            }
            Map<String, StoredChunk> oldChunksByHash = stored != null
                    ? loadStoredChunksByHash(pdf) : Collections.emptyMap();
            if (stored != null) {
                int removed = deleteStoredSourceRows(pdf);
                if (removed <= 0) {
                    log.warn(logPrefix + "⛔ " + pdf.getName() + " 旧块清理未成功（删除 " + removed
                            + " 条），已中止重灌 —— 继续写入会让库里出现同一文件的两套 chunk_index");
                    return IngestResult.fail();
                }
                log.info(logPrefix + "🔄 重新入库：" + pdf.getName()
                        + "（正文与库内版本不同，已删除旧块 " + removed + " 条）");
            }

            int batchesForThisPdf = writeChunksInBatchesWithReuse(chunks, chunks.size(), oldChunksByHash, logPrefix);

            long totalChars = 0;
            int minChunk = Integer.MAX_VALUE, maxChunk = 0, emptyChunks = 0;
            for (dev.langchain4j.data.segment.TextSegment d : chunks) {
                // TextSegment 的访问器是 text()，不是 getText/getContent——旧反射找错名字，
                // 把每块长度都算成 0，日志误报「空块=全部」（2026-11-18 单文件重灌实测抓到）
                int l = segmentText(d).length();
                totalChars += l;
                if (l == 0) emptyChunks++;
                if (l < minChunk) minChunk = l;
                if (l > maxChunk) maxChunk = l;
            }
            String qcMsg = (emptyChunks == 0) ? "" : ("  ⚠️ 空块=" + emptyChunks);
            log.info(logPrefix + "✅ " + pdf.getName()
                    + "（" + (tookSec + (System.currentTimeMillis() - startMs) / 1000) + "s）"
                    + "  chunks=" + chunks.size()
                    + "  totalChars=" + totalChars
                    + "  chunkLen=[" + (chunks.isEmpty() ? 0 : minChunk) + "~" + maxChunk + "]"
                    + "  batches(≤10)=" + batchesForThisPdf
                    + qcMsg
                    + "  → 已写入 VectorStore");
            return IngestResult.ok(chunks.size(), batchesForThisPdf);
        } catch (Throwable th) {
            String msg = (th.getMessage() == null) ? "" : th.getMessage();
            String extraHint = "";
            String lower = msg.toLowerCase();
            if (lower.contains("encrypt") || lower.contains("password") || lower.contains("decrypt")
                    || (th.getClass().getSimpleName().toLowerCase().contains("encrypt"))
                    || (th.getClass().getSimpleName().toLowerCase().contains("password"))) {
                extraHint = " → 🔒 这是【加密 PDF】。解法：用 Edge 打开 → Ctrl+P → 打印为 PDF，输出无密码版本。";
            } else if (lower.contains("damaged") || lower.contains("corrupt") || lower.contains("truncated") || lower.contains("header")) {
                extraHint = " → ❌ PDF 文件损坏/被截断。重新下载或用 Chrome Ctrl+P 另存为 PDF 修复。";
            }
            log.warn(logPrefix + "❌ " + pdf.getName()
                    + " 处理失败（跳过）: "
                    + th.getClass().getSimpleName() + ": " + msg + extraHint);
            return IngestResult.fail();
        }
    }

    @PostConstruct
    public void init() {
        if (Objects.isNull(vectorStore)) {
            log.warn("ℹ️ [RagInitializer] 未检测到 VectorStore Bean，跳过 PDF 向量化初始化（@Tool 工具调用仍可用）");
            return;
        }

        List<File> files;
        try {
            files = collectSupportedFiles(pdfPath);
        } catch (Exception ex) {
            log.warn("[RagInitializer] 收集文档列表异常，全部跳过: " + ex.getMessage());
            startDirectoryWatcher();
            return;
        }
        if (files.isEmpty()) {
            log.warn("ℹ️ [RagInitializer] 路径 " + pdfPath + " 下没有任何支持的文档文件（pdf/md/docx/xlsx），跳过向量化初始化。");
            startDirectoryWatcher();
            return;
        }

        isOcrAvailable();
        log.info("");
        log.info("[RagInitializer] 共发现 " + files.size() + " 个文档，开始批量向量化 ...");
        log.info("[RagInitializer] Embedding 每批最多 " + DASHSCOPE_EMBEDDING_MAX_BATCH + " 个 chunks（DashScope 硬性限制）");

        int totalChunks = 0, totalBatches = 0, okCount = 0, failCount = 0, skippedCount = 0;
        for (File file : files) {
            IngestResult r = ingestOneFile(file, "  ");
            // 启动期重灌会换掉全部 embedding_id 与块内容，而启动对账只比 source 集合差集，
            // 文件名没变就零写入——不在这里按份刷，BM25 会留着指向已删旧块的死行（词面通道整条瞎掉）。
            // 热监听路径同款判据；非 PDF 路径内部已刷过一次，这里重复刷只是幂等重写，无副作用。
            if (r.ok && !r.skipped && r.chunks > 0) refreshKeywordIndexForSource(file, "启动入库 " + file.getName());
            totalChunks += r.chunks;
            totalBatches += r.batches;
            if (r.skipped) skippedCount++;
            if (r.ok) okCount++;
            else failCount++;
        }
        lastBootIngest = Map.of(
                "scanned_file_count", files.size(),
                "skipped_unchanged", skippedCount,
                "newly_ingested_or_rewritten", okCount - skippedCount,
                "failed", failCount,
                "chunks_seen", totalChunks,
                "embedding_batches", totalBatches,
                "extract_pipeline_version", EXTRACT_PIPELINE_VERSION);

        log.info("[RagInitializer] 完成。成功PDF=" + okCount
                + "  其中判重跳过=" + skippedCount
                + "  失败PDF=" + failCount
                + "  chunks总数=" + totalChunks
                + "  提交批次数=" + totalBatches
                + " (每批≤" + DASHSCOPE_EMBEDDING_MAX_BATCH + ")");
        // 启动期增量对账：只补建缺失的 source，库未变时零写入，不再 TRUNCATE 全表。
        // 历史遗留缺口由 reconcileAll 内部的 source 集合差集自动补齐。
        reconcileKeywordIndex();
        log.info("");

        startDirectoryWatcher();
    }

    /**
     * 读单个 PDF + 切分。
     * <p>
     * 为应对「扫描件 + 隐藏 OCR 文字层」PDF，采用多层 fallback 提取流程。
     * 打开 PDF 时同时绕过常见的所有者权限限制，以便从加密但内容可见的 PDF 中抽取文字。
     */
    // 旧签名：给诊断/测试代码用（不传 registrar = 不注册进程）
    private List<dev.langchain4j.data.segment.TextSegment> readAndSplitOnePdf(File pdf) throws Exception {
        return readAndSplitOnePdf(pdf, NOOP_REGISTRAR);
    }

    private List<dev.langchain4j.data.segment.TextSegment> readAndSplitOnePdf(File pdf, ProcessRegistrar registrar) throws Exception {
        if (registrar == null) registrar = NOOP_REGISTRAR;
        String password = null;
        try (PDDocument pdDoc = openPdfLenient(pdf, password)) {
            int pageCount = pdDoc.getNumberOfPages();
            boolean encrypted = pdDoc.isEncrypted();

            // ① 主路 P1：PDFTextStripper。它走 ToUnicode CMap，能正确解码 CID/Identity-H 字体；
            //    暴力扒 COS 字面量拿到的是未解码的 CID（ࡺ ᓯ 㵬），不能用来判断 PDF 类型。
            int[] stats = new int[5];
            StringBuilder firstHitDump = new StringBuilder(300);
            String fullText = new PDFTextStripper().getText(pdDoc);
            int len = len(fullText);
            int finalNonBlank = countNonBlank(fullText);
            String chosenPath = "P1 PDFTextStripper";

            // ② P2：P1 有效字数不足时，换一组排版参数再试一次
            if (finalNonBlank < MIN_VALID_NONBLANK) {
                PDFTextStripper s2 = new PDFTextStripper();
                s2.setSortByPosition(false);
                s2.setAddMoreFormatting(false);
                s2.setShouldSeparateByBeads(false);
                s2.setLineSeparator("\n");
                String t2 = s2.getText(pdDoc);
                if (usableScore(t2) > usableScore(fullText)) {
                    fullText = t2;
                    len = len(t2);
                    finalNonBlank = countNonBlank(t2);
                    chosenPath = "P2 PDFTextStripper(无排版)";
                }
            }

            // ③ P3：文字层拿到的是乱码或几乎没字，才降级到暴力扒全部 COS 流
            if (usableScore(fullText) < MIN_VALID_NONBLANK) {
                String brute = bruteExtractStringsFromAllStreams(pdDoc, stats, firstHitDump);
                if (usableScore(brute) > usableScore(fullText)) {
                    fullText = brute;
                    len = len(brute);
                    finalNonBlank = countNonBlank(brute);
                    chosenPath = "P3 暴力COS流";
                }
            }

            // ④ P4：OCR 终极兜底——前三条都不合格才跑；且只有 OCR 真的更好才采用
            boolean ocrNeeded = usableScore(fullText) < MIN_VALID_NONBLANK
                    || (pageCount >= 10 && (double) finalNonBlank / pageCount < 100.0)
                    || weirdRate(fullText) > WEIRD_RATE_MAX;
            if (ocrNeeded && isOcrAvailable()) {
                String ocr = ocrExtractFromPdf(pdDoc, registrar);
                int nbOcr = countNonBlank(ocr);
                if (usableScore(ocr) > usableScore(fullText) && nbOcr >= 20) {
                    fullText = ocr;
                    len = len(ocr);
                    finalNonBlank = nbOcr;
                    chosenPath = "P4 Tesseract OCR";
                } else {
                    log.info("    ↪ [OCR] 识别结果不如现有文本（OCR 非空白=" + nbOcr
                            + "，现有=" + finalNonBlank + "，现有乱码率="
                            + String.format("%.1f%%", weirdRate(fullText) * 100) + "），保留 " + chosenPath + " 文本");
                }
            }

            double finalWeirdRate = weirdRate(fullText);
            if (finalWeirdRate > WEIRD_RATE_MAX) {
                log.warn("    ↪ [内容QC] ⚠️ " + chosenPath + " 抽取文本乱码率="
                        + String.format("%.1f%%", finalWeirdRate * 100) + "（阈值 "
                        + String.format("%.0f%%", WEIRD_RATE_MAX * 100) + "），入库内容可能不可用："
                        + previewOf(fullText));
            } else if (finalNonBlank < MIN_VALID_NONBLANK) {
                log.warn("    ↪ [内容QC] ⚠️ " + chosenPath + " 有效字数仅 "
                        + finalNonBlank + "，该文档将被跳过");
            }

            log.info("    ↪ [抽取] " + chosenPath + "  总长=" + len
                    + " 非空白=" + finalNonBlank + " 加密=" + encrypted
                    + " 乱码率=" + String.format("%.1f%%", finalWeirdRate * 100));

            List<dev.langchain4j.data.segment.TextSegment> chunks = Collections.emptyList();

            if (finalNonBlank >= 20) {
                // 抽取路径的选优靠原始文本打分，正文落库前先清洗（控制字符/私用区字形/水印串/连字符码点）
                String cleaned = sanitizeExtractedText(fullText);
                String metaSource;
                try {
                    metaSource = pdf.getCanonicalPath();
                } catch (IOException ignore) {
                    metaSource = pdf.getAbsolutePath();
                }
                dev.langchain4j.data.document.Metadata meta = new dev.langchain4j.data.document.Metadata();
                meta.put("source", metaSource);
                meta.put("file_name", pdf.getName());
                meta.put("page_count", pageCount);
                meta.put("text_length", len);
                meta.put("non_blank_length", finalNonBlank);
                meta.put("file_hash", nullToEmpty(hashFileQuietly(pdf)));
                meta.put("content_hash", nullToEmpty(sha256Hex(cleaned)));
                meta.put("extract_version", EXTRACT_PIPELINE_VERSION);
                String[] verInfo = parseVersionFromFileName(pdf.getName());
                meta.put("version", verInfo[0]);
                meta.put("version_raw", verInfo[1]);
                meta.put("doc_type", parseDocTypeFromFileName(pdf.getName()));
                Document doc = Document.from(cleaned == null ? "" : cleaned, meta);
                List<dev.langchain4j.data.segment.TextSegment> c = splitter.split(doc);
                if (c != null) chunks = c;
            }

            return chunks;
        }
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     * 从文件名解析指南版本号。返回 [标准化版本号, 原始匹配串]。
     * 规则：先匹配连续 4 位年份（2023 / 2024），其次匹配 "2023年" / "2023版" / "v2.0" 这类。
     * 匹配不到时返回 ["unknown", ""]，不会抛异常。
     */
    static String[] parseVersionFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return new String[]{"unknown", ""};
        }
        String name = fileName.replaceAll("\\.[^.]+$", "");
        Matcher year4 = Pattern.compile("(19|20)\\d{2}").matcher(name);
        if (year4.find()) {
            return new String[]{year4.group(), year4.group()};
        }
        Matcher yearCn = Pattern.compile("(19|20)\\d{2}\\s*[年版]").matcher(name);
        if (yearCn.find()) {
            String raw = yearCn.group();
            return new String[]{raw.replaceAll("[^0-9]", ""), raw};
        }
        Matcher vNum = Pattern.compile("[vV]\\s*(\\d+(?:\\.\\d+)*)").matcher(name);
        if (vNum.find()) {
            return new String[]{vNum.group(1), vNum.group()};
        }
        return new String[]{"unknown", ""};
    }

    /**
     * 按文件名关键词分文档类型，闭集 {指南, 共识, 手册, 报告, 其他}，优先级按声明顺序。
     * 与章节号同纪律：认不准就落「其他」，不猜。doc_year 不另建字段——version 就是按文件名解析的年份。
     */
    public static String parseDocTypeFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "其他";
        String name = fileName.replaceAll("\\.[^.]+$", "");
        if (name.contains("指南")) return "指南";
        if (name.contains("共识")) return "共识";
        if (name.contains("手册")) return "手册";
        if (name.contains("报告")) return "报告";
        return "其他";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 拼接两段 JDBC 参数：查重 SQL 里前 N 个 ? 是 chunk_hash 列表，后 M 个 ? 是自身 source 列表。
     */
    private static Object[] concatArgs(Object[] head, String[] tail) {
        Object[] all = new Object[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    /**
     * 正文指纹：抽取文本变了吗。文字层修复、换抽取路径都会让它变化，从而触发重灌。
     */
    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
            return toHex(d.digest((s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 文件指纹：PDF 字节变了吗。命中即可完全跳过抽取，避免每次启动重跑 OCR。
     */
    private static String hashFileQuietly(File f) {
        try (InputStream in = new FileInputStream(f)) {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) d.update(buf, 0, r);
            return toHex(d.digest());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * 有效字数下限：低于此值认为该路径没抽到正文
     */
    private static final int MIN_VALID_NONBLANK = 200;
    /**
     * 非空白字符中「文档常见字符之外」的比例上限，超过即判定为乱码（如 CID 字体暴力抽取出的 ࡺᓯ㵬）
     */
    private static final double WEIRD_RATE_MAX = 0.15;

    /**
     * 语言无关的乱码判据：统计非空白字符里不属于「中日韩文字 / 拉丁与希腊字母数字 / 文档常用标点」的比例。
     * 用中文占比判乱码会误杀英文原版文档，故改为按 Unicode 区块白名单判定。
     */
    private static double weirdRate(String s) {
        if (s == null || s.isEmpty()) return 1.0;
        int weird = 0, nonBlank = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ') continue;
            nonBlank++;
            if (!isExpectedDocChar(c)) weird++;
        }
        return nonBlank == 0 ? 1.0 : ((double) weird / nonBlank);
    }

    private static boolean isExpectedDocChar(char c) {
        if (c >= 0x20 && c <= 0x7E) return true;          // ASCII 可见字符
        if (c >= 0xA0 && c <= 0xFF) return true;          // Latin-1（é ü ± °）
        if (c >= 0x100 && c <= 0x17F) return true;        // Latin 扩展
        if (c >= 0x370 && c <= 0x3FF) return true;        // 希腊字母（α β ω-3 脂肪酸）
        if (c >= 0x400 && c <= 0x4FF) return true;        // 西里尔字母
        if (c >= 0x2000 && c <= 0x206F) return true;      // 通用标点（引号、破折号、省略号）
        if (c >= 0x2070 && c <= 0x20CF) return true;      // 上下标、箭头
        if (c >= 0x2100 && c <= 0x218F) return true;      // 兼容符号、罗马数字
        if (c >= 0x2200 && c <= 0x22FF) return true;      // 数学运算符（≈ ≤ ≥）
        if (c >= 0x2460 && c <= 0x24FF) return true;      // 带圈数字（①②③）
        if (c >= 0x2500 && c <= 0x27BF) return true;      // 表格线 / 几何符号（△▲◆□）/ 杂项符号
        if (c >= 0x2E80 && c <= 0x2EFF) return true;      // CJK 部首补充
        if (c >= 0x3000 && c <= 0x30FF) return true;      // CJK 标点 + 假名
        if (c >= 0x3100 && c <= 0x312F) return true;      // 注音符号
        if (c >= 0x4E00 && c <= 0x9FFF) return true;      // CJK 统一汉字
        if (c >= 0xF900 && c <= 0xFAFF) return true;      // CJK 兼容汉字
        if (c >= 0xD800 && c <= 0xDFFF) return true;      // 代理对半个字符（CJK 扩展 B 区生僻姓名用字）
        if (c >= 0xFF00 && c <= 0xFFEF) return true;      // 全角字符
        return c == 0x2022 || c == 0x00B7;                // 项目符号、间隔号
    }

    /**
     * 候选文本可用性打分：乱码超标的按三折计，保证干净文本必然胜出
     */
    private static long usableScore(String s) {
        int nb = countNonBlank(s);
        return weirdRate(s) > WEIRD_RATE_MAX ? nb / 3 : nb;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format(Locale.ROOT, "%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /**
     * 去掉 \r\n\t 空格及零宽/不间断空白后计数
     */
    private static int countNonBlank(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '\u00A0' || c == '\u200B' || c == '\uFEFF') continue;
            n++;
        }
        return n;
    }

    /**
     * 正文清洗。每类命中数都以只读审计为准（scratch/charaudit.txt；抽取口径与生产一致——四份 PDF 全部走 P1，
     * 见 scratch/path2_final.txt）。写着「本库 0 个」的规则是兜底，防以后进来的 PDF，别按它们评估收益。
     * ① 控制字符 U+0000–U+001F（保留 \t\n\r）：血脂管理指南 750 个、基层高血压 3 个，另两份 0；
     * U+007F–U+009F 本库 0 个。这些字符夹在正文里（实例见 scratch/fhpeek.txt 清洗前块头），
     * 成因未考证，这里只确认「存在于正文且不是可见字符」。
     * ② 零宽/连接符 U+200B–U+200F、U+2060、U+FEFF：本库 0 个，兜底。
     * ③ 私用区字形：补充私用区 U+F0000 以上血脂管理指南 13 个，其中 U+100C94 􀲔 占 10 次，10 次全部紧跟在
     * LDL/VLDL/HDL⁃C 或 ApoB 之后，位置就是表「对脂蛋白的影响」里该画箭头的那一列（scratch/rawpeek2.txt）；
     * 至于它到底代表升还是降，文本里读不出来，所以只做删除不做替换。BMP 私用区 U+E000–U+F8FF 本库 0 个，兜底。
     * ④ 水印串 fmx_…：血脂管理指南 35 次，全库普查只出现 fmx_T3RoZXJNaXJyb3Jz 这一种写法（scratch/census.txt）。
     * ⑤ 连字符码点归一：U+2010/U+2011/U+2043/U+2212 → '-'，软连字符 U+00AD 删除，全库 2374 处
     * （U+2011 953、U+2010 913、U+2043 508；U+2212 与 U+00AD 本库 0 个，兜底）。
     * 「特殊码点不进分词器」是实测：scratch/p0_hyphen2.txt 里 df("ldl-c")=6，而 ldl‐c / ldl‑c / ldl⁃c / ldl−c 四种写法 df 全为 0。
     * 同一份指南把 LDL-C 排成多种码点（一级预防 U+2010×104 / U+2011×7，血脂 U+2043×226 / U+2010×6），
     * 归一后可逐字定位到 LDL-C 的块数：一级预防 1→38、血脂 0→86、基层 5→5（scratch/chunkdiff2.txt）。
     * metadata 里的 text_length / non_blank_length 仍是清洗前的数字——代码顺序是先按原始文本打分选路、再清洗落库，
     * 那两个字段是判断抽取路径合不合格的质量指标，不是正文长度，所以重灌后拿它们和块正文长度对账会看到差值。
     */
    public static String sanitizeExtractedText(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 && cp != '\t' && cp != '\n' && cp != '\r') continue;
            if (cp >= 0x7F && cp <= 0x9F) continue;
            if (cp == 0x00AD) continue;
            if (cp >= 0x200B && cp <= 0x200F) continue;
            if (cp == 0x2060 || cp == 0xFEFF) continue;
            if (cp >= 0xE000 && cp <= 0xF8FF) continue;
            if (cp >= 0xF0000) continue;
            if (cp == 0x2010 || cp == 0x2011 || cp == 0x2043 || cp == 0x2212) {
                sb.append('-');
                continue;
            }
            sb.appendCodePoint(cp);
        }
        // 水印按整串摘除，所以放在逐字符过滤之后；替换成一个空格而不是空串，避免相邻词被粘在一起
        return WATERMARK_RUN.matcher(sb).replaceAll(" ");
    }

    private static final java.util.regex.Pattern WATERMARK_RUN =
            java.util.regex.Pattern.compile("fmx_[A-Za-z0-9]{12,}");

    // 抽取流水线版本：改动抽取/清洗逻辑时必须 bump（如换分词前的文本规范化、修 PDF 选路）。
    // 它不参与 content_hash，只用来决定"文件字节没变能不能跳过抽取"——版本一变就强制重新抽取，
    // 否则修好选路 + 判重之后，旧库会因为 file_hash 相同而永远不重灌。
    private static final String EXTRACT_PIPELINE_VERSION = "2026-11-18a";

    // 增量入库：该 PDF 在库里的当前版本（块数 + 两级指纹 + 抽取版本）
    private static final class StoredVersion {
        final int chunkCount;
        final String fileHash;
        final String contentHash;
        final String extractVersion;
        final int totalChunks;

        StoredVersion(int chunkCount, String fileHash, String contentHash, String extractVersion, int totalChunks) {
            this.chunkCount = chunkCount;
            this.fileHash = fileHash == null ? "" : fileHash;
            this.contentHash = contentHash == null ? "" : contentHash;
            this.extractVersion = extractVersion == null ? "" : extractVersion;
            this.totalChunks = totalChunks;
        }

        /**
         * 是否完整入库过：chunk_index 应该铺满 0..total_chunks-1，缺块说明上次写库中断了
         */
        boolean complete() {
            return chunkCount > 0 && chunkCount == totalChunks;
        }

        boolean sameFile(String currentFileHash) {
            return complete()
                    && !currentFileHash.isEmpty() && currentFileHash.equals(fileHash)
                    && EXTRACT_PIPELINE_VERSION.equals(extractVersion);
        }

        boolean sameContent(String currentContentHash, int expectedChunks) {
            return !currentContentHash.isEmpty() && currentContentHash.equals(contentHash)
                    && chunkCount == expectedChunks;
        }

        boolean versionUpToDate() {
            return EXTRACT_PIPELINE_VERSION.equals(extractVersion);
        }
    }

    /**
     * 库内旧块的快照：embedding 主键 + 向量。
     * <p>
     * 块级增量更新时，先按 source 把旧块全部捞出来，按 chunk_hash 建索引。
     * 新切片的块若 hash 命中，就直接复用旧 embedding——不调模型、不算向量，只换 metadata 写回。
     */
    static final class StoredChunk {
        final String embeddingId;
        final float[] vector;

        StoredChunk(String embeddingId, float[] vector) {
            this.embeddingId = embeddingId;
            this.vector = vector;
        }
    }

    /**
     * 兼容历史记录的 source 写法：canonicalPath 与 absolutePath 都视为同一份文件（与写入侧 getCanonicalPath 对齐）。
     */
    private static String[] sourceKeys(File pdf) {
        String canonical = null;
        try {
            canonical = pdf.getCanonicalPath();
        } catch (IOException ignore) {
        }
        String absolute = pdf.getAbsolutePath();
        return (canonical == null || canonical.equals(absolute))
                ? new String[]{absolute} : new String[]{canonical, absolute};
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ", ?");
        return sb.append(")").toString();
    }

    private StoredVersion probeStoredVersion(File pdf) {
        if (this.pgJdbc == null || pdf == null) return null;
        String[] keys = sourceKeys(pdf);
        String sql = "SELECT COUNT(*), MAX(metadata->>'file_hash'), MAX(metadata->>'content_hash'), "
                + "MAX(metadata->>'extract_version'), MAX((metadata->>'total_chunks')::int) "
                + "FROM " + vectorStoreTable() + " WHERE metadata->>'source' IN " + placeholders(keys.length);
        try {
            return this.pgJdbc.query(sql, rs -> {
                if (!rs.next() || rs.getInt(1) == 0) return null;
                return new StoredVersion(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5));
            }, (Object[]) keys);
        } catch (Exception ex) {
            // 探测不出来就不能判定"库里没有"，交由调用方中止，避免同一 source 出现两套 chunk_index
            throw new IllegalStateException("读取库内版本失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 按 source 捞出库内旧块的 (chunk_hash → StoredChunk) 映射。
     * <p>
     * 块级增量更新的前提：在删旧行之前把旧块的 embedding 主键与向量读出来，
     * 新切片命中同 hash 时直接复用，省掉一次 embedding 调用。
     * 读失败退化为空 map（全量重算），不影响正确性。
     */
    private Map<String, StoredChunk> loadStoredChunksByHash(File file) {
        if (this.pgJdbc == null || file == null) return Collections.emptyMap();
        String[] keys = sourceKeys(file);
        String sql = "SELECT metadata->>'chunk_hash', embedding_id::text, embedding::text "
                + "FROM " + vectorStoreTable()
                + " WHERE metadata->>'source' IN " + placeholders(keys.length);
        try {
            return this.pgJdbc.query(sql, rs -> {
                Map<String, StoredChunk> map = new HashMap<>();
                while (rs.next()) {
                    String hash = rs.getString(1);
                    if (hash == null || hash.isEmpty()) continue;
                    String id = rs.getString(2);
                    float[] vec = parseVector(rs.getString(3));
                    if (vec != null) map.put(hash, new StoredChunk(id, vec));
                }
                return map;
            }, (Object[]) keys);
        } catch (Exception ex) {
            log.warn("    ⚠️ 读取旧块 embedding 失败（退化为全量重算）：" + ex.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 解析 PG vector 的文本表示（形如 [0.1,0.2,0.3]）为 float[]。
     */
    private static float[] parseVector(String text) {
        if (text == null || text.length() < 2) return null;
        try {
            String inner = text.substring(1, text.length() - 1);
            if (inner.isEmpty()) return new float[0];
            String[] parts = inner.split(",");
            float[] vec = new float[parts.length];
            for (int i = 0; i < parts.length; i++) vec[i] = Float.parseFloat(parts[i].trim());
            return vec;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 拼接进 SQL 的表名只允许标识符字符，非法配置退回默认表。
     */
    private String vectorStoreTable() {
        String s = (pgSchema == null || pgSchema.isBlank() ? "public" : pgSchema)
                + "." + (pgTable == null || pgTable.isBlank() ? "vector_store" : pgTable);
        return s.matches("[A-Za-z0-9_.]+") ? s : "public.vector_store";
    }

    /**
     * 文件字节或抽取版本变了、但抽出来的正文没变：回写 file_hash + extract_version，下次启动就能走零抽取的快速通道。
     */
    private void refreshStoredFileHash(File pdf, String newFileHash) {
        if (this.pgJdbc == null || newFileHash.isEmpty()) return;
        String[] keys = sourceKeys(pdf);
        Object[] args = new Object[keys.length + 2];
        args[0] = newFileHash;
        args[1] = EXTRACT_PIPELINE_VERSION;
        System.arraycopy(keys, 0, args, 2, keys.length);
        try {
            this.pgJdbc.update("UPDATE " + vectorStoreTable() + " "
                    + "SET metadata = metadata::jsonb || jsonb_build_object('file_hash', ?::text, 'extract_version', ?::text) "
                    + "WHERE metadata->>'source' IN " + placeholders(keys.length), args);
        } catch (Exception ex) {
            log.warn("    ⚠️ 回写 file_hash 失败（不影响正确性，下次启动会重新抽取比对）：" + ex.getMessage());
        }
    }

    /**
     * 重灌前先删掉同一份文件的旧块，保证库里每个 source 只有一套连续的 chunk_index。
     */
    public int deleteStoredSourceRows(File pdf) {
        if (this.pgJdbc == null) return 0;
        String[] keys = sourceKeys(pdf);
        try {
            return this.pgJdbc.update("DELETE FROM " + vectorStoreTable()
                    + " WHERE metadata->>'source' IN " + placeholders(keys.length), (Object[]) keys);
        } catch (Exception ex) {
            log.warn("    ⚠️ 清理旧块异常：" + ex.getMessage());
            return -1;
        }
    }

    /**
     * 打开 PDF（PDFBox 3.x Loader API）。
     * 内存/磁盘缓存策略由 static{} 块统一配置。对加密文档尝试放开权限位，便于抽取可见文字。
     * <p>
     * 包级可见（不是 private）是给 {@link PdfPageLocator} 复用的：页码定位必须走同一个开文件的路子，
     * 否则加密文档在入库时抽得出、定位时抽不出，页码就会莫名其妙对不上。
     */
    static PDDocument openPdfLenient(File pdf, String password) throws IOException {
        PDDocument doc = (password != null && !password.isEmpty())
                ? Loader.loadPDF(pdf, password)
                : Loader.loadPDF(pdf);
        if (doc.isEncrypted()) {
            try {
                Class<?> c = doc.getClass();
                for (String name : Arrays.asList(
                        "setAllowsExtractionText", "setAllowsExtractContent",
                        "setAllowsModifyAnnotations", "setAllowsModify",
                        "setAllowsAssembleDocument", "setAllowsPrinting",
                        "setAllowsFillInForm", "setAllowsScreenReaders",
                        "setExtractContent", "setExtractForAccessibility",
                        "setModifyAnnotations", "setModifyContents",
                        "setAssemblyDocument", "setCanPrint",
                        "setCanModify", "setCanExtractContent",
                        "setCanExtractForAccessibility", "setCanFillInForm",
                        "setCanModifyAnnotations", "setCanAssembleDocument")) {
                    try {
                        java.lang.reflect.Method m = c.getMethod(name, boolean.class);
                        m.invoke(doc, true);
                    } catch (Throwable ignore) {
                    }
                }
            } catch (Throwable ignore) {
            }
        }
        return doc;
    }

    /**
     * 暴力兜底：递归 walk 整份 PDF 所有 COS 对象，不放过任何一个 COSStream。
     * 入口覆盖全部 Page COS + 页面 Annotation（外观流），以及 Document Catalog 整棵 COS 树。
     * 对所有 COS 对象去重，避免页树 /Parent 与 /Kids 成环导致的指数级递归。
     */
    private static String bruteExtractStringsFromAllStreams(PDDocument pdDoc, int[] stats, StringBuilder firstHitDump) {
        StringBuilder sb = new StringBuilder(65536);
        Pattern literalPat = Pattern.compile("\\(([^\\)]{2,2000})\\)");
        Pattern hexPat = Pattern.compile("<([0-9A-Fa-f]{4,65536})>");
        IdentityHashMap<COSBase, Boolean> visited = new IdentityHashMap<>(4096);
        if (stats == null) stats = new int[4];

        try {
            PDPageTree pages = pdDoc.getPages();
            for (org.apache.pdfbox.pdmodel.PDPage page : pages) {
                if (page == null) continue;
                walkCOS(page.getCOSObject(), literalPat, hexPat, sb, visited, stats, firstHitDump);
                try {
                    java.util.List<PDAnnotation> anns = page.getAnnotations();
                    if (anns != null) {
                        for (PDAnnotation ann : anns) {
                            try {
                                walkCOS(ann.getCOSObject(), literalPat, hexPat, sb, visited, stats, firstHitDump);
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                } catch (Throwable ignore) {
                }
            }
            try {
                walkCOS(pdDoc.getDocumentCatalog().getCOSObject(), literalPat, hexPat, sb, visited, stats, firstHitDump);
            } catch (Throwable ignore) {
            }
        } catch (Throwable ignore) {
        }
        return sb.toString().trim();
    }

    /**
     * 单行预览，用于控制台告警时展示被判定为乱码的文本长什么样。
     */
    private static String previewOf(String s) {
        String p = (s == null ? "" : s).replaceAll("[\\r\\n\\t ]+", " ").trim();
        return "「" + (p.length() > 150 ? p.substring(0, 150) + "…" : p) + "」";
    }

    /**
     * 递归 walk COS 对象树，提取可读文字并统计；stats[0]=totalStreams stats[1]=Tj/TJ hits stats[2]=literalOk stats[3]=hexOk
     */
    private static void walkCOS(COSBase base, Pattern literalPat, Pattern hexPat,
                                StringBuilder sb, IdentityHashMap<COSBase, Boolean> visited,
                                int[] stats, StringBuilder firstHitDump) {
        if (base == null) return;
        // 解包 COSObject 间接引用包装器
        if (base instanceof org.apache.pdfbox.cos.COSObject co) {
            base = co.getObject();
            if (base == null) return;
        }
        // 对所有 COS 对象去重，防止页树引用成环导致的指数级递归
        if (visited.putIfAbsent(base, Boolean.TRUE) != null) return;

        if (base instanceof COSStream s) {
            stats[0]++;

            // 极速剪枝①：纯图片 Filter 的流（无 Tj/TJ 文本指令），直接跳过
            COSBase filterObj = null;
            try {
                filterObj = s.getDictionaryObject(COSName.FILTER);
            } catch (Throwable ignore) {
            }
            if (filterObj != null && isPureImageFilter(filterObj)) return;

            // 极速剪枝②：嵌入字体流，直接跳过
            try {
                COSBase subtype = s.getDictionaryObject(COSName.SUBTYPE);
                if (subtype instanceof COSName sn) {
                    String n = sn.getName();
                    if ("Type1".equals(n) || "TrueType".equals(n) || "CIDFontType0".equals(n)
                            || "CIDFontType2".equals(n) || "CIDFontType2C".equals(n) || "MMType1".equals(n)) {
                        return;
                    }
                }
                if (s.getItem(COSName.getPDFName("FontFile")) != null
                        || s.getItem(COSName.getPDFName("FontFile2")) != null
                        || s.getItem(COSName.getPDFName("FontFile3")) != null) {
                    return;
                }
            } catch (Throwable ignore) {
            }

            try (InputStream in = s.createInputStream()) {
                if (in == null) return;
                byte[] bytes = in.readAllBytes();
                if (bytes == null || bytes.length < 4) return;
                // 极速剪枝③：>256KB 的流 99.9% 是图片/字体/ICC 二进制，跳过
                if (bytes.length > 256 * 1024) return;
                String content = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (content.indexOf("Tj") < 0 && content.indexOf("TJ") < 0) return;
                stats[1]++;

                if (firstHitDump != null && firstHitDump.length() == 0) {
                    String dump = content.replaceAll("[\\r\\n\\t]+", " ");
                    firstHitDump.append(dump.length() > 500 ? dump.substring(0, 500) : dump);
                }

                Matcher mp = literalPat.matcher(content);
                while (mp.find()) {
                    String raw = mp.group(1);
                    String str = unescapePdfLiteral(raw);
                    if (looksReadable(str)) {
                        sb.append(str).append(' ');
                        stats[2]++;
                    }
                }
                Matcher mh = hexPat.matcher(content);
                while (mh.find()) {
                    String hex = mh.group(1);
                    String decoded = decodeHexSmart(hex);
                    if (looksReadable(decoded)) {
                        sb.append(decoded).append(' ');
                        stats[3]++;
                    }
                }
            } catch (Throwable ignore) {
            }
        } else if (base instanceof COSDictionary dict) {
            Set<Map.Entry<COSName, COSBase>> entries;
            try {
                entries = dict.entrySet();
            } catch (Throwable ignore) {
                return;
            }
            for (Map.Entry<COSName, COSBase> e : entries) {
                try {
                    walkCOS(e.getValue(), literalPat, hexPat, sb, visited, stats, firstHitDump);
                } catch (Throwable ignore) {
                }
            }
        } else if (base instanceof COSArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                try {
                    walkCOS(arr.get(i), literalPat, hexPat, sb, visited, stats, firstHitDump);
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * PDF 括号字面量转义还原：\( \) \\ \n \r \t \b \f 及八进制
     */
    private static String unescapePdfLiteral(String raw) {
        if (raw == null || raw.indexOf('\\') < 0) return raw;
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i + 1 >= raw.length()) break;
            char n = raw.charAt(++i);
            switch (n) {
                case '(':
                case ')':
                case '\\':
                    out.append(n);
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                default:
                    // 八进制：\ddd / \dd / \d
                    if (n >= '0' && n <= '7') {
                        int v = n - '0';
                        for (int k = 0; k < 2 && i + 1 < raw.length(); k++) {
                            char c3 = raw.charAt(i + 1);
                            if (c3 < '0' || c3 > '7') break;
                            v = v * 8 + (c3 - '0');
                            i++;
                        }
                        out.append((char) (v & 0xFF));
                    } else out.append(n);
            }
        }
        return out.toString();
    }

    /**
     * 十六进制智能解码：按 "UTF-16BE → GBK → UTF-8" 顺序尝试，返回最像可读文字的
     */
    private static String decodeHexSmart(String hex) {
        if (hex == null || (hex.length() & 1) != 0) return "";
        byte[] bytes;
        try {
            bytes = hexToBytes(hex);
        } catch (Throwable ignore) {
            return "";
        }
        if (bytes == null || bytes.length == 0) return "";

        String utf16 = "", gbk = "", utf8 = "";
        try {
            utf16 = new String(bytes, java.nio.charset.StandardCharsets.UTF_16BE);
        } catch (Throwable ignore) {
        }
        try {
            gbk = new String(bytes, java.nio.charset.Charset.forName("GBK"));
        } catch (Throwable ignore) {
        }
        try {
            utf8 = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignore) {
        }

        String[] candidates = {utf16, gbk, utf8};
        String best = "";
        int bestScore = -1;
        for (String c : candidates) {
            if (c == null || c.isEmpty()) continue;
            int score = 0;
            for (int k = 0; k < c.length(); k++) {
                char ch = c.charAt(k);
                if (ch >= 0x4E00 && ch <= 0x9FFF) score += 3;
                else if (Character.isLetterOrDigit(ch)) score += 1;
                else if (" ，。、；：？！,.?!;:\n\t-".indexOf(ch) >= 0) score += 0;
                else score -= 2;
            }
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * 判断字符串是否"看起来像人读的文字"：CJK 汉字优先，怪异/控制字符超标则拒
     */
    private static boolean looksReadable(String s) {
        if (s == null || s.isEmpty()) return false;
        int cjk = 0, lettersOrDigit = 0, weird = 0, total = s.length();
        for (char c : s.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
                lettersOrDigit++;
            } else if (Character.isLetterOrDigit(c)) lettersOrDigit++;
            else if (c < 0x20 && "\n\r\t\b\f".indexOf(c) < 0) weird++;
            else if (c == 0x7F) weird++;
        }
        if (cjk >= 2) return true;
        if (weird * 10 > total) return false;
        return lettersOrDigit * 5 >= total * 2;
    }

    /**
     * 判断 COSStream 的 /Filter 是否属于纯图像压缩类（此类流不含文本指令，直接跳过）。
     */
    private static boolean isPureImageFilter(COSBase filter) {
        if (filter == null) return false;
        if (filter instanceof COSName name) {
            return isImageFilterName(name.getName());
        }
        if (filter instanceof COSArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                COSBase item;
                try {
                    item = arr.get(i);
                } catch (Throwable ignore) {
                    continue;
                }
                if (item instanceof COSName n && isImageFilterName(n.getName())) return true;
            }
        }
        return false;
    }

    private static boolean isImageFilterName(String n) {
        if (n == null) return false;
        return switch (n) {
            // PDF 标准里的纯图像 Filter（不承载 Tj/TJ 文本指令）
            case "DCTDecode", "DCT"
            , "JPXDecode", "JPX"
            , "CCITTFaxDecode", "CCF"
            , "JBIG2Decode"
            , "RunLengthDecode", "RL"
            , "LZWDecode", "LZW" -> true;
            default -> false;
        };
    }

    /**
     * 分批写入 VectorStore（Embedding 调用），每批 ≤ DASHSCOPE_EMBEDDING_MAX_BATCH。
     */
    private int writeChunksInBatches(List<dev.langchain4j.data.segment.TextSegment> chunks, String logPrefix) {
        return writeChunksInBatches(chunks, chunks == null ? 0 : chunks.size(), logPrefix);
    }

    /**
     * 分批写入 VectorStore（Embedding 调用），每批 ≤ DASHSCOPE_EMBEDDING_MAX_BATCH。
     *
     * @param totalChunks 这份文件过滤后的总块数。块级增量重写时传进来的 {@code chunks} 只有变化块，
     *                    但 {@code total_chunks} 必须按整份文件的块数写——它是「是否完整入库」的判据，
     *                    写成变化块的数量会让下次启动误判为缺块。
     */
    private int writeChunksInBatches(List<dev.langchain4j.data.segment.TextSegment> chunks, int totalChunks,
                                     String logPrefix) {
        if (chunks == null || chunks.isEmpty()) return 0;
        for (dev.langchain4j.data.segment.TextSegment c : chunks) {
            c.metadata().put("total_chunks", String.valueOf(totalChunks));
        }
        int batches = 0;
        long startMs = System.currentTimeMillis();
        for (List<dev.langchain4j.data.segment.TextSegment> batch : partition(chunks, DASHSCOPE_EMBEDDING_MAX_BATCH)) {
            writeBatchToStore(batch);
            batches++;
            if ((batches % 20) == 0) {
                long secs = (System.currentTimeMillis() - startMs + 500) / 1000;
                log.info(logPrefix + "   ↪ DashScope Embedding：已完成 " + batches + " 批（已用约 " + secs + "s）");
            }
        }
        return batches;
    }

    /**
     * 把一批 Document 写进 LangChain4j 的 EmbeddingStore。
     * <p>
     * LangChain4j 的写入要「向量 + 文本」成对给（{@code addAll(embeddings, segments)}），
     * 不像 Spring AI 的 {@code VectorStore.add(List<Document>)} 那样内部自动向量化，
     * 所以这里先显式 embed，再成对写入。
     */
    private void writeBatchToStore(List<dev.langchain4j.data.segment.TextSegment> batch) {
        if (vectorStore == null || batch == null || batch.isEmpty()) {
            return;
        }
        List<dev.langchain4j.data.segment.TextSegment> segments = new ArrayList<>(batch);
        dev.langchain4j.model.output.Response<List<dev.langchain4j.data.embedding.Embedding>> response =
                embeddingModel.embedAll(segments);
        vectorStore.addAll(response.content(), segments);
    }

    /**
     * 块级增量写入：命中旧 chunk_hash 的块复用旧 embedding（不调模型），其余块才调用 embedding 模型。
     * <p>
     * 实现要点：
     * <ul>
     *   <li>先把新块按 chunk_hash 分成「可复用」与「需重算」两组</li>
     *   <li>可复用组：直接拿旧行的 embedding_id + 向量，用 addAll(ids, embeddings, segments) 原地 upsert</li>
     *   <li>需重算组：批量调 embeddingModel.embedAll，生成新 UUID</li>
     *   <li>两组合并后分批写库，保证 ids / embeddings / segments 三表对齐</li>
     * </ul>
     * 由于 deleteStoredSourceRows 已在调用前清空本 source 旧行，这里的 upsert 对复用块等价于 insert。
     *
     * @param totalChunks 整份文件过滤后的块数（写入 total_chunks 元数据用）
     * @param oldChunksByHash 旧块 (chunk_hash → StoredChunk)，可复用的 embedding 来源
     */
    int writeChunksInBatchesWithReuse(
            List<dev.langchain4j.data.segment.TextSegment> chunks, int totalChunks,
            Map<String, StoredChunk> oldChunksByHash, String logPrefix) {
        if (chunks == null || chunks.isEmpty()) return 0;
        if (vectorStore == null) {
            return writeChunksInBatches(chunks, totalChunks, logPrefix);
        }

        for (dev.langchain4j.data.segment.TextSegment c : chunks) {
            c.metadata().put("total_chunks", String.valueOf(totalChunks));
        }

        List<dev.langchain4j.data.segment.TextSegment> allSegments = new ArrayList<>(chunks.size());
        List<String> allIds = new ArrayList<>(chunks.size());
        List<dev.langchain4j.data.embedding.Embedding> allEmbeddings = new ArrayList<>(chunks.size());
        List<dev.langchain4j.data.segment.TextSegment> needEmbed = new ArrayList<>();

        int reused = 0;
        for (dev.langchain4j.data.segment.TextSegment c : chunks) {
            String hash = c.metadata().getString("chunk_hash");
            StoredChunk old = (hash != null && oldChunksByHash != null) ? oldChunksByHash.get(hash) : null;
            if (old != null && old.vector != null) {
                allSegments.add(c);
                allIds.add(old.embeddingId);
                allEmbeddings.add(new dev.langchain4j.data.embedding.Embedding(old.vector));
                reused++;
            } else {
                needEmbed.add(c);
            }
        }

        if (!needEmbed.isEmpty()) {
            dev.langchain4j.model.output.Response<List<dev.langchain4j.data.embedding.Embedding>> response =
                    embeddingModel.embedAll(needEmbed);
            List<dev.langchain4j.data.embedding.Embedding> newEmbeddings = response.content();
            for (int i = 0; i < needEmbed.size(); i++) {
                allSegments.add(needEmbed.get(i));
                allIds.add(java.util.UUID.randomUUID().toString());
                allEmbeddings.add(newEmbeddings.get(i));
            }
        }

        int batches = 0;
        long startMs = System.currentTimeMillis();
        for (int i = 0; i < allSegments.size(); i += DASHSCOPE_EMBEDDING_MAX_BATCH) {
            int end = Math.min(i + DASHSCOPE_EMBEDDING_MAX_BATCH, allSegments.size());
            vectorStore.addAll(
                    allIds.subList(i, end),
                    allEmbeddings.subList(i, end),
                    allSegments.subList(i, end));
            batches++;
            if ((batches % 20) == 0) {
                long secs = (System.currentTimeMillis() - startMs + 500) / 1000;
                log.info(logPrefix + "   ↪ 块级增量写入：已完成 " + batches + " 批（已用约 " + secs + "s）");
            }
        }

        chunkReuseOld.addAndGet(reused);
        chunkReuseNew.addAndGet(needEmbed.size());
        if (reused > 0 || !needEmbed.isEmpty()) {
            log.info(logPrefix + "    ↪ [块级增量] 复用旧 embedding=" + reused
                    + "  新调 embedding=" + needEmbed.size() + "  总块=" + chunks.size());
        }
        return batches;
    }

    /**
     * 块级过滤与去重，写入 VectorStore 前的最后一道闸门：
     * <ol>
     *   <li>为每个块计算 chunk_hash（SHA-256 of 块正文）并打 block_type（text/table/figure），写入 metadata</li>
     *   <li>低信息量过滤：纯数字/纯标点/有效字符过少/页码模式</li>
     *   <li>文件内去重：同 chunk_hash 只保留首次出现</li>
     *   <li>跨文件去重：库内<b>其它 source</b> 已存在同 chunk_hash 的块不再写入</li>
     * </ol>
     * <p>
     * 跨文件查重必须排除本文件自己的 source：重灌时本文件的旧行此刻还没删
     * （删除发生在调用本方法之后），若把自身旧行算作"库内已存在"，未变的块会被判为
     * 重复跳过，紧接着旧行被删掉，这些块就静默丢失了。
     *
     * @param selfSourceKeys 本文件的 source 键（canonical/absolute），从查重结果中排除自身
     */
    List<dev.langchain4j.data.segment.TextSegment> filterAndDeduplicateChunks(
            List<dev.langchain4j.data.segment.TextSegment> chunks, String[] selfSourceKeys, String logPrefix) {
        if (chunks == null || chunks.isEmpty()) return Collections.emptyList();

        int total = chunks.size();
        List<String> hashes = new ArrayList<>(total);
        for (dev.langchain4j.data.segment.TextSegment c : chunks) {
            String hash = sha256Hex(segmentText(c));
            c.metadata().put("chunk_hash", nullToEmpty(hash));
            c.metadata().put("block_type", BlockTypeClassifier.classify(segmentText(c)));
            hashes.add(hash);
        }

        Set<String> existingInDb = new HashSet<>();
        if (pgJdbc != null) {
            String[] selfKeys = (selfSourceKeys == null || selfSourceKeys.length == 0)
                    ? new String[]{"\u0000__never_matches__"} : selfSourceKeys;
            try {
                for (List<String> batch : partition(hashes, 500)) {
                    Set<String> found = pgJdbc.query(
                            "SELECT DISTINCT metadata->>'chunk_hash' FROM " + vectorStoreTable()
                                    + " WHERE metadata->>'chunk_hash' IN " + placeholders(batch.size())
                                    + " AND COALESCE(metadata->>'source', '') NOT IN ("
                                    + placeholders(selfKeys.length) + ")",
                            rs -> {
                                Set<String> s = new HashSet<>();
                                while (rs.next()) s.add(rs.getString(1));
                                return s;
                            }, concatArgs(batch.toArray(), selfKeys));
                    if (found != null) existingInDb.addAll(found);
                }
            } catch (Exception ex) {
                log.warn(logPrefix + "    ⚠️ 跨文件查重失败（不影响写入，退化为不过滤跨文件重复）：" + ex.getMessage());
                existingInDb.clear();
            }
        }

        List<dev.langchain4j.data.segment.TextSegment> result = new ArrayList<>();
        Set<String> seenInFile = new HashSet<>();
        int lowInfoCount = 0, withinDupCount = 0, crossDupCount = 0;

        for (int i = 0; i < total; i++) {
            dev.langchain4j.data.segment.TextSegment c = chunks.get(i);
            String text = segmentText(c);
            String hash = hashes.get(i);

            if (isLowInformationChunk(text)) {
                lowInfoCount++;
                continue;
            }
            if (!seenInFile.add(hash)) {
                withinDupCount++;
                continue;
            }
            if (existingInDb.contains(hash)) {
                crossDupCount++;
                continue;
            }
            result.add(c);
        }

        chunkFilterOriginal.addAndGet(total);
        chunkFilterDroppedLowInfo.addAndGet(lowInfoCount);
        chunkFilterDroppedWithinFile.addAndGet(withinDupCount);
        chunkFilterDroppedCrossFile.addAndGet(crossDupCount);
        chunkFilterKept.addAndGet(result.size());

        if (lowInfoCount > 0 || withinDupCount > 0 || crossDupCount > 0) {
            log.info(logPrefix + "    ↪ [块过滤] 原始=" + total
                    + "  低信息量=" + lowInfoCount
                    + "  文件内重复=" + withinDupCount
                    + "  跨文件重复=" + crossDupCount
                    + "  保留=" + result.size());
        }
        return result;
    }

    private static String segmentText(dev.langchain4j.data.segment.TextSegment s) {
        try {
            return s.text();
        } catch (Throwable t) {
            try {
                return (String) s.getClass().getMethod("getText").invoke(s);
            } catch (Throwable t2) {
                try {
                    return (String) s.getClass().getMethod("getContent").invoke(s);
                } catch (Throwable t3) {
                    return "";
                }
            }
        }
    }

    static boolean isLowInformationChunk(String text) {
        if (text == null || text.isBlank()) return true;
        String trimmed = text.trim();
        int nonBlank = 0, meaningful = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (!Character.isWhitespace(ch)) nonBlank++;
            if (Character.isLetter(ch) || isCjk(ch)) meaningful++;
        }
        if (nonBlank < 10) return true;
        if (meaningful < 2) return true;
        return trimmed.matches("^(第\\s*\\d+\\s*页|Page\\s*\\d+|\\d+\\s*/\\s*\\d+|\\d+)$");
    }

    private static boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
    }

    /**
     * 通用分片：把 list 切成多个子 list，每个子 list size ≤ batchSize。
     */
    private static <T> List<List<T>> partition(List<T> list, int batchSize) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        List<List<T>> result = new ArrayList<>((list.size() + batchSize - 1) / batchSize);
        for (int i = 0; i < list.size(); i += batchSize) {
            int end = Math.min(i + batchSize, list.size());
            result.add(new ArrayList<>(list.subList(i, end)));
        }
        return result;
    }

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "md", "markdown", "docx", "xlsx", "xls");

    private static boolean isSupportedFile(File f) {
        return f != null && f.isFile() && isSupportedFileName(f.getName());
    }

    /** 只认后缀、不看文件在不在盘上：ENTRY_DELETE 事件到达时文件已不存在，删档分发必须走这条。 */
    static boolean isSupportedFileName(String fileName) {
        if (fileName == null) return false;
        String name = fileName.toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return SUPPORTED_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /**
     * 收集所有待处理的文档文件：目录则扫描所有支持格式，单个文件则检查后缀。
     */
    private List<File> collectSupportedFiles(String path) {
        if (path == null || path.isBlank()) return Collections.emptyList();
        File f = new File(path);
        if (!f.exists()) return Collections.emptyList();
        if (f.isFile()) {
            return isSupportedFile(f) ? Collections.singletonList(f) : Collections.emptyList();
        }
        if (f.isDirectory()) {
            File[] arr = f.listFiles(RagInitializer::isSupportedFile);
            if (arr == null || arr.length == 0) return Collections.emptyList();
            List<File> list = new ArrayList<>(Arrays.asList(arr));
            list.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            return list;
        }
        return Collections.emptyList();
    }

    // ========== WatchService 目录热监听 ==========

    private static final long FILE_STABLE_CHECK_MS = 1000L;
    private static final long FILE_STABLE_WAIT_TIMEOUT_MS = 30_000L;

    private void startDirectoryWatcher() {
        if (vectorStore == null) return;
        File dir = new File(pdfPath);
        if (!dir.exists()) {
            log.warn("ℹ️ [RagInitializer] WatchService 跳过：路径 " + pdfPath + " 不存在");
            return;
        }
        if (!dir.isDirectory()) {
            log.warn("ℹ️ [RagInitializer] WatchService 跳过：" + pdfPath + " 不是目录");
            return;
        }

        Thread watcherThread = new Thread(() -> watchLoop(dir.toPath()), "rag-pdf-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private void watchLoop(Path dirPath) {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            this.watchService = ws;
            dirPath.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);

            log.warn("👁️ [RagInitializer] WatchService 已启动，监听目录：" + dirPath.toAbsolutePath());

            while (true) {
                WatchKey key;
                try {
                    key = ws.take();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (key == null) continue;

                for (WatchEvent<?> rawEvent : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = rawEvent.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> event = (WatchEvent<Path>) rawEvent;
                    Path child = event.context();
                    if (child == null) continue;

                    Path fullPath = dirPath.resolve(child);
                    try {
                        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                            // 删档事件到达时文件已不存在，isFile() 恒 false——只能按文件名后缀认。
                            // 原来和 CREATE/MODIFY 共用 isSupportedFile，把所有 DELETE 静默丢掉，
                            // 删档清理从来没真跑过（2026-11-18 小验证实测抓到，守门 SourceFileWatchTest）。
                            if (!isSupportedFileName(child.toString())) continue;
                            handleSourceFileDelete(fullPath.toFile());
                        } else {
                            if (!isSupportedFile(fullPath.toFile())) continue;
                            handleFileEvent(fullPath);
                        }
                    } catch (Throwable t) {
                        log.warn("  [热监听] ❌ 处理 " + fullPath.getFileName() + " 异常: " + t.getMessage());
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    log.warn("⚠️ [RagInitializer] WatchKey 已失效，停止监听");
                    break;
                }
            }
        } catch (Throwable th) {
            log.warn("⚠️ [RagInitializer] WatchService 异常退出: " + th.getClass().getSimpleName() + ": " + th.getMessage());
        } finally {
            this.watchService = null;
        }
    }

    private void handleFileEvent(Path fullPath) throws InterruptedException {
        String fileName = fullPath.getFileName().toString();

        // 如果已经在等待列表里，说明是连续多次 MODIFY 事件，不重复计时
        long now = System.currentTimeMillis();
        synchronized (pendingFileStableTime) {
            if (pendingFileStableTime.containsKey(fullPath)) return;
            pendingFileStableTime.put(fullPath, now);
        }

        // 等文件写完（大小连续两次稳定）
        boolean stable = waitFileStable(fullPath);

        synchronized (pendingFileStableTime) {
            pendingFileStableTime.remove(fullPath);
        }

        if (!stable) {
            log.warn("  [热监听] ⚠️ 文件写入不稳定，超时放弃：" + fileName);
            return;
        }

        File pdfFile = fullPath.toFile();
        if (!pdfFile.exists() || !pdfFile.isFile()) return;

        log.info("");
        log.info("🔔 [热监听] 检测到新/变更文件：" + fileName);
        IngestResult r = ingestOneFile(pdfFile, "  [热监听] ");
        // 内容真变过才刷新：判重跳过的文件（hash 相同）关键词索引本来就是对的，白花时间
        // 按份增量刷，不再全表重建
        if (r.ok && !r.skipped && r.chunks > 0) refreshKeywordIndexForSource(pdfFile, "热监听入库 " + fileName);
        log.info("");
    }

    private void handleSourceFileDelete(File pdf) {
        pendingFileStableTime.remove(pdf.toPath());
        String fileName = pdf.getName();
        int vectorRows = deleteStoredSourceRows(pdf);
        int bm25Rows = (bm25IndexService != null && bm25IndexService.isAvailable())
                ? bm25IndexService.deleteSource(sourceKeys(pdf)) : 0;
        log.warn("🗑️ [热监听] 检测到 PDF 已删除：" + fileName
                + "  → 清理 vector_store 块=" + vectorRows + "  bm25_docs 行=" + bm25Rows);
    }

    private boolean waitFileStable(Path path) throws InterruptedException {
        File f = path.toFile();
        long deadline = System.currentTimeMillis() + FILE_STABLE_WAIT_TIMEOUT_MS;
        long lastSize = -1;
        long stableSince = 0;
        int stableCount = 0;

        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!f.exists()) {
                Thread.sleep(FILE_STABLE_CHECK_MS);
                continue;
            }
            long size = f.length();
            if (size == lastSize) {
                stableCount++;
                if (stableCount >= 2) return true;
            } else {
                stableCount = 0;
            }
            lastSize = size;
            Thread.sleep(FILE_STABLE_CHECK_MS);
        }
        // 超时退出：如果文件已存在且有稳定过至少一次，仍然尝试处理
        return f.exists() && stableCount >= 1;
    }

    private String ocrExtractFromPdf(PDDocument pdDoc, ProcessRegistrar registrar) {
        if (registrar == null) registrar = NOOP_REGISTRAR;
        File workDir = null;
        try {
            int pages = pdDoc.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(pdDoc);
            workDir = Files.createTempDirectory("rag-ocr-" + System.currentTimeMillis()).toFile();
            StringBuilder allText = new StringBuilder(pages * 4096);

            for (int i = 0; i < pages; i++) {
                long pageStart = System.currentTimeMillis();
                File png = null;
                File outBase = null;
                try {
                    // 1) PDF 页 → PNG
                    BufferedImage img = renderer.renderImageWithDPI(i, ocrDpi, ImageType.RGB);
                    png = new File(workDir, "p" + i + ".png");
                    ImageIO.write(img, "png", png);
                    long pngSize = png.length();
                    img.flush();

                    // 2) tesseract PNG → TXT（强制 UTF-8 输出）
                    outBase = new File(workDir, "p" + i);
                    List<String> cmd = new ArrayList<>(12);
                    cmd.add(ocrTesseractPath);
                    cmd.add(png.getAbsolutePath());
                    cmd.add(outBase.getAbsolutePath());
                    cmd.add("-l");
                    cmd.add(ocrLanguage);
                    cmd.add("--dpi");
                    cmd.add(String.valueOf(ocrDpi));
                    cmd.add("--tessdata-dir");
                    cmd.add(effectiveTessdataDir != null ? effectiveTessdataDir : ocrTessdataDir);
                    cmd.add("-c");
                    cmd.add("output_encoding=UTF-8");

                    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
                    java.util.Map<String, String> env = pb.environment();
                    env.put("TESSERACT_OUTPUT_ENCODING", "UTF-8");
                    Process p = pb.start();
                    registrar.register(p);
                    StringBuilder procOut = new StringBuilder(512);
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) procOut.append(line).append('\n');
                    }
                    boolean finished = p.waitFor(ocrPageTimeoutSec, TimeUnit.SECONDS);
                    int exit = p.exitValue();
                    if (!finished) {
                        p.destroyForcibly();
                        log.warn("      ⚠️ 第 " + (i + 1) + "/" + pages + " 页 OCR 超时（>" + ocrPageTimeoutSec + "s），跳过");
                        continue;
                    }
                    if (exit != 0) {
                        String hint = procOut.toString().replaceAll("\\s+", " ").trim();
                        if (hint.length() > 500) hint = hint.substring(0, 500) + "...";
                        log.warn("      ❌ 第 " + (i + 1) + "/" + pages + " 页 OCR 失败 exit=" + exit
                                + "：" + (hint.isEmpty() ? "(stdout空)" : hint));
                        continue;
                    }

                    // 3) 读取 TXT 结果（已通过 -c output_encoding=UTF-8 强制 UTF-8）
                    File txtFile = new File(outBase.getAbsolutePath() + ".txt");
                    String pageText = "";
                    long txtSize = txtFile.exists() ? txtFile.length() : -1;
                    if (txtFile.exists() && txtSize > 0) {
                        pageText = Files.readString(txtFile.toPath(), StandardCharsets.UTF_8);
                    }
                    allText.append(pageText).append("\n\n");

                    // 4) 进度输出
                    int nb = countNonBlank(pageText);
                    long totalMs = System.currentTimeMillis() - pageStart;
                    String pageMsg = "      ↪ 第 " + (i + 1) + "/" + pages + " 页 OCR 完成（共" + totalMs
                            + "ms，PNG=" + (pngSize / 1024) + "KB，TXT="
                            + (txtSize > 0 ? txtSize + "B" : "空") + "，非空字符≈" + nb + "）";
                    if (nb == 0) {
                        log.warn(pageMsg);
                    } else {
                        log.info(pageMsg);
                    }
                } catch (Throwable pageTh) {
                    log.warn("      ⚠️ 第 " + (i + 1) + "/" + pages + " 页 OCR 异常: "
                            + pageTh.getClass().getSimpleName() + ": " + pageTh.getMessage());
                } finally {
                    if (png != null && png.exists()) png.delete();
                    if (outBase != null) {
                        for (String suffix : new String[]{".txt", ".tsv", ".hocr", ".pdf"}) {
                            File f = new File(outBase.getAbsolutePath() + suffix);
                            if (f.exists()) f.delete();
                        }
                    }
                }
            }
            return allText.toString().trim();
        } catch (Throwable th) {
            log.warn("  ❌ [P4-OCR] 整体异常，放弃 OCR: " + th.getClass().getSimpleName() + ": " + th.getMessage());
            return "";
        } finally {
            // 清理临时目录
            if (workDir != null && workDir.exists()) {
                try {
                    File[] left = workDir.listFiles();
                    if (left != null) for (File f : left) f.delete();
                    workDir.delete();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * 块过滤累计计数快照，给 /rag/diag 用。
     * <p>注意这是<b>进程内累计</b>，不是库内现状：应用重启后归零，只反映重启以来跑过的入库量。
     * 库内现状要看 stored_overview 的 chunks_in_store。
     */
    private Map<String, Object> blockFilterStats() {
        long original = chunkFilterOriginal.get();
        long kept = chunkFilterKept.get();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("original_chunks", original);
        m.put("dropped_low_info", chunkFilterDroppedLowInfo.get());
        m.put("dropped_within_file_dup", chunkFilterDroppedWithinFile.get());
        m.put("dropped_cross_file_dup", chunkFilterDroppedCrossFile.get());
        m.put("kept", kept);
        m.put("dropped_total", original - kept);
        m.put("drop_rate",
                original == 0 ? "0%" : String.format("%.1f%%", (original - kept) * 100.0 / original));
        long reused = chunkReuseOld.get();
        long newlyEmbedded = chunkReuseNew.get();
        long totalWritten = reused + newlyEmbedded;
        m.put("incremental_reused_old_embedding", reused);
        m.put("incremental_new_embedding_calls", newlyEmbedded);
        m.put("incremental_reuse_rate",
                totalWritten == 0 ? "0%" : String.format("%.1f%%", reused * 100.0 / totalWritten));
        return m;
    }

    /**
     * 运行时诊断：配置、OCR 状态、本轮入库流水、块过滤计数、库内 chunk 分布 + 逐份 PDF 体检（不写 VectorStore）。
     *
     * @param deep false（默认）= 只开文件读页数并和库内指纹比对；true = 再跑 P1/P2/P3 三层正文提取。
     *             三层提取每份 PDF 都要把全文扒一遍，文件一多这个接口自己就先把自己打挂了，所以不进默认路径。
     */
    public Map<String, Object> diagnose(boolean deep) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("pdf_path_config", pdfPath);
        out.put("vector_store_type", vectorStore == null ? "<NULL>" : vectorStore.getClass().getName());
        out.put("vector_store_is_null", vectorStore == null);
        out.put("ocr", ocrSection());
        out.put("last_boot_ingest", lastBootIngest == null
                ? "本轮启动没跑批量入库（目录里没有 PDF，或没有 VectorStore Bean）" : lastBootIngest);
        out.put("block_filter", blockFilterStats());
        out.put("stored_overview", storedOverview());

        List<File> files;
        try {
            files = collectSupportedFiles(pdfPath);
        } catch (Exception ex) {
            out.put("collect_error", ex.toString());
            return out;
        }

        out.put("scanned_file_count", files.size());
        // 逐份体检要开一次文件读页数、再打一条 SQL 问库内指纹，是 per-file 成本。
        // 和库内列表一样套 LIMIT：目录里堆到几万份时，这个接口不该自己把连接打满。
        int listed = Math.min(files.size(), STORED_OVERVIEW_LIMIT);
        List<Map<String, Object>> fileDiags = new ArrayList<>();
        for (int i = 0; i < listed; i++) {
            File f = files.get(i);
            Map<String, Object> one = quickPdfDiag(f);
            if (deep) one.putAll(deepExtractDiag(f));
            fileDiags.add(one);
        }
        out.put("files", fileDiags);
        out.put("files_listed", listed);
        out.put("files_truncated", files.size() > listed);
        return out;
    }

    /**
     * OCR 这条腿的真实状态：README 让人「看 ocr_ready 和 tessdata_dir」，那两个键必须真的存在。
     */
    private Map<String, Object> ocrSection() {
        Map<String, Object> o = new java.util.LinkedHashMap<>();
        o.put("enabled_config", ocrEnabled);
        // 没跑过批量入库时探测结果还没产生，区分「没探过」和「探过=不可用」
        o.put("probed", ocrAvailableCache != null);
        o.put("ocr_ready", ocrAvailableCache);
        o.put("tesseract_path_config", ocrTesseractPath);
        o.put("tessdata_dir", effectiveTessdataDir != null ? effectiveTessdataDir : ocrTessdataDir);
        o.put("tessdata_dir_config", ocrTessdataDir);
        o.put("language", ocrLanguage);
        o.put("dpi", ocrDpi);
        o.put("diag_line", ocrDiagLine);
        return o;
    }

    /**
     * 单份 PDF 的便宜体检：页数/字节指纹 + 库里已有的块数，下次启动会不会跳过。
     */
    private Map<String, Object> quickPdfDiag(File pdf) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", pdf.getName());
        m.put("size_bytes", pdf.length());
        try (PDDocument pdDoc = openPdfLenient(pdf, null)) {
            m.put("page_count", pdDoc.getNumberOfPages());
            m.put("encrypted", pdDoc.isEncrypted());
        } catch (Throwable ex) {
            m.put("open_error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        String fileHash = hashFileQuietly(pdf);
        m.put("file_hash", fileHash == null ? "" : fileHash.substring(0, Math.min(12, fileHash.length())));
        StoredVersion stored = null;
        try {
            stored = probeStoredVersion(pdf);
        } catch (RuntimeException ex) {
            m.put("probe_error", ex.getMessage());
        }
        if (stored == null) {
            m.put("in_store", false);
        } else {
            m.put("in_store", true);
            m.put("stored_chunks", stored.chunkCount);
            m.put("stored_total_chunks", stored.totalChunks);
            m.put("complete", stored.complete());
            m.put("stored_extract_version", stored.extractVersion);
            m.put("current_extract_version", EXTRACT_PIPELINE_VERSION);
            m.put("will_skip_next_boot", stored.sameFile(fileHash));
        }
        return m;
    }

    /**
     * 库内实况：按 source 数块（只读 SQL，不碰模型）。列表一律带 LIMIT，百万份文件下不能全表往外倒。
     */
    private static final int STORED_OVERVIEW_LIMIT = 200;

    private Map<String, Object> storedOverview() {
        Map<String, Object> o = new java.util.LinkedHashMap<>();
        if (pgJdbc == null) {
            o.put("available", false);
            o.put("reason", "pgVectorJdbcTemplate 未注入（PGVector 未启用），库内分布只能看启动日志");
            return o;
        }
        String table = vectorStoreTable();
        try {
            Map<String, Object> totals = pgJdbc.query(
                    "SELECT COUNT(*) AS chunks, COUNT(DISTINCT metadata->>'source') AS files FROM " + table,
                    rs -> {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        if (rs.next()) {
                            row.put("chunks_in_store", rs.getLong("chunks"));
                            row.put("files_in_store", rs.getLong("files"));
                        }
                        return row;
                    });
            o.put("chunks_in_store", totals.get("chunks_in_store"));
            o.put("files_in_store", totals.get("files_in_store"));
        } catch (Exception ex) {
            o.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return o;
        }
        try {
            List<Map<String, Object>> rows = pgJdbc.query(
                    "SELECT metadata->>'source' AS src, COUNT(*) AS c, "
                            + "MAX((metadata->>'total_chunks')::int) AS total, "
                            + "MAX(metadata->>'extract_version') AS ev "
                            + "FROM " + table + " GROUP BY 1 ORDER BY 1 LIMIT " + STORED_OVERVIEW_LIMIT,
                    (java.sql.ResultSet rs, int n) -> {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        String src = rs.getString("src");
                        int c = rs.getInt("c");
                        int total = rs.getInt("total");
                        String ev = rs.getString("ev");
                        row.put("source", src == null ? "<无 source>" : new File(src).getName());
                        row.put("chunks", c);
                        row.put("total_chunks", total);
                        row.put("extract_version", ev);
                        row.put("incomplete", total > 0 && c != total);
                        row.put("stale_extract_version", !EXTRACT_PIPELINE_VERSION.equals(ev));
                        row.put("file_missing_on_disk", src != null && !new File(src).exists());
                        return row;
                    });
            o.put("per_file", rows);
            o.put("per_file_limit", STORED_OVERVIEW_LIMIT);
            o.put("per_file_truncated", rows.size() >= STORED_OVERVIEW_LIMIT);
            o.put("incomplete_count_in_listed", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("incomplete"))).count());
            o.put("stale_version_count_in_listed", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("stale_extract_version"))).count());
            o.put("orphan_count_in_listed", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("file_missing_on_disk"))).count());
        } catch (Exception ex) {
            o.put("per_file_error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        return o;
    }

    /**
     * 对单个 PDF 跑多层提取 + 暴力扫统计（不切片不写库，返回诊断 Map）。
     */
    private Map<String, Object> deepExtractDiag(File pdf) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", pdf.getName());
        m.put("size_bytes", pdf.length());
        try {
            try (PDDocument pdDoc = openPdfLenient(pdf, null)) {
                m.put("page_count", pdDoc.getNumberOfPages());
                m.put("encrypted", pdDoc.isEncrypted());

                String t1 = new PDFTextStripper().getText(pdDoc);
                int nb1 = countNonBlank(t1);
                m.put("P1_len", len(t1));
                m.put("P1_non_blank", nb1);

                int nb2 = 0;
                if (nb1 < 500) {
                    PDFTextStripper s2 = new PDFTextStripper();
                    s2.setSortByPosition(false);
                    s2.setAddMoreFormatting(false);
                    s2.setShouldSeparateByBeads(false);
                    String t2 = s2.getText(pdDoc);
                    nb2 = countNonBlank(t2);
                }
                m.put("P2_non_blank", nb2);

                int[] stats = new int[4];
                StringBuilder firstHit = new StringBuilder(500);
                String brute = bruteExtractStringsFromAllStreams(pdDoc, stats, firstHit);
                int nb3 = countNonBlank(brute);
                m.put("P3_len", len(brute));
                m.put("P3_non_blank", nb3);
                m.put("streams_total", stats[0]);
                m.put("streams_with_Tj_TJ", stats[1]);
                m.put("literal_pieces_ok", stats[2]);
                m.put("hex_pieces_ok", stats[3]);
                if (firstHit.length() > 0) {
                    String d = firstHit.toString();
                    m.put("first_Tj_TJ_stream_500", d.length() > 500 ? d.substring(0, 500) : d);
                }
                if (nb3 > 0) {
                    String prev = brute.replaceAll("[\\r\\n\\t ]+", " ").trim();
                    m.put("pass3_preview_400", prev.length() > 400 ? prev.substring(0, 400) : prev);
                } else if (nb1 > 0) {
                    String prev = t1.replaceAll("[\\r\\n\\t ]+", " ").trim();
                    m.put("pass1_preview_400", prev.length() > 400 ? prev.substring(0, 400) : prev);
                }
                m.put("verdict",
                        nb3 >= 500 ? "PASS_3_OK" :
                                Math.max(nb1, nb2) >= 500 ? "PASS_1_OR_2_OK" :
                                        stats[1] == 0 ? "NO_TEXT_STREAM_AT_ALL（纯图片，建议用 OCR 识别）" :
                                                "HAS_TEXT_STREAM_BUT_DECODE_FAILED");
            }
        } catch (Throwable th) {
            m.put("error", th.getClass().getSimpleName() + ": " + th.getMessage());
        }
        return m;
    }
}