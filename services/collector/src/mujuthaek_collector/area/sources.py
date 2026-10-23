"""area 원천 6종과 S3 키 규칙.

S3 키: area/{원천}/{기준일}/{영문 파일 이름}.csv  (예: area/STORES/2026-06-30/stores_gangwon.csv)
상가정보는 시도 파일 하나가 적재 한 번이라 범위(scope)가 시도 이름이고, 나머지는 ALL이에요.
파일 이름을 영문으로 두는 건 LANG이 없는 서버·컨테이너에서도 키를 그대로 다룰 수 있게 하려는 거예요.
"""

from datetime import date

ALL = "ALL"

# 원천 → 영문 파일 이름 (상가는 시도마다 따로라 STORES_SCOPES로)
FILE_NAMES = {
    "SUBWAY_STATION": "subway_station",
    "BUS_STOP": "bus_stop",
    "SCHOOL": "school",
    "PARK": "park",
    "HOSPITAL": "hospital",
}
STORES = "STORES"
SOURCES = (*FILE_NAMES, STORES)

# 상가(상권)정보 시도 파일 이름의 시도 → 영문 이름 (소상공인시장진흥공단 2026-06 기준 16개. 광주는 전남과 한 파일)
STORES_SCOPES = {
    "강원": "gangwon",
    "경기": "gyeonggi",
    "경남": "gyeongnam",
    "경북": "gyeongbuk",
    "대구": "daegu",
    "대전": "daejeon",
    "부산": "busan",
    "서울": "seoul",
    "세종": "sejong",
    "울산": "ulsan",
    "인천": "incheon",
    "전남광주": "jeonnam_gwangju",
    "전북": "jeonbuk",
    "제주": "jeju",
    "충남": "chungnam",
    "충북": "chungbuk",
}


def check_scope(source: str, scope: str | None) -> str:
    """원천과 범위가 맞는지 보고 범위를 돌려줘요. 상가는 시도 이름, 나머지는 ALL(비우면 ALL)."""
    if source not in SOURCES:
        raise ValueError(f"모르는 area 원천이에요: {source} (가능: {', '.join(SOURCES)})")
    if source == STORES:
        if scope not in STORES_SCOPES:
            raise ValueError(f"상가정보는 시도 이름이 있어야 해요: {scope} (가능: {', '.join(STORES_SCOPES)})")
        return scope
    if scope not in (None, "", ALL):
        raise ValueError(f"{source}는 범위가 ALL이에요: {scope}")
    return ALL


def file_name(source: str, scope: str | None = None) -> str:
    scope = check_scope(source, scope)
    base = f"stores_{STORES_SCOPES[scope]}" if source == STORES else FILE_NAMES[source]
    return base + ".csv"


def s3_key(source: str, data_date: date, scope: str | None = None) -> str:
    return f"area/{source}/{data_date.isoformat()}/{file_name(source, scope)}"
