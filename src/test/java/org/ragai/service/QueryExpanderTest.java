package org.ragai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 口语问法 → 指南术语的扩展守门（2026-11-19 补）。
 * <p>
 * 起因：冒烟实测口语「我爸八十多了，血压降到多少比较合适？」在 {@link QueryExpander} 里无任何触发词，
 * 三路检索都用原话，答案块（基层#21 ≥80岁150/90）被重排压到 top16 外。补的触发词需要：
 * ① 口语真能触发到目标值/高龄/身体活动这类通用术语；② 现有口语规则（坏胆固醇/全家）不被破坏；
 * ③ 书面问法不被新规则带偏（不凭空多出 ≥80岁/达标 这类词）。纯静态逻辑，不连库不打模型。
 */
class QueryExpanderTest {

    @Test
    @DisplayName("口语降压目标+高龄：触发降压目标/高龄/≥80岁/控制目标，且停用词「我爸」被滤掉")
    void oralBpTargetAndElderlyExpand() {
        String exp = QueryExpander.expandForBm25("我爸八十多了，血压降到多少比较合适？");
        assertTrue(exp.contains("降压目标"), exp);
        assertTrue(exp.contains("血压控制目标"), exp);
        assertTrue(exp.contains("高龄"), exp);
        assertTrue(exp.contains("≥80岁"), exp);
        assertTrue(exp.contains("控制目标"), exp);
        assertFalse(exp.contains("我爸"), "口语停用词应被滤掉: " + exp);
    }

    @Test
    @DisplayName("口语「锻炼」→ 身体活动/体力活动/中等强度")
    void colloquialExerciseExpands() {
        String exp = QueryExpander.expandForBm25("想护心血管，一周得锻炼多久才有用？");
        assertTrue(exp.contains("身体活动"), exp);
        assertTrue(exp.contains("体力活动"), exp);
        assertTrue(exp.contains("中等强度"), exp);
    }

    @Test
    @DisplayName("口语「吃法」→ DASH饮食/膳食")
    void colloquialDietExpands() {
        String exp = QueryExpander.expandForBm25("听说有种吃法对血压好，能降压几个毫米汞柱？");
        assertTrue(exp.contains("DASH饮食"), exp);
        assertTrue(exp.contains("膳食"), exp);
    }

    @Test
    @DisplayName("口语「被人呛烟」→ 二手烟/被动吸烟")
    void colloquialSecondHandSmokeExpands() {
        String exp = QueryExpander.expandForBm25("我不抽烟但天天被人呛烟，对心脏有影响吗？");
        assertTrue(exp.contains("二手烟"), exp);
        assertTrue(exp.contains("被动吸烟"), exp);
    }

    @Test
    @DisplayName("口语「有多少人得」→ 患病率/流行病学")
    void colloquialPrevalenceExpands() {
        String exp = QueryExpander.expandForBm25("咱们国家有多少人得高血压？");
        assertTrue(exp.contains("患病率"), exp);
        assertTrue(exp.contains("流行病学"), exp);
    }

    @Test
    @DisplayName("口语「多不多」→ 患病率/流行病学：血脂流行题答案块靠这两个词召回，删了会掉出 top50")
    void colloquialPrevalenceDuoBuDuoExpands() {
        String exp = QueryExpander.expandForBm25("现在血脂高的人多不多？");
        assertTrue(exp.contains("患病率"), exp);
        assertTrue(exp.contains("流行病学"), exp);
    }

    @Test
    @DisplayName("现有口语规则不被破坏：坏胆固醇→LDL-C，全家→FH")
    void existingColloquialRulesStillFire() {
        assertTrue(QueryExpander.expandForBm25("化验单上那个坏胆固醇要降到多少？").contains("LDL-C"));
        assertTrue(QueryExpander.expand("我们全家胆固醇都高，说是遗传的").contains("FH"));
    }

    @Test
    @DisplayName("书面降压目标问法不被新规则带偏：不凭空多出 ≥80岁/达标")
    void writtenBpTargetNotPolluted() {
        String exp = QueryExpander.expandForBm25("高血压患者的降压目标是多少？");
        assertFalse(exp.contains("≥80岁"), "书面一般降压目标不应被加高龄档: " + exp);
        assertFalse(exp.contains("达标"), "书面「目标是多少」不触发「降到多少」: " + exp);
        String exp2 = QueryExpander.expandForBm25("80岁以上高龄高血压患者的降压目标是多少？");
        assertFalse(exp2.contains("≥80岁"), "书面用阿拉伯数字80岁，不触发中文「八十」: " + exp2);
    }

    @Test
    @DisplayName("扩展词与查询里已有的词去重：降压目标已在查询里就不再重复添加")
    void addedTermsDeduplicatesAgainstQuery() {
        List<String> extra = QueryExpander.addedTerms("血压降到多少 降压目标");
        assertFalse(extra.contains("降压目标"), "查询已含降压目标，不应重复添加: " + extra);
        assertTrue(extra.contains("血压控制目标"), extra.toString());
    }

    @Test
    @DisplayName("无触发词时原样返回：expand 不凭空加词，空/null 安全")
    void noTriggerReturnsOriginal() {
        assertEquals("qqqzzz", QueryExpander.expand("qqqzzz"));
        assertTrue(QueryExpander.addedTerms("qqqzzz").isEmpty());
        assertEquals("", QueryExpander.expand(""));
        assertEquals(null, QueryExpander.expand(null));
    }
}