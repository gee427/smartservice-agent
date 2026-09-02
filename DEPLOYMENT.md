# SmartService Agent 部署指南

> P3-1：容器化部署；P3-2：GitHub Actions CI/CD；P3-3：日志与告警；P3-4：Prometheus 监控对接；P4：多环境 / 质量门禁 / 审计 / 协作规范。本文档覆盖本地开发部署、Docker 生产部署、持续集成与监控告警。

## 1. 架构与端口

```
┌──────────────┐   HTTP/SSE   ┌─────────────────┐   HTTP    ┌────────────┐
│   浏览器       │ ──────────▶ │  platform (8080) │ ────────▶ │  Redis     │
│ 聊天端/管理后台 │             │  Spring Boot 3.2  │  Redis   │  (6379)    │
└──────────────┘             └────────┬──────────┘          └────────────┘
                                      │ OpenAI 兼容 API
                                      ▼
                            ┌──────────────────┐
                            │  LM Studio (1234) │  本地模型推理
                            └──────────────────┘
```

| 组件 | 端口 | 说明 |
|---|---|---|
| platform | 8080 | Spring Boot 应用（聊天 API / 管理后台 / 静态页面） |
| Redis | 6379 | 会话持久化、用户认证、限流计数 |
| LM Studio | 1234 | LLM 推理（qwen3vl-8b-uncensored 等），OpenAI 兼容接口 |

## 2. 前置依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| JDK | 17+ | 运行 Spring Boot |
| Maven | 3.9+ | 构建（或直接下载 jar） |
| Redis | 6+ | 会话/认证/限流存储 |
| LM Studio | 任意 | 本地 LLM 服务（需加载模型） |
| Docker Desktop | 最新 | 容器化部署（可选） |

## 3. 本地开发部署（最简）

```bash
# 1. 启动 Redis（Windows 示例）
E:\bc\redis\redis-server.exe

# 2. 启动 LM Studio 并加载模型（qwen3vl-8b-uncensored-hauhaucs-aggressive）

# 3. 构建并运行平台
cd java-agent-platform
mvn package -DskipTests
java -jar target/java-agent-platform-1.0.0.jar --server.port=8080
```

验证：浏览器打开 `http://localhost:8080/`（聊天端）、`http://localhost:8080/admin.html`（管理后台，需注册登录）。

## 4. Docker 部署（推荐）

> 环境要求：Docker Desktop（Windows/Mac）或 Linux Docker + docker compose v2。
> 若 Docker 尚未安装：https://www.docker.com/products/docker-desktop/

### 4.1 一键启动

```bash
# 项目根目录
docker compose up -d --build
```

- `redis`：redis:7-alpine，数据持久化到 named volume `redis-data`
- `platform`：多阶段构建（maven 构建 → JRE 运行），非 root 用户，含健康检查
- LLM 通过 `host.docker.internal:1234` 复用**宿主机**的 LM Studio（无需容器化模型）

### 4.2 常用命令

```bash
docker compose ps          # 查看状态（platform 应 healthy）
docker compose logs -f platform
docker compose down        # 停止（保留数据）
docker compose down -v     # 停止并清空 Redis 数据
```

### 4.3 验证清单

```bash
curl http://localhost:8080/api/agent/health       # {"code":0,...}
curl http://localhost:8080/api/admin/health       # 无 token → 40100（JWT 保护生效）
# 注册 → 拿 token → 带 token 访问管理后台 → 200
```

## 5. CI/CD（GitHub Actions）

> P3-2：工作流文件 `.github/workflows/ci.yml`。推送到 `main`/`develop` 或对 `main` 提 PR 时自动触发。

### 5.1 工作流结构

| Job | 触发 | 内容 |
|---|---|---|
| `test-java-core` | 所有 push/PR | JDK 17 + Maven 缓存，`mvn verify`（编译 + repackage 校验） |
| `test-java-platform` | 所有 push/PR | 启动 Redis 7 容器（healthcheck 就绪）+ `mvn verify` 跑 35 个测试 + JaCoCo 覆盖率报告（上传 artifact，保留 14 天） |
| `test-python-prototype` | 所有 push/PR | Python 3.10 `py_compile` 语法检查（不装重依赖，秒级完成） |
| `build-and-push-docker` | 仅 `main` push | Buildx 构建镜像 → 推送 GHCR（`ghcr.io/<owner>/smartservice-agent:latest` + `:<sha>`），层缓存 type=gha |

- 三个测试 Job **并行**执行，Docker Job 依赖两个 Java Job 全部通过。
- 同一分支连续推送自动取消旧工作流（`concurrency`），避免浪费配额。
- 对话用例均为确定性路径（空消息校验 / 限流计数 / 纯路由规则），SSE 用例在 LLM down 时输出占位文本但结构断言仍过，本地引擎挂了 CI 也能稳定通过。

### 5.2 本地模拟 CI（无需推送即可预检）

```bash
# Java 两个模块（platform 需先启动 Redis，见第 3 节）
cd java-agent-core && mvn -B -ntp verify
cd java-agent-platform && mvn -B -ntp verify

# Python 语法检查
cd python-prototype && python -m py_compile day1_single_agent.py day2_memory_agent.py day3_rag_agent.py
```

### 5.3 从 GHCR 拉取镜像部署

```bash
docker pull ghcr.io/<owner>/smartservice-agent:latest
docker run -d --name agent-platform -p 8080:8080 \
  -e SPRING_DATA_REDIS_HOST=<redis-host> \
  -e AGENT_LLM_BASEURL=http://<llm-host>:1234/v1 \
  -e JWT_SECRET=<32位以上随机串> \
  ghcr.io/<owner>/smartservice-agent:latest
```

> 首次推送后，镜像默认是**私有**的。需登录 GitHub → 仓库 → Packages → 该包 → Package settings → 把 visibility 改为 Public，才能被其他人/服务器匿名拉取。

## 6. 日志与告警（P3-3）

### 6.1 日志配置

`java-agent-platform/src/main/resources/logback-spring.xml`（Spring Boot 自动识别，无需额外依赖）：

| 项 | 策略 |
|---|---|
| 控制台 | 本地开发，时间/级别/线程/logger 彩色输出 |
| 文件 | `logs/agent-platform.log`（可用环境变量 `LOG_PATH` 覆盖），按**日期 + 大小**轮转（每天每文件 ≤10MB），保留 **7 天**，总量上限 1GB，历史文件自动 gzip |
| 级别 | root INFO（改 `LOG_LEVEL` 或 logback 内调整） |

生产建议：将 `LOG_PATH` 指向持久化磁盘，配合 filebeat/vector 采集到 ELK 或 Loki；`agent.*.log.gz` 归档策略按合规要求调整。

### 6.2 统一健康探针

`LlmHealthIndicator`（metrics 包）将 LLM 状态挂载到 Spring Boot 标准健康端点，与 Redis 组件并列：

```bash
curl http://localhost:8080/actuator/health
# → {"status":"DOWN","components":{ "redis":{"status":"UP",...}, "llm":{"status":"DOWN","details":{"model":"...","reason":"LM Studio unreachable or model unloaded"}}, ... }}
```

- 该端点**无需 JWT**，专供监控探针/K8s liveness/负载均衡探测使用。
- LLM 挂时整体 `status=DOWN`，HTTP 映射 **503**；引擎恢复后自动回 UP（`LlmClient.isAvailable()` 内置最小 chat 探测 + 30s 结果缓存，轮询不拖慢）。

### 6.3 告警脚本

`scripts/health-check.sh`（Git Bash / Linux 通用，零依赖：curl + python）：

```bash
./health-check.sh                          # 默认 http://localhost:8080
./health-check.sh --url http://host:8080   # 指定地址
```

退出码分级（供定时任务判断）：

| 退出码 | 含义 | 处置建议 |
|---|---|---|
| 0 | 全部 UP | 正常 |
| 1 | Redis / 平台 DOWN | 严重，立即处理 |
| 2 | 仅 LLM DOWN | 服务可降级运行（FAQ 直查），重载 LM Studio 模型 |
| 3 | 服务不可达 | 检查进程/网络 |

定时执行示例：

```bash
# Linux cron：每 5 分钟检查，异常时调用通知脚本
*/5 * * * * /opt/smartservice/scripts/health-check.sh || /opt/smartservice/scripts/notify.sh

# Windows：任务计划程序 → 新建任务 → 操作 = 程序 "C:\Program Files\Git\bin\bash.exe"
#   参数 = -lc "/e/SmartService-Agent/scripts/health-check.sh"
```

通知渠道示例（企业微信机器人，异常时 POST）：`curl -s -X POST 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx' -H 'Content-Type: application/json' -d '{"msgtype":"text","text":{"content":"SmartService 健康告警"}}'`

## 7. 监控与告警（P3-4）

`/actuator/prometheus` 已暴露全部指标（P1-2 引入 micrometer-registry-prometheus），P3-4 补充业务指标与 Prometheus/Alertmanager 对接配置。

### 7.1 指标清单

| 指标（Prometheus 命名） | 说明 |
|---|---|
| `agent_requests_total` / `agent_requests_by_intent_total{intent}` | 请求总数 / 意图分布 |
| `agent_requests_errors_total` / `agent_requests_errors_by_intent_total` | **失败请求**（路由/流式异常路径计数） |
| `agent_requests_stream_total` / `agent_streams_by_intent_total` | SSE 流式请求数 / 意图分布 |
| `agent_stream_chars_total` | SSE 累计输出字符数 |
| `agent_tokens_total` | Token 消耗估计 |
| `agent_response_time_*` | 响应时间直方图（ms） |
| `agent_sessions_active` | 活跃会话数 |
| `agent_rate_limited_total` / `agent_rate_limited_by_resource_total{resource}` | **限流触发**（chat/login） |
| `agent_redis_up` / `agent_llm_up` | **服务健康 0/1**（管理后台健康检查每次评估后刷新，供告警规则使用） |

### 7.2 配置文件（monitoring/ 目录）

| 文件 | 说明 |
|---|---|
| `prometheus.yml` | 15s 抓取 `localhost:8080/actuator/prometheus`，加载规则，对接 Alertmanager（9093） |
| `alert-rules.yml` | 4 条规则：AgentDown（抓取失败 1m，critical）/ RedisDown（2m，critical）/ LlmDown（5m，warning，服务可降级）/ HighErrorRate（5m 错误率 >20%，warning） |
| `alertmanager.yml` | 路由聚合 + 企业微信机器人 webhook 接收器（key 替换后生效） |

### 7.3 本地运行（Windows 解压版，免 Docker）

```bash
# 1. 下载 prometheus + alertmanager windows-amd64 压缩包解压
# 2. 启动（项目根目录执行，配置内相对路径基于当前目录）
prometheus.exe --config.file=monitoring/prometheus.yml
alertmanager.exe --config.file=monitoring/alertmanager.yml
# 3. 验证
#    Prometheus UI    http://localhost:9090  → Status/Targets 应显示 UP
#    告警页           http://localhost:9090/alerts
#    手动查询         agent_requests_total 或 agent_llm_up
```

### 7.4 告警触发链路

```
管理后台 5s 轮询 /api/admin/health ──▶ 刷新 agent_redis_up / agent_llm_up gauge
        Prometheus 15s 抓取 /actuator/prometheus
        ├─▶ up == 0            → AgentDown
        ├─▶ agent_redis_up==0  → RedisDown
        ├─▶ agent_llm_up==0    → LlmDown（warning）
        └─▶ 错误率 > 20%        → HighErrorRate
                     ▼
        Alertmanager → 企业微信机器人 webhook（可换邮件/Slack/自建）
```

生产建议：metrics 端点用独立管理端口 + basic_auth 暴露，勿与业务接口混用；K8s 环境直接用 Prometheus Operator / ServiceMonitor；`agent_redis_up`/`agent_llm_up` 依赖健康检查被访问，生产由探针（第 6.3 节 health-check.sh 带 token 版）定时触发。

## 8. 国内镜像加速（可选，构建慢时）

Docker 拉取基础镜像慢/失败时，配置镜像加速器。Docker Desktop：
Settings → Docker Engine，编辑 `daemon.json`：

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://dockerproxy.com",
    "https://mirror.ccs.tencentyun.com"
  ]
}
```

保存并 Restart。之后 `docker compose up -d --build` 即可。

## 9. 生产配置注意事项

| 项 | 本地默认 | 生产建议 |
|---|---|---|
| JWT 密钥 | 代码内置 demo 值 | 环境变量 `JWT_SECRET` 注入（≥32 字符随机串） |
| Redis 地址 | localhost | compose 内服务名 / 云 Redis 连接串 |
| LLM 地址 | localhost:1234 | 独立推理服务 / 云 API，密钥注入 |
| 限流阈值 | chat 10/min, login 5/min | 按业务压测调整 |
| 可观测性 | Actuator 本地 | Prometheus 抓取 `/actuator/prometheus` |

## 10. 多环境配置与质量门禁（P4）

### 10.1 多环境（P4-1）
配置拆分：`application.yml`（公共）+ `application-dev.yml` / `application-test.yml` / `application-prod.yml`。

| 环境 | 激活方式 | 要点 |
|---|---|---|
| dev | 默认（`spring.profiles.default: dev`） | LM Studio + 本机 Redis + 演示 JWT |
| test | `mvn verify` 自动（surefire 注入） | 集成测试用真实 Redis |
| prod | `--spring.profiles.active=prod` | **全部环境变量注入**：REDIS_HOST / JWT_SECRET / LLM_BASE_URL 等，JWT_SECRET 缺失直接启动失败 |

### 10.2 代码质量门禁（P4-3）
- `config/checkstyle.xml`：Tab/行尾空白/行长 160/无用 import/大括号/空白规范（空块例外已声明）。
- 接入 `maven-checkstyle-plugin`（verify 阶段，违规即 BUILD FAILURE），**core 与 platform 均生效**。
- 本地验证：`mvn verify` 或 `mvn checkstyle:check`（违规明细在 `target/checkstyle-result.xml`）。

### 10.3 审计日志（P4-4）
- 敏感操作（注册/登录成功·失败/删除会话）记录到 `logs/audit.log`（独立 appender，保留 90 天）。
- 格式：`action=... user=... ip=... result=ok|fail detail=...`，支持 grep 检索与合规追溯。
- 扩展：在业务代码注入 `AuditLogger` 后调用 `auditLogger.success/failure(...)` 即可。

### 10.4 协作规范（P4-2）
- `.editorconfig`：统一缩进/换行/编码（配合 `.gitattributes` LF）。
- Dependabot：Maven 与 GitHub Actions 每周一自动检查更新（`.github/dependabot.yml`）。
- PR/Issue 模板：`.github/PULL_REQUEST_TEMPLATE.md`、`.github/ISSUE_TEMPLATE/`。

## 11. 常见问题

- **管理后台显示 llm: DOWN**：LM Studio 引擎未加载模型（`/models` 有响应但 `/chat/completions` 挂起）。在 LM Studio 中重新加载模型。
- **容器内连不上 LM Studio**：确认宿主机 LM Studio 在运行；Linux 下 `extra_hosts: host.docker.internal:host-gateway` 已配置。
- **构建慢**：先做镜像加速（第 5 节）；Dockerfile 已做依赖层缓存，改动源码后仅重编最后一步。
