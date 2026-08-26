#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
consumer_root="$repository_root/samples/published-consumer"
consumer_repo="${MUYUN_CONSUMER_REPOSITORY:-$repository_root/build/consumer-repo}"
consumer_port="${MUYUN_CONSUMER_PORT:-18080}"
database_port="${MUYUN_CONSUMER_DB_PORT:-54323}"
api_context_path="${MUYUN_API_CONTEXT_PATH:-/api}"
consumer_version="${MUYUN_CONSUMER_VERSION:-$(awk -F= '/^muyunVersion=/ { print $2; exit }' "$repository_root/gradle.properties")}"
project_name="muyun-published-consumer"
log_file="$repository_root/build/published-consumer.log"

if [[ -z "$consumer_version" ]]; then
  echo "Missing MuYunSpring version. Set MUYUN_CONSUMER_VERSION or muyunVersion in gradle.properties." >&2
  exit 1
fi

cleanup() {
  if [[ -n "${consumer_pid:-}" ]] && kill -0 "$consumer_pid" 2>/dev/null; then
    kill "$consumer_pid" || true
    wait "$consumer_pid" || true
  fi
  MUYUN_CONSUMER_DB_PORT="$database_port" docker compose -p "$project_name" -f "$consumer_root/compose.yaml" down --volumes || true
}
trap cleanup EXIT

MUYUN_CONSUMER_DB_PORT="$database_port" docker compose -p "$project_name" -f "$consumer_root/compose.yaml" up -d

consumer_args=(-PmuyunRepository="$consumer_repo" "-PmuyunVersion=$consumer_version")

"$repository_root/gradlew" -p "$consumer_root" bootRun \
  "${consumer_args[@]}" \
  --args="--spring.profiles.active=smoke" \
  --no-daemon >"$log_file" 2>&1 &
consumer_pid=$!

for _ in {1..60}; do
  # A protected endpoint must return 401 without a bearer token. This proves
  # both that Spring is ready and that the configured API context is mounted.
  status="$(curl --silent --output /dev/null --write-out '%{http_code}' "http://127.0.0.1:$consumer_port$api_context_path/iam.auth/profile" || true)"
  if [[ "$status" == "401" ]]; then
    exit 0
  fi
  if ! kill -0 "$consumer_pid" 2>/dev/null; then
    cat "$log_file"
    exit 1
  fi
  sleep 2
done

cat "$log_file"
echo "Published consumer did not expose its protected API within 120 seconds." >&2
exit 1
