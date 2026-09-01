package com.smartservice.orchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P0-2: LLM Agent 抽象基类
 * 提供系统提示词 + 历史构建 + 标准 LLM 对话，子类只需定义领域提示词
 */
public abstract class AbstractLlmAgent implements BusinessAgent {

    protected final LlmClient llmClient;

    protected AbstractLlmAgent(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 领域系统提示词
     */
    protected abstract String systemPrompt();

    /**
     * 标准 LLM 对话（带多轮历史）
     */
    @Override
    public String process(String userId, String sessionId, String message, List<String> history) {
        return llmClient.chat(buildMessages(history, message), 0.7, 1000);
    }

    /**
     * 流式对话：直接走 LLM SSE 逐 token 推送（纯 LLM 子类适用）
     */
    @Override
    public void processStream(String userId, String sessionId, String message,
                              List<String> history, java.util.function.Consumer<String> onToken) {
        try {
            llmClient.streamChat(buildMessages(history, message), 0.7, 1000, onToken);
        } catch (Exception e) {
            logError(e);
            onToken.accept("（AI 服务暂时不可用，请稍后重试）");
        }
    }

    protected void logError(Exception e) {
        java.util.logging.Logger.getLogger(getClass().getName())
            .warning("Stream chat failed: " + e.getMessage());
    }

    /**
     * 按 2 字符块推送（供规则型/工具型子类的流式兜底）
     */
    protected void pushChunks(String result, java.util.function.Consumer<String> onToken) {
        for (int i = 0; i < result.length(); i += 2) {
            onToken.accept(result.substring(i, Math.min(i + 2, result.length())));
        }
    }

    protected List<Map<String, Object>> buildMessages(List<String> history, String message) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt()));
        for (int i = 0; i < history.size(); i += 2) {
            if (i < history.size()) {
                messages.add(Map.of("role", "user", "content", history.get(i)));
            }
            if (i + 1 < history.size()) {
                messages.add(Map.of("role", "assistant", "content", history.get(i + 1)));
            }
        }
        messages.add(Map.of("role", "user", "content", message));
        return messages;
    }
}
