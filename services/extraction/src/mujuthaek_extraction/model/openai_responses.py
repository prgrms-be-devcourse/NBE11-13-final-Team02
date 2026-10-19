"""OpenAI Responses API 연결 (결정 E1: 실험 모델 gpt-6-luna).

아카이브 실험(run_extraction.py)에서 동작을 확인한 요청 형식을 따르며 SDK 없이 표준 라이브러리 HTTP로 호출합니다.
- 키는 생성자로만 받고 출력·저장하지 않습니다. 오류 응답 본문도 출력하지 않습니다(제공자가 요청 내용을 되돌려 줄 수 있음).
- store=False로 제공자 쪽 응답 보관을 요청하지 않습니다.
- 입력 순서: [공통 지시 → 원문 → 호출별 지시]. 같은 공고의 호출끼리 앞부분이 같아 프롬프트 캐시가 맞습니다.
"""

from __future__ import annotations

import base64
import functools
import hashlib
import http.client
import json
import re
import urllib.error
import urllib.request
from collections.abc import Callable
from pathlib import Path
from typing import Any

from mujuthaek_extraction.model.base import ModelError, ModelRequest, ModelResponse, StopReason
from mujuthaek_extraction.model.strict_schema import drop_null_optionals, to_strict

ENDPOINT = "https://api.openai.com/v1/responses"
TRANSIENT_STATUS = {408, 409, 429, 500, 502, 503, 504}


def read_api_key(env_file: Path) -> str | None:
    """KEY=VALUE 형식 파일에서 OPENAI_API_KEY만 읽습니다. 값은 돌려주기만 하고 출력하지 않습니다."""
    for line in env_file.read_text(encoding="utf-8-sig").splitlines():
        match = re.match(r"^\s*(?:export\s+)?OPENAI_API_KEY\s*=\s*(.*?)\s*$", line)
        if match:
            return match.group(1).strip().strip("\"'") or None
    return None


@functools.lru_cache(maxsize=4)
def _strict_cached(schema_json: str) -> tuple[dict[str, Any], frozenset[str]]:
    return to_strict(json.loads(schema_json))


def _strict(schema: dict[str, Any]) -> tuple[dict[str, Any], frozenset[str]]:
    """공유 형식은 호출마다 같아 변환을 한 번만 합니다."""
    return _strict_cached(json.dumps(schema, ensure_ascii=False, sort_keys=True))


def _keep_own(value: Any, schema: dict[str, Any]) -> Any:
    """공유 형식으로 받은 응답에서 이번 호출이 맡은 칸(맨 위·coverage)만 남깁니다. 검사는 호출 형식(output_schema)으로 합니다."""
    if not isinstance(value, dict):
        return value
    own = schema.get("properties", {})
    kept = {k: v for k, v in value.items() if k in own}
    coverage_own = (own.get("coverage") or {}).get("properties")
    if isinstance(kept.get("coverage"), dict) and coverage_own is not None:
        kept["coverage"] = {k: v for k, v in kept["coverage"].items() if k in coverage_own}
    # 맡은 목록 섹션을 null로 비우고 coverage에만 사유를 적은 경우(공유 형식은 섹션이 선택 칸이라 생김) 빈 목록으로 둡니다.
    # 실측 OpenAI LH 제주 든든전세: 공급주택목록이 첨부라 supplyGroups를 null로 → 필수 칸 누락으로 구조 호출을 다시 요청
    covered = kept.get("coverage") if isinstance(kept.get("coverage"), dict) else {}
    for key in schema.get("required", []):
        if key not in kept and (own.get(key) or {}).get("type") == "array" and isinstance(covered.get(key), dict):
            kept[key] = []
    return kept


class OpenAIResponsesModel:
    """OpenAI Responses API에 엄격 JSON 스키마로 요청하는 유료 추출 모델입니다."""

    def __init__(
        self,
        api_key: str,
        model: str = "gpt-6-luna",
        reasoning_effort: str = "medium",
        timeout_seconds: float = 900,
        opener: Callable[..., Any] = urllib.request.urlopen,
        shared_schema: bool = False,
        light_calls: frozenset[str] = frozenset(),
        light_effort: str = "low",
    ):
        self._api_key = api_key
        self.model = model
        self.reasoning_effort = reasoning_effort
        self.shared_schema = shared_schema  # 모든 호출이 같은 출력 형식 + 원문 끝 캐시 지점(설정 OPENAI_SHARED_SCHEMA)
        self.light_calls = light_calls  # 추론 강도를 낮출 호출(설정 OPENAI_LIGHT_CALLS)
        self.light_effort = light_effort
        self.timeout_seconds = timeout_seconds
        self._opener = opener
        self._optional_names: dict[str, frozenset[str]] = {}

    def __repr__(self) -> str:  # 키가 로그에 섞이지 않게 합니다
        return f"OpenAIResponsesModel(model={self.model!r}, reasoning_effort={self.reasoning_effort!r})"

    def body(self, request: ModelRequest) -> dict[str, Any]:
        """요청 본문을 만들고, 응답에서 걷어 낼 선택 칸 이름을 호출별로 기억합니다. 공유 형식이면 캐시 지점·캐시 열쇠를 붙입니다."""
        shared = self.shared_schema and request.shared_schema is not None
        strict, optional = _strict(request.shared_schema) if shared else to_strict(request.output_schema)
        self._optional_names[request.call_name] = optional
        effort = self.light_effort if request.call_name in self.light_calls else self.reasoning_effort
        source: dict[str, Any] = {"type": "input_text", "text": request.user}
        instructions = request.instructions
        if shared:
            # 출력 형식이 프롬프트 앞에 붙어, 호출마다 형식이 다르면 원문 캐시가 매번 깨졌습니다(실측: 9회 모두 캐시 읽기 0, 쓰기 요금만).
            # 같은 형식을 보내고 원문 끝에 캐시 지점을 둡니다. 맡지 않은 섹션은 null로 두게 하고 응답에서 걸러 냅니다(_keep_own)
            source["prompt_cache_breakpoint"] = {"mode": "explicit"}
            assigned = ", ".join(k for k in request.output_schema.get("properties", {}) if k not in ("quotes", "missingFields", "coverage"))
            instructions += (
                f"\n\n# 출력 형식 안내\n출력 형식에는 모든 섹션 칸이 있지만 이번 호출은 {assigned}만 채웁니다. 나머지 섹션과 그 coverage는 null로 둡니다."
            )
        body: dict[str, Any] = {
            "model": self.model,
            "store": False,
            "reasoning": {"effort": effort},
            "max_output_tokens": request.max_output_tokens,
            "input": [
                {
                    "role": "user",
                    "content": [
                        {"type": "input_text", "text": request.system},
                        source,
                        {"type": "input_text", "text": instructions},
                        *(
                            {"type": "input_image", "image_url": f"data:{image.media_type};base64,{base64.b64encode(image.data).decode('ascii')}"}
                            for image in request.images
                        ),
                    ],
                }
            ],
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "extraction_shared" if shared else f"extraction_{request.call_name}",
                    "strict": True,
                    "schema": strict,
                }
            },
        }
        if shared:
            body["prompt_cache_options"] = {"mode": "explicit"}
            # 같은 공고의 호출을 같은 캐시로 보내는 열쇠(원문 해시). 실측: 같은 원문인데 일정 호출에서 캐시를 다시 씀
            body["prompt_cache_key"] = "notice-" + hashlib.sha256(request.user.encode("utf-8")).hexdigest()[:32]
        return body

    def complete(self, request: ModelRequest) -> ModelResponse:
        """요청을 보내 응답을 돌려줍니다. 실패는 재시도 여부(transient)와 과금 가능성(maybe_billed)을 담은 ModelError로 바꿉니다."""
        http_request = urllib.request.Request(
            ENDPOINT,
            data=json.dumps(self.body(request), ensure_ascii=False).encode("utf-8"),
            headers={"Authorization": "Bearer " + self._api_key, "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with self._opener(http_request, timeout=self.timeout_seconds) as response:
                result = json.load(response)
        except http.client.IncompleteRead:
            # 응답이 중간에 끊김: 예전엔 ModelError가 아니라 날것의 예외라 재시도·대체 모델을 건너뛰고 추출 전체가 멈췄습니다
            raise ModelError("응답이 중간에 끊겼습니다.", transient=True, maybe_billed=True) from None
        except ValueError:
            raise ModelError("응답 본문이 JSON이 아닙니다.", transient=True, maybe_billed=True) from None
        except urllib.error.HTTPError as error:
            code, message = None, None
            try:
                detail = json.loads(error.read()).get("error") or {}
                code, message = detail.get("code"), detail.get("message")
            except Exception:  # 본문 형식이 달라도 오류 분류만 합니다
                pass
            safe_code = code if isinstance(code, str) and re.fullmatch(r"[A-Za-z0-9_\-]{1,80}", code) else None
            # 요청 거부 사유(스키마 오류 등)만 짧게 남깁니다. 키는 헤더에만 있지만 키처럼 보이는 문자열은 가립니다.
            safe_message = ""
            if error.code == 400 and isinstance(message, str):
                safe_message = ": " + re.sub(r"sk-[A-Za-z0-9_\-]+", "<숨김>", message.replace(self._api_key, "<숨김>"))[:500]
            raise ModelError(f"HTTP {error.code} ({safe_code}){safe_message}", transient=error.code in TRANSIENT_STATUS) from None
        except TimeoutError:  # 응답을 기다리다 시간 초과: 제공자는 이미 일을 했을 수 있습니다
            raise ModelError("연결 실패: TimeoutError", transient=True, maybe_billed=True) from None
        except urllib.error.URLError as error:
            billed = isinstance(error.reason, TimeoutError)
            raise ModelError(f"연결 실패: {type(error).__name__}", transient=True, maybe_billed=billed) from None
        return self.parse(request, result)

    def parse(self, request: ModelRequest, result: dict[str, Any]) -> ModelResponse:
        """Responses API 응답에서 출력 글자·종료 사유·토큰 사용량을 꺼내 ModelResponse로 돌려줍니다."""
        usage = result.get("usage") or {}
        details = usage.get("input_tokens_details") or {}
        texts, refused = [], False
        for item in result.get("output") or []:
            if item.get("type") != "message":
                continue
            for content in item.get("content") or []:
                if content.get("type") == "output_text":
                    texts.append(content.get("text", ""))
                elif content.get("type") == "refusal":
                    refused = True
        incomplete = (result.get("incomplete_details") or {}).get("reason")
        if refused:
            stop = StopReason.REFUSED
        elif result.get("status") == "incomplete" and incomplete == "max_output_tokens":
            stop = StopReason.MAX_TOKENS
        elif result.get("status") == "completed":
            stop = StopReason.END
        else:
            stop = StopReason.OTHER
        raw_text = "".join(texts)
        if stop is StopReason.END:
            try:  # 엄격 모드가 채운 선택 필드의 null을 지워 원래 AI 출력 형식으로 되돌립니다
                cleaned = drop_null_optionals(json.loads(raw_text), self._optional_names.get(request.call_name, frozenset()))
                if self.shared_schema and request.shared_schema is not None:
                    cleaned = _keep_own(cleaned, request.output_schema)
                raw_text = json.dumps(cleaned, ensure_ascii=False)
            except ValueError:
                pass  # 실행기가 JSON 오류로 처리하고 재요청합니다
        return ModelResponse(
            raw_text=raw_text,
            model_id=result.get("model") or self.model,
            stop_reason=stop,
            input_tokens=usage.get("input_tokens"),
            output_tokens=usage.get("output_tokens"),
            cached_input_tokens=details.get("cached_tokens"),
            cache_write_tokens=details.get("cache_write_tokens"),
            usage=usage,
        )
