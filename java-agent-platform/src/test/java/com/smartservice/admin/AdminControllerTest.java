package com.smartservice.admin;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2-3: 管理后台认证保护集成测试
 * 验证 JWT 拦截器：无 token / 伪造 token 40100，合法 token 放行
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdUsers.forEach(u -> redisTemplate.delete("agent:user:" + u));
    }

    private String registerAndGetToken() throws Exception {
        String user = "au_" + System.nanoTime();
        createdUsers.add(user);
        String ip = "10.97." + ThreadLocalRandom.current().nextInt(1, 65536)
            + "." + ThreadLocalRandom.current().nextInt(1, 256);
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"pass1234\"}"))
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get("token").asText();
    }

    @Test
    void adminApi_withoutToken_unauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void adminApi_withForgedToken_unauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/health")
                .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.fake.fake"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void adminApi_withValidToken_allowed() throws Exception {
        String token = registerAndGetToken();
        mockMvc.perform(get("/api/admin/health")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value("UP"))
            .andExpect(jsonPath("$.data.redis").exists())
            .andExpect(jsonPath("$.data.llm").exists());
    }

    @Test
    void adminSessions_withValidToken_returnsList() throws Exception {
        String token = registerAndGetToken();
        mockMvc.perform(get("/api/admin/sessions")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void me_withValidToken_returnsProfile() throws Exception {
        String token = registerAndGetToken();
        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.role").value("ADMIN"))
            .andExpect(jsonPath("$.data.username").isNotEmpty());
    }
}
