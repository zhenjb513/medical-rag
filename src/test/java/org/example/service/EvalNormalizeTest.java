package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判分前的文本归一口径（EvaluationService.normalizeForMatch）。
 * 断言与探针 scratch/EvalNormCheck.java（14 条）同源，用例文本取自单题端到端实测答案原文（scratch/chat1.txt 第 17 行）。
 * 纯静态方法，不连库、不打模型。
 */
class EvalNormalizeTest {

    private static String norm(String s) {
        return EvaluationService.normalizeForMatch(s);
    }

    private static void assertKeywordHits(String keyword, String answer) {
        assertTrue(norm(answer).contains(norm(keyword)),
                () -> "关键词[" + keyword + "] 归一后应命中答案，答案归一=" + norm(answer));
    }

    private static void assertKeywordMisses(String keyword, String answer) {
        assertFalse(norm(answer).contains(norm(keyword)),
                () -> "关键词[" + keyword + "] 不该命中答案，答案归一=" + norm(answer));
    }

    @Test
    @DisplayName("实测答案里的 U+2013 连接符要能命中考卷关键词 4~6周")
    void realAnswerDashMatchesKeyword() {
        String realAnswer = "所有调整用药均需在医生指导下进行，并于用药4–6周内复查血脂、肝酶和肌酸激酶";
        assertKeywordHits("4~6周", realAnswer);
        assertKeywordHits("复查", realAnswer);
        assertKeywordMisses("2~4周", realAnswer);
    }

    @Test
    @DisplayName("波浪号/破折号全家族归一成 ~")
    void dashFamilyCollapses() {
        assertEquals("20%~30%", norm("20%–30%"));
        assertKeywordHits("20%~30%", "被动吸烟使冠心病风险增加20%~30%");
        assertKeywordHits("20%~30%", "风险增加 20%–30%");
        assertKeywordHits("20%~30%", "风险增加20%-30%");
        assertKeywordHits("20%~30%", "风险增加20%—30%");
        assertKeywordHits("20%~30%", "风险增加20%～30%");
    }

    @Test
    @DisplayName("空白（含全角空格）一律删除，库里无空格的数值串能命中模型加空格的写法")
    void whitespaceRemoved() {
        assertKeywordHits("4.5mmol/L", "胆酸螯合剂禁用于TG > 4.5 mmol/L者");
        assertKeywordHits("140/90", "诊室血压 ≥ 140 / 90 mmHg");
        assertKeywordHits("2.45亿", "现有高血压患者约 2.45 亿人");
        assertEquals("", norm(" 　 ­\n\t"));
    }

    @Test
    @DisplayName("归一救不了的两类改写：单百分号区间、中文连接词（口径边界，别指望加规则能救）")
    void knownBlindSpots() {
        assertKeywordMisses("20%~30%", "风险增加20–30%");
        assertKeywordMisses("20%~30%", "风险增加20%至30%");
    }

    @Test
    @DisplayName("null 归一成空串，不抛异常")
    void nullBecomesEmpty() {
        assertEquals("", norm(null));
    }
}
