package com.smartservice.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * P0-1: platform 会话管理（Redis）
 * 存储用户对话历史（user/assistant 交替），支持 TTL 过期与历史截断
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionManager {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String KEY_PREFIX = "agent:session:";
    private static final long TTL_HOURS = 24;
    private static final int MAX_HISTORY = 10;

    @SuppressWarnings("unchecked")
    private List<String> getRawHistory(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        List<String> history = (List<String>) redisTemplate.opsForValue().get(key);
        return history == null ? new ArrayList<>() : history;
    }

    /**
     * 交替字符串历史（user, assistant, ...），供 RouterAgent / LLM 构建消息列表
     */
    public List<String> getHistory(String sessionId) {
        return getRawHistory(sessionId);
    }

    /**
     * role/content Map 列表，供 API 展示
     */
    public List<Map<String, String>> getMessages(String sessionId) {
        List<String> history = getRawHistory(sessionId);
        List<Map<String, String>> messages = new ArrayList<>();
        for (int i = 0; i < history.size(); i += 2) {
            if (i < history.size()) {
                messages.add(Map.of("role", "user", "content", history.get(i)));
            }
            if (i + 1 < history.size()) {
                messages.add(Map.of("role", "assistant", "content", history.get(i + 1)));
            }
        }
        return messages;
    }

    /**
     * 添加消息到会话（内部按 user/assistant 交替存储）
     */
    public void addMessage(String sessionId, String role, String content) {
        String key = KEY_PREFIX + sessionId;
        List<String> history = getRawHistory(sessionId);
        history.add(content);

        // 只保留最近 MAX_HISTORY 轮对话
        // 注意：必须 new ArrayList 拷贝，subList 返回的是视图（ArrayList$SubList），
        // 直接写 Redis 后反序列化会失败（无默认构造器）——曾导致 SerializationException
        if (history.size() > MAX_HISTORY * 2) {
            history = new ArrayList<>(
                history.subList(history.size() - MAX_HISTORY * 2, history.size()));
        }

        redisTemplate.opsForValue().set(key, history, TTL_HOURS, TimeUnit.HOURS);
        log.debug("Session {} updated ({}), history size: {}", sessionId, role, history.size());
    }

    /**
     * 清除会话
     */
    public void clearSession(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
        log.info("Session {} cleared", sessionId);
    }
}
