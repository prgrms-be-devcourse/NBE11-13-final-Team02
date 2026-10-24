from datetime import date

import pytest

from mujuthaek_collector.area import sources


def test_facility_keys():
    assert sources.s3_key("BUS_STOP", date(2025, 10, 31)) == "area/BUS_STOP/2025-10-31/bus_stop.csv"
    assert sources.s3_key("SUBWAY_STATION", date(2026, 6, 18), "ALL") == "area/SUBWAY_STATION/2026-06-18/subway_station.csv"


def test_store_keys_use_english_sido_names():
    assert sources.s3_key("STORES", date(2026, 6, 30), "강원") == "area/STORES/2026-06-30/stores_gangwon.csv"
    assert sources.s3_key("STORES", date(2026, 6, 30), "전남광주") == "area/STORES/2026-06-30/stores_jeonnam_gwangju.csv"
    assert len(sources.STORES_SCOPES) == 16


@pytest.mark.parametrize(
    "source,scope",
    [
        ("SUPERMARKET", "ALL"),  # 모르는 원천
        ("STORES", None),  # 상가는 시도가 있어야 해요
        ("STORES", "광주"),  # 광주는 전남과 한 파일(전남광주)
        ("BUS_STOP", "서울"),  # 시설은 ALL만
    ],
)
def test_rejects_unknown_source_or_scope(source, scope):
    with pytest.raises(ValueError):
        sources.s3_key(source, date(2026, 6, 30), scope)


def test_keys_match_the_area_source_ready_contract():
    # contracts/events/area.source.ready.v1.json의 s3Key 패턴과 같아야 api가 받아요
    import re

    pattern = re.compile(r"^area/(SUBWAY_STATION|BUS_STOP|SCHOOL|PARK|HOSPITAL|STORES)/\d{4}-\d{2}-\d{2}/[a-z0-9_]+\.csv$")
    keys = [sources.s3_key(s, date(2026, 6, 30)) for s in sources.FILE_NAMES]
    keys += [sources.s3_key("STORES", date(2026, 6, 30), sido) for sido in sources.STORES_SCOPES]
    assert all(pattern.match(k) for k in keys)
