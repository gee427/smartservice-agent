package com.smartservice.orchestrator.agent;

import com.smartservice.orchestrator.BusinessAgent;
import com.smartservice.workflow.ReturnProcessWorkflow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * P0-2 + P0-3: Return Agent
 * 退货流程：基于状态机（ReturnProcessWorkflow）的多轮引导
 * 会话状态持久化到 Redis（服务重启不丢，TTL 24h）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnAgent implements BusinessAgent {

    private final ReturnProcessWorkflow workflow;
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String STATE_KEY_PREFIX = "agent:return:state:";
    private static final long TTL_HOURS = 24;

    @Override
    public String process(String userId, String sessionId, String message, List<String> history) {
        ReturnProcessWorkflow.State current = loadState(sessionId);

        switch (current) {
            case INIT -> {
                // START → ELIGIBILITY_CHECK
                saveState(sessionId, next(current, ReturnProcessWorkflow.Event.START));
                return "您好，请问需要办理退货吗？请提供订单号（直接输入数字即可）。";
            }
            case ELIGIBILITY_CHECK -> {
                if (isEligible(message)) {
                    saveState(sessionId, next(current, ReturnProcessWorkflow.Event.PASS));
                    return "订单符合退货条件。请问退货原因是？（质量问题 / 不喜欢 / 其他）";
                }
                saveState(sessionId, next(current, ReturnProcessWorkflow.Event.REJECT));
                return "无法验证订单信息，本次退货申请已关闭。可稍后重新发起。";
            }
            case REASON_COLLECT -> {
                saveState(sessionId, next(current, ReturnProcessWorkflow.Event.PROVIDE_REASON));
                if (message.contains("质量")) {
                    return "质量问题可申请全额退款或换货，您选择哪种？（退款 / 换货）";
                }
                return "非质量问题支持7天无理由退货。确认办理吗？（确认 / 取消）";
            }
            case SOLUTION_OFFER -> {
                if (message.contains("退款") || message.contains("换货") || message.contains("确认")) {
                    saveState(sessionId, next(current, ReturnProcessWorkflow.Event.ACCEPT));
                    return "请确认是否办理退货？（确认 / 取消）";
                }
                saveState(sessionId, next(current, ReturnProcessWorkflow.Event.DECLINE));
                return "好的，已取消本次退货申请。";
            }
            case CONFIRMATION -> {
                if (message.contains("确认")) {
                    saveState(sessionId, next(current, ReturnProcessWorkflow.Event.CONFIRM));
                    return "退货申请已提交！我们将在3-5个工作日内处理，请保持手机畅通，留意退款到账通知。";
                }
                saveState(sessionId, next(current, ReturnProcessWorkflow.Event.CANCEL));
                return "已取消申请。如有需要请随时联系。";
            }
            case COMPLETED -> {
                return "您的退货申请已完成办理。如需新的售后需求，请开启新会话。";
            }
            case CANCELLED -> {
                return "您的退货申请已关闭。如需重新办理，请说\"重新办理退货\"。";
            }
            default -> {
                return "抱歉，退货流程出现异常，请重新发起。";
            }
        }
    }

    /**
     * 通过状态机计算下一状态（状态机真正参与流转）
     */
    private ReturnProcessWorkflow.State next(ReturnProcessWorkflow.State current,
                                             ReturnProcessWorkflow.Event event) {
        ReturnProcessWorkflow.State next = workflow.nextState(current, event);
        log.info("Return workflow transition: {} --{}--> {}", current, event, next);
        return next;
    }

    /**
     * 资格校验简化版：输入包含数字（订单号）即视为通过
     */
    private boolean isEligible(String message) {
        return message != null && message.matches(".*\\d.*");
    }

    /**
     * 从 Redis 读取会话状态（无则 INIT）
     */
    private ReturnProcessWorkflow.State loadState(String sessionId) {
        Object value = redisTemplate.opsForValue().get(STATE_KEY_PREFIX + sessionId);
        if (value == null) {
            return ReturnProcessWorkflow.State.INIT;
        }
        try {
            return ReturnProcessWorkflow.State.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            return ReturnProcessWorkflow.State.INIT;
        }
    }

    /**
     * 持久化会话状态到 Redis（TTL 24h）
     */
    private void saveState(String sessionId, ReturnProcessWorkflow.State state) {
        redisTemplate.opsForValue().set(STATE_KEY_PREFIX + sessionId, state.name(), TTL_HOURS, TimeUnit.HOURS);
    }
}
