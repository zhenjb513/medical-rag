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
 * 版本号后过滤（C1）里属于检索服务的那一半：{@link HybridRetrievalService#applyVersionFilter} 的语义。
 * 查询串怎么解析、回执怎么写明「后过滤」在 controller 包下的同名测试里。
 * <p>
 * 这个参数在 {@code search()} 上挂了很久但没有任何调用方，所以它原来的状态是「编得过、跑不通」。
 * 接上 HTTP 之后最容易出错的是两件事：留空时的行为被顺手改掉，以及没有 version 的块被当成匹配。
 * <p>
 * 零模型调用：过滤的是测试自己造的 Document 列表。
 */
class RetrievalVersionFilterTest {

    private static Document doc(String id, String version) {
        Metadata m = new Metadata();
        m.put("id", id);
        m.put("file_name", id + ".pdf");
        if (version != null) m.put("version", version);
        return Document.from("正文 " + id, m);
    }

    private static List<String> idsOf(List<Document> docs) {
        return docs.stream().map(d -> d.metadata().getString("id")).toList();
    }

    private static final List<Document> POOL = List.of(
            doc("a", "2023"), doc("b", "2021"), doc("c", "2023"), doc("d", null));

    @Test
    @DisplayName("命中白名单的留下，其余整条剔掉，名次顺序不变")
    void keepsWhitelistedInOrder() {
        assertEquals(List.of("a", "c"), idsOf(HybridRetrievalService.applyVersionFilter(POOL, Set.of("2023"))));
    }

    @Test
    @DisplayName("多个版本一起放行")
    void multipleVersionsAreAUnion() {
        assertEquals(List.of("a", "b", "c"),
                idsOf(HybridRetrievalService.applyVersionFilter(POOL, Set.of("2023", "2021"))));
    }

    @Test
    @DisplayName("留空＝不过滤，而且返回的是同一个 list 实例")
    void blankFilterIsIdentity() {
        assertSame(POOL, HybridRetrievalService.applyVersionFilter(POOL, null));
        assertSame(POOL, HybridRetrievalService.applyVersionFilter(POOL, Set.of()));
    }

    @Test
    @DisplayName("白名单命中不了任何块时返回空表，而不是原表")
    void noMatchReturnsEmptyNotNull() {
        assertEquals(List.of(), idsOf(HybridRetrievalService.applyVersionFilter(POOL, Set.of("1999"))));
    }

    @Test
    @DisplayName("没有 version 键的块永不匹配，白名单写 unknown 也不行")
    void docWithoutVersionNeverMatches() {
        assertEquals(List.of(), idsOf(HybridRetrievalService.applyVersionFilter(POOL, Set.of("unknown"))),
                "把「解析不出年份」当成「知道它是哪一年的」就会让老库混进版本对照的结论里");
        assertEquals(List.of("e"), idsOf(HybridRetrievalService.applyVersionFilter(
                List.of(doc("d", null), doc("e", "unknown")), Set.of("unknown"))),
                "入库时认不出年份落的是字符串 unknown，它和「压根没这个键」是两回事");
    }

    @Test
    @DisplayName("三参 search() 仍按不过滤走，老调用点行为不变")
    void threeArgSearchStillDoesNotFilter() {
        RagReferenceService rag = Mockito.mock(RagReferenceService.class);
        Metadata scored = new Metadata();
        scored.put("id", "a");
        scored.put("version", "2019");
        scored.put("score", 0.75);
        Mockito.when(rag.search(Mockito.anyString(), Mockito.anyInt(), Mockito.any()))
                .thenReturn(List.of(Document.from("正文 a", scored)));

        HybridRetrievalService svc = new HybridRetrievalService(
                rag, Mockito.mock(Bm25IndexService.class), Mockito.mock(RerankService.class),
                Runnable::run, null);

        assertEquals(List.of("a"), idsOf(svc.search("血脂目标", 5, null)),
                "白名单为空时旧块（哪怕是 2019 的）照样返回");
    }
}
