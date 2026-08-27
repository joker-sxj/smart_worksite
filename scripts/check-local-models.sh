#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/lifecycle.sh
source "$script_dir/lib/lifecycle.sh"
root="$(project_root)"
wait_seconds=0
run_smoke=false
profile_arg="${MODEL_PROFILE:-}"

while (( $# > 0 )); do
  case "$1" in
    --wait) [[ "${2:-}" =~ ^[0-9]+$ ]] || { printf '%s\n' '--wait requires seconds.' >&2; exit 2; }; wait_seconds="$2"; shift 2 ;;
    --model-profile) [[ -n "${2:-}" ]] || { printf '%s\n' '--model-profile requires a name or path.' >&2; exit 2; }; profile_arg="$2"; shift 2 ;;
    --smoke) run_smoke=true; shift ;;
    *) printf 'Usage: %s [--model-profile NAME_OR_PATH] [--wait SECONDS] [--smoke]\n' "$0" >&2; exit 2 ;;
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


smoke_check() {
  require_command python3
  CHAT_PORT="$chat_port" EMBEDDING_PORT="$embedding_port" RERANK_PORT="$rerank_port" \
  CHAT_MODEL="${CHAT_MODEL_NAME:-smart-worksite-chat}" EMBEDDING_MODEL="${EMBEDDING_MODEL_NAME:-smart-worksite-embedding}" \
  RERANK_MODEL="${RERANK_MODEL_NAME:-smart-worksite-reranker}" CHAT_MAX_LEN="${CHAT_MAX_MODEL_LEN:-16384}" \
  REQUEST_TIMEOUT="${MODEL_SMOKE_TIMEOUT_SECONDS:-300}" python3 - <<'PY'
import json, os, sys, urllib.request, urllib.error

def post(url, payload):
    request = urllib.request.Request(url, data=json.dumps(payload).encode(), headers={"Content-Type":"application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=float(os.environ["REQUEST_TIMEOUT"])) as response:
        return json.loads(response.read().decode())

def fail(name, error):
    print(f"{name} smoke: FAILED ({str(error)[:240]})", file=sys.stderr)
    raise SystemExit(1)

chat_max = int(os.environ["CHAT_MAX_LEN"])
# A repeated CJK character is close to one token for Qwen; reserve 512 tokens for templates/output.
prompt = "工" * max(1, chat_max - 512)
try:
    body = post(f"http://127.0.0.1:{os.environ['CHAT_PORT']}/v1/chat/completions", {
        "model": os.environ["CHAT_MODEL"], "messages": [{"role":"user","content":prompt}],
        "temperature": 0, "max_tokens": 1, "stream": False
    })
    if not body.get("choices"): raise ValueError("missing choices")
    print(f"chat boundary smoke: PASS (profile max context {chat_max})")
except Exception as error: fail("chat boundary", error)
try:
    body = post(f"http://127.0.0.1:{os.environ['EMBEDDING_PORT']}/v1/embeddings", {"model":os.environ["EMBEDDING_MODEL"],"input":["construction safety"]})
    if not body.get("data"): raise ValueError("missing embedding data")
    print("embedding smoke: PASS")
except Exception as error: fail("embedding", error)
try:
    body = post(f"http://127.0.0.1:{os.environ['RERANK_PORT']}/v1/rerank", {"model":os.environ["RERANK_MODEL"],"query":"safety risk","documents":["risk closed","risk unresolved"]})
    if not (body.get("results") or body.get("data")): raise ValueError("missing rerank results")
    print("rerank smoke: PASS")
except Exception as error: fail("rerank", error)
PY
}

if $run_smoke; then
  smoke_check || {
    printf 'Local model smoke check failed. Inspect: docker compose logs local-llm local-embedding local-reranker\n' >&2
    exit 1
  }
fi
