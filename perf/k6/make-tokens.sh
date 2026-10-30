#!/usr/bin/env bash
# 더미 회원(perf/seed) 1~N번의 access token을 미리 만들어요 → perf/k6/data/tokens.json (커밋하지 않아요)
# 모양은 lib/token.js·api AuthTokenService와 같아요(HS256, iss=mujuthaek-api, sub=회원 id, role USER). 로그인 흐름이 VU마다 돌려 써요
#   perf/k6/make-tokens.sh <local|dev> [회원 수, 기본 1000] [유효 시간(시간), 기본 8]
# 비밀 값: local = perf/.env LOCAL_JWT_SECRET(비우면 api 기본값), dev = perf/.env DEV_JWT_SECRET
source "$(dirname "$0")/../lib/common.sh"

TARGET="${1:-}"
COUNT="${2:-1000}"
HOURS="${3:-8}"
case "$TARGET" in
  local) SECRET="${LOCAL_JWT_SECRET:-local-dev-only-jwt-secret-change-me-0123456789}" ;;
  dev) SECRET="${DEV_JWT_SECRET:-}"; [ -n "$SECRET" ] || { echo "perf/.env에 DEV_JWT_SECRET(dev 서버 JWT_SECRET)을 채워 주세요" >&2; exit 1; } ;;
  *) sed -n '2,6p' "$0"; exit 1 ;;
esac
[ "$TARGET" = local ] || [ "$SECRET" != "${JWT_SECRET:-}" ] || { echo "DEV_JWT_SECRET이 운영 JWT_SECRET과 같아요. 거부해요" >&2; exit 1; }

OUT="$PERF_DIR/k6/data/tokens.json"
SECRET="$SECRET" python3 - "$COUNT" "$HOURS" "$TARGET" > "$OUT" <<'PY'
import base64, hashlib, hmac, json, os, sys, time

count, hours, target = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
secret = os.environ["SECRET"].encode()
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
now = int(time.time())
tokens = []
for n in range(1, count + 1):
    # perf/seed/sql/guard.sql seed_id(3, n)
    sub = f"5eed0003-0000-7000-8000-{n:012x}"
    claims = {"iss": "mujuthaek-api", "sub": sub, "iat": now, "exp": now + hours * 3600, "role": "USER", "nickname": f"perf-seed-{n}"}
    unsigned = b64(json.dumps({"alg": "HS256"}, separators=(",", ":")).encode()) + "." + b64(json.dumps(claims, separators=(",", ":")).encode())
    tokens.append(unsigned + "." + b64(hmac.new(secret, unsigned.encode(), hashlib.sha256).digest()))
json.dump({"target": target, "expiresAt": now + hours * 3600, "tokens": tokens}, sys.stdout)
PY
chmod 600 "$OUT"
echo "$OUT: 토큰 $(jq '.tokens|length' "$OUT")개, $(date -r "$(jq '.expiresAt' "$OUT")" '+%m/%d %H:%M')까지"
