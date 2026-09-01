package com.smartservice.audit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * P4-4: 审计日志组件（商用合规要求：关键操作留痕，可追溯）
 *
 * 输出到独立文件 logs/audit.log（logback 单独 appender，保留 90 天），
 * 单行格式：{action} user={username} ip={ip} result={ok|fail} detail={...} cost={Nms}
 *
 * 覆盖场景：注册 / 登录成功·失败 / 管理后台删除会话等敏感操作。
 * 审计日志与业务日志隔离，避免运维检索被业务噪音淹没。
 */
@Slf4j
@Component
public class AuditLogger {

    /** 独立 logger 名，对应 logback-spring.xml 中的 AUDIT appender */
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /**
     * 记录一条审计事件（自动附带客户端 IP，无需调用方传 request）
     *
     * @param action 动作标识，如 auth.login / admin.session.delete
     * @param username 操作者
     * @param result  结果：ok / fail
     * @param detail  补充信息（如失败原因、目标资源）
     */
    public void log(String action, String username, String result, String detail) {
        AUDIT.info("action={} user={} ip={} result={} detail={}",
            action, safe(username), clientIp(), result, safe(detail));
    }

    /** 便捷方法：成功操作（自动计算耗时） */
    public void success(String action, String username, String detail, long startMs) {
        log(action, username, "ok", detail + " cost=" + (System.currentTimeMillis() - startMs) + "ms");
    }

    /** 便捷方法：失败操作 */
    public void failure(String action, String username, String detail, long startMs) {
        log(action, username, "fail", detail + " cost=" + (System.currentTimeMillis() - startMs) + "ms");
    }

    /**
     * 从当前请求上下文提取客户端 IP。
     * 优先取 X-Forwarded-For（经过反向代理/负载均衡时），否则取 remoteAddr。
     */
    private String clientIp() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "-";
            }
            HttpServletRequest req = attrs.getRequest();
            String forwarded = req.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return "-";
        }
    }

    private String safe(String s) {
        return s == null || s.isBlank() ? "-" : s.replace('\n', ' ').replace('\r', ' ');
    }
}
