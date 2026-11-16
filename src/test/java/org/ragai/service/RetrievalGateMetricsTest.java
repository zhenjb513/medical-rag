package org.ragai.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线上检索的可观测性（D1 最小档）：闸门判了什么、最像的那条有多像、最后真给了模型几块。
 * <p>
 * 这三件事以前只落在日志里，想回答「这一周闸门拦掉了多少比例」只能 grep。
 * 打点本身不会报错——写错 tag 名、把评测跑分混进线上分母，指标照样出数，只是数的是另一回事。
 * 所以这里钉的是<b>标签形状</b>和<b>计数口径</b>，不是阈值取多少。
 * <p>
 * 一次模型调用都没有：向量通道是 {@link RagReferenceService} 的替身，闸门比的是测试自己塞进
 * metadata 的余弦值；阈值用 {@code answerFloor} 的字段默认值 0.60，用例里的分数都离它够远
 * （0.50 / 0.75），不卡边界。
 */
class RetrievalGateMetricsTest {

    private static final String Q = "血脂目标值是多少";

    private RagReferenceService rag;
    private Bm25IndexService bm25;
    private RerankService rerank;
    private SimpleMeterRegistry registry;
    private HybridRetrievalService svc;

    @BeforeEach
    void setUp() {
        rag = Mockito.mock(RagReferenceService.class);
        bm25 = Mockito.mock(Bm25IndexService.class);
        rerank = Mockito.mock(RerankService.class);
        Mockito.when(rerank.rerank(Mockito.anyString(), Mockito.any()))
                .thenAnswer(inv -> inv.getArgument(1));
        registry = new SimpleMeterRegistry();
        // 同步执行器：hybrid 的两个 supplyAsync 在当前线程跑完，断言不用等异步
        svc = new HybridRetrievalService(rag, bm25, rerank, Runnable::run, registry);
    }

    private static Document hit(String id, String version, Double score) {
        Metadata m = new Metadata();
        m.put("id", id);
        m.put("source", "D:/ai-docs/基层指南.pdf");
        m.put("chunk_index", "1");
        if (version != null) m.put("version", version);
        if (score != null) m.put("score", score);
        return Document.from("块正文 " + id, m);
    }

    private void recall(List<Document> docs) {
        Mockito.when(rag.search(Mockito.anyString(), Mockito.anyInt(), Mockito.any())).thenReturn(docs);
    }

    /** 没记过的序列按 0 算——断言写的是「这条线今天没被加过」，不是「这个 meter 存不存在」。 */
    private double gateCount(String entry, String result) {
        Counter c = registry.find(HybridRetrievalService.GATE_COUNTER)
                .tag("entry", entry).tag("result", result).counter();
        return c == null ? 0.0 : c.count();
    }

    private DistributionSummary top1(String entry) {
        return registry.find(HybridRetrievalService.TOP1_SUMMARY).tag("entry", entry).summary();
    }

    private static void assertSamples(DistributionSummary s, int count, double total) {
        assertNotNull(s, "闸门被调用过就该有这条分布，否则看板只能靠 query 补 0");
        assertEquals(count, s.count());
        assertEquals(total, s.totalAmount(), 1e-9);
    }

    // ===== ① 闸门：拦与放要能分开数 =====

    @Test
    @DisplayName("放行：entry=online result=passed 计一次，top1 分数进分布")
    void passedGateIsTagged() {
        recall(List.of(hit("a", "2023", 0.75), hit("b", "2023", 0.70)));

        List<Document> out = svc.search(Q, 5, null);

        assertEquals(2, out.size(), "第一名够像就该把候选原样交出去");
        assertEquals(1.0, gateCount("online", "passed"));
        assertEquals(0.0, gateCount("online", "blocked"), "放行的那次不该同时在拦截一侧留下计数");
        assertSamples(top1("online"), 1, 0.75);
    }

    @Test
    @DisplayName("拦下：闸门计数 +1，那个不够像的分数也照记（拦的时候才最需要看分布）")
    void blockedGateKeepsTheScore() {
        recall(List.of(hit("a", "2023", 0.50)));

        List<Document> out = svc.search(Q, 5, null);

        assertTrue(out.isEmpty(), "第一名不够像就整批当没资料");
        assertEquals(1.0, gateCount("online", "blocked"));
        assertEquals(0.0, gateCount("online", "passed"));
        assertSamples(top1("online"), 1, 0.50);
    }

    @Test
    @DisplayName("一条都没召回到：只计拦截、分布里 0 个样本——「没查到」和「不够像」是两件事")
    void emptyRecallCountsAsBlockedWithoutAScore() {
        recall(List.of());

        assertTrue(svc.search(Q, 5, null).isEmpty());

        assertEquals(1.0, gateCount("online", "blocked"));
        assertSamples(top1("online"), 0, 0.0);
    }

    @Test
    @DisplayName("计的是检索次数不是提问次数：调两次就 +2")
    void counterCountsRetrievalsNotQuestions() {
        recall(List.of(hit("a", "2023", 0.75)));

        svc.search(Q, 5, null);
        svc.search(Q, 5, null);

        // 多跳开着时一次提问会调 search() 好几轮，所以这个数不能直接当「今天答了多少题」
        assertEquals(2.0, gateCount("online", "passed"));
    }

    // ===== ② 评测口子不能混进线上分母 =====

    @Test
    @DisplayName("searchFused / searchReranked 打的是 entry=eval，线上那条线不被跑分污染")
    void evalEntryIsTaggedSeparately() {
        recall(List.of(hit("a", "2023", 0.75)));

        svc.searchFused(Q, 5, false);
        svc.searchReranked(Q, 5, false);

        assertEquals(2.0, gateCount("eval", "passed"), "两个评测口子各计一次");
        assertEquals(0.0, gateCount("online", "passed"),
                "跑一遍 61 题基线就把线上闸门率带偏，这个 tag 就是为了把两者分开");
        assertSamples(top1("eval"), 2, 1.50);
        assertNull(top1("online"), "评测跑分不该在线上那条分布里留下任何样本");
    }

    // ===== ③ 最后交给模型的块数：闸门放行 ≠ 上下文没被砍 =====

    @Test
    @DisplayName("context.chunks 记的是版本过滤之后的条数，不是召回条数、也不是 topK")
    void contextChunksCountsWhatSurvivesTheFilters() {
        recall(List.of(hit("a", "2023", 0.75), hit("b", "2021", 0.74), hit("c", "2021", 0.73)));

        assertEquals(1, svc.search(Q, 5, null, Set.of("2023")).size(), "放行但只留下 1 块");
        assertEquals(3, svc.search(Q, 5, null).size(), "不过滤时 3 块全给");

        DistributionSummary chunks = registry.find(HybridRetrievalService.CONTEXT_CHUNKS).summary();
        assertSamples(chunks, 2, 4.0);
    }

    @Test
    @DisplayName("被闸门拦下时 context.chunks 记 0，不然这条分布只剩「有资料」的那几次")
    void blockedRetrievalRecordsZeroChunks() {
        recall(List.of(hit("a", "2023", 0.50)));

        svc.search(Q, 5, null);

        assertSamples(registry.find(HybridRetrievalService.CONTEXT_CHUNKS).summary(), 1, 0.0);
    }

    // ===== ④ 没有注册表时行为不变 =====

    @Test
    @DisplayName("meterRegistry=null 时照常出结果，不打点也不抛")
    void nullRegistryDoesNotChangeRetrieval() {
        recall(List.of(hit("a", "2023", 0.75)));
        HybridRetrievalService bare = new HybridRetrievalService(rag, bm25, rerank, Runnable::run, null);

        assertEquals(1, bare.search(Q, 5, null).size());
    }

    @Test
    @DisplayName("指标名与 tag 值是看板上写死的字面量，改名要两边一起改")
    void meterNamesAreStableContract() {
        recall(List.of(hit("a", "2023", 0.75)));
        svc.search(Q, 5, null);

        assertEquals("rag.retrieval.gate", HybridRetrievalService.GATE_COUNTER);
        assertEquals("rag.retrieval.top1.score", HybridRetrievalService.TOP1_SUMMARY);
        assertEquals("rag.retrieval.context.chunks", HybridRetrievalService.CONTEXT_CHUNKS);
        assertEquals("online", HybridRetrievalService.ENTRY_ONLINE);
        assertEquals("eval", HybridRetrievalService.ENTRY_EVAL);

        java.util.Set<String> tags = new java.util.TreeSet<>();
        registry.getMeters().forEach(m -> m.getId().getTags()
                .forEach(t -> tags.add(t.getKey() + "=" + t.getValue())));
        assertEquals(java.util.Set.of("entry=online", "result=passed"), tags,
                "多一个或少一个 tag 值，看板上的分组就会跟着变：" + tags);
        assertEquals(3, registry.getMeters().size(),
                () -> "实际注册出来的 meter：" + registry.getMeters());
    }
}
