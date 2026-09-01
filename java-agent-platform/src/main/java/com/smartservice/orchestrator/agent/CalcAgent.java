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
            + "当用户提出任何数学计算需求时，你必须调用 Calculator 工具完成计算，"
            + "绝对禁止自己心算或凭经验直接给出结果；"
            + "以工具返回的结果为准组织回答，并给出简明中文说明。";
    }

    @Override
    public String process(String userId, String sessionId, String message, List<String> history) {
        List<Map<String, Object>> messages = buildMessages(history, message);
        // toolChoice="required"：协议层强制首轮必须调用 Calculator 工具，杜绝"LLM 自己算"跳过工具链路
        return llmClient.chatWithTools(messages, toolExecutor.buildToolsSchema(), toolExecutor, 3, "required");
    }

    /**
     * 真流式：工具调用阶段非流式（结构化），工具执行完后最终回答 SSE 逐 token 推送
     */
    @Override
    public void processStream(String userId, String sessionId, String message,
                              List<String> history, java.util.function.Consumer<String> onToken) {
        try {
            List<Map<String, Object>> messages = buildMessages(history, message);
            llmClient.streamChatWithTools(messages, toolExecutor.buildToolsSchema(), toolExecutor, 3, "required", onToken);
        } catch (Exception e) {
            logError(e);
            onToken.accept("（AI 服务暂时不可用，请稍后重试）");
        }
    }
}
