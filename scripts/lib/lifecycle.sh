#!/usr/bin/env bash

project_root() {
  cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P
}

load_env() {
  local env_file="$1" raw line key value first last
  [[ -f "$env_file" ]] || { printf 'Configuration file not found: %s\n' "$env_file" >&2; return 1; }
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    line="${raw#${raw%%[![:space:]]*}}"
    line="${line%${line##*[![:space:]]}}"
    [[ -z "$line" || "${line:0:1}" == '#' || "$line" != *'='* ]] && continue
    [[ "$line" == export\ * ]] && line="${line#export }"
    key="${line%%=*}"; value="${line#*=}"
    key="${key//[[:space:]]/}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    value="${value#${value%%[![:space:]]*}}"; value="${value%${value##*[![:space:]]}}"
    if (( ${#value} >= 2 )); then
      first="${value:0:1}"; last="${value: -1}"
      if [[ ( "$first" == '"' && "$last" == '"' ) || ( "$first" == "'" && "$last" == "'" ) ]]; then
        value="${value:1:${#value}-2}"
      fi
    fi
    export "$key=$value"
  done < "$env_file"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || { printf 'Required command is not available: %s\n' "$1" >&2; return 1; }
}

normalize_host_model_endpoints() {
  local chat_host_port="${CHAT_HOST_PORT:-18000}"
  case "${QWEN_VL_ENDPOINT:-}" in
    http://local-vlm:8000/v1/chat/completions|http://local-llm:8000/v1/chat/completions|http://127.0.0.1:*/v1/chat/completions)
      export QWEN_VL_ENDPOINT="http://127.0.0.1:${chat_host_port}/v1/chat/completions"
      ;;
  esac
}

resolve_model_profile() {
  local root="$1" requested="$2" candidate
  [[ -n "$requested" ]] || return 1
  if [[ -f "$requested" ]]; then
    candidate="$requested"
  elif [[ -f "$root/$requested" ]]; then
    candidate="$root/$requested"
  elif [[ -f "$root/deploy/model-profiles/$requested" ]]; then
    candidate="$root/deploy/model-profiles/$requested"
  elif [[ -f "$root/deploy/model-profiles/$requested.env" ]]; then
    candidate="$root/deploy/model-profiles/$requested.env"
  elif [[ -f "$root/deploy/model-profiles/$requested.env.example" ]]; then
    candidate="$root/deploy/model-profiles/$requested.env.example"
  else
    printf 'Model profile not found: %s\n' "$requested" >&2
    return 1
  fi
  (cd "$(dirname "$candidate")" && printf '%s/%s\n' "$PWD" "$(basename "$candidate")")
}

configure_model_profile() {
  local root="$1" requested="$2" resolved
  resolved="$(resolve_model_profile "$root" "$requested")" || return 1
  export MODEL_PROFILE="$requested"
  export MODEL_PROFILE_FILE="$resolved"
}

load_active_model_profile() {
  local root="$1" active_file="$root/logs/run/model-profile"
  if [[ -z "${MODEL_PROFILE_FILE:-}" && -f "$active_file" ]]; then
    configure_model_profile "$root" "$(cat "$active_file")"
  elif [[ -z "${MODEL_PROFILE_FILE:-}" && -n "${MODEL_PROFILE:-}" ]]; then
    configure_model_profile "$root" "$MODEL_PROFILE"
  fi
}

docker_compose() {
  local root="$1"; shift
  local args=(-f "$root/deploy/docker-compose-env.yml" --env-file "$root/deploy/.env")
  if [[ -n "${MODEL_PROFILE_FILE:-}" ]]; then
    args+=(-f "$root/deploy/docker-compose-models.yml" --env-file "$MODEL_PROFILE_FILE")
  fi
  docker compose "${args[@]}" "$@"
}

tcp_check() {
  local port="$1" host="${2:-127.0.0.1}"
  timeout 1 bash -c ">/dev/tcp/$host/$port" >/dev/null 2>&1
}

wait_tcp() {
  local name="$1" port="$2" timeout_seconds="${3:-120}" elapsed=0
  until tcp_check "$port"; do
    (( elapsed >= timeout_seconds )) && { printf '%s did not start on port %s within %s seconds.\n' "$name" "$port" "$timeout_seconds" >&2; return 1; }
    sleep 2; elapsed=$((elapsed + 2))
  done
}

http_ready() {
  local url="$1"
  if command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time 5 -o /dev/null "$url" 2>/dev/null
  elif command -v python3 >/dev/null 2>&1; then
    python3 - "$url" <<'PY'
import sys, urllib.request
with urllib.request.urlopen(sys.argv[1], timeout=5) as response:
    if not 200 <= response.status < 400:
        raise SystemExit(1)
PY
  else
    printf 'curl or python3 is required for HTTP checks.\n' >&2
    return 1
  fi
}

listening_pids() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -t -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | sort -u | paste -sd, -
  elif command -v fuser >/dev/null 2>&1; then
    fuser -n tcp "$port" 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -u | paste -sd, -
  fi
}

assert_service_port_available() {
  local name="$1" port="$2" url="$3" checker="$4" host="${5:-127.0.0.1}" pids owner=''
  tcp_check "$port" "$host" || return 0
  "$checker" "$url" && return 0
  pids="$(listening_pids "$port" || true)"
  [[ -n "$pids" ]] && owner=" PID(s): $pids"
  printf '%s cannot start because port %s is occupied%s, but %s is not responding as expected. Stop the occupying process or change the configured port.\n' "$name" "$port" "$owner" "$url" >&2
  return 1
}

http_health() {
  local url="$1" body
  if command -v curl >/dev/null 2>&1; then
    body="$(curl -fsS --max-time 5 "$url" 2>/dev/null)" || return 1
  elif command -v python3 >/dev/null 2>&1; then
    body="$(python3 - "$url" <<'PY'
import sys, urllib.request
with urllib.request.urlopen(sys.argv[1], timeout=5) as response:
    print(response.read().decode('utf-8'))
PY
)" || return 1
  else
    printf 'curl or python3 is required for HTTP health checks.\n' >&2
    return 1
  fi
  [[ "$body" == *'"status":"UP"'* || "$body" == *'"status": "UP"'* ]]
}
wait_http_ready() {
  local name="$1" url="$2" timeout_seconds="${3:-90}" elapsed=0
  until http_ready "$url"; do
    (( elapsed >= timeout_seconds )) && { printf '%s HTTP check failed at %s after %s seconds.\n' "$name" "$url" "$timeout_seconds" >&2; return 1; }
    sleep 2; elapsed=$((elapsed + 2))
  done
}

wait_http() {
  local name="$1" url="$2" timeout_seconds="${3:-120}" elapsed=0
  until http_health "$url"; do
    (( elapsed >= timeout_seconds )) && { printf '%s health check failed at %s after %s seconds.\n' "$name" "$url" "$timeout_seconds" >&2; return 1; }
    sleep 2; elapsed=$((elapsed + 2))
  done
}

managed_pid() {
  local pid_file="$1" expected_cwd="$2" marker="$3" pid cwd args
  [[ -f "$pid_file" ]] || return 1
  pid="$(tr -d '[:space:]' < "$pid_file")"
  [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null || return 1
  cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)"
  args="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  [[ "$cwd" == "$(readlink -f "$expected_cwd")" && "$args" == *"$marker"* ]] || return 1
  printf '%s\n' "$pid"
}

positive_integer_or_default() {
  local value="$1" default_value="$2"
  [[ "$value" =~ ^[1-9][0-9]*$ ]] && printf '%s\n' "$value" || printf '%s\n' "$default_value"
}

assert_minimum_free_disk() {
  local target="$1" minimum_mb available_kb available_mb
  minimum_mb="$(positive_integer_or_default "${MIN_FREE_DISK_MB:-}" 2048)"
  available_kb="$(df -Pk "$target" | awk 'NR == 2 { print $4 }')"
  [[ "$available_kb" =~ ^[0-9]+$ ]] || { printf 'Unable to determine free disk space for %s.\n' "$target" >&2; return 1; }
  available_mb=$((available_kb / 1024))
  if (( available_mb < minimum_mb )); then
    printf 'Insufficient disk space: %s MB free under %s; at least %s MB is required. Run "df -h", "du -xhd1 ~ | sort -h", and "docker system df -v" to locate usage before starting.\n' "$available_mb" "$target" "$minimum_mb" >&2
    return 1
  fi
}

java_major_version() {
  local output version major
  output="$(java -version 2>&1)" || return 1
  [[ "$output" == *'version "'* ]] || return 1
  version="${output#*'version "'}"
  version="${version%%\"*}"
  major="${version%%.*}"
  [[ "$major" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$major"
}

cleanup_stale_project_logs() {
  local log_dir="$1" retention_days max_size_mb
  [[ -d "$log_dir" ]] || return 0
  retention_days="$(positive_integer_or_default "${HOST_LOG_RETENTION_DAYS:-}" 30)"
  max_size_mb="$(positive_integer_or_default "${HOST_LOG_MAX_SIZE_MB:-}" 10)"
  find "$log_dir" -maxdepth 1 -type f -name '*.log' \( -mtime "+$retention_days" -o -size +"${max_size_mb}"M \) \
    ! -name 'backend.out.log' ! -name 'backend.out.log.[0-9]*' \
    ! -name 'backend.err.log' ! -name 'backend.err.log.[0-9]*' \
    ! -name 'frontend.out.log' ! -name 'frontend.out.log.[0-9]*' \
    ! -name 'frontend.err.log' ! -name 'frontend.err.log.[0-9]*' \
    -delete
}

start_managed() {
  local name="$1" cwd="$2" command_text="$3" marker="$4" pid_file="$5" out_file="$6" err_file="$7" pid
  local root runner max_size_mb max_files retention_days
  if pid="$(managed_pid "$pid_file" "$cwd" "$marker")"; then
    printf '%s is already running (PID %s).\n' "$name" "$pid"
    return 0
  fi
  rm -f "$pid_file"
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
  runner="$root/scripts/lib/run-with-log-limit.mjs"
  [[ -f "$runner" ]] || { printf 'Log runner not found: %s\n' "$runner" >&2; return 1; }
  max_size_mb="$(positive_integer_or_default "${HOST_LOG_MAX_SIZE_MB:-}" 10)"
  max_files="$(positive_integer_or_default "${HOST_LOG_MAX_FILES:-}" 3)"
  retention_days="$(positive_integer_or_default "${HOST_LOG_RETENTION_DAYS:-}" 30)"
  nohup node "$runner" \
    --cwd "$cwd" --stdout "$out_file" --stderr "$err_file" \
    --max-size-mb "$max_size_mb" --max-files "$max_files" --retention-days "$retention_days" \
    -- bash -c "$command_text" </dev/null >/dev/null 2>&1 &
  pid=$!
  printf '%s\n' "$pid" > "$pid_file"
  printf 'Started %s (PID %s); logs rotate daily at %sMB with up to %s archives per natural day.\n' "$name" "$pid" "$max_size_mb" "$max_files"
}

kill_tree() {
  local pid="$1" child
  while read -r child; do
    [[ -n "$child" ]] && kill_tree "$child"
  done < <(pgrep -P "$pid" 2>/dev/null || true)
  kill -TERM "$pid" 2>/dev/null || true
}

stop_managed() {
  local name="$1" cwd="$2" marker="$3" pid_file="$4" pid elapsed=0
  if ! pid="$(managed_pid "$pid_file" "$cwd" "$marker")"; then
    rm -f "$pid_file"
    printf '%s is already stopped or its PID file is stale.\n' "$name"
    return 0
  fi
  kill_tree "$pid"
  while kill -0 "$pid" 2>/dev/null && (( elapsed < 10 )); do sleep 1; elapsed=$((elapsed + 1)); done
  if kill -0 "$pid" 2>/dev/null; then kill -KILL "$pid" 2>/dev/null || true; fi
  rm -f "$pid_file"
  printf 'Stopped %s.\n' "$name"
}

configured_port() {
  local name="$1" default_value="$2" value=""
  if [[ "$name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    value="${!name:-}"
  fi
  [[ "$value" =~ ^[0-9]+$ ]] && printf '%s\n' "$value" || printf '%s\n' "$default_value"
}
