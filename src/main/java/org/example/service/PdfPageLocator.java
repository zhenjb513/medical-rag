package org.example.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 块号 → 第几页。只读 PDF：不写库、不重灌、不调模型。
 *
 * <p>为什么能无损做：入库时整篇正文是一次性抽出来的（{@link RagInitializer} 的 P1 路径），每个块是这条文本流的
 * 连续子串；而「按页抽 → 压掉空白 → 拼接」和「整篇抽 → 压掉空白」得到的是同一个串，所以拿库里已有的块文字
 * 按页边界一映就知道它落在哪几页，库里的正文、块号、切法一个字节都不用动。
 *
 * <p>两套页码都要给，别混（务必转告看结果的人）：
 * <ul>
 *   <li><b>PDF 物理页序</b>：阅读器从 1 开始数页数数出来的第几页，一定能给。</li>
 *   <li><b>页脚印码</b>：杂志/书上角上印的那个页码，对着纸质件翻的就是它。这个只能「认」出来：
 *       逐页看页眉页脚那几行的数字，若整份文件算出的（印码 − 物理页）是同一个数，就认为规律成立并换算；
 *       对不上就如实说认不出，绝不猜。指南前面有封面、版权页、目录，印码一般从正文才起算，所以这个差值通常很大
 *       （实测「国家基层」PDF 第 1 页印的是 836，差 835）。</li>
 * </ul>
 *
 * <p>「对不上」是正常结果而不是 bug：走 OCR 兜底或暴力 COS 流入库的扫描件，库里正文不是 PDFTextStripper 抽的，
 * 按页再抽一遍自然对不上；这时如实回定位不到，绝不猜页。文件在入库后被改动过也是同样这句。
 */
public final class PdfPageLocator {

    private PdfPageLocator() {}

    /**
     * 页脚印码没定下来时的偏移哨兵。不能用 0 当哨兵——「印码 == 物理页」本身是合法规律，
     * 拿 0 表示「认不出」会把这种文件误报成没有页码。
     */
    public static final int NO_OFFSET = Integer.MIN_VALUE;

    /**
     * 至少要这么多页认出印码，才敢把那个差值当成整份文件的规律。
     * 阈值是这么定的：3 页以下碰上巧合一致的概率太高（例如正文起始页连号），宁可只给物理页序。
     */
    static final int MIN_DETECTED_PAGES = 5;

    /** 页眉行行首的印码，如「836 中国循环杂志 2025 年 9 月…」「837中国循环杂志…」（后跟空格或直接跟汉字）。 */
    private static final Pattern LEADING = Pattern.compile("^(\\d{1,4})(?![.\\-/])");
    /** 中文期刊的「· 221 ·」，实测按默认参数抽出来是行尾的「·· 221」。 */
    private static final Pattern DOTTED = Pattern.compile("[·•\\-–—]{1,2}\\s*(\\d{1,4})\\s*[·•\\-–—]{0,2}$");
    /** 页脚一个裸数字，WHO 那份正文是这样。 */
    private static final Pattern BARE = Pattern.compile("^(\\d{1,4})$");

    /**
     * 定位结果。found=false 时页号无意义，reason 说明原因；pageCount 是这份 PDF 的总页数，
     * 用来让人判断「第 12 页」是相对多少页而言。
     *
     * <p>printedOffset == {@link #NO_OFFSET} 表示这份文件的页脚印码认不准（原因在 printedNote），
     * 这时绝不输出印码；否则印码 = 物理页 + printedOffset，可能算出 0 或负数——那说明这块落在
     * 印码起算之前（封面、目录那几页），describe() 会如实这么说。
     */
    public record PageSpan(boolean found, int startPage, int endPage, int pageCount,
                           int printedOffset, String printedNote, String reason) {}

    /** 一份 PDF 读盘一次的结果：压平正文用来对文字，印码规律用来把物理页换算成书上那一页。 */
    record Pages(List<String> pageFlat, int printedOffset, String printedNote, int detectedPages, int ambiguousPages) {}

    /** 纯逻辑那一半：给定每页压平的正文和一块文字，算它跨了哪几页（页号从 1 开始），顺带带上印码规律。 */
    static PageSpan spanOf(List<String> pageFlat, String chunkText, int printedOffset, String printedNote) {
        int pageCount = pageFlat == null ? 0 : pageFlat.size();
        String needle = flat(chunkText);
        if (needle.isEmpty()) {
            return miss("块正文是空的", pageCount, printedOffset, printedNote);
        }
        if (pageCount == 0) {
            return miss("这份 PDF 一页文字都没抽到", pageCount, printedOffset, printedNote);
        }
        StringBuilder joined = new StringBuilder(needle.length() * 2);
        int[] pageStart = new int[pageCount + 1];
        for (int i = 0; i < pageCount; i++) {
            pageStart[i] = joined.length();
            joined.append(pageFlat.get(i));
        }
        pageStart[pageCount] = joined.length();

        String doc = joined.toString();
        int pos = doc.indexOf(needle);
        if (pos < 0) {
            return miss("库里正文和磁盘上这份 PDF 现在抽出来的文字对不上（多为扫描件走了 OCR／暴力COS流入库，或文件在入库后被改动）",
                    pageCount, printedOffset, printedNote);
        }
        return new PageSpan(true, pageOf(pageStart, pos), pageOf(pageStart, pos + needle.length() - 1),
                pageCount, printedOffset, printedNote, null);
    }

    /** 压平后的下标属于第几页：找最后一个「起始下标 &lt;= offset」的页。空页起始下标与后一页相同，自然归到后一页。 */
    private static int pageOf(int[] pageStart, int offset) {
        int lo = 0;
        int hi = pageStart.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (pageStart[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo + 1;
    }

    /**
     * 读盘那一半：按页抽正文、清洗、压空白，再交给 {@link #spanOf}。
     * 结果是纯文字比对，所以不调任何模型；每份文件只算一次（见 {@link #pagesCached}）。
     */
    public static PageSpan locate(File pdf, String chunkText) {
        if (pdf == null || !pdf.isFile()) {
            String where = pdf == null || pdf.getPath().isBlank() ? "<库里没记 source>" : pdf.getPath();
            return miss("本地读不到这份 PDF（库里的 source 指向的是当时那台机器的路径），页码只能人工翻：" + where,
                    0, NO_OFFSET, null);
        }
        Pages pages;
        try {
            pages = pagesCached(pdf);
        } catch (IOException | RuntimeException e) {
            return miss("这份 PDF 抽不动：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()), 0, NO_OFFSET, null);
        }
        return spanOf(pages.pageFlat(), chunkText, pages.printedOffset(), pages.printedNote());
    }

    /**
     * 逐页抽取：默认参数的 {@link PDFTextStripper}（与入库 P1 路径同一套，否则压平后的串和整篇抽的不是一个串），
     * 顺手用同一页的原文认印码——不额外再抽第二遍。
     */
    static Pages load(File pdf) throws IOException {
        List<String> flatPages = new ArrayList<>();
        List<Set<Integer>> candidates = new ArrayList<>();
        try (PDDocument doc = RagInitializer.openPdfLenient(pdf, null)) {
            int pageCount = doc.getNumberOfPages();
            for (int i = 1; i <= pageCount; i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = RagInitializer.sanitizeExtractedText(stripper.getText(doc));
                flatPages.add(flat(text));
                candidates.add(printedCandidates(text));
            }
        }
        return withOffset(candidates, flatPages);
    }

    /** 把每页的印码候选汇总成一个规律（见 {@link #printedOffsetOf}），装配成 {@link Pages}。 */
    private static Pages withOffset(List<Set<Integer>> candidates, List<String> flatPages) {
        int detected = 0;
        int ambiguous = 0;
        for (Set<Integer> c : candidates) {
            if (c.size() == 1) {
                detected++;
            } else if (c.size() > 1) {
                ambiguous++;
            }
        }
        int offset = printedOffsetOf(candidates, flatPages.size());
        String note = offset == NO_OFFSET ? printedReason(candidates, flatPages.size(), detected) : null;
        return new Pages(flatPages, offset, note, detected, ambiguous);
    }

    /**
     * 一页里可能被当成印码的数字候选。只看页眉页脚那一行的形状，且只碰每页头两行 + 尾两行：
     * 正文里的数字（剂量、血压值、表格里的 2.6）绝不能进候选，否则规律会被自己的正文带歪。
     *
     * <p>返回多个值代表这一页认不准（例如页眉写着「838 中国循环杂志…（总第 327 期）」而页脚另有数字），
     * 调用方按「歧义页」跳过，不强行挑一个。
     */
    static Set<Integer> printedCandidates(String pageText) {
        Set<Integer> out = new LinkedHashSet<>();
        List<String> lines = nonBlankLines(pageText);
        if (lines.isEmpty()) {
            return out;
        }
        Matcher leading = LEADING.matcher(lines.get(0));
        if (leading.find()) {
            out.add(Integer.parseInt(leading.group(1)));
        }
        for (String line : lines.subList(0, Math.min(2, lines.size()))) {
            addDotted(line, out);
        }
        for (String line : lines.subList(Math.max(0, lines.size() - 2), lines.size())) {
            addDotted(line, out);
        }
        Matcher bare = BARE.matcher(lines.get(lines.size() - 1));
        if (bare.matches()) {
            out.add(Integer.parseInt(bare.group(1)));
        }
        return out;
    }

    private static void addDotted(String line, Set<Integer> out) {
        Matcher m = DOTTED.matcher(line);
        if (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
    }

    private static List<String> nonBlankLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * 印码规律：把所有「认得准」的页拿来算（印码 − 物理页），只有一个取值才算成立。
     *
     * <p>为什么要整份一致才认：单页认出一个数字毫无意义，页码恰恰是最容易撞上正文数字的东西。
     * 实测这条件正好把四类文件分开——三份中文期刊每页一致（+835／+220／+999），
     * WHO 那份前置页用罗马数字另起一套编号，算出来好几个取值，于是不给印码。
     * 认不出只是少一行提示，物理页码照给；认错则会让人翻到错的那一页，危害大得多。
     */
    static int printedOffsetOf(List<Set<Integer>> candidates, int pageCount) {
        if (pageCount <= 0 || candidates == null || candidates.size() != pageCount) {
            return NO_OFFSET;
        }
        Set<Integer> offsets = new TreeSet<>();
        int detected = 0;
        for (int i = 0; i < pageCount; i++) {
            Set<Integer> c = candidates.get(i);
            if (c.size() != 1) {
                continue;
            }
            offsets.add(c.iterator().next() - (i + 1));
            detected++;
        }
        return detected >= MIN_DETECTED_PAGES && offsets.size() == 1 ? offsets.iterator().next() : NO_OFFSET;
    }

    /** 认不出印码时，如实说清是「样本太少」还是「规律打架」——前者可能是这份文件本来就没印页码。 */
    private static String printedReason(List<Set<Integer>> candidates, int pageCount, int detected) {
        if (detected == 0) {
            return "这份 PDF 一页页码都没认出来（可能压根没印页码，也可能是扫描件）";
        }
        if (detected < MIN_DETECTED_PAGES) {
            return "只从 " + detected + " 页认出印码，不足 " + MIN_DETECTED_PAGES + " 页，不敢当成规律";
        }
        Set<Integer> offsets = new TreeSet<>();
        for (int i = 0; i < pageCount; i++) {
            Set<Integer> c = candidates.get(i);
            if (c.size() == 1) {
                offsets.add(c.iterator().next() - (i + 1));
            }
        }
        StringBuilder sb = new StringBuilder("各页认出的印码算出好几个偏移（");
        int shown = 0;
        for (int o : offsets) {
            sb.append(shown++ == 0 ? "" : "、").append(o > 0 ? "+" + o : o);
            if (shown >= 4) {
                sb.append("…");
                break;
            }
        }
        return sb.append("），说明页码另起过编号或位置不固定，只给物理页序").toString();
    }

    /**
     * 按页压平的正文缓存，键含路径 + 修改时间 + 字节数，所以文件被换掉不会命中旧结果。
     * 只留 8 份：这是人工核对口子，一次会话就查几份文件；一份压平正文几百 KB，再多就是白白堆在堆里，
     * 等几千份文件的规模真来了，该做的是把映射落库而不是加大缓存。
     */
    private static final int CACHE_MAX = 8;
    private static final Map<String, Pages> CACHE = new LinkedHashMap<>();

    private static synchronized Pages pagesCached(File pdf) throws IOException {
        String key = pdf.getAbsolutePath() + '|' + pdf.lastModified() + '|' + pdf.length();
        Pages cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Pages pages = load(pdf);
        while (CACHE.size() >= CACHE_MAX) {
            var oldest = CACHE.entrySet().iterator();
            oldest.next();
            oldest.remove();
        }
        CACHE.put(key, pages);
        return pages;
    }

    private static PageSpan miss(String reason, int pageCount, int printedOffset, String printedNote) {
        return new PageSpan(false, 0, 0, pageCount, printedOffset, printedNote, reason);
    }

    /** 压掉所有空白：PDF 抽取会在中文字之间塞空格，不压会假阴性（与 {@code Bm25IndexService.flat} 同一口径）。 */
    private static String flat(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    /** 给人看的那一行。跨页就写页范围，别只写起始页让人以为答案在一页里。 */
    public static String describe(PageSpan span) {
        if (span == null || !span.found()) {
            return "定位不到：" + (span == null ? "未知原因" : span.reason());
        }
        boolean samePage = span.startPage() == span.endPage();
        StringBuilder sb = new StringBuilder();
        sb.append(samePage ? "第 " + span.startPage() + " 页"
                : "第 " + span.startPage() + "–" + span.endPage() + " 页（这块跨页）");
        sb.append("（PDF 物理页序，全 ").append(span.pageCount()).append(" 页");
        if (span.printedOffset() == NO_OFFSET) {
            sb.append("；印在页上的页码认不出：").append(span.printedNote());
        } else {
            int printedStart = span.startPage() + span.printedOffset();
            int printedEnd = span.endPage() + span.printedOffset();
            if (printedEnd < 1) {
                // 别说「这些页没印页码」：WHO 那份前置页印的是罗马数字，只是不按这套阿拉伯编号
                sb.append("；这块整个在阿拉伯页码起算之前（封面、目录、前言那几页另起编号，不在这个规律里）");
            } else if (printedStart < 1) {
                // 前置页与正文交界上的块（实测 WHO #7 覆盖物理 10–13 页）：尾巴已经进了正文页码，
                // 只说「起算之前」会把这半截信息抹掉
                sb.append("；这块从头几页（另起编号的前置页）一直写到页码起算之后，末尾落在页上印的 ")
                        .append(printedEnd);
            } else {
                sb.append("；页上印的是 ").append(samePage ? String.valueOf(printedStart)
                        : printedStart + "–" + printedEnd);
            }
        }
        return sb.append("）").toString();
    }
}
