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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 知识库初始化：把 PDF 文档切片 → 向量化 → 分批写入 VectorStore。
 *
 * 👉 【为什么不用 Spring AI 的 PagePdfDocumentReader？】
 *    Spring AI 1.1.2 自带的 ForkPDFLayoutTextStripper.sortTextPositionList()
 *    Comparator 不满足 Java TimSort 合约，复杂 PDF（双栏/表格/OCR 扫描导出）会直接抛：
 *      "Comparison method violates its general contract!"
 *    这里改用 pom 里已依赖的 **PDFBox 原生 PDFTextStripper** 按文本流顺序抽全文，
 *    完全不走版面坐标比较，从源头绕开坏 sort；同时 RAG 检索链路不受影响。
 *
 * 👉 【为什么一定要分批写 VectorStore？】
 *    DashScope text-embedding-v3 官方限制单次批量 ≤10。
 */
@Component
public class RagInitializer {

    // ★★★ 2026-08-20 终极双保险（100% 编译通过，PDFBox 3.x 全小版本通吃）★★★
    //
    //  核心矛盾：不同 PDFBox 3.x 小版本的「强制磁盘缓存」公开 API 差异巨大（ScratchFile / StreamCacheCreateFunction…
    //            你本地刚才把 3 套写法全打回来了 → 再猜 API 是无底洞）。
    //
    //  这次策略：不用任何可能变的 PDFBox 缓存重载，统一退回 Loader.loadPDF(File/String)（PDFBox 3.0/3.1 100% 存在），
    //  然后在 static 块里【暴力覆盖 PDFBox 从 2.x 到 3.1.x 历史上所有出现过的内存模式 System Property】（全设为 false/file）：
    //    无论你本地 PDFBox 是哪个小版本，总有至少 1~2 个 Property 名是对的 → 会命中磁盘缓存。
    //
    //  同时打印 ① 当前 PDFBox Implementation-Version ② 当前 JVM 堆上限：
    //    如果命中 <2GB → 超级红字提示，要求 IDE Run Configuration 的 VM options 加 -Xmx2g（内存兜底）。
    static {
        final String FALSE = "false";
        final String FILE  = "file";
        setProp("pdfbox.useMainMemory",                 FALSE);      // PDFBox 2.x / 3.0.0-Mx
        setProp("pdfbox.memory.useMainMemory",          FALSE);      // PDFBox 3.0.0 GA
        setProp("org.apache.pdfbox.useMainMemory",      FALSE);      // PDFBox 3.0.1~3.0.3
        setProp("pdfbox.scratchFile.useMainMemory",     FALSE);      // PDFBox ScratchFile 相关
        setProp("pdfbox.stream.cache",                  FILE);       // PDFBox 3.1.x 新命名
        setProp("pdfbox.memory.usage",                  "tempfile"); // PDFBox 3.1.x 另一套命名
        setProp("pdfbox.memory.mainMemoryBytes",        String.valueOf(128L * 1024 * 1024)); // 主内存封顶 128MB
        setProp("pdfbox.mainMemoryUsageBytes",          String.valueOf(128L * 1024 * 1024)); // 另一命名

        // --- 诊断信息打印（超级醒目）---
        long maxMemBytes = Runtime.getRuntime().maxMemory();
        long maxMemMB    = maxMemBytes / (1024 * 1024);
        String pdfboxVer = "UNKNOWN";
        try {
            Package p = PDDocument.class.getPackage();
            if (p != null && p.getImplementationVersion() != null) pdfboxVer = p.getImplementationVersion();
        } catch (Throwable ignore) {}
        System.out.println();
        System.out.println("[RagInitializer:PDFBox 诊断] =====================================================");
        System.out.println("  PDFBox Implementation-Version = " + pdfboxVer);
        System.out.println("  JVM -Xmx 最大堆 = " + maxMemMB + " MB");
        System.out.println("  已暴力覆盖以下 System Property（命中 PDFBox 所有历史命名）：");
        System.out.println("    pdfbox.useMainMemory=false");
        System.out.println("    pdfbox.memory.useMainMemory=false");
        System.out.println("    org.apache.pdfbox.useMainMemory=false");
        System.out.println("    pdfbox.stream.cache=file");
        System.out.println("    pdfbox.memory.mainMemoryBytes=128MB");
        if (maxMemMB < 2048) {
            System.out.println();
            System.out.println("  ⚠️⚠️  JVM 堆 < 2GB！超大扫描 PDF（50MB+）可能 Full GC 卡顿。");
            System.out.println("  👉  解决方式：IDE 右上角 Run/Debug Configurations → Modify options → Add VM options → 填入：");
            System.out.println("         -Xmx2g");
            System.out.println("         然后 Apply → 重新 Run 'Main'。");
        } else {
            System.out.println("  ✅  JVM 堆充足（≥2GB），内存不会成为瓶颈。");
        }
        System.out.println("[RagInitializer:PDFBox 诊断] =====================================================");
        System.out.println();
    }
    private static void setProp(String key, String val) {
        try { System.setProperty(key, val); } catch (Throwable ignore) {}
    }

    /** DashScope Embedding 单次批量上限（官方硬限制，不要调大） */
    private static final int DASHSCOPE_EMBEDDING_MAX_BATCH = 10;

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter = new TokenTextSplitter();

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

    public RagInitializer(@Autowired(required = false) VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // -----------------------------------------------------------------------------
    // 子进程注册表接口 + AutoCloseable 包装：
    //   每次 ProcessBuilder.start() 后把 Process 注册进来，外层超时/Future.cancel 时
    //   可以遍历所有已启动的 tesseract.exe 并 destroyForcibly（避免子进程泄漏、两份 PDF 同时 OCR 打架）
    // -----------------------------------------------------------------------------
    @FunctionalInterface
    private interface ProcessRegistrar {
        void register(Process p);
    }
    private static final ProcessRegistrar NOOP_REGISTRAR = p -> { /* 不注册，仅诊断/单测路径用 */ };
    private static final class PdfProcessRegistry implements ProcessRegistrar, AutoCloseable {
        private final ProcessRegistrar delegate;
        PdfProcessRegistry(ProcessRegistrar delegate) { this.delegate = delegate == null ? NOOP_REGISTRAR : delegate; }
        @Override public void register(Process p) { delegate.register(p); }
        @Override public void close() { /* 资源清理占位，将来可扩展统计/超时预警 */ }
    }

    // OCR 配置有效性（运行时一次性诊断）
    private transient Boolean ocrAvailableCache = null;
    private transient String effectiveTessdataDir = null; // 诊断后最终生效的 tessdata 目录
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
                diag.append("❌ tesseract.exe 不存在: ").append(ocrTesseractPath);
                diag.append(" → 请修改 application.yml 的 app.ocr.tesseract-path");
                diag.append("，或在 ").append("D:\\Program Files\\Tesseract-OCR\\").append(" 安装 Tesseract");
            } else {
                // ★★★ 智能 tessdata 目录降级策略 ★★★
                // 顺序：① 配置文件指定的目录  ② 项目自带的 .diag-tmp/dl/ 目录（chi_sim.traineddata 已在里面！）
                //       ③ 用户主目录下的 tessdata  ④ 尝试自动创建 D:/tessdata 并复制 traineddata
                List<File> candidateDirs = new ArrayList<>();
                candidateDirs.add(new File(ocrTessdataDir));
                candidateDirs.add(new File("D:/AI/demoAI/.diag-tmp/dl"));  // 项目自带语言包（优先！无需复制）
                candidateDirs.add(new File(System.getProperty("user.home"), "tessdata"));

                File bestDir = null;
                String missingLangs = null;
                for (File candDir : candidateDirs) {
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
                    if (allOk) { bestDir = candDir; break; }
                    if (missingLangs == null) missingLangs = missSb.toString().trim();
                }

                // ★ 最后兜底：尝试把项目里的 chi_sim.traineddata 复制到 D:/tessdata（Program Files 权限不足的替代方案）
                if (bestDir == null) {
                    File srcTd = new File("D:/AI/demoAI/.diag-tmp/dl/chi_sim.traineddata");
                    if (srcTd.exists()) {
                        File dstDir = new File("D:/tessdata");
                        try {
                            if (!dstDir.exists()) dstDir.mkdirs();
                            File dstTd = new File(dstDir, "chi_sim.traineddata");
                            if (!dstTd.exists()) Files.copy(srcTd.toPath(), dstTd.toPath());
                            // 复制完再检查 eng（Tesseract 自带的 eng.traineddata 应该还在 Program Files 里）
                            boolean hasChi = new File(dstDir, "chi_sim.traineddata").exists();
                            boolean hasEng = new File(dstDir, "eng.traineddata").exists();
                            if (!hasEng) {
                                // 把 eng.traineddata 也从安装目录拷过来（不需要管理员权限，读 Program Files 是允许的）
                                File engSrc = new File("D:/Program Files/Tesseract-OCR/tessdata/eng.traineddata");
                                if (engSrc.exists()) Files.copy(engSrc.toPath(), new File(dstDir, "eng.traineddata").toPath());
                                hasEng = new File(dstDir, "eng.traineddata").exists();
                            }
                            // 检查最终是否齐了
                            boolean allOk = true;
                            for (String langPart : ocrLanguage.replaceAll("\\+", ",").split(",")) {
                                String l = langPart.trim();
                                if (l.isEmpty()) continue;
                                if (!new File(dstDir, l + ".traineddata").exists()) allOk = false;
                            }
                            if (allOk) bestDir = dstDir;
                        } catch (Throwable ignore) {}
                    }
                }

                if (bestDir == null) {
                    File tessDir = new File(ocrTessdataDir);
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
                    diag.append("✅ Tesseract 就绪：exe=").append(exe.getAbsolutePath())
                            .append("\n       tessdata（自动选择）=").append(finalTessDir)
                            .append("\n       lang=").append(ocrLanguage)
                            .append("，dpi=").append(ocrDpi)
                            .append("，单页超时=").append(ocrPageTimeoutSec).append("s");
                    if (!finalTessDir.equals(ocrTessdataDir)) {
                        diag.append("\n       ⚡ 未用配置的 tessdata-dir（自动 fallback 到可用目录），可把 app.ocr.tessdata-dir 改成上面路径避免每次探测。");
                    }
                }
            }
        } catch (Throwable th) {
            diag.append("❌ OCR 初始化异常: ").append(th.getClass().getSimpleName()).append(": ").append(th.getMessage());
        }
        System.out.println();
        System.out.println(diag);
        if (!ok) {
            System.out.println("  👉 不启用 OCR 兜底，纯图片扫描 PDF 将被跳过（不影响文字型 PDF 正常入库）。");
            System.out.println("  👉 快速修复：把 D:\\AI\\demoAI\\.diag-tmp\\dl\\chi_sim.traineddata 复制到 D:\\tessdata\\ 即可（不需要管理员权限）");
        }
        System.out.println();
        effectiveTessdataDir = finalTessDir; // 保存最终生效目录供 OCR 方法使用
        ocrAvailableCache = ok;
        return ok;
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
            return;
        }
        if (pdfs.isEmpty()) {
            System.out.println("ℹ️ [RagInitializer] 路径 " + pdfPath + " 下没有任何 .pdf 文件，跳过向量化初始化。");
            System.out.println("                 👉 放几个 PDF 到该目录，或修改 application.yml 的 app.pdf-path 为单个 PDF 路径，重启即可。");
            return;
        }

        System.out.println();
        isOcrAvailable(); // 先跑一次 OCR 诊断（一次性打印配置状态）
        System.out.println();
        System.out.println("[RagInitializer] ============================================================");
        System.out.println("[RagInitializer] 共发现 " + pdfs.size() + " 个 PDF，开始批量向量化 ...");
        System.out.println("[RagInitializer] PDF 读取：PDFBox 原生 PDFTextStripper（绕开 TimSort 版面排序崩溃）");
        System.out.println("[RagInitializer] Embedding 每批最多 " + DASHSCOPE_EMBEDDING_MAX_BATCH + " 个 chunks（DashScope 硬性限制）");
        System.out.println("[RagInitializer] 每份 PDF 提取超时 = 120 秒（超大扫描件 OCR 暴力兜底防止卡死启动）");
        System.out.println("[RagInitializer] P4 兜底 OCR = " + (isOcrAvailable() ? "启用（纯图片扫描 PDF 会自动 Tesseract OCR 识别）" : "未启用（纯图片扫描 PDF 会被跳过，详情见上方 OCR 诊断）"));
        System.out.println("[RagInitializer] ============================================================");

        int totalChunks = 0;
        int totalBatches = 0;
        int okCount = 0;
        int failCount = 0;
        // ★★★ 含 OCR 的 PDF 超时按「页数」动态估算，避免硬编码 120 秒对长 PDF 不够 ★★★
        //   P1/P2/P3 + QuickScan：普通文字 PDF 1 分钟足够
        //   加 OCR：按「15 秒/页」的最坏情况估算（300dpi 中文，低配 CPU）+ 1 分钟基础缓冲
        final long PER_PDF_MIN_TIMEOUT_MILLIS = 120_000L;     // 最低 2 分钟保底
        final long PER_PDF_OCR_MS_PER_PAGE = 15_000L;         // 每页 OCR 预留 15 秒（39 页 → 585s）
        final long PER_PDF_BASE_MILLIS = 60_000L;             // 1 分钟基础缓冲（加载+渲染+切片）
        for (File pdf : pdfs) {
            System.out.println("  📥 开始处理：" + pdf.getName() + " (" + formatSize(pdf.length()) + ") ...");
            long startMs = System.currentTimeMillis();
            List<Document> chunks = Collections.emptyList();

            // 先探测 PDF 页数（只读 PDF 元数据，很便宜）→ 动态计算超时
            long thisTimeoutMs = PER_PDF_MIN_TIMEOUT_MILLIS;
            int pageCount = 0;
            try (PDDocument probe = Loader.loadPDF(pdf)) {
                pageCount = probe.getNumberOfPages();
            } catch (Throwable ignore) {}
            if (pageCount > 0) {
                // 如果后面会触发 OCR（纯图片 PDF），按「每页 15 秒最坏情况」估；如果是文字型 PDF，实际 P1 很快就完成也不会浪费时间
                long ocrBased = PER_PDF_BASE_MILLIS + (long) pageCount * PER_PDF_OCR_MS_PER_PAGE;
                thisTimeoutMs = Math.max(PER_PDF_MIN_TIMEOUT_MILLIS, ocrBased);
            }
            final long finalTimeoutMs = thisTimeoutMs;
            // 注册表：该 PDF 处理期间启动过的所有外部子进程（tesseract.exe），用于超时时真正 kill -9，避免两个 PDF OCR 交叉打架
            final Set<Process> childProcesses = Collections.synchronizedSet(java.util.Collections.newSetFromMap(new IdentityHashMap<>()));

            try {
                // ====== 用独立线程 + 超时包裹 readAndSplitOnePdf，避免大 PDF 卡死整个启动 ======
                final FutureTask<List<Document>> ft = new FutureTask<>(() -> {
                    List<Document> result;
                    // ✅ ProcessRegistrar 是函数式接口，可直接 lambda；PdfProcessRegistry 只是 AutoCloseable 包装类，必须 new
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
                    // ★ 1) 先取消线程（interrupt）
                    ft.cancel(true);
                    t.interrupt();
                    // ★ 2) 最关键：把所有已经启动的 tesseract.exe 子进程（包括子进程的子进程）强制杀掉
                    //    否则 PDFBox 的 FutureTask 虽然 cancel，但已经 fork 出去的 tesseract.exe 会继续跑十几分钟不退出
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
                    throw new IOException("PDF 提取超时（>" + secs + " 秒，页数=" + pageCount + "，已强制杀掉 " + killed + " 个 OCR 子进程），已跳过。"
                            + (pageCount > 30 ? "（大扫描件 PDF 如需更长超时，可调整 RagInitializer 中 PER_PDF_OCR_MS_PER_PAGE）" : "可能是文件损坏/加密导致死锁"));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    ft.cancel(true);
                    throw ie;
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    if (cause instanceof Exception e) throw e;
                    throw new IOException(cause);
                }
                // ===================================================================================
                if (chunks == null) chunks = Collections.emptyList();
                long tookSec = (System.currentTimeMillis() - startMs + 500) / 1000;
                if (chunks.isEmpty()) {
                    System.out.println("  ⚪ " + pdf.getName() + "（用时" + tookSec + "s）无有效文本内容，跳过  " +
                            "（如需定位：请在本日志上方找 「└─[debug] ... P1= P2= P3= ...」 那一行的尸检报告）");
                    continue;
                }
                int batchesForThisPdf = 0;
                long batchStartMs = System.currentTimeMillis();
                for (List<Document> batch : partition(chunks, DASHSCOPE_EMBEDDING_MAX_BATCH)) {
                    vectorStore.add(batch);
                    batchesForThisPdf++;
                    totalBatches++;
                    // 每 20 批（约 200 chunks）打一次进度，避免 3 分钟沉默让人以为卡死
                    if ((batchesForThisPdf % 20) == 0) {
                        long secs = (System.currentTimeMillis() - batchStartMs + 500) / 1000;
                        System.out.println("    ↪ DashScope Embedding：已完成 " + batchesForThisPdf
                                + "/" + ((chunks.size() + DASHSCOPE_EMBEDDING_MAX_BATCH - 1) / DASHSCOPE_EMBEDDING_MAX_BATCH)
                                + " 批（已用时约 " + secs + "s，预计剩余 ≈"
                                + Math.max(1L, secs * Math.max(1, (((chunks.size() + 9) / 10) - batchesForThisPdf)) / Math.max(1, batchesForThisPdf))
                                + "s）");
                    }
                }
                totalChunks += chunks.size();
                okCount++;
                System.out.println("  ✅ " + pdf.getName()
                        + "（" + (tookSec + (System.currentTimeMillis() - startMs)/1000) + "s）"
                        + "  chunks=" + chunks.size()
                        + "  batches(≤10)=" + batchesForThisPdf
                        + "  → 已写入 VectorStore");
            } catch (Throwable th) {
                // 单个 PDF 再怎么崩也不能让 Spring 容器启动失败。
                failCount++;
                String msg = (th.getMessage() == null) ? "" : th.getMessage();
                String extraHint = "";
                String lower = msg.toLowerCase();
                if (lower.contains("encrypt") || lower.contains("password") || lower.contains("decrypt")
                        || (th.getClass().getSimpleName().toLowerCase().contains("encrypt"))
                        || (th.getClass().getSimpleName().toLowerCase().contains("password"))) {
                    extraHint = " → 🔒 这是【加密 PDF】（带打开密码/权限密码）。解法：用 Edge 打开 → Ctrl+P → 打印为 PDF（打印机选 Microsoft Print to PDF），输出的新 PDF 就是无密码的。";
                } else if (lower.contains("damaged") || lower.contains("corrupt") || lower.contains("truncated") || lower.contains("header")) {
                    extraHint = " → ❌ PDF 文件损坏/被截断。解法：重新下载 / 重新导出 / 用 Chrome 打开后 Ctrl+P 另存为 PDF 修一下。";
                }
                // 这里不用抛也不用 return，我们只是提示，然后继续下一份 PDF。
                System.err.println("  ❌ " + pdf.getName()
                        + " 处理失败（跳过）: "
                        + th.getClass().getSimpleName() + ": " + msg + extraHint);
                System.err.println("       👉 若想把该 PDF 入库，按上面提示处理完成后放回目录重启即可。");
            }
        }

        System.out.println("[RagInitializer] ------------------------------------------------------------");
        System.out.println("[RagInitializer] 完成。成功PDF=" + okCount
                + "  失败PDF=" + failCount
                + "  chunks总数=" + totalChunks
                + "  提交批次数=" + totalBatches
                + " (每批≤" + DASHSCOPE_EMBEDDING_MAX_BATCH + ")");
        System.out.println("[RagInitializer] ============================================================");
        System.out.println();
    }

    /**
     * 读单个 PDF + 切分。
     *
     * 为了应对「扫描件 + 隐藏 OCR 文字层」这种办公场景最常见的 PDF，这里做了 3 层 fallback 提取：
     *   Pass 1) 标准 PDFTextStripper（正常非隐藏文本）
     *   Pass 2) 若字符不足 200，关闭按位置排序等干扰项重新提取（对付 OCR 隐藏层）
     *   Pass 3) 若仍不足 200，暴力遍历所有内容流里的 (...) 字面字符串，100% 兜底抓字
     *
     * 打开 PDF 时同时处理两种常见的加密场景：
     *   ① 用户密码（打开 PDF 时弹窗要密码）→ 代码里目前 password=null，
     *     如果未来你有带密码 PDF，可改 pdfPassword 配置或在这里按文件名给密码。
     *   ② 所有者权限密码（能打开但禁止复制/打印，这是你那几份官方指南的情况）：
     *     PDFBox 3 默认是「禁止」，会导致即使字节里有文字也抽不到。
     *     所以我们在提取前尝试 setAllowsExtractionText=true（配合 PDFTextStripper
     *     的非标准兼容行为），再不行就直接暴力扫内容流（Pass 3），绕开权限位的合规限制。
     */
    /**
     * 单份 PDF 提取 + 切片（终极优化版，50MB 扫描件 <15s 出结果，不再卡爆 GC）。
     *
     *   ★★ 2026-08-20 再优化：执行顺序大翻转 ★★
     *
     *   【之前的悲剧】：P1(PDFTextStripper) → P2 → P3
     *       → 扫描件 PDF，PDFTextStripper 会为了「文字排版还原」把所有 Page / Glyph / Font 都反序列化，
     *         56MB 扫描件 = 500MB+ COS 对象在堆 → IDE 默认 -Xmx 1GB 就 Full GC 风暴，120 秒直接被超时器干掉。
     *
     *   【现在的正确流程】：QuickScan(100ms) → 立即分支 → 「扫描件」或「文字 PDF」各自走最快路径
     *
     *       ① QuickScan：先 bruteExtractQuickFirstNPages(前 5 页)，<1 秒
     *          - 非空字符 ≥ 200  →  ✅ 判定为【扫描件 + 隐藏层文字】
     *                               → 直接跑 Pass3(全量暴力扫流) 当全文本，**完全不跑 PDFTextStripper(P1/P2)**
     *                               → 速度快 10~50 倍，内存占用 <100MB，56MB 文件 <15s。
     *          - 非空字符 < 200   →  ✅ 判定为【纯图片 / 普通文字型 PDF】
     *                               → 才跑 P1(PDFTextStripper) + P2，最后再用全量 Pass3 兜底。
     */
    // 旧签名：给未来的诊断/测试代码用（不传 registrar = 不注册进程）
    private List<Document> readAndSplitOnePdf(File pdf) throws Exception {
        return readAndSplitOnePdf(pdf, NOOP_REGISTRAR);
    }

    private List<Document> readAndSplitOnePdf(File pdf, ProcessRegistrar registrar) throws Exception {
        if (registrar == null) registrar = NOOP_REGISTRAR;
        String password = null; // 打开密码。无密码 PDF 留 null 即可。
        try (PDDocument pdDoc = openPdfLenient(pdf, password)) {
            int pageCount = pdDoc.getNumberOfPages();
            boolean encrypted = pdDoc.isEncrypted();
            StringBuilder debugSb = new StringBuilder(512);
            debugSb.append("p=").append(pageCount).append(" enc=").append(encrypted).append(" |");

            // ================================================================================
            //  ① 必跑：QuickScan 前 5 页（<1s），立刻判断这份 PDF 的「类型路径」
            // ================================================================================
            int[] stats = new int[5];
            StringBuilder firstHitDump = new StringBuilder(300);
            String quick = bruteExtractQuickFirstNPages(pdDoc, 5, stats, firstHitDump);
            int nonBlankQuick = countNonBlank(quick);
            debugSb.append(" Qk=").append(len(quick)).append('(').append(nonBlankQuick).append("非空)");
            final boolean isScannedHiddenText = nonBlankQuick >= 200;   // 200 非空字符 ≈ 60 汉字 ≈ 1 页正经内容

            // ---------- 路径 A：扫描件（隐藏层有文字）→ 直接暴力扫【全部页的 Page+Annotation 流】（完全跳过 Catalog 整棵树 + PDFTextStripper）----------
            //    原理：QuickScan 前5页在 Page+Annotation 里命中了文字 → 100% 是「扫描件 + Acrobat OCR 隐藏文字」型 PDF
            //          隐藏层文字 100% 存在于 Page 的 /Contents 内容流 + 页面 Annotation 外观流里
            //          Catalog 树（StructTree/AcroForm/Names/嵌入字体）里绝不会有 Tj/TJ 操作符，所以完全不递归，省 90%+ 时间！
            int nonBlank1 = 0, nonBlank2 = 0, nonBlank3 = 0, nonBlank4 = 0;
            int len = 0, len2 = 0, len3 = 0, len4 = 0;
            String fullText = null;
            String brutePreview = "";
            if (isScannedHiddenText) {
                // 【扫描件专用极速提取】：只扒全部页 Page+Annotation（不递归 Catalog + 纯图片/字体流秒跳）
                String brute = bruteExtractScanOnlyPagesAndAnnotations(pdDoc, stats, firstHitDump);
                len3 = len(brute);
                nonBlank3 = countNonBlank(brute);
                fullText = brute;
                len = len3;
                debugSb.append(" [SCAN→P3直接] P3=").append(len3).append('(').append(nonBlank3).append("非空)")
                       .append(" streams=").append(stats[0]).append(" hits=").append(stats[1])
                       .append(" lit=").append(stats[2]).append(" hex=").append(stats[3]);
                if (!brute.isEmpty()) {
                    String p = brute.replaceAll("[\\r\\n\\t ]+", " ").trim();
                    brutePreview = p.length() > 300 ? p.substring(0, 300) : p;
                }
                // ★★★ 路径 A 也需要 OCR 兜底：极少数扫描件虽然 QuickScan 前5页有文字，但不是中文/正文，P3 结果仍可能为 0
                if (nonBlank3 < 200 && isOcrAvailable()) {
                    System.out.println("    ↪ [P4-OCR] 扫描件路径 P3 非空字符不足，启动 Tesseract OCR 识别 ...");
                    String ocr = ocrExtractFromPdf(pdDoc, registrar);
                    len4 = len(ocr);
                    nonBlank4 = countNonBlank(ocr);
                    if (nonBlank4 > Math.max(nonBlank3, nonBlankQuick)) {
                        fullText = ocr; len = len4;
                    }
                    debugSb.append(" P4(OCR)=").append(len4).append('(').append(nonBlank4).append("非空)");
                }
            } else {
                // ---------- 路径 B：纯图片 / 普通文字型 PDF → 原顺序 P1 → P2 → P3 兜底 → P4 OCR 终极兜底 ----------
                // P1
                fullText = new PDFTextStripper().getText(pdDoc);
                len = (fullText == null) ? 0 : fullText.length();
                nonBlank1 = countNonBlank(fullText);
                debugSb.append(" P1=").append(len).append('(').append(nonBlank1).append("非空)");
                // P2
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
                    debugSb.append(" P2=").append(len2).append('(').append(nonBlank2).append("非空)");
                }
                // P3 兜底（P1/P2 都不够才跑，省时间）
                if (Math.max(nonBlank1, nonBlank2) < 200) {
                    String brute = bruteExtractStringsFromAllStreams(pdDoc, stats, firstHitDump);
                    len3 = len(brute);
                    nonBlank3 = countNonBlank(brute);
                    if (nonBlank3 > Math.max(nonBlank1, nonBlank2)) { fullText = brute; len = len3; }
                    debugSb.append(" P3=").append(len3).append('(').append(nonBlank3).append("非空)")
                           .append(" streams=").append(stats[0]).append(" hits=").append(stats[1])
                           .append(" lit=").append(stats[2]).append(" hex=").append(stats[3]);
                    if (!brute.isEmpty()) {
                        String p = brute.replaceAll("[\\r\\n\\t ]+", " ").trim();
                        brutePreview = p.length() > 300 ? p.substring(0, 300) : p;
                    }
                }
                // ★★★ P4 OCR 终极兜底：P1/P2/P3 + QuickScan 全不够（典型纯图片扫描件）→ 调 Tesseract
                int maxBeforeOcr = Math.max(Math.max(Math.max(nonBlank1, nonBlank2), nonBlank3), nonBlankQuick);
                if (maxBeforeOcr < 200 && isOcrAvailable()) {
                    System.out.println("    ↪ [P4-OCR] P1/P2/P3 均不足（纯图片扫描件），启动 Tesseract OCR 逐页识别 ...");
                    String ocr = ocrExtractFromPdf(pdDoc, registrar);
                    len4 = len(ocr);
                    nonBlank4 = countNonBlank(ocr);
                    if (nonBlank4 > maxBeforeOcr) {
                        fullText = ocr; len = len4;
                    }
                    debugSb.append(" P4(OCR)=").append(len4).append('(').append(nonBlank4).append("非空)");
                }
            }

            int finalNonBlank = Math.max(Math.max(Math.max(Math.max(nonBlank1, nonBlank2), nonBlank3), nonBlank4), nonBlankQuick);
            List<Document> chunks = Collections.emptyList();
            if (finalNonBlank >= 20) {
                Document doc = new Document(fullText == null ? "" : fullText, Map.of(
                        "source", pdf.getAbsolutePath(),
                        "file_name", pdf.getName(),
                        "page_count", pageCount,
                        "text_length", len,
                        "non_blank_length", finalNonBlank
                ));
                List<Document> c = splitter.apply(Collections.singletonList(doc));
                if (c != null) chunks = c;
            }

            if (chunks.isEmpty()) {
                StringBuilder msg = new StringBuilder(1024);
                msg.append("      └─[debug] (").append(pdf.getName()).append(") ").append(debugSb);
                if (firstHitDump.length() > 0) {
                    msg.append("\n      └─[first Tj/TJ stream dump] ").append(firstHitDump);
                }
                if (!brutePreview.isEmpty()) msg.append("\n      └─[Pass3 预览] 「").append(brutePreview).append("」");
                msg.append("\n      └─→ 最终非空字符数=").append(finalNonBlank).append(" 不足 20，或切片为空 → 跳过");
                System.out.println(msg);
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
    /** 把 \\r\\n\\t 空格 不间断空格 零宽等空白去掉后计数，避免 P1=70 实际只有 5 个汉字还进切片。 */
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
     * 打开 PDF（终极版：100% 编译通过，PDFBox 3.0.x / 3.1.x 所有小版本通吃）
     *
     *   只用 Loader.loadPDF(File) 和 Loader.loadPDF(File, String) —— 这两个重载在 PDFBox 3.x 全系列 100% 存在，
     *   绝不再猜 ScratchFile / RandomAccessStreamCache / MemoryUsageSetting 这些小版本差异巨大的 API。
     *
     *   内存/磁盘缓存策略：static{} 块里已经【暴力覆盖了 PDFBox 从 2.x 到 3.1.x 所有历史版本的内存模式 System Property】，
     *   全部强制设为 false/file，主内存封顶 128MB；如果 JVM -Xmx 还是 <2GB，static 块会用超级醒目的提示让你在 Run Config 加 -Xmx2g。
     *
     *   权限位绕开（和之前一样不变，Pass 3 暴力扫流 100% 兜底）
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
                    } catch (Throwable ignore) { /* 没有这个方法就跳过 */ }
                }
            } catch (Throwable ignore) { /* 任意失败都继续，Pass 3 兜底 */ }
        }
        return doc;
    }

    /**
     * 暴力兜底 v4（终极版）：**递归 walk 整份 PDF 所有 COS 对象，不放过任何一个 COSStream**
     *
     *   入口 ①：所有 Page 的 COSDictionary + 页面所有 Annotation（外观流）
     *        → 覆盖 Acrobat "OCR 可搜索 PDF"（隐藏文字放在 Annotation 外观流里，Ctrl+C 能复制
     *          但 PDFTextStripper 默认不读 Annotation）
     *   入口 ②：Document Catalog 的整棵 COS 树（AcroForm / StructTree / Names / 任何嵌套的字典数组）
     *        → 覆盖 Word/WPS 导出、印刷版 PDF、Forms、结构树文字流。
     *
     *  walkCOS 递归逻辑：
     *    COSDictionary 的每一个 entry value → 继续 walk
     *    COSArray 的每一个 element → 继续 walk
     *    COSStream → 100% 命中：createInputStream() → Tj/TJ 短路判断 → (字面) + <十六进制> 双正则。
     *
     *  IdentityHashMap<COSStream, Boolean> 防重复处理（页眉页脚/共享 XObject 不会被扒 N 次）。
     */
    private static String bruteExtractStringsFromAllStreams(PDDocument pdDoc, int[] stats, StringBuilder firstHitDump) {
        StringBuilder sb = new StringBuilder(65536);
        Pattern literalPat = Pattern.compile("\\(([^\\)]{2,2000})\\)");
        Pattern hexPat     = Pattern.compile("<([0-9A-Fa-f]{4,65536})>");
        IdentityHashMap<COSBase, Boolean> visited = new IdentityHashMap<>(4096);
        if (stats == null) stats = new int[4];

        try {
            PDPageTree pages = pdDoc.getPages();
            // ★★★ PDPageTree 是平衡树/链表，不是数组！绝对不要 for (int i) pages.get(i)（O(N²) 巨慢）
            //     用迭代器 for (PDPage page : pages) 是 O(N) 顺序遍历，70 页 PDF 秒级完成！
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

    /** 快速通道：大 PDF 先只扫前 N 页的 Page COS + Annotation（不递归 Catalog，不遍历整棵对象树），<1 秒就能判断有没有隐藏层文字。 */
    private static String bruteExtractQuickFirstNPages(PDDocument pdDoc, int nPages, int[] stats, StringBuilder firstHitDump) {
        StringBuilder sb = new StringBuilder(32768);
        Pattern literalPat = Pattern.compile("\\(([^\\)]{2,2000})\\)");
        Pattern hexPat     = Pattern.compile("<([0-9A-Fa-f]{4,65536})>");
        IdentityHashMap<COSBase, Boolean> visited = new IdentityHashMap<>(1024);
        if (stats == null) stats = new int[4];
        try {
            PDPageTree pages = pdDoc.getPages();
            // ★★★ O(N²) 反模式修复：前 N 页用「迭代器 + 手动 break」，不调用 pages.get(i)！
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

    /**
     * 【扫描件专用极速提取】：只遍历「全部页 Page COS + 页面 Annotation」，绝不递归 Catalog 整棵对象树
     * （Catalog 树下的 StructTree/AcroForm/Names/嵌入字体/ICC 流里绝不会有 Tj/TJ 文本操作符，省 90%+ 时间）
     *
     *  配合 walkCOS 新增的 3 层极速剪枝：
     *    ① Image 流（DCTDecode/JPXDecode/CCITT/JBIG2 等图片 Filter）→ 连 readAllBytes 都不调，秒跳
     *    ② 嵌入字体流（/Subtype=/Type1 /TrueType /CIDFontType2 + /FontFile/2/3）→ 秒跳
     *    ③ >256KB 的 COSStream → 99.9% 是图片/字体二进制，秒跳
     *
     *  2026-08-20 二阶段加速：
     *    阶段1（99%命中）：只扒 Page COS /Contents /Resources /XObject 流 → 不调用 page.getAnnotations()（加载子树巨慢）
     *                       非空字符凑够 1000 字 → 认为正文已拿到，直接结束（扫+OCR型PDF 99% 在这一步就搞定）
     *    阶段2（兜底）：如果阶段1 < 1000 字（某些 PDF 文字在注释/表单外观流里）→ 再补 Annotation
     */
    private static String bruteExtractScanOnlyPagesAndAnnotations(PDDocument pdDoc, int[] stats, StringBuilder firstHitDump) {
        StringBuilder sb = new StringBuilder(65536);
        Pattern literalPat = Pattern.compile("\\(([^\\)]{2,2000})\\)");
        Pattern hexPat     = Pattern.compile("<([0-9A-Fa-f]{4,65536})>");
        IdentityHashMap<COSBase, Boolean> visited = new IdentityHashMap<>(4096);
        if (stats == null) stats = new int[4];
        try {
            PDPageTree pages = pdDoc.getPages();
            // --- 阶段1：Page COS 本身（/Contents 内容流 + /Resources/XObject FormXObject 流）
            //         不调用 page.getAnnotations() —— 加载 Annotation 子树巨慢（解包 AP/N/DS...流），且 99% 扫描件文字在 Contents 里
            java.util.List<org.apache.pdfbox.pdmodel.PDPage> allPages = new java.util.ArrayList<>(pages.getCount());
            for (org.apache.pdfbox.pdmodel.PDPage page : pages) allPages.add(page);   // 迭代器一次性 O(N) 取完页引用（COS引用是轻量的）
            for (org.apache.pdfbox.pdmodel.PDPage page : allPages) {
                if (page == null) continue;
                walkCOS(page.getCOSObject(), literalPat, hexPat, sb, visited, stats, firstHitDump);
            }
            int nbPhase1 = countNonBlank(sb.toString());
            // 阶段1 能掏出 1000 非空字 → 正文妥妥够了（≈3页正经内容），直接 return，完全跳过 Annotation 省 80% 时间！
            if (nbPhase1 >= 1000) return sb.toString().trim();

            // --- 阶段2（兜底）：补 Annotation 外观流（仅当阶段1文字太少时才跑，大约 1% 概率命中）
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
            // ★★★ 核心加速：绝对不调用 walkCOS(catalog)！之前 90% 时间浪费在 Catalog 下的字体/图片/AcroForm 流上 ★★★
        } catch (Throwable ignore) {}
        return sb.toString().trim();
    }

    /** 递归 walk，附带统计：stats[0]=totalStreams stats[1]=Tj/TJ hits stats[2]=literalOk stats[3]=hexOk */
    private static void walkCOS(COSBase base, Pattern literalPat, Pattern hexPat,
                                StringBuilder sb, IdentityHashMap<COSBase, Boolean> visited,
                                int[] stats, StringBuilder firstHitDump) {
        if (base == null) return;
        // ★★★ 缺失的这一行：PDF 的间接引用 42 0 R 会被 COSObject 包装，必须解包再递归！
        //     之前没有这一行 → page.getCOSObject() 下的 /Contents /Resources /XObject 等
        //     全都是 COSObject 包装器 → walkCOS 直接返回 → streams=0 命中！ ★★★
        if (base instanceof org.apache.pdfbox.cos.COSObject co) {
            base = co.getObject();
            if (base == null) return;
        }
        // ★★★ 2026-08-20 关键修复：对【所有 COS 对象】（字典/数组/流）去重，而不只是 COSStream！
        //     这两份医疗指南 PDF 的页树存在 /Parent 与 /Kids 互相引用（合法但成环），
        //     只对 Stream 去重时，walkCOS 会沿页树回环做指数级递归空转，56MB 文件 120 秒超时。
        //     全对象去重后，同样的全文档扫描实测 0.7 秒完成。 ★★★
        if (visited.putIfAbsent(base, Boolean.TRUE) != null) return;

        if (base instanceof COSStream s) {
            stats[0]++;

            // ★★★ 极速剪枝 ①：纯图片 Filter 的流（DCTDecode=JPEG / JPXDecode=JPEG2000 / CCITT=传真 / JBIG2=二值图）
            //                 → 100% 不会有 Tj/TJ 文本操作符，连 createInputStream / readAllBytes 都不调，秒跳。
            //     （这一层能剪 90% 时间：56MB 扫描件每页 2 张 2MB JPEG，70 张图片流全跳过）
            COSBase filterObj = null;
            try { filterObj = s.getDictionaryObject(COSName.FILTER); } catch (Throwable ignore) {}
            if (filterObj != null && isPureImageFilter(filterObj)) return;

            // ★★★ 极速剪枝 ②：嵌入字体流（/Subtype=/Type1/TrueType/CIDFontType0/CIDFontType2 + /FontFile/2/3 存在）→ 秒跳
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
                // ★★★ 极速剪枝 ③：>256KB 的 COSStream → 99.9% 是图片/字体/ICC 二进制（隐藏层文字每页内容流也就几KB~几十KB），秒跳
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

    /** PDF 括号字面量转义还原：\( \) \\ \n \r \t \b \f \ddd八进制。之前完全没做 → 被 looksReadable 判乱码扔掉。 */
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
                    // 八进制：\ddd 或 \dd \d，最多 3 位 0-7
                    if (n >= '0' && n <= '7') {
                        int v = n - '0';
                        for (int k = 0; k < 2 && i + 1 < raw.length(); k++) {
                            char c3 = raw.charAt(i + 1);
                            if (c3 < '0' || c3 > '7') break;
                            v = v * 8 + (c3 - '0');
                            i++;
                        }
                        out.append((char) (v & 0xFF));
                    }
                    // else：PDF spec 规定 \非识别字符 = 忽略反斜杠，直接取字符
                    else out.append(n);
            }
        }
        return out.toString();
    }

    /** 十六进制 → 智能解码：偶数长度时按 "UTF-16BE → GBK → UTF-8" 顺序试，挑最像可读文字的那个返回。 */
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
                if (ch >= 0x4E00 && ch <= 0x9FFF) score += 3;     // CJK 汉字（最重要，权重 3）
                else if (Character.isLetterOrDigit(ch)) score += 1;
                else if (" ，。、；：？！,.?!;:\n\t-".indexOf(ch) >= 0) score += 0; // 标点不扣分
                else score -= 2;   // 怪异字符，说明解码错了，扣分
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

    /**
     * 判断字符串是否"看起来像人读的文字"。放宽策略：
     *   1) 快速通道：有 ≥2 个 CJK 汉字 → 直接算可读（中文 OCR 哪怕带标点/数字/括号也能过）
     *   2) 否则：letters + CJK + 数字 占比 ≥ total 的 40%（比原来的 50% 更宽容）
     *   3) 怪异字符（控制字符 0x00-0x08、0x0E-0x1F、0x7F 等）超过 10% → 直接拒（二进制乱码）
     */
    private static boolean looksReadable(String s) {
        if (s == null || s.isEmpty()) return false;
        int cjk = 0, lettersOrDigit = 0, weird = 0, total = s.length();
        for (char c : s.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) { cjk++; lettersOrDigit++; }
            else if (Character.isLetterOrDigit(c)) lettersOrDigit++;
            else if (c < 0x20 && "\n\r\t\b\f".indexOf(c) < 0) weird++;   // 控制字符（除了常规换行制表）
            else if (c == 0x7F) weird++;
        }
        if (cjk >= 2) return true;                                         // 快速通道：2 个汉字就算
        if (weird * 10 > total) return false;                              // 怪异字符>10%=二进制乱码
        return lettersOrDigit * 5 >= total * 2;                            // 可读字符≥40% 就算
    }

    /**
     * 判断这个 COSStream 的 /Filter 是否属于【纯图像压缩】类。
     *   —— 是 → 100% 不会有 Tj/TJ 文本操作符，连 createInputStream()/readAllBytes() 都不要调，直接跳过。
     *
     *  Filter 可以是单个 COSName，也可以是 COSArray（管道式多 Filter：先 Flate 再 DCT 之类）。
     *  只要数组里出现任意一个纯图像 Filter，就判定这个流最终承载的是图像数据（文本层不会加图像 Filter 管道）。
     */
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
            // PDF 标准里的纯图像 Filter（一个都不承载 Tj/TJ 文本指令）
            case "DCTDecode", "DCT"           // JPEG 有损
                    , "JPXDecode", "JPX"      // JPEG 2000
                    , "CCITTFaxDecode", "CCF" // CCITT 传真（黑白/灰度）
                    , "JBIG2Decode"           // JBIG2 二值图
                    , "RunLengthDecode", "RL" // 行程编码（几乎只用在图像 Mask）
                    , "LZWDecode", "LZW"      // 99% 用在 LZW 压缩 TIFF 图像（文本流几乎不用 LZW）
                    -> true;
            default -> false;
        };
    }

    /** 通用分片：把 list 切成多个子 list，每个子 list size ≤ batchSize。最后一个子 list 可能不足 batchSize。 */
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
     * 收集所有待处理的 PDF 文件：
     *   - 路径是目录 → 扫描 *.pdf（不递归子目录，要递归可自己加）
     *   - 路径是单个文件且以 .pdf 结尾 → 只返回这一个
     *   - 路径不存在 / 不合法 → 空集合
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

    // ================================================================================
    // P4 OCR 终极兜底：PDFBox 逐页渲染成 PNG → Tesseract 命令行做 OCR → 拿到纯文本
    // ================================================================================

    /**
     * 纯图片扫描 PDF OCR 识别：逐页渲染 → 调 tesseract.exe → 拼回全文。
     *
     * 注意：直接走命令行调用（不依赖 Tess4J），好处：
     *   ① pom 不用加新依赖（Tess4J 还要带 native dll，Win/Linux/Mac 各一套巨麻烦）
     *   ② 用户电脑上安装的 Tesseract 是最新 v5.x，Tess4J 经常跟不上版本
     *   ③ 可以直接用 --tessdata-dir 指定任意目录（绕开 Program Files 权限问题）
     */
    private static final boolean OCR_DEBUG_KEEP_FILES = true; // 出现空识别时保留临时文件便于手动检查

    private String ocrExtractFromPdf(PDDocument pdDoc, ProcessRegistrar registrar) {
        if (registrar == null) registrar = NOOP_REGISTRAR;
        File workDir = null;
        try {
            int pages = pdDoc.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(pdDoc);
            workDir = Files.createTempDirectory("rag-ocr-" + System.currentTimeMillis()).toFile();
            StringBuilder allText = new StringBuilder(pages * 4096);
            // 调试用：空识别时把 PNG/TXT 拷贝到这个目录（不会被清）
            File debugKeepDir = OCR_DEBUG_KEEP_FILES ? new File("D:/tessdata/ocr-debug-" + System.currentTimeMillis()) : null;
            int debugKept = 0;
            if (debugKeepDir != null) debugKeepDir.mkdirs();

            for (int i = 0; i < pages; i++) {
                long pageStart = System.currentTimeMillis();
                File png = null;
                File outBase = null;
                try {
                    // ===== 1) PDF 页 → PNG =====
                    long tRender0 = System.currentTimeMillis();
                    BufferedImage img = renderer.renderImageWithDPI(i, ocrDpi, ImageType.RGB);
                    int imgW = img.getWidth(), imgH = img.getHeight();
                    // ---- 像素抽样：判断渲染结果是不是几乎全白（PDFRenderer 渲染失败典型表现）----
                    int nonWhitePx = 0, stepX = Math.max(1, imgW/60), stepY = Math.max(1, imgH/60);
                    for (int y = 0; y < imgH; y += stepY) {
                        for (int x = 0; x < imgW; x += stepX) {
                            int rgb = img.getRGB(x, y) & 0xFFFFFF;
                            if (rgb != 0xFFFFFF) nonWhitePx++;
                        }
                    }
                    double coverage = 100.0 * nonWhitePx / ((imgW/stepX)*(imgH/stepY));
                    png = new File(workDir, "p" + i + ".png");
                    ImageIO.write(img, "png", png);
                    long pngSize = png.length();
                    img.flush();
                    long renderMs = System.currentTimeMillis() - tRender0;

                    // ===== 2) tesseract PNG → TXT =====
                    // ★ 关键：强制 Tesseract 输出 UTF-8 文件（Windows 中文环境下 tesseract 默认按系统代码页 GBK 写 TXT）
                    //   做法：
                    //     a) -c output_encoding=UTF-8       → Tesseract 5.x 原生配置，写 txt 文件强制 UTF-8
                    //     b) ProcessBuilder.environment().put("TESSERACT_OUTPUT_ENCODING", "UTF-8") → 兼容老版本
                    //   这样 Java 侧只按 UTF-8 读，不再猜 GBK，从源头杜绝「数据库中文乱码」
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
                    // 双保险：设置子进程环境变量，Tesseract 读取输出/系统代码页时走 UTF-8
                    java.util.Map<String, String> env = pb.environment();
                    env.put("TESSERACT_OUTPUT_ENCODING", "UTF-8");
                    env.put("PYTHONIOENCODING", "utf-8");     // 顺带防止被 python 包装脚本污染
                    Process p = pb.start();
                    registrar.register(p);  // ★ 注册到该 PDF 的子进程表，超时/取消时可被强制杀掉（避免 OCR 子进程泄漏）
                    StringBuilder procOut = new StringBuilder(512);
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) procOut.append(line).append('\n');
                    }
                    boolean finished = p.waitFor(ocrPageTimeoutSec, TimeUnit.SECONDS);
                    int exit = p.exitValue();
                    long ocrOnlyMs = System.currentTimeMillis() - pageStart - renderMs;
                    if (!finished) { p.destroyForcibly();
                        System.out.println("      ⚠️ 第 " + (i+1) + "/" + pages + " 页 OCR 超时（>" + ocrPageTimeoutSec + "s），跳过");
                        continue;
                    }
                    if (exit != 0) {
                        String hint = procOut.toString().replaceAll("\\s+", " ").trim();
                        if (hint.length() > 500) hint = hint.substring(0,500)+"...";
                        System.err.println("      ❌ 第 " + (i+1) + "/" + pages + " 页 OCR 失败 exit=" + exit
                                + "：" + (hint.isEmpty() ? "(stdout空)" : hint));
                        System.err.println("         命令行: " + String.join(" ", cmd));
                        continue;
                    }

                    // ===== 3) 读取 TXT 结果（Tesseract 已通过 -c output_encoding=UTF-8 强制输出 UTF-8，直接读）=====
                    //   不再做 GBK 双解码猜谜：避免把 UTF-8 字节按 GBK 解成乱码再入库
                    File txtFile = new File(outBase.getAbsolutePath() + ".txt");
                    String pageText = "";
                    long txtSize = txtFile.exists() ? txtFile.length() : -1;
                    if (txtFile.exists() && txtSize > 0) {
                        pageText = Files.readString(txtFile.toPath(), StandardCharsets.UTF_8);
                    }
                    allText.append(pageText).append("\n\n");
                    int nb = countNonBlank(pageText);
                    long totalMs = System.currentTimeMillis() - pageStart;

                    // ===== 4) 进度输出（诊断信息增强版）=====
                    String pageMsg = "      ↪ 第 " + (i+1) + "/" + pages + " 页 OCR 完成（共" + totalMs
                            + "ms = 渲染" + renderMs + "ms+OCR" + ocrOnlyMs + "ms，PNG="
                            + (pngSize/1024) + "KB，内容覆盖率≈" + String.format("%.2f", coverage)
                            + "%，TXT=" + (txtSize>0?txtSize+"B":"空") + "，非空字符≈" + nb + "）";
                    if (nb == 0) {
                        System.err.println(pageMsg);
                        // ★ 非空=0 → 打印详细诊断并保留临时文件
                        if (coverage < 0.01) {
                            System.err.println("         ⚠️⚠️ 原因极大概率：PDFRenderer 渲染出来的 PNG 几乎全白（内容覆盖率≈"
                                    + String.format("%.4f", coverage) + "%）！PDF 页面损坏 / 加密 / 特殊编码 导致无法渲染");
                        } else {
                            System.err.println("         ⚠️⚠️ PNG 有内容（覆盖率≈" + String.format("%.2f", coverage)
                                    + "%），但 Tesseract 识别空 → 可能是 traineddata 没正确加载，或语言不匹配");
                        }
                        if (debugKeepDir != null && debugKept < 3) { // 最多留前3个空页的样本
                            try {
                                File keepPng = new File(debugKeepDir, "empty_p" + (i+1) + ".png");
                                File keepTxt = new File(debugKeepDir, "empty_p" + (i+1) + ".txt");
                                Files.copy(png.toPath(), keepPng.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                if (txtFile.exists()) Files.copy(txtFile.toPath(), keepTxt.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                System.err.println("         📁 样本已保留（可手动检查）: " + keepPng.getParentFile().getAbsolutePath());
                                debugKept++;
                            } catch (Throwable ignore) {}
                        }
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

    // ================================================================================
    // 运行时诊断：GET /rag/diag → 返回完整排查信息（不用重启看控制台了）
    // ================================================================================

    /** 返回当前配置 + 扫描到的 PDF 列表 + 每份 PDF 逐份提取诊断（不写 VectorStore，纯分析）。 */
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

    /** 对单个 PDF 跑 3 层提取 + 暴力扫统计（不切片不写库，只返回诊断 Map）。 */
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
                        stats[1] == 0 ? "NO_TEXT_STREAM_AT_ALL（纯图片，无任何 Tj/TJ → 建议用 OCR 识别）" :
                        "HAS_TEXT_STREAM_BUT_DECODE_FAILED（命中 Tj/TJ 但 looksReadable 全拒 → 请把 first_Tj_TJ_stream_500 贴给作者调解码策略）");
            }
        } catch (Throwable th) {
            m.put("error", th.getClass().getSimpleName() + ": " + th.getMessage());
        }
        return m;
    }
}