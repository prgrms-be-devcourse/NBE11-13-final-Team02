# 배포 (EC2 · Docker Compose)

운영(prod EC2)과 개발(dev EC2) 서버는 같은 구성이에요. DB는 RDS, 원본 파일은 S3(EC2 IAM 역할), 프론트는 Vercel이 `/api/**`를 서버의 nginx(:80)로 넘겨요.

| 서버 | 서비스 | 배포 |
| --- | --- | --- |
| prod (3.37.131.73) | rabbitmq · nginx · api · notification · collector · extraction | 태그 `api-v1.2.0` 등 → `release.yml` |
| dev (52.79.106.144) | rabbitmq(shovel) · nginx · api · notification · extraction (수집기 없음) | `dev` 브랜치 push → `deploy-dev.yml` |

## 서버 폴더 `/opt/mujuthaek`
```
.env               GitHub Environment 비밀 ENV_FILE을 배포 때마다 그대로 써요 (chmod 600). 서버에서 고치지 않아요
versions.env       서비스별 이미지 태그 (API_TAG=v1.2.0 …). deploy.sh만 바꿔요
compose.prod.yml   compose.dev.yml   nginx/default.conf   rabbitmq/enabled_plugins   ← 이 폴더(infra/deploy)를 배포 때마다 복사
deploy.sh          shovel.sh          copy-prod-to-dev.sh
migrations/<서비스> deploy.sh가 그 버전 이미지에서 꺼낸 Flyway 파일 (collector·extraction)
```
손으로 볼 때: `docker compose -f compose.prod.yml --env-file .env --env-file versions.env ps` (logs, restart도 같은 방식)

## 첫 배포
1. GitHub → Actions → **Release** → Run workflow: 브랜치 `dev`(또는 `main`), 서비스 `all`, 버전 `v0.1.0`, **build 켬**
   - 네 서비스를 테스트·빌드해서 `ghcr.io/prgrms-be-devcourse/nbe11-13-final-team02/<서비스>:v0.1.0`으로 올리고 운영에 배포해요
   - 순서: rabbitmq → Flyway(collector·extraction) → api → notification → collector → extraction → nginx. api·notification은 각자 Flyway를 직접 돌려요
2. 개발 서버: Actions → **Deploy dev** → Run workflow(`dev`)로 세 서비스를 모두 빌드·배포해요. 그 뒤로는 `dev`에 올라온 바뀐 서비스만 배포돼요
3. 처음 최상위 관리자: 관리자로 쓸 계정으로 가입 → `/api/users/me`의 `id`를 ENV_FILE의 `ADMIN_BOOTSTRAP_USER_ID`에 넣고 api 다시 배포
4. 수집기는 처음 뜰 때 묶음마다 최신 N개만 골라 AI 추출로 보내요 (services/collector/README.md 「처음 수집 개수 제한」)

## 이후 배포 · 롤백
- 운영: `git tag api-v1.2.1 && git push origin api-v1.2.1` → 테스트 → 이미지 → 배포 → 릴리즈 노트
- 롤백: Actions → Release → 서비스와 **이미 있는 버전**, build 끔. 또는 서버에서 `./deploy.sh prod api v1.2.0`
- deploy.sh는 헬스체크(api·notification `/actuator/health`, 파이썬은 Docker healthcheck, 최대 3분)가 실패하면 이전 태그로 되돌리고 실패로 끝나요.
  **DB 마이그레이션은 되돌리지 않아요** — 새 컬럼을 지우는 변경은 두 번에 나눠 올려요

## 개발 서버 데이터
개발 서버에는 수집기가 없고, 개발 추출은 `EXTRACTION_MODEL_MODE=recorded`라 새 공고의 결과를 만들지 못해요.
그래서 개발 서버는 **운영이 만든 결과**를 그대로 받아 운영과 같은 공고·혜택·생활권을 봐요. 두 단계예요 (둘 다 한 번만).

### 1. 지금까지의 데이터: copy-prod-to-dev.sh (운영 서버에서)
운영 api DB의 아래 테이블을 개발 api DB(`api_dev`)로 옮겨요. 개발 쪽 대상 테이블은 비우고 넣어요(한 트랜잭션, 실패하면 그대로).
```bash
cd /opt/mujuthaek
./copy-prod-to-dev.sh --dry-run      # 옮길 테이블·줄 수·같이 비워지는 개발 테이블만 보기
./copy-prod-to-dev.sh                # 개발 DB 계정·암호를 입력받아 복사 (--skip-big: 주소점·상가 빼기)
```
| 스키마 | 옮기는 테이블 | 옮기지 않음 |
| --- | --- | --- |
| notice | 공고·버전·원본 글·정정/후속·지역·추출 결과·생활권 점수·중복 후보·정책 판정 (전부) | `notice_reports`(회원 신고) |
| benefit | 혜택·조건·지원 내용 (전부) | — |
| area | 지역 경계·시설·상가(`stores`)·주소점(`address_points`)·도로명·업종 규칙·점수 규칙·생활권 지표·공고 위치·적재 기록 | `area_reports`(회원 제보) |
| eligibility | 기준값·기준 세트·대출 상품/규칙·규제지역·청약 예치금 | `criteria_set_history`(관리자 변경 기록) |
| account·notification·chat·traffic·common | — | 회원·알림·채팅·트래픽·아웃박스·처리 기록·Flyway 기록 |

- 스키마 단위로 골라요: 위 네 스키마에 테이블이 새로 생기면 같이 옮겨요 (제외 목록은 스크립트의 `EXCLUDE`)
- `stores`·`address_points`가 수 GB라 몇 분 걸려요. 주소 검색·상권이 필요 없으면 `--skip-big`
- 개발의 `notice_reports`처럼 옮기는 테이블을 참조하는 개발 테이블은 같이 비워져요 (`--dry-run`이 알려 줘요)
- 옮기는 테이블끼리의 외래키(공고 `merged_into` 같은 자기 참조 포함)는 넣는 동안 풀었다가 다시 걸어 검증해요. 개발 DB 계정이 테이블 주인이어야 해요
- 개발 api가 운영과 같은 버전(같은 마이그레이션)이어야 해요. 다르면 멈춰요
- pg_dump·psql은 `postgres:17-alpine` 이미지로 돌려요 (서버에 설치하지 않아요)

### 2. 이후 결과: shovel (운영 결과 이벤트를 개발로 복사)
운영 RabbitMQ의 `dev.mirror` 큐(3일·2만 건까지)가 `mujuthaek.events`의 결과 이벤트를 받아 두고, 개발 RabbitMQ의 shovel(from-prod)이 가져가요.
| 모드 | 복사하는 이벤트 | 쓰는 때 |
| --- | --- | --- |
| `results` (기본) | `extraction.completed`(공고·창업지원)·`policy.extracted`(혜택)·`area.source.ready`(생활권 원천) | 개발 api가 운영과 같은 데이터를 보게 |
| `raw` | `raw.notice.collected`·`raw.policy.collected`·`area.source.ready` | 개발 추출을 시험할 때 (recorded 모드라 결과가 안 나올 수 있어요) |
```bash
# 운영 서버: dev.mirror 큐·바인딩, 이 큐만 읽는 계정 shovel (암호는 입력)
./shovel.sh prod                     # results
./shovel.sh prod --mode raw          # raw로 바꾸기 (다른 모드 바인딩은 풀어요)
./shovel.sh prod --recreate          # 큐를 지우고 다시 만들기 (예전 raw 메시지가 쌓여 있을 때)
# 개발 서버: from-prod shovel (같은 암호 입력)
./shovel.sh dev
```
- 순서: `copy-prod-to-dev.sh` → `shovel.sh prod`(이미 raw로 만들었다면 `--recreate`) → `shovel.sh dev`. 복사와 shovel 사이에 운영에서 나온 결과는 3일 안이면 큐에 남아 있다가 들어가요
- 개발 api는 결과 이벤트를 받아 DB에만 써요. S3를 읽는 건 `area.source.ready`의 생활권 원천(`area/…`)뿐이고, 운영과 같은 버킷이라 개발 서버 IAM 역할에 그 버킷 읽기 권한이 있어야 해요. 공고·혜택 결과는 S3를 읽지 않아요(원문은 기관 링크)
- 상태: 개발 서버 관리 화면(`ssh -L 15672:127.0.0.1:15672 ubuntu@<dev>` → http://localhost:15672 → Admin → Shovel Status)

## 생활권·상권 원천 적재 (운영, 한 번만)
S3에 이미 올린 원천 파일을 목록으로 다시 적재 요청해요 (파일이 운영 버킷에 있어야 해요).
```bash
C="docker compose -f compose.prod.yml --env-file .env --env-file versions.env exec collector python -m mujuthaek_collector"
$C area-upload --skip-upload --manifest area/area_manifest.csv
$C area-upload --skip-upload --manifest area/area_manifest_boundary.csv
$C area-upload --skip-upload --manifest area/area_manifest_address.csv
```

## 서버가 켜져 있는 시간
서버는 자동 중지·시작으로 09~18시(11/7부터 10~19시)에만 켜져 있어요. 하루 한 번 작업은 모두 11시대예요.
- api: 혜택 상태 11:00(+ 뜬 뒤 1분), 마감 D-3·D-1 알림 11:00 이후 첫 확인(그날 꺼져 있었으면 켜진 뒤 따라잡기), 정리 11:10~11:40
- notification: 정리 11:20·11:30
- collector: 정책 11:00, 정정 확인 11:30, 파일 정리 11:50, 처음 수집 고르기 12:30, 정책 다시 확인 금 13:00, 게시판 평일 9~19시 매시 5분

## 사람이 할 일
- 소셜 로그인 콘솔 3곳(카카오·네이버·구글)에 리다이렉트 URI 등록
  - `https://mujuthaek-fe.vercel.app/login/oauth2/code/kakao`, `…/naver`, `…/google`
  - dev 프론트 주소(Vercel Preview 고정 주소)의 같은 경로 `/login/oauth2/code/{kakao,naver,google}`
- Vercel rewrites: `/api/**`·`/oauth2/**`·`/login/oauth2/**` → `http://<탄력적 IP>:80` (프론트 저장소 작업, 환경 변수 `API_ORIGIN`)
- ENV_FILE: `FRONTEND_URL`(로그인 뒤 돌아갈 프론트 주소), `OAUTH_REDIRECT_BASE_URL`(소셜 로그인 콜백 주소의 앞부분), `CORS_ORIGINS`에 dev 프론트 주소
  - 운영: `FRONTEND_URL=https://mujuthaek-fe.vercel.app`, `OAUTH_REDIRECT_BASE_URL=https://mujuthaek-fe.vercel.app`
  - 개발: 두 값 모두 dev 프론트 주소 (Vercel Preview 고정 주소)
  - `OAUTH_REDIRECT_BASE_URL`이 없으면 콜백 주소를 요청 주소로 만들어요. Vercel → nginx → api를 거치면 http·서버 IP가 될 수 있어서 Google이 거절해요
- 브라우저는 Vercel 주소만 불러요. api는 nginx가 넘긴 X-Forwarded-Host·Proto로 OAuth redirect-uri를 `https://<Vercel 주소>` 기준으로 만들고,
  refresh 토큰 쿠키(`Path=/api/auth; Secure; HttpOnly; SameSite=Lax`, Domain 없음)도 Vercel 주소에 저장돼요

## 주의
- `.env` 값에 `$`가 있으면 compose가 변수로 읽어요. 암호에 `$`를 쓰지 않거나 `$$`로 써요
- 파이썬 워커의 RabbitMQ 주소는 `amqp://RABBITMQ_USER:RABBITMQ_PASSWORD@rabbitmq:5672/`로 만들어요. 암호에 `@ : / ? #`가 있으면 안 돼요
- Flyway 계정은 기본 `collector_app`·`extraction_app`이에요. 다르면 ENV_FILE에 `COLLECTOR_DB_USER`·`EXTRACTION_DB_USER`를 넣어요
- 관리 화면(15672)·api(8080)·notification(8081)은 서버 안(127.0.0.1)에서만 열려요. 밖에서는 nginx(:80)만

## 문제 해결
| 로그 | 원인 · 할 일 |
| --- | --- |
| scp·ssh 단계 `dial tcp …:22: i/o timeout` | 서버가 꺼진 시간이에요(자동 중지). 켜진 뒤 그 실행을 Re-run 하거나 Run workflow |
| Flyway `password authentication failed for user "…_app"` | Flyway 계정·암호가 DB와 달라요. ENV_FILE의 `EXTRACTION_DB_USER`·`COLLECTOR_DB_USER`(기본 `extraction_app`·`collector_app`)와 `…_DB_PASSWORD`를 `…_DB_URL` 안의 값과 같게. deploy.sh는 그 서비스만 이전 태그로 되돌려요 |
| 헬스체크 3분 초과 → 「이전 태그로 되돌려요」 | `docker compose -f compose.<env>.yml --env-file .env --env-file versions.env logs --tail 200 <서비스>`로 원인 확인 |
| 예전 실행을 Re-run 했더니 서비스가 옛 버전으로 | Re-run은 그 실행의 커밋(`dev-<7자리>`)으로 배포해요. 최신으로 맞추려면 Run workflow(세 서비스 전부) |
