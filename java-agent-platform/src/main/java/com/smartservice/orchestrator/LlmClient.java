package com.smartservice.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * P0-2: LLM 调用客户端
 * 统一封装 LM Studio (OpenAI 兼容 API) 调用，支持普通对话与 ReAct 工具循环
 */
@Slf4j
@Component
public class LlmClient {

    /**
     * RestTemplate 显式配置超时：LLM 服务无响应时必须快速失败，而不是无限挂起
     * fast：意图分类等低延迟场景（5s）；默认：对话生成（20s）
     * tool：工具调用链路专用（60s）——qwen3vl 等 reasoning 模型拿到工具结果后
     *      需要"思考"再组织回答，生成耗时明显长于普通对话，20s 会误报超时
     */
    private final RestTemplate restTemplate = createRestTemplate(20_000);
    private final RestTemplate fastRestTemplate = createRestTemplate(5_000);
    private final RestTemplate toolRestTemplate = createRestTemplate(60_000);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static RestTemplate createRestTemplate(int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }

    /**
     * 快速对话（5s 读超时），用于意图分类等对延迟敏感的场景
     */
    public String chatFast(List<Map<String, Object>> messages, double temperature, int maxTokens) {
        return chat(messages, temperature, maxTokens, fastRestTemplate);
    }

    @Value("${agent.llm.base-url:http://localhost:1234/v1}")
    private String baseUrl;

    @Value("${agent.llm.model:qwen3vl-8b-uncensored-hauhaucs-aggressive}")
    private String model;

    /**
     * 普通对话，返回最终文本
     */
    public String chat(List<Map<String, Object>> messages, double temperature, int maxTokens) {
        return chat(messages, temperature, maxTokens, restTemplate);
    }

    private String chat(List<Map<String, Object>> messages, double temperature,
                        int maxTokens, RestTemplate client) {
        Map<String, Object> resp = callRaw(messages, temperature, maxTokens, null, null, client);
        if (resp == null) {
            return "抱歉，LLM 调用失败，请稍后重试。";
        }
        String content = (String) resp.get("content");
        return content != null ? content : "（模型返回空）";
    }

    /**
     * 带工具调用（OpenAI function calling 格式），自动执行 ReAct 循环直至无 tool_calls
     */
    public String chatWithTools(List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools,
                                ToolExecutor toolExecutor,
                                int maxIterations) {
        return chatWithTools(messages, tools, toolExecutor, maxIterations, null);
    }

    /**
     * 带工具调用，支持强制首轮必须调用工具（toolChoice="required"）
     * 用于计算/天气等"必须走工具链路"的 Agent：LLM 自己心算/编造结果会跳过工具调用，
     * 协议层强制 tool_choice 后，模型第一轮必须返回 tool_calls，杜绝"自己算"路径。
     */
    public String chatWithTools(List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools,
                                ToolExecutor toolExecutor,
                                int maxIterations,
                                String toolChoice) {
        List<Map<String, Object>> working = new ArrayList<>(messages);
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            // 强制 tool_choice 只在第一轮生效！
            // 实测（qwen3vl + LM Studio）：拿到工具结果后的轮次若仍带 tool_choice="required"，
            // 模型会被强迫"必须再调用一次工具"，陷入矛盾直接卡死（>75s 无响应）。
            // 后续轮次改回默认(auto)，模型才能正常基于工具结果组织最终回答。
            String effectiveToolChoice = (iteration == 0) ? toolChoice : null;
            Map<String, Object> resp = callRaw(working, 0.3, 2000, tools, effectiveToolChoice, toolRestTemplate);
            if (resp == null) {
                return "抱歉，LLM 调用失败，请稍后重试。";
            }

            String content = (String) resp.get("content");
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) resp.get("tool_calls");

            if (toolCalls == null || toolCalls.isEmpty()) {
                return content != null ? content : "（模型返回空）";
            }

            // assistant 消息原样保留 tool_calls
            Map<String, Object> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", content);
            assistantMsg.put("tool_calls", toolCalls);
            working.add(assistantMsg);

            for (Map<String, Object> tc : toolCalls) {
                Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                String toolName = (String) fn.get("name");
                String argsJson = (String) fn.get("arguments");
                String toolCallId = (String) tc.get("id");

                log.info("🔧 调用工具: {}({})", toolName, argsJson);

                String result;
                try {
                    result = toolExecutor.execute(toolName, argsJson);
                } catch (Exception e) {
                    result = "工具执行错误：" + e.getMessage();
                }

                log.info("  📊 结果: {}", result);
                working.add(Map.of("role", "tool", "tool_call_id", toolCallId, "content", result));
            }
        }
        return "抱歉，Agent 执行超过最大迭代次数(" + maxIterations + ")，请重试。";
    }

    /**
     * 流式对话（SSE），逐 token 回调
     * 使用 JDK HttpClient 解析 text/event-stream，供流式输出端点使用
     */
    public void streamChat(List<Map<String, Object>> messages, double temperature,
                           int maxTokens, Consumer<String> onToken) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("stream", true);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build();

        // 连接与首字节超时保护：LM Studio 引擎崩溃时 stream 请求会无限挂起，必须兜底
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        // 非 2xx 视为失败（如模型未加载的 400），抛异常让上层捕获并提示用户
        if (response.statusCode() >= 400) {
            throw new RuntimeException("LM Studio stream error: HTTP " + response.statusCode());
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]")) {
                    break;
                }
                JsonNode node = objectMapper.readTree(data);
                JsonNode delta = node.path("choices").get(0).path("delta").path("content");
                if (!delta.isMissingNode() && !delta.isNull() && !delta.asText().isEmpty()) {
                    onToken.accept(delta.asText());
                }
            }
        }
    }

    /**
     * 带工具调用的真流式：工具调用阶段非流式（结构化调用无法逐 token），
     * 工具执行完后最终回答走 SSE 逐 token 推送（打字机效果）
     * @param toolChoice 首轮强制工具调用（"required"），后续轮次自动回退 auto
     */
    public void streamChatWithTools(List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools,
                                    ToolExecutor toolExecutor,
                                    int maxIterations,
                                    String toolChoice,
                                    Consumer<String> onToken) throws Exception {
        List<Map<String, Object>> working = new ArrayList<>(messages);
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            String effectiveToolChoice = (iteration == 0) ? toolChoice : null;
            Map<String, Object> resp = callRaw(working, 0.3, 2000, tools, effectiveToolChoice, toolRestTemplate);
            if (resp == null) {
                onToken.accept("抱歉，LLM 调用失败，请稍后重试。");
                return;
            }
            String content = (String) resp.get("content");
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) resp.get("tool_calls");

            if (toolCalls == null || toolCalls.isEmpty()) {
                // 无工具调用（如天气没给城市直接反问）→ 推送已有内容
                if (content != null && !content.isBlank()) {
                    onToken.accept(content);
                }
                return;
            }

            // 有工具调用：补 assistant 消息 + 执行工具
            Map<String, Object> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", content);
            assistantMsg.put("tool_calls", toolCalls);
            working.add(assistantMsg);

            for (Map<String, Object> tc : toolCalls) {
                Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                String toolName = (String) fn.get("name");
                String argsJson = (String) fn.get("arguments");
                String toolCallId = (String) tc.get("id");
                log.info("🔧 调用工具: {}({})", toolName, argsJson);
                String result;
                try {
                    result = toolExecutor.execute(toolName, argsJson);
                } catch (Exception e) {
                    result = "工具执行错误：" + e.getMessage();
                }
                log.info("  📊 结果: {}", result);
                working.add(Map.of("role", "tool", "tool_call_id", toolCallId, "content", result));
            }

            // 工具已执行完：最终回答走真流式（SSE 逐 token），边生成边推送
            streamChat(working, 0.3, 2000, onToken);
            return;
        }
        onToken.accept("抱歉，Agent 执行超过最大迭代次数(" + maxIterations + ")，请重试。");
    }

    /**
     * P1-2: 探测 LLM 引擎是否真正可用（最小 chat 请求 + 30s 结果缓存）
     * 两层假阳性教训：
     *  1) TCP 端口探测：进程存活即通过，引擎崩溃时端口仍监听
     *  2) /models 探测：HTTP 层可响应，但引擎无 worker 时 /chat/completions 仍挂起
     * 因此 readiness 必须发一个极小 chat 请求（max_tokens=1），
     * 引擎挂掉时 fastRestTemplate 5s 读超时兜底返回 false。
     * 探测结果缓存 30s，避免管理后台 5s 轮询反复打 LLM 干扰业务。
     */
    private volatile boolean llmHealthy = false;
    private volatile long lastProbeAt = 0L;
    private static final long PROBE_CACHE_MS = 30_000L;

    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now - lastProbeAt < PROBE_CACHE_MS) {
            return llmHealthy;
        }
        llmHealthy = probeEngine();
        lastProbeAt = now;
        return llmHealthy;
    }

    private boolean probeEngine() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "user", "content", "ping")));
            body.put("max_tokens", 1);
            body.put("temperature", 0);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> resp = fastRestTemplate.postForEntity(
                baseUrl + "/chat/completions", entity, Map.class);
            return resp.getStatusCode().is2xxSuccessful()
                && resp.getBody() != null
                && resp.getBody().containsKey("choices");
        } catch (Exception e) {
            log.debug("LLM 引擎探测失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 基础调用，返回 content + tool_calls
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callRaw(List<Map<String, Object>> messages,
                                        double temperature,
                                        int maxTokens,
                                        List<Map<String, Object>> tools,
                                        String toolChoice,
                                        RestTemplate client) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("temperature", temperature);
            requestBody.put("max_tokens", maxTokens);
            if (tools != null) {
                requestBody.put("tools", tools);
                if (toolChoice != null) {
                    requestBody.put("tool_choice", toolChoice);
                }
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> rawResponse = client.postForEntity(
                baseUrl + "/chat/completions", entity, Map.class
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
            log.error("调用 LM Studio 失败: {}", e.getMessage());
            return null;
        }
    }
}
