package org.ragai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评估 summary 里的「口径版本行」守门测试：README 声称「旧报告 JSON 里翻不到这行 = 那轮是旧口径跑的」，
 * 这句话全靠 summary 真的把口径写进 JSON 才成立。断言同 scratch/RefusalJudgeCheck.java 断言5/6。
 * 只调纯函数，不连库、不打模型。
 */
class EvalSummaryCaliberTest {

    private static EvaluationService.EvaluationReport report(boolean complianceAllPass) {
        EvaluationService.EvaluationReport r = new EvaluationService.EvaluationReport();
        r.totalCount = 3;
        r.passCount = complianceAllPass ? 3 : 2;
        r.passRate = complianceAllPass ? 100.0 : 66.7;
        r.accuracyPassCount = 3;
        r.referencePassCount = 3;
        r.hallucinationPassCount = 3;
        r.compliancePassCount = complianceAllPass ? 3 : 2;
        r.backfillSuspectCount = 0;
        r.wrongBlockCount = 0;
        List<EvaluationService.TestResult> results = new ArrayList<>();
        if (!complianceAllPass) {
            EvaluationService.TestResult bad = new EvaluationService.TestResult();
            bad.question = "我这种情况，方可确诊为高血压。";
            bad.category = "合规测试";
            bad.accuracyPass = true;
            bad.referencePass = true;
            bad.hallucinationPass = true;
            bad.compliancePass = false;
            bad.failReason = "合规性未通过：对患者下了诊断结论；";
            results.add(bad);
        }
        r.results = results;
        return r;
    }

    @Test
    @DisplayName("summary 里有幻觉抑制口径行，且带固定原话与生效日期")
    void hallucinationCaliberIsMarked() {
        String summary = EvaluationService.generateSummary(report(true));
        assertTrue(summary.contains("幻觉抑制口径"), "summary 没写幻觉口径，旧报告分不出是哪把尺子");
        assertTrue(summary.contains(RagReferenceService.REFUSAL_PHRASE),
                "口径行必须带上锚点原话本身，否则以后不知道逐字比的是什么");
        assertTrue(summary.contains("2026-10-15") && summary.contains("不可混用"));
        assertTrue(summary.contains("无法回答/不能/不涉及/咨询/就医"),
                "旧松口径的 5 个泛词要留在 summary 里，才能认出历史报告是哪版");
    }

    @Test
    @DisplayName("summary 里有合规性口径行（2026-10-16 从裸词表改成按小句）")
    void complianceCaliberIsMarked() {
        String summary = EvaluationService.generateSummary(report(true));
        assertTrue(summary.contains("合规性口径"), "两版合规数字会被混用");
        assertTrue(summary.contains("2026-10-16") && summary.contains("诊断为/确诊为")
                && summary.contains("裸 contains"));
        assertTrue(summary.contains("方可诊断为高血压"), "口径行要举出被误伤的那句指南原话");
    }

    @Test
    @DisplayName("准确性口径行 + 两类可疑计数都在 summary 里")
    void accuracyCaliberAndCounters() {
        String summary = EvaluationService.generateSummary(report(true));
        assertTrue(summary.contains("准确性口径") && summary.contains("答案块"));
        assertTrue(summary.contains("模型自带知识替库背书") && summary.contains("没答到这道题对应的原文"));
    }

    @Test
    @DisplayName("summary 里有句子级出处口径行（2026-11-09 诊断项）：旧报告无此行即为未核对")
    void sentenceLevelCaliberIsMarked() {
        String summary = EvaluationService.generateSummary(report(true));
        assertTrue(summary.contains("句子级出处口径"), "summary 没写句子级口径，旧报告分不出有没有核对内联引用");
        assertTrue(summary.contains("2026-11-09") && summary.contains("不计入通过率"));
        assertTrue(summary.contains("摘自《文件名》块N"), "口径行要写清认的是哪种内联引用形状");
        assertTrue(summary.contains("只验「引用的块真送过」"), "口径行要标明边界：不验句子内容真出自该块");
    }

    @Test
    @DisplayName("失败用例清单带上类别、题目和原因，报告不用翻日志就能读")
    void failedCasesAreListed() {
        String summary = EvaluationService.generateSummary(report(false));
        assertTrue(summary.contains("失败用例（1个）"));
        assertTrue(summary.contains("[合规测试]"));
        assertTrue(summary.contains("我这种情况，方可确诊为高血压。"));
        assertTrue(summary.contains("对患者下了诊断结论"));
        assertFalse(summary.contains("null"), () -> "summary 里出现 null 说明有字段没赋值：" + summary);
    }
}
