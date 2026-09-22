package org.example.service;

import jakarta.annotation.PostConstruct;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 知识库初始化：把 PDF 文档切片 → 向量化 → 分批写入 VectorStore。
 *
 * 不使用 Spring AI 自带的 PagePdfDocumentReader：其版面坐标排序 Comparator 不满足 Java TimSort 合约，
 * 复杂 PDF（双栏/表格/OCR 扫描件）会抛 "Comparison method violates its general contract"。
 * 这里改用 PDFBox 原生 PDFTextStripper 按文本流顺序抽全文，从源头规避坏排序。
 *
 * 由于 DashScope text-embedding-v3 官方限制单次批量 ≤10，写入时按批切分。
 */
@Component
public class RagInitializer {

    // 统一强制 PDFBox 使用磁盘文件缓存而非主内存，避免大 PDF 加载时内存暴涨。
    // 兼容 PDFBox 2.x ~ 3.1.x 各小版本不同命名的 System Property。
    static {
        final String FALSE = "false";
        final String FILE  = "file";
        setProp("pdfbox.useMainMemory",                 FALSE);
        setProp("pdfbox.memory.useMainMemory",          FALSE);
        setProp("org.apache.pdfbox.useMainMemory",      FALSE);
        setProp("pdfbox.scratchFile.useMainMemory",     FALSE);
        setProp("pdfbox.stream.cache",                  FILE);
        setProp("pdfbox.memory.usage",                  "tempfile");
        setProp("pdfbox.memory.mainMemoryBytes",        String.valueOf(128L * 1024 * 1024));
        setProp("pdfbox.mainMemoryUsageBytes",          String.valueOf(128L * 1024 * 1024));

        long maxMemBytes = Runtime.getRuntime().maxMemory();
        long maxMemMB    = maxMemBytes / (1024 * 1024);
        String pdfboxVer = "UNKNOWN";
        try {
            Package p = PDDocument.class.getPackage();
            if (p != null && p.getImplementationVersion() != null) pdfboxVer = p.getImplementationVersion();
        } catch (Throwable ignore) {}
        if (maxMemMB < 2048) {
            System.out.println("[RagInitializer] ⚠️ JVM 堆 < 2GB（当前 " + maxMemMB + " MB），超大扫描 PDF 可能发生 Full GC。");
            System.out.println("[RagInitializer]    建议在 Run Configuration 的 VM options 中加入 -Xmx2g。");
        }
    }
    private static void setProp(String key, String val) {
        try { System.setProperty(key, val); } catch (Throwable ignore) {}
    }

    /** DashScope Embedding 单次批量上限（官方硬限制，不要调大） */
    private static final int DASHSCOPE_EMBEDDING_MAX_BATCH = 10;

    private final VectorStore vectorStore;
    /** PGVector JDBC：用于「PDF 已入库」去重（PGVector 未启用时为 null） */
    private final org.springframework.jdbc.core.JdbcTemplate pgJdbc;
    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(512)
            .build();

    @Value("${app.pdf-path:D:/ai-docs}")
    private String pdfPath;

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
            @Autowired(required = false) VectorStore vectorStore,
            @Autowired(required = false)
            @org.springframework.beans.factory.annotation.Qualifier("pgVectorJdbcTemplate")
            org.springframework.jdbc.core.JdbcTemplate pgJdbc) {
        this.vectorStore = vectorStore;
        this.pgJdbc = pgJdbc;
    }

    // 子进程注册表接口 + AutoCloseable 包装，用于超时时终止遗留的 tesseract.exe 子进程
    @FunctionalInterface
    private interface ProcessRegistrar {
        void register(Process p);
    }
    private static final ProcessRegistrar NOOP_REGISTRAR = p -> { };
    private static final class PdfProcessRegistry implements ProcessRegistrar, AutoCloseable {
        private final ProcessRegistrar delegate;
        PdfProcessRegistry(ProcessRegistrar delegate) { this.delegate = delegate == null ? NOOP_REGISTRAR : delegate; }
        @Override public void register(Process p) { delegate.register(p); }
        @Override public void close() { }
    }

    // OCR 配置有效性（运行时一次性判定，缓存结果）
    private transient Boolean ocrAvailableCache = null;
    private transient String effectiveTessdataDir = null;

    // WatchService 相关：目录热监听
    private volatile WatchService watchService;
    private final Map<Path, Long> pendingFileStableTime = new ConcurrentHashMap<>();
    private boolean isOcrAvailable() {
        if (ocrAvailableCache != null) return ocrAvailableCache;
        if (!ocrEnabled) { ocrAvailableCache = false; return false; }
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
                candidateDirs.add(new File("D:/AI/demoAI/.diag-tmp/dl"));
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
                        if (!td.exists()) { allOk = false; missSb.append(l).append(" "); }
                    }
                    if (allOk) { bestDir = candDir; bestDirIndex = i; break; }
                    if (missingLangs == null) missingLangs = missSb.toString().trim();
                }

                // 最后兜底：把项目里的 chi_sim.traineddata 复制到 D:/tessdata
                if (bestDir == null) {
                    File srcTd = new File("D:/AI/demoAI/.diag-tmp/dl/chi_sim.traineddata");
                    if (srcTd.exists()) {
                        File dstDir = new File("D:/tessdata");
                        try {
                            if (!dstDir.exists()) dstDir.mkdirs();
                            File dstTd = new File(dstDir, "chi_sim.traineddata");
                            if (!dstTd.exists()) Files.copy(srcTd.toPath(), dstTd.toPath());
                            boolean hasEng = new File(dstDir, "eng.traineddata").exists();
                            if (!hasEng) {
                                File engSrc = new File("D:/Program Files/Tesseract-OCR/tessdata/eng.traineddata");
                                if (engSrc.exists()) Files.copy(engSrc.toPath(), new File(dstDir, "eng.traineddata").toPath());
                                hasEng = new File(dstDir, "eng.traineddata").exists();
                            }
                            boolean allOk = true;
                            for (String langPart : ocrLanguage.replaceAll("\\+", ",").split(",")) {
                                String l = langPart.trim();
                                if (l.isEmpty()) continue;
                                if (!new File(dstDir, l + ".traineddata").exists()) allOk = false;
                            }
                            if (allOk) { bestDir = dstDir; bestDirIndex = -2; }
                        } catch (Throwable ignore) {}
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
                        try { usedConfigured = configuredDir.exists() && configuredDir.isDirectory()
                                && configuredDir.getCanonicalFile().equals(bestDir.getCanonicalFile()); }
                        catch (Throwable ignore) {}
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
        System.out.println();
        System.out.println(diag);
        if (!ok) {
            System.out.println("  👉 不启用 OCR 兜底，纯图片扫描 PDF 将被跳过（不影响文字型 PDF 正常入库）。");
        }
        System.out.println();
        effectiveTessdataDir = finalTessDir;
        ocrAvailableCache = ok;
        return ok;
    }

    /** 单个 PDF 入库结果（给 init() 汇总用） */
    private static final class IngestResult {
        final int chunks;
        final int batches;
        final boolean ok;
        final boolean skipped;
        IngestResult(int chunks, int batches, boolean ok, boolean skipped) {
            this.chunks = chunks; this.batches = batches; this.ok = ok; this.skipped = skipped;
        }
        static IngestResult skipped(int chunks) { return new IngestResult(chunks, 0, true, true); }
        static IngestResult ok(int chunks, int batches) { return new IngestResult(chunks, batches, true, false); }
        static IngestResult fail() { return new IngestResult(0, 0, false, false); }
    }

    private static final long PER_PDF_MIN_TIMEOUT_MILLIS   = 120_000L;
    private static final long PER_PDF_OCR_MS_PER_PAGE      = 15_000L;
    private static final long PER_PDF_BASE_MILLIS          = 60_000L;

    /**
     * 把单个 PDF 做 提取→切片→Embedding→写入 VectorStore。
     * 已入库的 PDF 直接跳过（probeAlreadyIngested）。
     *
     * @param pdf 目标 PDF 文件
     * @param logPrefix 日志前缀（启动时用 "  "，热监听用 "  [热监听]"）
     */
    private IngestResult ingestOnePdf(File pdf, String logPrefix) {
        AlreadyIngestedInfo ingested = probeAlreadyIngested(pdf);
        if (ingested != null) {
            System.out.println(logPrefix + "⏭️  跳过：" + pdf.getName() + "（已入库 " + ingested.chunkCount
                    + " chunks，有效字数≈" + ingested.representativeNonBlank + "）");
            return IngestResult.skipped(ingested.chunkCount);
        }

        System.out.println(logPrefix + "📥 开始处理：" + pdf.getName() + " (" + formatSize(pdf.length()) + ") ...");
        long startMs = System.currentTimeMillis();
        List<Document> chunks = Collections.emptyList();

        long thisTimeoutMs = PER_PDF_MIN_TIMEOUT_MILLIS;
        int pageCount = 0;
        try (PDDocument probe = Loader.loadPDF(pdf)) {
            pageCount = probe.getNumberOfPages();
        } catch (Throwable ignore) {}
        if (pageCount > 0) {
            long ocrBased = PER_PDF_BASE_MILLIS + (long) pageCount * PER_PDF_OCR_MS_PER_PAGE;
            thisTimeoutMs = Math.max(PER_PDF_MIN_TIMEOUT_MILLIS, ocrBased);
        }
        final long finalTimeoutMs = thisTimeoutMs;
        final Set<Process> childProcesses = Collections.synchronizedSet(java.util.Collections.newSetFromMap(new IdentityHashMap<>()));

        try {
            final FutureTask<List<Document>> ft = new FutureTask<>(() -> {
                List<Document> result;
                ProcessRegistrar registrarLambda = proc -> { if (proc != null) childProcesses.add(proc); };
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
                            p.descendants().forEach(ph -> { try { ph.destroyForcibly(); } catch (Throwable ignore) {} });
                            p.destroyForcibly();
                            killed++;
                        } catch (Throwable ignore) {}
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

            long tookSec = (System.currentTimeMillis() - startMs + 500) / 1000;
            if (chunks.isEmpty()) {
                System.out.println(logPrefix + "⚪ " + pdf.getName() + "（用时" + tookSec + "s）无有效文本内容，跳过");
                return IngestResult.ok(0, 0);
            }
            int batchesForThisPdf = 0;
            long batchStartMs = System.currentTimeMillis();
            for (List<Document> batch : partition(chunks, DASHSCOPE_EMBEDDING_MAX_BATCH)) {
                vectorStore.add(batch);
                batchesForThisPdf++;
                if ((batchesForThisPdf % 20) == 0) {
                    long secs = (System.currentTimeMillis() - batchStartMs + 500) / 1000;
                    System.out.println(logPrefix + "   ↪ DashScope Embedding：已完成 " + batchesForThisPdf + " 批（已用约 " + secs + "s）");
                }
            }
            long totalChars = 0;
            int minChunk = Integer.MAX_VALUE, maxChunk = 0, emptyChunks = 0;
            java.lang.reflect.Method docTextGetter = null;
            for (java.lang.reflect.Method m : Document.class.getMethods()) {
                String n = m.getName();
                if ((n.equals("getText") || n.equals("getContent")) && m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                    docTextGetter = m;
                    break;
                }
            }
            for (Document d : chunks) {
                String s = null;
                if (docTextGetter != null) { try { s = (String) docTextGetter.invoke(d); } catch (Throwable ignore) {} }
                int l = (s == null) ? 0 : s.length();
                totalChars += l;
                if (l == 0) emptyChunks++;
                if (l < minChunk) minChunk = l;
                if (l > maxChunk) maxChunk = l;
            }
            String qcMsg = (emptyChunks == 0) ? "" : ("  ⚠️ 空块=" + emptyChunks);
            System.out.println(logPrefix + "✅ " + pdf.getName()
                    + "（" + (tookSec + (System.currentTimeMillis() - startMs)/1000) + "s）"
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
            System.err.println(logPrefix + "❌ " + pdf.getName()
                    + " 处理失败（跳过）: "
                    + th.getClass().getSimpleName() + ": " + msg + extraHint);
            return IngestResult.fail();
        }
    }

    @PostConstruct
    public void init() {
        if (Objects.isNull(vectorStore)) {
            System.out.println("ℹ️ [RagInitializer] 未检测到 VectorStore Bean，跳过 PDF 向量化初始化（@Tool 工具调用仍可用）");
            return;
        }

        List<File> pdfs;
        try {
            pdfs = collectPdfs(pdfPath);
        } catch (Exception ex) {
            System.err.println("[RagInitializer] 收集 PDF 列表异常，全部跳过: " + ex.getMessage());
            startDirectoryWatcher();
            return;
        }
        if (pdfs.isEmpty()) {
            System.out.println("ℹ️ [RagInitializer] 路径 " + pdfPath + " 下没有任何 .pdf 文件，跳过向量化初始化。");
            startDirectoryWatcher();
            return;
        }

        isOcrAvailable();
        System.out.println();
        System.out.println("[RagInitializer] 共发现 " + pdfs.size() + " 个 PDF，开始批量向量化 ...");
        System.out.println("[RagInitializer] Embedding 每批最多 " + DASHSCOPE_EMBEDDING_MAX_BATCH + " 个 chunks（DashScope 硬性限制）");

        int totalChunks = 0, totalBatches = 0, okCount = 0, failCount = 0;
        for (File pdf : pdfs) {
            IngestResult r = ingestOnePdf(pdf, "  ");
            totalChunks += r.chunks;
            totalBatches += r.batches;
            if (r.ok) okCount++;
            else failCount++;
        }

        System.out.println("[RagInitializer] 完成。成功PDF=" + okCount
                + "  失败PDF=" + failCount
                + "  chunks总数=" + totalChunks
                + "  提交批次数=" + totalBatches
                + " (每批≤" + DASHSCOPE_EMBEDDING_MAX_BATCH + ")");
        System.out.println();

        startDirectoryWatcher();
    }

    /**
     * 读单个 PDF + 切分。
     *
     * 为应对「扫描件 + 隐藏 OCR 文字层」PDF，采用多层 fallback 提取流程。
     * 打开 PDF 时同时绕过常见的所有者权限限制，以便从加密但内容可见的 PDF 中抽取文字。
     */
    // 旧签名：给诊断/测试代码用（不传 registrar = 不注册进程）
    private List<Document> readAndSplitOnePdf(File pdf) throws Exception {
        return readAndSplitOnePdf(pdf, NOOP_REGISTRAR);
    }

    private List<Document> readAndSplitOnePdf(File pdf, ProcessRegistrar registrar) throws Exception {
        if (registrar == null) registrar = NOOP_REGISTRAR;
        String password = null;
        try (PDDocument pdDoc = openPdfLenient(pdf, password)) {
            int pageCount = pdDoc.getNumberOfPages();
            boolean encrypted = pdDoc.isEncrypted();

            // ① QuickScan 前 5 页（<1s），判断 PDF 类型路径
            int[] stats = new int[5];
            StringBuilder firstHitDump = new StringBuilder(300);
            String quick = bruteExtractQuickFirstNPages(pdDoc, 5, stats, firstHitDump);
            int nonBlankQuick = countNonBlank(quick);
            final boolean isScannedHiddenText = nonBlankQuick >= 200;

            int nonBlank1 = 0, nonBlank2 = 0, nonBlank3 = 0, nonBlank4 = 0;
            int len = 0, len2 = 0, len3 = 0, len4 = 0;
            String fullText = null;
            String brutePreview = "";
            if (isScannedHiddenText) {
                // 路径 A：扫描件（隐藏层有文字）→ 只扒全部页 Page+Annotation 内容流，快速提取
                String brute = bruteExtractScanOnlyPagesAndAnnotations(pdDoc, stats, firstHitDump);
                len3 = len(brute);
                nonBlank3 = countNonBlank(brute);
                fullText = brute;
                len = len3;
                if (!brute.isEmpty()) {
                    String p = brute.replaceAll("[\\r\\n\\t ]+", " ").trim();
                    brutePreview = p.length() > 300 ? p.substring(0, 300) : p;
                }
                // 路径 A 极少数结果仍不足时也做 OCR 兜底
                if (nonBlank3 < 200 && isOcrAvailable()) {
                    String ocr = ocrExtractFromPdf(pdDoc, registrar);
                    len4 = len(ocr);
                    nonBlank4 = countNonBlank(ocr);
                    if (nonBlank4 > Math.max(nonBlank3, nonBlankQuick)) {
                        fullText = ocr; len = len4;
                    }
                }
            } else {
                // 路径 B：纯图片 / 普通文字型 PDF → P1 → P2 → P3 兜底 → P4 OCR
                fullText = new PDFTextStripper().getText(pdDoc);
                len = (fullText == null) ? 0 : fullText.length();
                nonBlank1 = countNonBlank(fullText);
                if (nonBlank1 < 200) {
                    PDFTextStripper s2 = new PDFTextStripper();
                    s2.setSortByPosition(false);
                    s2.setAddMoreFormatting(false);
                    s2.setShouldSeparateByBeads(false);
                    s2.setLineSeparator("\n");
                    String t2 = s2.getText(pdDoc);
                    nonBlank2 = countNonBlank(t2);
                    len2 = len(t2);
                    if (nonBlank2 > nonBlank1) { fullText = t2; len = len2; }
                }
                // P3 兜底（P1/P2 不够才跑）
                if (Math.max(nonBlank1, nonBlank2) < 200) {
                    String brute = bruteExtractStringsFromAllStreams(pdDoc, stats, firstHitDump);
                    len3 = len(brute);
                    nonBlank3 = countNonBlank(brute);
                    if (nonBlank3 > Math.max(nonBlank1, nonBlank2)) { fullText = brute; len = len3; }
                    if (!brute.isEmpty()) {
                        String p = brute.replaceAll("[\\r\\n\\t ]+", " ").trim();
                        brutePreview = p.length() > 300 ? p.substring(0, 300) : p;
                    }
                }
                // P4 OCR 终极兜底（2 种触发条件：纯图片扫描件；或页多但每页平均字数异常少）
                int maxBeforeOcr = Math.max(Math.max(Math.max(nonBlank1, nonBlank2), nonBlank3), nonBlankQuick);
                double avgPerPage = (pageCount <= 0) ? 0 : ((double) maxBeforeOcr / pageCount);
                boolean triggerOcrByCondition1 = (maxBeforeOcr < 200) && isOcrAvailable();
                boolean triggerOcrByCondition2 = (pageCount >= 10) && (avgPerPage < 100.0) && isOcrAvailable() && (nonBlank4 == 0);
                if (triggerOcrByCondition1 || triggerOcrByCondition2) {
                    String ocr = ocrExtractFromPdf(pdDoc, registrar);
                    len4 = len(ocr);
                    nonBlank4 = countNonBlank(ocr);
                    if (nonBlank4 > maxBeforeOcr) {
                        fullText = ocr; len = len4;
                    }
                }
            }

            int finalNonBlank = Math.max(Math.max(Math.max(Math.max(nonBlank1, nonBlank2), nonBlank3), nonBlank4), nonBlankQuick);
            List<Document> chunks = Collections.emptyList();

            // 抽取内容质量 QC（乱码自动救回），命中不良特征且 OCR 可用时用 OCR 重扫
            if (finalNonBlank >= 20 && nonBlank4 == 0 && isOcrAvailable()) {
                String checkText = (fullText == null) ? "" : fullText;
                int total = checkText.length();
                int fffd = 0, cjk = 0, ctrl = 0;
                for (int i = 0; i < total; i++) {
                    char c = checkText.charAt(i);
                    if      (c == '\uFFFD')                                   fffd++;
                    else if (c >= '\u4E00' && c <= '\u9FFF')                  cjk++;
                    else if (c < 0x20 && c != '\r' && c != '\n' && c != '\t') ctrl++;
                }
                double fffdRate = (finalNonBlank == 0) ? 0 : ((double) fffd / finalNonBlank);
                double cjkRate  = (finalNonBlank == 0) ? 0 : ((double) cjk  / finalNonBlank);
                double ctrlRate = (total        == 0) ? 0 : ((double) ctrl / total);
                boolean qualityBad = false;
                String why = "";
                if (fffdRate > 0.05)                    { qualityBad = true; why = "替换符(U+FFFD)比例=" + String.format("%.1f%%", fffdRate*100); }
                else if (pageCount >= 10 && cjkRate < 0.15) { qualityBad = true; why = pageCount + "页但中文占比仅" + String.format("%.1f%%", cjkRate*100); }
                else if (ctrlRate > 0.03)               { qualityBad = true; why = "控制字符比例=" + String.format("%.1f%%", ctrlRate*100); }

                if (qualityBad) {
                    System.out.println("    ↪ [内容QC] ⚠️ 抽取内容疑似乱码/低质量：" + why
                            + "。废弃当前文本，用 Tesseract OCR 重新识别 ...");
                    System.out.println("             （U+FFFD=" + fffd + "，CJK汉字=" + cjk + "/" + finalNonBlank
                            + "，控制字符=" + ctrl + "）");
                    String preview = checkText.replaceAll("[\\r\\n\\t ]+", " ").trim();
                    if (preview.length() > 150) preview = preview.substring(0, 150) + "...";
                    System.out.println("             预览: 「" + preview + "」");
                    String ocr = ocrExtractFromPdf(pdDoc, registrar);
                    int nbOcr = countNonBlank(ocr);

                    // OCR 质量合格（CJK 比例达标）则无条件采用；否则按字数是否过少决定取舍
                    String ocrCheck = (ocr == null) ? "" : ocr;
                    int oFffd = 0, oCjk = 0;
                    for (int i = 0; i < ocrCheck.length(); i++) {
                        char c = ocrCheck.charAt(i);
                        if (c == '\uFFFD') oFffd++;
                        else if (c >= '\u4E00' && c <= '\u9FFF') oCjk++;
                    }
                    double oFffdRate = (nbOcr == 0) ? 0 : ((double) oFffd / nbOcr);
                    double oCjkRate  = (nbOcr == 0) ? 0 : ((double) oCjk  / nbOcr);
                    boolean ocrQualityOk = (oCjkRate >= 0.15) && (oFffdRate <= 0.01);
                    boolean ocrWorseThan20pct = (finalNonBlank >= 500) && (nbOcr * 5 < finalNonBlank);
                    boolean useOcr = ocrQualityOk || !ocrWorseThan20pct;

                    if (useOcr && nbOcr >= 20) {
                        fullText = ocr;
                        len = len(ocr);
                        finalNonBlank = nbOcr;
                        nonBlank4 = nbOcr;
                        String tag = ocrQualityOk ? "✅（OCR 质量合格）" : "⚠️（OCR 质量一般，但比乱码原值强）";
                        System.out.println("    ↪ [内容QC] 已采用 OCR 文本 " + tag + "，非空=" + nbOcr);
                    } else {
                        String whyRejected = ocrWorseThan20pct ? ("OCR 字数仅为原文本 " + (nbOcr*100/Math.max(1,finalNonBlank)) + "%，内容过少")
                                                               : ("OCR 字数 < 20 字符阈值");
                        System.out.println("    ↪ [内容QC] ⚠️ 未能救回，仍保留原值：" + whyRejected);
                    }
                }
            }

            if (finalNonBlank >= 20) {
                // metadata 的 source 与 probeAlreadyIngested() 保持同一种路径（getCanonicalPath 归一化）
                String metaSource;
                try { metaSource = pdf.getCanonicalPath(); } catch (IOException ignore) { metaSource = pdf.getAbsolutePath(); }
                Document doc = new Document(fullText == null ? "" : fullText, Map.of(
                        "source", metaSource,
                        "file_name", pdf.getName(),
                        "page_count", pageCount,
                        "text_length", len,
                        "non_blank_length", finalNonBlank
                ));
                List<Document> c = splitter.apply(Collections.singletonList(doc));
                if (c != null) chunks = c;
            }

            return chunks;
        }
    }

    private static int len(String s) { return s == null ? 0 : s.length(); }
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format(Locale.ROOT, "%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
    /** 去掉 \r\n\t 空格及零宽/不间断空白后计数 */
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

    // 增量入库：探测 PDF 是否已成功入库（chunk 数 ≥1 且 有效字数 ≥500）
    private static final class AlreadyIngestedInfo {
        final int chunkCount;
        final int representativeNonBlank;
        AlreadyIngestedInfo(int chunkCount, int representativeNonBlank) {
            this.chunkCount = chunkCount;
            this.representativeNonBlank = representativeNonBlank;
        }
    }
    private AlreadyIngestedInfo probeAlreadyIngested(File pdf) {
        if (this.pgJdbc == null || pdf == null) return null;
        // 兼容老/新记录的 source（absolutePath 与 canonicalPath 均匹配）
        String canonicalPath = null;
        try { canonicalPath = pdf.getCanonicalPath(); } catch (IOException ignore) { }
        final String absolutePath = pdf.getAbsolutePath();
        final boolean samePath = (canonicalPath != null) && canonicalPath.equals(absolutePath);
        final int MIN_VALID_CHUNKS = 1;
        final int MIN_VALID_NON_BLANK = 500;
        try {
            String sql;
            Object[] args;
            if (samePath) {
                sql = "SELECT COUNT(*), COALESCE(MAX((metadata->>'non_blank_length')::int), 0) "
                    + "FROM   public.vector_store WHERE metadata->>'source' = ?";
                args = new Object[]{ canonicalPath };
            } else {
                sql = "SELECT COUNT(*), COALESCE(MAX((metadata->>'non_blank_length')::int), 0) "
                    + "FROM   public.vector_store WHERE metadata->>'source' IN (?, ?)";
                args = (canonicalPath == null) ? new Object[]{ absolutePath, absolutePath }
                                               : new Object[]{ canonicalPath, absolutePath };
            }
            return this.pgJdbc.query(sql, rs -> {
                if (rs.next()) {
                    int chunks = rs.getInt(1);
                    int nb     = rs.getInt(2);
                    if (chunks >= MIN_VALID_CHUNKS && nb >= MIN_VALID_NON_BLANK) {
                        return new AlreadyIngestedInfo(chunks, nb);
                    }
                }
                return null;
            }, args);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 打开 PDF（兼容 PDFBox 3.0.x / 3.1.x）。仅用 Loader.loadPDF 系列稳定 API，
     * 内存/磁盘缓存策略由 static{} 块统一配置。对加密文档尝试放开权限位，便于抽取可见文字。
     */
    private static PDDocument openPdfLenient(File pdf, String password) throws IOException {
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
                    } catch (Throwable ignore) { }
                }
            } catch (Throwable ignore) { }
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
        Pattern hexPat     = Pattern.compile("<([0-9A-Fa-f]{4,65536})>");
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
                            try { walkCOS(ann.getCOSObject(), literalPat, hexPat, sb, visited, stats, firstHitDump); } catch (Throwable ignore) {}
                        }
                    }
                } catch (Throwable ignore) {}
            }
            try { walkCOS(pdDoc.getDocumentCatalog().getCOSObject(), literalPat, hexPat, sb, visited, stats, firstHitDump); } catch (Throwable ignore) {}
        } catch (Throwable ignore) {}
        return sb.toString().trim();
    }

    /** 快速通道：先扫前 N 页的 Page COS + Annotation（不递归 Catalog），快速判断有无隐藏文字层。 */
    private static String bruteExtractQuickFirstNPages(PDDocument pdDoc, int nPages, int[] stats, StringBuilder firstHitDump) {
        StringBuilder sb = new StringBuilder(32768);
        Pattern literalPat = Pattern.compile("\\(([^\\)]{2,2000})\\)");
        Pattern hexPat     = Pattern.compile("<([0-9A-Fa-f]{4,65536})>");
        IdentityHashMap<COSBase, Boolean> visited = new IdentityHashMap<>(1024);
        if (stats == null) stats = new int[4];
        try {
            PDPageTree pages = pdDoc.getPages();
            int processed = 0;
            for (org.apache.pdfbox.pdmodel.PDPage page : pages) {
                if (processed >= nPages) break;
                if (page == null) continue;
                walkCOS(page.getCOSObject(), literalPat, hexPat, sb, visited, stats, firstHitDump);
                try {
                    java.util.List<PDAnnotation> anns = page.getAnnotations();
                    if (anns != null) {
                        for (PDAnnotation ann : anns) {
                            try { walkCOS(ann.getCOSObject(), literalPat, hexPat, sb, visited, stats, firstHitDump); } catch (Throwable ignore) {}
                        }
                    }
                } catch (Throwable ignore) {}
                processed++;
            }
        } catch (Throwable ignore) {}
        return sb.toString().trim();
    }

    /** 扫描件专用极速提取：只遍历全部页 Page COS + Annotation，绝不递归 Catalog 整棵对象树。 */
    private static String bruteExtractScanOnlyPagesAndAnnotations(PDDocument pdDoc, int[] stats, StringBuilder firstHitDump) {
        StringBuilder sb = new StringBuilder(65536);
        Pattern literalPat = Pattern.compile("\\(([^\\)]{2,2000})\\)");
        Pattern hexPat     = Pattern.compile("<([0-9A-Fa-f]{4,65536})>");
        IdentityHashMap<COSBase, Boolean> visited = new IdentityHashMap<>(4096);
        if (stats == null) stats = new int[4];
        try {
            PDPageTree pages = pdDoc.getPages();
            // 阶段1：Page COS 本身（/Contents 内容流 + /Resources/XObject 流），不加载 Annotation 子树
            java.util.List<org.apache.pdfbox.pdmodel.PDPage> allPages = new java.util.ArrayList<>(pages.getCount());
            for (org.apache.pdfbox.pdmodel.PDPage page : pages) allPages.add(page);
            for (org.apache.pdfbox.pdmodel.PDPage page : allPages) {
                if (page == null) continue;
                walkCOS(page.getCOSObject(), literalPat, hexPat, sb, visited, stats, firstHitDump);
            }
            int nbPhase1 = countNonBlank(sb.toString());
            // 阶段1 足够（≥1000 非空字）则直接返回，跳过 Annotation
            if (nbPhase1 >= 1000) return sb.toString().trim();

            // 阶段2（兜底）：补 Annotation 外观流
            for (org.apache.pdfbox.pdmodel.PDPage page : allPages) {
                if (page == null) continue;
                try {
                    java.util.List<PDAnnotation> anns = page.getAnnotations();
                    if (anns != null) {
                        for (PDAnnotation ann : anns) {
                            try { walkCOS(ann.getCOSObject(), literalPat, hexPat, sb, visited, stats, firstHitDump); } catch (Throwable ignore) {}
                        }
                    }
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
        return sb.toString().trim();
    }

    /** 递归 walk COS 对象树，提取可读文字并统计；stats[0]=totalStreams stats[1]=Tj/TJ hits stats[2]=literalOk stats[3]=hexOk */
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
            try { filterObj = s.getDictionaryObject(COSName.FILTER); } catch (Throwable ignore) {}
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
            } catch (Throwable ignore) {}

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
                    if (looksReadable(str)) { sb.append(str).append(' '); stats[2]++; }
                }
                Matcher mh = hexPat.matcher(content);
                while (mh.find()) {
                    String hex = mh.group(1);
                    String decoded = decodeHexSmart(hex);
                    if (looksReadable(decoded)) { sb.append(decoded).append(' '); stats[3]++; }
                }
            } catch (Throwable ignore) {}
        } else if (base instanceof COSDictionary dict) {
            Set<Map.Entry<COSName, COSBase>> entries;
            try { entries = dict.entrySet(); } catch (Throwable ignore) { return; }
            for (Map.Entry<COSName, COSBase> e : entries) {
                try { walkCOS(e.getValue(), literalPat, hexPat, sb, visited, stats, firstHitDump); } catch (Throwable ignore) {}
            }
        } else if (base instanceof COSArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                try { walkCOS(arr.get(i), literalPat, hexPat, sb, visited, stats, firstHitDump); } catch (Throwable ignore) {}
            }
        }
    }

    /** PDF 括号字面量转义还原：\( \) \\ \n \r \t \b \f 及八进制 */
    private static String unescapePdfLiteral(String raw) {
        if (raw == null || raw.indexOf('\\') < 0) return raw;
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\') { out.append(c); continue; }
            if (i + 1 >= raw.length()) break;
            char n = raw.charAt(++i);
            switch (n) {
                case '(': case ')': case '\\': out.append(n); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
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

    /** 十六进制智能解码：按 "UTF-16BE → GBK → UTF-8" 顺序尝试，返回最像可读文字的 */
    private static String decodeHexSmart(String hex) {
        if (hex == null || (hex.length() & 1) != 0) return "";
        byte[] bytes;
        try { bytes = hexToBytes(hex); } catch (Throwable ignore) { return ""; }
        if (bytes == null || bytes.length == 0) return "";

        String utf16 = "", gbk = "", utf8 = "";
        try { utf16 = new String(bytes, java.nio.charset.StandardCharsets.UTF_16BE); } catch (Throwable ignore) {}
        try { gbk   = new String(bytes, java.nio.charset.Charset.forName("GBK")); } catch (Throwable ignore) {}
        try { utf8  = new String(bytes, java.nio.charset.StandardCharsets.UTF_8); }    catch (Throwable ignore) {}

        String[] candidates = {utf16, gbk, utf8};
        String best = "";
        int    bestScore = -1;
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
            if (score > bestScore) { bestScore = score; best = c; }
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

    /** 判断字符串是否"看起来像人读的文字"：CJK 汉字优先，怪异/控制字符超标则拒 */
    private static boolean looksReadable(String s) {
        if (s == null || s.isEmpty()) return false;
        int cjk = 0, lettersOrDigit = 0, weird = 0, total = s.length();
        for (char c : s.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) { cjk++; lettersOrDigit++; }
            else if (Character.isLetterOrDigit(c)) lettersOrDigit++;
            else if (c < 0x20 && "\n\r\t\b\f".indexOf(c) < 0) weird++;
            else if (c == 0x7F) weird++;
        }
        if (cjk >= 2) return true;
        if (weird * 10 > total) return false;
        return lettersOrDigit * 5 >= total * 2;
    }

    /** 判断 COSStream 的 /Filter 是否属于纯图像压缩类（此类流不含文本指令，直接跳过）。 */
    private static boolean isPureImageFilter(COSBase filter) {
        if (filter == null) return false;
        if (filter instanceof COSName name) {
            return isImageFilterName(name.getName());
        }
        if (filter instanceof COSArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                COSBase item;
                try { item = arr.get(i); } catch (Throwable ignore) { continue; }
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
                    , "LZWDecode", "LZW"
                    -> true;
            default -> false;
        };
    }

    /** 通用分片：把 list 切成多个子 list，每个子 list size ≤ batchSize。 */
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

    /**
     * 收集所有待处理的 PDF 文件：目录则扫描 *.pdf，单个文件则只返回该文件，路径无效则空集合。
     */
    private List<File> collectPdfs(String path) {
        if (path == null || path.isBlank()) return Collections.emptyList();
        File f = new File(path);
        if (!f.exists()) return Collections.emptyList();
        if (f.isFile()) {
            if (f.getName().toLowerCase().endsWith(".pdf")) return Collections.singletonList(f);
            return Collections.emptyList();
        }
        if (f.isDirectory()) {
            File[] arr = f.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
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
            System.out.println("ℹ️ [RagInitializer] WatchService 跳过：路径 " + pdfPath + " 不存在");
            return;
        }
        if (!dir.isDirectory()) {
            System.out.println("ℹ️ [RagInitializer] WatchService 跳过：" + pdfPath + " 不是目录");
            return;
        }

        Thread watcherThread = new Thread(() -> watchLoop(dir.toPath()), "rag-pdf-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private void watchLoop(Path dirPath) {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            this.watchService = ws;
            dirPath.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);

            System.out.println("👁️ [RagInitializer] WatchService 已启动，监听目录：" + dirPath.toAbsolutePath());

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
                    if (!fullPath.toString().toLowerCase().endsWith(".pdf")) continue;

                    try {
                        handleFileEvent(fullPath);
                    } catch (Throwable t) {
                        System.err.println("  [热监听] ❌ 处理 " + fullPath.getFileName() + " 异常: " + t.getMessage());
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    System.out.println("⚠️ [RagInitializer] WatchKey 已失效，停止监听");
                    break;
                }
            }
        } catch (Throwable th) {
            System.err.println("⚠️ [RagInitializer] WatchService 异常退出: " + th.getClass().getSimpleName() + ": " + th.getMessage());
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
            System.err.println("  [热监听] ⚠️ 文件写入不稳定，超时放弃：" + fileName);
            return;
        }

        File pdfFile = fullPath.toFile();
        if (!pdfFile.exists() || !pdfFile.isFile()) return;

        System.out.println();
        System.out.println("🔔 [热监听] 检测到新/变更 PDF：" + fileName);
        ingestOnePdf(pdfFile, "  [热监听] ");
        System.out.println();
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
                    cmd.add("-l"); cmd.add(ocrLanguage);
                    cmd.add("--dpi"); cmd.add(String.valueOf(ocrDpi));
                    cmd.add("--tessdata-dir"); cmd.add(effectiveTessdataDir != null ? effectiveTessdataDir : ocrTessdataDir);
                    cmd.add("-c"); cmd.add("output_encoding=UTF-8");

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
                        System.out.println("      ⚠️ 第 " + (i+1) + "/" + pages + " 页 OCR 超时（>" + ocrPageTimeoutSec + "s），跳过");
                        continue;
                    }
                    if (exit != 0) {
                        String hint = procOut.toString().replaceAll("\\s+", " ").trim();
                        if (hint.length() > 500) hint = hint.substring(0,500)+"...";
                        System.err.println("      ❌ 第 " + (i+1) + "/" + pages + " 页 OCR 失败 exit=" + exit
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
                    String pageMsg = "      ↪ 第 " + (i+1) + "/" + pages + " 页 OCR 完成（共" + totalMs
                            + "ms，PNG=" + (pngSize/1024) + "KB，TXT="
                            + (txtSize>0?txtSize+"B":"空") + "，非空字符≈" + nb + "）";
                    if (nb == 0) {
                        System.err.println(pageMsg);
                    } else {
                        System.out.println(pageMsg);
                    }
                } catch (Throwable pageTh) {
                    System.err.println("      ⚠️ 第 " + (i+1) + "/" + pages + " 页 OCR 异常: "
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
            System.err.println("  ❌ [P4-OCR] 整体异常，放弃 OCR: " + th.getClass().getSimpleName() + ": " + th.getMessage());
            return "";
        } finally {
            // 清理临时目录
            if (workDir != null && workDir.exists()) {
                try {
                    File[] left = workDir.listFiles();
                    if (left != null) for (File f : left) f.delete();
                    workDir.delete();
                } catch (Throwable ignore) {}
            }
        }
    }

    /** 运行时诊断：返回当前配置、扫描到的 PDF 列表及各 PDF 提取诊断（不写 VectorStore）。 */
    public Map<String, Object> diagnose() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("pdf_path_config", pdfPath);
        out.put("vector_store_type", vectorStore == null ? "<NULL>" : vectorStore.getClass().getName());
        out.put("vector_store_is_null", vectorStore == null);

        List<File> pdfs;
        try { pdfs = collectPdfs(pdfPath); }
        catch (Exception ex) { out.put("collect_error", ex.toString()); return out; }

        out.put("scanned_pdf_count", pdfs.size());
        List<Map<String, Object>> pdfDiags = new ArrayList<>();
        for (File pdf : pdfs) pdfDiags.add(diagnoseOnePdf(pdf));
        out.put("pdfs", pdfDiags);
        return out;
    }

    /** 对单个 PDF 跑多层提取 + 暴力扫统计（不切片不写库，返回诊断 Map）。 */
    private Map<String, Object> diagnoseOnePdf(File pdf) {
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
                    s2.setSortByPosition(false); s2.setAddMoreFormatting(false);
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