# SmartService-Agent 智能客服平台

[![CI](https://github.com/gee427/smartservice-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/gee427/smartservice-agent/actions/workflows/ci.yml)


基于 Java + Python + 本地 LLM（LM Studio）的渐进式 Agent 学习项目。

## 项目结构

```
SmartService-Agent/
├── python-prototype/          # 阶段一：Python 原型验证（Day 1-3）
│   ├── day1_single_agent.py   # 单 Agent + 工具调用
│   ├── day2_memory_agent.py   # 加入对话记忆
│   ├── day3_rag_agent.py      # RAG 知识库
│   ├── product_manual.txt     # 产品手册（自动生成）
│   └── requirements.txt       # Python 依赖
│
├── java-agent-core/           # 阶段二：Java 单 Agent（Day 4-8）
│   ├── src/main/java/com/smartservice/
│   │   ├── agent/             # Agent 核心
│   │   ├── rag/               # RAG 服务
│   │   ├── memory/            # 会话管理
│   │   └── tools/             # 工具定义
│   ├── src/main/resources/
│   │   └── application.yml    # 配置文件
│   └── pom.xml                # Maven 配置
│
├── java-agent-platform/       # 阶段三~四：生产级平台（Day 9-21）
│   ├── src/main/java/com/smartservice/
│   │   ├── api/               # REST API + 统一响应/异常
│   │   ├── auth/              # P2-1 认证（JWT + BCrypt）
│   │   ├── orchestrator/      # Agent 编排 + 7 个业务 Agent
│   │   ├── workflow/          # 状态机工作流
│   │   ├── metrics/           # P3-4 监控指标 + P3-3 健康组件
│   │   ├── audit/             # P4-4 审计日志
│   │   ├── ratelimit/         # P2-2 限流
│   │   ├── security/          # JWT 工具 + 拦截器
│   │   └── config/            # 配置类（CORS/OpenAPI/WebMvc）
│   ├── src/main/resources/
│   │   ├── application.yml      # P4-1 公共配置（默认 dev）
│   │   ├── application-dev.yml  # 开发环境（LM Studio + 本机 Redis）
│   │   ├── application-test.yml # 测试环境（surefire 自动激活）
│   │   ├── application-prod.yml # 生产环境（全环境变量注入）
│   │   ├── static/              # 前端：聊天端 + 管理后台（原生 HTML/JS）
│   │   └── logback-spring.xml   # P3-3 日志 + P4-4 审计日志轮转
│   ├── src/test/java/         # 单元测试（35 个）
│   ├── Dockerfile             # 容器化
│   └── pom.xml
│
├── config/
│   └── checkstyle.xml         # P4-3 代码质量门禁规则（mvn verify 强制）
│
├── docs/
│   ├── PRD.md                 # P4-5 产品需求文档
│   ├── ARCHITECTURE.md        # P4-5 架构设计文档（Mermaid 图）
│   └── OPERATIONS.md          # P4-8 操作手册（模块说明 + 启动指引）
│
├── scripts/
│   ├── health-check.sh        # P3-3 健康告警：actuator 三层探针，退出码 0/1/2/3
│   ├── smoke-test.sh          # P4-6 E2E 冒烟：注册→对话→SSE→管理→健康
│   └── load-test.sh           # P4-6 并发压测：P50/P95/成功率报告
│
├── monitoring/                # P3-4 监控对接
│   ├── prometheus.yml         # 抓取 /actuator/prometheus（15s，9090 端口）
│   ├── alert-rules.yml        # 告警规则：AgentDown/RedisDown/LlmDown/HighErrorRate
│   └── alertmanager.yml       # 企业微信 webhook 通知
│
├── .github/
│   ├── workflows/ci.yml       # GitHub Actions CI/CD（4 job 并行 + GHCR）
│   ├── dependabot.yml         # P4-2 依赖自动更新
│   ├── PULL_REQUEST_TEMPLATE.md  # P4-2 PR 模板
│   └── ISSUE_TEMPLATE/        # P4-2 Bug/Feature 模板
└── README.md
```

## 快速开始

### 1. 环境准备
- VS Code + 扩展（Python, Extension Pack for Java, Spring Boot Extension Pack）
- Python 3.10+
- JDK 17
- Maven 3.8+
- LM Studio（本地 LLM）

### 2. 启动 LM Studio
1. 打开 LM Studio
2. 下载 Qwen2.5-7B-Instruct-GGUF 模型
3. 启动 Local Server（默认 http://localhost:1234）

### 3. 运行 Python 原型
```bash
cd python-prototype
python -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate
pip install -r requirements.txt
python day1_single_agent.py
```

### 4. 运行 Java 项目
```bash
cd java-agent-core
mvn spring-boot:run
```

### 5. 访问入口（platform 8080，监控 9090）
- 聊天端：http://localhost:8080/
- 管理后台：http://localhost:8080/admin.html（注册/登录后使用）
- **Swagger API 文档（P3-5）**：http://localhost:8080/swagger-ui/index.html
- 健康探针（P3-3/P3-6）：http://localhost:9090/actuator/health（监控独立端口）
- Prometheus 指标（P3-4/P3-6）：http://localhost:9090/actuator/prometheus
- **E2E 冒烟（P4-6）**：`bash scripts/smoke-test.sh`
- **操作手册（P4-8）**：`docs/OPERATIONS.md`（模块详解 + 启动步骤 + 故障排查）

### 6. 多环境（P4-1）
```bash
# 开发（默认）：LM Studio + 本机 Redis
mvn spring-boot:run
# 生产：环境变量注入后
java -jar target/java-agent-platform-1.0.0.jar --spring.profiles.active=prod
# 测试：mvn verify 由 surefire 自动激活 test profile
```
## 技术栈

- **LLM**: LM Studio（本地 Qwen2.5/Llama）
- **Python**: LangChain, OpenAI SDK, Chroma
- **Java**: Spring Boot, Spring AI, Redis, springdoc-openapi（Swagger）
- **部署**: Docker, Docker Compose, GitHub Actions（CI/CD：测试 + 覆盖率 + GHCR 镜像）
- **运维**: Actuator + Micrometer/Prometheus（P3-4），logback 滚动日志（P3-3），健康告警脚本
