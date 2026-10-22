"""검증까지 끝난 후보 → extraction.completed payload (팀 이벤트 흐름 문서 4·8절, 2026-10-15 확정 형식).

- 필수: rawPostId, rawVersion, field, title, conditions, reviewStatus(AUTO_PASSED/PARTIALLY_PASSED/IN_REVIEW)
- 부분 완료(PARTIALLY_PASSED, 2026-10-17): 공고는 게시하고 heldItems의 항목·섹션만 보류(보류한 자격 조건은 inReview)
- 선택: provider, regionCode(시군구 5자리), schedule(applyStartOn/applyEndOn), applicationWindows(순위·대상별 접수 기간 — 알림용),
  summary, reprocess, notice(표준 공고 전체 — 상세 화면용), judgment(판정 틀 — 판정 담당이 정한 형식 그대로, judgment/)
- 검수 중이어도 내보내며, 자격 조건마다 inReview로 표시합니다. 검증을 통과하지 못한 접수 일정은 schedule에 넣지 않습니다.
봉투(eventId 등)는 공통 라이브러리 new_event로 만듭니다. 이 함수는 시계·UUID를 쓰지 않습니다.
"""

from __future__ import annotations

import logging
import re
from typing import Any

from mujuthaek_extraction.contracts import tools
from mujuthaek_extraction.judgment import build_judgment
from mujuthaek_extraction.judgment.policy_frame import region_named, space_frame, startup_frame
from mujuthaek_extraction.judgment.schema import frame_errors
from mujuthaek_extraction.judgment.space import build_space
from mujuthaek_extraction.judgment.startup import build_startup

log = logging.getLogger(__name__)


def _issue_targets(candidate: dict[str, Any], *, with_notes: bool = False) -> list[tuple[str, str]]:
    quality = candidate["quality"]
    return [(i["target"], i["code"]) for i in [*quality["reviewIssues"], *(quality["notes"] if with_notes else [])]]


def _codes_under(issues: list[tuple[str, str]], prefix: str) -> list[str]:
    return sorted({code for target, code in issues if target == prefix or target.startswith(prefix + "/")})


def _held_codes(candidate: dict[str, Any], pointer: str) -> list[str]:
    """부분 완료로 보류한 범위가 pointer와 겹치면(같음·안·품음, 계약 overlaps_held) 그 보류 사유.
    /schedules/i/applicationMode만 보류해도 그 일정은 쓰지 않습니다."""
    return sorted({code for h in candidate["quality"].get("heldItems", []) if tools().overlaps_held(pointer, [h["target"]]) for code in h["codes"]})


def conditions(candidate: dict[str, Any]) -> list[dict[str, Any]]:
    """자격 조건마다 payload 조건을 만듭니다. 검수 이슈·보류·예외 미검토가 있으면 inReview와 그 사유를 붙입니다."""
    issues = _issue_targets(candidate)
    out = []
    for index, item in enumerate(candidate["eligibility"]):
        reasons = sorted({*_codes_under(issues, f"/eligibility/{index}"), *_held_codes(candidate, f"/eligibility/{index}")})
        if item["exceptionsStatus"] == "NOT_REVIEWED":
            reasons.append("EXCEPTIONS_NOT_REVIEWED")
        out.append(
            {
                **tools().condition_fields(item),
                "inReview": bool(reasons),
                "reviewReasons": reasons,
            }
        )
    return out


def application_schedule(candidate: dict[str, Any]) -> dict[str, str | None] | None:
    """공통 계약의 동일한 일정 투영을 사용합니다."""
    return tools().application_schedule(candidate)


def application_windows(candidate: dict[str, Any]) -> list[dict[str, Any]]:
    """날짜 미정 구간을 포함한 검증된 신청 일정. 보류·검수 범위는 제외합니다."""
    return tools().application_windows(candidate)


def sigungu_code(candidate: dict[str, Any]) -> str | None:
    """이벤트 regionCode: 계약 검사기(event_region_code)와 같은 규칙. 모든 위치가 한 시군구면 그 코드, 여러 시군구지만 한 시도면 xx000,
    여러 시도·코드 없는 위치·보류한 위치 지역·위치 없음이면 null.
    예전엔 코드 없는 위치를 건너뛰고 수집기 값으로 대신 채워 계약과 어긋났습니다(검토 2026-10-20)."""
    held = [h["target"] for h in candidate["quality"].get("heldItems") or []]
    return tools().event_region_code(candidate["locations"], held)


def completed_payload(candidate: dict[str, Any], *, provider: str | None = None, regions: Any = None, pages: dict[str, int] | None = None) -> dict[str, Any]:
    """검증 결과로 reviewStatus를 정해 extraction.completed payload를 만듭니다. 검증 통과에 검수 중인 조건이 없을 때만 AUTO_PASSED입니다."""
    status = candidate["quality"]["verification"]["status"]
    items = conditions(candidate)
    if status == "PASSED" and not any(c["inReview"] for c in items):
        review = "AUTO_PASSED"
    elif status == "PARTIALLY_PASSED":
        review = "PARTIALLY_PASSED"
    else:
        review = "IN_REVIEW"
    return {
        "rawPostId": candidate["input"]["rawPostId"],
        "rawVersion": candidate["input"]["rawVersion"],
        "field": candidate["input"]["field"],
        "title": candidate["notice"]["title"] or candidate["sourceReferences"][0]["sourceTitle"],
        "conditions": items,
        "reviewStatus": review,
        **({"heldItems": candidate["quality"]["heldItems"]} if candidate["quality"].get("heldItems") else {}),
        "provider": provider,
        "regionCode": sigungu_code(candidate),
        "schedule": application_schedule(candidate),
        "applicationWindows": application_windows(candidate),
        # 보류한 공고 설명은 목록 요약에 쓰지 않습니다(공고 기본 정보 칸 보류, 2026-10-20)
        "summary": None if _held_codes(candidate, "/notice/description") else candidate["notice"]["description"],
        "reprocess": candidate["input"]["reprocess"],
        "notice": candidate,
        # 판정 틀(사용자 결정 2026-10-20: 이 이벤트에 칸을 더해 틀 객체를 그대로 담음). 틀이 없는 분야·틀 검사 실패면 null
        "judgment": build_judgment(candidate, regions, pages=pages),
    }


def approved_payload(candidate: dict[str, Any], *, provider: str | None = None, regions: Any = None) -> dict[str, Any]:
    """검수자가 승인한 결과. 사람이 보류 항목까지 확인했으므로 보류(heldItems)를 풀고, 조건의 inReview를 모두 내리고,
    보류 때문에 빠졌던 접수 일정도 다시 내보냅니다(계약 application_schedule(approved=True)). reviewStatus는 APPROVED입니다.
    예전엔 승인용 사본에서 보류 목록을 찾다 KeyError로 멈췄고, 고쳐도 상세에 보류 목록이 남아 계약 위반(EVENT_HELD_ITEMS_CONFLICT)이었습니다."""
    # 자동 검증 기록·이슈는 유지합니다. 현재 보류만 해제하고 원 후보는 검수 작업 기록에 보존합니다.
    quality = {k: v for k, v in candidate["quality"].items() if k != "heldItems"}
    reviewed = {**candidate, "quality": quality}
    payload = completed_payload(reviewed, provider=provider, regions=regions)
    for condition in payload["conditions"]:
        condition.update(inReview=False, reviewReasons=[])
    payload.update(
        reviewStatus="APPROVED",
        notice=reviewed,
        # 조건(conditions)의 inReview를 내린 것과 같게, 판정 틀도 추출 검수 사유로는 확인 필요를 붙이지 않습니다(2026-10-21)
        judgment=build_judgment(reviewed, regions, approved=True),
        schedule=tools().application_schedule(reviewed, approved=True),
        applicationWindows=tools().application_windows(reviewed, approved=True),
    )
    payload.pop("heldItems", None)
    return payload


def _in_review(criteria: list[dict[str, Any]]) -> bool:
    return any(c["inReview"] or _in_review([i for o in c.get("options") or [] for i in o["criteria"]]) for c in criteria)


POLICY_FIELDS = ("STARTUP_SUPPORT", "SPACE_RECRUITMENT")


# 권역 이름 → 시도 이름(지역표로 코드를 찾으므로 통합·개편된 시도도 지역표를 따름)
REGION_GROUPS = {
    "수도권": ("서울", "인천", "경기"),
    "충청권": ("대전", "세종", "충북", "충남"),
    "호남권": ("광주", "전북", "전남"),
    "영남권": ("부산", "대구", "울산", "경북", "경남"),
    "동남권": ("부산", "울산", "경남"),
    "대경권": ("대구", "경북"),
    "강원권": ("강원",),
    "제주권": ("제주",),
}


def _codes_of(names: list[str], regions: Any) -> list[str] | None:
    """지역 이름(권역 포함) → 시군구 5자리. 하나라도 못 찾으면 None."""
    if not names or regions is None:
        return None
    found = []
    for name in names:
        name = re.sub(r"[\[\]()]", "", str(name)).strip()
        for part in REGION_GROUPS.get(name, (name,)):
            match = regions.lookup(part)
            if match.code is None or match.note:
                return None
            found.append(match.code[:5])
    return list(dict.fromkeys(found))


def policy_region_codes(policy: dict[str, Any], regions: Any, reading: Any = None) -> list[str] | None:
    """정책의 지원 지역 → 시군구 5자리 목록(xx000은 시도 전체). 전국이면 빈 목록, 정하지 못하면 None(빈 목록은 전국으로 읽혀 틀림).
    수집기 값(코드, 없으면 이름 — K-Startup은 「서울」처럼 이름만, 기업마당은 「충청권」 같은 권역도 줌)을 먼저 쓰고, 수집기가 지역을 모르면
    AI가 글에서 읽은 지원 지역을 씁니다. AI 값은 근거 문장이 원문에 있고 그 문장에 지역 이름이 있을 때만 씁니다."""
    scope = policy.get("regionScope")
    if scope == "NATIONWIDE":
        return []
    codes = [str(c) for c in policy.get("regionCodes") or [] if re.fullmatch(r"[0-9]{5}", str(c))]
    if codes:
        return list(dict.fromkeys(codes))
    if scope in ("SIDO", "SIGUNGU") and (found := _codes_of(policy.get("regionNames") or [], regions)) is not None:
        return found
    if reading is None:
        return None
    support = reading.answer.get("supportRegions") or {}
    names, quote = support.get("names") or [], support.get("quote")
    text = re.sub(r"\s+", "", str(policy.get("text") or ""))
    plain = re.sub(r"\s+", "", quote or "")
    if len(plain) < 2 or plain not in text or not names or not all(region_named(n, quote) or n in REGION_GROUPS and n in plain for n in names):
        return None
    if names == ["전국"]:
        return []
    return _codes_of(names, regions)


def policy_frame(policy: dict[str, Any], reading: Any, regions: Any) -> dict[str, Any] | None:
    """정책 판정 틀: AI 읽기가 있으면 그것으로, 없거나 틀 검사를 통과하지 못하면 K-Startup 구간 칸 규칙으로(startup·space).
    AI가 창업 지원·사업공간 모집이 아니라고 읽었으면 None(보내지 않음)."""
    startup = policy.get("field") == "STARTUP_SUPPORT"
    if reading is not None and not reading.answer["relevant"]:
        return None
    if reading is not None:
        frame = (startup_frame if startup else space_frame)(policy, reading, regions)
        errors = frame_errors(frame) if frame is not None else []
        if frame is not None and not errors:
            return frame
        if errors:  # 틀 검사에 걸린 AI 틀: 위치만 남기고 구간 칸 규칙으로(값은 남기지 않음)
            log.warning("정책 AI 판정 틀이 틀 검사에 걸렸습니다", extra={"rawPolicyId": policy.get("rawPolicyId"), "errors": errors[:5]})
    fallback = build_startup(policy) if startup else build_space(policy)
    return fallback if fallback is not None and not frame_errors(fallback) else None


def _day(value: Any) -> str | None:
    text = str(value or "")
    return f"{text[:4]}-{text[4:6]}-{text[6:8]}" if re.fullmatch(r"20\d{6}", text) else None


def policy_completed_payload(policy: dict[str, Any], frame: dict[str, Any] | None, regions: Any, reading: Any = None) -> dict[str, Any] | None:
    """정책 판정 틀 → extraction.completed payload(판정 요청안 2026-10-22: 틀은 이 이벤트의 judgment, rawPostId는 rawPolicyId).
    상세(notice)·자격 조건(conditions)은 주거 공고용이라 없습니다. 틀이 없거나 지원 지역을 정하지 못하면 None(보내지 않음).
    틀에 확인 필요 조건이 하나라도 있으면 IN_REVIEW입니다. 접수 기간은 K-Startup 날짜 칸(규칙)에서만 옮깁니다."""
    codes = policy_region_codes(policy, regions, reading)
    if frame is None or codes is None or policy.get("field") not in POLICY_FIELDS:
        return None
    record = policy.get("record") or {}
    start, end = _day(record.get("pbanc_rcpt_bgng_dt")), _day(record.get("pbanc_rcpt_end_dt"))
    every = [*frame["common"], *(c for g in frame["groups"] for c in g["criteria"]), *(c for p in frame["preferences"] for c in p["criteria"])]
    return {
        "rawPostId": policy["rawPolicyId"],
        "rawVersion": policy["version"],
        "field": policy["field"],
        "title": policy["title"],
        "conditions": [],
        "reviewStatus": "IN_REVIEW" if _in_review(every) else "AUTO_PASSED",
        "provider": policy.get("agency"),
        "regionCode": None,
        "regionCodes": codes,
        "schedule": {"applyStartOn": start, "applyEndOn": end} if start or end else None,
        "applicationWindows": [],
        "summary": None,
        "reprocess": bool(policy.get("reprocess")),
        "judgment": frame,
    }
