package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产检索模式的收口：HybridRetrievalService.search() 只能拿到「可答性闸门判得动」的通道。
 * <p>
 * 闸门看的是向量侧余弦（hasEvidence），BM25 命中不带 score，所以 mode=bm25 一旦走到生产入口
 * 就等于整条链路没有闸门。这里钉住降级表，防止以后有人把 bm25 分支加回 search()。
 * <p>
 * 另外钉住「模式名认不出来时别一声不吭」：{@code unrecognizedModeWarning} 要在启动时喊，
 * {@code executedMode} 要如实说真正执行的是 vector（enforceGateCapable 对陌生写法原样透传，
 * 但 search() 的 switch 只有 hybrid 一个 case，其余全落 default）。
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

    @Test
    @DisplayName("认得出来的三个模式名不打「配置没生效」警告，大小写都算认得")
    void recognizedModeNamesStayQuiet() {
        for (String m : List.of("vector", "hybrid", "bm25", "VECTOR", "Hybrid", "BM25")) {
            assertNull(HybridRetrievalService.unrecognizedModeWarning(m), m + " 是生产/评测都认得的写法，不该报警");
        }
        // 没配 = @Value 的默认值 vector，同样不该弹警告，否则每次正常启动都白喊一句
        assertNull(HybridRetrievalService.unrecognizedModeWarning(null));
        assertNull(HybridRetrievalService.unrecognizedModeWarning("vector"));
    }

    @Test
    @DisplayName("拼错的、评测专用的模式名要喊出来：以前静默按 vector 跑，配置等于没生效")
    void unrecognizedModeNamesAreNamedOutLoud() {
        for (String m : List.of("rerank", "hysrid", "Hybrd", "elasticsearch", "vector-hybrid", " ", "")) {
            String warn = HybridRetrievalService.unrecognizedModeWarning(m);
            assertNotNull(warn, m + " 不认识却一声不吭，日志看着像配置生效了");
            assertTrue(warn.contains(m), "警告里要带上配的那个值，否则没法定位：" + warn);
            assertTrue(warn.contains("vector"), "要说清实际执行的是 vector：" + warn);
        }
    }

    @Test
    @DisplayName("executedMode 如实反映 search() 走的分支：只有 hybrid 走融合，其它全在 default（vector）")
    void executedModeNamesTheRealChannel() {
        assertEquals("vector", HybridRetrievalService.executedMode("vector", true));
        assertEquals("vector", HybridRetrievalService.executedMode("vector", false));
        assertEquals("hybrid", HybridRetrievalService.executedMode("hybrid", true));
        assertEquals("hybrid", HybridRetrievalService.executedMode("BM25", true), "bm25 降级成融合");
        // 索引不可用时 hybrid/bm25 都退回 vector
        assertEquals("vector", HybridRetrievalService.executedMode("hybrid", false));
        assertEquals("vector", HybridRetrievalService.executedMode("bm25", false));
        // 不认识的写法被 enforceGateCapable 原样透传，但 switch 里没有它的 case，真正跑的是 vector
        assertEquals("vector", HybridRetrievalService.executedMode("rerank", true));
        assertEquals("vector", HybridRetrievalService.executedMode("hysrid", true));
        assertEquals("vector", HybridRetrievalService.executedMode("", true));
        assertEquals("vector", HybridRetrievalService.executedMode(null, true));
    }
}
