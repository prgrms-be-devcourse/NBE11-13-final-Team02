"""여덟 번째 시험 채점(2026-10-24)의 일반 규칙: 잘린 응답들의 항목 모으기, 접수 방식 막는 말의 범위, 인용 번호표, 보류 범위,
주택형 이름 맞추기. 합성 자료만 씁니다. AI·네트워크 호출 없음."""

import json

from mujuthaek_extraction.extract import fill_from_cuts

# ---- 2. 잘린 응답들의 항목 모으기 ----


def doc(i, name):
    return {"id": f"d{i}", "name": name, "submissionTime": "계약 시", "quoteRefs": [f"q{i}"]}


def test_items_finished_in_an_earlier_cut_answer_are_added():
    quotes = [{"ref": f"q{i}", "documentId": "t", "text": t} for i, t in ((1, "가상 신분증"), (2, "가상 등본"), (3, "가상 초본"))]
    full = json.dumps({"quotes": quotes, "requiredDocuments": [doc(1, "가상 신분증"), doc(2, "가상 등본"), doc(3, "가상 초본")]}, ensure_ascii=False)
    first_cut = full[: full.index('"가상 초본", "submissionTime"')]  # 세 번째 서류 중간에서 잘림
    part = {"quotes": [{"ref": "q1", "documentId": "t", "text": "가상 신분증"}], "requiredDocuments": [doc(1, "가상 신분증")]}
    added = fill_from_cuts(part, [first_cut], ("requiredDocuments",))
    names = [d["name"] for d in part["requiredDocuments"]]
    assert names == ["가상 신분증", "가상 등본"] and added == 1
    new = part["requiredDocuments"][1]
    assert new["id"] == "d2~c1" or new["id"] == "d2"
    assert any(q["ref"] == "c1-q2" for q in part["quotes"]) and new["quoteRefs"] == ["c1-q2"]
