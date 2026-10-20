package org.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 块号 → 第几节。和页码一样是<b>读时现算</b>：不写库、不重灌、不调模型。
 *
 * <p>能这么做的原因与 {@link PdfPageLocator} 同源：库里每块是整篇正文流里的一段连续文字，
 * 只要把整篇按「同一套清洗 + 压掉空白」重拼一遍，再抽一遍章节标题（标题也来自同一遍抽取），
 * 拿块的起止下标一对就知道它归在哪一个标题底下。
 *
 * <p>三套编号体系都要认（实测四份指南正好各用一套）：
 * <ul>
 *   <li>数字层级：{@code 5.4.3.2 有合并症的高血压药物治疗方案}（国家基层那份）；</li>
 *   <li>中文序号：{@code 一、概述} {@code （二）支持证据}（血脂、一级预防那两份）；</li>
 *   <li>英文 Title Case：{@code 6.1 Guideline recommendations}（WHO 那份，目录行靠行尾「制表符+页码」剔掉）。</li>
 * </ul>
 *
 * <p><b>为什么必须加编号连续性这道闸</b>：只看行首形状会把剂量和化验值当章节。实测宽松规则量出的
 * 「32.9% 的块有数字章节号」几乎全是假的——{@code 4.9 mmol/L}、{@code 12.5 mg}、{@code 5 g食盐)} 都是
 * 「数字 + 后面有字」这个形状。所以规矩是两条：①行首 + 后面得像个标题词（含中文，或英文单词），
 * 且最前面的拉丁串不能是单位；②编号必须沿大纲单调（同层递增／往下一层要接得住上一条／往回卷要祖先层递增）。
 *
 * <p><b>抽出来的顺序不一定等于阅读顺序</b>：双栏和表格排版会让 {@code 4.2.2} 出现在 {@code 4.1.2} 之前
 * （实测国家基层那份就是这样）。连续性过滤会把这种串拒掉，被拒的编号若正好落在某个块里，
 * 那个块<b>整行不给章节号</b>，只说「顺序存疑」的原因：实测那 4 块（该文件 10~13，双栏流程图）
 * 按位置配出来的号是错的（写的是 {@code 4.1.1.2}，正文其实出自 {@code 4.2.2} 诊断流程），
 * 给出去就等于让人翻错那一节——和「页码对不上就明说」同一个纪律。
 */
public final class SectionIndex {

    private SectionIndex() {}

    /**
     * 一份文件至少认出这么多个连贯的章节标题，才承认「这份文件有可用的编号规律」。
     * 定 3 的理由：一两个标题太容易是撞出来的正文数字，撑不起整份文件的归属判断。
     */
    static final int MIN_HEADINGS = 3;

    /** 数字层级标题：行首 {@code 5} / {@code 5.4} / {@code 5.4.3.2}，后面跟着标题文字。 */
    private static final Pattern NUM_HEAD = Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,3}){0,4})\\s+(\\S.{1,30})$");
    /** 中文序号标题：{@code 一、概述} 或 {@code （二）支持证据}。 */
    private static final Pattern CN_HEAD = Pattern.compile("^([一二三四五六七八九十]{1,3}、|（[一二三四五六七八九十]{1,3}）)\\s*(\\S.{1,30})$");
    /** 目录行：标题后面是制表符 + 页码。这种行不是正文里的小节标题，整条跳过（WHO 那份有 43 条）。 */
    private static final Pattern TOC_TAIL = Pattern.compile("\\t\\s*\\d{1,4}\\s*$");
    private static final Pattern LATIN_RUN = Pattern.compile("^[A-Za-zµμ]+");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern CN_UNIT = Pattern.compile("^(片|粒|支|盒|次|岁|周|天|日|小时|分钟|月|年|倍|人|例|种|项|元|米|个|条|份)");

    /**
     * 单位表（小写比）。取标题最前面那段拉丁字母去比，命中就当单位拒掉。
     * 为什么不用 {@code \\b}：中文环境里 {@code g} 后面紧跟汉字时根本没有词边界，
     * 「5 g食盐」「6.0 mmHg时主要终点」「75 min高强度」全都漏过去（这条实测踩过）。
     */
    private static final Set<String> UNITS = Set.of(
            "mm", "mmhg", "mmol", "mol", "mg", "kg", "g", "ml", "dl", "cl", "cm", "km", "min", "sec",
            "s", "h", "u", "iu", "kpa", "kcal", "bpm", "ng", "ug", "l", "m", "meq");

    /** 一个认出来的标题候选。flatOffset 是它在「压平后的整篇正文」里的下标，和块文字的下标同一坐标系。 */
    record Head(String num, String title, int flatOffset, boolean numeric, int[] parts) {
        int depth() {
            return parts == null ? 1 : parts.length;
        }
    }

    /** 连续性过滤后的结果：接受的用来配章节，被拒的留着判断「顺序存疑」。 */
    record Outline(List<Head> accepted, List<Head> rejected) {
        static final Outline NONE = new Outline(List.of(), List.of());

        boolean usable() {
            return accepted.size() >= MIN_HEADINGS;
        }
    }

    /** 一块归在哪一节。crosses=这块横跨了两个标题；suspect=这块附近认到的编号排不进大纲，顺序不可信。 */
    public record SectionSpan(boolean found, String startNum, String startTitle, String endNum, String endTitle,
                              boolean crosses, boolean suspect, int acceptedHeadings, int rejectedHeadings,
                              String reason) {}

    // ---------- 抽取 ----------

    /**
     * 抽一页里的标题候选。{@code base} 是这一页在压平整篇里的起始下标，
     * 页内偏移按「每行压平后的长度」累计，所以候选的下标和块文字的下标天然同坐标系。
     */
    static List<Head> pageHeads(String pageText, int base) {
        List<Head> out = new ArrayList<>();
        if (pageText == null) {
            return out;
        }
        int running = 0;
        for (String raw : pageText.split("\\r?\\n", -1)) {
            String line = raw.trim();
            if (!line.isEmpty()) {
                Head h = headOf(line, base + running);
                if (h != null) {
                    out.add(h);
                }
                running += PdfPageLocator.flat(line).length();
            }
        }
        return out;
    }

    /** 单行判定：形状像标题、又不是目录行、开头不是单位，才算候选。 */
    static Head headOf(String line, int flatOffset) {
        if (line == null || line.isEmpty() || TOC_TAIL.matcher(line).find()) {
            return null;
        }
        Matcher num = NUM_HEAD.matcher(line);
        if (num.matches()) {
            String n = num.group(1);
            String title = num.group(2).trim();
            return headingShapeOk(n, title) ? new Head(n, title, flatOffset, true, partsOf(n)) : null;
        }
        Matcher cn = CN_HEAD.matcher(line);
        if (cn.matches()) {
            // 中文序号这一支不查单位表：「一、支持证据」是指南里最常见的小节名，
            // 而单位表里的「支」「片」「个」会把这类真标题全挡掉（这条实测踩过）。假候选「1 片 qd」走的是数字那一支。
            String n = cn.group(1).replace("、", "").replace("（", "").replace("）", "");
            return new Head(n, cn.group(2).trim(), flatOffset, false, null);
        }
        return null;
    }

    static boolean headingShapeOk(String num, String title) {
        if (title == null || title.isEmpty() || isUnitTitle(title)) {
            return false;
        }
        // 没有「4.0 节」这种编号，出现了就说明匹配上的是正文里的小数
        if (num.endsWith(".0") || num.equals("0")) {
            return false;
        }
        // 标题前 8 个字里带句读的一律不收：真标题不带逗号
        String head = title.substring(0, Math.min(title.length(), 8));
        if (head.matches(".*[，。、；].*")) {
            return false;
        }
        return CJK.matcher(title).find() || title.matches("^[A-Z][a-zA-Z]{2,}.*");
    }

    /** 标题以最前面的拉丁字母串（或中文量词）开头且那个串是单位 → 这是正文句子，不是章节。 */
    static boolean isUnitTitle(String title) {
        if (title == null || title.isEmpty()) {
            return true;
        }
        Matcher m = LATIN_RUN.matcher(title);
        if (m.find()) {
            return UNITS.contains(m.group(0).toLowerCase());
        }
        return CN_UNIT.matcher(title).find();
    }

    private static int[] partsOf(String num) {
        String[] sp = num.split("\\.");
        int[] p = new int[sp.length];
        for (int i = 0; i < sp.length; i++) {
            p[i] = Integer.parseInt(sp[i]);
        }
        return p;
    }

    // ---------- 连续性闸 ----------

    /**
     * 大纲路径必须单调：同层递增、往下一层要正好接在上一条之后、往回卷要祖先那一层递增。
     * 中文序号没有可比的层级，不参与这道闸（{@code numeric=false} 一律收）。
     */
    static boolean fits(int[] last, int[] now) {
        if (last == null) {
            return true;
        }
        if (now.length == last.length) {
            return prefixEquals(last, now, now.length - 1) && now[now.length - 1] > last[last.length - 1];
        }
        if (now.length > last.length) {
            return prefixEquals(last, now, last.length);                 // 4.1 -> 4.1.2：父路径正好是上一条
        }
        int k = now.length;
        return prefixEquals(last, now, k - 1) && now[k - 1] > last[k - 1];  // 1.3 -> 2、2.7 -> 3：回卷到祖先层
    }

    private static boolean prefixEquals(int[] a, int[] b, int len) {
        if (len < 0 || len > a.length || len > b.length) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从每个候选起点各跑一遍，取接受条数最多的那条链。
     * 为什么要挑起点：一个假阳性只要当了第一个锚点，后面整串真标题都会被它顶掉
     * （实测「5 mmHg，死亡风险降低…」占了起点，基层那份 65 条真标题被误杀）。
     */
    static Outline chain(List<Head> all) {
        if (all == null || all.isEmpty()) {
            return Outline.NONE;
        }
        List<Integer> numericIdx = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).numeric()) {
                numericIdx.add(i);
            }
        }
        List<Integer> best = null;
        for (int start : numericIdx) {
            List<Integer> kept = walkFrom(all, start);
            if (best == null || kept.size() > best.size()) {
                best = kept;
            }
        }
        if (best == null) {                       // 全是中文序号：没有可比的东西，整串收下
            best = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                best.add(i);
            }
        }
        List<Head> accepted = new ArrayList<>();
        List<Head> rejected = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            (best.contains(i) ? accepted : rejected).add(all.get(i));
        }
        return new Outline(accepted, rejected);
    }

    private static List<Integer> walkFrom(List<Head> all, int start) {
        List<Integer> kept = new ArrayList<>();
        int[] last = null;
        for (int i = start; i < all.size(); i++) {
            Head h = all.get(i);
            if (!h.numeric()) {
                kept.add(i);
                continue;
            }
            if (fits(last, h.parts())) {
                kept.add(i);
                last = h.parts();
            }
        }
        return kept;
    }

    // ---------- 配到块上 ----------

    /**
     * 给定压平的整篇、认出的大纲和一块正文，算这块归在哪一节。
     * 「定位不到」是正常结果：库里文字和这份 PDF 对不上、这块在第一个标题之前、整份文件没规律，
     * 三种都如实说，绝不凑一个章节号。
     */
    static SectionSpan sectionOf(List<String> pageFlat, Outline outline, String chunkText) {
        String doc = PdfPageLocator.joined(pageFlat);
        int pageCount = pageFlat == null ? 0 : pageFlat.size();
        String needle = PdfPageLocator.flat(chunkText);
        if (needle.isEmpty()) {
            return miss("块正文是空的", outline);
        }
        if (pageCount == 0) {
            return miss("这份 PDF 一页文字都没抽到", outline);
        }
        int pos = doc.indexOf(needle);
        if (pos < 0) {
            return miss("库里正文和磁盘上这份 PDF 现在抽出来的文字对不上（多为扫描件走了 OCR／暴力COS流入库，或文件在入库后被改动）", outline);
        }
        if (outline == null || outline.accepted().isEmpty()) {
            return miss("这份文件一个章节标题都没认出来", outline);
        }
        if (!outline.usable()) {
            return miss("只认出 " + outline.accepted().size() + " 个章节标题，不足 " + MIN_HEADINGS
                    + " 个，不敢当成这份文件的编号规律", outline);
        }
        int end = pos + needle.length() - 1;
        Head start = null;
        Head finalHead = null;
        Head firstInside = null;
        for (Head h : outline.accepted()) {
            if (h.flatOffset() <= pos) {
                start = h;
            } else if (firstInside == null && h.flatOffset() <= end) {
                firstInside = h;                     // 块里出现的第一个标题（accepted 按下标升序）
            }
            if (h.flatOffset() <= end) {
                finalHead = h;
            } else {
                break;
            }
        }
        if (start == null) {
            // 块头在第一个标题之前，但标题正好落在这块里（实测基层那份块 4：封面 +「1 基层高血压管理基本要求」…）：
            // 从块里第一个标题起报，后面还有别的标题就照常算跨节，不把起点抹成一个
            start = firstInside;
        }
        if (start == null) {
            return new SectionSpan(false, null, null, null, null, false, false,
                    outline.accepted().size(), outline.rejected().size(),
                    "这块在整份文件第一个章节标题之前（封面、目录那几页），没有可归属的小节");
        }
        boolean crosses = finalHead != start;
        boolean suspect = false;
        for (Head r : outline.rejected()) {
            // 只有「层级编号」被拒才算存疑：单层的「1 片 qd」那种本来就是被形状规则挡在候选之外或撞车的剂量，
            // 拿它去污染一整块的可信度是过严
            if (r.numeric() && r.depth() >= 2 && r.flatOffset() >= start.flatOffset() && r.flatOffset() <= end) {
                suspect = true;
                break;
            }
        }
        return new SectionSpan(true, start.num(), start.title(), finalHead.num(), finalHead.title(),
                crosses, suspect, outline.accepted().size(), outline.rejected().size(), null);
    }

    static SectionSpan miss(String reason, Outline outline) {
        int a = outline == null ? 0 : outline.accepted().size();
        int r = outline == null ? 0 : outline.rejected().size();
        return new SectionSpan(false, null, null, null, null, false, false, a, r, reason);
    }

    /** 给人看的那一行：跨节要写范围，顺序存疑就干脆不给号。 */
    public static String describe(SectionSpan s) {
        if (s == null || !s.found()) {
            return "章节号未认出：" + (s == null ? "未知原因" : s.reason());
        }
        if (s.suspect()) {
            // 实测这 4 块（国家基层那份 10~13）就是双栏流程图：给的 4.1.1.2 是错的，正文其实出自 4.2.2 诊断流程。
            // 所以存疑时一个字都不给号——页码那条链路是「认不出就明说」，这里同理：给错比不给更坏。
            return "章节号不给（这块附近认到了排不进大纲的编号，双栏或表格把阅读顺序打乱了，按位置配的号不可信）";
        }
        StringBuilder sb = new StringBuilder();
        if (s.crosses()) {
            sb.append(s.startNum()).append(' ').append(s.startTitle())
                    .append(" → ").append(s.endNum()).append(' ').append(s.endTitle())
                    .append("（这块跨了两节，正文从头一节一直写到后一节）");
        } else {
            sb.append(s.startNum()).append(' ').append(s.startTitle());
        }
        return sb.toString();
    }
}
