package org.ragai.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * doc_type 后过滤（1d）：语义必须与 version 过滤完全同构——白名单命中留下、留空恒等、
 * 没有键的块永不匹配。两个白名单同时给是交集。零模型调用：过滤的是测试自造的 Document。
 */
class RetrievalDocTypeFilterTest {

    private static Document doc(String id, String version, String docType) {
        Metadata m = new Metadata();
        m.put("id", id);
        m.put("file_name", id + ".pdf");
        if (version != null) m.put("version", version);
        if (docType != null) m.put("doc_type", docType);
        m.put("score", 0.75);
        return Document.from("正文 " + id, m);
    }

    private static List<String> idsOf(List<Document> docs) {
        return docs.stream().map(d -> d.metadata().getString("id")).toList();
    }

    private static final List<Document> POOL = List.of(
            doc("a", "2023", "指南"), doc("b", "2021", "共识"),
            doc("c", "2023", "指南"), doc("d", "2023", null));

    @Test
    @DisplayName("命中白名单的留下，名次顺序不变；多值是并集")
    void keepsWhitelistedInOrder() {
        assertEquals(List.of("a", "c"), idsOf(HybridRetrievalService.applyDocTypeFilter(POOL, Set.of("指南"))));
        assertEquals(List.of("a", "b", "c"),
                idsOf(HybridRetrievalService.applyDocTypeFilter(POOL, Set.of("指南", "共识"))));
    }

    @Test
    @DisplayName("留空＝不过滤，返回同一个 list 实例")
    void blankFilterIsIdentity() {
        assertSame(POOL, HybridRetrievalService.applyDocTypeFilter(POOL, null));
        assertSame(POOL, HybridRetrievalService.applyDocTypeFilter(POOL, Set.of()));
    }

    @Test
    @DisplayName("没有 doc_type 键的块永不匹配——重灌前的旧块全没有这个键，白名单写「其他」也捞不回来")
    void docWithoutDocTypeNeverMatches() {
        assertEquals(List.of(), idsOf(HybridRetrievalService.applyDocTypeFilter(POOL, Set.of("其他"))));
        assertEquals(List.of("e"), idsOf(HybridRetrievalService.applyDocTypeFilter(
                List.of(doc("d", "2023", null), doc("e", "2023", "其他")), Set.of("其他"))),
                "入库认不出类型落的是字符串「其他」，它和「压根没这个键」是两回事");
    }

    @Test
    @DisplayName("白名单命中不了任何块时返回空表")
    void noMatchReturnsEmptyNotNull() {
        assertEquals(List.of(), idsOf(HybridRetrievalService.applyDocTypeFilter(POOL, Set.of("手册"))));
    }

    private static HybridRetrievalService svcReturning(List<Document> raw) {
        RagReferenceService rag = Mockito.mock(RagReferenceService.class);
        Mockito.when(rag.search(Mockito.anyString(), Mockito.anyInt(), Mockito.any())).thenReturn(raw);
        return new HybridRetrievalService(rag, Mockito.mock(Bm25IndexService.class),
                Mockito.mock(RerankService.class), Runnable::run, null);
    }

    @Test
    @DisplayName("五参 search()：version 与 doc_type 两个白名单是交集")
    void bothFiltersAreIntersection() {
        HybridRetrievalService svc = svcReturning(POOL);
        assertEquals(List.of("a", "c"),
                idsOf(svc.search("血脂目标", 5, null, Set.of("2023"), Set.of("指南"))));
        assertEquals(List.of(),
                idsOf(svc.search("血脂目标", 5, null, Set.of("2021"), Set.of("指南"))),
                "b 是 2021 共识：version 命中但类型不命中，交集为空");
    }

    @Test
    @DisplayName("四参 search() 不看 doc_type，老调用点行为不变")
    void fourArgSearchIgnoresDocType() {
        HybridRetrievalService svc = svcReturning(POOL);
        assertEquals(List.of("a", "b", "c", "d"),
                idsOf(svc.search("血脂目标", 5, null, Set.of())),
                "白名单全空时旧块（哪怕没有 doc_type 键）照样返回");
        assertEquals(List.of("a", "c", "d"),
                idsOf(svc.search("血脂目标", 5, null, Set.of("2023"))));
    }
}