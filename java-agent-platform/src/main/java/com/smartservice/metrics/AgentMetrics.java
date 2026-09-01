package com.smartservice.metrics;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Day 17-18: 监控指标
 * 记录 Agent 请求数、响应时间、Token 消耗等
 */
@Slf4j
@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    private final Counter requestCounter;
    private final Counter tokenCounter;
    private final Timer responseTimer;
    private final AtomicInteger activeSessions;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.requestCounter = Counter.builder("agent.requests.total")
            .description("Total agent requests")
            .register(registry);

        this.tokenCounter = Counter.builder("agent.tokens.total")
            .description("Total tokens consumed")
            .register(registry);

        this.responseTimer = Timer.builder("agent.response.time")
            .description("Agent response time in ms")
            .register(registry);

        this.activeSessions = new AtomicInteger(0);
        Gauge.builder("agent.sessions.active", activeSessions, AtomicInteger::get)
            .description("Active sessions")
            .register(registry);
    }

    public void recordRequest(String agentType, boolean success, 
                               long latencyMs, int tokenCount) {
        requestCounter.increment();

        // P1-2: 意图分布统计（按 intent tag 分组）
        registry.counter("agent.requests.by.intent", "intent", agentType).increment();

        tokenCounter.increment(tokenCount);
        responseTimer.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    public void incrementActiveSessions() {
        activeSessions.incrementAndGet();
    }

    public void decrementActiveSessions() {
        activeSessions.decrementAndGet();
    }
}
