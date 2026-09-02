package com.smartservice.orchestrator.agent;

import com.smartservice.orchestrator.AbstractLlmAgent;
import com.smartservice.orchestrator.LlmClient;
import com.smartservice.orchestrator.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * P0-2: Weather Agent
 * 天气查询：通过 LLM 工具调用（function calling）链路查询天气
 * 演示平台侧的 ReAct 工具循环
 */
@Component
public class WeatherAgent extends AbstractLlmAgent {

    private final ToolExecutor toolExecutor;

    protected WeatherAgent(LlmClient llmClient, ToolExecutor toolExecutor) {
        super(llmClient);
        this.toolExecutor = toolExecutor;
    }

    @Override
    protected String systemPrompt() {
        return "你是 SmartService 本地 AI 助手中的天气查询助手。"
            + "当用户询问某个城市的天气时，你必须调用 Weather 工具查询，"
            + "绝对禁止自己编造天气信息，以工具返回的结果为准回答；"
            + "如果用户没有说明城市，先询问是哪个城市，不要调用工具。用中文回答。";
    }

    @Override
    public String process(String userId, String sessionId, String message, List<String> history) {
        List<Map<String, Object>> messages = buildMessages(history, message);
        // 注意：天气不能像计算一样强制 tool_choice="required"——
        // Weather 工具的 city 是必填参数，若用户没给城市（如"今天天气怎么样"），
        // 强制调用会逼模型编造城市名。因此这里仅靠 prompt 约束：
        // 给城市→必须调工具；没给城市→先询问。CalcAgent 因表达式必然完整才用强制。
        return llmClient.chatWithTools(messages, toolExecutor.buildToolsSchema(), toolExecutor, 3);
    }

    /**
     * 真流式：工具调用阶段非流式（结构化），工具执行完后最终回答 SSE 逐 token 推送
     * 不给城市时不强制工具（toolChoice=null），prompt 约束会引导模型先询问
     */
    @Override
    public void processStream(String userId, String sessionId, String message,
                              List<String> history, java.util.function.Consumer<String> onToken) {
        try {
            List<Map<String, Object>> messages = buildMessages(history, message);
            llmClient.streamChatWithTools(messages, toolExecutor.buildToolsSchema(), toolExecutor, 3, null, onToken);
        } catch (Exception e) {
            logError(e);
            onToken.accept("（AI 服务暂时不可用，请稍后重试）");
        }
    }
}
