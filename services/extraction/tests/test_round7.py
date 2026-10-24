"""일곱 번째 시험 채점(2026-10-24)의 일반 규칙: 잘린 표 읽기 답 살리기와 표 수에 맞춘 한도, 인용의 표 표시 걷어내기,
재확인 항목 단위 채택, 접수 방식 낱말, 공고 전체 접수 방식, 공급 섹션 일부 보류. 합성 자료만 씁니다. AI·네트워크 호출 없음."""

from mujuthaek_extraction import supply_tables as st

# ---- 1. 잘린 표 읽기 답 ----


def test_cut_mapping_keeps_only_finished_table_answers():
    raw = '{"tables": [{"table": "1-1", "role": "SUPPLY", "columns": []}, {"table": "1-2", "role": "PRICE", "columns": [{"column": 1, "meaning": "HOUS'
    assert [e["table"] for e in st.salvage_mapping(raw)] == ["1-1"]
    assert st.salvage_mapping('{"tables": [') == []
    assert st.salvage_mapping("잘못된 답") == []


def test_output_limit_grows_with_the_number_of_tables():
    assert st.scaled_limit(2) == st.MAX_OUTPUT_TOKENS
    assert st.scaled_limit(10) == 7_000
    assert st.scaled_limit(100) == st.MAX_OUTPUT_TOKENS_CAP


def test_cut_mapping_answer_asks_again_only_for_the_tables_left():
    import json
    from dataclasses import replace

    from test_supply_tables import MAPPING, material, structure_answer

    from mujuthaek_extraction.extract import run_extraction
    from mujuthaek_extraction.model import ModelResponse, RecordedModel, StopReason
    from mujuthaek_extraction.rules.housing_notice import RULES

    source = material()
    other = {"table": "9-9", "role": "SUPPLY", "rowUnit": "HOUSING_TYPE", "complex": None, "columns": []}
    cut = ModelResponse(raw_text='{"tables": [' + json.dumps(other) + ', {"table": "1-1", "colu', model_id="m", stop_reason=StopReason.MAX_TOKENS)
    model = RecordedModel({"supply_tables": [cut, json.dumps(MAPPING)], "structure": [structure_answer(source)]})
    rules = replace(RULES, calls=RULES.calls[:1], split_calls=(), large_text_chars=None)
    run = run_extraction(rules, source, model)
    asked = [r for r in model.requests if r.call_name == "supply_tables"]
    assert len(asked) == 2 and "9-9" not in asked[1].instructions and "1-1" in asked[1].instructions
    assert [sorted(p["coverage"]) for p in run.parts] == [["locations", "notice"], ["supplyGroups"]]
