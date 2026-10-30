-- 주요 API가 쓰는 쿼리를 EXPLAIN (ANALYZE, BUFFERS)로 뽑아요 (perf/seed/explain.sh). guard.sql 다음에 돌아요.
-- SQL은 api 코드의 것을 그대로 옮겼어요(위치는 각 쿼리 위 주석). 값은 더미에서 골라요: 서울, 관심·알림이 가장 많은 더미 회원
-- 쿼리마다 한 번 먼저 돌려 캐시를 데운 뒤(결과는 버려요) 잰 값을 남겨요. 읽기 전용 트랜잭션이라 아무것도 바꾸지 않아요
begin transaction read only;

-- 값 고르기
select (select count(*) from notice.notices where id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1)) as seed_notices \gset
select string_agg(quote_literal(id::text), ',') as cand_ids from (
  select id from notice.notices n
  where current_version > 0 and not hidden and merged_into is null and field = 'HOUSING'
    and status in ('UPCOMING', 'APPLYING', 'ALWAYS', 'UNKNOWN')
  order by coalesce(apply_end, date '9999-12-31'), id limit 300) x \gset
select string_agg(quote_literal(location_id::text), ',') as cand_locs
from area.notice_locations where notice_id in (:cand_ids) \gset
select string_agg(quote_literal(notice_id::text), ',') as map_ids from (
  select distinct on (notice_id) notice_id from area.notice_locations
  where geom && st_makeenvelope(126.76, 37.42, 127.18, 37.70, 4326)::geography
  order by notice_id, notice_location_id limit 1000) x \gset
select string_agg(quote_literal(id::text), ',') as benefit_ids from (
  select id from benefit.benefits b where b.field = 'BENEFIT' and b.status in ('UPCOMING', 'OPEN')
    and (b.region_scope = 'NATIONWIDE' or exists (select 1 from unnest(b.region_codes) r, unnest(array['11']) f
         where r like f || '%' or f like r || '%'))) x \gset
select user_id as fav_user from account.favorites
where user_id between pg_temp.seed_lo(3) and pg_temp.seed_hi(3)
group by user_id order by count(*) desc, user_id limit 1 \gset
select user_id as inbox_user from notification.notifications
where user_id between pg_temp.seed_lo(3) and pg_temp.seed_hi(3)
group by user_id order by count(*) desc, user_id limit 1 \gset
select id as detail_id from notice.notices where id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1) order by id limit 1 \gset
select target_id as fav_benefit from account.favorites where user_id between pg_temp.seed_lo(3) and pg_temp.seed_hi(3) and target_type = 'BENEFIT' limit 1 \gset

-- ── 공고 검색 후보 (NoticeQueries.java candidates): 시도 필터 / 시군구 필터 / 지역 없음. 정렬·페이지는 Java라 늘 limit 300
\set q 'notice_candidates_sido'
\set sql 'select id, field, title, provider, notice_type, region_code, apply_start, apply_end, posted_on, status, needs_check from notice.notices n where current_version > 0 and not hidden and merged_into is null and field = ''HOUSING'' and status in (''UPCOMING'', ''APPLYING'', ''ALWAYS'', ''UNKNOWN'') and (nationwide or exists (select 1 from notice.notice_regions r where r.notice_id = n.id and (false or left(r.sigungu_code, 2) in (''11'')))) order by coalesce(apply_end, date ''9999-12-31'') asc, id asc limit 300'
\ir explain_one.sql

\set q 'notice_candidates_sigungu'
\set sql 'select id, field, title, provider, notice_type, region_code, apply_start, apply_end, posted_on, status, needs_check from notice.notices n where current_version > 0 and not hidden and merged_into is null and field = ''HOUSING'' and status in (''UPCOMING'', ''APPLYING'', ''ALWAYS'', ''UNKNOWN'') and (nationwide or exists (select 1 from notice.notice_regions r where r.notice_id = n.id and (false or r.sigungu_code in (''11680'') or (r.whole and r.sigungu_code in (''11000'', ''11680''))))) order by coalesce(apply_end, date ''9999-12-31'') asc, id asc limit 300'
\ir explain_one.sql

\set q 'notice_candidates_all'
\set sql 'select id, field, title, provider, notice_type, region_code, apply_start, apply_end, posted_on, status, needs_check from notice.notices n where current_version > 0 and not hidden and merged_into is null and field = ''HOUSING'' and status in (''UPCOMING'', ''APPLYING'', ''ALWAYS'', ''UNKNOWN'') order by coalesce(apply_end, date ''9999-12-31'') asc, id asc limit 300'
\ir explain_one.sql

-- 판정 입력 (NoticeQueries.java currentVersions·judgingSources). 판정 캐시가 비었을 때 300개 payload를 다 읽어요
\set q 'notice_current_versions'
\set sql 'select id, current_version from notice.notices where id in (' :cand_ids ') and current_version > 0 and not hidden'
\ir explain_one.sql

\set q 'notice_judging_payloads'
\set sql 'select n.id, n.current_version, n.field, n.notice_type, jsonb_build_object(''judgment'', v.payload -> ''judgment'', ''notice'', v.payload -> ''notice'')::text from notice.notices n join notice.notice_versions v on v.notice_id = n.id and v.version = n.current_version where n.id in (' :cand_ids ') and n.current_version > 0 and not n.hidden'
\ir explain_one.sql

-- 후보의 위치·지표·생활권 점수 (PostgresNoticeLocations · PostgresSiteDetails · PostgresLocationScores)
\set q 'area_locations'
\set sql 'select location_id, notice_id, notice_location_id, notice_version, address_text, bjd_code_given, status, fail_reason, st_y(geom::geometry) as lat, st_x(geom::geometry) as lng from area.notice_locations where notice_id = any(array[' :cand_ids ']::uuid[]) order by notice_id, notice_location_id'
\ir explain_one.sql

\set q 'area_metrics'
\set sql 'select location_id, metric, value from area.location_metrics where location_id = any(array[' :cand_locs ']::uuid[])'
\ir explain_one.sql

\set q 'area_life_scores'
\set sql 'select location_id, category, score, score_version, calculated_at from area.location_scores where score_kind = ''LIFE'' and location_id = any(array[' :cand_locs ']::uuid[]) order by location_id, category'
\ir explain_one.sql

-- ── 지도 (PostgresNoticeLocations representativesWithin + NoticeQueries summaries, 1000개씩)
\set q 'map_points_seoul'
\set sql 'select * from (select distinct on (notice_id) location_id, notice_id, notice_location_id, notice_version, address_text, bjd_code_given, status, fail_reason, st_y(geom::geometry) as lat, st_x(geom::geometry) as lng from area.notice_locations where geom && st_makeenvelope(126.76, 37.42, 127.18, 37.70, 4326)::geography order by notice_id, notice_location_id) x limit 5000'
\ir explain_one.sql

\set q 'map_points_capital'
\set sql 'select * from (select distinct on (notice_id) location_id, notice_id, notice_location_id, notice_version, address_text, bjd_code_given, status, fail_reason, st_y(geom::geometry) as lat, st_x(geom::geometry) as lng from area.notice_locations where geom && st_makeenvelope(126.60, 37.20, 127.40, 37.80, 4326)::geography order by notice_id, notice_location_id) x limit 5000'
\ir explain_one.sql

\set q 'map_summaries'
\set sql 'select m.id, n.id, n.current_version, n.field, n.title, n.provider, n.notice_type, n.status, n.apply_start, n.apply_end, n.nationwide, array(select r.sigungu_code from notice.notice_regions r where r.notice_id = n.id order by r.sigungu_code) as regions, n.detail_url from notice.notices m join notice.notices n on n.id = coalesce(m.merged_into, m.id) where m.id in (' :map_ids ') and n.current_version > 0 and not n.hidden'
\ir explain_one.sql

-- ── 공고 상세 (NoticeQueries detail · AreaScoreRepository)
\set q 'notice_detail'
\set sql 'select n.id, n.field, n.title, n.provider, n.notice_type, n.region_code, n.apply_start, n.apply_end, n.posted_on, n.supply_type_text, n.detail_url, n.current_version, n.reviewed, n.updated_at, v.payload::text, n.status, n.needs_check from notice.notices n join notice.notice_versions v on v.notice_id = n.id and v.version = n.current_version where n.id = coalesce((select m.merged_into from notice.notices m where m.id = ' :'detail_id' '), ' :'detail_id' ') and n.current_version > 0 and not n.hidden'
\ir explain_one.sql

\set q 'notice_area_scores'
\set sql 'select s.location_id, s.kind, s.score, s.calculated_at, s.calculation_version from notice.notice_area_scores s join notice.notices n on n.id = s.notice_id join notice.notice_versions iv on iv.notice_id = s.notice_id and iv.version = s.notice_version join notice.notice_versions cv on cv.notice_id = n.id and cv.version = n.current_version where s.notice_id = ' :'detail_id' ' and iv.payload->''notice''->''locations'' = cv.payload->''notice''->''locations'' order by s.location_id, s.kind'
\ir explain_one.sql

-- ── 혜택 판정 검색 (BenefitRepository openBenefits + withLines): 열린 혜택 전부와 조건·지원 내용 전부, 판정은 Java
\set q 'benefits_open_sido'
\set sql 'select * from benefit.benefits b where b.field = ''BENEFIT'' and b.status in (''UPCOMING'', ''OPEN'') and (cardinality(cast(''{11}'' as text[])) = 0 or b.region_scope = ''NATIONWIDE'' or exists (select 1 from unnest(b.region_codes) r, unnest(cast(''{11}'' as text[])) f where r like f || ''%'' or f like r || ''%'')) order by b.apply_end nulls last, b.id'
\ir explain_one.sql

\set q 'benefits_open_all'
\set sql 'select * from benefit.benefits b where b.field = ''BENEFIT'' and b.status in (''UPCOMING'', ''OPEN'') and (cardinality(cast(''{}'' as text[])) = 0 or b.region_scope = ''NATIONWIDE'' or exists (select 1 from unnest(b.region_codes) r, unnest(cast(''{}'' as text[])) f where r like f || ''%'' or f like r || ''%'')) order by b.apply_end nulls last, b.id'
\ir explain_one.sql

\set q 'benefit_conditions'
\set sql 'select * from benefit.benefit_conditions where benefit_id = any(array[' :benefit_ids ']::uuid[]) order by benefit_id, ordinal'
\ir explain_one.sql

\set q 'benefit_supports'
\set sql 'select * from benefit.benefit_supports where benefit_id = any(array[' :benefit_ids ']::uuid[]) order by benefit_id, ordinal'
\ir explain_one.sql

-- ── 관심 목록 (FavoriteRepository page + 혜택은 하나씩 BenefitRepository summary)
\set q 'favorites_page'
\set sql 'select target_type, target_id, created_at from account.favorites where user_id = ' :'fav_user' ' and (cast(null as text) is null) and (cast(null as timestamptz) is null) order by created_at desc, target_id desc limit 21'
\ir explain_one.sql

\set q 'favorite_benefit_one'
\set sql 'select id, field, source_version, title, agency, status, apply_start, apply_end, apply_always, region_scope, region_codes, detail_url from benefit.benefits where id = ' :'fav_benefit'
\ir explain_one.sql

-- ── 알림 목록·안 읽은 수 (InboxRepository)
\set q 'notifications_page'
\set sql 'select id, type, target_type, target_id, title, body, link_path, created_at, read_at from notification.notifications where user_id = ' :'inbox_user' ' and (cast(null as uuid) is null) and (not false or read_at is null) order by id desc limit 21'
\ir explain_one.sql

\set q 'notifications_unread_count'
\set sql 'select count(*) from notification.notifications where user_id = ' :'inbox_user' ' and read_at is null'
\ir explain_one.sql

rollback;
