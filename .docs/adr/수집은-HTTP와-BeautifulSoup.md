# ADR: 수집은 HTTP + BeautifulSoup, Playwright는 예비

2026-10-14 · 상태: 채택 · 자세한 설계: [수집기 설계](../collector/무주택자들%20지방%20공사%20공고%20수집기%20설계.md) · [전체 코드](../collector/무주택자들%20지방%20공사%20공고%20수집기%20전체%20코드.md)

## 배경
- POC 수집기는 지방 공사 9곳의 목록, 상세, 첨부를 모두 브라우저 없이 HTTP 요청만으로 받았어요.
- 다만 HTML을 정규식으로 잘라 읽어서, 화면 구조가 조금만 바뀌어도 깨졌어요.
- 처음에는 Playwright(브라우저 자동화)로 바꾸는 방안을 검토했어요.

## 결정
- 수집 방식은 **HTTP 그대로** 가요(`core/http_client.PoliteClient`, httpx).
- HTML 읽기는 **정규식 대신 BeautifulSoup(lxml)** 으로 바꿔요.
- Playwright(`core/browser.PoliteBrowser`)는 아래 경우에만 해당 출처에 써요.
  - 화면이 자바스크립트로만 그려지고, 데이터를 주는 주소를 찾을 수 없을 때
  - 첨부를 스크립트 클릭으로만 받을 수 있을 때
- 대기실이나 로봇 확인 화면은 Playwright로 우회하지 않아요. 그런 화면이 나오면 그 회차를 멈춰요(BLOCKED).

## 이유
- 이미 동작이 확인된 방식이고, 빠르고 메모리를 적게 써요. collector 컨테이너 상한 1GB 안에서 충분해요.
- POC가 깨지기 쉬웠던 원인은 HTTP가 아니라 정규식 파싱이었어요.

## 영향
- 지금은 Playwright가 필요한 출처가 없어서 `core/browser.py`는 만들지 않아요.
- 필요한 출처가 생기면 그때 ADR을 추가해요. 메모리 상한 조정과 Docker 이미지에 Chromium 추가도 그때 해요.
- 모든 요청은 `PoliteClient`로만 보내요. robots.txt 확인, 요청 간격, 차단 감지를 빠뜨리지 않기 위해서예요.
