package com.smartservice.orchestrator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-3: ToolExecutor 单元测试
 * 覆盖天气查询、计算器（白名单校验）、未知工具
 */
class ToolExecutorTest {

    private final ToolExecutor executor = new ToolExecutor();

    @Test
    void weather_knownCity() throws Exception {
        String result = executor.execute("Weather", "{\"city\":\"北京\"}");
        assertEquals("晴，25°C，北风2级", result);
    }

    @Test
    void weather_unknownCity() throws Exception {
        String result = executor.execute("Weather", "{\"city\":\"火星\"}");
        assertTrue(result.contains("暂无"));
    }

    @Test
    void weather_blankCity() throws Exception {
        String result = executor.execute("Weather", "{\"city\":\"\"}");
        assertTrue(result.contains("请提供城市名"));
    }

    @Test
    void calculator_validExpression() throws Exception {
        String result = executor.execute("Calculator", "{\"expression\":\"15 * 23 + 8\"}");
        assertTrue(result.contains("353"), "实际结果: " + result);
    }

    @Test
    void calculator_invalidCharacters_rejected() throws Exception {
        // 白名单校验必须拦截字母/注入字符
        String result = executor.execute("Calculator", "{\"expression\":\"1 + abc\"}");
        assertTrue(result.contains("非法字符"), "实际结果: " + result);

        String inject = executor.execute("Calculator", "{\"expression\":\"1; rm -rf /\"}");
        assertTrue(inject.contains("非法字符"), "实际结果: " + inject);
    }

    @Test
    void unknownTool_returnsHint() throws Exception {
        String result = executor.execute("HackTool", "{}");
        assertTrue(result.contains("未知工具"));
    }

    @Test
    void buildToolsSchema_containsTwoFunctions() {
        assertEquals(2, executor.buildToolsSchema().size());
    }
}
