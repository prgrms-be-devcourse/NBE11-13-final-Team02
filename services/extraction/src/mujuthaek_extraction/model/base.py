"""추출 모델 호출 인터페이스. 제공자 구현은 이 Protocol을 따르며, 이 패키지는 어떤 제공자에도 의존하지 않습니다.

모델 선택·유료 호출 범위는 별도 결정(E1) 후 제공자 구현을 추가합니다. 테스트·오프라인 실행은 RecordedModel을 씁니다.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Protocol


class StopReason(Enum):
    END = "END"  # 정상 종료
    MAX_TOKENS = "MAX_TOKENS"  # 출력 한도로 잘림
    REFUSED = "REFUSED"  # 제공자가 응답을 거부함
    OTHER = "OTHER"


@dataclass(frozen=True)
class ModelImage:
    """요청에 붙이는 그림(PDF 쪽을 그린 PNG 등). 그림을 받는 모델만 씁니다(image_reading)."""

    label: str  # 요청 글자에서 이 그림을 가리키는 이름 (예: "그림 1")
    media_type: str
    data: bytes


@dataclass(frozen=True)
class ModelRequest:
    prompt_version: str
    call_name: str
    system: str  # 모든 호출에 같은 공통 지시 (캐시 앞부분)
    user: str  # 원문·호별 표 (같은 공고의 호출끼리 같음, 캐시 앞부분)
    instructions: str  # 호출마다 다른 지시·구조 요약·재요청 사유 (캐시 뒷부분)
    output_schema: dict[str, Any]  # 제공자 어댑터가 Structured Output 제약에 맞게 바꿔 보낼 수 있습니다(결정 E5)
    max_output_tokens: int
    images: tuple[ModelImage, ...] = ()
    # 같은 공고의 모든 호출이 함께 쓰는 출력 형식(맡은 섹션만 채움). 제공자 출력 형식이 프롬프트 앞에 붙는 경우 호출마다 형식이 달라
    # 원문 캐시가 깨지므로(실측 OpenAI: 캐시 읽기 0건), 어댑터가 켜져 있으면 이것을 보냅니다. 응답 검사는 output_schema로 합니다
    shared_schema: dict[str, Any] | None = None


@dataclass(frozen=True)
class ModelResponse:
    raw_text: str
    model_id: str
    stop_reason: StopReason
    input_tokens: int | None = None
    output_tokens: int | None = None
    cached_input_tokens: int | None = None
    cache_write_tokens: int | None = None
    usage: dict[str, Any] | None = None  # 제공자가 준 사용량 원본 (job 기록용)


class ModelError(Exception):
    """제공자 호출 실패. transient=True면 제한 재시도 대상입니다(네트워크·한도 초과·일시 장애).
    maybe_billed=True: 요청이 제공자에 닿은 뒤 실패해(응답 대기 중 시간 초과·응답이 중간에 끊김) 비용이 청구됐을 수 있습니다."""

    def __init__(self, message: str, *, transient: bool, maybe_billed: bool = False):
        super().__init__(message)
        self.transient = transient
        self.maybe_billed = maybe_billed


class ExtractionModel(Protocol):
    def complete(self, request: ModelRequest) -> ModelResponse: ...
