"""판정 틀 공통 뼈대: 대상군 나누기, 자격 조건식 → 틀 조건, 예외 → 가산·ANY_OF, 문장만 있는 항목 분류, 보류 → inReview.

분야별 차이(조건 종류 대응, 신분 코드, 문장 규칙)는 Spec으로 받습니다(housing.py, business_space.py).
"""

from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date
from typing import Any

from mujuthaek_extraction.judgment.common import (
    BENEFIT_LIST,
    LIMIT_WORDS,
    NOT_REQUIRED,
    PERMIT_WORDS,
    Context,
    ask,
    base_fields,
    group_id,
    note,
    reference_date,
    unsure,
)

Criterion = dict[str, Any]
Made = tuple[list[Criterion], list[dict[str, Any]]]


@dataclass(frozen=True)
class Spec:
    schema: str
    atom: Callable[[Context, dict[str, Any], dict[str, Any], str | None, date], Criterion | None]  # 원자 조건 → 틀 조건(못 옮기면 None)
    statuses_of: Callable[[str], list[str]]  # 대상 이름 전체가 신분이면 그 코드
    status_codes: Callable[[str], list[str]]  # 문장에 나오는 신분 코드
    status_rest: Callable[[str], str] = lambda text: text  # 신분 낱말을 뺀 나머지(비면 전체가 신분)
    text: Callable[[Context, dict[str, Any], str, list[str]], Made | None] = lambda *_: None  # 분야별 문장 규칙(못 정하면 None)
    absorb: Callable[[list[Criterion], dict[str, Any]], bool] = lambda *_: False  # 예외를 기본 조건에 흡수(소득 가산)
    # 예외로 바뀐 기준이 기본 기준의 가산으로 이미 같은 값이면 True(그 선택지를 만들지 않음, 주거: 맞벌이·가구원 수 소득)
    covered: Callable[[list[Criterion], dict[str, Any], list[Criterion]], bool] = lambda *_: False
    extra_reasons: Callable[[dict[str, Any], list[Criterion]], list[str]] = lambda *_: []
    merge: Callable[[list[Criterion]], list[Criterion]] = lambda criteria: criteria  # ALL 안 같은 종류 합치기
    simplify: Callable[[Criterion], Criterion] = lambda criterion: criterion  # ANY_OF 정리(사업공간 거주지·사업장 지역)
    preferences: bool = False  # 우대(preferences) 칸이 있는 틀
    # 닫힌 목록(2026-10-21 결정): 코드가 자동 판정하는 조건 종류. 그 밖의 종류는 원문 질문(ASK)으로 바꿉니다. None이면 제한 없음(사업공간)
    automatic: frozenset[str] | None = None
    # 두 방법 일치: 자동 판정 조건의 값을 코드가 원문에서 따로 읽어 다르면 이유(같으면 None). None이면 하지 않음
    readback: Callable[[Criterion, Any], str | None] | None = None
    # 신청 규칙(자격이 아님): 중복 신청·청약, 한 블록·단지·주택형만 신청(실측 2026-10-21 광주선운2·김해 매각이 질문으로 나감)
    rule_words: tuple[str, ...] = field(
        default=(r"중복\s*(?:으로\s*)?(?:신청|청약)", r"1\s*세대\s*1\s*주택", r"한\s*개\s*(?:블록|단지|주택형)만", r"1\s*개\s*(?:블록|단지)\s*내\s*1\s*개")
    )
    # 당첨·입주 후 의무: 「입주 전까지 증명」, 「신청자격은 입주 시까지 계속 유지해야」(실측 든든전세)
    # 「기존 임대주택을 명도하여야」「입주 전까지 상환하여야」「예비입주자 지위는 자동 상실」「재계약 거절」「자격을 … 유지하여야」
    # 「입주 전에 세대 분리를 해야」(2026-10-22 방향 미정 959건 분류: 자격으로 물으면 아직 하지 않은 사람이 떨어짐)
    obligation: str = (
        r"입주\s*(?:전|시)\s*까지.*(?:증명|제출)|(?:자격|요건)\S*\s*(?:을|은|는)?\s*[^.]{0,30}계속\s*유지"
        r"|(?:명도|상환|퇴거)\s*하?\s*여야|지위\S*\s*(?:는|은)?\s*(?:자동\s*)?(?:상실|탈락)|재계약\S*\s*거절|임대(?:료|보증금)\S*\s*할증"
        r"|유지\s*(?:하여|해)\s*야|입주\s*(?:전|시)\s*(?:에|까지)[^.]*(?:여|해)\s*야|입주\s*이후[^.]{0,40}(?:명도|퇴거|해지|상환|전입|거주)\S*\s*(?:하여|해)\s*야"
    )


def build_frame(candidate: dict[str, Any], regions: Any, spec: Spec, *, approved: bool = False) -> dict[str, Any] | None:
    ctx = Context(candidate, regions, approved=approved)
    when = reference_date(candidate, approved=approved)
    if when is None:
        return None
    # 대상군: 자격 항목이 실제로 가리키는 대상(계층·순위). 공고 대상 이름과 다를 수 있습니다(실측 LH 강원: 공고 대상 「저소득계층」,
    # 자격 항목은 「1순위 우선 수급자」「2순위」 — 예전엔 이름이 안 맞는 항목을 공통 조건에 넣어 소득 70%와 100%를 함께 요구함)
    # 보류한 공고 대상 이름은 쓰지 않습니다(자격 항목이 가리키는 대상만으로 나눔, 2026-10-21)
    held_names = unsure(candidate, "/notice/targetGroups", approved=approved)
    noticed = [] if held_names else list(dict.fromkeys(candidate["notice"].get("targetGroups") or []))
    referenced = list(dict.fromkeys(t for item in candidate["eligibility"] for t in item["scope"].get("targetGroups") or []))
    names = list(dict.fromkeys([*noticed, *referenced]))
    groups = [{"id": group_id(ctx, name), "name": name, "criteria": []} for name in names] or [{"id": "all", "name": "전체", "criteria": []}]
    by_name = {g["name"]: g for g in groups}
    common: list[Criterion] = []
    notes: list[dict[str, Any]] = []
    limited: list[tuple[dict[str, Any], list[str], list[Criterion]]] = []  # (한정 범위, 대상, 조건)
    open_names: set[str] = set()  # 주택형·단지 한정 없이 쓰인 대상 이름
    for index, item in enumerate(candidate["eligibility"]):
        made, item_notes = _from_item(ctx, spec, index, item, when)
        targets = [t for t in item["scope"].get("targetGroups") or [] if t in by_name]
        for n in item_notes:
            notes.append({**n, "groupId": by_name[targets[0]]["id"] if len(targets) == 1 else None})
        if not made:
            continue
        if item["scope"].get("level") in ("SUPPLY_GROUP", "LOCATION"):
            limited.append((item["scope"], targets, made))  # 아래에서 주택형·단지별 대상군으로 나눕니다
            continue
        open_names.update(targets)
        if not targets:
            common.extend(made)
            continue
        for target in targets:
            by_name[target]["criteria"].extend(ctx.copy(c, by_name[target]["id"]) if len(targets) > 1 else c for c in made)
    for group in groups:
        # 이름 전체가 신분이거나, 순위·소득 구간 말고는 신분 낱말뿐인 이름(「1순위 월평균소득 70% 이하 장애인」)만 신분 조건으로 씁니다.
        # 「장애인 등 주거약자」「국가유공자 등」처럼 신분 말고 다른 대상이 남으면 신분 하나로 좁히지 않고 대상 해당 여부를 묻습니다
        # (2026-10-21 리뷰: 주거약자 대상에 장애인만 요구). 「신혼부부·한부모가족」처럼 여러 대상을 묶은 이름도 한 신분만 요구하면 다른 대상이 떨어집니다
        codes = spec.statuses_of(group["name"])
        found = [] if codes or re.search(r"[·ㆍ,/]|및|또는", group["name"]) else spec.status_codes(group["name"])
        if found and not RANK_WORDS.sub("", spec.status_rest(group["name"])).strip():
            codes = found
        elif found and not any(c["kind"] in ("STATUS", "ASK") and str(c["id"]).endswith("-member") for c in group["criteria"]):
            group["criteria"].insert(
                0,
                {
                    **base_fields(ctx.new_id(f"{group['id']}-member"), "ASK", None),
                    "question": f"「{group['name']}」 대상에 해당하나요?",
                    "metWhen": "YES",
                },
            )
        if codes and not any(c["kind"] == "STATUS" for c in group["criteria"]):
            group["criteria"].insert(0, {**base_fields(ctx.new_id(f"{group['id']}-status"), "STATUS", None), "anyOf": codes})
        if referenced and group["name"] not in referenced and not group["criteria"]:
            # 자격 항목이 하나도 없는 공고 대상(상위 묶음 이름일 수 있음): 빼면 지원 불가, 그대로 두면 공통 조건만으로 지원 가능이 되어
            # 둘 다 틀릴 수 있으므로 대상 해당 여부를 묻고 확인 필요로 둡니다
            membership = {
                **base_fields(ctx.new_id(f"{group['id']}-member"), "ASK", None),
                "question": f"「{group['name']}」 대상에 해당하나요?",
                "metWhen": "YES",
            }
            ctx.flag(membership, ["이 대상의 자격 조건을 추출 결과에서 찾지 못했습니다"])
            group["criteria"].append(membership)
    preferences, selection = _selection(ctx, spec, candidate, by_name, when)
    notes = [*notes, *selection]
    if limited:
        groups = _split_by_scope(ctx, candidate, groups, limited, open_names)
        kept = {g["id"] for g in groups}
        for entry in [*notes, *preferences]:  # 빠진 대상군을 가리키던 안내·우대는 공고 전체로
            if entry.get("groupId") is not None and entry["groupId"] not in kept:
                entry["groupId"] = None
    groups = [{"id": g["id"], "name": g["name"], "rank": rank_of(g["name"]), "criteria": g["criteria"]} for g in groups]
    shared = {"criteria": common}
    for group in [shared, *groups]:
        _flag_list_items(ctx, group)
        group["criteria"] = _close(ctx, spec, group["criteria"], group.get("name"))
    common = shared["criteria"]
    frame: dict[str, Any] = {
        "schema": spec.schema,
        "rawPostId": candidate["input"]["rawPostId"],
        "rawVersion": candidate["input"]["rawVersion"],
        "referenceDate": when.isoformat(),
        "common": common,
        "groups": groups,
    }
    if spec.preferences:
        frame["preferences"] = preferences
    frame["notes"] = [*notes, *_guidance_notes(candidate)]
    return _strip_private(frame)


# 대상 이름에서 신분 말고 남아도 되는 말: 순위·목·군·공급 구분·소득 구간
RANK_WORDS = re.compile(
    r"\d\s*순위|[가-하]\s*목|[가-하]\s*군|[가-하]\s*[.)]|일반|우선|특별|공급|월\s*평균\s*소득\s*(?:의\s*)?\d+(?:\.\d+)?\s*%\s*(?:이하|이내|미만)?"
    r"|소득\s*\d+(?:\.\d+)?\s*%\s*(?:이하|이내|미만)?|\d+\s*형|[()\[\]]"
)
# 신청 순위: 「1순위」「제2순위」. 「1·2순위」「1~3순위」처럼 숫자가 여럿이면 한 순위로 정할 수 없어요
RANK = re.compile(r"제?\s*(\d+)((?:\s*(?:[·ㆍ,~∼\-]|및|또는)\s*\d+)*)\s*순위")


def rank_of(name: str) -> int | None:
    """대상군 이름의 신청 순위(1이 가장 앞). 순위가 없거나 여러 순위가 섞였으면 None (판정 틀 group.rank)."""
    found: set[int] = set()
    for match in RANK.finditer(name):
        if match.group(2):  # 「1·2순위」: 한 대상군에 순위가 여럿
            return None
        found.add(int(match.group(1)))
    return found.pop() if len(found) == 1 else None


# 원문 목록 표시: ㉮㉯ / ①② / 가. 나. / (가) (나) / ⓐⓑ
LIST_MARK = re.compile(
    r"^\s*[※*\-•·▪◾■□○◦]?\s*"
    r"(?:(?P<circled>[㉮-㉻])|(?P<number>[①-⑳])|(?P<letter>[가-하])\s*[.)]|\(\s*(?P<paren>[가-하])\s*\)|(?P<alpha>[ⓐ-ⓩ]))"
)


def _flag_list_items(ctx: Context, group: dict[str, Any]) -> None:
    """한 대상군에 원문 목록(㉮·㉯…)의 서로 다른 항목이 각각 조건으로 들어갔거나, 서로 다른 신분 조건이 둘 이상이면 확인 필요.
    목록은 「다음 중 하나」일 수도 「모두」일 수도 있어 코드로 정할 수 없습니다(2026-10-21 실측: 우선공급의 다자녀·국가유공자·수급자 항목이
    「고령자」 대상의 필수 조건으로 모두 들어가 아무도 자격이 안 됨)."""
    marked: dict[str, dict[str, list[Criterion]]] = {}
    for criterion in group["criteria"]:
        match = LIST_MARK.match(str(criterion.get("quote") or ""))
        if not match:
            continue
        family = next(name for name, value in match.groupdict().items() if value)
        marked.setdefault(family, {}).setdefault(match.group(family), []).append(criterion)
    for items in marked.values():
        if len(items) >= 2:
            for criteria in items.values():
                for criterion in criteria:
                    ctx.flag(criterion, ["원문 목록의 여러 항목이 각각 필요한 조건으로 들어갔습니다. 그중 하나만 갖추면 되는지 원문 확인이 필요합니다"])
    statuses = [c for c in group["criteria"] if c["kind"] == "STATUS"]
    if len(statuses) >= 2:
        for criterion in statuses:
            ctx.flag(criterion, ["한 대상에 서로 다른 신분 조건이 여럿 붙었습니다. 모두 필요한지 하나만 필요한지 원문 확인이 필요합니다"])


def _close(ctx: Context, spec: Spec, criteria: list[Criterion], group_name: str | None) -> list[Criterion]:
    """닫힌 목록과 두 방법 일치를 적용합니다(ANY_OF 안까지).
    - 자동 판정 종류가 아니면 원문 질문으로 바꿉니다(신분·혼인·청약통장·자녀 등). 확인 필요 표시와 이유는 그대로 둡니다.
    - 자동 판정 종류는 코드가 원문에서 읽은 값과 같아야 확정입니다. 검수자가 승인한 결과는 사람이 값을 확인했으므로 대조하지 않습니다."""
    out: list[Criterion] = []
    asked: set[str] = set()
    for criterion in criteria:
        if criterion["kind"] == "ANY_OF":
            for option in criterion["options"]:
                option["criteria"] = _close(ctx, spec, option["criteria"], group_name)
        elif spec.automatic is not None and criterion["kind"] not in spec.automatic and criterion["kind"] != "ASK":
            criterion = _as_question(criterion, group_name)
        elif spec.readback is not None and criterion["kind"] != "ASK" and not ctx.approved and not criterion["inReview"]:
            reason = spec.readback(criterion, ctx.regions)
            if reason:
                ctx.flag(criterion, [reason])
        if criterion["kind"] == "ASK":
            if criterion["question"] in asked:
                continue  # 같은 질문(대상 해당 여부 등)은 한 번만
            asked.add(criterion["question"])
        out.append(criterion)
    return out


def _as_question(criterion: Criterion, group_name: str | None) -> Criterion:
    quote = criterion.get("quote")
    if quote:
        question = f"다음 조건에 해당하나요? 「{quote}」"
    else:
        question = f"「{group_name or '이 대상'}」 대상에 해당하나요?"  # 대상 이름에서 만든 신분 조건
    return {
        **{k: criterion[k] for k in ("id", "quote", "page", "inReview", "reviewReason")},
        "kind": "ASK",
        "question": question,
        "metWhen": "YES",
    }


def _strip_private(node: Any) -> Any:
    """가공 중에만 쓰는 칸(「_」로 시작)을 뺍니다. 받은 틀의 칸 구성은 바꾸지 않습니다."""
    if isinstance(node, dict):
        return {k: _strip_private(v) for k, v in node.items() if not str(k).startswith("_")}
    if isinstance(node, list):
        return [_strip_private(v) for v in node]
    return node


def _from_item(ctx: Context, spec: Spec, index: int, item: dict[str, Any], when: date) -> Made:
    """자격 항목 하나 → (틀 조건 목록, 안내 목록)."""
    quote = item.get("originalText")
    reasons = ctx.review_reasons(index, item)
    expression = item["rule"].get("expression")
    if expression is None:
        return _from_text(ctx, spec, item, reasons)
    made = expression_criteria(ctx, spec, item, expression, quote, when)
    if made is None:  # 조건식을 틀 종류로 옮길 수 없음(NOT, 틀에 없는 속성 등) → 원문 질문
        made = [ask(ctx, item, ["조건식을 틀의 조건 종류로 옮길 수 없어 원문을 질문으로 보냅니다"])]
    made, unhandled = _exceptions(ctx, spec, item, made, when)
    unhandled += spec.extra_reasons(item, made)
    if ANY_ONE.search(quote or "") and expression.get("kind") != "ANY":
        unhandled.append("원문은 「어느 하나」에 해당하면 되는 조건인데 조건식은 모두 갖춰야 하는 조건입니다")
    for criterion in made:
        ctx.flag(criterion, [*reasons, *unhandled])
    return made, []


def _scope_names(candidate: dict[str, Any], scope: dict[str, Any]) -> list[str]:
    """한정 범위의 주택형·단지 이름(없으면 빈 목록)."""
    groups = {g["id"]: g for g in candidate.get("supplyGroups") or []}
    places = {loc["id"]: loc for loc in candidate.get("locations") or []}
    names = [str(groups[i].get("housingTypeCode") or groups[i].get("unitLabel") or "") for i in scope.get("supplyGroupIds") or [] if i in groups]
    names += [str(places[i].get("complexName") or places[i].get("buildingName") or "") for i in scope.get("locationIds") or [] if i in places]
    return [n for n in dict.fromkeys(names) if n.strip()]


REST = "그 밖의 주택형·단지"


def _split_by_scope(
    ctx: Context,
    candidate: dict[str, Any],
    groups: list[dict[str, Any]],
    limited: list[tuple[dict[str, Any], list[str], list[Criterion]]],
    open_names: set[str],
) -> list[dict[str, Any]]:
    """특정 주택형·단지에만 붙은 조건 → 그 주택형·단지 대상군(판정 담당 문서: 「단지·공급군마다 다른 조건은 대상군을 단지별로
    나눠 주세요(예: "A단지 청년")」, 2026-10-21). 공통 조건에 넣으면 다른 주택형 신청자까지 떨어집니다.
    - 대상군 「59A」(대상 계층이 있으면 「59A 청년」) = 원래 대상군의 조건 + 그 주택형에만 붙은 조건
    - 나눈 대상군의 원래 대상군은, 한정 조건이 없는 주택형·단지가 남아 있으면 「그 밖의 주택형·단지」로 이름을 밝혀 남깁니다.
      모든 주택형을 덮거나, 그 대상이 한정 조건에만 나오면(예: 주거약자형 신청자만의 순위) 뺍니다. 한정 조건과 관계없는 대상군은 그대로 둡니다"""
    by_label: dict[str, list[tuple[list[str], list[Criterion]]]] = {}
    for scope, targets, made in limited:
        label = ", ".join(_scope_names(candidate, scope)) or str(scope.get("description") or "해당 주택형")
        by_label.setdefault(label, []).append((targets, made))
    added, split = [], set()
    for label, entries in by_label.items():
        named = {t for targets, _ in entries for t in targets}
        everyone = any(not targets for targets, _ in entries)
        for base in groups:
            if not everyone and base["name"] not in named:
                continue
            name = label if base["id"] == "all" else f"{label} {base['name']}"
            gid = group_id(ctx, name)
            criteria = [ctx.copy(c, gid) for c in base["criteria"]]
            criteria += [ctx.copy(c, gid) for targets, made in entries if not targets or base["name"] in targets for c in made]
            added.append({"id": gid, "name": name, "criteria": criteria})
            split.add(base["id"])
    covered = _covers_all(candidate, [scope for scope, _, _ in limited])
    rest = []
    for group in groups:
        if group["id"] not in split:
            rest.append(group)
        elif not covered and (group["id"] == "all" or group["name"] in open_names):
            rest.append({**group, "name": REST if group["id"] == "all" else f"{group['name']} ({REST})"})
    return [*rest, *added]


def _covers_all(candidate: dict[str, Any], scopes: list[dict[str, Any]]) -> bool:
    """한정 범위들이 공고의 모든 주택형(또는 모든 단지)을 덮는지."""
    supply = {g["id"] for g in candidate.get("supplyGroups") or []}
    places = {loc["id"] for loc in candidate.get("locations") or [] if loc.get("kind") != "AREA"}
    by_supply = {i for scope in scopes for i in scope.get("supplyGroupIds") or []}
    by_place = {i for scope in scopes for i in scope.get("locationIds") or []}
    return bool(supply and supply <= by_supply) or bool(places and places <= by_place)


def _exceptions(ctx: Context, spec: Spec, item: dict[str, Any], base: list[Criterion], when: date) -> tuple[list[Criterion], list[str]]:
    """자격 항목의 예외를 틀로 옮깁니다. (조건 목록, 옮기지 못한 예외 사유).
    - 분야가 흡수하는 예외(주거: 소득 「가구원 수 n인이면 m%」 → 가산 %p)
    - 그 밖의 면제(WAIVE)·교체(REPLACE) → ANY_OF: 「기본 기준」 또는 「예외에 해당(원문 질문) + 바뀐 기준」
    - 요건 추가(ADD_REQUIREMENT)·기타(OTHER)·옮길 수 없는 교체 → 사유(inReview)"""
    rest = [e for e in item.get("exceptions") or [] if not spec.absorb(base, e)]
    if not rest:
        return base, []
    options = [{"label": "기본 기준", "criteria": base}]
    unhandled = []
    for exception in rest:
        label = (exception.get("description") or exception["originalText"])[:80]
        condition = {
            **base_fields(ctx.new_id(f"{item['id']}-{exception['id']}"), "ASK", exception["originalText"]),
            "question": f"다음 예외에 해당하나요? 「{exception['originalText']}」",
            "metWhen": "YES",
        }
        if exception["effect"] == "WAIVE":
            options.append({"label": label, "criteria": [condition]})
            continue
        if exception["effect"] == "REPLACE" and exception.get("effectExpression"):
            replaced = expression_criteria(ctx, spec, item, exception["effectExpression"], exception["originalText"], when)
            if replaced and spec.covered(base, exception, replaced):
                continue
            if replaced:
                options.append({"label": label, "criteria": [condition, *replaced]})
                continue
        unhandled.append(f"예외 「{label}」을 틀로 옮기지 못했습니다")
    if len(options) == 1:
        return base, unhandled
    return [{**base_fields(ctx.new_id(f"{item['id']}-or"), "ANY_OF", item.get("originalText")), "options": options}], unhandled


def expression_criteria(ctx: Context, spec: Spec, item: dict[str, Any], node: dict[str, Any], quote: str | None, when: date) -> list[Criterion] | None:
    kind = node.get("kind")
    if kind == "ATOMIC":
        made = spec.atom(ctx, item, node, quote, when)
        return None if made is None else [made]
    if kind == "ALL":
        children = [expression_criteria(ctx, spec, item, child, quote, when) for child in node["children"]]
        if any(c is None for c in children):
            return None
        return spec.merge(merge_age([c for group in children for c in group if group is not None]))
    if kind == "ANY":
        options = []
        for number, child in enumerate(node["children"], start=1):
            made = expression_criteria(ctx, spec, item, child, quote, when)
            if not made:
                return None
            options.append({"label": _option_label(child, number), "criteria": made})
        return [spec.simplify({**base_fields(ctx.new_id(item["id"]), "ANY_OF", quote), "options": options})]
    return None  # NOT: 틀에 부정 조건이 없음


def _option_label(node: dict[str, Any], number: int) -> str:
    if node.get("kind") == "ATOMIC" and node.get("basis"):
        return str(node["basis"])
    return f"{number}번째 길"


def bounds(node: dict[str, Any]) -> tuple[float | None, float | None] | None:
    """원자 조건의 (하한, 상한)(둘 다 포함). EQ는 같은 값, LT/GT는 정수 한 칸 안쪽."""
    op, value = node["operator"], node["value"]
    if op == "BETWEEN" and isinstance(value, list) and len(value) == 2:
        return value[0], value[1]
    if not isinstance(value, int | float) or isinstance(value, bool):
        return None
    return {"LTE": (None, value), "LT": (None, value - 1), "GTE": (value, None), "GT": (value + 1, None), "EQ": (value, value)}.get(op)


def merge_age(criteria: list[Criterion]) -> list[Criterion]:
    """「19세 이상」과 「39세 이하」가 한 항목에 같이 오면(원자 조건 둘) 나이 조건 하나(19~39)로 합칩니다."""
    ages = [c for c in criteria if c["kind"] == "AGE"]
    if len(ages) < 2:
        return criteria
    lows = [c["minAge"] for c in ages if c["minAge"] is not None]
    highs = [c["maxAge"] for c in ages if c["maxAge"] is not None]
    first = ages[0]
    first["minAge"], first["maxAge"] = (max(lows) if lows else None), (min(highs) if highs else None)
    for other in ages[1:]:
        if other.get("inReview"):
            first["inReview"], first["reviewReason"] = True, other["reviewReason"]
    return [c for c in criteria if c["kind"] != "AGE" or c is first]


ANY_ONE = re.compile(
    r"(?:다음|아래)\s*(?:각\s*(?:호|목)\s*)?(?:의\s*)?(?:어느\s*)?(?:하나|1\s*개|한\s*가지)"
    r"|중\s*(?:어느\s*)?(?:하나|1\s*개|한\s*가지)|어느\s*하나"
)
BONUS_NOTE = re.compile(r"가산[^.]{0,40}?\d+\s*%|\d+\s*%\s*p?\s*가산")
# 「※ 무주택세대구성원 : 아래 표의 세대구성원 전원이 … 세대의 구성원」 같은 용어 정의: 용어가 사람·세대를 뜻하는 명사이고 설명도 그렇게 끝남
TERM_DEFINITION = re.compile(
    r"^[※*\-•·\s]*(?P<term>[^:：\n|]{1,24}?(?:자|원|생|부부|가족|계층|세대|가구))\s*[:：]\s*\S.*(?:사람|자|구성원|세대|가구|말함|말합니다)\s*[.)」]?$", re.S
)


def _term_definition(spec: Spec, ctx: Context, item: dict[str, Any], text: str) -> bool:
    """용어 정의 문장인지. 용어가 대상 이름이고 그 대상에 신분 코드가 없으면 그 문장이 대상의 실제 요건이라(「사회초년생 : 종사 기간 5년 이내」)
    정의로 보지 않습니다(안내로 넘기면 그 대상이 요건 없이 지원 가능이 됨)."""
    match = TERM_DEFINITION.match(text)
    if not match or LIMIT_WORDS.search(text):
        return False
    term = re.sub(r"\s+", "", match.group("term"))
    names = [*(ctx.candidate["notice"].get("targetGroups") or []), *(item["scope"].get("targetGroups") or [])]
    return not any(term in re.sub(r"\s+", "", name) and not spec.statuses_of(name) and not spec.status_codes(term) for name in names)


# 신청 시점의 요건(공고일·신청일 현재·기준): 의무 문장과 함께 있으면 그 문장은 안내가 아니라 요건입니다
CURRENT_REQUIREMENT = re.compile(r"(?:모집\s*)?공고일\S*\s*(?:현재|기준)|신청일\S*\s*(?:현재|기준)")
GENERIC_RECIPIENT = re.compile(r"(?<!차상위)수급(?:권)?자")
# 신분 정의 문장에 신분 말고 남으면 안 되는 말
STATUS_REST_CONDITION = re.compile(r"소득|자산|이하|이상|미만|초과|\d+\s*(?:%|세)|등(?:\s|$|으로|의)|준하는|그\s*밖의|외에")
# 숫자로 적은 요건(나이·소득·금액·기간·면적·횟수): 「불문」 문장에 같이 있으면 안내로 넘기지 않습니다
REQUIREMENT = re.compile(r"\d+\s*(?:세|%|퍼센트|원|만\s*원|억|년|개월|㎡|회)")
DEFINITION = re.compile(r"(?:이)?란\s.*(?:말함|말합니다|말한다)|이라\s*함은|(?:을|를)\s*말합니다|말함\s*\.?$|인정(?:합니다|함)\s*\.?$")
# 세대구성원 범위 정의(「…세대 분리되어 있는 배우자도 세대구성원에 포함」「(이하 ‘분리배우자’) 포함」)와 검증 생략 안내
# (「…증빙자료 제출하면 소득자산 검증이 생략됩니다」): 신청자가 갖출 요건이 아님(2026-10-22 방향 미정 959건 분류)
SCOPE_DEFINITION = re.compile(
    r"(?:세대\s*구성원|자격\s*검증\s*대상|가구원\s*수)\S*\s*에\s*(?:포함|포합)(?:합니다|됩니다|하며|되며|됨)?\s*\.?$"
    r"|배우자\S*\s*\(이하[^)]*\)\s*포함\s*\.?$|배우자\S*\s*포함\s*\.?$|검증\S*\s*생략(?:됩니다|됨)\s*\.?$"
)


def _from_text(ctx: Context, spec: Spec, item: dict[str, Any], reasons: list[str]) -> Made:
    """조건식이 없는 항목(원문 문장만)."""
    text = (item.get("originalText") or "").strip()
    if not text:
        return [], []
    description = str(item.get("description") or "")
    shown = description or text
    if NOT_REQUIRED.search(text) or NOT_REQUIRED.search(description):  # 「거주지역 … 불문」「따지지 않아요」: 요건이 아님
        if not REQUIREMENT.search(text) and not LIMIT_WORDS.search(text):
            return [], [note("OTHER", shown, text)]
        # 「거주지역 불문, 만 19세 이상 39세 이하」: 문장 전체를 안내로 넘기면 나이 요건이 사라집니다
        return [ask(ctx, item, [*reasons, "「제한 없음」 표현과 다른 요건이 한 문장에 함께 있어 원문을 질문으로 보냅니다"])], []
    if re.search(r"\d\s*순위", text):
        return [], [note("SELECTION", shown, text)]
    if DEFINITION.search(text) or _term_definition(spec, ctx, item, text):
        return [], [note("OTHER", shown, text)]
    if SCOPE_DEFINITION.search(text) and not LIMIT_WORDS.search(text) and not REQUIREMENT.search(text):
        return [], [note("OTHER", shown, text)]
    if BONUS_NOTE.search(text) and not LIMIT_WORDS.search(text):
        # 「가산 적용 : 출산자녀 2인 이상 20% 가산 …」: 자격이 아니라 소득 기준의 가산 안내. 출생 기준일 없이는 틀의 가산으로 옮길 수 없어
        # 안내로 두고, 같은 대상 소득 조건은 「가산을 못 읽음」으로 inReview가 됩니다(Context.mentions_bonus)
        return [], [note("OTHER", shown, text)]
    if any(re.search(word, text) for word in spec.rule_words):
        return [], [note("RULE", shown, text)]
    if re.search(spec.obligation, text) and not LIMIT_WORDS.search(text) and not CURRENT_REQUIREMENT.search(text):
        # 제한 낱말이나 「공고일 현재 … 거주」 같은 지금 갖출 요건이 함께 있으면 안내로 보내지 않습니다(리뷰 2026-10-22: 거주 요건·신청 제한이 사라짐)
        return [], [note("OBLIGATION", shown, text)]
    if PERMIT_WORDS.search(text) and not LIMIT_WORDS.search(text):  # 「…도 신청할 수 있습니다」: 자격을 넓히는 안내(제한이 아님)
        return [], [note("OTHER", shown, text)]
    targets = item["scope"].get("targetGroups") or []
    found = set(spec.status_codes(text))
    if targets and found and all(found <= set(spec.statuses_of(t)) for t in targets):
        # 신분 계층의 법령 정의: 그 계층 이름의 신분 조건이 이미 다 담음. 정의에 더 많은 신분이 있으면(「사회적기업」 대상에
        # 예비사회적기업도) 이름만으로는 그 신분이 떨어지므로 아래에서 정의 문장의 신분으로 조건을 만듭니다
        return [], [note("OTHER", shown, text)]
    special = spec.text(ctx, item, text, reasons)
    if special is not None:
        return special
    if found and targets and re.search(r"해당하는|따른|교부된|수급자|계층|가족|기업|인증|지정", text) and not LIMIT_WORDS.search(text):
        # 대상 계층의 신분 정의 문장(「…생계·주거·의료·교육급여 수급자」) → 신분 조건
        status = {**base_fields(ctx.new_id(item["id"]), "STATUS", text), "anyOf": list(dict.fromkeys(spec.status_codes(text)))}
        extra = ["신분 정의에 배우자·세대원도 포함하는 문구가 있습니다"] if re.search(r"배우자|세대원", text) else []
        if GENERIC_RECIPIENT.search(re.sub(BENEFIT_LIST.pattern + r"\s*(?:수급권자\s*(?:또는|및)\s*)?(?:수급(?:권)?자)?", " ", text)):
            # 「수급자가구 또는 차상위계층, 한부모가족」: 급여 종류 없는 「수급자」는 신분 코드가 없어 빠지고 나머지 신분만 요구하게 됨(실측 2026-10-21)
            extra.append("급여 종류 없이 「수급자」로 적힌 대상이 있어 신분 코드로 정확히 옮길 수 없습니다")
        if STATUS_REST_CONDITION.search(spec.status_rest(text)):
            # 「생계·의료급여 수급자 선정 기준의 소득 인정액 이하인 자」: 신분이 아니라 소득 기준(실측: 의료급여 수급자만 요구로 바뀜)
            extra.append("신분 말고 다른 조건(소득·나이·「등」)이 함께 있어 신분 조건만으로 담을 수 없습니다")
        ctx.flag(status, [*reasons, *extra])
        return [status], []
    return [ask(ctx, item, reasons)], []


def _selection(
    ctx: Context, spec: Spec, candidate: dict[str, Any], by_name: dict[str, dict[str, Any]], when: date
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """선정 규칙 → (우대, 안내). 우대 칸이 있는 틀이면 가점(SCORING)과 「우대」 우선순위는 우대로, 나머지는 안내(SELECTION)입니다."""
    preferences, notes = [], []
    for rule in candidate.get("selectionRules") or []:
        text = rule.get("description") or rule.get("originalText") or ""
        if not text:
            continue
        kind = rule.get("kind")
        favored = kind == "SCORING" and isinstance(rule.get("points"), int | float) and rule["points"] > 0
        favored = favored or (kind == "PRIORITY" and "우대" in f"{rule.get('originalText') or ''}{text}")
        if not spec.preferences or not favored:
            notes.append(note("SELECTION", text, rule.get("originalText")))
            continue
        expression = (rule.get("rule") or {}).get("expression")
        made = expression_criteria(ctx, spec, rule, expression, rule.get("originalText"), when) if expression else None
        if not made:
            made = [ask(ctx, {**rule, "originalText": rule.get("originalText") or text}, [])]
        targets = [t for t in (rule.get("scope") or {}).get("targetGroups") or [] if t in by_name]
        preferences.append(
            {
                "id": ctx.new_id(f"pref-{rule.get('id') or len(preferences) + 1}"),
                "label": text[:80],
                "groupId": by_name[targets[0]]["id"] if len(targets) == 1 else None,
                "effect": "BONUS_POINTS" if kind == "SCORING" else "PRIORITY",
                "points": rule["points"] if kind == "SCORING" else None,
                "criteria": made,
                "quote": rule.get("originalText"),
                "page": None,
            }
        )
    return preferences, notes


def _guidance_notes(candidate: dict[str, Any]) -> list[dict[str, Any]]:
    out = []
    for item in candidate.get("guidance") or []:
        text = item.get("description") or item.get("title")
        if text:
            out.append(note("RULE" if str(item.get("kind", "")).startswith("APPLICATION") else "OTHER", text, None))
    return out


def as_int(value: Any) -> int | None:
    return None if value is None else int(value)


def as_number(value: float) -> int | float:
    return int(value) if float(value).is_integer() else float(value)
