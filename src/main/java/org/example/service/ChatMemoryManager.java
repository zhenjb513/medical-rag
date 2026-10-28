package org.example.service;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatMemoryManager {

    public static final int DEFAULT_MAX_ROUNDS = 10;
    public static final long DEFAULT_TTL_MS = 30 * 60 * 1000L;

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final int maxRounds;
    private final long ttlMs;

    public ChatMemoryManager() {
        this(DEFAULT_MAX_ROUNDS, DEFAULT_TTL_MS);
    }

    public ChatMemoryManager(int maxRounds, long ttlMs) {
        this.maxRounds = maxRounds;
        this.ttlMs = ttlMs;
    }

    public String newSessionId() {
        String id = UUID.randomUUID().toString().replace("-", "");
        sessions.put(id, new SessionState());
        return id;
    }

    public List<Message> getHistory(String sessionId) {
        cleanupExpired();
        SessionState state = sessions.get(sessionId);
        return state == null ? new ArrayList<>() : new ArrayList<>(state.history);
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
        cleanupExpired();
        SessionState state = sessions.computeIfAbsent(sessionId, k -> new SessionState());
        synchronized (state) {
            state.history.add(new UserMessage(userMessage));
            state.history.add(new AssistantMessage(assistantMessage));
            while (state.history.size() > maxRounds * 2) {
                state.history.remove(0);
            }
            state.lastAccess = System.currentTimeMillis();
        }
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public int activeSessionCount() {
        cleanupExpired();
        return sessions.size();
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().lastAccess > ttlMs);
    }

    private static class SessionState {
        final List<Message> history = new ArrayList<>();
        volatile long lastAccess = System.currentTimeMillis();
    }
}