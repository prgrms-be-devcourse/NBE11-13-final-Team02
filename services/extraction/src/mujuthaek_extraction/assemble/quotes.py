"""AI가 낸 인용문을 원문에서 찾아 위치(코드 포인트 [start, end))를 계산합니다.

AI는 위치를 세지 않습니다. 정확히 일치하는 곳을 먼저 찾고, 없으면 NFKC·공백 제거로 정규화해 찾은 뒤 원문 위치로 되돌립니다.
같은 인용문이 여러 곳에 있으면 같은 항목의 다른 인용과 가장 가까운 곳을 고릅니다(결정 E3). 기준이 없을 때 충분히 긴 인용은
요약·본문에 같은 문장이 반복된 경우로 보고 첫 위치를 고르며, 짧은 인용은 AMBIGUOUS로 남깁니다(E3 보완, LH 사례).
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from enum import Enum

MIN_QUOTE_CHARS = 4  # 정규화 후 이보다 짧으면 우연 일치 가능성이 커서 검수 메모를 남깁니다
LONG_QUOTE_CHARS = 15  # 정규화 후 이 이상이면 여러 곳에 있어도 같은 문장의 반복으로 보고 첫 위치를 씁니다


class QuoteStatus(Enum):
    FOUND = "FOUND"  # 한 곳에서만 찾음
    CHOSEN_NEAREST = "CHOSEN_NEAREST"  # 여러 곳 중 같은 항목의 다른 인용과 가장 가까운 곳
    CHOSEN_FIRST = "CHOSEN_FIRST"  # 기준이 없지만 긴 인용이라 첫 위치를 씀 (요약·본문 반복)
    AMBIGUOUS = "AMBIGUOUS"  # 여러 곳이고 고를 근거가 없음
    NOT_FOUND = "NOT_FOUND"


@dataclass(frozen=True)
class QuoteResolution:
    quote: str
    status: QuoteStatus
    span: tuple[int, int] | None  # 원문 코드 포인트 [start, end). AMBIGUOUS·NOT_FOUND면 None
    candidates: tuple[tuple[int, int], ...]
    exact: bool  # 원문 그대로 일치했는지(정규화 없이)
    too_short: bool
    scattered: bool = False  # 이어진 문장으로는 없고, 낱말들이 가까운 곳에 흩어져 있어 찾음(find_scattered)


def normalize(text: str) -> str:
    """NFKC + 공백 제거 + PDF 표 칸 구분자(|) 제거. AI가 같은 행의 칸을 구분자 없이 이어 인용해도 찾을 수 있게 합니다(2026-10-16 실측)."""
    return "".join(unicodedata.normalize("NFKC", text).split()).replace("|", "")


def _clusters(text: str) -> list[tuple[int, int]]:
    """정규화 단위가 되는 글자 묶음. 결합 문자와 분해된 한글 자모(초성+중성+종성)는 앞 글자와 묶습니다."""
    spans: list[tuple[int, int]] = []
    for index, char in enumerate(text):
        code = ord(char)
        joins = unicodedata.combining(char) or 0x1160 <= code <= 0x11FF or 0xD7B0 <= code <= 0xD7FF
        if spans and joins:
            spans[-1] = (spans[-1][0], index + 1)
        else:
            spans.append((index, index + 1))
    return spans


class NormalizedText:
    """정규화한 글자마다 원문 위치를 기억해 두고, 정규화 문자열에서 찾은 위치를 원문 위치로 되돌립니다."""

    def __init__(self, text: str):
        self.text = text
        chars: list[str] = []
        starts: list[int] = []
        ends: list[int] = []
        for start, end in _clusters(text):
            for char in normalize(text[start:end]):
                chars.append(char)
                starts.append(start)
                ends.append(end)
        self.value = "".join(chars)
        self._starts = starts
        self._ends = ends

    def find_all(self, needle: str) -> list[tuple[int, int]]:
        found = []
        position = self.value.find(needle)
        while needle and position >= 0:
            found.append((self._starts[position], self._ends[position + len(needle) - 1]))
            position = self.value.find(needle, position + 1)
        return sorted(set(found))


def _exact_all(text: str, quote: str) -> list[tuple[int, int]]:
    found = []
    position = text.find(quote)
    while quote and position >= 0:
        found.append((position, position + len(quote)))
        position = text.find(quote, position + 1)
    return found


# PDF를 글자로 바꿀 때 프로그램이 붙인 표시. 원문 내용이 아니므로 AI가 인용에 넣어도 빼고 찾습니다(실측: "[표 6-3] | 서류 | …")
LAYOUT_MARKER = re.compile(r"\[표\s*\d+-\d+\]|---\s*\d+쪽[^\n]*?---")


def strip_markers(quote: str) -> str:
    return LAYOUT_MARKER.sub(" ", quote).strip()


# 줄 머리 글머리표. AI가 문장 중간부터 인용하면서 앞 문장의 글머리표를 붙이는 경우가 있어, 못 찾으면 떼고 다시 찾습니다
# (실측: 원문 "• 공고문은 … 있으며, 금회 모집 공고는 …" → 인용 "• 금회 모집 공고는 …")
LEADING_BULLET = re.compile(r"^\s*[•·▪■□○●◦※\-*]+\s*")


def candidates(text: str, quote: str, index: NormalizedText | None = None) -> tuple[list[tuple[int, int]], bool]:
    quote = strip_markers(quote)
    exact = _exact_all(text, quote)
    if exact:
        return exact, True
    index = index or NormalizedText(text)
    found = index.find_all(normalize(quote))
    bare = LEADING_BULLET.sub("", quote)
    if not found and bare != quote and len(normalize(bare)) >= LONG_QUOTE_CHARS:
        found = index.find_all(normalize(bare))
    return found, False


SCATTER_MIN_CHARS = 20  # 낱말 단위로 찾을 인용의 최소 길이(정규화 후)
SCATTER_MIN_WORDS = 4


def find_scattered(index: NormalizedText, quote: str) -> list[tuple[int, int]]:
    """인용의 낱말이 모두 가까운 범위(인용 길이의 약 2배) 안에 있으면 그 범위를 돌려줍니다.
    PDF의 순서도·칸 없는 표는 글자가 줄 단위로 뒤섞여 읽혀, AI가 읽기 순서대로 이은 인용이 그대로는 없습니다
    (실측: 남해창선 "동일순위 내 경쟁 시 선정순서 … 전입일자가 오래된 자 세대구성원 수가 많은 자 …")."""
    words = [w for w in (normalize(t) for t in re.split(r"[\s|]+", strip_markers(quote))) if w]
    length = sum(len(w) for w in words)
    if length < SCATTER_MIN_CHARS or len(words) < SCATTER_MIN_WORDS:
        return []
    anchor = max(words, key=len)
    reach = 2 * length
    found = set()
    position = index.value.find(anchor)
    while position >= 0:
        lo, hi = max(0, position - reach), position + len(anchor) + reach
        starts, ends = [], []
        for word in words:
            hits = []
            at = index.value.find(word, lo)
            while 0 <= at and at + len(word) <= hi:
                hits.append(at)
                at = index.value.find(word, at + 1)
            if not hits:
                break
            best = min(hits, key=lambda h: abs(h - position))
            starts.append(best)
            ends.append(best + len(word))
        else:
            if max(ends) - min(starts) <= reach + len(anchor):
                found.add((index._starts[min(starts)], index._ends[max(ends) - 1]))
        position = index.value.find(anchor, position + 1)
    return sorted(found)


def _distance(a: tuple[int, int], b: tuple[int, int]) -> int:
    return max(0, max(a[0], b[0]) - min(a[1], b[1]))


def resolve_item_quotes(text: str, quotes: list[str], index: NormalizedText | None = None) -> list[QuoteResolution]:
    """한 항목(예: 자격 조건 하나)의 인용문들을 함께 풀어 위치를 정합니다. 결과 순서는 입력 순서와 같습니다."""
    index = index or NormalizedText(text)
    found = [candidates(text, quote, index) for quote in quotes]
    scattered = [not spans and bool(find_scattered(index, quote)) for quote, (spans, _) in zip(quotes, found, strict=True)]
    found = [(find_scattered(index, quote), False) if flag else pair for quote, pair, flag in zip(quotes, found, scattered, strict=True)]
    anchors = [spans[0] for spans, _ in found if len(spans) == 1]
    results = []
    for quote, (spans, exact), flag in zip(quotes, found, scattered, strict=True):
        length = len(normalize(strip_markers(quote)))
        short = length < MIN_QUOTE_CHARS
        status, span = QuoteStatus.NOT_FOUND, None
        if len(spans) == 1:
            status, span = QuoteStatus.FOUND, spans[0]
        elif spans and anchors:
            ranked = sorted(spans, key=lambda s: min(_distance(s, a) for a in anchors))
            best = min(_distance(ranked[0], a) for a in anchors)
            second = min(_distance(ranked[1], a) for a in anchors)
            if best < second:
                status, span = QuoteStatus.CHOSEN_NEAREST, ranked[0]
            elif length >= LONG_QUOTE_CHARS:
                status, span = QuoteStatus.CHOSEN_FIRST, spans[0]
            else:
                status = QuoteStatus.AMBIGUOUS
        elif spans and length >= LONG_QUOTE_CHARS:
            status, span = QuoteStatus.CHOSEN_FIRST, spans[0]
        elif spans:
            status = QuoteStatus.AMBIGUOUS
        results.append(QuoteResolution(quote, status, span, tuple(spans), exact, short, flag))
    return results


def quote_in_unit_row(row: dict, quote: str) -> bool:
    """호별 표 한 행의 값 중에 인용문이 들어 있는지 확인합니다(정규화 비교)."""
    needle = normalize(quote)
    return bool(needle) and any(needle in normalize(str(value)) for key, value in row.items() if key != "checks")


GROUP_LOOKBACK = 3000  # 대상 계층 이름을 찾아볼 앞쪽 글자 수
GROUP_MARGIN = 0.5  # 가장 가까운 후보가 두 번째보다 이 비율 이하로 가까워야 고릅니다


def choose_by_target_groups(text: str, resolution: QuoteResolution, groups: list[str]) -> QuoteResolution:
    """같은 문장이 계층마다 반복될 때(예: 행복주택 대학생·청년 부분의 "② 혼인 중이 아닐 것"),
    항목의 대상 계층 이름(scope.targetGroups)이 바로 앞에 가장 가깝게 나오는 위치를 고릅니다. 뚜렷하지 않으면 그대로 둡니다."""
    # 긴 인용이라 첫 위치를 고른 경우(CHOSEN_FIRST)도 다시 봅니다. 예전엔 15자 이상 반복 문장이 대상 계층을 보기 전에 첫 위치로 정해져,
    # 신혼부부 조건의 근거가 청년 부분을 가리켰습니다(검토 2026-10-16). 계층 이름으로 뚜렷이 못 고르면 첫 위치 그대로 둡니다.
    if resolution.status not in (QuoteStatus.AMBIGUOUS, QuoteStatus.CHOSEN_FIRST) or not groups or len(resolution.candidates) < 2:
        return resolution
    names = [n for g in groups for n in {g, re.sub(r"\(.*?\)", "", g).strip()} if n]

    def distance(span: tuple[int, int]) -> int:
        window = text[max(0, span[0] - GROUP_LOOKBACK) : span[0]]
        found = [window.rfind(n) for n in names]
        best = max(found)
        return len(window) - best if best >= 0 else GROUP_LOOKBACK + 1

    ranked = sorted(resolution.candidates, key=distance)
    first, second = distance(ranked[0]), distance(ranked[1])
    if first <= GROUP_LOOKBACK and first <= second * GROUP_MARGIN:
        return QuoteResolution(resolution.quote, QuoteStatus.CHOSEN_NEAREST, ranked[0], resolution.candidates, resolution.exact, resolution.too_short)
    if len(groups) >= 2 and all(distance(span) <= GROUP_LOOKBACK for span in resolution.candidates):
        # 여러 계층에 함께 걸린 항목이고 반복된 곳마다 그 계층 이름이 앞에 있음: 어느 곳이든 같은 조건이라 첫 위치를 씁니다
        # (실측: 대학생·청년 부분에 똑같이 있는 "③ 【완화조건】 소득요건 배제")
        first_span = resolution.candidates[0]
        return QuoteResolution(resolution.quote, QuoteStatus.CHOSEN_FIRST, first_span, resolution.candidates, resolution.exact, resolution.too_short)
    return resolution


NEAR_MARGIN = 0.5  # 가장 가까운 후보가 두 번째보다 이 비율 이하로 가까워야 고릅니다


def choose_near(resolution: QuoteResolution, previous: tuple[int, int] | None) -> QuoteResolution:
    """위치를 못 정한(AMBIGUOUS) 인용을 같은 섹션 바로 앞 항목의 근거 위치에 가장 가까운 후보로 정합니다. 뚜렷하지 않으면 그대로 둡니다."""
    if resolution.status is not QuoteStatus.AMBIGUOUS or previous is None or len(resolution.candidates) < 2:
        return resolution
    ranked = sorted(resolution.candidates, key=lambda span: _distance(span, previous))
    first, second = _distance(ranked[0], previous), _distance(ranked[1], previous)
    if first <= second * NEAR_MARGIN:
        return QuoteResolution(resolution.quote, QuoteStatus.CHOSEN_NEAREST, ranked[0], resolution.candidates, resolution.exact, resolution.too_short)
    return resolution
