"""JobStore를 실제 PostgreSQL에서 확인합니다. EXTRACTION_TEST_DB_URL이 있을 때만 돌고, 임시 스키마에 migrations/를 적용해 씁니다.

EXTRACTION_TEST_DB_URL=postgresql://extraction_app:extraction-pass@127.0.0.1:5432/extraction uv run pytest tests/test_store_postgres.py
"""

import os
import uuid
from pathlib import Path

import pytest
from test_pipeline import answers, new_event, raw_event, run
from test_worker_parts import pending_item

from mujuthaek_extraction.model import RecordedModel
from mujuthaek_extraction.model.factory import NoticeModels
from mujuthaek_extraction.pipeline import Outcome
from mujuthaek_extraction.review_api import ReviewService
from mujuthaek_extraction.store import JobStore

DB_URL = os.getenv("EXTRACTION_TEST_DB_URL")
pytestmark = pytest.mark.skipif(not DB_URL, reason="EXTRACTION_TEST_DB_URL이 없어 실제 DB 검사를 건너뜁니다")
MIGRATIONS = Path(__file__).resolve().parents[1] / "migrations"


@pytest.fixture
def store():
    import psycopg

    schema = "test_" + uuid.uuid4().hex[:12]
    conn = psycopg.connect(DB_URL, autocommit=True)
    conn.execute(f"create schema {schema}")
    conn.execute(f"set search_path to {schema}")
    for path in sorted(MIGRATIONS.glob("V*.sql")):
        conn.execute(path.read_text(encoding="utf-8"))
    try:
        yield JobStore(conn)
    finally:
        conn.execute(f"drop schema {schema} cascade")
        conn.close()


def count(store, sql):
    return store.conn.execute(sql).fetchone()[0]


def test_completed_job_writes_outbox_and_processed_event_together(store):
    event = raw_event()
    outcome = run(event, NoticeModels(RecordedModel(answers()), None))
    job_id = store.save(event, outcome)
    assert count(store, "select count(*) from outbox") == 1
    assert store.already_processed(event["eventId"])
    assert count(store, "select count(*) from review_queue") == 0
    status, payload = store.conn.execute("select status, payload->'payload'->>'reviewStatus' from extraction_jobs, outbox").fetchone()
    assert (status, payload) == ("COMPLETED", "AUTO_PASSED") and job_id


def test_failed_job_is_not_marked_processed_and_can_be_retried(store):
    event = raw_event()
    store.save(event, Outcome("FAILED", error="모델 연결 실패"))
    assert not store.already_processed(event["eventId"])
    assert [e["eventId"] for e in store.retryable_events()] == [event["eventId"]]
    assert count(store, "select count(*) from outbox") == 0


def test_review_flow_approve_publishes_again(store):
    item = pending_item()
    outcome = Outcome("IN_REVIEW", event=None, candidate=item["candidate"], review_reasons=["EXCEPTION_SUSPECTED /eligibility/2"])
    job_id = store.save(item["rawEvent"], outcome)
    assert [r["jobId"] for r in store.reviews("PENDING")] == [job_id]

    class Session:
        def __enter__(self):
            return store

        def __exit__(self, *exc):
            return False

    service = ReviewService(lambda: Session(), new_event)
    status, body = service.approve(job_id, {"note": "확인"})
    assert status == 200 and body["reviewStatus"] == "APPROVED"
    assert count(store, "select count(*) from outbox where payload->'payload'->>'reviewStatus' = 'APPROVED'") == 1
    assert store.review(job_id)["reviewStatus"] == "APPROVED"
    assert service.approve(job_id, {})[0] == 409


def test_same_event_saved_twice_publishes_once(store):
    # 검토 지적: 중복 수신된 같은 이벤트가 작업·아웃박스를 한 번 더 써서 완료 이벤트가 두 번 나가던 것
    from mujuthaek_extraction.store import DuplicateEvent

    event = raw_event()
    outcome = run(event, NoticeModels(RecordedModel(answers()), None))
    store.save(event, outcome)
    with pytest.raises(DuplicateEvent):
        store.save(event, outcome)
    assert count(store, "select count(*) from outbox") == 1
    assert count(store, "select count(*) from extraction_jobs") == 1


def versioned(version):
    import copy

    event = copy.deepcopy(raw_event())
    event["eventId"] = str(uuid.uuid4())
    event["payload"]["version"] = version
    return event


def test_older_version_finishing_after_newer_one_is_not_published(store):
    # 검토 지적: v1이 늦게 끝나거나 다시 처리되면 v2 뒤에 발행되어 옛 자격 조건이 새 것을 덮던 것
    outcome = run(raw_event(), NoticeModels(RecordedModel(answers()), None))
    store.save(versioned(2), outcome)
    old_job = store.save(versioned(1), outcome)
    assert count(store, "select count(*) from outbox") == 1
    status = store.conn.execute("select status from extraction_jobs where id = %s", (old_job,)).fetchone()[0]
    assert status == "SUPERSEDED"


def test_approving_old_version_after_newer_one_published_is_refused(store):
    item = pending_item()
    old = versioned(1) | {"payload": {**item["rawEvent"]["payload"], "version": 1}}
    job_id = store.save(old, Outcome("IN_REVIEW", event=None, candidate=item["candidate"], review_reasons=["X /eligibility/0"]))
    store.save(versioned(2) | {"payload": {**item["rawEvent"]["payload"], "version": 2}}, run(raw_event(), NoticeModels(RecordedModel(answers()), None)))

    class Session:
        def __enter__(self):
            return store

        def __exit__(self, *exc):
            return False

    status, body = ReviewService(lambda: Session(), new_event).approve(job_id, {})
    assert status == 409 and "새 버전" in body["error"]
    assert count(store, "select count(*) from outbox") == 1


def test_retry_waits_longer_each_time_and_stops_after_max_attempts(store):
    # 검토 지적: 자료 대기·실패는 사람이 retry를 돌려야만 다시 처리됐고, 횟수 제한·간격도 없었음
    event = raw_event()
    store.save(event, Outcome("WAITING_SOURCE", error="PDF 아직 없음"))
    assert [e["eventId"] for e in store.retryable_events(base_delay_seconds=0)] == [event["eventId"]]
    assert store.retryable_events(base_delay_seconds=3600) == []  # 1시간 기다려야 하는데 방금 처리함
    store.save(event, Outcome("WAITING_SOURCE", error="PDF 아직 없음"))
    store.conn.execute("update extraction_jobs set created_at = now() - interval '90 minutes'")
    assert store.retryable_events(base_delay_seconds=3600) == []  # 2번째라 2시간
    store.conn.execute("update extraction_jobs set created_at = now() - interval '3 hours'")
    assert len(store.retryable_events(base_delay_seconds=3600)) == 1
    assert store.retryable_events(max_attempts=2) == []  # 2번 처리했으면 더 하지 않음
