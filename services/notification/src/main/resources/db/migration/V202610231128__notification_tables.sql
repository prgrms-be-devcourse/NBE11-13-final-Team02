-- 알림 설정·이메일·알림·발송 (설계: contracts/schemas/notification-tables.draft.md)
-- 알림 서버는 api가 정한 대상(notification.requested의 userIds)에게 보내기만 해요. 공고·관심 조건 복사본은 두지 않아요
-- 회원 소득·자산·나이는 받지도 저장하지도 않아요. user_id는 api 회원 ID라 다른 DB여서 FK를 걸지 않아요

-- 알림 종류·채널 켜기·끄기. 줄이 없는 회원은 알림함에만 남고 발송하지 않아요. 조용한 시간은 22:00~08:00 고정이라 켜기·끄기만 둬요
create table notification_settings (
    user_id       uuid        primary key,
    email_enabled boolean     not null default false,   -- 인증된 이메일이 있어야 켤 수 있어요
    push_enabled  boolean     not null default false,   -- 웹 푸시 (다음 단계)
    new_notice    boolean     not null default true,    -- NEW_NOTICE: 새 주거·사업공간 공고
    new_support   boolean     not null default true,    -- NEW_SUPPORT: 새 지원 정책·창업지원
    deadline      boolean     not null default true,    -- DEADLINE_D3·D1: 관심 공고 마감
    correction    boolean     not null default true,    -- CORRECTED: 관심 공고 정정
    follow_up     boolean     not null default true,    -- FOLLOW_UP: 관심 공고 후속 안내 (서류심사·당첨자 발표 등)
    quiet_enabled boolean     not null default false,   -- 22:00~08:00 알림을 08:00에 보내기
    updated_at    timestamptz not null default now()
);

-- 알림 받을 이메일 (회원당 1개). 입력 화면에서 수집·수신 동의를 받고, 인증 코드를 확인해야 공고 알림을 보내요
-- 코드·수신 거부 토큰은 원문 없이 SHA-256 해시만 둬요. 반송·스팸 신고(SES → SNS)가 오면 status를 바꾸고 다시 보내지 않아요
create table email_addresses (
    user_id                uuid        primary key,
    email                  text        not null,
    consent_version        text        not null,
    consented_at           timestamptz not null,
    verify_code_hash       text,
    verify_expires_at      timestamptz,
    verify_attempts        int         not null default 0,   -- 5번 틀리면 코드 무효
    verified_at            timestamptz,
    status                 text        not null default 'ACTIVE' check (status in ('ACTIVE', 'BOUNCED', 'COMPLAINED')),
    unsubscribe_token_hash text        not null unique,
    updated_at             timestamptz not null default now()
);

-- 회원에게 보낼(보낸) 알림 1건. 알림함·발송 이력. 문구에 필요한 값(제목·마감일·링크)은 요청 이벤트에서 받아 여기 남겨요
-- dedup_key = {user_id}:{요청의 dedupKey}. 같은 요청이 다시 와도(재전송·중복 발행) 알림을 두 번 만들지 않아요
-- 90일 지난 알림은 매일 지워요 (deliveries도 같이)
create table notifications (
    id          uuid        primary key,
    user_id     uuid        not null,
    type        text        not null check (type in ('NEW_NOTICE', 'NEW_SUPPORT', 'DEADLINE_D3', 'DEADLINE_D1', 'CORRECTED', 'FOLLOW_UP')),
    target_type text        not null check (target_type in ('NOTICE', 'BENEFIT')),
    target_id   uuid        not null,
    dedup_key   text        not null unique,
    title       text        not null,
    body        text        not null,
    link_path   text        not null,                    -- PWA 상세 화면 경로 (/notices/{id} …)
    created_at  timestamptz not null default now(),
    read_at     timestamptz
);
create index notifications_user_idx on notifications (user_id, created_at desc);
create index notifications_created_idx on notifications (created_at);  -- 90일 지난 알림 지우기

-- 채널별 발송 1건. 워커가 scheduled_at이 지난 PENDING을 for update skip locked로 가져가요
-- 조용한 시간이면 scheduled_at을 다음 08:00(Asia/Seoul)으로, 재시도면 뒤로 미뤄요. 오류에 이메일 주소는 넣지 않아요
-- 새 공고·새 정책 이메일(digest)은 하루 두 번(08:00·18:00)으로 scheduled_at을 맞추고, 같은 회원·같은 시각 건을 메일 1통으로 묶어 보내요
-- 마감·정정·후속 안내는 묶지 않고 바로 보내요
create table deliveries (
    id                  uuid        primary key,
    notification_id     uuid        not null references notifications (id) on delete cascade,
    channel             text        not null check (channel in ('EMAIL', 'PUSH')),
    digest              boolean     not null default false,   -- 묶음 메일로 보낼 건
    status              text        not null default 'PENDING'
                                    check (status in ('PENDING', 'SENT', 'FAILED', 'SKIPPED', 'CANCELLED')),
    scheduled_at        timestamptz not null,
    attempts            int         not null default 0,
    last_error          text,
    provider_message_id text,                            -- SES MessageId (반송 알림과 맞춰요). 묶음 메일은 같은 값
    sent_at             timestamptz
);
create index deliveries_pending_idx on deliveries (scheduled_at) where status = 'PENDING';
