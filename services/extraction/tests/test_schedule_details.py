"""일정 접수 방법·장소·시각, 서류 묶음 확인(프론트 요청 6-1~6-5, 2026-10-23). 합성 원문만 씁니다. AI 호출 없음."""

import copy
import dataclasses
from datetime import UTC, datetime

from helpers import ai_part, example, source_for

from mujuthaek_extraction.assemble.assembler import assemble
from mujuthaek_extraction.assemble.details import channel_mentions, channels_from_words, times_in, upgrade_stored
from mujuthaek_extraction.contracts import tools
from mujuthaek_extraction.publish import completed_payload
from mujuthaek_extraction.verify import SourceBundle, verify_extraction

VISIT = (
    "현장 접수는 2026년 10월 5일 10:00부터 10월 6일 오후 6시까지 가상주거공사 1층 접수처(서울특별시 종로구 가상로 1, 101호)에서 받습니다. "
    "접수 시간은 10:00~18:00(점심시간 12:00~13:00 제외)이며 토·일요일 및 공휴일은 접수하지 않습니다. 인터넷 신청 불가."
)
MAIL = "서류는 이메일(apply@example.org, 제목: [서류] 성명_주택형)이나 등기우편으로 보냅니다. 등기부등본 1부, 주민등록등본 2통(주민센터 발급)."


def assembled(schedule=None, documents=None, extra=VISIT + "\n" + MAIL):
    data = example("housing")
    source = source_for("housing", data)
    material = dataclasses.replace(source.material, text=source.material.text + "\n" + extra)
    part = copy.deepcopy(ai_part(data))
    part["quotes"] += [
        {"ref": "visit", "documentId": "text1", "text": VISIT},
        {"ref": "mail", "documentId": "text1", "text": MAIL},
    ]
    if schedule is not None:
        part["schedules"].append(schedule)
    if documents is not None:
        part["requiredDocuments"] += documents
    return assemble([part], dataclasses.replace(source, material=material)), material


def visit_schedule(**changes):
    schedule = {
        "id": "visit",
        "kinds": ["APPLICATION"],
        "rawLabel": "현장 접수",
        "scope": {
            "level": "NOTICE",
            "locationIds": [],
            "supplyGroupIds": [],
            "targetGroups": [],
            "supplyCategory": "ALL",
            "resolution": "RESOLVED",
            "description": "공고 전체예요.",
        },
        "channels": ["ONSITE"],
        "startsAt": {"precision": "MINUTE", "value": "2026-10-05T10:00:00+09:00"},
        "endsAt": {"precision": "MINUTE", "value": "2026-10-06T18:00:00+09:00"},
        "endStatus": "FIXED",
        "endCondition": None,
        "description": "현장 접수 기간이에요.",
        "applicationUrl": None,
        "priorityRank": None,
        "applicationMode": "FIXED_PERIOD",
        "places": [{"name": "가상주거공사 1층 접수처", "address": "서울특별시 종로구 가상로 1", "detail": "101호"}],
        "applicationEmail": None,
        "dailyHours": [{"start": "10:00", "end": "18:00"}],
        "breaks": [{"start": "12:00", "end": "13:00"}],
        "excludedDays": ["SAT", "SUN", "HOLIDAY"],
        "timeNote": "마지막 날은 오후 6시까지예요.",
        "quoteRefs": ["visit"],
    }
    schedule.update(changes)
    return schedule


def document(**changes):
    item = {
        "id": "resident",
        "name": "주민등록등본",
        "scope": {
            "level": "NOTICE",
            "locationIds": [],
            "supplyGroupIds": [],
            "targetGroups": ["청년"],
            "supplyCategory": "ALL",
            "resolution": "RESOLVED",
            "description": "청년이에요.",
        },
        "appliesTo": "신청자",
        "submissionTime": "서류 제출 시",
        "group": "COMMON",
        "copies": 2,
        "issuer": "주민센터",
        "description": None,
        "quoteRefs": ["mail"],
    }
    item.update(changes)
    return item


def cleared(result):
    return [i["description"] for i in result.issues if i["code"] == "DETAIL_UNVERIFIED_CLEARED"]


def test_verified_place_time_days_and_channels_are_kept():
    result, _ = assembled(visit_schedule())
    assert result.contract_errors == [] and cleared(result) == []
    schedule = result.candidate["schedules"][-1]
    assert schedule["places"] == [{"name": "가상주거공사 1층 접수처", "address": "서울특별시 종로구 가상로 1", "detail": "101호"}]
    assert schedule["dailyHours"] == [{"start": "10:00", "end": "18:00"}] and schedule["breaks"] == [{"start": "12:00", "end": "13:00"}]
    assert schedule["excludedDays"] == ["SAT", "SUN", "HOLIDAY"] and schedule["timeNote"] == "마지막 날은 오후 6시까지예요."
    assert schedule["endsAt"] == {"precision": "MINUTE", "value": "2026-10-06T18:00:00+09:00"}  # 「오후 6시」
    # 공고 전체 신청 방법은 신청 일정들의 방법을 정해진 순서로 합칩니다(예시 일정 ONLINE + 현장 ONSITE)
    assert result.candidate["notice"]["applicationChannels"] == ["ONLINE", "ONSITE"]


def test_unverified_values_are_cleared_not_guessed():
    schedule = visit_schedule(
        channels=["ONSITE", "ONLINE", "FAX"],  # 인터넷은 「불가」, 팩스는 근거에 없음
        places=[{"name": "가상주거공사 2층 접수처", "address": "서울특별시 종로구 가상로 9", "detail": "101호"}],
        dailyHours=[{"start": "09:00", "end": "18:00"}],
        excludedDays=["MON"],
        timeNote="마감일은 17:30까지예요.",
        endsAt={"precision": "MINUTE", "value": "2026-10-06T17:00:00+09:00"},
    )
    result, _ = assembled(schedule)
    assert result.contract_errors == []
    kept = result.candidate["schedules"][-1]
    assert kept["channels"] == ["ONSITE"]
    assert kept["places"] == [{"name": None, "address": None, "detail": "101호"}]
    assert kept["dailyHours"] == [] and kept["breaks"] == [{"start": "12:00", "end": "13:00"}]
    assert kept["excludedDays"] == [] and kept["timeNote"] is None
    assert kept["endsAt"] == {"precision": "DAY", "value": "2026-10-06"}  # 근거에 없는 시각은 날짜만 남깁니다
    assert len(cleared(result)) == 6
    # 일정 항목이 아니라 섹션을 가리켜 이 일정이 접수 기간에서 빠지지 않습니다
    assert {i["target"] for i in result.issues if i["code"] == "DETAIL_UNVERIFIED_CLEARED"} == {"/schedules"}


def test_place_with_nothing_verified_is_removed_and_email_checked():
    email = {"address": "apply@example.org", "subjectFormat": "[서류] 성명_주택형"}
    schedule = visit_schedule(
        id="mail",
        kinds=["DOCUMENT_SUBMISSION"],
        rawLabel="서류 제출",
        channels=[],
        applicationMode="NOT_APPLICABLE",
        places=[{"name": "어딘가", "address": None, "detail": None}],
        applicationEmail=email,
        dailyHours=[],
        breaks=[],
        excludedDays=[],
        timeNote=None,
        startsAt={"precision": "DAY", "value": "2026-10-05"},
        endsAt={"precision": "DAY", "value": "2026-10-06"},
        quoteRefs=["mail"],
    )
    result, _ = assembled(schedule)
    kept = result.candidate["schedules"][-1]
    assert kept["places"] == [] and kept["applicationEmail"] == email
    # AI가 비운 신청 방법은 서류 제출 일정의 근거 낱말로 정합니다: 이메일·등기우편(「등기부등본」은 우편이 아님)
    assert kept["channels"] == ["EMAIL", "MAIL"]
    wrong, _ = assembled({**schedule, "applicationEmail": {"address": "other@example.org", "subjectFormat": None}})
    assert wrong.candidate["schedules"][-1]["applicationEmail"] is None


def test_channel_words_and_negations():
    assert channel_mentions("인터넷 신청 불가, 방문 접수만 받습니다") == ({"ONSITE"}, {"ONLINE"})
    assert channel_mentions("전자우편으로 제출") == ({"EMAIL"}, set())
    assert channel_mentions("등기부등본 1부, 우편번호 03000") == (set(), set())
    assert channel_mentions("등기로 발송하거나 팩스(FAX)로") == ({"MAIL", "FAX"}, set())
    assert channels_from_words(["이메일", "서초구", "주택 전화예약 후 방문", "ONLINE"]) == ["ONLINE", "ONSITE", "EMAIL"]


def test_time_words():
    assert {10 * 60, 18 * 60, 12 * 60 + 30} <= times_in("10시부터 오후 6시까지, 12시 30분")
    assert 6 * 60 in times_in("6시") and 18 * 60 in times_in("6시")  # 오전·오후 표시가 없으면 둘 다
    assert 6 * 60 not in times_in("오후 6시")
    assert times_in("24시간 운영, 1시간 이내") == set()


def test_document_copies_issuer_checked_and_duplicates_merged():
    documents = [
        document(),
        document(id="resident-2", scope={**document()["scope"], "targetGroups": ["신혼부부"], "description": "신혼부부예요."}, appliesTo="신혼부부"),
        document(id="registry", name="등기부등본", group="CONDITIONAL", copies=3, issuer="법원", appliesTo="주택 소유자"),
        document(id="resident-proxy", group="PROXY_SPOUSE"),  # 묶음이 다르면 합치지 않습니다
    ]
    result, _ = assembled(documents=documents)
    assert result.contract_errors == []
    names = [(d["id"], d["group"]) for d in result.candidate["requiredDocuments"]]
    assert names[-3:] == [("resident", "COMMON"), ("registry", "CONDITIONAL"), ("resident-proxy", "PROXY_SPOUSE")]
    merged = result.candidate["requiredDocuments"][-3]
    assert merged["scope"]["targetGroups"] == ["청년", "신혼부부"] and merged["appliesTo"] == "신청자, 신혼부부"
    assert (merged["copies"], merged["issuer"]) == (2, "주민센터")
    registry = result.candidate["requiredDocuments"][-2]
    assert (registry["copies"], registry["issuer"]) == (None, None)  # 근거는 「1부」, 법원은 근거에 없음
    targets = {i["target"] for i in result.issues if i["code"] == "DETAIL_UNVERIFIED_CLEARED"}
    assert targets == {f"/requiredDocuments/{len(names) - 2}"}


def test_coerced_schedule_is_published_as_held_item():
    # 원문은 계약 예시 그대로 둡니다(원문을 바꾸면 문서 해시가 달라 공고 전체가 검수로 감)
    data = example("housing")
    source = source_for("housing", data)
    part = copy.deepcopy(ai_part(data))
    part["schedules"].append({**copy.deepcopy(part["schedules"][0]), "id": "broken", "kinds": ["APPLICATION", "INTERVIEW"], "applicationMode": "SOMETIMES"})
    result = assemble([part], source)
    report = verify_extraction(result.candidate, SourceBundle.from_material(source.material), None, clock=lambda: datetime(2026, 10, 15, tzinfo=UTC))
    candidate = report.candidate
    index = next(i for i, s in enumerate(candidate["schedules"]) if s["id"] == "broken")
    held = {h["target"]: h["codes"] for h in candidate["quality"].get("heldItems") or []}
    assert candidate["quality"]["verification"]["status"] == "PARTIALLY_PASSED"
    assert "AI_OUTPUT_ITEM_COERCED" in held[f"/schedules/{index}"]
    payload = completed_payload(candidate)
    event = {
        "eventId": "01929a3e-0000-7000-8000-0000000000c1",
        "eventType": "extraction.completed",
        "schemaVersion": 1,
        "occurredAt": "2026-10-15T00:00:00Z",
        "producer": "extraction",
        "traceId": None,
        "payload": payload,
    }
    assert tools().validate(event, "events/extraction.completed.v1.json") == []
    assert payload["reviewStatus"] == "PARTIALLY_PASSED" and payload["heldItems"] == candidate["quality"]["heldItems"]
    assert "broken" not in {w["scheduleId"] for w in payload["applicationWindows"]}


def test_old_stored_candidate_is_upgraded_for_approval():
    data = example("housing")
    old = copy.deepcopy(data)
    for schedule in old["schedules"]:
        for key in ("places", "applicationEmail", "dailyHours", "breaks", "excludedDays", "timeNote"):
            schedule.pop(key)
        schedule["channels"] = ["인터넷 청약", "서초구"]
    for item in old["requiredDocuments"]:
        for key in ("group", "copies", "issuer"):
            item.pop(key)
    old["notice"].pop("applicationChannels")
    assert tools().validate(old, "schemas/extracted-notice.v1.json")
    upgraded = upgrade_stored(old)
    assert tools().validate(upgraded, "schemas/extracted-notice.v1.json") == []
    assert upgraded["schedules"][0]["channels"] == ["ONLINE"] and upgraded["notice"]["applicationChannels"] == ["ONLINE"]
    assert upgrade_stored(copy.deepcopy(upgraded)) == upgraded
