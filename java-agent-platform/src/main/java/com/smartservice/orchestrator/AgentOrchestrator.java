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
    private final LlmClient llmClient;

    /**
     * 处理用户消息：路由 + 执行 + 监控
     * Token 统计：请求内所有 LLM 调用的真实 usage 由 LlmClient 累计，
     * 结束时取真实 total_tokens 上报（替换 message.length()/2 字符估算）
     */
    public AgentResult process(String userId, String sessionId, String message, List<String> history) {
        long startTime = System.currentTimeMillis();
        llmClient.beginTokenAccumulate();

        // 1. 路由到对应的 Agent（P3-4: 异常路径也记录错误指标后原样抛出）
        AgentResult result;
        try {
            result = routerAgent.route(userId, sessionId, message, history);
        } catch (Exception e) {
            long failLatency = System.currentTimeMillis() - startTime;
            agentMetrics.recordRequest("ERROR", false, failLatency, llmClient.endTokenAccumulate());
            log.warn("Orchestration failed: {}", e.getMessage());
            throw e;
        }

        // 2. 记录监控指标
        long latency = System.currentTimeMillis() - startTime;
        agentMetrics.recordRequest(result.intent(), true, latency, llmClient.endTokenAccumulate());

        log.info("Orchestrated: intent={}, latency={}ms", result.intent(), latency);
        return result;
    }

    /**
     * P1-1: 流式处理：分类 → 业务 Agent 流式输出 → 记录监控
     * P3-4: 累计流式输出字符数，走 recordStream 独立指标
     * Token 统计：与 process 同口径——请求内所有 LLM 调用真实 usage 由 LlmClient 累计，
     * 结束时取真实 total_tokens 上报（含意图分类与工具循环各轮，替换字符估算）
     * @return 分类出的意图
     */
    public String processStream(String userId, String sessionId, String message,
                                List<String> history, java.util.function.Consumer<String> onToken) {
        long startTime = System.currentTimeMillis();
        llmClient.beginTokenAccumulate();

        String intent;
        try {
            intent = routerAgent.classify(message);
        } catch (Exception e) {
            long failLatency = System.currentTimeMillis() - startTime;
            agentMetrics.recordRequest("ERROR", false, failLatency, llmClient.endTokenAccumulate());
            throw e;
        }
        log.info("Stream intent classified: {} for message: {}", intent, message);

        // 包装 onToken 累计输出字符数
        java.util.concurrent.atomic.AtomicInteger chars = new java.util.concurrent.atomic.AtomicInteger(0);
        BusinessAgent agent = routerAgent.resolveAgent(intent);
        try {
            agent.processStream(userId, sessionId, message, history, token -> {
                chars.addAndGet(token.length());
                onToken.accept(token);
            });
        } catch (Exception e) {
            long failLatency = System.currentTimeMillis() - startTime;
            agentMetrics.recordRequest(intent, false, failLatency, llmClient.endTokenAccumulate());
            log.warn("Stream failed: intent={}, err={}", intent, e.getMessage());
            throw e;
        }

        long latency = System.currentTimeMillis() - startTime;
        agentMetrics.recordStream(intent, chars.get(), latency, llmClient.endTokenAccumulate());
        log.info("Streamed: intent={}, latency={}ms, chars={}", intent, latency, chars.get());
        return intent;
    }

    /**
     * 路由结果
     */
    public record AgentResult(String intent, String response) {}
}
