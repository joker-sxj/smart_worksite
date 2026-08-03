#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/lifecycle.sh
source "$script_dir/lib/lifecycle.sh"
root="$(project_root)"
env_file="$root/deploy/.env"
env_example="$root/deploy/.env.example"
log_dir="$root/logs"
run_dir="$log_dir/run"
check_only=false

case "${1:-}" in
  '') ;;
  --check) check_only=true ;;
  *) printf 'Usage: %s [--check]\n' "$0" >&2; exit 2 ;;
esac

for command_name in docker java mvn node npm timeout pgrep; do require_command "$command_name"; done
docker compose version >/dev/null
if [[ ! -f "$env_file" ]]; then
  [[ -f "$env_example" ]] && cp "$env_example" "$env_file"
  printf 'Created %s. Configure its local values, especially QWEN_API_KEY, then run this script again.\n' "$env_file" >&2
  exit 1
fi
load_env "$env_file"
[[ -n "${QWEN_API_KEY:-}" ]] || { printf 'QWEN_API_KEY is empty in deploy/.env. Configure it before starting the complete project.\n' >&2; exit 1; }
java_major="$(java -version 2>&1 | head -n 1 | sed -E 's/.*"([0-9]+).*/\1/')"
[[ "$java_major" =~ ^[0-9]+$ ]] && (( java_major >= 17 )) || { printf 'Java 17 or newer is required.\n' >&2; exit 1; }
printf 'Prerequisite and configuration checks passed.\n'
$check_only && { printf 'Check mode completed; no services were started.\n'; exit 0; }

mkdir -p "$run_dir"
printf 'Starting Docker Compose services...\n'
docker_compose "$root" up -d
mysql_port="$(configured_port MYSQL_PORT 3306)"
redis_port="$(configured_port REDIS_PORT 6379)"
minio_port="$(configured_port MINIO_API_PORT 9000)"
ai_port="$(configured_port AI_SERVICE_PORT 8015)"
server_port="$(configured_port SERVER_PORT 8080)"
wait_tcp MySQL "$mysql_port"
wait_tcp Redis "$redis_port"
wait_tcp MinIO "$minio_port"
wait_http 'Python AI service' "http://127.0.0.1:$ai_port/v1/health"

backend_health_uri="http://127.0.0.1:$server_port/actuator/health"
assert_service_port_available 'Java backend' "$server_port" "$backend_health_uri" http_health
if ! http_health "$backend_health_uri"; then
  start_managed 'Java backend' "$root" 'mvn spring-boot:run' 'spring-boot:run' "$run_dir/backend.pid" "$log_dir/backend.out.log" "$log_dir/backend.err.log"
else
  printf 'Java backend is already healthy.\n'
fi
wait_http 'Java backend' "$backend_health_uri" 120 || { printf 'See logs/backend.out.log and logs/backend.err.log.\n' >&2; exit 1; }

frontend_dir="$root/frontend"
frontend_uri='http://localhost:5173/'
assert_service_port_available 'Vue frontend' 5173 "$frontend_uri" http_ready localhost
if ! http_ready "$frontend_uri"; then
  if [[ ! -d "$frontend_dir/node_modules" ]]; then
    printf 'Installing frontend dependencies...\n'
    (cd "$frontend_dir" && npm install)
  fi
  start_managed 'Vue frontend' "$frontend_dir" 'npm run dev' 'npm run dev' "$run_dir/frontend.pid" "$log_dir/frontend.out.log" "$log_dir/frontend.err.log"
else
  printf 'Vue frontend is already serving HTTP on port 5173.\n'
fi
wait_http_ready 'Vue frontend' "$frontend_uri" 90 || { printf 'See logs/frontend.out.log and logs/frontend.err.log.\n' >&2; exit 1; }

printf '\nSmart Worksite is ready.\n'
printf 'Frontend: http://localhost:5173\n'
printf 'Backend health: http://127.0.0.1:%s/actuator/health\n' "$server_port"
printf 'Python AI health: http://127.0.0.1:%s/v1/health\n' "$ai_port"
printf 'Logs: %s\n' "$log_dir"
