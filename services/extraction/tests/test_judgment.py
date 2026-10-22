"""판정 틀(housing-judgment.v1) 가공: 판정 담당이 준 틀 그대로, 코드만으로. AI 호출 없음."""

import copy

from test_publish import EVENT_SCHEMA, envelope, verified

from mujuthaek_extraction.contracts import tools
from mujuthaek_extraction.judgment import build_judgment
from mujuthaek_extraction.judgment.schema import frame_errors
from mujuthaek_extraction.publish import completed_payload

URBAN = {
    "householdMemberCount": None,
    "householdScope": "해당 세대(세대구성원 전원)",
    "referenceVersion": "전년도",
    "referenceYear": 2025,
    "standardName": "도시근로자 가구원수별 가구당 월평균소득",
}


def atom(attribute, operator, value, subject="HOUSEHOLD", benchmark=None, unit="PERCENT"):
    return {
        "kind": "ATOMIC",
        "subject": subject,
        "attribute": attribute,
        "operator": operator,
        "value": value,
        "unit": unit,
        "basis": None,
        "referenceTime": "입주자모집공고일",
        "evidenceIds": ["ev-1"],
        "benchmark": benchmark,
    }


def item(item_id, category, text, expression=None, groups=(), exceptions=(), description=None):
    return {
        "id": item_id,
        "category": category,
        "appliesTo": None,
        "description": description or text,
        "originalText": text,
        "referenceTime": "입주자모집공고일",
        "rule": {"expression": expression, "status": "STRUCTURED" if expression else "UNSTRUCTURED", "reason": None},
        "scope": {"targetGroups": list(groups)},
        "exceptions": list(exceptions),
        "exceptionsStatus": "PRESENT" if exceptions else "NONE_IN_SOURCE",
        "evidenceIds": ["ev-1"],
    }


def candidate(eligibility, target_groups=(), held=()):
    return {
        "input": {"rawPostId": "00000000-0000-7000-8000-000000000002", "rawVersion": 3, "field": "HOUSING"},
        "notice": {"targetGroups": list(target_groups), "announcedOn": "2026-10-07"},
        "schedules": [],
        "eligibility": eligibility,
        "selectionRules": [],
        "guidance": [],
        "evidence": [{"id": "ev-1", "quote": "1인가구는 20%p, 2인가구는 10%p 가산 적용한 금액임"}],
        "quality": {"reviewIssues": [], "heldItems": list(held)},
    }


def replace_income(exception_id, size, percent):
    return {
        "id": exception_id,
        "originalText": "1인가구는 20%p, 2인가구는 10%p 가산 적용한 금액임",
        "description": f"{size}인가구는 소득 기준 비율을 {percent}%로 바꿔 적용해요.",
        "when": {"expression": atom("HOUSEHOLD_MEMBER_COUNT", "EQ", size, unit="COUNT"), "status": "STRUCTURED", "reason": None},
        "effect": "REPLACE",
        "effectExpression": atom("INCOME_BENCHMARK_PERCENT", "LTE", percent, benchmark={**URBAN, "householdMemberCount": size}),
        "evidenceIds": ["ev-1"],
    }


def criteria(frame):
    out = []

    def walk(items):
        for c in items:
            out.append(c)
            for option in c.get("options") or []:
                walk(option["criteria"])

    walk(frame["common"])
    for group in frame["groups"]:
        walk(group["criteria"])
    return out


def test_completed_event_carries_the_frame_as_given_and_matches_both_contracts():
    payload = completed_payload(verified(), provider="가상주거공사")
    frame = payload["judgment"]
    assert tools().validate(envelope(payload), EVENT_SCHEMA) == [] and frame_errors(frame) == []
    assert (frame["schema"], frame["rawPostId"], frame["rawVersion"]) == ("housing-judgment.v1", payload["rawPostId"], payload["rawVersion"])
    ages = [c for c in criteria(frame) if c["kind"] == "AGE"]
    assert [(c["minAge"], c["maxAge"]) for c in ages] == [(19, 39)]  # 「19세 이상」「39세 이하」 원자 조건 둘 → 하나


def test_household_size_income_exceptions_become_bonuses():
    # 실측 LH 강원: 70% 이하 + 예외 「1인가구 90%」「2인가구 80%」 → 가산 1인 +20%p, 2인 +10%p (틀 예시 서리풀과 같은 모양)
    income = item(
        "income-70",
        "INCOME",
        "해당 세대의 월평균소득이 전년도 도시근로자 가구원수별 가구당 월평균소득의 70% 이하인 자",
        atom("INCOME_BENCHMARK_PERCENT", "LTE", 70, benchmark=URBAN),
        exceptions=[replace_income("ex-1", 1, 90), replace_income("ex-2", 2, 80)],
    )
    frame = build_judgment(candidate([income]))
    [made] = frame["common"]
    assert made["kind"] == "INCOME" and not made["inReview"]
    assert (made["basis"], made["standard"], made["year"], made["percent"]) == ("HOUSEHOLD", "URBAN_WORKER_INCOME", 2025, 70)
    assert [(b["when"]["factor"], b["when"]["min"], b["addPercent"]) for b in made["bonuses"]] == [("HOUSEHOLD_SIZE", 1, 20), ("HOUSEHOLD_SIZE", 2, 10)]


def test_income_without_readable_bonus_is_in_review_when_the_notice_mentions_one():
    # 가산을 빼고 보내면 1·2인 가구가 실제와 달리 불충족이 됩니다
    income = item(
        "income-70",
        "INCOME",
        "월평균소득 70% 이하인 자",
        atom("INCOME_BENCHMARK_PERCENT", "LTE", 70, benchmark=URBAN),
        description="70% 이하여야 해요. 맞벌이는 가산해요.",
    )
    [made] = build_judgment(candidate([income]))["common"]
    assert made["inReview"] and "가산" in made["reviewReason"]


def test_waived_minor_becomes_any_of_with_the_exception_as_question():
    exception = {
        "id": "age-ex-child",
        "originalText": "자녀가 있는 미성년 세대주(단, 동일한 세대별 주민등록표등본에 자녀가 등재되어야 함)",
        "description": "자녀가 있는 미성년 세대주는 신청할 수 있어요.",
        "when": {"expression": None, "status": "UNSTRUCTURED", "reason": "표현할 수 없음"},
        "effect": "WAIVE",
        "effectExpression": None,
        "evidenceIds": ["ev-1"],
    }
    age = item(
        "age",
        "AGE",
        "「민법」상 미성년자(19세 미만)는 공급 신청할 수 없습니다.",
        atom("AGE_YEARS", "GTE", 19, subject="APPLICANT", unit="YEAR"),
        exceptions=[exception],
    )
    [made] = build_judgment(candidate([age]))["common"]
    assert made["kind"] == "ANY_OF" and not made["inReview"]
    base, waived = made["options"]
    assert base["criteria"][0]["minAge"] == 19
    assert waived["criteria"][0]["kind"] == "ASK" and waived["criteria"][0]["metWhen"] == "YES"


def test_rank_items_become_their_own_groups_and_an_umbrella_group_is_asked():
    # 실측 LH 강원: 공고 대상 「저소득계층」, 자격 항목은 「1순위 일반 장애인」(70%)·「2순위」(100%). 예전엔 둘 다 공통 조건이 되어 함께 요구됨
    seventy = item("income-70", "INCOME", "70% 이하인 자", atom("INCOME_BENCHMARK_PERCENT", "LTE", 70, benchmark=URBAN), groups=["1순위 일반 장애인"])
    hundred = item("income-100", "INCOME", "100% 이하인 자", atom("INCOME_BENCHMARK_PERCENT", "LTE", 100, benchmark=URBAN), groups=["2순위"])
    frame = build_judgment(candidate([seventy, hundred], target_groups=["저소득계층"]))
    assert frame["common"] == []
    by_name = {g["name"]: g["criteria"] for g in frame["groups"]}
    assert [(c["kind"], c.get("percent"), c.get("anyOf")) for c in by_name["1순위 일반 장애인"]] == [("STATUS", None, ["DISABLED"]), ("INCOME", 70, None)]
    assert [(c["kind"], c.get("percent")) for c in by_name["2순위"]] == [("INCOME", 100)]
    [umbrella] = by_name["저소득계층"]
    assert umbrella["kind"] == "ASK" and umbrella["inReview"]


def test_sentences_that_are_not_requirements_become_notes():
    # 실측 LH 대전대동2 「거주지역」(따지지 않아요), 인천 정의 문장, 순위 문장
    items = [
        item("res", "RESIDENCE", "거주지역", description="거주지역은 따지지 않아요."),
        item("def", "HOUSEHOLD", "다음의 세대구성원에 해당하는 사람 전원이 주택을 소유하고 있지 않는 세대의 구성원을 말합니다."),
        item("rank", "RESIDENCE", "1순위 | 인천광역시 거주자 / 2순위 | 그 밖의 지역"),
        item("dup", "OTHER", "1세대 1주택 신청하여야 하며, 중복 신청하는 경우 전부 무효 처리합니다"),
    ]
    frame = build_judgment(candidate(items))
    assert frame["common"] == [] and [n["kind"] for n in frame["notes"]] == ["OTHER", "OTHER", "SELECTION", "RULE"]


def test_limit_sentence_is_a_question_answered_no():
    limit = item("foreign", "NATIONALITY", "외국인의 경우 본 아파트 청약신청이 불가합니다.")
    [made] = build_judgment(candidate([limit]))["common"]
    assert (made["kind"], made["metWhen"], made["inReview"]) == ("ASK", "NO", False)


def test_benefit_list_definition_becomes_status_codes():
    text = "「국민기초생활보장법」 제7조제1항제1호~제7조제1항제4호에 따른 생계·주거· 의료·교육급여 수급자"
    frame = build_judgment(candidate([item("recipient", "OTHER", text, groups=["수급자"])], target_groups=["수급자"]))
    [made] = {g["name"]: g for g in frame["groups"]}["수급자"]["criteria"]
    assert made["kind"] == "STATUS" and sorted(made["anyOf"]) == ["EDUCATION_BENEFIT", "HOUSING_BENEFIT", "LIVELIHOOD_BENEFIT", "MEDICAL_BENEFIT"]


def test_held_eligibility_is_sent_in_review_not_dropped():
    held = [{"target": "/eligibility", "extent": "SECTION", "codes": ["CRITERION_NOT_STRUCTURED"], "description": "확인 전까지 보류"}]
    age = item("age", "AGE", "만 19세 이상", atom("AGE_YEARS", "GTE", 19, subject="APPLICANT", unit="YEAR"))
    [made] = build_judgment(candidate([age], held=held))["common"]
    assert made["kind"] == "AGE" and made["inReview"] and "CRITERION_NOT_STRUCTURED" in made["reviewReason"]


def test_no_reference_date_or_other_field_gives_no_frame():
    undated = candidate([])
    undated["notice"]["announcedOn"] = None
    assert build_judgment(undated) is None
    policy = candidate([])
    policy["input"]["field"] = "POLICY"
    assert build_judgment(policy) is None


def test_contract_rejects_a_frame_of_another_notice_or_with_duplicate_ids():
    payload = completed_payload(verified(), provider="가상주거공사")
    other = copy.deepcopy(payload)
    other["judgment"]["rawVersion"] += 1
    assert "EVENT_JUDGMENT_IDENTITY_CONFLICT /payload/judgment" in tools().judgment_errors(other)
    duplicated = copy.deepcopy(payload)
    duplicated["judgment"]["common"].append(copy.deepcopy(duplicated["judgment"]["common"][0]))
    assert "JUDGMENT_DUPLICATE_ID /payload/judgment" in tools().judgment_errors(duplicated)


def business(eligibility, target_groups=(), selection=()):
    made = candidate(eligibility, target_groups)
    made["input"]["field"] = "BUSINESS_SPACE"
    made["selectionRules"] = list(selection)
    return made


def test_business_space_frame_follows_its_own_schema_with_statuses_regions_and_limits():
    # 실측 틀 예시(LH 양산사송 희망상가)의 모양: 대상군 이름이 유형, 주소지 또는 사업장 지역, 신청 제한 사유
    either = {
        "kind": "ANY",
        "children": [
            atom("RESIDENCE_REGION_CODE", "EQ", "4133000000", "APPLICANT", unit="CODE"),
            atom("BUSINESS_REGION_CODE", "EQ", "4133000000", "BUSINESS", unit="CODE"),
        ],
    }
    items = [
        item("region", "BUSINESS_REGION", "주소지 또는 사업장 소재지가 양산시인 자", either),
        item("limits", "OTHER", "피성년후견인 또는 피한정후견인, 국세·지방세 체납자는 신청할 수 없습니다."),
        item("social", "OTHER", "「사회적기업법」에 따라 사회적기업 인증을 받은 자ㆍ예비사회적기업으로 지정된 자", groups=["사회적기업"]),
    ]
    frame = build_judgment(business(items, target_groups=["소상공인", "사회적기업"]))
    assert frame["schema"] == "business-space-judgment.v1" and frame_errors(frame) == [] and frame["preferences"] == []
    region, limits = frame["common"]
    assert (region["kind"], region["basis"], region["regionCodes"]) == ("REGION", "RESIDENCE_OR_BUSINESS_SITE", ["4133000000"])
    assert (limits["kind"], limits["anyOf"], limits["inReview"]) == ("EXCLUDED_STATUS", ["ADULT_WARD", "TAX_ARREARS"], False)
    by_name = {g["name"]: g["criteria"] for g in frame["groups"]}
    assert [(c["kind"], c["anyOf"]) for c in by_name["소상공인"]] == [("STATUS", ["SMALL_BUSINESS"])]
    assert [(c["kind"], c["anyOf"]) for c in by_name["사회적기업"]] == [("STATUS", ["PRE_SOCIAL_ENTERPRISE", "SOCIAL_ENTERPRISE"])]


def test_business_space_scoring_rule_becomes_a_bonus_preference_and_industry_limit_is_kept():
    scoring = {
        "id": "local-bonus",
        "kind": "SCORING",
        "points": 5,
        "maxPoints": None,
        "description": "업체 소재지가 성동구인 경우 5점",
        "originalText": "업체 소재지가 성동구인 경우 5점",
        "rule": {"expression": atom("BUSINESS_REGION_CODE", "EQ", "1120000000", "BUSINESS", unit="CODE"), "status": "STRUCTURED", "reason": None},
        "scope": {"targetGroups": []},
    }
    industry = atom("INDUSTRY_CODE", "NOT_IN", ["56", "64"], "BUSINESS", benchmark={"standardName": "한국표준산업분류 11차"}, unit="CODE")
    frame = build_judgment(business([item("industry", "INDUSTRY", "음식점업·금융업은 입점할 수 없습니다", industry)], selection=[scoring]))
    assert frame_errors(frame) == []
    [limit] = frame["common"]
    assert (limit["mode"], limit["revision"], limit["codes"]) == ("EXCLUDE", 11, ["56", "64"])
    [preference] = frame["preferences"]
    assert (preference["effect"], preference["points"], preference["criteria"][0]["basis"]) == ("BONUS_POINTS", 5, "BUSINESS_SITE")


def test_term_definitions_bonus_notes_and_rules_are_not_asked_as_requirements():
    # 실측 2026-10-21 광주선운2·든든전세·김해 매각: 정의·가산 안내·신청 규칙·입주 후 의무가 「자격을 갖추었나요?」 질문으로 나감
    income = item("income", "INCOME", "월평균소득 100% 이하", atom("INCOME_BENCHMARK_PERCENT", "LTE", 100, benchmark=URBAN))
    items = [
        item("def", "HOUSEHOLD", "※ 무주택세대구성원 : 아래 표의 세대구성원 전원이 주택을 소유하고 있지 않은 세대의 구성원"),
        item("bonus", "OTHER", "* 가산 적용 : 출산자녀 2인 이상 20% 가산, 출산자녀 1인 10% 가산"),
        item("dup", "OTHER", "A-1블록, A-3블록 중 반드시 한 개 블록만 지정하여 신청하셔야 하며, 두 개 블록에 중복으로 신청하는 경우 모두 무효"),
        item("keep", "OTHER", "신청자격은 입주자 모집공고일부터 입주 시까지 계속 유지하고 있어야 합니다."),
        income,
    ]
    frame = build_judgment(candidate(items))
    assert [n["kind"] for n in frame["notes"]] == ["OTHER", "OTHER", "RULE", "OBLIGATION"]
    [made] = frame["common"]
    assert made["kind"] == "INCOME" and made["inReview"] and "가산" in made["reviewReason"]  # 가산 안내를 못 옮겨 소득은 확인 필요


def test_group_requirement_written_as_a_term_definition_stays_a_question():
    # 「사회초년생 : …」은 사회초년생 대상의 실제 요건이라 안내로 넘기면 그 대상이 요건 없이 지원 가능이 됨
    text = "사회초년생 : 소득이 있는 업무에 종사한 기간이 총 5년 이내인 사람"
    frame = build_judgment(candidate([item("rookie", "OTHER", text, groups=["사회초년생"])], target_groups=["사회초년생"]))
    [made] = {g["name"]: g for g in frame["groups"]}["사회초년생"]["criteria"]
    assert made["kind"] == "ASK" and made["metWhen"] == "YES"


def test_cannot_apply_or_be_selected_sentences_are_limits():
    text = "재당첨 제한 기간 중에 있는 분은 청약할 수 없습니다."
    [made] = build_judgment(candidate([item("rewin", "OTHER", text)]))["common"]
    assert (made["kind"], made["metWhen"], made["inReview"]) == ("ASK", "NO", False)


def test_income_below_percent_keeps_source_value_and_review():
    """「100% 미만」을 「99% 이하」로 바꾸면 99.5%가 떨어집니다. 원문 값과 확인 필요로 둡니다."""
    income = item("e1", "INCOME", "월평균소득 100% 미만", atom("INCOME_BENCHMARK_PERCENT", "LT", 100, benchmark=URBAN))
    frame = build_judgment(candidate([income]))
    made = frame["common"][0]
    assert made["kind"] == "INCOME" and made["percent"] == 100 and made["inReview"] and "미만" in made["reviewReason"]
    assert frame_errors(frame) == []


def test_supply_group_condition_becomes_its_own_group():
    """주택형 하나에만 붙은 조건은 판정 담당 문서대로 그 주택형 대상군으로 나눕니다(「대상군을 단지별로 나눠 주세요」)."""
    age = item("e1", "AGE", "만 19세 이상인 자", atom("AGE_YEARS", "GTE", 19, subject="APPLICANT", unit="YEARS"))
    limited = item("e2", "INCOME", "전용 59㎡ 주택형은 소득 100% 이하", atom("INCOME_BENCHMARK_PERCENT", "LTE", 100, benchmark=URBAN))
    limited["scope"] = {"level": "SUPPLY_GROUP", "supplyGroupIds": ["sg-59A"], "locationIds": [], "targetGroups": [], "description": "59A"}
    base = candidate([age, limited])
    base["supplyGroups"] = [{"id": "sg-59A", "housingTypeCode": "59A", "unitLabel": None}, {"id": "sg-84B", "housingTypeCode": "84B", "unitLabel": None}]
    frame = build_judgment(base)
    assert [c["kind"] for c in frame["common"]] == ["AGE"]  # 공통 조건에는 주택형 한정 조건이 없음
    groups = {g["name"]: [c["kind"] for c in g["criteria"]] for g in frame["groups"]}
    assert groups == {"그 밖의 주택형·단지": [], "59A": ["INCOME"]}
    assert frame_errors(frame) == []
    # 모든 주택형에 한정 조건이 있으면 「그 밖의」 대상군은 만들지 않습니다
    other = item("e3", "INCOME", "전용 84㎡ 주택형은 소득 120% 이하", atom("INCOME_BENCHMARK_PERCENT", "LTE", 120, benchmark=URBAN))
    other["scope"] = {**limited["scope"], "supplyGroupIds": ["sg-84B"], "description": "84B"}
    base["eligibility"].append(other)
    assert [g["name"] for g in build_judgment(base)["groups"]] == ["59A", "84B"]


def test_supply_group_condition_for_one_target_group_is_split_with_that_group():
    youth = item("e1", "AGE", "청년: 만 19~39세", atom("AGE_YEARS", "BETWEEN", [19, 39], subject="APPLICANT", unit="YEARS"), groups=["청년"])
    limited = item("e2", "INCOME", "청년 59A형은 소득 100% 이하", atom("INCOME_BENCHMARK_PERCENT", "LTE", 100, benchmark=URBAN), groups=["청년"])
    limited["scope"] = {"level": "SUPPLY_GROUP", "supplyGroupIds": ["sg-59A"], "locationIds": [], "targetGroups": ["청년"], "description": "59A"}
    base = candidate([youth, limited], target_groups=["청년", "신혼부부"])
    base["supplyGroups"] = [{"id": "sg-59A", "housingTypeCode": "59A", "unitLabel": None}, {"id": "sg-84B", "housingTypeCode": "84B", "unitLabel": None}]
    frame = build_judgment(base)
    groups = {g["name"]: [c["kind"] for c in g["criteria"]] for g in frame["groups"]}
    assert groups["59A 청년"] == ["AGE", "INCOME"] and groups["청년 (그 밖의 주택형·단지)"] == ["AGE"]
    assert "59A 신혼부부" not in groups
    assert frame_errors(frame) == []


def test_not_required_sentence_with_other_requirement_stays_a_question():
    """「거주지역 불문, 만 19세 이상 39세 이하」를 안내로 넘기면 나이 요건이 사라집니다."""
    for text, description in (
        ("거주지역 불문, 만 19세 이상 39세 이하인 자", None),
        ("만 19세 이상 39세 이하인 자", "거주지역은 따지지 않고 만 19~39세면 돼요"),
    ):
        frame = build_judgment(candidate([item("e3", "AGE", text, description=description)]))
        assert [c["kind"] for c in frame["common"]] == ["ASK"] and frame["notes"] == []
    only = build_judgment(candidate([item("e4", "RESIDENCE", "거주지역 불문")]))
    assert only["common"] == [] and [n["kind"] for n in only["notes"]] == ["OTHER"]


def test_held_announcement_date_is_not_the_reference_date():
    """보류한 공고일(API와 다름 등)을 판정 기준일로 쓰면 소득 기준 연도까지 틀립니다. 쓸 날짜가 없으면 틀을 보내지 않습니다."""
    income = item("e1", "INCOME", "월평균소득 100% 이하", atom("INCOME_BENCHMARK_PERCENT", "LTE", 100, benchmark=URBAN))
    held = candidate([income], held=[{"target": "/notice/announcedOn", "codes": ["API_MISMATCH"]}])
    assert build_judgment(held) is None
    issue = candidate([income])
    issue["quality"]["reviewIssues"] = [{"code": "VALUE_NOT_IN_QUOTE", "target": "/notice/announcedOn", "description": "x"}]
    assert build_judgment(issue) is None
    assert build_judgment(candidate([income]))["referenceDate"] == "2026-10-07"


def test_held_notice_target_groups_are_not_used_as_groups():
    age = item("e1", "AGE", "만 19세 이상", atom("AGE_YEARS", "GTE", 19, subject="APPLICANT", unit="YEARS"))
    frame = build_judgment(candidate([age], target_groups=["청년"], held=[{"target": "/notice/targetGroups", "codes": ["X"]}]))
    assert [g["name"] for g in frame["groups"]] == ["전체"]


def test_approved_frame_drops_extraction_review_reasons_but_keeps_its_own():
    """승인한 결과는 조건의 inReview를 내리는 것과 같게, 추출 검수 사유로는 확인 필요를 붙이지 않습니다."""
    age = item("e1", "AGE", "만 19세 이상인 자", atom("AGE_YEARS", "GTE", 19, subject="APPLICANT", unit="YEARS"))
    base = candidate([age])
    base["quality"]["reviewIssues"] = [{"code": "VALUE_NOT_IN_QUOTE", "target": "/eligibility/0/rule", "description": "x"}]
    assert build_judgment(base)["common"][0]["inReview"]
    approved = build_judgment(base, approved=True)["common"][0]
    assert approved["kind"] == "AGE" and not approved["inReview"]
    # 틀 자체의 사유(코드로 정하지 못함)는 승인해도 남습니다
    vague = candidate([item("e2", "OTHER", "그 밖에 공사가 정하는 요건")])
    assert build_judgment(vague, approved=True)["common"][0]["inReview"]


def test_page_numbers_come_from_pdf_page_marks():
    """판정 틀의 page: 근거 위치 앞의 가장 가까운 「--- N쪽 ---」 표시. 첨부 경계를 넘거나 쪽 표시가 없으면 비웁니다."""
    from mujuthaek_extraction.judgment.pages import evidence_pages

    text = "머리말\n--- 1쪽 ---\n공고 개요\n--- 2쪽 왼쪽 ---\n만 19세 이상인 자\n=== 첨부 b.pdf ===\n표지\n--- 1쪽 ---\n소득 기준"
    at = {name: text.index(name) for name in ("머리말", "공고 개요", "만 19세", "표지", "소득 기준")}
    located = {
        "evidence": [{"id": f"ev-{k}", "sourceId": "pdf-text", "locator": {"kind": "TEXT_RANGE", "start": v, "end": v + 2}} for k, v in at.items()]
        + [{"id": "ev-other", "sourceId": "text", "locator": {"kind": "TEXT_RANGE", "start": 0, "end": 2}}]
    }
    pages = evidence_pages(located, {"pdf-text": text, "text": "수집 글자"})
    assert pages == {"ev-공고 개요": 1, "ev-만 19세": 2, "ev-소득 기준": 1}

    age = item("e1", "AGE", "만 19세 이상인 자", atom("AGE_YEARS", "GTE", 19, subject="APPLICANT", unit="YEARS"))
    frame = build_judgment(candidate([age]), pages={"ev-1": 2})
    assert frame["common"][0]["page"] == 2
    assert build_judgment(candidate([age]))["common"][0]["page"] is None
