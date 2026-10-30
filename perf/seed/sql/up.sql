-- 더미 넣기 (seed.sh up). guard.sql · templates.sql + 본보기 CSV 다음에 이어 돌아요.
-- 한 트랜잭션이라 중간에 실패하면 아무것도 남지 않아요. 같은 본보기면 같은 더미가 나와요(setseed)
\timing on
begin;

do $$ begin
  if exists (select 1 from notice.notices where id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1))
     or exists (select 1 from account.users where id between pg_temp.seed_lo(3) and pg_temp.seed_hi(3)) then
    raise exception '더미가 이미 있어요. 먼저 perf/seed/seed.sh <대상> down';
  end if;
  if not exists (select 1 from tpl_notices) or not exists (select 1 from tpl_benefits) then
    raise exception '본보기가 비었어요. perf/seed/fetch-templates.sh';
  end if;
end $$;

select setseed(0.20261030) \g /dev/null

\echo == 지역 비중
-- ── 지역: 시도 비중(서울·경기 크게)을 그 시도의 시군구에 고르게 나눠, 누적 구간 [lo, hi)로 뽑아요
create temp table seed_sido (code text primary key, weight numeric);
insert into seed_sido values
  ('11', 30), ('41', 30), ('28', 8), ('26', 5), ('48', 4), ('27', 3), ('47', 3), ('44', 3),
  ('29', 2), ('30', 2), ('31', 2), ('43', 2), ('46', 2), ('51', 2), ('52', 2), ('50', 1), ('36', 1);

create temp table seed_places as
with s as (
  select r.code, r.parent_code as sido, r.full_name, r.lat, r.lng,
         d.weight / count(*) over (partition by r.parent_code) as w
  from tpl_regions r join seed_sido d on d.code = r.parent_code
  where r.level = 'SIGUNGU' and r.lat is not null
)
select code, sido, full_name, lat, lng,
       (sum(w) over (order by code) - w) / sum(w) over () as lo
from s;
create index on seed_places (lo);
analyze seed_places;

\echo == 공고 1만 (본보기 payload 복사가 가장 오래 걸려요)
-- ── 공고 1만: 본보기를 돌려 쓰고 지역·상태·날짜·위치·점수는 새로 뽑아요
--    상태: 모집 중 40 · 마감 45 · 예정 10 · 상시 5 (%)
create temp table seed_n as
select x.i,
       1 + (x.i - 1) % (select count(*) from tpl_notices) as tpl,
       1 + floor(x.l * (select max(loc_no) from tpl_location_scores))::int as loc_no,
       p.code as sigungu, p.sido, p.full_name,
       p.lat + (x.dy - 0.5) * 0.05 as lat, p.lng + (x.dx - 0.5) * 0.06 as lng,
       x.nationwide, x.second_region,
       case when x.s < 0.40 then 'APPLYING' when x.s < 0.85 then 'CLOSED' when x.s < 0.95 then 'UPCOMING' else 'ALWAYS' end as status,
       x.d1, x.d2, x.d3
from (
  select i, random() as r, random() as s, random() as l, random() as dx, random() as dy,
         random() < 0.02 as nationwide, random() < 0.3 as second_region,
         random() as d1, random() as d2, random() as d3
  from generate_series(1, 10000) i
) x
cross join lateral (select * from seed_places p where p.lo <= x.r order by p.lo desc limit 1) p;

alter table seed_n add apply_start date, add apply_end date, add posted_on date;
update seed_n set
  apply_start = case status
    when 'APPLYING' then current_date - (1 + d1 * 20)::int
    when 'CLOSED' then current_date - (31 + d1 * 70)::int
    when 'UPCOMING' then current_date + (3 + d1 * 27)::int end,
  apply_end = case status
    when 'APPLYING' then current_date + (1 + d2 * 30)::int
    when 'CLOSED' then current_date - (1 + d2 * 29)::int
    when 'UPCOMING' then current_date + (17 + d1 * 27)::int end;
update seed_n set posted_on = coalesce(apply_start, current_date - 30) - (3 + d3 * 11)::int;

insert into notice.notices (
  id, raw_post_id, field, title, provider, notice_type, supply_type_text, region_code, apply_start, apply_end,
  posted_on, summary_text, detail_url, current_version, current_raw_post_id, current_raw_version, reviewed, hidden,
  nationwide, status, next_status_check_at, needs_check, announced_on)
select pg_temp.seed_id(1, n.i), pg_temp.seed_id(6, n.i), t.field, t.title || ' #' || n.i, t.provider, t.notice_type,
       t.supply_type_text, case when n.nationwide then null else n.sigungu end, n.apply_start, n.apply_end,
       n.posted_on, t.summary_text, t.detail_url, 1, pg_temp.seed_id(6, n.i), 1, true, false,
       n.nationwide, n.status, null, false, n.posted_on
from seed_n n join tpl_notices t on t.tpl = n.tpl;

-- payload는 본보기 그대로예요(상세·판정 화면이 읽는 모양을 지키려고). 제목·지역은 notices 행이 정해요
insert into notice.notice_versions (
  notice_id, version, raw_post_id, raw_version, source_event_id, review_status, payload, conditions, held_items,
  application_windows, content_hash, changed_fields, created_at)
select pg_temp.seed_id(1, n.i), 1, pg_temp.seed_id(6, n.i), 1, pg_temp.seed_id(7, n.i), t.review_status, t.payload,
       t.conditions, t.held_items, t.application_windows, md5('perf-seed-' || n.i), '[]', now()
from seed_n n join tpl_notices t on t.tpl = n.tpl;

-- 지역: 전국 공고(2%)는 없음, 30%는 같은 시도의 다른 시군구 하나 더
insert into notice.notice_regions (notice_id, sigungu_code, whole)
select pg_temp.seed_id(1, i), sigungu, false from seed_n where not nationwide
union all
select pg_temp.seed_id(1, n.i), o.code, false
from seed_n n
cross join lateral (
  select p.code from seed_places p where p.sido = n.sido and p.code <> n.sigungu order by random() + n.i * 0 limit 1
) o
where n.second_region and not n.nationwide;

-- 위치: 시군구 중심에서 약 ±3km. 생활권 점수·지표는 운영 위치 하나를 골라 ±10 흔들어요
insert into area.notice_locations (
  location_id, notice_id, notice_location_id, notice_version, kind, address_text, geom, status, method, region_mismatch)
select pg_temp.seed_id(8, n.i), pg_temp.seed_id(1, n.i), coalesce(t.loc, 'complex-a'), 1, 'COMPLEX',
       n.full_name || ' (perf-seed ' || n.i || ')',
       st_setsrid(st_makepoint(n.lng, n.lat), 4326)::geography, 'OK', 'FILE', false
from seed_n n join tpl_notices t on t.tpl = n.tpl;

insert into area.location_scores (location_id, score_kind, category, score, score_version)
select pg_temp.seed_id(8, n.i), s.score_kind, s.category,
       least(100, greatest(0, s.score + round((random() - 0.5) * 20)))::smallint, s.score_version
from seed_n n join tpl_location_scores s on s.loc_no = n.loc_no;

insert into area.location_metrics (location_id, metric, value)
select pg_temp.seed_id(8, n.i), m.metric, m.value
from seed_n n join tpl_location_metrics m on m.loc_no = n.loc_no;

insert into notice.notice_area_scores (
  notice_id, notice_version, location_id, kind, score, calculated_at, calculation_version, received_at)
select pg_temp.seed_id(1, n.i), 1, coalesce(t.loc, 'complex-a'), a.kind,
       least(100, greatest(0, a.score + (random() - 0.5) * 20))::numeric(5, 2), now(), a.calculation_version, now()
from seed_n n
join tpl_notices t on t.tpl = n.tpl
cross join (values ('LIVING'), ('COMMERCIAL')) k(kind)
cross join lateral (
  select s.kind, s.score, s.calculation_version from tpl_notice_area_scores s
  where s.kind = k.kind order by random() + n.i * 0 limit 1
) a;

\echo == 혜택 5천
-- ── 혜택 5천: 본보기를 돌려 쓰고 상태·지역·날짜는 새로 뽑아요
--    상태: 열림 55 · 예정 10 · 마감 15 · 숨김 20 (%) / 지역: 전국 20 · 시도 15 · 시군구 65 (%)
create temp table seed_b as
select x.i,
       1 + (x.i - 1) % (select count(*) from tpl_benefits) as tpl,
       case when x.s < 0.55 then 'OPEN' when x.s < 0.65 then 'UPCOMING' when x.s < 0.80 then 'CLOSED' else 'HIDDEN' end as status,
       case when x.g < 0.20 then 'NATIONWIDE' when x.g < 0.35 then 'SIDO' else 'SIGUNGU' end as scope,
       p.code as sigungu, p.sido, x.always, x.d1, x.d2,
       (5 + floor(x.c * 6))::int as want_conditions
from (
  select i, random() as r, random() as s, random() as g, random() < 0.1 as always,
         random() as d1, random() as d2, random() as c
  from generate_series(1, 5000) i
) x
cross join lateral (select * from seed_places p where p.lo <= x.r order by p.lo desc limit 1) p;

insert into benefit.benefits (
  id, raw_policy_id, source_code, provider, source_policy_id, source_version, field, category, title, agency,
  region_scope, region_names, region_codes, categories, detail_url, apply_url, summary, apply_start, apply_end,
  apply_always, business_start, business_end, schedule_text, how_to_apply, documents, contact, status, relevant,
  confidence, needs_review, review_reasons, has_document, extracted_at)
select pg_temp.seed_id(2, b.i), pg_temp.seed_id(9, b.i), 'perf-seed', t.provider, 'perf-seed-' || b.i, 1, t.field,
       t.category, t.title || ' #' || b.i, t.agency, b.scope,
       case b.scope when 'SIDO' then array[sd.name] when 'SIGUNGU' then array[sg.full_name] else '{}' end,
       case b.scope when 'SIDO' then array[b.sido] when 'SIGUNGU' then array[b.sigungu] else '{}' end,
       t.categories, t.detail_url, t.apply_url, t.summary,
       case b.status
         when 'OPEN' then current_date - (1 + b.d1 * 60)::int
         when 'UPCOMING' then current_date + (5 + b.d1 * 35)::int
         else current_date - (90 + b.d1 * 110)::int end,
       case when b.always then null else case b.status
         when 'OPEN' then current_date + (5 + b.d2 * 115)::int
         when 'UPCOMING' then current_date + (45 + b.d2 * 60)::int
         else current_date - (1 + b.d2 * 59)::int end end,
       b.always, t.business_start, t.business_end, t.schedule_text, t.how_to_apply, t.documents, t.contact, b.status,
       t.relevant, t.confidence, t.needs_review, t.review_reasons, t.has_document, now()
from seed_b b
join tpl_benefits t on t.tpl = b.tpl
left join tpl_regions sd on sd.code = b.sido
left join tpl_regions sg on sg.code = b.sigungu;

-- 조건: 본보기 혜택의 조건을 그대로 쓰고, 5~10개가 안 되면 운영 조건 전체에서 아직 없는 종류를 실제 빈도대로 더해요(AND)
--       본보기 조건이 10개를 넘으면 앞에서부터 원하는 수(5~10)만 써요
create temp table seed_bc as
select i, ordinal, type, value, grp, known, raw_text, evidence, confidence, origin
from (
  select b.i, b.want_conditions, c.*, row_number() over (partition by b.i order by c.ordinal) as k
  from seed_b b join tpl_benefit_conditions c on c.tpl = b.tpl
) c
where k <= want_conditions;

insert into seed_bc
select b.i, 1000 + x.j, x.type, x.value, 1000 + x.j, x.known, x.raw_text, x.evidence, x.confidence, x.origin
from (
  select b.i, b.want_conditions - coalesce(c.n, 0) as extra, coalesce(c.types, '{}') as types
  from seed_b b
  left join (select tpl, count(*) as n, array_agg(distinct type) as types from tpl_benefit_conditions group by tpl) c
    on c.tpl = b.tpl
) b
cross join lateral (
  select p.*, row_number() over () as j
  from (
    select * from tpl_benefit_conditions p
    where p.type <> all (b.types)
    order by random() + b.i * 0
    limit greatest(b.extra, 0)
  ) p
) x;

insert into benefit.benefit_conditions (id, benefit_id, ordinal, type, value, grp, known, raw_text, evidence, confidence, origin)
select pg_temp.seed_id(4, row_number() over (order by i, ordinal)), pg_temp.seed_id(2, i), ordinal, type, value, grp,
       known, raw_text, evidence, confidence, origin
from seed_bc;

insert into benefit.benefit_supports (id, benefit_id, ordinal, kind, amount_max, amount_per, months, rate_pct, raw_text, evidence)
select pg_temp.seed_id(10, row_number() over (order by b.i, s.ordinal)), pg_temp.seed_id(2, b.i), s.ordinal, s.kind,
       s.amount_max, s.amount_per, s.months, s.rate_pct, s.raw_text, s.evidence
from seed_b b join tpl_benefit_supports s on s.tpl = b.tpl;

\echo == 회원 10만
-- ── 회원 10만 (이메일 없음 → 메일·푸시가 나가지 않아요)
insert into account.users (id, nickname, role, created_at, updated_at, last_login_at)
select pg_temp.seed_id(3, u), 'perf-seed-' || u, 'USER',
       now() - random() * interval '180 days', now(), now() - random() * interval '30 days'
from generate_series(1, 100000) u;

\echo == 관심 약 50만
-- ── 관심: 회원마다 0~10개(평균 5), 공고 70 · 혜택 30 (%)
insert into account.favorites (user_id, target_type, target_id, created_at)
select pg_temp.seed_id(3, u),
       case when f.r < 0.7 then 'NOTICE' else 'BENEFIT' end,
       case when f.r < 0.7 then pg_temp.seed_id(1, 1 + floor(f.t * 10000)::bigint)
            else pg_temp.seed_id(2, 1 + floor(f.t * 5000)::bigint) end,
       now() - f.a * interval '60 days'
from generate_series(1, 100000) u
cross join lateral generate_series(1, round(random() * 10 + u * 0)::int) g
cross join lateral (select random() + g * 0 as r, random() + g * 0 as t, random() + g * 0 as a) f
on conflict do nothing;

\echo == 알림 약 200만 (1~2분)
-- ── 알림: 회원마다 0~40개(평균 20), 최근 60일(90일 정리 작업에 안 걸리게), 70%는 읽음
--    id = 5eed0005-<만든 시각 ms>-<번호>: 목록이 id desc라 시간 순서와 맞아요
insert into notification.notifications (
  id, user_id, type, target_type, target_id, title, body, link_path, dedup_key, read_at, created_at)
select format('5eed0005-%s-%s-%s-%s', substr(h, 1, 4), substr(h, 5, 4), substr(h, 9, 4), lpad(to_hex(k), 12, '0'))::uuid,
       pg_temp.seed_id(3, u), type,
       case when type = 'NEW_SUPPORT' then 'BENEFIT' else 'NOTICE' end,
       target,
       case type
         when 'NEW_NOTICE' then '내 조건에 맞는 새 공고가 올라왔어요'
         when 'DEADLINE_D3' then '관심 공고 마감 3일 전이에요'
         when 'DEADLINE_D1' then '관심 공고 마감 하루 전이에요'
         when 'NEW_SUPPORT' then '받을 수 있는 새 혜택이 있어요'
         when 'CORRECTED' then '관심 공고가 정정됐어요'
         else '관심 공고에 후속 안내가 올라왔어요' end,
       'perf-seed 알림 ' || k,
       case when type = 'NEW_SUPPORT' then '/benefits/' else '/notices/' end || target,
       'perf-seed:' || k,
       case when read then created + interval '1 hour' end,
       created
from (
  select u, u * 100 + g as k, created,
         lpad(to_hex((extract(epoch from created) * 1000)::bigint), 12, '0') as h,
         case when t < 0.40 then 'NEW_NOTICE' when t < 0.60 then 'DEADLINE_D3' when t < 0.75 then 'DEADLINE_D1'
              when t < 0.90 then 'NEW_SUPPORT' when t < 0.95 then 'CORRECTED' else 'FOLLOW_UP' end as type,
         case when t >= 0.75 and t < 0.90 then pg_temp.seed_id(2, 1 + floor(o * 5000)::bigint)
              else pg_temp.seed_id(1, 1 + floor(o * 10000)::bigint) end as target,
         read
  from (
    select u, g, now() - random() * interval '60 days' + g * interval '0' as created,
           random() + g * 0 as t, random() + g * 0 as o, random() + g * 0 < 0.7 as read
    from generate_series(1, 100000) u
    cross join lateral generate_series(1, round(random() * 40 + u * 0)::int) g
  ) y
) z;

commit;
\timing off

analyze notice.notices, notice.notice_versions, notice.notice_regions, notice.notice_area_scores,
        area.notice_locations, area.location_scores, area.location_metrics,
        benefit.benefits, benefit.benefit_conditions, benefit.benefit_supports,
        account.users, account.favorites, notification.notifications;
