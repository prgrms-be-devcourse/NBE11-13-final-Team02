from pathlib import Path

from mujuthaek_collector.core.documents.tables import read_tables, units_from_tables
from mujuthaek_collector.core.files import FileRef, pick_notice_files, pick_unit_lists, unclear_notice


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


def test_notice_name_variants_and_summary_files():
    files = refs("신청자격 요약.pdf", "FAQ.pdf", "2026 행복주택 입주자모집.pdf")
    assert pick_notice_files(files)[0].name == "2026 행복주택 입주자모집.pdf" and not unclear_notice(files)


def test_unclear_notice_when_no_name_matches():
    assert unclear_notice(refs("붙임1 신청자격.pdf", "2026 청년주택.pdf"))
    assert not unclear_notice(refs("2026 청년주택.pdf", "2026 청년주택.hwp"))  # 같은 문서를 형식만 달리 올린 것


def test_old_excel_unit_list_is_picked_over_pdf():
    files = refs("모집공고문.pdf", "공급주택목록.pdf", "공급주택목록.xls")
    assert [f.name for f in pick_unit_lists(files)] == ["공급주택목록.xls"]


def test_read_old_excel_unit_list():
    data = (Path(__file__).parent / "fixtures" / "files" / "unit-list.xls").read_bytes()
    units = units_from_tables(read_tables(data, "xls"))
    assert [(u["type"], u["area_m2"], u["deposit_won"], u["rent_won"]) for u in units] == [
        ("26A", 26.37, 12_000_000, 150_000),
        ("26A", 26.37, 13_000_000, 140_000),
    ]
