#!/usr/bin/env bash
# 테스트 한 번 = 결과 폴더 하나. k6를 돌리고 그 구간의 숫자·그래프 이미지·느린 쿼리·버전·데이터 양을 다 모아요.
#   perf/run.sh [--data prod|seed] [--warmup 초] [--repeat N] <smoke|load|load200|soak|stress|breakpoint|spike> ["고친 것 메모"]
# --warmup: 본 측정 전 예열(기본 90초, VU 50명, 결과·Prometheus에서 빼요. 0이면 안 해요)
# --repeat: 같은 테스트를 N번(기본 1) 재서 run1/ run2/ …와 그 중앙값(맨 위 폴더)을 남겨요. compare.sh·결과 줄은 중앙값을 써요
# 대상은 BASE_URL로 정해요: 운영 EC2 = prod, DEV_EC2_HOST = dev, localhost = local(로컬 api 지표도 모아요, CloudWatch만 빠져요)
# --data seed = 더미(perf/seed) 기준: k6/data/seed.json + 더미 회원 토큰(k6/make-tokens.sh), 더미 기준 프로필(load200은 더미만)
# 결과: perf/results/<yyyyMMdd-HHmm>_<test>_<api 버전>/ (운영 대상·운영 데이터), 그 밖에는 <test>_<대상>-<데이터>_<api 버전>
source "$(dirname "$0")/lib/common.sh"

DATA=prod
WARMUP=90
REPEAT=1
while [ $# -gt 0 ]; do
  case "$1" in
    --data) DATA="${2:-}"; shift 2 ;;
    --warmup) WARMUP="${2:-}"; shift 2 ;;
    --repeat) REPEAT="${2:-}"; shift 2 ;;
    *) break ;;
  esac
done
usage() { sed -n '2,8p' "$0"; exit 1; }
case "$DATA" in prod|seed) ;; *) usage ;; esac
[[ "$WARMUP" =~ ^[0-9]+$ ]] || usage
[[ "$REPEAT" =~ ^[1-9][0-9]*$ ]] || usage

TEST="${1:-}"
NOTE="${2:-}"
if [ "$DATA" = prod ]; then
  case "$TEST" in
    smoke) LOAD="2명" ;;
    load) LOAD="100명" ;;
    soak) LOAD="50명" ;;
    stress) LOAD="50→1,000명" ;;
    breakpoint) LOAD="초당 20건~" ;;
    spike) LOAD="20→500→20명" ;;
    *) usage ;;
  esac
else
  case "$TEST" in
    smoke) LOAD="2명" ;;
    load) LOAD="100명" ;;
    load200) LOAD="200명" ;;
    soak) LOAD="100명 1시간" ;;
    stress) LOAD="100→1,000명" ;;
    breakpoint) LOAD="초당 20건~" ;;
    spike) LOAD="20→600→20명" ;;
    *) usage ;;
  esac
fi

TARGET="$(perf_target)" || exit 1
use_target "$TARGET"
[ "$TARGET" != prod ] || [ "$DATA" = prod ] || { echo "운영에는 더미가 없어요. --data seed는 dev·local에서만 써요" >&2; exit 1; }

DATA_FILE=data.json
TOKENS_FILE=""
TOKENS=0
if [ "$DATA" = seed ]; then
  DATA_FILE=seed.json
  [ -f "$PERF_DIR/k6/data/seed.json" ] || { echo "k6/data/seed.json이 없어요: perf/seed/k6-data.sh $TARGET" >&2; exit 1; }
  TOKENS_FILE="$PERF_DIR/k6/data/tokens.json"
fi

# ── 시작 전 점검: 측정이 중간에 끊길 일을 미리 막아요. 하나라도 걸리면 시작하지 않고 이유를 말해요
FAILED=0
fail() { echo "  ✗ $*" >&2; FAILED=1; }
TEST_SECONDS="$(k6 inspect -e TEST="$TEST" -e DATA="$DATA" -e DATA_FILE="$DATA_FILE" "$PERF_DIR/k6/main.js" 2> /dev/null \
  | python3 "$PERF_DIR/lib/stagewatch.py" seconds "$TEST")" || TEST_SECONDS=0
TOTAL=$((WARMUP + REPEAT * (TEST_SECONDS + 90)))
echo "== 예상 소요: 약 $(((TOTAL + 59) / 60))분 (예열 ${WARMUP}초 + 측정 $(((TEST_SECONDS + 59) / 60))분 × ${REPEAT}회 + 회마다 결과 모으기 약 1분 반)"
NEED_UNTIL=$(($(date +%s) + TOTAL + 600)) # 끝날 시각 + 10분 여유
if [ "$DATA" = seed ]; then
  if [ ! -f "$TOKENS_FILE" ] || [ "$(jq -r .target "$TOKENS_FILE")" != "$TARGET" ] || [ "$(jq .expiresAt "$TOKENS_FILE")" -lt "$NEED_UNTIL" ]; then
    if [ "$TARGET" = local ]; then
      echo "  · 더미 회원 토큰이 측정이 끝날 때까지 못 가서 다시 만들어요"
      "$PERF_DIR/k6/make-tokens.sh" local 1000 $(((TOTAL + 600) / 3600 + 2)) > /dev/null || fail "토큰을 만들지 못했어요"
    else
      fail "$TARGET용 더미 회원 토큰이 없거나 측정이 끝나기 전에 지나요: perf/k6/make-tokens.sh $TARGET 1000 $(((TOTAL + 600) / 3600 + 2))"
    fi
  fi
  [ ! -f "$TOKENS_FILE" ] || TOKENS="$(jq '.tokens | length' "$TOKENS_FILE")"
fi
[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$BASE_URL/actuator/health")" = 200 ] \
  || fail "대상 api 헬스가 200이 아니에요: $BASE_URL/actuator/health"
if pgrep -f "k6 run" > /dev/null; then
  fail "다른 k6가 돌고 있어요(같은 DB에 다른 부하): $(pgrep -f 'k6 run' | tr '\n' ' ')"
fi
if [ "$TARGET" = local ]; then
  # 같은 DB(api_fe)에 붙은 다른 api: BASE_URL 말고 다른 포트에서 공고 API가 대답하면 다른 api예요(알림 8081 등은 대답하지 않아요)
  base_port="${BASE_URL##*:}"
  base_port="${base_port%%/*}"
  for port in $(lsof -nP -iTCP -sTCP:LISTEN 2> /dev/null | awk '$1 == "java" {sub(/.*:/, "", $9); print $9}' | sort -u); do
    [ "$port" != "$base_port" ] || continue
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$port/api/v1/regions")" != 200 ] \
      || fail "다른 api가 $port 포트에 떠 있어요(같은 DB에 부하가 겹쳐요). 끄고 다시 해요"
  done
fi

echo "== 준비 확인 (대상 $TARGET, 데이터 $DATA)"
PROM_OK=1
if [ "$TARGET" = local ]; then
  # 로컬 api(노트북 8080)도 Prometheus가 긁어요(prometheus.yml api-local, target="local"). CloudWatch만 빠져요
  API_TAG=local
  NOTIFICATION_TAG=local
  if ! curl -sf "$PROM/-/ready" > /dev/null; then
    fail "Prometheus가 없어요: docker compose -f perf/docker-compose.yml up -d"
  else
    # 로컬 api 대상(target="local")만 봐요. 서버 터널(:18080)은 로컬에서 늘 down이라 보지 않아요. api가 막 뜨는 중이면 1분까지 기다려요
    for _ in $(seq 1 12); do prom_api_up && break; sleep 5; done
    prom_api_up || fail "Prometheus가 로컬 api($(prom_target))를 up으로 긁지 못해요(perf/prometheus/prometheus.yml api-local)"
  fi
  curl -sf "$GRAFANA/api/health" > /dev/null || warn "Grafana가 없어요. 그래프 이미지는 빠져요"
else
  curl -sf "$PROM/-/ready" > /dev/null || { echo "Prometheus가 없어요: docker compose -f perf/docker-compose.yml up -d" >&2; exit 1; }
  curl -sf "$GRAFANA/api/health" > /dev/null || { echo "Grafana가 없어요: docker compose -f perf/docker-compose.yml up -d" >&2; exit 1; }
  curl -sf -o /dev/null http://127.0.0.1:18080/actuator/prometheus || { echo "서버 지표가 안 보여요: 다른 터미널에서 perf/tunnel.sh $TARGET" >&2; exit 1; }
  curl -sf -o /dev/null http://127.0.0.1:19100/metrics || warn "node-exporter가 안 보여요(perf/server.sh exporters-up). 서버 CPU 그래프가 빠져요"

  VERSIONS="$(remote_versions)" || { echo "서버($EC2_HOST)에 SSH로 붙지 못했어요 (perf/.env의 SSH_KEY)" >&2; exit 1; }
  API_TAG="$(sed -n 's/^API_TAG=//p' <<< "$VERSIONS")"
  NOTIFICATION_TAG="$(sed -n 's/^NOTIFICATION_TAG=//p' <<< "$VERSIONS")"
fi
[ "$FAILED" = 0 ] || { echo "== 위 이유로 시작하지 않아요" >&2; exit 1; }

# 운영 대상·운영 데이터는 예전 이름 그대로(이미 잰 결과와 나란히 놓이게)
if [ "$TARGET" = prod ] && [ "$DATA" = prod ]; then
  NAME="$(date +%Y%m%d-%H%M)_${TEST}_${API_TAG:-unknown}"
else
  NAME="$(date +%Y%m%d-%H%M)_${TEST}_${TARGET}-${DATA}_${API_TAG:-unknown}"
fi
OUT="$PERF_DIR/results/$NAME"
mkdir -p "$OUT"
echo "== $NAME"

MEMBER=false
if [ "$DATA" = seed ]; then
  MEMBER=true
  unset JWT_SECRET TEST_USER_ID
else
  [ -n "${JWT_SECRET:-}" ] && [ -n "${TEST_USER_ID:-}" ] && MEMBER=true
fi
GIT_SHA="$(git -C "$PERF_DIR" rev-parse --short HEAD)"
git -C "$PERF_DIR" diff --quiet -- . || GIT_SHA="$GIT_SHA(+perf 수정)"

# 사람이 Ctrl+C를 누르면 k6도 같이 받아 정상 종료하고, 이 회를 「사람이 멈춤」으로 남긴 뒤 다음 회로 넘어가지 않아요
HUMAN_STOP=0
trap 'HUMAN_STOP=1' INT

k6_env() { # k6를 이 테스트의 데이터·대상으로
  TEST="$1" DATA="$DATA" DATA_FILE="$DATA_FILE" TOKENS_FILE="$TOKENS_FILE" BASE_URL="$BASE_URL" "${@:2}"
}

# 예열: JVM·DB 캐시를 데워요. 결과 폴더·Prometheus에는 남기지 않아요(로그만)
if [ "$WARMUP" -gt 0 ]; then
  echo "== 예열 ${WARMUP}초 (결과에서 빼요)"
  set +e
  k6_env warmup env WARMUP_SECONDS="$WARMUP" OUT_DIR="$OUT" k6 run -q "$PERF_DIR/k6/main.js" > "$OUT/warmup.log" 2>&1
  set -e
  rm -f "$OUT/summary.json"
  [ "$HUMAN_STOP" = 0 ] || { echo "== 예열 중에 사람이 멈춰서 측정하지 않아요" >&2; exit 130; }
fi

# 본 측정 한 번 = 폴더 하나 (k6·느린 쿼리·데이터 양·meta·서버 지표)
measure() {
  local OUT="$1" ID="$2"
  mkdir -p "$OUT"
  local PG
  if db_sql < "$PERF_DIR/lib/pg_stats.sql" > "$OUT/pg_before.csv" 2> "$OUT/pg.err"; then PG=1; else
    PG=0; warn "pg_stat_statements를 못 읽었어요(README 「사용자가 할 일」). 느린 쿼리는 빠져요: $(head -c 200 "$OUT/pg.err")"
  fi
  db_sql < "$PERF_DIR/lib/table_counts.sql" > "$OUT/table_counts.csv" 2>> "$OUT/pg.err" || warn "데이터 양을 못 읽었어요"

  # 단계가 있는 테스트: 단계 계획(plan.json)을 프로필에서 뽑고, 도는 동안 stagewatch가 단계별로 감시해요
  local STAGED=0
  case "$TEST" in stress | breakpoint | spike) STAGED=1 ;; esac
  if [ "$STAGED" = 1 ]; then
    k6 inspect -e TEST="$TEST" -e DATA="$DATA" -e DATA_FILE="$DATA_FILE" "$PERF_DIR/k6/main.js" 2> /dev/null \
      | python3 "$PERF_DIR/lib/stagewatch.py" plan "$TEST" > "$OUT/plan.json"
  fi
  START=$(date +%s)
  set +e
  # k6 응답 시간은 native histogram으로 보내요: 단계·30초 구간 p95·p99를 Prometheus에서 정확히 계산해요
  k6_env "$TEST" env OUT_DIR="$OUT" \
    K6_PROMETHEUS_RW_SERVER_URL="$PROM/api/v1/write" \
    K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=true \
    K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT="$OUT/k6-report.html" K6_WEB_DASHBOARD_OPEN=false \
    k6 run -o experimental-prometheus-rw --tag testid="$ID" "$PERF_DIR/k6/main.js" > >(tee "$OUT/k6.log") 2>&1 &
  local K6_PID=$!
  # 측정을 끊지 않고 기록만 해요(30초 창 p95·에러율, CPU). 끊는 건 안전 장치 두 가지뿐: 5xx·연결 실패 60초 넘게 50% 초과, api 헬스 60초 넘게 실패
  python3 "$PERF_DIR/lib/stagewatch.py" watch "$OUT" "$ID" "$K6_PID" "$BASE_URL" &
  local WATCH_PID=$!
  # Ctrl+C(trap)가 오면 wait가 먼저 돌아와요. k6가 정상 종료할 때까지 다시 기다려요
  while :; do
    wait "$K6_PID"
    K6_EXIT=$?
    kill -0 "$K6_PID" 2> /dev/null || break
  done
  wait "$WATCH_PID" 2> /dev/null
  set -e
  END=$(date +%s)

  # 이 회의 상태: 정상 / 안전 장치로 끊김(사유) / 사람이 멈춤 / k6 오류
  local SAFETY RUN_STATUS
  SAFETY="$(jq -r '.safety_stop.reason // empty' "$OUT/timeline.json" 2> /dev/null)"
  if [ -n "$SAFETY" ]; then
    RUN_STATUS="안전 장치로 끊김: $SAFETY"
  elif [ "$HUMAN_STOP" = 1 ]; then
    RUN_STATUS="사람이 멈춤(Ctrl+C)"
  elif [ "$K6_EXIT" = 0 ] || [ "$K6_EXIT" = 99 ]; then
    RUN_STATUS="정상" # 99는 k6 기준(기록만)을 넘었다는 뜻이고 끝까지 돌았어요
  else
    RUN_STATUS="k6 오류(종료 코드 $K6_EXIT)"
  fi

  if [ "$PG" = 1 ]; then
    db_sql < "$PERF_DIR/lib/pg_stats.sql" > "$OUT/pg_after.csv" 2>> "$OUT/pg.err" \
      && python3 "$PERF_DIR/lib/tools.py" pgdiff "$OUT/pg_before.csv" "$OUT/pg_after.csv" "$OUT/slow_queries.csv" \
      && rm "$OUT/pg_before.csv" "$OUT/pg_after.csv"
  fi
  [ -s "$OUT/pg.err" ] || rm -f "$OUT/pg.err"

  cat > "$OUT/meta.json" <<JSON
{
  "test": "$TEST",
  "load": "$LOAD",
  "note": $(jq -Rn --arg v "$NOTE" '$v'),
  "started_at": "$(TZ=Asia/Seoul date -r "$START" +%Y-%m-%dT%H:%M:%S+09:00)",
  "ended_at": "$(TZ=Asia/Seoul date -r "$END" +%Y-%m-%dT%H:%M:%S+09:00)",
  "start_epoch": $START,
  "end_epoch": $END,
  "api_tag": "$API_TAG",
  "notification_tag": "$NOTIFICATION_TAG",
  "base_url": "$BASE_URL",
  "target": "$TARGET",
  "data": "$DATA",
  "data_file": "k6/data/$DATA_FILE",
  "member_flow": $MEMBER,
  "member_tokens": $TOKENS,
  "k6_exit_code": $K6_EXIT,
  "run_status": $(jq -Rn --arg v "$RUN_STATUS" '$v'),
  "k6_version": $(k6 version | head -1 | jq -Rs 'rtrimstr("\n")'),
  "perf_git_sha": "$GIT_SHA",
  "data_json_sha256": "$(shasum -a 256 "$PERF_DIR/k6/data/$DATA_FILE" | cut -c1-16)",
  "warmup_seconds": $WARMUP,
  "prometheus_testid": "$ID"
}
JSON

  # 모을지는 측정이 끝난 지금 판단해요: 이 대상의 api를 측정 구간에 긁었는지(시작 전 한 번 본 것으로 정하지 않아요)
  if [ "$PROM_OK" = 1 ] && prom_api_up "$((END - START + 60))s"; then
    echo "== 결과 모으기 (Prometheus 마지막 수집을 15초 기다려요)"
    sleep 15
    "$PERF_DIR/collect.sh" "$OUT" || warn "결과 모으기 중 오류가 있었어요(다시: perf/collect.sh $OUT)"
  else
    echo "== 서버 지표·그래프 모으기는 건너뛰어요 (Prometheus가 없거나 측정 구간에 api($(prom_target)) 지표가 없어요)"
  fi

  echo "== 측정 끝: $OUT — $RUN_STATUS (k6 종료 코드 $K6_EXIT)"
}

if [ "$REPEAT" -eq 1 ]; then
  measure "$OUT" "$NAME" || warn "측정에 오류가 있었어요"
else
  runs=()
  for i in $(seq 1 "$REPEAT"); do
    echo "== ${i}/${REPEAT}회"
    # 한 회가 안전 장치로 끊기거나 오류가 나도 다음 회는 돌아요(사람이 멈췄을 때만 그만둬요)
    measure "$OUT/run$i" "${NAME}_run$i" || warn "${i}회에 오류가 있었어요"
    runs+=("$OUT/run$i")
    [ "$HUMAN_STOP" = 0 ] || { warn "사람이 멈춰서 남은 회는 하지 않아요"; break; }
  done
  python3 "$PERF_DIR/lib/tools.py" median "$OUT" "${runs[@]}"
  echo "== ${REPEAT}회 중앙값 → $OUT (회별: run1/ … run${REPEAT}/)"
  for r in "${runs[@]}"; do python3 "$PERF_DIR/lib/tools.py" row "$r"; done
fi
K6_EXIT="$(jq -r .k6_exit_code "$OUT/meta.json")"

echo
echo "== 끝: $OUT  (k6 종료 코드 $K6_EXIT: 0 = k6 기준 통과, 99 = k6 기준 넘음(기록만), 회 상태: $(jq -r '[.run_statuses // [.run_status]][0] | join(" / ")' "$OUT/meta.json"))"
echo "문서 「결과 기록」 표에 붙일 줄:"
python3 "$PERF_DIR/lib/tools.py" row "$OUT"
