"""공급·가격 표를 코드가 행 단위로 옮기기(supply_tables, 구조 방식 2026-10-17). AI는 표마다 열 의미만 답합니다."""

import copy
import json
from dataclasses import replace

from helpers import ai_part, example
from test_pdf_reader import attachment, merged_table_pdf
from test_pipeline import raw_event

from mujuthaek_extraction import supply_tables as st
from mujuthaek_extraction.extract import run_extraction
from mujuthaek_extraction.intake import InMemorySourceReader, build_intake
from mujuthaek_extraction.model import RecordedModel
from mujuthaek_extraction.rules.housing_notice import RULES


def column(n, meaning, ck=None, pk=None, unit=None, plan=None, stage=None, group=None):
    return {"column": n, "meaning": meaning, "countKind": ck, "priceKind": pk, "unit": unit, "plan": plan, "priceStage": stage, "targetGroup": group}


def material():
    data = merged_table_pdf()
    event = copy.deepcopy(raw_event())
    event["payload"]["files"] = [attachment("notice.pdf", data)]
    return build_intake(event, InMemorySourceReader(files={"raw/x/notice.pdf": data}), table_labels=True).material


MAPPING = {
    "tables": [
        {
            "table": "1-1",
            "role": "SUPPLY",
            "rowUnit": "HOUSING_TYPE",
            "complex": None,
            "columns": [
                column(1, "HOUSING_TYPE"),
                column(2, "PRICE", pk="DEPOSIT", unit="천원", plan="기본", stage="STANDARD"),
                column(3, "PRICE", pk="MONTHLY_RENT", unit="원", plan="기본", stage="STANDARD"),
                column(4, "COUNT", ck="TOTAL_HOUSING"),
            ],
        }
    ]
}


def test_rows_are_copied_by_code_from_cells_with_merged_labels_and_row_quotes():
    source = material()
    [table] = st.candidates(source)
    assert table.label == "1-1" and table.columns[:2] == ("Type", "Deposit › Total")
    part = st.build_part([table], MAPPING, source.text_document_id)
    groups = part["supplyGroups"]
    assert [g["housingTypeCode"] for g in groups] == ["39A", "46B"]  # 세로 병합 「39A」 두 행은 한 묶음
    assert [(p["kind"], p["sourceAmount"], p["sourceUnit"]) for p in groups[0]["prices"]] == [
        ("DEPOSIT", "9,330", "천원"),
        ("MONTHLY_RENT", "82,630", "원"),
        ("DEPOSIT", "10,100", "천원"),
        ("MONTHLY_RENT", "90,000", "원"),
    ]
    assert [(c["kind"], c["value"]) for c in groups[0]["counts"]] == [("TOTAL_HOUSING", 25)] and groups[1]["counts"][0]["value"] == 7
    assert all(q["text"] in source.text and q["text"].startswith("| ") for q in part["quotes"])  # 인용은 원문 표 행 그대로
    assert part["coverage"] == {"supplyGroups": {"status": "EXTRACTED", "reason": None}}


def test_shared_count_cell_becomes_one_pool_and_conversion_rows_split_by_sign():
    # 실측(인천 행복주택): 대학생·청년이 한 칸의 「금회 모집 20」을 함께 쓰고, 전환안은 한도 칸의 부호로 증액·감액이 나뉨
    rows = (
        ("형", "대상", "금회 모집", "보증금", "월세", "전환 한도", "전환 보증금", "전환 월세"),
        ("18", "대학생", "20", "21,062", "87,600", "(+)5,000", "26,062", "62,600"),
        ("", "", "", "", "", "(-)16,000", "5,062", "134,200"),
        ("", "청년", "", "22,302", "92,800", "(+)6,000", "28,302", "62,800"),
    )
    cells = ((0, 1, 2, 3, 4, 5, 6, 7), (8, 9, 10, 11, 12, 13, 14, 15), (8, 9, 10, 11, 12, 16, 17, 18), (8, 19, 10, 20, 21, 22, 23, 24))
    table = st.Table("3-1", 3, "□ 임대조건", rows[0], rows, cells)
    mapping = {
        "tables": [
            {
                "table": "3-1",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": "화수정원마을 1단지",
                "columns": [
                    column(1, "HOUSING_TYPE"),
                    column(2, "TARGET_GROUP"),
                    column(3, "COUNT", ck="OFFERED"),
                    column(4, "PRICE", pk="DEPOSIT", unit="천원", plan="기본", stage="STANDARD"),
                    column(5, "PRICE", pk="MONTHLY_RENT", unit="원", plan="기본", stage="STANDARD"),
                    column(6, "CONVERSION_LIMIT"),
                    column(7, "PRICE", pk="DEPOSIT", unit="천원", plan="최대 전환", stage="CONVERSION_DEPOSIT_UP"),
                    column(8, "PRICE", pk="MONTHLY_RENT", unit="원", plan="최대 전환", stage="CONVERSION_DEPOSIT_UP"),
                ],
            }
        ]
    }
    part = st.build_part([table], mapping, "pdf-text")
    student, youth = part["supplyGroups"]
    assert student["scope"]["targetGroups"] == ["대학생"] and youth["scope"]["targetGroups"] == ["청년"]
    [a], [b] = student["counts"], youth["counts"]
    assert (a["value"], b["value"]) == (20, 20) and a["allocationPoolId"] == b["allocationPoolId"] is not None  # 한 물량, 합산하지 않음
    stages = [(p["kind"], p["sourceAmount"], p["priceStage"]) for p in student["prices"]]
    assert stages == [
        ("DEPOSIT", "21,062", "STANDARD"),
        ("MONTHLY_RENT", "87,600", "STANDARD"),
        ("DEPOSIT", "26,062", "CONVERSION_DEPOSIT_UP"),
        ("MONTHLY_RENT", "62,600", "CONVERSION_DEPOSIT_UP"),
        ("DEPOSIT", "5,062", "CONVERSION_DEPOSIT_DOWN"),  # 위 행의 기본 가격을 되풀이하지 않음
        ("MONTHLY_RENT", "134,200", "CONVERSION_DEPOSIT_DOWN"),
    ]
    assert not any("(+)" in p["sourceAmount"] or "5,000" == p["sourceAmount"] for p in student["prices"])  # 전환 한도 칸은 가격 아님
    up, down = {p["pricePlanId"] for p in student["prices"][2:4]}, {p["pricePlanId"] for p in student["prices"][4:]}
    assert len(up) == len(down) == 1 and up != down  # 같은 안의 보증금·월세는 같은 요금안 ID
    st.link_locations(
        part,
        [
            {"id": "loc-1", "kind": "COMPLEX", "complexName": "화수정원마을1단지", "buildingName": None},
            {"id": "loc-2", "kind": "COMPLEX", "complexName": "만부마을", "buildingName": None},
        ],
    )
    assert student["locationId"] == youth["locationId"] == "loc-1" and "_complex" not in student


def structure_answer(source, sections=("notice", "locations")):
    part = ai_part(example("housing"), sections)
    for quote in part["quotes"]:
        quote["documentId"] = source.text_document_id  # 인용 문서 ID는 이 자료의 ID만 허용됩니다
    return json.dumps(part, ensure_ascii=False)


def test_merged_type_cell_with_two_area_rows_makes_two_groups_and_building_column_is_not_the_complex():
    # 실측(2026-10-18 LH 철원갈말): 「24A」 한 칸 아래 24.59㎡ 8호·24.66㎡ 12호, 「해당동 101 102」 열, 가격표는 24A 한 행
    rows = (
        ("형", "건설 호수", "전용", "대기", "모집", "해당동"),
        ("24A", "8", "24.59", "0", "20", "101 102"),
        ("", "12", "24.66", "", "", ""),
    )
    cells = ((0, 1, 2, 3, 4, 5), (6, 7, 8, 9, 10, 11), (6, 12, 13, 9, 10, 11))
    supply = st.Table("1-3", 1, "■ 형별 공급계획", rows[0], rows, cells)
    price_rows = (("형", "보증금", "월세"), ("24A", "2,343,000", "46,630"))
    prices = st.Table("2-1", 2, "■ 임대조건", price_rows[0], price_rows, ((0, 1, 2), (3, 4, 5)))
    mapping = {
        "tables": [
            {
                "table": "1-3",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": "철원갈말",
                "columns": [
                    column(1, "HOUSING_TYPE"),
                    column(2, "COUNT", ck="TOTAL_HOUSING"),
                    column(3, "AREA_EXCLUSIVE"),
                    column(4, "COUNT", ck="WAITLIST"),
                    column(5, "COUNT", ck="OFFERED"),
                    column(6, "BUILDING"),
                ],
            },
            {
                "table": "2-1",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": "철원갈말",
                "columns": [column(1, "HOUSING_TYPE"), column(2, "PRICE", pk="DEPOSIT", unit="원"), column(3, "PRICE", pk="MONTHLY_RENT", unit="원")],
            },
        ]
    }
    groups = st.build_part([supply, prices], mapping, "pdf-text")["supplyGroups"]
    assert [(g["exclusiveAreaSqm"], [c["value"] for c in g["counts"] if c["kind"] == "TOTAL_HOUSING"]) for g in groups] == [(24.59, [8]), (24.66, [12])]
    assert all([p["sourceAmount"] for p in g["prices"]] == ["2,343,000", "46,630"] for g in groups)  # 가격표 한 행이 두 묶음 모두에
    offered = [c for g in groups for c in g["counts"] if c["kind"] == "OFFERED"]
    assert len(offered) == 2 and offered[0]["allocationPoolId"] == offered[1]["allocationPoolId"] is not None  # 병합 칸 한 물량


def test_conversion_cell_holding_up_and_down_amounts_is_split_by_both_signs():
    # 실측(2026-10-18 LH 포항 행복주택): 한도 칸 「(+) - (-) 10,000」, 전환 보증금 칸 「12,614 2,614」, 전환 월세 칸 「57,810 86,970」
    rows = (("형", "보증금", "월세", "한도", "전환 보증금", "전환 월세"), ("21A", "12,614", "57,810", "(+) - (-) 10,000", "12,614 2,614", "57,810 86,970"))
    table = st.Table("6-1", 6, "임대조건", rows[0], rows, ((0, 1, 2, 3, 4, 5), (6, 7, 8, 9, 10, 11)))
    mapping = {
        "tables": [
            {
                "table": "6-1",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": "포항",
                "columns": [
                    column(1, "HOUSING_TYPE"),
                    column(2, "PRICE", pk="DEPOSIT", unit="천원", plan="기본", stage="STANDARD"),
                    column(3, "PRICE", pk="MONTHLY_RENT", unit="원", plan="기본", stage="STANDARD"),
                    column(4, "CONVERSION_LIMIT"),
                    column(5, "PRICE", pk="DEPOSIT", unit="천원", plan="최대 전환", stage="CONVERSION_DEPOSIT_UP"),
                    column(6, "PRICE", pk="MONTHLY_RENT", unit="원", plan="최대 전환", stage="CONVERSION_DEPOSIT_UP"),
                ],
            }
        ]
    }
    [group] = st.build_part([table], mapping, "pdf-text")["supplyGroups"]
    assert [(p["kind"], p["sourceAmount"], p["priceStage"]) for p in group["prices"]] == [
        ("DEPOSIT", "12,614", "STANDARD"),
        ("MONTHLY_RENT", "57,810", "STANDARD"),
        ("DEPOSIT", "12,614", "CONVERSION_DEPOSIT_UP"),
        ("DEPOSIT", "2,614", "CONVERSION_DEPOSIT_DOWN"),
        ("MONTHLY_RENT", "57,810", "CONVERSION_DEPOSIT_UP"),
        ("MONTHLY_RENT", "86,970", "CONVERSION_DEPOSIT_DOWN"),
    ]


def test_two_target_columns_are_joined_and_price_rows_attach_only_to_the_same_target():
    # 실측(2026-10-18 LH 포항 행복주택): 대상 칸 「청년」|「소득無」 중 마지막만 남고, 전세보증금 표의 계층별 행이 21A 묶음마다 모두 붙음
    supply_rows = (("형", "대상", "세부", "호수"), ("21A", "청년", "소득無", "107"), ("", "", "소득有", ""))
    supply = st.Table("6-1", 6, "임대조건", supply_rows[0], supply_rows, ((0, 1, 2, 3), (4, 5, 6, 7), (4, 5, 8, 7)))
    lease_rows = (("형", "대상", "세부", "전세보증금"), ("21A", "청년", "소득無", "24,176,000"), ("", "", "소득有", "25,598,000"))
    lease = st.Table("7-1", 7, "전세", lease_rows[0], lease_rows, ((0, 1, 2, 3), (4, 5, 6, 7), (4, 5, 8, 9)))
    target = [column(2, "TARGET_GROUP"), column(3, "TARGET_GROUP")]
    mapping = {
        "tables": [
            {
                "table": "6-1",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": "포항",
                "columns": [column(1, "HOUSING_TYPE"), *target, column(4, "COUNT", ck="OFFERED")],
            },
            {
                "table": "7-1",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": "포항",
                "columns": [column(1, "HOUSING_TYPE"), *target, column(4, "PRICE", pk="DEPOSIT", unit="원", plan="전세")],
            },
        ]
    }
    groups = st.build_part([supply, lease], mapping, "pdf-text")["supplyGroups"]
    assert [g["scope"]["targetGroups"] for g in groups] == [["청년 소득無"], ["청년 소득有"]]
    assert [[p["sourceAmount"] for p in g["prices"]] for g in groups] == [["24,176,000"], ["25,598,000"]]


def test_price_rows_attach_when_the_two_tables_name_the_same_class_differently():
    # 실측(LH 인천석남 행복주택): 공급표는 「대학생」「청년」「고령자 (주거약자용外)」, 임대조건표는 「대학생· 청년(소득無)」
    # 「청년(소득有)」「고령자」라 가격이 모집 수 없는 묶음으로 따로 남음. 소득 없는 청년은 대학생 조건을 받으므로 청년 묶음에는 두 조건이 다 붙습니다
    supply_rows = (
        ("형", "계층", "호수"),
        ("21A", "대학생", "5"),
        ("", "청년", "23"),
        ("26AS", "고령자 (주거약자용)", "5"),
        ("26B", "고령자 (주거약자용外)", "1"),
    )
    supply = st.Table("1-7", 1, "임대대상", supply_rows[0], supply_rows, ((0, 1, 2), (3, 4, 5), (3, 6, 7), (8, 9, 10), (11, 12, 13)))
    lease_rows = (
        ("형", "계층", "보증금"),
        ("21A", "대학생· 청년(소득無)", "32,708,000"),
        ("", "청년(소득有)", "34,632,000"),
        ("26AS", "고령자 (주거약자용)", "45,790,000"),
        ("26B", "고령자", "45,790,000"),
    )
    lease = st.Table("2-1", 2, "임대조건", lease_rows[0], lease_rows, ((0, 1, 2), (3, 4, 5), (3, 6, 7), (8, 9, 10), (11, 12, 13)))
    mapping = {
        "tables": [
            {
                "table": "1-7",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": None,
                "columns": [column(1, "HOUSING_TYPE"), column(2, "TARGET_GROUP"), column(3, "COUNT", ck="OFFERED")],
            },
            {
                "table": "2-1",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": None,
                "columns": [column(1, "HOUSING_TYPE"), column(2, "TARGET_GROUP"), column(3, "PRICE", pk="DEPOSIT", unit="원", plan="기본")],
            },
        ]
    }
    groups = st.build_part([supply, lease], mapping, "pdf-text")["supplyGroups"]
    assert [(g["housingTypeCode"], g["scope"]["targetGroups"], sorted(p["sourceAmount"] for p in g["prices"])) for g in groups] == [
        ("21A", ["대학생"], ["32,708,000"]),
        ("21A", ["청년"], ["32,708,000", "34,632,000"]),
        ("26AS", ["고령자 (주거약자용)"], ["45,790,000"]),
        ("26B", ["고령자 (주거약자용外)"], ["45,790,000"]),
    ]


def test_headerless_table_at_top_of_next_page_continues_the_mapped_table():
    # 실측(LH 청주지북 공공분양): 분양가 표 55A·55A-1 행 뒤 55B~59C 행이 다음 쪽 머리 없는 표로 읽혀 분양가 55개가 빠짐
    from types import SimpleNamespace

    pages = [
        {
            "page": 2,
            "blocks": [
                {
                    "type": "table",
                    "id": "2-5",
                    "columns": ["타입", "층별", "주택가격"],
                    "rows": [["55A", "1층", "288,580"], ["", "2층", "291,650"]],
                    "cells": [[0, 1, 2], [0, 3, 4]],
                }
            ],
        },
        {
            "page": 3,
            "blocks": [
                {"type": "table", "id": "3-1", "columns": [], "rows": [["55B", "1층", "288,620"], ["", "2층", "291,690"]], "cells": [[0, 1, 2], [0, 3, 4]]}
            ],
        },
        {"page": 4, "blocks": [{"type": "table", "id": "4-1", "columns": [], "rows": [["※ 안내", "1", "비고"]]}]},  # 숫자 칸이 다르면 이어짐이 아님
    ]
    tables = st.candidates(SimpleNamespace(pdf_pages=[{"pages": pages}]))
    assert [(t.label, t.continues) for t in tables] == [("2-5", None), ("3-1", "2-5")]
    mapping = {
        "tables": [
            {
                "table": "2-5",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": None,
                "columns": [column(1, "HOUSING_TYPE"), column(2, "FLOOR"), column(3, "PRICE", pk="SALE_PRICE", unit="천원", plan="분양가")],
            }
        ]
    }
    groups = st.build_part(tables[:2], mapping, "pdf-text")["supplyGroups"]
    assert sorted({g["housingTypeCode"] for g in groups}) == ["55A", "55B"]
    assert sorted(p["sourceAmount"] for g in groups if g["housingTypeCode"] == "55B" for p in g["prices"]) == ["288,620", "291,690"]


def test_price_rows_split_by_area_attach_to_the_type_whose_area_range_holds_them():
    # 실측(LH 부산당감 50년 공공임대): 공급표 「39 | 39.70 ~ 39.87 | 100」, 임대조건표는 면적 「39.70」「39.87」 행마다 보증금
    supply_rows = (("주택형", "전용", "모집"), ("39", "39.70 ~ 39.87", "100"))
    supply = st.Table("2-3", 2, "", supply_rows[0], supply_rows, None)
    lease_rows = (("주택형", "보증금"), ("39.70", "19,217,000"), ("39.87", "19,284,000"))
    lease = st.Table("3-1", 3, "", lease_rows[0], lease_rows, None)
    mapping = {
        "tables": [
            {
                "table": "2-3",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": None,
                "columns": [column(1, "HOUSING_TYPE"), column(2, "AREA_EXCLUSIVE"), column(3, "COUNT", ck="OFFERED")],
            },
            {
                "table": "3-1",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": None,
                "columns": [column(1, "HOUSING_TYPE"), column(2, "PRICE", pk="DEPOSIT", unit="원", plan="기본")],
            },
        ]
    }
    [group] = st.build_part([supply, lease], mapping, "pdf-text")["supplyGroups"]
    assert group["housingTypeCode"] == "39" and group["exclusiveAreaRange"] == {"min": 39.7, "max": 39.87}
    assert [(p["sourceAmount"], p["scope"]["description"].split(" ")[1]) for p in group["prices"]] == [("19,217,000", "39.7㎡"), ("19,284,000", "39.87㎡")]
    assert len({p["pricePlanId"] for p in group["prices"]}) == 2


def test_structure_call_skips_supply_when_tables_are_mapped_and_falls_back_otherwise():
    source = material()
    model = RecordedModel({"supply_tables": [json.dumps(MAPPING)], "structure": [structure_answer(source)]})
    rules = replace(RULES, calls=RULES.calls[:1], split_calls=(), large_text_chars=None)  # 구조 호출까지만 봅니다
    run = run_extraction(rules, source, model)
    names = [r.call_name for r in model.requests]
    assert names == ["supply_tables", "structure"]
    structure_request = model.requests[1]
    assert "supplyGroups" not in structure_request.output_schema["properties"] and "프로그램이 공급표에서 직접 옮겼으므로" in structure_request.instructions
    assert model.requests[0].user == structure_request.user  # 같은 원문 앞부분(입력 캐시 공유)
    assert [sorted(p["coverage"]) for p in run.parts] == [["locations", "notice"], ["supplyGroups"]]

    broken = RecordedModel({"supply_tables": ['{"tables": "not a list"}'], "structure": [structure_answer(source, RULES.calls[0].sections)]})
    run = run_extraction(rules, source, broken)
    assert "supplyGroups" in broken.requests[1].output_schema["properties"] and len(run.parts) == 1  # 이전 방식으로


def test_price_table_rows_attach_to_count_groups_and_loose_answers_still_map():
    # 실측(SH 신정도시마을): 호수 표(일반형·복층형)와 임대료·전환 표(「39㎡」 한 행)가 나뉨. AI는 표 번호를 「표 4-2」로,
    # 전환 열의 priceStage를 비워 답했고, 같은 종류 수량 열 두 개(일반공급·우선공급)에 대상을 적지 않았습니다
    counts = st.Table(
        "3-1",
        3,
        "",
        ("단지", "주택형", "금회 공급호수 › 일반공급", "금회 공급호수 › 우선공급"),
        (("단지", "주택형", "금회 공급호수", ""), ("신정도시마을", "39 (일반형)", "6", "2"), ("", "0039 (복층형)", "4", "1")),
        None,
    )
    rents = st.Table(
        "4-2",
        4,
        "",
        ("단지", "신청 유형", "임대보증금(천원)", "월임대료 (원)", "총 세대수"),
        (("단지", "신청 유형", "임대보증금(천원)", "월임대료 (원)", "총 세대수"), ("신정도시마을", "39㎡", "44,000", "335,000", "92세대")),
        None,
    )
    conversion = st.Table(
        "5-1",
        5,
        "",
        ("단지", "신청 유형", "전환 구분", "최대 전환 › 보증금(천원)", "최대 전환 › 월 임대료(원)"),
        (("단지", "신청 유형", "전환 구분", "보증금", "월세"), ("신정도시마을", "39㎡", "(+)", "80,000", "134,000"), ("", "", "(-)", "17,600", "390,000")),
        ((0, 1, 2, 3, 4), (5, 6, 7, 8, 9), (5, 6, 10, 11, 12)),
    )
    mapping = {
        "tables": [
            {
                "table": "표 3-1 (3쪽)",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": None,
                "columns": [column(1, "COMPLEX"), column(2, "HOUSING_TYPE"), column(3, "COUNT", ck="OFFERED"), column(4, "COUNT", ck="OFFERED")],
            },
            {
                "table": "표 4-2",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": None,
                "columns": [
                    column(1, "COMPLEX"),
                    column(2, "HOUSING_TYPE"),
                    column(3, "PRICE", pk="DEPOSIT", unit="천원"),
                    column(4, "PRICE", pk="MONTHLY_RENT", unit="원"),
                    column(5, "COUNT", ck="TOTAL_HOUSING"),
                ],
            },
            {
                "table": "5-1",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": None,
                "columns": [
                    column(1, "COMPLEX"),
                    column(2, "HOUSING_TYPE"),
                    column(4, "PRICE", pk="DEPOSIT", unit="천원", plan="최대 전환"),
                    column(5, "PRICE", pk="MONTHLY_RENT", unit="원", plan="최대 전환"),
                ],
            },
        ]
    }
    part = st.build_part([counts, rents, conversion], mapping, "pdf-text")
    flat, duplex = part["supplyGroups"]  # 가격표 행은 따로 남지 않고 두 모집 묶음에 붙음
    assert [(c["value"], c["scope"]["targetGroups"]) for c in flat["counts"]] == [(6, ["일반공급"]), (2, ["우선공급"])]  # 총 세대수 92는 붙이지 않음
    for group in (flat, duplex):
        assert [(p["kind"], p["sourceAmount"], p["priceStage"]) for p in group["prices"]] == [
            ("DEPOSIT", "44,000", "STANDARD"),
            ("MONTHLY_RENT", "335,000", "STANDARD"),
            ("DEPOSIT", "80,000", "CONVERSION_DEPOSIT_UP"),
            ("MONTHLY_RENT", "134,000", "CONVERSION_DEPOSIT_UP"),
            ("DEPOSIT", "17,600", "CONVERSION_DEPOSIT_DOWN"),
            ("MONTHLY_RENT", "390,000", "CONVERSION_DEPOSIT_DOWN"),
        ]
        plans = [p["pricePlanId"] for p in group["prices"]]
        assert len(set(plans)) == 3 and all(p.startswith(group["id"]) for p in plans)  # 묶음마다 자기 요금안 ID
    assert {q["ref"] for q in part["quotes"]} >= set(flat["quoteRefs"])


def test_count_column_without_kind_takes_kind_from_title():
    # 실측(인천 행복주택): 「호수」 열을 COUNT로만 답해(countKind 없음) 대상 주택 수가 모두 버려졌음
    assert st._count_kind_from_title("호수") == "TOTAL_HOUSING" and st._count_kind_from_title("총 세대수") == "TOTAL_HOUSING"
    assert st._count_kind_from_title("금회 모집 호수") == "OFFERED" and st._count_kind_from_title("대기자 수") == "WAITLIST"
    assert st._count_kind_from_title("비고") is None
    table = st.Table("3-1", 3, "", ("형", "호수", "금회 모집"), (("형", "호수", "금회 모집"), ("18", "8", "20")), None)
    mapping = {
        "tables": [
            {
                "table": "3-1",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": None,
                "columns": [column(1, "HOUSING_TYPE"), column(2, "COUNT"), column(3, "COUNT", ck="OFFERED")],
            }
        ]
    }
    [group] = st.build_part([table], mapping, "pdf-text")["supplyGroups"]
    assert [(c["kind"], c["value"]) for c in group["counts"]] == [("TOTAL_HOUSING", 8), ("OFFERED", 20)]


def test_combined_vacancy_and_total_cell_is_split_into_two_counts():
    # 실측(인천 행복주택): 열 제목 「현재 공가 호수 / 전체 호수」, 칸 「7/8」
    table = st.Table("3-1", 3, "", ("형", "현재 공가 호수 / 전체 호수"), (("형", "현재 공가 호수 / 전체 호수"), ("18", "7/8")), None)
    mapping = {
        "tables": [{"table": "3-1", "role": "SUPPLY", "rowUnit": "HOUSING_TYPE", "complex": None, "columns": [column(1, "HOUSING_TYPE"), column(2, "COUNT")]}]
    }
    [group] = st.build_part([table], mapping, "pdf-text")["supplyGroups"]
    assert [(c["kind"], c["value"]) for c in group["counts"]] == [("CURRENT_VACANCIES", 7), ("TOTAL_HOUSING", 8)]


def test_lh_type_code_gives_area_and_price_rows_join_the_same_code_across_differently_named_complexes():
    # 실측(LH 충북혁신): 전용면적 열을 답하지 않았고, 공급표 「충북혁신 A-4블록 (천년나무4)」와 가격표 「충북혁신4 (천년나무4)」의
    # 이름이 달라 가격이 붙지 않음. 59A·59A-1은 면적이 같아 면적 번호로만 맞추면 서로의 가격이 섞임
    supply_rows = (("단지", "주택형", "모집"), ("충북혁신 A-4블록 (천년나무4)", "059.9800A (59A)", "45"), ("", "059.9800X (59A-1)", "25"))
    supply = st.Table("3-4", 3, "", supply_rows[0], supply_rows, ((0, 1, 2), (3, 4, 5), (3, 6, 7)))
    price_rows = (("단지", "주택형", "보증금"), ("충북혁신4 (천년나무4)", "059.9800A (59A)", "33,148,000"), ("", "059.9800X (59A-1)", "33,000,000"))
    prices = st.Table("4-1", 4, "", price_rows[0], price_rows, ((0, 1, 2), (3, 4, 5), (3, 6, 7)))
    keys = [column(1, "COMPLEX"), column(2, "HOUSING_TYPE")]
    mapping = {
        "tables": [
            {"table": "3-4", "role": "SUPPLY", "rowUnit": "HOUSING_TYPE", "complex": None, "columns": [*keys, column(3, "COUNT", ck="OFFERED")]},
            {"table": "4-1", "role": "SUPPLY", "rowUnit": "HOUSING_TYPE", "complex": None, "columns": [*keys, column(3, "PRICE", pk="DEPOSIT", unit="원")]},
        ]
    }
    groups = st.build_part([supply, prices], mapping, "pdf-text")["supplyGroups"]
    assert [(g["exclusiveAreaSqm"], [p["sourceAmount"] for p in g["prices"]]) for g in groups] == [(59.98, ["33,148,000"]), (59.98, ["33,000,000"])]


def test_continuation_row_with_its_own_different_count_is_its_own_group():
    # 실측(LH 아산읍내): 「26.37」 칸 아래 건설 세대수 478·596 두 행. 596 행이 앞 묶음에 묻혀 빠짐
    rows = (("형", "건설", "모집"), ("26.37", "478", "200"), ("", "596", ""), ("31.32", "149", "40"))
    table = st.Table("1-2", 1, "", rows[0], rows, ((0, 1, 2), (3, 4, 5), (3, 6, 5), (7, 8, 9)))
    mapping = {
        "tables": [
            {
                "table": "1-2",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": None,
                "columns": [column(1, "HOUSING_TYPE"), column(2, "COUNT", ck="TOTAL_HOUSING"), column(3, "COUNT", ck="OFFERED")],
            }
        ]
    }
    groups = st.build_part([table], mapping, "pdf-text")["supplyGroups"]
    assert [[(c["kind"], c["value"]) for c in g["counts"]] for g in groups] == [
        [("TOTAL_HOUSING", 478), ("OFFERED", 200)],
        [("TOTAL_HOUSING", 596), ("OFFERED", 200)],
        [("TOTAL_HOUSING", 149), ("OFFERED", 40)],
    ]
    assert groups[1]["counts"][1].get("allocationPoolId")  # 병합된 모집 200은 두 묶음이 함께 쓰는 물량


def test_shifted_amount_pair_moves_right_and_deposit_answered_as_limit_is_a_price():
    # 실측(LH 양산): 「① 감액 › 임대보증금」을 한도로 답했고, 그 칸에 「4,551 154,980」이 붙고 오른쪽 월임대료 칸이 빔
    rows = (("형", "기본 › 보증금", "① 감액 › 임대보증금", "① 감액 › 월임대료(원)"), ("55", "18,551", "4,551 154,980", ""))
    table = st.Table("2-4", 2, "", rows[0], rows, None)
    mapping = {
        "tables": [
            {
                "table": "2-4",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": None,
                "columns": [
                    column(1, "HOUSING_TYPE"),
                    column(2, "PRICE", pk="DEPOSIT", unit="천원", plan="기본", stage="STANDARD"),
                    column(3, "CONVERSION_LIMIT", pk="DEPOSIT", unit="천원", plan="감액", stage="CONVERSION_DEPOSIT_DOWN"),
                    column(4, "PRICE", pk="MONTHLY_RENT", unit="원", plan="감액", stage="CONVERSION_DEPOSIT_DOWN"),
                ],
            }
        ]
    }
    [group] = st.build_part([table], mapping, "pdf-text")["supplyGroups"]
    assert [(p["kind"], p["sourceAmount"], p["priceStage"]) for p in group["prices"]] == [
        ("DEPOSIT", "18,551", "STANDARD"),
        ("DEPOSIT", "4,551", "CONVERSION_DEPOSIT_DOWN"),
        ("MONTHLY_RENT", "154,980", "CONVERSION_DEPOSIT_DOWN"),
    ]


def test_unit_rows_without_count_column_are_one_offered_unit_each():
    # 실측(LH 김포 장애인 자립주택): 동·호·모집유형·면적·보증금 표에 수량 열이 없어 모집 수가 모두 빠짐
    rows = (("동", "호", "면적", "보증금"), ("1동", "401", "26.5", "1,000,000"), ("1동", "404", "26.5", "1,000,000"))
    table = st.Table("3-2", 3, "", rows[0], rows, None)
    mapping = {
        "tables": [
            {
                "table": "3-2",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_UNIT",
                "complex": "여기",
                "columns": [column(1, "BUILDING"), column(2, "UNIT"), column(3, "AREA_EXCLUSIVE"), column(4, "PRICE", pk="DEPOSIT", unit="원")],
            }
        ]
    }
    groups = st.build_part([table], mapping, "pdf-text")["supplyGroups"]
    assert [[(c["kind"], c["value"]) for c in g["counts"]] for g in groups] == [[("OFFERED", 1)], [("OFFERED", 1)]]
    housing = {"tables": [{**mapping["tables"][0], "rowUnit": "HOUSING_TYPE"}]}
    assert all(g["counts"] == [] for g in st.build_part([table], housing, "pdf-text")["supplyGroups"])  # 주택형 행은 그대로
