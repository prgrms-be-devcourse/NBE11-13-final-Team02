# 웹 푸시 계약 (초안 v1)

이메일 대신 **웹 푸시**로 알림을 보내기 위한 약속이에요. 이메일 코드는 그대로 두고, 푸시를 채널 하나로 더해요.
[notification-events.draft.md](notification-events.draft.md)의 B안(PWA는 api만 부름, api가 "누구에게·어디로·무엇을", 알림 서버는 "언제·어떻게")을 그대로 따라요.

- 상태: **초안**이에요. 8장의 결정 필요 항목을 정한 뒤 `contracts/events/*.v1.json`과 api 문서로 옮겨요
- 표준: Web Push(RFC 8030), VAPID(RFC 8292), 내용 암호화 `aes128gcm`(RFC 8291). 브라우저마다 따로 만들 것 없이 같은 코드로 보내요

```
① PWA  "알림 받기" 버튼 → 권한 허용 → pushManager.subscribe(VAPID 공개키)
        → 브라우저가 구독 정보 {endpoint, p256dh, auth}를 만들어 줌
② PWA → api   PUT /api/v1/push/subscriptions (기기마다 1줄 저장)
③ api  알림 요청을 만들 때 recipients[].push에 그 회원의 기기 목록을 넣음 → 아웃박스 → 알림 서버
④ 알림 서버  기기마다 endpoint로 암호화한 내용을 보냄 (VAPID 개인키로 서명)
        → 브라우저 회사의 푸시 서비스 (Google·Mozilla·Apple) → 기기
⑤ PWA 서비스 워커  push 이벤트 → 알림 띄움 → 누르면 linkPath 화면 열기
⑥ 죽은 구독(404·410)  알림 서버 → api   push.subscription.expired → api가 그 줄을 지움
```

## 1. 저장하는 것

### 1-1. api: 구독 기기 `notification.push_subscriptions` (기기마다 1줄)

| 칸 | 모양 | 뜻 |
| --- | --- | --- |
| `id` | uuid PK | UUID v7 |
| `user_id` | uuid | 회원. user 모듈 테이블을 직접 참조하지 않아요(FK 없음). 탈퇴는 `UserWithdrawn`으로 지워요 |
| `endpoint` | text **unique** | 이 기기로 가는 주소 (브라우저가 만듦). 최대 2,048자 |
| `p256dh` | text | 내용을 그 기기만 열 수 있게 잠그는 공개키 (브라우저가 만듦) |
| `auth` | text | 암호화에 같이 쓰는 비밀값 (브라우저가 만듦) |
| `user_agent` | text \| null | "갤럭시 Chrome"처럼 기기 목록에 보여 줄 이름 (최대 200자) |
| `created_at`, `updated_at` | timestamptz | 처음 등록, 마지막 등록(같은 기기가 다시 보냄) |

- 회원 한 명당 **최대 5대**. 6번째를 등록하면 `updated_at`이 가장 오래된 기기를 지워요
- `endpoint`·`p256dh`·`auth`는 **알면 그 기기에 알림을 보낼 수 있는 값**이라 비밀번호처럼 다뤄요. 로그·오류 응답에 넣지 않아요 (로그에는 `userId`와 주소의 도메인 `fcm.googleapis.com` 정도만)

### 1-2. api: 알림 설정 (이미 [user-preferences.draft.md](user-preferences.draft.md)에 있음)

- `push_enabled`: **계정 전체** 푸시 켬·끔 (모든 기기). 기본 false
- 종류별 켬·끔, `quiet_enabled`는 이메일과 같이 써요
- 기기 하나만 끄기 = 그 기기 구독을 지우기(`DELETE`). 계정 전체 끄기 = `push_enabled = false` (구독은 남겨 둬서 다시 켜면 바로 받음)

### 1-3. 서버 설정: VAPID 키 한 쌍 (서비스 전체에 1개)

| 이름 | 누가 | 뜻 |
| --- | --- | --- |
| `PUSH_VAPID_PUBLIC_KEY` | api(PWA에 줌) · 알림 서버 | "이 서비스에서 오는 알림만 받을게" |
| `PUSH_VAPID_PRIVATE_KEY` | **알림 서버만** | 보낼 때 서명. **커밋하지 않고 `.env`·배포 비밀값에만** |
| `PUSH_VAPID_SUBJECT` | 알림 서버 | 연락처 (`mailto:팀 주소` 또는 서비스 주소). 푸시 서비스가 문제 있을 때 연락해요 |

- 만들기: `npx web-push generate-vapid-keys` (한 번만)
- **키를 바꾸면 모든 구독이 무효**가 돼요. 바꿀 일이 생기면 PWA가 새 키로 다시 구독하게 해야 해요

### 1-4. 알림 서버: 따로 저장하지 않아요

보내는 동안만 `deliveries.address`에 기기 목록을 두고, 끝나면(SENT·FAILED) 지워요 (이메일 주소와 같은 규칙).

## 2. api HTTP (PWA가 불러요, api `notification` 모듈)

| 요청 | 로그인 | 하는 일 | 응답 |
| --- | --- | --- | --- |
| `GET /api/v1/push/public-key` | 필요 없음 | VAPID 공개키 | `200 {"publicKey": "BEl6…"}` |
| `PUT /api/v1/push/subscriptions` | 필요 | 이 기기 등록·갱신. 같은 `endpoint`가 있으면 내용·`updated_at`을 바꾸고, **다른 회원의 것이면 지금 회원으로 옮겨요**(같은 브라우저에서 다른 계정으로 로그인) | `204` |
| `DELETE /api/v1/push/subscriptions` | 필요 | 이 기기 해제 (이 기기 알림 끄기, **로그아웃 직전**) | `204` (없어도 204) |
| `GET /api/v1/push/subscriptions` | 필요 | 내 기기 목록 (`id`, `userAgent`, `createdAt`, `updatedAt`). `endpoint`·키는 주지 않아요 | `200 [...]` |
| `DELETE /api/v1/push/subscriptions/{id}` | 필요 | 목록에서 다른 기기 해제 | `204` / 남의 기기면 `404` |

`PUT`·`DELETE` 본문 (브라우저 `subscription.toJSON()` 모양 그대로):

```json
{
  "endpoint": "https://fcm.googleapis.com/fcm/send/dX3k…",
  "keys": { "p256dh": "BNcRd…", "auth": "tBHI…" },
  "userAgent": "Chrome / Android"
}
```

- `DELETE`는 `endpoint`만 있어도 돼요
- 검사: `endpoint`는 `https://`로 시작, `p256dh`·`auth`는 base64url. 틀리면 `400`
- PWA는 **앱을 열 때마다** 현재 구독을 `PUT`해요 (브라우저가 구독을 몰래 바꾼 경우를 맞추고, `updated_at`이 "최근에 쓴 기기"가 돼요)

## 3. `notification.requested`에 더하는 칸

| 칸 | 모양 | 필수 | 뜻 |
| --- | --- | --- | --- |
| `recipients[].push` | object[] \| null | 아니요 (없으면 null) | 보낼 기기 목록. api가 **`push_enabled` + 이 종류 켬 + 기기가 1대 이상**일 때만 넣어요. 최대 5개 |
| `recipients[].push[].endpoint` | string | ✅ | |
| `recipients[].push[].p256dh` | string | ✅ | |
| `recipients[].push[].auth` | string | ✅ | |

```json
"recipients": [
  {
    "userId": "0192b6f0-0000-7000-8000-0000000000a1",
    "email": null,
    "quietEnabled": true,
    "push": [
      { "endpoint": "https://fcm.googleapis.com/fcm/send/dX3k…", "p256dh": "BNcRd…", "auth": "tBHI…" },
      { "endpoint": "https://web.push.apple.com/QGl4…", "p256dh": "BLc2…", "auth": "k9Qe…" }
    ]
  }
]
```

- `email`과 `push`가 둘 다 null인 회원은 알림함에만 남아요 (알림 서버는 아무것도 안 보내요)
- 칸을 더하는 것이라 `schemaVersion`은 `1` 그대로예요. 지금 알림 서버는 모르는 칸을 조용히 무시해서(`NotificationRequest.Recipient`에 `push`가 없음) 먼저 넣어도 깨지지는 않지만 푸시가 나가지 않아요. 그래서 **알림 서버(`PushChannel`)를 먼저 배포**한 뒤 api가 `push`를 넣기 시작해요
- 문구 길이: 푸시 내용은 암호화 뒤 4KB가 한계라, api가 `title` 100자·`body` 200자를 넘으면 잘라서 끝에 `…`을 붙여요 (이메일·알림함도 같은 문구)

## 4. 알림 서버가 보내는 방법 (`PushChannel`)

`NotificationChannel`을 구현한 빈 하나예요. 발송 줄·중복 거르기·조용한 시간·재시도는 지금 것을 그대로 써요.

### 발송 줄

- 회원 1명 × 채널 1개 = `deliveries` 1줄 (지금과 같음). `address`에는 기기 목록 JSON
- **묶지 않아요** (`supportsDigest = false`). 새 공고도 바로 보내요. 단 `quietEnabled`이고 22:00~08:00이면 다음 08:00
- 한 줄 안에서 기기마다 보내고, **성공하거나 죽은 기기는 `address`에서 빼요.** 재시도는 남은 기기에만 해서, 이미 받은 기기가 두 번 받지 않아요

### 기기에 보내는 내용 (서비스 워커가 `event.data.json()`으로 받음)

```json
{
  "v": 1,
  "type": "DEADLINE_D1",
  "title": "내일 마감: 서초구 서리풀 청년아트타운 잔여세대 입주자 모집공고",
  "body": "관심 공고 접수가 내일(10월 30일) 마감돼요.",
  "linkPath": "/notices/0192b6f0-1111-7000-8000-000000000001",
  "tag": "/notices/0192b6f0-1111-7000-8000-000000000001"
}
```

- `tag`: 같은 화면(공고) 알림은 기기에서 **최신 것 하나로 바꿔 보여요** (D-3 뒤 정정이 오면 정정만 남음). `linkPath`와 같은 값이에요

### 함께 보내는 헤더

| 헤더 | 값 | 뜻 |
| --- | --- | --- |
| `TTL` | `DEADLINE_D1` 12시간 · `DEADLINE_D3` 48시간 · `NEW_*` 24시간 · `CORRECTED`·`FOLLOW_UP` 72시간 | 기기가 꺼져 있을 때 푸시 서비스가 얼마나 들고 있을지. 지나면 버려요 (늦게 받으면 틀린 말이 되는 마감 알림은 짧게) |
| `Urgency` | `DEADLINE_D1` `high` · 나머지 `normal` | 배터리 절약 모드에서도 바로 보낼지 |
| `Content-Encoding` | `aes128gcm` | 표준 암호화 |
| `Authorization` | `vapid t=…, k=…` | VAPID 서명 (라이브러리가 만듦) |

### 푸시 서비스의 답과 처리

| 답 | 뜻 | 처리 |
| --- | --- | --- |
| `201` | 받음 | 그 기기 완료 |
| `404`·`410` | **죽은 구독** (앱 삭제·권한 끔·만료) | 그 기기 빼고 `push.subscription.expired` (5장). 다시 보내지 않아요 |
| `429` | 너무 많이 보냄 | `Retry-After`만큼 뒤에 다시 |
| `5xx`·시간 초과 | 푸시 서비스 문제 | 지금 재시도 규칙대로 다시 |
| `400`·`413` | 우리 쪽 잘못 (형식·크기) | 다시 보내지 않고 FAILED, `last_error`에 코드 |
| `401`·`403` | VAPID 키 문제 (설정 오류) | 다시 보내지 않고 FAILED, **경고 로그** (모든 발송이 실패하는 상황이라 바로 알아야 해요) |

- 줄 상태: 남은 기기가 없으면 SENT (한 대라도 받았으면 SENT, 모두 죽었으면 FAILED `ALL_EXPIRED`)
- 로그에는 `userId`·`deliveryId`·푸시 서비스 도메인·응답 코드만 남겨요

## 5. 새 이벤트 `push.subscription.expired` (알림 서버 → api)

| 칸 | 모양 | 필수 | 뜻 |
| --- | --- | --- | --- |
| `userId` | uuid | ✅ | |
| `endpoint` | string | ✅ | 죽은 기기 주소 |
| `status` | int | ✅ | `404` 또는 `410` |

- api `notification` 모듈이 받아 `user_id`·`endpoint`가 모두 같은 줄만 지워요 (그 사이 다른 회원으로 옮겨진 기기는 안 지움)
- 봉투 `producer`는 `notification`. **알림 서버에도 아웃박스가 필요**해요 (`email.bounced`와 같이, 발송 줄 결과와 같은 트랜잭션에 적기)
- 아웃박스가 생기기 전까지는 알림 서버가 그 기기를 그 줄에서 빼기만 하고, 다음 요청에 또 들어오면 또 410을 받아요 (불필요한 호출일 뿐 사용자 문제는 없음)

## 6. 탈퇴·로그아웃

| 언제 | api | 알림 서버 |
| --- | --- | --- |
| 탈퇴 (`UserWithdrawn`) | 그 회원 구독을 모두 지움 (같은 트랜잭션) | `user.withdrawn`으로 발송 줄 지움 (주소 포함) |
| 로그아웃 | PWA가 로그아웃 **전에** `DELETE /push/subscriptions` | — |
| 같은 브라우저에서 다른 계정 로그인 | `PUT`이 구독을 새 회원으로 옮김 | — |

- 탈퇴 전에 만든 요청이 늦게 도착할 수 있어요 (아웃박스 재시도 순서). 알림 서버가 **탈퇴한 회원 ID를 기억해 두고 거르는 것**을 발송 PR에서 더해요

## 7. 브라우저별 차이 (프론트가 챙길 것)

서버 쪽은 브라우저와 상관없이 같아요. `endpoint` 도메인만 달라요.

| 브라우저 | `endpoint` 도메인 | 챙길 것 |
| --- | --- | --- |
| Chrome·Edge·삼성 인터넷 (Android·PC) | `fcm.googleapis.com` 등 | 큰 이미지·버튼은 되지만 쓰지 않아요 (모두 같게) |
| Firefox | `updates.push.services.mozilla.com` | 권한은 버튼을 눌렀을 때만 물을 수 있어요 |
| Safari (Mac) | `web.push.apple.com` | 위와 같음 |
| **iPhone·iPad** (iOS·iPadOS 16.4+) | `web.push.apple.com` | **홈 화면에 추가한 PWA에서만** 돼요. 그냥 Safari로 열면 "홈 화면에 추가해 주세요" 안내 |

모든 브라우저 공통:
- HTTPS 필요 (로컬 `localhost`는 예외)
- 권한은 **"알림 받기" 버튼을 누를 때** 물어요 (페이지를 열자마자 물으면 막히거나 거절이 많아요)
- 푸시를 받으면 **반드시 알림을 띄워요** (`userVisibleOnly: true`). 조용히 받기만 하면 브라우저가 권한을 거둬요
- 제목·본문·아이콘만 써요

서비스 워커가 할 일: `push`(알림 띄우기), `notificationclick`(그 `linkPath` 열기, 이미 열린 탭이 있으면 그 탭으로), `pushsubscriptionchange`(새 구독을 `PUT`).

## 8. 결정 필요

| 항목 | 제안 |
| --- | --- |
| 구독 테이블 위치 | api `notification` 모듈 (`notification.push_subscriptions`). 알림에만 쓰는 데이터라서 |
| 구독 정보를 요청에 실어 보내기 (B안 유지) | 유지. 알림 서버가 api DB를 보지 않고, 키는 보내는 동안만 머물러요 |
| 회원당 기기 수 | 5대 |
| TTL 값 | 4장 표 |
| `push_enabled`를 켜는 때 | 첫 구독 등록 때 PWA가 설정 API로 같이 켬 (user 모듈 담당과 확인) |
| 새 공고가 한꺼번에 많은 날 | v1은 그대로 보냄. 많으면 "새 공고 N건" 묶음 알림이나 하루 상한을 다음에 |
| 이메일 | 코드는 두고, 운영에서는 이메일 칸을 null로 (나중에 정리) |
