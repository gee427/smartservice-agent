#!/usr/bin/env bash
# =====================================================================
# SmartService-Agent 端到端冒烟测试（P4-6）
#
# 全链路验证：注册 → 普通对话(FAQ) → SSE 流式 → 管理后台 → 健康探针
# 任一环节失败即退出非 0（供 CI / 发布前自检使用）
#
# 用法:
#   ./smoke-test.sh                 # 默认 http://localhost:8080（actuator 自动探测 9090）
#   ./smoke-test.sh --url http://localhost:8080
#
# 前置条件: 平台已启动(8080) + Redis 在跑；LLM 可挂（FAQ 走关键词直查不依赖 LLM）
# =====================================================================
set -uo pipefail

BASE_URL="http://localhost:8080"
[[ "${1:-}" == "--url" && $# -ge 2 ]] && BASE_URL="$2"
MGMT_URL="${BASE_URL/8080/9090}"   # P3-6: actuator 独立端口

PASS=0; FAIL=0
ts() { date '+%H:%M:%S'; }
ok()  { PASS=$((PASS+1)); echo "[$(ts)] ✅ $1"; }
bad() { FAIL=$((FAIL+1)); echo "[$(ts)] ❌ $1"; }

# 找 python
PY=""
for c in python python3; do
  if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
done
[[ -z "$PY" ]] && { echo "[ERROR] 未找到 python/python3"; exit 3; }

JSON() { printf '%s' "$2" | "$PY" -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

echo "=== SmartService-Agent 冒烟测试 @ $(date '+%F %T') ==="
echo "业务端口: $BASE_URL  监控端口: $MGMT_URL"
echo ""

# ---------- 1. 注册（随机用户名避免重复） ----------
U="smoke_$(date +%s)"
R=$(curl -s -X POST "$BASE_URL/api/auth/register" -H "Content-Type: application/json" \
     -d "{\"username\":\"$U\",\"password\":\"pass1234\"}")
CODE=$(JSON "d['code']" "$R")
if [[ "$CODE" == "0" ]]; then
  TOKEN=$(JSON "d['data']['token']" "$R")
  ok "注册 $U 成功"
else
  bad "注册失败: $R"
  exit 1
fi

# ---------- 2. 普通对话（FAQ 关键词直查） ----------
R=$(curl -s -X POST "$BASE_URL/api/agent/chat" -H "Content-Type: application/json" \
     -H "X-Forwarded-For: 10.9.9.9" \
     -d '{"userId":"smoke","message":"退货政策是什么"}')
CODE=$(JSON "d['code']" "$R")
INTENT=$(JSON "d.get('data',{}).get('intent','?')" "$R" 2>/dev/null || echo "?")
if [[ "$CODE" == "0" ]]; then
  ok "普通对话成功 intent=$INTENT"
else
  bad "对话失败: $R"
fi

# ---------- 3. SSE 流式对话 ----------
R=$(curl -s -N -X POST "$BASE_URL/api/agent/chat/stream" -H "Content-Type: application/json" \
     -H "X-Forwarded-For: 10.9.9.9" \
     -d '{"userId":"smoke","message":"你好"}' --max-time 30)
if echo "$R" | grep -q "data:"; then
  if echo "$R" | grep -q '"done"'; then
    ok "SSE 流式响应完整（含 done 事件）"
  else
    ok "SSE 有 token 输出（无 done 事件，可能是纯文本流）"
  fi
else
  bad "SSE 无响应内容: ${R:0:200}"
fi

# ---------- 4. 管理后台（带 JWT） ----------
R=$(curl -s "$BASE_URL/api/admin/health" -H "Authorization: Bearer $TOKEN")
CODE=$(JSON "d['code']" "$R")
[[ "$CODE" == "0" ]] && ok "管理后台 health（JWT 鉴权通过）" || bad "管理后台失败: ${R:0:200}"

R=$(curl -s "$BASE_URL/api/admin/metrics" -H "Authorization: Bearer $TOKEN")
CODE=$(JSON "d['code']" "$R")
[[ "$CODE" == "0" ]] && ok "管理后台 metrics" || bad "metrics 失败: ${R:0:200}"

R=$(curl -s "$BASE_URL/api/admin/sessions?limit=5" -H "Authorization: Bearer $TOKEN")
CODE=$(JSON "d['code']" "$R")
[[ "$CODE" == "0" ]] && ok "管理后台 sessions" || bad "sessions 失败: ${R:0:200}"

# ---------- 5. 健康探针（actuator 9090） ----------
R=$(curl -s --max-time 10 "$MGMT_URL/actuator/health")
STATUS=$(JSON "d.get('status','?')" "$R")
if [[ "$STATUS" == "UP" || "$STATUS" == "DOWN" ]]; then
  ok "actuator/health 响应 status=$STATUS（LLM DOWN 属正常降级）"
else
  bad "actuator/health 异常: ${R:0:200}"
fi

# ---------- 6. Swagger / OpenAPI ----------
HTTP=$(curl -s -o /dev/null -w "%{http_code}" -L "$BASE_URL/swagger-ui/index.html" --max-time 10)
[[ "$HTTP" == "200" ]] && ok "Swagger UI HTTP 200" || bad "Swagger UI HTTP=$HTTP"

echo ""
echo "=== 结果: PASS=$PASS FAIL=$FAIL ==="
[[ "$FAIL" -eq 0 ]] || exit 1
echo "✅ 冒烟测试全部通过"
