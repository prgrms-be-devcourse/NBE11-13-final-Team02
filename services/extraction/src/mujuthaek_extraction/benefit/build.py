"""혜택 정제 결과 만들기: 규칙 조건(출처 코드 칸) + AI 답 → policy.extracted payload.

틀린 값보다 빈 값이 낫다는 원칙으로, AI 값은 근거 문장(evidence)이 원문에 있고 값이 그 근거에 있을 때만 씁니다.
- 근거가 원문에 없으면 그 조건은 known=false(value null)로 바꾸고 EVIDENCE_NOT_FOUND
- 근거는 있는데 숫자·금액이 근거에 없거나, 지역 이름을 코드로 못 바꾸면 그 조건은 known=false (rawText는 남겨 화면에 보여 줌)
- 같은 type이 규칙과 AI 둘 다에서 나오면 규칙 줄만 보내고, 값이 다르면 RULE_AI_CONFLICT
quality.confidence는 AI 자기 보고가 아니라 코드가 확인한 결과로 매깁니다(공고 추출과 같은 원칙).
"""

from __future__ import annotations

import json
import re
import unicodedata
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from mujuthaek_extraction.benefit.codes import RuleResult
from mujuthaek_extraction.benefit.reading import PROMPT_VERSION, BenefitReading

ENUMS = {
    "MARITAL": {"SINGLE", "MARRIED", "NEWLYWED", "ENGAGED"},
    "PREGNANCY": {"PRE_PARENT", "PREGNANT", "BIRTH"},
    "HOUSEHOLD_TYPE": {"SINGLE_PERSON", "SINGLE_PARENT", "GRANDPARENT", "MULTI_CHILD", "MULTICULTURAL", "NK_DEFECTOR", "EXTENDED"},
    "WELFARE_STATUS": {"BASIC_LIVELIHOOD", "NEAR_POOR"},
    "SPECIAL_STATUS": {"DISABLED", "VETERAN", "ILLNESS", "PROTECTED_YOUTH", "SOLDIER"},
    "EMPLOYMENT": {"EMPLOYED", "SELF_EMPLOYED", "UNEMPLOYED", "FREELANCER", "DAILY_WORKER", "SHORT_TERM", "STARTUP", "OTHER"},
    "EDUCATION": {
        "ELEMENTARY",
        "MIDDLE",
        "BELOW_HIGH",
        "HIGH_ENROLLED",
        "HIGH_GRAD_EXPECTED",
        "HIGH_GRAD",
        "UNIV_ENROLLED",
        "UNIV_GRAD_EXPECTED",
        "UNIV_GRAD",
        "GRADUATE",
        "OTHER",
    },
    "OCCUPATION": {"FARMER", "FISHER", "LIVESTOCK", "FORESTRY"},
}
TEXT_ONLY = {"EXCLUSIVE", "PRIORITY", "OTHER"}  # 판정하지 않고 글만 보여 주는 조건(known=false)
# 핵심 값: 비우면 의미 없는 데이터가 되므로 확실하지 않아도 가장 근거 있는 값을 채우고 확인 필요로 표시합니다(2026-10-25 사용자 결정).
# 근거 문장이 원문에서 확인되지 않은 AI 값은 지어낸 값일 수 있어 핵심 값이어도 비웁니다(같은 날 사용자 결정)
CORE = {"AGE", "RESIDENCE", "HOMELESS", "MARITAL", "INCOME_AMOUNT", "INCOME_MEDIAN_PCT", "ASSET_AMOUNT"}
# 기준이 원문에 없을 때 채우는 기본 기준: 소득은 가구(부부합산, 주거 지원에 많음), 무주택은 본인(「무주택 청년」)
DEFAULT_BASIS = {"INCOME_AMOUNT": "HOUSEHOLD", "INCOME_MEDIAN_PCT": "HOUSEHOLD", "HOMELESS": "PERSON"}
LOW_CONFIDENCE = 0.6
AMOUNT_KEYS = {"min", "max", "depositMax", "monthlyMax"}  # 조건 값에서 금액·수치 칸


def _norm(text: str | None) -> str:
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", text or ""))


def quoted(evidence: str | None, sources: list[str]) -> bool:
    """근거가 원문(글·출처 칸 줄)에 그대로 있는지(띄어쓰기·전각 차이는 무시). 4글자보다 짧으면 우연히 맞을 수 있어 인정하지 않습니다."""
    needle = _norm(evidence)
    return len(needle) >= 4 and any(needle in _norm(s) for s in sources)


def _numbers(text: str) -> set[str]:
    return set(re.findall(r"\d+(?:\.\d+)?", unicodedata.normalize("NFKC", text or "").replace(",", "")))


def has_number(quote: str, value: float | int | None) -> bool:
    if value is None:
        return True
    number = int(value) if float(value).is_integer() else value
    return str(number) in _numbers(quote)


_MONEY = re.compile(r"((?:\d[\d,]*(?:\.\d+)?\s*(?:억|천만|백만|십만|만|천|백|십)\s*)+(?:\d[\d,]*\s*)?원?|\d[\d,]*\s*원)")
_PART = re.compile(r"(\d[\d,]*(?:\.\d+)?)?\s*(억|만|천|백|십)?")
_SMALL = {"천": 1000, "백": 100, "십": 10}
_BIG = {"만": 10**4, "억": 10**8}


def _korean_amount(text: str) -> Decimal:
    """「4천 5백만」「1억 5천만」「6.5천만」「122백만」「5,000만」「200,000」→ 원. 천·백·십은 만·억 앞 묶음 안에서 더하고 만·억에서 곱합니다
    (2026-10-25 고침: 예전에는 「4천 5백만」을 4천 + 5백만 = 500만 4천 원으로 읽음)."""
    total, group = Decimal(0), Decimal(0)
    for number, unit in _PART.findall(text):
        if not number and not unit:
            continue
        value = Decimal(number.replace(",", "")) if number else Decimal(1)
        if unit in _SMALL:
            group += value * _SMALL[unit]
        elif unit in _BIG:
            total += (group + (value if number else 0)) * _BIG[unit] if group else value * _BIG[unit]
            group = Decimal(0)
        else:
            group += value
    return total + group


def won_amounts(quote: str) -> set[int]:
    """근거 문장의 금액(원). 「6천만원」「2.5억원」「1억 5천만원」「5,000만 원」「200,000원」을 읽습니다. 금액 단위·「원」이 없는 숫자는 금액이 아닙니다."""
    text = unicodedata.normalize("NFKC", quote or "")
    found: set[int] = set()
    for match in _MONEY.finditer(text):
        total = _korean_amount(match.group(1))
        if total == total.to_integral_value() and total > 0:
            found.add(int(total))
    return found


def has_won(quote: str, won: int | None) -> bool:
    return won is None or won in won_amounts(quote)


def manwon(won: int | None) -> int | None:
    """원 → 만 원 정수. 만 원 단위로 나눠떨어지지 않으면 None(초안 단위가 만 원이라 잘라 쓰지 않음)."""
    if won is None or won % 10000:
        return None
    return won // 10000


class LocalRegions:
    """정책을 등록한 기관의 시도로 시도 없는 지역 이름(「북구」는 여러 시도에 있음)을 찾습니다(2026-10-25).
    기본 시도로 찾은 결과는 시군구까지 정해졌을 때만 씁니다(「관내」같은 말이 시도 전체로 잡히지 않게)."""

    def __init__(self, table: Any, sido: str | None):
        self.table = table
        self.sido = sido

    def lookup(self, name: str) -> Any:
        first = self.table.lookup(name)
        if (first.code is not None and not first.note) or not self.sido:
            return first
        local = self.table.lookup(name, default_sido=self.sido)
        return local if local.code is not None and not local.note and local.level in ("SIGUNGU", "DONG") else first


def _local_regions(regions: Any, record: dict[str, Any]) -> Any:
    if regions is None:
        return None
    for key in ("rgtrHghrkInstCdNm", "rgtrUpInstCdNm"):
        name = str(record.get(key) or "").strip()
        if name and (match := regions.lookup(name)).level == "SIDO" and not match.note:
            return LocalRegions(regions, name)
    return regions


def region_code(regions: Any, name: str) -> str | None:
    """지역 이름 → 시도 2자리 또는 시군구 5자리. 못 찾거나 모호하면 None."""
    if regions is None or not name or not name.strip():
        return None
    match = regions.lookup(name)
    if match is None or match.code is None or match.note:
        return None
    if match.level == "SIDO":
        return match.code[:2]
    if match.level in ("SIGUNGU", "DONG"):
        return match.code[:5]
    return None


def _regions(regions: Any, names: list[str]) -> list[str] | None:
    codes = [region_code(regions, n) for n in names]
    if not codes or any(c is None for c in codes):
        return None
    return list(dict.fromkeys(c for c in codes if c))


def _int(value: Any) -> int | None:
    if value is None:
        return None
    if isinstance(value, float) and not value.is_integer():
        return None
    return int(value)


def ai_value(item: dict[str, Any], regions: Any) -> dict[str, Any] | None:
    """AI 조건 한 줄 → 계약의 value. 값이 근거에 없거나 못 바꾸면 None(known=false)."""
    kind, quote = item["type"], item["evidence"]
    low, high = _int(item.get("min")), _int(item.get("max"))

    def numbers_ok(*values: int | None) -> bool:
        return all(has_number(quote, v) for v in values)

    if kind in TEXT_ONLY:
        return None
    if kind in ENUMS:
        values = [v for v in item.get("values") or [] if v in ENUMS[kind]]
        if not values:
            return None
        value: dict[str, Any] = {"in": list(dict.fromkeys(values))}
        if kind == "PREGNANCY" and item.get("months") is not None:
            if not numbers_ok(item["months"]):
                return None
            value["months"] = item["months"]
        return value
    if kind == "AGE":
        # 「만 40세 미만」→ max 39처럼 근거와 숫자가 하나 다를 수 있어 미만·초과면 하나 차이를 인정
        low_ok = low is None or has_number(quote, low) or (has_number(quote, low - 1) and "초과" in quote)
        high_ok = high is None or has_number(quote, high) or (has_number(quote, high + 1) and "미만" in quote)
        if (low is None and high is None) or not (low_ok and high_ok):
            return None
        return {k: v for k, v in (("min", low), ("max", high)) if v is not None}
    if kind == "NEWLYWED_YEARS":
        return {"max": high} if high is not None and numbers_ok(high) else None
    if kind == "CHILDREN":
        value = {k: v for k, v in (("minCount", low), ("maxAgeOfYoungest", high)) if v is not None}
        return value if value and numbers_ok(low, high) else None
    if kind == "INCOME_MEDIAN_PCT":
        basis = item.get("basis") if item.get("basis") in ("HOUSEHOLD", "PERSONAL") else None
        return {"max": high, "basis": basis} if high is not None and basis and numbers_ok(high) else None
    if kind == "INCOME_AMOUNT":
        basis = item.get("basis") if item.get("basis") in ("HOUSEHOLD", "PERSONAL") else None
        unit = {"YEAR": "MANWON_PER_YEAR", "MONTH": "MANWON_PER_MONTH"}.get(item.get("amountPer") or "")
        lo, hi = item.get("amountMinWon"), item.get("amountMaxWon")
        if not basis or not unit or (lo is None and hi is None) or not (has_won(quote, lo) and has_won(quote, hi)):
            return None
        lo_m, hi_m = manwon(lo), manwon(hi)
        if (lo is not None and lo_m is None) or (hi is not None and hi_m is None):
            return None
        return {"unit": unit, "basis": basis, **{k: v for k, v in (("min", lo_m), ("max", hi_m)) if v is not None}}
    if kind == "ASSET_AMOUNT":
        top, asset = item.get("amountMaxWon"), item.get("assetKind")
        if top is None or asset is None or not has_won(quote, top) or manwon(top) is None:
            return None
        return {"max": manwon(top), "unit": "MANWON", "kind": asset}
    if kind in ("RESIDENCE", "MOVE_IN", "RESIDENCE_MONTHS"):
        codes = _regions(regions, item.get("regionNames") or [])
        months = item.get("months")
        if kind == "RESIDENCE":
            return {"regions": codes} if codes else None
        if kind == "MOVE_IN":
            if not codes or not numbers_ok(months):
                return None
            return {"regions": codes, **({"withinMonths": months} if months is not None else {})}
        if months is None or not numbers_ok(months):
            return None
        return {"min": months, **({"regions": codes} if codes else {})}
    if kind == "HOMELESS":
        basis = {"PERSON": "PERSON", "PERSONAL": "PERSON", "HOUSEHOLD": "HOUSEHOLD"}.get(item.get("basis") or "")
        return {"basis": basis} if basis else None
    if kind == "HOMELESS_YEARS":
        return {"min": low} if low is not None and numbers_ok(low) else None
    if kind == "FIRST_HOME":
        return {}
    if kind == "LEASE":
        kinds = [k for k in item.get("leaseKinds") or [] if k in ("MONTHLY", "JEONSE")]
        deposit, monthly = item.get("depositMaxWon"), item.get("monthlyMaxWon")
        if not (has_won(quote, deposit) and has_won(quote, monthly)):
            return None
        if (deposit is not None and manwon(deposit) is None) or (monthly is not None and manwon(monthly) is None):
            return None
        value = {"unit": "MANWON"}
        if kinds:
            value["kind"] = list(dict.fromkeys(kinds))
        if deposit is not None:
            value["depositMax"] = manwon(deposit)
        if monthly is not None:
            value["monthlyMax"] = manwon(monthly)
        return value if len(value) > 1 else None
    if kind == "WORKPLACE":
        value = {}
        if "SME" in (item.get("values") or []) and "중소" in quote:  # 「대전 소재 직장」에 중소기업을 붙이지 않게(2026-10-25 시험)
            value["kind"] = ["SME"]
        if item.get("regionNames"):
            codes = _regions(regions, item["regionNames"])
            if not codes:
                return None
            value["regions"] = codes
        return value or None
    return None


def same_value(kind: str, rule: dict[str, Any] | None, ai: dict[str, Any] | None) -> bool:
    """규칙 값과 AI 값이 같은 뜻인지. 지역은 한쪽이 다른 쪽을 포함하면 같다고 봅니다(규칙 시군구 목록 ↔ AI 시도 「대구광역시」)."""
    if rule == ai:
        return True
    if not isinstance(rule, dict) or not isinstance(ai, dict):
        return False
    if "regions" in rule and "regions" in ai:
        left, right = set(rule["regions"]), set(ai["regions"])
        covered = all(any(r.startswith(a) or a.startswith(r) for a in right) for r in left)
        rest_same = {k: v for k, v in rule.items() if k != "regions"} == {k: v for k, v in ai.items() if k != "regions"}
        return covered and rest_same
    return False


def _support(item: dict[str, Any], sources: list[str]) -> tuple[dict[str, Any], bool, bool]:
    """AI 지원 내용 한 줄 → 계약 모양, 근거가 원문에 있었는지, 금액을 근거 밖에서 찾았는지(확인 필요).
    지원 금액·대출 한도는 핵심 값이라(2026-10-25 사용자 결정) 근거 문장에 없어도 같은 금액이 원문 다른 줄에 있으면 채웁니다
    (「(보증금) 연 1.3%」를 근거로 들고 한도 「최대 4,500만원」은 다음 줄에 있는 경우). 원문 어디에도 없는 금액·개월·비율은 비웁니다."""
    evidence_ok = quoted(item["evidence"], sources)
    quote = item["evidence"] if evidence_ok else ""
    amount = item.get("amountMaxWon")
    amount_ok = amount is not None and has_won(quote, amount) and manwon(amount) is not None
    elsewhere = False
    if evidence_ok and amount is not None and not amount_ok and manwon(amount) is not None:
        elsewhere = amount_ok = any(has_won(line, amount) for source in sources for line in source.splitlines())
    months = item.get("months") if item.get("months") is not None and has_number(quote, item["months"]) else None
    if evidence_ok and item.get("months") is not None and months is None and elsewhere:
        months = item["months"] if any(has_number(line, item["months"]) and "개월" in line for source in sources for line in source.splitlines()) else None
    rate = item.get("ratePct") if item.get("ratePct") is not None and has_number(quote, item["ratePct"]) else None
    return (
        {
            "kind": item["kind"],
            "amountMax": manwon(amount) if amount_ok else None,
            "amountPer": item.get("amountPer") if amount_ok else None,
            "months": months,
            "ratePct": rate,
            "rawText": item["rawText"].strip() or item["evidence"].strip(),
            "evidence": item["evidence"].strip(),
        },
        evidence_ok,
        elsewhere,
    )


def _date(value: Any, sources_text: str) -> str | None:
    """AI 날짜는 연·월·일 숫자가 원문에 있을 때만."""
    if not value or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(value)):
        return None
    year, month, day = (int(p) for p in str(value).split("-"))
    numbers = {n.lstrip("0") or "0" for n in re.findall(r"\d+", sources_text)}
    compact = f"{year:04d}{month:02d}{day:02d}"
    if compact in re.sub(r"\D", "", sources_text):
        return str(value)
    if (str(year) in numbers or str(year % 100) in numbers) and str(month) in numbers and str(day) in numbers:
        return str(value)
    return None


def _source(payload: dict[str, Any], regions: Any) -> dict[str, Any]:
    """통일 기본 칸은 수집기 값 그대로. 지역 코드가 비고 이름만 있으면 지역표로 채웁니다(초안 6장 1번)."""
    codes = [c for c in payload.get("regionCodes") or [] if re.fullmatch(r"\d{5}", str(c))]
    if not codes and payload.get("regionNames"):
        filled = _regions(regions, payload["regionNames"])
        codes = filled or []
    return {
        "title": payload["title"],
        "agency": payload.get("agency"),
        "regionScope": payload.get("regionScope") or "UNKNOWN",
        "regionNames": list(payload.get("regionNames") or []),
        "regionCodes": codes,
        "categories": list(payload.get("categories") or []),
        "detailUrl": payload.get("detailUrl"),
        "applyUrl": payload.get("applyUrl"),
    }


def _same_sentence(left: str, right: str) -> bool:
    a, b = _norm(left), _norm(right)
    return bool(a) and bool(b) and (a in b or b in a)


def _root(parent: list[int], i: int) -> int:
    while parent[i] != i:
        parent[i] = parent[parent[i]]
        i = parent[i]
    return i


def _split_or_groups(
    ai_rows: list[tuple[tuple, dict[str, Any]]],
    covered: dict[int, list[tuple[str, str]]],
    rule_types: dict[str, dict[str, Any]],
) -> tuple[list[tuple[tuple, dict[str, Any]]], dict[int, tuple]]:
    """AI 묶음 안에서 실제로 「또는」인 줄만 같이 둡니다. AI가 관계없는 조건을 모두 같은 번호로 주는 일이 있어(2026-10-25 시험),
    같은 type이거나 근거 문장이 겹치는(한쪽이 다른 쪽을 포함) 줄끼리만 이어 붙이고, 이어지지 않는 줄은 다른 묶음으로 나눕니다.
    규칙 줄로 대신한 AI 줄(covered)도 같은 방식으로 이어지면 그 규칙 묶음을 이 묶음으로 옮깁니다(돌려주는 moved: 규칙 묶음 번호 → 새 키)."""
    members: dict[int, list[tuple[str, str, int | None]]] = {}  # AI 묶음 → (type, 근거, ai_rows 위치 또는 규칙이면 None)
    for position, (key, row) in enumerate(ai_rows):
        if key[0] == "ai":
            members.setdefault(key[1], []).append((row["type"], row["evidence"], position))
    for group, items in covered.items():
        if group in members:  # 그 AI 묶음에 남은 줄이 있을 때만 「또는」 관계일 수 있음
            members[group].extend((kind, evidence, None) for kind, evidence in items)
    new_keys: dict[int, tuple] = {}
    moved: dict[int, tuple] = {}
    for group, items in members.items():
        parent = list(range(len(items)))
        for i in range(len(items)):
            for j in range(i + 1, len(items)):
                if items[i][0] == items[j][0] or _same_sentence(items[i][1], items[j][1]):
                    parent[_root(parent, i)] = _root(parent, j)
        for i, (kind, _, position) in enumerate(items):
            key = ("ai", group, _root(parent, i))
            if position is not None:
                new_keys[position] = key
            elif any(_root(parent, k) == _root(parent, i) and items[k][2] is not None for k in range(len(items))):
                moved.setdefault(rule_types[kind]["group"], key)
    rows = [(new_keys.get(position, key), row) for position, (key, row) in enumerate(ai_rows)]
    return rows, moved


WON_FIELDS = ("amountMinWon", "amountMaxWon", "depositMaxWon", "monthlyMaxWon")  # AI 조건 답에서 원 단위 금액 칸


def _leftover_amounts(items: list[dict[str, Any]]) -> set[str]:
    """금액이 남는 근거 문장(정규화). 같은 문장을 근거로 든 조건들이 그 문장의 금액을 다 쓰지 않으면, 남은 금액은 조건부 금액일 수 있습니다
    (「전세 보증금 5억원(지방 4억원 이하)」에서 5억만 쓰면 지방 거주자에게 너그럽게 틀림, 2026-10-25 시험). 그런 문장의 금액 값은 비웁니다.
    「연소득 6천만원(부부합산 8천만원)」을 두 조건이 나눠 쓰면 남는 금액이 없어 그대로 둡니다."""
    used: dict[str, set[int]] = {}
    for item in items:
        won = {int(item[k]) for k in WON_FIELDS if isinstance(item.get(k), int | float) and item[k] > 0}
        if won:
            used.setdefault(_norm(item["evidence"]), set()).update(won)
    return {quote for quote, won in used.items() if won_amounts(quote) - won}


def _settle_age(rule_rows: list[dict[str, Any]], items: list[dict[str, Any]], sources: list[str], regions: Any) -> str | None:
    """나이는 비우지 않습니다(2026-10-25 사용자 결정: 핵심 값은 반드시 채움). 출처 코드 칸 나이와 AI가 원문 글에서 읽은 나이가 다르면
    원문 값을 쓰고 "RULE_AI_CONFLICT"(확인 필요)를 돌려줍니다(「만18세이상, 만40세미만」인데 코드 칸은 19~39인 경우).
    원문 값이 근거로 확인되지 않거나 여러 개(전형별)면 코드 칸 값을 그대로 둡니다."""
    rule = next((row for row in rule_rows if row["type"] == "AGE" and row["known"]), None)
    if rule is None:
        return None
    rule_lines = {_norm(row["evidence"]) for row in rule_rows}
    read = []
    for item in items:
        if item["type"] == "AGE" and quoted(item["evidence"], sources) and _norm(item["evidence"]) not in rule_lines:
            if (value := ai_value(item, regions)) is not None:
                read.append((value, item))
    if not read or any(value == rule["value"] for value, _ in read):
        return None
    if len({json.dumps(value, sort_keys=True) for value, _ in read}) > 1:
        return "RULE_AI_CONFLICT"
    value, item = read[0]
    rule.update(
        value=value,
        origin="AI",
        rawText=(item["rawText"] or item["evidence"]).strip(),
        evidence=item["evidence"].strip(),
        confidence=round(min(float(item["confidence"]), 1.0), 2),
    )
    return "RULE_AI_CONFLICT"


def _merge_ages(conditions: list[dict[str, Any]]) -> None:
    """AI가 나이를 여러 묶음(「그리고」)에 나눠 담으면(「19~34세는 복지로, 35~39세는 인천청년포털」) 이어지는 범위를 한 줄로 합칩니다.
    나이는 비우지 않습니다. 범위가 이어지지 않으면(19~24, 30~34) 첫 줄만 남기고 나머지는 글로 둡니다."""
    ages = [row for row in conditions if row["type"] == "AGE" and row["known"]]
    if len({row["group"] for row in ages}) < 2:
        return
    ranges = sorted((row["value"].get("min", 0), row["value"].get("max", 200)) for row in ages)
    joined = all(nxt[0] <= cur[1] + 1 for cur, nxt in zip(ranges, ranges[1:], strict=False))
    keep = ages[0]
    if joined:
        low, high = min(r[0] for r in ranges), max(r[1] for r in ranges)
        keep["value"] = {k: v for k, v in (("min", low if low else None), ("max", high if high < 200 else None)) if v is not None}
        keep["rawText"] = " / ".join(row["rawText"] for row in ages)
    for row in ages[1:]:
        conditions.remove(row)


def _settle_income(rule_rows: list[dict[str, Any]], income: dict[str, int], items: list[dict[str, Any]], sources: list[str], regions: Any) -> str | None:
    """출처 칸 연소득(기준 모름)과 AI가 글에서 읽은 소득 조건을 맞춥니다. 같은 금액을 읽었으면 규칙 줄을 빼고 AI 줄(기준 포함)을 쓰며 None,
    다른 금액만 읽었으면 "RULE_AI_CONFLICT"(규칙 줄은 값 없는 채로 남고 AI 소득 줄은 버려짐), 글에 소득 조건이 없으면 None(규칙 줄은 값 없이 남음).
    출처 칸 줄(「소득 최대 금액: 5000」)을 근거로 든 AI 줄은 기준을 지어낸 것이라 세지 않습니다.
    (2026-10-25 핵심 값 결정 뒤) 원문 소득 줄이 있으면 금액이 달라도 원문 줄을 쓰고, 없으면 가구 기준으로 채워 "LOW_CONFIDENCE"."""
    if not income or not any(row["type"] == "INCOME_AMOUNT" and not row["known"] for row in rule_rows):
        return None
    rule_lines = {_norm(row["evidence"]) for row in rule_rows}
    values = [
        ai_value(item, regions)
        for item in items
        if item["type"] == "INCOME_AMOUNT" and quoted(item["evidence"], sources) and _norm(item["evidence"]) not in rule_lines
    ]
    values = [v for v in values if v is not None and v.get("unit") == "MANWON_PER_YEAR"]
    if values:  # 원문 글의 소득 줄(기준 포함)을 씁니다. 금액이 코드 칸과 다르면 확인 필요
        rule_rows[:] = [row for row in rule_rows if row["type"] != "INCOME_AMOUNT"]
        return None if any(all(v.get(k) == amount for k, amount in income.items()) for v in values) else "RULE_AI_CONFLICT"
    # 원문에 기준이 없으면 가구(부부합산) 기준으로 채우고 확인 필요(2026-10-25 사용자 결정: 주거 대출·지원은 부부합산이 많음)
    row = next(row for row in rule_rows if row["type"] == "INCOME_AMOUNT")
    row.update(value={"unit": "MANWON_PER_YEAR", "basis": "HOUSEHOLD", **income}, known=True, confidence=0.5)
    return "LOW_CONFIDENCE"


def build_payload(payload: dict[str, Any], reading: BenefitReading, rules: RuleResult, regions: Any, *, now: datetime | None = None) -> dict[str, Any]:
    """policy.extracted payload. reading은 AI 답, rules는 출처 코드 칸에서 옮긴 조건·일정."""
    answer = reading.answer
    regions = _local_regions(regions, payload.get("record") or {})
    text = str(payload.get("text") or "")
    record_lines = [f"{k}: {v}" for k, v in (payload.get("record") or {}).items() if isinstance(v, str | int)]
    sources = [text, *record_lines]
    reasons: list[str] = []
    failed_evidence = 0

    # 조건: 규칙 줄 먼저, AI 줄은 그 뒤. 규칙에 있는 type의 AI 줄은 버리되 값이 다르면 충돌로 표시하고,
    # 핵심 값(CORE)이면 원문 글에서 읽은 값으로 바꾸고 아니면 규칙 값도 비움.
    # 묶음(group)은 같은 번호끼리 「또는」이라 아래 세 가지를 지킵니다(2026-10-25 첫 유료 시험에서 판정이 틀어진 경우):
    # - AI가 「또는」 묶음에 넣은 줄을 규칙 줄로 대신하면, 그 규칙 줄(규칙 묶음째)을 AI 묶음으로 옮깁니다.
    #   안 옮기면 「대전 거주 또는 대전 소재 대학·직장」이 「거주 그리고 (대학 또는 직장)」으로 더 엄격해집니다
    # - 글로만 보여 주는 줄(TEXT_ONLY)은 판정할 수 없어 「또는」에 끼면 묶음 전체가 흐려지므로 하나씩 따로 묶습니다
    # - AI 묶음 안에서도 같은 type이거나 근거 문장이 겹치는 줄만 「또는」으로 둡니다(_split_or_groups)
    # - 규칙 줄의 근거 줄을 그대로 다시 낸 글 줄은 같은 조건이 두 번 보이므로 버립니다
    rule_rows = [dict(c) for c in rules.conditions]
    if conflict := _settle_income(rule_rows, rules.income, answer.get("conditions") or [], sources, regions):
        reasons.append(conflict)
    if conflict := _settle_age(rule_rows, answer.get("conditions") or [], sources, regions):
        reasons.append(conflict)
    rule_types: dict[str, dict[str, Any]] = {}
    for row in rule_rows:
        rule_types.setdefault(row["type"], row)
    rule_evidence = {_norm(row["evidence"]) for row in rule_rows}
    ai_rows: list[tuple[tuple[str, int], dict[str, Any]]] = []
    covered: dict[int, list[tuple[str, str]]] = {}  # AI 묶음 번호 → 그 묶음에서 규칙 줄로 대신한 (type, 근거)
    leftover = _leftover_amounts(answer.get("conditions") or [])
    for index, item in enumerate(answer.get("conditions") or []):
        evidence_ok = quoted(item["evidence"], sources)
        if evidence_ok and item["type"] in DEFAULT_BASIS and item.get("basis") not in ("HOUSEHOLD", "PERSONAL", "PERSON"):
            # 원문에 기준(가구·본인)이 없는 핵심 값은 기본 기준으로 채우고 확인 필요(2026-10-25 사용자 결정)
            item = {**item, "basis": DEFAULT_BASIS[item["type"]]}
            if ai_value(item, regions) is not None:
                reasons.append("LOW_CONFIDENCE")
        value = ai_value(item, regions) if evidence_ok else None
        if value is not None and _norm(item["evidence"]) in leftover and any(item.get(k) for k in WON_FIELDS):
            if item["type"] in CORE:
                reasons.append("LOW_CONFIDENCE")  # 근거 문장에 다른 금액(조건부 금액일 수 있음)이 있음: 채우고 확인 필요
            else:
                value = None  # 근거 문장의 다른 금액(조건부 금액일 수 있음)을 쓰지 않음
        if not evidence_ok:
            failed_evidence += 1
        if item["type"] in rule_types:
            rule = rule_types[item["type"]]
            if value is not None and rule["known"] and item["type"] != "AGE" and not same_value(item["type"], rule["value"], value):
                reasons.append("RULE_AI_CONFLICT")
                if item["type"] in CORE:  # 핵심 값은 비우지 않고 원문 글에서 읽은 값을 씀
                    rule.update(
                        value=value,
                        origin="AI",
                        rawText=(item["rawText"] or item["evidence"]).strip(),
                        evidence=item["evidence"].strip(),
                        confidence=round(min(float(item["confidence"]), 1.0), 2),
                    )
                else:  # 핵심 값이 아니면 확실하지 않으므로 비움
                    rule.update(known=False, value=None)
            covered.setdefault(item["group"], []).append((item["type"], item["evidence"]))
            continue
        if item["type"] in TEXT_ONLY and _norm(item["evidence"]) in rule_evidence:
            continue
        known = value is not None and item["type"] not in TEXT_ONLY
        key = ("solo", index) if item["type"] in TEXT_ONLY else ("ai", item["group"])
        ai_rows.append(
            (
                key,
                {
                    "type": item["type"],
                    "value": value if known else None,
                    "group": 0,
                    "known": known,
                    "origin": "AI",
                    "rawText": (item["rawText"] or item["evidence"]).strip() or item["type"],
                    "evidence": item["evidence"].strip() or item["rawText"].strip() or item["type"],
                    "confidence": round(min(float(item["confidence"]), 1.0) if evidence_ok else min(float(item["confidence"]), 0.3), 2),
                },
            )
        )
    ai_rows, moved = _split_or_groups(ai_rows, covered, rule_types)
    keyed = [(moved.get(row["group"], ("rule", row["group"])), row) for row in rule_rows] + ai_rows
    numbers: dict[tuple, int] = {}
    for key, row in keyed:
        row["group"] = numbers.setdefault(key, len(numbers) + 1)
    conditions = [row for _, row in keyed]
    # 같은 type이 서로 다른 묶음(「그리고」)에 나오면 전형별 조건입니다(「미혼 그리고 기혼」 「순자산 5.11억(구입) 그리고 3.45억(전세)」).
    # 이 형식은 「(A 그리고 B) 또는 (C 그리고 D)」를 나타내지 못해, 핵심 값은 한 묶음 「또는」으로 모아 어느 전형이든 맞으면 되게 하고
    # 확인 필요로 표시합니다(2026-10-25 사용자 결정). 핵심 값이 아니면 비웁니다
    _merge_ages(conditions)
    groups_by_type: dict[str, list[int]] = {}
    for row in conditions:
        if row["known"] and row["group"] not in groups_by_type.get(row["type"], []):
            groups_by_type.setdefault(row["type"], []).append(row["group"])
    for row in conditions:
        groups = groups_by_type.get(row["type"], [])
        if row["known"] and len(groups) > 1:
            if row["type"] in CORE:
                row["group"] = groups[0]
                reasons.append("LOW_CONFIDENCE")
            else:
                row.update(known=False, value=None)
    # 같은 묶음(「또는」)에 type·기준이 같고 금액만 다른 줄이 있으면 조건부 금액입니다(「부부합산 1.3억, 맞벌이는 2억」).
    # 핵심 값은 「또는」 그대로 두고(어느 쪽이든 맞으면 됨) 확인 필요, 핵심 값이 아니면 큰 금액이 모두에게 적용되어 너그럽게 틀리므로 비웁니다
    same_kind: dict[tuple, list[dict[str, Any]]] = {}
    for row in conditions:
        if row["known"] and isinstance(row["value"], dict) and row["type"] != "AGE":  # 나이 범위의 「또는」은 그대로 맞는 뜻
            kind = json.dumps({k: v for k, v in row["value"].items() if k not in AMOUNT_KEYS}, sort_keys=True, ensure_ascii=False)
            same_kind.setdefault((row["group"], row["type"], kind), []).append(row)
    for rows in same_kind.values():
        if len(rows) > 1 and len({json.dumps(r["value"], sort_keys=True) for r in rows}) > 1:
            if rows[0]["type"] in CORE:
                reasons.append("LOW_CONFIDENCE")
                continue
            for row in rows:
                row.update(known=False, value=None)
    numbers = {}
    for row in conditions:  # 묶음 번호를 나온 차례대로 다시 매김
        row["group"] = numbers.setdefault(row["group"], len(numbers) + 1)

    supports = []
    for item in answer.get("supports") or []:
        made, evidence_ok, elsewhere = _support(item, sources)
        if not evidence_ok:
            failed_evidence += 1
        if elsewhere:
            reasons.append("LOW_CONFIDENCE")
        if made["evidence"]:
            supports.append(made)

    sources_text = "\n".join(sources)
    ai_schedule = answer.get("schedule") or {}
    schedule = {
        "applyStart": _date(ai_schedule.get("applyStart"), sources_text),
        "applyEnd": _date(ai_schedule.get("applyEnd"), sources_text),
        "applyAlways": bool(ai_schedule.get("applyAlways")),
        "businessStart": _date(ai_schedule.get("businessStart"), sources_text),
        "businessEnd": _date(ai_schedule.get("businessEnd"), sources_text),
        "rawText": ai_schedule.get("rawText"),
    }
    schedule.update({k: v for k, v in rules.schedule.items() if v is not None})  # 규칙 값이 AI 값보다 우선

    if failed_evidence:
        reasons.append("EVIDENCE_NOT_FOUND")
    if not payload.get("hasDocument"):
        reasons.append("NO_DOCUMENT")
    if reading.truncated:
        reasons.append("TEXT_TRUNCATED")
    confidence = 0.9
    if not payload.get("hasDocument") and not payload.get("hasOriginText"):
        confidence -= 0.2  # API 항목만으로 추출
    confidence -= 0.1 * failed_evidence
    if reading.truncated:
        confidence = min(confidence, 0.5)
    confidence = round(max(confidence, 0.1), 2)
    if confidence < LOW_CONFIDENCE:
        reasons.append("LOW_CONFIDENCE")
    reasons = list(dict.fromkeys(reasons))
    needs_review = any(r in reasons for r in ("EVIDENCE_NOT_FOUND", "LOW_CONFIDENCE", "RULE_AI_CONFLICT", "TEXT_TRUNCATED"))

    summary = answer.get("summary")
    if summary:
        summary = summary.strip()[:80] or None
    return {
        "rawPolicyId": payload["rawPolicyId"],
        "version": payload["version"],
        "sourceCode": payload["sourceCode"],
        "provider": payload["provider"],
        "policyId": payload["policyId"],
        "field": payload["field"],
        "reprocess": bool(payload.get("reprocess")),
        "source": _source(payload, regions),
        "relevant": bool(answer["relevant"]),
        "relevanceReason": answer["relevanceReason"].strip() or ("주거·이주 혜택" if answer["relevant"] else "범위 밖"),
        "summary": summary,
        "supports": supports,
        "schedule": schedule,
        "howToApply": (answer.get("howToApply") or "").strip() or rules.how_to_apply,
        # 제출 서류는 출처 칸 목록이 있으면 그것을(AI는 그 칸을 못 봄), 없으면 AI가 글에서 읽은 목록
        "documents": rules.documents or [d.strip() for d in answer.get("documents") or [] if d and d.strip()],
        "contact": (answer.get("contact") or "").strip() or rules.contact,
        "conditions": conditions,
        "quality": {
            "confidence": confidence,
            "needsReview": needs_review,
            "reviewReasons": reasons,
            "hasDocument": bool(payload.get("hasDocument")),
            "documentStatus": str(payload.get("documentStatus") or "NONE"),
            "model": reading.model,
            "promptVersion": PROMPT_VERSION,
            "extractedAt": (now or datetime.now(UTC)).isoformat().replace("+00:00", "Z"),
        },
    }
