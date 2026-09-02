package com.smartservice.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Day 9-10 + P0-2: 路由 Agent（轻量路由版）
 * 本地 AI 聊天平台定位（2026-09-02 去客服化改造）：
 * 仅保留两个确定性工具意图（天气 / 计算）关键词直连，
 * 其余消息 100% 走通用 AI 助手（CHAT），聊天不被业务域局限。
 * 不再做 LLM 意图分类：省一次 LLM 往返，行为完全确定。
 */
@Slf4j
@Component
public class RouterAgent {

    private final Map<String, BusinessAgent> agentRegistry;

    /**
     * 意图代码 → Agent bean 名映射（仅保留工具 + 通用对话）
     */
    private static final Map<String, String> INTENT_TO_AGENT = Map.of(
        "WEATHER", "weatherAgent",
        "CALC", "calcAgent",
        "CHAT", "chatAgent"
    );

    /**
     * Spring 自动注入所有 BusinessAgent 实现（key = bean 名）
     */
    public RouterAgent(Map<String, BusinessAgent> agents) {
        this.agentRegistry = agents;
        log.info("Registered business agents: {}", agents.keySet());
    }

    /**
     * 意图分类并路由
     */
    public AgentOrchestrator.AgentResult route(String userId, String sessionId,
                                                String message, List<String> history) {
        String intent = classify(message);
        log.info("Intent classified: {} for message: {}", intent, message);

        BusinessAgent agent = resolveAgent(intent);
        String response = agent.process(userId, sessionId, message, history);
        return new AgentOrchestrator.AgentResult(intent, response);
    }

    /**
     * 暴露意图分类（供流式端点等复用）
     */
    public String classify(String message) {
        return classifyIntent(message);
    }

    /**
     * 暴露全部业务 Agent 名称（供管理后台展示）
     */
    public List<String> getAgentNames() {
        return new java.util.ArrayList<>(agentRegistry.keySet());
    }

    /**
     * 暴露意图 → Agent 映射（供管理后台展示）
     */
    public Map<String, String> getIntentMap() {
        return INTENT_TO_AGENT;
    }

    /**
     * 根据意图解析对应业务 Agent（未知意图回退通用对话 CHAT）
     */
    public BusinessAgent resolveAgent(String intent) {
        String agentName = INTENT_TO_AGENT.getOrDefault(intent, "chatAgent");
        BusinessAgent agent = agentRegistry.get(agentName);
        return agent != null ? agent : agentRegistry.get("chatAgent");
    }

    /**
     * 确定性关键词路由：
     * - 天气 / 计算：强意图词直连，不依赖 LLM（与线上行为一致、测试稳定）
     * - 其余：一律 CHAT（通用 AI 助手），不拦截、不引导业务
     */
    private String classifyIntent(String message) {
        String msg = message == null ? "" : message;

        // 天气查询（气象强词，误伤普通聊天的概率低）
        if (msg.contains("天气") || msg.contains("气温") || msg.contains("下雨")
            || msg.contains("降雨") || msg.contains("下雪") || msg.contains("降雪")
            || msg.contains("预报") || msg.contains("台风")) {
            return "WEATHER";
        }
        // 数学计算（明确说"计算"，或 数字 +（等于/乘号等运算语义）；
        // 刻意排除 "-" "/"（日期 2026-09-02、分数 1/2 易误判），聊天自由优先）
        if (msg.contains("计算")
            || (msg.matches(".*[0-9].*") && (msg.contains("等于")
                || msg.contains("+") || msg.contains("*") || msg.contains("×")
                || msg.contains("除以") || msg.contains("乘以")))) {
            return "CALC";
        }

        return "CHAT";
    }
}
