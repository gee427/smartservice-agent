package com.smartservice.orchestrator;

import com.smartservice.metrics.AgentMetrics;
import com.smartservice.workflow.ReturnProcessWorkflow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Day 11-12: Agent 编排器
 * 协调 RouterAgent、Workflow、Metrics 之间的调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final RouterAgent routerAgent;
    private final ReturnProcessWorkflow returnProcessWorkflow;
    private final AgentMetrics agentMetrics;

    /**
     * 处理用户消息：路由 + 执行 + 监控
     */
    public AgentResult process(String userId, String sessionId, String message, List<String> history) {
        long startTime = System.currentTimeMillis();

        // 1. 路由到对应的 Agent
        AgentResult result = routerAgent.route(userId, sessionId, message, history);

        // 2. 记录监控指标
        long latency = System.currentTimeMillis() - startTime;
        agentMetrics.recordRequest(result.intent(), true, latency, message.length() / 2);

        log.info("Orchestrated: intent={}, latency={}ms", result.intent(), latency);
        return result;
    }

    /**
     * P1-1: 流式处理：分类 → 业务 Agent 流式输出 → 记录监控
     * @return 分类出的意图
     */
    public String processStream(String userId, String sessionId, String message,
                                List<String> history, java.util.function.Consumer<String> onToken) {
        long startTime = System.currentTimeMillis();

        String intent = routerAgent.classify(message);
        log.info("Stream intent classified: {} for message: {}", intent, message);

        BusinessAgent agent = routerAgent.resolveAgent(intent);
        agent.processStream(userId, sessionId, message, history, onToken);

        long latency = System.currentTimeMillis() - startTime;
        agentMetrics.recordRequest(intent, true, latency, message.length() / 2);
        log.info("Streamed: intent={}, latency={}ms", intent, latency);
        return intent;
    }

    /**
     * 路由结果
     */
    public record AgentResult(String intent, String response) {}
}
