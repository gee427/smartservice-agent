package com.smartservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SmartService Agent Platform 启动类
 * 阶段三~四：多 Agent 协作 + 生产级平台
 */
@SpringBootApplication
public class AgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
        System.out.println("========================================");
        System.out.println("SmartService Agent Platform 启动成功！");
        System.out.println("API 地址: http://localhost:8080/api/agent");
        System.out.println("监控地址: http://localhost:8080/actuator/prometheus");
        System.out.println("========================================");
    }
}
