"""일정의 접수 방법·장소·시각과 서류 묶음을 원문 근거로 확인합니다(프론트 요청 6-1~6-5, 2026-10-23).

AI가 적은 값은 그 항목의 근거 인용문(원문 글자)에서 확인될 때만 둡니다. 틀린 값보다 빈 값이 낫습니다.
- 접수 장소(places)·이메일(applicationEmail)·발급 기관(issuer): 근거에 그대로 있는 글자만(공백·표 칸 구분자는 무시). 아니면 null
  접수 장소·발급 기관·제출 부수는 인용문이 걸친 원문 **표 행 전체**(같은 행의 다른 칸)에서 찾습니다(2026-10-23: 설명 칸만 인용하고
  같은 행의 발급처 칸을 인용하지 않아 맞는 발급처가 지워졌음). 표 행이 아닌 글 줄은 인용문만 봅니다. 글자 그대로 있어야 하는 것은 같습니다.
  주소는 시도 이름의 정식 이름·약칭을 같게 봅니다(「충청남도」↔「충남」, regions.SIDO_NAMES)
- 이름·주소가 모두 비는 장소는 뺍니다(층·호만 남은 장소는 어디인지 알 수 없음)
- 접수 시각(dailyHours·breaks)·시각 설명(timeNote)·시작/끝 시각(startsAt·endsAt의 MINUTE): 근거에 그 시각이 있어야 합니다.
  아니면 칸을 비우고(시작/끝은 날짜만 남김) 표시 메모를 남깁니다
- 제외 요일(excludedDays): 근거에 그 요일 낱말이 있어야 합니다
- 신청 방법(channels): 근거에 그 방법의 낱말이 막는 말(불가·제외 등) 없이 있어야 합니다. AI가 비워 두었으면 신청·서류 제출 일정만
  근거 낱말로 정합니다(인터넷·온라인·홈페이지→ONLINE, 방문·현장·창구→ONSITE, 이메일→EMAIL, 우편·등기 우편→MAIL, 팩스→FAX)
- 제출 부수(copies): 근거에 「1부」「2통」처럼 그 수가 있어야 합니다
- 같은 이름·같은 묶음·같은 제출 시점의 서류는 하나로 합칩니다(근거·대상 범위를 합침). 부수·발급 기관은 둘 다 값이 있고 다를 때만 비웁니다
- 공고 전체 신청 방법(notice.applicationChannels)은 계약 검사기와 같은 규칙으로 프로그램이 계산합니다

지운 값의 메모 위치: 일정은 섹션(/schedules)을 가리킵니다. 일정 항목을 가리키는 메모는 그 일정을 접수 기간(applicationWindows)에서
빼므로(계약 application_items), 값을 비워 안전해진 일정까지 빠지지 않게 합니다. 서류는 그 항목을 가리킵니다.
"""

from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone
from typing import Any

from mujuthaek_extraction.assemble.quotes import normalize
from mujuthaek_extraction.assemble.regions import SIDO_NAMES
from mujuthaek_extraction.contracts import tools

CLEARED = "DETAIL_UNVERIFIED_CLEARED"
KST = timezone(timedelta(hours=9))

# 신청 방법 낱말. 이메일을 먼저 찾아 지운 뒤 우편을 찾습니다(「전자우편」이 우편으로 읽히지 않게)
CHANNEL_WORDS = (
    ("EMAIL", re.compile(r"이\s*메일|e\s*-?\s*mail|전자\s*우편", re.IGNORECASE)),
    ("FAX", re.compile(r"팩스|팩시밀리|fax", re.IGNORECASE)),
    ("ONLINE", re.compile(r"인터넷|온라인|홈페이지|누리집|모바일|웹\s*사이트|on\s*-?\s*line", re.IGNORECASE)),
    ("ONSITE", re.compile(r"방문|현장|창구|내방")),
    # 「등기부등본」「등기사항증명서」는 서류 이름이라 등기 뒤에 우편·발송 낱말이 올 때만, 「우편번호」는 빼고
    ("MAIL", re.compile(r"우편(?!\s*번호)|등기\s*(?:우편|로|접수|발송|송부|제출)")),
)
# 낱말 바로 뒤(같은 구절 안)에 오면 그 방법을 막는 말
NEGATION = re.compile(r"^[^.。\n;:]{0,12}?(?:불가|안\s*됨|안\s*됩|받지\s*않|하지\s*않|않습니다|않음|불인정|제외|금지)")
APPLYING_KINDS = {"APPLICATION", "DOCUMENT_SUBMISSION"}

# 시각: 「10:00」「10시」「10시 30분」「오후 6시」「6시 반」. 「24시간」「1시간」 같은 길이는 빼고, 「2026.10.」 뒤 숫자는 보지 않습니다
TIME = re.compile(r"(오전|오후|아침|낮|저녁|밤)?\s*(?<![\d.])(\d{1,2})\s*(?::\s*(\d{2})|시(?!\s*간)(?:\s*(\d{1,2})\s*분|\s*(반))?)")
EVENING = {"오후", "저녁", "밤"}
DAY_WORDS = {
    "MON": "월",
    "TUE": "화",
    "WED": "수",
    "THU": "목",
    "FRI": "금",
    "SAT": "토",
    "SUN": "일",
}
HOLIDAY = re.compile(r"공휴일|휴일|국경일|명절|연휴")
WEEKEND = re.compile(r"주말")
# 「1부」「각 2통씩」「1매」. 「1부터」「제1장」은 아님
COPIES = re.compile(r"(?<![제\d])(\d{1,2})\s*(?:부|통|매|장)(?=$|[^가-힣]|씩|을|를|만|및|와|과|이|로)")


# 요일을 잇는 가운뎃점류: 「·」(U+00B7) 말고도 공고에 「․」(U+2024)「ㆍ」(U+318D)「・」(U+30FB)「‧」(U+2027)가 흔합니다
# (2026-10-24 채점: 「토․일요일」의 「토」를 못 찾아 맞는 제외 요일이 모두 지워졌음)
MIDDLE_DOTS = "·․ㆍ・‧"
DAY_JOINERS = rf"{MIDDLE_DOTS},/~∼\-、"


def _day_word(day: str) -> re.Pattern[str]:
    """요일 낱말: 「월요일」, 또는 한 글자 요일이 다른 요일·괄호와 붙어 있을 때(「(월)」「토·일」「토․일요일」). 「3월」「31일」「월 임대료」는 아님."""
    after, before = rf"[)）{DAY_JOINERS}]", rf"[(（{DAY_JOINERS}]"
    return re.compile(rf"{day}\s*요일|(?<![가-힣\d]){day}(?=\s*{after}|\s*$)|(?<={before}){day}(?![가-힣\d])|(?<={before}\s){day}(?![가-힣\d])")


DAY_PATTERNS = {code: _day_word(word) for code, word in DAY_WORDS.items()}


def channel_mentions(text: str) -> tuple[set[str], set[str]]:
    """글에서 찾은 신청 방법: (막는 말 없이 나온 방법, 막는 말과 함께 나온 방법)."""
    positive: set[str] = set()
    negative: set[str] = set()
    for channel, pattern in CHANNEL_WORDS:
        for match in pattern.finditer(text):
            (negative if NEGATION.match(text[match.end() :]) else positive).add(channel)
        text = pattern.sub(lambda m: " " * len(m[0]), text)
    return positive, negative


def ordered_channels(channels: set[str] | list[str]) -> list[str]:
    return [c for c in tools().APPLICATION_CHANNELS if c in set(channels)]


def channels_from_words(values: list[Any]) -> list[str]:
    """이전 v1의 자유 글자 신청 방법(「이메일」「방문접수」 등)을 정해진 값으로. 이미 정해진 값이면 그대로, 낱말이 없으면 버립니다."""
    found: set[str] = set()
    for value in values:
        if not isinstance(value, str):
            continue
        if value in tools().APPLICATION_CHANNELS:
            found.add(value)
            continue
        positive, _ = channel_mentions(value)
        found |= positive
    return ordered_channels(found)


def time_tokens(text: str) -> list[set[int]]:
    """글에 적힌 시각마다 그 시각으로 읽을 수 있는 값들(자정부터 분).
    오전·오후 표시가 없는 1~11시는 오후일 수도 있어 둘 다 넣습니다(「6시」 = 06:00 또는 18:00)."""
    tokens: list[set[int]] = []
    for match in TIME.finditer(text):
        marker, hour = match[1], int(match[2])
        minute = int(match[3] or match[4] or (30 if match[5] else 0))
        if hour > 24 or minute > 59 or (hour == 24 and minute):
            continue
        if marker in EVENING and hour < 12:
            tokens.append({(hour + 12) * 60 + minute})
        elif marker:
            tokens.append({(0 if hour == 12 and marker in {"오전", "아침"} else hour) * 60 + minute})
        else:
            tokens.append({hour * 60 + minute, (hour + 12) * 60 + minute} if 1 <= hour < 12 else {hour * 60 + minute})
    return tokens


def times_in(text: str) -> set[int]:
    """글에 적힌 시각으로 읽을 수 있는 모든 값(자정부터 분)."""
    return set().union(*time_tokens(text))


def _minutes(value: str) -> int:
    hour, minute = value.split(":")
    return int(hour) * 60 + int(minute)


# 매일 같은 시각 창구가 아니라 이어지는 기간·밤낮 없는 접수를 말하는 낱말(2026-10-24 채점: 「야간(17:00~10:00)에도 청약 가능」인
# 신청 일정과 「10:00 ~ 23(금) 17:00」 연속 구간이 「매일 10~17시」로 읽힘). 이런 근거로는 dailyHours를 두지 않습니다
CONTINUOUS_WORDS = re.compile(r"다음\s*날|익일|야간|24\s*시간|철야")
# 두 시각 사이의 글: 「~」「부터」로 이어지고(시각 없는) 짧은 글만 낀 경우
RANGE_GAP = re.compile(r"^\s*(?:[~∼～〜\-–]|부터|에서)\s*(?P<between>[^~∼～〜:\n]{0,24}?)\s*$")
# 두 시각 사이에 날짜가 끼면(「10:00 ~ 23(금) 17:00」「10.22.(목) 10:00 ~ 10.23.(금) 17:00」) 첫날 시작~마지막 날 끝의 연속 구간입니다
DATE_MARK = re.compile(r"\(\s*[월화수목금토일]\s*\)|\d{1,2}\s*일(?!\s*간)|\d{1,2}\s*월\s*\d|\d{1,2}\s*[./]\s*\d{1,2}")


def _time_windows(text: str) -> tuple[list[tuple[set[int], set[int]]], list[tuple[set[int], set[int]]]]:
    """글의 「시각 ~ 시각」 구간: (같은 날 구간들, 날짜가 끼어 이어지는 구간들). 구간마다 (시작으로 읽을 수 있는 값, 끝으로 읽을 수 있는 값)."""
    matches = [(m, time_tokens(m[0])) for m in TIME.finditer(text)]
    same: list[tuple[set[int], set[int]]] = []
    spanning: list[tuple[set[int], set[int]]] = []
    for (first, start), (second, end) in zip(matches, matches[1:], strict=False):
        gap = RANGE_GAP.match(text[first.end() : second.start()])
        if gap is None or not start or not end:
            continue
        (spanning if DATE_MARK.search(gap["between"]) else same).append((start[0], end[0]))
    return same, spanning


def continuous_hours(text: str, spans: list[dict[str, str]]) -> str | None:
    """dailyHours를 둘 수 없는 이유(없으면 None). 하루 안의 구간(시작 < 끝)이고, 근거가 이어지는 기간·야간·24시간 접수를 말하지 않으며,
    그 시각이 날짜가 낀 연속 구간(「10:00 ~ 23(금) 17:00」)으로만 적혀 있지 않아야 합니다."""
    if not spans:
        return None
    if any(_minutes(span["start"]) >= _minutes(span["end"]) for span in spans):
        return "끝 시각이 시작 시각보다 늦지 않아 하루 안의 접수 시각이 아니에요"
    word = CONTINUOUS_WORDS.search(text)
    if word:
        return f"근거가 「{word[0]}」 접수를 말해 매일 같은 시각에만 받는 것이 아니에요"
    same, spanning = _time_windows(text)
    for span in spans:
        start, end = _minutes(span["start"]), _minutes(span["end"])
        if any(start in a and end in b for a, b in spanning) and not any(start in a and end in b for a, b in same):
            return "그 시각이 첫날 시작~마지막 날 끝의 이어지는 기간으로만 적혀 있어요"
    return None


# 서류 표의 「필수/선택」 칸(2026-10-24 채점: 「공통/해당자」 묶음 제목 없이 「서류유형: 필수/선택」 칸만 있는 표의 서류가 모두 null).
# 칸 전체가 「필수」「선택」이거나 괄호 표시(「[필수]」)일 때만 봅니다. 「필수 선택 필수」처럼 한 칸에 몰린 깨진 행은 정하지 않습니다
REQUIRED_CELL = re.compile(r"^[\[［(（【]?\s*(필수|선택)\s*[\]］)）】]?$")
REQUIRED_TAG = re.compile(r"[\[［(（【]\s*(필수|선택)\s*[\]］)）】]")
REQUIRED_GROUPS = {"필수": "COMMON", "선택": "CONDITIONAL"}


def required_mark_group(text: str) -> str | None:
    """서류 근거(표 행 전체)의 「필수」→COMMON, 「선택」→CONDITIONAL. 두 표시가 섞이거나 없으면 None."""
    marks: set[str] = set()
    for line in text.split("\n"):
        if line.lstrip().startswith("|"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            marks |= {m[1] for m in (REQUIRED_CELL.match(c) for c in cells) if m}
            line = " ".join(c for c in cells if not REQUIRED_CELL.match(c))
        marks |= {m[1] for m in REQUIRED_TAG.finditer(line)}
    return REQUIRED_GROUPS[marks.pop()] if len(marks) == 1 else None


def _contains(text: str, value: Any) -> bool:
    return isinstance(value, str) and bool(normalize(value)) and normalize(value) in normalize(text)


# 시도 이름(긴 것부터): 주소 앞머리의 시도를 찾아 정식 이름·약칭을 바꿔 봅니다
_SIDO_PREFIXES = sorted(SIDO_NAMES, key=len, reverse=True)
TABLE_AIDS = ("  [열 제목] ", "  ↳ ")  # 구조 방식이 표에 덧붙이는 줄(verify.consistency와 같음)


def _address_found(text: str, address: Any) -> bool:
    """주소가 근거에 그대로 있는지. 시도 이름은 정식 이름·약칭 어느 쪽이든 같게 봅니다(「충남 아산시 …」 ↔ 「충청남도 아산시 …」).
    시도 뒤의 시군구·도로명·번지는 글자 그대로 있어야 합니다."""
    if _contains(text, address):
        return True
    if not isinstance(address, str):
        return False
    plain, haystack = normalize(address), normalize(text)
    sido = next((name for name in _SIDO_PREFIXES if plain.startswith(normalize(name))), None)
    if sido is None or len(plain) <= len(normalize(sido)):
        return False
    rest = plain[len(normalize(sido)) :]
    return any(normalize(same) + rest in haystack for same in SIDO_NAMES[sido] if same != sido)


def table_rows(text: str, start: int, end: int) -> str | None:
    """원문 [start, end) 범위가 걸친 줄들이 모두 표 행(| 줄, 표 읽기 도움 줄 포함)이면 그 줄들 전체, 아니면 None."""
    if not 0 <= start < end <= len(text):
        return None
    first = text.rfind("\n", 0, start) + 1
    last = text.find("\n", end - 1)
    lines = text[first : len(text) if last < 0 else last]
    rows = [line for line in lines.split("\n") if line.strip()]
    if not rows or not all(line.lstrip().startswith("|") or line.startswith(TABLE_AIDS) for line in rows):
        return None
    return lines


def _evidence_texts(candidate: dict[str, Any]) -> dict[str, str]:
    return {e["id"]: e["quote"] for e in candidate.get("evidence") or []}


class _Checker:
    def __init__(self, candidate: dict[str, Any], text: str | None = None, text_source: str | None = None):
        self.candidate = candidate
        self.quotes = _evidence_texts(candidate)
        # 근거 id → 인용문이 걸친 원문 표 행 전체(표 행이 아니거나 원문을 모르면 인용문)
        self.rows: dict[str, str] = {}
        for evidence in candidate.get("evidence") or []:
            locator = evidence.get("locator") or {}
            expanded = None
            if text is not None and evidence.get("sourceId") == text_source and locator.get("kind") == "TEXT_RANGE":
                expanded = table_rows(text, int(locator.get("start", -1)), int(locator.get("end", -1)))
            self.rows[evidence["id"]] = expanded if expanded is not None else evidence["quote"]
        self.notes: list[str] = []

    def cited(self, item: dict[str, Any], rows: bool = False) -> str:
        """항목의 근거 글. rows=True면 인용문이 걸친 원문 표 행 전체(같은 행의 다른 칸까지)."""
        source = self.rows if rows else self.quotes
        return "\n".join(source.get(i, "") for i in item.get("evidenceIds") or [])

    def clear(self, target: str, label: str, what: str) -> None:
        # 후보의 검수 이슈 목록에 바로 넣습니다. 서류를 합칠 때(_remove_merged) 위치가 함께 맞춰집니다
        self.candidate["quality"]["reviewIssues"].append(
            {"code": CLEARED, "target": target, "description": f"{label}: {what} 근거 인용문에서 확인하지 못해 비웠어요(원문 확인 필요)"}
        )

    # 일정
    def schedules(self) -> None:
        for schedule in self.candidate.get("schedules") or []:
            text = self.cited(schedule)
            label = f"{schedule.get('rawLabel') or schedule.get('id')}({schedule.get('id')})"
            self.channels(schedule, text, label)
            self.places(schedule, self.cited(schedule, rows=True), label)
            self.email(schedule, text, label)
            self.hours(schedule, text, label)
            self.days(schedule, text, label)
            self.minute_bounds(schedule, text, label)

    def channels(self, schedule: dict[str, Any], text: str, label: str) -> None:
        positive, _ = channel_mentions(f"{schedule.get('rawLabel') or ''}\n{text}")
        given = list(schedule.get("channels") or [])
        kept = [c for c in given if c in positive]  # 막는 말과만 나온 방법(「인터넷 신청 불가」)은 positive에 없습니다
        if given and kept != given:
            dropped = ", ".join(c for c in given if c not in kept)
            self.clear("/schedules", label, f"신청 방법 {dropped}을(를)")
        if not given and APPLYING_KINDS & set(schedule.get("kinds") or []) and positive:
            kept = list(positive)
            self.notes.append(f"/schedules {label}: 신청 방법을 근거의 낱말로 정했어요({', '.join(ordered_channels(kept))})")
        schedule["channels"] = ordered_channels(kept)

    def places(self, schedule: dict[str, Any], text: str, label: str) -> None:
        kept = []
        for place in schedule.get("places") or []:
            cleared = [
                key
                for key in ("name", "address", "detail")
                if place.get(key) is not None and not (_address_found(text, place[key]) if key == "address" else _contains(text, place[key]))
            ]
            for key in cleared:
                place[key] = None
            if cleared:
                self.clear("/schedules", label, f"접수 장소의 {'·'.join(cleared)}를")
            # 이름·주소가 둘 다 없으면 층·호(detail)만으로는 어디인지 알 수 없어 장소를 뺍니다
            if place.get("name") is not None or place.get("address") is not None:
                kept.append(place)
            elif place.get("detail") is not None:
                self.notes.append(f"/schedules {label}: 이름·주소 없이 층·호만 남은 접수 장소를 뺐어요({place['detail']})")
        schedule["places"] = kept

    def email(self, schedule: dict[str, Any], text: str, label: str) -> None:
        email = schedule.get("applicationEmail")
        if not isinstance(email, dict):
            return
        if not _contains(text.lower(), str(email.get("address") or "").lower()):
            schedule["applicationEmail"] = None
            self.clear("/schedules", label, "접수 이메일 주소를")
        elif email.get("subjectFormat") is not None and not _contains(text, email["subjectFormat"]):
            email["subjectFormat"] = None
            self.clear("/schedules", label, "이메일 제목 형식을")

    def hours(self, schedule: dict[str, Any], text: str, label: str) -> None:
        stated = times_in(text)
        for key, name in (("dailyHours", "접수 시각"), ("breaks", "쉬는 시각")):
            spans = schedule.get(key) or []
            if any(_minutes(span[end]) not in stated for span in spans for end in ("start", "end")):
                schedule[key] = []
                self.clear("/schedules", label, f"{name}을")
        why = continuous_hours(text, schedule.get("dailyHours") or [])
        if why:
            schedule["dailyHours"] = []
            self.candidate["quality"]["reviewIssues"].append(
                {"code": CLEARED, "target": "/schedules", "description": f"{label}: 접수 시각을 비웠어요 — {why}(원문 확인 필요)"}
            )
        note = schedule.get("timeNote")
        if isinstance(note, str) and any(not token & stated for token in time_tokens(note)):
            schedule["timeNote"] = None
            self.clear("/schedules", label, "시각 설명을")

    def days(self, schedule: dict[str, Any], text: str, label: str) -> None:
        days = schedule.get("excludedDays") or []
        weekend = bool(WEEKEND.search(text))
        found = {code for code, pattern in DAY_PATTERNS.items() if pattern.search(text)}
        found |= {"SAT", "SUN"} if weekend else set()
        found |= {"HOLIDAY"} if HOLIDAY.search(text) else set()
        # 확인한 요일은 남기고 확인하지 못한 요일만 뺍니다(2026-10-24: 한 요일을 못 찾아 맞는 제외 요일까지 모두 지웠음)
        dropped = [day for day in days if day not in found]
        if dropped:
            schedule["excludedDays"] = [day for day in days if day in found]
            self.clear("/schedules", label, f"제외 요일 {', '.join(dropped)}을(를)")

    def minute_bounds(self, schedule: dict[str, Any], text: str, label: str) -> None:
        """시각까지 적은 시작·끝(MINUTE)의 시각이 근거에 없으면 날짜(DAY)만 남깁니다. 날짜는 다른 검사(V9)가 따로 봅니다."""
        stated = times_in(text)
        for key in ("startsAt", "endsAt"):
            value = schedule.get(key)
            if not isinstance(value, dict) or value.get("precision") != "MINUTE":
                continue
            try:
                moment = datetime.fromisoformat(value["value"]).astimezone(KST)
            except (TypeError, ValueError):
                continue
            if moment.hour * 60 + moment.minute not in stated:
                schedule[key] = {"precision": "DAY", "value": moment.date().isoformat()}
                self.clear("/schedules", label, f"{'시작' if key == 'startsAt' else '끝'} 시각({moment:%H:%M})을")

    # 서류
    def documents(self) -> None:
        for d, document in enumerate(self.candidate.get("requiredDocuments") or []):
            text = self.cited(document, rows=True)
            label = str(document.get("name") or document.get("id"))
            if document.get("group") is None:
                group = required_mark_group(text)
                if group is not None:
                    document["group"] = group
                    self.notes.append(f"/requiredDocuments/{d}: 서류 표의 「필수/선택」 칸으로 묶음을 {group}(으)로 정했어요({label})")
            copies = document.get("copies")
            if copies is not None and copies not in {int(m[1]) for m in COPIES.finditer(text)}:
                document["copies"] = None
                self.clear(f"/requiredDocuments/{d}", label, "제출 부수를")
            if document.get("issuer") is not None and not _contains(text, document["issuer"]):
                document["issuer"] = None
                self.clear(f"/requiredDocuments/{d}", label, "발급 기관을")
        self.merge_documents()

    def merge_documents(self) -> None:
        documents = self.candidate.get("requiredDocuments") or []
        first: dict[tuple, int] = {}
        index = 0
        while index < len(documents):
            document = documents[index]
            key = (normalize(str(document.get("name") or "")), document.get("group"), normalize(str(document.get("submissionTime") or "")))
            kept = first.get(key)
            merged = _merge_document(documents[kept], document) if kept is not None else None
            if merged is None:
                first.setdefault(key, index)
                index += 1
                continue
            documents[kept] = merged
            _remove_merged(self.candidate, "requiredDocuments", index, kept)
            self.notes.append(f"/requiredDocuments/{kept}: 같은 이름·묶음·제출 시점의 서류를 합쳤어요({merged['name']})")


def _join(a: Any, b: Any, sep: str) -> Any:
    if a == b or b is None:
        return a
    if a is None:
        return b
    return sep.join(dict.fromkeys([*str(a).split(sep), *str(b).split(sep)]))


def _merge_scope(a: dict[str, Any], b: dict[str, Any]) -> dict[str, Any] | None:
    """두 서류의 대상 범위를 합칩니다. 단계(공고·위치·공급 묶음)나 신규/재공급 구분이 다르면 합치지 않습니다(None)."""
    if a == b:
        return dict(a)
    if a["level"] != b["level"] or a["supplyCategory"] != b["supplyCategory"]:
        return None
    union = lambda x, y: list(dict.fromkeys([*x, *y]))  # noqa: E731
    return {
        "level": a["level"],
        "locationIds": union(a["locationIds"], b["locationIds"]),
        "supplyGroupIds": union(a["supplyGroupIds"], b["supplyGroupIds"]),
        # 대상 계층이 빈 범위는 모든 계층이라 합치면 모든 계층입니다
        "targetGroups": [] if not a["targetGroups"] or not b["targetGroups"] else union(a["targetGroups"], b["targetGroups"]),
        "supplyCategory": a["supplyCategory"],
        "resolution": "RESOLVED" if a["resolution"] == b["resolution"] == "RESOLVED" else "NEEDS_REVIEW",
        "description": _join(a["description"], b["description"], " / "),
    }


def _keep_one(a: Any, b: Any) -> Any:
    if a is None:
        return b
    if b is None or a == b:
        return a
    return None


def _merge_document(a: dict[str, Any], b: dict[str, Any]) -> dict[str, Any] | None:
    scope = _merge_scope(a["scope"], b["scope"])
    if scope is None:
        return None
    return {
        **a,
        "scope": scope,
        "appliesTo": _join(a["appliesTo"], b["appliesTo"], ", "),
        "description": _join(a.get("description"), b.get("description"), " / "),
        # 부수·발급 기관은 둘 다 값이 있고 다를 때만 비웁니다(한쪽만 원문에 적힌 같은 서류의 값은 남김)
        "copies": _keep_one(a.get("copies"), b.get("copies")),
        "issuer": _keep_one(a.get("issuer"), b.get("issuer")),
        "evidenceIds": list(dict.fromkeys([*a["evidenceIds"], *b["evidenceIds"]])),
    }


def _remove_merged(candidate: dict[str, Any], section: str, index: int, into: int) -> None:
    """합쳐 없앤 항목을 빼고, quality의 위치 기록을 맞춥니다: 없앤 항목을 가리키던 기록은 합친 항목으로, 뒤 항목은 한 칸 앞으로."""
    del candidate[section][index]
    prefix = re.compile(rf"^/{re.escape(section)}/(\d+)(?=/|$)")
    quality = candidate["quality"]
    for collection in ("missingFields", "reviewIssues", "notes"):
        seen: set[str] = set()
        kept = []
        for entry in quality.get(collection) or []:
            match = prefix.match(entry["target"])
            if match:
                position = int(match[1])
                position = into if position == index else position - (position > index)
                entry["target"] = f"/{section}/{position}{entry['target'][match.end() :]}"
            if collection == "missingFields" and (entry["target"] in seen or _has_value(candidate, entry["target"])):
                continue  # 합친 항목의 같은 칸 누락 기록은 하나만, 합쳐서 값이 생긴 칸의 누락 기록은 뺌
            seen.add(entry["target"])
            kept.append(entry)
        quality[collection][:] = kept


def _has_value(candidate: dict[str, Any], pointer: str) -> bool:
    try:
        value = tools().resolve(candidate, pointer)
    except (KeyError, IndexError, TypeError, ValueError):
        return False
    return value is not None and value != []


def refresh_application_channels(candidate: dict[str, Any]) -> None:
    """공고 전체 신청 방법을 신청 일정에서 다시 계산합니다(계약 application_channels와 같은 규칙). 항목을 빼거나 고친 뒤 부릅니다."""
    if isinstance(candidate.get("notice"), dict) and isinstance(candidate.get("schedules"), list):
        try:
            candidate["notice"]["applicationChannels"] = tools().application_channels(candidate)
        except (KeyError, TypeError):
            return  # 형식이 깨진 후보는 계약 검사가 따로 알립니다


def check_details(candidate: dict[str, Any], text: str | None = None, text_source: str | None = None) -> list[str]:
    """결합한 후보의 일정·서류 세부 값을 근거로 확인하고(그 자리에서 고침) 공고 전체 신청 방법을 계산합니다.
    text·text_source: 원문 글과 그 문서 id(근거 위치 TEXT_RANGE가 가리키는 글). 주면 장소·발급 기관·부수를 인용문이 걸친 표 행 전체에서 찾습니다.
    지운 값은 후보의 검수 이슈(quality.reviewIssues, 검증기가 표시 메모로 옮김)에 넣고, 작업 메모를 돌려줍니다."""
    checker = _Checker(candidate, text, text_source)
    checker.schedules()
    checker.documents()
    refresh_application_channels(candidate)
    return checker.notes


SCHEDULE_DEFAULTS = {"places": [], "applicationEmail": None, "dailyHours": [], "breaks": [], "excludedDays": [], "timeNote": None}
DOCUMENT_DEFAULTS = {"group": None, "copies": None, "issuer": None}


def upgrade_stored(candidate: dict[str, Any]) -> dict[str, Any]:
    """2026-10-23 이전에 저장한 후보(검수 대기)를 새 계약 형식으로 옮깁니다. 이미 새 형식이면 바꾸지 않습니다(여러 번 불러도 같음).
    자유 글자 신청 방법은 낱말로 정해진 값으로 바꾸고(「서초구」처럼 방법이 아닌 글자는 버림), 새 칸은 빈 값으로 둡니다."""
    for schedule in candidate.get("schedules") or []:
        if not isinstance(schedule, dict):
            continue
        for key, value in SCHEDULE_DEFAULTS.items():
            schedule.setdefault(key, list(value) if isinstance(value, list) else value)
        if isinstance(schedule.get("channels"), list):
            schedule["channels"] = channels_from_words(schedule["channels"])
    for document in candidate.get("requiredDocuments") or []:
        if isinstance(document, dict):
            for key, value in DOCUMENT_DEFAULTS.items():
                document.setdefault(key, value)
    refresh_application_channels(candidate)
    return candidate
