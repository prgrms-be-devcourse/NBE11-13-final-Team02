#!/usr/bin/env bash
# 성능 테스트용 더미 데이터를 넣고·빼고·세요. 본보기는 fetch-templates.sh로 운영에서 읽어 둔 것이에요.
#   perf/seed/seed.sh <local|dev> <up|down|count>
# local = 노트북 infra의 api DB(perf/.env LOCAL_DB_NAME, 기본 api_fe), dev = dev 서버 .env의 api_dev(RDS, 서버를 거쳐 접속)
# prod(api DB)에는 못 넣어요: 여기서 서버·DB 이름을 확인하고, sql/guard.sql이 DB 안에서 한 번 더 확인해요
source "$(dirname "$0")/../lib/common.sh"

TARGET="${1:-}"
CMD="${2:-}"
SQL="$PERF_DIR/seed/sql"
TPL="$PERF_DIR/seed/templates"
TEMPLATES="notices location_scores location_metrics notice_area_scores benefits benefit_conditions benefit_supports regions"

case "$TARGET" in local|dev) ;; *) sed -n '2,5p' "$0"; exit 1 ;; esac
case "$CMD" in up|down|count) ;; *) sed -n '2,5p' "$0"; exit 1 ;; esac

if [ "$TARGET" = local ]; then DB="$LOCAL_DB_NAME"; else DB="$DEV_DB_NAME"; fi

# 모든 SQL 앞에 붙여요: 대상·DB 이름을 넘기고 guard.sql로 확인
header() {
  printf '\\set seed_target %s\n\\set seed_db %s\n' "$TARGET" "$DB"
  cat "$SQL/guard.sql"
}

templates() {
  cat "$SQL/templates.sql"
  for name in $TEMPLATES; do
    printf 'copy tpl_%s from stdin with (format csv, header);\n' "$name"
    cat "$TPL/$name.csv"
    printf '\\.\n'
  done
}

confirm() {
  [ "$TARGET" = dev ] || return 0
  [ "${SEED_YES:-}" = 1 ] && return 0
  printf 'dev(%s, %s)에서 더미를 %s 해요. RDS를 운영과 같이 써서 그동안 운영도 느려질 수 있어요. 계속할까요? [y/N] ' \
    "$DEV_EC2_HOST" "$DB" "$1"
  read -r yes
  [ "$yes" = y ] || { echo "그만뒀어요"; exit 1; }
}

count() { { header; cat "$SQL/count.sql"; } | target_sql "$TARGET"; }

START=$(date +%s)
case "$CMD" in
  up)
    [ -f "$TPL/fetched_at" ] || { echo "본보기가 없어요: perf/seed/fetch-templates.sh 먼저" >&2; exit 1; }
    confirm 넣기
    echo "== $TARGET($DB)에 더미 넣기 (본보기: 운영 $(cat "$TPL/fetched_at"))"
    { header; templates; cat "$SQL/up.sql"; } | target_sql "$TARGET"
    ;;
  down)
    confirm 지우기
    echo "== $TARGET($DB)에서 더미 지우기"
    { header; cat "$SQL/down.sql"; } | target_sql "$TARGET"
    ;;
esac
[ "$CMD" = count ] || echo "== 걸린 시간 $(($(date +%s) - START))초"
count
