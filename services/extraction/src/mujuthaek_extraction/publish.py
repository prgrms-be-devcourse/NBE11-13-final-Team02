"""검증까지 끝난 후보 → extraction.completed payload (팀 이벤트 흐름 문서 4·8절, 2026-10-15 확정 형식).

- 필수: rawPostId, rawVersion, field, title, conditions, reviewStatus(AUTO_PASSED/PARTIALLY_PASSED/IN_REVIEW)
- 부분 완료(PARTIALLY_PASSED, 2026-10-17): 공고는 게시하고 heldItems의 항목·섹션만 보류(보류한 자격 조건은 inReview)
- 선택: provider, regionCode(시군구 5자리), schedule(applyStartOn/applyEndOn), applicationWindows(순위·대상별 접수 기간 — 알림용),
  summary, reprocess, notice(표준 공고 전체 — 상세 화면용), judgment(판정 틀 — 판정 담당이 정한 형식 그대로, judgment/)
- 검수 중이어도 내보내며, 자격 조건마다 inReview로 표시합니다. 검증을 통과하지 못한 접수 일정은 schedule에 넣지 않습니다.
봉투(eventId 등)는 공통 라이브러리 new_event로 만듭니다. 이 함수는 시계·UUID를 쓰지 않습니다.
"""

from __future__ import annotations

from typing import Any

from mujuthaek_extraction.contracts import tools
from mujuthaek_extraction.judgment import build_judgment


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
