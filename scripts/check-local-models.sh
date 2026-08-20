#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/lifecycle.sh
source "$script_dir/lib/lifecycle.sh"
root="$(project_root)"
wait_seconds=0
profile_arg="${MODEL_PROFILE:-}"

while (( $# > 0 )); do
  case "$1" in
    --wait) [[ "${2:-}" =~ ^[0-9]+$ ]] || { printf '%s\n' '--wait requires seconds.' >&2; exit 2; }; wait_seconds="$2"; shift 2 ;;
    --model-profile) [[ -n "${2:-}" ]] || { printf '%s\n' '--model-profile requires a name or path.' >&2; exit 2; }; profile_arg="$2"; shift 2 ;;
    *) printf 'Usage: %s [--model-profile NAME_OR_PATH] [--wait SECONDS]\n' "$0" >&2; exit 2 ;;
  esac
done

[[ -n "$profile_arg" ]] && configure_model_profile "$root" "$profile_arg"
load_env "$root/deploy/.env"
[[ -n "${MODEL_PROFILE_FILE:-}" ]] && load_env "$MODEL_PROFILE_FILE"

request_timeout="${MODEL_HEALTH_TIMEOUT_SECONDS:-30}"
[[ "$request_timeout" =~ ^[1-9][0-9]*$ ]] || request_timeout=30
chat_port="$(configured_port CHAT_HOST_PORT 18000)"
embedding_port="$(configured_port EMBEDDING_HOST_PORT 18001)"
rerank_port="$(configured_port RERANK_HOST_PORT 18002)"

probe() {
  local url="$1" expected="${2:-}" body
  if command -v curl >/dev/null 2>&1; then
    body="$(curl -fsS --max-time "$request_timeout" "$url" 2>/dev/null)" || return 1
  elif command -v python3 >/dev/null 2>&1; then
    body="$(python3 - "$url" "$request_timeout" <<'PY'
import sys, urllib.request
with urllib.request.urlopen(sys.argv[1], timeout=float(sys.argv[2])) as response:
    print(response.read().decode("utf-8"))
PY
)" || return 1
  else
    printf 'curl or python3 is required for model health checks.\n' >&2
    return 1
  fi
  [[ -z "$expected" || "$body" == *"$expected"* ]]
}

check_once() {
  local healthy=true
  if probe "http://127.0.0.1:${chat_port}/health"; then printf 'chat: UP (%s)\n' "${CHAT_MODEL_NAME:-unknown}"; else printf 'chat: DOWN\n'; healthy=false; fi
  if probe "http://127.0.0.1:${chat_port}/v1/models" "${CHAT_MODEL_NAME:-}"; then printf 'vision: UP (%s)\n' "${CHAT_MODEL_NAME:-unknown}"; else printf 'vision: DOWN\n'; healthy=false; fi
  if probe "http://127.0.0.1:${embedding_port}/v1/models" "${EMBEDDING_MODEL_NAME:-}"; then printf 'embedding: UP (%s)\n' "${EMBEDDING_MODEL_NAME:-unknown}"; else printf 'embedding: DOWN\n'; healthy=false; fi
  if probe "http://127.0.0.1:${rerank_port}/v1/models" "${RERANK_MODEL_NAME:-}"; then printf 'rerank: UP (%s)\n' "${RERANK_MODEL_NAME:-unknown}"; else printf 'rerank: DOWN\n'; healthy=false; fi
  $healthy
}

elapsed=0
while ! check_once; do
  (( elapsed >= wait_seconds )) && exit 1
  sleep 10
  elapsed=$((elapsed + 10))
  printf 'Waiting for local models (%s/%s seconds)...\n' "$elapsed" "$wait_seconds"
done
