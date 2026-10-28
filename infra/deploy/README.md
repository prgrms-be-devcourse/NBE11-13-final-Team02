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
deploy.sh          shovel.sh
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

## 개발 서버로 수집 이벤트 복사 (shovel, 한 번만)
개발 서버에는 수집기가 없어요. 운영 RabbitMQ의 `dev.mirror` 큐(3일·2만 건까지)가 `mujuthaek.events`의
`raw.notice.collected`·`raw.policy.collected`·`area.source.ready`를 받아 두고, 개발 RabbitMQ의 shovel이 가져가요.
```bash
# 운영 서버 (/opt/mujuthaek): dev.mirror 큐·바인딩, 읽기 전용 계정 shovel (암호는 입력)
./shovel.sh prod
# 개발 서버 (/opt/mujuthaek): from-prod shovel (같은 암호 입력)
./shovel.sh dev
```
상태는 개발 서버 관리 화면(`ssh -L 15672:127.0.0.1:15672 ubuntu@<dev>` → http://localhost:15672 → Admin → Shovel Status).
개발 서버의 extraction은 `EXTRACTION_MODEL_MODE=recorded`라 AI를 부르지 않아요.

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
- ENV_FILE: `FRONTEND_URL=https://mujuthaek-fe.vercel.app`(로그인 뒤 돌아갈 주소), `CORS_ORIGINS`에 dev 프론트 주소
- 브라우저는 Vercel 주소만 불러요. api는 nginx가 넘긴 X-Forwarded-Host·Proto로 OAuth redirect-uri를 `https://<Vercel 주소>` 기준으로 만들고,
  refresh 토큰 쿠키(`Path=/api/auth; Secure; HttpOnly; SameSite=Lax`, Domain 없음)도 Vercel 주소에 저장돼요

## 주의
- `.env` 값에 `$`가 있으면 compose가 변수로 읽어요. 암호에 `$`를 쓰지 않거나 `$$`로 써요
- 파이썬 워커의 RabbitMQ 주소는 `amqp://RABBITMQ_USER:RABBITMQ_PASSWORD@rabbitmq:5672/`로 만들어요. 암호에 `@ : / ? #`가 있으면 안 돼요
- Flyway 계정은 기본 `collector_app`·`extraction_app`이에요. 다르면 ENV_FILE에 `COLLECTOR_DB_USER`·`EXTRACTION_DB_USER`를 넣어요
- 관리 화면(15672)·api(8080)·notification(8081)은 서버 안(127.0.0.1)에서만 열려요. 밖에서는 nginx(:80)만
