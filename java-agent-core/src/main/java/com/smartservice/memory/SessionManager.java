package com.smartservice.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Day 6-7: Redis 会话管理
 * 存储用户对话历史，支持 TTL 过期
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionManager {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String KEY_PREFIX = "agent:session:";
    private static final long TTL_HOURS = 24;
    private static final int MAX_HISTORY = 10;

    /**
     * 获取会话历史（返回 role/content Map 列表）
     */
    @SuppressWarnings("unchecked")
    public List<java.util.Map<String, String>> getHistory(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        List<String> history = (List<String>) redisTemplate.opsForValue().get(key);

        if (history == null) {
            return new ArrayList<>();
        }

        List<java.util.Map<String, String>> messages = new ArrayList<>();
        for (int i = 0; i < history.size(); i += 2) {
            if (i < history.size()) {
                messages.add(java.util.Map.of("role", "user", "content", history.get(i)));
            }
            if (i + 1 < history.size()) {
                messages.add(java.util.Map.of("role", "assistant", "content", history.get(i + 1)));
            }
        }
        return messages;
    }

    /**
     * 添加消息到会话
     */
    public void addMessage(String sessionId, String role, String content) {
        String key = KEY_PREFIX + sessionId;

        @SuppressWarnings("unchecked")
        List<String> history = (List<String>) redisTemplate.opsForValue().get(key);
        if (history == null) {
            history = new ArrayList<>();
        }

        history.add(content);

        // 只保留最近 MAX_HISTORY 轮对话
        if (history.size() > MAX_HISTORY * 2) {
            history = history.subList(history.size() - MAX_HISTORY * 2, history.size());
        }

        redisTemplate.opsForValue().set(key, history, TTL_HOURS, TimeUnit.HOURS);
        log.debug("Session {} updated, history size: {}", sessionId, history.size());
    }

    /**
     * 清除会话
     */
    public void clearSession(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
        log.info("Session {} cleared", sessionId);
    }
}
