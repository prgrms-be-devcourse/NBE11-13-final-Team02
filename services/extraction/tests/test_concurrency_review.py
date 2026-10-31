"""동시성 시나리오 5: 관리자 여럿이 같은 검수 건을 동시에 승인·수정해도 하나만 성공하고 나머지는 409로 거절, 덮어쓰기가 없는지.

perf/concurrency/run.sh가 CONCURRENCY=1·EXTRACTION_TEST_DB_URL(로컬 Postgres)로 돌려요. 회마다 임시 스키마를 만들고 지워서
로컬 extraction DB의 실제 검수 건은 건드리지 않아요. 검수 API처럼 연결 풀(최대 20)에서 연결을 빌려 써요.
"""

import copy
import json
import os
import threading
import uuid
from collections import Counter
from contextlib import contextmanager
from pathlib import Path

import pytest
from test_pipeline import new_event
from test_worker_parts import pending_item

from mujuthaek_extraction.pipeline import Outcome
from mujuthaek_extraction.review_api import ReviewService
from mujuthaek_extraction.store import JobStore

DB_URL = os.getenv("EXTRACTION_TEST_DB_URL")
pytestmark = pytest.mark.skipif(
    not DB_URL or os.getenv("CONCURRENCY") != "1", reason="CONCURRENCY=1과 EXTRACTION_TEST_DB_URL이 있을 때만 (perf/concurrency/run.sh)"
)
MIGRATIONS = Path(__file__).resolve().parents[1] / "migrations"
THREADS = int(os.getenv("CONCURRENCY_THREADS", "100"))
ROUNDS = int(os.getenv("CONCURRENCY_ROUNDS", "10"))


def one_round(round_no: int) -> dict:
    import psycopg
    from psycopg_pool import ConnectionPool

    schema = "test_conc_" + uuid.uuid4().hex[:10]
    admin = psycopg.connect(DB_URL, autocommit=True)
    admin.execute(f"create schema {schema}")
    admin.execute(f"set search_path to {schema}")
    for path in sorted(MIGRATIONS.glob("V*.sql")):
        admin.execute(path.read_text(encoding="utf-8"))
    pool = ConnectionPool(
        DB_URL,
        min_size=1,
        max_size=20,
        timeout=60,
        kwargs={"autocommit": True, "options": f"-c search_path={schema}"},
        open=True,
    )
    try:
        item = pending_item()
        outcome = Outcome("IN_REVIEW", event=None, candidate=item["candidate"], review_reasons=["EXCEPTION_SUSPECTED /eligibility/2"])
        job_id = JobStore(admin).save(item["rawEvent"], outcome)

        @contextmanager
        def session():
            with pool.connection() as conn:
                yield JobStore(conn)

        service = ReviewService(session, new_event)
        barrier = threading.Barrier(THREADS)
        results: list[tuple[int, int, str | None]] = [None] * THREADS  # type: ignore[list-item]

        def admin_action(i: int) -> None:
            body: dict = {"note": f"관리자 {i}", "reviewer": f"admin-{i}"}
            title = None
            if i % 2:  # 반은 고쳐서 승인(제목을 관리자마다 다르게), 반은 그대로 승인
                candidate = copy.deepcopy(item["candidate"])
                title = f"{candidate['notice']['title']} (관리자 {i} 수정)"
                candidate["notice"]["title"] = title
                body["candidate"] = candidate
            barrier.wait()
            try:
                status, _ = service.approve(job_id, body)
            except Exception as error:  # noqa: BLE001 - 결과로 세요
                status = f"예외:{type(error).__name__}"
            results[i] = (i, status, title)

        threads = [threading.Thread(target=admin_action, args=(i,)) for i in range(THREADS)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(120)

        codes = Counter(str(r[1]) for r in results)
        winners = [r for r in results if r[1] == 200]
        row = admin.execute(
            "select q.status, q.decided_by, j.status, j.candidate->'notice'->>'title' from review_queue q"
            " join extraction_jobs j on j.id = q.job_id where q.job_id = %s",
            (job_id,),
        ).fetchone()
        outbox = admin.execute("select count(*), max(payload->'payload'->'notice'->'notice'->>'title') from outbox").fetchone()
        winner = winners[0] if len(winners) == 1 else None
        expected_title = (winner[2] if winner and winner[2] else item["candidate"]["notice"]["title"]) if winner else None
        db = {
            "review_status": row[0],
            "decided_by": row[1],
            "job_status": row[2],
            "stored_title_is_winners": row[3] == expected_title,
            "outbox_events": outbox[0],
            "published_title_is_winners": outbox[1] == expected_title,
        }
        passed = (
            len(winners) == 1
            and codes.get("409", 0) == THREADS - 1
            and row[0] == "APPROVED"
            and row[1] == f"admin-{winner[0]}"
            and db["stored_title_is_winners"]
            and outbox[0] == 1
            and db["published_title_is_winners"]
        )
        return {"round": round_no, "pass": passed, "outcomes": dict(sorted(codes.items())), "db": db}
    finally:
        pool.close()
        admin.execute(f"drop schema {schema} cascade")
        admin.close()


def test_concurrent_approvals_of_the_same_review_let_only_one_win():
    rounds = [one_round(n) for n in range(1, ROUNDS + 1)]
    result = {
        "scenario": "5 관리자 동시 승인·수정 (같은 검수 건)",
        "criteria": "200은 하나, 나머지는 409, 저장된 후보·발행 이벤트는 이긴 요청의 것(덮어쓰기 없음), 발행 이벤트 1건",
        "pass": all(r["pass"] for r in rounds),
        "rounds": rounds,
    }
    out = os.getenv("CONCURRENCY_OUT")
    if out:
        Path(out).mkdir(parents=True, exist_ok=True)
        (Path(out) / "5_review.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    assert result["pass"], rounds
