"""근거 위치(원문 글자 범위) → PDF 쪽 번호 (판정 틀의 page 칸, 2026-10-21).

PDF를 읽은 원문에는 쪽마다 「--- 4쪽 ---」(2단 쪽이면 「--- 4쪽 왼쪽 ---」) 표시가 있고, 첨부가 여러 개면 「=== 첨부 이름 ===」로 나뉩니다
(intake/pdf_reader.py, intake/adapter.py). 근거의 시작 위치 앞에 있는 가장 가까운 쪽 표시가 그 근거의 쪽입니다. 그 사이에 첨부 경계가
있으면(다음 첨부의 머리말 부분) 쪽을 정하지 않습니다. 쪽 표시가 없는 원문(수집 글자·엑셀)의 근거도 쪽이 없습니다(null).
"""

from __future__ import annotations

import bisect
import re
from typing import Any

PAGE_MARK = re.compile(r"^--- (\d+)쪽(?: 왼쪽| 오른쪽)? ---$", re.M)
FILE_MARK = re.compile(r"^=== 첨부 .* ===$", re.M)


def evidence_pages(candidate: dict[str, Any], texts: dict[str, str]) -> dict[str, int]:
    """근거 ID → 쪽 번호. 쪽을 정할 수 없는 근거는 넣지 않습니다."""
    marks: dict[str, tuple[list[int], list[int], list[int]]] = {}
    for source_id, text in texts.items():
        found = [(m.start(), int(m.group(1))) for m in PAGE_MARK.finditer(text)]
        files = [m.start() for m in FILE_MARK.finditer(text)]
        marks[source_id] = ([p for p, _ in found], [n for _, n in found], files)
    pages: dict[str, int] = {}
    for evidence in candidate.get("evidence") or []:
        locator = evidence.get("locator") or {}
        if locator.get("kind") != "TEXT_RANGE" or evidence.get("sourceId") not in marks:
            continue
        positions, numbers, files = marks[evidence["sourceId"]]
        at = bisect.bisect_right(positions, locator.get("start", -1)) - 1
        if at < 0:
            continue
        boundary = bisect.bisect_right(files, locator["start"]) - 1
        if boundary >= 0 and files[boundary] > positions[at]:
            continue  # 쪽 표시 뒤에 다른 첨부가 시작됨
        pages[evidence["id"]] = numbers[at]
    return pages


def quote_pages(candidate: dict[str, Any], pages: dict[str, int]) -> dict[str, int]:
    """원문 문장 → 쪽 번호. 판정 틀 조건·안내의 quote는 자격 항목·예외·선정 규칙의 원문 문장이라, 그 항목의 근거 중 첫 쪽을 씁니다."""
    out: dict[str, int] = {}
    items = [*(candidate.get("eligibility") or []), *(candidate.get("selectionRules") or [])]
    items += [e for item in candidate.get("eligibility") or [] for e in item.get("exceptions") or []]
    for item in items:
        text = item.get("originalText")
        found = [pages[e] for e in item.get("evidenceIds") or [] if e in pages]
        if text and found and text not in out:
            out[text] = min(found)
    return out


def fill_pages(frame: dict[str, Any], by_quote: dict[str, int]) -> None:
    """판정 틀의 조건·우대·안내에서 page가 비어 있고 quote가 원문 문장과 같으면 쪽 번호를 채웁니다(그 자리에서 바꿈)."""

    def visit(node: dict[str, Any]) -> None:
        if node.get("page") is None and node.get("quote") in by_quote:
            node["page"] = by_quote[node["quote"]]
        for option in node.get("options") or []:
            for inner in option["criteria"]:
                visit(inner)
        for inner in node.get("criteria") or []:
            visit(inner)

    for criterion in frame.get("common") or []:
        visit(criterion)
    for group in frame.get("groups") or []:
        for criterion in group["criteria"]:
            visit(criterion)
    for entry in [*(frame.get("preferences") or []), *(frame.get("notes") or [])]:
        visit(entry)
