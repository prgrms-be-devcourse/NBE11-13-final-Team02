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


# ---- 2. 표 행을 이어 쓰며 칸 일부를 빼먹은 인용 ----


def test_joined_table_rows_quote_is_found_cell_by_cell():
    from mujuthaek_extraction.assemble.quotes import NormalizedText, split_joined_quote

    text = "\n".join(
        [
            "[표 25-1]",
            "| 제출서류 | 비 고 | 부수 |",
            "| 가상 개인정보 동의서 [붙임1] | 대상자 : 가상 세대 전원 * 가상급여수급자는 가상 금융 동의서 작성 제외 동의방법 : 정자 서명 | 1통 |",
            "| 가상 금융정보 제공 동의서 [붙임2] |  | 1통 |",
        ]
    )
    quote = (
        "[표 25-1] 가상 개인정보 동의서 [붙임1] | 대상자 : 가상 세대 전원 * 가상급여수급자는 가상 금융 동의서 작성 제외 | 1통 | "
        "가상 금융정보 제공 동의서 [붙임2] |  | 1통"
    )
    assert split_joined_quote(text, quote, NormalizedText(text)) == [
        "가상 개인정보 동의서 [붙임1]",
        "대상자 : 가상 세대 전원 * 가상급여수급자는 가상 금융 동의서 작성 제외",
        "가상 금융정보 제공 동의서 [붙임2]",
    ]
    # 칸 안에서 낱말을 빼먹은 조각이 있으면 나누지 않습니다
    broken = "가상 개인정보 동의서 [붙임1] | 대상자 : 가상 세대 * 가상급여수급자는 작성 제외 | 1통"
    assert split_joined_quote(text, broken, NormalizedText(text)) is None
