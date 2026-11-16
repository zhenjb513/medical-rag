package org.ragai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动期 BM25 对账的差集口径（A1）。
 * <p>
 * {@code reconcileAll()} 靠这两个差集决定「这次启动到底动不动表」：补建组走 refreshSource、
 * 清理组走 deleteSource。算错的方向都不报错——漏了 missing 就是新文件永远进不了关键词索引
 * （词搜不到、意思搜得到），漏了 orphan 就是删掉的文件残留一堆对不上号的倒排行。
 * 所以把纯逻辑抽成 {@code diffSources} 单独钉住，不必连真库。
 */
class Bm25ReconcileTest {

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    @Test
    @DisplayName("两边一致 = 两个差集都空，启动一个字节都不写")
    void unchangedLibraryProducesNoWork() {
        Bm25IndexService.SourceDiff d = Bm25IndexService.diffSources(set("a.pdf", "b.pdf"), set("a.pdf", "b.pdf"));
        assertTrue(d.missing().isEmpty(), "库没变却要点火重建，等于退回 TRUNCATE");
        assertTrue(d.orphan().isEmpty());
    }

    @Test
    @DisplayName("向量表新增的 source 进补建组")
    void newVectorSourceIsMissingFromIndex() {
        Bm25IndexService.SourceDiff d = Bm25IndexService.diffSources(set("a.pdf", "new.pdf"), set("a.pdf"));
        assertEquals(List.of("new.pdf"), d.missing());
        assertTrue(d.orphan().isEmpty());
    }

    @Test
    @DisplayName("倒排表里向量表已经没有了的 source 进清理组")
    void indexRowWithoutVectorRowIsOrphan() {
        Bm25IndexService.SourceDiff d = Bm25IndexService.diffSources(set("a.pdf"), set("a.pdf", "gone.pdf"));
        assertEquals(List.of("gone.pdf"), d.orphan());
        assertTrue(d.missing().isEmpty());
    }

    @Test
    @DisplayName("同一次对账里补建与清理互不影响（改名文件会同时命中两组）")
    void bothDirectionsComputedIndependently() {
        Bm25IndexService.SourceDiff d = Bm25IndexService.diffSources(set("a.pdf", "b.pdf"), set("a.pdf", "old.pdf"));
        assertEquals(List.of("b.pdf"), d.missing());
        assertEquals(List.of("old.pdf"), d.orphan());
    }

    @Test
    @DisplayName("空库/空索引的退化：一边全空时差集退化成另一边全量，不是抛异常")
    void emptySideDegeneratesToFullOtherSide() {
        Bm25IndexService.SourceDiff firstBoot = Bm25IndexService.diffSources(set("a.pdf", "b.pdf"), Set.of());
        assertEquals(2, firstBoot.missing().size(), "首次启动（倒排表是空的）要把每份都补建出来");
        assertTrue(firstBoot.orphan().isEmpty());

        Bm25IndexService.SourceDiff emptyLibrary = Bm25IndexService.diffSources(Set.of(), set("a.pdf"));
        assertTrue(emptyLibrary.missing().isEmpty());
        assertEquals(1, emptyLibrary.orphan().size());
    }

    @Test
    @DisplayName("空 source 字符串照样参与对账——它来自 metadata->>'source' 缺键，不能当没有")
    void emptyStringSourceStillCounts() {
        Bm25IndexService.SourceDiff d = Bm25IndexService.diffSources(set(""), set(""));
        assertTrue(d.missing().isEmpty());
        assertTrue(d.orphan().isEmpty());
    }

    @Test
    @DisplayName("null 集合按空集处理：读库失败退化成「不动」，而不是把整张表判成孤儿")
    void nullInputsAreTreatedAsEmpty() {
        Bm25IndexService.SourceDiff d = Bm25IndexService.diffSources(null, null);
        assertTrue(d.missing().isEmpty());
        assertTrue(d.orphan().isEmpty());

        Bm25IndexService.SourceDiff indexUnknown = Bm25IndexService.diffSources(set("a.pdf"), null);
        assertEquals(List.of("a.pdf"), indexUnknown.missing());
    }
}
