package com.smartservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P3-5: OpenAPI / Swagger UI 配置
 *
 * 访问:
 *   Swagger UI   http://localhost:8080/swagger-ui.html
 *   OpenAPI JSON http://localhost:8080/v3/api-docs
 *
 * 声明 bearer-jwt 安全方案后，Swagger UI 右上角 Authorize 输入
 * 登录/注册接口返回的 token，即可直接调试 /api/admin/** 受保护端点。
 * 注意: 文档声明仅影响 UI 展示，实际鉴权仍由 JwtAuthInterceptor 控制。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI smartServiceOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("SmartService Agent Platform API")
                .description("SmartService 本地 AI 聊天平台 REST API（SSE 流式 / 会话持久化 / 通用对话 + 天气/计算工具路由 / JWT 认证 / 限流 / 监控指标）")
                .version("1.0.0")
                .contact(new Contact().name("SmartService-Agent")))
            .components(new Components()
                .addSecuritySchemes("bearer-jwt",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
