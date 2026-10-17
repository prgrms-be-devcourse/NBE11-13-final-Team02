"""공고문·주택목록의 표에서 호별 임대조건(전용면적·보증금·월임대료·공급 대상 등)을 뽑아요.

기관마다 표 모양이 달라서 머리글 이름으로 칸의 뜻을 찾아요. 두 줄 머리글, 가로·세로로 합쳐진 칸,
'단위: 천원' 같은 금액 단위, 다음 쪽으로 이어지는 표를 처리해요. 말이 안 되는 값은 비우고 check에 남겨요.
"""

import re
import tempfile
import zipfile
from html import unescape
from pathlib import Path

from mujuthaek_collector.core.documents.hwp import hwp_to_html
from mujuthaek_collector.core.html import soup, table_grid

Table = tuple[int, str, list[list]]  # (쪽 번호, 그 쪽의 글자, 행들)

UNIT_FIELDS = [  # (표준 항목, 머리글 패턴) — 위에 있는 항목부터 맞춰봐요
    (
        "count",
        r"공급\s*호\s*수|공급\s*세대|모집\s*호\s*수|모집\s*인원|금회\s*(?:공급|모집)|모집\s*세대|공급\s*수|^\s*세대\s*수\s*$",
    ),
    ("target", r"공급\s*대상|입주\s*대상|대상\s*계층|^\s*계층\s*$"),
    ("rent", r"월\s*임대료|월\s*세|임대료"),
    ("deposit", r"보증금"),
    ("area", r"전용|면적"),
    ("unit", r"동\s*·?\s*호|호\s*실|세대\s*번호|^\s*호\s*수?\s*$"),
    ("address", r"주소|소재지|지번|임대\s*대상\s*명"),
    ("complex", r"단지|지구\s*명|주택\s*명|건물\s*명|명칭"),
    ("district", r"자치구|시군구|소재\s*구|^\s*구\s*$|지역"),
    ("type", r"주택\s*형|타입|형별|유형|평형"),
    ("floor", r"^\s*층\s*$|층\s*수|해당\s*층"),
    ("rooms", r"방\s*수|방\s*개수|구조"),
    ("elevator", r"승강기|엘리베이터"),
    ("parking", r"주차"),
]
NOT_AREA = re.compile(r"공용|공급\s*면적|계약|합계|대지|공유")
FILL_DOWN = {"area", "type", "complex", "district", "address", "target"}  # 세로로 합쳐진 칸은 위 행 값을 이어받음
KEY_FIELDS = {"area", "deposit", "rent", "unit", "address", "complex", "type", "count"}
IDENTITY_FIELDS = {"unit", "address", "complex", "type", "area"}
SANE = {"deposit_won": (500_000, 3_000_000_000), "rent_won": (10_000, 5_000_000), "area_m2": (5, 300)}
MONEY_WORD = re.compile(r"(?:임대\s*)?보증금|월\s*임대료|월\s*세|임대료")
GROUP_MARK = re.compile(r"^[『\[(（]?\s*[가-힣A-Za-z0-9]\s*[』\])）]?\s*군\s*:?\s*")  # '『가』군:' 같은 머리말


def cell_text(value) -> str:
    return re.sub(r"\s+", " ", str(value)).strip() if value is not None else ""


def map_header(labels: list[str]) -> dict[int, str]:
    """머리글 칸마다 표준 항목을 붙여요. 같은 항목은 처음 나온 칸 하나에만.
    면적은 '전용'이 든 칸을 먼저 쓰고, 공용·공급·계약·합계 면적 칸은 전용면적으로 쓰지 않아요."""
    mapping: dict[int, str] = {}
    used: set[str] = set()
    candidates = [i for i, label in enumerate(labels) if "전용" in label and not re.search(r"공용|보증금|임대료", label)]
    best = next((i for i in candidates if "면적" in labels[i]), candidates[0] if candidates else None)
    if best is not None:
        mapping[best], used = "area", {"area"}
    for index, label in enumerate(labels):
        if index in mapping:
            continue
        for field, pattern in UNIT_FIELDS:
            if field in used or (field == "area" and NOT_AREA.search(label)):
                continue
            if re.search(pattern, label):
                mapping[index] = field
                used.add(field)
                break
    return mapping


def is_header(row: list[str]) -> bool:
    """호별 표의 머리글: 핵심 항목 2개 이상 + 그중 하나는 호·주소·단지·주택형·면적.
    (돈 항목만 있는 전환비율표 같은 건 제외)"""
    labels = [cell_text(c) for c in row]
    numeric = sum(bool(re.fullmatch(r"[\d,.\-~ ]+", c)) for c in labels if c)
    fields = set(map_header(labels).values())
    return len(KEY_FIELDS & fields) >= 2 and bool(IDENTITY_FIELDS & fields) and numeric <= len(labels) // 3


def money_multiplier(text: str) -> int:
    if re.search(r"천\s*원", text):
        return 1000
    if re.search(r"만\s*원", text):
        return 10000
    return 1


def to_int(value: str, multiplier: int) -> int | None:
    """칸의 첫 번째 숫자만 써요. '104,454,261 / 141,561,341'처럼 숫자가 여러 개여도 이어 붙이지 않아요."""
    found = re.search(r"\d[\d,]*", value or "")
    return int(found.group(0).replace(",", "")) * multiplier if found else None


def to_float(value: str) -> float | None:
    found = re.search(r"\d+(?:\.\d+)?", (value or "").replace(",", ""))
    return float(found.group(0)) if found else None


def units_from_tables(tables: list[Table]) -> list[dict]:
    """머리글을 찾아 표준 항목으로 바꾼 호별 목록. 머리글 없이 다음 쪽으로 이어지는 표는 앞 머리글을 그대로 써요.

    이어지는 표로 보는 건 바로 다음 쪽까지이고, 금액·면적 칸이 말이 되는 행이 절반은 넘어야 해요.
    (그렇지 않으면 칸 수만 같은 다른 표 — 예: 뒤쪽의 소득 종류 표 — 라서 거기서 멈춰요)
    """
    units: list[dict] = []
    header: list[str] | None = None
    mapping: dict[int, str] = {}
    extra_targets: list[int] = []
    groups: list[tuple[str, int, int]] = []
    multipliers: dict[int, int] = {}
    page_unit = 1
    last_page = 0
    for page, page_text, raw_rows in tables:
        unit_note = re.search(r"단위[^\n]{0,15}", page_text or "")
        page_unit = money_multiplier(unit_note.group(0)) if unit_note else page_unit
        rows = [[cell_text(c) for c in row] for row in raw_rows if row and any(cell_text(c) for c in row)]
        start = 0
        continued = header is not None
        for i, row in enumerate(rows):
            if not is_header(row):
                continue
            filled, last = [], ""
            for cell in row:  # 가로로 합쳐진 칸(빈 칸)은 왼쪽 머리글을 이어받음
                last = cell or last
                filled.append(cell or last)
            labels, start = filled, i + 1
            below = rows[i + 1] if i + 1 < len(rows) else None
            two_rows = "" in row and below and len(below) == len(row) and any(below) and all(re.search(r"[가-힣A-Za-z]", c) for c in below if c)
            if two_rows:
                labels = [f"{a} {b}".strip() if b and b != a else a for a, b in zip(filled, below)]
                start = i + 2
            header, mapping, continued = labels, map_header(labels), False
            target = next((idx for idx, field in mapping.items() if field == "target"), None)
            # '공급 대상' 머리글이 가로로 합쳐져 옆 칸(예: 소득있음·소득없음)까지 덮으면 그 칸도 대상에 붙여요
            extra_targets = [idx for idx, label in enumerate(labels) if target is not None and idx != target and idx not in mapping and label == labels[target]]
            groups = money_groups(labels)
            money_columns = {idx for idx, field in mapping.items() if field in ("deposit", "rent")} | {i for _, d, r in groups for i in (d, r)}
            multipliers = {idx: money_multiplier(labels[idx]) if money_multiplier(labels[idx]) > 1 else page_unit for idx in money_columns}
            break
        if header is None:
            continue
        if continued and page > last_page + 1:
            header = None
            continue
        above: dict[int, str] = {}
        table_units: list[dict] = []
        for row in rows[start:]:
            if len(row) != len(header) or is_header(row):
                continue
            first = next((c for c in row if c), "")
            if re.fullmatch(r"(?:소|합|총)?\s*계|합\s*계|총\s*계", first):
                continue
            unit: dict = {"page": page}
            for idx, field in mapping.items():
                value = row[idx]
                if not value and field in FILL_DOWN and above.get(idx):
                    value = above[idx]
                above[idx] = value
                if field in ("deposit", "rent"):
                    unit[field + "_won"] = to_int(value, multipliers.get(idx, 1))
                elif field == "area":
                    unit["area_m2"] = to_float(value)
                elif field == "count":
                    unit["count"] = to_int(value, 1)
                elif field == "target":
                    unit["target"] = " ".join(v for v in [value, *(row[j] for j in extra_targets)] if v)
                else:
                    unit[field] = value
            raw: dict[str, str] = {}
            for number, (label, value) in enumerate(zip(header, row), 1):
                key = label or f"칸{number}"
                while key in raw:
                    key += "+"
                raw[key] = value
            unit["raw"] = raw
            row_units = [unit]
            if groups:  # 신청자 군마다 금액이 다르면 군마다 한 줄씩 (공급 호수는 군끼리 함께 쓰는 수라 줄마다 그대로)
                row_units = [
                    {
                        **unit,
                        "target": " ".join(v for v in (unit.get("target"), name) if v),
                        "deposit_won": to_int(row[deposit], multipliers[deposit]),
                        "rent_won": to_int(row[rent], multipliers[rent]),
                    }
                    for name, deposit, rent in groups
                ]
            for unit in row_units:
                for key, (low, high) in SANE.items():
                    if unit.get(key) is not None and not low <= unit[key] <= high:
                        unit["check"] = [*unit.get("check", []), f"{key}={unit[key]}"]
                        unit[key] = None
                if any(unit.get(k) for k in ("area_m2", "deposit_won", "rent_won", "unit", "address")):
                    table_units.append(unit)
        if continued and not looks_like_units(table_units, mapping):
            header = None
            continue
        units.extend(table_units)
        last_page = page
    unit_values = [u.get("unit", "") for u in units if u.get("unit")]
    if unit_values and all(re.fullmatch(r"[\d,]+", v) for v in unit_values):
        for u in units:  # '호수' 칸이 숫자뿐이면 호실이 아니라 공급 호수
            if u.get("unit"):
                u.setdefault("count", to_int(u.pop("unit"), 1))
    if units and sum(bool(u.get("check")) for u in units) > len(units) / 2:
        return []  # 절반 넘게 이상한 값이면 호별 표가 아니라고 보고 버림
    return units


def money_groups(labels: list[str]) -> list[tuple[str, int, int]]:
    """'『가』군: 수급자 등 임대보증금 / 월임대료', '『나』군: 일반 등 …'처럼 신청자 군마다 보증금·월임대료 칸이 따로 있으면
    [(군 이름, 보증금 칸, 월임대료 칸)]. 보증금·월임대료가 둘 다 있는 군이 2개 이상일 때만 나눠요.
    (보증금 없이 월임대료만 여러 칸인 감면표 같은 건 나누지 않아요)"""
    found: dict[str, dict[str, int]] = {}
    for idx, label in enumerate(labels):
        word = MONEY_WORD.search(label)
        if not word:
            continue
        name = GROUP_MARK.sub("", label[: word.start()]).strip(" :·-")
        kind = "deposit" if "보증금" in word.group(0) else "rent"
        found.setdefault(name, {}).setdefault(kind, idx)
    groups = [(name, cols["deposit"], cols["rent"]) for name, cols in found.items() if name and len(cols) == 2]
    return groups if len(groups) >= 2 else []


def looks_like_units(units: list[dict], mapping: dict[int, str]) -> bool:
    """머리글 없이 이어지는 표가 정말 호별 표인지: 금액·면적 칸이 있으면 그 값이 말이 되는 행이 절반 이상."""
    if not units or not {"area", "deposit", "rent"} & set(mapping.values()):
        return bool(units)
    sane = sum(any(u.get(k) is not None for k in ("area_m2", "deposit_won", "rent_won")) for u in units)
    return sane * 2 >= len(units)


def read_tables(data: bytes, ext: str) -> list[Table]:
    """파일에서 표를 [(쪽, 쪽 글자, 행들)]로 꺼내요."""
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / f"source.{ext}"
        path.write_bytes(data)
        if ext == "pdf":
            import pdfplumber

            out: list[Table] = []
            with pdfplumber.open(path) as pdf:
                for number, page in enumerate(pdf.pages, 1):
                    text = page.extract_text() or ""
                    for table in page.extract_tables() or []:
                        out.append((number, text, table))
            return out
        if ext in ("xlsx", "xlsm"):
            import openpyxl

            book = openpyxl.load_workbook(path, read_only=True, data_only=True)
            return [(i, sheet.title, [list(r) for r in sheet.iter_rows(values_only=True)]) for i, sheet in enumerate(book.worksheets, 1)]
        if ext == "xls":  # 옛 엑셀 형식. 빈 칸은 None, 정수는 int로 바꿔 xlsx와 같은 모양으로 넘겨요
            import xlrd

            book = xlrd.open_workbook(path)
            return [(i, sheet.name, [[xls_cell(c) for c in sheet.row(r)] for r in range(sheet.nrows)]) for i, sheet in enumerate(book.sheets(), 1)]
        if ext == "hwpx":
            with zipfile.ZipFile(path) as archive:
                xml = "".join(archive.read(n).decode("utf-8", errors="replace") for n in sorted(archive.namelist()) if re.match(r"Contents/section\d+\.xml", n))
            out = []
            for table in re.findall(r"<hp:tbl\b[\s\S]*?</hp:tbl>", xml):
                rows = [
                    [unescape(re.sub(r"<[^>]+>", " ", cell)) for cell in re.findall(r"<hp:tc\b[\s\S]*?</hp:tc>", row)]
                    for row in re.findall(r"<hp:tr\b[\s\S]*?</hp:tr>", table)
                ]
                out.append((1, unescape(re.sub(r"<[^>]+>", " ", xml))[:2000], rows))
            return out
        if ext == "hwp":
            html, _ = hwp_to_html(path)
            if not html:
                return []
            document = soup(html)
            out = []
            for table in document.find_all("table"):
                if table.find("table") is not None:
                    continue  # 안에 다른 표가 없는 표만
                context = " ".join(s.strip() for s in table.find_all_previous(string=True, limit=60)[::-1])[-400:]
                out.append((1, context, table_grid(table)))
            return out
    return []


def xls_cell(cell):
    import xlrd

    if cell.ctype in (xlrd.XL_CELL_EMPTY, xlrd.XL_CELL_BLANK):
        return None
    if cell.ctype == xlrd.XL_CELL_NUMBER and float(cell.value).is_integer():
        return int(cell.value)  # xls는 숫자를 모두 실수로 줘요 (12000.0 → 12000)
    return cell.value


def summarize(units: list[dict]) -> str:
    """로그용 한 줄 요약: '15행, 전용 59.34㎡~59.98㎡, 보증금 72,000,000원~112,000,000원'."""

    def span(key: str, unit: str) -> str | None:
        values = [u[key] for u in units if u.get(key)]
        if not values:
            return None
        low, high = min(values), max(values)
        fmt = (lambda v: f"{v:,.2f}{unit}") if key == "area_m2" else (lambda v: f"{v:,}{unit}")
        return fmt(low) if low == high else f"{fmt(low)}~{fmt(high)}"

    parts = [f"{len(units)}행"]
    for key, label, unit in (("area_m2", "전용", "㎡"), ("deposit_won", "보증금", "원"), ("rent_won", "월임대료", "원")):
        value = span(key, unit)
        if value:
            parts.append(f"{label} {value}")
    return ", ".join(parts)
