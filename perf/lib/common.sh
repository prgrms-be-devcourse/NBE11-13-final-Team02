# shellcheck shell=bash disable=SC2034
# perf 스크립트들이 같이 쓰는 설정과 서버 접속. source 해서 써요
set -euo pipefail

PERF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 명령줄의 BASE_URL이 perf/.env보다 먼저예요(BASE_URL=http://<dev> perf/run.sh … 로 대상만 바꿔요)
CLI_BASE_URL="${BASE_URL:-}"
if [ -f "$PERF_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$PERF_DIR/.env"
  set +a
fi

BASE_URL="${CLI_BASE_URL:-${BASE_URL:-http://3.37.131.73}}"
EC2_HOST="${EC2_HOST:-3.37.131.73}"
SSH_USER="${SSH_USER:-ubuntu}"
SSH_KEY="${SSH_KEY:-}"
SSH_KEY="${SSH_KEY/#\~/$HOME}"
if [ -z "${AWS_PROFILE:-}" ]; then unset AWS_PROFILE; fi
PROM=http://localhost:9090
GRAFANA=http://localhost:3300
REMOTE_DIR=/opt/mujuthaek

warn() { echo "  ! $*" >&2; }

remote() {
  local key=()
  [ -n "$SSH_KEY" ] && key=(-i "$SSH_KEY")
  ssh ${key[@]+"${key[@]}"} -o BatchMode=yes -o ConnectTimeout=10 "$SSH_USER@$EC2_HOST" "$@"
}

# 표준 입력의 SQL을 운영 api DB에 돌려 CSV로 내요. 서버 .env의 API_DB_* 값으로 서버 안에서 psql을 띄워요(RDS는 밖에서 안 열려요)
remote_sql() {
  remote "cd $REMOTE_DIR && docker run --rm -i --env-file .env postgres:16-alpine sh -c '
    url=\"\${API_DB_URL#jdbc:}\"
    PGPASSWORD=\"\$API_DB_PASSWORD\" psql \"\$url\" -U \"\${API_DB_USER:-api_app}\" -X -q --csv -v ON_ERROR_STOP=1 -f -'"
}

remote_versions() {
  remote "cat $REMOTE_DIR/versions.env"
}

# 더미 데이터(perf/seed)를 넣는 곳. local = 노트북 infra의 api DB, dev = dev 서버 .env의 api_dev(RDS)
# prod(api DB)에는 여기서도, perf/seed/sql/guard.sql에서도 막아요
LOCAL_DB_HOST="${LOCAL_DB_HOST:-127.0.0.1}"
LOCAL_DB_PORT="${LOCAL_DB_PORT:-5432}"
LOCAL_DB_NAME="${LOCAL_DB_NAME:-api_fe}"
LOCAL_DB_USER="${LOCAL_DB_USER:-api_app}"
DEV_EC2_HOST="${DEV_EC2_HOST:-}"
DEV_DB_NAME=api_dev

# 표준 입력의 SQL을 대상 DB에 돌려요. 나머지 인자는 psql에 그대로 넘겨요(--csv 등)
#   target_sql <local|dev> [psql 옵션…]
target_sql() {
  local target="$1"; shift
  case "$target" in
    local)
      if [ -z "${LOCAL_DB_PASSWORD:-}" ] && [ -f "$PERF_DIR/../infra/.env" ]; then
        LOCAL_DB_PASSWORD="$(sed -n 's/^API_DB_PASSWORD=//p' "$PERF_DIR/../infra/.env" | tail -1)"
      fi
      [ "$LOCAL_DB_NAME" != api ] || { echo "LOCAL_DB_NAME=api 는 쓰지 않아요(운영과 같은 이름)" >&2; return 1; }
      PGPASSWORD="${LOCAL_DB_PASSWORD:-}" psql -h "$LOCAL_DB_HOST" -p "$LOCAL_DB_PORT" -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" \
        -X -q -v ON_ERROR_STOP=1 "$@" -f -
      ;;
    dev)
      [ -n "$DEV_EC2_HOST" ] || { echo "perf/.env에 DEV_EC2_HOST(dev 서버)를 채워 주세요" >&2; return 1; }
      [ "$DEV_EC2_HOST" != "$EC2_HOST" ] || { echo "DEV_EC2_HOST가 운영 서버($EC2_HOST)와 같아요. 거부해요" >&2; return 1; }
      # 서버 .env의 DB 이름이 api_dev가 아니면 psql을 띄우지 않아요
      EC2_HOST="$DEV_EC2_HOST" remote "cd $REMOTE_DIR && docker run --rm -i --env-file .env postgres:16-alpine sh -c '
        url=\"\${API_DB_URL#jdbc:}\"; db=\"\${url##*/}\"; db=\"\${db%%\\?*}\"
        [ \"\$db\" = \"$DEV_DB_NAME\" ] || { echo \"dev 서버 DB가 $DEV_DB_NAME 이 아니에요(\$db). 거부해요\" >&2; exit 3; }
        PGPASSWORD=\"\$API_DB_PASSWORD\" psql \"\$url\" -U \"\${API_DB_USER:-api_app}\" -X -q -v ON_ERROR_STOP=1 $* -f -'"
      ;;
    *) echo "대상은 local 또는 dev예요: $target" >&2; return 1 ;;
  esac
}

# BASE_URL의 호스트로 대상을 정해요: prod · dev · local
perf_target() {
  local host="${BASE_URL#*://}"
  host="${host%%[:/]*}"
  case "$host" in
    "$EC2_HOST") echo prod ;;
    localhost | 127.0.0.1) echo local ;;
    *)
      if [ -n "$DEV_EC2_HOST" ] && [ "$host" = "$DEV_EC2_HOST" ]; then echo dev; else
        echo "BASE_URL($BASE_URL)이 운영(EC2_HOST)·dev(DEV_EC2_HOST)·localhost 중 어디인지 모르겠어요" >&2
        return 1
      fi
      ;;
  esac
}

# 대상에 맞게 SSH·CloudWatch 설정을 바꿔요. dev는 서버가 따로라 SSH·EC2 지표가 그쪽으로 가요(RDS는 운영과 같아요)
use_target() {
  TARGET="$1"
  if [ "$TARGET" = dev ]; then
    EC2_HOST="$DEV_EC2_HOST"
    EC2_INSTANCE_ID="${DEV_EC2_INSTANCE_ID:-}"
    export EC2_HOST EC2_INSTANCE_ID
  fi
}

# 대상 api DB에 SQL(표준 입력)을 돌려 CSV로 내요. prod·dev = 서버 .env의 DB, local = target_sql local
db_sql() {
  if [ "$TARGET" = local ]; then target_sql local --csv; else remote_sql; fi
}
