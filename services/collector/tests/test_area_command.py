import csv
from datetime import date
from pathlib import Path

from mujuthaek_collector.area import command, sources
from mujuthaek_collector.area.upload import sha256_of
from tests.fakes import MemoryFileStore

MANIFEST = Path(__file__).resolve().parents[1] / "area" / "area_manifest.csv"


class MemoryOutbox:
    """area events.record가 쓰는 만큼만: execute(insert … on conflict do nothing).rowcount"""

    def __init__(self):
        self.events = {}

    def execute(self, sql, params):
        new = params[0] not in self.events
        self.events.setdefault(params[0], params[3].obj)
        return type("Cursor", (), {"rowcount": 1 if new else 0})()


def write_csv(path: Path, lines: int) -> Path:
    path.write_text("정류장번호,정류장명,위도,경도,도시코드,도시명\n" + "1,시청,37.56,126.97,11,서울\n" * lines, encoding="utf-8")
    return path


def test_upload_records_one_event_and_a_second_run_records_nothing(tmp_path):
    files, outbox = MemoryFileStore(), MemoryOutbox()
    path = write_csv(tmp_path / "국토교통부_전국 버스정류장 위치정보_20251031.csv", 3)

    first = command.upload_file(files, outbox, "BUS_STOP", date(2025, 10, 31), path)
    second = command.upload_file(files, outbox, "BUS_STOP", date(2025, 10, 31), path)

    assert (first.uploaded, first.recorded) == (1, 1)
    assert (second.uploaded, second.recorded) == (0, 0)
    [event] = outbox.events.values()
    assert event["payload"] == {
        "source": "BUS_STOP",
        "scope": "ALL",
        "s3Key": "area/BUS_STOP/2025-10-31/bus_stop.csv",
        "sha256": sha256_of(path),
        "dataDate": "2025-10-31",
        "fileName": path.name,
        "rows": 3,
    }


def test_manifest_records_events_for_files_already_in_s3(tmp_path):
    files, outbox = MemoryFileStore(), MemoryOutbox()
    manifest = tmp_path / "manifest.csv"
    rows = [
        ["BUS_STOP", "ALL", "area/BUS_STOP/2025-10-31/bus_stop.csv", "a" * 64, "2025-10-31", "버스.csv", "3"],
        ["STORES", "강원", "area/STORES/2026-06-30/stores_gangwon.csv", "b" * 64, "2026-06-30", "상가_강원.csv", "7"],
        ["STORES", "경기", "area/STORES/2026-06-30/stores_gyeonggi.csv", "c" * 64, "2026-06-30", "상가_경기.csv", "7"],
    ]
    with manifest.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["source", "scope", "s3Key", "sha256", "dataDate", "fileName", "rows"])
        writer.writerows(rows)
    files.objects = {r[2]: b"" for r in rows}
    files.metadatas = {rows[0][2]: {"sha256": "a" * 64}, rows[1][2]: {"sha256": "b" * 64}, rows[2][2]: {"sha256": "x" * 64}}

    result = command.from_manifest(files, outbox, manifest)

    assert result.recorded == 2
    assert result.problems == ["area/STORES/2026-06-30/stores_gyeonggi.csv: S3의 sha256이 목록과 달라요"]
    assert command.from_manifest(files, outbox, manifest).recorded == 0  # 두 번째는 같은 eventId라 기록 안 함


def test_the_manifest_in_the_repo_follows_the_key_rule():
    # 2026-10-22에 실제로 올린 21개 목록
    with MANIFEST.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 21
    for row in rows:
        assert row["s3Key"] == sources.s3_key(row["source"], date.fromisoformat(row["dataDate"]), row["scope"])


class RecordingChannel:
    def __init__(self):
        self.calls = []

    def __getattr__(self, name):
        return lambda **kwargs: self.calls.append((name, kwargs))


def test_declares_the_api_queue_with_the_same_arguments():
    channel = RecordingChannel()
    command.declare_receiver(channel)
    assert (
        "queue_declare",
        {"queue": "mujuthaek.area.source-ready", "durable": True, "arguments": {"x-dead-letter-exchange": "mujuthaek.dlx"}},
    ) in channel.calls
    assert ("queue_bind", {"queue": "mujuthaek.area.source-ready", "exchange": "mujuthaek.events", "routing_key": "area.source.ready"}) in channel.calls
