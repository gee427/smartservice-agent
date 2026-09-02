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
        // 注入假抓取器：geocoding 返回北京坐标，forecast 返回 WMO code=3（阴）
        executor.setHttpFetcher(url -> url.contains("geocoding-api")
                ? "{\"results\":[{\"name\":\"北京\",\"latitude\":39.9,\"longitude\":116.4,\"country\":\"中国\"}]}"
                : "{\"current\":{\"temperature_2m\":23.5,\"wind_speed_10m\":12.0,\"weather_code\":3}}");
        String result = executor.execute("Weather", "{\"city\":\"北京\"}");
        assertTrue(result.contains("北京"), "实际: " + result);
        assertTrue(result.contains("阴"), "WMO 映射错误: " + result);
        assertTrue(result.contains("24°C"), "实际: " + result);
        assertTrue(result.contains("风速 12 km/h"), "实际: " + result);
    }

    @Test
    void weather_unknownCity() throws Exception {
        // geocoding 返回空 results -> 未找到城市
        executor.setHttpFetcher(url -> url.contains("geocoding-api") ? "{\"results\":[]}" : "{}");
        String result = executor.execute("Weather", "{\"city\":\"火星\"}");
        assertTrue(result.contains("未找到城市"), "实际: " + result);
    }

    @Test
    void weather_networkDown() throws Exception {
        // 抓取器抛网络异常 -> 优雅降级为「网络不可用」
        executor.setHttpFetcher(url -> { throw new RuntimeException(new java.net.SocketTimeoutException("timeout")); });
        String result = executor.execute("Weather", "{\"city\":\"北京\"}");
        assertTrue(result.contains("网络不可用"), "实际: " + result);
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
