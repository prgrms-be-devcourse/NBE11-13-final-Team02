-- 관리자 권한 변경 기록 (2026-10-27). 관리자 화면에서 회원을 관리자로 지정·해제할 때마다 한 줄. 누가 누구를 바꿨는지만 남겨요.
-- 대상 회원이 탈퇴하면 그 회원의 기록도 지워요(요구사항 3-16). 바꾼 관리자가 탈퇴하면 누가 바꿨는지만 비워요.
create table account.role_changes (
    id          bigint generated always as identity primary key,
    user_id     uuid        not null references account.users (id) on delete cascade,
    from_role   varchar(20) not null,
    to_role     varchar(20) not null check (to_role in ('USER', 'ADMIN')),
    changed_by  uuid        references account.users (id) on delete set null,
    changed_at  timestamptz not null default now()
);

create index role_changes_time_idx on account.role_changes (changed_at desc);
