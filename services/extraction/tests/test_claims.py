"""V9 값–인용 대조: 인용문은 진짜인데 값을 잘못 옮긴 결과가 자동 완료되지 않아야 합니다(검토 2026-10-16)."""

import copy

from test_verify import assembled, clock

from mujuthaek_extraction.verify import verify_extraction
from mujuthaek_extraction.verify.claims import _has_date, _has_number, check_claims


def age_atoms(candidate):
    stack = [candidate["eligibility"]]
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            if node.get("kind") == "ATOMIC" and node.get("attribute") == "AGE_YEARS":
                yield node
            stack.extend(node.values())
        elif isinstance(node, list):
            stack.extend(node)


def test_wrong_age_with_real_quote_is_not_auto_passed():
    candidate, sources = assembled()
    assert verify_extraction(candidate, sources, None, clock=clock).passed
    wrong = copy.deepcopy(candidate)
    next(a for a in age_atoms(wrong) if a["value"] == 39)["value"] = 45  # 원문은 「39세 이하」
    report = verify_extraction(wrong, sources, None, clock=clock)
    assert not report.passed
    assert any(i["code"] == "VALUE_NOT_IN_QUOTE" for i in report.candidate["quality"]["reviewIssues"])


def test_clean_examples_have_no_findings():
    for name in ("housing", "business"):
        candidate, sources = assembled(name)
        assert check_claims(candidate, sources) == []


def test_number_forms_written_differently_still_match():
    assert _has_number(["총자산가액 합산기준 ( 337 )백만원 이하"], 337_000_000, "KRW")
    assert _has_number(["신혼부부, 고령자, 일반 1인 가구 | 3억3,700만 원 이하"], 337_000_000, "KRW")
    assert _has_number(["자동차가액 4,542만 원 이하"], 45_420_000, "KRW")
    assert _has_number(["총 자산기준 2억4천5백만원 이하"], 245_000_000, "KRW")
    assert _has_number(["총자산 34,500만원 이하"], 345_000_000, "KRW")
    assert _has_number(["보증금 1 ,000,000원"], 1_000_000)
    assert _has_number(["혼인 기간 2년 이내"], 24, "MONTH")
    assert _has_number(["두 자녀 이상"], 2, "COUNT")
    assert _has_number(["자동차를 소유하고 있지 않아야 합니다"], 0, "KRW")
    assert _has_number(["월평균소득 50%(1인 70%, 2인 60%) 이하"], 3, "COUNT", at_least=True)
    assert not _has_number(["만 19세 이상 39세 이하"], 45, "YEAR")


def test_date_forms_written_differently_still_match():
    def day(value):
        return {"precision": "DAY", "value": value}

    assert _has_date(["2026. 8. 10.(월) ~ 14.(금)"], day("2026-08-10"))
    assert _has_date(["2026. 8. 10.(월) ~ 14.(금)"], day("2026-08-14"), end=True)
    assert _has_date(["2026년 10월 1일 10시부터 31일 17시까지"], day("2026-10-31"), end=True)
    assert _has_date(["’26. 08. 중"], {"precision": "MONTH", "value": "2026-08"})
    assert not _has_date(["2026. 8. 10.(월) ~ 14.(금)"], day("2026-08-21"))


def test_dropped_eligibility_condition_is_not_auto_passed():
    # 검토 지적: 소득 70% 조건과 무주택 조건을 지우고 나이만 남겨도 자동 완료되던 것 (V8 자격 기준 누락)
    candidate, sources = assembled()
    kept = copy.deepcopy(candidate)
    kept["eligibility"] = [e for e in kept["eligibility"] if any(True for _ in age_atoms({"eligibility": [e]}))]
    assert kept["eligibility"] and len(kept["eligibility"]) < len(candidate["eligibility"])
    report = verify_extraction(kept, sources, None, clock=clock)
    assert not report.passed
    assert any(i["code"] == "ELIGIBILITY_OMISSION_SUSPECTED" for i in report.candidate["quality"]["reviewIssues"])


def test_two_character_quote_does_not_support_an_eligibility_condition():
    # 검토 지적: 너무 짧은 인용문도 확인된 근거로 쳐서 「39」 한 줄 근거의 자격 조건이 자동 완료되던 것
    candidate, sources = assembled()
    short = copy.deepcopy(candidate)
    text = next(iter(sources.texts.values()))
    start = text.index("39")
    short["evidence"].append(
        {"id": "ev-short", "sourceId": next(iter(sources.texts)), "locator": {"kind": "TEXT_RANGE", "start": start, "end": start + 2}, "quote": "39"}
    )
    item = next(e for e in short["eligibility"] if any(True for _ in age_atoms({"eligibility": [e]})))
    item["evidenceIds"] = ["ev-short"]
    for atom in age_atoms({"eligibility": [item]}):
        atom["evidenceIds"] = ["ev-short"]
    report = verify_extraction(short, sources, None, clock=clock)
    assert not report.passed


def test_locator_pointing_at_wrong_kind_of_document_is_reported_not_crashing():
    candidate, sources = assembled()
    broken = copy.deepcopy(candidate)
    broken["evidence"][0]["locator"] = {"kind": "UNIT_ROW", "pointer": "/0"}
    report = verify_extraction(broken, sources, None, clock=clock)
    assert "EVIDENCE_LOCATOR_KIND_MISMATCH" in {f.code for f in report.findings} and not report.passed


def test_null_count_and_between_ages_do_not_crash_and_are_range_checked():
    from mujuthaek_extraction.verify.consistency import check_consistency

    candidate, sources = assembled()
    odd = copy.deepcopy(candidate)
    odd["supplyGroups"][0]["counts"] = [{**c, "value": None} for c in odd["supplyGroups"][0]["counts"]]
    atom = next(age_atoms(odd))
    atom |= {"operator": "BETWEEN", "value": [19, 390]}
    found = {(f.code, f.target.split("/")[1]) for f in check_consistency(odd, sources)}
    assert ("VALUE_OUT_OF_RANGE", "eligibility") in found


def test_adult_age_19_from_adult_wording_is_not_flagged_but_other_ages_are():
    # 실측: 「성년자」를 프롬프트 규칙대로 AGE_YEARS ≥ 19로 옮긴 값이 매번 값–인용 불일치로 걸려 재확인 호출을 씀
    candidate, _ = assembled()
    atom = next(age_atoms(candidate))
    quote_id = atom.get("evidenceIds", [None])[0] or candidate["eligibility"][0]["evidenceIds"][0]
    evidence = next(e for e in candidate["evidence"] if e["id"] == quote_id)
    evidence["quote"] = "신청자는 공고일 현재 성년자인 무주택세대구성원"
    atom["value"] = 19
    assert not [f for f in check_claims(candidate) if f.code == "VALUE_NOT_IN_QUOTE" and "AGE" in f.description]
    atom["value"] = 20
    assert [f for f in check_claims(candidate) if f.code == "VALUE_NOT_IN_QUOTE" and "AGE" in f.description]
