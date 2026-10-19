"""V9 값–인용 대조 (2026-10-16 검토 후 추가): 값이 자기 근거 인용문 안에 실제로 있는지 봅니다.

V2는 인용문이 원문에 있는지만 봅니다. 그래서 인용문은 진짜인데 값을 잘못 옮긴 경우(「만 39세 이하」를 45로)가 자동 완료되었습니다.
여기서는 숫자·날짜 값마다 그 값이 근거 인용문들 중 하나에 나오는지 확인합니다. 나오지 않으면 VALUE_NOT_IN_QUOTE.

- 자격 조건 숫자(나이·소득 비율·금액·개월·인원), 공고일, 일정 날짜, 모집 수, 전용면적, 원문 금액(sourceAmount)
- 숫자 표기 차이는 허용: 쉼표·띄어쓰기("1 ,000,000"), 억·만원 표기(34,500만원 = 345,000,000), 표 단위(천원·만원),
  한글 수("두 자녀"), 년↔개월(2년 = 24개월), 날짜 표기(2026. 8. 10. / 8월 10일 / 8.10~14의 끝날)
- 값이 비었거나 근거가 없는 항목은 다른 검사(CLAIM_UNSUPPORTED)가 다룹니다. 여기서는 판단하지 않습니다.
- 판정 섹션(자격·공고일)은 값이 **자기 인용문**에 있어야 합니다. 공급·일정은 AI가 가격 행만 인용하고 면적·수량은 다른 표에서
  가져오는 일이 많아(실측 약 100건 중 200여 건), 자기 인용문에 없으면 **원문 어디에도 없을 때만** 의심합니다(값 자체가 틀린 경우).
- 원문에 숫자로 안 적히는 값: 0(「소유하지 않아야」), 호실 하나의 모집 수 1, 「1인 70%, 2인 60%」 다음의 3인 이상 구간
"""

from __future__ import annotations

import re
from datetime import date
from typing import Any

from mujuthaek_extraction.verify.exceptions import _values as amounts_in
from mujuthaek_extraction.verify.model import Finding, SourceBundle

NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?")
KOREAN_NUMBERS = {"한": 1, "하나": 1, "두": 2, "둘": 2, "세": 3, "셋": 3, "네": 4, "넷": 4, "다섯": 5, "여섯": 6, "일곱": 7, "여덟": 8, "아홉": 9, "열": 10}
KOREAN_NUMBER = re.compile(r"(하나|다섯|여섯|일곱|여덟|아홉|한|두|둘|세|셋|네|넷|열)\s*(?:명|자녀|사람|가구|세대|인|개|번|회|살|세)")
ADULT = re.compile(r"성년")  # 「성년자」「미성년자」: 나이 숫자 없이 민법상 19세를 뜻함
TABLE_SCALES = (1, 1_000, 10_000, 1_000_000)  # 표 머리 "(단위: 천원)"·「( 337 )백만원」처럼 단위가 따로 있는 칸


def _clean(text: str) -> str:
    return re.sub(r"(?<=\d)\s+(?=[,.]\d)|(?<=[,.])\s+(?=\d)", "", text)


def _numbers(text: str) -> set[float]:
    out = set()
    for token in NUMBER.findall(_clean(text)):
        try:
            out.add(float(token.replace(",", "")))
        except ValueError:
            continue
    out |= {float(KOREAN_NUMBERS[word]) for word in KOREAN_NUMBER.findall(text)}
    return out


def _has_number(texts: list[str], value: float, unit: str | None = None, *, at_least: bool = False) -> bool:
    if value == 0:
        return True  # 「소유하지 않아야」「없는 자」처럼 0은 숫자로 적히지 않습니다
    numbers = set().union(*(_numbers(t) for t in texts))
    if at_least and unit == "COUNT" and value - 1 in numbers:
        return True  # 「50%(1인 70%, 2인 60%)」의 나머지 구간 = 3인 이상
    candidates = {value}
    if unit == "MONTH" and value % 12 == 0:
        candidates.add(value / 12)  # 2년 = 24개월
    if unit == "KRW":
        if any(abs(a - value) < 0.5 for t in texts for _, a, _ in amounts_in(_clean(t), "KRW")):
            return True
        candidates |= {value / scale for scale in TABLE_SCALES}
    return any(abs(n - c) < 0.005 for n in numbers for c in candidates)


def _has_date(texts: list[str], value: dict[str, str] | None, *, end: bool = False) -> bool | None:
    """None: 판단 안 함(값 없음·형식 모름)."""
    if not value:
        return None
    try:
        day = date.fromisoformat(value["value"][:10]) if value["precision"] != "MONTH" else None
        month = int(value["value"][5:7])
    except (ValueError, KeyError):
        return None
    text = " ".join(_clean(t) for t in texts)
    if day is None:
        return bool(re.search(rf"(?:\d{{4}}|[’']\d{{2}})\s*[./년]\s*0?{month}(?!\d)|(?<!\d)0?{month}\s*월", text))
    m, d = day.month, day.day
    if re.search(rf"(?<!\d)0?{m}\s*(?:[./-]|월)\s*0?{d}(?!\d)", text):
        return True
    # 「8. 10.(월) ~ 14.(금)」「10월 1일 10시부터 31일 17시까지」처럼 끝날이 일만 적힌 기간
    return bool(end and re.search(rf"(?:[~∼-]|부터)\s*0?{d}(?!\d)\s*(?:\.|일|\(|$)", text))


def _atoms(expression: dict[str, Any] | None, path: str):
    if not expression:
        return
    if expression["kind"] == "ATOMIC":
        yield path, expression
    for i, child in enumerate(expression.get("children") or []):
        yield from _atoms(child, f"{path}/children/{i}")


def _amount_parts(text: str) -> set[str]:
    return {re.sub(r"\D", "", n) for n in NUMBER.findall(_clean(text))}


def check_claims(candidate: dict[str, Any], sources: SourceBundle | None = None) -> list[Finding]:
    """숫자·날짜 값이 근거 인용문(공급·일정은 원문 전체도)에 나오는지 보고, 나오지 않는 값마다 VALUE_NOT_IN_QUOTE를 돌려줍니다."""
    quotes = {e["id"]: e["quote"] for e in candidate["evidence"]}
    whole = ["\n".join(sources.texts.values())] if sources and sources.texts else []
    findings: list[Finding] = []

    def texts(*id_lists: list[str] | None) -> list[str]:
        return [quotes[i] for ids in id_lists if ids for i in ids if i in quotes]

    def miss(target: str, what: str, value: Any, cited: list[str]) -> None:
        shown = " / ".join(q[:60] for q in cited[:2])
        findings.append(Finding("V9", "VALUE_NOT_IN_QUOTE", target, f"{what} {value}이(가) 근거 인용문에 없습니다 「{shown}」"))

    for e, item in enumerate(candidate["eligibility"]):
        for path, atom in _atoms(item["rule"]["expression"], f"/eligibility/{e}/rule/expression"):
            value, unit = atom.get("value"), atom.get("unit")
            if unit not in ("YEAR", "KRW", "PERCENT", "MONTH", "COUNT"):
                continue
            values = value if isinstance(value, list) else [value]
            cited = texts(atom.get("evidenceIds"), item["evidenceIds"])
            if not cited:
                continue
            for v in values:
                at_least = atom.get("operator") in ("GTE", "GT")
                if atom.get("attribute") == "AGE_YEARS" and v == 19 and any(ADULT.search(t) for t in cited):
                    continue  # 「성년자」「미성년자는 신청 불가」 = 민법상 19세(프롬프트 규칙). 실측: 매번 걸려 재확인 호출을 씀
                if isinstance(v, int | float) and not isinstance(v, bool) and not _has_number(cited, float(v), unit, at_least=at_least):
                    miss(f"{path}/value", atom.get("attribute", "값"), v, cited)
                    break

    notice = candidate["notice"]
    announced = notice.get("announcedOn")
    cited = texts(notice.get("evidenceIds"))
    if announced and cited and _has_date(cited, {"precision": "DAY", "value": announced}) is False:
        miss("/notice/announcedOn", "공고일", announced, cited)

    for s, schedule in enumerate(candidate["schedules"]):
        cited = texts(schedule["evidenceIds"])
        if not cited:
            continue
        for key, is_end in (("startsAt", False), ("endsAt", True)):
            if _has_date(cited, schedule.get(key), end=is_end) is False and _has_date(whole, schedule.get(key), end=is_end) is not True:
                miss(f"/schedules/{s}/{key}", schedule["rawLabel"], schedule[key]["value"], cited)

    for g, group in enumerate(candidate["supplyGroups"]):
        area = group.get("exclusiveAreaSqm")
        cited = texts(group["evidenceIds"])
        if isinstance(area, int | float) and cited and not _has_number(cited + whole, float(area)):
            miss(f"/supplyGroups/{g}/exclusiveAreaSqm", "전용면적", area, cited)
        for c, count in enumerate(group["counts"]):
            cited = texts(count["evidenceIds"])
            single_unit = group.get("itemKind") == "HOUSING_UNIT" and count.get("value") == 1
            if isinstance(count.get("value"), int) and cited and not single_unit and not _has_number(cited + whole, float(count["value"])):
                miss(f"/supplyGroups/{g}/counts/{c}/value", "모집 수", count["value"], cited)
        for p, price in enumerate(group["prices"]):
            cited = texts(price["evidenceIds"])
            parts = _amount_parts(price.get("sourceAmount") or "") - {""}  # 「42,800 ~49,600」은 두 수 모두
            if parts and cited and not parts <= set().union(*(_amount_parts(t) for t in cited + whole)):
                miss(f"/supplyGroups/{g}/prices/{p}/sourceAmount", price["kind"], price["sourceAmount"], cited)
    return findings
