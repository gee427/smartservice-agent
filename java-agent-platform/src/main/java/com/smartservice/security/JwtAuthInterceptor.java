package com.smartservice.security;

import com.smartservice.api.ApiResponse;
import com.smartservice.api.BusinessException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * P2-1: JWT 认证拦截器
 * 从 Authorization: Bearer <token> 解析并校验，通过后把用户信息放入 request attribute
 * 无 token / 无效 / 过期 一律返回 40100（前端凭此跳转登录页）
 */
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USERNAME = "auth.username";
    public static final String ATTR_ROLE = "auth.role";

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new BusinessException(ApiResponse.ErrorCode.UNAUTHORIZED, "缺少认证凭证");
        }

        String token = header.substring(7).trim();
        try {
            Claims claims = jwtUtil.parse(token);
            request.setAttribute(ATTR_USERNAME, jwtUtil.getUsername(claims));
            request.setAttribute(ATTR_ROLE, jwtUtil.getRole(claims));
            return true;
        } catch (Exception e) {
            throw new BusinessException(ApiResponse.ErrorCode.UNAUTHORIZED, "凭证无效或已过期");
        }
    }
}
