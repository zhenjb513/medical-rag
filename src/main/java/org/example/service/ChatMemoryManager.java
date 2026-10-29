package org.example.service;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class ChatMemoryManager {

    public static final String KEY_PREFIX = "mkbai:chat:";
    public static final String SESSION_SET = "mkbai:chat:sessions";
    public static final int DEFAULT_MAX_ROUNDS = 10;
    public static final long DEFAULT_TTL_SECONDS = 30 * 60;
    public static final long DEFAULT_TTL_MS = DEFAULT_TTL_SECONDS * 1000L;

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisTemplate<String, String> strTemplate;
    private final int maxRounds;
    private final long ttlSeconds;

    @Autowired
    public ChatMemoryManager(@Qualifier("chatRedisTemplate") RedisTemplate<String, Object> redisTemplate,
                             @Qualifier("chatStrRedisTemplate") RedisTemplate<String, String> strTemplate) {
        this(redisTemplate, strTemplate, DEFAULT_MAX_ROUNDS, DEFAULT_TTL_SECONDS);
    }

    public ChatMemoryManager(@Qualifier("chatRedisTemplate") RedisTemplate<String, Object> redisTemplate,
                             @Qualifier("chatStrRedisTemplate") RedisTemplate<String, String> strTemplate,
                             int maxRounds, long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.strTemplate = strTemplate;
        this.maxRounds = maxRounds;
        this.ttlSeconds = ttlSeconds;
    }

    public String newSessionId() {
        String id = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForList().rightPush(key(id), new ArrayList<>());
        redisTemplate.expire(key(id), Duration.ofSeconds(ttlSeconds));
        strTemplate.opsForSet().add(SESSION_SET, id);
        return id;
    }

    @SuppressWarnings("unchecked")
    public List<Message> getHistory(String sessionId) {
        Object raw = redisTemplate.opsForList().range(key(sessionId), 0, -1);
        if (raw == null) return new ArrayList<>();
        return (List<Message>) raw;
    }

    public List<Message> buildPromptMessages(String sessionId, String systemPrompt, String userMessage) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.addAll(getHistory(sessionId));
        messages.add(new UserMessage(userMessage));
        return messages;
    }

    public void appendTurn(String sessionId, String userMessage, String assistantMessage) {
        String k = key(sessionId);
        redisTemplate.opsForList().rightPush(k, new UserMessage(userMessage));
        redisTemplate.opsForList().rightPush(k, new AssistantMessage(assistantMessage));

        Long size = redisTemplate.opsForList().size(k);
        if (size != null && size > maxRounds * 2) {
            redisTemplate.opsForList().trim(k, -(maxRounds * 2), -1);
        }
        redisTemplate.expire(k, Duration.ofSeconds(ttlSeconds));
        strTemplate.opsForSet().add(SESSION_SET, sessionId);
    }

    public void remove(String sessionId) {
        redisTemplate.delete(key(sessionId));
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
        public RedisTemplate<String, Object> chatRedisTemplate(RedisConnectionFactory factory) {
            RedisTemplate<String, Object> template = new RedisTemplate<>();
            template.setConnectionFactory(factory);

            ObjectMapper om = new ObjectMapper();
            om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
            om.activateDefaultTyping(om.getPolymorphicTypeValidator(),
                    ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
            GenericJackson2JsonRedisSerializer jsonSer = new GenericJackson2JsonRedisSerializer(om);

            template.setKeySerializer(new StringRedisSerializer());
            template.setValueSerializer(jsonSer);
            template.setHashKeySerializer(new StringRedisSerializer());
            template.setHashValueSerializer(jsonSer);
            template.afterPropertiesSet();
            return template;
        }

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