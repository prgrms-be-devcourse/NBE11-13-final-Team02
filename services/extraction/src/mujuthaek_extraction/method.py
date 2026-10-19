"""추출 방식 선택 — 이전 방식으로 되돌리는 스위치(2026-10-17 사용자 요청: "롤백 가능하게 기존 방식을 보존").

EXTRACTION_METHOD
  structured (기본): 표 머리글을 칸 좌표로 복원해 열 제목 줄과 행마다 읽기 도움 줄(↳)을 붙이고, 프롬프트 v11을 씁니다.
  legacy: 2026-10-17 이전 방식 — 표를 행 글자로만 넘기고 프롬프트 v10을 씁니다.

같은 원본도 방식에 따라 AI가 받는 글자가 달라지므로, 결과를 비교할 때는 방식을 함께 기록합니다.
"""

from __future__ import annotations

import os

STRUCTURED = "structured"
LEGACY = "legacy"
METHODS = (STRUCTURED, LEGACY)


def extraction_method() -> str:
    method = os.getenv("EXTRACTION_METHOD", STRUCTURED)
    if method not in METHODS:
        raise ValueError(f"EXTRACTION_METHOD는 {list(METHODS)} 중 하나여야 합니다: {method!r}")
    return method
