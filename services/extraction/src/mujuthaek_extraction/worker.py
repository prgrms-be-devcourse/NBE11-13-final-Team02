"""extraction 워커: 큐 소비 → 처리 → 저장·아웃박스, 아웃박스 발행기(2초), 검수 API(:8092).

큐 (팀 이벤트 흐름 문서)
  교환기 mujuthaek.events(topic) ── raw.notice.collected ──▶ 큐 mujuthaek.raw.collected (extraction이 선언)
  처리 실패·형식 오류 메시지는 재시도 없이 mujuthaek.dlx → mujuthaek.raw.collected.dlq
  prefetch 1: 한 번에 한 건만 처리합니다(AI 호출이 길어서). 처리는 별도 스레드에서 하고 ack는 연결 스레드로 넘깁니다(pika 하트비트 유지).
  DB 연결은 메시지마다 새로 엽니다. 예전에는 하나를 계속 써서 DB가 한 번 끊기면 이후 메시지가 전부 .dlq로 갔습니다(검토 2026-10-16).
  DB를 못 쓰면 .dlq가 아니라 잠시 뒤 큐로 되돌립니다(자료 문제가 아니라 일시 장애이므로).
  큐 연결이 끊겨 같은 메시지가 다시 오면, 같은 이벤트를 처리 중인 스레드가 끝나기를 기다렸다가 이미 끝났는지 다시 봅니다(AI 비용 두 번 방지).
"""

from __future__ import annotations

import functools
import json
import logging
import threading
import time
from contextlib import contextmanager
from datetime import UTC, datetime
from typing import Any

from mujuthaek_extraction.assemble.regions import RegionCodeTable
from mujuthaek_extraction.config import Settings
from mujuthaek_extraction.health import Health
from mujuthaek_extraction.intake import LocalDirSourceReader
from mujuthaek_extraction.model.factory import ModelFactory
from mujuthaek_extraction.pipeline import Outcome, process
from mujuthaek_extraction.review_api import ReviewService, serve
from mujuthaek_extraction.s3_reader import S3SourceReader
from mujuthaek_extraction.store import DuplicateEvent, JobStore
from mujuthaek_platform import broker, db, outbox
from mujuthaek_platform.events import new_event

log = logging.getLogger(__name__)
EXCHANGE = outbox.EXCHANGE
DLX = "mujuthaek.dlx"
QUEUE = "mujuthaek.raw.collected"
DLQ = QUEUE + ".dlq"
ROUTING_KEY = "raw.notice.collected"
DB_RETRY_SECONDS = 30  # DB 장애 때 큐로 되돌리기 전 기다림(바로 되돌리면 같은 메시지가 쉬지 않고 돎)


def _db_errors() -> tuple[type[BaseException], ...]:
    import psycopg

    return (psycopg.OperationalError, psycopg.InterfaceError)


def declare(channel) -> None:
    channel.exchange_declare(exchange=EXCHANGE, exchange_type="topic", durable=True)
    channel.exchange_declare(exchange=DLX, exchange_type="topic", durable=True)
    channel.queue_declare(queue=DLQ, durable=True)
    channel.queue_bind(queue=DLQ, exchange=DLX, routing_key=ROUTING_KEY)
    channel.queue_declare(queue=QUEUE, durable=True, arguments={"x-dead-letter-exchange": DLX})
    channel.queue_bind(queue=QUEUE, exchange=EXCHANGE, routing_key=ROUTING_KEY)


class Processor:
    """메시지 본문 1건 처리. 큐 없이도(파일·다시 처리) 같은 경로를 씁니다."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.models = ModelFactory(settings)
        if settings.local_files_dir:  # S3 키가 정해지기 전 시험용 PDF 폴더
            self.reader = LocalDirSourceReader(settings.local_files_dir)
        else:
            self.reader = S3SourceReader(settings.s3_bucket, settings.s3_endpoint, settings.s3_access_key, settings.s3_secret_key)
        self.regions = RegionCodeTable.from_csv(settings.region_table, settings.region_table_date)
        self._running: dict[str, threading.Event] = {}  # 처리 중인 eventId → 끝나면 켜짐
        self._running_lock = threading.Lock()

    def run(self, raw_event: dict[str, Any]) -> Outcome:
        try:
            return process(
                raw_event,
                models=self.models.for_notice(),
                reader=self.reader,
                regions=self.regions,
                clock=lambda: datetime.now(UTC),
                new_event=new_event,
                require_pdf=self.settings.require_pdf,
                method=self.settings.extraction_method,
            )
        except Exception as error:  # 예상 못 한 결함도 작업 기록에 남기고 다시 처리할 수 있게 둡니다
            log.exception("처리 중 오류")
            return Outcome("FAILED", error=f"처리 중 오류: {type(error).__name__}: {str(error)[:300]}")

    def handle(self, store: JobStore, raw_event: dict[str, Any]) -> str | None:
        """처리하고 저장합니다. 이미 끝난 이벤트면 None."""
        event_id = raw_event.get("eventId")
        if not isinstance(event_id, str):
            return self._handle(store, raw_event, event_id)
        while True:
            with self._running_lock:
                other = self._running.get(event_id)
                if other is None:
                    mine = self._running[event_id] = threading.Event()
                    break
            other.wait()  # 같은 이벤트를 다른 스레드가 처리 중: 끝난 뒤 다시 확인합니다
        try:
            return self._handle(store, raw_event, event_id)
        finally:
            with self._running_lock:
                self._running.pop(event_id, None)
            mine.set()

    def _handle(self, store: JobStore, raw_event: dict[str, Any], event_id: Any) -> str | None:
        if isinstance(event_id, str) and store.already_processed(event_id):
            log.info("이미 처리한 이벤트", extra={"eventId": event_id})
            return None
        outcome = self.run(raw_event)
        try:
            job_id = store.save(raw_event, outcome)
        except DuplicateEvent:
            log.info("같은 이벤트를 다른 처리가 먼저 끝내 저장하지 않았습니다", extra={"eventId": event_id})
            return None
        log.info(
            "작업 저장",
            extra={"jobId": job_id, "eventId": event_id, "status": outcome.status, "model": outcome.model_used, "costUsd": round(outcome.cost_usd, 4)},
        )
        return job_id


@contextmanager
def store_session(settings: Settings):
    conn = db.connect(settings.db_url)
    try:
        yield JobStore(conn)
    finally:
        conn.close()


def relay_loop(settings: Settings, stop: threading.Event, health: Health | None = None) -> None:
    """stop이 설정될 때까지 아웃박스 이벤트를 relay_seconds마다 발행합니다. 오류가 나면 5초 뒤 다시 연결합니다."""
    while not stop.is_set():
        try:
            conn = db.connect(settings.db_url)
            conn.autocommit = True
            connection = broker.connect(settings.rabbitmq_url)
            channel = outbox.open_channel(connection)  # 확인 모드: 브로커가 받았다고 확인한 것만 "보냄"으로 기록
            while not stop.is_set():
                sent = outbox.relay_once(conn, channel)
                if health:
                    health.beat("relay")
                if sent:
                    log.info("이벤트 발행", extra={"count": sent})
                connection.process_data_events(time_limit=settings.relay_seconds)
        except Exception:
            log.exception("아웃박스 발행 오류. 5초 뒤 다시 연결합니다")
            stop.wait(5)


CONSUMER_BEAT_SECONDS = 30


def consume(settings: Settings, processor: Processor, health: Health | None = None) -> None:
    """큐를 선언하고 메시지를 한 번에 하나씩 받아 처리합니다. 처리는 별도 스레드에서 하고 확인 응답은 연결 스레드로 넘깁니다."""
    connection = broker.connect(settings.rabbitmq_url)
    channel = connection.channel()
    declare(channel)
    channel.basic_qos(prefetch_count=1)

    def finish(delivery_tag: int, decision: str) -> None:
        if decision == "ack":
            channel.basic_ack(delivery_tag)
        elif decision == "requeue":
            channel.basic_nack(delivery_tag, requeue=True)  # DB 장애: 큐로 되돌려 나중에 다시
        else:
            channel.basic_nack(delivery_tag, requeue=False)  # 재시도 없이 .dlq로

    def work(delivery_tag: int, body: bytes) -> None:
        """메시지 하나를 처리하고 결과에 따라 ack, DB 장애면 requeue, JSON이 아니거나 저장 실패면 .dlq로 정합니다."""
        decision = "ack"
        try:
            raw_event = json.loads(body)
            if not isinstance(raw_event, dict):
                raise ValueError("이벤트가 객체가 아닙니다.")
            with store_session(settings) as store:
                processor.handle(store, raw_event)
        except _db_errors():
            log.exception("DB를 쓸 수 없어 잠시 뒤 큐로 되돌립니다")
            time.sleep(DB_RETRY_SECONDS)
            decision = "requeue"
        except ValueError:
            log.warning("JSON 이벤트가 아니어서 .dlq로 보냅니다")
            decision = "dlq"
        except Exception:
            log.exception("저장 실패. .dlq로 보냅니다")
            decision = "dlq"
        connection.add_callback_threadsafe(functools.partial(finish, delivery_tag, decision))

    def on_message(_channel, method, _properties, body: bytes) -> None:
        threading.Thread(target=work, args=(method.delivery_tag, body), daemon=True).start()

    def tick() -> None:  # 연결 스레드가 살아 메시지를 받을 수 있는 동안 신호를 보냅니다(AI 처리는 다른 스레드라 막히지 않음)
        if health:
            health.beat("consumer")
        connection.call_later(CONSUMER_BEAT_SECONDS, tick)

    channel.basic_consume(queue=QUEUE, on_message_callback=on_message)
    log.info("큐 소비 시작", extra={"queue": QUEUE, "model": processor.models.describe()})
    tick()
    channel.start_consuming()


def retry_loop(settings: Settings, processor: Processor, stop: threading.Event, health: Health | None = None) -> None:
    """자료 대기(WAITING_SOURCE)·실패(FAILED)를 점점 길게 기다리며 다시 처리합니다(최대 settings.max_attempts번).
    예전엔 사람이 retry 명령을 돌려야만 다시 처리돼, PDF가 1분 늦게 올라와도 공고가 계속 대기에 남았습니다(검토 2026-10-16)."""
    while not stop.wait(settings.retry_seconds):
        if health:
            health.beat("retry")
        try:
            with store_session(settings) as store:
                events = store.retryable_events(limit=10, max_attempts=settings.max_attempts, base_delay_seconds=settings.retry_seconds)
                for raw_event in events:
                    processor.handle(store, raw_event)
        except Exception:
            log.exception("다시 처리 중 오류. 다음 차례에 다시 봅니다")


def run(settings: Settings) -> None:
    """발행·다시 처리·검수 API를 백그라운드 스레드로 띄우고, 큐 소비를 연결이 끊길 때마다 다시 붙여 계속 돌립니다."""
    processor = Processor(settings)
    stop = threading.Event()
    # 부분마다 이 초 안에 신호가 없으면 /health가 503 (발행기는 2초마다, 소비자는 30초마다, 다시 처리는 retry_seconds마다 신호)
    health = Health({"consumer": 120, "relay": 120, "retry": settings.retry_seconds * 2 + 60})
    threading.Thread(target=relay_loop, args=(settings, stop, health), daemon=True, name="relay").start()
    threading.Thread(target=retry_loop, args=(settings, processor, stop, health), daemon=True, name="retry").start()
    service = ReviewService(lambda: store_session(settings), new_event)
    server = serve(service, settings.review_host, settings.review_port, settings.review_token, health)
    threading.Thread(target=server.serve_forever, daemon=True, name="review-api").start()
    log.info("검수 API 시작", extra={"host": settings.review_host, "port": settings.review_port})
    while True:  # 큐 연결이 끊기면 다시 붙습니다
        try:
            consume(settings, processor, health)
        except KeyboardInterrupt:
            stop.set()
            server.shutdown()
            return
        except Exception:
            log.exception("큐 연결 오류. 5초 뒤 다시 연결합니다")
            time.sleep(5)
