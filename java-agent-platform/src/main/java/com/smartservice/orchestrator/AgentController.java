package com.smartservice.orchestrator;

import com.smartservice.api.ApiResponse;
import com.smartservice.memory.SessionManager;
import com.smartservice.memory.SessionTracker;
import com.smartservice.metrics.AgentMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Day 9-10 + P0-1/P0-4 + P1-1/P1-2: 平台 REST API
 * 多 Agent 协作入口：Redis 会话持久化 + 统一响应体 + SSE 流式输出 + 会话索引
 */
@Tag(name = "对话", description = "多 Agent 对话：普通 / SSE 流式 / 会话管理（P0-P1）")
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentOrchestrator orchestrator;
    private final AgentMetrics agentMetrics;
    private final SessionManager sessionManager;
    private final SessionTracker sessionTracker;
    private final Executor taskExecutor;

    private static final long SSE_TIMEOUT_MS = 120_000L;

    @Operation(summary = "普通对话", description = "路由到 7 类业务 Agent 返回完整回复；同一 IP 每分钟限 10 次")
    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        String sessionId = request.sessionId() != null ? request.sessionId() :
                          java.util.UUID.randomUUID().toString();

        agentMetrics.incrementActiveSessions();

        // 读取该会话历史（交替字符串），传入编排器作为多轮上下文
        List<String> history = sessionManager.getHistory(sessionId);

        AgentOrchestrator.AgentResult result = orchestrator.process(
            request.userId(), sessionId, request.message(), history
        );

        // 持久化本次对话
        sessionManager.addMessage(sessionId, "user", request.message());
        sessionManager.addMessage(sessionId, "assistant", result.response());
        sessionTracker.track(sessionId, request.userId(), result.intent(),
            sessionManager.getHistory(sessionId).size() / 2);

        log.info("Session {} | intent: {}", sessionId, result.intent());
        return ApiResponse.success(new ChatResponse(sessionId, result.intent(), result.response(), "success"));
    }

    /**
     * P1-1: SSE 流式对话
     * 返回 text/event-stream，逐块推送 AI 回复，前端打字机渲染
     */
    @Operation(summary = "SSE 流式对话", description = "text/event-stream 逐块推送，末尾 done 事件回传 intent；同一 IP 每分钟限 10 次")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        String sessionId = request.sessionId() != null ? request.sessionId() :
                          java.util.UUID.randomUUID().toString();
        agentMetrics.incrementActiveSessions();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        taskExecutor.execute(() -> {
            StringBuilder full = new StringBuilder();
            try {
                List<String> history = sessionManager.getHistory(sessionId);
                String intent = orchestrator.processStream(request.userId(), sessionId,
                    request.message(), history, token -> {
                        full.append(token);
                        try {
                            emitter.send(SseEmitter.event().name("message").data(token));
                        } catch (IOException e) {
                            throw new RuntimeException("SSE send failed", e);
                        }
                    });

                // 持久化完整对话
                sessionManager.addMessage(sessionId, "user", request.message());
                sessionManager.addMessage(sessionId, "assistant", full.toString());
                sessionTracker.track(sessionId, request.userId(), intent,
                    sessionManager.getHistory(sessionId).size() / 2);

                emitter.send(SseEmitter.event().name("done")
                    .data(Map.of("intent", intent, "done", true)));
                emitter.complete();
                log.info("Stream finished for session {}, intent={}, chars={}",
                    sessionId, intent, full.length());
            } catch (Exception e) {
                log.error("Stream chat failed for session {}", sessionId, e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @GetMapping("/sessions/{sessionId}/history")
    public ApiResponse<List<Map<String, String>>> getHistory(@PathVariable String sessionId) {
        return ApiResponse.success(sessionManager.getMessages(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Map<String, String>> clearSession(@PathVariable String sessionId) {
        sessionManager.clearSession(sessionId);
        return ApiResponse.success(Map.of("status", "cleared", "sessionId", sessionId));
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of("status", "UP", "service", "java-agent-platform"));
    }

    public record ChatRequest(String userId, String sessionId,
                              @NotBlank(message = "消息内容不能为空") String message) {}

    public record ChatResponse(String sessionId, String intent, String content, String status) {}
}
