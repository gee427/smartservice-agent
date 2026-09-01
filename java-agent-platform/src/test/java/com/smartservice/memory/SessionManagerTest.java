package com.smartservice.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-3: SessionManager 历史截断回归测试
 * 验证超过 20 条（10 轮）后截断为最近 20 条，且截断结果可正常反序列化
 * （历史 bug：subList 视图直接写 Redis，读回时 ArrayList$SubList 无法反序列化 -> 50000）
 */
@SpringBootTest
class SessionManagerTest {

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void addMessage_trimsToMaxHistory_andRemainsDeserializable() {
        String sid = "sm_" + System.nanoTime();
        try {
            // 22 条消息（11 轮）> 20 条上限，触发截断路径
            for (int i = 0; i < 22; i++) {
                sessionManager.addMessage(sid, i % 2 == 0 ? "user" : "assistant", "msg-" + i);
            }

            // 读回并反序列化：修复前这里抛 SerializationException
            List<String> history = sessionManager.getHistory(sid);
            assertEquals(20, history.size(), "历史应截断为最近 20 条");
            assertEquals("msg-21", history.get(19), "保留最新消息");

            // 交替视图也应正常
            assertEquals(20, sessionManager.getMessages(sid).size());
        } finally {
            redisTemplate.delete("agent:session:" + sid);
        }
    }
}
