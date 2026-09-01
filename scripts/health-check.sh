#!/usr/bin/env bash
# =====================================================================
# SmartService-Agent 健康检查 / 告警脚本（P3-3）
#
# 探测 /actuator/health（无需认证），一次调用获取三层状态：
#   components.redis / components.llm / 整体 status
# 注意：P3-6 起监控端点独立端口 9090（management.server.port），默认探测 9090
#
# 用法:
#   ./health-check.sh                       # 默认 http://localhost:9090
#   ./health-check.sh --url http://host:9090
#
# 退出码（供 cron / 任务计划程序 / CI 判断）:
#   0 = 全部 UP
#   1 = 平台进程或 Redis DOWN（严重）
#   2 = 仅 LLM DOWN（服务可降级运行，警告）
#   3 = 服务不可达 / 响应异常
#
# 定时执行示例:
#   Linux  cron  : */5 * * * * /opt/smartservice/scripts/health-check.sh || /opt/alert.sh
#   Windows 计划任务: 程序 Git Bash，参数 -lc "/e/SmartService-Agent/scripts/health-check.sh"
# =====================================================================
set -euo pipefail

BASE_URL="http://localhost:9090"
[[ "${1:-}" == "--url" && $# -ge 2 ]] && BASE_URL="$2"

HEALTH_URL="$BASE_URL/actuator/health"
TIMEOUT=10

# 找一个可用的 python（Git Bash 的 python / Linux 的 python3）
PY=""
for c in python python3; do
  if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
done
[[ -z "$PY" ]] && { echo "[ERROR] 未找到 python/python3，无法解析 JSON"; exit 3; }

resp="$(curl -sS --max-time "$TIMEOUT" "$HEALTH_URL" 2>&1)" || {
  echo "[CRITICAL] $HEALTH_URL 不可达: $resp"
  exit 3
}

# 解析 JSON（单引号包裹避免转义；字段不存在时输出 UNKNOWN）
read -r overall redis llm <<<"$(printf '%s' "$resp" | "$PY" -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print("UNKNOWN UNKNOWN UNKNOWN")
    sys.exit(0)
comp = d.get("components", {})
def s(name):
    return comp.get(name, {}).get("status", "UNKNOWN") if isinstance(comp.get(name), dict) else "UNKNOWN"
print(d.get("status", "UNKNOWN"), s("redis"), s("llm"))
')"

ts="$(date '+%Y-%m-%d %H:%M:%S')"

if [[ "$overall" == "UP" && "$redis" == "UP" && "$llm" == "UP" ]]; then
  echo "[$ts] OK platform=UP redis=UP llm=UP"
  exit 0
fi

# 汇总告警内容
alert="[$ts] ALERT $HEALTH_URL"
[[ "$overall" != "UP" ]] && alert="$alert overall=$overall"
[[ "$redis" != "UP" ]]    && alert="$alert redis=$redis"
[[ "$llm" != "UP" ]]      && alert="$alert llm=$llm"
alert="$alert :: $resp"

# 退出码分级（以组件为准，overall 会被最差组件拖累，不能直接用）:
#   redis/平台 挂 = 1（严重），仅 llm 挂 = 2（警告）
if [[ "$redis" != "UP" ]]; then
  echo "$alert"
  exit 1
fi
echo "$alert"
exit 2
