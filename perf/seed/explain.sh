#!/usr/bin/env bash
# 주요 API 쿼리(공고 검색·지도·상세, 혜택 판정 검색, 홈 요약, 관심·알림 목록)의 실행 계획을 모아요. 더미를 넣은 뒤에 돌려요.
#   perf/seed/explain.sh <local|dev>
# 결과: perf/results/explain/<yyyyMMdd>_<대상>/<쿼리>.txt + summary.md (인덱스 후보는 적기만 해요. 코드·마이그레이션은 안 바꿔요)
source "$(dirname "$0")/../lib/common.sh"

TARGET="${1:-}"
case "$TARGET" in local|dev) ;; *) sed -n '2,4p' "$0"; exit 1 ;; esac
if [ "$TARGET" = local ]; then DB="$LOCAL_DB_NAME"; else DB="$DEV_DB_NAME"; fi
SQL="$PERF_DIR/seed/sql"
OUT="$PERF_DIR/results/explain/$(date +%Y%m%d)_$TARGET"
mkdir -p "$OUT"

echo "== $TARGET($DB) 실행 계획 모으기 → $OUT"
# explain.sql의 \ir explain_one.sql을 그 자리에 펼쳐 보내요(dev는 서버에서 돌아 파일을 못 읽어요)
{
  printf '\\set seed_target %s\n\\set seed_db %s\n' "$TARGET" "$DB"
  cat "$SQL/guard.sql"
  awk -v f="$SQL/explain_one.sql" '$0 == "\\ir explain_one.sql" { while ((getline l < f) > 0) print l; close(f); next } { print }' "$SQL/explain.sql"
} | target_sql "$TARGET" > "$OUT/all.txt"

python3 "$PERF_DIR/seed/explain_summary.py" "$OUT" "$TARGET"
echo "== $OUT/summary.md"
