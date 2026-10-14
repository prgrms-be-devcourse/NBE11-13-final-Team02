from datetime import date

from mujuthaek_collector.core.documents.tables import map_header, units_from_tables
from mujuthaek_collector.core.html import body_text, js_args, parse_date, soup, table_grid


def test_table_grid_expands_merged_cells():
    table = soup("<table><tr><th rowspan=2>주택형</th><th colspan=2>임대조건</th></tr><tr><th>보증금</th><th>월임대료</th></tr></table>").table
    assert table_grid(table) == [["주택형", "임대조건", ""], ["", "보증금", "월임대료"]]


def test_body_text_keeps_table_rows_and_joins_split_spans():
    html = "<div><p><span>입주</span><span>자격</span></p><table><tr><td>보증금</td><td>1,000</td></tr></table></div>"
    text = body_text(soup(html).div)
    assert "입주자격" in text
    assert "보증금 | 1,000" in text


def test_js_args_and_dates():
    assert js_args("HHBbs.DownFile('/umca', 'BBS_4', 'FILE_1', '0')", "HHBbs.DownFile") == ["/umca", "BBS_4", "FILE_1", "0"]
    assert parse_date("2026.10.08") == date(2026, 10, 8)
    assert parse_date("26. 10. 8.") == date(2026, 10, 8)
    assert parse_date("2026년 10월 8일") == date(2026, 10, 8)


def test_map_header_prefers_exclusive_area():
    mapping = map_header(["주택형", "공급면적", "전용면적", "보증금", "월임대료"])
    assert mapping[2] == "area" and 1 not in mapping


def test_units_from_two_row_header_with_thousand_won():
    rows = [
        ["주택형", "전용면적", "임대조건", ""],
        ["", "", "보증금", "월임대료"],
        ["26A", "26.37", "12,000", "150"],
        ["", "", "13,000", "140"],
        ["합계", "", "", ""],
    ]
    units = units_from_tables([(1, "단위: 천원", rows)])
    assert [(u["type"], u["area_m2"], u["deposit_won"], u["rent_won"]) for u in units] == [
        ("26A", 26.37, 12_000_000, 150_000),
        ("26A", 26.37, 13_000_000, 140_000),  # 세로로 합쳐진 주택형·면적은 위 행 값을 이어받아요
    ]
