"""extraction DB 저장 (psycopg 3). 작업 기록·검수 대기열·아웃박스·processed_events를 한 트랜잭션으로 씁니다.

중복 처리 방지: 끝난 처리(다시 하지 않을 상태)만 processed_events에 넣습니다. WAITING_SOURCE·FAILED는 넣지 않아 다시 처리할 수 있습니다.
processed_events를 **트랜잭션 맨 앞에서** 넣고, 이미 있으면(같은 이벤트를 다른 처리가 먼저 끝냄) 아무것도 쓰지 않고 DuplicateEvent를 냅니다.
예전에는 맨 끝에 on conflict do nothing으로 넣어, 중복 수신된 같은 이벤트가 작업·아웃박스를 한 번 더 써서 완료 이벤트가 두 번 나갔습니다(검토 2026-10-16).

버전 순서: 같은 원본(rawPostId)은 공고 단위 잠금(pg_advisory_xact_lock)으로 줄 세우고, 더 새 버전이 이미 발행됐으면
늦게 끝난 옛 버전 결과는 발행하지 않고 SUPERSEDED로 남깁니다. 검수 승인도 같은 이유로 막습니다(옛 자격 조건이 새 것을 덮지 않게).
"""

from __future__ import annotations

import uuid
from typing import Any

from psycopg.types.json import Jsonb

from mujuthaek_extraction.pipeline import Outcome
from mujuthaek_platform import outbox
from mujuthaek_platform.ids import uuid7

REVIEW_STATUSES = {"IN_REVIEW", "INTAKE_REVIEW"}
PARTIAL = "PARTIAL"  # 부분 게시: 이미 발행했고, 보류 항목은 사람이 확인해 승인할 수 있게 검수 목록에도 넣습니다(게시를 막지 않음)
SUPERSEDED = "SUPERSEDED"


class DuplicateEvent(Exception):
    """같은 이벤트를 다른 처리가 이미 끝냈습니다. 아무것도 저장하지 않았습니다."""


def _lock_notice(cur, raw_post_id: str | None) -> None:
    if raw_post_id:
        cur.execute("select pg_advisory_xact_lock(hashtext(%s))", (raw_post_id,))


def _newer_published(cur, raw_post_id: str | None, raw_version: Any) -> bool:
    if not raw_post_id or not isinstance(raw_version, int):
        return False
    row = cur.execute(
        "select 1 from extraction_jobs where raw_post_id = %s and raw_version > %s and completed_event_id is not null limit 1",
        (raw_post_id, raw_version),
    ).fetchone()
    return row is not None


def _held_reasons(candidate: dict[str, Any] | None) -> list[str]:
    """부분 게시에서 보류한 항목의 사유 코드(검수 목록에 보여 줄 값)."""
    held = ((candidate or {}).get("quality") or {}).get("heldItems") or []
    return sorted({code for item in held for code in item.get("codes", [])})


def _payload(raw_event: dict[str, Any]) -> dict[str, Any]:
    payload = raw_event.get("payload")
    return payload if isinstance(payload, dict) else {}


def _uuid_or_none(value: Any) -> str | None:
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, TypeError):
        return None


class JobStore:
    """extraction DB에 작업 기록·검수 대기열·아웃박스를 읽고 쓰는 저장소입니다."""

    def __init__(self, conn):
        conn.autocommit = True  # 묶음 쓰기는 with conn.transaction()으로만 합니다 (읽기가 트랜잭션을 열어 두지 않게)
        self.conn = conn

    def already_processed(self, event_id: str) -> bool:
        return self.conn.execute("select 1 from processed_events where event_id = %s", (event_id,)).fetchone() is not None

    def save(self, raw_event: dict[str, Any], outcome: Outcome, run_id: str | None = None) -> str:
        """처리 결과를 저장하고 작업 ID를 돌려줍니다. 발행할 이벤트는 같은 트랜잭션에서 아웃박스에 넣습니다.
        run_id: 이 처리의 비용 장부 ID(model/spend.py). 장부의 비용 줄에 작업 ID를 적어, 저장된 처리의 비용으로 표시합니다."""
        job_id = str(uuid7())
        payload = _payload(raw_event)
        event_id = _uuid_or_none(raw_event.get("eventId"))
        raw_post_id = _uuid_or_none(payload.get("rawPostId"))
        raw_version = payload.get("version") if isinstance(payload.get("version"), int) else None
        status, event, error = outcome.status, outcome.event, outcome.error
        with self.conn.transaction(), self.conn.cursor() as cur:
            _lock_notice(cur, raw_post_id)
            if event_id and not outcome.retryable:
                claimed = cur.execute(
                    "insert into processed_events (event_id, event_type) values (%s, %s) on conflict do nothing returning event_id",
                    (event_id, raw_event.get("eventType") or "unknown"),
                ).fetchone()
                if claimed is None:
                    raise DuplicateEvent(event_id)  # 트랜잭션을 되돌려 작업·아웃박스를 쓰지 않습니다
            if event and _newer_published(cur, raw_post_id, raw_version):
                status, event, error = SUPERSEDED, None, f"더 새 버전이 이미 발행되어 v{raw_version} 결과는 발행하지 않았습니다."
            cur.execute(
                "insert into extraction_jobs (id, event_id, raw_post_id, raw_version, source_code, field, status, model_used, cost_usd,"
                " attempts, ai_responses, pdf_pages, candidate, raw_event, completed_event_id, error, usage)"
                " values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
                (
                    job_id,
                    event_id or job_id,  # 형식이 잘못돼 eventId가 없으면 작업 ID로 대신 남깁니다
                    raw_post_id,
                    raw_version,
                    payload.get("sourceCode") if isinstance(payload.get("sourceCode"), str) else None,
                    payload.get("field") if isinstance(payload.get("field"), str) else None,
                    status,
                    outcome.model_used,
                    round(outcome.cost_usd, 4),
                    Jsonb(outcome.attempts),
                    Jsonb(outcome.responses),
                    Jsonb(outcome.pdf_pages),
                    Jsonb(outcome.candidate) if outcome.candidate is not None else None,
                    Jsonb(raw_event),
                    event["eventId"] if event else None,
                    error,
                    Jsonb(outcome.usage),
                ),
            )
            if event:
                outbox.add(cur, event)
            if run_id:
                cur.execute("update extraction_spend set job_id = %s where run_id = %s", (job_id, run_id))
            if status in REVIEW_STATUSES:
                cur.execute("insert into review_queue (job_id, reasons) values (%s, %s)", (job_id, Jsonb(outcome.review_reasons)))
            elif status == PARTIAL and (held := _held_reasons(outcome.candidate)):
                cur.execute("insert into review_queue (job_id, reasons) values (%s, %s)", (job_id, Jsonb(held)))
        return job_id

    # ---- 원본 버전 처리권 (migrations/V202610201200__extraction_claims.sql) ----
    def claim(self, raw_post_id: str, raw_version: int, owner: str, lease_seconds: float) -> bool:
        """처리권을 잡습니다. 아무도 없거나 기한이 지났으면 잡고 True, 다른 처리가 기한 안에 잡고 있으면 False."""
        row = self.conn.execute(
            "insert into extraction_claims (raw_post_id, raw_version, owner, expires_at)"
            " values (%s, %s, %s, now() + make_interval(secs => %s))"
            " on conflict (raw_post_id, raw_version) do update set owner = excluded.owner, expires_at = excluded.expires_at, created_at = now()"
            " where extraction_claims.expires_at < now() or extraction_claims.owner = excluded.owner"
            " returning owner",
            (raw_post_id, raw_version, owner, lease_seconds),
        ).fetchone()
        return row is not None

    def renew_claim(self, raw_post_id: str, raw_version: int, owner: str, lease_seconds: float) -> bool:
        """처리 중에 기한을 늘립니다. 이미 다른 처리에 넘어갔으면 False."""
        updated = self.conn.execute(
            "update extraction_claims set expires_at = now() + make_interval(secs => %s) where raw_post_id = %s and raw_version = %s and owner = %s",
            (lease_seconds, raw_post_id, raw_version, owner),
        ).rowcount
        return updated == 1

    def release_claim(self, raw_post_id: str, raw_version: int, owner: str) -> None:
        self.conn.execute("delete from extraction_claims where raw_post_id = %s and raw_version = %s and owner = %s", (raw_post_id, raw_version, owner))

    # ---- 검수 ----
    def reviews(self, status: str = "PENDING", limit: int = 50) -> list[dict[str, Any]]:
        rows = self.conn.execute(
            "select j.id, j.raw_post_id, j.raw_version, j.source_code, j.status, r.status, r.reasons, r.created_at, j.candidate->'notice'->>'title',"
            " j.raw_event->'payload'->>'title' from review_queue r join extraction_jobs j on j.id = r.job_id where r.status = %s"
            " order by r.created_at limit %s",
            (status, limit),
        ).fetchall()
        keys = ("jobId", "rawPostId", "rawVersion", "sourceCode", "jobStatus", "reviewStatus", "reasons", "queuedAt", "title", "sourceTitle")
        return [{k: (str(v) if k in ("jobId", "rawPostId") and v else v) for k, v in zip(keys, row, strict=True)} for row in rows]

    def review(self, job_id: str) -> dict[str, Any] | None:
        row = self.conn.execute(
            "select j.id, j.status, r.status, r.reasons, r.note, j.candidate, j.raw_event, j.error, j.model_used, j.cost_usd"
            " from review_queue r join extraction_jobs j on j.id = r.job_id where j.id = %s",
            (job_id,),
        ).fetchone()
        if row is None:
            return None
        keys = ("jobId", "jobStatus", "reviewStatus", "reasons", "note", "candidate", "rawEvent", "error", "modelUsed", "costUsd")
        item = dict(zip(keys, row, strict=True))
        item["jobId"] = str(item["jobId"])
        item["costUsd"] = float(item["costUsd"])
        return item

    def approve(self, job_id: str, candidate: dict[str, Any], event: dict[str, Any], note: str | None) -> None:
        """대기 중인 검수를 승인하고 고친 후보와 발행할 이벤트를 한 트랜잭션으로 저장합니다.
        더 새 버전이 이미 발행됐거나 대기 중인 검수가 아니면 LookupError를 냅니다."""
        with self.conn.transaction(), self.conn.cursor() as cur:
            job = cur.execute("select raw_post_id, raw_version from extraction_jobs where id = %s", (job_id,)).fetchone()
            raw_post_id = str(job[0]) if job and job[0] else None
            _lock_notice(cur, raw_post_id)
            if job and _newer_published(cur, raw_post_id, job[1]):
                raise LookupError(f"더 새 버전이 이미 발행되어 v{job[1]} 결과는 승인할 수 없습니다. 새 버전을 검수하세요.")
            cur.execute(
                "update review_queue set status = 'APPROVED', note = %s, decided_at = now() where job_id = %s and status = 'PENDING'",
                (note, job_id),
            )
            if cur.rowcount != 1:
                raise LookupError("대기 중인 검수가 아닙니다.")
            cur.execute(
                "update extraction_jobs set status = 'APPROVED', candidate = %s, completed_event_id = %s, updated_at = now() where id = %s",
                (Jsonb(candidate), event["eventId"], job_id),
            )
            outbox.add(cur, event)

    def reject(self, job_id: str, note: str | None) -> None:
        with self.conn.transaction(), self.conn.cursor() as cur:
            cur.execute(
                "update review_queue set status = 'REJECTED', note = %s, decided_at = now() where job_id = %s and status = 'PENDING'",
                (note, job_id),
            )
            if cur.rowcount != 1:
                raise LookupError("대기 중인 검수가 아닙니다.")

    # ---- 다시 처리 ----
    def spent_before(self, raw_event: dict[str, Any]) -> float:
        """같은 원본 버전(rawPostId + version)의 앞선 처리 중 다시 처리 대상(FAILED·WAITING_SOURCE)으로 끝난 것들이 쓴 유료 비용.
        원본 ID나 버전이 없으면 같은 eventId로 셉니다. 끝난 처리(완료·검수 등) 뒤 일부러 다시 돌리는 경우는 새 예산입니다."""
        payload = _payload(raw_event)
        raw_post_id = _uuid_or_none(payload.get("rawPostId"))
        raw_version = payload.get("version") if isinstance(payload.get("version"), int) else None
        if raw_post_id and raw_version is not None:
            where, params = "raw_post_id = %s and raw_version = %s", (raw_post_id, raw_version)
        elif event_id := _uuid_or_none(raw_event.get("eventId")):
            where, params = "event_id = %s", (event_id,)
        else:
            return 0.0
        row = self.conn.execute(
            f"select coalesce(sum(cost_usd), 0) from extraction_jobs where {where} and status in ('FAILED', 'WAITING_SOURCE')", params
        ).fetchone()
        # 저장 전에 중단된 처리(프로세스 종료 등)의 비용: 작업 기록이 없어 위 합계에 빠지므로 비용 장부에서 셉니다(2026-10-21).
        # 응답을 못 받은 호출은 최악 비용으로 셉니다
        orphan = self.conn.execute(
            f"select coalesce(sum(coalesce(cost_usd, reserved_usd)), 0) from extraction_spend where {where} and job_id is null", params
        ).fetchone()
        return (float(row[0]) if row else 0.0) + (float(orphan[0]) if orphan else 0.0)

    # ---- 중단 반복·같은 버전 다시 받음 (2026-10-21) ----
    def record_start(self, event_id: str) -> None:
        """처리를 시작했다고 적습니다. 저장 전에 중단되면 시작 횟수가 작업 수보다 많아집니다."""
        self.conn.execute("insert into extraction_starts (event_id) values (%s)", (event_id,))

    def interrupted_runs(self, event_id: str) -> int:
        """이 이벤트를 시작했는데 결과를 저장하지 못한 횟수(프로세스 종료·메모리 부족 등)."""
        row = self.conn.execute(
            "select (select count(*) from extraction_starts where event_id = %s) - (select count(*) from extraction_jobs where event_id = %s)",
            (event_id, event_id),
        ).fetchone()
        return max(int(row[0]), 0) if row else 0

    def version_published(self, raw_post_id: str, raw_version: int) -> bool:
        """같은 원본 버전의 결과를 이미 발행했는지(다른 eventId로 같은 버전이 다시 온 경우를 걸러 비용을 다시 쓰지 않게)."""
        row = self.conn.execute(
            "select 1 from extraction_jobs where raw_post_id = %s and raw_version = %s and completed_event_id is not null limit 1",
            (raw_post_id, raw_version),
        ).fetchone()
        return row is not None

    def mark_processed(self, event_id: str, event_type: str) -> None:
        self.conn.execute("insert into processed_events (event_id, event_type) values (%s, %s) on conflict do nothing", (event_id, event_type))

    def retryable_events(self, limit: int = 20, max_attempts: int | None = None, base_delay_seconds: float = 0) -> list[dict[str, Any]]:
        """마지막 처리가 WAITING_SOURCE·FAILED이고 아직 끝나지 않은(processed_events에 없는) 이벤트. 오래 기다린 것부터.

        max_attempts: 이만큼 처리한 이벤트는 더 하지 않습니다(계속 실패하는 공고에 비용이 계속 들지 않게).
        base_delay_seconds: 마지막 처리 뒤 base × 2^(처리 횟수-1)초가 지난 것만(5분 → 10분 → 20분 …).
        예전엔 표 전체(raw_event 포함)를 읽고 행마다 조회해 느렸고, 순서도 eventId 순이라 한도 20이 임의였습니다."""
        rows = self.conn.execute(
            "select raw_event from ("
            " select distinct on (event_id) event_id, status, raw_event, created_at,"
            " count(*) over (partition by event_id) as attempts"
            " from extraction_jobs order by event_id, created_at desc"
            ") last where status in ('WAITING_SOURCE', 'FAILED')"
            " and not exists (select 1 from processed_events p where p.event_id = last.event_id)"
            " and (%s::int is null or attempts < %s::int)"
            " and created_at <= now() - make_interval(secs => %s * power(2, attempts - 1))"
            " order by created_at limit %s",
            (max_attempts, max_attempts, base_delay_seconds, limit),
        ).fetchall()
        return [row[0] for row in rows]
