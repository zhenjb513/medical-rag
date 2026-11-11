package org.ragai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMemoryManagerTest {

    @SuppressWarnings("unchecked")
    private final SetOperations<String, String> setOps = Mockito.mock(SetOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> strTemplate = Mockito.mock(RedisTemplate.class);

    private ChatMemoryManager manager() {
        Mockito.when(strTemplate.opsForSet()).thenReturn(setOps);
        Mockito.when(strTemplate.opsForValue()).thenReturn(valueOps);
        return new ChatMemoryManager(strTemplate, 10, 1800L);
    }

    @Test
    @DisplayName("新会话的历史是空的：提示词只有 system + user，且每个元素真是 Message")
    void freshSessionYieldsCleanPrompt() {
        Mockito.when(valueOps.get(Mockito.anyString())).thenReturn(null);
        ChatMemoryManager mgr = manager();

        List<ChatMessage> messages = mgr.buildPromptMessages("sess-new", "你是助手", "降压目标是多少？");

        assertEquals(2, messages.size());
        for (ChatMessage m : messages) {
            assertInstanceOf(ChatMessage.class, m);
        }
        assertEquals("你是助手", ((SystemMessage) messages.get(0)).text());
        assertTrue(((UserMessage) messages.get(1)).singleText().contains("降压目标是多少？"));
    }

    @Test
    @DisplayName("已有历史能正确反序列化并拼进提示词")
    void existingHistoryIsDeserializedAndMerged() {
        List<ChatMessage> stored = List.of(
                UserMessage.from("上一轮问题"),
                AiMessage.from("上一轮回答")
        );
        String json = ChatMessageSerializer.messagesToJson(stored);
        Mockito.when(valueOps.get(Mockito.anyString())).thenReturn(json);

        List<ChatMessage> messages = manager().buildPromptMessages("sess-old", "你是助手", "新问题");

        assertEquals(4, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertInstanceOf(UserMessage.class, messages.get(1));
        assertEquals("上一轮问题", ((UserMessage) messages.get(1)).singleText());
        assertInstanceOf(AiMessage.class, messages.get(2));
        assertEquals("上一轮回答", ((AiMessage) messages.get(2)).text());
        assertInstanceOf(UserMessage.class, messages.get(3));
        assertEquals("新问题", ((UserMessage) messages.get(3)).singleText());
    }

    @Test
    @DisplayName("历史 JSON 损坏时按空历史处理，不抛 500")
    void corruptedJsonFallsBackToEmptyHistory() {
        Mockito.when(valueOps.get(Mockito.anyString())).thenReturn("not-valid-json");

        List<ChatMessage> history = manager().getHistory("sess-corrupt");

        assertTrue(history.isEmpty());
    }

    @Test
    @DisplayName("appendTurn 把历史序列化成 JSON 写回，并续期 TTL")
    void appendTurnSerializesAndStoresJson() {
        Mockito.when(valueOps.get(Mockito.anyString())).thenReturn(null);
        ChatMemoryManager mgr = manager();

        mgr.appendTurn("sess-1", "你好", "你好！有什么可以帮您？");

        Mockito.verify(valueOps).set(Mockito.eq("mkbai:chat:sess-1"),
                Mockito.argThat(json -> json != null && json.contains("你好") && json.contains("你好！")),
                Mockito.eq(Duration.ofSeconds(1800L)));
        Mockito.verify(setOps).add(ChatMemoryManager.SESSION_SET, "sess-1");
    }

    @Test
    @DisplayName("建会话只登记 sessionId：不许往历史里写任何东西")
    void newSessionRegistersIdWithoutTouchingHistory() {
        ChatMemoryManager mgr = manager();
        String sessionId = mgr.newSessionId();

        assertTrue(sessionId.matches("[0-9a-f]{32}"), "sessionId 形状变了前端会遭殃：" + sessionId);
        Mockito.verify(setOps).add(ChatMemoryManager.SESSION_SET, sessionId);
        Mockito.verify(valueOps, Mockito.never())
                .set(Mockito.anyString(), Mockito.anyString(), Mockito.any(Duration.class));
    }
}