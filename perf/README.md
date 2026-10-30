# 성능 테스트 (perf)

운영(prod)·dev 서버에 k6로 부하를 주고, 그 구간의 숫자·그래프 이미지·느린 쿼리·버전·데이터 양을 **결과 폴더 하나**에 모아요. 규모를 키워 재려면 더미 데이터(`perf/seed`)를 로컬·dev에 넣어요(「세 단계」). 개선 전에 잰 폴더와 개선 후 폴더를 `compare.sh`로 나란히 비교해요.

```
노트북                                         운영 EC2 (127.0.0.1에만 열림)
 k6 ──────── http://3.37.131.73 (nginx) ────▶ api
 Prometheus ◀─ SSH 터널(tunnel.sh) ─────────── api·notification /actuator/prometheus, node-exporter, cAdvisor
 Grafana(3300) + 이미지 렌더러                  RDS ◀─ server.sh가 서버 안에서 psql (pg_stat_statements)
```

## 세 단계

운영 데이터는 작아서(공고 22, 혜택 398) 검색·판정 쿼리가 실제 규모에서 어떤지 알 수 없어요. 그래서 세 번 나눠 재요.

| 단계 | 어디에 | 데이터 | 무엇을 | 명령 |
|---|---|---|---|---|
| ① | 운영(prod) | 운영 그대로 | 지금 서버의 기준선 | `perf/run.sh <test>` (아래 「테스트 날 순서」) |
| ② | 로컬 | 더미(공고 1만, 회원 10만 …) | 느린 쿼리·인덱스 점검, 스모크 | `seed.sh local up` → `explain.sh local` → `BASE_URL=http://localhost:8080 run.sh --data seed smoke` |
| ③ | dev | 더미 | 부하·스트레스 | `seed.sh dev up` → `BASE_URL=http://<dev> run.sh --data seed <test>` → `seed.sh dev down` |

> ⚠️ **dev 테스트 때는 운영도 느려져요.** dev의 api_dev는 운영 api와 **같은 RDS 인스턴스**에 있어요. 더미를 넣고 빼는 동안(각 수 분)과 부하를 주는 동안 RDS CPU·IO를 같이 써서 운영 응답도 느려질 수 있어요. 팀과 운영 채널에 시간을 공지하고, 사용자가 적은 시간에 해요. 끝나면 바로 `seed.sh dev down` 해요.

## 더미 데이터 (perf/seed)

운영에서 본보기(공고·위치·생활권 점수, 혜택·조건·지원 내용, 시군구 중심점)를 **읽기만 해서** 가져와, 그걸 돌려 쓰고 흔들어 규모를 키워요.

| 표 | 더미 수 | 만드는 법 |
|---|---|---|
| 공고 `notice.notices` + 버전·지역·위치·생활권 점수 | 1만 | 운영 공고를 돌려 쓰고(payload는 그대로, 제목 뒤 `#번호`), 지역은 서울 30 · 경기 30 · 인천 8 · 나머지(%), 상태는 모집 중 40 · 마감 45 · 예정 10 · 상시 5(%), 위치는 시군구 중심 ±3km, 생활권 점수는 운영 점수 ±10 |
| 혜택 `benefit.benefits` + 조건·지원 내용 | 5천, 조건 5~10개씩 | 운영 혜택을 돌려 쓰고 조건이 모자라면 운영 조건 전체에서 실제 빈도대로 더해요. 상태 열림 55 · 예정 10 · 마감 15 · 숨김 20, 지역 전국 20 · 시도 15 · 시군구 65(%) |
| 회원 `account.users` | 10만 | 닉네임 `perf-seed-<n>`, 이메일 없음(메일·푸시가 나가지 않아요) |
| 관심 `account.favorites` | 약 50만 | 회원마다 0~10개, 공고 70 · 혜택 30(%) |
| 알림 `notification.notifications` | 약 200만 | 회원마다 0~40개, 최근 60일, 70% 읽음 |

- **표시**: 더미 id는 모두 `5eed00TT-…`(표마다 TT가 달라요, `sql/guard.sql`)라서 한 번에 지워요. 혜택은 `source_code = 'perf-seed'`도 달아요.
- **같은 더미**: 같은 본보기면 같은 더미가 나와요(setseed). 그래서 로컬에서 만든 `k6/data/seed.json`(공고·혜택 id 샘플)을 dev에서도 써요.
- **막는 것**: prod(api DB)에는 못 넣어요. `seed.sh`가 서버(`DEV_EC2_HOST` ≠ 운영)와 서버 .env의 DB 이름(`api_dev`)을 보고, DB 안에서 `sql/guard.sql`이 DB 이름(`api`면 거부)·RDS 여부를 한 번 더 봐요.

```bash
perf/seed/fetch-templates.sh            # 운영에서 본보기 읽기(SELECT만) → perf/seed/templates/ (커밋 안 해요). 단계 ②·③ 동안 다시 받지 않아요
perf/seed/seed.sh local up              # 넣기(한 트랜잭션, 로컬 약 4~5분) → 끝에 표별 「전체 | 더미」
perf/seed/seed.sh local count           # 표별 전체 수와 더미 수
perf/seed/k6-data.sh local              # k6/data/seed.json (지역·공고·혜택 id 샘플, 지원자 8벌). 넣은 뒤 한 번, 커밋해요
perf/k6/make-tokens.sh local            # 더미 회원 1~1,000번 토큰 → k6/data/tokens.json (커밋 안 해요, 8시간 유효)
perf/seed/explain.sh local              # 주요 쿼리 실행 계획 → perf/results/explain/<날짜>_local/summary.md
BASE_URL=http://localhost:8080 perf/run.sh --data seed smoke
perf/seed/seed.sh local down            # 지우기(자식 표부터, 테스트 중 진짜 회원이 더미에 붙인 관심·알림도) + VACUUM ANALYZE
```

dev는 `local` 대신 `dev`예요. 넣기·지우기 전에 한 번 더 물어요(`SEED_YES=1`이면 묻지 않아요). `make-tokens.sh dev`는 dev 서버의 JWT_SECRET(`DEV_JWT_SECRET`)으로 서명해요.

### 로컬에서 잰 시간 (2026-10-30, MacBook · Docker postgis 17)

| 단계 | 걸린 시간 |
|---|---|
| `fetch-templates.sh` (운영 읽기) | 9초 |
| `seed.sh local up` | 206초 ~ 282초 (알림 200만이 1분 40초 ~ 3분) |
| `seed.sh local down` | 31초 (VACUUM 포함 93초) |
| `explain.sh local` | 15초 |
| `run.sh --data seed smoke` | 1분 10초 ~ 1분 20초 |

### 로컬 점검 결과 (단계 ②, 2026-10-30)

스모크(2명): 33건 모두 2xx, 5xx 0. 하지만 목록·검색 p95가 **810ms**로 기준(500ms)을 넘었어요(판정 p95 839ms, 조회 p95 164ms). 운영 데이터 규모에서는 넘지 않던 값이에요. `explain.sh local`이 찾은 후보(적기만 했어요, 코드·마이그레이션은 그대로):

| 쿼리 | 실행 시간 | 후보 |
|---|---:|---|
| 판정 입력 payload 300개 (`notices/search`·`home/summary`, 판정 캐시가 빌 때) | 473ms | 판정에 쓰는 judgment·notice만 따로 저장, 또는 캐시(2,000개 LRU)를 데워 두기. 첫 검색이 5초 걸렸어요 |
| 열린 혜택 전부 (`benefits/search`·`home/summary`, 지역 없음) | 209ms | 대부분 병렬 작업자를 띄우는 시간이에요(노드 시간 3ms). 지역 앞자리 `like`는 GIN을 못 써요. 요청마다 열린 혜택 3천여 개와 조건 전부를 읽어 Java에서 판정해요 → 시도 코드 배열 + GIN, 또는 열린 혜택 캐시 |
| 공고 후보 (시도 필터) | 7ms | `left(sigungu_code, 2)`가 `notice_regions_code_idx`를 못 써 표 전체를 훑어요 → 식 인덱스 또는 `like '11%'` |
| 공고 후보 (지역 없음) | 7ms | 마감순 300개를 뽑으려 공고 표 전체를 훑어요 → `(field, coalesce(apply_end, …), id)` 부분 인덱스 |
| 지도 (서울) | 8ms | `distinct on (notice_id)` 정렬 + 요약의 공고별 하위 쿼리(최대 1,000번) |
| 관심 목록 | 0.1ms | 쿼리는 빨라요. 다만 혜택·지원 사업 관심을 한 줄씩 따로 읽어요(N+1) |

관심(50만)·알림(200만) 목록은 인덱스로 0.1ms 안이라 지금은 후보가 없어요. dev(RDS)에서 다시 재면 숫자가 달라질 수 있어요(`explain.sh dev`).

### 더미 기준 부하 프로필 (`--data seed`)

응답 시간·에러율 기준은 운영 기준과 같아요. 운영 기준 프로필(`k6/lib/config.js`의 `PROFILES`)은 그대로 두고 `SEED_PROFILES`를 따로 써요.

| test | 모양 |
|---|---|
| `smoke` | 2명 1분 |
| `load` | 100명 (2분 올리고 15분 유지) |
| `load200` | 200명 (같은 모양) |
| `stress` | 100 → 200 → 400 → 600 → 800 → 1,000명, 단계마다 3분 |
| `breakpoint` | 초당 20건부터 2분마다 +20 |
| `spike` | 20 → 600 → 20명 |
| `soak` | 100명 1시간 |

- 흐름 비중: 둘러보기 50 · 판정 25 · 로그인 25(%). 로그인 흐름은 더미 회원 1,000명의 토큰을 VU마다 돌려 써요(관심 등록·해제도 더미 회원이 해요). 둘러보기에 혜택 상세가 더해져요.
- 결과 폴더는 `<시각>_<test>_<대상>-<데이터>_<api 버전>`(예: `20261031-1400_load_dev-seed_v0.2.1`), meta.json에 `target`·`data`·`data_file`·`member_tokens`가 남아요.
- dev는 서버 지표 터널을 `perf/tunnel.sh dev`로 열어요. node-exporter·cAdvisor는 운영 compose에만 있어서 dev는 서버 CPU·컨테이너 그래프가 빠져요(api·DB 쪽은 나와요).
- dev에서 pg_stat_statements는 RDS 인스턴스 전체라 운영 쿼리도 섞여요. `slow_queries.csv`는 그걸 감안해서 봐요.
- dev 사이트 화면에 더미 공고·혜택(제목 끝 `#번호`)이 보여요. 마감 알림 작업이 더미 관심 공고로 더미 회원에게 알림 행을 만들 수 있지만, 이메일·푸시 구독이 없어서 밖으로 나가는 것은 없고 `down`이 지워요.

## 처음 한 번 (사용자가 할 일)

| 언제 | 할 일 | 비고 |
|---|---|---|
| 지금 | AWS 보안 그룹: 내 IP에서 EC2 22·80 열려 있는지 | |
| 지금 | EC2 크레딧 모드(Standard·Unlimited) 확인 | Standard면 테스트 사이 30분 쉬기 |
| 지금 | `cp perf/.env.example perf/.env` 후 SSH 키·인스턴스 id 채우기 | `perf/.env`는 커밋되지 않아요 |
| 지금 | `perf/server.sh version` 으로 SSH·버전 확인 | |
| 단계 ③ 전 | `perf/.env`에 `DEV_EC2_HOST`, `DEV_JWT_SECRET`(dev 서버 JWT_SECRET), 선택 `DEV_EC2_INSTANCE_ID` | dev 서버 SSH 키가 운영과 같은지 확인(`EC2_HOST=<dev> perf/server.sh version`) |
| 지금(권장) | RDS **api DB**에 `CREATE EXTENSION IF NOT EXISTS pg_stat_statements;` (마스터 계정) | `shared_preload_libraries`에 없으면 파라미터 그룹 수정 + 재부팅. `perf/server.sh check`로 확인 |
| 지금(선택) | 테스트 계정: 소셜 로그인으로 가입 → 회원 id를 `TEST_USER_ID`에, 운영 `JWT_SECRET`을 `perf/.env`에 | 비우면 로그인 흐름(15%)을 판정 흐름으로 돌려요. **나중에 넣으면 baseline을 다시 재야 해요** |
| 배포 | 이 브랜치(지표 노출)가 prod에 배포돼 있어야 해요 | `/actuator/prometheus`가 있어야 서버 쪽 숫자가 나와요 |
| 테스트 날 | 운영 슬랙 알림 채널에 공지(스트레스·스파이크에서 5xx 알림이 몰려요), 팀에 시간 공지(RDS를 dev와 같이 써요) | dev 테스트(단계 ③)도 같아요: 운영이 같이 느려져요 |

## 테스트 날 순서

```bash
docker compose -f perf/docker-compose.yml up -d   # Prometheus·Grafana (http://localhost:3300)
perf/server.sh exporters-up                        # 서버에 node-exporter·cAdvisor
perf/tunnel.sh                                     # ← 다른 터미널에 켜 두기

perf/run.sh smoke                                  # 먼저 스모크로 결과 폴더가 다 차는지 확인
perf/run.sh load
perf/run.sh soak                                   # 1시간, 18시(11/7부터 19시) 자동 중지 전에
perf/run.sh stress                                 # 사이사이 30분 쉬기(크레딧)
perf/run.sh breakpoint
perf/run.sh spike

perf/server.sh exporters-down                      # 다 끝나면
```

- 11:00~11:45에는 돌리지 않아요. 일일 정리 작업(혜택 상태, 토큰·outbox·요청 로그 정리)이 이때 돌아요.
- k6 종료 코드 99는 합격 기준을 넘었다는 뜻이고, 결과는 그대로 모아요. 스트레스·한계 찾기는 기준을 넘고 1분 뒤 스스로 멈춰요.
- 끝나면 문서 「결과 기록」 표에 붙일 한 줄을 출력해요.

## 결과 폴더

`perf/results/<yyyyMMdd-HHmm>_<test>_<api 버전>/` (커밋되지 않아요. 지우지 말고 보관해요)

| 파일 | 내용 |
|---|---|
| `meta.json` | 시각, api·notification 버전, 부하, 메모, 로그인 흐름 포함 여부, k6 종료 코드, perf 스크립트 커밋, data.json 해시 |
| `summary.json` · `k6.log` · `k6-report.html` | k6 결과(API 종류별 p95·p99, RPS, 에러율). html은 브라우저로 여는 그래프 리포트 |
| `server_summary.json` | 서버 p95·p99, CPU 평균·최대, steal, 힙, GC, Hikari 사용·대기 최대, 컨테이너별 CPU·메모리 |
| `server_by_uri.csv` | API(경로)별 서버 p95·p99·평균·건수·5xx |
| `stages.csv` | 스트레스·한계 찾기만: 단계별 RPS·에러율·p95, 기준을 처음 넘은 단계(`over`) |
| `slow_queries.csv` | 테스트 동안 DB 시간을 많이 쓴 쿼리 50개 (pg_stat_statements 전후 차이) |
| `table_counts.csv` | 테이블별 행 수 — 전후에 데이터 양이 크게 다르면 비교가 틀어져요 |
| `images/00-dashboard.png`, `01~15.png` | Grafana 패널 이미지(응답 시간, RPS, 에러율, 서버 p95·p99, CPU·steal, 컨테이너, 힙, GC, Hikari …) |
| `images/cw-*.png` · `cloudwatch.json` | CloudWatch: EC2 CPU·크레딧, RDS CPU·커넥션·메모리·IO |
| `timeseries/NN.csv` | 패널과 같은 쿼리의 15초 간격 숫자 |
| `prometheus_snapshot.txt` | Prometheus 스냅숏 이름. 원본 시계열은 `perf-prometheus-data` 볼륨에 남아요 |

CloudWatch는 몇 분 늦게 채워져요. 비어 있으면 10분 뒤 `perf/collect.sh perf/results/<폴더>`로 다시 모아요(그래프·서버 지표도 다시 받아요).

## 고친 뒤

```bash
perf/run.sh load "Hikari 풀 10 → 20"                # 메모가 meta.json과 표의 「고친 것」에 들어가요
perf/compare.sh perf/results/<전 폴더> perf/results/<후 폴더> > 비교.md
```

비교가 맞으려면 같게 둬요: `k6/data/data.json`(다시 뽑지 않기), `k6/lib/config.js`·`flows.js`(바꾸면 meta.json의 perf 커밋이 달라져요), 테스트 시간대, 로그인 흐름 포함 여부. 한 번에 하나만 고쳐요.

## 시나리오

`k6/main.js`가 반복마다 흐름 하나를 골라요: 둘러보기 60% · 조건 넣고 판정 25% · 로그인 사용자 15%(더미 기준은 50 · 25 · 25), 행동 사이 3~5초. 한계 찾기는 쉬는 시간 없이 반복 한 번에 요청 한 건이라 초당 반복 수가 곧 RPS예요.

| 종류(태그 `type`) | p95 / p99 기준 | API |
|---|---|---|
| 조회 `lookup` | 200ms / 500ms | regions, eligibility/criteria, push/public-key, users/me·favorites·notifications·notification-settings, 관심 등록·해제 |
| 목록·검색 `search` | 500ms / 1s | home/summary, notices/search, notices/map, notices/{id}, benefits·spaces·supports/search (조건 없이) |
| 판정 `judge` | 1s / 2s | benefits/search·notices/search·home/summary + 지원자 조건 |

- 모든 검색은 POST + JSON 바디예요. 지원자 조건은 `data.json`의 가짜 조합 4개(사람 값 아님)에 시군구만 바꿔 넣어요.
- `/api/auth/refresh`는 뺐어요: refresh 토큰이 HttpOnly 쿠키라 쓰면 테스트 계정 로그인이 돌아가며 바뀌어요.
- 관심 등록은 등록 직후 해제해서 운영 DB에 남지 않아요. 남았으면 테스트 계정 화면에서 지워요.
- 외부 API를 부르는 기능(주소 좌표, AI 추출)과 관리자 API는 부르지 않아요.

## 알아 둘 것

- 요청마다 `RequestLogFilter`가 요청 로그를 DB에 써요(5초마다 모아서). 운영과 같은 조건이라 그대로 두고 재요. 전후 비교도 같은 조건이에요.
- api DB 커넥션 풀(Hikari)은 기본값 10, 가상 스레드 켜짐이에요. 유력한 병목이지만 baseline을 재기 전엔 바꾸지 않아요.
- `k6/data/data.json`은 `k6/fetch-data.sh`로 운영에서 뽑은 값이에요. baseline 뒤에는 다시 뽑지 않아요.
- Grafana 대시보드를 고치려면 `lib/dashboard.py`를 고치고 `python3 perf/lib/dashboard.py`. 패널 순서(id)는 결과 이미지 이름이라 끝에만 더해요.
