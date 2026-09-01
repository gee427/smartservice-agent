package com.smartservice.config;

import com.smartservice.ratelimit.RateLimitInterceptor;
import com.smartservice.security.JwtAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * P2-1/P2-2: Web MVC 配置
 * 拦截器顺序：限流(最外层) → 认证（先注册的先执行）
 * - /api/admin/** 与 /api/auth/me：JWT 认证
 * - /api/agent/chat* 与 /api/auth/login|register：限流（防刷 LLM / 防爆破）
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 限流（最外层）
        registry.addInterceptor(rateLimitInterceptor)
            .addPathPatterns("/api/agent/chat", "/api/agent/chat/stream",
                "/api/auth/login", "/api/auth/register");
        // 2. 认证
        registry.addInterceptor(jwtAuthInterceptor)
            .addPathPatterns("/api/admin/**", "/api/auth/me");
    }
}
