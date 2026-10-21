"""판정 틀 가공의 공통 부분: ID, 조건 공통 칸, 검수 사유, 원문 질문(ASK), 안내(note), 기준일, 신분 코드."""

from __future__ import annotations

import copy
import re
from datetime import date
from typing import Any

from mujuthaek_extraction.contracts import tools

# 주거 틀의 신분 코드(계층 이름·신분 정의 문장 → 코드). 사업공간 틀의 유형 코드는 business_space.STATUS_WORDS
STATUS_WORDS = (
    (re.compile(r"생계(?:\s*급여)?"), "LIVELIHOOD_BENEFIT"),
    (re.compile(r"의료(?:\s*급여)?"), "MEDICAL_BENEFIT"),
    (re.compile(r"주거\s*급여"), "HOUSING_BENEFIT"),
    (re.compile(r"교육\s*급여"), "EDUCATION_BENEFIT"),
    (re.compile(r"차상위(?:\s*계층)?"), "NEAR_POOR"),
    (re.compile(r"한\s*부모(?:\s*가족)?"), "SINGLE_PARENT"),
    (re.compile(r"장애인"), "DISABLED"),
    (re.compile(r"국가\s*유공자|보훈\s*대상자?"), "NATIONAL_MERIT"),
    (re.compile(r"북한\s*이탈\s*주민"), "NORTH_KOREAN_DEFECTOR"),
    (re.compile(r"보호\s*종료\s*아동|시설\s*퇴소자|자립\s*준비\s*청년"), "FACILITY_LEAVER"),
    (re.compile(r"대학생"), "UNIVERSITY_STUDENT"),
    (re.compile(r"취업\s*준비생"), "JOB_SEEKER"),
)
STATUS_FILLER = re.compile(r"수급(?:권)?자|계층|가구|가족|대상자|기업|[·,/및\s()]")
BONUS_WORDS = re.compile(r"[1-9]\s*인\s*(?:가구)?\s*\(?\s*\d+\s*%|맞벌이|가산|신생아|출생\s*자녀")
# 「청약할 수 없습니다」「입주자로 선정될 수 없습니다」도 제한(실측 2026-10-21 김해 매각·든든전세가 방향 모르는 질문으로 나감)
LIMIT_WORDS = re.compile(r"불가(?:합니다|함|능)?|(?:신청|청약|선정|공급)\S*\s*(?:할|될|받을)\s*수\s*없|제외|무효|안\s*됩니다")
PERMIT_WORDS = re.compile(r"(?:신청|청약)\s*(?:이\s*)?가능|(?:신청|청약)할\s*수\s*있|(?:도|이상도)\s*가능")
NOT_REQUIRED = re.compile(r"불문|따지지\s*않|묻지\s*않|검증하지\s*않|적용(?:되|하)지\s*않|제한하지\s*않|관계\s*없")
REQUIRE_END = re.compile(r"(?:것|자|자로서|사람|세대|경우|에게\s*공급합니다)\s*[.)]?$")


class Context:
    """후보 한 건의 가공 상태: 쓴 ID, 검수 사유를 찾을 이슈·보류."""

    def __init__(self, candidate: dict[str, Any], regions: Any = None, *, approved: bool = False):
        """approved: 검수자가 승인한 결과. 사람이 이슈·보류까지 확인했으므로 추출 검수 사유로는 inReview를 붙이지 않습니다
        (틀 자체의 사유 — 가산을 못 읽음, 조건식을 옮길 수 없음 등 — 은 그대로 남습니다)."""
        self.candidate = candidate
        self.regions = regions
        self.used: set[str] = set()
        quality = candidate["quality"]
        self.issues = [] if approved else [(i["target"], i["code"]) for i in quality.get("reviewIssues") or []]
        self.held = [] if approved else quality.get("heldItems") or []
        self.approved = approved
        self.groups = 0

    def new_id(self, base: str) -> str:
        base = re.sub(r"\s+", "-", str(base).strip()) or "c"
        name, n = base, 1
        while name in self.used:
            n += 1
            name = f"{base}-{n}"
        self.used.add(name)
        return name

    def copy(self, criterion: dict[str, Any], suffix: str) -> dict[str, Any]:
        """여러 대상군에 같은 조건을 넣을 때 ID만 바꾼 사본(ANY_OF 안 조건까지)."""
        made = copy.deepcopy(criterion)

        def rename(node: dict[str, Any]) -> None:
            node["id"] = self.new_id(f"{node['id']}-{suffix}")
            for option in node.get("options") or []:
                for inner in option["criteria"]:
                    rename(inner)

        rename(made)
        return made

    def review_reasons(self, index: int, item: dict[str, Any]) -> list[str]:
        """추출이 이 자격 항목을 확신하지 못한 이유(검수 이슈·보류·예외). publish.conditions와 같은 기준에 예외 유무를 더합니다."""
        pointer = f"/eligibility/{index}"
        codes = {code for target, code in self.issues if target == pointer or target.startswith(pointer + "/")}
        codes |= {code for h in self.held if tools().overlaps_held(pointer, [h["target"]]) for code in h["codes"]}
        reasons = [f"추출 검수 사유: {', '.join(sorted(codes))}"] if codes else []
        if item.get("exceptionsStatus") == "NOT_REVIEWED" and not self.approved:
            reasons.append("예외 문구를 확인하지 않았습니다")
        return reasons

    def flag(self, criterion: dict[str, Any], reasons: list[str]) -> None:
        if not reasons:
            return
        criterion["inReview"] = True
        before = criterion.get("reviewReason")
        criterion["reviewReason"] = "; ".join(dict.fromkeys([*([before] if before else []), *reasons]))

    def quoted(self, pattern: str) -> bool:
        """후보의 원문 근거 인용 어딘가에 pattern이 있는지(설명 문장의 값을 원문으로 확인할 때)."""
        return any(re.search(pattern, e.get("quote") or "") for e in self.candidate.get("evidence") or [])

    def mentions_bonus(self, item: dict[str, Any]) -> bool:
        """같은 대상의 소득 항목 어딘가에 가산 낱말이 있는지(그 항목 원문에서 못 읽었으면 가산이 빠졌을 수 있음)."""
        mine = set(item["scope"].get("targetGroups") or [])
        for other in self.candidate["eligibility"]:
            words = f"{other.get('originalText') or ''} {other.get('description') or ''}"
            # 소득 항목이 아니어도 「가산 적용 : 출산자녀 … 20% 가산」처럼 가산만 따로 적은 문장이면 봅니다(실측 광주선운2)
            if other.get("category") != "INCOME" and not re.search(r"가산[^.]{0,40}?\d+\s*%|\d+\s*%\s*p?\s*가산", words):
                continue
            theirs = set(other["scope"].get("targetGroups") or [])
            if mine and theirs and not (mine & theirs):
                continue
            if BONUS_WORDS.search(f"{other.get('originalText') or ''} {other.get('description') or ''}"):
                return True
        return False


def base_fields(new_id: str, kind: str, quote: str | None) -> dict[str, Any]:
    return {"id": new_id, "kind": kind, "quote": quote or None, "page": None, "inReview": False, "reviewReason": None}


def group_id(ctx: Context, name: str) -> str:
    ctx.groups += 1
    return ctx.new_id(f"group-{ctx.groups}")


def ask(ctx: Context, item: dict[str, Any], reasons: list[str]) -> dict[str, Any]:
    """원문 문장을 그대로 묻는 질문. 신청 제한 문장이면 「해당하면 안 됨(metWhen NO)」, 자격 문장이면 「갖추면 됨(YES)」.
    둘 중 무엇인지 정할 수 없으면 YES로 두고 inReview입니다."""
    text = (item.get("originalText") or item.get("description") or "").strip()
    criterion = base_fields(ctx.new_id(item["id"]), "ASK", item.get("originalText"))
    if LIMIT_WORDS.search(text):
        criterion |= {"question": f"다음 신청 제한에 해당하나요? 「{text}」", "metWhen": "NO"}
    else:
        criterion |= {"question": f"다음 자격을 갖추었나요? 「{text}」", "metWhen": "YES"}
        if not REQUIRE_END.search(text):
            reasons = [*reasons, "원문 문장이 자격인지 신청 제한인지 코드로 정하지 못했습니다"]
    ctx.flag(criterion, reasons)
    return criterion


def note(kind: str, text: str, quote: str | None) -> dict[str, Any]:
    return {"kind": kind, "groupId": None, "text": text, "quote": quote or None, "page": None}


def unsure(candidate: dict[str, Any], pointer: str, *, approved: bool = False) -> bool:
    """그 위치가 보류됐거나 검수 이슈가 걸려 확정 값이 아닌지. 승인한 결과는 사람이 확인했으므로 늘 확정입니다."""
    if approved:
        return False
    quality = candidate["quality"]
    targets = [str(i["target"]).split(" ", 1)[0] for i in quality.get("reviewIssues") or []]
    targets += [h["target"] for h in quality.get("heldItems") or []]
    return tools().overlaps_held(pointer, targets)


def reference_date(candidate: dict[str, Any], *, approved: bool = False) -> date | None:
    """판정 기준일: 공고일(일정의 공고일, 없으면 공고 기본 정보의 공고일). 보류되거나 검수 이슈가 걸린 날짜는 쓰지 않습니다
    (2026-10-21 검토: API와 다른 공고일을 보류해도 그 날짜가 기준일이 되어 소득 기준 연도까지 틀림). 쓸 날짜가 없으면 None(틀 없음)."""
    for s, schedule in enumerate(candidate.get("schedules") or []):
        if "ANNOUNCEMENT" in (schedule.get("kinds") or []):
            for end in ("startsAt", "endsAt"):
                value = _day(schedule.get(end))
                if value and not unsure(candidate, f"/schedules/{s}/{end}", approved=approved):
                    return value
    if unsure(candidate, "/notice/announcedOn", approved=approved):
        return None
    return _day(candidate["notice"].get("announcedOn"))


def _day(value: Any) -> date | None:
    if isinstance(value, dict):
        value = value.get("value")
    if isinstance(value, str) and re.match(r"\d{4}-\d{2}-\d{2}", value):
        return date.fromisoformat(value[:10])
    return None


BENEFIT_LIST = re.compile(r"(?:생계|주거|의료|교육)(?:[\s·,ㆍ]*(?:생계|주거|의료|교육))*\s*급여")
BENEFIT_CODES = {"생계": "LIVELIHOOD_BENEFIT", "의료": "MEDICAL_BENEFIT", "주거": "HOUSING_BENEFIT", "교육": "EDUCATION_BENEFIT"}


def status_codes(text: str, words: tuple[tuple[re.Pattern[str], str], ...]) -> list[str]:
    """문장에 나오는 신분 코드(words가 가진 코드만). 「생계·주거·의료·교육급여」처럼 급여를 묶어 적은 것도 읽습니다."""
    allowed = {code for _, code in words}
    codes = [BENEFIT_CODES[w] for m in BENEFIT_LIST.finditer(text) for w in re.findall(r"생계|주거|의료|교육", m.group(0))]
    codes = [c for c in codes if c in allowed]
    rest = BENEFIT_LIST.sub(" ", text)
    codes += [code for pattern, code in words if code not in BENEFIT_CODES.values() and pattern.search(rest)]
    return list(dict.fromkeys(codes))


def statuses_of(name: str, words: tuple[tuple[re.Pattern[str], str], ...]) -> list[str]:
    """대상 이름의 모든 낱말이 신분이면 그 신분 코드들, 아니면 빈 목록."""
    codes, rest = [], name
    for pattern, code in words:
        if pattern.search(rest):
            codes.append(code)
            rest = pattern.sub(" ", rest)
    return codes if codes and not STATUS_FILLER.sub("", rest) else []
