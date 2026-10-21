"""주거 판정 틀(housing-judgment.v1) 가공. 코드만 씁니다(공통 뼈대는 frame.py).

- 자격 조건식(원자 조건) → 나이·무주택·소득·총자산·자동차·혼인·자녀·청약통장·거주지.
- 소득 가산: 예외 「가구원 수 n인이면 m%」(구조), 원문 「100%(1인 120%, 2인 110%)」, 설명 「1인가구는 20%p 가산」(원문 인용에
  같은 값이 있을 때만 확정). 공고 어딘가에 가산 낱말이 있는데 못 읽으면 그 소득 조건은 inReview(가산을 빼면 1·2인 가구가 불충족).
- 문장만 있는 「서울특별시에 주민등록된 …」은 거주지 코드(지역표), 그 밖의 문장 규칙은 frame.py.
"""

from __future__ import annotations

import re
from datetime import date
from typing import Any

from mujuthaek_extraction.judgment.common import STATUS_WORDS, Context, base_fields, status_codes, statuses_of
from mujuthaek_extraction.judgment.frame import Criterion, Made, Spec, as_int, as_number, bounds, build_frame

STANDARDS = (  # 앞에서부터 먼저 맞는 것(「도시근로자 가구원수별 가구당 월평균소득」은 가구원수별)
    (re.compile(r"중위\s*소득"), "MEDIAN_INCOME"),
    (re.compile(r"도시\s*근로자.*가구원\s*수\s*별"), "URBAN_WORKER_INCOME"),
    (re.compile(r"도시\s*근로자.*가구당"), "URBAN_WORKER_AVERAGE_INCOME"),
)
RELAXED_LIMIT = re.compile(r"(?:출생|신생아)[^.]{0,40}?\d[\d,.]*\s*(?:억|만)\s*원")
STRICT_INCOME = "원문은 소득 기준 「미만」인데 틀의 percent는 「이하」만 나타냅니다(기준값과 같은 소득은 원문상 불충족)"
RELATIVE_YEAR = {"전년도": -1, "당해연도": 0, "당해년도": 0, "금년도": 0}


def _atom(ctx: Context, item: dict[str, Any], node: dict[str, Any], quote: str | None, when: date) -> Criterion | None:
    attribute, op, value, subject = node["attribute"], node["operator"], node["value"], node.get("subject")
    span = bounds(node)
    if attribute == "AGE_YEARS" and span and subject == "APPLICANT":
        return {**base_fields(ctx.new_id(item["id"]), "AGE", quote), "minAge": as_int(span[0]), "maxAge": as_int(span[1])}
    if attribute == "HOMELESS" and op == "EQ" and value is True and subject in ("APPLICANT", "HOUSEHOLD"):
        return {**base_fields(ctx.new_id(item["id"]), "HOMELESS", quote), "scope": subject, "minYears": None}
    if attribute == "INCOME_BENCHMARK_PERCENT" and span and span[0] is None and span[1] is not None:
        if op != "LT":
            return _income(ctx, item, node, quote, when, span[1])
        # 소득 비율은 소수가 될 수 있어 「100% 미만」을 「99% 이하」로 바꾸면 99.5%가 떨어집니다. 원문 값을 두고 확인 필요로 둡니다
        income = _income(ctx, item, node, quote, when, value)
        if income is not None:
            ctx.flag(income, [STRICT_INCOME])
        return income
    if attribute in ("TOTAL_ASSETS_KRW", "CAR_ASSETS_KRW") and span and span[0] is None and span[1] is not None:
        if attribute == "TOTAL_ASSETS_KRW" and subject not in ("APPLICANT", "HOUSEHOLD"):
            return None
        criterion = base_fields(ctx.new_id(item["id"]), "ASSET" if attribute == "TOTAL_ASSETS_KRW" else "CAR", quote)
        if attribute == "TOTAL_ASSETS_KRW":
            criterion["basis"] = subject
        return criterion | {"maxWon": int(span[1]), "overrides": []}
    if attribute == "MARITAL_STATUS" and op == "EQ" and value in ("UNMARRIED", "MARRIED"):
        return {
            **base_fields(ctx.new_id(item["id"]), "MARRIAGE", quote),
            "require": "NOT_MARRIED" if value == "UNMARRIED" else "MARRIED",
            "maxYears": None,
            "allowEngaged": value == "MARRIED" and _engaged(item),
        }
    if attribute == "MARRIAGE_MONTHS" and span and span[0] is None and span[1] is not None and span[1] % 12 == 0:
        return {
            **base_fields(ctx.new_id(item["id"]), "MARRIAGE", quote),
            "require": "MARRIED",
            "maxYears": int(span[1]) // 12,
            "allowEngaged": _engaged(item),
        }
    if attribute == "CHILD_COUNT" and span and span[1] is None:
        text = f"{item.get('originalText') or ''}"
        return {
            **base_fields(ctx.new_id(item["id"]), "CHILDREN", quote),
            "minCount": as_int(span[0]),
            "youngestMaxAge": _youngest_age(text),
            "includeUnborn": "태아" in text,
        }
    if attribute in ("SAVINGS_ACCOUNT_EXISTS", "SAVINGS_MONTHS", "SAVINGS_PAYMENT_COUNT", "SAVINGS_BALANCE_KRW"):
        if attribute == "SAVINGS_ACCOUNT_EXISTS" and not (op == "EQ" and value is True):
            return None
        if attribute != "SAVINGS_ACCOUNT_EXISTS" and not (span and span[1] is None):
            return None
        low = as_int(span[0]) if span else None
        return {
            **base_fields(ctx.new_id(item["id"]), "SAVINGS", quote),
            "minMonths": low if attribute == "SAVINGS_MONTHS" else None,
            "minPayments": low if attribute == "SAVINGS_PAYMENT_COUNT" else None,
            "minBalanceWon": low if attribute == "SAVINGS_BALANCE_KRW" else None,
        }
    if attribute == "RESIDENCE_REGION_CODE" and op in ("EQ", "IN"):
        codes = value if isinstance(value, list) else [value]
        return {**base_fields(ctx.new_id(item["id"]), "RESIDENCE", quote), "regionCodes": list(dict.fromkeys(codes)), "minMonths": None}
    if attribute == "NATIONALITY_CODE" and op == "EQ" and value == "KR":
        return {**base_fields(ctx.new_id(item["id"]), "ASK", quote), "question": "대한민국 국적인가요?", "metWhen": "YES"}
    return None


def _merge(criteria: list[Criterion]) -> list[Criterion]:
    """「혼인 중」과 「혼인 n년 이내」가 한 항목에 같이 오면 혼인 조건 하나로 합칩니다."""
    marriages = [c for c in criteria if c["kind"] == "MARRIAGE" and c["require"] == "MARRIED"]
    if len(marriages) != 2:
        return criteria
    keep, other = (marriages[0], marriages[1]) if marriages[1]["maxYears"] is None else (marriages[1], marriages[0])
    keep["allowEngaged"] = keep["allowEngaged"] or other["allowEngaged"]
    return [c for c in criteria if c is not other]


def _income(ctx: Context, item: dict[str, Any], node: dict[str, Any], quote: str | None, when: date, percent: float) -> Criterion | None:
    benchmark = node.get("benchmark") or {}
    name = str(benchmark.get("standardName") or "")
    standard = next((value for pattern, value in STANDARDS if pattern.search(name)), None)
    if standard is None:
        return None
    year = benchmark.get("referenceYear")
    if not isinstance(year, int):
        offset = RELATIVE_YEAR.get(str(benchmark.get("referenceVersion") or "").strip())
        year = when.year + offset if offset is not None else None
    if year is None:
        return None
    scope = str(benchmark.get("householdScope") or "")
    if "부모" in scope:
        basis = "APPLICANT_AND_PARENTS"
    elif re.search(r"세대원\s*(?:인\s*)?신청자.*본인|세대주.*세대.*세대원.*본인", scope):
        basis = "HOUSEHOLD_IF_HEAD"
    elif node.get("subject") in ("APPLICANT", "HOUSEHOLD"):
        basis = node["subject"]
    else:
        return None
    criterion = {
        **base_fields(ctx.new_id(item["id"]), "INCOME", quote),
        "basis": basis,
        "standard": standard,
        "year": year,
        "percent": as_number(percent),
        "bonuses": [],
        "bonusCombine": "SUM",
        "printedTable": [],
    }
    bonuses, unread = _bonuses(f"{item.get('originalText') or ''}", percent)
    if not bonuses and not unread:
        # 원문 인용에는 기준 비율만 있고 가산은 설명에만 있는 경우(실측 LH 강원: 「1인가구는 20%p, 2인가구는 10%p 가산」).
        # 설명은 AI 문장이라 공고 근거 인용에 같은 가구원 수·%가 있을 때만 확정하고, 없으면 넣되 inReview입니다
        bonuses, unread = _added_points(str(item.get("description") or ""))
        if bonuses and not all(ctx.quoted(rf"{b['when']['min']}\s*인[^%]{{0,20}}?{b['addPercent']:g}\s*%") for b in bonuses):
            ctx.flag(criterion, ["소득 가산을 공고 설명에서 읽었고 원문 인용에서 같은 값을 찾지 못했습니다"])
    criterion["bonuses"] = bonuses
    if unread or (not bonuses and ctx.mentions_bonus(item)):
        ctx.flag(criterion, ["소득 가산 문구를 코드로 다 읽지 못했습니다(가산을 빼면 1·2인 가구 판정이 달라질 수 있음)"])
    return criterion


ADDED_POINTS = re.compile(r"(?P<size>[1-9])\s*인\s*가구(?:는|은)?\s*(?P<value>\d+(?:\.\d+)?)\s*%\s*p")


def _added_points(text: str) -> tuple[list[dict[str, Any]], bool]:
    """「1인가구는 20%p, 2인가구는 10%p 가산」 → 가구원 수별 가산 %p. (가산 목록, 가산 낱말이 있는데 못 읽은 것이 있는지)."""
    found: list[dict[str, Any]] = []
    for match in ADDED_POINTS.finditer(text):
        size = int(match.group("size"))
        when = {"factor": "HOUSEHOLD_SIZE", "min": size, "max": size, "since": None, "countRule": None}
        if any(b["when"] == when for b in found):
            return [], True
        found.append({"when": when, "addPercent": as_number(float(match.group("value")))})
    leftover = ADDED_POINTS.sub("", text)
    return found, bool(re.search(r"맞벌이|신생아|출생\s*자녀|자녀\s*수", leftover)) or (not found and "가산" in text)


# 「100%(1인 120%, 2인 110%)」「120%(맞벌이)」: 기준 비율 뒤 괄호 안의 가구원 수·맞벌이별 비율(절대값)
BONUS_PART = re.compile(
    r"(?:(?P<size>[1-9])\s*인\s*(?:가구)?|(?P<dual>맞벌이(?:\s*부부)?)(?:\s*(?:의\s*)?경우)?)\s*(?:는|은)?\s*(?P<value>\d+(?:\.\d+)?)\s*(?:%|퍼센트)"
)


def _bonuses(text: str, percent: float) -> tuple[list[dict[str, Any]], bool]:
    """원문의 가구원 수·맞벌이별 비율을 가산 %p로. (가산 목록, 가산 낱말이 있는데 못 읽은 것이 있는지)."""
    found: list[dict[str, Any]] = []
    for match in BONUS_PART.finditer(text):
        add = float(match.group("value")) - percent
        if add <= 0:
            return [], True
        if match.group("size"):
            size = int(match.group("size"))
            when = {"factor": "HOUSEHOLD_SIZE", "min": size, "max": size, "since": None, "countRule": None}
        else:
            when = {"factor": "DUAL_INCOME", "min": None, "max": None, "since": None, "countRule": None}
        if any(b["when"] == when for b in found):
            return [], True
        found.append({"when": when, "addPercent": as_number(add)})
    leftover = BONUS_PART.sub("", text)
    return found, bool(re.search(r"가산|신생아|출생\s*자녀|자녀\s*수", leftover))


def _absorb(base: list[Criterion], exception: dict[str, Any]) -> bool:
    """「가구원 수가 n인이면 소득 기준 m%」 교체를 소득 조건의 가구원 수 가산으로 옮기면 True(그 조건에 더함)."""
    incomes = [c for c in base if c["kind"] == "INCOME"]
    if len(incomes) != 1:
        return False
    income = incomes[0]
    effect, condition = exception.get("effectExpression") or {}, (exception.get("when") or {}).get("expression") or {}
    if exception["effect"] != "REPLACE" or effect.get("kind") != "ATOMIC" or condition.get("kind") != "ATOMIC":
        return False
    if effect.get("attribute") != "INCOME_BENCHMARK_PERCENT" or effect.get("operator") not in ("LTE", "LT"):
        return False
    if condition.get("attribute") != "HOUSEHOLD_MEMBER_COUNT" or income["percent"] is None:
        return False
    span = bounds(condition)
    value = effect["value"]
    if not span or span[0] is None or not isinstance(value, int | float) or value <= income["percent"]:
        return False
    sizes = {"factor": "HOUSEHOLD_SIZE", "min": as_int(span[0]), "max": as_int(span[1]), "since": None, "countRule": None}
    add = as_number(value - income["percent"])
    if any(b["when"] == sizes for b in income["bonuses"]):
        return any(b["when"] == sizes and b["addPercent"] == add for b in income["bonuses"])
    income["bonuses"].append({"when": sizes, "addPercent": add})
    kept = [r for r in (income.get("reviewReason") or "").split("; ") if r and "가산" not in r]  # 가산을 구조로 받았으면 못 읽었다는 사유는 지움
    income["reviewReason"], income["inReview"] = "; ".join(kept) or None, bool(kept)
    if effect["operator"] == "LT":
        _add_reason(income, STRICT_INCOME)
    return True


def _add_reason(criterion: Criterion, reason: str) -> None:
    """Context 없이 검수 사유를 더합니다(_absorb는 Spec 호출 모양상 Context를 받지 않음)."""
    criterion["inReview"] = True
    criterion["reviewReason"] = "; ".join(dict.fromkeys([*filter(None, (criterion.get("reviewReason") or "").split("; ")), reason]))


def _extra(item: dict[str, Any], made: list[Criterion]) -> list[str]:
    text = f"{item.get('originalText') or ''} {item.get('description') or ''}"
    if any(c["kind"] in ("ASSET", "CAR") for c in made) and RELAXED_LIMIT.search(text):
        return ["출생 자녀에 따른 한도 완화 문구가 있는데 예외로 받지 못했습니다"]
    return []


SIMPLE_RESIDENCE = re.compile(
    r"^(?P<region>(?:\S+(?:특별시|광역시|특별자치시|특별자치도|도))(?:\s*\S+(?:시|군|구))?)\s*에\s*(?:주민등록(?:이|을|이\s*되어)?\s*(?:등재|되어|된|한)?|거주)"
)


def _text(ctx: Context, item: dict[str, Any], text: str, reasons: list[str]) -> Made | None:
    """「서울특별시에 주민등록된 …」처럼 지역 하나에 사는 사람만 되는 문장 → 거주지. 「또는」「연접」·소득 근거지·대학 소재지가 섞이면 하지 않습니다."""
    if ctx.regions is None or re.search(r"또는|연접|인근|소득\s*근거지|대학|직장|근무", text):
        return None
    match = SIMPLE_RESIDENCE.match(text)
    if not match:
        return None
    found = ctx.regions.lookup(match.group("region"))
    if not found.code or found.level not in ("SIDO", "SIGUNGU"):
        return None
    residence = {**base_fields(ctx.new_id(item["id"]), "RESIDENCE", text), "regionCodes": [found.code], "minMonths": None}
    ctx.flag(residence, reasons)
    return [residence], []


def _engaged(item: dict[str, Any]) -> bool:
    words = " ".join([*((item.get("scope") or {}).get("targetGroups") or []), item.get("appliesTo") or "", item.get("originalText") or ""])
    return "예비신혼" in words or "예비 신혼" in words


def _youngest_age(text: str) -> int | None:
    match = re.search(r"(?:만\s*)?(\d+)\s*세\s*이하", text)
    if match:
        return int(match.group(1))
    match = re.search(r"만\s*(\d+)\s*세\s*미만", text)
    return int(match.group(1)) - 1 if match else None


SPEC = Spec(
    schema="housing-judgment.v1",
    atom=_atom,
    statuses_of=lambda name: statuses_of(name, STATUS_WORDS),
    status_codes=lambda text: status_codes(text, STATUS_WORDS),
    text=_text,
    absorb=_absorb,
    extra_reasons=_extra,
    merge=_merge,
)


def build_housing(candidate: dict[str, Any], regions: Any = None, *, approved: bool = False) -> dict[str, Any] | None:
    return build_frame(candidate, regions, SPEC, approved=approved)
