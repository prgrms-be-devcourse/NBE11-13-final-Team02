"""raw.notice.collected 1건 → 추출 결과 1건 (B5). DB·큐를 모르는 순수 처리 단계입니다. 저장·발행은 store·worker가 합니다.

순서: 입력 해석(intake) → 섹션별 추출(로컬 AI, 실패·부족하면 유료 API) → 결합·검증 → 자격 조건 AI 재확인 → extraction.completed
상태
  COMPLETED       검증 통과(AUTO_PASSED)로 발행
  IN_REVIEW       발행하되 검수 대기열에 올림(조건별 inReview)
  INTAKE_REVIEW   자료끼리 맞지 않아 추출하지 않고 검수 대기열에 올림 (발행 없음)
  WAITING_SOURCE  S3 원문·첨부를 아직 읽을 수 없음 (발행 없음, 다시 처리 대상)
  FAILED          모델 호출 실패·추출 실패 (발행 없음, 다시 처리 대상)
  REJECTED        이벤트 형식이 잘못됨 (발행 없음, 다시 처리하지 않음)
  UNSUPPORTED     아직 규칙이 없는 분야 (발행 없음)
"""

from __future__ import annotations

import copy
import logging
import re
from collections.abc import Callable
from dataclasses import asdict, dataclass, field
from datetime import datetime
from typing import Any

from mujuthaek_extraction.assemble.assembler import AssemblyInput, assemble
from mujuthaek_extraction.assemble.quarantine import quarantine
from mujuthaek_extraction.assemble.regions import SOURCE_SIDO, RegionCodeTable
from mujuthaek_extraction.contracts import tools
from mujuthaek_extraction.extract import ExtractionRun, run_extraction
from mujuthaek_extraction.image_reading import read_image_tables
from mujuthaek_extraction.intake import ApiReferenceProvider, IntakeStatus, SourceReader, build_intake
from mujuthaek_extraction.intake.adapter import PDF_TEXT_DOCUMENT_ID
from mujuthaek_extraction.judgment import build_judgment
from mujuthaek_extraction.judgment.pages import attach_locator_pages, evidence_pages
from mujuthaek_extraction.method import LEGACY, STRUCTURED, extraction_method
from mujuthaek_extraction.model.factory import NoticeModels
from mujuthaek_extraction.model.fallback import FallbackOnErrorModel, extract_with_fallback
from mujuthaek_extraction.publish import completed_payload
from mujuthaek_extraction.recheck import lost_by_recheck, recheck_problems
from mujuthaek_extraction.rules import discover
from mujuthaek_extraction.rules.base import FieldRules
from mujuthaek_extraction.usage import MeteredModel, UsageRecorder
from mujuthaek_extraction.verify import SourceBundle, verify_extraction

log = logging.getLogger(__name__)
PRODUCER = "extraction"
EVENT_TYPE = "extraction.completed"
# 분야별 규칙은 rules/ 하위 폴더에서 자동으로 모읍니다(FIELD·RULES를 내놓은 폴더만, 요구 2-02). 지금은 HOUSING만 있고
# BUSINESS_SPACE·STARTUP_SUPPORT는 폴더에 규칙을 넣기 전까지 UNSUPPORTED입니다
_CURRENT_RULES, _LEGACY_RULES = discover()
RULES_BY_FIELD: dict[str, FieldRules] = _CURRENT_RULES
# 추출 방식별 규칙(method.py). legacy는 2026-10-17 이전 방식(표 행 글자만 + 이전 프롬프트, 폴더의 RULES_LEGACY)
RULES_BY_METHOD: dict[str, dict[str, FieldRules]] = {STRUCTURED: RULES_BY_FIELD, LEGACY: _LEGACY_RULES}
INTAKE_STATUS = {IntakeStatus.REJECTED: "REJECTED", IntakeStatus.WAITING_SOURCE: "WAITING_SOURCE", IntakeStatus.REVIEW_REQUIRED: "INTAKE_REVIEW"}
RETRYABLE = {"WAITING_SOURCE", "FAILED"}  # 다시 처리할 수 있는 상태 (processed_events에 넣지 않음)


@dataclass
class Outcome:
    """이벤트 1건 처리 결과입니다. 상태(모듈 설명 참고)와 발행할 이벤트, job 기록에 남길 시도·응답·비용을 담습니다."""

    status: str
    event: dict[str, Any] | None = None  # 발행할 extraction.completed 봉투
    candidate: dict[str, Any] | None = None  # 검증까지 끝난 표준 공고 후보
    review_reasons: list[str] = field(default_factory=list)
    model_used: str | None = None
    cost_usd: float = 0.0
    attempts: list[dict[str, Any]] = field(default_factory=list)
    error: str | None = None
    responses: list[list[str]] = field(default_factory=list)  # AI 응답 원문 [호출 이름, 응답] (다시 돌리지 않고 원인을 볼 수 있게)
    repairs: list[str] = field(default_factory=list)  # 재확인·항목 격리로 바꾼 것 (로그·확인용)
    pdf_pages: list[dict[str, Any]] = field(default_factory=list)  # 첨부 PDF의 쪽·표 구조 JSON
    usage: dict[str, Any] = field(default_factory=dict)  # 호출별 시간·토큰·추정 비용과 단계별 시간 (usage.UsageRecorder.summary)

    @property
    def retryable(self) -> bool:
        return self.status in RETRYABLE


def _attempts(*runs: ExtractionRun | None) -> list[dict[str, Any]]:
    return [asdict(a) for run in runs if run is not None for a in run.attempts]


def _responses(*runs: ExtractionRun | None) -> list[list[str]]:
    return [[name, text] for run in runs if run is not None for name, text in run.raw_responses]


UNVERIFIED_RULE = {"status": "UNSTRUCTURED", "expression": None, "reason": "조건식이 계약 검사를 통과하지 못해 원문만 보냅니다. 검수 후 다시 보냅니다."}
ELIGIBILITY_ERROR = re.compile(r" /eligibility/(\d+)(?:/|$)")


def degraded_payload(payload: dict[str, Any], errors: list[str], regions: Any = None, pages: dict[str, int] | None = None) -> dict[str, Any]:
    """상세 notice가 계약을 통과하지 못할 때: notice를 빼고 IN_REVIEW로 보냅니다.
    문제가 난 자격 조건은 조건식을 내려(원문만) 자동 판정되지 않게 하고 검수 표시를 붙입니다. 전체 후보는 DB에 남아 검수자가 고칩니다.
    판정 틀도 같은 조건을 원문 질문(ASK)·확인 필요로 다시 만듭니다(2026-10-21: 깨진 조건식으로 만든 틀이 확정으로 나갔음)."""
    broken = {int(m[1]) for e in errors if (m := ELIGIBILITY_ERROR.search(e))}
    conditions = []
    for index, condition in enumerate(payload["conditions"]):
        if index in broken:
            reasons = sorted({*condition["reviewReasons"], "CONTRACT_RULE_INVALID"})
            condition = {**condition, "rule": dict(UNVERIFIED_RULE), "exceptions": [], "inReview": True, "reviewReasons": reasons}
        conditions.append(condition)
    rest = {k: v for k, v in payload.items() if k != "notice"}
    return {**rest, "conditions": conditions, "reviewStatus": "IN_REVIEW", "judgment": _degraded_judgment(payload, broken, errors, regions, pages)}


def _degraded_judgment(
    payload: dict[str, Any], broken: set[int], errors: list[str], regions: Any, pages: dict[str, int] | None = None
) -> dict[str, Any] | None:
    """문제가 난 자격 조건을 조건식 없이(원문만)·검수 이슈를 붙여 판정 틀을 다시 만듭니다. 틀 자체가 계약 오류면 None."""
    candidate = payload.get("notice")
    if not isinstance(candidate, dict) or any("/payload/judgment" in e for e in errors):
        return None
    flagged = copy.deepcopy(candidate)
    for index in broken:
        if index < len(flagged.get("eligibility") or []):
            flagged["eligibility"][index]["rule"] = dict(UNVERIFIED_RULE)
            flagged["eligibility"][index]["exceptions"] = []
            issue = {"code": "CONTRACT_RULE_INVALID", "target": f"/eligibility/{index}", "description": UNVERIFIED_RULE["reason"]}
            flagged["quality"]["reviewIssues"].append(issue)
    try:
        return build_judgment(flagged, regions, pages=pages)
    except Exception:  # noqa: BLE001 — 계약을 통과하지 못한 후보라 틀을 못 만들면 틀 없이 보냅니다
        log.warning("검수 전환 판정 틀을 만들지 못해 null로 보냅니다", exc_info=True)
        return None


def process(
    raw_event: dict[str, Any],
    *,
    models: NoticeModels,
    reader: SourceReader,
    regions: RegionCodeTable | None,
    clock: Callable[[], datetime],
    new_event: Callable[[str, str, dict[str, Any]], dict[str, Any]],
    api_provider: ApiReferenceProvider | None = None,
    require_pdf: bool = True,
    method: str | None = None,
    parallel_calls: int = 1,
    parallel_stagger: float = 0.0,
) -> Outcome:
    """raw.notice.collected 이벤트 1건을 입력 해석부터 extraction.completed 봉투까지 처리해 결과를 돌려줍니다.
    상세 공고가 계약을 통과하지 못하면 공고 상세를 빼고 검수로 보내며, 그것도 안 되면 발행하지 않고 FAILED로 남깁니다.
    호출·단계마다 걸린 시간과 토큰을 재어 Outcome.usage에 담습니다(결과는 바꾸지 않음).
    parallel_calls: 구조 호출 뒤 섹션 호출을 동시에 보낼 수(워커는 설정 EXTRACTION_PARALLEL_CALLS). 1이면 하나씩.
    parallel_stagger: 병렬로 보낼 때 섹션 호출을 내보내는 간격(초, 워커는 설정 EXTRACTION_PARALLEL_STAGGER_SECONDS)."""
    usage = UsageRecorder()
    names = models.names
    metered = NoticeModels(
        MeteredModel(models.primary, usage, names.get("primary", "primary")),
        MeteredModel(models.fallback, usage, names.get("fallback", "fallback")) if models.fallback is not None else None,
        models.budget,
        names,
    )
    outcome = _process(raw_event, metered, usage, reader, regions, clock, new_event, api_provider, require_pdf, method, parallel_calls, parallel_stagger)
    outcome.usage = usage.summary()
    return outcome


def _process(
    raw_event: dict[str, Any],
    models: NoticeModels,
    usage: UsageRecorder,
    reader: SourceReader,
    regions: RegionCodeTable | None,
    clock: Callable[[], datetime],
    new_event: Callable[[str, str, dict[str, Any]], dict[str, Any]],
    api_provider: ApiReferenceProvider | None,
    require_pdf: bool,
    method: str | None,
    parallel_calls: int,
    parallel_stagger: float,
) -> Outcome:
    method = method or extraction_method()
    with usage.stage("intake"):
        intake = build_intake(raw_event, reader, api_provider, require_pdf=require_pdf, table_labels=method == STRUCTURED)
    if not intake.ready:
        reasons = [f"{i.code}: {i.message}" for i in intake.issues]
        return Outcome(INTAKE_STATUS[intake.status], review_reasons=reasons, error="; ".join(reasons))
    rules = RULES_BY_METHOD[method].get(intake.raw.field)
    if rules is None:
        return Outcome("UNSUPPORTED", error=f"{intake.raw.field} 분야 추출 규칙이 아직 없습니다.")

    material, context = intake.material, intake.context
    # 글자로 읽히지 않는 그림 표가 있으면 먼저 그림 판독 글자를 원문에 붙입니다(image_reading). 실패해도 추출은 계속합니다
    reader_model = FallbackOnErrorModel(models.primary, models.fallback) if models.fallback is not None else models.primary
    with usage.stage("image_reading"):
        material, context, image_run = read_image_tables(material, context, reader_model)
    pdf_pages = list(material.pdf_pages)
    if models.fallback is not None:
        result = extract_with_fallback(rules, material, models.primary, models.fallback, usage=usage, parallel=parallel_calls, stagger=parallel_stagger)
        run, first_run, used = result.run, result.primary_run, result.used
        model_used = models.names.get(used, used)
        recheck_model = FallbackOnErrorModel(models.primary, models.fallback) if used == "primary" else models.fallback
    else:
        run, first_run = run_extraction(rules, material, models.primary, usage=usage, parallel=parallel_calls, stagger=parallel_stagger), None
        model_used, recheck_model = models.names.get("primary", "primary"), models.primary
    cost = lambda: models.budget.spent_usd if models.budget else 0.0  # noqa: E731
    if run.notes:  # 잘림 뒤 나눠 부르기·항목 살리기(extract, 2026-10-24): 작업 기록에 남겨 평가에서 볼 수 있게
        usage.record("extractionNotes", list(run.notes))

    def assembled(parts: list[dict[str, Any]]) -> dict[str, Any] | None:
        source_code = context.source_references[0]["sourceCode"] if context.source_references else None
        default_sido = SOURCE_SIDO.get(source_code)  # SH 공고의 "강동구"처럼 시도가 생략된 지역을 위해
        candidate = assemble(parts, AssemblyInput(context, material, regions=regions, default_sido=default_sido)).candidate
        if candidate is not None:  # 입력 단계에서 정한 사람 확인 강제 (예: 공고 PDF 없이 수집 글자로 추출)
            for code, description in context.forced_review:
                candidate["quality"]["reviewIssues"].append({"code": code, "target": "/documents", "description": description})
        return candidate

    def verify(candidate: dict[str, Any]):
        return verify_extraction(candidate, SourceBundle.from_material(material), context.api_reference, clock=clock)

    def evaluate(parts: list[dict[str, Any]]):
        candidate = assembled(parts)
        return None if candidate is None else verify(candidate)

    with usage.stage("assemble_verify"):
        first = evaluate(run.parts)
    if first is None:
        failed = "; ".join(f"{name}: {reason}" for name, reason in run.failed_calls) or "공고 기본 정보를 추출하지 못했습니다."
        attempts, responses = _attempts(image_run, first_run, run), _responses(image_run, first_run, run)
        return Outcome("FAILED", model_used=model_used, cost_usd=cost(), attempts=attempts, error=failed, responses=responses, pdf_pages=pdf_pages)

    # 1) 문제 항목을 AI에 한 번 더 (오류 종류와 무관하게 검사기 문구를 그대로 전달)
    with usage.stage("recheck"):
        recheck = recheck_problems(rules, material, recheck_model, run.parts, lambda parts: evaluate(parts) or first)
    report = recheck.report
    repairs = [f"재확인 {'채택' if recheck.accepted else '미채택'}: {', '.join(recheck.rechecked)}"] if recheck.rechecked else []
    # 섹션별 채택·거절 이유를 작업 기록(usage.recheck)과 로그에 남깁니다(2026-10-24: 평가에서 어느 섹션을 왜 버렸는지 볼 수 있게)
    if recheck.adopted_sections:
        repairs.append(f"재확인 섹션별 채택: {', '.join(recheck.adopted_sections)}")
    repairs += [f"재확인 미채택({scope}): {reason}" for scope, reason in recheck.rejected]
    if (summary := recheck.summary()) is not None:
        usage.record("recheck", summary)
    # 2) 그래도 남은 계약 오류는 위치만 보고 그 항목만 낮춥니다 (검증 전 후보에 적용하고 다시 검증)
    quarantine_actions: list[str] = []
    if report.contract_errors:
        with usage.stage("quarantine"):
            cleaned, actions = quarantine(assembled(recheck.parts) or report.candidate, report.contract_errors)
            report = verify(cleaned)
        repairs += actions
        quarantine_actions = list(actions)
    # 공통 검사: 재확인 답으로 바꿔 넣으며 처음 값이 사라진 칸, 계약 오류 정리로 바꾸거나 비운 값을 메모로 남겨 검수로 보냅니다(2026-10-26)
    changed = [*lost_by_recheck(run.parts, recheck.parts if recheck.accepted else run.parts), *(("/", a) for a in quarantine_actions)]
    notes = report.candidate["quality"].setdefault("notes", [])
    for target, description in changed:
        notes.append({"code": "AI_OUTPUT_VALUE_CHANGED", "target": target, "description": description})
    raw = raw_event["payload"]
    with usage.stage("publish"):
        # 판정 틀의 page: 근거 위치를 PDF 원문의 쪽 표시로 쪽 번호로 바꿉니다
        pages = evidence_pages(report.candidate, SourceBundle.from_material(material).texts)
        # 공고 상세 API의 근거 쪽수: PDF 글자 원문 근거의 글자 범위에 같은 쪽 번호를 싣습니다(저장 후보에도 남아 승인 발행에도 쓰임)
        attach_locator_pages(report.candidate, pages, PDF_TEXT_DOCUMENT_ID)
        payload = completed_payload(report.candidate, provider=raw.get("provider"), regions=regions, pages=pages)
        event = {**new_event(EVENT_TYPE, PRODUCER, payload), "traceId": raw_event.get("traceId")}
        schema = f"events/{EVENT_TYPE}.v1.json"
        errors = tools().validate(event, schema)
    attempts, responses = _attempts(image_run, first_run, run, recheck.run), _responses(image_run, first_run, run, recheck.run)
    reasons = [f"{i['code']} {i['target']}" for i in report.candidate["quality"]["reviewIssues"]]
    if errors:
        event = {**event, "payload": degraded_payload(payload, errors, regions, pages)}
        retry_errors = tools().validate(event, schema)
        if retry_errors:  # 우리 쪽 결함: 발행하지 않고 실패로 남깁니다
            failed = "; ".join(retry_errors[:5])
            return Outcome("FAILED", None, report.candidate, [], model_used, cost(), attempts, failed, responses, repairs, pdf_pages)
        log.warning("상세 notice 없이 검수로 발행", extra={"rawPostId": raw.get("rawPostId"), "errors": errors[:5]})
        reasons.append("EVENT_DETAIL_OMITTED: 상세 공고가 계약을 통과하지 못해 조건만 보냈습니다")
        return Outcome("IN_REVIEW", event, report.candidate, reasons, model_used, cost(), attempts, "; ".join(errors[:5]), responses, repairs, pdf_pages)
    # PARTIAL: 공고는 게시하고 보류 항목만 '원문 확인 필요'(검수 대기열에 넣지 않음, store.REVIEW_STATUSES 밖)
    status = {"AUTO_PASSED": "COMPLETED", "PARTIALLY_PASSED": "PARTIAL"}.get(payload["reviewStatus"], "IN_REVIEW")
    log.info("추출 완료", extra={"rawPostId": raw.get("rawPostId"), "status": status, "model": model_used, "recheckAccepted": recheck.accepted})
    if repairs:
        log.info("자동 수정", extra={"rawPostId": raw.get("rawPostId"), "repairs": repairs[:20]})
    return Outcome(status, event, report.candidate, reasons, model_used, cost(), attempts, responses=responses, repairs=repairs, pdf_pages=pdf_pages)
