#!/usr/bin/env bash
# GitHub Actions에서 글 하나를 슬랙으로 보내요 (배포 결과 · CI 실패). 사용: slack-notify.sh "<글>"
# 웹훅 주소를 찾는 순서 (api·추출기 운영 알림과 같은 웹훅):
#   1. SLACK_WEBHOOK_URL 환경 변수 (저장소·환경 비밀 SLACK_WEBHOOK_URL)
#   2. ENV_FILE(환경 비밀, 서버 .env와 같은 내용) 안의 SLACK_WEBHOOK_URL=, 없으면 EXTRACTION_SLACK_WEBHOOK_URL= 줄
#   둘 다 없으면 보내지 않고 성공으로 끝나요. 보내기에 실패해도 경고만 남기고 성공으로 끝나요(알림이 배포 결과를 바꾸지 않게).
# 웹훅 주소는 비밀이라 출력하지 않고 로그에서 가려요(::add-mask::).
set -euo pipefail

text="${1:?보낼 글을 주세요}"
url="${SLACK_WEBHOOK_URL:-}"

if [ -z "$url" ] && [ -n "${ENV_FILE:-}" ]; then
  for key in SLACK_WEBHOOK_URL EXTRACTION_SLACK_WEBHOOK_URL; do
    line="$(printf '%s\n' "$ENV_FILE" | grep -E "^[[:space:]]*(export[[:space:]]+)?${key}=" | tail -n 1 || true)"
    value="${line#*=}"
    value="${value%$'\r'}"
    value="${value%\"}"
    value="${value#\"}"
    value="${value%\'}"
    value="${value#\'}"
    if [ -n "$value" ]; then
      url="$value"
      break
    fi
  done
fi

if [ -z "$url" ]; then
  echo "슬랙 웹훅 주소(SLACK_WEBHOOK_URL)가 없어 알림을 건너뛰어요"
  exit 0
fi
echo "::add-mask::$url"

payload="$(jq -n --arg text "$text" '{text: $text}')"
if curl -fsS -m 10 -X POST -H 'Content-Type: application/json' --data "$payload" "$url" > /dev/null 2>&1; then
  echo "슬랙에 알렸어요"
else
  echo "::warning::슬랙 알림을 보내지 못했어요"
fi
