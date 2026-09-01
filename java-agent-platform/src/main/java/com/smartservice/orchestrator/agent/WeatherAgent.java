package com.smartservice.orchestrator.agent;

import com.smartservice.orchestrator.AbstractLlmAgent;
import com.smartservice.orchestrator.LlmClient;
import com.smartservice.orchestrator.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
        return "你是智能客服平台的天气助手。"
            + "当用户询问天气时，使用 Weather 工具查询指定城市的天气。"
            + "如果用户没说城市，先询问是哪个城市。用中文回答。";
    }

    @Override
    public String process(String userId, String sessionId, String message, List<String> history) {
        List<Map<String, Object>> messages = buildMessages(history, message);
        return llmClient.chatWithTools(messages, toolExecutor.buildToolsSchema(), toolExecutor, 3);
    }

    /**
     * 工具调用链路完成后流式输出（工具循环不流式，最终结果按块推送）
     */
    @Override
    public void processStream(String userId, String sessionId, String message,
                              List<String> history, java.util.function.Consumer<String> onToken) {
        pushChunks(process(userId, sessionId, message, history), onToken);
    }
}
