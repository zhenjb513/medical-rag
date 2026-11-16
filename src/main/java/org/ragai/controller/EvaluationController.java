package org.ragai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.ragai.service.EvaluationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    private static final Logger log = LoggerFactory.getLogger(EvaluationController.class);

    private final EvaluationService evaluationService;
    private final org.ragai.service.EvaluationTaskService evaluationTaskService;
    private final org.ragai.service.RecallEvaluationService recallEvaluationService;
    private final org.ragai.service.HybridRetrievalService hybridRetrievalService;
    private final ObjectMapper jsonMapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    @Autowired
    public EvaluationController(EvaluationService evaluationService,
                                org.ragai.service.EvaluationTaskService evaluationTaskService,
                                org.ragai.service.RecallEvaluationService recallEvaluationService,
                                org.ragai.service.HybridRetrievalService hybridRetrievalService) {
        this.evaluationService = evaluationService;
        this.evaluationTaskService = evaluationTaskService;
        this.recallEvaluationService = recallEvaluationService;
        this.hybridRetrievalService = hybridRetrievalService;
    }

    /**
     * 看生产链路真正检索到了什么：直接走 HybridRetrievalService.search()（受 mode / rerank-enabled 支配），
     * 只召回不生成。⚠️ 不是零调用——每次至少打一遍向量通道（text-embedding-v3），
     * 生产开着 rerank-enabled 时还要多打一次 gte-rerank-v2；零调用的对照口子是 /evaluation/recall?mode=bm25。
     * <p>
     * {@code versions=} 是版本白名单（逗号分隔，如 {@code 2023,2021}），取自入库时按文件名解析出的
     * metadata {@code version}（认不出年份的文档落的是 {@code unknown}）。它是**召回之后的后过滤**，
     * 不是库侧条件：先按 topK 召回再剔掉不在白名单里的块，所以过滤后条数通常少于请求的 topK，
     * 想要满额就把 topK 调大。留空 = 不过滤，行为与本参数加入前完全一致。
     */
    @GetMapping(value = "/retrieve", produces = "text/plain; charset=utf-8")
    public String retrieve(@RequestParam @NotBlank(message = "查询词不能为空") String q,
                           @RequestParam(defaultValue = "8") @Min(1) @Max(100) int topK,
                           @RequestParam(defaultValue = "") String versions) {
        long start = System.currentTimeMillis();
        java.util.Set<String> versionFilter = parseVersions(versions);
        var docs = hybridRetrievalService.search(q, topK, null, versionFilter);
        StringBuilder sb = new StringBuilder();
        sb.append("mode=").append(hybridRetrievalService.describeMode())
                .append("  请求topK=").append(topK).append("  实际返回=").append(docs.size());
        if (!versionFilter.isEmpty()) {
            sb.append("  版本过滤=").append(String.join("|", versionFilter))
                    .append("（后过滤，条数少于 topK 属正常）");
        }
        sb.append("  重排查询串=").append(hybridRetrievalService.isRerankUseExpandedQuery() ? "扩展后" : "原话")
                .append("  耗时").append(System.currentTimeMillis() - start).append("ms\n");
        int i = 0;
        for (var d : docs) {
            Object ci = d.metadata().getString("chunk_index");
            sb.append("  ").append(++i).append(". ").append(d.metadata().getString("file_name"))
                    .append("#").append(ci).append("  score=").append(d.metadata().getDouble("score") == null ? "n/a"
                            : String.format("%.3f", d.metadata().getDouble("score"))).append('\n');
        }
        return sb.toString();
    }

    /** versions= 的解析：逗号/顿号分隔、去空白、去重，最多认 8 个值。留空返回空集＝不过滤。 */
    static java.util.Set<String> parseVersions(String versions) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (versions == null || versions.isBlank()) return out;
        for (String part : versions.split("[,，、]")) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
            if (out.size() >= 8) break;
        }
        return out;
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
        log.info("[召回基线] 开始，mode={} expand={} probeK={}", mode, expand, probeK);
        String report = recallEvaluationService.run(probeK, mode, expand);
        log.info("[召回基线] 完成，mode={} 耗时{}ms", mode, System.currentTimeMillis() - start);
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
     * 提交一轮端到端评估，立刻返回 taskId，不在请求线程里等结果。
     * 全量 61 题实测 394s：同步等等于让一个 Tomcat 工作线程挂 7 分钟，而且客户端一断线
     * 报告就没了（2026-10-12 那次 node fetch 默认 300s 超时，钱照烧、明细全丢）。
     */
    @GetMapping(value = "/run", produces = "application/json; charset=utf-8")
    public String runEvaluation(@RequestParam(defaultValue = "") String only) throws Exception {
        org.ragai.service.EvaluationTaskService.EvalTask task = evaluationTaskService.submit(parseOnly(only));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("taskId", task.taskId);
        out.put("state", task.state);
        out.put("subset_filter", task.subset);
        out.put("message", "已在后台运行，用 GET /evaluation/status/" + task.taskId + " 取回执");
        return jsonMapper.writeValueAsString(out);
    }

    /** 评估任务回执：RUNNING 只有已耗时，DONE 带完整报告（字段形状与同步时代一致），FAILED 带错误。 */
    @GetMapping(value = "/status/{taskId}", produces = "application/json; charset=utf-8")
    public String evaluationStatus(@PathVariable String taskId) throws Exception {
        org.ragai.service.EvaluationTaskService.EvalTask task = evaluationTaskService.find(taskId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (task == null) {
            out.put("code", 404);
            out.put("message", "taskId 不存在或已被淘汰（已完结的只留最近 20 个）");
            return jsonMapper.writeValueAsString(out);
        }
        out.put("code", 0);
        out.put("taskId", task.taskId);
        out.put("state", task.state);
        out.put("subset_filter", task.subset);
        out.put("elapsed_ms", (task.finishedAt > 0 ? task.finishedAt : System.currentTimeMillis()) - task.startedAt);
        if ("DONE".equals(task.state)) {
            out.put("report", jsonMapper.readTree(task.reportJson));
            out.put("report_file", task.reportFile);
        }
        if ("FAILED".equals(task.state)) {
            out.put("error", task.errorMsg);
        }
        return jsonMapper.writeValueAsString(out);
    }

    /**
     * 获取评估摘要（纯文本）。只认带 {@code only=} 的子集：
     * 不带就是 61 题真金白银的全量，那条路现在归异步口子 {@code /run} + {@code /status/{taskId}} 管。
     */
    @GetMapping(value = "/summary", produces = "text/plain; charset=utf-8")
    public String getSummary(@RequestParam(defaultValue = "") String only) {
        java.util.List<String> subset = parseOnly(only);
        if (subset.isEmpty()) {
            return "全量摘要改走异步口子了：GET /evaluation/run 提交 → GET /evaluation/status/{taskId} 取 summary。\n"
                    + "这个口子现在只接带 only= 的子集，因为不带 only= 点一次就是 61 题的付费全量。";
        }
        return evaluationService.runEvaluation(subset).summary;
    }
}