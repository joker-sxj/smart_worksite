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
model_profile="${MODEL_PROFILE:-}"

while (( $# > 0 )); do
  case "$1" in
    --check) check_only=true; shift ;;
    --model-profile)
      [[ -n "${2:-}" ]] || { printf '%s\n' '--model-profile requires a profile name or path.' >&2; exit 2; }
      model_profile="$2"; shift 2 ;;
    *) printf 'Usage: %s [--check] [--model-profile NAME_OR_PATH]\n' "$0" >&2; exit 2 ;;
  esac
done

for command_name in docker java mvn node npm timeout pgrep; do require_command "$command_name"; done
docker compose version >/dev/null
if [[ ! -f "$env_file" ]]; then
  [[ -f "$env_example" ]] && cp "$env_example" "$env_file"
  printf 'Created %s. Configure its local model endpoints, then run this script again.\n' "$env_file" >&2
  exit 1
fi
load_env "$env_file"
if [[ -n "$model_profile" ]]; then
  configure_model_profile "$root" "$model_profile"
  load_env "$MODEL_PROFILE_FILE"
fi
normalize_host_model_endpoints
assert_minimum_free_disk "$root"
deployment_mode="${AI_DEPLOYMENT_MODE:-CLOUD_ALLOWED}"
if requires_host_model_preflight "$deployment_mode" "${MODEL_PROFILE_FILE:-}"; then
  QWEN_VL_MODEL="$(effective_qwen_vl_model "${QWEN_VL_MODEL:-}")"
  export QWEN_VL_MODEL
  validate_host_model_configuration "$QWEN_VL_ENDPOINT" "$QWEN_VL_MODEL"
fi
if [[ "${deployment_mode^^}" == 'CLOUD_ALLOWED' && -z "${QWEN_API_KEY:-}" ]]; then
  printf 'QWEN_API_KEY is required when AI_DEPLOYMENT_MODE=CLOUD_ALLOWED.\n' >&2
  exit 1
fi
java_major="$(java_major_version || true)"
[[ "$java_major" =~ ^[0-9]+$ ]] && (( java_major >= 17 )) || { printf 'Java 17 or newer is required.\n' >&2; exit 1; }
printf 'Prerequisite and configuration checks passed.\n'
if $check_only; then
  if requires_host_model_preflight "$deployment_mode" "${MODEL_PROFILE_FILE:-}"; then
    printf 'Check mode completed; live model connectivity preflight was skipped because no services were started.\n'
  else
    printf 'Check mode completed; no services were started.\n'
  fi
  exit 0
fi

mkdir -p "$run_dir"
cleanup_stale_project_logs "$log_dir"
if [[ -n "${MODEL_PROFILE_FILE:-}" ]]; then
  printf '%s\n' "$MODEL_PROFILE_FILE" > "$run_dir/model-profile"
  "$script_dir/check-gpu-runtime.sh" "$MODEL_PROFILE_FILE"
  printf 'Starting local model services with profile %s...\n' "${MODEL_PROFILE_NAME:-$model_profile}"
  docker_compose "$root" up -d local-llm local-embedding local-reranker
  "$script_dir/check-local-models.sh" --model-profile "$MODEL_PROFILE_FILE" --wait "${MODEL_STARTUP_TIMEOUT_SECONDS:-3600}"
else
  rm -f "$run_dir/model-profile"
fi
if requires_host_model_preflight "$deployment_mode" "${MODEL_PROFILE_FILE:-}"; then
  preflight_host_model_endpoint "$QWEN_VL_ENDPOINT" "${QWEN_VL_MODEL:-}"
fi
printf 'Starting Docker Compose services...\n'
docker_compose "$root" up -d --build
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
restart_managed_if_running 'Java backend' "$root" 'spring-boot:run' "$run_dir/backend.pid"
assert_service_port_available 'Java backend' "$server_port" "$backend_health_uri" http_health
if ! http_health "$backend_health_uri"; then
  start_managed 'Java backend' "$root" 'mvn spring-boot:run' 'spring-boot:run' "$run_dir/backend.pid" "$log_dir/backend.out.log" "$log_dir/backend.err.log"
else
  printf 'Java backend is already healthy.\n'
fi
wait_http 'Java backend' "$backend_health_uri" 120 || { printf 'See logs/backend.out.log and logs/backend.err.log.\n' >&2; exit 1; }

frontend_dir="$root/frontend"
frontend_uri='http://localhost:5173/'
restart_managed_if_running 'Vue frontend' "$frontend_dir" 'npm run dev' "$run_dir/frontend.pid"
assert_service_port_available 'Vue frontend' 5173 "$frontend_uri" http_ready localhost
if ! http_ready "$frontend_uri"; then
  if [[ ! -d "$frontend_dir/node_modules" ]]; then
    printf 'Installing frontend dependencies...\n'
    (cd "$frontend_dir" && npm install)
  fi
  start_managed 'Vue frontend' "$frontend_dir" 'npm run dev -- --host 0.0.0.0' 'npm run dev' "$run_dir/frontend.pid" "$log_dir/frontend.out.log" "$log_dir/frontend.err.log"
else
  printf 'Vue frontend is already serving HTTP on port 5173.\n'
fi
wait_http_ready 'Vue frontend' "$frontend_uri" 90 || { printf 'See logs/frontend.out.log and logs/frontend.err.log.\n' >&2; exit 1; }

printf '\nSmart Worksite is ready.\n'
printf 'Frontend (local): http://localhost:5173\n'
remote_host="$(hostname -I 2>/dev/null | awk '{print $1}')"
if [[ -n "$remote_host" ]]; then
  printf 'Remote frontend: http://%s:5173\n' "$remote_host"
else
  printf 'Remote frontend: http://<server-ip>:5173\n'
fi
printf 'Backend health: http://127.0.0.1:%s/actuator/health\n' "$server_port"
printf 'Python AI health: http://127.0.0.1:%s/v1/health\n' "$ai_port"
if [[ -n "${MODEL_PROFILE_FILE:-}" ]]; then printf 'Local model profile: %s\n' "${MODEL_PROFILE_NAME:-$model_profile}"; fi
printf 'Logs: %s\n' "$log_dir"
