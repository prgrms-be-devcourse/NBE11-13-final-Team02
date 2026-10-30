-- 모든 seed SQL 맨 앞에서 돌아요. seed.sh가 앞에 \set seed_target·seed_db를 붙여 보내요
-- 운영 api DB이거나 대상과 다른 DB면 여기서 멈춰요(ON_ERROR_STOP이라 뒤의 SQL은 실행되지 않아요)
set perf.target = :'seed_target';
set perf.db = :'seed_db';
do $$
declare
  target text := current_setting('perf.target');
  expected text := current_setting('perf.db');
  rds boolean := exists (select 1 from pg_roles where rolname = 'rds_superuser');
begin
  if current_database() = 'api' then
    raise exception '운영 api DB에는 더미를 넣지 않아요 (지금 DB: %)', current_database();
  end if;
  if current_database() <> expected then
    raise exception '대상 DB가 %가 아니에요 (지금 DB: %)', expected, current_database();
  end if;
  if target = 'local' and rds then
    raise exception 'local 대상인데 RDS에 붙었어요 (DB: %)', current_database();
  end if;
  if target = 'dev' and current_database() <> 'api_dev' then
    raise exception 'dev 대상은 api_dev만 돼요 (지금 DB: %)', current_database();
  end if;
  if target not in ('local', 'dev') then
    raise exception '대상은 local·dev만 돼요: %', target;
  end if;
end $$;

-- 더미 id: 5eed00TT-0000-7000-8000-<번호 12자리 16진수>. TT는 표마다 달라요
--   01 공고 · 02 혜택 · 03 회원 · 04 혜택 조건 · 05 알림 · 06 공고 원문 · 07 공고 이벤트 · 08 공고 위치 · 09 혜택 원문 · 10 혜택 지원 내용
create function pg_temp.seed_id(tt int, n bigint) returns uuid language sql immutable as
$$ select format('5eed%s-0000-7000-8000-%s', lpad(tt::text, 4, '0'), lpad(to_hex(n), 12, '0'))::uuid $$;
create function pg_temp.seed_lo(tt int) returns uuid language sql immutable as
$$ select format('5eed%s-0000-0000-0000-000000000000', lpad(tt::text, 4, '0'))::uuid $$;
create function pg_temp.seed_hi(tt int) returns uuid language sql immutable as
$$ select format('5eed%s-ffff-ffff-ffff-ffffffffffff', lpad(tt::text, 4, '0'))::uuid $$;
