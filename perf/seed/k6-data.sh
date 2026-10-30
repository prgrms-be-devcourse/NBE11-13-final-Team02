#!/usr/bin/env bash
# 더미 기준 k6 테스트 데이터를 만들어요 → perf/k6/data/seed.json (기존 data.json은 그대로. 운영 기준 결과와 비교가 깨지지 않게)
# 같은 본보기로 넣은 더미는 id·지역이 같아서 local에서 만든 파일을 dev에서도 써요. 넣은 뒤 한 번만 만들어 커밋해요
#   perf/seed/k6-data.sh <local|dev>
source "$(dirname "$0")/../lib/common.sh"

TARGET="${1:-}"
case "$TARGET" in local|dev) ;; *) sed -n '2,4p' "$0"; exit 1 ;; esac
if [ "$TARGET" = local ]; then DB="$LOCAL_DB_NAME"; else DB="$DEV_DB_NAME"; fi
OUT="$PERF_DIR/k6/data/seed.json"

# 지역 코드는 더미 공고 분포 그대로(서울·경기가 자주 나와요), 공고·혜택 id는 상태를 섞어 200개씩
picked="$({
  printf '\\set seed_target %s\n\\set seed_db %s\n' "$TARGET" "$DB"
  cat "$PERF_DIR/seed/sql/guard.sql"
  cat <<'SQL'
select setseed(0.5) \g /dev/null
select json_build_object(
  'sidoCodes', (select json_agg(c) from (select left(sigungu_code, 2) c from notice.notice_regions
      where notice_id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1) order by random() limit 40) x),
  'sigunguCodes', (select json_agg(c) from (select sigungu_code c from notice.notice_regions
      where notice_id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1) order by random() limit 40) x),
  'noticeIds', (select json_agg(id) from (select id from notice.notices
      where id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1) order by random() limit 200) x),
  'benefitIds', (select json_agg(id) from (select id from benefit.benefits
      where id between pg_temp.seed_lo(2) and pg_temp.seed_hi(2) and status <> 'HIDDEN' order by random() limit 200) x),
  'users', (select count(*) from account.users where id between pg_temp.seed_lo(3) and pg_temp.seed_hi(3))
);
SQL
} | target_sql "$TARGET" -At)"

[ "$(jq '.noticeIds | length' <<< "$picked")" -gt 0 ] || { echo "더미가 없어요: perf/seed/seed.sh $TARGET up" >&2; exit 1; }

# 지도 영역 [swLat, swLng, neLat, neLng, zoom]: data.json과 같은 4개 + 더미가 촘촘한 좁은 영역(서울 도심·분당·인천)
# 지원자 조건: data.json과 같은 4벌 + 더미 혜택 조건(나이·소득·무주택·혼인·자녀·복지)이 많이 걸리는 4벌. 실제 사람 값이 아니에요
jq --argjson picked "$picked" '{
  sidoCodes: $picked.sidoCodes,
  sigunguCodes: $picked.sigunguCodes,
  noticeIds: $picked.noticeIds,
  benefitIds: $picked.benefitIds,
  mapBoxes: (.mapBoxes + [
    [37.54, 126.95, 37.58, 127.02, 15],
    [37.33, 127.08, 37.40, 127.15, 14],
    [37.40, 126.60, 37.55, 126.75, 13]
  ]),
  applicants: (.applicants + [
    { birthMonth: "1999-06", householdHead: true, householdSize: 1, marital: "SINGLE", homeOwner: false, homelessYears: "LT_1",
      targetGroups: ["YOUTH"], subscription: { months: "6_12", payments: 8 },
      income: { MEDIAN_INCOME: { gtPct: 100, lePct: 150 } }, incomeYear: 2026, assets: { gtWon: 0, leWon: 50000000 } },
    { birthMonth: "1990-02", householdHead: true, householdSize: 3, marital: "MARRIED", marriedOn: "2021-09", children: 1,
      homeOwner: false, homelessYears: "3_5", targetGroups: ["NEWLYWED"], subscription: { months: "24_PLUS", payments: 60 },
      householdIncome: { MEDIAN_INCOME: { gtPct: 100, lePct: 150 } }, incomeYear: 2026,
      assets: { gtWon: 100000000, leWon: 300000000 }, car: { gtWon: 10000000, leWon: 35000000 } },
    { birthMonth: "1958-04", householdHead: true, householdSize: 1, marital: "SINGLE", homeOwner: false, homelessYears: "5_PLUS",
      welfareStatuses: ["BASIC_LIVELIHOOD"], subscription: { months: "LT_6", payments: 0 },
      income: { MEDIAN_INCOME: { gtPct: 0, lePct: 30 } }, incomeYear: 2026, assets: { gtWon: 0, leWon: 30000000 } },
    { birthMonth: "1982-12", householdHead: true, householdSize: 5, marital: "MARRIED", marriedOn: "2008-03", children: 3,
      homeOwner: false, homelessYears: "5_PLUS", subscription: { months: "24_PLUS", payments: 150 },
      householdIncome: { URBAN_WORKER_INCOME: { gtPct: 70, lePct: 100 } }, incomeYear: 2026,
      assets: { gtWon: 50000000, leWon: 250000000 }, car: { gtWon: 0, leWon: 25000000 } }
  ]),
  seedUsers: $picked.users
}' "$PERF_DIR/k6/data/data.json" > "$OUT"

echo "$OUT: 시도 $(jq '.sidoCodes|length' "$OUT"), 시군구 $(jq '.sigunguCodes|length' "$OUT"), 공고 $(jq '.noticeIds|length' "$OUT"), 혜택 $(jq '.benefitIds|length' "$OUT"), 지원자 $(jq '.applicants|length' "$OUT")벌"
