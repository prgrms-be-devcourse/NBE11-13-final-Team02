#!/usr/bin/env bash
# 더미 데이터의 본보기를 운영 api DB에서 읽어 와요(읽기 전용 트랜잭션, SELECT만). 결과: perf/seed/templates/*.csv (커밋하지 않아요)
# 본보기가 바뀌면 더미도 바뀌어서, 한 번 받은 뒤에는 단계 ②·③을 다 잴 때까지 다시 받지 않아요.
#   perf/seed/fetch-templates.sh
source "$(dirname "$0")/../lib/common.sh"

OUT="$PERF_DIR/seed/templates"
mkdir -p "$OUT"

# 파일 이름별로 운영에서 읽을 SELECT
query() {
  case "$1" in
    # 공개된 공고와 현재 버전. loc = 첫 위치의 payload id(더미 공고 하나에 위치 하나)
    notices) echo "select row_number() over (order by n.id) as tpl, n.field, n.title, n.provider, n.notice_type,
        n.supply_type_text, n.summary_text, n.detail_url, v.review_status, v.payload, v.conditions, v.held_items,
        v.application_windows,
        (select min(l.notice_location_id) from area.notice_locations l where l.notice_id = n.id) as loc
      from notice.notices n join notice.notice_versions v on v.notice_id = n.id and v.version = n.current_version
      where n.current_version > 0 and not n.hidden and n.merged_into is null" ;;
    # 위치별 생활권 점수·지표. loc_no로 한 위치의 값을 묶어서 같이 써요
    location_scores) echo "select dense_rank() over (order by location_id) as loc_no, score_kind, category, score, score_version
      from area.location_scores" ;;
    location_metrics) echo "select s.loc_no, m.metric, m.value from area.location_metrics m
      join (select distinct location_id, dense_rank() over (order by location_id) as loc_no from area.location_scores) s
        on s.location_id = m.location_id" ;;
    notice_area_scores) echo "select kind, score, calculation_version from notice.notice_area_scores" ;;
    benefits) echo "select row_number() over (order by id) as tpl, provider, field, category, title, agency, categories,
        detail_url, apply_url, summary, apply_always, business_start, business_end, schedule_text, how_to_apply,
        documents, contact, relevant, confidence, needs_review, review_reasons, has_document
      from benefit.benefits" ;;
    benefit_conditions) echo "select b.tpl, c.ordinal, c.type, c.value, c.grp, c.known, c.raw_text, c.evidence, c.confidence, c.origin
      from benefit.benefit_conditions c
      join (select id, row_number() over (order by id) as tpl from benefit.benefits) b on b.id = c.benefit_id" ;;
    benefit_supports) echo "select b.tpl, s.ordinal, s.kind, s.amount_max, s.amount_per, s.months, s.rate_pct, s.raw_text, s.evidence
      from benefit.benefit_supports s
      join (select id, row_number() over (order by id) as tpl from benefit.benefits) b on b.id = s.benefit_id" ;;
    # 시군구 중심점(경계 안쪽의 점). 경계가 없는 코드는 더미 위치로 쓰지 않아요
    regions) echo "select r.code, r.level, r.parent_code, r.name, r.full_name,
        st_y(st_pointonsurface(b.geom::geometry)) as lat, st_x(st_pointonsurface(b.geom::geometry)) as lng
      from common.regions r left join area.region_boundaries b on b.level = 'SIG' and b.code = r.code
      where r.valid" ;;
  esac
}

for name in notices location_scores location_metrics notice_area_scores benefits benefit_conditions benefit_supports regions; do
  printf 'begin transaction read only;\ncopy (%s) to stdout with (format csv, header);\ncommit;\n' "$(query "$name")" \
    | remote_sql > "$OUT/$name.csv.tmp"
  mv "$OUT/$name.csv.tmp" "$OUT/$name.csv"
  echo "$name: $(python3 -c 'import csv,sys; csv.field_size_limit(sys.maxsize); print(sum(1 for _ in csv.reader(open(sys.argv[1]))) - 1)' "$OUT/$name.csv")행"
done
date '+%Y-%m-%dT%H:%M:%S%z' > "$OUT/fetched_at"
echo "== $OUT"
