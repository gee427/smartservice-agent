package com.smartservice.orchestrator.agent;

import com.smartservice.orchestrator.AbstractLlmAgent;
import com.smartservice.orchestrator.LlmClient;
import org.springframework.stereotype.Component;

/**
 * P0-2: Chat Agent
 * 闲聊对话：通用 LLM，无领域知识库
 */
@Component
public class ChatAgent extends AbstractLlmAgent {

    protected ChatAgent(LlmClient llmClient) {
        super(llmClient);
    }

    @Override
    protected String systemPrompt() {
        return "你是智能客服平台的闲聊助手。"
            + "和用户轻松自然地聊天，回答简短友好。如果话题涉及产品业务，引导用户咨询相关服务。用中文。";
    }
}
