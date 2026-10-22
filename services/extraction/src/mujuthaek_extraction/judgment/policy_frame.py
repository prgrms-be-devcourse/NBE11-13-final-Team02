"""정책 공고 AI 읽기(rules/policy/reading.py) → 창업지원·사업공간 판정 틀. 코드만 씁니다.

AI는 중간 형식으로 답하고 이 모듈이 틀의 칸으로 옮깁니다. 확신이 없으면 그 조건은 inReview입니다.
- 근거 문장(quote)이 원문 글에 그대로 없으면 확인 필요
- 나이·연수·날짜·금액·업종 코드·지역 이름이 근거 문장에 없으면 확인 필요(AI가 값을 바꿨을 수 있음)
- 업력 날짜 범위는 공고가 찍은 날짜가 있으면 그대로, 연수만 있으면 기준일에서 틀 문서 규칙대로 계산(기준일이 없으면 질문)
- 틀에 칸이 없는 조건(사업공간 틀의 업력 상한·매출 등)은 근거 문장을 묻는 질문(ASK)
"""

from __future__ import annotations

import re
import unicodedata
from datetime import date, timedelta
from typing import Any

STARTUP_STATUS = {"SME", "WOMAN", "SMALL_BUSINESS", "VENTURE_COMPANY", "SOCIAL_VENTURE", "DISABLED", "ONE_PERSON_CREATIVE", "STUDENT"}
STARTUP_EXCLUDED = {"CREDIT_DELINQUENT", "TAX_ARREARS", "CLAWBACK_UNPAID", "PROGRAM_SANCTION", "BUSINESS_SUSPENDED", "WAGE_ARREARS", "SUBSIDY_EXCLUDED"}
SPACE_STATUS = {
    "SMALL_BUSINESS",
    "CAREER_BREAK_WOMAN",
    "SOCIAL_ENTERPRISE",
    "PRE_SOCIAL_ENTERPRISE",
    "SOCIAL_COOPERATIVE",
    "VILLAGE_ENTERPRISE",
    "SELF_SUFFICIENCY_ENTERPRISE",
    "SOCIAL_VENTURE",
    "NATIONAL_MERIT",
}
SPACE_EXCLUDED = {"TAX_ARREARS", "CREDIT_DELINQUENT", "BUSINESS_SUSPENDED"}
EXCLUDING_KINDS = {"EXCLUDED_STATUS", "NO_RECENT_CLOSURE", "INDUSTRY_EXCLUDE"}

QUOTE_MISSING = "근거 문장을 공고 글에서 찾지 못했습니다"
VALUE_MISSING = "값이 근거 문장에 없습니다(AI가 값을 바꿨을 수 있음)"
TRUNCATED = "공고 글이 길어 앞부분만 읽었습니다"
NO_REFERENCE = "기준일을 정하지 못해 연수를 개업일 범위로 바꿀 수 없습니다"
NO_REGION = "지역 이름을 법정동 코드로 바꾸지 못했습니다"


def _norm(text: str) -> str:
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", text or ""))


def _quoted(quote: str | None, text: str) -> bool:
    return bool(quote) and len(_norm(quote)) >= 4 and _norm(quote) in _norm(text)


def _numbers(text: str) -> set[str]:
    return set(re.findall(r"\d+", (text or "").replace(",", "")))


def _has_number(quote: str, value: float | int | None) -> bool:
    if value is None:
        return True
    number = int(value) if float(value).is_integer() else value
    return str(number) in _numbers(quote) or str(value) in (quote or "")


def _has_date(quote: str, value: str | None) -> bool:
    """「2023년 1월 23일」「'23.1.23.」「2023-01-23」 어느 꼴이든 연·월·일 숫자가 근거 문장에 있는지."""
    if not value:
        return True
    try:
        day = date.fromisoformat(value)
    except ValueError:
        return False
    numbers = {n.lstrip("0") or "0" for n in _numbers(quote)}
    year_ok = str(day.year) in numbers or str(day.year % 100) in numbers
    return year_ok and str(day.month) in numbers and str(day.day) in numbers


def _has_won(quote: str, won: int | None) -> bool:
    """금액이 원·만 원·억 원 어느 단위로든 근거 문장에 있는지."""
    if won is None:
        return True
    plain = (quote or "").replace(",", "")
    candidates = {str(won)}
    if won % 10000 == 0:
        candidates.add(str(won // 10000))
    if won % 100000000 == 0:
        candidates.add(str(won // 100000000))
    elif won % 10000000 == 0:
        candidates.add(f"{won / 100000000:g}")
    return any(re.search(rf"(?<![\d.]){re.escape(c)}(?![\d])", plain) for c in candidates)


def _years_ago(day: date, years: int) -> date:
    try:
        return day.replace(year=day.year - years)
    except ValueError:  # 2월 29일이 없는 해
        return day.replace(year=day.year - years, day=28)


def opened_range(reference: date, years: int, rule: str) -> tuple[str | None, str | None]:
    """틀 문서 「업력 날짜 정하는 법」: N년 이내 = N년 전 같은 날~기준일, 미만 = 다음 날부터, 이상 = ~N년 전 같은 날, 초과 = ~전날."""
    back = _years_ago(reference, years)
    if rule == "WITHIN":
        return back.isoformat(), reference.isoformat()
    if rule == "UNDER":
        return (back + timedelta(days=1)).isoformat(), reference.isoformat()
    if rule == "AT_LEAST":
        return None, back.isoformat()
    return None, (back - timedelta(days=1)).isoformat()  # OVER


class _Builder:
    def __init__(self, reading: Any, text: str, regions: Any, *, startup: bool):
        self.answer, self.text, self.regions, self.startup = reading.answer, text, regions, startup
        self.truncated = reading.truncated
        self.used: set[str] = set()
        self.keys = {g["key"] for g in self.answer["groups"]}

    def new_id(self, base: str) -> str:
        name, n = base, 1
        while name in self.used:
            n += 1
            name = f"{base}-{n}"
        self.used.add(name)
        return name

    def base(self, kind: str, quote: str | None) -> dict[str, Any]:
        made = {"id": self.new_id(kind.lower().replace("_", "-")), "kind": kind, "quote": quote, "page": None, "inReview": False, "reviewReason": None}
        return made | {"asOf": None} if self.startup else made

    @staticmethod
    def flag(criterion: dict[str, Any], reasons: list[str]) -> dict[str, Any]:
        reasons = list(dict.fromkeys(r for r in [*(criterion.get("reviewReason") or "").split("; "), *reasons] if r))
        return criterion | {"inReview": bool(reasons), "reviewReason": "; ".join(reasons) or None}

    def ask(self, item: dict[str, Any], quote: str, reasons: list[str]) -> dict[str, Any]:
        met = item.get("metWhen") or ("NO" if item["kind"] in EXCLUDING_KINDS else "YES")
        question = item.get("question") or (f"다음 신청 제외 대상에 해당하나요? 「{quote}」" if met == "NO" else f"다음 조건에 해당하나요? 「{quote}」")
        return self.flag(self.base("ASK", quote) | {"question": question, "metWhen": met}, reasons)

    def reference(self) -> date | None:
        """판정 기준일: 「공고일 기준」이면 공고일, 찍힌 기준일이면 그 날, 문구가 없으면 None. 근거 문장에 그 날짜가 있어야 씁니다."""
        rule = self.answer["referenceRule"]
        found = self.answer["announcedOn"] if rule == "ANNOUNCEMENT" else self.answer["referenceDate"] if rule == "EXPLICIT" else None
        return self._checked_date(found)

    def announced(self) -> date | None:
        return self._checked_date(self.answer["announcedOn"])

    def _checked_date(self, found: dict[str, Any] | None) -> date | None:
        if not found or not found.get("date") or not _quoted(found.get("quote"), self.text) or not _has_date(found["quote"], found["date"]):
            return None
        try:
            return date.fromisoformat(found["date"])
        except ValueError:
            return None

    def region_codes(self, names: list[str]) -> list[str] | None:
        codes = []
        for name in names:
            match = self.regions.lookup(name) if self.regions is not None else None
            if match is None or match.code is None or match.note:
                return None
            codes.append(match.code)
        return list(dict.fromkeys(codes)) or None

    def criterion(self, item: dict[str, Any], reference: date | None) -> dict[str, Any]:
        """AI 조건 하나 → 틀 조건 하나(못 담으면 질문)."""
        quote, kind = item["quote"], item["kind"]
        reasons = [] if _quoted(quote, self.text) else [QUOTE_MISSING]
        if self.truncated:
            reasons.append(TRUNCATED)
        if item["group"] is not None and item["group"] not in self.keys:
            reasons.append("대상군을 정하지 못했습니다")  # 없는 대상군을 가리킨 조건은 공통으로 두되 확인 필요
        if kind == "AGE" and (item["minAge"] is not None or item["maxAge"] is not None):
            seen = _has_number(quote, item["minAge"]) and (_has_number(quote, item["maxAge"]) or _has_number(quote, (item["maxAge"] or 0) + 1))
            return self.flag(self.base("AGE", quote) | {"minAge": item["minAge"], "maxAge": item["maxAge"]}, reasons + ([] if seen else [VALUE_MISSING]))
        if kind == "REGION" and item["regionNames"] and item["regionBasis"]:
            codes = self.region_codes(item["regionNames"])
            if codes is None:
                return self.ask(item, quote, reasons + [NO_REGION])
            seen = all(_norm(n)[:2] in _norm(quote) for n in item["regionNames"])
            return self.flag(self.base("REGION", quote) | {"basis": item["regionBasis"], "regionCodes": codes}, reasons + ([] if seen else [VALUE_MISSING]))
        if kind in ("PRE_FOUNDER", "REGISTERED"):
            made = self.base("BUSINESS_REGISTRATION", quote) | {"require": "NOT_REGISTERED" if kind == "PRE_FOUNDER" else "REGISTERED"}
            return self.flag(made if self.startup else made | {"closedWithinYears": None}, reasons)
        if kind == "BUSINESS_AGE" and self.startup:
            return self.business_age(item, quote, reasons, reference)
        if kind == "BUSINESS_AGE" and item["years"] and item["yearsRule"] and not item["question"]:
            # 사업공간 틀에는 업력 칸이 「N개월 이상」뿐이라 질문으로. 문장 전체 대신 업력만 묻습니다
            years = f"{item['years']:g}"
            question = {
                "WITHIN": f"사업을 시작한 날(법인은 설립등기일)부터 {years}년 이내인가요?",
                "UNDER": f"사업을 시작한 날(법인은 설립등기일)부터 {years}년이 지나지 않았나요?",
                "AT_LEAST": f"사업을 시작한 날(법인은 설립등기일)부터 {years}년 이상 지났나요?",
                "OVER": f"사업을 시작한 날(법인은 설립등기일)부터 {years}년이 넘게 지났나요?",
            }[item["yearsRule"]]
            seen = [] if _has_number(quote, item["years"]) else [VALUE_MISSING]
            return self.ask(item | {"question": question, "metWhen": "YES"}, quote, reasons + seen)
        if kind in ("NO_RECENT_CLOSURE", "CLOSURE_REQUIRED") and self.startup:
            return self.closure(item, quote, reasons, reference)
        if kind == "CLOSURE_REQUIRED" and not self.startup and isinstance(item["closedYears"], int | float) and float(item["closedYears"]).is_integer():
            seen = _has_number(quote, item["closedYears"])
            made = self.base("BUSINESS_REGISTRATION", quote) | {"require": "CLOSED", "closedWithinYears": int(item["closedYears"])}
            return self.flag(made, reasons + ([] if seen else [VALUE_MISSING]))
        if kind == "REVENUE" and self.startup and isinstance(item["revenueYear"], int) and (item["revenueMinWon"] or item["revenueMaxWon"]):
            seen = _has_won(quote, item["revenueMinWon"]) and _has_won(quote, item["revenueMaxWon"])
            made = self.base("REVENUE", quote) | {"year": item["revenueYear"], "minWon": item["revenueMinWon"], "maxWon": item["revenueMaxWon"]}
            return self.flag(made, reasons + ([] if seen else [VALUE_MISSING]))
        if kind in ("INDUSTRY_EXCLUDE", "INDUSTRY_ONLY") and item["industryCodes"]:
            codes = list(dict.fromkeys(item["industryCodes"]))
            if all(re.fullmatch(r"[0-9]{2,5}", c) and c in _numbers(quote) for c in codes):
                mode = "EXCLUDE" if kind == "INDUSTRY_EXCLUDE" else "ONLY"
                return self.flag(self.base("INDUSTRY", quote) | {"mode": mode, "revision": 11, "codes": codes, "maybeCodes": []}, reasons)
        if kind in ("STATUS", "EXCLUDED_STATUS") and item["statusCodes" if kind == "STATUS" else "excludedCodes"]:
            allowed = (STARTUP_STATUS if kind == "STATUS" else STARTUP_EXCLUDED) if self.startup else (SPACE_STATUS if kind == "STATUS" else SPACE_EXCLUDED)
            codes = list(dict.fromkeys(item["statusCodes" if kind == "STATUS" else "excludedCodes"]))
            if all(c in allowed for c in codes):
                return self.flag(self.base(kind, quote) | {"anyOf": codes}, reasons)
        return self.ask(item, quote, reasons)

    def business_age(self, item: dict[str, Any], quote: str, reasons: list[str], reference: date | None) -> dict[str, Any]:
        if item["openedFrom"] or item["openedTo"]:
            seen = _has_date(quote, item["openedFrom"]) and _has_date(quote, item["openedTo"])
            made = self.base("BUSINESS_AGE", quote) | {"openedFrom": item["openedFrom"], "openedTo": item["openedTo"]}
            return self.flag(made, reasons + ([] if seen else [VALUE_MISSING]))
        years, rule = item["years"], item["yearsRule"]
        if not isinstance(years, int | float) or not float(years).is_integer() or not rule:
            return self.ask(item, quote, reasons)
        if reference is None:
            return self.ask(item, quote, reasons + [NO_REFERENCE])
        opened_from, opened_to = opened_range(reference, int(years), rule)
        made = self.base("BUSINESS_AGE", quote) | {"openedFrom": opened_from, "openedTo": opened_to}
        return self.flag(made, reasons + ([] if _has_number(quote, years) else [VALUE_MISSING]))

    def closure(self, item: dict[str, Any], quote: str, reasons: list[str], reference: date | None) -> dict[str, Any]:
        mode = "NONE" if item["kind"] == "NO_RECENT_CLOSURE" else "ANY"
        if item["closedFrom"] or item["closedTo"]:
            seen = _has_date(quote, item["closedFrom"]) and _has_date(quote, item["closedTo"])
            made = self.base("CLOSURE", quote) | {"mode": mode, "closedFrom": item["closedFrom"], "closedTo": item["closedTo"]}
            return self.flag(made, reasons + ([] if seen else [VALUE_MISSING]))
        years = item["closedYears"]
        if isinstance(years, int | float) and float(years).is_integer() and reference is not None:
            made = self.base("CLOSURE", quote) | {"mode": mode, "closedFrom": _years_ago(reference, int(years)).isoformat(), "closedTo": None}
            return self.flag(made, reasons + ([] if _has_number(quote, years) else [VALUE_MISSING]))
        if years is None and mode == "ANY":
            return self.flag(self.base("CLOSURE", quote) | {"mode": "ANY", "closedFrom": None, "closedTo": None}, reasons)
        return self.ask(item, quote, reasons + ([] if reference is not None else [NO_REFERENCE]))

    def criteria(self, items: list[dict[str, Any]], reference: date | None) -> list[dict[str, Any]]:
        """같은 anyOf 묶음은 ANY_OF 하나로, 그 안에서 같은 option끼리는 한 길(모두 갖춰야 함). 길이 하나뿐이면 그 조건들 그대로."""
        made: list[dict[str, Any]] = []
        bundles: dict[str, list[dict[str, Any]]] = {}
        for item in items:
            if item["anyOf"]:
                if item["anyOf"] not in bundles:
                    bundles[item["anyOf"]] = []
                    made.append({"_bundle": item["anyOf"]})
                bundles[item["anyOf"]].append(item)
            else:
                made.append(self.criterion(item, reference))
        out = []
        for entry in made:
            if "_bundle" not in entry:
                out.append(entry)
                continue
            paths: dict[str, list[dict[str, Any]]] = {}
            for n, member in enumerate(bundles[entry["_bundle"]]):
                paths.setdefault(member.get("option") or f"_{n}", []).append(member)
            if len(paths) == 1:
                out.extend(self.criterion(m, reference) for m in next(iter(paths.values())))
                continue
            options = [{"label": _path_label(path, n), "criteria": [self.criterion(m, reference) for m in path]} for n, path in enumerate(paths.values(), 1)]
            out.append(self.base("ANY_OF", None) | {"options": options})
        return out

    def build(self, reference: date | None) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
        keys = {g["key"]: g for g in self.answer["groups"]}
        group_ids = {key: self.new_id(f"group-{key}") for key in keys}
        common_items = [c for c in self.answer["conditions"] if c["group"] not in keys]
        groups = [
            {"id": group_ids[key], "name": group["name"], "criteria": self.criteria([c for c in self.answer["conditions"] if c["group"] == key], reference)}
            for key, group in keys.items()
        ] or [{"id": self.new_id("all"), "name": "전체", "criteria": []}]
        common = self.criteria(common_items, reference)
        preferences = []
        for item in self.answer["preferences"]:
            quote = item["quote"]
            reasons = [] if _quoted(quote, self.text) else [QUOTE_MISSING]
            points = item["points"] if item["effect"] == "BONUS_POINTS" and isinstance(item["points"], int | float) and item["points"] > 0 else None
            if item["effect"] == "BONUS_POINTS" and points is None:
                continue  # 점수 없는 가점은 틀에 담을 수 없어 안내로
            ask = self.flag(self.base("ASK", quote) | {"question": item["question"] or f"다음에 해당하나요? 「{quote}」", "metWhen": "YES"}, reasons)
            preferences.append(
                {
                    "id": self.new_id("pref"),
                    "label": item["label"][:80] or quote[:80],
                    "groupId": group_ids.get(item["group"]),
                    "effect": item["effect"],
                    "points": points,
                    "criteria": [ask],
                    "quote": quote,
                    "page": None,
                }
            )
        notes = [
            {
                "kind": n["kind"],
                "groupId": group_ids.get(n["group"]),
                "text": n["text"],
                "quote": n["quote"] if _quoted(n["quote"], self.text) else None,
                "page": None,
            }
            for n in self.answer["notes"]
            if n["text"].strip()
        ]
        return common, groups, preferences, notes


def _path_label(path: list[dict[str, Any]], number: int) -> str:
    """길 이름: 조건 종류를 이어 붙인 짧은 이름(근거 문장은 길마다 같을 수 있어 쓰지 않음)."""
    names = {"BUSINESS_AGE": "업력", "AGE": "나이", "REGION": "지역", "PRE_FOUNDER": "예비창업자", "REGISTERED": "사업자", "STATUS": "유형"}
    parts = []
    for item in path:
        if item["kind"] == "BUSINESS_AGE" and item["years"]:
            parts.append(f"업력 {item['years']:g}년")
        else:
            parts.append(names.get(item["kind"], (item.get("question") or item["kind"])[:20]))
    return f"{number}번째 길: " + " + ".join(dict.fromkeys(parts))


def startup_frame(payload: dict[str, Any], reading: Any, regions: Any) -> dict[str, Any] | None:
    """AI 읽기 → 창업지원 틀. 창업 지원이 아니라고 읽었으면 None."""
    if not reading.answer["relevant"]:
        return None
    builder = _Builder(reading, str(payload.get("text") or ""), regions, startup=True)
    reference = builder.reference()
    common, groups, preferences, notes = builder.build(reference)
    return {
        "schema": "startup-support-judgment.v1",
        "rawPolicyId": payload["rawPolicyId"],
        "rawVersion": payload["version"],
        "referenceDate": reference.isoformat() if reference else None,
        "common": common,
        "groups": groups,
        "preferences": preferences,
        "notes": notes,
    }


def space_frame(payload: dict[str, Any], reading: Any, regions: Any) -> dict[str, Any] | None:
    """AI 읽기 → 사업공간 틀. 사업공간 틀은 기준일이 필수라, 기준일·공고일을 근거와 함께 읽지 못했으면 None."""
    if not reading.answer["relevant"]:
        return None
    builder = _Builder(reading, str(payload.get("text") or ""), regions, startup=False)
    reference = builder.reference() or builder.announced()
    if reference is None:
        return None
    common, groups, preferences, notes = builder.build(reference)
    return {
        "schema": "business-space-judgment.v1",
        "rawPostId": payload["rawPolicyId"],
        "rawVersion": payload["version"],
        "referenceDate": reference.isoformat(),
        "common": common,
        "groups": groups,
        "preferences": preferences,
        "notes": notes,
    }
