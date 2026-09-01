# SmartService Agent 部署指南

> P3-1：容器化部署；P3-2：GitHub Actions CI/CD。本文档覆盖本地开发部署、Docker 生产部署与持续集成流水线。

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
- 集成测试不依赖 LM Studio（走 FAQ 关键词直查路径），本地引擎挂了也能在 CI 稳定通过。

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

## 6. 国内镜像加速（可选，构建慢时）

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

## 7. 生产配置注意事项

| 项 | 本地默认 | 生产建议 |
|---|---|---|
| JWT 密钥 | 代码内置 demo 值 | 环境变量 `JWT_SECRET` 注入（≥32 字符随机串） |
| Redis 地址 | localhost | compose 内服务名 / 云 Redis 连接串 |
| LLM 地址 | localhost:1234 | 独立推理服务 / 云 API，密钥注入 |
| 限流阈值 | chat 10/min, login 5/min | 按业务压测调整 |
| 可观测性 | Actuator 本地 | Prometheus 抓取 `/actuator/prometheus` |

## 8. 常见问题

- **管理后台显示 llm: DOWN**：LM Studio 引擎未加载模型（`/models` 有响应但 `/chat/completions` 挂起）。在 LM Studio 中重新加载模型。
- **容器内连不上 LM Studio**：确认宿主机 LM Studio 在运行；Linux 下 `extra_hosts: host.docker.internal:host-gateway` 已配置。
- **构建慢**：先做镜像加速（第 5 节）；Dockerfile 已做依赖层缓存，改动源码后仅重编最后一步。
