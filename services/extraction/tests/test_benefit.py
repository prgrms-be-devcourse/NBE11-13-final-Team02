"""주거·이주 혜택(BENEFIT) 정제 → policy.extracted (2026-10-25, 담당자3 초안 모양). 합성 자료와 녹화 응답만 씁니다. AI·네트워크 호출 없음."""

import json
from contextlib import contextmanager

import pytest

from mujuthaek_extraction.assemble.regions import RegionCodeTable
from mujuthaek_extraction.benefit.build import build_payload, has_won, quoted, same_value, won_amounts
from mujuthaek_extraction.benefit.codes import rules_for, youth_rules
from mujuthaek_extraction.benefit.reading import BenefitReading, read_benefit
from mujuthaek_extraction.contracts import tools
from mujuthaek_extraction.model import ModelError, RecordedModel

REGIONS = RegionCodeTable(
    [
        ("2700000000", "대구광역시", True),
        ("2711000000", "대구광역시 중구", True),
        ("2714000000", "대구광역시 동구", True),
        ("5200000000", "전북특별자치도", True),
        ("5273000000", "전북특별자치도 무주군", True),
    ],
    "2026-10-01",
)

TEXT = """제목: 가상 청년 전월세 보증금 이자 지원
기관: 가상광역시 주택과
지역: 시군구
분야: 주거, 전월세 및 주거급여 지원
[항목]
정책 설명: 목돈 마련이 어려운 가상 청년을 위해 전월세 임차보증금 대출이자 지원
지원 내용: 대출금 최대 1억원(임차보증금의 90% 이내)의 최대 연 3.5% 이자지원
지원 대상 최소 연령: 19
지원 대상 최대 연령: 39
추가 신청 자격: 19 ~ 39세 대구시 거주 무주택 청년
 - (물건기준) 전세보증금 2.5억원 이하 주거용 주택
 - (소득기준) 연소득 6천만원(부부합산 8천만원)이하
신청 방법: 가상 누리집 온라인 신청
사업 시작일: 20260107
사업 종료일: 20261231"""

RECORD = {
    "jobCd": "0013010",
    "zipCd": "27110,27140",
    "plcyNo": "20261021000000000001",
    "sbizCd": "0014010",
    "aplyYmd": None,
    "schoolCd": "0049010",
    "mrgSttsCd": "0055003",
    "earnCndSeCd": "0043001",
    "earnMaxAmt": "0",
    "earnMinAmt": "0",
    "aplyPrdSeCd": "0057002",
    "bizPrdBgngYmd": "20260107",
    "bizPrdEndYmd": "20261231",
    "sprtTrgtMaxAge": "39",
    "sprtTrgtMinAge": "19",
    "sprtTrgtAgeLmtYn": "N",
}


def payload(**changes):
    base = {
        "rawPolicyId": "01929a3e-0000-7000-8000-0000000b0001",
        "version": 1,
        "sourceCode": "youth",
        "provider": "YOUTHCENTER",
        "field": "BENEFIT",
        "policyId": "20261021000000000001",
        "title": "가상 청년 전월세 보증금 이자 지원",
        "agency": "가상광역시 주택과",
        "regionScope": "SIGUNGU",
        "regionNames": [],
        "regionCodes": ["27110", "27140"],
        "categories": ["주거", "전월세 및 주거급여 지원"],
        "detailUrl": "https://example.test/policy/1",
        "applyUrl": None,
        "sourceModifiedAt": None,
        "text": TEXT,
        "textKey": None,
        "record": RECORD,
        "hasDocument": False,
        "hasOriginText": False,
        "documentStatus": "NONE",
        "originStatus": "NONE",
        "files": [],
        "reprocess": False,
    }
    return base | changes


def condition(type_, evidence, **fields):
    empty = {
        "group": 1,
        "type": type_,
        "rawText": evidence,
        "evidence": evidence,
        "confidence": 0.9,
        "min": None,
        "max": None,
        "values": [],
        "basis": None,
        "regionNames": [],
        "months": None,
        "amountMinWon": None,
        "amountMaxWon": None,
        "amountPer": None,
        "assetKind": None,
        "leaseKinds": [],
        "depositMaxWon": None,
        "monthlyMaxWon": None,
    }
    return empty | fields


def answer(conditions=None, supports=None, **changes):
    base = {
        "relevant": True,
        "relevanceReason": "가상 청년의 전월세 보증금 대출 이자를 지원하는 주거 혜택",
        "summary": "가상 청년 전월세 보증금 대출 이자 최대 연 3.5% 지원",
        "supports": supports
        if supports is not None
        else [
            {
                "kind": "DEPOSIT_LOAN",
                "amountMaxWon": None,
                "amountPer": None,
                "months": None,
                "ratePct": 3.5,
                "rawText": "대출금 최대 1억원의 최대 연 3.5% 이자지원",
                "evidence": "대출금 최대 1억원(임차보증금의 90% 이내)의 최대 연 3.5% 이자지원",
            }
        ],
        "schedule": {"applyStart": None, "applyEnd": None, "applyAlways": True, "businessStart": "2026-01-07", "businessEnd": "2026-12-31", "rawText": None},
        "howToApply": "가상 누리집 온라인 신청",
        "documents": [],
        "contact": None,
        "conditions": conditions or [],
    }
    return base | changes


def built(conditions=None, **kw):
    reading = BenefitReading(answer(conditions, **kw), truncated=False, model="recorded")
    data = payload()
    return build_payload(data, reading, rules_for("youth", data["record"], data["text"]), REGIONS)


# ---- 규칙 (온통청년 코드 칸) ----


def test_youth_rules_move_age_and_schedule_and_skip_no_limit_codes_and_policy_region():
    rules = youth_rules(RECORD, TEXT)
    by_type = {c["type"]: c for c in rules.conditions}
    # 취업·학력·특화·결혼은 「제한없음」 코드라 줄 없음. zipCd는 정책 지역이라 거주 조건으로 옮기지 않음
    assert set(by_type) == {"AGE"}
    assert by_type["AGE"]["value"] == {"min": 19, "max": 39} and by_type["AGE"]["evidence"] == "지원 대상 최대 연령: 39"
    assert all(c["origin"] == "RULE" and c["confidence"] == 1 for c in rules.conditions)
    assert rules.schedule == {"applyAlways": True, "rawText": "상시", "businessStart": "2026-01-07", "businessEnd": "2026-12-31"}


def test_youth_codes_with_several_values_become_one_or_group():
    record = RECORD | {
        "jobCd": "0013001,0013008",
        "mrgSttsCd": "0055001",
        "earnCndSeCd": "0043002",
        "earnMaxAmt": "5000",
        "aplyPrdSeCd": "0057001",
        "aplyYmd": "20260123 ~ 20260202",
    }
    rules = youth_rules(record, TEXT)
    jobs = [c for c in rules.conditions if c["type"] in ("EMPLOYMENT", "OCCUPATION")]
    assert [(c["type"], c["value"]) for c in jobs] == [("EMPLOYMENT", {"in": ["EMPLOYED"]}), ("OCCUPATION", {"in": ["FARMER"]})]
    assert len({c["group"] for c in jobs}) == 1  # 「재직자 또는 영농종사자」
    assert {"type": "MARITAL", "value": {"in": ["MARRIED"]}}.items() <= next(c for c in rules.conditions if c["type"] == "MARITAL").items()
    income = next(c for c in rules.conditions if c["type"] == "INCOME_AMOUNT")
    # 본인·부부 기준을 몰라 값은 비우고 금액만 조립에 넘김
    assert (income["known"], income["value"], income["rawText"]) == (False, None, "연소득 5000만 원 이하")
    assert rules.income == {"max": 5000}
    assert rules.schedule["applyStart"] == "2026-01-23" and rules.schedule["applyEnd"] == "2026-02-02" and rules.schedule["applyAlways"] is False


def test_other_sources_have_no_rules_yet():
    assert rules_for("gov24", {"JA0110": "Y"}, TEXT).conditions == []


# ---- 금액·근거 ----


@pytest.mark.parametrize(
    ("quote", "won"),
    [
        ("연소득 6천만원 이하", 60_000_000),
        ("전세보증금 2.5억원 이하", 250_000_000),
        ("최대 1억 5천만원", 150_000_000),
        ("5,000만 원", 50_000_000),
        ("월 20만원", 200_000),
    ],
)
def test_korean_amounts_are_read(quote, won):
    assert has_won(quote, won)


def test_numbers_without_money_unit_are_not_amounts():
    assert won_amounts("만 19~39세, 2026년") == set()


def test_quote_must_be_in_the_source():
    assert quoted("연소득 6천만원(부부합산 8천만원)이하", [TEXT])
    assert not quoted("연소득 7천만원 이하", [TEXT])
    assert not quoted("청년", [TEXT])  # 너무 짧은 인용


# ---- 조립 ----


def test_ai_conditions_are_checked_and_converted_to_manwon():
    data = built(
        [
            condition("HOMELESS", "대구시 거주 무주택 청년", basis="PERSON", group=1),
            condition("INCOME_AMOUNT", "연소득 6천만원(부부합산 8천만원)이하", group=2, amountMaxWon=60_000_000, amountPer="YEAR", basis="PERSONAL"),
            condition("INCOME_AMOUNT", "연소득 6천만원(부부합산 8천만원)이하", group=2, amountMaxWon=80_000_000, amountPer="YEAR", basis="HOUSEHOLD"),
            condition("LEASE", "전세보증금 2.5억원 이하 주거용 주택", group=3, leaseKinds=["JEONSE"], depositMaxWon=250_000_000),
        ]
    )
    ai = [c for c in data["conditions"] if c["origin"] == "AI"]
    assert [(c["type"], c["value"]) for c in ai] == [
        ("HOMELESS", {"basis": "PERSON"}),
        ("INCOME_AMOUNT", {"unit": "MANWON_PER_YEAR", "basis": "PERSONAL", "max": 6000}),
        ("INCOME_AMOUNT", {"unit": "MANWON_PER_YEAR", "basis": "HOUSEHOLD", "max": 8000}),
        ("LEASE", {"unit": "MANWON", "kind": ["JEONSE"], "depositMax": 25000}),
    ]
    rule_groups = {c["group"] for c in data["conditions"] if c["origin"] == "RULE"}
    assert min(c["group"] for c in ai) > max(rule_groups)  # AI 묶음 번호는 규칙 뒤로
    assert ai[1]["group"] == ai[2]["group"]  # 본인 또는 부부 기준
    assert data["supports"][0]["ratePct"] == 3.5 and data["supports"][0]["amountMax"] is None
    assert data["quality"]["needsReview"] is False and data["quality"]["reviewReasons"] == ["NO_DOCUMENT"]
    assert (
        tools().validate(
            {
                "eventId": "01929a3e-0000-7000-8000-000000000e01",
                "eventType": "policy.extracted",
                "schemaVersion": 1,
                "occurredAt": "2026-10-25T00:00:00Z",
                "producer": "extraction",
                "payload": data,
            },
            "events/policy.extracted.v1.json",
        )
        == []
    )


def test_wrong_number_or_missing_quote_leaves_the_value_empty():
    data = built(
        [
            condition("INCOME_AMOUNT", "연소득 6천만원(부부합산 8천만원)이하", amountMaxWon=70_000_000, amountPer="YEAR", basis="PERSONAL"),
            condition("HOMELESS", "주택을 소유하지 않은 세대주", basis="HOUSEHOLD", group=2),
        ]
    )
    ai = [c for c in data["conditions"] if c["origin"] == "AI"]
    assert [(c["type"], c["known"], c["value"]) for c in ai] == [("INCOME_AMOUNT", False, None), ("HOMELESS", False, None)]
    assert ai[1]["confidence"] <= 0.3
    assert "EVIDENCE_NOT_FOUND" in data["quality"]["reviewReasons"] and data["quality"]["needsReview"] is True


def test_rule_replaces_the_same_ai_row_and_a_real_difference_empties_the_rule_value():
    same = built([condition("AGE", "19 ~ 39세 대구시 거주 무주택 청년", min=19, max=39)])
    assert [(c["origin"], c["known"]) for c in same["conditions"]] == [("RULE", True)]  # AI의 나이는 규칙 줄로 대신
    assert "RULE_AI_CONFLICT" not in same["quality"]["reviewReasons"]
    differ = built([condition("AGE", "19 ~ 39세 대구시 거주 무주택 청년", min=19, max=34)])
    assert "RULE_AI_CONFLICT" not in differ["quality"]["reviewReasons"]  # 34가 근거에 없어 값이 비므로 비교하지 않음
    other = built_text(TEXT + "\n신청 자격: 만 19세 이상 34세 이하", [condition("AGE", "만 19세 이상 34세 이하", min=19, max=34)])
    assert [(c["origin"], c["known"], c["value"]) for c in other["conditions"]] == [("RULE", False, None)]  # 확실하지 않으면 비움
    assert "RULE_AI_CONFLICT" in other["quality"]["reviewReasons"] and other["quality"]["needsReview"] is True


def test_same_value_treats_region_containment_as_same():
    assert same_value("RESIDENCE", {"regions": ["27110", "27140"]}, {"regions": ["27"]})
    assert not same_value("RESIDENCE", {"regions": ["27110"]}, {"regions": ["52"]})


def test_text_only_conditions_are_unknown_and_source_regions_are_filled():
    data = build_payload(
        payload(regionCodes=[], regionNames=["전북특별자치도 무주군"], sourceCode="gov24", provider="GOV24", record={}),
        BenefitReading(answer([condition("EXCLUSIVE", "대구시 거주 무주택 청년")]), truncated=True, model="recorded"),
        rules_for("gov24", {}, TEXT),
        REGIONS,
    )
    assert data["conditions"][0]["known"] is False and data["conditions"][0]["value"] is None
    assert data["source"]["regionCodes"] == ["52730"]
    assert {"TEXT_TRUNCATED", "LOW_CONFIDENCE"} <= set(data["quality"]["reviewReasons"]) and data["quality"]["confidence"] <= 0.5


# ---- AI 읽기·worker ----


def test_read_benefit_refuses_bad_shape():
    with pytest.raises(ModelError) as caught:
        read_benefit(payload(), RecordedModel({"benefit_reading": ['{"relevant": true}']}))
    assert caught.value.code == "BAD_SHAPE"


class FakeStore:
    def __init__(self):
        self.saved = []

    def judged(self, raw_event):
        return False

    def save_judgment(self, raw_event, event):
        self.saved.append(event)
        return True


def raw_event(**changes):
    return {"eventId": "01929a3e-0000-7000-8000-00000000e0b1", "eventType": "raw.policy.collected", "payload": payload(**changes)}


def test_judge_benefit_publishes_a_contract_valid_policy_extracted():
    from mujuthaek_extraction.worker import judge_benefit

    store = FakeStore()
    model = RecordedModel({"benefit_reading": [json.dumps(answer([condition("HOMELESS", "대구시 거주 무주택 청년", basis="PERSON")]), ensure_ascii=False)]})
    event_id = judge_benefit(store, raw_event(), model, REGIONS)
    [event] = store.saved
    assert event_id == event["eventId"] and event["eventType"] == "policy.extracted"
    assert tools().validate(event, "events/policy.extracted.v1.json") == []
    assert judge_benefit(store, raw_event(field="STARTUP_SUPPORT"), model, REGIONS) is None


def test_judge_benefit_maps_model_failures():
    from mujuthaek_extraction.worker import AiFailed, judge_benefit

    with pytest.raises(AiFailed):
        judge_benefit(FakeStore(), raw_event(), RecordedModel({"benefit_reading": ["not json"]}), REGIONS)


def test_benefit_is_skipped_unless_enabled(monkeypatch):
    from mujuthaek_extraction import worker

    called = []
    monkeypatch.setattr(worker, "judge_benefit", lambda *a: called.append("benefit"))
    monkeypatch.setattr(worker, "judge_policy", lambda *a: called.append("policy"))

    class Settings:
        benefit_enabled = False
        notice_budget_usd = 1.0

    class Models:
        fallback = None

        def for_notice(self, ledger=None):
            return self

        primary = None

    class Processor:
        models = Models()
        regions = None

        def open_ledger(self, raw_event):
            return None

    @contextmanager
    def session(settings):
        yield FakeStore()

    monkeypatch.setattr(worker, "store_session", session)
    retries = worker.RetryCounter(3) if hasattr(worker, "RetryCounter") else None
    assert worker.judge_message(Settings(), Processor(), raw_event(), retries) == "ack" and called == []
    Settings.benefit_enabled = True
    assert worker.judge_message(Settings(), Processor(), raw_event(), retries) == "ack" and called == ["benefit"]


# ---- 묶음(group): 같은 번호 = 「또는」 (2026-10-25 첫 유료 시험에서 판정이 틀어진 경우) ----

OR_TEXT = TEXT + "\n신청 자격: 대구광역시에 주소를 두거나 대구시 소재 대학 또는 직장에 재학, 재직하는 청년\n - 쉐어하우스 입주자는 신청 불가"


def built_text(text, conditions, record=RECORD):
    data = payload(text=text, record=record)
    reading = BenefitReading(answer(conditions), truncated=False, model="recorded")
    return build_payload(data, reading, rules_for("youth", data["record"], text), REGIONS)


EMPLOYED = RECORD | {"jobCd": "0013001"}


def test_rule_row_joins_the_ai_or_group_it_replaces():
    quote = "대구광역시에 주소를 두거나 대구시 소재 대학 또는 직장에 재학, 재직하는 청년"
    data = built_text(
        OR_TEXT,
        [
            condition("RESIDENCE", quote, group=1, regionNames=["대구광역시"]),
            condition("EDUCATION", quote, group=1, values=["UNIV_ENROLLED"]),
            condition("EMPLOYMENT", quote, group=1, values=["EMPLOYED"]),
        ],
        EMPLOYED,
    )
    groups = {c["type"]: c["group"] for c in data["conditions"]}
    assert groups["RESIDENCE"] == groups["EDUCATION"] == groups["EMPLOYMENT"]  # 「거주 또는 재학 또는 재직」
    assert groups["AGE"] != groups["RESIDENCE"]
    assert [c["origin"] for c in data["conditions"] if c["type"] == "EMPLOYMENT"] == ["RULE"]


def test_rule_row_stays_alone_when_the_ai_group_has_nothing_else():
    data = built_text(OR_TEXT, [condition("EMPLOYMENT", "직장에 재학, 재직하는 청년", group=1, values=["EMPLOYED"])], EMPLOYED)
    assert sorted(c["group"] for c in data["conditions"]) == [1, 2]


def test_text_only_rows_get_their_own_group_and_rule_lines_are_not_repeated():
    data = built_text(
        OR_TEXT,
        [
            condition("LEASE", "전세보증금 2.5억원 이하 주거용 주택", group=1, leaseKinds=["JEONSE"], depositMaxWon=250_000_000),
            condition("OTHER", "쉐어하우스 입주자는 신청 불가", group=1),
            condition("OTHER", "지원 대상 최대 연령: 39", group=1),  # 규칙 줄(AGE)의 근거를 다시 낸 줄
        ],
    )
    ai = [c for c in data["conditions"] if c["origin"] == "AI"]
    assert [c["type"] for c in ai] == ["LEASE", "OTHER"]
    assert ai[0]["group"] != ai[1]["group"]
    assert len({c["group"] for c in data["conditions"]}) == len(data["conditions"])


def test_unrelated_rows_in_one_ai_group_are_split_and_rules_stay_put():
    # AI가 관계없는 조건을 모두 1번으로 줌: 나이(규칙으로 대신)·임대·무주택은 근거 문장이 달라 「또는」이 아님
    data = built_text(
        OR_TEXT,
        [
            condition("AGE", "지원 대상 최대 연령: 39", group=1, min=19, max=39),
            condition("LEASE", "전세보증금 2.5억원 이하 주거용 주택", group=1, leaseKinds=["JEONSE"], depositMaxWon=250_000_000),
            condition("HOMELESS", "대구시 거주 무주택 청년", group=1, basis="PERSON"),
        ],
    )
    assert len({c["group"] for c in data["conditions"]}) == len(data["conditions"])


def test_same_type_in_two_and_groups_is_a_track_condition_and_left_unknown():
    # 「청년 전형은 미혼, 부부 전형은 기혼」을 「미혼 그리고 기혼」으로 담으면 아무도 못 맞추므로 값을 비움
    text = TEXT + "\n신청 자격: 청년 전형은 미혼 청년\n청년부부 전형은 혼인한 부부"
    data = built_text(
        text,
        [
            condition("MARITAL", "청년 전형은 미혼 청년", group=1, values=["SINGLE"]),
            condition("MARITAL", "청년부부 전형은 혼인한 부부", group=2, values=["MARRIED"]),
            condition("HOMELESS", "대구시 거주 무주택 청년", group=3, basis="PERSON"),
        ],
    )
    marital = [c for c in data["conditions"] if c["type"] == "MARITAL"]
    assert [(c["known"], c["value"]) for c in marital] == [(False, None), (False, None)]
    assert next(c for c in data["conditions"] if c["type"] == "HOMELESS")["known"] is True


INCOME_RECORD = RECORD | {"earnCndSeCd": "0043002", "earnMaxAmt": "6000"}
INCOME_QUOTE = "연소득 6천만원(부부합산 8천만원)이하"


def test_source_income_is_replaced_by_the_ai_rows_that_read_the_same_amount_with_its_basis():
    data = built_text(
        TEXT,
        [
            condition("INCOME_AMOUNT", INCOME_QUOTE, amountMaxWon=60_000_000, amountPer="YEAR", basis="PERSONAL"),
            condition("INCOME_AMOUNT", INCOME_QUOTE, amountMaxWon=80_000_000, amountPer="YEAR", basis="HOUSEHOLD"),
        ],
        INCOME_RECORD,
    )
    income = [(c["origin"], c["value"]) for c in data["conditions"] if c["type"] == "INCOME_AMOUNT"]
    assert income == [
        ("AI", {"unit": "MANWON_PER_YEAR", "basis": "PERSONAL", "max": 6000}),
        ("AI", {"unit": "MANWON_PER_YEAR", "basis": "HOUSEHOLD", "max": 8000}),
    ]
    assert "RULE_AI_CONFLICT" not in data["quality"]["reviewReasons"]


def test_source_income_stays_unknown_without_a_text_reading_or_with_a_different_amount():
    alone = built_text(TEXT, [], INCOME_RECORD)
    assert [(c["origin"], c["known"]) for c in alone["conditions"] if c["type"] == "INCOME_AMOUNT"] == [("RULE", False)]
    assert alone["quality"]["needsReview"] is False
    guessed = built_text(  # 출처 칸 줄을 근거로 기준을 지어낸 줄은 세지 않음
        TEXT + "\n소득 최대 금액: 6000",
        [condition("INCOME_AMOUNT", "소득 최대 금액: 6000", amountMaxWon=60_000_000, amountPer="YEAR", basis="PERSONAL")],
        INCOME_RECORD,
    )
    assert [(c["origin"], c["known"]) for c in guessed["conditions"] if c["type"] == "INCOME_AMOUNT"] == [("RULE", False)]
    other = built_text(
        TEXT,
        [condition("INCOME_AMOUNT", INCOME_QUOTE, amountMaxWon=80_000_000, amountPer="YEAR", basis="HOUSEHOLD")],
        RECORD | {"earnCndSeCd": "0043002", "earnMaxAmt": "5000"},
    )
    assert [(c["origin"], c["known"]) for c in other["conditions"] if c["type"] == "INCOME_AMOUNT"] == [("RULE", False)]
    assert "RULE_AI_CONFLICT" in other["quality"]["reviewReasons"]


def test_amounts_that_differ_only_by_a_condition_in_one_or_group_are_left_unknown():
    text = TEXT + "\n(소득) 부부합산 연소득 1.3억원 이하 (단, 맞벌이 가구는 2억원 이하)"
    quote = "부부합산 연소득 1.3억원 이하 (단, 맞벌이 가구는 2억원 이하)"
    data = built_text(
        text,
        [
            condition("INCOME_AMOUNT", quote, amountMaxWon=130_000_000, amountPer="YEAR", basis="HOUSEHOLD"),
            condition("INCOME_AMOUNT", quote, amountMaxWon=200_000_000, amountPer="YEAR", basis="HOUSEHOLD"),
        ],
    )
    assert [(c["known"], c["value"]) for c in data["conditions"] if c["type"] == "INCOME_AMOUNT"] == [(False, None), (False, None)]
