package com.smartservice.orchestrator.agent;

import com.smartservice.orchestrator.AbstractLlmAgent;
import com.smartservice.orchestrator.LlmClient;
import com.smartservice.orchestrator.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * P0-2: Calc Agent
 * 数学计算：通过 LLM 工具调用（function calling）链路执行计算
 */
@Component
public class CalcAgent extends AbstractLlmAgent {

    private final ToolExecutor toolExecutor;

    protected CalcAgent(LlmClient llmClient, ToolExecutor toolExecutor) {
        super(llmClient);
        this.toolExecutor = toolExecutor;
    }

    @Override
    protected String systemPrompt() {
        return "你是智能客服平台的计算助手。"
            + "当用户需要数学计算时，使用 Calculator 工具计算结果，并给出简明回答。用中文。";
    }

    @Override
    public String process(String userId, String sessionId, String message, List<String> history) {
        List<Map<String, Object>> messages = buildMessages(history, message);
        return llmClient.chatWithTools(messages, toolExecutor.buildToolsSchema(), toolExecutor, 3);
    }

    /**
     * 工具调用链路完成后流式输出
     */
    @Override
    public void processStream(String userId, String sessionId, String message,
                              List<String> history, java.util.function.Consumer<String> onToken) {
        pushChunks(process(userId, sessionId, message, history), onToken);
    }
}
