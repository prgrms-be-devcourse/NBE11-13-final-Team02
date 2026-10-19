"""항목 격리 (2026-10-16 결정): AI 재확인 뒤에도 남은 계약 오류를 오류 종류와 상관없이 위치만 보고 처리합니다.

새 공고에서 처음 보는 오류가 나와도 코드를 고치지 않고, 문제 항목만 낮추고 나머지는 그대로 내보내기 위한 단계입니다.
  /eligibility/i...                  자격 조건: 조건식을 내리고 원문만 남김(자동 판정 안 함) + 사람 확인 이슈. 그래도 오류면 조건을 뺌
  /counts/<id>                       수량 묶음을 풂(값은 그대로)
  /quality/missingFields·notes/i     그 기록을 지움
  /quality/coverage/<섹션>           내용과 맞게 PARTIAL로
  그 밖의 섹션 /<섹션>/i/...          가장 안쪽 항목(가격·수량·일정·서류 등)만 빼고 notes에 남김
  /notice/...(수량 제외)·/evidence    고칠 수 없음 → 그대로 둠 (발행 단계가 상세 없이 검수로 보냄)
없는 위치·공급·근거를 가리키는 참조 오류는 항목을 지우지 않고 참조만 정리합니다(_repair_references).
오류가 사라지거나 더 바꿀 것이 없을 때까지 최대 MAX_ROUNDS번 반복합니다(항목을 빼면 그 항목을 가리키던 다른 항목에서 새 오류가 날 수 있음).
"""

from __future__ import annotations

import copy
from typing import Any

from mujuthaek_extraction.assemble.coverage import DROPPED_BY_CONTRACT, FIXED_BY_CHECK
from mujuthaek_extraction.contracts import tools

SCHEMA = "schemas/extracted-notice.v1.json"
MAX_ROUNDS = 5
CRITICAL = {"notice", "eligibility"}
REFERENCE_ERRORS = {"DANGLING_LOCATION", "DANGLING_SCOPE", "DANGLING_EVIDENCE", "INVALID_LOCATION_PARENT", "SCOPE_LOCATION_CONFLICT"}
RECORD_ARRAYS = {"locations", "schedules", "supplyGroups", "eligibility", "selectionRules", "requiredDocuments", "guidance", "counts", "prices"}
UNVERIFIED_RULE = {"status": "UNSTRUCTURED", "expression": None, "reason": "조건식이 계약 검사를 통과하지 못해 원문만 남겼습니다. 검수에서 확인합니다."}
LABEL_KEYS = ("name", "title", "complexName", "unitLabel", "rawLabel", "originalText", "description", "id")


def _tokens(pointer: str) -> list[str | int]:
    return [int(t) if t.isdigit() else t for t in pointer.lstrip("/").split("/")] if pointer.strip("/") else []


def _pointer(tokens: list[str | int]) -> str:
    return "/" + "/".join(str(t) for t in tokens)


def _get(root: Any, tokens: list[str | int]) -> Any:
    for token in tokens:
        root = root[token]
    return root


def _label(node: Any) -> str:
    if isinstance(node, dict):
        for key in LABEL_KEYS:
            if isinstance(node.get(key), str) and node[key].strip():
                return " ".join(node[key].split())[:40]
    return "이름 없는 항목"


def _counts(candidate: dict[str, Any]) -> dict[str, dict[str, Any]]:
    found = {c["id"]: c for c in candidate["notice"].get("counts") or [] if isinstance(c, dict)}
    for group in candidate["supplyGroups"]:
        found.update({c["id"]: c for c in group.get("counts") or [] if isinstance(c, dict)})
    return found


def _remove(candidate: dict[str, Any], tokens: list[str | int]) -> None:
    """배열 원소를 빼고, quality의 위치 기록을 빠진 원소 기준으로 고칩니다."""
    array, index, depth = _get(candidate, tokens[:-1]), tokens[-1], len(tokens) - 1
    del array[index]
    quality = candidate["quality"]
    for collection in ("missingFields", "reviewIssues", "notes"):
        kept = []
        for entry in quality[collection]:
            target = _tokens(entry["target"])
            if target[: len(tokens)] == tokens:  # 빠진 원소 안을 가리킴
                if collection == "missingFields":
                    continue
                entry = {**entry, "target": _pointer(tokens[:-1])}
            elif target[:depth] == tokens[:-1] and len(target) > depth and isinstance(target[depth], int) and target[depth] > index:
                entry = {**entry, "target": _pointer([*tokens[:-1], target[depth] - 1, *target[depth + 1 :]])}
            kept.append(entry)
        quality[collection] = kept


def _deepest_record(tokens: list[str | int]) -> list[str | int] | None:
    for end in range(len(tokens), 1, -1):
        if isinstance(tokens[end - 1], int) and tokens[end - 2] in RECORD_ARRAYS:
            return tokens[:end]
    return None


def _nodes(value: Any):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from _nodes(child)
    elif isinstance(value, list):
        for child in value:
            yield from _nodes(child)


def _repair_references(result: dict[str, Any]) -> list[str]:
    """없는 위치·공급·근거를 가리키는 참조만 정리합니다. 항목은 지우지 않습니다.
    실측: 단지 항목 하나가 빠지자 그 단지를 가리키던 공급 항목 5개(가격 전부)가 연쇄로 지워짐(익산인화 행복주택 재생)."""
    locations = {x["id"] for x in result["locations"]}
    groups = {x["id"] for x in result["supplyGroups"]}
    evidence = {x["id"] for x in result["evidence"]}
    actions = []
    for section in ("notice", "locations", "schedules", "supplyGroups", "eligibility", "selectionRules", "requiredDocuments", "guidance"):
        for node in _nodes(result[section]):
            for key in ("locationId", "parentLocationId"):
                if node.get(key) is not None and node[key] not in locations:
                    actions.append(f"{section}: 없는 위치 {node[key]} 참조를 비웠습니다")
                    node[key] = None
            scope = node.get("scope")
            if isinstance(scope, dict):
                kept_locations = [x for x in scope["locationIds"] if x in locations]
                kept_groups = [x for x in scope["supplyGroupIds"] if x in groups]
                if (kept_locations, kept_groups) != (scope["locationIds"], scope["supplyGroupIds"]):
                    scope.update(locationIds=kept_locations, supplyGroupIds=kept_groups)
                    level_empty = (scope["level"] == "LOCATION" and not kept_locations) or (scope["level"] == "SUPPLY_GROUP" and not kept_groups)
                    if level_empty:  # 가리킬 대상이 없어졌으면 공고 전체 범위로 넓히고 확인 필요로 둡니다
                        scope.update(level="NOTICE", locationIds=[], supplyGroupIds=[], resolution="NEEDS_REVIEW")
                    actions.append(f"{section}: 없는 위치·공급을 범위에서 뺐습니다")
            ids = node.get("evidenceIds")
            kept = [i for i in ids if i in evidence] if isinstance(ids, list) else []
            if kept and kept != ids:  # 남는 근거가 없으면 그대로 두어 항목 빼기로 처리합니다
                node["evidenceIds"] = kept
                actions.append(f"{section}: 없는 근거 참조를 뺐습니다")
    return actions


def quarantine(candidate: dict[str, Any], errors: list[str]) -> tuple[dict[str, Any], list[str]]:
    """계약 오류가 남은 후보 → 문제 항목만 낮춘 새 후보와 한 일 목록. 입력 후보는 바꾸지 않습니다."""
    result = copy.deepcopy(candidate)
    actions: list[str] = []
    downgraded: set[str] = set()
    if any(e.split(" ", 1)[0] in REFERENCE_ERRORS for e in errors):
        actions += _repair_references(result)
        errors = tools().validate(result, SCHEMA)
    for _ in range(MAX_ROUNDS):
        if not errors or any(e.startswith("SCHEMA") for e in errors):
            break
        changed = False
        removals: dict[str, tuple[list[str | int], list[str]]] = {}
        quality = result["quality"]
        for error in errors:
            code, _, pointer = error.partition(" ")
            tokens = _tokens(pointer)
            if not tokens:
                continue
            head = tokens[0]
            if head == "counts" and len(tokens) == 2:
                count = _counts(result).get(str(tokens[1]))
                if count and (count.get("parentCountId") or count.get("aggregationComplete") or count.get("aggregationRole") != "STANDALONE"):
                    count.update(parentCountId=None, aggregationRole="STANDALONE", aggregationComplete=False)
                    actions.append(f"{code} {pointer}: 수량 묶음을 풀었습니다")
                    changed = True
            elif head == "quality" and len(tokens) >= 3 and tokens[1] in ("missingFields", "notes", "reviewIssues") and isinstance(tokens[2], int):
                removals.setdefault(_pointer(tokens[:3]), (tokens[:3], []))[1].append(code)
            elif head == "quality" and len(tokens) == 3 and tokens[1] == "coverage":
                entry = quality["coverage"].get(str(tokens[2]))
                if entry and entry["status"] != "PARTIAL":
                    quality["coverage"][str(tokens[2])] = {"status": "PARTIAL", "reason": FIXED_BY_CHECK}
                    actions.append(f"{code} {pointer}: 범위 상태를 PARTIAL로")
                    changed = True
            elif head == "eligibility" and len(tokens) >= 2 and isinstance(tokens[1], int) and tokens[1] < len(result["eligibility"]):
                item = result["eligibility"][tokens[1]]
                if item["id"] in downgraded:  # 조건식을 내려도 오류: 조건을 뺍니다
                    removals.setdefault(_pointer(tokens[:2]), (tokens[:2], []))[1].append(code)
                    continue
                downgraded.add(item["id"])
                item.update(rule=dict(UNVERIFIED_RULE), exceptions=[], exceptionsStatus="NOT_REVIEWED")
                description = f"{_label(item)}: {code} 오류로 조건식을 내리고 원문만 남겼습니다"
                quality["reviewIssues"].append({"code": "ITEM_QUARANTINED", "target": _pointer(tokens[:2]), "description": description})
                actions.append(f"{code} {pointer}: 조건식을 내렸습니다")
                changed = True
            elif head not in ("notice", "evidence", "quality") or (head == "notice" and "counts" in tokens):
                record = _deepest_record(tokens)
                if record:
                    removals.setdefault(_pointer(record), (record, []))[1].append(code)
        # 같은 배열에서는 뒤 원소부터 빼야 앞 원소 위치가 밀리지 않습니다
        for pointer, (tokens, codes) in sorted(removals.items(), key=lambda kv: (len(kv[1][0]), kv[1][0][-1]), reverse=True):
            try:
                node = _get(result, tokens)
            except (KeyError, IndexError, TypeError):
                continue
            section = str(tokens[0])
            _remove(result, tokens)
            changed = True
            actions.append(f"{', '.join(sorted(set(codes)))} {pointer}: 항목을 뺐습니다")
            if section == "quality":
                continue
            reason = ", ".join(sorted(set(codes)))
            entry = {"code": "ITEM_QUARANTINED", "target": _pointer(tokens[:-1]), "description": f"{_label(node)}: {reason} 오류로 항목을 뺐습니다"}
            quality["reviewIssues" if section in CRITICAL else "notes"].append(entry)
            if section in result["quality"]["coverage"] and result["quality"]["coverage"][section]["status"] == "EXTRACTED":
                result["quality"]["coverage"][section] = {"status": "PARTIAL", "reason": DROPPED_BY_CONTRACT}
        if not changed:
            break
        errors = tools().validate(result, SCHEMA)
    return result, actions
