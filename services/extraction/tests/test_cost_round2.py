"""비용 절감 2차(2026-10-25): 큰 서류 표는 처음부터 나눠 부르기(설정 EXTRACTION_PRESPLIT_DOC_ROWS=0이면 이전 동작)."""

import json
from types import SimpleNamespace

from mujuthaek_extraction import extract
from mujuthaek_extraction.extract import document_rows


def test_document_rows_counts_document_table_rows():
    rows = "\n".join(f"| 가상 증명서 {i} | 1부 |" for i in range(70))
    text = "■ 제출서류\n| 서류 | 부수 |\n" + rows
    assert document_rows(SimpleNamespace(text=text)) == 70
    assert document_rows(SimpleNamespace(text="서류 없음")) == 0


def test_large_document_table_is_split_from_the_start(monkeypatch):
    from helpers import ai_part, example, source_for
    from test_round3 import split_script

    from mujuthaek_extraction.model import RecordedModel
    from mujuthaek_extraction.rules.housing_notice import RULES

    data = example("housing")
    source = source_for("housing", data)
    monkeypatch.setattr(extract, "PRESPLIT_ROWS", 1)
    monkeypatch.setattr(extract, "document_rows", lambda material: 5)
    from test_round3 import documents_part

    good = json.dumps(ai_part(data, ("requiredDocuments",)), ensure_ascii=False)
    script = split_script(data, [good])
    script["documents-submit"] = [json.dumps(documents_part(data, ["submit-a"]), ensure_ascii=False)]
    script["documents-visit"] = [json.dumps(documents_part(data, ["visit-a"]), ensure_ascii=False)]
    model = RecordedModel(script)
    result = extract.run_extraction(RULES, source.material, model)
    names = [r.call_name for r in model.requests]
    assert "documents" not in names and "documents-submit" in names and "documents-visit" in names
    assert any("처음부터 표 묶음별로" in n for n in result.notes)


def test_presplit_off_keeps_the_single_documents_call(monkeypatch):
    from helpers import ai_part, example, source_for
    from test_round3 import split_script

    from mujuthaek_extraction.model import RecordedModel
    from mujuthaek_extraction.rules.housing_notice import RULES

    data = example("housing")
    source = source_for("housing", data)
    monkeypatch.setattr(extract, "PRESPLIT_ROWS", 0)
    good = json.dumps(ai_part(data, ("requiredDocuments",)), ensure_ascii=False)
    model = RecordedModel(split_script(data, [good]))
    extract.run_extraction(RULES, source.material, model)
    assert "documents" in [r.call_name for r in model.requests]


def test_cut_answers_drop_strict_mode_nulls_before_salvage():
    """2026-10-25 33번: 잘린 응답은 어댑터가 엄격 모드 null 칸을 지우지 못해, 살린 인용의 「unitRow」: null이 형식 오류가 되어
    서류 전체를 버렸습니다. 살릴 때 선택 칸 null을 지웁니다."""
    import json

    from mujuthaek_extraction.assemble import ai_output
    from mujuthaek_extraction.extract import fill_from_cuts, salvage_truncated, schema_errors
    from mujuthaek_extraction.model.strict_schema import to_strict

    optional = to_strict(ai_output.schema())[1]
    assert "unitRow" in optional
    quotes = [{"ref": f"q{i}", "documentId": "t", "text": t, "unitRow": None} for i, t in ((1, "가상 신분증"), (2, "가상 등본"), (3, "가상 초본"))]
    docs = [
        {"id": f"d{i}", "name": n, "submissionTime": "계약 시", "quoteRefs": [f"q{i}"]} for i, n in ((1, "가상 신분증"), (2, "가상 등본"), (3, "가상 초본"))
    ]
    full = json.dumps({"quotes": quotes, "requiredDocuments": docs}, ensure_ascii=False)
    cut = full[: full.index('"가상 초본", "submissionTime"')]
    salvaged = salvage_truncated(cut, ("requiredDocuments",), optional)
    assert all("unitRow" not in q for q in salvaged["quotes"])
    assert len(salvaged["requiredDocuments"]) == 2
    assert "unitRow" not in (schema_errors(salvaged, ai_output.schema()) or "")
    part = {"quotes": [], "requiredDocuments": []}
    assert fill_from_cuts(part, [cut], ("requiredDocuments",), optional) == 2
    assert all("unitRow" not in q for q in part["quotes"])
