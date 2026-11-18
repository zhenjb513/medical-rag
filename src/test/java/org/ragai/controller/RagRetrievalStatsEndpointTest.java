package org.ragai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.ragai.service.Bm25IndexService;
import org.ragai.service.HybridRetrievalService;
import org.ragai.service.InventoryService;
import org.ragai.service.PhiAuditService;
import org.ragai.service.RagInitializer;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /rag/retrieval-stats 口子本身（1e）：控制器只做「调服务汇总 + 序列化 JSON」，
 * 汇总口径在 RetrievalStatsTest 里钉。这里钉 JSON 出得去、形状没被序列化改坏。零模型调用。
 */
class RagRetrievalStatsEndpointTest {

    @Test
    @DisplayName("汇总 map 原样序列化成 JSON，嵌套结构不丢")
    void endpointSerializesServiceStats() throws Exception {
        HybridRetrievalService hybrid = Mockito.mock(HybridRetrievalService.class);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", true);
        Map<String, Object> online = new LinkedHashMap<>();
        online.put("passed", 3.0);
        online.put("blocked", 1.0);
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("online", online);
        stats.put("gate", gate);
        Mockito.when(hybrid.retrievalStats()).thenReturn(stats);

        RagController controller = new RagController(
                Mockito.mock(Bm25IndexService.class), Mockito.mock(PhiAuditService.class),
                Mockito.mock(InventoryService.class), Mockito.mock(RagInitializer.class),
                null, Mockito.mock(ApplicationContext.class), hybrid);

        String body = controller.retrievalStats();
        JsonNode json = new ObjectMapper().readTree(body);
        assertTrue(json.get("enabled").asBoolean());
        assertEquals(3.0, json.get("gate").get("online").get("passed").asDouble());
        assertEquals(1.0, json.get("gate").get("online").get("blocked").asDouble());
        Mockito.verify(hybrid).retrievalStats();
    }
}