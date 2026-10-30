-- 표별 전체 수와 더미 수(seed.sh count, up·down 끝에도)
select t.tbl as "표", t.total as "전체", t.seed as "더미"
from (
  select 1 o, 'notice.notices' tbl, (select count(*) from notice.notices) total,
         (select count(*) from notice.notices where id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1)) seed
  union all select 2, 'notice.notice_versions', (select count(*) from notice.notice_versions),
         (select count(*) from notice.notice_versions where notice_id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1))
  union all select 3, 'notice.notice_regions', (select count(*) from notice.notice_regions),
         (select count(*) from notice.notice_regions where notice_id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1))
  union all select 4, 'notice.notice_area_scores', (select count(*) from notice.notice_area_scores),
         (select count(*) from notice.notice_area_scores where notice_id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1))
  union all select 5, 'area.notice_locations', (select count(*) from area.notice_locations),
         (select count(*) from area.notice_locations where location_id between pg_temp.seed_lo(8) and pg_temp.seed_hi(8))
  union all select 6, 'area.location_scores', (select count(*) from area.location_scores),
         (select count(*) from area.location_scores where location_id between pg_temp.seed_lo(8) and pg_temp.seed_hi(8))
  union all select 7, 'area.location_metrics', (select count(*) from area.location_metrics),
         (select count(*) from area.location_metrics where location_id between pg_temp.seed_lo(8) and pg_temp.seed_hi(8))
  union all select 8, 'benefit.benefits', (select count(*) from benefit.benefits),
         (select count(*) from benefit.benefits where id between pg_temp.seed_lo(2) and pg_temp.seed_hi(2))
  union all select 9, 'benefit.benefit_conditions', (select count(*) from benefit.benefit_conditions),
         (select count(*) from benefit.benefit_conditions where benefit_id between pg_temp.seed_lo(2) and pg_temp.seed_hi(2))
  union all select 10, 'benefit.benefit_supports', (select count(*) from benefit.benefit_supports),
         (select count(*) from benefit.benefit_supports where benefit_id between pg_temp.seed_lo(2) and pg_temp.seed_hi(2))
  union all select 11, 'account.users', (select count(*) from account.users),
         (select count(*) from account.users where id between pg_temp.seed_lo(3) and pg_temp.seed_hi(3))
  union all select 12, 'account.favorites', (select count(*) from account.favorites),
         (select count(*) from account.favorites where user_id between pg_temp.seed_lo(3) and pg_temp.seed_hi(3))
  union all select 13, 'notification.notifications', (select count(*) from notification.notifications),
         (select count(*) from notification.notifications where user_id between pg_temp.seed_lo(3) and pg_temp.seed_hi(3))
) t
order by t.o;
