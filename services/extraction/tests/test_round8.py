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


# ---- 1. 접수 방식 막는 말의 범위 ----


def test_blocking_a_sub_method_does_not_block_the_whole_channel():
    from mujuthaek_extraction.assemble.details import channel_mentions

    assert "ONLINE" not in channel_mentions("인터넷 청약 (※모바일청약 불가)")[1]
    assert "ONSITE" not in channel_mentions("특별공급은 은행창구 접수 불가")[1]
    assert "ONLINE" in channel_mentions("인터넷 신청 불가")[1]


def test_channel_named_only_in_a_condition_is_kept():
    from test_round4 import assembled_extra
    from test_schedule_details import visit_schedule

    line = "2순위 접수: 인터넷 청약이 불가한 경우 가상 은행에서 접수 2026.10.05."
    schedule = visit_schedule(
        id="second",
        kinds=["APPLICATION"],
        rawLabel="2순위 접수",
        channels=["ONLINE"],
        places=[],
        dailyHours=[],
        breaks=[],
        excludedDays=[],
        timeNote=None,
        quoteRefs=["second"],
    )
    result = assembled_extra([line], {"second": line}, schedules=[schedule])
    [kept] = [s for s in result.candidate["schedules"] if s["id"] == "second"]
    assert kept["channels"] == ["ONLINE"]


# ---- 3. 인용 번호표 ----


def test_quote_with_a_different_numbering_mark_is_found():
    from mujuthaek_extraction.assemble.quotes import NormalizedText, candidates

    text = "제출서류\n1) 가상 입주신청서 및 개인정보 동의서 각 1부\n2) 가상 자기소개서 및 활동 계획서 각 1부\n"
    index = NormalizedText(text)
    found, _ = candidates(text, "1. 가상 입주신청서 및 개인정보 동의서 각 1부", index)
    assert len(found) == 1 and text[found[0][0] : found[0][1]].startswith("가상 입주신청서")
    assert candidates(text, "① 가상 자기소개서 및 활동 계획서 각 1부", index)[0]
