package com.smartservice.ratelimit;

import com.smartservice.api.ApiResponse;
import com.smartservice.api.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * P2-2: 限流拦截器（Redis Lua 原子计数，固定窗口）
 * 维度：按客户端 IP，资源分两类——chat（对话接口）/ login（认证接口防爆破）
 * Lua 脚本原子执行 INCR + 首次 EXPIRE，避免并发下计数竞态。
 * 窗口 60s，超限抛 RATE_LIMITED(42900)。
 *
 * 商用演进：生产环境通常由 API 网关（Sentinel / Kong / Nginx limit_req）统一限流，
 * 或使用 Redis 令牌桶（平滑突发）。本实现为应用层最小闭环，含 Lua 原子性示范。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final long WINDOW_SECONDS = 60L;

    private static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>(
        "local c = redis.call('INCR', KEYS[1]) " +
        "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
        "if c > tonumber(ARGV[2]) then return 0 end " +
        "return 1",
        Long.class);

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${agent.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${agent.rate-limit.chat-per-minute:10}")
    private int chatPerMinute;

    @Value("${agent.rate-limit.login-per-minute:5}")
    private int loginPerMinute;

    public RateLimitInterceptor(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String uri = request.getRequestURI();
        String resource;
        int limit;
        if (uri.startsWith("/api/auth/")) {
            resource = "login";
            limit = loginPerMinute;
        } else {
            resource = "chat";
            limit = chatPerMinute;
        }

        String ip = clientIp(request);
        String key = "rate:" + resource + ":" + ip;

        Long allowed = redisTemplate.execute(FIXED_WINDOW_SCRIPT,
            List.of(key), WINDOW_SECONDS, limit);

        if (allowed == null || allowed == 0L) {
            log.warn("Rate limited: resource={} ip={} limit={}/min", resource, ip, limit);
            throw new BusinessException(ApiResponse.ErrorCode.RATE_LIMITED);
        }
        return true;
    }

    /**
     * 取客户端 IP：优先 X-Forwarded-For（反代场景），否则 remoteAddr
     */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String ip = request.getRemoteAddr();
        return ip != null ? ip : "unknown";
    }
}
