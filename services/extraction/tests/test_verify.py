"""독립 검증 V1~V4와 최종 판정. 결합기가 만든 후보를 쓰며 네트워크·AI 호출이 없습니다."""

import copy
import hashlib
import json
from datetime import UTC, datetime

import pytest
from helpers import ai_part, example, source_for

from mujuthaek_extraction.assemble.assembler import assemble
from mujuthaek_extraction.intake.adapter import units_bytes
from mujuthaek_extraction.intake.model import ApiReference
from mujuthaek_extraction.verify import SourceBundle, verify_extraction

NOW = datetime(2026, 10, 15, 3, 0, tzinfo=UTC)


def clock():
    return NOW


def assembled(name="housing"):
    data = example(name)
    source = source_for(name, data)
    result = assemble([ai_part(data)], source)
    assert result.contract_errors == [] and result.issues == []
    return result.candidate, SourceBundle.from_material(source.material)


def codes(report):
    return {f.code for f in report.findings}


@pytest.mark.parametrize("name", ["housing", "business"])
def test_clean_candidate_passes_and_input_is_unchanged(name):
    candidate, sources = assembled(name)
    before = copy.deepcopy(candidate)
    report = verify_extraction(candidate, sources, None, clock=clock)
    assert report.passed, report.findings
    assert report.contract_errors == [] and candidate == before
    verification = report.verification
    assert verification["sourceStatus"] == "PASSED" and verification["apiComparison"]["status"] == "SKIPPED_NO_API_REFERENCE"
    assert verification["checkedAt"] == NOW.isoformat() and verification["rulesVersion"] == "verify-1.9.0"


# ---------- V2 원문 근거 ----------


def test_quote_not_in_source_needs_review():
    candidate, sources = assembled()
    candidate["evidence"][0]["quote"] = "원문에 없는 인용문입니다"
    report = verify_extraction(candidate, sources, None, clock=clock)
    assert not report.passed and {"EVIDENCE_QUOTE_NOT_FOUND", "CLAIM_UNSUPPORTED"} <= codes(report)
    assert report.verification["sourceStatus"] == "REVIEW_REQUIRED"


def test_wrong_locator_reports_where_quote_actually_is():
    candidate, sources = assembled()
    evidence = candidate["evidence"][0]
    text = sources.texts[evidence["sourceId"]]
    quote = text[10:30].strip()
    start = text.find(quote)
    evidence["quote"] = quote
    evidence["locator"] = {"kind": "TEXT_RANGE", "start": 100, "end": 120}
    report = verify_extraction(candidate, sources, None, clock=clock)
    finding = next(f for f in report.findings if f.code == "EVIDENCE_LOCATOR_MISMATCH")
    assert f"[{start},{start + len(quote)})" in finding.description


def test_changed_source_text_breaks_hash():
    candidate, sources = assembled()
    doc_id = candidate["documents"][0]["id"]
    tampered = SourceBundle(texts={doc_id: sources.texts[doc_id] + " "})
    assert "EVIDENCE_SOURCE_HASH_MISMATCH" in codes(verify_extraction(candidate, tampered, None, clock=clock))


def test_original_text_must_be_verbatim():
    candidate, sources = assembled()
    candidate["eligibility"][0]["originalText"] = "원문과 다르게 고쳐 쓴 자격 문장입니다"
    assert "ORIGINAL_TEXT_NOT_FOUND" in codes(verify_extraction(candidate, sources, None, clock=clock))


def test_pdf_evidence_without_pdf_is_unavailable_not_passed():
    candidate, sources = assembled()
    candidate["documents"].append({"id": "file-1", "kind": "PDF", "name": "a.pdf", "sha256": "a" * 64, "originalUrl": None, "pageCount": 3})
    candidate["evidence"].append(
        {"id": "ev-pdf", "sourceId": "file-1", "locator": {"kind": "PDF_PAGE", "page": 1, "printedPage": None}, "quote": "가상 공고 문장"}
    )
    candidate["notice"]["evidenceIds"].append("ev-pdf")
    report = verify_extraction(candidate, sources, None, clock=clock)
    assert "EVIDENCE_SOURCE_UNAVAILABLE" in codes(report) and not report.passed


# ---------- V3 수치 ----------


def test_amount_must_match_original_amount_and_unit():
    candidate, sources = assembled()
    candidate["supplyGroups"][0]["prices"][0]["amountKrw"] += 1
    assert "PRICE_UNIT_MISMATCH" in codes(verify_extraction(candidate, sources, None, clock=clock))


def test_two_deposits_in_one_plan_are_mixed():
    candidate, sources = assembled()
    prices = candidate["supplyGroups"][0]["prices"]
    extra = copy.deepcopy(next(p for p in prices if p["kind"] == "DEPOSIT"))
    extra["id"] += "-2"
    prices.append(extra)
    assert "PRICE_PLAN_MIXED" in codes(verify_extraction(candidate, sources, None, clock=clock))


def test_collector_units_disagreement_is_reported_with_both_values():
    candidate, sources = assembled()
    group = candidate["supplyGroups"][0]
    units = ({"houseType": group["housingTypeCode"], "areaM2": group["exclusiveAreaSqm"] + 1},)
    candidate["documents"].append(
        {
            "id": "units",
            "kind": "UNITS",
            "name": "수집 호별 표",
            "sha256": hashlib.sha256(units_bytes(units)).hexdigest(),
            "originalUrl": None,
            "pageCount": None,
        }
    )
    candidate["evidence"].append({"id": "ev-unit", "sourceId": "units", "locator": {"kind": "UNIT_ROW", "pointer": "/0"}, "quote": group["housingTypeCode"]})
    group["evidenceIds"].append("ev-unit")
    bundle = SourceBundle(texts=sources.texts, units={"units": units})
    report = verify_extraction(candidate, bundle, None, clock=clock)
    finding = next(f for f in report.findings if f.code == "UNITS_DISAGREE")
    assert finding.target == "/supplyGroups/0" and "전용면적" in finding.description


# ---------- 최종 판정 ----------


def test_incomplete_coverage_or_existing_issue_blocks_pass():
    candidate, sources = assembled()
    partial = copy.deepcopy(candidate)
    partial["quality"]["coverage"]["eligibility"] = {"status": "PARTIAL", "reason": "일부만 추출했습니다."}
    assert not verify_extraction(partial, sources, None, clock=clock).passed
    failed = copy.deepcopy(candidate)
    failed["quality"]["coverage"]["schedules"] = {"status": "FAILED", "reason": "호출 실패"}
    failed["schedules"] = []
    assert not verify_extraction(failed, sources, None, clock=clock).passed


def test_evidence_problem_outside_judgment_sections_becomes_note_and_passes():
    # 검수 등급(2026-10-15): 서류·일정 등의 근거 위치 문제는 사람 검수 대신 notes로 옮겨 표시만 합니다
    candidate, sources = assembled()
    candidate["quality"]["coverage"]["requiredDocuments"] = {"status": "PARTIAL", "reason": "근거를 확인하지 못한 항목을 제외했습니다."}
    dropped = {"code": "ITEM_DROPPED_NO_EVIDENCE", "target": "/requiredDocuments", "description": "근거를 확인하지 못한 항목 1개를 뺐습니다: 등본"}
    candidate["quality"]["reviewIssues"].append(dropped)
    report = verify_extraction(candidate, sources, None, clock=clock)
    assert report.passed, report.findings
    assert report.candidate["quality"]["reviewIssues"] == [] and report.candidate["quality"]["notes"] == [dropped]


def test_evidence_problem_on_eligibility_still_needs_review():
    candidate, sources = assembled()
    candidate["quality"]["reviewIssues"].append({"code": "EVIDENCE_QUOTE_NOT_FOUND", "target": "/eligibility/0", "description": "x"})
    report = verify_extraction(candidate, sources, None, clock=clock)
    assert not report.passed and report.candidate["quality"]["notes"] == []

    flagged = copy.deepcopy(candidate)
    flagged["quality"]["reviewIssues"].append({"code": "EVIDENCE_AMBIGUOUS", "target": "/notice", "description": "결합 단계 이슈"})
    report = verify_extraction(flagged, sources, None, clock=clock)
    assert not report.passed and report.verification["sourceStatus"] == "PASSED"


def test_schema_broken_candidate_is_not_checked_further():
    candidate, sources = assembled()
    candidate["notice"]["field"] = "SPACESHIP"
    report = verify_extraction(candidate, sources, None, clock=clock)
    assert not report.passed and report.verification["sourceStatus"] == "NOT_RUN"
    assert any(code.startswith("CONTRACT_SCHEMA") for code in codes(report))


def test_completion_gate_rechecks_ready_conditions():
    candidate, sources = assembled()
    assert candidate["eligibility"][0]["rule"]["reason"] is None
    # 검수 대기 중에는 허용되지만 완료(PASSED)에는 자격 조건의 미확정 누락이 없어야 합니다
    candidate["quality"]["missingFields"].append({"target": "/eligibility/0/rule/reason", "reason": "NEEDS_REVIEW", "description": "확인 필요"})
    report = verify_extraction(candidate, sources, None, clock=clock)
    assert not report.passed and "CONTRACT_UNRESOLVED_MISSING_FIELD" in codes(report)


def test_unresolved_missing_value_outside_judgment_sections_is_note():
    candidate, sources = assembled()
    assert candidate["supplyGroups"][0]["prices"][0]["vatRate"] is None
    candidate["quality"]["missingFields"].append({"target": "/supplyGroups/0/prices/0/vatRate", "reason": "NEEDS_REVIEW", "description": "확인 필요"})
    report = verify_extraction(candidate, sources, None, clock=clock)
    assert report.passed, report.findings
    assert [n["code"] for n in report.candidate["quality"]["notes"]] == ["MISSING_FIELD_NEEDS_REVIEW"]


# ---------- V4 API 대조 ----------


def with_lh_identity(candidate):
    reference = candidate["sourceReferences"][0]
    reference.update(sourceCode="lh", sourceNoticeId="0000061143")
    return reference


def api(candidate, body, **overrides):
    reference = with_lh_identity(candidate)
    raw = json.dumps(body, ensure_ascii=False).encode()
    values = dict(
        source_code="lh",
        source_notice_id="0000061143",
        raw_post_id=reference["rawPostId"],
        raw_version=reference["rawVersion"],
        sha256=hashlib.sha256(raw).hexdigest(),
        body=raw,
    )
    values.update(overrides)
    return ApiReference(**values)


def lh_body(offered=1, begin="20261001"):
    return {"목록": {"PAN_NT_ST_DT": "2026.10.01"}, "공급": {"dsList01": [{"LTR_SPL_RMNO": offered}]}, "마이홈": [{"beginDe": begin}]}


def count_field(report):
    return next(f for f in report.verification["apiComparison"]["fields"] if f["target"].startswith("/notice/counts"))


def test_matching_api_reference_passes_and_records_fields():
    candidate, sources = assembled()
    reference = api(candidate, lh_body())
    report = verify_extraction(candidate, sources, reference, clock=clock)
    comparison = report.verification["apiComparison"]
    assert report.passed and report.contract_errors == [], report.findings
    assert comparison["status"] == "MATCHED" and comparison["referenceSha256"] == reference.sha256
    statuses = {f["sourcePath"]: f["status"] for f in comparison["fields"]}
    assert statuses["/목록/PAN_NT_ST_DT"] == "MATCH" and count_field(report)["status"] == "MATCH"
    begin = next(f for f in comparison["fields"] if f["sourcePath"] == "/마이홈/0/beginDe")
    assert begin["status"] in {"MATCH", "NOT_COMPARABLE"} and (begin["status"] == "MATCH" or "날짜는 같습니다" in begin["reason"])


def test_api_mismatch_needs_review():
    candidate, sources = assembled()
    report = verify_extraction(candidate, sources, api(candidate, lh_body(offered=2)), clock=clock)
    assert not report.passed and report.verification["apiComparison"]["status"] == "MISMATCH"
    assert "API_MISMATCH" in codes(report) and report.contract_errors == []


def test_api_value_marked_see_notice_is_not_comparable():
    candidate, sources = assembled()
    report = verify_extraction(candidate, sources, api(candidate, lh_body(offered="공고문 참조")), clock=clock)
    field = count_field(report)
    assert field["status"] == "NOT_COMPARABLE" and field["expected"] is None and field["reason"]


def test_zero_supply_count_means_undecided_not_mismatch():
    candidate, sources = assembled()
    report = verify_extraction(candidate, sources, api(candidate, lh_body(offered=0)), clock=clock)
    field = count_field(report)
    assert field["status"] == "NOT_COMPARABLE" and "API_MISMATCH" not in codes(report)


def test_api_of_other_version_or_tampered_body_is_not_used():
    candidate, sources = assembled()
    other = api(candidate, lh_body(), raw_version=99)
    report = verify_extraction(candidate, sources, other, clock=clock)
    assert report.verification["apiComparison"]["status"] == "NOT_RUN" and "API_REFERENCE_IDENTITY_CONFLICT" in codes(report)

    candidate, sources = assembled()
    tampered = api(candidate, lh_body(), sha256="0" * 64)
    assert "API_REFERENCE_HASH_MISMATCH" in codes(verify_extraction(candidate, sources, tampered, clock=clock))


def test_source_without_own_mapping_uses_common_mapping():
    # 출처 전용 대조표가 없으면 공통 대조표(키 이름으로 찾기)를 씁니다
    candidate, sources = assembled()
    reference = api(candidate, lh_body())
    candidate["sourceReferences"][0]["sourceCode"] = "sh"
    report = verify_extraction(candidate, sources, ApiReference(**{**reference.__dict__, "source_code": "sh"}), clock=clock)
    assert report.verification["apiComparison"]["status"] == "MATCHED" and report.passed


def test_differently_shaped_api_json_is_found_by_key_names():
    # 마이홈 목록 형식: 같은 뜻의 값이 다른 키·다른 깊이에 있습니다
    candidate, sources = assembled()
    body = {"response": {"body": {"item": [{"rcritPblancDe": "20261001", "LTR_SPL_RMNO": "1", "beginDe": "20261001"}]}}}
    report = verify_extraction(candidate, sources, api(candidate, body), clock=clock)
    paths = {f["sourcePath"] for f in report.verification["apiComparison"]["fields"]}
    assert "/response/body/item/0/rcritPblancDe" in paths and report.verification["apiComparison"]["status"] == "MATCHED"


def test_api_json_without_known_keys_does_not_block():
    candidate, sources = assembled()
    report = verify_extraction(candidate, sources, api(candidate, [{"dsSch": [{"PAGE": "1"}]}]), clock=clock)
    assert report.verification["apiComparison"]["status"] == "NO_COMPARABLE_FIELDS" and report.passed


def test_correction_notice_api_date_is_not_compared():
    # 정정공고의 API 공고 날짜는 정정일인 경우가 많아 날짜 불일치로 검수에 보내지 않습니다 (실측 3건)
    candidate, sources = assembled()
    candidate["notice"]["title"] = "[정정공고] " + candidate["notice"]["title"]
    body = lh_body()
    body["목록"]["PAN_NT_ST_DT"] = "2026.12.24"
    report = verify_extraction(candidate, sources, api(candidate, body), clock=clock)
    field = next(f for f in report.verification["apiComparison"]["fields"] if f["sourcePath"] == "/목록/PAN_NT_ST_DT")
    assert field["status"] == "NOT_COMPARABLE" and "정정" in field["reason"] and "API_MISMATCH" not in codes(report)


def test_correction_word_in_api_body_text_does_not_skip_date_check():
    # 검토 지적: API 응답 어딘가의 "정정"(안내 글 등)만으로도 날짜 불일치를 비교 안 함으로 돌렸음
    candidate, sources = assembled()
    body = lh_body()
    body["목록"]["PAN_NT_ST_DT"] = "2026.12.24"
    body["목록"]["SPL_INF_GUD_FCTS"] = "공고 내용이 정정될 수 있습니다"  # 공고명이 아닌 안내 칸
    report = verify_extraction(candidate, sources, api(candidate, body), clock=clock)
    assert "API_MISMATCH" in codes(report)


def test_supply_count_is_sum_of_regional_rows_not_myhome_slice():
    # 실측(대구경북 청년매입임대): 원문 총 155호, 마이홈 sumSuplyCo 37(달서구 몫), LH 공급 지역별 행 합계 155
    candidate, sources = assembled()
    offered = next(c for c in candidate["notice"]["counts"] if c["kind"] == "OFFERED" and c["scope"]["level"] == "NOTICE")["value"]
    rows = [{"LTR_SPL_RMNO": str(offered - 1)}, {"LTR_SPL_RMNO": "1"}]
    body = {"공급": {"dsList02": rows}, "마이홈": [{"sumSuplyCo": 1}]}
    report = verify_extraction(candidate, sources, api(candidate, body), clock=clock)
    field = count_field(report)
    assert field["status"] == "MATCH" and field["expected"] == offered and "2행" in field["sourcePath"]
    assert "API_MISMATCH" not in codes(report)


def test_supply_count_without_regional_rows_is_not_compared():
    candidate, sources = assembled()
    report = verify_extraction(candidate, sources, api(candidate, {"마이홈": [{"sumSuplyCo": 999}]}), clock=clock)
    field = count_field(report)
    assert field["status"] == "NOT_COMPARABLE" and "API_MISMATCH" not in codes(report)


def test_deposits_for_different_target_groups_are_not_mixed():
    # 1순위 보증금 100만원 / 2·3순위 200만원처럼 대상 계층이 다른 가격은 전환안 섞임이 아닙니다 (청년매입임대 실측)
    candidate, sources = assembled()
    prices = candidate["supplyGroups"][0]["prices"]
    base = next(p for p in prices if p["kind"] == "DEPOSIT")
    extra = copy.deepcopy(base)
    extra["id"] += "-rank2"
    base["scope"]["targetGroups"], extra["scope"]["targetGroups"] = ["1순위"], ["2순위"]
    prices.append(extra)
    assert "PRICE_PLAN_MIXED" not in codes(verify_extraction(candidate, sources, None, clock=clock))


def test_units_comparison_without_rent_plans_does_not_crash():
    # 검토 지적: 보증금·임대료 가격안이 없는 묶음(분양가만 등)에 가격안 번호가 있으면 정의되지 않은 변수로 멈췄음
    from mujuthaek_extraction.verify.numbers import check_numbers

    candidate, sources = assembled()
    group = candidate["supplyGroups"][0]
    group["prices"] = [{**group["prices"][0], "kind": "SALE_PRICE", "pricePlanId": "plan-sale"}]
    candidate["evidence"].append({"id": "ev-unit", "sourceId": "units", "locator": {"kind": "UNIT_ROW", "pointer": "/0"}, "quote": "x"})
    group["evidenceIds"].append("ev-unit")
    bundle = SourceBundle(texts=sources.texts, units={"units": ({"areaM2": group["exclusiveAreaSqm"]},)})
    assert check_numbers(candidate, bundle) == []


def test_section_the_ai_reported_as_partial_is_not_auto_completed():
    # 실측(2026-10-16 시험): 가점표를 일부만 옮기고 PARTIAL로 적은 결과가 판정 섹션이 아니라는 이유로 자동 완료되던 것
    from mujuthaek_extraction.assemble.coverage import DROPPED_NO_EVIDENCE

    candidate, sources = assembled()
    partial = copy.deepcopy(candidate)
    partial["quality"]["coverage"]["selectionRules"] = {"status": "PARTIAL", "reason": "가점표의 자녀 수 행을 옮기지 못했습니다."}
    report = verify_extraction(partial, sources, None, clock=clock)
    assert not report.passed
    issues = report.candidate["quality"]["reviewIssues"]
    assert any(i["code"] == "COVERAGE_PARTIAL_REPORTED" and i["target"] == "/quality/coverage/selectionRules" for i in issues)

    # 프로그램이 근거 없는 항목을 빼면서 바꾼 PARTIAL은 지금처럼 표시만 하고 완료합니다
    dropped = copy.deepcopy(candidate)
    dropped["quality"]["coverage"]["selectionRules"] = {"status": "PARTIAL", "reason": DROPPED_NO_EVIDENCE}
    assert verify_extraction(dropped, sources, None, clock=clock).passed


# ---------- 부분 완료 (2026-10-17) ----------


def test_issue_on_one_condition_publishes_notice_and_holds_only_that_condition():
    # 공고 수천 건을 다 사람이 볼 수 없어, 문제가 특정 조건에만 있으면 공고는 게시하고 그 조건만 판정 보류합니다
    from mujuthaek_extraction.contracts import tools
    from mujuthaek_extraction.publish import completed_payload

    candidate, sources = assembled()
    candidate["quality"]["reviewIssues"].append({"code": "EXCEPTION_SUSPECTED", "target": "/eligibility/0", "description": "가산 기준이 빠졌을 수 있습니다."})
    report = verify_extraction(candidate, sources, None, clock=clock)
    quality = report.candidate["quality"]
    assert report.verification["status"] == "PARTIALLY_PASSED" and not report.passed
    assert quality["heldItems"] == [
        {"target": "/eligibility/0", "extent": "ITEM", "codes": ["EXCEPTION_SUSPECTED"], "description": "확인 전까지 판정·화면에 쓰지 않습니다(원문 확인 필요)"}
    ]
    payload = completed_payload(report.candidate)
    assert payload["reviewStatus"] == "PARTIALLY_PASSED" and payload["heldItems"] == quality["heldItems"]
    assert payload["conditions"][0]["inReview"] and "EXCEPTION_SUSPECTED" in payload["conditions"][0]["reviewReasons"]
    event = {
        "eventId": "00000000-0000-4000-8000-000000000001",
        "eventType": "extraction.completed",
        "schemaVersion": 1,
        "occurredAt": NOW.isoformat(),
        "producer": "extraction",
        "traceId": None,
        "payload": payload,
    }
    assert tools().validate(event, "events/extraction.completed.v1.json") == []


def test_notice_identity_problem_or_missing_eligibility_section_scopes():
    candidate, sources = assembled()
    identity = copy.deepcopy(candidate)  # 공고 정체성 문제는 보류할 수 없습니다
    identity["quality"]["reviewIssues"].append({"code": "NOT_A_NOTICE_SUSPECTED", "target": "/notice", "description": "Q&A 문서입니다."})
    report = verify_extraction(identity, sources, None, clock=clock)
    assert report.verification["status"] == "REVIEW_REQUIRED" and "heldItems" not in report.candidate["quality"]

    partial = copy.deepcopy(candidate)  # 자격 섹션을 다 못 뽑았으면 자격 섹션 전체를 보류(판정 보류)하고 공고는 게시합니다
    partial["quality"]["coverage"]["eligibility"] = {"status": "PARTIAL", "reason": "일부만 추출했습니다."}
    report = verify_extraction(partial, sources, None, clock=clock)
    held = report.candidate["quality"]["heldItems"]
    assert report.verification["status"] == "PARTIALLY_PASSED" and [(h["target"], h["extent"]) for h in held] == [("/eligibility", "SECTION")]
    from mujuthaek_extraction.publish import completed_payload

    assert all(c["inReview"] for c in completed_payload(report.candidate)["conditions"])
