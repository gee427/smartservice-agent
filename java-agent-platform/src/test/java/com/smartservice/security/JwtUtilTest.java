package com.smartservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-3: JwtUtil 单元测试
 */
class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-key-0123456789abcdef-0123456789";

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 24);
    private final JwtUtil otherJwtUtil = new JwtUtil(SECRET + "-different", 24);
    private final JwtUtil expiredJwtUtil = new JwtUtil(SECRET, 0); // 0 小时：立即过期

    @Test
    void generateAndParse_roundTrip() {
        String token = jwtUtil.generate("alice", "ADMIN");
        Claims claims = jwtUtil.parse(token);

        assertEquals("alice", jwtUtil.getUsername(claims));
        assertEquals("ADMIN", jwtUtil.getRole(claims));
        assertTrue(claims.getExpiration().getTime() > System.currentTimeMillis());
    }

    @Test
    void tamperedToken_throws() {
        String token = jwtUtil.generate("alice", "ADMIN");
        // 篡改 payload 部分（第 2 段末尾改一个字符）
        String[] parts = token.split("\\.");
        char last = parts[1].charAt(parts[1].length() - 1);
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 1)
            + (last == 'a' ? 'b' : 'a') + "." + parts[2];
        assertThrows(JwtException.class, () -> jwtUtil.parse(tampered));
    }

    @Test
    void expiredToken_throws() throws InterruptedException {
        String token = expiredJwtUtil.generate("bob", "ADMIN");
        Thread.sleep(20); // 确保 exp <= now
        assertThrows(JwtException.class, () -> expiredJwtUtil.parse(token));
    }

    @Test
    void differentSecret_fails() {
        String token = jwtUtil.generate("carol", "ADMIN");
        assertThrows(JwtException.class, () -> otherJwtUtil.parse(token));
    }
}
