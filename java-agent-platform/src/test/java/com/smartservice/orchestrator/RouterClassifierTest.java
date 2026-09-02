package com.smartservice.orchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * 2026-09-02: 轻量路由规则单测（纯确定性，不依赖 Spring / LLM）
 * 去客服化后：天气 / 计算关键词直连，其余一律 CHAT；
 * 旧的客服业务词（运费/退货/保修等）不再命中任何业务意图。
 */
class RouterClassifierTest {

    private RouterAgent router;
    private BusinessAgent chatAgent;

    @BeforeEach
    void setUp() {
        chatAgent = mock(BusinessAgent.class);
        router = new RouterAgent(Map.of("chatAgent", chatAgent));
    }

    // ---- 天气直连 ----

    @Test
    void weather_keywords() {
        assertEquals("WEATHER", router.classify("北京今天天气怎么样"));
        assertEquals("WEATHER", router.classify("上海明天下雨吗"));
        assertEquals("WEATHER", router.classify("查询一下广州的气温"));
        assertEquals("WEATHER", router.classify("明天会下雪吗"));
    }

    // ---- 计算直连 ----

    @Test
    void calc_keywords() {
        assertEquals("CALC", router.classify("帮我算一下 15*23+8 等于多少"));
        assertEquals("CALC", router.classify("计算 2 的 10 次方"));
        assertEquals("CALC", router.classify("100 除以 4 等于多少"));
        assertEquals("CALC", router.classify("123×456 等于几"));
    }

    // ---- 其余一律 CHAT（聊天自由优先）----

    @Test
    void chat_free_topics() {
        assertEquals("CHAT", router.classify("写一首关于秋天的诗"));
        assertEquals("CHAT", router.classify("帮我翻译这句话成英文"));
        assertEquals("CHAT", router.classify("怎么学习 Java？"));
        assertEquals("CHAT", router.classify("你好"));
    }

    @Test
    void chat_oldCustomerServiceWords_notBusinessIntent() {
        // 旧客服词（运费/退货/发票/保修）不再命中任何客服业务意图
        assertEquals("CHAT", router.classify("你们的运费怎么收"));
        assertEquals("CHAT", router.classify("我想退货"));
        assertEquals("CHAT", router.classify("这个怎么保修"));
    }

    @Test
    void chat_dates_notMisclassifiedAsCalc() {
        // 日期中的 "-" 不应触发 CALC（2026-09-02）
        assertEquals("CHAT", router.classify("今天是2026-09-02，星期几"));
        assertEquals("CHAT", router.classify("帮我看看这份合同 1/2 的条款"));
    }

    // ---- 未知意图回退 CHAT ----

    @Test
    void unknownIntent_fallsBackToChatAgent() {
        assertSame(chatAgent, router.resolveAgent("UNKNOWN"));
        assertSame(chatAgent, router.resolveAgent("FAQ"));
    }
}
