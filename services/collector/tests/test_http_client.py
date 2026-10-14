import httpx
import pytest

from mujuthaek_collector.core.http_client import PoliteClient
from mujuthaek_collector.errors import BlockedError, FileRejected


def make_client(handler):
    return PoliteClient(delay_seconds=0, transport=httpx.MockTransport(handler), sleep=lambda _: None)


def robots(body="User-agent: *\nDisallow: /upload/\n"):
    return httpx.Response(200, text=body)


def test_redirect_to_disallowed_path_is_rejected():
    def handler(request):
        if request.url.path == "/robots.txt":
            return robots()
        if request.url.path == "/app/com/file/innoFD.do":
            return httpx.Response(302, headers={"Location": "/upload/secret.pdf"})
        return httpx.Response(200, content=b"%PDF")

    with make_client(handler) as client, pytest.raises(FileRejected):
        client.download("https://www.i-sh.co.kr/app/com/file/innoFD.do?seq=1")


def test_forbidden_stops_the_run():
    def handler(request):
        return robots() if request.url.path == "/robots.txt" else httpx.Response(429)

    with make_client(handler) as client, pytest.raises(BlockedError):
        client.text("https://a.kr/list.do")


def test_waiting_room_is_blocked_but_normal_page_with_script_is_fine():
    waiting = "<html><script src='netfunnel.js'></script><p>잠시만 기다려 주세요</p></html>"
    normal = "<html><script src='netfunnel.js'></script><table><tbody><tr><td>공고</td></tr></tbody></table></html>"

    def handler(request):
        if request.url.path == "/robots.txt":
            return robots()
        return httpx.Response(200, text=waiting if request.url.path == "/wait" else normal)

    with make_client(handler) as client:
        assert "공고" in client.text("https://a.kr/list")
        with pytest.raises(BlockedError):
            client.text("https://a.kr/wait")


def test_html_instead_of_file_is_rejected():
    def handler(request):
        if request.url.path == "/robots.txt":
            return httpx.Response(404)  # robots.txt가 없으면 전체 허용
        return httpx.Response(200, headers={"Content-Type": "text/html"}, text="<html>로그인</html>")

    with make_client(handler) as client, pytest.raises(FileRejected):
        client.download("https://a.kr/download.do?id=1")


def test_robots_txt_is_fetched_once_across_clients():
    """스케줄러는 회차마다 클라이언트를 새로 만들어요. 그래도 robots.txt는 도메인마다 하루 한 번만 받아요."""
    robots_requests = []

    def handler(request):
        if request.url.path == "/robots.txt":
            robots_requests.append(request.url.host)
            return robots()
        return httpx.Response(200, text="<table><tr><td>공고</td></tr></table>")

    for _ in range(3):  # 매시 회차 3번
        with make_client(handler) as client:
            client.text("https://a.kr/list.do")
    assert robots_requests == ["a.kr"]
