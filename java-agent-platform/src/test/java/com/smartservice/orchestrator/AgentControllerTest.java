package com.smartservice.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P2-3: 对话接口集成测试（真实 Redis + MockMvc，需本地 LLM 在线）
 * 通用对话用例：SSE 验证异步流式链路；限流用例验证配额逻辑（意图均走 CHAT）
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private String uniqueIp() {
        // 16bit 随机段，碰撞概率极低，确保每个用例独立限流配额
        return "10.98." + ThreadLocalRandom.current().nextInt(1, 65536)
            + "." + ThreadLocalRandom.current().nextInt(1, 256);
    }

    @Test
    void chat_emptyMessage_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/agent/chat")
                .header("X-Forwarded-For", uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"t\",\"message\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.message", containsString("不能为空")));
    }

    @Test
    void chat_stream_returnsSseWithDoneEvent() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/agent/chat/stream")
                .header("X-Forwarded-For", uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"t\",\"sessionId\":\"it-stream\",\"message\":\"帮我写一句中秋祝福\"}"))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andExpect(content().string(containsString("done")))
            .andExpect(content().string(containsString("intent")));
    }

    @Test
    void chat_rateLimited_afterTenAttempts() throws Exception {
        // 用空消息触发 40000 快速返回（参数校验先于业务、不调 LLM，秒级完成）
        // 只验证限流拦截器：RateLimitInterceptor 在 handler 前按 IP 计数，与业务结果无关。
        // 不能再用真实对话消息：每次 LLM ~15s，11 次调用跨 >60s 固定窗口，
        // 配额随窗口滑动重置 → 任何 60s 内都不超 10 次，永远不触发 42900。
        String ip = uniqueIp(); // 同一 IP 连续 11 次
        String body = "{\"userId\":\"t\",\"sessionId\":\"it-rate\",\"message\":\"\"}";

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/agent/chat").header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()); // code=40000（HTTP 仍为 200）
        }
        // 第 11 次触发限流
        mockMvc.perform(post("/api/agent/chat").header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(jsonPath("$.code").value(42900));
    }
}
