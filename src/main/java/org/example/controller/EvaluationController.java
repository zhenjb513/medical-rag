package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.EvaluationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 医疗RAG系统评估接口
 */
@RestController
@RequestMapping("/evaluation")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final ObjectMapper jsonMapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    @Autowired
    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /**
     * 运行完整评估，返回JSON格式评估报告
     */
    @GetMapping(value = "/run", produces = "application/json; charset=utf-8")
    public String runEvaluation() throws Exception {
        System.out.println("[评估] 开始运行医疗测试集评估...");
        long startTime = System.currentTimeMillis();

        EvaluationService.EvaluationReport report = evaluationService.runEvaluation();

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("[评估] 完成，耗时" + duration + "ms，通过率" + report.passRate + "%");

        // 构建简化的JSON输出（避免answer太长）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_count", report.totalCount);
        out.put("pass_count", report.passCount);
        out.put("pass_rate", report.passRate + "%");
        out.put("duration_ms", duration);

        Map<String, Object> dimensions = new LinkedHashMap<>();
        dimensions.put("答案准确性", report.accuracyPassCount + "/" + report.totalCount);
        dimensions.put("引用正确性", report.referencePassCount + "/" + report.totalCount);
        dimensions.put("幻觉抑制", report.hallucinationPassCount + "/" + report.totalCount);
        dimensions.put("合规性", report.compliancePassCount + "/" + report.totalCount);
        out.put("dimensions", dimensions);

        out.put("category_stats", report.categoryStats);
        out.put("summary", report.summary);

        // 详细结果（只包含问题和是否通过，不包含完整answer）
        var detailedResults = new java.util.ArrayList<Map<String, Object>>();
        for (EvaluationService.TestResult r : report.results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("question", r.question);
            item.put("category", r.category);
            item.put("should_be_answerable", r.shouldBeAnswerable);
            item.put("accuracy_pass", r.accuracyPass);
            item.put("reference_pass", r.referencePass);
            item.put("hallucination_pass", r.hallucinationPass);
            item.put("compliance_pass", r.compliancePass);
            item.put("all_pass", r.isAllPass());
            if (!r.isAllPass()) {
                item.put("fail_reason", r.failReason);
            }
            detailedResults.add(item);
        }
        out.put("detailed_results", detailedResults);

        return jsonMapper.writeValueAsString(out);
    }

    /**
     * 获取评估摘要（纯文本）
     */
    @GetMapping(value = "/summary", produces = "text/plain; charset=utf-8")
    public String getSummary() {
        EvaluationService.EvaluationReport report = evaluationService.runEvaluation();
        return report.summary;
    }
}