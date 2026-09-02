package com.smartservice.config;

import com.smartservice.ratelimit.RateLimitInterceptor;
import com.smartservice.security.JwtAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * P2-1/P2-2: Web MVC 配置
 * 拦截器顺序：限流(最外层) → 认证（先注册的先执行）
 * - /api/admin/** 与 /api/auth/me：JWT 认证
 * - /api/agent/chat* 与 /api/auth/login|register：限流（防刷 LLM / 防爆破）
 *
 * P4-2: CORS 跨域白名单
 * 同源部署（Spring Boot 静态托管前端）本不需要；前后端分离部署时生效。
 * 开发默认放开常见本地端口，生产由 agent.cors.allowed-origins 收紧（环境变量注入）。
 *
 * 注意：不要在此自定义 addResourceHandlers 覆盖默认静态资源映射！
 * Spring Boot 默认将 /** 映射到 classpath:/static/ 等位置，能正确处理 /js/*.js、/css/*.css。
 * 若自定义 pattern（如 /js/**），Spring 会剥掉 /js/ 前缀去 location 找资源，导致 admin.js 404。
 * 静态资源缓存策略（no-store）改由 application*.yml 的 spring.web.resources.cache 控制。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    /** P4-2: 允许跨域来源（逗号分隔），默认常见本地开发端口 */
    @Value("${agent.cors.allowed-origins:http://localhost:8080,http://localhost:3000,http://localhost:5173}")
    private String allowedOrigins;

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

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // P4-2: 仅 API 路径开放跨域；允许携带凭证（Authorization 头）
        registry.addMapping("/api/**")
            .allowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).toArray(String[]::new))
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600);
    }
}
