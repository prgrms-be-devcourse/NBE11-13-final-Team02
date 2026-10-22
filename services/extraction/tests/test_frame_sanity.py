"""판정 틀의 뜻 검사(judgment/sanity.py): 틀 읽기(FrameReader)가 틀 전체를 거절하는 거꾸로 된 범위·공백뿐인 글자.

AI 값 하나가 틀어져도 틀 전체가 버려지지 않게, 그 조건만 원문 질문(확인 필요)으로 바꾸고, 남은 것은 frame_errors가 잡습니다.
"""

import copy

from test_judgment import atom, candidate, item

from mujuthaek_extraction.judgment import build_judgment
from mujuthaek_extraction.judgment.frame import merge_age
from mujuthaek_extraction.judgment.sanity import INVERTED, semantic_errors
from mujuthaek_extraction.judgment.schema import frame_errors


def test_housing_inverted_age_span_and_contradicting_ages_become_review_questions():
    flipped = item("age", "AGE", "만 19세 이상 39세 이하인 자", atom("AGE_YEARS", "BETWEEN", [39, 19], subject="APPLICANT", unit="YEAR"))
    older, younger = atom("AGE_YEARS", "GTE", 40, subject="APPLICANT", unit="YEAR"), atom("AGE_YEARS", "LTE", 39, subject="APPLICANT", unit="YEAR")
    both = {"kind": "ALL", "children": [older, younger]}
    clash = item("age", "AGE", "만 40세 이상, 만 39세 이하인 자", both)
    for made in (flipped, clash):
        frame = build_judgment(candidate([made]))
        assert frame is not None and frame_errors(frame) == []
        [criterion] = frame["common"]
        assert criterion["kind"] == "ASK" and criterion["inReview"] and INVERTED in criterion["reviewReason"]
    ages = [
        {"id": "a", "kind": "AGE", "quote": "q", "page": None, "inReview": False, "reviewReason": None, "minAge": 19, "maxAge": None},
        {"id": "b", "kind": "AGE", "quote": "q", "page": None, "inReview": False, "reviewReason": None, "minAge": None, "maxAge": 39},
    ]
    assert [(c["kind"], c.get("minAge"), c.get("maxAge")) for c in merge_age(copy.deepcopy(ages))] == [("AGE", 19, 39)]


def test_frame_errors_catches_what_the_frame_reader_rejects():
    """틀 읽기(FrameReader)의 뜻 규칙: 스키마는 통과해도 틀 전체가 거절되는 것들. 내보내기 전 마지막 안전망."""
    when = {"factor": "HOUSEHOLD_SIZE", "min": 3, "max": 1, "since": None, "countRule": None}
    dual = when | {"factor": "DUAL_INCOME", "min": 1, "max": None}
    rules = [
        ({"kind": "REVENUE", "year": 2025, "minWon": 2, "maxWon": 1}, "minWon"),
        ({"kind": "BUSINESS_AGE", "openedFrom": "2026-01-02", "openedTo": "2026-01-01"}, "openedFrom"),
        ({"kind": "CLOSURE", "mode": "ANY", "closedFrom": "2026-01-02", "closedTo": "2026-01-01"}, "closedFrom"),
        (
            {"kind": "INCOME", "basis": "HOUSEHOLD", "standard": "MEDIAN_INCOME", "year": 2025, "percent": 100, "bonuses": [{"when": when, "addPercent": 1}]},
            "min",
        ),
        ({"kind": "ASSET", "basis": "HOUSEHOLD", "maxWon": 1, "overrides": [{"when": dual, "maxWon": 2}]}, "맞벌이"),
        ({"kind": "ASK", "question": " ", "metWhen": "YES"}, "question"),
        ({"kind": "SAVINGS", "minBalanceWon": 1, "depositAreaM2": 85}, "depositAreaM2"),
        ({"kind": "BUSINESS_REGISTRATION", "require": "REGISTERED", "closedWithinYears": 3}, "closedWithinYears"),
        ({"kind": "CHILDREN", "minCount": None, "youngestMaxAge": None}, "minCount"),
        ({"kind": "REGION", "basis": "RESIDENCE", "regionCodes": ["1100000000", "1100000000"]}, "regionCodes"),
        ({"kind": "ANY_OF", "options": [{"label": " ", "criteria": []}]}, "options"),
    ]
    for fields, word in rules:
        frame = {"schema": "x", "groups": [{"id": "g", "name": "전체", "criteria": [{"id": "c"} | fields]}], "notes": []}
        assert any(word in e for e in semantic_errors(frame)), fields["kind"]
