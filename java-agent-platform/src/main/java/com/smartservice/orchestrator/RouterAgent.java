package com.smartservice.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Day 9-10 + P0-2: 路由 Agent
 * 基于 LLM 的意图分类，分发到差异化业务 Agent（Spring 注入注册表）
 */
@Slf4j
@Component
public class RouterAgent {

    private final LlmClient llmClient;
    private final Map<String, BusinessAgent> agentRegistry;

    /**
     * 意图代码 → Agent bean 名映射
     */
    private static final Map<String, String> INTENT_TO_AGENT = Map.of(
        "FAQ", "faqAgent",
        "TECH", "techAgent",
        "SALES", "salesAgent",
        "RETURN", "returnAgent",
        "WEATHER", "weatherAgent",
        "CALC", "calcAgent",
        "CHAT", "chatAgent"
    );

    /**
     * Spring 自动注入所有 BusinessAgent 实现（key = bean 名）
     */
    public RouterAgent(LlmClient llmClient, Map<String, BusinessAgent> agents) {
        this.llmClient = llmClient;
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
     * 根据意图解析对应业务 Agent（未知意图回退 FAQ）
     */
    public BusinessAgent resolveAgent(String intent) {
        String agentName = INTENT_TO_AGENT.getOrDefault(intent, "faqAgent");
        BusinessAgent agent = agentRegistry.get(agentName);
        return agent != null ? agent : agentRegistry.get("faqAgent");
    }

    /**
     * 使用 LLM 进行意图分类
     * 前置规则：确定性关键词直接命中意图（不依赖 LLM 稳定性），LLM 只兜底模糊场景
     * 说明：LLM 对中文意图分类存在漂移（同一句话可能今天判 FAQ 明天判 RETURN），
     * 关键意图（天气/计算/退货流程/FAQ 知识词）用规则钉死，测试与线上行为才稳定。
     */
    private String classifyIntent(String message) {
        String msg = message == null ? "" : message;

        // ---- 规则前置（确定性）----
        if (msg.contains("天气")) {
            return "WEATHER";
        }
        // 计算：含"计算"，或含数字 +（等于/多少/运算符号）
        if (msg.contains("计算")
            || (msg.matches(".*[0-9].*") && (msg.contains("等于") || msg.contains("多少")
                || msg.contains("+") || msg.contains("-") || msg.contains("*") || msg.contains("/") || msg.contains("×")))) {
            return "CALC";
        }
        // 退货/售后：办理流程语义（问订单号 → 原因 → 确认 → 提交）
        if (msg.contains("退货") || msg.contains("退款") || msg.contains("售后")) {
            return "RETURN";
        }
        // FAQ 知识库词（纯知识咨询，无流程歧义）
        if (msg.contains("保修") || msg.contains("发货") || msg.contains("发票") || msg.contains("运费")) {
            return "FAQ";
        }

        // ---- LLM 兜底（模糊场景）----
        try {
            String prompt = "请判断以下用户问题的意图类别，只返回类别代码（大写）：\n"
                + "- FAQ: 常见问题（产品使用、参数、功能咨询）\n"
                + "- TECH: 技术问题（故障排查、报错、维修）\n"
                + "- SALES: 销售咨询（价格、购买、优惠、配件）\n"
                + "- RETURN: 退货/售后流程\n"
                + "- WEATHER: 天气查询\n"
                + "- CALC: 数学计算\n"
                + "- CHAT: 闲聊/问候\n\n"
                + "用户问题：" + message + "\n\n"
                + "意图类别：";

            String result = llmClient.chatFast(
                List.of(Map.of("role", "user", "content", prompt)), 0.3, 50
            ).trim().toUpperCase();

            return switch (result) {
                case "FAQ", "TECH", "SALES", "RETURN", "WEATHER", "CALC", "CHAT" -> result;
                default -> "FAQ";
            };
        } catch (Exception e) {
            log.warn("Intent classification failed, fallback to FAQ: {}", e.getMessage());
        }
        return "FAQ";
    }
}
