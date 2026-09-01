package com.smartservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * P2-1: JWT 工具
 * HS256 签名，claims: sub=username, role, iat, exp
 * 使用 jjwt 0.12.x 标准 API（builder/parser 校验签名）
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireMs;

    public JwtUtil(@Value("${agent.jwt.secret}") String secret,
                   @Value("${agent.jwt.expire-hours:24}") long expireHours) {
        // HS256 要求密钥 >= 256 bit（32 字节），过短直接启动失败暴露问题
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMs = expireHours * 3600_000L;
    }

    /**
     * 签发 token
     */
    public String generate(String username, String role) {
        Date now = new Date();
        return Jwts.builder()
            .subject(username)
            .claim("role", role)
            .issuedAt(now)
            .expiration(new Date(now.getTime() + expireMs))
            .signWith(key)
            .compact();
    }

    /**
     * 解析并校验 token，返回 claims；无效/过期抛异常
     */
    public Claims parse(String token) {
        return Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public String getUsername(Claims claims) {
        return claims.getSubject();
    }

    public String getRole(Claims claims) {
        return claims.get("role", String.class);
    }

    public long getExpireMs() {
        return expireMs;
    }
}
