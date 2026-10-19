"""분야 규칙에 따라 모델을 섹션별로 호출하고 AI 출력 조각을 모읍니다 (결합 전 단계).

- 일시 오류: 제한 재시도(지수 지연). 대기는 주입한 sleep으로 하므로 테스트에서 실제로 기다리지 않습니다.
- JSON으로 읽을 수 없는 응답: 오류를 알려 1회 재요청(결정 E7). 출력 한도로 잘린 응답·거부는 재요청하지 않습니다.
- 실패한 호출은 맡은 섹션을 FAILED로 보고하는 빈 조각으로 남겨, 다른 섹션 결과를 보존합니다.
- 구조 호출(첫 호출)이 실패하면 뒤 호출은 실행하지 않습니다(참조할 ID가 없고 비용만 듭니다).
- 응답 원문·사용량은 job 기록용으로 돌려주며 공고 계약에는 넣지 않습니다(2-10 비용 집계 입력).
"""

from __future__ import annotations

import json
import os
import re
from collections.abc import Callable
from dataclasses import dataclass, field, replace
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker

from mujuthaek_extraction import supply_tables
from mujuthaek_extraction.assemble.ai_output import SECTIONS
from mujuthaek_extraction.assemble.slips import fix_format_slips
from mujuthaek_extraction.intake.model import ExtractionMaterial
from mujuthaek_extraction.model.base import ExtractionModel, ModelError, ModelRequest, StopReason
from mujuthaek_extraction.rules.base import CallSpec, FieldRules, call_schema, document_ids, structure_summary


@dataclass(frozen=True)
class RetryPolicy:
    max_attempts: int = 3  # 일시 오류 포함 한 요청의 최대 시도 횟수
    base_delay_seconds: float = 2.0
    json_repairs: int = 1
    # 형식이 틀린 항목이 이보다 적으면(그리고 틀린 곳이 모두 섹션 항목 안이면) 다시 요청하지 않고 결합기가 그 항목만 뺍니다.
    # 다시 요청은 원문 전체를 또 보내 비용이 큽니다(실측 OpenAI: 9회 중 2회). 1이면 이전처럼 늘 다시 요청(설정 EXTRACTION_REPAIR_MIN_BAD_ITEMS)
    repair_min_bad_items: int = 1
    # 출력 한도로 잘린 응답은 같은 요청을 이만큼 다시 보냅니다. 실측 OpenAI LH 김제(19412): 서류 호출이 표 행 인용 뒤
    # 빈 칸 「|  |」을 3만 자 넘게 반복하다 24,000토큰에서 잘려 서류 34개가 모두 빠짐(원문 그 행의 빈 칸은 1개). 반복은 우연이라 다시 받으면 대개 정상입니다
    truncation_retries: int = 1


DEFAULT_POLICY = RetryPolicy(repair_min_bad_items=int(os.getenv("EXTRACTION_REPAIR_MIN_BAD_ITEMS", "3")))


def bad_items(part: Any, schema: dict[str, Any]) -> int | None:
    """형식이 틀린 섹션 항목 수. 섹션 항목 밖(맨 위·인용·coverage 등)이 틀렸으면 None(다시 요청해야 함)."""
    found: set[tuple[str, int]] = set()
    for error in Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(part):
        path = list(error.absolute_path)
        if len(path) < 2 or path[0] not in SECTIONS or path[0] == "notice" or not isinstance(path[1], int):
            return None
        found.add((path[0], path[1]))
    return len(found)


@dataclass(frozen=True)
class CallAttempt:
    call_name: str
    prompt_version: str
    repair: bool
    model_id: str | None
    stop_reason: str | None
    input_tokens: int | None
    output_tokens: int | None
    cached_input_tokens: int | None
    error: str | None
    cache_write_tokens: int | None = None


@dataclass
class ExtractionRun:
    parts: list[dict[str, Any]] = field(default_factory=list)
    attempts: list[CallAttempt] = field(default_factory=list)
    failed_calls: list[tuple[str, str]] = field(default_factory=list)
    raw_responses: list[tuple[str, str]] = field(default_factory=list)


def _failed_part(call: CallSpec, reason: str) -> dict[str, Any]:
    return {"quotes": [], "coverage": {section: {"status": "FAILED", "reason": reason} for section in call.sections}}


def _parse(raw: str) -> Any:
    text = raw.strip()
    if text.startswith("```"):  # 지시를 어기고 코드 블록으로 감싼 경우만 벗깁니다
        text = text.split("\n", 1)[1] if "\n" in text else ""
        text = text.rsplit("```", 1)[0]
    value = json.loads(text)
    if not isinstance(value, dict):
        raise ValueError("JSON 객체가 아닙니다")
    return value


def schema_errors(part: Any, schema: dict[str, Any], limit: int = 8) -> str | None:
    """원래 계약 규칙(조건부·형식 포함)으로 검사합니다. 제공자의 엄격 모드는 이 규칙 일부를 빼고 보내므로 여기서 다시 확인합니다."""
    errors = sorted(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(part), key=lambda e: list(map(str, e.absolute_path)))
    if not errors:
        return None
    lines = [f"/{'/'.join(map(str, e.absolute_path))}: {e.message[:160]}" for e in errors[:limit]]
    more = f" 외 {len(errors) - limit}건" if len(errors) > limit else ""
    return f"형식 규칙 위반 {len(errors)}건{more}\n" + "\n".join(lines)


def run_extraction(
    rules: FieldRules,
    material: ExtractionMaterial,
    model: ExtractionModel,
    *,
    policy: RetryPolicy = DEFAULT_POLICY,
    sleep: Callable[[float], None] = lambda seconds: None,
    budget: Callable[[ModelRequest], bool] | None = None,
    structure: dict[str, Any] | None = None,
) -> ExtractionRun:
    """structure: 이미 추출한 구조 요약(재확인 호출처럼 구조 호출 없이 부를 때). 없으면 첫 호출 응답에서 만듭니다."""
    run = ExtractionRun()

    def call_model(request: ModelRequest, repair: bool):
        """요청을 보내고 (응답, None) 또는 (None, 실패 사유)를 돌려줍니다. 일시 오류만 지수 지연으로 다시 시도하고, 시도마다 기록합니다."""
        for attempt in range(1, policy.max_attempts + 1):
            try:
                response = model.complete(request)
            except ModelError as error:
                run.attempts.append(CallAttempt(request.call_name, request.prompt_version, repair, None, None, None, None, None, str(error)))
                if not error.transient or attempt == policy.max_attempts:
                    return None, f"모델 호출 실패: {error}"
                sleep(policy.base_delay_seconds * 2 ** (attempt - 1))
                continue
            run.attempts.append(
                CallAttempt(
                    request.call_name,
                    request.prompt_version,
                    repair,
                    response.model_id,
                    response.stop_reason.value,
                    response.input_tokens,
                    response.output_tokens,
                    response.cached_input_tokens,
                    None,
                    response.cache_write_tokens,
                )
            )
            run.raw_responses.append((request.call_name, response.raw_text))
            return response, None
        return None, "모델 호출 실패"

    calls = list(rules.calls_for(material))
    # 구조 방식: 공급·가격 표는 코드가 옮기고(supply_tables) 구조 호출은 공고·위치만 맡습니다. 열 의미를 못 받으면 이전처럼 구조 호출이 공급까지
    table_part = None
    if rules.supply_tables and structure is None and calls and material.pdf_pages:
        table_part = _supply_from_tables(rules, material, calls[0], call_model, budget)
        if table_part is not None:
            first = calls[0]
            sections = tuple(s for s in first.sections if s != "supplyGroups")
            note = supply_tables.structure_note(table_part)
            calls[0] = replace(first, sections=sections, instructions=f"{first.instructions}\n{note}")
    for number, call in enumerate(calls):
        if number > 0 and structure is None:
            reason = "구조 호출이 실패해 실행하지 않았습니다."
            run.failed_calls.append((call.name, reason))
            run.parts.append(_failed_part(call, reason))
            continue
        request = rules.build_request(call, material, structure)
        if budget is not None and not budget(request):
            reason = "추출 예산 한도로 호출하지 않았습니다."
            run.failed_calls.append((call.name, reason))
            run.parts.append(_failed_part(call, reason))
            continue

        part, reason, repairs, truncations = None, None, 0, 0
        unrepaired = None  # 형식 위반이 있는 첫 응답. 수정 호출이 실패하면(예산 한도 등) 이것을 넘깁니다
        while part is None and reason is None:
            response, reason = call_model(request, repair=repairs > 0)
            if response is None:
                break
            if response.stop_reason is StopReason.MAX_TOKENS:
                if truncations < policy.truncation_retries and (budget is None or budget(request)):
                    truncations += 1
                    continue
                reason = "출력 한도로 응답이 잘렸습니다."
            elif response.stop_reason is StopReason.REFUSED:
                reason = "모델이 응답을 거부했습니다."
            else:
                try:
                    part = fix_format_slips(_parse(response.raw_text))
                    problem = schema_errors(part, request.output_schema)
                except ValueError as error:  # json.JSONDecodeError 포함
                    part, problem = None, f"JSON으로 읽을 수 없는 응답입니다: {str(error)[:200]}"
                if problem is not None:
                    few = part is not None and (count := bad_items(part, request.output_schema)) is not None and count < policy.repair_min_bad_items
                    if repairs >= policy.json_repairs or few:
                        if part is None:
                            reason = problem
                        # 형식 위반이 남은 조각은 그대로 넘기고, 결합기가 위반한 섹션만 FAILED로 처리합니다
                    else:
                        repairs += 1
                        unrepaired = unrepaired if part is None else part
                        part = None
                        request = rules.build_request(call, material, structure, repair_error=problem)

        if part is None and unrepaired is not None:
            # 수정 호출이 어떤 이유로든(예산 차단·출력 한도로 잘림·거부·호출 실패) 답을 못 주면 첫 응답을 넘깁니다.
            # 실측(GH 800 유료): 감점 행 2개의 형식 위반 때문에 자격·선정·서류가 통째로 사라졌습니다.
            # 결합기가 위반한 섹션만 FAILED로 처리하므로 나머지 섹션은 살립니다.
            part, reason = unrepaired, None
        if part is None:
            run.failed_calls.append((call.name, reason))
            run.parts.append(_failed_part(call, reason))
            continue
        if number == 0 and structure is None and rules.continuation is not None and table_part is None:
            _continue_supply(rules, material, part, call_model, budget)
        run.parts.append(part)
        if number == 0 and structure is None:
            if table_part is not None:
                supply_tables.link_locations(table_part, part.get("locations") or [])
                run.parts.append(table_part)
                structure = structure_summary({**part, "supplyGroups": table_part["supplyGroups"]})
            else:
                structure = structure_summary(part)
    return run


def _supply_from_tables(rules: FieldRules, material: ExtractionMaterial, first: CallSpec, call_model, budget) -> dict[str, Any] | None:
    """공급표 열 의미를 AI에 묻고 코드가 행을 옮긴 공급 조각. 표가 없거나 답이 형식에 맞지 않으면 None(이전 방식으로)."""
    tables = supply_tables.candidates(material)
    if not tables:
        return None
    request = supply_tables.mapping_request(rules.build_request(first, material), tables)
    if budget is not None and not budget(request):
        return None
    response, _ = call_model(request, repair=False)
    if response is None or response.stop_reason is not StopReason.END:
        return None
    mapping = supply_tables.parse_mapping(response.raw_text)
    if mapping is None or schema_errors(mapping, supply_tables.MAPPING_SCHEMA) is not None:
        return None
    part = supply_tables.build_part(tables, mapping, material.text_document_id)
    if part is None:
        return None
    check = {**part, "supplyGroups": [{k: v for k, v in g.items() if k != "_complex"} for g in part["supplyGroups"]]}
    if schema_errors(check, call_schema(("supplyGroups",), document_ids(material))) is not None:
        return None
    return part


CONTINUE_MARK = "이어서:"  # 구조 응답이 공급 묶음을 다 담지 못했다는 표시 (coverage.supplyGroups.reason 머리)
MAX_CONTINUATIONS = 10  # 공고 하나의 비용 상한(BudgetedModel)이 먼저 막을 수 있습니다


def _wants_more(part: dict[str, Any]) -> bool:
    coverage = (part.get("coverage") or {}).get("supplyGroups") or {}
    return coverage.get("status") == "PARTIAL" and str(coverage.get("reason") or "").startswith(CONTINUE_MARK)


def _continue_supply(rules: FieldRules, material: ExtractionMaterial, part: dict[str, Any], call_model, budget) -> None:
    """호실이 많아 한 번의 출력 한도에 공급 묶음을 다 담지 못한 공고: 이어받기 호출로 나머지를 받아 같은 조각에 붙입니다.
    실측: 김해·양산 다세대 일반매각 153호 — 한 번에 담지 못해 첫 단지 3호만 넣고 끝남(나머지 150호 가격 누락)."""
    call = rules.continuation
    for round_number in range(1, MAX_CONTINUATIONS + 1):
        if not _wants_more(part):
            return
        request = rules.build_request(call, material, structure_summary(part))
        if budget is not None and not budget(request):
            return
        response, _ = call_model(request, repair=False)
        if response is None or response.stop_reason is not StopReason.END:
            return
        try:
            more = _parse(response.raw_text)
        except ValueError:
            return
        if schema_errors(more, request.output_schema) is not None:
            return
        _merge_more(part, more, round_number)


def _ids(node: Any) -> list[str]:
    if isinstance(node, dict):
        own = [node["id"]] if isinstance(node.get("id"), str) else []
        return own + [i for value in node.values() for i in _ids(value)]
    if isinstance(node, list):
        return [i for value in node for i in _ids(value)]
    return []


def _rename_clashing_ids(part: dict[str, Any], groups: list[Any], round_number: int) -> None:
    """이어받기 응답이 앞에서 쓴 ID(공급 묶음·가격·수량)를 다시 쓰면 새 ID로 바꿉니다.
    그대로 붙이면 계약의 ID 중복 검사에 걸려 격리 단계가 뒤쪽 호실과 그 가격을 메모만 남기고 지웁니다(검토 2026-10-16).
    이어받기 호출은 앞 묶음의 ID만 보고 그 안의 가격·수량 ID는 모르므로 겹치기 쉽습니다."""
    taken = set(_ids({k: v for k, v in part.items() if k != "quotes"}))
    rename: dict[str, str] = {}
    for old in _ids(groups):
        if old in taken or old in rename:
            new, n = f"{old}~m{round_number}", 2
            while new in taken:
                new, n = f"{old}~m{round_number}-{n}", n + 1
            rename[old] = new
            taken.add(new)
        else:
            taken.add(old)
    if not rename:
        return
    stack: list[Any] = [groups]
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            if node.get("id") in rename:
                node["id"] = rename[node["id"]]
            if node.get("parentCountId") in rename:
                node["parentCountId"] = rename[node["parentCountId"]]
            if isinstance(node.get("supplyGroupIds"), list):
                node["supplyGroupIds"] = [rename.get(i, i) for i in node["supplyGroupIds"]]
            stack.extend(node.values())
        elif isinstance(node, list):
            stack.extend(node)


def _merge_more(part: dict[str, Any], more: dict[str, Any], round_number: int) -> None:
    """이어받기 응답을 구조 조각에 붙입니다. 인용 ref와 겹치는 ID는 새 이름으로, 누락 기록 위치는 붙인 자리만큼 옮깁니다."""
    rename = {q["ref"]: f"m{round_number}-{q['ref']}" for q in more.get("quotes", [])}
    for quote in more.get("quotes", []):
        part.setdefault("quotes", []).append({**quote, "ref": rename[quote["ref"]]})
    groups = more.get("supplyGroups") or []
    stack: list[Any] = [groups]
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            if "quoteRefs" in node:
                node["quoteRefs"] = [rename.get(ref, ref) for ref in node["quoteRefs"]]
            stack.extend(node.values())
        elif isinstance(node, list):
            stack.extend(node)
    _rename_clashing_ids(part, groups, round_number)
    offset = len(part.setdefault("supplyGroups", []))
    part["supplyGroups"].extend(groups)
    for entry in more.get("missingFields", []):
        target = re.sub(r"^/supplyGroups/(\d+)", lambda m: f"/supplyGroups/{int(m.group(1)) + offset}", entry.get("target", ""))
        part.setdefault("missingFields", []).append({**entry, "target": target})
    coverage = (more.get("coverage") or {}).get("supplyGroups")
    if coverage:
        part.setdefault("coverage", {})["supplyGroups"] = coverage
