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
    text: Callable[[Context, dict[str, Any], str, list[str]], Made | None] = lambda *_: None  # 분야별 문장 규칙(못 정하면 None)
    absorb: Callable[[list[Criterion], dict[str, Any]], bool] = lambda *_: False  # 예외를 기본 조건에 흡수(소득 가산)
    extra_reasons: Callable[[dict[str, Any], list[Criterion]], list[str]] = lambda *_: []
    merge: Callable[[list[Criterion]], list[Criterion]] = lambda criteria: criteria  # ALL 안 같은 종류 합치기
    simplify: Callable[[Criterion], Criterion] = lambda criterion: criterion  # ANY_OF 정리(사업공간 거주지·사업장 지역)
    preferences: bool = False  # 우대(preferences) 칸이 있는 틀
    # 신청 규칙(자격이 아님): 중복 신청·청약, 한 블록·단지·주택형만 신청(실측 2026-10-21 광주선운2·김해 매각이 질문으로 나감)
    rule_words: tuple[str, ...] = field(
        default=(r"중복\s*(?:으로\s*)?(?:신청|청약)", r"1\s*세대\s*1\s*주택", r"한\s*개\s*(?:블록|단지|주택형)만", r"1\s*개\s*(?:블록|단지)\s*내\s*1\s*개")
    )
    # 당첨·입주 후 의무: 「입주 전까지 증명」, 「신청자격은 입주 시까지 계속 유지해야」(실측 든든전세)
    obligation: str = r"입주\s*(?:전|시)\s*까지.*(?:증명|제출)|(?:자격|요건)\S*\s*(?:을|은|는)?\s*[^.]{0,30}계속\s*유지"


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
        # 이름 전체가 신분이거나, 한 덩어리 이름의 끝이 신분(「월평균소득 70% 이하 장애인」). 「신혼부부·한부모가족」처럼 여러 대상을
        # 묶은 이름은 한 신분만 요구하면 다른 대상이 떨어지므로 쓰지 않습니다
        codes = spec.statuses_of(group["name"]) or ([] if re.search(r"[·ㆍ,/]|및|또는", group["name"]) else spec.status_codes(group["name"]))
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
    return frame


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


# 숫자로 적은 요건(나이·소득·금액·기간·면적·횟수): 「불문」 문장에 같이 있으면 안내로 넘기지 않습니다
REQUIREMENT = re.compile(r"\d+\s*(?:세|%|퍼센트|원|만\s*원|억|년|개월|㎡|회)")
DEFINITION = re.compile(r"(?:이)?란\s.*(?:말함|말합니다|말한다)|이라\s*함은|(?:을|를)\s*말합니다|말함\s*\.?$|인정(?:합니다|함)\s*\.?$")


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
    if BONUS_NOTE.search(text) and not LIMIT_WORDS.search(text):
        # 「가산 적용 : 출산자녀 2인 이상 20% 가산 …」: 자격이 아니라 소득 기준의 가산 안내. 출생 기준일 없이는 틀의 가산으로 옮길 수 없어
        # 안내로 두고, 같은 대상 소득 조건은 「가산을 못 읽음」으로 inReview가 됩니다(Context.mentions_bonus)
        return [], [note("OTHER", shown, text)]
    if any(re.search(word, text) for word in spec.rule_words):
        return [], [note("RULE", shown, text)]
    if re.search(spec.obligation, text):
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
