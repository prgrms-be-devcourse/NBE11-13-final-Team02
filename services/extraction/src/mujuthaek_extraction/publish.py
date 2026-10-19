"""검증까지 끝난 후보 → extraction.completed payload (팀 이벤트 흐름 문서 4·8절, 2026-10-15 확정 형식).

- 필수: rawPostId, rawVersion, field, title, conditions, reviewStatus(AUTO_PASSED/PARTIALLY_PASSED/IN_REVIEW)
- 부분 완료(PARTIALLY_PASSED, 2026-10-17): 공고는 게시하고 heldItems의 항목·섹션만 보류(보류한 자격 조건은 inReview)
- 선택: provider, regionCode(시군구 5자리), schedule(applyStartOn/applyEndOn), applicationWindows(순위·대상별 접수 기간 — 알림용),
  summary, reprocess, notice(표준 공고 전체 — 상세 화면용)
- 검수 중이어도 내보내며, 자격 조건마다 inReview로 표시합니다. 검증을 통과하지 못한 접수 일정은 schedule에 넣지 않습니다.
봉투(eventId 등)는 공통 라이브러리 new_event로 만듭니다. 이 함수는 시계·UUID를 쓰지 않습니다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

KST = timezone(timedelta(hours=9))


def _issue_targets(candidate: dict[str, Any], *, with_notes: bool = False) -> list[tuple[str, str]]:
    quality = candidate["quality"]
    return [(i["target"], i["code"]) for i in [*quality["reviewIssues"], *(quality["notes"] if with_notes else [])]]


def _codes_under(issues: list[tuple[str, str]], prefix: str) -> list[str]:
    return sorted({code for target, code in issues if target == prefix or target.startswith(prefix + "/")})


def _day(value: dict[str, str] | None) -> str | None:
    if not value or value["precision"] == "MONTH":
        return None  # 월만 아는 값은 날짜로 바꾸지 않습니다
    if value["precision"] == "DAY":
        return value["value"]
    return datetime.fromisoformat(value["value"]).astimezone(KST).date().isoformat()


def _held_codes(candidate: dict[str, Any], pointer: str) -> list[str]:
    """부분 완료로 보류한 범위(항목 또는 섹션)에 pointer가 들면 그 보류 사유."""
    return sorted(
        {code for h in candidate["quality"].get("heldItems", []) if pointer == h["target"] or pointer.startswith(h["target"] + "/") for code in h["codes"]}
    )


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
                "id": item["id"],
                "target": item["appliesTo"],
                "targetGroups": list(item["scope"]["targetGroups"]),
                "category": item["category"],
                "description": item["description"],
                "originalText": item["originalText"],
                "referenceTime": item["referenceTime"],
                "rule": item["rule"],
                "exceptions": item["exceptions"],
                "inReview": bool(reasons),
                "reviewReasons": reasons,
            }
        )
    return out


def application_schedule(candidate: dict[str, Any]) -> dict[str, str | None] | None:
    """공고 전체에 적용되는 접수 일정이 하나이고 검수 이슈·표시 메모가 없을 때만 내보냅니다."""
    issues = _issue_targets(candidate, with_notes=True)
    found = [
        (index, s)
        for index, s in enumerate(candidate["schedules"])
        if "APPLICATION" in s["kinds"] and s["scope"]["level"] == "NOTICE" and s["scope"]["resolution"] == "RESOLVED"
    ]
    if len(found) != 1 or _codes_under(issues, f"/schedules/{found[0][0]}") or _held_codes(candidate, f"/schedules/{found[0][0]}"):
        return None
    schedule = found[0][1]
    start, end = _day(schedule["startsAt"]), _day(schedule["endsAt"])
    return {"applyStartOn": start, "applyEndOn": end} if start or end else None


def application_windows(candidate: dict[str, Any]) -> list[dict[str, Any]]:
    """알림용 접수 기간 전체. 순위·대상별로 접수 기간이 따로면(GH 1·2·3순위·현장 접수) 하나씩 담습니다.
    검수 이슈가 붙은 일정과 날짜가 하나도 없는 일정은 뺍니다."""
    issues = _issue_targets(candidate, with_notes=True)
    out = []
    for index, schedule in enumerate(candidate["schedules"]):
        if "APPLICATION" not in schedule["kinds"] or _codes_under(issues, f"/schedules/{index}") or _held_codes(candidate, f"/schedules/{index}"):
            continue
        start, end = _day(schedule["startsAt"]), _day(schedule["endsAt"])
        if start is None and end is None:
            continue
        out.append(
            {
                "scheduleId": schedule["id"],
                "label": schedule["rawLabel"],
                "applyStartOn": start,
                "applyEndOn": end,
                "priorityRank": schedule["priorityRank"],
                "targetGroups": list(schedule["scope"]["targetGroups"]),
                "scopeDescription": schedule["scope"]["description"],
            }
        )
    return out


def sigungu_code(candidate: dict[str, Any], fallback: str | None = None) -> str | None:
    """위치들의 법정동코드 앞 5자리가 하나로 같을 때만 그 시군구 코드를 씁니다(결정 A3)."""
    prefixes = {loc["regionCode"][:5] for loc in candidate["locations"] if loc["regionCode"] and loc["regionCode"][2:5] != "000"}
    if len(prefixes) == 1:
        return prefixes.pop()
    return fallback if fallback and len(fallback) == 5 else None


def completed_payload(candidate: dict[str, Any], *, provider: str | None = None, region_fallback: str | None = None) -> dict[str, Any]:
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
        **({"heldItems": candidate["quality"]["heldItems"]} if review == "PARTIALLY_PASSED" else {}),
        "provider": provider,
        "regionCode": sigungu_code(candidate, region_fallback),
        "schedule": application_schedule(candidate),
        "applicationWindows": application_windows(candidate),
        "summary": candidate["notice"]["description"],
        "reprocess": candidate["input"]["reprocess"],
        "notice": candidate,
    }


def approved_payload(candidate: dict[str, Any], *, provider: str | None = None, region_fallback: str | None = None) -> dict[str, Any]:
    """검수자가 승인한 결과. 검수 이슈는 사람이 확인했으므로 조건의 inReview를 모두 내리고 reviewStatus를 APPROVED로 보냅니다."""
    quality = {k: v for k, v in candidate["quality"].items() if k != "heldItems"}
    reviewed = {**candidate, "quality": {**quality, "reviewIssues": []}}
    payload = completed_payload(reviewed, provider=provider, region_fallback=region_fallback)
    for condition in payload["conditions"]:
        condition.update(inReview=False, reviewReasons=[])
    payload["reviewStatus"] = "APPROVED"
    payload["notice"] = candidate  # 상세 화면에는 검증 기록(검수 이슈 포함)을 그대로 남깁니다
    return payload
