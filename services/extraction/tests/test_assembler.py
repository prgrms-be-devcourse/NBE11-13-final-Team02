"""AI 출력 + 신뢰 문맥 → extracted-notice v1 후보. 계약 예시를 AI 출력으로 되돌린 fixture를 씁니다. AI 호출 없음."""

import copy
import dataclasses
import json

import pytest
from helpers import SECTIONS, ai_part, example, source_for

from mujuthaek_extraction.assemble import ai_output
from mujuthaek_extraction.assemble.assembler import AssemblyInput, assemble


def rename_evidence(original, candidate):
    """예시의 근거 ID(e1…)를 결합기가 만든 ID(ev-1…)로 바꿔 비교합니다.

    결합기는 인용문만큼의 범위를 계산하고, 예시는 끝 줄바꿈까지 포함한 범위를 쓰기도 합니다. 같은 문서·같은 시작 위치면 같은 근거입니다.
    """
    ours = {(e["sourceId"], e["locator"]["start"]): e for e in candidate["evidence"]}
    mapping = {}
    for e in original["evidence"]:
        mine = ours[(e["sourceId"], e["locator"]["start"])]
        assert mine["locator"]["end"] <= e["locator"]["end"] and mine["quote"] == e["quote"]
        mapping[e["id"]] = mine["id"]

    def walk(node):
        if isinstance(node, dict):
            return {k: ([mapping[x] for x in v] if k == "evidenceIds" else walk(v)) for k, v in node.items()}
        if isinstance(node, list):
            return [walk(x) for x in node]
        return node

    return walk(original)


@pytest.mark.parametrize("name", ["housing", "business"])
def test_round_trip_reproduces_contract_example(name):
    data = example(name)
    result = assemble([ai_part(data)], source_for(name, data))

    assert result.contract_errors == [] and result.issues == []
    candidate = result.candidate
    expected = rename_evidence(data, candidate)
    for key in (*SECTIONS, "input", "sourceReferences", "documents"):
        assert candidate[key] == expected[key], key
    assert candidate["quality"]["coverage"] == data["quality"]["coverage"]
    assert candidate["quality"]["verification"]["status"] == "NOT_RUN"
    assert "fieldAssessments" not in candidate["quality"]


def test_section_calls_are_merged_like_one_call():
    data = example("housing")
    source = source_for("housing", data)
    whole = assemble([ai_part(data)], source).candidate
    parts = [
        ai_part(data, ("notice", "schedules", "guidance")),
        ai_part(data, ("locations", "supplyGroups")),
        ai_part(data, ("eligibility", "selectionRules", "requiredDocuments")),
    ]
    split = assemble(parts, source)
    assert split.contract_errors == [] and split.candidate == whole


def test_ai_cannot_supply_trusted_or_computed_fields():
    schema_text = json.dumps(ai_output.schema(), ensure_ascii=False)
    for name in ("evidenceIds", "amountKrw", "regionCode", "sourceReference", "verification", "locator"):
        assert name not in schema_text
    data = example("housing")
    part = ai_part(data)
    part["input"] = data["input"]  # 신뢰 문맥을 AI가 넣으려 하면 응답 전체를 쓰지 않습니다
    result = assemble([part], source_for("housing", data))
    assert result.candidate is None and result.issues[0]["code"] == "AI_OUTPUT_INVALID"


def test_quote_not_in_source_drops_only_that_record_and_marks_partial():
    data = example("housing")
    part = ai_part(data)
    part["quotes"].append({"ref": "fake", "documentId": "text1", "text": "원문에 없는 보증금 문장입니다"})
    part["supplyGroups"][0]["prices"][0]["quoteRefs"] = ["fake"]
    rent_id = part["supplyGroups"][0]["prices"][1]["id"]
    result = assemble([part], source_for("housing", data))

    prices = result.candidate["supplyGroups"][0]["prices"]
    assert [p["id"] for p in prices] == [rent_id]
    assert result.candidate["quality"]["coverage"]["supplyGroups"]["status"] == "PARTIAL"
    issues = {i["code"]: i for i in result.issues}
    assert issues["EVIDENCE_QUOTE_NOT_FOUND"]["target"] == issues["ITEM_DROPPED_NO_EVIDENCE"]["target"] == "/supplyGroups/0/prices"
    # 빠진 항목은 목록을 가리키므로, 어떤 항목·어떤 인용문인지 설명에 남깁니다
    dropped = data["supplyGroups"][0]["prices"][0]["id"]
    not_found = issues["EVIDENCE_QUOTE_NOT_FOUND"]["description"]
    assert dropped in not_found and "「원문에 없는 보증금 문장입니다」" in not_found
    assert issues["ITEM_DROPPED_NO_EVIDENCE"]["description"].endswith(f"1개를 뺐습니다: {dropped}")


def test_pointers_after_dropped_record_are_shifted():
    data = example("housing")
    part = ai_part(data)
    part["quotes"].append({"ref": "fake", "documentId": "text1", "text": "원문에 없는 보증금 문장입니다"})
    prices = part["supplyGroups"][0]["prices"]
    prices[0]["quoteRefs"] = ["fake"]
    prices[1]["sourceAmount"] = "공고문 참조"  # 계산 불가 → 뺀 뒤 위치(prices/0)에 누락 사유
    prices[1].update(taxTreatment="UNKNOWN", vatRate=None)
    part["missingFields"] = [{"target": "/supplyGroups/0/prices/1/vatRate", "reason": "NOT_IN_SOURCE", "description": "부가세율이 없습니다."}]
    result = assemble([part], source_for("housing", data))

    missing = {m["target"]: m["reason"] for m in result.candidate["quality"]["missingFields"]}
    assert missing == {"/supplyGroups/0/prices/0/vatRate": "NOT_IN_SOURCE", "/supplyGroups/0/prices/0/amountKrw": "NEEDS_REVIEW"}
    assert result.candidate["supplyGroups"][0]["prices"][0]["amountKrw"] is None


def test_unsupported_condition_drops_whole_eligibility_item_not_just_the_condition():
    data = example("housing")
    part = ai_part(data)
    part["quotes"].append({"ref": "fake", "documentId": "text1", "text": "원문에 없는 나이 조건입니다"})
    child = part["eligibility"][0]["rule"]["expression"]["children"][0]
    child["quoteRefs"] = ["fake"]
    before = len(part["eligibility"])
    result = assemble([part], source_for("housing", data))
    assert len(result.candidate["eligibility"]) == before - 1  # 조건 하나만 빼면 ALL의 의미가 바뀌므로 항목 전체를 뺍니다
    assert result.candidate["quality"]["coverage"]["eligibility"]["status"] == "PARTIAL"


def test_lease_term_without_evidence_becomes_null_with_reason():
    data = example("housing")
    part = ai_part(data)
    part["quotes"].append({"ref": "fake", "documentId": "text1", "text": "원문에 없는 임대기간 문장"})
    part["supplyGroups"][0]["leaseTerm"]["quoteRefs"] = ["fake"]
    result = assemble([part], source_for("housing", data))
    assert result.candidate["supplyGroups"][0]["leaseTerm"] is None
    missing = {m["target"]: m["reason"] for m in result.candidate["quality"]["missingFields"]}
    assert missing["/supplyGroups/0/leaseTerm"] == "NEEDS_REVIEW"


def test_notice_without_evidence_gives_no_candidate():
    data = example("housing")
    part = ai_part(data)
    part["quotes"].append({"ref": "fake", "documentId": "text1", "text": "원문에 없는 공고 제목"})
    part["notice"]["quoteRefs"] = ["fake"]
    result = assemble([part], source_for("housing", data))
    assert result.candidate is None and any(i["code"] == "ITEM_WITHOUT_EVIDENCE" for i in result.issues)


def test_invalid_item_is_dropped_but_section_survives():
    # 항목 격리(2026-10-16): 형식이 틀린 항목만 빼고 섹션은 살립니다
    data = example("housing")
    part = copy.deepcopy(ai_part(data))
    part["schedules"].append({**copy.deepcopy(part["schedules"][0]), "id": "broken", "applicationMode": "SOMETIMES"})
    result = assemble([part], source_for("housing", data))
    assert [s["id"] for s in result.candidate["schedules"]] == [data["schedules"][0]["id"]]
    assert result.candidate["quality"]["coverage"]["schedules"]["status"] == "PARTIAL"
    assert ("AI_OUTPUT_ITEM_DROPPED", "/schedules") in {(i["code"], i["target"]) for i in result.issues}


def test_invalid_exception_is_dropped_and_condition_marked():
    data = example("housing")
    part = copy.deepcopy(ai_part(data))
    exception = part["eligibility"][2]["exceptions"][0]
    exception["effectExpression"] = None  # 실측 AI 실수: REPLACE인데 효과 조건식이 없음
    result = assemble([part], source_for("housing", data))
    condition = result.candidate["eligibility"][2]
    assert condition["exceptions"] == [] and condition["exceptionsStatus"] == "NOT_REVIEWED"
    assert ("AI_OUTPUT_ITEM_DROPPED", "/eligibility/2") in {(i["code"], i["target"]) for i in result.issues}
    assert len(result.candidate["eligibility"]) == len(data["eligibility"])


def test_unrecoverable_section_still_fails_alone():
    data = example("housing")
    part = ai_part(data)
    part["schedules"] = "일정 없음"  # 목록이 아니면 살릴 항목이 없습니다
    result = assemble([part], source_for("housing", data))
    assert result.candidate["schedules"] == []
    assert result.candidate["quality"]["coverage"]["schedules"]["status"] == "FAILED"
    assert result.candidate["supplyGroups"]


def test_missing_section_is_not_attempted():
    data = example("housing")
    result = assemble([ai_part(data, ("notice", "locations", "supplyGroups"))], source_for("housing", data))
    assert result.candidate["quality"]["coverage"]["schedules"]["status"] == "NOT_ATTEMPTED"


def test_ambiguous_and_nearest_quotes():
    data = example("housing")
    source = source_for("housing", data)
    text = source.material.text
    repeated = "입니다."
    assert text.count(repeated) > 1
    part = ai_part(data)
    part["quotes"].append({"ref": "rep", "documentId": "text1", "text": repeated})
    notice = copy.deepcopy(part["notice"])
    part["notice"]["quoteRefs"] = ["rep"]  # 단독 → 위치를 못 정함, 근거가 없어 후보 없음
    assert assemble([part], source).candidate is None

    part["notice"] = notice
    part["notice"]["quoteRefs"] = [*notice["quoteRefs"], "rep"]
    result = assemble([part], source)
    # 기준 인용(원문 전체)이 모든 후보를 덮어 거리가 같으므로 고르지 않고 검수로 남깁니다. 다른 근거로 항목은 유지됩니다.
    assert result.candidate is not None
    assert ("EVIDENCE_AMBIGUOUS", "/notice") in {(i["code"], i["target"]) for i in result.issues}
    assert ("EVIDENCE_QUOTE_TOO_SHORT", "/notice") not in {(i["code"], i["target"]) for i in result.issues}


def test_region_code_is_computed_from_original_region_and_original_is_kept():
    data = example("housing")
    result = assemble([ai_part(data)], source_for("housing", data))
    location = result.candidate["locations"][0]
    assert location["region"] == "서울특별시 종로구" and location["regionCode"] == "1111000000"

    no_table = source_for("housing", data)
    no_table = AssemblyInput(no_table.context, no_table.material, regions=None)
    result = assemble([ai_part(data)], no_table)
    assert result.candidate["locations"][0]["regionCode"] is None
    assert {m["target"] for m in result.candidate["quality"]["missingFields"]} == {"/locations/0/regionCode"}


def test_missing_field_that_has_value_becomes_review_issue():
    data = example("housing")
    part = ai_part(data)
    part["missingFields"] = [{"target": "/supplyGroups/0/prices", "reason": "NEEDS_REVIEW", "description": "조건별 호수 배분이 없습니다."}]
    result = assemble([part], source_for("housing", data))

    assert result.candidate["quality"]["missingFields"] == []
    assert [(i["code"], i["target"]) for i in result.issues] == [("MISSING_FIELD_HAS_VALUE", "/supplyGroups/0/prices")]
    assert result.contract_errors == []


def test_broken_count_grouping_is_ungrouped_but_values_kept():
    data = example("housing")
    part = copy.deepcopy(ai_part(data))
    count = part["notice"]["counts"][0]
    count.update(aggregationRole="TOTAL", aggregationComplete=True)  # 구성 수량 없이 '합계 완결' (실측 AI 실수)
    part["supplyGroups"][0]["counts"] = [{**copy.deepcopy(count), "id": "orphan", "aggregationRole": "COMPONENT", "parentCountId": "missing"}]
    result = assemble([part], source_for("housing", data))
    assert result.contract_errors == []
    fixed = result.candidate["notice"]["counts"][0]
    assert fixed["aggregationComplete"] is False and fixed["value"] == count["value"]
    orphan = result.candidate["supplyGroups"][0]["counts"][0]
    assert orphan["parentCountId"] is None and orphan["aggregationRole"] == "STANDALONE"
    assert any("묶음을 풀었습니다" in n for n in result.notes)


def test_missing_field_pointing_nowhere_is_dropped():
    data = example("housing")
    part = copy.deepcopy(ai_part(data))
    part["missingFields"] = [{"target": "/supplyGroups/7/prices", "reason": "NOT_IN_SOURCE", "description": "없는 공급"}]
    result = assemble([part], source_for("housing", data))
    assert result.contract_errors == [] and result.candidate["quality"]["missingFields"] == []


def test_wrong_quote_ref_falls_back_to_item_original_text():
    # 인용 참조가 원문에 없는 문장을 가리켜도, 항목의 originalText가 원문 한 곳에 있으면 그곳을 근거로 씁니다
    data = example("housing")
    part = ai_part(data)
    part["quotes"].append({"ref": "wrong", "documentId": "text1", "text": "다른 계층의 원문에 없는 문장입니다"})
    part["eligibility"][2]["quoteRefs"] = ["wrong"]
    result = assemble([part], source_for("housing", data))

    assert "income" in [e["id"] for e in result.candidate["eligibility"]]
    assert "EVIDENCE_QUOTE_NOT_FOUND" not in {i["code"] for i in result.issues}
    assert any("originalText" in n for n in result.notes)


def test_previous_year_income_benchmark_gets_year_from_announcement():
    # "전년도 도시근로자 …" 기준은 공고일 연도 - 1로 채웁니다 (청년 매입임대 실측)
    data = example("housing")
    part = ai_part(data)
    atoms = [n for e in part["eligibility"] for n in [e["rule"].get("expression")] if n and n.get("attribute") == "INCOME_BENCHMARK_PERCENT"]
    assert atoms
    atoms[0]["benchmark"].update(standardName="전년도 도시근로자 가구당 월평균소득", referenceYear=None, referenceVersion=None)
    result = assemble([part], source_for("housing", data))
    year = int(result.candidate["notice"]["announcedOn"][:4]) - 1
    found = [
        e["rule"]["expression"]["benchmark"]["referenceYear"]
        for e in result.candidate["eligibility"]
        if (e["rule"].get("expression") or {}).get("attribute") == "INCOME_BENCHMARK_PERCENT"
    ]
    assert found == [year]


def test_filled_benchmark_year_resolves_ai_missing_report():
    # AI가 연도를 누락으로 보고하고 결합기가 채우면 모순(MISSING_FIELD_HAS_VALUE)으로 검수에 보내지 않습니다 (일괄 실측 8건)
    data = example("housing")
    part = ai_part(data)
    index = next(i for i, e in enumerate(part["eligibility"]) if (e["rule"].get("expression") or {}).get("attribute") == "INCOME_BENCHMARK_PERCENT")
    part["eligibility"][index]["rule"]["expression"]["benchmark"].update(
        standardName="전년도 도시근로자 가구당 월평균소득", referenceYear=None, referenceVersion=None
    )
    target = f"/eligibility/{index}/rule/expression/benchmark/referenceYear"
    part.setdefault("missingFields", []).append({"target": target, "reason": "NOT_IN_SOURCE", "description": "연도가 없습니다."})
    result = assemble([part], source_for("housing", data))
    assert "MISSING_FIELD_HAS_VALUE" not in {i["code"] for i in result.issues}
    assert target not in {m["target"] for m in result.candidate["quality"]["missingFields"]}


def test_missing_field_records_follow_items_after_bad_ones_are_dropped():
    # 검토 지적: 형식이 틀린 항목을 빼도 누락 기록 위치를 그대로 두어, 기록이 버려지거나 다른 항목을 가리켰음
    from mujuthaek_extraction.assemble.assembler import _shift_missing

    part = {
        "missingFields": [
            {"target": "/eligibility/0/rule", "reason": "NOT_IN_SOURCE"},  # 빼낸 항목 → 지움
            {"target": "/eligibility/2/referenceTime", "reason": "NOT_IN_SOURCE"},  # 2 → 1
            {"target": "/eligibility/12", "reason": "NOT_IN_SOURCE"},  # 12 → 11 (자릿수 다른 번호)
            {"target": "/schedules/0/endsAt", "reason": "NOT_IN_SOURCE"},  # 다른 섹션은 그대로
        ]
    }
    _shift_missing(part, "eligibility", {0})
    assert [m["target"] for m in part["missingFields"]] == ["/eligibility/1/referenceTime", "/eligibility/11", "/schedules/0/endsAt"]


def test_duplicate_ids_are_renamed_and_one_day_fixed_schedule_gets_its_end():
    # 실측(OpenAI dcco-24788): 가점표 행마다 같은 id → DUPLICATE_ID로 7행이 빠짐, 공고일을 FIXED + endsAt 없음으로 적어 형식 오류로 빠짐
    data = example("housing")
    part = copy.deepcopy(ai_part(data))
    first = part["schedules"][0]
    part["schedules"].append({**copy.deepcopy(first), "endStatus": "FIXED", "endsAt": None})
    result = assemble([part], source_for("housing", data))
    schedules = result.candidate["schedules"]
    assert [s["id"] for s in schedules] == [first["id"], f"{first['id']}.2"]
    assert schedules[1]["endsAt"] == schedules[1]["startsAt"]
    assert ("AI_OUTPUT_ID_RENAMED", "/schedules") in {(i["code"], i["target"]) for i in result.issues}


def test_strict_mode_fillers_are_cleared_instead_of_dropping_rows():
    # 실측(OpenAI LH 대전 영구임대): 구조화된 가점 조건의 사유를 「""」로, 추첨 항목에 순서 번호를 적어 가점표 12행이 형식 오류로 빠짐
    data = example("housing")
    part = copy.deepcopy(ai_part(data))
    rules = part["selectionRules"]
    structured = next(r for r in rules if isinstance(r.get("rule"), dict) and r["rule"].get("status") == "STRUCTURED")
    structured["rule"]["reason"] = ""
    other = next(r for r in rules if r.get("kind") != "TIE_BREAK")
    other["order"] = 5
    result = assemble([part], source_for("housing", data))
    kept = {r["id"]: r for r in result.candidate["selectionRules"]}
    assert structured["id"] in kept and kept[structured["id"]]["rule"]["reason"] is None
    assert other["id"] in kept and kept[other["id"]]["order"] is None
    assert "AI_OUTPUT_ITEM_DROPPED" not in {i["code"] for i in result.issues}


def test_bare_host_link_gets_https():
    from mujuthaek_extraction.assemble.slips import fix_format_slips

    part = fix_format_slips({"guidance": [{"url": "www.lh.or.kr"}, {"url": "https://apply.lh.or.kr"}, {"url": "1600-1004"}]})
    assert [g["url"] for g in part["guidance"]] == ["https://www.lh.or.kr", "https://apply.lh.or.kr", "1600-1004"]


def test_bare_application_link_and_empty_present_exceptions_are_fixed():
    # 실측(OpenAI 청약홈 에코델타): 신청 주소 「www.applyhome.co.kr」, 예외 PRESENT + [] 때문에 일정·자격을 한 번씩 다시 요청
    from mujuthaek_extraction.assemble.slips import fix_format_slips

    part = fix_format_slips({"schedules": [{"applicationUrl": "www.applyhome.co.kr"}], "eligibility": [{"exceptionsStatus": "PRESENT", "exceptions": []}]})
    assert part["schedules"][0]["applicationUrl"] == "https://www.applyhome.co.kr"
    assert part["eligibility"][0]["exceptionsStatus"] == "NOT_REVIEWED"


def test_document_name_repeated_in_two_tables_takes_the_first_place():
    # 실측(청약홈 에코델타): 서류표가 원문에 두 번(글자 덩어리·표) 나와 첫 서류 두 개가 위치를 못 정해 빠짐
    data = example("housing")
    source = source_for("housing", data)
    text = source.material.text
    name = next(d["name"] for d in data["requiredDocuments"] if text.count(d["name"]) >= 1)
    doubled = dataclasses.replace(source.material, text=text + "\n" + name + " 서류표 되풀이\n")
    part = copy.deepcopy(ai_part(data))
    for d in part["requiredDocuments"]:
        if d["name"] == name:
            part["quotes"].append({"ref": "q-dup", "documentId": source.material.text_document_id, "text": name})
            d["quoteRefs"] = ["q-dup"]
    result = assemble([part], dataclasses.replace(source, material=doubled))
    assert name in {d["name"] for d in result.candidate["requiredDocuments"]}
    assert "EVIDENCE_AMBIGUOUS" not in {i["code"] for i in result.issues if i["target"].startswith("/requiredDocuments")}


def test_schedule_quoted_by_a_bare_date_repeated_in_the_source_takes_the_first_place():
    # 실측(LH 다자녀 전세임대): 공고문 끝의 「2026. 03. 24.」만 인용한 공고일 일정이 같은 날짜가 여러 곳에 있어 통째로 빠짐
    data = example("housing")
    source = source_for("housing", data)
    part = copy.deepcopy(ai_part(data))
    schedule = part["schedules"][0]
    year, month, day = schedule["startsAt"]["value"][:10].split("-")
    bare = f"{year}. {month}. {day}."
    doubled = dataclasses.replace(source.material, text=source.material.text + f"\n{bare}\n접수 안내\n{bare}\n")
    part["quotes"].append({"ref": "q-date", "documentId": source.material.text_document_id, "text": bare})
    schedule["quoteRefs"] = ["q-date"]
    result = assemble([part], dataclasses.replace(source, material=doubled))
    assert schedule["id"] in {s["id"] for s in result.candidate["schedules"]}
    assert "EVIDENCE_AMBIGUOUS" not in {i["code"] for i in result.issues if i["target"].startswith("/schedules")}
