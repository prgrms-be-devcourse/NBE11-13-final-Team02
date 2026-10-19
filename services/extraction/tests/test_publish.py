"""extraction.completed payload 만들기(팀 확정 형식). 결합·검증까지 거친 후보를 씁니다. AI 호출 없음."""

import copy
from datetime import UTC, datetime

from helpers import ai_part, example, source_for

from mujuthaek_extraction.assemble.assembler import assemble
from mujuthaek_extraction.contracts import tools
from mujuthaek_extraction.publish import completed_payload
from mujuthaek_extraction.verify import SourceBundle, verify_extraction

EVENT_SCHEMA = "events/extraction.completed.v1.json"


def verified(name="housing"):
    data = example(name)
    source = source_for(name, data)
    candidate = assemble([ai_part(data)], source).candidate
    return verify_extraction(candidate, SourceBundle.from_material(source.material), None, clock=lambda: datetime(2026, 10, 15, tzinfo=UTC)).candidate


def envelope(payload):
    return {
        "eventId": "01929a3e-0000-7000-8000-0000000000c1",
        "eventType": "extraction.completed",
        "schemaVersion": 1,
        "occurredAt": "2026-10-15T00:00:00Z",
        "producer": "extraction",
        "traceId": None,
        "payload": payload,
    }


def test_passed_candidate_becomes_auto_passed_event_that_matches_contract():
    payload = completed_payload(verified(), provider="가상주거공사")
    assert tools().validate(envelope(payload), EVENT_SCHEMA) == []
    assert payload["reviewStatus"] == "AUTO_PASSED"
    assert payload["regionCode"] == "11110"  # 법정동 10자리 1111000000의 앞 5자리
    assert payload["schedule"] == {"applyStartOn": "2026-10-01", "applyEndOn": "2026-10-31"}
    assert [c["inReview"] for c in payload["conditions"]] == [False] * len(payload["conditions"])


def test_review_issue_marks_condition_and_event_in_review():
    candidate = verified()
    candidate["quality"]["reviewIssues"].append({"code": "EVIDENCE_AMBIGUOUS", "target": "/eligibility/1", "description": "검수 필요"})
    candidate["quality"]["verification"]["status"] = "REVIEW_REQUIRED"
    payload = completed_payload(candidate)
    assert payload["reviewStatus"] == "IN_REVIEW"
    assert [c["reviewReasons"] for c in payload["conditions"]] == [[], ["EVIDENCE_AMBIGUOUS"], []]
    assert tools().validate(envelope(payload), EVENT_SCHEMA) == []


def test_unverified_application_schedule_is_left_out():
    candidate = verified()
    index = next(i for i, s in enumerate(candidate["schedules"]) if "APPLICATION" in s["kinds"])
    candidate["quality"]["reviewIssues"].append({"code": "EVIDENCE_QUOTE_NOT_FOUND", "target": f"/schedules/{index}", "description": "x"})
    assert completed_payload(candidate)["schedule"] is None


def test_region_is_null_when_locations_span_several_sigungu():
    candidate = verified()
    extra = copy.deepcopy(candidate["locations"][0])
    extra.update(id="other", regionCode="4111000000")
    candidate["locations"].append(extra)
    assert completed_payload(candidate)["regionCode"] is None
    assert completed_payload(candidate, region_fallback="11110")["regionCode"] == "11110"


def test_rank_specific_application_windows_are_all_sent_for_notifications():
    # GH 국민임대 실측: 1·2·3순위 접수 기간이 따로 → schedule(하나)은 비우고 applicationWindows에 순위별로 모두 담습니다
    candidate = verified()
    base = next(s for s in candidate["schedules"] if "APPLICATION" in s["kinds"])
    ranks = []
    for rank, (start, end) in enumerate([("2026-10-01", "2026-10-02"), ("2026-10-05", "2026-10-06"), ("2026-10-07", "2026-10-08")], start=1):
        item = copy.deepcopy(base)
        item.update(id=f"apply-rank{rank}", rawLabel=f"{rank}순위 접수", priorityRank=rank)
        item["startsAt"], item["endsAt"] = {"precision": "DAY", "value": start}, {"precision": "DAY", "value": end}
        ranks.append(item)
    candidate["schedules"] = [s for s in candidate["schedules"] if s is not base] + ranks
    payload = completed_payload(candidate)
    assert payload["schedule"] is None
    windows = payload["applicationWindows"]
    assert [(w["priorityRank"], w["applyStartOn"], w["applyEndOn"]) for w in windows] == [
        (1, "2026-10-01", "2026-10-02"),
        (2, "2026-10-05", "2026-10-06"),
        (3, "2026-10-07", "2026-10-08"),
    ]
    assert tools().validate(envelope(payload), EVENT_SCHEMA) == []


def test_application_window_with_review_issue_is_left_out():
    candidate = verified()
    index = next(i for i, s in enumerate(candidate["schedules"]) if "APPLICATION" in s["kinds"])
    assert len(completed_payload(candidate)["applicationWindows"]) == 1
    candidate["quality"]["reviewIssues"].append({"code": "EVIDENCE_QUOTE_NOT_FOUND", "target": f"/schedules/{index}", "description": "x"})
    assert completed_payload(candidate)["applicationWindows"] == []
