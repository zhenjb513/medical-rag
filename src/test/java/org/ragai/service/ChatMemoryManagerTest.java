package org.ragai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多轮记忆的会话历史。这里的返回值会原样进 {@code new Prompt(messages)}，
 * 而模型侧（DashScopeChatModel.createRequest）对每个元素强转 Message——
 * 所以「列表里混进非 Message」不是脏数据问题，是整条问答链路 500 的问题。
 * 2026-11-05 拍问诊截图时撞上的就是这个：新会话第一条流式消息必然 ClassCastException。
 */
class ChatMemoryManagerTest {

    @SuppressWarnings("unchecked")
    private final ListOperations<String, Object> listOps = Mockito.mock(ListOperations.class);
    @SuppressWarnings("unchecked")
    private final SetOperations<String, Object> setOps = Mockito.mock(SetOperations.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> strTemplate = Mockito.mock(RedisTemplate.class);

    @SuppressWarnings("unchecked")
    private ChatMemoryManager manager() {
        Mockito.when(redisTemplate.opsForList()).thenReturn(listOps);
        Mockito.when(strTemplate.opsForSet()).thenReturn((SetOperations) setOps);
        return new ChatMemoryManager(redisTemplate, strTemplate, 10, 1800L);
    }

    @Test
    @DisplayName("新会话的历史是空的：提示词只有 system + user，且每个元素真是 Message")
    void freshSessionYieldsCleanPrompt() {
        Mockito.when(listOps.range(Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong()))
                .thenReturn(List.of());
        ChatMemoryManager mgr = manager();

        List<Message> messages = mgr.buildPromptMessages("sess-new", "你是助手", "降压目标是多少？");

        assertEquals(2, messages.size());
        // 模型侧就是这么用的：逐个强转，任何一个元素不是 Message 就整轮炸
        for (Message m : messages) {
            assertInstanceOf(Message.class, m);
        }
        assertEquals("你是助手", messages.get(0).getText());
        assertTrue(messages.get(1).getText().contains("降压目标是多少？"));
    }

    @Test
    @DisplayName("Redis 里躺着旧版写进去的 ArrayList 占位：跳过脏元素，保留真历史的顺序")
    void legacySentinelIsSkippedNotPassedThrough() {
        // 旧 newSessionId() 往会话列表右推过一个空 ArrayList，反序列化回来就不是 Message
        List<Object> polluted = new ArrayList<>();
        polluted.add(new ArrayList<>());
        UserMessage earlier = new UserMessage("上一轮问题");
        AssistantMessage earlierAnswer = new AssistantMessage("上一轮回答");
        polluted.add(earlier);
        polluted.add(earlierAnswer);
        Mockito.when(listOps.range(Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong()))
                .thenReturn(polluted);

        List<Message> history = manager().getHistory("sess-dirty");

        assertEquals(2, history.size(), "脏元素该被跳掉，而不是原样交给模型");
        assertSame(earlier, history.get(0));
        assertSame(earlierAnswer, history.get(1));
    }

    @Test
    @DisplayName("建会话只登记 sessionId：不许再往历史列表里塞占位值")
    void newSessionRegistersIdWithoutTouchingHistoryList() {
        ChatMemoryManager mgr = manager();
        String sessionId = mgr.newSessionId();

        assertTrue(sessionId.matches("[0-9a-f]{32}"), "sessionId 形状变了前端会遭殃：" + sessionId);
        Mockito.verify(setOps).add(ChatMemoryManager.SESSION_SET, sessionId);
        Mockito.verify(listOps, Mockito.never())
                .rightPush(Mockito.anyString(), Mockito.any());
    }
}
