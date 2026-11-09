package org.ragai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ragai.controller.ChatController;
import org.springframework.ai.document.Document;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 句子级出处核对（EvaluationService.judgeInlineCitations）的口径守门测试。
 * <p>
 * 钉住三件事：① 内联「摘自《文件名》块N」必须对照本次真正送进模型的块逐条核实，落不上就是编造/抄错；
 * ② 文件名含 '-'（02-WHO...）不能被归一化成 '~' 导致块主键对不上——所以判分用原始答案而不是 normalizeForMatch；
 * ③ 提示词规则3 教的那个形状与判分器正则必须互相认得（反射读 SYSTEM_PROMPT），任何一边单独改掉都挂在这里。
 * <p>
 * 这是诊断项不计入 isAllPass，与 backfill/wrongBlock 同一档；只构造 Document 字段，不连库、不打模型。
 */
class InlineCitationJudgeTest {

    private static final String XZ = "中国血脂管理指南2023年.pdf";
    private static final String WHO = "02-WHO成人高血压药物治疗指南2021.pdf";

    private static Document doc(String file, int chunkIndex) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "/data/pdf/" + file);
        meta.put("file_name", file);
        meta.put("chunk_index", chunkIndex);
        return new Document("id-" + file + "-" + chunkIndex, "正文", meta);
    }

    @Test
    @DisplayName("引用的块真送进模型了：判落在真块上，不算编造")
    void resolvesWhenCitedBlockWasInContext() {
        String answer = "目标值<1.8mmol/L（摘自《" + XZ + "》块40）。";
        var v = EvaluationService.judgeInlineCitations(answer, List.of(doc(XZ, 40), doc(XZ, 41)));
        assertEquals(1, v.total());
        assertEquals(1, v.resolved());
        assertTrue(v.fabricated().isEmpty());
    }

    @Test
    @DisplayName("块号是编的：上下文里没有这一块，判编造/抄错")
    void flagsFabricatedBlockNumber() {
        String answer = "目标值<1.8mmol/L（摘自《" + XZ + "》块999）。";
        var v = EvaluationService.judgeInlineCitations(answer, List.of(doc(XZ, 40)));
        assertEquals(1, v.total());
        assertEquals(0, v.resolved());
        assertEquals(List.of(XZ + "#999"), v.fabricated());
    }

    @Test
    @DisplayName("文件名是编的：块号对但指南名不在上下文，照样判编造")
    void flagsWrongFileName() {
        String answer = "（摘自《中国高血压防治指南2023版.pdf》块40）。";
        var v = EvaluationService.judgeInlineCitations(answer, List.of(doc(XZ, 40)));
        assertEquals(0, v.resolved());
        assertEquals(List.of("中国高血压防治指南2023版.pdf#40"), v.fabricated());
    }

    @Test
    @DisplayName("文件名带 '-' 也能对上：判分用原始文本，不把 '-' 归一成 '~'")
    void fileNameWithDashResolves() {
        String answer = "WHO 目标<130mmHg（摘自《" + WHO + "》块7）。";
        var v = EvaluationService.judgeInlineCitations(answer, List.of(doc(WHO, 7)));
        assertEquals(1, v.resolved(), "02-WHO 的 '-' 若被归一化成 '~'，块主键就对不上了");
        assertTrue(v.fabricated().isEmpty());
    }

    @Test
    @DisplayName("旧格式/拒答没有内联引用：total=0 如实报，不硬编一个判定")
    void noInlineCitationReportsZeroHonestly() {
        var refusal = EvaluationService.judgeInlineCitations(
                RagReferenceService.REFUSAL_PHRASE + "。", List.of(doc(XZ, 40)));
        assertEquals(0, refusal.total());
        assertTrue(refusal.fabricated().isEmpty());
        var oldFormat = EvaluationService.judgeInlineCitations(
                "目标值<1.8mmol/L（见文档1、2）。", List.of(doc(XZ, 40)));
        assertEquals(0, oldFormat.total(), "「见文档N」是旧格式，不是逐句出处标记");
    }

    @Test
    @DisplayName("多条引用混着真假：条数对得上，编造块去重列出")
    void mixedCitationsCountsAddUp() {
        String answer = "甲（摘自《" + XZ + "》块40）。乙（摘自《" + XZ + "》块999）。"
                + "丙（摘自《" + XZ + "》块999）。丁（摘自《" + XZ + "》块41）。";
        var v = EvaluationService.judgeInlineCitations(answer, List.of(doc(XZ, 40), doc(XZ, 41)));
        assertEquals(4, v.total());
        assertEquals(2, v.resolved());
        assertEquals(List.of(XZ + "#999"), v.fabricated(), "同一个编造块出现两次只列一次");
        assertTrue(v.evidence().contains("2 条编造/抄错"), "evidence 要说清几条没落上：" + v.evidence());
    }

    @Test
    @DisplayName("只认「摘自《...》块N」：模型自列清单和页脚不算逐句出处")
    void footerAndChecklistNotCounted() {
        String answer = "结论（摘自《" + XZ + "》块40）。\n引用来源清单：\n- 《" + XZ + "》块40\n"
                + "【引用来源】\n" + XZ + "（块号 40、41）";
        var v = EvaluationService.judgeInlineCitations(answer, List.of(doc(XZ, 40)));
        assertEquals(1, v.total(), "清单和页脚里的块号不该被当成内联引用重复计数");
    }

    @Test
    @DisplayName("提示词规则3 教的形状与判分器正则互相认得（防两边各改各的）")
    void promptTemplateAndJudgeRegexAgree() throws Exception {
        Field f = ChatController.class.getDeclaredField("SYSTEM_PROMPT");
        f.setAccessible(true);
        String prompt = (String) f.get(null);
        assertTrue(prompt.contains("摘自《文件名.pdf》块N"),
                "提示词规则3 不再教「摘自《文件名》块N」这个形状，判分器正则会认空");
        String instance = "（摘自《" + XZ + "》块40）";
        var v = EvaluationService.judgeInlineCitations(instance, List.of(doc(XZ, 40)));
        assertEquals(1, v.total(), "判分器正则认不出提示词教的那个形状的具体实例");
        assertEquals(1, v.resolved());
    }
}