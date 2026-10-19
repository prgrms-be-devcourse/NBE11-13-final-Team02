"""공급·가격 표는 코드가 행 단위로 옮기고, AI는 표마다 열의 의미만 답합니다(구조 방식, 2026-10-17 설계 검토 1번).

이전에는 AI가 공급표의 모든 행을 JSON으로 다시 썼습니다. 실측(2026-10-17 시험): 가격 128개짜리 인천 행복주택 표를 유료 모델이
10개만 남기고 멈췄고, 한 행의 두 번째 요금안·호실 일부를 빠뜨리거나 칸을 잘못 붙였습니다(모집 수 재현율 43~64%).

흐름
  1. candidates(): PDF 표 중 열 제목(intake/pdf_reader가 병합 칸에서 복원)에 공급·가격 낱말이 있고 숫자 행이 있는 표
  2. mapping_request(): AI에 표마다 열의 의미(주택형·대상·전용면적·수량 종류·가격 종류·단위·요금안)만 묻습니다. 값은 적지 않습니다
  3. build_part(): 코드가 칸 값을 그대로 옮겨 AI 출력과 같은 모양의 공급 조각을 만듭니다. 인용은 원문 표 행(| 줄) 그대로라
     근거 검증(V2)을 똑같이 거칩니다. 병합 칸(칸 번호)으로 위 행에서 이어받는 값·여러 묶음이 함께 쓰는 모집 물량을 복원합니다
  4. link_locations(): 구조 호출이 만든 단지(locations)와 표의 단지 이름을 맞춰 locationId를 붙입니다
열 의미를 받지 못하거나 공급표가 없으면 None — 이전처럼 구조 호출이 공급 묶음까지 만듭니다.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from dataclasses import dataclass, replace
from typing import Any

from mujuthaek_extraction.intake.model import ExtractionMaterial
from mujuthaek_extraction.intake.pdf_reader import NUMERIC_VALUE
from mujuthaek_extraction.model.base import ModelRequest

CALL_NAME = "supply_tables"
PROMPT_VERSION = "supply_tables/v1"
MAX_TABLES = 40
SUPPLY_HINT = re.compile(r"보증금|임대료|월세|분양|가격|전용|면적|호수|세대\s*수|모집|공급|공가|예비|deposit|rent|price|area|units", re.I)
SUBTOTAL = re.compile(r"^(?:합\s*계|소\s*계|총\s*계|계|합계)$")
NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?")
UNIT_IN_TITLE = re.compile(r"\((천원|만원|원)\)|(천원|만원)")
KEY_MEANINGS = ("COMPLEX", "BUILDING", "HOUSING_TYPE", "UNIT", "FLOOR", "TARGET_GROUP")
COUNT_KINDS = ["TOTAL_HOUSING", "OFFERED", "RESERVE", "CURRENT_VACANCIES", "WAITLIST"]
PRICE_KINDS = ["DEPOSIT", "MONTHLY_RENT", "SALE_PRICE", "CONTRACT_PAYMENT", "BALANCE", "OTHER"]
STAGES = ["STANDARD", "CONVERSION_DEPOSIT_UP", "CONVERSION_DEPOSIT_DOWN", "BEFORE_SUPPORT", "AFTER_SUPPORT", "OTHER"]
MEANINGS = [*KEY_MEANINGS, "AREA_EXCLUSIVE", "COUNT", "PRICE", "CONVERSION_LIMIT", "IGNORE"]
# 실측(2026-10-17): 열 의미 답 10건 중 9건이 표 번호를 「표 3-1」「표 3-1 (3쪽)」로 적어 표를 못 찾고 이전 방식으로 돌아갔습니다
LABEL_NOISE = re.compile(r"^\s*표\s*|\s*\(\s*\d+\s*쪽\s*\)\s*$")
SIGN_CELL = re.compile(r"\(?\s*([+\-−])\s*\)?")  # 전환 구분 칸 「(+)」「(-)」
SUPPLY_COUNT_KINDS = {"OFFERED", "RESERVE", "WAITLIST", "CURRENT_VACANCIES"}
HOUSING_NUMBER = re.compile(r"\d+(?:\.\d+)?")


def _nullable(schema: dict[str, Any]) -> dict[str, Any]:
    return {"anyOf": [schema, {"type": "null"}]}


MAPPING_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["tables"],
    "properties": {
        "tables": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["table", "role", "rowUnit", "complex", "columns"],
                "properties": {
                    "table": {"type": "string", "minLength": 1},
                    "role": {"enum": ["SUPPLY", "OTHER"]},
                    "rowUnit": {"enum": ["HOUSING_TYPE", "HOUSING_UNIT", "BUILDING", "OTHER"]},
                    "complex": _nullable({"type": "string", "minLength": 1}),
                    "columns": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "additionalProperties": False,
                            "required": ["column", "meaning", "countKind", "priceKind", "unit", "plan", "priceStage", "targetGroup"],
                            "properties": {
                                "column": {"type": "integer", "minimum": 1},
                                "meaning": {"enum": MEANINGS},
                                "countKind": _nullable({"enum": COUNT_KINDS}),
                                "priceKind": _nullable({"enum": PRICE_KINDS}),
                                "unit": _nullable({"enum": ["원", "천원", "만원"]}),
                                "plan": _nullable({"type": "string", "minLength": 1}),
                                "priceStage": _nullable({"enum": STAGES}),
                                "targetGroup": _nullable({"type": "string", "minLength": 1}),
                            },
                        },
                    },
                },
            },
        }
    },
}

INSTRUCTIONS = """# 이번 호출에서 할 일 (supply_tables)
아래 표들은 공급·가격 표 후보입니다. 표마다 **각 열이 무엇인지만** 답합니다. 행의 값은 프로그램이 원문 칸에서 그대로 옮기므로 값을 적지 않습니다.
- role: 이번 공고로 공급하는 주택형·호실·주택과 그 수량·가격·면적을 담은 표면 SUPPLY.
  소득·자산 기준표, 일정표, 배점표, 면적·용어 설명표, 이미 다른 표가 담은 요약표는 OTHER.
- rowUnit: 한 행이 주택형이면 HOUSING_TYPE, 호실이면 HOUSING_UNIT, 주택(건물) 한 채면 BUILDING.
- complex: 표 전체가 한 단지의 것이면(표 위 제목 등) 그 단지 이름, 행마다 단지가 다르거나 모르면 null.
- columns: 의미 있는 열만 넣습니다(넣지 않은 열은 무시). 열 번호는 [열 제목] 줄의 번호입니다. meaning:
  - COMPLEX 단지·주택 이름, BUILDING 동, HOUSING_TYPE 주택형·타입·평형, UNIT 호실, FLOOR 층, TARGET_GROUP 행마다 다른 공급 대상·계층·순위
  - AREA_EXCLUSIVE 주거전용면적(공급·계약·공용·합계 면적은 넣지 않음)
  - COUNT 수량 — countKind: 건설 호수·총 세대수 TOTAL_HOUSING, 금회 모집 OFFERED(예비입주자만 모집하는 공고면 금회 모집 예비자 수가 OFFERED),
    (예비입주자·입주대기자 공고에서 공실이 아닌 대상 주택 규모를 뜻하는 「공급호수」「세대수」 열은 TOTAL_HOUSING — 대상 주택 수와 모집 수는 따로 셉니다),
    즉시 입주자와 함께 뽑는 새 예비자 RESERVE, 이미 대기 중인 예비자 WAITLIST, 현재 공가 CURRENT_VACANCIES.
    수량 열이 공급 유형별(일반공급·우선공급·특별공급 등)로 나뉘면 targetGroup에 그 유형 이름
  - PRICE 금액 — priceKind(보증금 DEPOSIT·월임대료 MONTHLY_RENT·분양가 SALE_PRICE·계약금 CONTRACT_PAYMENT·잔금 BALANCE·그 밖 OTHER),
    unit(열 제목·표 위의 [단위] 표시), plan(요금안 이름: 기본, 보증금 최대 전환 등 — 같은 요금안의 보증금·월세는 같은 이름),
    priceStage(기본 STANDARD, 보증금을 올리거나 내린 전환안 CONVERSION_DEPOSIT_UP, 지원 전(당초) BEFORE_SUPPORT, 지원 후(반값 등) AFTER_SUPPORT),
    targetGroup(이 열이 특정 순위·계층에만 해당하면 그 이름, 예: 1순위·수급자 등)
  - CONVERSION_LIMIT 전환 가능 보증금 한도·증감액 칸(「(+)5,000」「(-)16,000」). 가격이 아닙니다. 프로그램이 이 칸의 부호로 보증금 증액·감액 전환안을 나눕니다
- 관리비·선납 할인·옵션 비용 열은 넣지 않습니다. 확실하지 않은 열은 넣지 않습니다.
- 표마다 하나씩, table에는 아래 「표 번호」 값만 씁니다(예: "3-1". 「표」 글자·쪽 번호는 붙이지 않음).
- 수량·가격이 표 여러 개에 나뉘어 있으면(호수 표와 임대료 표) 표마다 답합니다. 프로그램이 같은 단지·같은 면적 묶음에 가격을 붙입니다.
- 전환안 열은 priceStage를 반드시 적습니다. 한 행에 증액·감액이 「(+)」「(-)」 칸으로 나뉘면 그 칸을 CONVERSION_LIMIT로 넣습니다."""


@dataclass(frozen=True)
class Table:
    label: str  # 요청·조각 ID에 쓰는 표 번호(첨부 PDF가 여럿이면 「2:3-1」)
    page: int
    heading: str  # 표 바로 위 글(표 제목·단위 표시)
    columns: tuple[str, ...]
    rows: tuple[tuple[str, ...], ...]
    cells: tuple[tuple[int, ...], ...] | None
    continues: str | None = None  # 앞 쪽 표가 머리 행 없이 이어진 표면 그 표 번호(열 의미를 물려받음)


def _numeric(cell: str) -> bool:
    return bool(cell) and NUMERIC_VALUE.fullmatch(cell) is not None


def candidates(material: ExtractionMaterial) -> list[Table]:
    """첨부 PDF의 표 중 공급·가격 표 후보(열 제목에 공급 단서가 있고 숫자 칸이 있는 표)와 그 이어지는 표를 최대 MAX_TABLES개 고릅니다."""
    found: list[Table] = []
    many = len(material.pdf_pages) > 1
    for number, document in enumerate(material.pdf_pages, start=1):
        last: Table | None = None  # 이 문서에서 마지막으로 고른 표(다음 쪽 첫 표가 그 표의 이어짐인지 봅니다)
        for page in document.get("pages") or []:
            heading = ""
            first_table = True
            for block in page.get("blocks") or []:
                if block.get("type") == "text":
                    heading = " ".join(block.get("text", "").strip().split("\n")[-2:])[-160:]
                    continue
                columns = tuple(block.get("columns") or ())
                rows = tuple(tuple(r) for r in block.get("rows") or ())
                cells = block.get("cells")
                label = f"{number}:{block['id']}" if many else str(block["id"])
                page_number = int(page.get("page") or 0)
                numeric = any(_numeric(c) for r in rows for c in r)
                continues = first_table and _continues(last, page_number, columns, rows) and numeric
                first_table = False
                if continues and last is not None:
                    # 머리 행 없이 다음 쪽 맨 위로 이어진 표(실측 LH 청주지북 분양가 표: 55A·55A-1 뒤 55B~59C 행이 다음 쪽
                    # 머리 없는 표로 읽혀 매핑되지 않음 — 분양가 55개 빠짐). 앞 표의 열 제목을 쓰고 매핑은 앞 표를 따릅니다
                    last = Table(
                        label, page_number, heading, last.columns, rows, tuple(tuple(r) for r in cells) if cells else None, last.continues or last.label
                    )
                    found.append(last)
                elif columns and any(SUPPLY_HINT.search(c) for c in columns) and numeric:
                    last = Table(label, page_number, heading, columns, rows, tuple(tuple(r) for r in cells) if cells else None)
                    found.append(last)
                else:
                    last = None
                    continue
                if len(found) >= MAX_TABLES:
                    return found
    return found


def _continues(last: Table | None, page: int, columns: tuple[str, ...], rows: tuple[tuple[str, ...], ...]) -> bool:
    """머리 행 없는 표가 앞 쪽 마지막 공급 표의 이어짐인지: 바로 다음 쪽의 첫 표이고, 칸 수가 같고, 숫자가 있는 칸이
    앞 표에서도 숫자가 있던 칸이며 그 칸의 절반 이상을 채웁니다(제목 없는 안내 표를 잘못 잇지 않게)."""
    if last is None or columns or page != last.page + 1 or not rows or any(len(r) != len(last.columns) for r in rows):
        return False
    before = {c for r in last.rows for c, v in enumerate(r) if _numeric(v)}
    now = {c for r in rows for c, v in enumerate(r) if _numeric(v)}
    return bool(now) and now <= before and 2 * len(now) >= len(before)


def _row_line(row: tuple[str, ...]) -> str:
    return "| " + " | ".join(row) + " |"  # intake/pdf_reader.render와 같은 모양(원문 글자 그대로)


def mapping_request(base: ModelRequest, tables: list[Table], max_output_tokens: int = 16_000) -> ModelRequest:
    """base: 구조 호출 요청. 같은 [공통 지시 → 원문] 앞부분을 그대로 써서 입력 캐시를 함께 쓰고, 지시·출력 형식만 바꿉니다."""
    listing = []
    for table in tables:
        if table.continues:
            continue  # 이어진 표는 앞 표의 답을 따릅니다
        sample = next((r for r in table.rows if any(_numeric(c) for c in r)), table.rows[0] if table.rows else ())
        titles = " | ".join(f"{i + 1}. {t or '-'}" for i, t in enumerate(table.columns))
        listing.append(f"## 표 번호 {table.label} — {table.page}쪽\n앞 글: {table.heading or '-'}\n[열 제목] {titles}\n예시 행: {_row_line(sample)}")
    return replace(
        base,
        prompt_version=PROMPT_VERSION,
        call_name=CALL_NAME,
        instructions=INSTRUCTIONS + "\n\n" + "\n\n".join(listing),
        output_schema=MAPPING_SCHEMA,
        max_output_tokens=max_output_tokens,
    )


def _filled(table: Table) -> tuple[list[list[str]], list[list[int]]]:
    """병합 칸 복원: 같은 칸 번호의 빈 자리를 그 칸의 첫 자리 글자로. 둘째 값은 자리마다 그 글자가 처음 나온 행 번호."""
    first: dict[int, tuple[int, str]] = {}
    filled, origin = [], []
    for r, row in enumerate(table.rows):
        line, source = [], []
        for c, value in enumerate(row):
            cid = table.cells[r][c] if table.cells and r < len(table.cells) and c < len(table.cells[r]) else -1
            if cid >= 0 and cid in first and not value:
                line.append(first[cid][1])
                source.append(first[cid][0])
            else:
                if cid >= 0 and cid not in first:
                    first[cid] = (r, value)
                line.append(value)
                source.append(r)
        filled.append(line)
        origin.append(source)
    return filled, origin


def _cell_id(table: Table, r: int, c: int) -> int:
    return table.cells[r][c] if table.cells and r < len(table.cells) and c < len(table.cells[r]) else -1


def _number(text: str, last: bool = False) -> float | None:
    found = NUMBER.findall(text.replace(" ", ""))
    if not found:
        return None
    return float((found[-1] if last else found[0]).replace(",", ""))


def _slug(text: str) -> str:
    return re.sub(r"[^0-9a-z]+", "-", text.lower()).strip("-") or "t"


def _unit(column: dict[str, Any], title: str) -> str:
    if column.get("unit"):
        return column["unit"]
    match = UNIT_IN_TITLE.search(title)
    return (match.group(1) or match.group(2)) if match else "원"


def _scope(group_id: str, groups: list[str], description: str) -> dict[str, Any]:
    return {
        "level": "SUPPLY_GROUP",
        "locationIds": [],
        "supplyGroupIds": [group_id],
        "targetGroups": groups,
        "supplyCategory": "ALL",
        "resolution": "RESOLVED",
        "description": description,
    }


def build_part(tables: list[Table], mapping: dict[str, Any], document_id: str) -> dict[str, Any] | None:
    """열 의미 → AI 출력과 같은 모양의 공급 조각(quotes·supplyGroups·coverage). 공급표가 하나도 없으면 None."""
    by_label = {t.label: t for t in tables}
    quotes: list[dict[str, Any]] = []
    quote_of: dict[str, str] = {}
    groups: list[dict[str, Any]] = []
    shared: dict[tuple[str, int, str], set[str]] = {}  # (표, 칸 번호, 수량 종류) → 그 칸의 수량을 쓰는 공급 묶음
    pending_pools: list[tuple[dict[str, Any], tuple[str, int, str]]] = []

    def quote(line: str) -> str:
        if line not in quote_of:
            quote_of[line] = f"t{len(quote_of) + 1}"
            quotes.append({"ref": quote_of[line], "documentId": document_id, "text": line})
        return quote_of[line]

    entries = list(mapping.get("tables") or [])
    supply_of = {str(e.get("table")): e for e in entries if e.get("role") == "SUPPLY"}
    for table in tables:  # 이어진 표는 앞 표와 같은 열 의미로 읽습니다
        if table.continues and table.continues in supply_of and table.label not in supply_of:
            entries.append({**supply_of[table.continues], "table": table.label})
    for entry in entries:
        table = by_label.get(str(entry.get("table"))) or by_label.get(LABEL_NOISE.sub("", str(entry.get("table") or "")))
        if table is None or entry.get("role") != "SUPPLY":
            continue
        width = len(table.columns)
        columns = [c for c in entry.get("columns") or [] if 1 <= int(c.get("column") or 0) <= width and c.get("meaning") != "IGNORE"]
        keys = [c for c in columns if c["meaning"] in KEY_MEANINGS]
        if not keys or not any(c["meaning"] in ("PRICE", "COUNT", "AREA_EXCLUSIVE") for c in columns):
            continue
        filled, origin = _filled(table)
        # 호실마다 한 행인 모집 표(동·호·면적·보증금)에 수량 열이 없으면 한 행이 모집 1호입니다(실측 LH 김포 장애인 자립주택: 모집 수 0/4)
        unit_rows = entry.get("rowUnit") == "HOUSING_UNIT" and not any(c["meaning"] == "COUNT" for c in columns)
        first_data = next((r for r, row in enumerate(filled) if any(_numeric(c) for c in row)), None)
        if first_data is None:
            continue
        for c in columns:  # 한도 열로 답했지만 제목에 한도가 없고 금액 종류까지 적은 열은 전환 후 금액입니다(실측: LH 양산 「① 감액 › 임대보증금」)
            if c["meaning"] == "CONVERSION_LIMIT" and c.get("priceKind") and not LIMIT_WORDS.search(table.columns[c["column"] - 1]):
                c["meaning"] = "PRICE"
        limit_column = next((c["column"] - 1 for c in columns if c["meaning"] == "CONVERSION_LIMIT"), None)
        first_price = min((c["column"] for c in columns if c["meaning"] == "PRICE"), default=0)
        for c in columns:  # 종류를 비운 수량 열은 열 제목으로 정합니다(실측: 인천 「호수」 열을 COUNT로만 답해 대상 주택 수가 모두 빠짐)
            if c["meaning"] != "COUNT":
                continue
            title = table.columns[c["column"] - 1]
            parts = [_count_kind_from_title(t) for t in title.split("/")] if title.count("/") == 1 else []
            if len(parts) == 2 and all(parts) and (not c.get("countKind") or c["countKind"] in parts):
                c["splitKinds"] = parts  # 「현재 공가 호수 / 전체 호수」 칸의 「7/8」 → 공가 7, 대상 주택 8 (답이 둘 중 하나만 골라도 나눔)
            if not c.get("countKind"):
                c["countKind"] = parts[0] if c.get("splitKinds") else _count_kind_from_title(title)
        same_kind = Counter(c.get("countKind") for c in columns if c["meaning"] == "COUNT" and not c.get("targetGroup"))
        for c in columns:  # 같은 종류 수량 열이 둘 이상인데 대상을 비워 답하면(실측: SH 일반공급·우선공급) 열 제목 끝 이름을 대상으로
            if c["meaning"] == "COUNT" and not c.get("targetGroup") and same_kind[c.get("countKind")] > 1:
                c["targetGroup"] = table.columns[c["column"] - 1].split("›")[-1].strip() or None
        current: dict[str, Any] | None = None
        current_key: tuple[str, ...] | None = None
        current_rows: set[int] = set()
        for r in range(first_data, len(filled)):
            row = filled[r]
            values = {c["column"] - 1: row[c["column"] - 1] if c["column"] - 1 < len(row) else "" for c in columns}
            key = tuple(values[c["column"] - 1] for c in keys)
            if any(SUBTOTAL.match(v or "") for v in key) or not any(_numeric(v) for v in values.values()):
                continue
            if not any(key):
                key = current_key or key  # 이름 칸이 비어 있으면(병합 정보 없는 이어지는 행) 앞 묶음의 행
            _split_shifted_pairs(values, columns)
            line = _row_line(table.rows[r])
            ref = quote(line)
            # 주택형 칸이 세로로 병합되어 이름이 같아도 그 행의 전용면적이 다르면 다른 공급 행입니다
            # (실측 2026-10-18 LH 철원갈말: 「24A」 한 칸 아래 24.59㎡ 8호·24.66㎡ 12호 두 행이 한 묶음으로 합쳐져 둘째 행이 빠짐)
            own_area = next(
                (values[c["column"] - 1] for c in columns if c["meaning"] == "AREA_EXCLUSIVE" and origin[r][c["column"] - 1] == r and values[c["column"] - 1]),
                None,
            )
            area = _number(own_area) if own_area else None
            new_area = current is not None and area is not None and current.get("exclusiveAreaSqm") not in (None, area)
            # 이름·면적 칸이 위 행과 병합된 이어지는 행이라도 자기 수량 칸에 다른 수가 있으면 다른 공급 행입니다
            # (실측: LH 아산읍내 「26.37」 한 칸 아래 건설 세대수 478·596 두 행 중 596이 빠짐). 같은 수의 되풀이는 같은 행
            had = {(cnt["kind"], cnt["value"]) for cnt in current["counts"]} if current is not None else set()
            own_count = (
                current is not None
                and key == current_key
                and any(
                    c["meaning"] == "COUNT"
                    and origin[r][c["column"] - 1] == r
                    and _number(values[c["column"] - 1] or "") is not None
                    and any(kind == c.get("countKind") for kind, _ in had)
                    and (c.get("countKind"), int(_number(values[c["column"] - 1]) or 0)) not in had
                    for c in columns
                )
            )
            if key != current_key or current is None or new_area or own_count:
                named = {c["meaning"]: values[c["column"] - 1] for c in keys if values[c["column"] - 1]}
                # 대상 칸이 둘 이상(「청년」|「소득無」)이면 이어 씁니다. 예전엔 마지막 칸만 남아 「소득無」가 대상이 됨(실측: LH 포항 행복주택)
                parts = list(dict.fromkeys(values[c["column"] - 1] for c in keys if c["meaning"] == "TARGET_GROUP" and values[c["column"] - 1]))
                if parts:
                    named["TARGET_GROUP"] = " ".join(parts)
                group_id = f"sg-t{_slug(table.label)}-r{r}"
                target = named.get("TARGET_GROUP")
                name = " ".join(v for m, v in named.items() if m != "TARGET_GROUP") or table.label
                current = {
                    "id": group_id,
                    "locationId": None,
                    "housingTypeCode": named.get("HOUSING_TYPE"),
                    "buildingUse": None,
                    "exclusiveAreaSqm": None,
                    "exclusiveAreaRange": None,
                    "scope": _scope(group_id, [target] if target else [], f"{name}{' ' + target if target else ''} 공급 묶음에 적용합니다."),
                    "counts": [],
                    "prices": [],
                    "quoteRefs": [ref],
                    "itemKind": {"HOUSING_TYPE": "HOUSING_TYPE", "HOUSING_UNIT": "HOUSING_UNIT"}.get(entry.get("rowUnit"), "UNKNOWN"),
                    "unitLabel": named.get("UNIT") or (named.get("COMPLEX") if entry.get("rowUnit") == "BUILDING" else None),
                    "floor": named.get("FLOOR"),
                    "commonAreaSqm": None,
                    "leaseTerm": None,
                    # 표 전체의 단지 이름이 있으면 「해당동 101 102」 같은 동 칸보다 먼저 씁니다(실측: 철원 공급표의 동 번호가 단지로 쓰여 가격표가 안 붙음)
                    "_complex": named.get("COMPLEX") or entry.get("complex") or named.get("BUILDING"),
                    "_plans": {},
                }
                groups.append(current)
                current_key, current_rows = key, set()
                if unit_rows:
                    current["counts"].append(
                        {
                            "id": f"{group_id}.count1",
                            "kind": "OFFERED",
                            "value": 1,
                            "scope": _scope(group_id, current["scope"]["targetGroups"], current["scope"]["description"]),
                            "aggregationRole": "STANDALONE",
                            "parentCountId": None,
                            "aggregationComplete": False,
                            "quoteRefs": [ref],
                        }
                    )
                for column in columns:
                    c = column["column"] - 1
                    value = values[c]
                    if column["meaning"] == "AREA_EXCLUSIVE" and value:
                        span = re.findall(r"\d+(?:\.\d+)?", value)
                        if len(span) == 2 and re.search(r"[~∼～〜]", value):
                            current["exclusiveAreaRange"] = {"min": float(span[0]), "max": float(span[1])}
                        elif span:
                            current["exclusiveAreaSqm"] = float(span[0])
                    if column["meaning"] == "COUNT" and column.get("countKind") and value:
                        pieces = value.split("/") if column.get("splitKinds") and value.count("/") == 1 else [value]
                        kinds = column["splitKinds"] if len(pieces) == 2 else [column["countKind"]]
                        for kind, piece in zip(kinds, pieces, strict=True):
                            number = 0 if piece.strip() in {"-", "−", "–"} else _number(piece, last=kind == "TOTAL_HOUSING" and "/" in piece)
                            if number is None:
                                continue
                            count = {
                                "id": f"{group_id}.count{len(current['counts']) + 1}",
                                "kind": kind,
                                "value": int(number),
                                "scope": _scope(
                                    group_id,
                                    [column["targetGroup"]] if column.get("targetGroup") else current["scope"]["targetGroups"],
                                    current["scope"]["description"],
                                ),
                                "aggregationRole": "STANDALONE",
                                "parentCountId": None,
                                "aggregationComplete": False,
                                "quoteRefs": [quote(_row_line(table.rows[origin[r][c]]))],
                            }
                            current["counts"].append(count)
                            cell = (table.label, _cell_id(table, origin[r][c], c), kind)  # 「7/8」처럼 한 칸의 두 수는 종류별로 따로 묶음
                            if cell[1] >= 0:
                                shared.setdefault(cell, set()).add(group_id)
                                pending_pools.append((count, cell))
            current_rows.add(r)
            sign = _sign(row, limit_column)
            for column in columns:
                c = column["column"] - 1
                if column["meaning"] != "PRICE" or not column.get("priceKind"):
                    continue
                value = values[c].strip()
                if not value or value in {"-", "−", "–"} or _number(value) is None:
                    continue
                if origin[r][c] != r and origin[r][c] in current_rows:
                    continue  # 같은 묶음의 위 행에서 세로로 이어진 값: 이미 넣음
                base_plan = column.get("plan") or "기본"
                base_stage = column.get("priceStage") or _guess_stage(column, base_plan, limit_column, first_price, sign)
                amounts = AMOUNT_IN_CELL.findall(value) if SPACED_AMOUNTS.search(value) else [value]  # 범위(「1,000~2,000」)는 한 값
                if len(amounts) > 1:
                    # 한 칸에 금액 두 개: 전환 칸이고 한도 칸에 (+)와 (-)가 함께 있으면 위가 증액·아래가 감액(실측: LH 포항 행복주택
                    # 「(+) - (-) 10,000」 | 「12,614 2,614」). 그 밖에는 어느 안의 값인지 몰라 넣지 않습니다(빠진 가격으로 검수)
                    if not (base_stage.startswith("CONVERSION") and len(amounts) == 2 and "(+)" in sign and "(-)" in sign.replace("−", "-")):
                        continue
                    cells = [(amounts[0], "(+)"), (amounts[1], "(-)")]
                else:
                    cells = [(value, sign)]
                for cell_value, cell_sign in cells:
                    plan, stage = base_plan, base_stage
                    if stage.startswith("CONVERSION") and cell_sign:
                        down = cell_sign.startswith(("(-)", "-", "−", "(−)"))
                        stage = "CONVERSION_DEPOSIT_DOWN" if down else "CONVERSION_DEPOSIT_UP"
                        plan = f"{plan} (보증금 {'감액' if down else '증액'})"
                    _add_price(current, column, table.columns[c], ref, cell_value, plan, stage)
    for group in groups:  # 전용면적 열이 없거나 비면 LH 주택형 코드(「059.9800A」 → 59.98)로 채웁니다(실측: LH 충북혁신)
        code = TYPE_CODE_AREA.match(group.get("housingTypeCode") or "")
        if code and group["exclusiveAreaSqm"] is None and group["exclusiveAreaRange"] is None:
            group["exclusiveAreaSqm"] = float(code[1])
    groups = _attach_price_tables(groups)
    if not groups:
        return None
    for count, cell in pending_pools:  # 병합된 한 칸의 수량을 여러 묶음이 쓰면 함께 쓰는 모집 물량입니다(합산하지 않음)
        if len(shared.get(cell, ())) > 1:
            count["allocationPoolId"] = f"pool-t{_slug(cell[0])}-c{cell[1]}-{cell[2].lower()}"
    for group in groups:
        group.pop("_plans")
    return {
        "quotes": quotes,
        "supplyGroups": groups,
        "missingFields": [],
        "coverage": {"supplyGroups": {"status": "EXTRACTED", "reason": None}},
    }


AMOUNT_IN_CELL = re.compile(r"\d[\d,]*(?:\.\d+)?")
LIMIT_WORDS = re.compile(r"한도|가능\s*금|전환\s*금")
TYPE_CODE_AREA = re.compile(r"\s*0*(\d{1,3}\.\d{2})\d{2}[A-Z]")  # LH 주택형 코드: 전용면적(소수 넷째 자리까지) + 형 문자
SPACED_AMOUNTS = re.compile(r"\d[\d,.]*\s+\d")  # 공백으로 떨어진 두 금액(위아래 두 줄이 한 칸)


def _split_shifted_pairs(values: dict[int, str], columns: list[dict[str, Any]]) -> None:
    """한 칸에 금액 둘이 붙고 바로 오른쪽 가격 칸이 비면 칸이 밀린 것입니다: 둘째 금액을 오른쪽 칸으로 옮깁니다
    (실측: LH 양산 「4,551 154,980」 | 「」 — 감액 보증금과 월임대료)."""
    prices = {c["column"] - 1 for c in columns if c["meaning"] in ("PRICE", "CONVERSION_LIMIT")}
    for c in sorted(prices):
        value = values.get(c) or ""
        amounts = AMOUNT_IN_CELL.findall(value)
        if len(amounts) == 2 and SPACED_AMOUNTS.search(value) and c + 1 in prices and not (values.get(c + 1) or "").strip():
            values[c], values[c + 1] = amounts[0], amounts[1]


def _add_price(group: dict[str, Any], column: dict[str, Any], title: str, ref: str, value: str, plan: str, stage: str) -> None:
    """공급 묶음에 표 칸 하나의 가격을 붙입니다. 같은 요금안·단계·대상이면 같은 요금안 ID를 쓰고, 금액 앞 증감 부호는 뗍니다."""
    groups_for_price = [column["targetGroup"]] if column.get("targetGroup") else group["scope"]["targetGroups"]
    plan_id = group["_plans"].setdefault((plan, stage, tuple(groups_for_price)), f"{group['id']}.plan{len(group['_plans']) + 1}")
    kind = column["priceKind"]
    group["prices"].append(
        {
            "id": f"{group['id']}.price{len(group['prices']) + 1}",
            "kind": kind,
            "scope": _scope(group["id"], list(groups_for_price), f"{plan} 요금안입니다."),
            "sourceAmount": re.sub(r"^\(?[+\-−]\)?\s*", "", value.strip()),
            "sourceUnit": _unit(column, title),
            "quoteRefs": [ref],
            "taxTreatment": "UNKNOWN",
            "vatRate": None,
            "billingPeriod": "MONTHLY" if kind == "MONTHLY_RENT" else "ONCE",
            "pricePlanId": plan_id,
            "priceStage": stage,
        }
    )


COUNT_TITLE_KINDS = (  # 앞에서부터 먼저 맞는 것. 「모집」이 없는 「호수·세대수」는 대상 주택 수(사용자 결정 2026-10-17: 대상 주택 수와 모집 수를 따로 셈)
    (re.compile(r"대기"), "WAITLIST"),
    (re.compile(r"공가"), "CURRENT_VACANCIES"),
    (re.compile(r"모집|금회|공급\s*세대|예비"), "OFFERED"),
    (re.compile(r"호\s*수|세대\s*수|건설|총\s*세대"), "TOTAL_HOUSING"),
)


def _count_kind_from_title(title: str) -> str | None:
    """열 의미 답이 countKind를 비웠을 때 열 제목으로 고르는 수량 종류. 정할 수 없으면 None(그 열은 쓰지 않음)."""
    for pattern, kind in COUNT_TITLE_KINDS:
        if pattern.search(title or ""):
            return kind
    return None


def _sign(row: list[str], limit_column: int | None) -> str:
    """전환 증감 부호: 한도 칸이 있으면 그 칸, 없으면 「(+)」「(-)」만 적힌 칸(전환 구분)."""
    if limit_column is not None and limit_column < len(row):
        return row[limit_column].strip()
    return next((v.strip() for v in row if v and SIGN_CELL.fullmatch(v.strip())), "")


def _guess_stage(column: dict[str, Any], plan: str, limit_column: int | None, first_price: int, sign: str) -> str:
    """priceStage를 비워 답한 열(실측: 인천 행복주택 「최대전환」 열): 한도 칸 오른쪽·이름에 「전환」·첫 가격 열이 아니면서 부호 행이면 전환안."""
    after_limit = limit_column is not None and column["column"] - 1 > limit_column
    if after_limit or "전환" in plan or (sign and column["column"] != first_price and "기본" not in plan and "기준" not in plan):
        return "CONVERSION_DEPOSIT_UP"  # 부호가 있으면 아래에서 증액·감액으로 나눕니다
    return "STANDARD"


def _housing_number(group: dict[str, Any]) -> float | None:
    for text in (group.get("housingTypeCode"), group.get("unitLabel")):
        found = HOUSING_NUMBER.findall(text or "")
        if found:
            return float(found[0])  # 「0039 (복층형)」「39㎡」「39 (일반형)」 → 39
    return group.get("exclusiveAreaSqm")


def _within_area_range(group: dict[str, Any], number: float) -> bool:
    """가격표가 주택형 대신 전용면적(39.70·39.87)으로 행을 나눴고, 공급표는 주택형 39에 면적 범위(39.70 ~ 39.87)를 적은 경우
    (실측 LH 부산당감 50년 공공임대: 가격 12개가 모집 수 없는 묶음으로 따로 남음). 범위 안이고 정수 부분이 주택형 번호와 같아야 합니다."""
    span = group.get("exclusiveAreaRange")
    own = _housing_number(group)
    return bool(span) and own is not None and int(own) == int(number) and span["min"] <= number <= span["max"]


def _attach_price_tables(groups: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """모집 수량 없는 가격표 행(실측: SH 「신정도시마을 39㎡」 임대료 표 한 행)을 다른 표의 같은 단지·같은 면적 번호 모집 묶음 모두에 붙입니다.
    그 행의 총 세대수(단지 전체 수)는 묶음마다 붙이면 겹치므로 붙이지 않습니다. 맞는 모집 묶음이 없으면 그 행은 그대로 둡니다."""

    def offered(group: dict[str, Any]) -> bool:
        return any(c["kind"] in SUPPLY_COUNT_KINDS for c in group["counts"])

    def table_of(group: dict[str, Any]) -> str:
        return group["id"].rsplit("-r", 1)[0]

    with_counts = [g for g in groups if offered(g)]
    complexes = {_norm(g.get("_complex")) for g in groups} - {""}
    per_table: dict[str, set[str]] = {}
    for g in groups:
        per_table.setdefault(table_of(g), set()).add(_norm(g.get("_complex")))

    def same_complex(a: str, b: str) -> bool:
        return len(complexes) <= 1 or not a or not b or a in b or b in a  # 이름 칸이 빈 행(병합 정보 없음)은 어느 단지와도 맞춤

    def single_complex(a: dict[str, Any], b: dict[str, Any]) -> bool:
        # 표가 둘이고 각각 단지 하나뿐이면 이름이 조금 달라도 같은 단지(실측: 「충북혁신 A-4블록 (천년나무4)」 ↔ 「충북혁신4 (천년나무4)」)
        return len(per_table) == 2 and len(per_table[table_of(a)] - {""}) == 1 and len(per_table[table_of(b)] - {""}) == 1

    kept: list[dict[str, Any]] = []
    for group in groups:
        if offered(group) or not group["prices"]:
            kept.append(group)
            continue
        number, name = _housing_number(group), _norm(group.get("_complex"))
        groups_of = set(group["scope"]["targetGroups"])
        targets = [
            g
            for g in with_counts
            if table_of(g) != table_of(group)
            and number is not None
            and (_housing_number(g) == number or _within_area_range(g, number))
            and (same_complex(name, _norm(g.get("_complex"))) or single_complex(group, g))
            # 대상 계층이 둘 다 적혀 있으면 맞아야 합니다(실측: 포항 전세보증금 표의 계층별 5행이 21A 묶음마다 모두 붙음)
            and _classes_match(groups_of, set(g["scope"]["targetGroups"]))
        ]
        code = _norm(group.get("housingTypeCode")).upper()
        exact = [g for g in targets if code and _norm(g.get("housingTypeCode")).upper() == code]
        targets = exact or targets  # 주택형 코드가 똑같은 행이 있으면 그 행에만(59A·59A-1·59C가 모두 59.98)
        same = [g for g in targets if {_norm(x) for x in groups_of} == {_norm(x) for x in g["scope"]["targetGroups"]}]
        targets = same or targets  # 계층 이름이 똑같은 행이 있으면 그 행에만
        if not targets:
            kept.append(group)
            continue
        for target in targets:
            by_range = _housing_number(target) != number  # 면적 범위 안의 행: 면적마다 다른 임대 조건이므로 요금안을 면적으로 나눕니다
            seen = {(p["kind"], p["sourceAmount"], p["priceStage"], tuple(p["scope"]["targetGroups"])) for p in target["prices"]}
            plans: dict[str, str] = {}
            for price in group["prices"]:
                groups_for_price = price["scope"]["targetGroups"] or target["scope"]["targetGroups"]
                if (price["kind"], price["sourceAmount"], price["priceStage"], tuple(groups_for_price)) in seen:
                    continue  # 기본 임대 조건이 두 표에 되풀이된 경우
                suffix = f"-{_slug(str(number))}" if by_range else ""
                plan_id = plans.setdefault(price["pricePlanId"], f"{target['id']}.plan-{_slug(price['pricePlanId'])}{suffix}")
                description = f"전용 {number:g}㎡ {price['scope']['description']}" if by_range else price["scope"]["description"]
                target["prices"].append(
                    {
                        **price,
                        "id": f"{target['id']}.price{len(target['prices']) + 1}",
                        "scope": _scope(target["id"], list(groups_for_price), description),
                        "pricePlanId": plan_id,
                    }
                )
            target["quoteRefs"] = list(dict.fromkeys([*target["quoteRefs"], *group["quoteRefs"]]))
    return kept


CLASS_NOTE = re.compile(r"\([^)]*\)")


def _class_words(name: str) -> set[str]:
    """계층 이름의 낱말. 괄호 설명은 빼고 「·」「,」「/」로 나눕니다: 「대학생· 청년(소득無)」 → {대학생, 청년}."""
    return {_norm(w) for w in re.split(r"[·,/]", CLASS_NOTE.sub("", name)) if _norm(w)}


def _classes_match(a: set[str], b: set[str]) -> bool:
    """공급표와 임대조건표가 같은 계층을 다르게 적은 경우를 맞춥니다(실측 LH 인천석남 행복주택: 공급표 「대학생」「청년」
    「고령자 (주거약자용外)」 ↔ 임대조건표 「대학생· 청년(소득無)」「청년(소득有)」「고령자」라 가격 56개가 모집 수 없는 묶음으로 남음)."""
    if not a or not b:
        return True
    words_a = set().union(*(_class_words(x) for x in a))
    words_b = set().union(*(_class_words(x) for x in b))
    return bool(words_a & words_b)


def complex_names(part: dict[str, Any]) -> list[str]:
    return list(dict.fromkeys(g["_complex"] for g in part["supplyGroups"] if g.get("_complex")))


def structure_note(part: dict[str, Any]) -> str:
    names = complex_names(part)
    shown = ", ".join(names[:30]) or "(표에 단지 이름 칸 없음)"
    return (
        "공급 묶음(supplyGroups)은 프로그램이 공급표에서 직접 옮겼으므로 이 호출에서는 만들지 않습니다. "
        f"위치(locations)는 원문의 단지·주택마다 만들고, 단지 이름은 공급표의 이름을 그대로 씁니다: {shown}"
    )


def _norm(text: str | None) -> str:
    return re.sub(r"[\s·.,()\[\]\-~]", "", text or "")


def link_locations(part: dict[str, Any], locations: list[dict[str, Any]]) -> None:
    """표의 단지 이름 ↔ 구조 호출의 위치. 이름이 맞는 곳이 하나면 그 위치, 단지가 하나뿐이면 모두 그 단지."""
    complexes = [loc for loc in locations if isinstance(loc, dict) and loc.get("kind") != "BUILDING"] or [loc for loc in locations if isinstance(loc, dict)]
    for group in part["supplyGroups"]:
        name = _norm(group.pop("_complex", None))
        found = [
            loc["id"]
            for loc in locations
            if isinstance(loc, dict) and name and any(n and (n in name or name in n) for n in (_norm(loc.get("complexName")), _norm(loc.get("buildingName"))))
        ]
        if len(set(found)) == 1:
            group["locationId"] = found[0]
        elif len(complexes) == 1:
            group["locationId"] = complexes[0]["id"]


def parse_mapping(raw: str) -> dict[str, Any] | None:
    try:
        value = json.loads(raw.strip().removeprefix("```json").removeprefix("```").removesuffix("```"))
    except ValueError:
        return None
    return value if isinstance(value, dict) and isinstance(value.get("tables"), list) else None
