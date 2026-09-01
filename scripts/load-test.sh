#!/usr/bin/env bash
# =====================================================================
# SmartService-Agent 并发压测脚本（P4-6）
#
# 对 /api/agent/chat 发起并发请求，统计成功率与延迟分位数。
# 用法:
#   ./load-test.sh                    # 默认: 并发 10, 总请求 100
#   ./load-test.sh -c 20 -n 200       # 并发 20, 200 请求
#   ./load-test.sh -u http://localhost:8080
#
# 注意: 平台限流 10/min/IP —— 压测时若全部走同一 IP 会触发 429，
#       脚本对每个请求轮换 X-Forwarded-For 模拟不同用户。
# =====================================================================
set -uo pipefail

BASE_URL="http://localhost:8080"
CONCURRENCY=10
TOTAL=100

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c) CONCURRENCY="$2"; shift 2 ;;
    -n) TOTAL="$2"; shift 2 ;;
    -u) BASE_URL="$2"; shift 2 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

PY=""
for c in python python3; do
  if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
done
[[ -z "$PY" ]] && { echo "[ERROR] 未找到 python/python3"; exit 3; }

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
CHAT="$BASE_URL/api/agent/chat"

echo "=== 压测开始 @ $(date '+%F %T') ==="
echo "目标: $CHAT  并发: $CONCURRENCY  总请求: $TOTAL"
echo ""

run_one() {
  local i="$1" ip="10.$((RANDOM % 255)).$((RANDOM % 255)).$((RANDOM % 255))"
  local body='{"userId":"load_user","message":"退货政策是什么"}'
  # 输出: 起始时间戳;HTTP码;耗时ms（用 curl %{time_total} 秒，乘 1000）
  curl -s -o /dev/null -w "%{http_code};%{time_total}" \
    -X POST "$CHAT" -H "Content-Type: application/json" \
    -H "X-Forwarded-For: $ip" -d "$body" --max-time 15
}

# 用 xargs 并发执行，输出每行: 请求序;HTTP码;耗时秒
seq 1 "$TOTAL" | xargs -P "$CONCURRENCY" -I{} bash -c '
  ip="10.$((RANDOM % 255)).$((RANDOM % 255)).$((RANDOM % 255))"
  curl -s -o /dev/null -w "%{http_code};%{time_total}\n" \
    -X POST "'"$CHAT"'" -H "Content-Type: application/json" \
    -H "X-Forwarded-For: $ip" -d "{\"userId\":\"load_user\",\"message\":\"退货政策是什么\"}" \
    --max-time 15' > "$WORKDIR/results.txt"

# ---------- 统计 ----------
# 注意：Git Bash 的 /tmp 是 MSYS 虚拟路径，Windows 原生 python 无法直接访问，
#       需用 cygpath 转换为 Windows 路径（health-check/smoke 脚本的 python 只读 stdin，故无此问题）
RESULTS_WIN="$(cygpath -w "$WORKDIR/results.txt" 2>/dev/null || echo "$WORKDIR/results.txt")"
"$PY" - "$RESULTS_WIN" "$TOTAL" <<'PYEOF'
import sys

lines = [l.strip() for l in open(sys.argv[1]) if l.strip()]
total = int(sys.argv[2])
lat = []
codes = {}
for l in lines:
    parts = l.split(';')
    if len(parts) < 2:
        continue
    code, t = parts[0], float(parts[1])
    codes[code] = codes.get(code, 0) + 1
    lat.append(t * 1000)

lat.sort()
n = len(lat)
def pct(p):
    if not lat:
        return 0.0
    idx = min(int(p / 100 * n), n - 1)
    return lat[idx]

success = codes.get('200', 0)
rate = success / total * 100 if total else 0
print(f"\n{'指标':<12}{'值':>12}")
print('-' * 28)
print(f"{'总请求':<12}{total:>12}")
print(f"{'成功(200)':<12}{success:>12} ({rate:.1f}%)")
print(f"{'其他响应':<12}{total - success:>12} (分布: {dict(sorted(codes.items()))})")
print(f"{'P50 延迟':<12}{pct(50):>10.1f} ms")
print(f"{'P90 延迟':<12}{pct(90):>10.1f} ms")
print(f"{'P95 延迟':<12}{pct(95):>10.1f} ms")
print(f"{'P99 延迟':<12}{pct(99):>10.1f} ms")
print(f"{'最大延迟':<12}{lat[-1] if lat else 0:>10.1f} ms")
print(f"{'平均延迟':<12}{(sum(lat)/n if lat else 0):>10.1f} ms")
print()
if rate < 95:
    print(f"⚠️ 成功率 {rate:.1f}% < 95%，存在异常（检查限流/LLM 超时/代码错误）")
    sys.exit(1)
print("✅ 压测通过（成功率 ≥ 95%）")
PYEOF
