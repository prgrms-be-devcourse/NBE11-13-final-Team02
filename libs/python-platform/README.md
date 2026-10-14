# mujuthaek-platform

파이썬 워커(collector·extraction)가 같이 쓰는 기술 코드예요. 업무 규칙은 넣지 않아요.

| 모듈 | 함수 | 하는 일 |
| --- | --- | --- |
| `ids` | `uuid7()` | 시간 순으로 정렬되는 UUID v7 (DB 기본 키) |
| `events` | `new_event(type, producer, payload)` | 이벤트 봉투 만들기 (`eventId`, `eventType`, `schemaVersion`, `occurredAt`, `producer`, `payload`) |
| `outbox` | `add(cur, event)` | 저장과 **같은 트랜잭션**에서 아웃박스 테이블에 이벤트 기록 |
|  | `relay_once(conn, channel)` | 안 보낸 이벤트를 RabbitMQ `mujuthaek.events`(topic)로 발행. 라우팅 키는 `eventType` |
| `broker` | `connect(url)` | RabbitMQ 연결 (pika) |
| `db` | `connect(url)` | PostgreSQL 연결 (psycopg 3) |
| `logging` | `setup(service)` | JSON 한 줄 로그. `extra` 값도 칸으로 남김 |
| `contracts` | `validate_event(event)` | `contracts/events/<eventType>.v<schemaVersion>.json`으로 검사 (테스트용, jsonschema 필요) |

## 쓰는 법
워커의 `pyproject.toml`에 경로로 연결해요.

```toml
dependencies = ["mujuthaek-platform"]

[tool.uv.sources]
mujuthaek-platform = { path = "../../libs/python-platform", editable = true }
```

```python
from mujuthaek_platform import outbox
from mujuthaek_platform.events import new_event

event = new_event("raw.notice.collected", "collector", payload)
with conn.transaction(), conn.cursor() as cur:
    cur.execute("insert into …")   # 업무 데이터 저장
    outbox.add(cur, event)          # 같은 트랜잭션

outbox.relay_once(conn, channel)   # 발행기 (5초마다)
```

## 아웃박스 테이블
각 워커의 마이그레이션에 이 모양으로 만들어요. `payload`에는 **봉투 전체**가 들어가요. 받는 쪽이 `eventId`로 중복을 걸러야 해서예요.

```sql
create table outbox (
    event_id       uuid primary key,
    event_type     text not null,
    schema_version int  not null,
    payload        jsonb not null,
    created_at     timestamptz not null default now(),
    published_at   timestamptz,
    attempts       int  not null default 0
);
```

발행은 "최소 한 번"이에요. 보낸 직후 DB를 갱신하기 전에 죽으면 같은 이벤트를 다시 보내요. 그래서 받는 쪽은 `processed_events`로 `eventId`를 한 번만 처리해요.

## 개발
```bash
cd libs/python-platform
uv run ruff check && uv run ruff format --check && uv run pytest
```
