# api (자바 25 + 스프링 부트, 모듈러 모놀리스)

```
src/main/java/kr/mujuthaek/api/
├─ ApiApplication.java
├─ common/          event/ · dto/response/ · exception/ · pagination/ · config/   (업무 규칙 없음)
├─ notice/          공고                     ┐
├─ benefit/         혜택·창업지원            │  모듈마다: controller · service · repository · domain ·
├─ eligibility/     판정·준비할 돈 (+ rule/) │           model(필요할 때) · dto/request · dto/response ·
├─ area/            생활권·상권              │           mapper · event
├─ user/            로그인·관심 (account)    │
└─ notification/    사용자 알림·웹 푸시      ┘
```

## 규칙 요약
- 다른 모듈 데이터는 서비스 계층에서 그 모듈 서비스의 **조회 메서드**로. 한 방향으로만, record로만
- 다른 모듈의 repository·테이블은 쓰지 않음. 다른 모듈에 영향을 주는 변경은 `common/event` 이벤트로
- 각 모듈의 `service` 패키지만 공개(`@NamedInterface`). `ModuleStructureTest`가 경계와 순환을 검사

자세한 규칙은 저장소 루트의 `CLAUDE.md`와 팀 컨벤션 문서를 봐요.

## 실행
```bash
./gradlew spotlessApply       # 포맷 맞추기
./gradlew check               # 포맷 검사 + 테스트 (모듈 경계 포함)
./gradlew bootRun             # 실행 (DB·RabbitMQ는 infra에서 먼저 띄우기)
```

## 로그인 (user 모듈)
카카오·네이버·구글 소셜 로그인 + JWT예요. 제공자를 더 붙이려면 `application.yml`의 registration과 `SocialProfiles`에 추가해요.

| 토큰 | 유효 | 어디에 | 쓰임 |
| --- | --- | --- | --- |
| access token (JWT, HS256) | 30분 | 프론트 메모리 | 요청마다 `Authorization: Bearer <토큰>`. 서버에 저장하지 않아요 |
| refresh token | 14일 | HttpOnly 쿠키 `refresh_token` (Path=/api/auth, SameSite=Lax, 운영 Secure) | access token 재발급. DB(`account.refresh_tokens`)에는 SHA-256 해시만 |

| 동작 | 주소 | 비고 |
| --- | --- | --- |
| 로그인 시작 | `GET /oauth2/authorization/{kakao\|naver\|google}` | 제공자 동의 화면으로 이동. 끝나면 refresh token 쿠키를 받고 `FRONTEND_URL/`로, 실패하면 `FRONTEND_URL/login?error=social&reason=…`로 돌아가요 |
| 콜백 | `GET /login/oauth2/code/{kakao\|naver\|google}` | 각 개발자 콘솔에 Redirect(Callback) URI로 등록 (스프링 필터가 처리, 컨트롤러 없음) |
| access token 받기·재발급 | `POST /api/auth/refresh` | 쿠키로 확인. `{accessToken, tokenType: "Bearer", expiresIn}` + 새 refresh token 쿠키 (회전: 쓴 값은 다시 못 써요). 실패하면 401 `INVALID_REFRESH_TOKEN` → 다시 로그인. 같은 쿠키로 동시에 부르면 먼저 온 하나만 새 토큰을 받고 나머지는 409 `CONFLICT`(쿠키는 그대로) → 원래 요청을 한 번 다시. `Authorization` 헤더는 읽지 않아요(만료된 access token이 붙어 와도 막히지 않게) |
| 내 정보 | `GET /api/users/me` | Bearer 없거나 만료면 401 `UNAUTHORIZED` |
| 로그아웃 | `POST /api/auth/logout` | 이 기기의 refresh token 삭제 + 쿠키 삭제, 204 |
| 탈퇴 시작 | `POST /api/users/me/withdrawal[?provider=naver]` | 바로 지우지 않고 `{provider, reauthUrl, expiresIn, otherProviders}`를 줘요. `otherProviders`(함께 연결된 다른 소셜)는 서버가 연결을 끊을 수 없어서, 탈퇴 전에 "각 서비스에서 직접 해제하세요"라고 보여 줘요. 프론트가 `reauthUrl`로 **페이지 이동** → 그 소셜로 다시 로그인 → 회원 삭제(hard delete) → 소셜 연결 끊기 → `FRONTEND_URL/withdrawn`. 실패·취소는 `FRONTEND_URL/mypage?withdraw=cancelled\|expired\|account_mismatch\|failed` |

프론트 순서: 로그인 버튼 → `/oauth2/authorization/kakao`로 **페이지 이동** → 돌아오면 `POST /api/auth/refresh`(`credentials: "include"`)로 access token → API 요청에 Bearer → 401이면 refresh 한 번 → 또 401이면 로그인 화면.

- 권한: 처음 로그인하면 `USER`. `ADMIN`은 DB에서 바꿔요 (다음 재발급부터, 최대 30분 안에 적용):
  `update account.users set role = 'ADMIN' where id = '<회원 ID>';`
- 주소 규칙: `/api/admin/**`은 ADMIN, `/api/users/**`는 로그인한 회원, 나머지는 누구나. 401·403도 Problem Details(`code`: `UNAUTHORIZED`·`FORBIDDEN`)로 와요.
- 다른 모듈 컨트롤러에서 로그인 회원: `@AuthenticationPrincipal LoginUser user` → `user.userId()` (`user.service.LoginUser`)
- 탈퇴는 hard delete예요. `users`를 지우면 소셜 계정·refresh token·탈퇴 티켓이 cascade로 같이 지워지고, `common/event/UserWithdrawn`으로 다른 모듈(알림 설정·푸시 구독 등)이 자기 데이터를 지워요. 재인증으로 받은 그 사용자의 제공자 토큰으로 **다시 로그인한 소셜만** 연결을 끊어요(카카오 unlink, 네이버 토큰 삭제, 구글 revoke). 제공자 토큰을 저장하지 않고 Admin 키도 두지 않아서, 함께 연결된 다른 소셜의 앱 동의는 사용자가 그 서비스에서 직접 해제해야 해요(탈퇴 시작 응답의 `otherProviders`). 연결 끊기가 실패해도 탈퇴는 완료돼요(로그만). 탈퇴 후 남은 access token(최대 30분)으로는 `/api/users/me`와 모든 쓰기 요청(GET·HEAD·OPTIONS 말고)이 401이에요 — 다른 모듈이 데이터를 지운 뒤 다시 만들지 못하게요. 만료된 refresh token과 끝내지 않은 탈퇴 티켓은 매일 04:30(한국 시각)에 지워요. 자세한 설계: 탈퇴·연결 끊기 문서.
- 모든 기기 로그아웃(권한 회수 등): `AuthTokenService.revokeAll(userId)`. 이미 발급한 access token은 남은 시간(최대 30분)까지 유효해요.
- 서버는 로그인 상태를 저장하지 않아요(STATELESS). 카카오 로그인 중간의 state 값만 잠깐 세션에 두고 로그인이 끝나면 지워요. CSRF는 꺼요 (Bearer 헤더 인증, 쿠키는 재발급·로그아웃에만 SameSite=Lax + POST).
- 소셜 계정에서는 닉네임만 받아요 (카카오 `profile_nickname`, 네이버 제공 정보 '별명', 구글 `profile`의 이름). 이메일·나이 등은 받지 않아요.
- 구글 scope에 `openid`를 넣지 않아요. 넣으면 OIDC 처리기로 가서 가입 처리(`SocialOAuth2UserService`)를 거치지 않아요.
- 같은 사람이어도 제공자가 다르면 다른 회원이에요 (이메일을 받지 않아 같은 사람인지 알 수 없어요). 계정 합치기는 로그인한 상태에서 다른 소셜 계정을 연결하는 기능으로 따로 만들어야 해요 (`social_accounts`는 한 회원에 여러 개를 붙일 수 있어요).
- 실패하면 `FRONTEND_URL/login?error=social&reason=<OAuth2 오류 코드>`로 돌아가고 서버 로그에 원인이 남아요 (`LoginFailureHandler`).
- 환경 변수: `KAKAO_CLIENT_ID`·`KAKAO_CLIENT_SECRET`, `NAVER_CLIENT_ID`·`NAVER_CLIENT_SECRET`, `GOOGLE_CLIENT_ID`·`GOOGLE_CLIENT_SECRET`, `FRONTEND_URL`, `JWT_SECRET`(32바이트 이상, 운영 필수) (`infra/.env.example`). 키가 없으면 그 제공자 로그인만 실패하고 서버는 떠요.
