package com.smartservice.orchestrator.agent;

import com.smartservice.orchestrator.AbstractLlmAgent;
import com.smartservice.orchestrator.LlmClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * P0-2: FAQ Agent
 * 常见问题解答：关键词命中内置知识库直接返回，未命中走 LLM 兜底
 */
@Component
public class FaqAgent extends AbstractLlmAgent {

    private static final Map<String, String> FAQ = Map.ofEntries(
        Map.entry("退货", "本店支持7天无理由退货，商品需保持完好、不影响二次销售。"),
        Map.entry("保修", "所有商品提供1年免费保修，人为损坏不在保修范围内。"),
        Map.entry("发货", "下单后48小时内发货，偏远地区可能延迟1-2天。"),
        Map.entry("发票", "支持开具电子发票，下单时填写发票抬头即可。"),
        Map.entry("运费", "满99元免运费，不满99元收取10元运费。")
    );

    protected FaqAgent(LlmClient llmClient) {
        super(llmClient);
    }

    @Override
    protected String systemPrompt() {
        return "你是智能客服平台的常见问题解答专员。"
            + "请基于产品手册知识回答用户关于产品使用、参数、购买规则的问题。"
            + "回答简洁准确，用中文。不知道的内容如实说明，不要编造。";
    }

    @Override
    public String process(String userId, String sessionId, String message, List<String> history) {
        // 知识库直查：命中关键词直接返回标准答案
        String hit = matchKnowledgeBase(message);
        return hit != null ? hit : super.process(userId, sessionId, message, history);
    }

    @Override
    public void processStream(String userId, String sessionId, String message,
                              List<String> history, java.util.function.Consumer<String> onToken) {
        String hit = matchKnowledgeBase(message);
        if (hit != null) {
            pushChunks(hit, onToken);
        } else {
            super.processStream(userId, sessionId, message, history, onToken);
        }
    }

    private String matchKnowledgeBase(String message) {
        for (Map.Entry<String, String> entry : FAQ.entrySet()) {
            if (message.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
