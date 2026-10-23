# 알림 서버가 받는 이벤트 계약 (초안 v1)

알림 서버(`services/notification`)가 받는 이벤트는 **2개**예요. 둘 다 api가 보내요.

| 이벤트 | 보내는 곳 | 뜻 |
| --- | --- | --- |
| `notification.requested` | api `notification` 모듈 | "이 회원들에게 이 알림을 보내 주세요" |
| `user.withdrawn` | api `user` 모듈 | "이 회원이 탈퇴했어요. 알림 데이터를 지워 주세요" |

**역할 나누기 (2026-10-23 결정)**
- **api가 "누구에게, 무엇을"** 정해요. 공고·정책·관심 조건·관심 공고 데이터를 가진 곳이 api라서, 대상자 찾기와 마감 D-3·D-1 계산은 api `notification` 모듈이 해요 (CLAUDE.md 호출 방향 `notification → user·notice·benefit` 그대로)
- **알림 서버는 "어떻게, 언제"** 보낼지만 정해요. 회원 알림 설정·이메일 인증 확인, 조용한 시간, 묶음 메일, 중복 방지, SES 발송·재시도·반송 처리, 알림함·이력
- 알림 서버는 공고·관심 조건을 계산하거나 판정하지 않고, 복사본도 두지 않아요
- 상태: **초안**이에요. 확인 뒤 `contracts/events/notification.requested.v1.json`, `user.withdrawn.v1.json`으로 옮겨요

```
api
 ├ notice   ── NoticePublished·NoticeUpdated (내부 이벤트, 이미 있음)
 ├ benefit  ── BenefitPublished·BenefitUpdated (내부 이벤트, 만들 예정)
 ├ user     ── 관심 조건·관심 공고 조회 메서드
 └ notification 모듈 (api 안)
      대상자 찾기 → 500명씩 나눠 notification.requested → api 아웃박스 (같은 트랜잭션)
                                                         │ 릴레이
                                                         ▼
                                 mujuthaek.events ──▶ mujuthaek.notification ──▶ 알림 서버
                                                         설정 확인 → 예약(조용한 시간·묶음) → SES → 이력
```

## 1. 공통

### 봉투

다른 이벤트(`extraction.completed` 등)와 같아요.

| 칸 | 값 |
| --- | --- |
| `eventId` | UUID v7. 알림 서버는 `processed_events`로 한 번만 처리해요 |
| `eventType` | `notification.requested` · `user.withdrawn` (= RabbitMQ 라우팅 키) |
| `schemaVersion` | `1` |
| `occurredAt` | 사건이 일어난 시각 (ISO 8601) |
| `producer` | `api` |
| `traceId` | 비어 있어도 돼요 |
| `payload` | 아래 |

### 보내는 방법: api 아웃박스 (아직 없음, 먼저 필요)

담당자3이 만들고 담당자1이 리뷰해요 (2026-10-23 결정).
- 테이블 `common.outbox`: Python 쪽(`services/extraction/migrations/V202610151700__extraction_jobs_review_outbox.sql`의 `outbox`)과 같은 칸 — `event_id`, `event_type`, `schema_version`, `payload`(봉투 전체), `created_at`, `published_at`, `attempts`
- 적기: 공고 저장 등과 **같은 트랜잭션**에서 한 줄 (커밋 뒤에 적거나 RabbitMQ로 바로 보내면 그 사이 api가 죽을 때 사라져요)
- 릴레이: 1초마다 안 보낸 줄을 `mujuthaek.events`로 보내고, RabbitMQ가 받았다고 하면 `published_at` 기록

## 2. `notification.requested`

알림 한 종류를 회원 여러 명(최대 500명)에게 보내 달라는 요청이에요. 대상이 500명을 넘으면 api가 여러 이벤트로 나눠 보내요 (이벤트마다 `eventId`는 다르고 `dedupKey`는 같아요).

### payload

| 칸 | 모양 | 필수 | 뜻 |
| --- | --- | --- | --- |
| `type` | enum | ✅ | `NEW_NOTICE` 새 주거·사업공간 공고 · `NEW_SUPPORT` 새 지원 정책·창업지원 · `DEADLINE_D3`·`DEADLINE_D1` 관심 공고 마감 · `CORRECTED` 관심 공고 정정 · `FOLLOW_UP` 관심 공고 후속 안내 |
| `dedupKey` | string | ✅ | 같은 알림인지 가리는 열쇠 (회원 ID 빼고). 아래 표. 알림 서버는 `{userId}:{dedupKey}`로 한 회원에게 한 번만 만들어요 |
| `userIds` | uuid[] | ✅ 1~500개 | 받을 회원 |
| `target` | object | ✅ | 무엇에 대한 알림인지 |
| `target.type` | `NOTICE` \| `BENEFIT` | ✅ | 공고(notice 모듈) / 정책·창업지원·사업공간 모집(benefit 모듈) |
| `target.id` | uuid | ✅ | noticeId / benefitId |
| `target.title` | string | ✅ | 알림 문구에 써요 |
| `target.provider` | string \| null | ✅ | LH, SH, 기관 이름 |
| `target.applyEnd` | date \| null | ✅ | 접수 마감일. 문구에 써요 ("11월 8일 마감") |
| `target.linkPath` | string | ✅ | PWA 상세 화면 경로 (`/notices/{id}`, `/benefits/{id}`) |
| `correction` | object \| null | ✅ | `CORRECTED`일 때만 |
| `correction.changedFields` | string[] | ✅ | 사용자에게 알릴 바뀐 항목만 (아래 4장). 문구 "접수 일정·자격 조건이 바뀌었어요" |
| `followUp` | object \| null | ✅ | `FOLLOW_UP`일 때만 |
| `followUp.title` | string | ✅ | "강동구 행복주택 당첨자 발표" |
| `followUp.postedOn` | date \| null | ✅ | 게시일 |

### dedupKey 규칙 (api가 만들어요)

| type | dedupKey | 결과 |
| --- | --- | --- |
| NEW_NOTICE·NEW_SUPPORT | `{target.type}:{target.id}:NEW` | 관심 조건과 관심 공고에 둘 다 걸려도 1번 (api가 대상자를 합쳐서 보내도, 나눠 보내도) |
| DEADLINE_D3·D1 | `{target.type}:{target.id}:D3:{applyEnd}` | 정정으로 마감일이 바뀌면 새 날짜 기준으로 다시 1번 |
| CORRECTED | `{target.type}:{target.id}:CORRECTED:{version}` | 정정 회차마다 1번 |
| FOLLOW_UP | `{target.type}:{target.id}:FOLLOW_UP:{후속 글 ID}` | 후속 안내 글마다 1번 |

### 예시

```json
{
  "eventId": "0192b6f0-7c00-7000-8000-000000000101",
  "eventType": "notification.requested",
  "schemaVersion": 1,
  "occurredAt": "2026-10-23T14:10:00Z",
  "producer": "api",
  "payload": {
    "type": "NEW_NOTICE",
    "dedupKey": "NOTICE:0192b6f0-1111-7000-8000-000000000001:NEW",
    "userIds": ["0192b6f0-0000-7000-8000-0000000000a1", "0192b6f0-0000-7000-8000-0000000000a7"],
    "target": {
      "type": "NOTICE",
      "id": "0192b6f0-1111-7000-8000-000000000001",
      "title": "서울 강동구 행복주택 입주자 모집",
      "provider": "SH",
      "applyEnd": "2026-11-06",
      "linkPath": "/notices/0192b6f0-1111-7000-8000-000000000001"
    },
    "correction": null,
    "followUp": null
  }
}
```

### 알림 서버가 하는 일

`userIds`의 회원마다:
1. `notifications`에 한 줄 (`dedup_key = {userId}:{dedupKey}`, 이미 있으면 건너뜀). 문구(제목·본문)는 `type`별 템플릿으로 만들어요 — **설정과 상관없이 알림함에는 남아요**
2. 아래가 모두 맞으면 `deliveries`에 EMAIL 한 줄
   - `notification_settings.email_enabled`가 켜짐
   - `type`에 맞는 설정이 켜짐: NEW_NOTICE→`new_notice`, NEW_SUPPORT→`new_support`, DEADLINE_*→`deadline`, CORRECTED→`correction`, FOLLOW_UP→`follow_up`
   - `email_addresses`가 인증됨(`verified_at`) + `status = ACTIVE`
3. 보낼 시각(`scheduled_at`)
   - NEW_NOTICE·NEW_SUPPORT: 다음 묶음 시각(08:00 또는 18:00), `digest = true`
   - 나머지: 지금. 단 `quiet_enabled`이고 22:00~08:00이면 다음 08:00

알림 서버는 대상자가 맞는지 다시 확인하지 않아요 (api를 믿어요).

## 3. `user.withdrawn`

내부 이벤트 `UserWithdrawn`(#54, 이미 발행)을 api user 모듈이 그대로 밖으로 보내요.

| 칸 | 모양 | 필수 |
| --- | --- | --- |
| `userId` | uuid | ✅ |

알림 서버는 그 회원의 `notification_settings`·`email_addresses`·`notifications`(→ `deliveries` cascade)를 지워요 (탈퇴 후 데이터 0건).
탈퇴 직전에 만들어진 `notification.requested`가 늦게 도착하면 알림함 줄이 다시 생길 수 있어요. 설정·이메일이 없어서 발송은 안 되고, 90일 정리 때 지워져요.

## 4. api `notification` 모듈이 하는 일 (보내는 쪽 안내)

| 언제 | 대상자 | type |
| --- | --- | --- |
| `NoticePublished` (reprocess = false) | 주거(`HOUSING`)면 `housing_interests`, 사업공간(`BUSINESS_SPACE`)면 `business_interests`가 맞는 회원 + 그 공고를 관심 공고로 둔 회원 | NEW_NOTICE |
| `BenefitPublished` (reprocess = false) | `BENEFIT`면 `support_interests`, `STARTUP_SUPPORT`·`SPACE_RECRUITMENT`면 `business_interests`가 맞는 회원 | NEW_SUPPORT |
| `NoticeUpdated`·`BenefitUpdated` (reprocess = false), 알릴 항목이 바뀜 | 관심 공고로 둔 회원 | CORRECTED |
| `NoticeUpdated`에 `followUps` | 관심 공고로 둔 회원 | FOLLOW_UP |
| 매일 09:00 스케줄러 | 마감일이 3일 뒤·1일 뒤인 열린 공고·정책을 관심 공고로 둔 회원 | DEADLINE_D3·D1 |

- 관심 조건·관심 공고는 user 모듈의 service 조회 메서드로, 공고·정책 값은 notice·benefit의 service 조회 메서드로 가져와요 (다른 모듈 테이블을 직접 읽지 않아요)
- 대상자 찾기와 아웃박스 기록은 **공고 저장과 같은 트랜잭션**에서 해요 (내부 이벤트를 `@EventListener` 또는 `@TransactionalEventListener(phase = BEFORE_COMMIT)`로 받아요)

### 알릴 정정 항목 (`correction.changedFields`)

`NoticeUpdated.changedFields`(notice 모듈 `NoticeContent.SECTIONS` + 2개) 중 아래 ✅만 넣어요. 하나도 없으면 CORRECTED를 보내지 않아요.

| 값 | 뜻 | 알림 |
| --- | --- | --- |
| `notice` | 제목·기관·공고일 등 기본 정보 | ✅ |
| `schedules` | 일정 (접수 기간 등) | ✅ |
| `supplyGroups` | 공급 호수·가격 | ✅ |
| `eligibility` | 자격 조건 | ✅ |
| `selectionRules` | 선정 방법·순위 | ✅ |
| `requiredDocuments` | 제출 서류 | ✅ |
| `locations` | 위치 | ✅ |
| `guidance` | 안내 문구 | ❌ |
| `followUps` | 후속 안내가 붙음 | CORRECTED가 아니라 FOLLOW_UP으로 |
| `sources` | 다른 출처 공고와 합치기·되돌리기 | ❌ |

### 지역 맞추기 규칙

회원 관심 지역 `R`(5자리), 공고·정책 지역 목록 `T`:
- 공고·정책이 전국(`nationwide` 또는 `regionScope = NATIONWIDE`) → 회원 `include_nationwide`가 true면 맞음
- `T`에 `R`이 있음 → 맞음
- `T`에 `R`의 시도 전체(`R` 앞 2자리 + `000`)가 있음 → 맞음 (경기도 정책 → 성남 회원)
- 회원이 시도 전체(`11000`)를 골랐고 `T`에 같은 시도 시군구가 있음 → 맞음 (주거 공고만)
- `regionScope = UNKNOWN` → 안 맞음 (엉뚱한 알림 방지)

benefit의 시도 코드가 2자리(`11`)면 맞출 때 `11000`으로 바꿔 봐요.

## 5. 결정과 남은 것

결정 (2026-10-23)

| 결정 | 내용 |
| --- | --- |
| 대상자 계산 위치 | **api** `notification` 모듈. 알림 서버는 발송만 |
| 이벤트 | `notification.requested`, `user.withdrawn` 2개 (예전 초안의 `notice.*`·`benefit.*`·`user.interest.changed`·`user.favorites.changed`는 쓰지 않아요) |
| api 아웃박스·릴레이 | 담당자3이 만들고 담당자1이 리뷰 |
| 후속 안내 | 알려요 (FOLLOW_UP) |
| 알림 문구용 요약(`summaryText`) | 넣지 않아요. 메일은 제목·마감일·상세 링크만 |

남은 것
1. `BenefitUpdated` 내부 이벤트와 정책 정정 알림 항목 (benefit 저장을 만들 때)
2. 후속 안내 글 제목·ID를 notice 모듈 조회 메서드로 받을 수 있는지 (notice 담당 확인)
