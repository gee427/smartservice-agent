package com.smartservice.auth;

import com.smartservice.api.ApiResponse;
import com.smartservice.security.AuthService;
import com.smartservice.security.JwtAuthInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * P2-1/P2-2: 认证接口
 * 注册/登录公开（限流保护），/me 需要登录（拦截器保护）
 */
@Tag(name = "认证", description = "注册 / 登录 / 当前用户（P2-1 JWT + P2-2 限流）")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "注册", description = "注册即返回 JWT token，默认 ADMIN 角色；用户名 3-32 位字母/数字/下划线，密码 6-64 位")
    @PostMapping("/register")
    public ApiResponse<Map<String, String>> register(@Valid @RequestBody AuthRequest request) {
        return ApiResponse.success(authService.register(request.username(), request.password()));
    }

    @Operation(summary = "登录", description = "校验密码返回 JWT token；同一 IP 每分钟限 5 次")
    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@Valid @RequestBody AuthRequest request) {
        return ApiResponse.success(authService.login(request.username(), request.password()));
    }

    /**
     * 当前登录用户信息（由拦截器写入 attribute）
     */
    @Operation(summary = "当前用户", description = "需 Authorization: Bearer &lt;token&gt;，返回用户名与角色")
    @GetMapping("/me")
    public ApiResponse<Map<String, String>> me(HttpServletRequest request) {
        String username = (String) request.getAttribute(JwtAuthInterceptor.ATTR_USERNAME);
        String role = (String) request.getAttribute(JwtAuthInterceptor.ATTR_ROLE);
        return ApiResponse.success(Map.of("username", username, "role", role));
    }

    public record AuthRequest(
        @Pattern(regexp = "[a-zA-Z0-9_]{3,32}", message = "用户名需为 3-32 位字母/数字/下划线")
        String username,
        @Size(min = 6, max = 64, message = "密码长度需为 6-64 位")
        String password) {}
}
