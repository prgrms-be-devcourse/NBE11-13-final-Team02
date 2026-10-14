# NBE11-13-final-Team02 · 무주택자들 백엔드

공공 주거·창업지원 공고를 자동으로 모으고, AI로 자격 조건을 뽑아, 사용자가 지원할 수 있는지 알려주는 서비스의 백엔드예요.

## 구성
| 프로그램 | 언어 | 하는 일 |
| --- | --- | --- |
| `services/api` | 자바 25 + 스프링 부트 | 프론트의 단일 입구. 모듈 6개(notice, benefit, eligibility, area, user, notification) + `common` |
| `services/collector` | 파이썬 3.12 | 기관 게시판·공공 API 수집, 원본 저장 |
| `services/extraction` | 파이썬 3.12 | Claude API로 자격 조건·일정·금액 추출, 검증 |

EC2 1대에 Docker 컨테이너 3개, DB는 RDS(PostgreSQL + PostGIS) 1대에 데이터베이스 3개(collector, extraction, api)예요.

## 처음 실행
준비물: JDK 25, Python 3.12, [uv](https://docs.astral.sh/uv/), Docker Desktop

```bash
cp infra/.env.example infra/.env           # 값 채우기 (ANTHROPIC_API_KEY를 비우면 가짜 추출기로 동작)
make infra                                 # PostgreSQL·RabbitMQ·MinIO (make help로 명령 보기)
cd services/api && ./gradlew bootRun       # api
```
파이썬 워커는 `services/collector`, `services/extraction`에서 `uv sync` 후 `uv run python -m mujuthaek_collector run`처럼 실행해요.

## 주소 (로컬)
| 이름 | 주소 |
| --- | --- |
| api | http://localhost:8080 (`/actuator/health`) |
| RabbitMQ 관리 화면 | http://localhost:15672 |
| MinIO (S3 API) | http://localhost:9000 (minio / minio-pass, 버킷 `mujuthaek-raw`) |

## 담당
| 담당자 | 맡는 폴더 |
| --- | --- |
| 담당자1 | `infra/`, `libs/`, `.github/`, `services/collector/` (sources/lh·policy 제외), api `common` |
| 담당자2 | `services/extraction/` (rules/policy·startup_support 제외), api `notice` |
| 담당자3 | api `benefit`·`user`·`notification`, `collector/.../sources/policy`, `extraction/.../rules/policy`·`startup_support` |
| 담당자4 | api `eligibility`·`area`, `collector/.../sources/lh` |
| 모두 | `contracts/`, api `common/event/` |
  

## 문서
문서는 `.docs/`에 있어요.

| 문서 | 내용 |
| --- | --- |
| [개발 환경](.docs/개발%20환경.md) | 로컬 실행, Docker가 필요한 명령, 브랜치·PR·CI 규칙 |
| [결정 기록](.docs/adr/) | 브랜치 흐름과 CI 기준, 수집 방식 등 |
| [collector 설계](.docs/collector/무주택자들%20지방%20공사%20공고%20수집기%20설계.md) · [전체 코드](.docs/collector/무주택자들%20지방%20공사%20공고%20수집기%20전체%20코드.md) · [진행 현황](.docs/collector/진행%20현황.md) | 지방 공사 공고 수집기 |
