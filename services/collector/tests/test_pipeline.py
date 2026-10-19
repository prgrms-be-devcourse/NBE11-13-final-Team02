"""파이프라인 전체 흐름: 가짜 출처·가짜 클라이언트·메모리 저장소로 전체 흐름을 확인해요."""

from datetime import date

from mujuthaek_collector import pipeline
from mujuthaek_collector.core.files import FileRef
from mujuthaek_collector.errors import BlockedError, ParseError
from mujuthaek_collector.sources.base import Board, Detail, ListItem, Request, Source

TODAY = date(2026, 10, 14)


class FakeSource(Source):
    code, name, provider = "dcco", "가짜 기관", "TEST"
    boards = [Board("43", "임대")]

    def __init__(self, rows):
        self.rows = rows  # [(post_key, title, posted_on)]

    def list_request(self, board, page):
        return Request(f"https://a.kr/list?page={page}")

    def parse_list(self, board, body, page_url):
        if body == "BROKEN":
            raise ParseError("목록 표 없음")
        rows = self.rows if page_url.endswith("page=1") else []
        return [ListItem(board.key, key, title, day, f"https://a.kr/view?id={key}") for key, title, day in rows]

    def parse_detail(self, item, body):
        if body == "BROKEN":
            raise ParseError("본문 없음")
        return Detail(item.title, body, [FileRef("모집공고문.pdf", f"https://a.kr/f/{item.post_key}.pdf", 1)])


def pages(client, rows, body="본문"):
    for n in (1, 2, 3):
        client.pages[f"https://a.kr/list?page={n}"] = "list"
    for key, *_ in rows:
        client.pages[f"https://a.kr/view?id={key}"] = body
        client.files[f"https://a.kr/f/{key}.pdf"] = b"%PDF-1.4 fake"


def run(source, client, store, file_store, settings, monkeypatch, **kwargs):
    monkeypatch.setattr(pipeline, "extract_text", lambda data, ext: ("공고문 글자 " * 30, "PDF 텍스트·표"))
    return pipeline.run_source(source, client, store, file_store, settings, today=TODAY, **kwargs)


ROWS = [
    ("1", "2026년 행복주택 입주자 모집공고", date(2026, 10, 1)),
    ("2", "2026년 행복주택 입주자 모집공고(2026. 10. 1.) 서류심사 대상자 발표", date(2026, 10, 10)),
    ("3", "2026년 기존주택 매입 공고", date(2026, 10, 2)),
    ("4", "2026년 영구임대 입주자 모집공고(2026. 5. 1.) 당첨자 발표", date(2026, 10, 3)),
]


def test_new_posts_are_saved_with_event(client, store, file_store, settings, monkeypatch):
    pages(client, ROWS)
    result = run(FakeSource(ROWS), client, store, file_store, settings, monkeypatch)
    assert result.status == "SUCCESS" and result.new_count == 2
    assert store.by_key("1").status == "DONE"
    assert store.by_key("2").parent_post_id == store.by_key("1").id  # 후속 안내 → 원래 모집공고
    assert store.by_key("3").status == "SKIPPED"  # 매입 공고
    assert store.by_key("4").status == "SKIPPED"  # 원래 공고가 없는 후속 안내
    event = store.events[0]
    assert event["eventType"] == "raw.notice.collected" and event["payload"]["files"][0]["sha256"]
    assert any(key.startswith("raw/dcco/1/v1/") for key in file_store.objects)


def test_event_files_point_to_uploaded_s3_objects(client, store, file_store, settings, monkeypatch):
    """이벤트의 s3Key는 실제로 올린 S3 경로와 같고, 받는 쪽은 role로 공고문을 찾아요."""
    rows = ROWS[:1]
    pages(client, rows)
    run(FakeSource(rows), client, store, file_store, settings, monkeypatch)
    (file,) = store.events[0]["payload"]["files"]
    assert file["s3Key"] == "raw/dcco/1/v1/01-모집공고문.pdf" and file["s3Key"] in file_store.objects
    assert (file["role"], file["fileType"], file["name"]) == ("NOTICE_DOC", "pdf", "모집공고문.pdf")


def test_same_list_next_time_stops_after_first_page(client, store, file_store, settings, monkeypatch):
    pages(client, ROWS)
    run(FakeSource(ROWS), client, store, file_store, settings, monkeypatch)
    client.requested.clear()
    result = run(FakeSource(ROWS), client, store, file_store, settings, monkeypatch)
    assert result.new_count == 0 and client.requested == ["https://a.kr/list?page=1"]


def test_recheck_creates_new_version_only_when_changed(client, store, file_store, settings, monkeypatch):
    rows = ROWS[:1]
    pages(client, rows)
    run(FakeSource(rows), client, store, file_store, settings, monkeypatch)
    assert run(FakeSource(rows), client, store, file_store, settings, monkeypatch, recheck=True).new_count == 0
    client.pages["https://a.kr/view?id=1"] = "정정된 본문"
    assert run(FakeSource(rows), client, store, file_store, settings, monkeypatch, recheck=True).new_count == 1
    assert store.by_key("1").latest_version == 2


def test_detail_limit_leaves_rest_pending(client, store, file_store, settings, monkeypatch):
    rows = [(str(i), f"제{i}차 국민임대 입주자 모집공고", date(2026, 10, i)) for i in range(1, 6)]
    pages(client, rows)
    limited = settings.__class__(**{**settings.__dict__, "detail_limit": 2})
    run(FakeSource(rows), client, store, file_store, limited, monkeypatch)
    assert sorted(p.status for p in store.posts.values()) == ["DONE", "DONE", "PENDING", "PENDING", "PENDING"]
    assert store.boards[("dcco", "43")].last_list_hash == ""  # 밀린 글이 있으면 목록 해시를 저장하지 않아요


def test_broken_detail_marks_failed_and_continues(client, store, file_store, settings, monkeypatch):
    rows = ROWS[:1] + [("5", "2026년 국민임대 입주자 모집공고", date(2026, 10, 5))]
    pages(client, rows)
    client.pages["https://a.kr/view?id=5"] = "BROKEN"
    result = run(FakeSource(rows), client, store, file_store, settings, monkeypatch)
    assert store.by_key("5").status == "FAILED" and store.by_key("1").status == "DONE"
    assert result.status == "SUCCESS" and any("상세를 읽지 못했어요" in w for w in result.warnings)


def test_blocked_stops_source_and_keeps_post_pending(client, store, file_store, settings, monkeypatch):
    pages(client, ROWS[:1])
    client.pages["https://a.kr/view?id=1"] = BlockedError("HTTP 429")
    result = run(FakeSource(ROWS[:1]), client, store, file_store, settings, monkeypatch)
    assert result.status == "BLOCKED" and store.by_key("1").status == "PENDING"


def test_old_posts_are_not_opened(client, store, file_store, settings, monkeypatch):
    rows = [("9", "2024년 국민임대 입주자 모집공고", date(2024, 1, 1))]
    pages(client, rows)
    run(FakeSource(rows), client, store, file_store, settings, monkeypatch)
    assert store.by_key("9").status == "SKIPPED" and "365일" in store.by_key("9").last_error


def test_empty_list_counts_towards_structure_alert(client, store, file_store, settings, monkeypatch):
    pages(client, [])
    for _ in range(3):
        result = run(FakeSource([]), client, store, file_store, settings, monkeypatch)
    assert store.boards[("dcco", "43")].empty_runs == 3 and "구조 변경 의심" in result.warnings[-1]


def test_unclear_notice_file_leaves_warning(client, store, file_store, settings, monkeypatch):
    class UnnamedFiles(FakeSource):
        def parse_detail(self, item, body):
            files = [FileRef("붙임1 신청자격.pdf", "https://a.kr/f/a.pdf", 1), FileRef("2026 행복주택.pdf", "https://a.kr/f/b.pdf", 2)]
            return Detail(item.title, body, files)

    rows = ROWS[:1]
    pages(client, rows)
    client.files["https://a.kr/f/a.pdf"] = client.files["https://a.kr/f/b.pdf"] = b"%PDF-1.4 fake"
    result = run(UnnamedFiles(rows), client, store, file_store, settings, monkeypatch)
    assert result.new_count == 1 and any("첨부 순서로 골랐어요 (붙임1 신청자격.pdf)" in w for w in result.warnings)
