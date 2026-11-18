package org.ragai.controller;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.ragai.service.ChatMemoryManager;
import org.ragai.service.HybridRetrievalService;
import org.ragai.service.InventoryService;
import org.ragai.service.MultiHopRetrievalService;
import org.ragai.service.RagReferenceService;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /chat/stream 的 versions=/docTypes= 口子（1d）。钉三件事：
 * ① 解析后的白名单原样进多跳检索；② 留空＝空集＝不过滤；
 * ③ Resilience4j 降级方法签名必须与原方法参数一致＋Throwable——加了请求参数忘改降级方法，
 * 编译照样过，运行时熔断/限流一触发才找不到降级方法。零模型调用：流式模型是替身。
 */
class ChatStreamFilterParamTest {

    private MultiHopRetrievalService multiHop;
    private RagReferenceService ragRef;
    private StreamingChatModel streaming;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        multiHop = Mockito.mock(MultiHopRetrievalService.class);
        ragRef = Mockito.mock(RagReferenceService.class);
        streaming = Mockito.mock(StreamingChatModel.class);
        controller = new ChatController(
                Mockito.mock(ChatModel.class), streaming,
                Mockito.mock(HybridRetrievalService.class), multiHop, ragRef,
                Mockito.mock(ChatMemoryManager.class), Mockito.mock(InventoryService.class),
                new SimpleMeterRegistry());
        ReflectionTestUtils.setField(controller, "chatTopK", 8);
    }

    /** 替身流式模型：吐一行带换行的答案就完成，让 token 流能 flush 出非空答案。 */
    private void stubStreamingAnswer() {
        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1, StreamingChatResponseHandler.class);
            h.onPartialResponse("答案正文\n");
            h.onCompleteResponse(null);
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    private static List<String> dataOf(Flux<ServerSentEvent<String>> flux) {
        return flux.collectList().block().stream().map(ServerSentEvent::data).toList();
    }

    @Test
    @DisplayName("versions=/docTypes= 解析后原样传进多跳检索（全角逗号、顿号都认）")
    void filtersReachTheRetrieval() {
        when(multiHop.search(anyString(), anyInt(), isNull(), any(), any())).thenReturn(List.of());
        when(ragRef.buildContext(any())).thenReturn("");
        when(ragRef.extractReferences(any())).thenReturn(List.of());
        stubStreamingAnswer();

        List<String> events = dataOf(controller.chatStream("血脂目标", null, "2023，2021", "指南、共识"));

        verify(multiHop).search(anyString(), eq(8), isNull(),
                eq(Set.of("2023", "2021")), eq(Set.of("指南", "共识")));
        assertTrue(events.contains("[DONE]"), "流要正常收尾：" + events);
    }

    @Test
    @DisplayName("不带过滤参数时检索收到两个空集＝不过滤，老行为不变")
    void absentFiltersMeanEmptySets() {
        when(multiHop.search(anyString(), anyInt(), isNull(), any(), any())).thenReturn(List.of());
        when(ragRef.buildContext(any())).thenReturn("");
        when(ragRef.extractReferences(any())).thenReturn(List.of());
        stubStreamingAnswer();

        dataOf(controller.chatStream("血脂目标", null, null, null));

        verify(multiHop).search(anyString(), eq(8), isNull(), eq(Set.of()), eq(Set.of()));
    }

    @Test
    @DisplayName("熔断降级走纯检索时也带上同一份白名单")
    void circuitFallbackHonorsFilters() {
        Metadata m = new Metadata();
        m.put("file_name", "a.pdf");
        when(multiHop.search(anyString(), anyInt(), isNull(), any(), any()))
                .thenReturn(List.of(Document.from("正文", m)));
        when(ragRef.buildContext(any())).thenReturn("上下文正文");
        when(ragRef.extractReferences(any())).thenReturn(List.of());

        List<String> events = dataOf(controller.chatStreamCircuitFallback(
                "血脂目标", null, "2023", "指南", new RuntimeException("boom")));

        verify(multiHop).search(anyString(), eq(8), isNull(), eq(Set.of("2023")), eq(Set.of("指南")));
        assertTrue(events.get(0).contains("【服务降级】"), events.toString());
        assertTrue(events.get(0).contains("上下文正文"), events.toString());
    }

    @Test
    @DisplayName("限流降级签名同步后仍返回限流提示")
    void rateLimitFallbackStillWorks() {
        List<String> events = dataOf(controller.chatStreamRateLimitFallback(
                "血脂目标", null, "2023", "指南", new RuntimeException("rate")));
        assertTrue(events.get(0).contains("请求过于频繁"), events.toString());
        assertEquals("[DONE]", events.get(events.size() - 1));
    }

    @Test
    @DisplayName("两个降级方法的参数表＝chatStream 参数表＋Throwable（Resilience4j 按签名匹配，错一个运行时才炸）")
    void fallbackSignaturesMatchEndpoint() {
        Method endpoint = null;
        for (Method m : ChatController.class.getDeclaredMethods()) {
            if (m.getName().equals("chatStream")) endpoint = m;
        }
        Class<?>[] expected = new Class<?>[endpoint.getParameterCount() + 1];
        System.arraycopy(endpoint.getParameterTypes(), 0, expected, 0, endpoint.getParameterCount());
        expected[expected.length - 1] = Throwable.class;
        for (String name : List.of("chatStreamRateLimitFallback", "chatStreamCircuitFallback")) {
            Method fallback = null;
            for (Method m : ChatController.class.getDeclaredMethods()) {
                if (m.getName().equals(name)) fallback = m;
            }
            assertArrayEquals(expected, fallback.getParameterTypes(), name + " 签名与原方法不一致");
        }
    }
}