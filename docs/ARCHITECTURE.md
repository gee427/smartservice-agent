# SmartService-Agent 架构设计文档

> 版本：1.0 · 2026-09 · 配套代码库：SmartService-Agent

## 1. 系统架构总览

```mermaid
graph TB
    subgraph Client["客户端层"]
        U[终端用户浏览器<br/>chat 前端 / SSE]
        OP[运营浏览器<br/>admin 管理后台]
        DEV[开发者<br/>Swagger UI / REST 客户端]
    end

    subgraph Platform["Java 平台层 (java-agent-platform, :8080)"]
        CORS[WebMvc 配置<br/>拦截器: 限流→JWT→CORS]
        API[Controller 层<br/>Auth / Agent / Admin]
        ORCH[编排层 AgentOrchestrator]
        ROUTER[RouterAgent 轻量路由<br/>天气/计算直连，其余→Chat]
        AGENTS[业务 Agent<br/>Chat / Weather / Calc]
        METRICS[AgentMetrics 指标<br/>Micrometer]
        AUDIT[AuditLogger 审计]
        HEALTH[LlmHealthIndicator<br/>/actuator/health]
    end

    subgraph Infra["基础设施"]
        REDIS[(Redis<br/>会话/用户/限流计数)]
        LLM[LM Studio 本地 LLM<br/>localhost:1234]
        PROM[Prometheus<br/>:9090 抓取]
        AM[Alertmanager<br/>企业微信告警]
    end

    U -->|POST /api/agent/chat/stream| API
    OP -->|JWT| API
    DEV -->|/swagger-ui| API
    API --> ORCH
    ORCH --> ROUTER
    ROUTER --> AGENTS
    AGENTS --> LLM
    AGENTS --> REDIS
    API --> METRICS
    API --> AUDIT
    API --> HEALTH
    HEALTH --> LLM
    PROM -->|/actuator/prometheus :9090| Platform
    PROM --> AM
```

## 2. 分层说明

| 层 | 关键类 | 职责 |
|---|---|---|
| Controller | AuthController / AgentController / AdminController | 参数校验、统一响应体、SSE、JWT 上下文 |
| 编排 | AgentOrchestrator / RouterAgent | 路由决策、统一执行、错误降级、指标埋点 |
| 业务 Agent | ChatAgent / WeatherAgent / CalcAgent | 通用对话（主）/ 天气查询 / 数学计算（工具）|
| 安全 | AuthService / JwtUtil / JwtAuthInterceptor | BCrypt + JWT 签发校验 |
| 基础设施 | SessionManager / LlmClient / RateLimitInterceptor / AuditLogger | 会话持久化、LLM 调用、限流、审计 |
| 可观测 | AgentMetrics / LlmHealthIndicator | 指标、健康 |

## 3. 核心请求时序（流式对话）

```mermaid
sequenceDiagram
    participant U as 浏览器
    participant C as AgentController
    participant O as AgentOrchestrator
    participant R as RouterAgent
    participant A as 业务 Agent
    participant L as LlmClient
    participant S as SessionManager

    U->>C: POST /api/agent/chat/stream (SSE)
    C->>S: 读取/创建会话历史
    S-->>C: 历史上下文
    C->>O: processStream(message, history)
    O->>R: classify(message)
    R-->>O: intent (FAQ/TECH/...)
    O->>A: processStream(onToken)
    A->>L: 流式调用 LLM (SSE)
    L-->>A: token 流
    A-->>O: onToken 回调
    O-->>C: 逐 token 推送
    C-->>U: SSE data: token...
    C-->>U: SSE data: {done, intent}
    O->>S: 保存对话（截断到 max-history）
    O->>O: recordStream 指标埋点
```

## 4. 数据设计（Redis Key 约定）

| Key 模式 | 用途 | TTL |
|---|---|---|
| `agent:session:{sessionId}` | 会话消息列表（Hash/List） | 24h |
| `agent:session:index` | 会话索引（ZSet，按最后活动排序） | — |
| `agent:user:{username}` | 用户信息（Hash: passwordHash/role/createdAt） | 永久 |
| `agent:ratelimit:{resource}:{ip}:{window}` | 限流计数（固定窗口） | 60s |

## 5. 部署拓扑

```mermaid
graph LR
    subgraph Prod["生产环境（Docker Compose / 单机）"]
        N[nginx 反向代理<br/>TLS 终结]
        P[platform 容器<br/>:8080 业务<br/>:9090 监控(仅内网)]
        R[(Redis 容器)]
        M[LM Studio / LLM 服务]
    end

    PROM2[Prometheus] -->|scrape :9090| P
    PROM2 --> AM2[Alertmanager] --> WX[企业微信]

    N --> P
    P --> R
    P --> M
```

生产关键点：
- **监控端点**（:9090）只监听内网，由 Prometheus 抓取；不暴露公网。
- **配置**全部环境变量注入（见 application-prod.yml），JWT 密钥必填否则启动失败。
- **镜像**由 CI 推送 GHCR，首次推送后需在 GitHub Packages 设置 Public 才能匿名拉取。

## 6. 技术选型与理由

| 组件 | 选型 | 理由 |
|---|---|---|
| 语言/框架 | Java 17 + Spring Boot 3.2.5 | LTS、生态成熟、商用主流 |
| LLM 对接 | 自建 LlmClient（OpenAI 兼容协议） | 不绑定厂商 SDK；LM Studio 本地零成本 |
| 认证 | JWT(HS256) + BCrypt | 无状态、前后端分离友好；BCrypt 防彩虹表 |
| 存储 | Redis | 会话/用户/限流三合一，学习成本低；商用建议换 RDBMS |
| 限流 | Redis Lua 固定窗口 | 原子操作、分布式一致 |
| 测试 | JUnit5 + MockMvc + Jacoco | Spring 标准栈，覆盖率可量化 |
| 文档 | springdoc-openapi 2.5 | 与 Spring Boot 3.2 兼容，UI 可调 token |
| 监控 | Micrometer + Prometheus + Alertmanager | 事实标准，配置即可用 |
| 构建 | Maven + Checkstyle + jacoco | 质量门禁内建到 verify 阶段 |
| CI/CD | GitHub Actions | 免费配额、4 job 并行、GHCR 镜像 |

## 7. 已知取舍（学习项目 vs 商用）

| 项 | 本项目 | 商用建议 |
|---|---|---|
| 用户存储 | Redis Hash | MySQL/PostgreSQL + 唯一索引 + 密码策略 |
| 注册即 ADMIN | 简化 | RBAC 角色体系 + 审批流 |
| 前端 | 原生 HTML/JS | Vue/React + 构建 + 路由 + 状态管理 |
| 单实例 | 无横向扩展 | 多实例 + 负载均衡 + 分布式会话 |
| 密钥管理 | 环境变量 | 配置中心/密钥管理系统（Vault） |
