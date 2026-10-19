"""설정 → 공고 1건에 쓸 모델 묶음 (결정 C12).

auto (기본): primary = 로컬 AI, fallback = 유료 API(gpt-6-luna, 공고당 비용 상한)
  - 로컬 AI 호출이 실패(주소 없음·연결 안 됨)하면 그 호출만 바로 유료 API로 (FallbackOnErrorModel)
  - 로컬 AI 결과가 부족(실패 호출·계약 위반)하면 공고 전체를 유료 API로 다시 (extract_with_fallback)
  - LOCAL_AI_URL이 없으면(로컬 AI 구축 전) 유료 API를 바로 씁니다
  - 유료 API 키가 없으면 fallback 없이 로컬 AI만 씁니다(로컬도 안 되면 추출 실패로 기록)
recorded: 녹화 응답 재생(가짜 추출기). 유료 호출 없음
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from mujuthaek_extraction.config import Settings
from mujuthaek_extraction.model.base import ExtractionModel, ModelError, ModelRequest, ModelResponse, StopReason
from mujuthaek_extraction.model.local_ai import LocalChatModel
from mujuthaek_extraction.model.openai_responses import OpenAIResponsesModel
from mujuthaek_extraction.model.pricing import Budget, BudgetedModel


class UnavailableModel:
    """쓸 수 있는 모델이 없을 때. 호출하면 항상 실패합니다."""

    def __init__(self, reason: str):
        self.reason = reason

    def complete(self, request: ModelRequest) -> ModelResponse:
        raise ModelError(self.reason, transient=False)


class ReplayModel:
    """녹화 응답 재생 (EXTRACTION_MODEL_MODE=recorded). 폴더의 <호출 이름>.json(응답 문자열 목록)에서 첫 응답을 매번 돌려줍니다."""

    def __init__(self, folder: Path):
        self.answers: dict[str, str] = {}
        for path in folder.glob("*.json"):
            items = json.loads(path.read_text(encoding="utf-8"))
            if items:
                self.answers[path.stem] = items[0] if isinstance(items[0], str) else json.dumps(items[0], ensure_ascii=False)

    def complete(self, request: ModelRequest) -> ModelResponse:
        if request.call_name not in self.answers:
            raise ModelError(f"녹화 응답이 없는 호출입니다: {request.call_name}", transient=False)
        return ModelResponse(raw_text=self.answers[request.call_name], model_id="recorded", stop_reason=StopReason.END)


@dataclass
class NoticeModels:
    primary: ExtractionModel
    fallback: ExtractionModel | None
    budget: Budget | None = None  # 유료 API 비용 (공고 1건)
    names: dict[str, str] = field(default_factory=dict)


class ModelFactory:
    """설정에 맞는 모델(로컬 AI·유료 API·녹화 재생)을 한 번 만들어 두고, 공고마다 쓸 모델 묶음을 내줍니다."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self._local = LocalChatModel(settings.local_ai_url, settings.local_ai_model)  # 연결 실패 기억을 공고 사이에 유지합니다
        self._paid: OpenAIResponsesModel | None = None
        if settings.model_mode == "auto":
            key = settings.openai_api_key()
            if key:
                self._paid = OpenAIResponsesModel(
                    key,
                    model=settings.openai_model,
                    reasoning_effort=settings.openai_effort,
                    shared_schema=settings.openai_shared_schema,
                    light_calls=frozenset(settings.openai_light_calls),
                    light_effort=settings.openai_light_effort,
                )
        self._replay = ReplayModel(settings.recorded_dir) if settings.model_mode == "recorded" and settings.recorded_dir else None

    def describe(self) -> str:
        """로그용 요약. 키는 넣지 않습니다."""
        if self.settings.model_mode == "recorded":
            return f"recorded({self.settings.recorded_dir})"
        local = self.settings.local_ai_url or "없음"
        paid = self.settings.openai_model if self._paid else "키 없음"
        return f"auto(로컬 AI {local} → 유료 {paid}, 공고당 ${self.settings.notice_budget_usd:.2f})"

    def for_notice(self) -> NoticeModels:
        if self.settings.model_mode == "recorded":
            model = self._replay or UnavailableModel("EXTRACTION_RECORDED_DIR가 없습니다.")
            return NoticeModels(model, None, names={"primary": "recorded"})
        if self._paid is None:
            return NoticeModels(self._local, None, names={"primary": "local"})
        budget = Budget(self.settings.openai_model, self.settings.notice_budget_usd)
        paid = BudgetedModel(self._paid, budget)
        if not self.settings.local_ai_url:  # 로컬 AI가 아직 없으면 유료 API를 바로 씁니다 (같은 모델로 두 번 추출하지 않게)
            return NoticeModels(paid, None, budget, {"primary": self.settings.openai_model})
        return NoticeModels(self._local, paid, budget, {"primary": "local", "fallback": self.settings.openai_model})
