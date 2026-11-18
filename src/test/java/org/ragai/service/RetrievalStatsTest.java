package org.ragai.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /rag/retrieval-stats 的汇总口径（1e）：读的是打点测试钉过的那三个 meter，
 * 这里钉「汇总出来的数与打点一致、online/eval 不混、没样本不补假数、没注册表如实说没开」。
 * 零模型调用：向量通道是替身，分数是测试塞进 metadata 的。
 */
class RetrievalStatsTest {

    private static final String Q = "血脂目标值是多少";

    private RagReferenceService rag;
    private SimpleMeterRegistry registry;
    private HybridRetrievalService svc;

    @BeforeEach
    void setUp() {
        rag = Mockito.mock(RagReferenceService.class);
        Bm25IndexService bm25 = Mockito.mock(Bm25IndexService.class);
        RerankService rerank = Mockito.mock(RerankService.class);
        Mockito.when(rerank.rerank(Mockito.anyString(), Mockito.any()))
                .thenAnswer(inv -> inv.getArgument(1));
        registry = new SimpleMeterRegistry();
        svc = new HybridRetrievalService(rag, bm25, rerank, Runnable::run, registry);
    }

    private static Document hit(String id, Double score) {
        Metadata m = new Metadata();
        m.put("id", id);
        m.put("source", "D:/ai-docs/基层指南.pdf");
        m.put("chunk_index", "1");
        m.put("score", score);
        return Document.from("块正文 " + id, m);
    }

    private void recall(List<Document> docs) {
        Mockito.when(rag.search(Mockito.anyString(), Mockito.anyInt(), Mockito.any())).thenReturn(docs);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    private static double num(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).doubleValue();
    }

    @Test
    @DisplayName("放行+拦截+评测各走一遍：汇总数与打点一致，online/eval 分开")
    void statsMirrorTheMeters() {
        recall(List.of(hit("a", 0.75), hit("b", 0.70)));
        svc.search(Q, 5, null);                       // online passed，top1=0.75，context=2
        recall(List.of(hit("c", 0.50)));
        svc.search(Q, 5, null);                       // online blocked，top1=0.50，context=0
        recall(List.of(hit("d", 0.75)));
        svc.searchFused(Q, 5, false);            // eval passed，不进 context.chunks

        Map<String, Object> stats = svc.retrievalStats();
        assertEquals(true, stats.get("enabled"));

        Map<String, Object> online = sub(sub(stats, "gate"), "online");
        assertEquals(1.0, num(online, "passed"));
        assertEquals(1.0, num(online, "blocked"));
        Map<String, Object> eval = sub(sub(stats, "gate"), "eval");
        assertEquals(1.0, num(eval, "passed"));

        Map<String, Object> top1Online = sub(sub(stats, "top1_score"), "online");
        assertEquals(2.0, num(top1Online, "count"));
        assertEquals(0.625, num(top1Online, "mean"), 1e-9);
        assertEquals(0.75, num(top1Online, "max"), 1e-9);
        assertEquals(1.0, num(sub(sub(stats, "top1_score"), "eval"), "count"));

        Map<String, Object> chunks = sub(stats, "context_chunks");
        assertEquals(2.0, num(chunks, "count"), "searchFused 不走 search()，不该进上下文块数分布");
        assertEquals(1.0, num(chunks, "mean"), 1e-9, "(2+0)/2");
        assertEquals(2.0, num(chunks, "max"), 1e-9);
    }

    @Test
    @DisplayName("一次检索都没跑过：enabled=true 但各入口为空、块数 0——不补假数")
    void freshRegistryReportsNoSamples() {
        Map<String, Object> stats = svc.retrievalStats();
        assertEquals(true, stats.get("enabled"));
        assertTrue(sub(stats, "gate").isEmpty(), "没走过的入口不该出现在汇总里");
        assertTrue(sub(stats, "top1_score").isEmpty());
        assertEquals(0.0, num(sub(stats, "context_chunks"), "count"));
    }

    @Test
    @DisplayName("meterRegistry=null：如实报 enabled=false，不抛")
    void nullRegistryReportsDisabled() {
        HybridRetrievalService bare = new HybridRetrievalService(
                rag, Mockito.mock(Bm25IndexService.class), Mockito.mock(RerankService.class),
                Runnable::run, null);
        assertEquals(false, bare.retrievalStats().get("enabled"));
    }
}