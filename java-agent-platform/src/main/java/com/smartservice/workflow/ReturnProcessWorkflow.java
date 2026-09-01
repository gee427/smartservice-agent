package com.smartservice.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Day 13-14: 退货流程状态机
 * 管理复杂业务流程的状态转移
 */
@Slf4j
@Component
public class ReturnProcessWorkflow {

    public enum State {
        INIT, ELIGIBILITY_CHECK, REASON_COLLECT,
        SOLUTION_OFFER, CONFIRMATION, COMPLETED, CANCELLED
    }

    public enum Event {
        START, PASS, REJECT, PROVIDE_REASON,
        ACCEPT, DECLINE, CONFIRM, CANCEL
    }

    private final Map<State, Map<Event, State>> transitions;

    public ReturnProcessWorkflow() {
        this.transitions = buildTransitions();
    }

    private Map<State, Map<Event, State>> buildTransitions() {
        Map<State, Map<Event, State>> map = new EnumMap<>(State.class);

        map.put(State.INIT, Map.of(Event.START, State.ELIGIBILITY_CHECK));
        map.put(State.ELIGIBILITY_CHECK, Map.of(
            Event.PASS, State.REASON_COLLECT,
            Event.REJECT, State.CANCELLED
        ));
        map.put(State.REASON_COLLECT, Map.of(
            Event.PROVIDE_REASON, State.SOLUTION_OFFER
        ));
        map.put(State.SOLUTION_OFFER, Map.of(
            Event.ACCEPT, State.CONFIRMATION,
            Event.DECLINE, State.CANCELLED
        ));
        map.put(State.CONFIRMATION, Map.of(
            Event.CONFIRM, State.COMPLETED,
            Event.CANCEL, State.CANCELLED
        ));

        return map;
    }

    /**
     * 触发状态转移
     */
    public boolean trigger(State currentState, Event event) {
        Map<Event, State> fromCurrent = transitions.get(currentState);
        if (fromCurrent == null || !fromCurrent.containsKey(event)) {
            log.warn("Invalid transition: {} -> {}", currentState, event);
            return false;
        }
        return true;
    }

    /**
     * 获取下一个状态
     */
    public State nextState(State currentState, Event event) {
        return transitions.get(currentState).get(event);
    }

    /**
     * 根据当前状态生成回复
     */
    public String generateResponse(State state, String userInput) {
        return switch (state) {
            case INIT -> "您好，请问需要办理退货吗？（是/否）";
            case ELIGIBILITY_CHECK -> checkEligibility(userInput);
            case REASON_COLLECT -> "了解，请问退货原因是？（质量问题/不喜欢/其他）";
            case SOLUTION_OFFER -> offerSolution(userInput);
            case CONFIRMATION -> "请确认是否办理？（确认/取消）";
            case COMPLETED -> "退货申请已提交！将在3-5个工作日处理。";
            case CANCELLED -> "已取消申请。如有需要请随时联系。";
        };
    }

    private String checkEligibility(String input) {
        // 简化：假设输入包含订单号就通过
        if (input != null && input.length() > 3) {
            return "订单符合退货条件。";
        }
        return "无法验证订单信息。";
    }

    private String offerSolution(String reason) {
        if (reason.contains("质量")) {
            return "质量问题可申请全额退款或换货。您选择哪种？（退款/换货）";
        }
        return "非质量问题支持7天无理由退货。确认办理吗？";
    }
}
