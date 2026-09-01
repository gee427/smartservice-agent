package com.smartservice.metrics;

import com.smartservice.orchestrator.LlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * P3-3: LLM 健康指示器，挂载到 /actuator/health
 *
 * 与 RedisHealthIndicator 并列，使监控探针一次调用即可获得三层状态：
 *   /actuator/health → components: { redis: {...}, llm: {...} }
 *
 * LlmClient.isAvailable() 内部为最小 chat 探测（max_tokens=1）+ 30s 结果缓存，
 * 引擎挂掉时快速返回 DOWN，不会拖慢健康轮询。
 */
@Component
public class LlmHealthIndicator implements HealthIndicator {

    private final LlmClient llmClient;
    private final String model;

    public LlmHealthIndicator(LlmClient llmClient,
                              @Value("${agent.llm.model:unknown}") String model) {
        this.llmClient = llmClient;
        this.model = model;
    }

    @Override
    public Health health() {
        if (llmClient.isAvailable()) {
            return Health.up()
                    .withDetail("model", model)
                    .withDetail("base-url", "LM Studio local")
                    .build();
        }
        return Health.down()
                .withDetail("model", model)
                .withDetail("reason", "LM Studio unreachable or model unloaded")
                .build();
    }
}
