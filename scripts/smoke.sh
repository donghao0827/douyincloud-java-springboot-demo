#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/target/douyincloud-demo-0.0.1-SNAPSHOT.jar"
PORT="${SMOKE_PORT:-18000}"
BASE="http://127.0.0.1:$PORT"
DATA="${SMOKE_DATA:-/tmp/kapu-java-smoke}"
JAVA="${JAVA:-java}"

if [ ! -f "$JAR" ]; then
  echo "缺少 $JAR，先执行 mvn -s settings.xml -DskipTests package" >&2
  exit 1
fi

rm -rf "$DATA"
PORT="$PORT" AI_PAUSED=true DATA_DIR="$DATA" "$JAVA" -jar "$JAR" > /tmp/kapu-java-smoke.log 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT

for i in $(seq 1 30); do
  if curl -sf "$BASE/api/health" >/dev/null 2>&1; then break; fi
  sleep 0.5
done

curl -sf "$BASE/api/health" >/dev/null
curl -sf -X POST "$BASE/api/control" -H 'content-type: application/json' -d '{"action":"play"}' >/dev/null
sleep 1.2
STATE=$(curl -sf "$BASE/api/state")
echo "$STATE" | grep -q '"task":"game"' || { echo "游戏没有上屏: $STATE" >&2; exit 1; }

curl -sf -X POST "$BASE/live_data_callback" -H 'content-type: application/json' -H 'x-msg-type: live_comment' \
  -d '[{"msg_id":"smoke-1","data":{"content":"老板来了快收手","userInfo":{"userName":"小橘"}}}]' >/dev/null
sleep 0.8
STATE=$(curl -sf "$BASE/api/state")
echo "$STATE" | grep -q '判官降级\|小橘' || { echo "评论没有进状态机" >&2; exit 1; }

USAGE=$(curl -sf "$BASE/api/usage")
echo "$USAGE" | grep -q '"calls":0' || { echo "暂停模式下不应有模型调用: $USAGE" >&2; exit 1; }
echo "smoke ok"
