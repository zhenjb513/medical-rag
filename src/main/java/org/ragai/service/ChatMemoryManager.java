package org.ragai.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class ChatMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryManager.class);

    public static final String KEY_PREFIX = "mkbai:chat:";
    public static final String SESSION_SET = "mkbai:chat:sessions";
    public static final int DEFAULT_MAX_ROUNDS = 10;
    public static final long DEFAULT_TTL_SECONDS = 30 * 60;
    public static final long DEFAULT_TTL_MS = DEFAULT_TTL_SECONDS * 1000L;

    private final RedisTemplate<String, String> strTemplate;
    private final int maxRounds;
    private final long ttlSeconds;

    @Autowired
    public ChatMemoryManager(@Qualifier("chatStrRedisTemplate") RedisTemplate<String, String> strTemplate) {
        this(strTemplate, DEFAULT_MAX_ROUNDS, DEFAULT_TTL_SECONDS);
    }

    public ChatMemoryManager(@Qualifier("chatStrRedisTemplate") RedisTemplate<String, String> strTemplate,
                             int maxRounds, long ttlSeconds) {
        this.strTemplate = strTemplate;
        this.maxRounds = maxRounds;
        this.ttlSeconds = ttlSeconds;
    }

    public String newSessionId() {
        String id = UUID.randomUUID().toString().replace("-", "");
        strTemplate.opsForSet().add(SESSION_SET, id);
        return id;
    }

    /**
     * 会话历史。用 LangChain4j 自带的 {@link ChatMessageDeserializer} 反序列化，
     * 因为 {@code UserMessage}/{@code AiMessage} 没有无参构造函数，Jackson 直接反序列化会失败。
     * Redis 里只存一个 JSON 数组字符串，旧版本的 List 结构（元素为单条消息序列化结果）读不出来时直接清空。
     */
    public List<ChatMessage> getHistory(String sessionId) {
        String json = strTemplate.opsForValue().get(key(sessionId));
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(json);
            return messages != null ? messages : new ArrayList<>();
        } catch (Exception e) {
            log.warn("[多轮] 会话 {} 历史反序列化失败（{}），按空历史处理", sessionId, e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<ChatMessage> buildPromptMessages(String sessionId, String systemPrompt, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.addAll(getHistory(sessionId));
        messages.add(UserMessage.from(userMessage));
        return messages;
    }

    public void appendTurn(String sessionId, String userMessage, String assistantMessage) {
        List<ChatMessage> history = getHistory(sessionId);
        history.add(UserMessage.from(userMessage));
        history.add(AiMessage.from(assistantMessage));

        if (history.size() > maxRounds * 2) {
            history = new ArrayList<>(history.subList(history.size() - maxRounds * 2, history.size()));
        }

        String json = ChatMessageSerializer.messagesToJson(history);
        strTemplate.opsForValue().set(key(sessionId), json, Duration.ofSeconds(ttlSeconds));
        strTemplate.opsForSet().add(SESSION_SET, sessionId);
    }

    public void remove(String sessionId) {
        strTemplate.delete(key(sessionId));
        strTemplate.opsForSet().remove(SESSION_SET, sessionId);
    }

    public int activeSessionCount() {
        Long count = strTemplate.opsForSet().size(SESSION_SET);
        return count == null ? 0 : count.intValue();
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    @Configuration
    static class RedisConfig {
        @Bean
        public RedisTemplate<String, String> chatStrRedisTemplate(RedisConnectionFactory factory) {
            RedisTemplate<String, String> template = new RedisTemplate<>();
            template.setConnectionFactory(factory);
            StringRedisSerializer s = new StringRedisSerializer();
            template.setKeySerializer(s);
            template.setValueSerializer(s);
            template.setHashKeySerializer(s);
            template.setHashValueSerializer(s);
            template.afterPropertiesSet();
            return template;
        }
    }
}