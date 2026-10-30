-- 더미 지우기 (seed.sh down). guard.sql 다음에 돌아요. 자식 표부터 지워요(공고 쪽은 cascade가 없어요)
-- 테스트 중에 앱이 더미에 붙여 만든 행(진짜 회원의 더미 관심, 더미 공고 알림)도 같이 지워요
\timing on
begin;

delete from notification.notifications
where user_id between pg_temp.seed_lo(3) and pg_temp.seed_hi(3)
   or target_id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1)
   or target_id between pg_temp.seed_lo(2) and pg_temp.seed_hi(2);

delete from account.favorites
where (target_type = 'NOTICE' and target_id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1))
   or (target_type = 'BENEFIT' and target_id between pg_temp.seed_lo(2) and pg_temp.seed_hi(2));

-- 관심·관심 지역·알림 설정은 회원을 따라 cascade로 지워져요
delete from account.users where id between pg_temp.seed_lo(3) and pg_temp.seed_hi(3);

-- 생활권 점수·지표는 위치를 따라 cascade
delete from area.notice_locations where notice_id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1);
delete from notice.notice_area_scores where notice_id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1);
delete from notice.notice_regions where notice_id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1);
delete from notice.notice_versions where notice_id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1);
delete from notice.notices where id between pg_temp.seed_lo(1) and pg_temp.seed_hi(1);

-- 조건·지원 내용은 혜택을 따라 cascade
delete from benefit.benefits where id between pg_temp.seed_lo(2) and pg_temp.seed_hi(2);

commit;
\timing off

vacuum (analyze) notice.notices, notice.notice_versions, notice.notice_regions, notice.notice_area_scores,
        area.notice_locations, area.location_scores, area.location_metrics,
        benefit.benefits, benefit.benefit_conditions, benefit.benefit_supports,
        account.users, account.favorites, notification.notifications;
