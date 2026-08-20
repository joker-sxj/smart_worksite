#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/lifecycle.sh
source "$script_dir/lib/lifecycle.sh"
root="$(project_root)"
run_dir="$root/logs/run"

load_env "$root/deploy/.env" || exit 1
load_active_model_profile "$root" || exit 1
[[ -n "${MODEL_PROFILE_FILE:-}" ]] && load_env "$MODEL_PROFILE_FILE"

stop_managed 'Vue frontend' "$root/frontend" 'npm run dev' "$run_dir/frontend.pid"
stop_managed 'Java backend' "$root" 'spring-boot:run' "$run_dir/backend.pid"
printf 'Stopping Docker Compose services (volumes are preserved)...\n'
docker_compose "$root" down
rm -f "$run_dir/model-profile"
printf 'Smart Worksite is stopped. Persistent Docker volumes were preserved.\n'
