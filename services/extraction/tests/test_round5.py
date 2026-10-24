"""다섯 번째 시험 채점(2026-10-24)의 일반 규칙: 전환안 검산 기준, 방향별 한도 금액, 한도 칸 앞 금액 쌍은 기본 조건,
가로 병합으로 복제된 두 금액 칸, 붙인 가격의 중복 거르기·층 이름. 합성 표만 씁니다. AI·네트워크 호출 없음."""

from test_supply_tables import column

from mujuthaek_extraction import supply_tables as st
from mujuthaek_extraction.assemble.conversion import SAME_AS_BASE, plan_directions

# ---- 1. 전환안 검산 기준 ----


def price(plan, stage, kind, amount, unit="원"):
    return {"pricePlanId": plan, "priceStage": stage, "kind": kind, "sourceAmount": amount, "sourceUnit": unit, "scope": {"targetGroups": []}}


def test_deposit_only_standard_plan_is_not_a_base():
    # 전세 요금안(보증금만)이 기본안으로 같이 붙어도 보증금·월세가 다 있는 기본안으로 검산합니다
    prices = [
        price("a", "STANDARD", "DEPOSIT", "12,614"),
        price("a", "STANDARD", "MONTHLY_RENT", "57,810"),
        price("jeonse", "STANDARD", "DEPOSIT", "40,000"),
        price("up", "CONVERSION_DEPOSIT_UP", "DEPOSIT", "12,614"),
        price("up", "CONVERSION_DEPOSIT_UP", "MONTHLY_RENT", "57,810"),
    ]
    assert plan_directions(prices) == {"up": ("CONVERSION_DEPOSIT_UP", SAME_AS_BASE)}


def test_group_without_base_borrows_the_base_of_the_same_type():
    own = [price("d", "CONVERSION_DEPOSIT_DOWN", "DEPOSIT", "47,891"), price("d", "CONVERSION_DEPOSIT_DOWN", "MONTHLY_RENT", "27,880")]
    sibling = [price("s", "STANDARD", "DEPOSIT", "47,891"), price("s", "STANDARD", "MONTHLY_RENT", "27,880")]
    assert plan_directions(own) == {"d": ("CONVERSION_DEPOSIT_DOWN", "UNCHECKED")}
    assert plan_directions(own, sibling) == {"d": ("CONVERSION_DEPOSIT_DOWN", SAME_AS_BASE)}


# ---- 2·10. 한도 칸 ----

LIMIT_COLUMNS = [
    column(1, "HOUSING_TYPE"),
    column(2, "PRICE", pk="DEPOSIT", unit="천원", plan="기본", stage="STANDARD"),
    column(3, "PRICE", pk="MONTHLY_RENT", unit="원", plan="기본", stage="STANDARD"),
    column(4, "CONVERSION_LIMIT"),
    column(5, "PRICE", pk="DEPOSIT", unit="천원", plan="최대 전환", stage="CONVERSION_DEPOSIT_UP"),
    column(6, "PRICE", pk="MONTHLY_RENT", unit="원", plan="최대 전환", stage="CONVERSION_DEPOSIT_UP"),
]


def limit_part(limit, deposits, rents, columns=LIMIT_COLUMNS, titles=None):
    titles = titles or ("형", "보증금", "월세", "한도", "전환 보증금", "전환 월세")
    rows = (titles, ("21A", "12,614", "57,810", limit, deposits, rents))
    table = st.Table("6-1", 6, "임대조건", rows[0], rows, ((0, 1, 2, 3, 4, 5), (6, 7, 8, 9, 10, 11)))
    mapping = {"tables": [{"table": "6-1", "role": "SUPPLY", "rowUnit": "HOUSING_TYPE", "complex": None, "columns": columns}]}
    [group] = st.build_part([table], mapping, "pdf-text")["supplyGroups"]
    return [(p["kind"], p["sourceAmount"], p["priceStage"]) for p in group["prices"]]


def test_direction_without_amount_in_a_two_sign_limit_cell_makes_no_plan():
    prices = limit_part("(+) - (-) 10,000", "12,614 2,614", "57,810 86,970")
    assert [p for p in prices if p[2] == "CONVERSION_DEPOSIT_UP"] == []
    assert ("DEPOSIT", "2,614", "CONVERSION_DEPOSIT_DOWN") in prices


def test_direction_amounts_reads_each_sign():
    assert st._direction_amounts("(+) - (-) 10,000") == {"(+)": False, "(-)": True}
    assert st._direction_amounts("(+) 5,000 (-) 10,000") == {"(+)": True, "(-)": True}


def test_amount_pair_before_the_limit_is_the_base_when_converted_amounts_follow():
    # 「감액시 임대조건 › 임대보증금·월임대료 | 한도 | 전환보증금 | 전환임대료」: 앞 두 칸은 전환 전 기본 조건
    columns = [
        column(1, "HOUSING_TYPE"),
        column(2, "PRICE", pk="DEPOSIT", unit="원", plan="감액", stage="CONVERSION_DEPOSIT_DOWN"),
        column(3, "PRICE", pk="MONTHLY_RENT", unit="원", plan="감액", stage="CONVERSION_DEPOSIT_DOWN"),
        column(4, "CONVERSION_LIMIT"),
        column(5, "PRICE", pk="DEPOSIT", unit="원", plan="감액", stage="CONVERSION_DEPOSIT_DOWN"),
        column(6, "PRICE", pk="MONTHLY_RENT", unit="원", plan="감액", stage="CONVERSION_DEPOSIT_DOWN"),
    ]
    titles = ("평형", "감액시 임대조건 › 임대보증금", "감액시 임대조건 › 월임대료", "전환가능 보증금 한도액", "전환보증금", "전환임대료")
    prices = limit_part("-44,000,000", "3,891,000", "156,210", columns, titles)
    assert ("DEPOSIT", "12,614", "STANDARD") in prices and ("MONTHLY_RENT", "57,810", "STANDARD") in prices
    assert ("DEPOSIT", "3,891,000", "CONVERSION_DEPOSIT_DOWN") in prices


# ---- 6. 가로 병합으로 복제된 두 금액 칸 ----


def test_duplicated_two_amount_cell_is_split_into_the_next_column():
    values = {4: "4,551 154,980", 5: "4,551 154,980"}
    st._split_shifted_pairs(values, LIMIT_COLUMNS)
    assert values == {4: "4,551", 5: "154,980"}


def test_unsplit_amounts_hold_only_prices():
    columns = [column(1, "HOUSING_TYPE"), column(2, "COUNT", ck="OFFERED"), column(3, "PRICE", pk="DEPOSIT", unit="원", plan="기본", stage="STANDARD")]
    rows = (("형", "모집", "보증금"), ("21A", "5", "4,551 154,980"))
    table = st.Table("6-2", 6, "임대조건", rows[0], rows, None)
    part = st.build_part([table], {"tables": [{"table": "6-2", "role": "SUPPLY", "rowUnit": "HOUSING_TYPE", "complex": None, "columns": columns}]}, "pdf-text")
    [issue] = [i for i in part[st.ISSUES_KEY] if i["code"] == st.UNSPLIT_ISSUE]
    assert issue["field"] == "prices"


# ---- 7. 붙인 가격의 중복 거르기·층 이름 ----


def test_same_amount_on_each_floor_row_is_attached_once_per_floor_with_the_floor_name():
    count_rows = (("주택형", "모집"), ("55A", "10"))
    counts = st.Table("1-1", 1, "공급", count_rows[0], count_rows, None)
    price_rows = (("주택형", "층", "분양가", "융자금"), ("55A", "1층", "200,000", "55,000"), ("55A", "2층", "205,000", "55,000"))
    prices = st.Table("2-1", 2, "분양가격", price_rows[0], price_rows, None)
    mapping = {
        "tables": [
            {
                "table": "1-1",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": None,
                "columns": [column(1, "HOUSING_TYPE"), column(2, "COUNT", ck="OFFERED")],
            },
            {
                "table": "2-1",
                "role": "SUPPLY",
                "rowUnit": "HOUSING_TYPE",
                "complex": None,
                "columns": [
                    column(1, "HOUSING_TYPE"),
                    column(2, "FLOOR"),
                    column(3, "PRICE", pk="SALE_PRICE", unit="천원", plan="기본", stage="STANDARD"),
                    column(4, "PRICE", pk="OTHER", unit="천원", plan="기본", stage="STANDARD"),
                ],
            },
        ]
    }
    part = st.build_part([counts, prices], mapping, "pdf-text")
    [group] = [g for g in part["supplyGroups"] if g["counts"]]
    loans = [p for p in group["prices"] if p["kind"] == "OTHER"]
    assert len(loans) == 2
    assert {p["scope"]["description"].split(" ")[0] for p in loans} == {"1층", "2층"}
    assert not any("_attached" in p for p in group["prices"])


# ---- 3. 공고 기본 정보 근거 이슈는 칸 단위로 ----


def test_short_quote_issue_on_notice_points_at_the_field_it_supports():
    from mujuthaek_extraction.verify.verifier import held_target, narrow_notice_issues

    candidate = {
        "notice": {"targetGroups": ["가상청년", "가상신혼부부"], "supplyType": "가상행복주택"},
        "quality": {
            "reviewIssues": [
                {
                    "code": "EVIDENCE_QUOTE_TOO_SHORT",
                    "target": "/notice",
                    "description": "가상공고: 인용문이 너무 짧아 우연히 일치했을 수 있습니다 「① 가상청년」",
                }
            ]
        },
        "supplyGroups": [],
    }
    narrow_notice_issues(candidate)
    [issue] = candidate["quality"]["reviewIssues"]
    assert issue["target"] == "/notice/targetGroups"
    assert held_target(candidate, issue["target"]) == ("/notice/targetGroups", "ITEM")


def test_notice_issue_that_supports_no_single_field_stays_on_the_notice():
    from mujuthaek_extraction.verify.verifier import narrow_notice_issues

    candidate = {
        "notice": {"targetGroups": ["가상청년"], "supplyType": "가상행복주택"},
        "quality": {"reviewIssues": [{"code": "EVIDENCE_QUOTE_TOO_SHORT", "target": "/notice", "description": "가상공고: 짧음 「가상 공고」"}]},
    }
    narrow_notice_issues(candidate)
    assert candidate["quality"]["reviewIssues"][0]["target"] == "/notice"


# ---- 5. 병합 칸 값은 위치 의심에서 빼기 ----


def test_merged_cell_value_cited_with_its_first_row_is_not_position_suspected():
    from test_consistency import codes, scope

    first = "| 가상청년 | 소득무 | 500 | 12,000 |"
    own = "| 가상고령자 |  |  | 14,000 |"
    text = "\n".join(["[표 6-1]", "| 대상 | 구분 | 계약금 | 보증금 |", first, own])
    evidence = [
        {"id": "ev-own", "sourceId": "pdf-text", "locator": {"kind": "TEXT_RANGE", "start": text.index(own), "end": text.index(own) + len(own)}, "quote": own},
        {
            "id": "ev-first",
            "sourceId": "pdf-text",
            "locator": {"kind": "TEXT_RANGE", "start": text.index(first), "end": text.index(first) + len(first)},
            "quote": first,
        },
    ]
    contract = {
        "kind": "CONTRACT_PAYMENT",
        "sourceAmount": "500",
        "amountKrw": 500,
        "pricePlanId": "p",
        "scope": scope("가상고령자"),
        "evidenceIds": ["ev-own", "ev-first"],
    }
    candidate = {
        "evidence": evidence,
        "notice": {"counts": []},
        "locations": [],
        "supplyGroups": [
            {
                "housingTypeCode": None,
                "unitLabel": None,
                "exclusiveAreaSqm": None,
                "itemKind": "HOUSING_TYPE",
                "scope": scope("가상고령자"),
                "prices": [contract],
                "counts": [],
            }
        ],
        "schedules": [],
        "eligibility": [],
        "selectionRules": [],
        "requiredDocuments": [],
        "quality": {"coverage": {s: {"status": "EXTRACTED"} for s in ("eligibility", "requiredDocuments", "schedules")}},
    }
    assert [c for c in codes(candidate, text) if c[0] == "VALUE_POSITION_SUSPECTED"] == []
