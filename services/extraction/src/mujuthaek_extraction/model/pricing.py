"""모델 사용량 → 예상 비용과 예산 상한 (2-10 비용 집계의 최소 구현).

요율은 확인한 날짜·출처와 함께 둡니다. 청구서 금액이 아니라 응답 사용량과 표준 요율로 계산한 추정입니다.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from mujuthaek_extraction.model.base import ModelError, ModelRequest, ModelResponse


@dataclass(frozen=True)
class Price:
    input: float  # USD / 100만 토큰 (캐시 아닌 입력)
    cached_input: float
    cache_write: float
    output: float  # 추론 토큰 포함
    checked: str


IMAGE_TOKENS = 3_000  # 그림 한 장의 입력 토큰 상한 추정(쪽 그림 120dpi 기준, 실제보다 넉넉하게)
PRICES = {
    # 아카이브 실험 기록(lh_extraction_test/*/usage.json, 2026-10-14)에서 확인한 표준 단문 요율
    "gpt-6-luna": Price(input=0.10, cached_input=0.01, cache_write=0.125, output=0.50, checked="2026-10-14 developers.openai.com/api/docs/models/gpt-6-luna"),
}


def cost_usd(model: str, response: ModelResponse) -> float:
    price = PRICES[model]
    total = response.input_tokens or 0
    cached = response.cached_input_tokens or 0
    writes = response.cache_write_tokens or 0
    plain = max(total - cached - writes, 0)
    return (plain * price.input + cached * price.cached_input + writes * price.cache_write + (response.output_tokens or 0) * price.output) / 1_000_000


@dataclass
class Budget:
    """호출 전에 최악의 비용(입력 전부 캐시 쓰기 + 출력 한도 전부)을 더해도 상한 안일 때만 호출합니다."""

    model: str
    limit_usd: float
    spent_usd: float = 0.0
    calls: list[float] = field(default_factory=list)

    def worst_case(self, request: ModelRequest) -> float:
        price = PRICES[self.model]
        # 토큰 수를 모르므로 글자 수를 상한으로 씁니다(한국어는 대개 토큰 수 ≤ 글자 수).
        chars = len(request.system) + len(request.user) + len(request.instructions) + IMAGE_TOKENS * len(request.images)
        return (chars * price.cache_write + request.max_output_tokens * price.output) / 1_000_000

    def allow(self, request: ModelRequest) -> bool:
        return self.spent_usd + self.worst_case(request) <= self.limit_usd

    def record(self, response: ModelResponse) -> float:
        cost = cost_usd(self.model, response)
        self.spent_usd += cost
        self.calls.append(cost)
        return cost


class BudgetedModel:
    """실제 사용량으로 Budget.spent_usd를 누적하는 감싸기. 상한을 넘을 호출은 보내지 않고 ModelError를 냅니다."""

    def __init__(self, model, budget: Budget):
        self.model = model
        self.budget = budget

    def complete(self, request: ModelRequest) -> ModelResponse:
        if not self.budget.allow(request):
            raise ModelError(f"공고당 비용 상한(${self.budget.limit_usd:.2f})을 넘을 수 있어 호출하지 않았습니다.", transient=False)
        try:
            response = self.model.complete(request)
        except ModelError as error:
            if error.maybe_billed:
                # 시간 초과·끊긴 응답은 청구됐을 수 있어 최악 비용으로 셉니다. 예전엔 성공한 호출만 세어,
                # 큰 호출이 시간 초과로 세 번 재시도되면 공고 하나가 상한의 세 배까지 쓸 수 있었습니다(검토 2026-10-16)
                cost = self.budget.worst_case(request)
                self.budget.spent_usd += cost
                self.budget.calls.append(cost)
            raise
        self.budget.record(response)
        return response
