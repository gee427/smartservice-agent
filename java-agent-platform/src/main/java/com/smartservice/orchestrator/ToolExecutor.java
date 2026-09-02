package com.smartservice.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // 可替换的 HTTP 抓取器：默认走真实 Open-Meteo；测试可注入假实现，避免单测依赖外网
    private Function<String, String> httpFetcher = url -> {
        try {
            return httpGet(url);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    };

    void setHttpFetcher(Function<String, String> fetcher) {
        this.httpFetcher = fetcher;
    }

    // WMO 天气代码 -> 中文描述（Open-Meteo 返回）
    private static final Map<Integer, String> WMO = Map.ofEntries(
            Map.entry(0, "晴"), Map.entry(1, "大致晴朗"), Map.entry(2, "局部多云"), Map.entry(3, "阴"),
            Map.entry(45, "雾"), Map.entry(48, "雾凇"),
            Map.entry(51, "小毛毛雨"), Map.entry(53, "毛毛雨"), Map.entry(55, "大毛毛雨"),
            Map.entry(56, "冻毛毛雨"), Map.entry(57, "冻毛毛雨"),
            Map.entry(61, "小雨"), Map.entry(63, "中雨"), Map.entry(65, "大雨"),
            Map.entry(66, "冻雨"), Map.entry(67, "冻雨"),
            Map.entry(71, "小雪"), Map.entry(73, "中雪"), Map.entry(75, "大雪"),
            Map.entry(77, "雪粒"),
            Map.entry(80, "小阵雨"), Map.entry(81, "阵雨"), Map.entry(82, "大阵雨"),
            Map.entry(85, "小阵雪"), Map.entry(86, "大阵雪"),
            Map.entry(95, "雷暴"), Map.entry(96, "雷暴伴冰雹"), Map.entry(99, "雷暴伴冰雹")
    );

    /**
     * 查询实时天气（Open-Meteo 免费接口，无需 API key）：
     * 1) 城市名 -> 经纬度（geocoding-api.open-meteo.com）
     * 2) 经纬度 -> 当前天气（api.open-meteo.com/v1/forecast）
     * 网络不可用时优雅降级，返回可读提示而非抛异常。
     */
    public String getWeather(String city) {
        if (city == null || city.isBlank()) {
            return "请提供城市名。";
        }
        try {
            String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name="
                    + URLEncoder.encode(city, StandardCharsets.UTF_8)
                    + "&count=1&language=zh&format=json";
            JsonNode loc = objectMapper.readTree(httpFetcher.apply(geoUrl)).path("results").path(0);
            if (loc.isMissingNode()) {
                return "未找到城市：" + city;
            }
            double lat = loc.path("latitude").asDouble();
            double lon = loc.path("longitude").asDouble();
            String name = loc.path("name").asText(city);
            String country = loc.has("country") ? "（" + loc.path("country").asText() + "）" : "";

            String fcUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon
                    + "&current=temperature_2m,wind_speed_10m,weather_code&timezone=auto";
            JsonNode current = objectMapper.readTree(httpFetcher.apply(fcUrl)).path("current");
            if (current.isMissingNode()) {
                return name + country + "：暂无天气数据";
            }
            double temp = current.path("temperature_2m").asDouble();
            double wind = current.path("wind_speed_10m").asDouble();
            int code = current.path("weather_code").asInt();
            String desc = WMO.getOrDefault(code, "天气");
            return String.format("%s%s：%s，%.0f°C，风速 %.0f km/h", name, country, desc, temp, wind);
        } catch (Exception e) {
            // 解包到根因：真实抓取把网络异常包进 RuntimeException，测试也可能直接抛
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            if (root instanceof java.net.ConnectException || root instanceof java.net.SocketTimeoutException) {
                return "暂时无法获取" + city + "的天气（网络不可用）";
            }
            log.warn("Weather query failed for {}: {}", city, e.getMessage());
            return "获取" + city + "天气失败：" + (e.getMessage() != null ? e.getMessage() : "未知错误");
        }
    }

    private String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "SmartService/1.0")
                .GET()
                .build();
        HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    public String calculate(String expression) {
        if (expression == null || expression.isBlank()) {
            return "请提供数学表达式。";
        }
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
