package com.smartservice.orchestrator;

import java.util.List;
import java.util.function.Consumer;

/**
 * P0-2: 业务 Agent 接口
 * 所有业务 Agent 的统一契约，支持同步与流式两种处理方式
 */
public interface BusinessAgent {

    /**
     * 处理用户消息
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @param history   交替字符串历史（user, assistant, ...）
     * @return 回复文本
     */
    String process(String userId, String sessionId, String message, List<String> history);

    /**
     * 流式处理用户消息，逐块回调文本
     * 默认实现：完整执行后按字符块推送（打字机效果）
     */
    default void processStream(String userId, String sessionId, String message,
                               List<String> history, Consumer<String> onToken) {
        String result = process(userId, sessionId, message, history);
        // 按 2 字符块推送，模拟打字机
        for (int i = 0; i < result.length(); i += 2) {
            onToken.accept(result.substring(i, Math.min(i + 2, result.length())));
        }
    }
}
