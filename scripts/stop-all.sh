#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/lifecycle.sh
source "$script_dir/lib/lifecycle.sh"
root="$(project_root)"
run_dir="$root/logs/run"

stop_managed 'Vue frontend' "$root/frontend" 'npm run dev' "$run_dir/frontend.pid"
stop_managed 'Java backend' "$root" 'spring-boot:run' "$run_dir/backend.pid"
printf 'Stopping Docker Compose services (volumes are preserved)...\n'
docker_compose "$root" down
printf 'Smart Worksite is stopped. Persistent Docker volumes were preserved.\n'
