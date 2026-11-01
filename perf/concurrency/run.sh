#!/usr/bin/env bash
# 동시성 시험: 같은 대상을 동시에 바꿔도 데이터가 맞는지. 시나리오마다 100개를 한꺼번에 출발시켜 10회 반복하고, 끝난 뒤 DB를 세서 판단해요.
#   perf/concurrency/run.sh local [1|2|3|4|5|6|7|all]
# 대상은 로컬만이에요: BASE_URL이 localhost이고, DB가 로컬 api_fe(더미 데이터)여야 해요. 아니면 거부해요.
#   1~3  k6로 로컬 api에 (관심 등록, 관심 등록·해제 섞기, 알림 설정·관심 조건 저장). 더미 회원 99901~99903만 써요
#   4    JUnit (공고 저장 리스너·알림 요청 리스너, 같은 eventId) — Testcontainers DB
#   5    pytest (관리자 동시 승인·수정) — 로컬 Postgres의 시험 전용 DB extraction_concurrency, 회마다 임시 스키마
#   6    관심 수 같은 집계가 있는지 보고, 없으면 건너뛰어요
#   7    관심 199개인 더미 회원(99907)이 서로 다른 공고 100개를 동시에 관심 등록 → 200개를 넘지 않고 넘친 요청은 409
# 결과: perf/results/<yyyyMMdd-HHmm>_concurrency_local/ (summary.md, 시나리오별 JSON)
source "$(dirname "$0")/../lib/common.sh"

TARGET="${1:-}"
WHICH="${2:-all}"
[ "$TARGET" = local ] || { sed -n '2,9p' "$0"; echo "대상은 local만 돼요" >&2; exit 1; }
case "$WHICH" in 1 | 2 | 3 | 4 | 5 | 6 | 7 | all) ;; *) sed -n '2,9p' "$0"; exit 1 ;; esac
run() { [ "$WHICH" = all ] || [ "$WHICH" = "$1" ]; }

ROUNDS="${CONCURRENCY_ROUNDS:-10}"
VUS="${CONCURRENCY_THREADS:-100}"
export CONCURRENCY_ROUNDS="$ROUNDS" CONCURRENCY_THREADS="$VUS"
REPO="$(git -C "$PERF_DIR" rev-parse --show-toplevel)"

# ── 대상 확인: 로컬 api_fe가 아니면 거부. BASE_URL은 명령줄에서 주지 않으면 로컬 api (perf/.env의 운영 주소를 쓰지 않아요)
BASE_URL="${CLI_BASE_URL:-http://localhost:8080}"
use_target local
[ "$(perf_target)" = local ] || { echo "BASE_URL($BASE_URL)이 localhost가 아니에요. 거부해요" >&2; exit 1; }
[ "$LOCAL_DB_NAME" = api_fe ] || { echo "로컬 DB가 api_fe가 아니에요($LOCAL_DB_NAME). 거부해요" >&2; exit 1; }
sql() { # 표준 입력 SQL을 로컬 api_fe에 (guard.sql로 한 번 더 확인)
  { printf '\\set seed_target local\n\\set seed_db %s\n' "$LOCAL_DB_NAME"; cat "$PERF_DIR/seed/sql/guard.sql" -; } \
    | target_sql local -At
}
DB="$(echo "select current_database();" | sql)" || { echo "로컬 DB 확인 실패" >&2; exit 1; }
[ "$DB" = api_fe ] || { echo "붙은 DB가 api_fe가 아니에요($DB). 거부해요" >&2; exit 1; }

user_id() { printf '5eed0003-0000-7000-8000-%012x' "$1"; }
NOTICE_ID="$(echo "select id from notice.notices where id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1) and current_version > 0 and not hidden order by id limit 1;" | sql)"
[ -n "$NOTICE_ID" ] || { echo "더미 공고가 없어요: perf/seed/seed.sh local up" >&2; exit 1; }
if run 1 || run 2 || run 3 || run 7; then
  curl -sf -o /dev/null "$BASE_URL/api/v1/notices/$NOTICE_ID" \
    || { echo "로컬 api가 더미 공고를 못 찾아요. api가 api_fe에 붙어 있는지 확인해요(거부)" >&2; exit 1; }
fi

OUT="$PERF_DIR/results/$(date +%Y%m%d-%H%M)_concurrency_local"
mkdir -p "$OUT"
echo "== 동시성 시험 → $OUT (동시 $VUS × ${ROUNDS}회)"

# ── 1~3: k6
k6_round() { # 시나리오 회 토큰 [REGIONS] [NOTICE_IDS]
  local scenario="$1" round="$2" token="$3"
  SCENARIO="$scenario" BASE_URL="$BASE_URL" TOKEN="$token" NOTICE_ID="$NOTICE_ID" REGIONS="${4:-[]}" NOTICE_IDS="${5:-[]}" VUS="$VUS" \
    OUT="$OUT/raw/s${scenario}_r${round}.json" k6 run -q "$PERF_DIR/concurrency/k6/at-once.js" > "$OUT/raw/s${scenario}_r${round}.log" 2>&1
}

if run 1 || run 2 || run 3 || run 7; then
  mkdir -p "$OUT/raw"
  TOKENS="$OUT/raw/tokens.json"
  FIRST_USER=99901 TOKENS_OUT="$TOKENS" "$PERF_DIR/k6/make-tokens.sh" local 7 1 > /dev/null
  token() { jq -r ".tokens[$(($1 - 1))]" "$TOKENS"; }
fi

if run 1 || run 2; then
  for s in 1 2; do
    run "$s" || continue
    user="$(user_id $((99900 + s)))"
    echo "== 시나리오 $s"
    for r in $(seq 1 "$ROUNDS"); do
      echo "delete from account.favorites where user_id = '$user';" | sql > /dev/null
      k6_round "$s" "$r" "$(token "$s")"
      rows="$(echo "select count(*) from account.favorites where user_id = '$user' and target_type = 'NOTICE' and target_id = '$NOTICE_ID';" | sql)"
      listed="$(curl -sf -H "Authorization: Bearer $(token "$s")" "$BASE_URL/api/users/me/favorites?size=50" | jq --arg id "$NOTICE_ID" '[.items[] | select(.targetId == $id)] | length')"
      jq -n --argjson r "$r" --argjson rows "$rows" --argjson listed "${listed:-null}" '{round: $r, db_rows: $rows, api_listed: $listed}' \
        > "$OUT/raw/s${s}_r${r}.db.json"
      printf '  %s회: DB %s줄, 목록 %s\n' "$r" "$rows" "${listed:-?}"
    done
  done
fi

if run 3; then
  user="$(user_id 99903)"
  REGIONS="$(echo "select json_agg(code) from (select code from common.regions where level = 'SIGUNGU' and valid order by code limit 40) x;" | sql)"
  echo "$REGIONS" > "$OUT/raw/s3_regions.json"
  echo "== 시나리오 3"
  for r in $(seq 1 "$ROUNDS"); do
    printf "delete from account.notification_settings where user_id = '%s'; delete from account.housing_interests where user_id = '%s';" "$user" "$user" | sql > /dev/null
    k6_round 3 "$r" "$(token 3)" "$REGIONS"
    db="$(echo "select json_build_object(
        'settings', (select row_to_json(s) from (select new_notice, new_support, deadline, correction, follow_up, quiet_enabled from account.notification_settings where user_id = '$user') s),
        'housing', (select row_to_json(h) from (select region_codes, include_nationwide, notice_types from account.housing_interests where user_id = '$user') h));" | sql)"
    api="$(jq -n --argjson s "$(curl -sf -H "Authorization: Bearer $(token 3)" "$BASE_URL/api/users/me/notification-settings")" \
      --argjson h "$(curl -sf -H "Authorization: Bearer $(token 3)" "$BASE_URL/api/users/me/interests/housing")" '{settings: $s, housing: $h}')"
    jq -n --argjson r "$r" --argjson db "$db" --argjson api "$api" '{round: $r, db: $db, api: $api}' > "$OUT/raw/s3_r${r}.db.json"
    echo "  ${r}회 끝"
  done
fi

# ── 7: 관심 개수 제한(200)을 동시 요청이 넘는지
if run 7; then
  user="$(user_id 99907)"
  max=200
  echo "== 시나리오 7"
  for r in $(seq 1 "$ROUNDS"); do
    # 더미 공고로 관심 199개를 채우고, 그와 겹치지 않는 공고 VUS개를 VU마다 하나씩 보내요
    echo "delete from account.favorites where user_id = '$user';
          insert into account.favorites (user_id, target_type, target_id, created_at)
          select '$user', 'NOTICE', id, now() - interval '1 day' from notice.notices
          where id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1) and current_version > 0 and not hidden
          order by id limit $((max - 1));" | sql > /dev/null
    ids="$(echo "select json_agg(id) from (select id from notice.notices
          where id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1) and current_version > 0 and not hidden and merged_into is null
          order by id offset $((max - 1)) limit $VUS) x;" | sql)"
    k6_round 7 "$r" "$(token 7)" "[]" "$ids"
    rows="$(echo "select count(*) from account.favorites where user_id = '$user';" | sql)"
    jq -n --argjson r "$r" --argjson rows "$rows" --argjson max "$max" '{round: $r, db_rows: $rows, max: $max, start_rows: ($max - 1)}' \
      > "$OUT/raw/s7_r${r}.db.json"
    printf '  %s회: 관심 %s개\n' "$r" "$rows"
  done
fi

# ── 4: JUnit (공고 저장 리스너 · 알림 요청 리스너)
if run 4; then
  echo "== 시나리오 4 (JUnit, Testcontainers)"
  (cd "$REPO/services/api" && CONCURRENCY=1 CONCURRENCY_OUT="$OUT" ./gradlew -q test --rerun --tests 'kr.mujuthaek.api.concurrency.*' > "$OUT/raw-4-1.log" 2>&1) \
    || warn "4-1 불합격 또는 실행 실패 ($OUT/raw-4-1.log)"
  (cd "$REPO/services/notification" && CONCURRENCY=1 CONCURRENCY_OUT="$OUT" ./gradlew -q test --rerun --tests 'kr.mujuthaek.notification.concurrency.*' > "$OUT/raw-4-2.log" 2>&1) \
    || warn "4-2 불합격 또는 실행 실패 ($OUT/raw-4-2.log)"
fi

# ── 5: pytest (관리자 동시 승인·수정), 시험 전용 DB
if run 5; then
  echo "== 시나리오 5 (pytest, 시험 전용 DB extraction_concurrency)"
  infra_env="$(git -C "$PERF_DIR" rev-parse --path-format=absolute --git-common-dir)/../infra/.env"
  [ -f "$REPO/infra/.env" ] && infra_env="$REPO/infra/.env"
  pg_pw="$(sed -n 's/^POSTGRES_PASSWORD=//p' "$infra_env" | tail -1)"
  PGPASSWORD="$pg_pw" psql -h "$LOCAL_DB_HOST" -p "$LOCAL_DB_PORT" -U postgres -d postgres -X -q -At \
    -c "select 1 from pg_database where datname = 'extraction_concurrency'" | grep -q 1 \
    || PGPASSWORD="$pg_pw" psql -h "$LOCAL_DB_HOST" -p "$LOCAL_DB_PORT" -U postgres -d postgres -X -q -c "create database extraction_concurrency"
  (cd "$REPO/services/extraction" && CONCURRENCY=1 CONCURRENCY_OUT="$OUT" \
    EXTRACTION_TEST_DB_URL="postgresql://postgres:$pg_pw@$LOCAL_DB_HOST:$LOCAL_DB_PORT/extraction_concurrency" \
    uv run --quiet pytest -q tests/test_concurrency_review.py > "$OUT/raw-5.log" 2>&1) \
    || warn "5 불합격 또는 실행 실패 ($OUT/raw-5.log)"
fi

# ── 6: 집계 칸이 있는지
if run 6; then
  echo "== 시나리오 6 (집계 칸 찾기)"
  echo "select coalesce(json_agg(table_schema || '.' || table_name || '.' || column_name), '[]') from information_schema.columns
        where table_schema in ('account', 'benefit', 'notice', 'notification')
          and (column_name ~ '(favorite|like|bookmark|interest)s?_count' or column_name in ('favorites', 'likes', 'favorite_count'));" \
    | sql > "$OUT/raw-6.json"
fi

python3 "$PERF_DIR/concurrency/report.py" "$OUT" "$VUS" "$ROUNDS"
echo "== $OUT/summary.md"
