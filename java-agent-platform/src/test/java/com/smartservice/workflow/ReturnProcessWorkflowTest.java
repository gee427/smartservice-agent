package com.smartservice.workflow;

import com.smartservice.workflow.ReturnProcessWorkflow.Event;
import com.smartservice.workflow.ReturnProcessWorkflow.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-3: 退货状态机单元测试
 * 覆盖正常流转、拒绝/取消分支、非法转移
 */
class ReturnProcessWorkflowTest {

    private final ReturnProcessWorkflow wf = new ReturnProcessWorkflow();

    @Test
    void happyPath_fullFlow() {
        State s = State.INIT;
        assertTrue(wf.trigger(s, Event.START));
        s = wf.nextState(s, Event.START);
        assertEquals(State.ELIGIBILITY_CHECK, s);

        assertTrue(wf.trigger(s, Event.PASS));
        s = wf.nextState(s, Event.PASS);
        assertEquals(State.REASON_COLLECT, s);

        assertTrue(wf.trigger(s, Event.PROVIDE_REASON));
        s = wf.nextState(s, Event.PROVIDE_REASON);
        assertEquals(State.SOLUTION_OFFER, s);

        assertTrue(wf.trigger(s, Event.ACCEPT));
        s = wf.nextState(s, Event.ACCEPT);
        assertEquals(State.CONFIRMATION, s);

        assertTrue(wf.trigger(s, Event.CONFIRM));
        s = wf.nextState(s, Event.CONFIRM);
        assertEquals(State.COMPLETED, s);
    }

    @Test
    void eligibilityReject_cancels() {
        State s = State.ELIGIBILITY_CHECK;
        assertTrue(wf.trigger(s, Event.REJECT));
        assertEquals(State.CANCELLED, wf.nextState(s, Event.REJECT));
    }

    @Test
    void offerDecline_cancels() {
        State s = State.SOLUTION_OFFER;
        assertTrue(wf.trigger(s, Event.DECLINE));
        assertEquals(State.CANCELLED, wf.nextState(s, Event.DECLINE));
    }

    @Test
    void invalidTransition_returnsFalse() {
        // INIT 不能直接 CONFIRM
        assertFalse(wf.trigger(State.INIT, Event.CONFIRM));
        // REASON_COLLECT 不能 REJECT
        assertFalse(wf.trigger(State.REASON_COLLECT, Event.REJECT));
        // 终态 COMPLETED 无后续转移
        assertFalse(wf.trigger(State.COMPLETED, Event.CONFIRM));
        assertFalse(wf.trigger(State.CANCELLED, Event.START));
    }

    @Test
    void generateResponse_initialPrompt() {
        assertEquals("您好，请问需要办理退货吗？（是/否）", wf.generateResponse(State.INIT, ""));
        assertEquals("退货申请已提交！将在3-5个工作日处理。", wf.generateResponse(State.COMPLETED, ""));
        assertTrue(wf.generateResponse(State.REASON_COLLECT, "").contains("退货原因"));
    }

    @Test
    void eligibility_checkRequiresOrderInfo() {
        // 无订单信息 -> 无法验证
        assertEquals("无法验证订单信息。", wf.generateResponse(State.ELIGIBILITY_CHECK, "hi"));
        // 有订单信息 -> 通过
        assertEquals("订单符合退货条件。", wf.generateResponse(State.ELIGIBILITY_CHECK, "订单号 20260815001"));
    }
}
