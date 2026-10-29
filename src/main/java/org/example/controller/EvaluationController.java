package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.example.service.EvaluationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 医疗RAG系统评估接口
 */
@RestController
@RequestMapping("/evaluation")
@Validated
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final org.example.service.RecallEvaluationService recallEvaluationService;
    private final org.example.service.HybridRetrievalService hybridRetrievalService;
    private final ObjectMapper jsonMapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    @Autowired
    public EvaluationController(EvaluationService evaluationService,
                                org.example.service.RecallEvaluationService recallEvaluationService,
                                org.example.service.HybridRetrievalService hybridRetrievalService) {
        this.evaluationService = evaluationService;
        this.recallEvaluationService = recallEvaluationService;
        this.hybridRetrievalService = hybridRetrievalService;
    }

    /**
     * 看生产链路真正检索到了什么：直接走 HybridRetrievalService.search()（受 mode / rerank-enabled 支配），
     * 只召回不生成。⚠️ 不是零调用——每次至少打一遍向量通道（text-embedding-v3），
     * 生产开着 rerank-enabled 时还要多打一次 gte-rerank-v2；零调用的对照口子是 /evaluation/recall?mode=bm25。
     */
    @GetMapping(value = "/retrieve", produces = "text/plain; charset=utf-8")
    public String retrieve(@RequestParam @NotBlank(message = "查询词不能为空") String q,
                           @RequestParam(defaultValue = "8") @Min(1) @Max(100) int topK) {
        long start = System.currentTimeMillis();
        var docs = hybridRetrievalService.search(q, topK, null);
        StringBuilder sb = new StringBuilder();
        sb.append("mode=").append(hybridRetrievalService.describeMode())
                .append("  请求topK=").append(topK).append("  实际返回=").append(docs.size())
                .append("  重排查询串=").append(hybridRetrievalService.isRerankUseExpandedQuery() ? "扩展后" : "原话")
                .append("  耗时").append(System.currentTimeMillis() - start).append("ms\n");
        int i = 0;
        for (var d : docs) {
            Object ci = d.getMetadata().get("chunk_index");
            sb.append("  ").append(++i).append(". ").append(d.getMetadata().get("file_name"))
                    .append("#").append(ci).append("  score=").append(d.getScore() == null ? "n/a"
                            : String.format("%.3f", d.getScore())).append('\n');
        }
        return sb.toString();
    }

    /**
     * 只测检索不测生成：召回率基线。expand=true 时叠加相邻块扩展。
     * <p>
     * ⚠️ 调用量按 mode 分档，别当成一律免费：只有 {@code mode=bm25} 零模型调用（走
     * {@code RecallEvaluationService.bm25Probe}，绕开向量通道）；{@code vector} 与 {@code hybrid}／{@code rerank}
     * 都要过检索器，每题打一次 text-embedding-v3；{@code rerank} 在此之上每题再加一次 gte-rerank-v2。
     * 分档依据见 RecallEvaluationService 的 probe 分派与类头说明。
     */
    @GetMapping(value = "/recall", produces = "text/plain; charset=utf-8")
    public String recallBaseline(@RequestParam(defaultValue = "20") @Min(1) @Max(200) int probeK,
                                 @RequestParam(defaultValue = "vector") String mode,
                                 @RequestParam(defaultValue = "false") boolean expand) {
        long start = System.currentTimeMillis();
        System.out.println("[召回基线] 开始，mode=" + mode + " expand=" + expand + " probeK=" + probeK);
        String report = recallEvaluationService.run(probeK, mode, expand);
        System.out.println("[召回基线] 完成，mode=" + mode + " 耗时" + (System.currentTimeMillis() - start) + "ms");
        return report;
    }

    /**
     * 零成本预演：列出 /evaluation/run 会跑到哪些题，不调任何模型接口。
     * 付费重测之前先用它确认子集选对没有——跑错一批题的钱是白花。
     */
    @GetMapping(value = "/select", produces = "text/plain; charset=utf-8")
    public String selectQuestions(@RequestParam(defaultValue = "") String only) {
        return String.join("\n", evaluationService.matchedQuestions(parseOnly(only)));
    }

    /**
     * only= 是题目片段，逗号分隔；留空 = 全量。片段只在内存里比 contains，不查库。
     */
    private static java.util.List<String> parseOnly(String only) {
        if (only == null || only.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(only.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 运行完整评估，返回JSON格式评估报告
     */
    @GetMapping(value = "/run", produces = "application/json; charset=utf-8")
    public String runEvaluation(@RequestParam(defaultValue = "") String only) throws Exception {
        java.util.List<String> subset = parseOnly(only);
        System.out.println("[评估] 开始运行医疗测试集评估...  子集片段=" + (subset.isEmpty() ? "（全量）" : subset.toString()));
        long startTime = System.currentTimeMillis();

        EvaluationService.EvaluationReport report = evaluationService.runEvaluation(subset);

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("[评估] 完成，耗时" + duration + "ms，通过率" + report.passRate + "%");

        // 构建简化的JSON输出（避免answer太长）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subset_filter", subset.isEmpty() ? "全量" : String.join(",", subset));
        out.put("total_count", report.totalCount);
        out.put("pass_count", report.passCount);
        out.put("pass_rate", report.passRate + "%");
        out.put("duration_ms", duration);

        Map<String, Object> dimensions = new LinkedHashMap<>();
        dimensions.put("答案准确性", report.accuracyPassCount + "/" + report.totalCount);
        dimensions.put("引用正确性", report.referencePassCount + "/" + report.totalCount);
        dimensions.put("幻觉抑制", report.hallucinationPassCount + "/" + report.totalCount);
        dimensions.put("合规性", report.compliancePassCount + "/" + report.totalCount);
        dimensions.put("库外背书（答案写了但上下文没有）", report.backfillSuspectCount + "/" + report.totalCount);
        dimensions.put("出处不在答案块（有出处但答错段落）", report.wrongBlockCount + "/" + report.totalCount);
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
            item.put("accuracy_evidence", r.accuracyEvidence);
            item.put("backfill_suspect", r.backfillSuspect);
            item.put("wrong_block_suspect", r.wrongBlockSuspect);
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
     * 获取评估摘要（纯文本）。
     * ⚠️ 这个接口同样会真跑一遍评测链路（每题三次付费调用），所以必须带 only= 指定子集；
     * 不带就是全量，别随手点。
     */
    @GetMapping(value = "/summary", produces = "text/plain; charset=utf-8")
    public String getSummary(@RequestParam(defaultValue = "") String only) {
        EvaluationService.EvaluationReport report = evaluationService.runEvaluation(parseOnly(only));
        return report.summary;
    }
}