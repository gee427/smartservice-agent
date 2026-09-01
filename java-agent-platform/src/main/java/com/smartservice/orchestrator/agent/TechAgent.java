package com.smartservice.orchestrator.agent;

import com.smartservice.orchestrator.AbstractLlmAgent;
import com.smartservice.orchestrator.LlmClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * P0-2: Tech Agent
 * 技术支持：内置常见故障排查步骤库，未命中走 LLM 兜底
 */
@Component
public class TechAgent extends AbstractLlmAgent {

    private static final Map<String, String> TROUBLESHOOT = Map.ofEntries(
        Map.entry("无法连接", "请按以下步骤排查：1. 确认设备已通电；2. 检查Wi-Fi密码是否正确；3. 重启设备后重试。"),
        Map.entry("连不上", "请按以下步骤排查：1. 确认设备已通电；2. 检查Wi-Fi密码是否正确；3. 重启设备后重试。"),
        Map.entry("卡顿", "请尝试：1. 清理设备缓存；2. 检查网络信号；3. 更新固件到最新版本。"),
        Map.entry("黑屏", "请尝试：1. 长按电源键10秒强制重启；2. 检查电源适配器是否损坏；3. 仍无法解决请联系售后。"),
        Map.entry("报错", "请提供完整报错信息截图，并尝试重启设备。如果问题持续，需要升级固件。")
    );

    protected TechAgent(LlmClient llmClient) {
        super(llmClient);
    }

    @Override
    protected String systemPrompt() {
        return "你是智能客服平台的技术支持工程师。"
            + "针对设备故障、报错、维修问题给出逐步排查方案。"
            + "语气专业耐心，用中文回答。如果用户描述模糊，先引导提供更多信息。";
    }

    @Override
    public String process(String userId, String sessionId, String message, List<String> history) {
        // 故障库直查
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
        for (Map.Entry<String, String> entry : TROUBLESHOOT.entrySet()) {
            if (message.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
