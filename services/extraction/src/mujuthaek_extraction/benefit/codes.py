"""출처 코드 칸 → 조건·일정 (AI 없이 규칙으로, benefit-condition-types.draft.md 5장).

지금은 온통청년(youth)만 옮깁니다. 정부24 지원조건(JA 코드)은 실제 수집 자료로 칸 이름을 확인한 뒤 넣습니다(2026-10-25 로컬 수집 DB에
정부24·복지로 자료가 없어 짐작으로 만들지 않음). 그 출처는 AI가 글에서 읽습니다.

규칙으로 만든 조건은 origin RULE, confidence 1, evidence는 그 값이 들어 있는 원문 줄(글에 없으면 「칸 이름: 값」)입니다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date
from typing import Any

# 온통청년 API 코드(API코드정보.xlsx). 「제한없음」 코드는 줄을 만들지 않습니다
JOB = {
    "0013001": ("EMPLOYMENT", "EMPLOYED"),
    "0013002": ("EMPLOYMENT", "SELF_EMPLOYED"),
    "0013003": ("EMPLOYMENT", "UNEMPLOYED"),
    "0013004": ("EMPLOYMENT", "FREELANCER"),
    "0013005": ("EMPLOYMENT", "DAILY_WORKER"),
    "0013006": ("EMPLOYMENT", "STARTUP"),
    "0013007": ("EMPLOYMENT", "SHORT_TERM"),
    "0013008": ("OCCUPATION", "FARMER"),
    "0013009": ("EMPLOYMENT", "OTHER"),
}
JOB_ANY = "0013010"
SCHOOL = {
    "0049001": "BELOW_HIGH",
    "0049002": "HIGH_ENROLLED",
    "0049003": "HIGH_GRAD_EXPECTED",
    "0049004": "HIGH_GRAD",
    "0049005": "UNIV_ENROLLED",
    "0049006": "UNIV_GRAD_EXPECTED",
    "0049007": "UNIV_GRAD",
    "0049008": "GRADUATE",
    "0049009": "OTHER",
}
SCHOOL_ANY = "0049010"
# 특화 분야: (type, 값). 값이 None이면 OTHER(글만 보여 줌)
SPECIAL = {
    "0014001": ("WORKPLACE", "SME"),
    "0014002": ("OTHER", "여성"),
    "0014003": ("WELFARE_STATUS", "BASIC_LIVELIHOOD"),
    "0014004": ("HOUSEHOLD_TYPE", "SINGLE_PARENT"),
    "0014005": ("SPECIAL_STATUS", "DISABLED"),
    "0014006": ("OCCUPATION", "FARMER"),
    "0014007": ("SPECIAL_STATUS", "SOLDIER"),
    "0014008": ("OTHER", "지역인재"),
    "0014009": ("OTHER", "기타"),
}
SPECIAL_ANY = "0014010"
MARITAL = {"0055001": "MARRIED", "0055002": "SINGLE"}  # 0055003 제한없음
INCOME_YEARLY = "0043002"  # 연소득 (0043001 무관, 0043003 기타는 글을 AI가 읽음)
APPLY_ALWAYS = "0057002"  # 신청 기간 구분: 상시
APPLY_CLOSED = "0057003"  # 마감

# 원문 줄(수집기가 text에 넣는 이름)
LABELS = {
    "sprtTrgtMinAge": "지원 대상 최소 연령",
    "sprtTrgtMaxAge": "지원 대상 최대 연령",
    "earnMinAmt": "소득 최소 금액",
    "earnMaxAmt": "소득 최대 금액",
    "aplyYmd": "신청 기간",
    "bizPrdBgngYmd": "사업 시작일",
    "bizPrdEndYmd": "사업 종료일",
}


@dataclass
class RuleResult:
    conditions: list[dict[str, Any]] = field(default_factory=list)
    schedule: dict[str, Any] = field(default_factory=dict)  # 규칙으로 정한 일정 칸만


def _codes(value: Any) -> list[str]:
    return [c.strip() for c in str(value or "").split(",") if c.strip()]


def _int(value: Any) -> int | None:
    try:
        number = int(str(value).strip())
    except (TypeError, ValueError):
        return None
    return number


def _ymd(value: Any) -> str | None:
    text = re.sub(r"\D", "", str(value or ""))
    if len(text) != 8:
        return None
    try:
        return date(int(text[:4]), int(text[4:6]), int(text[6:])).isoformat()
    except ValueError:
        return None


def _evidence(text: str, key: str, value: Any) -> str:
    """그 값이 들어 있는 원문 줄. 글에 없으면 출처 칸 이름과 값."""
    label = LABELS.get(key)
    if label:
        for line in (text or "").splitlines():
            if line.strip().startswith(f"{label}:"):
                return line.strip()
    return f"{key}: {value}"


def youth_rules(record: dict[str, Any], text: str) -> RuleResult:
    """온통청년 record의 코드 칸 → 조건·일정. 쉼표로 여러 코드면 같은 group(「또는」)."""
    out = RuleResult()
    group = 0

    def add(rows: list[tuple[str, dict[str, Any] | None, str]], evidence: str) -> None:
        nonlocal group
        if not rows:
            return
        group += 1
        for kind, value, raw in rows:
            out.conditions.append(
                {
                    "type": kind,
                    "value": value,
                    "group": group,
                    "known": value is not None,
                    "origin": "RULE",
                    "rawText": raw,
                    "evidence": evidence,
                    "confidence": 1,
                }
            )

    low, high = _int(record.get("sprtTrgtMinAge")), _int(record.get("sprtTrgtMaxAge"))
    if (low or 0) > 0 or (high or 0) > 0:
        value = {k: v for k, v in (("min", low), ("max", high)) if v}
        raw = f"만 {low}~{high}세" if low and high else (f"만 {low}세 이상" if low else f"만 {high}세 이하")
        add([("AGE", value, raw)], _evidence(text, "sprtTrgtMaxAge" if high else "sprtTrgtMinAge", high or low))

    if (marital := MARITAL.get(str(record.get("mrgSttsCd") or ""))) is not None:
        add([("MARITAL", {"in": [marital]}, "기혼" if marital == "MARRIED" else "미혼")], f"mrgSttsCd: {record.get('mrgSttsCd')}")

    if str(record.get("earnCndSeCd") or "") == INCOME_YEARLY:
        low, high = _int(record.get("earnMinAmt")), _int(record.get("earnMaxAmt"))
        if (low or 0) > 0 or (high or 0) > 0:
            value = {"unit": "MANWON_PER_YEAR", "basis": "PERSONAL", **{k: v for k, v in (("min", low), ("max", high)) if v}}
            raw = f"연소득 {low}~{high}만 원" if low and high else (f"연소득 {low}만 원 이상" if low else f"연소득 {high}만 원 이하")
            add([("INCOME_AMOUNT", value, raw)], _evidence(text, "earnMaxAmt" if high else "earnMinAmt", high or low))

    jobs = _codes(record.get("jobCd"))
    if jobs and JOB_ANY not in jobs:
        rows: list[tuple[str, dict[str, Any] | None, str]] = []
        employment = [JOB[c][1] for c in jobs if c in JOB and JOB[c][0] == "EMPLOYMENT"]
        if employment:
            rows.append(("EMPLOYMENT", {"in": list(dict.fromkeys(employment))}, "취업 상태 " + ", ".join(employment)))
        if any(JOB.get(c, ("",))[0] == "OCCUPATION" for c in jobs):
            rows.append(("OCCUPATION", {"in": ["FARMER"]}, "영농종사자"))
        add(rows, f"jobCd: {record.get('jobCd')}")

    schools = _codes(record.get("schoolCd"))
    if schools and SCHOOL_ANY not in schools:
        values = list(dict.fromkeys(SCHOOL[c] for c in schools if c in SCHOOL))
        if values:
            add([("EDUCATION", {"in": values}, "학력 " + ", ".join(values))], f"schoolCd: {record.get('schoolCd')}")

    specials = _codes(record.get("sbizCd"))
    if specials and SPECIAL_ANY not in specials:
        rows = []
        merged: dict[str, list[str]] = {}
        for code in specials:
            if code not in SPECIAL:
                continue
            kind, value = SPECIAL[code]
            if kind == "OTHER":
                rows.append(("OTHER", None, f"특화 분야: {value}"))
            else:
                merged.setdefault(kind, []).append(value)
        for kind, values in merged.items():
            value = {"kind": values} if kind == "WORKPLACE" else {"in": list(dict.fromkeys(values))}
            rows.append((kind, value, f"특화 분야 {', '.join(values)}"))
        add(rows, f"sbizCd: {record.get('sbizCd')}")

    zips = [c for c in _codes(record.get("zipCd")) if re.fullmatch(r"\d{5}", c)]
    if zips:
        add([("RESIDENCE", {"regions": list(dict.fromkeys(zips))}, "거주 지역 제한")], f"zipCd: {record.get('zipCd')}")

    # 일정: 신청 기간 「YYYYMMDD ~ YYYYMMDD」, 구분 상시, 사업 기간
    period = str(record.get("aplyYmd") or "")
    days = re.findall(r"\d{8}", period)
    if len(days) == 2:
        out.schedule.update(applyStart=_ymd(days[0]), applyEnd=_ymd(days[1]), rawText=period.strip())
    if str(record.get("aplyPrdSeCd") or "") == APPLY_ALWAYS:
        out.schedule.update(applyAlways=True, rawText=out.schedule.get("rawText") or "상시")
    elif str(record.get("aplyPrdSeCd") or "") in ("0057001", APPLY_CLOSED):
        out.schedule.setdefault("applyAlways", False)
    if (start := _ymd(record.get("bizPrdBgngYmd"))) is not None:
        out.schedule["businessStart"] = start
    if (end := _ymd(record.get("bizPrdEndYmd"))) is not None:
        out.schedule["businessEnd"] = end
    return out


def rules_for(source_code: str, record: dict[str, Any] | None, text: str) -> RuleResult:
    if source_code == "youth" and isinstance(record, dict):
        return youth_rules(record, text)
    return RuleResult()
