package com.smartservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * SmartService Agent Core 启动类
 * 阶段二：Java 单 Agent 工程化
 */
@SpringBootApplication
public class AgentCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentCoreApplication.class, args);
        System.out.println("========================================");
        System.out.println("SmartService Agent Core 启动成功！");
        System.out.println("API 地址: http://localhost:8081/api/agent");
        System.out.println("========================================");
    }

    @Bean
    RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
