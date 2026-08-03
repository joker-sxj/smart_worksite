#!/usr/bin/env bash
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/lifecycle.sh
source "$script_dir/lib/lifecycle.sh"
root="$(project_root)"
run_dir="$root/logs/run"
healthy=true

load_env "$root/deploy/.env" || exit 1
printf 'Docker Compose services:\n'
docker_compose "$root" ps || healthy=false

if pid="$(managed_pid "$run_dir/backend.pid" "$root" 'spring-boot:run')"; then printf 'Java backend: RUNNING (PID %s)\n' "$pid"; else printf 'Java backend: no managed PID\n'; fi
if pid="$(managed_pid "$run_dir/frontend.pid" "$root/frontend" 'npm run dev')"; then printf 'Vue frontend: RUNNING (PID %s)\n' "$pid"; else printf 'Vue frontend: no managed PID\n'; fi

names=(Backend PythonAI MySQL Redis MinIO MinIOConsole)
ports=("$(configured_port SERVER_PORT 8080)" "$(configured_port AI_SERVICE_PORT 8015)" "$(configured_port MYSQL_PORT 3306)" "$(configured_port REDIS_PORT 6379)" "$(configured_port MINIO_API_PORT 9000)" "$(configured_port MINIO_CONSOLE_PORT 9001)")
if http_ready 'http://localhost:5173/'; then printf 'Frontend HTTP: UP\n'; else printf 'Frontend HTTP: DOWN\n'; healthy=false; fi
for index in "${!ports[@]}"; do
  if tcp_check "${ports[$index]}"; then printf '%s: LISTENING on %s\n' "${names[$index]}" "${ports[$index]}"; else printf '%s: DOWN on %s\n' "${names[$index]}" "${ports[$index]}"; healthy=false; fi
done
if http_health "http://127.0.0.1:${ports[0]}/actuator/health"; then printf 'Backend health: UP\n'; else printf 'Backend health: DOWN\n'; healthy=false; fi
if http_health "http://127.0.0.1:${ports[1]}/v1/health"; then printf 'Python AI health: UP\n'; else printf 'Python AI health: DOWN\n'; healthy=false; fi
$healthy
