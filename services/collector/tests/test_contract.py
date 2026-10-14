"""수집기가 만드는 이벤트가 contracts/events/raw.notice.collected.v1.json과 맞는지."""

from datetime import date
from uuid import UUID

from mujuthaek_collector.core.publisher import MAX_TEXT_CHARS, build_collected_event
from mujuthaek_platform.contracts import validate_event


def make(**overrides):
    values = dict(
        raw_post_id=UUID("01929a3e-0000-7000-8000-000000000001"),
        version=1,
        source_code="dcco",
        provider="DCCO",
        kind="NOTICE",
        title="2026년 국민임대 입주자 모집공고",
        posted_on=date(2026, 10, 1),
        detail_url="https://www.dcco.kr/web/board/view.do?mId=43&brdIdx=1",
        parent_post_id=None,
        full_text="제목: …",
        text_key=None,
        units=[{"type": "26A", "area_m2": 26.37, "deposit_won": 12_000_000, "rent_won": 150_000, "raw": {"주택형": "26A"}}],
        files=[{"name": "모집공고문.pdf", "download_url": "https://www.dcco.kr/web/board/download.do?fidx=1", "file_hash": "ab" * 32}],
    )
    values.update(overrides)
    return build_collected_event(**values)


def test_event_matches_contract():
    event = make()
    validate_event(event)
    assert event["payload"]["units"][0] == {"houseType": "26A", "areaM2": 26.37, "depositWon": 12_000_000, "rentWon": 150_000}


def test_follow_up_with_parent_and_long_text():
    event = make(
        kind="FOLLOW_UP", parent_post_id=UUID("01929a3e-0000-7000-8000-000000000002"), full_text="가" * (MAX_TEXT_CHARS + 10), text_key="text/dcco/1/v1.txt"
    )
    validate_event(event)
    assert len(event["payload"]["text"]) == MAX_TEXT_CHARS and event["payload"]["textKey"] == "text/dcco/1/v1.txt"
