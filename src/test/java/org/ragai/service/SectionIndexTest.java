package org.ragai.service;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 块号 → 第几节 的判定逻辑（纯逻辑，不读 PDF、不连库、不打模型）。
 *
 * <p>这套断言钉的是「章节号不能瞎给」。页码那条链路靠的是「501 块的页号从不回退」这种可验证的单调性，
 * 章节号没有这个保证：抽取顺序会被双栏和表格打乱（实测国家基层那份 {@code 4.2.2} 排在 {@code 4.1.2} 之前），
 * 而一个错的章节号比没有更坏——人会照着「5.4.3」去翻指南，还以为自己核对过了。
 *
 * <p>所以三条规矩各有一组断言：①剂量和化验值不许当标题（{@code 4.9 mmol/L}、{@code 5 g食盐}），
 * ②编号排不进大纲就拒掉，被拒的那条若正好落在块里要反咬成「顺序存疑」而不是悄悄给个号，
 * ③跨节就报范围、认不出就明说，绝不凑一个。
 */
class SectionIndexTest {

    // ---------- 候选形状 ----------

    /** 数字层级标题：国家基层那份的 {@code 5.4.3.2}，四份里最干净的一套编号。 */
    @Test
    void numericHeadingIsReadWithItsParts() {
        var h = SectionIndex.headOf("5.4.3.2 有合并症的高血压药物治疗方案（表 5）", 100);
        assertNotNull(h);
        assertEquals("5.4.3.2", h.num());
        assertTrue(h.numeric());
        assertEquals(4, h.depth());
        assertEquals(100, h.flatOffset(), "标题下标必须用调用方给的页内基准，否则和块文字不在一个坐标系");
    }

    /** 英文 Title Case（WHO 那份）也要认得，跟中文一样只要求「数字 + 后面像个标题」。 */
    @Test
    void englishTitleCaseHeadingIsRead() {
        assertNotNull(SectionIndex.headOf("6.1 Guideline recommendations", 0));
        assertNotNull(SectionIndex.headOf("2.2 Analytical framework and PICO", 0));
    }

    /** 目录行「标题 + 制表符 + 页码」不是正文里的小节标题；WHO 那份有 43 条，收了就把整份规律带歪。 */
    @Test
    void tocLineIsSkipped() {
        assertNull(SectionIndex.headOf("3.5 Combination therapy\t13", 0));
        assertNull(SectionIndex.headOf("5.1 Publication \t 24", 0));
    }

    /** 中文序号两套形状：{@code 一、概述}（血脂、一级预防用）和 {@code （二）支持证据}。 */
    @Test
    void chineseSerialHeadingsAreRead() {
        var a = SectionIndex.headOf("一、组织结构", 0);
        assertNotNull(a);
        assertEquals("一", a.num());
        assertFalse(a.numeric(), "中文序号没有可比层级，不参与连续性闸");
        assertNotNull(SectionIndex.headOf("（二）支持证据", 0));
        assertNotNull(SectionIndex.headOf("一、支持证据", 0),
                "「支持」开头的中文小节是指南里最常见的标题，不能被量词表误伤（「支」既是量词也是标题首字）");
    }

    /**
     * 剂量和化验值长得跟章节号一模一样，必须全部挡掉。这些串是从库内正文里抄来的实测误报
     * （宽松规则曾据此报出「32.9% 的块有章节号」，几乎全是假的）。
     */
    @Test
    void dosesAndLabValuesAreNeverHeadings() {
        assertNull(SectionIndex.headOf("4.9 mmol/L 的目标值见下表", 0));
        assertNull(SectionIndex.headOf("12.5 mg 每晚一次口服", 0));
        assertNull(SectionIndex.headOf("6.0 mmHg时主要终点降低", 0));
        assertNull(SectionIndex.headOf("2.6 mmol/L，风险增加", 0));
        assertNull(SectionIndex.headOf("1.8 g EPA干预组和安慰剂组", 0));
        assertNull(SectionIndex.headOf("3.5 mmHg［204］。DASH饮食可有效降低", 0));
    }

    /** {@code 5 g食盐} 这种「单位后面紧跟中文」没有词边界，所以单位比对取最前面那段拉丁字母而不是靠 \\b。 */
    @Test
    void unitGluedToChineseIsStillRejected() {
        assertNull(SectionIndex.headOf("5 g食盐）］与心血管死亡相关", 0));
        assertNull(SectionIndex.headOf("75 min高强度身体活动两种方式结合", 0));
        assertNull(SectionIndex.headOf("6 h者比 7~8 h者心血管病风险增加", 0));
        assertTrue(SectionIndex.isUnitTitle("mmHg且合并糖尿病或 CKD"), "单位开头就是正文句子");
    }

    /** 药品表里的量词开头（{@code 5 片 qd}）不是标题：这类串在表格里成排出现。 */
    @Test
    void chineseQuantifierTitleIsRejected() {
        assertTrue(SectionIndex.isUnitTitle("片 qd"));
        assertNull(SectionIndex.headOf("5 片 qd 每晚一次口服", 0));
    }

    /** 真标题不带逗号句号：带标点的说明这行是被换行劈开的正文句子（F1d 就是被这种假阳性占了起点）。 */
    @Test
    void sentenceWithPunctuationIsNotHeading() {
        assertFalse(SectionIndex.headingShapeOk("5", "mmHg，死亡风险降低 10%"));
        assertFalse(SectionIndex.headingShapeOk("3", "降压目标，收缩压应低于"));
        assertTrue(SectionIndex.headingShapeOk("5", "降压药物治疗"));
        assertFalse(SectionIndex.headingShapeOk("4.0", "随访管理"), "没有「4.0 节」这种编号，撞上的是正文小数");
    }

    // ---------- 连续性闸 ----------

    /** 大纲路径单调才收：同层递增、往下一层要接得住上一条、往回卷要祖先层递增。 */
    @Test
    void outlinePathMustBeMonotonic() {
        assertTrue(SectionIndex.fits(p("4.1"), p("4.1.2")), "往下一层：父路径正好是上一条");
        assertTrue(SectionIndex.fits(p("4.1.1"), p("4.1.2")), "同层递增");
        assertTrue(SectionIndex.fits(p("1.3"), p("2")), "跨章跳转：回卷到祖先层要递增");
        assertTrue(SectionIndex.fits(p("2.7"), p("3")));
        assertTrue(SectionIndex.fits(p("5.4.3.2"), p("5.4.4")));
        assertFalse(SectionIndex.fits(p("4.1.2"), p("4.1.1")), "同层回退不收");
        assertFalse(SectionIndex.fits(p("3"), p("1")), "整份文件不会从第 3 章跳回第 1 章");
        assertFalse(SectionIndex.fits(p("4.1"), p("4.2.1")), "往下一层时父路径必须对得上");
        assertFalse(SectionIndex.fits(p("4.1"), p("5.1.1")), "4.1 之后不能冒出 5.1.1（父路径不是上一条）");
        assertTrue(SectionIndex.fits(null, p("7")), "第一个锚点没有可比的上一条");
    }

    /**
     * 起点要挑「接受条数最多的那条链」。这条钉住 F1d 的 bug：一个假阳性只要当了第一个锚点，
     * 后面整串真标题都会被它顶掉（实测国家基层那份 65 条真标题被一条 {@code 5 mmHg，…} 误杀）。
     */
    @Test
    void chainPicksTheStartThatAcceptsMost() {
        var heads = List.of(SectionIndex.headOf("9 心脏", 0), SectionIndex.headOf("1 总则", 10),
                SectionIndex.headOf("2 治疗", 20), SectionIndex.headOf("2.1 药物选择", 30));
        var out = SectionIndex.chain(heads);
        assertEquals(3, out.accepted().size(), "「9 心脏」占了起点就把真大纲全顶掉，这种链不能选");
        assertEquals(1, out.rejected().size());
        assertEquals("9", out.rejected().get(0).num());
        assertEquals(List.of("1", "2", "2.1"),
                out.accepted().stream().map(SectionIndex.Head::num).toList());
    }

    /** 全是中文序号时没有可比的东西，整串收下（连续性闸只管数字层级）。 */
    @Test
    void chineseOnlyOutlineIsTakenWhole() {
        var out = SectionIndex.chain(List.of(SectionIndex.headOf("一、概述", 0),
                SectionIndex.headOf("二、支持证据", 10), SectionIndex.headOf("三、推荐", 20)));
        assertEquals(3, out.accepted().size());
        assertTrue(out.usable());
    }

    /** 认出的标题太少就不给规律：一两个太容易是撞出来的正文数字。 */
    @Test
    void tooFewHeadingsRefusesTheWholeFile() {
        var out = SectionIndex.chain(List.of(SectionIndex.headOf("3 治疗", 0),
                SectionIndex.headOf("4 随访", 10)));
        assertFalse(out.usable());
        var span = SectionIndex.sectionOf(List.of("aaaaaaaaaa3治疗bbbbbbbbbb4随访cccccccccc"), out, "cccccccccc");
        assertFalse(span.found());
        assertTrue(span.reason().contains("不足 " + SectionIndex.MIN_HEADINGS + " 个"), span.reason());
    }

    // ---------- 配到块上 ----------

    /** 造一份「按页压平 + 大纲」，基准算法必须和 {@code PdfPageLocator.load} 一致：每页起始下标 = 前面各页压平长度之和。 */
    private static SectionIndex.Outline outlineOf(List<String> pages) {
        List<SectionIndex.Head> heads = new ArrayList<>();
        int base = 0;
        for (String p : pages) {
            heads.addAll(SectionIndex.pageHeads(p, base));
            base += PdfPageLocator.flat(p).length();
        }
        return SectionIndex.chain(heads);
    }

    private static final List<String> THREE_SECTIONS = List.of(
            "1 概述\n概述讲了血压的定义和测量要求",
            "2 治疗\n治疗部分给了首选药物和剂量",
            "3 随访\n随访每三个月一次");

    @Test
    void blockInsideOneSectionReportsThatSection() {
        var span = SectionIndex.sectionOf(THREE_SECTIONS, outlineOf(THREE_SECTIONS), "首选药物和剂量");
        assertTrue(span.found(), span == null ? "" : span.reason());
        assertEquals("2", span.startNum());
        assertEquals("治疗", span.startTitle());
        assertFalse(span.crosses());
        assertFalse(span.suspect());
        assertEquals("2 治疗", SectionIndex.describe(span));
        assertEquals(3, span.acceptedHeadings(), "计数要如实带出来，人工核对时靠它判断这份文件认得到什么程度");
        assertEquals(0, span.rejectedHeadings());
    }

    /** 一块横跨两个标题时必须报范围：只报第一个会让人以为整块都在那一节里。 */
    @Test
    void blockCrossingSectionsReportsBoth() {
        var span = SectionIndex.sectionOf(THREE_SECTIONS, outlineOf(THREE_SECTIONS),
                "血压的定义和测量要求2治疗治疗部分给了");
        assertTrue(span.found());
        assertTrue(span.crosses());
        assertEquals("1", span.startNum());
        assertEquals("2", span.endNum());
        String line = SectionIndex.describe(span);
        assertTrue(line.contains("1 概述") && line.contains("2 治疗") && line.contains("跨了两节"), line);
    }

    /** 空白、换行是 PDF 抽取的常态，压平口径要和页码那条链路完全一致，否则同一块两处读数互相矛盾。 */
    @Test
    void whitespaceInsideBlockDoesNotBreakMatching() {
        var span = SectionIndex.sectionOf(THREE_SECTIONS, outlineOf(THREE_SECTIONS), "血压的定义 和\n测量要求");
        assertTrue(span.found(), "含空白的块应能配上章节号");
        assertEquals("1", span.startNum());
    }

    /** 落在第一个标题之前（封面、版权页那几块）：如实说没有可归属的小节，不硬套第一个标题。 */
    @Test
    void blockBeforeFirstHeadingSaysSo() {
        List<String> pages = List.of("封面页 高血压防治指南\n2 治疗\n治疗正文",
                "3 随访\n随访正文", "4 附录\n附录正文");
        var span = SectionIndex.sectionOf(pages, outlineOf(pages), "封面页 高血压");
        assertFalse(span.found());
        assertTrue(span.reason().contains("第一个章节标题之前"), span.reason());
    }

    /**
     * 块头在第一个标题之前、但标题就印在这块里（实测基层那份块 4：封面 +「1 基层高血压管理基本要求」）：
     * 从<b>块里第一个</b>标题起报，后面还有标题就照常算跨节——只报最后那个会把起点抹掉。
     */
    @Test
    void blockContainingSeveralHeadingsReportsFromTheFirstOne() {
        List<String> pages = List.of("封面页 高血压防治指南\n2 治疗\n治疗正文",
                "3 随访\n随访正文", "4 附录\n附录正文");
        var one = SectionIndex.sectionOf(pages, outlineOf(pages), "封面页 高血压防治指南2 治疗");
        assertTrue(one.found(), one.reason());
        assertEquals("2", one.startNum());
        assertFalse(one.crosses(), "块里只印了一个标题 → 不算跨节");
        assertEquals("2 治疗", SectionIndex.describe(one));

        var two = SectionIndex.sectionOf(pages, outlineOf(pages), "封面页 高血压防治指南2 治疗治疗正文3 随访");
        assertTrue(two.found(), two.reason());
        assertEquals("2", two.startNum(), "起点要取块里第一个标题，不能只报最后那个：" + SectionIndex.describe(two));
        assertEquals("3", two.endNum());
        assertTrue(two.crosses());
    }

    /**
     * 最危险的一种：附近认到了编号，但排不进大纲（双栏／表格把阅读顺序打乱了）。
     * 实测国家基层那份 10~13 块正是双栏流程图，按位置配出来的 {@code 4.1.1.2} 是错的（正文其实出自 4.2.2 诊断流程），
     * 所以存疑时整行不给号——和页码那条链路「认不出就明说」同一个纪律。
     */
    @Test
    void outOfOrderHeadingMarksBlockSuspect() {
        var heads = List.of(SectionIndex.headOf("1 总则", 0), SectionIndex.headOf("1.1 范围", 10),
                SectionIndex.headOf("1.2.1 被排版挪了位置", 20), SectionIndex.headOf("1.2 术语", 30));
        var out = SectionIndex.chain(heads);
        assertEquals(3, out.accepted().size());
        assertEquals("1.2.1", out.rejected().get(0).num(), "顺序倒置的那条要留在 rejected 里，它是存疑的证据");
        String page = "一123456789二123456789三123456789四123456789零";
        var span = SectionIndex.sectionOf(List.of(page), out, "三1234567");
        assertTrue(span.found());
        assertTrue(span.suspect(), "块里夹着排不进大纲的多级编号 → 顺序不可信");
        String line = SectionIndex.describe(span);
        assertTrue(line.startsWith("章节号不给"), line);
        assertFalse(line.contains("1.1 范围"), "存疑的块绝不能把一个可能错的章节号印出来：" + line);
    }

    /** 只有「层级编号」排不进去才算存疑；表格里那种单层的撞车编号不该把整块判死。 */
    @Test
    void singleLevelCollisionDoesNotMarkSuspect() {
        var heads = List.of(SectionIndex.headOf("9 心脏", 0), SectionIndex.headOf("1 总则", 10),
                SectionIndex.headOf("2 治疗", 20), SectionIndex.headOf("2.1 药物", 30));
        String page = "一123456789二123456789三123456789四123456789零";
        var span = SectionIndex.sectionOf(List.of(page), SectionIndex.chain(heads), "二123456789三1234");
        assertTrue(span.found());
        assertTrue(span.crosses());
        assertFalse(span.suspect(), "被拒的是单层撞车编号，不该连累整块");
    }

    /** 对不上、空块、空文件、整份没标题这四种失败要各自说清，绝不给章节号（与页码那套同样的措辞纪律）。 */
    @Test
    void unmatchedFailsLoudInsteadOfGuessing() {
        var miss = SectionIndex.sectionOf(THREE_SECTIONS, outlineOf(THREE_SECTIONS), "库里根本没有的一句话");
        assertFalse(miss.found());
        assertTrue(miss.reason().contains("对不上"), miss.reason());
        assertTrue(SectionIndex.describe(miss).startsWith("章节号未认出："));

        var blank = SectionIndex.sectionOf(THREE_SECTIONS, outlineOf(THREE_SECTIONS), "  \n ");
        assertTrue(blank.reason().contains("块正文是空的"));
        var noPage = SectionIndex.sectionOf(List.of(), outlineOf(List.of()), "任意文字");
        assertTrue(noPage.reason().contains("一页文字都没抽到"));
        var noHead = SectionIndex.sectionOf(List.of("没有任何标题的一页正文"),
                outlineOf(List.of("没有任何标题的一页正文")), "任何标题的一页");
        assertTrue(noHead.reason().contains("一个章节标题都没认出来"), noHead.reason());
    }

    /** 文件读不到时（库里 source 是当时那台机器的路径）要说清是文件不在，而不是文字对不上。 */
    @Test
    void missingFileSaysSoFromThePublicEntry() {
        var span = PdfPageLocator.locateSection(new File("D:/不存在的目录/没有这份.pdf"), "任何文字");
        assertFalse(span.found());
        assertTrue(span.reason().contains("本地读不到这份 PDF"), span.reason());
        assertTrue(span.reason().contains("没有这份.pdf"));
        assertTrue(SectionIndex.describe(span).contains("章节号未认出"));
        var noPath = PdfPageLocator.locateSection(new File(""), "任何文字");
        assertTrue(noPath.reason().contains("<库里没记 source>"));
    }

    // ---------- 与页码共用同一次扫描 ----------

    /** pageHeads 的页内偏移按「每行压平后的长度」累计，必须正好落在压平整篇的坐标系里。 */
    @Test
    void headingOffsetsLandInTheFlattenedCoordinateSystem() {
        String page = "1 概述\n血压的定义";
        var heads = SectionIndex.pageHeads(page, 1000);
        assertEquals(1, heads.size());
        assertEquals(1000, heads.get(0).flatOffset(), "标题在页首，偏移就是这一页的起始基准");
        String flat = PdfPageLocator.joined(List.of(page));
        assertEquals("1概述血压的定义", flat);
        var second = SectionIndex.pageHeads("2 治疗\n治疗正文", flat.length());
        assertEquals(flat.length(), second.get(0).flatOffset(), "第二页的标题要接着第一页的压平长度往后数");
    }

    private static int[] p(String num) {
        String[] sp = num.split("\\.");
        int[] out = new int[sp.length];
        for (int i = 0; i < sp.length; i++) {
            out[i] = Integer.parseInt(sp[i]);
        }
        return out;
    }
}
