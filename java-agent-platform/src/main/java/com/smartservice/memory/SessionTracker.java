package com.smartservice.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * P1-2: 会话索引服务
 * 维护活跃会话的元信息（userId/intent/最后活动/消息数），供管理后台查询
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionTracker {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String INDEX_KEY = "agent:session:index";
    private static final long INDEX_TTL_DAYS = 7;

    @SuppressWarnings("unchecked")
    public void track(String sessionId, String userId, String intent, int messageCount) {
        Map<String, Object> info = new HashMap<>();
        info.put("userId", userId != null ? userId : "anonymous");
        info.put("intent", intent != null ? intent : "UNKNOWN");
        info.put("lastActive", System.currentTimeMillis());
        info.put("messageCount", messageCount);

        redisTemplate.opsForHash().put(INDEX_KEY, sessionId, info);
        redisTemplate.expire(INDEX_KEY, INDEX_TTL_DAYS, TimeUnit.DAYS);
        log.debug("Session {} tracked: intent={}, msgs={}", sessionId, intent, messageCount);
    }

    /**
     * 会话列表（按最后活动时间倒序）
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listSessions(int limit) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(INDEX_KEY);
        List<Map<String, Object>> sessions = new ArrayList<>();

        for (Map.Entry<Object, Object> e : entries.entrySet()) {
            Map<String, Object> info = new HashMap<>();
            info.put("sessionId", String.valueOf(e.getKey()));
            if (e.getValue() instanceof Map<?, ?> m) {
                info.putAll((Map<String, Object>) m);
            }
            sessions.add(info);
        }

        sessions.sort(Comparator.<Map<String, Object>>comparingLong(
            s -> ((Number) s.getOrDefault("lastActive", 0L)).longValue()).reversed());

        if (sessions.size() > limit) {
            return sessions.subList(0, limit);
        }
        return sessions;
    }

    /**
     * 统计最近 windowMs 毫秒内有活动的会话数（按 lastActive 时间戳过滤）。
     * 供 "活跃会话" 指标使用：语义 = Redis 索引中最近 24h 有消息活动的会话数，
     * 服务重启不丢、随时间自然衰减（区别于进程内只增不减的计数器）。
     */
    @SuppressWarnings("unchecked")
    public long countActiveSince(long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        long active = 0;
        for (Object v : redisTemplate.opsForHash().entries(INDEX_KEY).values()) {
            if (v instanceof Map<?, ?> m && m.get("lastActive") instanceof Number n) {
                if (n.longValue() >= cutoff) {
                    active++;
                }
            }
        }
        return active;
    }

    /**
     * 移除会话索引（清空会话时同步清理）
     */
    public void remove(String sessionId) {
        redisTemplate.opsForHash().delete(INDEX_KEY, sessionId);
    }
}
