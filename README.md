# SmartService-Agent 智能客服平台

[![CI](https://github.com/<your-github-username>/SmartService-Agent/actions/workflows/ci.yml/badge.svg)](https://github.com/<your-github-username>/SmartService-Agent/actions/workflows/ci.yml)

<!-- 徽章地址中的 <your-github-username> 替换为实际 GitHub 用户名后即可显示 CI 状态 -->

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
│   │   ├── api/               # REST API
│   │   ├── orchestrator/      # Agent 编排
│   │   ├── workflow/          # 状态机工作流
│   │   ├── metrics/           # 监控指标
│   │   └── config/            # 配置类
│   ├── src/main/resources/
│   │   ├── application.yml      # 配置文件
│   │   └── logback-spring.xml   # P3-3 日志：按日期+大小轮转，保留 7 天
│   ├── src/test/java/         # 单元测试
│   ├── Dockerfile             # 容器化
│   ├── docker-compose.yml     # 服务编排
│   └── pom.xml
│
├── scripts/
│   └── health-check.sh        # P3-3 健康告警：/actuator/health 三层探针，退出码 0/1/2/3
│
├── monitoring/                # P3-4 监控对接
│   ├── prometheus.yml         # 抓取 /actuator/prometheus（15s）
│   ├── alert-rules.yml        # 告警规则：AgentDown/RedisDown/LlmDown/HighErrorRate
│   └── alertmanager.yml       # 企业微信 webhook 通知
│
├── .github/workflows/
│   └── ci.yml                 # GitHub Actions CI/CD
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

### 5. 访问入口（platform 8080）
- 聊天端：http://localhost:8080/
- 管理后台：http://localhost:8080/admin.html（注册/登录后使用）
- **Swagger API 文档（P3-5）**：http://localhost:8080/swagger-ui/index.html
- 健康探针（P3-3）：http://localhost:8080/actuator/health
- Prometheus 指标（P3-4）：http://localhost:8080/actuator/prometheus

## 学习路线

| 阶段 | 天数 | 目标 | 产出 |
|------|------|------|------|
| 阶段一 | Day 1-3 | Python 建立 Agent 直觉 | 多功能 Agent |
| 阶段二 | Day 4-8 | Java 工程化重构 | 多用户客服服务 |
| 阶段三 | Day 9-14 | 多 Agent 协作 | 智能客服团队 |
| 阶段四 | Day 15-21 | 生产级部署 | 可部署的企业服务 |

## 技术栈

- **LLM**: LM Studio（本地 Qwen2.5/Llama）
- **Python**: LangChain, OpenAI SDK, Chroma
- **Java**: Spring Boot, Spring AI, Redis, springdoc-openapi（Swagger）
- **部署**: Docker, Docker Compose, GitHub Actions（CI/CD：测试 + 覆盖率 + GHCR 镜像）
- **运维**: Actuator + Micrometer/Prometheus（P3-4），logback 滚动日志（P3-3），健康告警脚本
