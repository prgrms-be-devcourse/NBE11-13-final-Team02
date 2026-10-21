"""자격 조건 AI 재확인: 고친 결과가 검증에서 더 나을 때만 채택합니다. 녹화 응답만 씁니다."""

import copy
import json
from datetime import UTC, datetime

from helpers import ai_part, example, source_for, to_ai

from mujuthaek_extraction.assemble.assembler import assemble
from mujuthaek_extraction.model import RecordedModel
from mujuthaek_extraction.recheck import recheck_problems
from mujuthaek_extraction.rules.housing_notice import RULES
from mujuthaek_extraction.verify import SourceBundle, verify_extraction


def setup():
    data = example("housing")
    source = source_for("housing", data)
    part = copy.deepcopy(ai_part(data))  # ai_part는 예시 데이터와 목록을 공유합니다
    part["quotes"].append({"ref": "fake", "documentId": "text1", "text": "원문에 없는 무주택 문장입니다"})
    part["eligibility"][1]["quoteRefs"].append("fake")  # 근거 하나는 맞고 하나는 원문에 없음 → 조건은 남고 이슈만 붙음

    def evaluate(parts):
        candidate = assemble(parts, source).candidate
        return verify_extraction(candidate, SourceBundle.from_material(source.material), None, clock=lambda: datetime(2026, 10, 15, tzinfo=UTC))

    return data, source, [part], evaluate


def answer(item, quotes):
    return json.dumps({"quotes": quotes, "eligibility": [item], "coverage": {"eligibility": {"status": "EXTRACTED", "reason": None}}}, ensure_ascii=False)


def test_fixed_condition_is_accepted_when_verification_improves():
    data, source, parts, evaluate = setup()
    assert any(i["target"] == "/eligibility/1" for i in evaluate(parts).candidate["quality"]["reviewIssues"])
    fixed = json.loads(json.dumps(to_ai(data["eligibility"][1])).replace('"e1"', '"q1"'))  # 조건식 안의 근거까지 새 인용을 가리킴
    model = RecordedModel({"recheck": [answer(fixed, [{"ref": "q1", "documentId": "text1", "text": data["evidence"][0]["quote"]}])]})

    result = recheck_problems(RULES, source.material, model, parts, evaluate)
    assert result.accepted and result.rechecked == ["eligibility/" + data["eligibility"][1]["id"]]
    assert result.report.passed
    # 재확인 요청에는 문제 설명과 원래 인용문이 들어갑니다
    request = model.requests[0]
    assert "EVIDENCE_QUOTE_NOT_FOUND" in request.instructions and "원문에 없는 무주택 문장입니다" in request.instructions


def test_recheck_that_does_not_help_is_discarded():
    data, source, parts, evaluate = setup()
    unchanged = copy.deepcopy(parts[0]["eligibility"][1])
    quotes = [q for q in parts[0]["quotes"] if q["ref"] in unchanged["quoteRefs"]]
    model = RecordedModel({"recheck": [answer(unchanged, quotes)]})

    result = recheck_problems(RULES, source.material, model, parts, evaluate)
    assert not result.accepted and result.parts is parts and not result.report.passed


def test_no_condition_problem_means_no_call():
    data = example("housing")
    source = source_for("housing", data)
    model = RecordedModel({})

    def evaluate(parts):
        candidate = assemble(parts, source).candidate
        return verify_extraction(candidate, SourceBundle.from_material(source.material), None, clock=lambda: datetime(2026, 10, 15, tzinfo=UTC))

    result = recheck_problems(RULES, source.material, model, [ai_part(data)], evaluate)
    assert result.rechecked == [] and model.requests == []


def test_recheck_answer_still_breaking_the_schema_is_not_merged():
    # 검토 지적: 재확인 호출이 형식 위반 응답을 (수정 호출 뒤에도) 돌려주면 그대로 섞일 수 있었음
    data, source, parts, evaluate = setup()
    broken = copy.deepcopy(parts[0]["eligibility"][1])
    broken["category"] = "NOT_A_CATEGORY"
    quotes = [q for q in parts[0]["quotes"] if q["ref"] in broken["quoteRefs"]]
    model = RecordedModel({"recheck": [answer(broken, quotes), answer(broken, quotes)]})
    result = recheck_problems(RULES, source.material, model, parts, evaluate)
    assert not result.accepted and result.parts is parts


def partial_scoring_setup():
    # 선정 기준 하나를 빠뜨리고 AI가 PARTIAL로 적은 응답
    data = example("housing")
    source = source_for("housing", data)
    part = copy.deepcopy(ai_part(data))
    missing = part["selectionRules"].pop()
    part["coverage"]["selectionRules"] = {"status": "PARTIAL", "reason": "선정 기준 일부를 옮기지 못했습니다."}

    def evaluate(parts):
        candidate = assemble(parts, source).candidate
        return verify_extraction(candidate, SourceBundle.from_material(source.material), None, clock=lambda: datetime(2026, 10, 15, tzinfo=UTC))

    quotes = [q for q in part["quotes"] if q["ref"] in json.dumps(missing)]
    return source, [part], evaluate, missing, quotes


def scoring_answer(items, quotes):
    return json.dumps({"quotes": quotes, "selectionRules": items, "coverage": {"selectionRules": {"status": "EXTRACTED", "reason": None}}}, ensure_ascii=False)


def test_missing_items_of_a_partial_section_are_added_back():
    source, parts, evaluate, missing, quotes = partial_scoring_setup()
    assert not evaluate(parts).passed
    model = RecordedModel({"recheck": [scoring_answer([missing], quotes)]})
    result = recheck_problems(RULES, source.material, model, parts, evaluate)
    assert result.accepted and "selectionRules" in result.rechecked
    assert result.report.passed
    assert missing["id"] in {r["id"] for r in result.report.candidate["selectionRules"]}
    # 요청에는 이미 있는 항목 id만 보냅니다(목록 전체를 다시 보내지 않음)
    assert "existingIds" in model.requests[0].instructions


def test_partial_section_is_not_cleared_by_the_ai_just_saying_it_is_complete():
    source, parts, evaluate, missing, quotes = partial_scoring_setup()
    model = RecordedModel({"recheck": [scoring_answer([], [])]})
    result = recheck_problems(RULES, source.material, model, parts, evaluate)
    assert not result.report.passed
    assert result.report.candidate["quality"]["coverage"]["selectionRules"]["status"] == "PARTIAL"


def test_oversized_item_is_not_sent_and_request_is_split_into_lines(monkeypatch):
    # 실측(2026-10-18 LH 익산 행복주택): 호실별 가격 묶음 8개를 한 줄로 이어 45만 자 요청이 되어 모델이 읽지 못함
    import mujuthaek_extraction.recheck as recheck

    data, source, parts, evaluate = setup()
    unchanged = copy.deepcopy(parts[0]["eligibility"][1])
    quotes = [q for q in parts[0]["quotes"] if q["ref"] in unchanged["quoteRefs"]]
    model = RecordedModel({"recheck": [answer(unchanged, quotes)]})
    result = recheck_problems(RULES, source.material, model, parts, evaluate)
    assert result.rechecked and "\n" in model.requests[0].instructions.split("# 다시 확인할 항목\n", 1)[1]  # 항목을 줄 단위로

    monkeypatch.setattr(recheck, "MAX_TARGET_CHARS", 10)  # 어떤 항목도 상한 안에 들지 않으면 재확인을 부르지 않습니다
    model = RecordedModel({})
    result = recheck_problems(RULES, source.material, model, parts, evaluate)
    assert result.rechecked == [] and model.requests == []


def test_count_rule_error_at_its_real_location_points_to_the_owner_item():
    # 검사기는 수량 묶음 오류를 수량의 실제 위치로 알립니다 (/counts/<id> 형식은 2026-10-20에 없어짐)
    from mujuthaek_extraction.recheck import _item_key

    data = example("housing")
    group = data["supplyGroups"][0]
    assert _item_key(data, "/notice/counts/0") == ("notice", None)
    assert _item_key(data, "/supplyGroups/0/counts/0") == ("supplyGroups", group["id"])


def age_setup():
    # 나이 조건(AGE_YEARS GTE 19 / LTE 39)에 원문에 없는 인용 하나가 붙어 근거 이슈만 난 응답
    data = example("housing")
    source = source_for("housing", data)
    part = copy.deepcopy(ai_part(data))
    part["quotes"].append({"ref": "fake", "documentId": "text1", "text": "원문에 없는 나이 문장입니다"})
    part["eligibility"][0]["quoteRefs"].append("fake")

    def evaluate(parts):
        candidate = assemble(parts, source).candidate
        return verify_extraction(candidate, SourceBundle.from_material(source.material), None, clock=lambda: datetime(2026, 10, 15, tzinfo=UTC))

    original = to_ai(data["eligibility"][0])
    quotes = [q for q in ai_part(data)["quotes"] if q["ref"] in original["quoteRefs"]]
    return data, source, [part], evaluate, quotes


def test_quote_fix_that_flips_the_operator_is_not_accepted():
    # 실측(2026-10-20): 인용을 고치며 GTE 19를 LTE 19로 뒤집은 응답이 이슈 1→0으로 채택되어 통과됨
    data, source, parts, evaluate, quotes = age_setup()
    first = evaluate(parts)
    assert {i["code"] for i in first.candidate["quality"]["reviewIssues"] if i["target"] == "/eligibility/0"} == {"EVIDENCE_QUOTE_NOT_FOUND"}
    flipped = to_ai(data["eligibility"][0])
    flipped["rule"]["expression"]["children"][0]["operator"] = "LTE"
    model = RecordedModel({"recheck": [answer(flipped, quotes)]})

    result = recheck_problems(RULES, source.material, model, parts, evaluate)
    assert not result.accepted and result.parts is parts and not result.report.passed
    operators = [c["operator"] for c in result.report.candidate["eligibility"][0]["rule"]["expression"]["children"]]
    assert operators == ["GTE", "LTE"]  # 뒤집힌 뜻이 결과에 들어가지 않음


def test_quote_fix_keeping_the_meaning_is_accepted():
    data, source, parts, evaluate, quotes = age_setup()
    fixed = to_ai(data["eligibility"][0])
    fixed["description"] = "신청일 현재 만 19세 이상 39세 이하입니다."  # 설명 글만 바뀌는 것은 뜻이 바뀐 것이 아닙니다
    model = RecordedModel({"recheck": [answer(fixed, quotes)]})

    result = recheck_problems(RULES, source.material, model, parts, evaluate)
    assert result.accepted and result.report.passed


def test_meaning_change_is_allowed_when_a_value_problem_was_sent():
    # 값·조건 문제를 보낸 항목은 뜻을 고치는 것이 목적이므로 뜻 비교로 막지 않습니다(채택은 같은 검증 기준)
    from mujuthaek_extraction.recheck import _keep_meaning

    data, source, parts, evaluate, quotes = age_setup()
    flipped = to_ai(data["eligibility"][0])
    flipped["rule"]["expression"]["children"][0]["operator"] = "LTE"
    reply = {"eligibility": [flipped]}
    value_target = [{"section": "eligibility", "id": "age", "problems": ["VALUE_NOT_IN_QUOTE /eligibility/0: 값이 인용에 없습니다"]}]
    quote_target = [{"section": "eligibility", "id": "age", "problems": ["EVIDENCE_QUOTE_NOT_FOUND /eligibility/0: 인용문을 찾지 못했습니다"]}]
    assert _keep_meaning(parts, reply, value_target) is reply
    assert _keep_meaning(parts, reply, quote_target)["eligibility"] == []
    other_target = [{"section": "eligibility", "id": "income", "problems": ["VALUE_NOT_IN_QUOTE /eligibility/2: 값이 인용에 없습니다"]}]
    assert _keep_meaning(parts, reply, other_target)["eligibility"] == []  # 보내지 않은 항목의 뜻도 바꾸지 않습니다


def test_recheck_that_fixes_items_but_breaks_another_is_not_accepted():
    # 개수로만 비교하면 이슈 2개를 없애고 재확인하지 않은 항목에 1개를 새로 만든 응답(2→1)이 "좋아짐"으로 채택됩니다
    data, source, parts, evaluate, quotes = age_setup()
    parts[0]["eligibility"][1]["quoteRefs"].append("fake")  # 무주택 조건에도 같은 근거 이슈
    first = evaluate(parts)
    assert {i["target"] for i in first.candidate["quality"]["reviewIssues"]} == {"/eligibility/0", "/eligibility/1"}
    fixed = [to_ai(data["eligibility"][0]), to_ai(data["eligibility"][1])]
    broken_income = to_ai(data["eligibility"][2])
    broken_income["quoteRefs"].append("bad")  # 재확인하지 않은 소득 조건에 새 근거 이슈
    reply_quotes = [*ai_part(data)["quotes"], {"ref": "bad", "documentId": "text1", "text": "원문에 없는 소득 문장입니다"}]
    reply = json.dumps(
        {"quotes": reply_quotes, "eligibility": [*fixed, broken_income], "coverage": {"eligibility": {"status": "EXTRACTED", "reason": None}}},
        ensure_ascii=False,
    )
    model = RecordedModel({"recheck": [reply]})

    # 그대로 합쳤다면 이슈는 2→1로 줄지만 소득 조건에 처음 없던 문제가 생깁니다(개수 비교만 하면 채택됨)
    merged = [{**parts[0], "eligibility": [*fixed, broken_income], "quotes": reply_quotes}]
    assert {i["target"] for i in evaluate(merged).candidate["quality"]["reviewIssues"]} == {"/eligibility/2"}
    # 보낸 항목만 받으므로(2026-10-21) 보내지 않은 소득 조건은 응답에 섞여 와도 바뀌지 않고, 고친 두 항목만 채택됩니다
    result = recheck_problems(RULES, source.material, model, parts, evaluate)
    assert result.accepted and result.report.candidate["quality"]["reviewIssues"] == []
    assert result.parts[0]["eligibility"][2] == parts[0]["eligibility"][2]


def test_recheck_changes_only_problem_fields_of_notice():
    from mujuthaek_extraction.recheck import merge_recheck, sent_scope

    parts = [{"notice": {"title": "원래 제목", "announcedOn": "2026-10-07", "supplyType": "행복주택", "quoteRefs": ["q1"]}, "quotes": []}]
    answer = {"notice": {"title": "다른 제목", "announcedOn": "2026-10-08", "supplyType": "국민임대", "quoteRefs": ["q2"]}}
    targets = [{"section": "notice", "id": None, "problems": ["API_MISMATCH /notice/announcedOn: 공고일이 다릅니다"]}]
    merged = merge_recheck(parts, answer, (), sent_scope(targets))[0]["notice"]
    assert merged["announcedOn"] == "2026-10-08" and merged["title"] == "원래 제목" and merged["supplyType"] == "행복주택"
    # 칸을 정할 수 없는 문제(공고 전체)면 전부 받습니다
    whole = [{"section": "notice", "id": None, "problems": ["NOTICE_NOT_EXTRACTED /notice: 없음"]}]
    assert merge_recheck(parts, answer, (), sent_scope(whole))[0]["notice"]["title"] == "다른 제목"
