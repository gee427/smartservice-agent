# =====================================================================
# P3-1: SmartService Platform 容器化（多阶段构建）
# 构建阶段：maven 官方镜像 + JDK17 编译打包；运行阶段：JDK17 JRE 精简
#
# 构建（在 java-agent-platform/ 目录执行）：
#   docker build -t smartservice-platform:1.0.0 .
# 国内拉取基础镜像慢时，先配置 registry-mirrors（见 DEPLOYMENT.md）
# =====================================================================

# ---- 阶段 1：构建 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 先拷贝 pom.xml 单独拉依赖，利用 Docker 层缓存加速后续构建
COPY pom.xml .
RUN mvn dependency:go-offline -q || true

# 再拷贝源码并打包
COPY src ./src
RUN mvn package -DskipTests -q

# ---- 阶段 2：运行 ----
FROM eclipse-temurin:17-jre

# 时区（中国用户）
ENV TZ=Asia/Shanghai

# 商用安全实践：非 root 用户运行容器
RUN useradd --create-home --shell /usr/sbin/nologin appuser \
    && mkdir -p /app && chown -R appuser:appuser /app
USER appuser

WORKDIR /app
COPY --from=build /app/target/java-agent-platform-1.0.0.jar app.jar

EXPOSE 8080

# 健康检查（compose 与 K8s 就绪探针可复用）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -q --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# 配置均可通过环境变量覆盖（见 docker-compose.yml / DEPLOYMENT.md）
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
