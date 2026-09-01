package com.smartservice.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P2-3: 认证接口集成测试（真实 Redis + MockMvc）
 * 限流隔离：每个用例使用独立 X-Forwarded-For，避免互相消耗配额
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> createdUsers = new ArrayList<>();

    private String uniqueUser() {
        String u = "tu_" + System.nanoTime();
        createdUsers.add(u);
        return u;
    }

    private String uniqueIp() {
        // 16bit 随机段，碰撞概率极低，确保每个用例独立限流配额
        return "10.99." + ThreadLocalRandom.current().nextInt(1, 65536)
            + "." + ThreadLocalRandom.current().nextInt(1, 256);
    }

    @AfterEach
    void cleanup() {
        createdUsers.forEach(u -> redisTemplate.delete("agent:user:" + u));
    }

    @Test
    void register_returnsToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .header("X-Forwarded-For", uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + uniqueUser() + "\",\"password\":\"pass1234\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    void register_duplicateUser_returnsBadRequest() throws Exception {
        String user = uniqueUser();
        String body = "{\"username\":\"" + user + "\",\"password\":\"pass1234\"}";
        String ip = uniqueIp();

        mockMvc.perform(post("/api/auth/register").header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/auth/register").header("X-Forwarded-For", uniqueIp())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.message", containsString("已存在")));
    }

    @Test
    void register_invalidUsername_validationFails() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .header("X-Forwarded-For", uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ab\",\"password\":\"pass1234\"}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.message", containsString("用户名")));
    }

    @Test
    void login_wrongPassword_unauthorized() throws Exception {
        String user = uniqueUser();
        String ip = uniqueIp();
        mockMvc.perform(post("/api/auth/register").header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"pass1234\"}"));

        mockMvc.perform(post("/api/auth/login").header("X-Forwarded-For", uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"wrong-pass\"}"))
            .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void login_rateLimited_afterFiveAttempts() throws Exception {
        String ip = uniqueIp(); // 同一 IP 连续尝试
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login").header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"nosuchuser\",\"password\":\"x123456\"}"))
                .andExpect(jsonPath("$.code").value(40100));
        }
        // 第 6 次应触发限流 42900
        mockMvc.perform(post("/api/auth/login").header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"nosuchuser\",\"password\":\"x123456\"}"))
            .andExpect(jsonPath("$.code").value(42900));
    }

    @Test
    void register_thenLogin_roundTrip() throws Exception {
        String user = uniqueUser();
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                .header("X-Forwarded-For", uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"pass1234\"}"))
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();

        JsonNode data = mapper.readTree(reg.getResponse().getContentAsString()).get("data");
        assert data.get("token").asText().length() > 20;

        mockMvc.perform(post("/api/auth/login").header("X-Forwarded-For", uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"pass1234\"}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.token").isNotEmpty());
    }
}
