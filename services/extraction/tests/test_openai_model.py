"""OpenAI Responses 연결부. 가짜 HTTP 응답만 쓰며 네트워크·실제 키를 사용하지 않습니다."""

import io
import json
import urllib.error

import pytest
from helpers import ai_part, example, source_for
from jsonschema import Draft202012Validator

from mujuthaek_extraction.model import ModelError, StopReason
from mujuthaek_extraction.model.openai_responses import OpenAIResponsesModel, read_api_key
from mujuthaek_extraction.model.pricing import Budget, BudgetedModel, cost_usd
from mujuthaek_extraction.model.strict_schema import DROPPED_KEYWORDS, drop_null_optionals, to_strict
from mujuthaek_extraction.rules.base import call_schema
from mujuthaek_extraction.rules.housing_notice import RULES

FAKE_KEY = "test-key-not-real"


class FakeHTTP:
    def __init__(self, payload=None, error=None):
        self.payload, self.error, self.requests = payload, error, []

    def __call__(self, request, timeout):
        self.requests.append(request)
        if self.error:
            raise self.error
        return io.BytesIO(json.dumps(self.payload).encode())


def walk(node):
    if isinstance(node, dict):
        yield node
        for key, value in node.items():
            if key in ("properties", "$defs"):
                for child in value.values():
                    yield from walk(child)
            else:
                yield from walk(value)
    elif isinstance(node, list):
        for value in node:
            yield from walk(value)


@pytest.mark.parametrize("call", RULES.calls, ids=lambda c: c.name)
def test_strict_schema_is_all_required_and_keeps_field_names(call):
    strict, optional = to_strict(call_schema(call.sections))
    Draft202012Validator.check_schema(strict)
    for node in walk(strict):
        assert not DROPPED_KEYWORDS.intersection(node)
        assert "oneOf" not in node and "allOf" not in node and "const" not in node
        if "properties" in node:
            assert set(node["required"]) == set(node["properties"]) and node["additionalProperties"] is False
    assert "unitRow" in optional
    text = json.dumps(strict)
    assert "$ref" not in text and "$defs" not in text  # 엄격 모드가 거부한 정의 참조를 모두 펼쳤는지
    assert '"description"' in json.dumps(strict, ensure_ascii=False)  # 필드 이름 description은 남아야 합니다


def test_strict_round_trip_accepts_example_after_null_fill_and_cleanup():
    sections = RULES.calls[0].sections
    part = ai_part(example("housing"), sections)
    strict, optional = to_strict(call_schema(sections))
    # strict 모드는 선택 칸도 모두 요구합니다: 모델은 선택 칸을 null로 보내고, drop_null_optionals가 다시 뺍니다
    filled = {
        **part,
        "quotes": [{**q, "unitRow": None} for q in part["quotes"]],
        "notice": {**part["notice"], "counts": [{**c, "allocationPoolId": None} for c in part["notice"]["counts"]]},
        "supplyGroups": [
            {
                **g,
                "exclusiveAreaRange": None,
                "counts": [{**c, "allocationPoolId": None} for c in g["counts"]],
                "prices": [{**p, "priceStage": None} for p in g["prices"]],
            }
            for g in part["supplyGroups"]
        ],
    }
    assert list(Draft202012Validator(strict).iter_errors(filled)) == []
    cleaned = drop_null_optionals(filled, optional)
    assert list(Draft202012Validator(call_schema(sections)).iter_errors(cleaned)) == []


def response_payload(text, status="completed", reason=None, refusal=False, usage=None):
    content = [{"type": "refusal", "refusal": "no"}] if refusal else [{"type": "output_text", "text": text}]
    return {
        "model": "gpt-6-luna",
        "status": status,
        "incomplete_details": {"reason": reason} if reason else None,
        "output": [{"type": "reasoning"}, {"type": "message", "content": content}],
        "usage": usage or {"input_tokens": 1000, "output_tokens": 200, "input_tokens_details": {"cached_tokens": 600, "cache_write_tokens": 0}},
    }


def request_for(call_index=0):
    material = source_for("housing", example("housing")).material
    return RULES.build_request(RULES.calls[call_index], material)


def test_request_body_order_and_no_secret_in_repr():
    http = FakeHTTP(response_payload('{"quotes": []}'))
    model = OpenAIResponsesModel(FAKE_KEY, opener=http)
    model.complete(request_for())
    body = json.loads(http.requests[0].data)
    texts = [c["text"] for c in body["input"][0]["content"]]
    assert texts[0].startswith("당신은") and "<<<원문 시작>>>" in texts[1] and texts[2].startswith("# 이번 호출에서 할 일")
    assert body["store"] is False and body["text"]["format"]["strict"] is True
    assert FAKE_KEY not in repr(model) and FAKE_KEY not in json.dumps(body)
    assert http.requests[0].get_header("Authorization") == "Bearer " + FAKE_KEY


def test_parse_completed_removes_null_optionals_and_reads_usage():
    payload = response_payload(json.dumps({"quotes": [{"ref": "q1", "documentId": "text1", "text": "가나다라마", "unitRow": None}]}))
    response = OpenAIResponsesModel(FAKE_KEY, opener=FakeHTTP(payload)).complete(request_for())
    assert response.stop_reason is StopReason.END
    assert json.loads(response.raw_text)["quotes"][0] == {"ref": "q1", "documentId": "text1", "text": "가나다라마"}
    assert (response.input_tokens, response.cached_input_tokens, response.output_tokens) == (1000, 600, 200)


@pytest.mark.parametrize(
    ("payload", "stop"),
    [
        (response_payload("{", status="incomplete", reason="max_output_tokens"), StopReason.MAX_TOKENS),
        (response_payload("", refusal=True), StopReason.REFUSED),
        (response_payload("", status="failed"), StopReason.OTHER),
    ],
)
def test_parse_stop_reasons(payload, stop):
    assert OpenAIResponsesModel(FAKE_KEY, opener=FakeHTTP(payload)).complete(request_for()).stop_reason is stop


@pytest.mark.parametrize(("status", "transient"), [(429, True), (503, True), (400, False), (401, False)])
def test_http_errors_are_classified_without_body(status, transient):
    body = io.BytesIO(json.dumps({"error": {"code": "rate_limit_exceeded", "message": "echo " + FAKE_KEY}}).encode())
    error = urllib.error.HTTPError("https://api.openai.com/v1/responses", status, "x", {}, body)
    with pytest.raises(ModelError) as caught:
        OpenAIResponsesModel(FAKE_KEY, opener=FakeHTTP(error=error)).complete(request_for())
    assert caught.value.transient is transient and FAKE_KEY not in str(caught.value)


def test_network_error_is_transient():
    with pytest.raises(ModelError) as caught:
        OpenAIResponsesModel(FAKE_KEY, opener=FakeHTTP(error=urllib.error.URLError("down"))).complete(request_for())
    assert caught.value.transient


def test_read_api_key_from_env_file(tmp_path):
    env = tmp_path / ".env"
    env.write_text('OTHER=1\nOPENAI_API_KEY="abc"\n', encoding="utf-8")
    assert read_api_key(env) == "abc"
    env.write_text("OTHER=1\n", encoding="utf-8")
    assert read_api_key(env) is None


def test_cost_and_budget_guard():
    usage = {"input_tokens": 1_000_000, "output_tokens": 1_000_000, "input_tokens_details": {"cached_tokens": 500_000, "cache_write_tokens": 100_000}}
    payload = response_payload("{}", usage=usage)
    response = OpenAIResponsesModel(FAKE_KEY, opener=FakeHTTP(payload)).complete(request_for())
    expected = (400_000 * 0.10 + 500_000 * 0.01 + 100_000 * 0.125 + 1_000_000 * 0.50) / 1_000_000
    assert cost_usd("gpt-6-luna", response) == pytest.approx(expected)

    budget = Budget("gpt-6-luna", limit_usd=0.05)
    request = request_for()
    assert budget.allow(request)
    BudgetedModel(OpenAIResponsesModel(FAKE_KEY, opener=FakeHTTP(payload)), budget).complete(request)
    assert budget.spent_usd > 0.05 and not budget.allow(request)


def test_shared_schema_keeps_one_format_with_cache_breakpoint_and_light_effort():
    # 실측(OpenAI): 호출마다 출력 형식이 달라 원문 캐시 읽기가 9회 모두 0건(쓰기 요금만 냄). 같은 형식 + 원문 끝 캐시 지점
    http = FakeHTTP(response_payload(json.dumps({"quotes": [], "schedules": None, "notice": {"x": 1}, "coverage": {"notice": None, "schedules": None}})))
    model = OpenAIResponsesModel(FAKE_KEY, opener=http, shared_schema=True, light_calls=frozenset({"structure"}), light_effort="low")
    first, second = request_for(0), request_for(1)
    model.complete(first)
    response = model.complete(second)
    bodies = [json.loads(r.data) for r in http.requests]
    assert bodies[0]["text"]["format"] == bodies[1]["text"]["format"] and bodies[0]["text"]["format"]["name"] == "extraction_shared"
    assert all(b["prompt_cache_options"] == {"mode": "explicit"} for b in bodies)
    assert bodies[0]["prompt_cache_key"] == bodies[1]["prompt_cache_key"] and bodies[0]["prompt_cache_key"].startswith("notice-")
    assert all(b["input"][0]["content"][1]["prompt_cache_breakpoint"] == {"mode": "explicit"} for b in bodies)
    assert "prompt_cache_breakpoint" not in bodies[0]["input"][0]["content"][2]  # 호출별 지시는 캐시 뒤
    assert [b["reasoning"]["effort"] for b in bodies] == ["low" if first.call_name == "structure" else "medium", "medium"]
    kept = json.loads(response.raw_text)  # 맡지 않은 섹션(notice)과 그 coverage는 걸러 냄
    assert set(kept) <= set(second.output_schema["properties"]) and set(kept["coverage"]) <= set(second.output_schema["properties"]["coverage"]["properties"])


def test_shared_schema_off_keeps_per_call_format():
    http = FakeHTTP(response_payload('{"quotes": []}'))
    OpenAIResponsesModel(FAKE_KEY, opener=http).complete(request_for())
    body = json.loads(http.requests[0].data)
    assert body["text"]["format"]["name"] != "extraction_shared" and "prompt_cache_options" not in body


def test_shared_schema_null_section_with_coverage_becomes_empty_list():
    # 실측(OpenAI LH 제주 든든전세): 맡은 supplyGroups를 null로 두고 coverage에 PARTIAL 사유만 적어 구조 호출을 다시 요청함
    from mujuthaek_extraction.model.openai_responses import _keep_own

    schema = {
        "required": ["quotes", "supplyGroups", "coverage"],
        "properties": {"quotes": {"type": "array"}, "supplyGroups": {"type": "array"}, "coverage": {"properties": {"supplyGroups": {}}}},
    }
    kept = _keep_own({"quotes": [], "coverage": {"supplyGroups": {"status": "PARTIAL", "reason": "첨부 없음"}}}, schema)
    assert kept["supplyGroups"] == []
    assert "supplyGroups" not in _keep_own({"quotes": [], "coverage": {}}, schema)  # 사유도 없으면 그대로(다시 요청)
