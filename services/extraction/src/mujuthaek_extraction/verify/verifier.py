"""검증 실행과 최종 판정 (설계 5장).

검수 등급: 판정에 쓰지 않는 섹션(서류·일정·공급·위치·선정)의 근거 위치 문제는 quality.notes로 옮겨 자동 처리합니다(화면 표시만).
sourceStatus = PASSED ⇔ notes로 옮기지 않은 V2·V3·V5 문제 없음
status       = PASSED ⇔ V1 통과 ∧ sourceStatus PASSED ∧ API 대조가 MATCHED·NO_COMPARABLE_FIELDS·SKIPPED_NO_API_REFERENCE
                         ∧ 검수 이슈(reviewIssues) 없음 ∧ 공고·자격 섹션 완전 ∧ 그 밖의 섹션은 실패·미시도 없음
PASSED여도 계약의 완료 조건(ready 검사)으로 한 번 더 확인하고, 걸리면 REVIEW_REQUIRED로 되돌립니다.
AI 응답의 자기 보고로 PASSED가 되는 경로는 없습니다. 입력 후보는 바꾸지 않고 새 후보를 돌려줍니다.
"""

from __future__ import annotations

import copy
from collections.abc import Callable
from datetime import datetime
from typing import Any

from mujuthaek_extraction.assemble.coverage import self_reported_partial
from mujuthaek_extraction.contracts import tools
from mujuthaek_extraction.intake.model import ApiReference
from mujuthaek_extraction.verify.api_compare import compare_api
from mujuthaek_extraction.verify.claims import check_claims
from mujuthaek_extraction.verify.consistency import check_consistency
from mujuthaek_extraction.verify.evidence import check_evidence
from mujuthaek_extraction.verify.exceptions import check_missing_exceptions
from mujuthaek_extraction.verify.model import RULES_VERSION, Finding, PdfTextExtractor, SourceBundle, VerificationReport
from mujuthaek_extraction.verify.numbers import check_numbers

SCHEMA = "schemas/extracted-notice.v1.json"
API_OK = {"MATCHED", "NO_COMPARABLE_FIELDS", "SKIPPED_NO_API_REFERENCE"}
# 검수 등급 (2026-10-15 결정: AI 재확인 후에도 남으면 사람 확인은 판정에 영향 주는 것만)
CRITICAL_SECTIONS = {"notice", "eligibility"}
# 근거 위치 문제: 판정에 쓰지 않는 섹션이면 자동 처리(항목 제외·표시)하고 quality.notes로 옮깁니다
NOTE_CODES = {
    "EVIDENCE_QUOTE_NOT_FOUND",
    "EVIDENCE_AMBIGUOUS",
    "EVIDENCE_QUOTE_TOO_SHORT",
    "ITEM_DROPPED_NO_EVIDENCE",
    "CLAIM_UNSUPPORTED",
    "ORIGINAL_TEXT_NOT_FOUND",
    "MISSING_FIELD_HAS_VALUE",
    "AI_OUTPUT_ITEM_DROPPED",
    "ITEM_QUARANTINED",
    # 정답표 없이 하는 검사(V6 표 위치·V7 일관성·V8 누락): 의심이라 판정 섹션에서만 사람 확인
    "VALUE_POSITION_SUSPECTED",
    "SCHEDULE_ORDER_SUSPECTED",
    "VALUE_OUT_OF_RANGE",
    "RENT_EXCEEDS_DEPOSIT",
    "COUNT_TOTAL_SUSPECTED",
    "SECTION_OMISSION_SUSPECTED",
    "SCORING_OMISSION_SUSPECTED",
    "LIST_OMISSION_SUSPECTED",
    "ELIGIBILITY_OMISSION_SUSPECTED",
    "SUPPLY_PRICE_UNATTACHED",
    # V9 값–인용 대조: 자격·공고일이면 사람 확인(값을 잘못 옮기면 판정이 틀림), 공급·일정은 원문 어디에도 없을 때만 걸리므로 표시
    "VALUE_NOT_IN_QUOTE",
    # 그림 판독 글자(AI가 옮김)를 근거로 한 공급·일정 등은 표시만. 자격·공고 정보는 EVIDENCE_FROM_IMAGE로 사람 확인
    "IMAGE_TRANSCRIBED_VALUES",
}
# 비교를 못 했을 뿐 불일치가 아닌 API 대조 결과: 섹션과 상관없이 표시만 (불일치 API_MISMATCH는 사람 확인)
ALWAYS_NOTE_CODES = {"API_TARGET_MISSING", "API_SCOPE_AMBIGUOUS", "API_MAPPING_MISSING", "INCOME_CRITERION_UNUSED", "AI_OUTPUT_ID_RENAMED"}
# INCOME_CRITERION_UNUSED: 소득 기준 일부 누락 의심. 실측 정밀도가 낮아(재계약·완화 표 등) 검수자 화면 메모로만 둡니다(2026-10-16)


def is_note(issue: dict[str, str]) -> bool:
    if issue["code"] in ALWAYS_NOTE_CODES:
        return True
    section = issue["target"].lstrip("/").split("/", 1)[0]
    return issue["code"] in NOTE_CODES and section not in CRITICAL_SECTIONS and section != ""


def _gate_findings(result: dict[str, Any], contract: Any, known: list[str], now: str) -> list[Finding]:
    """완료 조건(범위 미확정·미해결 누락·미검토 예외 등)은 PASSED일 때만 검사되어 다른 문제가 다 풀린 뒤에야 드러납니다.
    처음부터 PASSED로 가정해 미리 검사하고, 걸리는 것을 검수 대상에 올려 AI 재확인이 한 번에 고칠 수 있게 합니다."""
    probe = copy.deepcopy(result)
    probe["quality"]["reviewIssues"] = []
    # API 대조는 따로 판정하므로 여기서는 통과 값으로 둡니다(스키마 오류로 의미 검사가 건너뛰어지지 않게)
    neutral_api = {"status": "SKIPPED_NO_API_REFERENCE", "referenceSha256": None, "referenceIdentity": None, "fields": []}
    probe["quality"]["verification"].update(status="PASSED", sourceStatus="PASSED", checkedAt=now, rulesVersion=RULES_VERSION, apiComparison=neutral_api)
    errors = [e for e in contract.validate(probe, SCHEMA) if e not in known and not e.startswith("SCHEMA")]
    return [_error_finding(e) for e in errors]


def check_reported_partial(result: dict[str, Any]) -> list[Finding]:
    """AI가 원문에 있는데 다 옮기지 못했다고 스스로 적은 섹션(PARTIAL). 판정 섹션은 계약 검사가 이미 막으므로 그 밖의 섹션만 봅니다.
    재확인이 빠진 항목을 채우지 못하면 사람 확인으로 갑니다(가점표·일정·서류가 빠진 채 자동 완료되지 않게)."""
    return [
        Finding(
            "V8",
            "COVERAGE_PARTIAL_REPORTED",
            f"/quality/coverage/{section}",
            f"원문에 있는데 다 옮기지 못했다고 적은 섹션입니다: {entry.get('reason') or '사유 없음'}",
        )
        for section, entry in result["quality"]["coverage"].items()
        if section not in CRITICAL_SECTIONS and self_reported_partial(entry)
    ]


def _incomplete(section: str, status: str) -> bool:
    return status in ({"PARTIAL", "NOT_ATTEMPTED", "FAILED"} if section in CRITICAL_SECTIONS else {"NOT_ATTEMPTED", "FAILED"})


def _error_finding(error: str) -> Finding:
    code, _, rest = error.partition(" ")
    target = rest.split(":", 1)[0].strip() or "/"
    return Finding("V1", f"CONTRACT_{code}", target if target.startswith("/") else "/", error)


def verify_extraction(
    candidate: dict[str, Any],
    sources: SourceBundle,
    api_reference: ApiReference | None,
    *,
    clock: Callable[[], datetime],
    pdf_text: PdfTextExtractor | None = None,
) -> VerificationReport:
    """후보 사본에 모든 검사를 돌려 검수 이슈·표시 메모를 나누고 PASSED·PARTIALLY_PASSED·REVIEW_REQUIRED를 판정해 돌려줍니다."""
    result = copy.deepcopy(candidate)
    quality = result["quality"]
    quality["verification"] = {
        "status": "NOT_RUN",
        "sourceStatus": "NOT_RUN",
        "checkedAt": None,
        "rulesVersion": None,
        "apiComparison": quality["verification"]["apiComparison"],
    }
    contract = tools()

    contract_errors = contract.validate(result, SCHEMA)
    findings = [_error_finding(e) for e in contract_errors]
    schema_broken = any(e.startswith("SCHEMA") for e in contract_errors)
    if not schema_broken:
        source_findings = check_evidence(result, sources, pdf_text) + check_numbers(result, sources) + check_missing_exceptions(result, sources)
        source_findings += check_consistency(result, sources) + check_claims(result, sources) + check_reported_partial(result)
        comparison, api_findings = compare_api(result, api_reference)
        findings += source_findings + api_findings + _gate_findings(result, contract, contract_errors, clock().isoformat())
        quality["verification"]["apiComparison"] = comparison
        blocking = [f for f in source_findings if not is_note(f.issue())]
        quality["verification"]["sourceStatus"] = "REVIEW_REQUIRED" if blocking else "PASSED"
    else:
        quality["verification"]["sourceStatus"] = "NOT_RUN"

    existing = {(i["code"], i["target"]) for i in quality["reviewIssues"]}
    for finding in findings:
        if (finding.code, finding.target) not in existing:
            quality["reviewIssues"].append(finding.issue())
            existing.add((finding.code, finding.target))
    for entry in quality["missingFields"]:  # 판정에 쓰지 않는 칸의 미확정 값은 표시만 합니다
        section = entry["target"].lstrip("/").split("/", 1)[0]
        if entry["reason"] not in {"NOT_IN_SOURCE", "NOT_APPLICABLE", "TO_BE_ANNOUNCED"} and section not in CRITICAL_SECTIONS:
            quality["notes"].append({"code": "MISSING_FIELD_NEEDS_REVIEW", "target": entry["target"], "description": entry["description"]})
    quality["notes"] = list({(i["code"], i["target"], i["description"]): i for i in [*quality["notes"], *filter(is_note, quality["reviewIssues"])]}.values())
    quality["reviewIssues"] = [i for i in quality["reviewIssues"] if not is_note(i)]

    passed = (
        not contract_errors
        and quality["verification"]["sourceStatus"] == "PASSED"
        and quality["verification"]["apiComparison"]["status"] in API_OK
        and not quality["reviewIssues"]
        and not any(_incomplete(section, entry["status"]) for section, entry in quality["coverage"].items())
    )
    held = None if passed or schema_broken else hold_plan(result, contract_errors)
    status = "PASSED" if passed else "PARTIALLY_PASSED" if held else "REVIEW_REQUIRED"
    quality["verification"].update(status=status, checkedAt=clock().isoformat(), rulesVersion=RULES_VERSION)
    if held:
        quality["heldItems"] = held

    if status != "REVIEW_REQUIRED":  # 완료 조건(근거 있는 필드 평가, 누락 사유, 미검토 예외 등)을 계약 검사기로 다시 확인합니다
        ready_errors = contract.validate(result, SCHEMA)
        if ready_errors:
            gate = [_error_finding(e) for e in ready_errors]
            findings += gate
            quality["reviewIssues"].extend(f.issue() for f in gate)
            quality["verification"]["status"] = "REVIEW_REQUIRED"
            quality.pop("heldItems", None)
            contract_errors = ready_errors
    return VerificationReport(candidate=result, findings=findings, contract_errors=contract_errors)


# ---- 부분 완료 (2026-10-17 설계 검토: 영향 범위만 보류) ----
# 공고 수천 건을 전부 사람이 볼 수 없으므로, 검수 이슈가 특정 항목·섹션에만 있으면 공고는 게시하고 그 범위만 '원문 확인 필요'로 보류합니다.
# 공고 정체성(제목·공고일·분야)·입력·원본 문서·API 불일치·계약 형식 오류는 보류할 수 없어 공고 전체가 검수로 갑니다.
LIST_SECTIONS = ("locations", "supplyGroups", "schedules", "eligibility", "selectionRules", "requiredDocuments", "guidance")


def held_target(candidate: dict[str, Any], pointer: str) -> tuple[str, str] | None:
    """검수 이슈 위치 → 보류할 (범위, ITEM/SECTION). 보류할 수 없는 위치면 None."""
    pointer = pointer.split(" ", 1)[0]
    parts = pointer.lstrip("/").split("/")
    if pointer.startswith("/quality/coverage/") and len(parts) >= 3 and parts[2] in LIST_SECTIONS:
        return f"/{parts[2]}", "SECTION"
    if pointer.startswith("/quality/missingFields/") and len(parts) >= 3 and parts[2].isdigit():
        entries = candidate["quality"]["missingFields"]
        index = int(parts[2])
        return held_target(candidate, entries[index]["target"]) if index < len(entries) else None
    if parts[0] == "counts" and len(parts) >= 2:  # 수량 규칙 오류(/counts/<수량 id>): 그 수량을 가진 공급 묶음
        for g, group in enumerate(candidate["supplyGroups"]):
            if any(c.get("id") == parts[1] for c in group.get("counts") or []):
                return f"/supplyGroups/{g}", "ITEM"
        return None
    if parts[0] in LIST_SECTIONS:
        if len(parts) >= 2 and parts[1].isdigit() and int(parts[1]) < len(candidate[parts[0]]):
            return f"/{parts[0]}/{parts[1]}", "ITEM"
        return f"/{parts[0]}", "SECTION"
    return None


def hold_plan(candidate: dict[str, Any], contract_errors: list[str]) -> list[dict[str, Any]] | None:
    """모든 검수 이슈·미완 섹션을 보류 범위로 묶을 수 있으면 보류 목록, 하나라도 공고 전체 문제면 None."""
    quality = candidate["quality"]
    if quality["verification"]["apiComparison"]["status"] not in API_OK or quality["verification"]["sourceStatus"] == "NOT_RUN":
        return None
    if _incomplete("notice", quality["coverage"]["notice"]["status"]):
        return None
    held: dict[str, dict[str, Any]] = {}

    def add(target: str, extent: str, code: str, description: str) -> None:
        entry = held.setdefault(target, {"target": target, "extent": extent, "codes": [], "description": description})
        if code not in entry["codes"]:
            entry["codes"].append(code)

    for issue in quality["reviewIssues"]:
        found = held_target(candidate, issue["target"])
        if found is None:
            return None
        add(*found, issue["code"], "확인 전까지 판정·화면에 쓰지 않습니다(원문 확인 필요)")
    for error in contract_errors:
        code, _, pointer = error.partition(" ")
        found = held_target(candidate, pointer)
        if found is None:
            return None
        add(*found, f"CONTRACT_{code}", "계약 완료 조건을 채우지 못해 확인 전까지 쓰지 않습니다")
    for section, entry in quality["coverage"].items():
        if section != "notice" and _incomplete(section, entry["status"]):
            add(f"/{section}", "SECTION", f"COVERAGE_{entry['status']}", "섹션을 다 추출하지 못해 확인 전까지 쓰지 않습니다")
    if not held:
        return None
    # 섹션 전체를 보류하면 그 안의 항목 보류는 섹션에 합칩니다
    sections = {t for t, h in held.items() if h["extent"] == "SECTION"}
    for target in [t for t in held if any(t.startswith(s + "/") for s in sections)]:
        section = next(s for s in sections if target.startswith(s + "/"))
        held[section]["codes"] = list(dict.fromkeys([*held[section]["codes"], *held.pop(target)["codes"]]))
    return list(held.values())
