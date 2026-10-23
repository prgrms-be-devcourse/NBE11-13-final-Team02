# 알림 서버 테이블 (초안 v1)

알림 서버(`services/notification`) DB `notification`의 테이블이에요. 첫 채널은 **이메일(AWS SES)**, 웹 푸시는 그다음이에요.

- 저장하는 곳: notification DB (스키마 public), 담당자3
- 받는 이벤트: `notification.requested`, `user.withdrawn` ([notification-events.draft.md](notification-events.draft.md))
- 마이그레이션: `V202610221630__notification_init.sql`(`processed_events`), `V202610231128__notification_tables.sql`(나머지 4개). 커밋해서 적용된 뒤에는 고치지 말고 새 파일을 만들어요
- **알림 서버는 api가 정한 대상에게 보내기만 해요.** 대상자 찾기·마감일 계산은 api `notification` 모듈이 하고, 알림 서버에는 공고·관심 조건 복사본이 없어요 (2026-10-23 결정)

## 1. 테이블 구성

```
[회원이 PWA에서 알림 서버 API로 직접 바꿔요]
notification_settings   알림 종류·채널 켜기·끄기, 조용한 시간
email_addresses         알림 받을 이메일, 인증·수신 동의·반송 상태

[notification.requested를 받아 알림 서버가 만들어요]
notifications           회원에게 보낼(보낸) 알림 1건 = 1줄. 알림함·발송 이력
deliveries              채널별 발송 1건 = 1줄. 조용한 시간·묶음·재시도·결과

processed_events        받은 이벤트 장부
```

## 2. 회원 설정

### 2-1. `notification_settings`

| 칸 | 모양 | 기본 | 뜻 |
| --- | --- | --- | --- |
| `user_id` | uuid PK | | api `users.id` (다른 DB라 FK 없음) |
| `email_enabled` | boolean | false | 이메일로 받기. 인증된 이메일이 있어야 켤 수 있어요 |
| `push_enabled` | boolean | false | 웹 푸시로 받기 (다음 단계) |
| `new_notice` | boolean | true | `NEW_NOTICE` 새 주거·사업공간 공고 |
| `new_support` | boolean | true | `NEW_SUPPORT` 새 지원 정책·창업지원 |
| `deadline` | boolean | true | `DEADLINE_D3`·`D1` 관심 공고 마감 |
| `correction` | boolean | true | `CORRECTED` 관심 공고 정정 |
| `follow_up` | boolean | true | `FOLLOW_UP` 관심 공고 후속 안내 (서류심사·당첨자 발표·계약 안내 등) |
| `quiet_enabled` | boolean | false | 22:00~08:00 알림을 08:00에 보내기. 시간은 고정이라 시작·끝 칸은 없어요 |
| `updated_at` | timestamptz | now() | |

설정 줄이 없는 회원은 알림함에만 남고 발송하지 않아요.

### 2-2. `email_addresses`

| 칸 | 모양 | 뜻 |
| --- | --- | --- |
| `user_id` | uuid PK | 회원당 이메일 1개 |
| `email` | text | 알림 받을 주소 |
| `consent_version` | text | 이메일 수집·알림 수신 동의 문구 버전. 입력 화면에서 동의해야 저장돼요 |
| `consented_at` | timestamptz | 동의 시각 |
| `verify_code_hash` | text \| null | 인증 코드(6자리)의 SHA-256. 원문은 메일에만 있어요 |
| `verify_expires_at` | timestamptz \| null | 10분 |
| `verify_attempts` | int | 틀린 횟수. 5번이면 코드 무효 (코드 맞히기 방지) |
| `verified_at` | timestamptz \| null | 인증 전에는 공고 알림을 보내지 않아요 |
| `status` | text | `ACTIVE` · `BOUNCED` 없는 주소(영구 반송) · `COMPLAINED` 스팸 신고. ACTIVE만 보내요 |
| `unsubscribe_token_hash` | text | 메일 아래 "수신 거부" 링크용. 로그인 없이 한 번 누르면 `email_enabled = false` |
| `updated_at` | timestamptz | |

- 이메일을 바꾸면 `verified_at`·`status`를 지우고 다시 인증해요
- SES는 반송·스팸 신고가 많으면 계정을 정지해요. SES → SNS 알림(Bounce·Complaint)을 받아 `status`를 바꾸고, 그 주소로 다시 보내지 않아요
- 이메일 주소는 로그에 남기지 않아요 (user_id만)

## 3. 알림

### 3-1. `notifications`

| 칸 | 모양 | 뜻 |
| --- | --- | --- |
| `id` | uuid PK | UUID v7 |
| `user_id` | uuid | 요청의 `userIds` 중 한 명 |
| `type` | text | `NEW_NOTICE` · `NEW_SUPPORT` · `DEADLINE_D3` · `DEADLINE_D1` · `CORRECTED` · `FOLLOW_UP` |
| `target_type`, `target_id` | | 요청의 `target.type`·`target.id` |
| `dedup_key` | text UNIQUE | `{user_id}:{요청의 dedupKey}`. 같은 알림을 두 번 만들지 않아요 |
| `title`, `body` | text | `type`별 템플릿 + 요청 값(제목·마감일·정정 항목·후속 글 제목)으로 만든 문구 |
| `link_path` | text | 요청의 `target.linkPath`. 메일·알림함에서 누르면 가는 PWA 화면 |
| `created_at` | timestamptz | |
| `read_at` | timestamptz \| null | 알림함에서 읽음 |

- `dedupKey`는 api가 정해요 ([notification-events.draft.md](notification-events.draft.md) 2장). 예: `NOTICE:X:NEW`, `NOTICE:X:D3:2026-11-08`, `NOTICE:X:CORRECTED:2`, `NOTICE:X:FOLLOW_UP:{후속 글 ID}`
- 회원 설정과 상관없이 알림함에는 남아요. 발송 여부는 `deliveries`가 정해요
- 이력에는 공고 ID·제목·종류만 남겨요. 판정 결과 같은 개인 조건은 받지도 남기지도 않아요
- **보관: 90일.** 매일 새벽 `created_at`이 90일 지난 알림을 지워요 (`deliveries`는 cascade). api는 같은 알림을 다시 요청하지 않아요(공개는 한 번, 마감일은 지남, 정정은 버전별)

### 3-2. `deliveries`

| 칸 | 모양 | 뜻 |
| --- | --- | --- |
| `id` | uuid PK | |
| `notification_id` | uuid FK → notifications (cascade) | |
| `channel` | `EMAIL` \| `PUSH` | |
| `digest` | boolean | 묶음 메일로 보낼 건 (새 공고·새 정책 이메일) |
| `status` | text | `PENDING` → `SENT` / `FAILED`(재시도 다 씀) / `SKIPPED`(보낼 때 이메일이 꺼짐·반송 상태) / `CANCELLED`(탈퇴) |
| `scheduled_at` | timestamptz | 보낼 시각 |
| `attempts` | int | |
| `last_error` | text \| null | SES 오류 코드 (주소는 넣지 않아요) |
| `provider_message_id` | text \| null | SES MessageId. 반송 알림(SNS)이 오면 어떤 발송인지 찾아요. 묶음 메일은 같은 값 |
| `sent_at` | timestamptz \| null | |

**발송 줄을 만드는 조건** (`notification.requested`를 받을 때 회원마다)
1. `notification_settings.email_enabled`가 켜짐
2. `type`에 맞는 설정이 켜짐 (2-1 표)
3. `email_addresses.verified_at`이 있고 `status = ACTIVE`

**보낼 시각 (`scheduled_at`)**
- `NEW_NOTICE`·`NEW_SUPPORT`: `digest = true`, 다음 묶음 시각(08:00 또는 18:00, Asia/Seoul). 두 시각 모두 조용한 시간 밖이에요
- 나머지: 지금. `quiet_enabled`이고 22:00~08:00이면 다음 08:00
- 재시도: 실패하면 `attempts`를 올리고 뒤로 미뤄요 (1분 → 5분 → 30분, 3번 넘으면 FAILED)

**발송 워커**
- `select … where status = 'PENDING' and scheduled_at <= now() order by scheduled_at limit 100 for update skip locked`로 가져가요 (서버 여러 대여도 같은 건을 두 번 안 보내요)
- `digest = true`는 같은 회원·같은 `scheduled_at` 건을 메일 1통("오늘 관심 조건에 맞는 새 공고 8건")으로 보내고 모두 `SENT` + 같은 `provider_message_id`
- 묶는 이유: 메일 여러 통에 지친 사용자의 스팸 신고가 쌓이면 SES 발송이 정지돼 모든 회원의 메일이 멈춰요. 웹 푸시는 묶지 않아요
- SES 초당 발송 한도(샌드박스 1통, 해제 후 계정마다 다름)를 넘지 않게 속도를 맞춰요

## 4. 결정 (2026-10-23)

| 결정 | 내용 |
| --- | --- |
| 계산은 api, 발송은 알림 서버 | 공고·관심 조건 복사본(`targets`·`user_interests`·`user_favorites`·`user_favorites_versions`)은 만들지 않아요 |
| 알림 이력 보관 | 90일 (3-1) |
| 새 공고 메일 묶음 | 새 공고·새 정책 이메일은 하루 두 번(08:00·18:00) 묶음, 마감·정정·후속 안내는 바로 (3-2) |
| 후속 안내 알림 | `FOLLOW_UP`, 설정 `follow_up`으로 끌 수 있음 |
| 웹 푸시 `push_subscriptions` | 이메일이 끝난 뒤 추가 |
