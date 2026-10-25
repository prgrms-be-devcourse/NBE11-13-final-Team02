"""혜택 정제 결과 만들기: 규칙 조건(출처 코드 칸) + AI 답 → policy.extracted payload.

틀린 값보다 빈 값이 낫다는 원칙으로, AI 값은 근거 문장(evidence)이 원문에 있고 값이 그 근거에 있을 때만 씁니다.
- 근거가 원문에 없으면 그 조건은 known=false(value null)로 바꾸고 EVIDENCE_NOT_FOUND
- 근거는 있는데 숫자·금액이 근거에 없거나, 지역 이름을 코드로 못 바꾸면 그 조건은 known=false (rawText는 남겨 화면에 보여 줌)
- 같은 type이 규칙과 AI 둘 다에서 나오면 규칙 줄만 보내고, 값이 다르면 RULE_AI_CONFLICT
quality.confidence는 AI 자기 보고가 아니라 코드가 확인한 결과로 매깁니다(공고 추출과 같은 원칙).
"""

from __future__ import annotations

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
LOW_CONFIDENCE = 0.6


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


_UNITS = {"억": 10**8, "천만": 10**7, "백만": 10**6, "십만": 10**5, "만": 10**4, "천": 10**3}
_MONEY = re.compile(r"((?:\d[\d,]*(?:\.\d+)?\s*(?:억|천만|백만|십만|만|천)\s*)+(?:\d[\d,]*\s*)?원?|\d[\d,]*\s*원)")
_PART = re.compile(r"(\d[\d,]*(?:\.\d+)?)\s*(억|천만|백만|십만|만|천)?")


def won_amounts(quote: str) -> set[int]:
    """근거 문장의 금액(원). 「6천만원」「2.5억원」「1억 5천만원」「5,000만 원」「200,000원」을 읽습니다. 금액 단위·「원」이 없는 숫자는 금액이 아닙니다."""
    text = unicodedata.normalize("NFKC", quote or "")
    found: set[int] = set()
    for match in _MONEY.finditer(text):
        total = Decimal(0)
        for number, unit in _PART.findall(match.group(1)):
            total += Decimal(number.replace(",", "")) * _UNITS.get(unit, 1)
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
        if (low is None and high is None) or not numbers_ok(low, high):
            # 「만 40세 미만」→ max 39처럼 근거와 숫자가 하나 다를 수 있어 미만·초과면 하나 차이를 인정
            if not (
                (high is not None and has_number(quote, high + 1) and "미만" in quote) or (low is not None and has_number(quote, low - 1) and "초과" in quote)
            ):
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
        if "SME" in (item.get("values") or []):
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


def _support(item: dict[str, Any], sources: list[str]) -> tuple[dict[str, Any], bool]:
    """AI 지원 내용 한 줄 → 계약 모양, 근거가 원문에 있었는지. 근거에 없는 금액·개월·비율은 비웁니다."""
    evidence_ok = quoted(item["evidence"], sources)
    quote = item["evidence"] if evidence_ok else ""
    amount = item.get("amountMaxWon")
    amount_ok = amount is not None and has_won(quote, amount) and manwon(amount) is not None
    months = item.get("months") if item.get("months") is not None and has_number(quote, item["months"]) else None
    rate = item.get("ratePct") if item.get("ratePct") is not None and has_number(quote, item["ratePct"]) else None
    return {
        "kind": item["kind"],
        "amountMax": manwon(amount) if amount_ok else None,
        "amountPer": item.get("amountPer") if amount_ok else None,
        "months": months,
        "ratePct": rate,
        "rawText": item["rawText"].strip() or item["evidence"].strip(),
        "evidence": item["evidence"].strip(),
    }, evidence_ok


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


def build_payload(payload: dict[str, Any], reading: BenefitReading, rules: RuleResult, regions: Any, *, now: datetime | None = None) -> dict[str, Any]:
    """policy.extracted payload. reading은 AI 답, rules는 출처 코드 칸에서 옮긴 조건·일정."""
    answer = reading.answer
    text = str(payload.get("text") or "")
    record_lines = [f"{k}: {v}" for k, v in (payload.get("record") or {}).items() if isinstance(v, str | int)]
    sources = [text, *record_lines]
    reasons: list[str] = []
    failed_evidence = 0

    # 조건: 규칙 줄 먼저(번호 그대로), AI 줄은 그 뒤 번호로. 규칙에 있는 type의 AI 줄은 버리되 값이 다르면 충돌로 표시
    conditions = [dict(c) for c in rules.conditions]
    rule_types = {c["type"]: c for c in conditions}
    offset = max((c["group"] for c in conditions), default=0)
    group_map: dict[int, int] = {}
    for item in answer.get("conditions") or []:
        evidence_ok = quoted(item["evidence"], sources)
        value = ai_value(item, regions) if evidence_ok else None
        if not evidence_ok:
            failed_evidence += 1
        if item["type"] in rule_types:
            rule = rule_types[item["type"]]
            if value is not None and rule["known"] and not same_value(item["type"], rule["value"], value):
                reasons.append("RULE_AI_CONFLICT")
            continue
        if item["group"] not in group_map:
            group_map[item["group"]] = offset + len(group_map) + 1
        known = value is not None and item["type"] not in TEXT_ONLY
        conditions.append(
            {
                "type": item["type"],
                "value": value if known else None,
                "group": group_map[item["group"]],
                "known": known,
                "origin": "AI",
                "rawText": (item["rawText"] or item["evidence"]).strip() or item["type"],
                "evidence": item["evidence"].strip() or item["rawText"].strip() or item["type"],
                "confidence": round(min(float(item["confidence"]), 1.0) if evidence_ok else min(float(item["confidence"]), 0.3), 2),
            }
        )

    supports = []
    for item in answer.get("supports") or []:
        made, evidence_ok = _support(item, sources)
        if not evidence_ok:
            failed_evidence += 1
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
        "howToApply": (answer.get("howToApply") or "").strip() or None,
        "documents": [d.strip() for d in answer.get("documents") or [] if d and d.strip()],
        "contact": (answer.get("contact") or "").strip() or None,
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
