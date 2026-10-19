package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 生产检索模式的收口：HybridRetrievalService.search() 只能拿到「可答性闸门判得动」的通道。
 * <p>
 * 闸门看的是向量侧余弦（hasEvidence），BM25 命中不带 score，所以 mode=bm25 一旦走到生产入口
 * 就等于整条链路没有闸门。这里钉住降级表，防止以后有人把 bm25 分支加回 search()。
 */
class RetrievalModeGuardTest {

    @Test
    @DisplayName("bm25 是评测通道：生产模式配置里出现它就降级成 hybrid")
    void bm25DowngradesToHybridWhenIndexExists() {
        assertEquals("hybrid", HybridRetrievalService.enforceGateCapable("bm25", true));
        assertEquals("hybrid", HybridRetrievalService.enforceGateCapable("BM25", true));
    }

    @Test
    @DisplayName("BM25 索引不可用时 bm25 降到 vector，不会降到一个同样依赖索引的 hybrid")
    void bm25DowngradesToVectorWhenIndexMissing() {
        assertEquals("vector", HybridRetrievalService.enforceGateCapable("bm25", false));
    }

    @Test
    @DisplayName("索引不可用 = 没有倒排表，除 vector 外一律退回 vector（改造前的退路）")
    void everythingNeedsTheIndexExceptVector() {
        assertEquals("vector", HybridRetrievalService.enforceGateCapable("hybrid", false));
        assertEquals("vector", HybridRetrievalService.enforceGateCapable("rerank", false));
        assertEquals("vector", HybridRetrievalService.enforceGateCapable("vector", false));
    }

    @Test
    @DisplayName("vector / hybrid / 其它模式名原样透传，改动只针对 bm25")
    void otherModesPassThrough() {
        assertEquals("vector", HybridRetrievalService.enforceGateCapable("vector", true));
        assertEquals("hybrid", HybridRetrievalService.enforceGateCapable("hybrid", true));
        assertEquals("rerank", HybridRetrievalService.enforceGateCapable("rerank", true));
        assertEquals("elasticsearch", HybridRetrievalService.enforceGateCapable("elasticsearch", true));
    }

    @Test
    @DisplayName("没配 / 配空 = vector，且任何输入的输出都不可能是 bm25")
    void noInputCanProduceAnUngatedMode() {
        List<String> inputs = List.of("vector", "bm25", "hybrid", "rerank", "BM25", "unknown", "");
        for (String in : inputs) {
            for (boolean index : new boolean[]{true, false}) {
                assertNotEquals("bm25", HybridRetrievalService.enforceGateCapable(in, index),
                        "输入 " + in + "（索引可用=" + index + "）漏出了免闸门模式");
            }
        }
        // 空串不认识，落到 search() 的 default 分支 = 带闸门的向量通道，方向是安全的
        assertEquals("", HybridRetrievalService.enforceGateCapable("", true));
        assertEquals("vector", HybridRetrievalService.enforceGateCapable(null, true));
    }
}
