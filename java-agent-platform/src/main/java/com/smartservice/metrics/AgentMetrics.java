package com.smartservice.metrics;

import com.smartservice.memory.SessionTracker;
import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P3-4: 监控指标
 *
 * 核心指标：
 *   agent.requests.total                总请求数
 *   agent.requests.by.intent{intent}    意图分布
 *   agent.requests.errors.total         失败请求数（P3-4 新增）
 *   agent.requests.errors.by.intent     失败意图分布（P3-4 新增）
 *   agent.requests.stream.total         SSE 流式请求数（P3-4 新增）
 *   agent.streams.by.intent             SSE 意图分布（P3-4 新增）
 *   agent.stream.chars.total            SSE 累计输出字符数（P3-4 新增）
 *   agent.tokens.total                  Token 消耗估计
 *   agent.response.time                 响应时间直方图（ms）
 *   agent.sessions.active               活跃会话数（Redis 索引最近 24h 有活动）
 *   agent.rate.limited.total            限流触发总数（P3-4 新增）
 *   agent.rate.limited.by.resource      按资源(chat/login)限流分布（P3-4 新增）
 *   agent.redis.up / agent.llm.up       服务健康状态 0/1（P3-4 新增，供 Prometheus 规则告警）
 */
@Slf4j
@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    private final SessionTracker sessionTracker;
    private final Counter requestCounter;
    private final Counter errorCounter;
    private final Counter tokenCounter;
    private final Counter streamCounter;
    private final Counter streamCharsCounter;
    private final Counter rateLimitedCounter;
    private final Timer responseTimer;
    private final AtomicInteger redisUpGauge;
    private final AtomicInteger llmUpGauge;

    /** 活跃会话统计窗口：24h（与后台"会话列表"的运营口径一致） */
    private static final long ACTIVE_WINDOW_MS = 24 * 60 * 60 * 1000L;

    public AgentMetrics(MeterRegistry registry, SessionTracker sessionTracker) {
        this.registry = registry;
        this.sessionTracker = sessionTracker;
        this.requestCounter = Counter.builder("agent.requests.total")
            .description("Total agent requests")
            .register(registry);

        this.errorCounter = Counter.builder("agent.requests.errors.total")
            .description("Failed agent requests")
            .register(registry);

        this.tokenCounter = Counter.builder("agent.tokens.total")
            .description("Total tokens consumed (real usage from LM Studio responses)")
            .register(registry);

        this.streamCounter = Counter.builder("agent.requests.stream.total")
            .description("Total SSE streaming requests")
            .register(registry);

        this.streamCharsCounter = Counter.builder("agent.stream.chars.total")
            .description("Total characters streamed")
            .register(registry);

        this.rateLimitedCounter = Counter.builder("agent.rate.limited.total")
            .description("Total rate-limited requests")
            .register(registry);

        this.responseTimer = Timer.builder("agent.response.time")
            .description("Agent response time in ms")
            .register(registry);

        // 活跃会话：每次读取实时统计 Redis 索引中最近 24h 有活动的会话数。
        // 语义修正：以前用进程内 AtomicInteger 只增不减（decrement 无调用方），
        // 数字实为"启动以来聊天调用次数"；现在随会话活动自然增减、重启不丢。
        Gauge.builder("agent.sessions.active", sessionTracker,
                st -> st.countActiveSince(ACTIVE_WINDOW_MS))
            .description("Active sessions (Redis index, last 24h)")
            .register(registry);

        // 服务健康 0/1：由 AdminController.health() 每次评估后刷新，
        // Prometheus 规则据此告警（agent_llm_up == 0 持续 5m → LlmDown）
        this.redisUpGauge = new AtomicInteger(0);
        Gauge.builder("agent.redis.up", redisUpGauge, AtomicInteger::get)
            .description("Redis health 1=UP 0=DOWN")
            .register(registry);

        this.llmUpGauge = new AtomicInteger(0);
        Gauge.builder("agent.llm.up", llmUpGauge, AtomicInteger::get)
            .description("LLM health 1=UP 0=DOWN")
            .register(registry);
    }

    public void recordRequest(String agentType, boolean success,
                               long latencyMs, int tokenCount) {
        requestCounter.increment();

        // P1-2: 意图分布统计（按 intent tag 分组）
        registry.counter("agent.requests.by.intent", "intent", agentType).increment();

        tokenCounter.increment(tokenCount);
        responseTimer.record(latencyMs, TimeUnit.MILLISECONDS);

        // P3-4: 失败计数（异常路径由 AgentOrchestrator 传入 success=false）
        if (!success) {
            errorCounter.increment();
            registry.counter("agent.requests.errors.by.intent", "intent", agentType).increment();
        }
    }

    /**
     * P3-4: SSE 流式请求指标
     * 流式与普通请求同口径：计入总请求数、意图分布与 Token 消耗估算
     */
    public void recordStream(String agentType, int chars, long latencyMs, int tokenCount) {
        // 流式请求同样计入总请求数与意图分布（否则前端只用 /chat/stream 时
        // agent.requests.total / agent.requests.by.intent 永远为 0）
        requestCounter.increment();
        registry.counter("agent.requests.by.intent", "intent", agentType).increment();

        tokenCounter.increment(tokenCount);
        streamCounter.increment();
        streamCharsCounter.increment(chars);
        registry.counter("agent.streams.by.intent", "intent", agentType).increment();
        responseTimer.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /** P3-4: 限流触发计数（RateLimitInterceptor 超限时调用） */
    public void recordRateLimited(String resource) {
        rateLimitedCounter.increment();
        registry.counter("agent.rate.limited.by.resource", "resource", resource).increment();
    }

    /** P3-4: 刷新服务健康 gauge（AdminController.health 每次评估后调用） */
    public void updateServiceHealth(boolean redisUp, boolean llmUp) {
        redisUpGauge.set(redisUp ? 1 : 0);
        llmUpGauge.set(llmUp ? 1 : 0);
    }
}
