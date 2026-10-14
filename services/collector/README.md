# collector (지방 공사 공고 수집 워커)

지방 공사 9곳의 주거 공고 게시판에서 새 모집공고를 모아요. 공고문 글자와 호별 임대조건을 뽑아 저장하고, `raw.notice.collected` 이벤트를 RabbitMQ로 보내요.
설계와 전체 코드: `.docs/collector/`

## 처음 실행
```bash
# 저장소 루트
make infra               # PostgreSQL·RabbitMQ·MinIO
make migrate-collector   # collector 테이블

# 여기 (services/collector)
uv sync
uv run pytest
uv run python -m mujuthaek_collector run-once dcco   # 대전 한 번 수집 (환경 변수 없이 로컬 값 사용)
```

## 명령
`uv run python -m mujuthaek_collector <명령>`

| 명령 | 하는 일 |
| --- | --- |
| `run` | 스케줄러로 계속 실행 + `:8091/health` (Docker 기본) |
| `run-once <출처> [--recheck]` | 출처 하나를 지금 수집하고 이벤트까지 발행. `--recheck`면 마감 전 공고를 다시 열어 정정 확인 |
| `run-all [--recheck]` | `sources.enabled`인 출처를 순서대로 |
| `relay` | 아웃박스 이벤트를 한 번 보내기 |
| `cleanup` | 보관 기한이 지난 원본 파일만 S3에서 지우기 |
| `save-fixtures <출처> [--details 3]` | 실제 목록·상세 화면을 `tests/fixtures/<출처>/`에 저장 (실제 사이트에 요청해요) |
| `status` | 최근 수집 회차 20개 |

출처: `sh`, `gh`, `bmc`, `ih`, `dcco`, `dudc`, `umca`, `gbdc`, `gndc`

## 확인
| 무엇 | 어떻게 |
| --- | --- |
| 저장된 글 | `docker compose -f infra/docker-compose.yml --env-file infra/.env exec postgres psql -U collector_app -d collector -c "select source_code, post_key, status, title from raw_posts order by created_at desc limit 20"` |
| 원본 파일 | `docker compose -f infra/docker-compose.yml --env-file infra/.env exec minio mc ls --recursive local/mujuthaek-raw/raw/` |
| 보낸 이벤트 | RabbitMQ 관리 화면 http://localhost:15672 → Exchanges `mujuthaek.events` |
| 안 보낸 이벤트 | `select count(*) from outbox where published_at is null` |

## 테스트
```bash
uv run pytest                                  # 기관 사이트·S3·RabbitMQ에 요청하지 않아요
uv run pytest tests/test_sources.py -k dcco    # 한 출처만
```
- 실제 DB 테스트(`test_storage_postgres.py`)는 `COLLECTOR_TEST_DB_URL`이 있을 때만 돌아요. 테스트용 DB는 처음 한 번만 만들어요.

  ```bash
  # 저장소 루트
  docker compose -f infra/docker-compose.yml --env-file infra/.env exec postgres psql -U postgres -c "create database collector_test owner collector_app"
  docker compose -f infra/docker-compose.yml --env-file infra/.env --profile apps run --rm migrate-collector \
    -url=jdbc:postgresql://postgres:5432/collector_test -user=collector_app -password=collector-pass -locations=filesystem:/flyway/sql migrate
  # services/collector
  COLLECTOR_TEST_DB_URL=postgresql://collector_app:collector-pass@localhost:5432/collector_test uv run pytest tests/test_storage_postgres.py
  ```

- 저장 화면 테스트(`test_fixtures.py`)는 `save-fixtures`로 저장한 출처만 돌아요.

## 새 기관 붙이기
1. `sources/local/`에 `Source`를 상속한 파일을 추가해요. `list_request`, `parse_list`, `parse_detail` 세 가지만 구현하면 돼요.
2. `sources/registry.py`의 `build_sources`와 `ORDER`에 추가해요.
3. 새 마이그레이션으로 `sources` 테이블에 한 줄 넣어요. 기존 마이그레이션 파일은 고치지 않아요.
4. `save-fixtures <출처>`로 화면을 저장하고 테스트해요.

## Docker
```bash
docker build -f services/collector/Dockerfile -t mujuthaek-collector .   # 저장소 루트에서
make up                                                                   # 스케줄러까지 띄우기 (정해진 시간에 실제 사이트 수집)
```
