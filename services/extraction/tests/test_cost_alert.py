"""월 상한 80% 슬랙 경고(요구 2-10)의 DB 밖 부분: 슬랙 전송·경고 글·주소가 없을 때·오류가 추출을 막지 않음. 네트워크 없음.
DB에서 분야별 집계·달마다 한 번 보내기는 test_store_postgres."""

import json
import logging
import urllib.error
from contextlib import contextmanager

import pytest

from mujuthaek_extraction import cost_alert
from mujuthaek_extraction.config import Settings
from mujuthaek_extraction.cost_alert import CostAlert, alert_text, slack_sender

COSTS = {
    "month": "2026-10",
    "paidCalls": 3,
    "costUsd": 500.0,
    "budgetUsd": 600.0,
    "budgetRatio": 0.8333,
    "fields": [{"field": "HOUSING", "paidCalls": 2, "costUsd": 450.0}, {"field": "STARTUP_SUPPORT", "paidCalls": 1, "costUsd": 50.0}],
}


class FakeResponse:
    def __init__(self, status):
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def test_slack_sender_posts_json_text_with_short_timeout(monkeypatch):
    sent = []

    def fake_urlopen(request, timeout):
        sent.append((request.full_url, request.get_method(), json.loads(request.data), request.headers["Content-type"], timeout))
        return FakeResponse(200)

    monkeypatch.setattr(cost_alert.urllib.request, "urlopen", fake_urlopen)
    slack_sender("https://hooks.example.invalid/T/B/x")("안녕")
    assert sent == [("https://hooks.example.invalid/T/B/x", "POST", {"text": "안녕"}, "application/json", cost_alert.SLACK_TIMEOUT_SECONDS)]


def test_slack_failure_raises_without_the_webhook_address(monkeypatch):
    def refused(request, timeout):
        raise urllib.error.URLError("연결 거부")

    monkeypatch.setattr(cost_alert.urllib.request, "urlopen", refused)
    with pytest.raises(RuntimeError) as error:
        slack_sender("https://hooks.example.invalid/T/B/secret")("x")
    assert "secret" not in str(error.value)


def test_alert_text_has_month_ratio_and_each_field():
    text = alert_text(COSTS)
    assert "2026-10" in text and "83%" in text and "$500.00 / $600.00" in text
    assert "- HOUSING: 2회, $450.00" in text and "- STARTUP_SUPPORT: 1회, $50.00" in text


class FakeConn:
    @contextmanager
    def transaction(self):
        yield


def test_without_webhook_url_only_one_warning_is_logged(monkeypatch, caplog):
    monkeypatch.setattr(cost_alert, "monthly_costs", lambda conn, month=None, budget_usd=None: COSTS)
    alert = CostAlert.from_url(600.0, None)
    with caplog.at_level(logging.WARNING, logger="mujuthaek_extraction.cost_alert"):
        assert not alert.check(FakeConn()) and not alert.check(FakeConn())
    assert len(caplog.records) == 1  # 처리마다 같은 경고가 쌓이지 않게 한 번만
    assert "EXTRACTION_SLACK_WEBHOOK_URL" in caplog.records[0].message


def test_errors_while_checking_do_not_break_extraction(monkeypatch):
    def broken(conn, month=None, budget_usd=None):
        raise RuntimeError("DB 끊김")

    monkeypatch.setattr(cost_alert, "monthly_costs", broken)
    assert CostAlert(600.0, lambda text: None).check(FakeConn()) is False


def test_ledger_close_checks_the_alert_only_after_spending():
    from mujuthaek_extraction.model.spend import SpendLedger

    class Conn:
        autocommit, closed = False, False

        def close(self):
            self.closed = True

    class Alert:
        def __init__(self):
            self.checked = 0

        def check(self, conn):
            self.checked += 1

    alert, conn = Alert(), Conn()
    SpendLedger(conn, daily_usd=1, monthly_usd=1, alert=alert).close()
    assert alert.checked == 0 and conn.closed  # 비용을 적지 않은 처리는 확인하지 않음
    ledger = SpendLedger(Conn(), daily_usd=1, monthly_usd=1, alert=alert)
    ledger._charged = True  # reserve가 장부에 적은 처리(실제 DB 판은 test_store_postgres)
    ledger.close()
    assert alert.checked == 1 and ledger.conn.closed


def test_webhook_url_comes_from_env_and_is_not_in_settings_repr(monkeypatch):
    monkeypatch.setenv("EXTRACTION_SLACK_WEBHOOK_URL", "https://hooks.example.invalid/T/B/hookToken123")
    settings = Settings.from_env()
    assert settings.slack_webhook_url.endswith("/hookToken123") and "hookToken123" not in repr(settings)
    monkeypatch.setenv("EXTRACTION_SLACK_WEBHOOK_URL", "")
    assert Settings.from_env().slack_webhook_url is None


def test_webhook_url_falls_back_to_service_env_file(monkeypatch, tmp_path):
    from mujuthaek_extraction import config

    (tmp_path / ".env").write_text("OPENAI_API_KEY=x\nEXTRACTION_SLACK_WEBHOOK_URL=https://hooks.example.invalid/T/B/fileToken\n", encoding="utf-8")
    monkeypatch.setattr(config, "SERVICE_DIR", tmp_path)
    monkeypatch.delenv("EXTRACTION_SLACK_WEBHOOK_URL", raising=False)
    assert Settings.from_env().slack_webhook_url.endswith("/fileToken")
    monkeypatch.setenv("EXTRACTION_SLACK_WEBHOOK_URL", "https://hooks.example.invalid/T/B/envToken")
    assert Settings.from_env().slack_webhook_url.endswith("/envToken")  # 환경 변수가 먼저
