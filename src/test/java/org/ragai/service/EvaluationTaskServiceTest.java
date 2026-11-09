package org.ragai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步评估任务表：报告 JSON 的字段形状必须和同步时代逐字一致（前端照旧渲染），
 * 而且同一时刻只能有一轮在跑——一次全量是 61 题真金白银，连点两次等于烧两份。
 * 另加一条：DONE 的报告要落盘，重启后钱已经花了但回执不能跟着没。
 */
class EvaluationTaskServiceTest {

    @TempDir
    Path reportDir;

    private EvaluationService stubService(long sleepMs) {
        EvaluationService svc = Mockito.mock(EvaluationService.class);
        Mockito.when(svc.runEvaluation(Mockito.anyList())).thenAnswer(inv -> {
            if (sleepMs > 0) {
                Thread.sleep(sleepMs);
            }
            return oneReport();
        });
        return svc;
    }

    private EvaluationService.EvaluationReport oneReport() {
        EvaluationService.TestResult pass = new EvaluationService.TestResult();
        pass.question = "高血压诊断界值是多少？";
        pass.category = "事实查询";
        pass.shouldBeAnswerable = true;
        pass.accuracyPass = true;
        pass.referencePass = true;
        pass.hallucinationPass = true;
        pass.compliancePass = true;
        pass.accuracyEvidence = "140→中国高血压防治指南.pdf#12（答案块，上下文第3块）";

        EvaluationService.TestResult fail = new EvaluationService.TestResult();
        fail.question = "超过多少才算高血压？";
        fail.category = "口语查询";
        fail.shouldBeAnswerable = true;
        fail.accuracyPass = true;
        fail.referencePass = true;
        fail.hallucinationPass = true;
        fail.compliancePass = false;
        fail.failReason = "合规性：写了「即可诊断为高血压」";

        EvaluationService.EvaluationReport r = new EvaluationService.EvaluationReport();
        r.totalCount = 2;
        r.passCount = 1;
        r.passRate = 50.0;
        r.accuracyPassCount = 2;
        r.referencePassCount = 2;
        r.hallucinationPassCount = 2;
        r.compliancePassCount = 1;
        r.backfillSuspectCount = 0;
        r.wrongBlockCount = 0;
        r.results = List.of(pass, fail);
        r.categoryStats = Map.of("事实查询", 1, "口语查询", 0);
        r.summary = "口径 2026-10-16 全量 61 题 98.4%（本条为测试构造）";
        return r;
    }

    @Test
    @DisplayName("报告字段形状与同步时代一致：六维、按类别统计、挂题才带 fail_reason")
    void reportJsonKeepsLegacyShape() throws Exception {
        EvaluationTaskService svc = new EvaluationTaskService(stubService(0), Runnable::run, reportDir.toString());
        String json = svc.buildReportJson(oneReport(), 394_300L, "全量");

        // 同步时代这个 Map 是 Spring MVC 默认的紧凑序列化，落盘保持同样写法，别自创缩进格式
        assertTrue(json.contains("\"subset_filter\":\"全量\""));
        assertTrue(json.contains("\"pass_rate\":\"50.0%\""));
        assertTrue(json.contains("\"duration_ms\":394300"));
        for (String key : List.of("答案准确性", "引用正确性", "幻觉抑制", "合规性",
                "库外背书（答案写了但上下文没有）", "出处不在答案块（有出处但答错段落）")) {
            assertTrue(json.contains(key), "少了维度 " + key);
        }
        // 通过的题不该有 fail_reason，挂的题必须有
        assertEquals(1, json.split("fail_reason", -1).length - 1);
        assertTrue(json.contains("140→中国高血压防治指南.pdf#12"));
    }

    @Test
    @DisplayName("提交即返回、跑完落回执：DONE 后能取到报告全文")
    void submitRunsAsyncAndStoresReport() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        EvaluationTaskService svc = new EvaluationTaskService(stubService(30), executor, reportDir.toString());
        EvaluationTaskService.EvalTask task = svc.submit(List.of());
        assertEquals("全量", task.subset);
        assertNotNull(svc.find(task.taskId));
        assertSame(task, svc.submit(List.of()), "还没跑完就重复提交，应当拿回同一个任务");

        // 等后台线程收工，而不是猜一个轮询预算：整套测试首次跑时 Mockito 要先做一次字节码增强，
        // 那 1 秒的 sleep 循环会被它吃掉，于是偶发「state 还没翻就断言」。
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "后台任务 10s 没跑完");

        EvaluationTaskService.EvalTask done = svc.find(task.taskId);
        assertEquals("DONE", done.state, () -> "state=" + done.state + "  errorMsg=" + done.errorMsg);
        assertNotNull(done.reportJson);
        assertTrue(done.reportJson.contains("\"total_count\":2"), done.reportJson);
        assertTrue(done.finishedAt >= done.startedAt,
                () -> "看到 DONE 时 finishedAt 还没落地：" + done.startedAt + " / " + done.finishedAt);
    }

    @Test
    @DisplayName("DONE 之后报告落盘：文件名带 taskId，内容和回执逐字一致")
    void doneReportLandsOnDisk() throws IOException {
        EvaluationTaskService svc = new EvaluationTaskService(stubService(0), Runnable::run, reportDir.toString());
        EvaluationTaskService.EvalTask task = svc.submit(List.of());

        assertNotNull(task.reportFile, "state 已经 DONE，报告路径就该同时落地");
        Path file = Path.of(task.reportFile);
        assertTrue(file.getFileName().toString().startsWith("report-"),
                () -> "文件名不对：" + file.getFileName());
        assertTrue(file.getFileName().toString().endsWith(task.taskId + ".json"),
                () -> "文件名要能回指 taskId：" + file.getFileName());
        assertEquals(task.reportJson, Files.readString(file), "落盘的那份和接口给的那份必须同一个字节");
        assertEquals(50.0, Double.parseDouble(new ObjectMapper()
                .readTree(Files.readString(file)).path("pass_rate").asText().replace("%", "")), 0.001);
    }

    @Test
    @DisplayName("落盘写不进去只降级成日志：不许把已经花完钱的一轮判成 FAILED")
    void unwritableReportDirKeepsTaskDone() {
        Path blocked = reportDir.resolve("not-a-dir");
        try {
            Files.writeString(blocked, "占位：同名文件挡住目录，createDirectories 必挂");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        EvaluationTaskService svc = new EvaluationTaskService(stubService(0), Runnable::run, blocked.toString());
        EvaluationTaskService.EvalTask task = svc.submit(List.of());

        assertEquals("DONE", task.state, () -> "落盘失败不该改判：" + task.errorMsg);
        assertNull(task.reportFile);
        assertNotNull(task.reportJson, "内存回执仍是完整的");
    }

    @Test
    @DisplayName("一轮还在跑时重复 submit 拿回同一个任务，不开第二份钱")    void duplicateSubmitReturnsInFlightTask() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        EvaluationService svcMock = Mockito.mock(EvaluationService.class);
        Mockito.when(svcMock.runEvaluation(Mockito.anyList())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return oneReport();
        });
        EvaluationTaskService svc = new EvaluationTaskService(svcMock, Executors.newSingleThreadExecutor(),
                reportDir.toString());

        EvaluationTaskService.EvalTask first = svc.submit(List.of("胆固醇"));
        EvaluationTaskService.EvalTask second = svc.submit(List.of("胆固醇"));
        assertSame(first, second, "在跑的任务没结束就该把它交回去，而不是再起一轮");
        assertEquals("胆固醇", first.subset, "子集标签应当如实带回来");

        release.countDown();
        for (int i = 0; i < 50 && first.isLive(); i++) {
            Thread.sleep(20);
        }
        assertEquals("DONE", first.state);
        // 跑完之后再来一次就该起新任务
        EvaluationTaskService.EvalTask third = svc.submit(List.of("血压"));
        assertTrue(third != first, "已结束的批次不该继续占着去重槽");
    }
}
