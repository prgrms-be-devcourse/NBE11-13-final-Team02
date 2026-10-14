import json
import logging
import uuid
from contextlib import contextmanager

import pytest
from jsonschema import ValidationError

from mujuthaek_platform import outbox
from mujuthaek_platform.contracts import validate_event
from mujuthaek_platform.events import new_event
from mujuthaek_platform.ids import uuid7
from mujuthaek_platform.logging import JsonFormatter


def test_uuid7_version_variant_and_order():
    ids = [uuid7() for _ in range(2000)]
    assert all(i.version == 7 and i.variant == uuid.RFC_4122 for i in ids)
    assert ids == sorted(ids, key=lambda i: i.int)  # 같은 밀리초 안에서도 만든 순서대로
    assert len(set(ids)) == len(ids)


def test_new_event_envelope():
    event = new_event("raw.notice.collected", "collector", {"a": 1})
    assert uuid.UUID(event["eventId"]).version == 7
    assert event["schemaVersion"] == 1 and event["producer"] == "collector" and event["payload"] == {"a": 1}
    assert event["occurredAt"].endswith("Z")


class FakeConn:
    """outbox.relay_once가 쓰는 만큼만: transaction(), execute().fetchall()"""

    def __init__(self, rows):
        self.rows = rows
        self.updated = []

    @contextmanager
    def transaction(self):
        yield

    def execute(self, sql, params=None):
        if sql.startswith("update"):
            self.updated.append(params[0])
        return self

    def fetchall(self):
        return self.rows


class FakeChannel:
    def __init__(self):
        self.declared, self.sent = [], []

    def exchange_declare(self, *args, **kwargs):
        self.declared.append(kwargs)

    def basic_publish(self, exchange, routing_key, body, properties):
        self.sent.append((exchange, routing_key, json.loads(body), properties.message_id))


def test_relay_once_publishes_envelope_with_routing_key():
    event = new_event("raw.notice.collected", "collector", {"title": "공고"})
    conn = FakeConn([(event["eventId"], event["eventType"], event)])
    channel = FakeChannel()
    assert outbox.relay_once(conn, channel) == 1
    assert channel.declared[0]["exchange"] == "mujuthaek.events" and channel.declared[0]["durable"]
    exchange, key, body, message_id = channel.sent[0]
    assert (exchange, key, message_id) == ("mujuthaek.events", "raw.notice.collected", event["eventId"])
    assert body == event and conn.updated == [event["eventId"]]


def sample_event(**payload):
    data = {
        "rawPostId": str(uuid7()),
        "version": 1,
        "sourceCode": "dcco",
        "provider": "DCCO",
        "regionCode": None,
        "field": "HOUSING",
        "kind": "NOTICE",
        "title": "2026년 국민임대 입주자 모집공고",
        "postedOn": "2026-10-01",
        "detailUrl": "https://www.dcco.kr/web/board/view.do?mId=43&brdIdx=1",
        "supersedesPostId": None,
        "text": "제목: …",
        "textKey": None,
        "units": [{"houseType": "26A", "areaM2": 26.37, "depositWon": 12_000_000, "rentWon": 150_000}],
        "files": [{"name": "모집공고문.pdf", "downloadUrl": "https://a.kr/f/1", "sha256": "ab" * 32}],
        "reprocess": False,
    }
    data.update(payload)
    return new_event("raw.notice.collected", "collector", data)


def test_contract_accepts_valid_event():
    validate_event(sample_event())


@pytest.mark.parametrize(
    "payload",
    [
        {"kind": "SOMETHING"},
        {"units": [{"depositWon": "12,000"}]},  # 금액은 원 단위 정수
        {"files": [{"name": "a.pdf", "downloadUrl": "x", "sha256": "short"}]},
        {"regionCode": "123"},  # 시군구 코드는 5자리
        {"extraField": 1},
    ],
)
def test_contract_rejects_invalid_payload(payload):
    with pytest.raises(ValidationError):
        validate_event(sample_event(**payload))


def test_json_log_includes_extra_fields():
    record = logging.LogRecord("collector", logging.INFO, __file__, 1, "수집 끝", None, None)
    record.source, record.new = "dcco", 3
    line = json.loads(JsonFormatter("collector").format(record))
    assert line["message"] == "수집 끝" and line["service"] == "collector"
    assert line["source"] == "dcco" and line["new"] == 3
