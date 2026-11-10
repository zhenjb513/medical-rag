package org.ragai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ragai.controller.ChatController;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板占位字探针（EvaluationService.judgeTemplateLeak）的口径守门测试。
 * <p>
 * 起因：2026-11-09 库存问答的答案里跟了一行「（摘自《文件名.pdf》块N）」——那是提示词规则3 的示例格式，
 * 模型没指南块可引时把示例本身抄了上去（scratch/t10_answer2.txt）。而内联引用核对的正则要求「块」后跟数字，
 * 「块N」认不出，于是这个毛病在报表上完全隐形。这一层就是补那个漏检。
 * <p>
 * 钉住四件事：① 实测漏出的那行必须命中；② 合法引用/拒答/代码拼的页脚不许误伤；
 * ③「上下文」这个词明确不判（正文正常说话会用到）；④ 提示词教的占位形状与探针互相认得。
 * 诊断项不计入 isAllPass，与 backfill/wrongBlock/citationFabricated 同一档；纯字符串判定，不连库不打模型。
 */
class TemplateLeakJudgeTest {

    private static final String XZ = "中国血脂管理指南2023年.pdf";

    @Test
    @DisplayName("实测那次库存答案：占位文件名和「块N」都要抓到")
    void catchesTheObservedInventoryAnswer() {
        String answer = "胃镜活检钳当前库存为150件。\n\n（摘自《文件名.pdf》块N）\n"
                + "—— 无相关临床指南涉及医疗耗材库存管理，该信息来源于医院耗材管理系统实时查询。\n\n【引用来源】\n";
        var v = EvaluationService.judgeTemplateLeak(answer);
        assertTrue(v.suspect());
        assertEquals(List.of("《文件名.pdf》", "块N"), v.leaks());
        assertTrue(v.evidence().contains("示例格式"), "evidence 要说清是抄了示例：" + v.evidence());
    }

    @Test
    @DisplayName("合法引用不误伤：真文件名 + 真块号")
    void legitimateCitationIsNotALeak() {
        String answer = "LDL-C 目标值<1.8mmol/L（摘自《" + XZ + "》块40）。";
        var v = EvaluationService.judgeTemplateLeak(answer);
        assertFalse(v.suspect());
        assertTrue(v.leaks().isEmpty());
    }

    @Test
    @DisplayName("拒答原话不误伤")
    void refusalPhraseIsNotALeak() {
        var v = EvaluationService.judgeTemplateLeak(RagReferenceService.REFUSAL_PHRASE + "。");
        assertFalse(v.suspect());
    }

    @Test
    @DisplayName("代码拼的页脚不误伤：「【引用来源】」「块号 40、41」「【2023版】」都不是占位字")
    void codeBuiltFooterIsNotALeak() {
        String answer = "结论。\n\n【引用来源】\n" + XZ + "【2023版】（块号 40、41；另有 2 块未记录块号）";
        var v = EvaluationService.judgeTemplateLeak(answer);
        assertFalse(v.suspect(), "页脚是代码拼的展示文本，不该算模型抄模板：" + v.leaks());
    }

    @Test
    @DisplayName("上下文头被抄进答案：【文档1】要抓到")
    void catchesCopiedContextHeader() {
        var v = EvaluationService.judgeTemplateLeak("依据【文档1】的内容，目标值<1.8mmol/L。");
        assertTrue(v.suspect());
        assertEquals(List.of("【文档1】"), v.leaks());
    }

    @Test
    @DisplayName("「上下文」这个词明确不判：正文正常说话也会用到，判了必误伤")
    void contextWordItselfIsNotJudged() {
        var v = EvaluationService.judgeTemplateLeak("提供的上下文里没有涉及该问题的内容，建议咨询专科医师。");
        assertFalse(v.suspect(), "这条口径是刻意留的边界，别哪天顺手加进正则：" + v.leaks());
    }

    @Test
    @DisplayName("占位块号的小写和 X 变体也认；同一处重复出现只列一次")
    void placeholderVariantsAndDedup() {
        var lower = EvaluationService.judgeTemplateLeak("（摘自《文件名》块n）");
        assertTrue(lower.suspect());
        assertEquals(List.of("《文件名》", "块n"), lower.leaks());
        var x = EvaluationService.judgeTemplateLeak("（摘自《" + XZ + "》块X）");
        assertTrue(x.suspect());
        assertEquals(List.of("块X"), x.leaks(), "真文件名不算占位字，只有块号是");
        var twice = EvaluationService.judgeTemplateLeak("甲（摘自《文件名.pdf》块N）。乙（摘自《文件名.pdf》块N）。");
        assertEquals(List.of("《文件名.pdf》", "块N"), twice.leaks(), "同一个占位字出现两次只列一次");
    }

    @Test
    @DisplayName("空答案如实报没漏，不硬编一个判定")
    void nullAndEmptyAnswerReportHonestly() {
        assertFalse(EvaluationService.judgeTemplateLeak(null).suspect());
        var v = EvaluationService.judgeTemplateLeak("");
        assertFalse(v.suspect());
        assertTrue(v.evidence().contains("没有提示词模板占位字"));
    }

    @Test
    @DisplayName("补的正是内联引用核对的漏检：「块N」那边 total=0，这边必须命中")
    void fillsTheInlineCitationBlindSpot() {
        String leak = "（摘自《文件名.pdf》块N）";
        var inline = EvaluationService.judgeInlineCitations(leak, List.of());
        assertEquals(0, inline.total(), "内联引用正则要求块号是数字，占位字「块N」它认不出——这就是漏检");
        assertTrue(inline.fabricated().isEmpty(), "认不出就不会判编造，报表上完全隐形");
        assertTrue(EvaluationService.judgeTemplateLeak(leak).suspect(), "所以必须有这一层把它显形");
    }

    @Test
    @DisplayName("提示词教的占位形状与探针互相认得（防两边各改各的）")
    void promptPlaceholderAndProbeAgree() throws Exception {
        Field f = ChatController.class.getDeclaredField("SYSTEM_PROMPT");
        f.setAccessible(true);
        String prompt = (String) f.get(null);
        assertTrue(prompt.contains("《文件名.pdf》块N"), "提示词不再用这个占位形状了，探针的正则要跟着改");
        assertTrue(EvaluationService.judgeTemplateLeak("（摘自《文件名.pdf》块N）").suspect(),
                "探针认不出提示词里教的那个占位字");
    }
}