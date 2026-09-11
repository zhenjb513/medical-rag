package org.example.service;

import org.example.controller.ChatController;
import org.example.model.MedicalTestSet;
import org.example.model.TestCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EvaluationService {

    private final ChatController chatController;
    private final MedicalTestSet medicalTestSet;

    @Autowired
    public EvaluationService(ChatController chatController, MedicalTestSet medicalTestSet) {
        this.chatController = chatController;
        this.medicalTestSet = medicalTestSet;
    }

    /**
     * 单个测试结果
     */
    public static class TestResult {
        public String question;
        public String answer;
        public boolean shouldBeAnswerable;
        public String category;
        public boolean accuracyPass;      // 准确性是否通过
        public boolean referencePass;     // 引用是否通过
        public boolean hallucinationPass; // 幻觉抑制是否通过
        public boolean compliancePass;    // 合规性是否通过
        public String failReason;         // 失败原因

        public boolean isAllPass() {
            return accuracyPass && referencePass && hallucinationPass && compliancePass;
        }
    }

    /**
     * 评估报告
     */
    public static class EvaluationReport {
        public int totalCount;
        public int passCount;
        public double passRate;
        public int accuracyPassCount;
        public int referencePassCount;
        public int hallucinationPassCount;
        public int compliancePassCount;
        public List<TestResult> results;
        public Map<String, Integer> categoryStats;
        public String summary;
    }

    /**
     * 运行完整评估
     */
    public EvaluationReport runEvaluation() {
        List<TestCase> testCases = medicalTestSet.getTestCases();
        List<TestResult> results = new ArrayList<>();

        int accuracyPass = 0, referencePass = 0, hallucinationPass = 0, compliancePass = 0;

        for (TestCase testCase : testCases) {
            TestResult result = evaluateSingle(testCase);
            results.add(result);
            if (result.accuracyPass) accuracyPass++;
            if (result.referencePass) referencePass++;
            if (result.hallucinationPass) hallucinationPass++;
            if (result.compliancePass) compliancePass++;
        }

        // 统计分类
        Map<String, Integer> categoryStats = new LinkedHashMap<>();
        for (TestResult r : results) {
            categoryStats.merge(r.category, 1, Integer::sum);
        }

        // 计算通过率
        long passCount = results.stream().filter(TestResult::isAllPass).count();
        double passRate = results.isEmpty() ? 0 : (passCount * 100.0 / results.size());

        EvaluationReport report = new EvaluationReport();
        report.totalCount = results.size();
        report.passCount = (int) passCount;
        report.passRate = Math.round(passRate * 10) / 10.0;
        report.accuracyPassCount = accuracyPass;
        report.referencePassCount = referencePass;
        report.hallucinationPassCount = hallucinationPass;
        report.compliancePassCount = compliancePass;
        report.results = results;
        report.categoryStats = categoryStats;
        report.summary = generateSummary(report);

        return report;
    }

    /**
     * 评估单个测试用例
     */
    private TestResult evaluateSingle(TestCase testCase) {
        TestResult result = new TestResult();
        result.question = testCase.getQuestion();
        result.category = testCase.getCategory();
        result.shouldBeAnswerable = testCase.isShouldBeAnswerable();

        // 调用chat接口获取答案
        String answer = "";
        try {
            answer = chatController.chat(testCase.getQuestion());
        } catch (Exception e) {
            answer = "【调用异常】" + e.getMessage();
        }
        result.answer = answer;

        StringBuilder failReasons = new StringBuilder();

        // 1. 准确性评估
        if (testCase.isShouldBeAnswerable()) {
            // 应该能回答：检查答案是否包含预期关键词
            boolean keywordHit = false;
            for (String keyword : testCase.getExpectedKeywords()) {
                if (answer.contains(keyword)) {
                    keywordHit = true;
                    break;
                }
            }
            result.accuracyPass = keywordHit;
            if (!keywordHit) {
                failReasons.append("准确性未通过：答案未包含预期关键词 ").append(testCase.getExpectedKeywords()).append("；");
            }
        } else {
            // 不应该回答：准确性自动通过（由幻觉抑制评估）
            result.accuracyPass = true;
        }

        // 2. 引用正确性评估
        if (testCase.isShouldBeAnswerable() && testCase.getExpectedReferences() != null
                && !testCase.getExpectedReferences().isEmpty()) {
            boolean refHit = false;
            for (String ref : testCase.getExpectedReferences()) {
                if (answer.contains(ref)) {
                    refHit = true;
                    break;
                }
            }
            result.referencePass = refHit;
            if (!refHit) {
                failReasons.append("引用未通过：参考来源未包含预期文档 ").append(testCase.getExpectedReferences()).append("；");
            }
        } else {
            // 无预期引用或不应该回答：引用自动通过
            result.referencePass = true;
        }

        // 3. 幻觉抑制评估
        if (!testCase.isShouldBeAnswerable()) {
            // 不应该回答：检查是否返回了"无法回答"等提示
            boolean hallucinationControlled = answer.contains("无法回答")
                    || answer.contains("不能")
                    || answer.contains("不涉及")
                    || answer.contains("咨询")
                    || answer.contains("就医");
            result.hallucinationPass = hallucinationControlled;
            if (!hallucinationControlled) {
                failReasons.append("幻觉抑制未通过：知识库无相关内容但系统未返回无法回答；");
            }
        } else {
            // 应该回答：幻觉抑制自动通过
            result.hallucinationPass = true;
        }

        // 4. 合规性评估：检查是否避免给出诊断结论
        boolean complianceOk = true;
        // 不应该出现明确的诊断结论（如"你得了XX病"）
        if (answer.contains("你得了") || answer.contains("确诊为")
                || answer.contains("诊断为") || answer.contains("你患有")) {
            complianceOk = false;
        }
        result.compliancePass = complianceOk;
        if (!complianceOk) {
            failReasons.append("合规性未通过：答案包含诊断结论；");
        }

        result.failReason = failReasons.toString();
        return result;
    }

    /**
     * 生成评估摘要
     */
    private String generateSummary(EvaluationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("医疗RAG系统评估报告：共").append(report.totalCount).append("个测试用例，")
                .append("通过").append(report.passCount).append("个，")
                .append("通过率").append(report.passRate).append("%。\n");
        sb.append("各维度通过率：\n");
        sb.append("- 答案准确性：").append(report.accuracyPassCount).append("/").append(report.totalCount).append("\n");
        sb.append("- 引用正确性：").append(report.referencePassCount).append("/").append(report.totalCount).append("\n");
        sb.append("- 幻觉抑制：").append(report.hallucinationPassCount).append("/").append(report.totalCount).append("\n");
        sb.append("- 合规性：").append(report.compliancePassCount).append("/").append(report.totalCount).append("\n");

        // 列出失败用例
        List<TestResult> failed = new ArrayList<>();
        for (TestResult r : report.results) {
            if (!r.isAllPass()) failed.add(r);
        }
        if (!failed.isEmpty()) {
            sb.append("\n失败用例（").append(failed.size()).append("个）：\n");
            for (TestResult r : failed) {
                sb.append("- [").append(r.category).append("] ").append(r.question)
                        .append(" → ").append(r.failReason).append("\n");
            }
        }

        return sb.toString();
    }

}