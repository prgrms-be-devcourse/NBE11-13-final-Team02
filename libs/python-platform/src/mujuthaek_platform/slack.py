"""슬랙 수신 웹훅(Incoming Webhook)으로 글 하나를 보내요. 어느 파이썬 워커(collector·extraction 등)에서나 같이 써요.

  from mujuthaek_platform.slack import SlackError, SlackNotifier

  notifier = SlackNotifier.from_env("SLACK_WEBHOOK_URL", env_file=SERVICE_DIR / ".env")  # 워커에 하나
  try:
      notifier.send("[collector] LH 수집이 3번 연달아 실패했어요")
  except SlackError:
      log.warning("슬랙 알림을 보내지 못했어요")  # 알림 실패가 본 작업을 막지 않게 잡아요

- 주소는 환경 변수에서 읽고, 없으면 env_file(KEY=VALUE 줄로 된 .env 파일)에서 읽어요.
  이름을 여러 개 주면 앞에서부터 찾아요
  (예: ("EXTRACTION_SLACK_WEBHOOK_URL", "SLACK_WEBHOOK_URL") — 서비스 전용 이름 먼저, 공통 이름은 나중).
- 주소가 없으면 보내지 않고(send가 False) 경고 로그를 처음 한 번만 남겨요. 로그에는 환경 변수 이름만 남겨요.
- 웹훅 주소 자체가 비밀이라 로그·예외 문구·repr 어디에도 넣지 않아요.
- 표준 라이브러리(urllib)만 쓰고, 기본 시간 제한은 5초예요. 연결 실패·시간 초과·HTTP 오류는 모두 SlackError로 내요.
- 같은 알림을 한 번만 보내는 일(예: 달마다 한 번)은 부르는 쪽이 정해요(extraction cost_alert.py는 DB 기록으로 막아요).
"""

from __future__ import annotations

import json
import logging
import os
import re
import threading
import urllib.request
from collections.abc import Sequence
from pathlib import Path

log = logging.getLogger(__name__)
DEFAULT_ENV_NAME = "SLACK_WEBHOOK_URL"
DEFAULT_TIMEOUT_SECONDS = 5.0


class SlackError(RuntimeError):
    """슬랙 전송 실패. 문구에는 오류 종류·응답 코드만 넣고 웹훅 주소는 넣지 않아요."""


def read_env_value(names: str | Sequence[str], env_file: Path | str | None = None) -> str | None:
    """이름마다 환경 변수 → env_file 순서로 찾아 처음 나온 값을 돌려줘요. 빈 값은 없는 것으로 봐요. 출력하지 않아요."""
    for name in (names,) if isinstance(names, str) else tuple(names):
        value = os.getenv(name) or _read_env_file(env_file, name)
        if value:
            return value
    return None


def _read_env_file(env_file: Path | str | None, name: str) -> str | None:
    if env_file is None or not Path(env_file).is_file():
        return None
    for line in Path(env_file).read_text(encoding="utf-8-sig").splitlines():
        match = re.match(rf"^\s*(?:export\s+)?{re.escape(name)}\s*=\s*(.*?)\s*$", line)
        if match:
            return match.group(1).strip().strip("\"'") or None
    return None


class SlackNotifier:
    """웹훅 주소 하나로 글을 보내요. 주소가 없으면 보내지 않는 알리미(enabled가 False)가 돼요."""

    def __init__(self, url: str | None, timeout: float = DEFAULT_TIMEOUT_SECONDS, env_name: str | None = None):
        self._url = url or None
        self.timeout = timeout
        self.env_name = env_name  # 주소가 없을 때 경고 로그에 남길 환경 변수 이름(값이 아니라 이름만)
        self._warned = False
        self._lock = threading.Lock()

    @classmethod
    def from_env(
        cls,
        name: str | Sequence[str] = DEFAULT_ENV_NAME,
        env_file: Path | str | None = None,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
    ) -> SlackNotifier:
        """환경 변수(없으면 env_file)에서 주소를 읽어 만들어요. 이름을 여러 개 주면 앞에서부터 찾아요."""
        names = (name,) if isinstance(name, str) else tuple(name)
        return cls(read_env_value(names, env_file), timeout=timeout, env_name=" 또는 ".join(names))

    @property
    def enabled(self) -> bool:
        return self._url is not None

    def __repr__(self) -> str:
        return f"SlackNotifier(enabled={self.enabled}, timeout={self.timeout})"  # 주소는 넣지 않아요

    def send(self, text: str) -> bool:
        """글 하나를 보내고 True. 주소가 없으면 보내지 않고 False(경고 로그는 처음 한 번만). 실패하면 SlackError."""
        if self._url is None:
            self._warn_once()
            return False
        request = urllib.request.Request(
            self._url,
            data=json.dumps({"text": text}, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:  # noqa: S310 - 운영자가 설정한 슬랙 주소만 써요
                status = response.status
        except OSError as error:  # 연결 실패·시간 초과·HTTP 오류(HTTPError도 OSError)
            raise SlackError(f"슬랙 전송 실패: {type(error).__name__}") from None
        if status >= 300:
            raise SlackError(f"슬랙 응답 {status}")
        return True

    def _warn_once(self) -> None:
        with self._lock:
            if self._warned:
                return
            self._warned = True
        log.warning("슬랙 웹훅 주소(%s)가 없어 슬랙 알림을 보내지 않아요", self.env_name or "설정 안 됨")
