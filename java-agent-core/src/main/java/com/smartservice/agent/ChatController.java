package com.smartservice.agent;

import com.smartservice.api.ApiResponse;
import com.smartservice.api.BusinessException;
import com.smartservice.memory.SessionManager;
import com.smartservice.tools.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Day 4-5: 单 Agent REST API + ReAct 工具调用
 * 直接调用 LM Studio HTTP API，支持 OpenAI Function Calling 格式
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class ChatController {

    private final SessionManager sessionManager;
    private final ToolRegistry toolRegistry;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_ITERATIONS = 5;

    // ========== OpenAI Function Calling 工具定义 ==========
    private List<Map<String, Object>> buildToolsSchema() {
        List<Map<String, Object>> toolsList = new ArrayList<>();

        // Weather 工具
        toolsList.add(Map.of(
            "type", "function",
            "function", Map.of(
                "name", "Weather",
                "description", "查询指定城市的天气情况",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "city", Map.of("type", "string", "description", "城市名，如'北京'、'上海'")
                    ),
                    "required", List.of("city")
                )
            )
        ));

        // Calculator 工具
        toolsList.add(Map.of(
            "type", "function",
            "function", Map.of(
                "name", "Calculator",
                "description", "进行数学计算，支持加减乘除和括号",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "expression", Map.of("type", "string", "description", "数学表达式，如'2+3*4'、'(100-20)/4'")
                    ),
                    "required", List.of("expression")
                )
            )
        ));

        return toolsList;
    }

    // ========== API 端点 ==========

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (!StringUtils.hasText(request.message())) {
            throw new BusinessException(ApiResponse.ErrorCode.BAD_REQUEST, "消息内容不能为空");
        }

        String sessionId = request.sessionId() != null ? request.sessionId() :
                          java.util.UUID.randomUUID().toString();

        // 获取历史记录（JSON 字符串列表）
        List<Map<String, String>> history = sessionManager.getHistory(sessionId);

        // 构建消息列表：system + history + user
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "你是一个智能客服助手，有 Weather 和 Calculator 两个工具可用。当用户需要查询天气或计算时，请调用对应工具。请用中文回答。"));
        for (Map<String, String> h : history) {
            messages.add(new HashMap<>(h));
        }
        messages.add(Map.of("role", "user", "content", request.message()));

        // 执行 ReAct 循环
        String finalAnswer = reactLoop(messages);

        // 保存对话记录
        sessionManager.addMessage(sessionId, "user", request.message());
        sessionManager.addMessage(sessionId, "assistant", finalAnswer);

        return ApiResponse.success(new ChatResponse(sessionId, finalAnswer, "success"));
    }

    @GetMapping("/sessions/{sessionId}/history")
    public ApiResponse<List<Map<String, String>>> getHistory(@PathVariable String sessionId) {
        return ApiResponse.success(sessionManager.getHistory(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Map<String, String>> clearSession(@PathVariable String sessionId) {
        sessionManager.clearSession(sessionId);
        return ApiResponse.success(Map.of("status", "cleared", "sessionId", sessionId));
    }

    // ========== ReAct 循环 ==========

    private String reactLoop(List<Map<String, Object>> messages) {
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            // 调用 LM Studio
            Map<String, Object> response = callLMStudio(messages);
            if (response == null) {
                return "抱歉，LM Studio 调用失败。";
            }

            String content = (String) response.get("content");
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) response.get("tool_calls");

            // 没有工具调用 → 这是最终回答
            if (toolCalls == null || toolCalls.isEmpty()) {
                return content != null ? content : "（模型返回空）";
            }

            // 有工具调用 → 执行工具，结果加入消息列表
            Map<String, Object> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", content);
            assistantMsg.put("tool_calls", toolCalls);
            messages.add(assistantMsg);

            for (Map<String, Object> tc : toolCalls) {
                String toolName = (String) ((Map<String, Object>) tc.get("function")).get("name");
                String argsStr = (String) ((Map<String, Object>) tc.get("function")).get("arguments");
                String toolCallId = (String) tc.get("id");

                log.info("🔧 调用工具: {}({})", toolName, argsStr);

                // 解析参数并执行工具
                String result;
                try {
                    if ("Weather".equals(toolName)) {
                        Map<String, String> args = objectMapper.readValue(argsStr, new TypeReference<Map<String, String>>() {
                            // 匿名子类：仅用于类型信息
                        });
                        result = toolRegistry.execute("Weather", args.get("city"));
                    } else if ("Calculator".equals(toolName)) {
                        Map<String, String> args = objectMapper.readValue(argsStr, new TypeReference<Map<String, String>>() {
                            // 匿名子类：仅用于类型信息
                        });
                        result = toolRegistry.execute("Calculator", args.get("expression"));
                    } else {
                        result = "未知工具：" + toolName;
                    }
                } catch (Exception e) {
                    result = "工具执行错误：" + e.getMessage();
                }

                log.info("  📊 结果: {}", result);

                // 将工具结果作为 tool 角色消息加入
                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", toolCallId);
                toolMsg.put("content", result);
                messages.add(toolMsg);
            }

            // 继续循环，让 LLM 处理工具结果
        }

        return "抱歉，Agent 执行超过最大迭代次数(" + MAX_ITERATIONS + ")，请重试。";
    }

    // ========== 调用 LM Studio HTTP API ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> callLMStudio(List<Map<String, Object>> messages) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "qwen3vl-8b-uncensored-hauhaucs-aggressive");
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 2000);
            requestBody.put("tools", buildToolsSchema());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> rawResponse = restTemplate.postForEntity(
                "http://localhost:1234/v1/chat/completions",
                entity,
                Map.class
            );

            Map<String, Object> body = rawResponse.getBody();
            if (body == null || !body.containsKey("choices")) {
                return null;
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
            if (choices.isEmpty()) {
                return null;
            }

            Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
            if (msg == null) {
                return null;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("content", msg.get("content"));
            result.put("tool_calls", msg.get("tool_calls"));
            return result;

        } catch (Exception e) {
            log.error("调用 LM Studio 失败", e);
            return null;
        }
    }

    // ========== DTOs ==========

    public record ChatRequest(String userId, String sessionId, String message) {
        // 聊天请求体
    }

    public record ChatResponse(String sessionId, String content, String status) {
        // 聊天响应体
    }
}
