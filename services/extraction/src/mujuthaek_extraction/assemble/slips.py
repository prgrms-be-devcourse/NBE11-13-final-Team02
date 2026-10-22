"""뜻이 분명한 형식 실수 고치기. 추출 단계(다시 요청할지 정하기 전)와 결합 단계가 함께 씁니다.

OpenAI 엄격 모드는 비울 칸도 채우게 해서 다음 실수가 생깁니다(실측 OpenAI LH 대전 영구임대: 가점표 12행이 형식 오류로 빠지고
일정·선정 호출을 한 번씩 다시 요청함). 원문 사실을 지어내지 않는 고침만 둡니다.
- 구조화된 조건의 사유를 null 대신 「""」로 적음 → null
- 추첨 등 순서가 없는 선정 항목에 순서 번호를 적음 → null
- 안내·신청 링크를 「www.lh.or.kr」처럼 프로토콜 없이 적음 → 「https://」를 붙임
- 자격 조건 예외를 PRESENT로 적고 예외 목록은 비움 → NOT_REVIEWED(사람 확인). 실측 OpenAI 청약홈 에코델타: 이 실수와 링크 실수로
  일정·자격 호출을 한 번씩 다시 요청했고, 다시 받은 일정 답은 인용을 빠뜨려 6개 항목의 근거가 사라졌습니다
- 적용 범위를 공고 전체(NOTICE)로 적고 공급 묶음·위치 ID를 함께 적음 → 그 ID의 범위(SUPPLY_GROUP·LOCATION). ID를 적은 쪽이 뜻이
  분명합니다(실측 2026-10-20 LH 인천부평: 「주거약자용 주택(36AS형)은 65세 이상만」이 이 실수로 빠져 자격 섹션 전체가 보류됨)
"""

from __future__ import annotations

import re
from typing import Any

BARE_HOST = re.compile(r"^(?:www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)+(?:/\S*)?$", re.IGNORECASE)


def _nodes(value: Any):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from _nodes(child)
    elif isinstance(value, list):
        for child in value:
            yield from _nodes(child)


def fix_format_slips(part: Any) -> Any:
    """AI 출력 조각에서 모듈 설명에 적은 형식 실수만 제자리에서 고치고 같은 객체를 돌려줍니다."""
    if not isinstance(part, dict):
        return part
    for item in part.get("selectionRules") or []:
        if isinstance(item, dict) and item.get("kind") != "TIE_BREAK" and item.get("order") is not None:
            item["order"] = None
    for section in ("eligibility", "selectionRules"):
        for node in _nodes(part.get(section)):
            if node.get("reason") == "" and node.get("status") == "STRUCTURED":
                node["reason"] = None
    for section, key in (("guidance", "url"), ("schedules", "applicationUrl")):
        for item in part.get(section) or []:
            url = item.get(key) if isinstance(item, dict) else None
            if isinstance(url, str) and BARE_HOST.match(url.strip()):
                item[key] = "https://" + url.strip()
    for item in part.get("eligibility") or []:
        if isinstance(item, dict) and item.get("exceptionsStatus") == "PRESENT" and item.get("exceptions") == []:
            item["exceptionsStatus"] = "NOT_REVIEWED"
    for node in _nodes({k: v for k, v in part.items() if k != "quotes"}):
        if node.get("level") == "NOTICE" and isinstance(node.get("supplyGroupIds"), list) and isinstance(node.get("locationIds"), list):
            if node["supplyGroupIds"]:
                node["level"] = "SUPPLY_GROUP"
            elif node["locationIds"]:
                node["level"] = "LOCATION"
    return part
