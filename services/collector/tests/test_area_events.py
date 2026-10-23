from datetime import date

import pytest
from jsonschema import ValidationError

from mujuthaek_collector.area.events import event_id, ready_event, record
from mujuthaek_platform.contracts import validate_event

SHA = "6505ddc859141a3c7526f2c3e0aec101afc4d7fdc52bb38a2bd7f54b67f917dd"


def stores_event():
    return ready_event("STORES", "강원", "area/STORES/2026-06-30/stores_gangwon.csv", SHA, date(2026, 6, 30), "원본.csv", 110206)


def test_event_follows_the_contract():
    validate_event(stores_event())
    validate_event(ready_event("BUS_STOP", "ALL", "area/BUS_STOP/2025-10-31/bus_stop.csv", SHA, date(2025, 10, 31)))


def test_event_id_is_fixed_by_source_scope_and_fingerprint():
    # contracts/examples/area.source.ready.json의 eventId와 같아야 해요 (같은 규칙)
    assert stores_event()["eventId"] == "1591c7fa-581b-5e5a-9ffe-2d28b6300247"
    assert stores_event()["eventId"] == stores_event()["eventId"]
    assert event_id("STORES", "경기", SHA) != event_id("STORES", "강원", SHA)


def test_contract_catches_a_wrong_scope():
    event = stores_event()
    event["payload"]["scope"] = "ALL"
    with pytest.raises(ValidationError):
        validate_event(event)


class FakeConn:
    def __init__(self):
        self.ids = set()

    def execute(self, sql, params):
        assert "on conflict (event_id) do nothing" in sql
        new = params[0] not in self.ids
        self.ids.add(params[0])
        return type("Cursor", (), {"rowcount": 1 if new else 0})()


def test_record_writes_each_event_once():
    conn = FakeConn()
    assert record(conn, stores_event()) is True
    assert record(conn, stores_event()) is False
