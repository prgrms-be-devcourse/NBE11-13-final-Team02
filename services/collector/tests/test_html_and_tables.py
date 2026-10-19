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


def test_table_continues_only_on_next_page_with_sane_values():
    """대전 신혼·신생아 공고: 1쪽 주택 표와 칸 수만 같은 15쪽 소득 종류 표를 주택으로 읽던 문제 (43-24788)."""
    header = ["주택명", "소재지", "공급호수", "보증금(원)", "월임대료(원)"]
    tables = [
        (1, "", [header, ["새미래숲", "지족로190번길 16", "1호", "49,273,000", "347,500"]]),
        (2, "", [["햇살채", "유성대로 10", "2호", "51,000,000", "350,000"]]),  # 바로 다음 쪽 → 이어지는 표
        (3, "", [["근로 소득", "", "상시 근로 소득", "상시고용되어 월정액 급여를 지급받는 자", ""]]),  # 금액이 말이 안 됨 → 멈춤
        (4, "", [["별빛채", "대덕대로 5", "1호", "48,000,000", "340,000"]]),  # 이미 멈췄으니 이어 붙이지 않음
    ]
    assert [u["complex"] for u in units_from_tables(tables)] == ["새미래숲", "햇살채"]
    far = [tables[0], (15, "", tables[1][2])]  # 몇 쪽 떨어진 표는 앞 표에 이어 붙이지 않음
    assert [u["complex"] for u in units_from_tables(far)] == ["새미래숲"]


def test_target_fills_down_and_keeps_merged_sub_column():
    """다가온 청년주택: '공급 대상' 머리글이 옆 칸(소득없음·소득있음)까지 덮고, 대상 칸이 세로로 합쳐져 있어요."""
    rows = [
        ["공급유형", "공급대상", "", "임대보증금(천원)", "월임대료(원)"],
        ["21", "대학생", "", "20,740", "96,780"],
        ["", "청년", "소득없음", "20,740", "96,780"],
        ["", "", "소득있음", "21,960", "102,480"],
    ]
    units = units_from_tables([(4, "", rows)])
    assert [(u["type"], u["target"], u["deposit_won"]) for u in units] == [
        ("21", "대학생", 20_740_000),
        ("21", "청년 소득없음", 20_740_000),
        ("21", "청년 소득있음", 21_960_000),
    ]


def test_money_per_applicant_group_becomes_one_row_each():
    """대전 영구임대: 가군(수급자 등)·나군(일반 등)마다 보증금·월임대료가 따로 있어요 (43-24786)."""
    rows = [
        ["단지", "전용면적(㎡)", "금회모집 예비자수", "『가』군: 수급자 등", "", "『나』군: 일반 등", ""],
        ["", "", "", "임대보증금(원)", "월임대료(원)", "임대보증금(원)", "월임대료(원)"],
        ["보라@", "28", "90", "1,939,000", "48,510", "4,084,000", "69,520"],
    ]
    units = units_from_tables([(2, "", rows)])
    assert [(u["complex"], u["target"], u["count"], u["deposit_won"], u["rent_won"]) for u in units] == [
        ("보라@", "수급자 등", 90, 1_939_000, 48_510),
        ("보라@", "일반 등", 90, 4_084_000, 69_520),
    ]


def test_reduced_rent_columns_are_not_groups():
    """보증금 없이 월임대료만 여러 칸(감면 세대 등)이면 나누지 않아요."""
    rows = [
        ["공급형별", "임대조건(원)", "", "감면세대 월임대료(원)", ""],
        ["", "임대보증금", "월임대료", "자녀 1인", "자녀 2인"],
        ["36", "35,200,000", "161,330", "80,670", "48,400"],
    ]
    units = units_from_tables([(7, "", rows)])
    assert [(u["type"], u["deposit_won"], u["rent_won"], u.get("target")) for u in units] == [("36", 35_200_000, 161_330, None)]
