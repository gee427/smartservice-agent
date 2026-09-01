package com.smartservice.tools;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具注册中心
 * 管理所有可用工具，支持动态注册
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolDefinition> tools = new HashMap<>();

    public ToolRegistry() {
        // 注册默认工具
        register("Weather", "查询城市天气", this::getWeather);
        register("Calculator", "数学计算", this::calculate);
    }

    public void register(String name, String description, Function<String, String> executor) {
        tools.put(name, new ToolDefinition(name, description, executor));
    }

    public String execute(String name, String input) {
        ToolDefinition tool = tools.get(name);
        if (tool == null) {
            return "工具不存在: " + name;
        }
        return tool.executor().apply(input);
    }

    public Map<String, ToolDefinition> getAllTools() {
        return new HashMap<>(tools);
    }

    // 工具实现
    private String getWeather(String city) {
        Map<String, String> data = Map.of(
            "北京", "晴，25°C，北风2级",
            "上海", "多云，28°C，东南风3级",
            "广州", "小雨，30°C，南风2级"
        );
        return data.getOrDefault(city, "暂无" + city + "数据");
    }

    private String calculate(String expression) {
        try {
            javax.script.ScriptEngine engine =
                new javax.script.ScriptEngineManager().getEngineByName("JavaScript");
            Object result = engine.eval(expression);
            return "计算结果：" + result;
        } catch (Exception e) {
            return "计算错误：" + e.getMessage();
        }
    }

    public record ToolDefinition(String name, String description,
                                  Function<String, String> executor) {
        // 工具定义
    }
}
