"""아웃박스: 저장과 같은 트랜잭션에서 이벤트를 테이블에 적고, 발행기가 나중에 RabbitMQ로 보내요.
그래서 "저장은 됐는데 이벤트가 사라지는" 일이 없어요.

필요한 테이블 (각 워커의 마이그레이션에 있어요)
  outbox(event_id uuid pk, event_type text, schema_version int, payload jsonb,
         created_at timestamptz, published_at timestamptz, attempts int)
  payload에는 봉투(events.new_event의 결과) 전체를 넣어요. 받는 쪽이 eventId로 중복을 걸러야 해서예요.

보내기는 "최소 한 번"이에요. 보낸 직후 DB 갱신 전에 죽으면 다음 차례에 같은 이벤트를 다시 보내요.
받는 쪽은 processed_events 테이블로 eventId를 한 번만 처리해요.
"""

import json
from typing import Any

EXCHANGE = "mujuthaek.events"
BATCH_SIZE = 100


def add(cur, event: dict[str, Any]) -> None:
    """이벤트를 아웃박스에 적어요. 저장과 같은 트랜잭션의 커서(cur)를 넘겨요."""
    from psycopg.types.json import Jsonb

    cur.execute(
        "insert into outbox (event_id, event_type, schema_version, payload) values (%s, %s, %s, %s)",
        (event["eventId"], event["eventType"], event["schemaVersion"], Jsonb(event)),
    )


def _properties(event_id: str, event_type: str):
    import pika

    return pika.BasicProperties(
        content_type="application/json",
        content_encoding="utf-8",
        delivery_mode=2,  # 브로커가 다시 시작해도 메시지를 잃지 않게 디스크에 저장
        message_id=event_id,
        type=event_type,
    )


def relay_once(conn, channel, limit: int = BATCH_SIZE) -> int:
    """안 보낸 이벤트를 오래된 것부터 최대 limit개 보내고, 보낸 수를 돌려줘요.

    exchange는 mujuthaek.events(topic, durable)이고 라우팅 키는 eventType이에요.
    여러 발행기가 동시에 돌아도 같은 행을 두 번 잡지 않게 `for update skip locked`로 가져와요.
    """
    channel.exchange_declare(exchange=EXCHANGE, exchange_type="topic", durable=True)
    sent = 0
    with conn.transaction():
        rows = conn.execute(
            "select event_id, event_type, payload from outbox where published_at is null "
            "order by created_at limit %s for update skip locked",
            (limit,),
        ).fetchall()
        for event_id, event_type, payload in rows:
            event = json.loads(payload) if isinstance(payload, str) else payload
            channel.basic_publish(
                exchange=EXCHANGE,
                routing_key=event_type,
                body=json.dumps(event, ensure_ascii=False).encode("utf-8"),
                properties=_properties(str(event_id), event_type),
            )
            conn.execute(
                "update outbox set published_at = now(), attempts = attempts + 1 where event_id = %s",
                (event_id,),
            )
            sent += 1
    return sent
