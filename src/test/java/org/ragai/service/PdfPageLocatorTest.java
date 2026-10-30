package org.ragai.service;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 块号 → 页码 的定位算法（纯逻辑，不读 PDF、不连库、不打模型）。
 *
 * <p>这套断言钉的是「页码不能瞎猜」：对不上就明说对不上，跨页就写页范围，
 * 空页/首尾页边界不能把页号算歪；页脚印码只在整份文件的差值一致时才给，
 * 认不出、样本太少、正文数字撞进来这三种情况都必须落到「只给物理页序」。
 * 页码错了比没有页码更坏——人会照着翻那一页，还以为自己核对过了。
 */
class PdfPageLocatorTest {

    private static final String P1 = "第一页正文开头几句";
    private static final String P2 = "第二页里有目标句子的前半段";
    private static final String P3 = "第三页接着写后半段收尾";

    /** spanOf 的默认那一半：本类里绝大多数用例只关页边界，不关心印码，就统一给「认不出印码」。 */
    private static PdfPageLocator.PageSpan span(List<String> pages, String chunk) {
        return PdfPageLocator.spanOf(pages, chunk, PdfPageLocator.NO_OFFSET, "测试未提供印码规律");
    }

    @Test
    void blockInsideOnePageReportsThatPage() {
        var span = span(List.of(P1, P2, P3), "目标句子的前半段");
        assertTrue(span.found());
        assertEquals(2, span.startPage());
        assertEquals(2, span.endPage());
        assertEquals(3, span.pageCount());
    }

    /** 块正好压在页边界上时必须报两页：只报起始页会让人以为整块在同一页里，白翻。 */
    @Test
    void blockCrossingPageBoundaryReportsBothPages() {
        var span = span(List.of(P1, P2, P3), "前半段第三页接着写");
        assertTrue(span.found());
        assertEquals(2, span.startPage());
        assertEquals(3, span.endPage());
        assertTrue(PdfPageLocator.describe(span).contains("跨页"));
    }

    /** 末字正好是某页最后一个字符时不能算进下一页——二分找的是「最后一个起始下标 &lt;= 偏移」。 */
    @Test
    void blockEndingExactlyOnPageBoundaryStaysOnThatPage() {
        var span = span(List.of(P1, P2, P3), "正文开头几句");
        assertEquals(1, span.startPage());
        assertEquals(1, span.endPage());
    }

    /** PDF 抽取会在中文字之间塞空格，库里的块还带换行；压平口径必须和 Bm25IndexService.locate 一致。 */
    @Test
    void whitespaceInsideBlockDoesNotBreakMatching() {
        var span = span(List.of(P1, P2, P3), "目标句子 的前\n半段");
        assertTrue(span.found(), "含空白的块应能定位到第 2 页");
        assertEquals(2, span.startPage());
    }

    /** 空白页（扫描页/纯图页）没有文字，但页号必须照数，否则后面每一页都算少一页。 */
    @Test
    void blankPageStillCountsInPageNumbers() {
        var span = span(List.of(P1, "", P2, P3), "后半段收尾");
        assertTrue(span.found());
        assertEquals(4, span.endPage());
    }

    @Test
    void textNotInDocumentIsReportedAsUnmatchedNotGuessed() {
        var span = span(List.of(P1, P2, P3), "库里根本没有的一句话");
        assertFalse(span.found());
        assertTrue(span.reason().contains("对不上"));
        // 猜页是最坏的失败模式：宁可一个字不给
        assertEquals("定位不到：" + span.reason(), PdfPageLocator.describe(span));
    }

    @Test
    void emptyBlockOrEmptyPagesFailLoud() {
        assertFalse(span(List.of(P1), "").found());
        assertTrue(span(List.of(P1), "   \n ").reason().contains("块正文是空"));
        var noPage = span(List.of(), "任意文字");
        assertFalse(noPage.found());
        assertTrue(noPage.reason().contains("一页文字都没抽到"));
    }

    /** 库里的 source 是当时那台机器的路径，换机器就是一条读不到的文件；这种情况得说清是文件不在，而不是文字对不上。 */
    @Test
    void missingFileSaysSoInsteadOfBlamingTheText() {
        var span = PdfPageLocator.locate(new File("D:/不存在的目录/没有这份.pdf"), "任何文字");
        assertFalse(span.found());
        assertTrue(span.reason().contains("本地读不到这份 PDF"));
        assertTrue(span.reason().contains("没有这份.pdf"), "要把路径带上，人才知道去哪儿找文件");
    }

    /** 库里的 source 为空（SimpleVectorStore 或老数据）时，消息不能以「：」收尾让人以为路径是空字符串。 */
    @Test
    void emptySourceSaysTheLibraryHasNoPath() {
        var span = PdfPageLocator.locate(new File(""), "任何文字");
        assertFalse(span.found());
        assertTrue(span.reason().contains("<库里没记 source>"));
    }

    @Test
    void describeUsesPhysicalPageWordingAndTotalPages() {
        String line = PdfPageLocator.describe(span(List.of(P1, P2, P3), "目标句子的前半段"));
        assertTrue(line.contains("第 2 页"));
        assertTrue(line.contains("全 3 页"));
        assertTrue(line.contains("PDF 物理页序"), "不写清口径，人会把物理页当成书上印的那一页");
        assertTrue(line.contains("印在页上的页码认不出"), "认不出时必须明说，不能只给个数字让人误信");
    }

    // ---------- 页脚印码那一半 ----------

    /** 中文期刊页眉「836 中国循环杂志…」：行首数字就是印码，整份差值一致 → 规律成立。 */
    @Test
    void headerLeadingNumberGivesConstantOffset() {
        List<Set<Integer>> perPage = List.of(Set.of(836), Set.of(837), Set.of(838),
                Set.of(839), Set.of(840), Set.of(841));
        assertEquals(835, PdfPageLocator.printedOffsetOf(perPage, perPage.size()));
    }

    /** 实测三份中文期刊的三种行形状都要认得出来：页眉行首、页尾「·· 221」、页尾裸数字。 */
    @Test
    void threeRealHeaderFooterShapesAreAllRead() {
        assertEquals(Set.of(836), PdfPageLocator.printedCandidates(
                "836 中国循环杂志 2025 年 9 月 第 40 卷 第 9 期（总第 327 期）\n正文正文\n血压及其危害"));
        assertEquals(Set.of(221), PdfPageLocator.printedCandidates(
                "中华心血管病杂志 2023 年3 月第 51 卷第 3 期\n正文正文\n·· 221"));
        assertEquals(Set.of(1), PdfPageLocator.printedCandidates("1\t Introduction\nMore people die…"));
    }

    /** 正文里的小节标题「4.1.1.2 测量方法」不是页码；行首后面紧跟小数点的一律不认。 */
    @Test
    void sectionHeadingNumberIsNotMistakenForPageNumber() {
        assertEquals(Set.of(), PdfPageLocator.printedCandidates("4.1.1.2  测量方法\n诊室血压规范测量三要点"));
        assertEquals(Set.of(), PdfPageLocator.printedCandidates("LDL-C 2.6 mmol/L\n非HDL-C 的目标值见下表"));
    }

    /** 页中间那几行的数字绝不能当候选：一次撞上就要整份文件的规律作废（宁可少认，不可认错）。 */
    @Test
    void numbersInBodyLinesAreIgnored() {
        assertEquals(Set.of(), PdfPageLocator.printedCandidates(
                "推荐每日摄入钠盐\n5\n克以下，收缩压控制在\n130\n毫米汞柱以内"));
    }

    /** 同一页里页眉和页脚各给一个不同数字 → 这页算歧义，直接跳过，不挑一个。 */
    @Test
    void ambiguousPageIsSkippedNotGuessed() {
        List<Set<Integer>> perPage = List.of(Set.of(836), Set.of(837), Set.of(838, 100),
                Set.of(839), Set.of(840), Set.of(841), Set.of(842));
        assertEquals(835, PdfPageLocator.printedOffsetOf(perPage, perPage.size()),
                "歧义页要跳过，但其余页一致的规律仍然成立");
    }

    /** WHO 那份的失败形态：前置页另起一套编号，算出好几个差值 → 整份不给印码，并说清为什么。 */
    @Test
    void inconsistentOffsetsRefusePrintedNumbers() {
        List<Set<Integer>> perPage = List.of(Set.of(), Set.of(), Set.of(4), Set.of(6),
                Set.of(1), Set.of(2), Set.of(3), Set.of(46), Set.of(8));
        assertEquals(PdfPageLocator.NO_OFFSET, PdfPageLocator.printedOffsetOf(perPage, perPage.size()));
        assertFalse(PdfPageLocator.describe(
                PdfPageLocator.spanOf(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"), "a",
                        PdfPageLocator.NO_OFFSET, "各页认出的印码算出好几个偏移")).contains("页上印的是"));
    }

    /** 只认出 3 页时不给规律：太少容易撞上巧合（阈值 5 写在 MIN_DETECTED_PAGES）。 */
    @Test
    void tooFewDetectedPagesRefusesOffset() {
        List<Set<Integer>> perPage = List.of(Set.of(9), Set.of(), Set.of(10), Set.of(), Set.of(11), Set.of());
        assertEquals(PdfPageLocator.NO_OFFSET, PdfPageLocator.printedOffsetOf(perPage, perPage.size()));
    }

    /** 每页候选数必须和页数一一对应，错位一格就会把偏移算反，所以数量不符时直接不给规律。 */
    @Test
    void candidateListSizeMismatchRefusesOffset() {
        List<Set<Integer>> perPage = List.of(Set.of(1), Set.of(2), Set.of(3), Set.of(4), Set.of(5));
        assertEquals(PdfPageLocator.NO_OFFSET, PdfPageLocator.printedOffsetOf(perPage, perPage.size() + 1));
        assertEquals(PdfPageLocator.NO_OFFSET, PdfPageLocator.printedOffsetOf(null, 3));
    }

    @Test
    void describeAddsPrintedNumberWhenOffsetKnown() {
        var samePage = PdfPageLocator.describe(
                PdfPageLocator.spanOf(List.of(P1, P2, P3), "目标句子的前半段", 835, null));
        assertTrue(samePage.contains("第 2 页"), samePage);
        assertTrue(samePage.contains("页上印的是 837"), "光有物理页码，人对不上纸质件：" + samePage);

        var crossing = PdfPageLocator.describe(
                PdfPageLocator.spanOf(List.of(P1, P2, P3), "前半段第三页接着写", 835, null));
        // 破折号用转义写死：这个断言钉的是「跨页要给印码范围」，不该被源文件编码左右
        assertTrue(crossing.contains("页上印的是 837" + '\u2013' + "838"),
                "跨页要给印码范围，否则人只翻一页：" + crossing);
    }

    /** 前置页另起编号的文件（WHO：正文才从 1 开始），落在起算之前的块必须明说，不能报个负数页码。 */
    @Test
    void blockBeforeNumberingStartSaysSoInsteadOfNegativePage() {
        var span = PdfPageLocator.spanOf(List.of(P1, P2, P3), "目标句子的前半段", -12, null);
        String line = PdfPageLocator.describe(span);
        assertTrue(line.contains("整个在阿拉伯页码起算之前"), line);
        assertFalse(line.contains("不印页码"), "前置页往往印着罗马数字，不能说人家没印页码");
    }

    /** 交界上那块（实测 WHO #7 覆盖物理 10–13 页）：只说「起算之前」会把尾巴已经进了正文页码这半截抹掉。 */
    @Test
    void blockCrossingIntoNumberingReportsTheTailPage() {
        var span = PdfPageLocator.spanOf(List.of(P1, P2, P3), "前半段第三页接着写", -2, null);
        String line = PdfPageLocator.describe(span);
        assertTrue(line.contains("一直写到页码起算之后"), line);
        assertTrue(line.contains("末尾落在页上印的 1"), "跨界的块要给出那半截的真实印码：" + line);
        assertFalse(line.contains("页上印的是 0"), "绝不报 0 或负页码：" + line);
    }

    /** 零偏移是合法规律（书的第一页就印 1），所以「认不出」不能用 0 表示——这条钉住 NO_OFFSET 那个哨兵。 */
    @Test
    void zeroOffsetIsValidPatternNotMissingOne() {
        var span = PdfPageLocator.spanOf(List.of(P1, P2, P3), "目标句子的前半段", 0, null);
        assertTrue(PdfPageLocator.describe(span).contains("页上印的是 2"), PdfPageLocator.describe(span));
    }

    /** 偏移成立时不该有解释文案；不成立时必须有，而且要说清是哪种不成立。 */
    @Test
    void printedNoteOnlyPresentWhenOffsetUnknown() {
        List<Set<Integer>> good = List.of(Set.of(5), Set.of(6), Set.of(7), Set.of(8), Set.of(9));
        assertEquals(4, PdfPageLocator.printedOffsetOf(good, good.size()));
        List<Set<Integer>> tooFew = List.of(Set.of(5), Set.of(), Set.of(6), Set.of(), Set.of(), Set.of());
        assertEquals(PdfPageLocator.NO_OFFSET, PdfPageLocator.printedOffsetOf(tooFew, tooFew.size()));
    }
}
