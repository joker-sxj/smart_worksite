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

docker_compose() {
  local root="$1"; shift
  docker compose -f "$root/deploy/docker-compose-env.yml" --env-file "$root/deploy/.env" "$@"
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

start_managed() {
  local name="$1" cwd="$2" command_text="$3" marker="$4" pid_file="$5" out_file="$6" err_file="$7" pid
  if pid="$(managed_pid "$pid_file" "$cwd" "$marker")"; then
    printf '%s is already running (PID %s).\n' "$name" "$pid"
    return 0
  fi
  rm -f "$pid_file"
  nohup bash -c "cd \"\$1\" && $command_text" bash "$cwd" >"$out_file" 2>"$err_file" &
  pid=$!
  printf '%s\n' "$pid" > "$pid_file"
  printf 'Started %s (PID %s).\n' "$name" "$pid"
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
  local name="$1" default_value="$2" value="${!name:-}"
  [[ "$value" =~ ^[0-9]+$ ]] && printf '%s\n' "$value" || printf '%s\n' "$default_value"
}
