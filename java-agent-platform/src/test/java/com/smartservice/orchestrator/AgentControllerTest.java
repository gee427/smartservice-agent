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
 * P2-3: 对话接口集成测试（真实 Redis + MockMvc）
 * FAQ 关键词直查不依赖 LLM，可稳定断言；SSE 验证异步流式链路
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
    void chat_faqKeyword_hitsKnowledgeBase() throws Exception {
        // "退货" 命中 FaqAgent 知识库直查，不依赖 LLM
        mockMvc.perform(post("/api/agent/chat")
                .header("X-Forwarded-For", uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"t\",\"sessionId\":\"it-faq\",\"message\":\"我想退货怎么操作\"}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.intent").value("FAQ"))
            .andExpect(jsonPath("$.data.content", containsString("退货")));
    }

    @Test
    void chat_stream_returnsSseWithDoneEvent() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/agent/chat/stream")
                .header("X-Forwarded-For", uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"t\",\"sessionId\":\"it-stream\",\"message\":\"你们支持退货吗\"}"))
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
        String ip = uniqueIp(); // 同一 IP 连续 11 次
        String body = "{\"userId\":\"t\",\"sessionId\":\"it-rate\",\"message\":\"退货政策是什么\"}";

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/agent/chat").header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0));
        }
        // 第 11 次触发限流
        mockMvc.perform(post("/api/agent/chat").header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(jsonPath("$.code").value(42900));
    }
}
