package com.smartservice.orchestrator.agent;

import com.smartservice.orchestrator.AbstractLlmAgent;
import com.smartservice.orchestrator.LlmClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * P0-2: Sales Agent
 * 销售咨询：内置产品价格表，命中产品直接报价，未命中走 LLM 兜底
 */
@Component
public class SalesAgent extends AbstractLlmAgent {

    private static final Map<String, String> PRICES = Map.of(
        "智能音箱", "299元",
        "智能手表", "1299元",
        "智能摄像头", "399元",
        "无线耳机", "499元",
        "扫地机器人", "1999元"
    );

    protected SalesAgent(LlmClient llmClient) {
        super(llmClient);
    }

    @Override
    protected String systemPrompt() {
        return "你是智能客服平台的销售顾问。"
            + "负责回答产品价格、优惠活动、购买流程、配件搭配等问题。"
            + "主动推荐合适产品，语气热情，用中文回答。";
    }

    @Override
    public String process(String userId, String sessionId, String message, List<String> history) {
        // 价格表直查
        String hit = matchPrice(message);
        return hit != null ? hit : super.process(userId, sessionId, message, history);
    }

    @Override
    public void processStream(String userId, String sessionId, String message,
                              List<String> history, java.util.function.Consumer<String> onToken) {
        String hit = matchPrice(message);
        if (hit != null) {
            pushChunks(hit, onToken);
        } else {
            super.processStream(userId, sessionId, message, history, onToken);
        }
    }

    private String matchPrice(String message) {
        for (Map.Entry<String, String> entry : PRICES.entrySet()) {
            if (message.contains(entry.getKey())) {
                String price = entry.getValue();
                String recommend = entry.getKey().equals("智能手表")
                    ? " 这款手表支持心率监测和睡眠分析，目前有满1000减100的活动。"
                    : " 当前下单可享包邮优惠。";
                return entry.getKey() + "售价" + price + "。" + recommend;
            }
        }
        return null;
    }
}
