package com.smartservice.admin;

import com.smartservice.api.ApiResponse;
import com.smartservice.memory.SessionTracker;
import com.smartservice.metrics.AgentMetrics;
import com.smartservice.orchestrator.LlmClient;
import com.smartservice.orchestrator.RouterAgent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * P1-2: 管理后台 API
 * 运营视图：会话列表、指标统计、Agent 状态、服务健康
 */
@Tag(name = "管理后台", description = "运营视图：会话 / 指标 / Agent / 健康（P1-2，需 JWT）")
@SecurityRequirement(name = "bearer-jwt")
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final SessionTracker sessionTracker;
    private final RouterAgent routerAgent;
    private final MeterRegistry registry;
    private final LlmClient llmClient;
    private final RedisConnectionFactory redisConnectionFactory;
    private final AgentMetrics agentMetrics;

    /**
     * 会话列表（按最后活动倒序）
     */
    @Operation(summary = "会话列表", description = "Redis 会话索引，按最后活动倒序，limit 上限 200")
    @GetMapping("/sessions")
    public ApiResponse<List<Map<String, Object>>> sessions(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(sessionTracker.listSessions(Math.min(limit, 200)));
    }

    /**
     * 删除会话（清消息 + 清索引）
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Map<String, String>> deleteSession(@PathVariable String sessionId) {
        sessionTracker.remove(sessionId);
        return ApiResponse.success(Map.of("status", "deleted", "sessionId", sessionId));
    }

    /**
     * 平台指标（从 Micrometer 读取）
     */
    @Operation(summary = "平台指标", description = "请求数 / Token / 平均延迟 / 活跃会话 / 意图分布（实时读 Micrometer）")
    @GetMapping("/metrics")
    public ApiResponse<Map<String, Object>> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();

        Counter total = registry.find("agent.requests.total").counter();
        m.put("totalRequests", total != null ? total.count() : 0);

        Counter tokens = registry.find("agent.tokens.total").counter();
        m.put("totalTokens", tokens != null ? tokens.count() : 0);

        Timer timer = registry.find("agent.response.time").timer();
        m.put("requestCount", timer != null ? timer.count() : 0);
        m.put("avgLatencyMs", timer != null ? Math.round(timer.mean(TimeUnit.MILLISECONDS) * 10) / 10.0 : 0);

        Gauge active = registry.find("agent.sessions.active").gauge();
        m.put("activeSessions", active != null ? active.value() : 0);

        // 意图分布
        List<Map<String, Object>> byIntent = new ArrayList<>();
        for (Counter c : registry.find("agent.requests.by.intent").counters()) {
            String intent = c.getId().getTag("intent");
            if (intent != null) {
                byIntent.add(Map.of("intent", intent, "count", c.count()));
            }
        }
        byIntent.sort(Comparator.<Map<String, Object>>comparingLong(
            o -> ((Number) o.get("count")).longValue()).reversed());
        m.put("intentDistribution", byIntent);

        return ApiResponse.success(m);
    }

    /**
     * 业务 Agent 列表与意图映射
     */
    @GetMapping("/agents")
    public ApiResponse<Map<String, Object>> agents() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agents", routerAgent.getAgentNames());
        m.put("intentMap", routerAgent.getIntentMap());
        return ApiResponse.success(m);
    }

    /**
     * 服务健康：平台 / Redis / LLM
     */
    @Operation(summary = "三层健康检查", description = "platform / redis / llm 状态，同时刷新 agent_redis_up / agent_llm_up 监控 gauge")
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");

        boolean redisUp = false;
        try {
            redisConnectionFactory.getConnection().ping();
            redisUp = true;
        } catch (Exception e) {
            log.warn("Redis health check failed: {}", e.getMessage());
        }
        m.put("redis", redisUp ? "UP" : "DOWN");

        boolean llmUp = llmClient.isAvailable();
        m.put("llm", llmUp ? "UP" : "DOWN");

        // P3-4: 刷新健康 gauge（agent.redis.up / agent.llm.up），供 Prometheus 规则告警
        agentMetrics.updateServiceHealth(redisUp, llmUp);
        return ApiResponse.success(m);
    }
}
