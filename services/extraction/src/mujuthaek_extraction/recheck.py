"""AI 재확인·자동 수정 (2026-10-15~16 결정: 사람 검수 전에 AI 재확인을 먼저 거칩니다).

검증에서 문제가 난 항목을 섹션과 상관없이 모아(사람 확인 이슈 + 계약 오류, 최대 MAX_ITEMS개) 문제 설명과 함께 AI에 한 번 더 보여 주고,
고친 항목을 같은 id 자리에 바꿔 넣습니다. 근거를 못 찾아 빠진 자격 조건도 함께 보냅니다.
고친 결과로 다시 결합·검증해서 나빠진 것 없이 좋아졌을 때만 채택하고, 아니면 처음 결과를 그대로 씁니다.
나빠졌는지는 개수가 아니라 (코드, 항목)별로 봅니다. 한 항목을 고치며 다른 항목에 새 문제가 생기면 채택하지 않습니다(2026-10-20).
근거(인용) 문제만 보낸 자격 조건은 조건의 뜻(대상·종류·조건식·예외)이 바뀌면 그 항목만 처음 것을 그대로 둡니다.
오류 종류마다 따로 처리하지 않습니다. 검사기가 준 오류 문구를 그대로 AI에 전달합니다. 표시만 하는 notes는 보내지 않습니다(비용).
AI 응답의 자기 보고로 통과되는 경로는 없습니다(채택 판단은 같은 검증기가 합니다).
"""

from __future__ import annotations

import copy
import json
import re
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from mujuthaek_extraction.assemble.ai_output import SECTIONS
from mujuthaek_extraction.contracts import tools
from mujuthaek_extraction.extract import ExtractionRun, run_extraction, schema_errors
from mujuthaek_extraction.intake.model import ExtractionMaterial
from mujuthaek_extraction.model.base import ExtractionModel
from mujuthaek_extraction.rules.base import CallSpec, FieldRules, structure_summary
from mujuthaek_extraction.verify.model import VerificationReport

NOTICE_FIELD = re.compile(r"\s/notice(?:/([A-Za-z]+))?")  # 문제 설명 「코드 /notice/announcedOn: …」의 칸 이름
MAX_ITEMS = 8  # 한 번에 다시 볼 항목 수 (응답 크기·비용 상한)
MAX_TARGET_CHARS = 40_000  # 다시 볼 항목 글자 수 상한. 호실별 가격 묶음 8개가 한 줄 45만 자가 되어 모델이 읽지 못함(실측 2026-10-18 LH 익산 행복주택)
LIST_SECTIONS = tuple(s for s in SECTIONS if s != "notice")
DROPPED_PROBLEM = (
    "ITEM_DROPPED_NO_EVIDENCE: 인용문을 원문에서 찾지 못해 이 항목이 결과에서 빠졌습니다. 원문에 글자 그대로 이어져 나오는 구간으로 다시 인용합니다."
)
REF_PREFIX = "r-"  # 재확인 응답의 인용 참조가 처음 응답과 겹치지 않게
ITEM_PATH = re.compile(r"^/([A-Za-z]+)(?:/(\d+))?")
COVERAGE_PATH = re.compile(r"^/quality/coverage/([A-Za-z]+)$")
# 근거(인용) 위치 문제. 이것만 보낸 자격 조건은 인용만 고쳐야 하고 조건의 뜻은 그대로여야 합니다
# (실측 2026-10-20: 인용을 고치며 AGE_YEARS GTE 19를 LTE 19로 뒤집은 응답이 이슈 1→0으로 채택되어 통과됨)
EVIDENCE_ONLY_CODES = frozenset(
    {
        "EVIDENCE_QUOTE_NOT_FOUND",
        "EVIDENCE_SCATTERED",
        "EVIDENCE_AMBIGUOUS",
        "EVIDENCE_QUOTE_TOO_SHORT",
        "EVIDENCE_LOCATOR_MISMATCH",
        "EVIDENCE_LOCATOR_OUT_OF_RANGE",
        "EVIDENCE_LOCATOR_KIND_MISMATCH",
        "EVIDENCE_SOURCE_UNKNOWN",
        "EVIDENCE_SOURCE_UNAVAILABLE",
        "EVIDENCE_SOURCE_UNSUPPORTED",
        "CLAIM_UNSUPPORTED",
        "ORIGINAL_TEXT_NOT_FOUND",
        "ITEM_DROPPED_NO_EVIDENCE",
    }
)
# 뜻 비교에서 빼는 글 칸(설명·인용 참조). 나머지(대상·종류·조건식·단위·기준표·예외)는 그대로 같아야 합니다
TEXT_KEYS = frozenset({"quoteRefs", "basis", "description", "originalText", "reason", "appliesTo"})
INSTRUCTIONS = """검증에서 아래 항목에 문제가 발견되었습니다. 원문 전체를 다시 확인해 각 항목을 고친 뒤 같은 섹션·같은 id로 돌려줍니다.
- 문제 설명(코드와 위치)은 프로그램 검사기가 낸 것입니다. 설명한 규칙에 맞게 고칩니다. 규칙은 공통 지시를 따릅니다.
- 예외 누락 의심이면 원문의 가산·완화 기준을 exceptions에 적고 그 표를 인용합니다.
  확인해 보니 이 조건의 예외가 아니면(다른 대상·다른 기준의 값) 조건을 그대로 돌려줍니다.
- 인용문을 원문에서 찾지 못했다면 원문에 글자 그대로 이어져 나오는 구간으로 다시 인용합니다. 표 칸이 섞여 있으면 칸마다 따로 짧게 인용합니다.
- 가구원 수 구간이 조건 없이 나뉘었다는 문제(TIER_CONDITION_MISSING)면 각 조건식을 「그 가구원 수가 아니거나 기준값 이하」로 고칩니다.
  예: 2인 110%는 ANY[NOT(HOUSEHOLD_MEMBER_COUNT EQ 2), INCOME_BENCHMARK_PERCENT LTE 110],
  3인 이상 100%는 ANY[HOUSEHOLD_MEMBER_COUNT LT 3, INCOME_BENCHMARK_PERCENT LTE 100].
- 재계약 기준이 자격에 들어 있다는 문제(RENEWAL_CRITERION_IN_ELIGIBILITY)면 원문을 확인해, 신청할 때의 기준이면 originalText를 신청 기준 문장으로 고치고,
  정말 재계약·갱신 때의 기준이면 그 조건을 그대로 돌려줍니다(사람이 확인합니다).
- 범위를 확정하지 못했다고 표시한 항목(UNRESOLVED_SCOPE)은 원문에서 적용 범위를 확인해 확정합니다. 정말 정할 수 없으면 그대로 둡니다.
- 가격표 금액을 넣지 않았다는 문제면 그 행의 빠진 칸을 계층(scope.targetGroups)·요금안(pricePlanId)별 가격으로 더해 그 공급 묶음 전체를 돌려줍니다.
  수량 종류가 열 제목과 다르다는 문제면 열 제목에 맞는 kind로 고칩니다(건설 호수 TOTAL_HOUSING, 대기 중 예비자 WAITLIST, 금회 모집 OFFERED).
- 수량 묶음(parentCountId·aggregationRole·aggregationComplete)이 맞지 않으면, 원문에 전체와 구성 수량이 함께 있을 때만 묶고 아니면 STANDALONE으로 둡니다.
- id가 null이고 existingIds가 있는 항목은 섹션 전체에 대한 문제입니다(원문에 있는데 빠진 항목).
  원문에서 그 섹션을 끝까지 다시 읽고 빠진 항목만 새 id로 넣습니다. existingIds에 있는 항목은 다시 넣지 않습니다.
  다 넣었으면 그 섹션 coverage를 EXTRACTED로, 아직 남았으면 PARTIAL과 남은 내용을 적습니다.
- 맡은 섹션에는 아래 항목만 넣습니다(목록이면 고친 항목·추가한 항목만). 다른 항목은 다시 추출하지 않습니다.
  그 밖의 coverage는 맡은 섹션마다 EXTRACTED로 적습니다."""


@dataclass
class RecheckResult:
    parts: list[dict[str, Any]]  # 채택한 응답 조각들 (재확인을 채택하지 않았으면 처음 것)
    report: VerificationReport
    rechecked: list[str]  # 다시 본 항목 ("섹션/id")
    accepted: bool
    run: ExtractionRun | None  # 재확인 호출 기록 (사용량·비용)


def _item_key(candidate: dict[str, Any], pointer: str) -> tuple[str, str | None] | None:
    """검수 이슈·계약 오류 위치 → 고칠 항목 (섹션, id). notice는 id 없이. 항목을 정할 수 없으면 None."""
    coverage = COVERAGE_PATH.match(pointer)
    if coverage:  # 섹션 전체 문제(AI가 스스로 PARTIAL로 적음): 빠진 항목을 추가로 받습니다
        return (coverage[1], None) if coverage[1] in LIST_SECTIONS else None
    # 수량 규칙 오류는 그 수량의 실제 위치(/notice/counts/i, /supplyGroups/g/counts/j)로 옵니다: 아래 ITEM_PATH가 공고·공급 항목으로 찾습니다
    if pointer.startswith("/quality/missingFields/"):  # 미해결 누락: 그 누락이 가리키는 항목
        try:
            entry = tools().resolve(candidate, pointer.split(" ")[0])
        except (KeyError, IndexError, TypeError, ValueError):
            return None
        return _item_key(candidate, entry["target"])
    match = ITEM_PATH.match(pointer)
    if not match:
        return None
    section, index = match[1], match[2]
    if section == "notice":
        return ("notice", None)
    if section in LIST_SECTIONS and index is not None and int(index) < len(candidate[section]):
        return (section, candidate[section][int(index)]["id"])
    return None


def problem_items(report: VerificationReport) -> dict[tuple[str, str | None], list[str]]:
    candidate = report.candidate
    problems: dict[tuple[str, str | None], list[str]] = {}
    for issue in candidate["quality"]["reviewIssues"]:
        key = _item_key(candidate, issue["target"])
        if key:
            problems.setdefault(key, []).append(f"{issue['code']} {issue['target']}: {issue['description']}")
    for error in report.contract_errors:
        code, _, pointer = error.partition(" ")
        key = _item_key(candidate, pointer)
        if key:
            problems.setdefault(key, []).append(f"{code} {pointer}")
    return problems


def _location(candidate: dict[str, Any], pointer: str) -> tuple[str, str | None]:
    """문제 위치 → (섹션, 항목 id). 순서가 바뀌어도 같은 항목이 같은 키가 되게 id로 봅니다. 항목을 정할 수 없으면 (섹션, 위치 그대로)."""
    key = _item_key(candidate, pointer)
    if key is not None:
        return key
    match = ITEM_PATH.match(pointer)
    return (match[1] if match else "", pointer)


def _problem_keys(report: VerificationReport) -> tuple[Counter, Counter]:
    """(계약 오류, 사람 확인 이슈)를 (코드, 위치)별로 셉니다. 표시만 하는 notes는 세지 않음."""
    candidate = report.candidate
    errors = Counter((error.partition(" ")[0], _location(candidate, error.partition(" ")[2])) for error in report.contract_errors)
    issues = Counter((issue["code"], _location(candidate, issue["target"])) for issue in candidate["quality"]["reviewIssues"])
    return errors, issues


def _improved(first: VerificationReport, second: VerificationReport, sections: list[str]) -> bool:
    """채택 조건: 어느 (코드, 위치)도 새로 생기거나 늘지 않고, 다시 본 섹션의 문제가 하나 이상 줄었을 때.
    개수만 비교하면 한 항목을 고치며 다른 항목에 새 문제를 만든 응답도 채택될 수 있습니다."""
    resolved = False
    for before, after in zip(_problem_keys(first), _problem_keys(second), strict=True):
        if after - before:  # 처음에 없던(또는 더 많아진) 문제
            return False
        resolved = resolved or any(location[0] in sections for _, location in before - after)
    return resolved


def _meaning(node: Any) -> Any:
    """자격 조건의 뜻만 남깁니다(설명·인용 참조 제외). ALL·ANY 자식 순서는 뜻이 아니므로 정렬합니다."""
    if isinstance(node, dict):
        out = {k: _meaning(v) for k, v in node.items() if k not in TEXT_KEYS}
        if out.get("kind") in ("ALL", "ANY") and isinstance(out.get("children"), list):
            out["children"] = sorted(out["children"], key=lambda c: json.dumps(c, ensure_ascii=False, sort_keys=True))
        if isinstance(out.get("scope"), dict):
            out["scope"] = {k: v for k, v in out["scope"].items() if k != "resolution"}
        return out
    if isinstance(node, list):
        return [_meaning(x) for x in node]
    return node


def _keep_meaning(parts: list[dict[str, Any]], answer: dict[str, Any], targets: list[dict[str, Any]]) -> dict[str, Any]:
    """근거 문제만 보낸(또는 보내지 않은) 자격 조건인데 응답에서 뜻(대상·종류·조건식·예외)이 바뀌었으면 그 항목을 응답에서 뺍니다.
    빼면 합칠 때 처음 항목이 그대로 남고(이슈도 남아 사람이 확인), 다른 항목의 수정은 같은 채택 기준으로 따로 판단됩니다.
    값·조건 문제(VALUE_NOT_IN_QUOTE, 계약 오류 등)를 보낸 항목은 뜻을 고치는 것이 목적이므로 여기서 막지 않습니다."""
    index = _part_with(parts, "eligibility")
    if index is None or not isinstance(answer.get("eligibility"), list):
        return answer
    original = {item.get("id"): item for item in parts[index]["eligibility"] if isinstance(item, dict)}
    sent = {t["id"]: t["problems"] for t in targets if t["section"] == "eligibility" and t["id"] is not None}
    # 보내지 않은 항목을 돌려준 경우도 뜻은 바뀌면 안 됩니다(맡기지 않은 항목)
    evidence_only = {item_id for item_id in original if all(p.split(" ", 1)[0].rstrip(":") in EVIDENCE_ONLY_CODES for p in sent.get(item_id, []))}
    kept = [
        item
        for item in answer["eligibility"]
        if not (isinstance(item, dict) and item.get("id") in evidence_only and item.get("id") in original and _meaning(item) != _meaning(original[item["id"]]))
    ]
    return answer if len(kept) == len(answer["eligibility"]) else {**answer, "eligibility": kept}


def _with_quotes(item: Any, quotes: dict[str, str]) -> Any:
    """AI에게 다시 보여 줄 때 인용 참조 대신 인용문을 그대로 넣습니다."""
    if isinstance(item, dict):
        return {k: _with_quotes(v, quotes) for k, v in item.items() if k != "quoteRefs"} | (
            {"quotes": [quotes.get(r, r) for r in item["quoteRefs"]]} if "quoteRefs" in item else {}
        )
    if isinstance(item, list):
        return [_with_quotes(x, quotes) for x in item]
    return item


def _rename_refs(node: Any) -> Any:
    if isinstance(node, dict):
        return {k: ([REF_PREFIX + r for r in v] if k == "quoteRefs" else _rename_refs(v)) for k, v in node.items()}
    if isinstance(node, list):
        return [_rename_refs(x) for x in node]
    return node


def _part_with(parts: list[dict[str, Any]], section: str) -> int | None:
    return next((i for i, p in enumerate(parts) if isinstance(p, dict) and section in p), None)


def sent_scope(targets: list[dict[str, Any]]) -> dict[str, Any]:
    """재확인에 보낸 범위: 목록 섹션은 보낸 항목 id, 공고 기본 정보는 문제가 난 칸 이름(칸을 정할 수 없으면 None = 전체)."""
    items: dict[str, set[str]] = {}
    notice_fields: set[str] | None = set()
    for target in targets:
        if target["section"] == "notice":
            for problem in target["problems"]:
                match = NOTICE_FIELD.search(problem)
                if match is None or match[1] is None:
                    notice_fields = None
                elif notice_fields is not None:
                    notice_fields.add(match[1])
        elif target["id"] is not None:
            items.setdefault(target["section"], set()).add(target["id"])
    return {"items": items, "notice": notice_fields if any(t["section"] == "notice" for t in targets) else set()}


def merge_recheck(
    parts: list[dict[str, Any]], recheck_part: dict[str, Any], add_sections: tuple[str, ...] = (), sent: dict[str, Any] | None = None
) -> list[dict[str, Any]]:
    """고친 항목만 같은 섹션·같은 id 자리에 바꿔 넣습니다. 처음에 없던 id는 섹션 전체를 다시 본 경우(add_sections)에만 뒤에 붙입니다.
    그 섹션의 범위 상태는 새 항목을 하나라도 받았을 때만 재확인 응답 것으로 바꿉니다(AI가 "다 넣었다"고 적기만 해서 통과되지 않게).
    sent(sent_scope): 보낸 항목만 바꾸고, 공고 기본 정보는 문제가 난 칸만 바꿉니다(2026-10-21 검토: 보내지 않은 일정·가격이 응답에 섞여
    다른 값으로 바뀌어도 메모만 생기면 채택될 수 있었음). None이면 응답의 모든 항목을 받습니다(이전 동작)."""
    merged = copy.deepcopy(parts)
    quotes = [{**q, "ref": REF_PREFIX + q["ref"]} for q in recheck_part.get("quotes", [])]
    touched: set[int] = set()
    for section in SECTIONS:
        if section not in recheck_part:
            continue
        index = _part_with(merged, section)
        if index is None:
            continue
        target = merged[index]
        if section == "notice":
            if isinstance(recheck_part["notice"], dict):
                answer = _rename_refs(recheck_part["notice"])
                fields = None if sent is None else sent["notice"]
                if fields is None:
                    target["notice"] = answer
                else:  # 문제가 난 칸(과 그 근거 참조)만
                    keep = {**target["notice"], **{k: v for k, v in answer.items() if k in fields or k == "quoteRefs"}}
                    if fields:
                        target["notice"] = keep
                touched.add(index)
            continue
        allowed = None if sent is None else sent["items"].get(section, set())
        fixed = {
            item["id"]: _rename_refs(item)
            for item in recheck_part[section]
            if isinstance(item, dict) and "id" in item and (allowed is None or item["id"] in allowed or section in add_sections)
        }
        replace = fixed if allowed is None else {k: v for k, v in fixed.items() if k in allowed}
        if replace:
            target[section] = [replace.get(item["id"], item) for item in target[section]]
            touched.add(index)
        if section in add_sections:
            existing = {item.get("id") for item in target[section] if isinstance(item, dict)}
            added = [item for item_id, item in fixed.items() if item_id not in existing]
            if added:
                target[section] = [*target[section], *added]
                new_coverage = (recheck_part.get("coverage") or {}).get(section)
                if isinstance(target.get("coverage"), dict) and isinstance(new_coverage, dict):
                    target["coverage"][section] = new_coverage
                touched.add(index)
    for index in touched:
        merged[index]["quotes"] = [*merged[index].get("quotes", []), *quotes]
    return merged


def recheck_problems(
    rules: FieldRules,
    material: ExtractionMaterial,
    model: ExtractionModel,
    parts: list[dict[str, Any]],
    evaluate: Callable[[list[dict[str, Any]]], VerificationReport],
    **run_options: Any,
) -> RecheckResult:
    """evaluate: 응답 조각들 → 결합·검증 결과. 처음 결과와 재확인 결과를 같은 함수로 판단합니다."""
    first = evaluate(parts)
    problems = problem_items(first)
    # 근거를 못 찾아 아예 빠진 항목도 섹션과 상관없이 다시 봅니다 (가격·공급 같은 필수 데이터가 조용히 빠지지 않게).
    # 빠지면 이슈가 목록 전체를 가리켜 위에서 찾을 수 없습니다. 자격 조건을 먼저 봅니다.
    for section in ("eligibility", *[s for s in LIST_SECTIONS if s != "eligibility"]):
        index = _part_with(parts, section)
        if index is None or not isinstance(parts[index].get(section), list):
            continue
        kept = {item["id"] for item in first.candidate[section]}
        for item in parts[index][section]:
            if isinstance(item, dict) and item.get("id") not in kept:
                problems.setdefault((section, item.get("id")), []).append(DROPPED_PROBLEM)

    targets, sections, used = [], [], 0
    # 섹션 전체 문제(빠진 항목 추가)를 먼저 둡니다. 항목 문제가 많아도 상한(MAX_ITEMS)에 밀려 빠지지 않게
    ordered = sorted(problems.items(), key=lambda kv: kv[0][1] is not None or kv[0][0] == "notice")
    for (section, item_id), descriptions in ordered:
        if len(targets) >= MAX_ITEMS:
            break
        index = _part_with(parts, section)
        if index is None:
            continue
        part = parts[index]
        quotes = {q["ref"]: q["text"] for q in part.get("quotes", []) if isinstance(q, dict)}
        if section == "notice":
            item = part["notice"]
        elif item_id is None:  # 섹션 전체: 지금 있는 항목 id와 AI가 적은 범위 사유만 보냅니다(목록 전체를 다시 보내지 않음, 비용)
            item = {"existingIds": [x.get("id") for x in part[section] if isinstance(x, dict)], "coverage": (part.get("coverage") or {}).get(section)}
        else:
            item = next((x for x in part[section] if isinstance(x, dict) and x.get("id") == item_id), None)
            if item is None:
                continue
        target = {"section": section, "id": item_id, "item": _with_quotes(item, quotes), "problems": descriptions}
        size = len(json.dumps(target, ensure_ascii=False))
        if used + size > MAX_TARGET_CHARS:
            continue  # 상한을 넘으면 이 항목은 이번에 보내지 않습니다(검수 이슈로 그대로 남음). 더 작은 다음 항목은 봅니다
        used += size
        targets.append(target)
        if section not in sections:
            sections.append(section)
    if not targets:
        return RecheckResult(parts, first, [], False, None)

    call = CallSpec(
        name="recheck",
        sections=tuple(s for s in SECTIONS if s in sections),
        instructions=INSTRUCTIONS
        + "\n\n# 다시 확인할 항목\n"
        + json.dumps(targets, ensure_ascii=False, indent=1),  # 줄 단위로(한 줄이 너무 길면 모델이 읽지 못함)
        uses_structure=True,
        max_output_tokens=48_000,  # 실측: 큰 공고(인천 4358)에서 24,000토큰으로 잘림(내용 6.7만 자, 반복 아님). 정상 종료 최대 14,261
    )
    one_call = FieldRules(rules.field, rules.prompt_version, rules.prompt_path, (call,))
    run = run_extraction(one_call, material, model, structure=structure_summary(parts[0]), **run_options)
    rechecked = [f"{t['section']}/{t['id']}" if t["id"] else t["section"] for t in targets]
    if run.failed_calls:
        return RecheckResult(parts, first, rechecked, False, run)
    # 수정 호출 뒤에도 형식 위반이 남은 응답(extract가 첫 응답을 살려 넘김)은 고친 결과가 아니므로 섞지 않습니다(검토 2026-10-16)
    schema = one_call.build_request(call, material, structure_summary(parts[0])).output_schema
    if schema_errors(run.parts[0], schema) is not None:
        return RecheckResult(parts, first, rechecked, False, run)
    answer = _keep_meaning(parts, run.parts[0], targets)
    whole = tuple(t["section"] for t in targets if t["id"] is None and t["section"] != "notice")
    merged = merge_recheck(parts, answer, whole, sent_scope(targets))
    second = evaluate(merged)
    if _improved(first, second, sections):
        return RecheckResult(merged, second, rechecked, True, run)
    return RecheckResult(parts, first, rechecked, False, run)
