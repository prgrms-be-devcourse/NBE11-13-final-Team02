"""area.source.ready 이벤트 (contracts/events/area.source.ready.v1.json).

eventId만 팀 규칙(UUID v7)과 달리 (원천, 범위, sha256)으로 만든 UUID v5예요. 같은 파일로 명령을 두 번 돌려도 같은 ID라,
아웃박스에도 한 번만 들어가고 api도 processed_events로 한 번만 처리해요.
"""

import uuid
from datetime import date
from typing import Any

from mujuthaek_platform.events import new_event

EVENT_TYPE = "area.source.ready"


def event_id(source: str, scope: str, sha256: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"https://mujuthaek.kr/events/{EVENT_TYPE}/{source}/{scope}/{sha256}"))


def ready_event(source: str, scope: str, s3_key: str, sha256: str, data_date: date, file_name: str | None = None, rows: int | None = None) -> dict[str, Any]:
    payload: dict[str, Any] = {"source": source, "scope": scope, "s3Key": s3_key, "sha256": sha256, "dataDate": data_date.isoformat()}
    if file_name is not None:
        payload["fileName"] = file_name
    if rows is not None:
        payload["rows"] = rows
    event = new_event(EVENT_TYPE, "collector", payload)
    event["eventId"] = event_id(source, scope, sha256)
    return event


def record(conn, event: dict[str, Any]) -> bool:
    """아웃박스에 적어요. 같은 eventId가 이미 있으면(보냈든 안 보냈든) 적지 않고 False. 발행은 기존 relay가 해요."""
    from psycopg.types.json import Jsonb

    cur = conn.execute(
        "insert into outbox (event_id, event_type, schema_version, payload) values (%s, %s, %s, %s) on conflict (event_id) do nothing",
        (event["eventId"], event["eventType"], event["schemaVersion"], Jsonb(event)),
    )
    return cur.rowcount == 1
