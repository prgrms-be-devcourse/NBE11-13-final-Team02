"""raw 이벤트 1건 → 처리 결과. 녹화 응답·메모리 리더만 씁니다(DB·큐·네트워크 없음)."""

import json
from datetime import UTC, datetime

from helpers import CONTRACTS, REGIONS, ai_part, every_call, example

from mujuthaek_extraction.contracts import tools
from mujuthaek_extraction.intake import InMemorySourceReader
from mujuthaek_extraction.model import ModelError, RecordedModel
from mujuthaek_extraction.model.factory import NoticeModels
from mujuthaek_extraction.pipeline import process
from mujuthaek_extraction.rules.housing_notice import RULES

TEXT = (CONTRACTS / "examples" / "sources" / "housing.synthetic.txt").read_bytes().decode("utf-8")


def raw_event(**overrides):
    payload = {
        "rawPostId": "01929a3e-0000-7000-8000-000000000001",
        "version": 1,
        "sourceCode": "virtual",
        "provider": "가상주거공사",
        "regionCode": None,
        "field": "HOUSING",
        "kind": "NOTICE",
        "title": "가상주거 청년 매입임대 입주자 모집 공고",
        "postedOn": "2026-10-01",
        "detailUrl": None,
        "supersedesPostId": None,
        "text": TEXT,
        "textKey": None,
        "units": [],
        "files": [],
        "reprocess": False,
    } | overrides
    return {
        "eventId": "01929a3e-0000-7000-8000-0000000000e1",
        "eventType": "raw.notice.collected",
        "schemaVersion": 1,
        "occurredAt": "2026-10-15T00:00:00Z",
        "producer": "collector",
        "traceId": "trace-1",
        "payload": payload,
    }


def answers():
    """계약 예시를 섹션별 AI 응답으로. 예시의 문서 ID(text1)를 intake가 주는 ID(text)로 바꿉니다."""
    data = example("housing")
    return {call.name: [json.dumps(ai_part(data, call.sections), ensure_ascii=False).replace('"text1"', '"text"')] for call in every_call(RULES)}


def new_event(event_type, producer, payload):
    return {
        "eventId": "01929a3e-0000-7000-8000-0000000000c1",
        "eventType": event_type,
        "schemaVersion": 1,
        "occurredAt": "2026-10-15T00:00:01Z",
        "producer": producer,
        "payload": payload,
    }


def run(event, models):
    clock = lambda: datetime(2026, 10, 15, tzinfo=UTC)  # noqa: E731
    # 계약 예시는 글자만 있는 가짜 공고라 PDF 필수 규칙을 끕니다 (PDF 규칙은 test_intake·test_pdf_reader에서 확인)
    return process(event, models=models, reader=InMemorySourceReader(), regions=REGIONS, clock=clock, new_event=new_event, require_pdf=False)


def test_clean_notice_is_completed_and_event_matches_contract():
    outcome = run(raw_event(), NoticeModels(RecordedModel(answers(), "local"), None, names={"primary": "local"}))
    assert outcome.status == "COMPLETED" and outcome.error is None
    assert outcome.event["payload"]["reviewStatus"] == "AUTO_PASSED" and outcome.event["traceId"] == "trace-1"
    assert tools().validate(outcome.event, "events/extraction.completed.v1.json") == []
    assert outcome.model_used == "local" and [a["call_name"] for a in outcome.attempts] == ["structure", "schedules", "eligibility", "selection", "documents"]


def test_local_ai_down_falls_back_to_paid_model_per_call():
    down = ModelError("로컬 AI 연결 실패", transient=False)
    local = RecordedModel({name: [down] for name in answers()}, "local")
    paid = RecordedModel(answers(), "gpt-6-luna")
    outcome = run(raw_event(), NoticeModels(local, paid, names={"primary": "local", "fallback": "gpt-6-luna"}))
    assert outcome.status == "COMPLETED"
    assert [r.call_name for r in paid.requests] == ["structure", "schedules", "eligibility", "selection", "documents"]


def test_no_model_available_is_failed_and_retryable():
    down = ModelError("로컬 AI 주소가 없습니다", transient=False)
    outcome = run(raw_event(), NoticeModels(RecordedModel({name: [down] for name in answers()}), None))
    assert outcome.status == "FAILED" and outcome.event is None and outcome.retryable


def test_broken_event_is_rejected_without_model_call():
    model = RecordedModel({})
    outcome = run(raw_event(rawPostId="not-a-uuid"), NoticeModels(model, None))
    assert outcome.status == "REJECTED" and not outcome.retryable and model.requests == []


def test_field_without_rules_is_unsupported():
    model = RecordedModel({})
    outcome = run(raw_event(field="BUSINESS_SPACE"), NoticeModels(model, None))
    assert outcome.status == "UNSUPPORTED" and model.requests == []


def test_missing_s3_text_waits_for_source():
    outcome = run(raw_event(textKey="text/virtual/1/v1.txt"), NoticeModels(RecordedModel({}), None))
    assert outcome.status == "WAITING_SOURCE" and outcome.retryable


def test_degraded_payload_drops_detail_and_unverified_rules():
    from mujuthaek_extraction.pipeline import degraded_payload

    outcome = run(raw_event(), NoticeModels(RecordedModel(answers()), None))
    payload = degraded_payload(outcome.event["payload"], ["INCOMPLETE_INCOME_BENCHMARK /eligibility/1/rule/expression"])
    assert "notice" not in payload and payload["reviewStatus"] == "IN_REVIEW"
    broken = payload["conditions"][1]
    assert broken["rule"]["status"] == "UNSTRUCTURED" and broken["inReview"] and "CONTRACT_RULE_INVALID" in broken["reviewReasons"]
    assert payload["conditions"][0]["rule"]["status"] == "STRUCTURED"  # 문제없는 조건은 그대로
    assert tools().validate({**outcome.event, "payload": payload}, "events/extraction.completed.v1.json") == []


def test_notice_without_pdf_is_published_but_forced_to_review():
    clock = lambda: datetime(2026, 10, 15, tzinfo=UTC)  # noqa: E731
    models = NoticeModels(RecordedModel(answers()), None)
    outcome = process(raw_event(), models=models, reader=InMemorySourceReader(), regions=REGIONS, clock=clock, new_event=new_event)
    assert outcome.status == "IN_REVIEW" and outcome.event["payload"]["reviewStatus"] == "IN_REVIEW"
    assert ("PDF_MISSING", "/documents") in {(i["code"], i["target"]) for i in outcome.candidate["quality"]["reviewIssues"]}
