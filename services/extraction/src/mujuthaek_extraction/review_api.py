"""검수 API (:8092). 사람이 확인할 공고를 조회하고 승인·반려합니다.

  GET  /health                       상태 (워커의 큐 소비·발행·다시 처리 중 하나라도 신호가 끊기면 503)
  GET  /reviews?status=PENDING       검수 목록 (PENDING·APPROVED·REJECTED)
  GET  /reviews/{jobId}              검수 상세 (표준 공고 후보·이슈·받은 이벤트)
  POST /reviews/{jobId}/approve      승인 → extraction.completed(reviewStatus APPROVED) 다시 발행
                                     본문(선택): {"candidate": 고친 표준 공고, "note": "메모"}
  POST /reviews/{jobId}/reject       반려 (발행 없음). 본문(선택): {"note": "메모"}

인증(검토 2026-10-16): EXTRACTION_REVIEW_TOKEN이 있으면 /health 밖의 모든 요청에 'Authorization: Bearer <토큰>'이 필요합니다.
토큰이 없으면 같은 컴퓨터(127.0.0.1·::1)에서 온 요청만 받습니다 — Docker처럼 0.0.0.0에 열어도 밖에서는 못 씁니다.
승인할 후보는 그 작업의 원본·버전·분야와 같아야 합니다(다른 공고의 결과를 발행하지 못하게).
"""

from __future__ import annotations

import hmac
import json
import logging
import re
import uuid
from collections.abc import Callable
from contextlib import AbstractContextManager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse

from mujuthaek_extraction.contracts import tools
from mujuthaek_extraction.publish import approved_payload

log = logging.getLogger(__name__)
CANDIDATE_SCHEMA = "schemas/extracted-notice.v1.json"
EVENT_SCHEMA = "events/extraction.completed.v1.json"
REVIEW_PATH = re.compile(r"^/reviews/([0-9a-fA-F-]{36})(?:/(approve|reject))?$")
MAX_BODY = 5 * 1024 * 1024
LOOPBACK = {"127.0.0.1", "::1", "::ffff:127.0.0.1"}


def _same_notice(candidate: dict[str, Any], raw_payload: dict[str, Any]) -> bool:
    """후보의 원본·버전·분야가 작업이 받은 이벤트와 같은지. 출처 기록(sourceReferences)도 같은 원본을 가리켜야 합니다."""
    given = candidate.get("input") or {}
    expected = (str(raw_payload.get("rawPostId")), raw_payload.get("version"), raw_payload.get("field"))
    if (str(given.get("rawPostId")), given.get("rawVersion"), given.get("field")) != expected:
        return False
    references = candidate.get("sourceReferences") or []
    return all((str(r.get("rawPostId")), r.get("rawVersion")) == expected[:2] for r in references)


class ReviewService:
    """HTTP와 분리한 검수 동작. store_session은 JobStore를 여는 컨텍스트입니다(요청마다 DB 연결)."""

    def __init__(self, store_session: Callable[[], AbstractContextManager[Any]], new_event: Callable[[str, str, dict[str, Any]], dict[str, Any]]):
        self.store_session = store_session
        self.new_event = new_event

    def list(self, status: str) -> tuple[int, Any]:
        if status not in {"PENDING", "APPROVED", "REJECTED"}:
            return 400, {"error": "status는 PENDING·APPROVED·REJECTED 중 하나입니다."}
        with self.store_session() as store:
            return 200, {"items": store.reviews(status)}

    def detail(self, job_id: str) -> tuple[int, Any]:
        with self.store_session() as store:
            item = store.review(job_id)
        return (200, item) if item else (404, {"error": "검수 항목이 없습니다."})

    def approve(self, job_id: str, body: dict[str, Any]) -> tuple[int, Any]:
        """검수 대기 작업을 승인하고 APPROVED 이벤트를 저장합니다. 고친 후보가 오면 그것을 쓰되 계약·같은 공고인지 먼저 확인합니다."""
        with self.store_session() as store:
            item = store.review(job_id)
            if item is None:
                return 404, {"error": "검수 항목이 없습니다."}
            if item["reviewStatus"] != "PENDING":
                return 409, {"error": f"이미 {item['reviewStatus']} 처리된 항목입니다."}
            candidate = body.get("candidate") or item["candidate"]
            if candidate is None:
                return 409, {"error": "추출 결과가 없어 승인할 수 없습니다. 원본 자료를 고친 뒤 다시 처리하세요."}
            errors = tools().validate(candidate, CANDIDATE_SCHEMA)
            if errors:
                return 422, {"error": "표준 공고 형식이 맞지 않습니다.", "details": errors[:20]}
            raw = (item["rawEvent"] or {}).get("payload") or {}
            if not _same_notice(candidate, raw):
                return 422, {"error": "후보의 원본·버전·분야가 이 검수 작업의 공고와 다릅니다."}
            payload = approved_payload(candidate, provider=raw.get("provider"), region_fallback=raw.get("regionCode"))
            event = {**self.new_event("extraction.completed", "extraction", payload), "traceId": (item["rawEvent"] or {}).get("traceId")}
            errors = tools().validate(event, EVENT_SCHEMA)
            if errors:
                return 422, {"error": "발행할 이벤트가 계약과 맞지 않습니다.", "details": errors[:20]}
            try:
                store.approve(job_id, candidate, event, body.get("note"))
            except LookupError as error:
                return 409, {"error": str(error)}
        log.info("검수 승인", extra={"jobId": job_id, "eventId": event["eventId"]})
        return 200, {"jobId": job_id, "eventId": event["eventId"], "reviewStatus": "APPROVED"}

    def reject(self, job_id: str, body: dict[str, Any]) -> tuple[int, Any]:
        with self.store_session() as store:
            try:
                store.reject(job_id, body.get("note"))
            except LookupError as error:
                return 409, {"error": str(error)}
        log.info("검수 반려", extra={"jobId": job_id})
        return 200, {"jobId": job_id, "reviewStatus": "REJECTED"}


def handler_for(service: ReviewService, token: str | None = None, health: Any = None) -> type[BaseHTTPRequestHandler]:
    """검수 서비스·토큰·생존 신호를 묶은 HTTP 요청 처리 클래스를 만들어 돌려줍니다."""

    class Handler(BaseHTTPRequestHandler):
        """검수 API 주소를 ReviewService 동작에 연결하고, 인증과 JSON 응답을 맡습니다."""

        def _allowed(self) -> bool:
            if token:
                given = self.headers.get("Authorization") or ""
                return hmac.compare_digest(given.encode(), f"Bearer {token}".encode())
            return self.client_address[0] in LOOPBACK

        def _send(self, status: int, body: Any) -> None:
            data = json.dumps(body, ensure_ascii=False, default=str).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _body(self) -> dict[str, Any] | None:
            """요청 본문을 JSON 객체로 읽습니다. 본문이 없으면 빈 객체, 길이·형식이 맞지 않거나 note가 글자가 아니면 None입니다."""
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                return None
            if length == 0:
                return {}
            if not 0 < length <= MAX_BODY:  # 음수 길이는 끝까지 읽어 버리므로 거절합니다
                return None
            try:
                value = json.loads(self.rfile.read(length))
            except ValueError:
                return None
            if not isinstance(value, dict) or not isinstance(value.get("note"), str | None):
                return None
            return value

        def _job_id(self, match: re.Match[str]) -> str | None:
            try:
                return str(uuid.UUID(match[1]))
            except ValueError:
                return None

        def do_GET(self) -> None:  # noqa: N802
            """/health는 인증 없이, 검수 목록·상세는 인증을 확인한 뒤 응답합니다."""
            url = urlparse(self.path)
            if url.path == "/health":
                if health is None:
                    return self._send(200, {"status": "ok"})
                ok, parts = health.report()
                return self._send(200 if ok else 503, {"status": "ok" if ok else "degraded", "parts": parts})
            if not self._allowed():
                return self._send(401, {"error": "인증이 필요합니다."})
            if url.path == "/reviews":
                return self._send(*service.list(parse_qs(url.query).get("status", ["PENDING"])[0]))
            match = REVIEW_PATH.match(url.path)
            if match and not match[2]:
                job_id = self._job_id(match)
                return self._send(*service.detail(job_id)) if job_id else self._send(400, {"error": "작업 ID 형식이 아닙니다."})
            self._send(404, {"error": "없는 주소입니다."})

        def do_POST(self) -> None:  # noqa: N802
            if not self._allowed():
                return self._send(401, {"error": "인증이 필요합니다."})
            match = REVIEW_PATH.match(urlparse(self.path).path)
            if not match or not match[2]:
                return self._send(404, {"error": "없는 주소입니다."})
            job_id = self._job_id(match)
            if job_id is None:
                return self._send(400, {"error": "작업 ID 형식이 아닙니다."})
            body = self._body()
            if body is None:
                return self._send(400, {"error": "본문은 5MB 이하의 JSON 객체여야 하고, note는 글자여야 합니다."})
            action = service.approve if match[2] == "approve" else service.reject
            self._send(*action(job_id, body))

        def log_message(self, format: str, *args: Any) -> None:  # noqa: A002 - 기본 접근 로그 대신 JSON 로그만 씁니다
            return

    return Handler


def serve(service: ReviewService, host: str, port: int, token: str | None = None, health: Any = None) -> ThreadingHTTPServer:
    if not token and host not in LOOPBACK and host != "localhost":
        log.warning("검수 API 토큰(EXTRACTION_REVIEW_TOKEN)이 없어 같은 컴퓨터에서 온 요청만 받습니다", extra={"host": host})
    server = ThreadingHTTPServer((host, port), handler_for(service, token, health))
    return server
