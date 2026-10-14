from mujuthaek_collector.core.files import FileRef, pick_notice_files, pick_unit_lists


def refs(*names):
    return [FileRef(name, f"https://a.kr/f/{i}", i) for i, name in enumerate(names)]


def test_notice_doc_prefers_named_pdf_and_skips_personal_files():
    files = refs("신청서 양식.hwp", "입주자 모집공고문.hwp", "입주자 모집공고문.pdf", "당첨자 명단.pdf", "위치도.pdf")
    picked = pick_notice_files(files)
    assert [f.name for f in picked] == ["입주자 모집공고문.pdf", "입주자 모집공고문.hwp"]


def test_hwp_first_when_preferred():
    assert pick_notice_files(refs("모집공고문.pdf", "모집공고문.hwp"), prefer="hwp")[0].name == "모집공고문.hwp"


def test_unit_list_takes_excel_once():
    files = refs("공급주택목록.pdf", "공급주택목록.xlsx", "예비자 명단.xlsx", "모집공고문.pdf")
    assert [f.name for f in pick_unit_lists(files)] == ["공급주택목록.xlsx"]


def test_draw_result_files_are_personal():
    """대전 결과 공고의 '추첨결과_….xlsx'에는 신청자 정보가 있을 수 있어서 받지 않아요."""
    files = refs("2026년 상반기 청년매입임대주택 입주대기자 선정 결과 발표.pdf", "추첨결과_2026-09-03-10-00-23.xlsx")
    assert files[1].personal and pick_unit_lists(files) == [] and pick_notice_files(files) == []
