package org.ragai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 端到端评估的后台任务表。
 *
 * <p>原来 {@code /evaluation/run} 在请求线程里一路跑完全量（实测 394s），代价是两件事：
 * 占着一个 Tomcat 工作线程近 7 分钟，以及客户端一断线就白烧一次钱却拿不到报告
 * （2026-10-12 那次 node fetch 默认 300s 超时就是这么丢的）。现在改成提交即返回 taskId，
 * 报告落在任务表里，客户端随时来回执。
 */
@Service
public class EvaluationTaskService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationTaskService.class);
    private static final int KEEP_FINISHED_TASKS = 20;

    public static final class EvalTask {
        public final String taskId;
        public final String subset;
        public volatile String state = "PENDING";
        public volatile long startedAt;
        public volatile long finishedAt;
        public volatile String reportJson;
        public volatile String errorMsg;

        EvalTask(String taskId, String subset) {
            this.taskId = taskId;
            this.subset = subset;
        }

        boolean isLive() {
            return "PENDING".equals(state) || "RUNNING".equals(state);
        }
    }

    private final EvaluationService evaluationService;
    private final Executor ragExecutor;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final Map<String, EvalTask> tasks = new LinkedHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private volatile EvalTask current;

    public EvaluationTaskService(EvaluationService evaluationService,
                                 @Qualifier("ragExecutor") Executor ragExecutor) {
        this.evaluationService = evaluationService;
        this.ragExecutor = ragExecutor;
    }

    /**
     * 提交一轮评估。已经有在跑的就把它交回来——一次全量是 61 题真金白银，连点两次等于烧两份。
     */
    public synchronized EvalTask submit(List<String> subset) {
        EvalTask inFlight = current;
        if (inFlight != null && inFlight.isLive()) {
            return inFlight;
        }
        String label = subset.isEmpty() ? "全量" : String.join(",", subset);
        EvalTask task = new EvalTask("eval-" + seq.incrementAndGet(), label);
        task.startedAt = System.currentTimeMillis();
        tasks.put(task.taskId, task);
        trimFinished();
        current = task;
        ragExecutor.execute(() -> run(task, subset));
        return task;
    }

    public synchronized EvalTask find(String taskId) {
        return tasks.get(taskId);
    }

    private void run(EvalTask task, List<String> subset) {
        task.state = "RUNNING";
        log.info("[评估] 任务 {} 开始运行  子集={}", task.taskId, task.subset);
        long start = System.currentTimeMillis();
        try {
            EvaluationService.EvaluationReport report = evaluationService.runEvaluation(subset);
            long duration = System.currentTimeMillis() - start;
            task.reportJson = buildReportJson(report, duration, task.subset);
            // finishedAt 必须在 state 之前写：轮询方一旦看到 DONE，读到的就该是完整的回执。
            // 反过来（原来写在 finally 里）有一个窗口是「已完成但耗时 0」，页面能把「用时 -」显示出来。
            task.finishedAt = System.currentTimeMillis();
            task.state = "DONE";
            log.info("[评估] 任务 {} 完成，耗时 {}ms，通过率 {}%", task.taskId, duration, report.passRate);
        } catch (Exception e) {
            task.finishedAt = System.currentTimeMillis();
            task.state = "FAILED";
            task.errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("[评估] 任务 {} 失败", task.taskId, e);
        } finally {
            if (current == task) {
                current = null;
            }
        }
    }

    /** 报告 JSON 的形状与改动前逐字段一致，前端照旧渲染。 */
    String buildReportJson(EvaluationService.EvaluationReport report, long durationMs, String subsetLabel)
            throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subset_filter", subsetLabel);
        out.put("total_count", report.totalCount);
        out.put("pass_count", report.passCount);
        out.put("pass_rate", report.passRate + "%");
        out.put("duration_ms", durationMs);

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

        List<Map<String, Object>> detailedResults = new ArrayList<>();
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

    /** 只留最近 {@code KEEP_FINISHED_TASKS} 个已完结任务，插入顺序即完成顺序，从头部淘汰。 */
    private void trimFinished() {
        List<String> removable = new ArrayList<>();
        for (Map.Entry<String, EvalTask> e : tasks.entrySet()) {
            if (tasks.size() - removable.size() <= KEEP_FINISHED_TASKS) {
                break;
            }
            if (!e.getValue().isLive()) {
                removable.add(e.getKey());
            }
        }
        removable.forEach(tasks::remove);
    }
}
