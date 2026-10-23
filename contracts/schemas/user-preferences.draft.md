# 회원 관심 데이터 테이블 (초안 v0)

회원이 고른 관심 공고·관심 조건·약관 동의를 저장하는 api 테이블이에요. **알림 대상자는 api `notification` 모듈이 이 테이블로 찾아요** (user 모듈 service 조회 메서드). 알림 서버(`services/notification`)에는 복사본을 보내지 않고, 찾은 대상을 `notification.requested`로 보내요 (2026-10-23 결정).

- 저장하는 곳: api DB, 스키마 `account`, 모듈 `user` (담당자3)
- 보내는 이벤트: `user.withdrawn`만 ([notification-events.draft.md](notification-events.draft.md) 3장). 관심 조건·관심 공고가 바뀌어도 이벤트를 보내지 않아요
- 알림 설정·알림 받을 이메일은 여기 두지 않아요. 알림 서버만 쓰는 데이터라 알림 서버 DB에 둬요 ([notification-tables.draft.md](notification-tables.draft.md))
- 상태: **초안**이에요. 확정하면 api 마이그레이션 `V<yyyyMMddHHmm>__user_preferences.sql`로 옮겨요

## 1. 지금 있는 테이블 (#54 소셜 로그인, 바꾸지 않아요)

| 테이블 | 하는 일 |
| --- | --- |
| `account.users` | 회원 (id, nickname, role, last_login_at) |
| `account.social_accounts` | 회원이 로그인에 쓰는 소셜 계정 (KAKAO·NAVER·GOOGLE). 한 회원이 여러 개를 가질 수 있어요 |
| `account.refresh_tokens` | 로그인 유지 토큰 (해시만) |
| `account.withdrawal_tickets` | 탈퇴 재인증 티켓 |

탈퇴하면 `users` 행을 지우고(hard delete) 아래 새 테이블도 `on delete cascade`로 같이 지워져요. 그래서 `status`·`deleted_at` 칸은 두지 않아요. 서버 세션 테이블도 두지 않아요 (JWT + refresh token).

## 2. 새 테이블

```
account.users (있음)
 ├─ favorites           관심 공고·관심 정책 (여러 줄)
 ├─ housing_interests   주거 공고 관심 조건 (1줄)
 ├─ business_interests  사업공간·창업지원 관심 조건 (1줄)
 ├─ support_interests   주거·이주 지원 정책 관심 조건 (1줄)
 └─ consents            약관 동의 이력 (여러 줄)
```

한 회원에게 여러 개가 있으면 따로 줄을 두고(favorites, consents), 한 회원에게 하나뿐인 조건 묶음은 배열 칸으로 한 줄에 둬요(*_interests).

### 2-1. `favorites` (관심 공고·관심 정책)

| 칸 | 모양 | 필수 | 뜻 |
| --- | --- | --- | --- |
| `user_id` | uuid | ✅ | `users.id` (cascade) |
| `target_type` | text | ✅ | `NOTICE` 주거·사업공간 공고(notice 모듈) · `BENEFIT` 지원 정책·창업지원·사업공간 모집(benefit 모듈) |
| `target_id` | uuid | ✅ | notice 또는 benefit의 ID. **FK를 걸지 않아요** (다른 모듈 테이블). 저장할 때 그 모듈의 service 조회 메서드로 있는지 확인해요 |
| `created_at` | timestamptz | ✅ | 저장한 시각. "최근 저장한 순" 목록 |

- 키: `primary key (user_id, target_type, target_id)` → 같은 공고를 두 번 저장 못 해요
- 인덱스: `(target_type, target_id)` → "이 공고를 저장한 사람" (정정·후속 안내·마감 알림 대상자 찾기)
- 한 사람당 개수 상한: 200개. 넘으면 "관심 공고는 200개까지 저장할 수 있어요"로 거절해요 (관심 목록 화면과 마감 알림 양을 적당히 두려고요)
- 자동 정리: 매일 새벽 **마감 후 30일 지난** 관심 공고를 지워요. 마감된 공고는 알림 대상도 아니라 지워도 손해가 없고, 상한에 거의 닿지 않게 돼요. 마감일은 notice·benefit의 service 조회 메서드로 확인해요

### 2-2. `housing_interests` (주거 공고 관심 조건)

새 주거 공고(`NoticePublished`, field = `HOUSING`)가 올라오면 이 조건이 맞는 사람에게 알려요.

| 칸 | 모양 | 필수 | 뜻 |
| --- | --- | --- | --- |
| `user_id` | uuid | ✅ PK | `users.id` (cascade) |
| `region_codes` | text[] | ✅ (빈 목록 가능) | **살고 싶은 곳.** 시군구 5자리, 시도 전체는 `xx000` (예: `11740` 강동구, `11000` 서울 전체). 공고 `regionCodes`와 같은 코드표(`common.regions`) |
| `include_nationwide` | boolean | ✅ 기본 true | 전국 대상 공고(`nationwide`)도 받을지 |
| `notice_types` | text[] | ✅ (빈 목록 = 전부) | 공급 유형. `standard-notice.v1.json`의 `supplyTypeCode` 값 (`HAPPY_HOUSING`, `NATIONAL_RENTAL`, `PURCHASE_RENTAL` …) |
| `updated_at` | timestamptz | ✅ | |

**제안 표의 `target_groups`(청년·신혼 …)는 v1에서 빼요.**
- 공고 쪽 대상 계층(`notice-common.v1.json`의 `targetGroups`)이 정해진 값이 아니라 원문 글자예요. 그래서 "청년"을 골라도 공고와 맞춰 볼 수 없어요.
- "나는 청년이다"로 쓰이면 나이를 대신 저장하는 셈이라 CLAUDE.md 규칙에도 걸릴 수 있어요.
- 자격은 사용자가 공고를 열 때 eligibility로 판정해요.

### 2-3. `business_interests` (사업공간·창업지원 관심 조건)

새 사업공간 공고(`NoticePublished`, field = `BUSINESS_SPACE`)와 창업지원·사업공간 모집 정책(`BenefitPublished`, field = `STARTUP_SUPPORT`·`SPACE_RECRUITMENT`)에 써요.

| 칸 | 모양 | 필수 | 뜻 |
| --- | --- | --- | --- |
| `user_id` | uuid | ✅ PK | `users.id` (cascade) |
| `region_codes` | text[] | ✅ (빈 목록 가능) | 사업장을 둘(둔) 곳. 시군구 5자리 / `xx000` |
| `include_nationwide` | boolean | ✅ 기본 true | 전국 대상도 받을지 |
| `industry_codes` | text[] | ✅ (빈 목록 = 전부) | 한국표준산업분류 대분류 `A`~`U` (`benefit-condition-types.draft.md`의 INDUSTRY) |
| `startup_stages` | text[] | ✅ (빈 목록 = 전부) | `PRE_STARTUP` 예비창업 · `OPERATING` 영업 중 (STARTUP_STAGE. `CLOSING`은 고르게 하지 않아요) |
| `updated_at` | timestamptz | ✅ | |

- 업종·단계는 정책에 그 조건(`INDUSTRY`·`STARTUP_STAGE`)이 있을 때만 걸러요. 정책에 업종 제한이 없으면 업종을 고른 사람에게도 가요.

### 2-4. `support_interests` (주거·이주 지원 정책 관심 조건)

새 지원 정책(`BenefitPublished`, field = `BENEFIT`)에 써요. 주거 공고 지역(살고 싶은 곳)과 따로 두는 이유는 **지원 정책은 대부분 지금 사는 곳 기준**이라서예요 (서울시 청년 월세 지원은 서울 거주자만).

| 칸 | 모양 | 필수 | 뜻 |
| --- | --- | --- | --- |
| `user_id` | uuid | ✅ PK | `users.id` (cascade) |
| `region_codes` | text[] | ✅ (빈 목록 가능) | **혜택 관심 지역** (보통 사는 곳). 시군구 5자리. 주소가 아니라 시군구까지만 받아요 |
| `include_nationwide` | boolean | ✅ 기본 true | 전국 정책도 받을지 |
| `support_kinds` | text[] | ✅ (빈 목록 = 전부) | 지원 종류. `benefits`의 지원 내용 `kind` 중 주거 쪽: `RENT` 월세 · `DEPOSIT_LOAN` 전세·보증금 대출 · `PURCHASE_LOAN` 구입 대출 · `HOUSING_SUPPLY` 주택 공급 · `MOVE_IN_GRANT` 이사비 · `CASH` 현금 |
| `updated_at` | timestamptz | ✅ | |

- 정책 분류(`benefits.categories`)는 출처마다 이름이 달라서(보조금24 "주거·자립", 온통청년 "주거 > 전월세 …") 선택지로 쓰지 않아요. AI 추출로 통일된 `kind`를 써요.
- 화면에서 처음엔 `housing_interests.region_codes`를 기본값으로 채워 줘요.

### 2-5. `consents` (약관 동의 이력)

| 칸 | 모양 | 필수 | 뜻 |
| --- | --- | --- | --- |
| `id` | uuid | ✅ PK | UUID v7 |
| `user_id` | uuid | ✅ | `users.id` (cascade) |
| `kind` | text | ✅ | `TERMS` 이용약관 · `PRIVACY` 개인정보 수집·이용 |
| `version` | text | ✅ | 약관 버전 (예: `2026-10-23`) |
| `agreed` | boolean | ✅ | true 동의 / false 철회. 바꿀 때마다 새 줄을 넣어요 (지금 상태 = kind별 가장 최근 줄) |
| `created_at` | timestamptz | ✅ | |

- 지금 가입 흐름(첫 소셜 로그인 = 가입)에 동의 단계가 없어요. 첫 로그인 뒤 "약관 동의" 화면을 거치게 하고, 동의 전에는 관심 저장 API를 막아요.
- 이메일 수집·알림 수신 동의는 이메일을 입력하는 알림 서버 화면에서 받아 알림 서버에 저장해요 (`notification-tables.draft.md`의 `email_addresses.consent_version`). 그래야 api ↔ 알림 서버 동의 이벤트가 필요 없어요.

## 3. 마이그레이션 초안

```sql
-- V<yyyyMMddHHmm>__user_preferences.sql
-- 관심 공고·관심 조건·약관 동의 (user 모듈, 스키마 account). 소득·자산·나이는 저장하지 않아요 (CLAUDE.md)

create table account.favorites (
    user_id     uuid        not null references account.users (id) on delete cascade,
    target_type varchar(20) not null check (target_type in ('NOTICE', 'BENEFIT')),
    target_id   uuid        not null,                 -- notice·benefit ID (다른 모듈이라 FK 없음)
    created_at  timestamptz not null default now(),
    primary key (user_id, target_type, target_id)
);
create index favorites_target_idx on account.favorites (target_type, target_id);

create table account.housing_interests (
    user_id            uuid        primary key references account.users (id) on delete cascade,
    region_codes       text[]      not null default '{}',   -- 시군구 5자리, 시도 전체 xx000
    include_nationwide boolean     not null default true,
    notice_types       text[]      not null default '{}',   -- supplyTypeCode. 빈 목록 = 전부
    updated_at         timestamptz not null default now()
);
create index housing_interests_region_idx on account.housing_interests using gin (region_codes);  -- 대상자 찾기

create table account.business_interests (
    user_id            uuid        primary key references account.users (id) on delete cascade,
    region_codes       text[]      not null default '{}',
    include_nationwide boolean     not null default true,
    industry_codes     text[]      not null default '{}',   -- 표준산업분류 대분류 A~U. 빈 목록 = 전부
    startup_stages     text[]      not null default '{}',   -- PRE_STARTUP, OPERATING. 빈 목록 = 전부
    updated_at         timestamptz not null default now()
);
create index business_interests_region_idx on account.business_interests using gin (region_codes);

create table account.support_interests (
    user_id            uuid        primary key references account.users (id) on delete cascade,
    region_codes       text[]      not null default '{}',   -- 혜택 관심 지역(보통 사는 곳), 시군구 5자리
    include_nationwide boolean     not null default true,
    support_kinds      text[]      not null default '{}',   -- RENT, DEPOSIT_LOAN … 빈 목록 = 전부
    updated_at         timestamptz not null default now()
);
create index support_interests_region_idx on account.support_interests using gin (region_codes);

create table account.consents (
    id         uuid        primary key,
    user_id    uuid        not null references account.users (id) on delete cascade,
    kind       varchar(20) not null check (kind in ('TERMS', 'PRIVACY')),
    version    varchar(20) not null,
    agreed     boolean     not null,
    created_at timestamptz not null default now()
);
create index consents_user_idx on account.consents (user_id, kind, created_at desc);
```

배열 칸 값 검사(코드 모양, 허용 값)는 DB check 대신 요청 DTO 검증에서 해요. 값 목록이 바뀔 때 마이그레이션을 새로 만들지 않으려고요.

## 4. 알림 대상자 찾기 (api notification 모듈이 써요)

user 모듈이 아래 조회 메서드를 내줘요. api `notification` 모듈은 이 메서드로 대상자를 찾아 `notification.requested`를 보내요 (테이블을 직접 읽지 않아요). 지역 맞추기 규칙은 [notification-events.draft.md](notification-events.draft.md) 4장.

| 메서드 (예시 이름) | 쓰는 테이블 | 언제 |
| --- | --- | --- |
| `findHousingInterested(regionCodes, nationwide, noticeType)` | `housing_interests` | 새 주거 공고 |
| `findBusinessInterested(regionCodes, nationwide, industries, startupStages)` | `business_interests` | 새 사업공간 공고·창업지원·사업공간 모집 |
| `findSupportInterested(regionScope, regionCodes, supportKinds)` | `support_interests` | 새 지원 정책 |
| `findFavoritedBy(targetType, targetId)` | `favorites` | 정정·후속 안내·마감 D-3·D-1 |

- 결과는 회원 ID 목록이에요. api가 500명씩 나눠 보내요
- 약관 동의(`TERMS`·`PRIVACY`)를 하지 않은 회원은 결과에서 빼요
- 지역 검색이 빨라야 해서 관심 조건 세 테이블의 `region_codes`에 GIN 인덱스를 둬요 (3장)

탈퇴하면 api user 모듈이 `user.withdrawn`을 아웃박스에 적어 알림 서버에 알려요 (`UserWithdrawn` 내부 이벤트를 그대로 밖으로).

## 5. 결정 (2026-10-23)

| 결정 | 내용 |
| --- | --- |
| 관심 공고 상한 | 200개 + 마감 후 30일 지난 관심 공고 자동 정리 (2-1) |
| 약관 동의 전 | 공고 둘러보기는 허용, 관심 저장·관심 조건·알림 설정 API만 막아요 (403 + "약관 동의가 필요해요") |
| 주거 관심 지역 시도 전체(`xx000`) | 그 시도 안 모든 시군구 공고를 보내요 |
| 대상자 찾기 | api `notification` 모듈이 이 테이블로 찾아요 (알림 서버에 복사본 없음) |
