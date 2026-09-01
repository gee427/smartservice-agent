package com.smartservice.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * P0-2: 工具执行器
 * 平台侧工具注册中心，供各业务 Agent 与 LLM function calling 使用
 */
@Slf4j
@Component
public class ToolExecutor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行工具（参数为 JSON 字符串）
     */
    public String execute(String name, String argumentsJson) throws Exception {
        switch (name) {
            case "Weather": {
                Map<String, String> args = objectMapper.readValue(argumentsJson, new TypeReference<Map<String, String>>() {});
                return getWeather(args.get("city"));
            }
            case "Calculator": {
                Map<String, String> args = objectMapper.readValue(argumentsJson, new TypeReference<Map<String, String>>() {});
                return calculate(args.get("expression"));
            }
            default:
                return "未知工具：" + name;
        }
    }

    /**
     * OpenAI function calling 格式的工具 Schema
     */
    public List<Map<String, Object>> buildToolsSchema() {
        return List.of(
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "Weather",
                    "description", "查询指定城市的天气情况",
                    "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of("city", Map.of("type", "string", "description", "城市名，如'北京'、'上海'")),
                        "required", List.of("city")
                    )
                )
            ),
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "Calculator",
                    "description", "进行数学计算，支持加减乘除和括号",
                    "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of("expression", Map.of("type", "string", "description", "数学表达式，如'2+3*4'、'(100-20)/4'")),
                        "required", List.of("expression")
                    )
                )
            )
        );
    }

    // ========== 工具实现 ==========

    public String getWeather(String city) {
        if (city == null || city.isBlank()) return "请提供城市名。";
        Map<String, String> data = Map.of(
            "北京", "晴，25°C，北风2级",
            "上海", "多云，28°C，东南风3级",
            "广州", "小雨，30°C，南风2级",
            "深圳", "阵雨，29°C，南风3级",
            "杭州", "阴，26°C，东风2级"
        );
        return data.getOrDefault(city, "暂无" + city + "的天气数据");
    }

    public String calculate(String expression) {
        if (expression == null || expression.isBlank()) return "请提供数学表达式。";
        // 白名单校验：只允许数字、四则运算、括号、小数点、取模
        if (!expression.matches("[0-9+\\-*/().%\\s]+")) {
            return "表达式包含非法字符：" + expression;
        }
        try {
            // 用 exp4j 解析求值（JDK 17 已移除 Nashorn，不再可用 javax.script JavaScript）
            double result = new ExpressionBuilder(expression).build().evaluate();
            // 整数值去掉小数点（353.0 -> 353）
            String text = result == Math.floor(result) && !Double.isInfinite(result)
                ? String.valueOf((long) result) : String.valueOf(result);
            return "计算结果：" + text;
        } catch (Exception e) {
            return "计算错误：" + e.getMessage();
        }
    }
}
