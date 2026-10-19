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
