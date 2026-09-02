package com.smartservice.orchestrator.agent;

import com.smartservice.orchestrator.AbstractLlmAgent;
import com.smartservice.orchestrator.LlmClient;
import org.springframework.stereotype.Component;

/**
 * P0-2: Chat Agent（2026-09-02 起升级为主对话助手）
 * 通用对话：本地 AI 聊天平台的主入口，无业务限制
 */
@Component
public class ChatAgent extends AbstractLlmAgent {

    protected ChatAgent(LlmClient llmClient) {
        super(llmClient);
    }

    @Override
    protected String systemPrompt() {
        return "你是 SmartService 本地 AI 助手的通用对话智能体，负责与用户自由聊天。"
            + "可以讨论任何话题：写作、翻译、代码、创意、知识问答、学习、生活建议等，"
            + "回答自然、友好、有条理；不确定的内容如实说明，不编造。"
            + "当用户明确需要查天气或做数学计算时，平台会自动路由到对应工具，你无需引导。用中文回答。";
    }
}
