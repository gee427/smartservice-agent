package com.smartservice.security;

import com.smartservice.api.ApiResponse;
import com.smartservice.api.BusinessException;
import com.smartservice.audit.AuditLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * P2-1: 用户认证服务
 * 用户存储：Redis hash agent:user:{username} -> {passwordHash, role, createdAt}
 * 密码：BCrypt 单向加密（不可逆，彩虹表/撞库无效）
 * 注意：Redis 存用户适合学习/演示；商用项目应换关系型数据库 + 唯一索引
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtUtil jwtUtil;
    private final AuditLogger auditLogger;

    private static final String USER_KEY_PREFIX = "agent:user:";
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String DEFAULT_ROLE = "ADMIN"; // 学习项目简化：注册即管理员

    /**
     * 注册：用户名唯一，密码 BCrypt 加密后落库
     * 用户名/密码格式由 Controller 层 @Valid 校验（P2-2），此处只查重
     */
    public Map<String, String> register(String username, String password) {
        long start = System.currentTimeMillis();
        String key = USER_KEY_PREFIX + username;
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            auditLogger.failure("auth.register", username, "username exists", start);
            throw new BusinessException(ApiResponse.ErrorCode.BAD_REQUEST, "用户名已存在");
        }

        Map<String, Object> user = new HashMap<>();
        user.put("passwordHash", ENCODER.encode(password));
        user.put("role", DEFAULT_ROLE);
        user.put("createdAt", System.currentTimeMillis());

        redisTemplate.opsForHash().putAll(key, user);
        log.info("新用户注册: {}", username);
        Map<String, String> result = login(username, password);
        auditLogger.success("auth.register", username, "user created", start);
        return result;
    }

    /**
     * 登录：校验密码，签发 JWT
     */
    public Map<String, String> login(String username, String password) {
        long start = System.currentTimeMillis();
        String key = USER_KEY_PREFIX + username;
        Object hashObj = redisTemplate.opsForHash().get(key, "passwordHash");
        if (hashObj == null) {
            auditLogger.failure("auth.login", username, "user not found", start);
            throw new BusinessException(ApiResponse.ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        String hash = String.valueOf(hashObj);
        if (!ENCODER.matches(password, hash)) {
            auditLogger.failure("auth.login", username, "bad password", start);
            throw new BusinessException(ApiResponse.ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        Object roleObj = redisTemplate.opsForHash().get(key, "role");
        String role = roleObj != null ? String.valueOf(roleObj) : DEFAULT_ROLE;
        String token = jwtUtil.generate(username, role);

        Map<String, String> result = new HashMap<>();
        result.put("token", token);
        result.put("username", username);
        result.put("role", role);
        result.put("expiresIn", jwtUtil.getExpireMs() + "");
        auditLogger.success("auth.login", username, "token issued", start);
        return result;
    }
}
