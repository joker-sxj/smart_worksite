#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
required_files=(
  "scripts/lib/lifecycle.sh"
  "scripts/start-all.sh"
  "scripts/status.sh"
  "scripts/stop-all.sh"
)

failures=0
fail() {
  printf 'ERROR: %s\n' "$1" >&2
  failures=$((failures + 1))
}

for relative_path in "${required_files[@]}"; do
  full_path="$repo_root/$relative_path"
  if [[ ! -f "$full_path" ]]; then
    fail "Missing required file: $relative_path"
    continue
  fi
  if ! bash -n "$full_path"; then
    fail "Bash syntax error in $relative_path"
  fi
done

if [[ -f "$repo_root/scripts/lib/lifecycle.sh" ]]; then
  grep -Fq 'tcp_check "$port" "$host"' "$repo_root/scripts/lib/lifecycle.sh" || fail 'assert_service_port_available must pass its host name to tcp_check.'
  if ! bash -c 'set -euo pipefail; source "$1"; [[ "$(configured_port MYSQL_PORT 3306)" == "3306" ]]; MYSQL_PORT=13306; [[ "$(configured_port MYSQL_PORT 3306)" == "13306" ]]; [[ "$(configured_port bad-name 1234)" == "1234" ]]' bash "$repo_root/scripts/lib/lifecycle.sh"; then
    fail 'configured_port must safely read unset, set, and invalid variable names under set -u.'
  fi
fi

if [[ -f "$repo_root/scripts/start-all.sh" ]] && ! grep -q -- '--check' "$repo_root/scripts/start-all.sh"; then
  fail 'scripts/start-all.sh must expose the --check option.'
fi

if [[ -f "$repo_root/scripts/start-all.sh" ]]; then
  grep -q 'assert_service_port_available' "$repo_root/scripts/start-all.sh" || fail 'scripts/start-all.sh must reject occupied but unhealthy host-service ports.'
  grep -q 'http_ready' "$repo_root/scripts/start-all.sh" || fail 'scripts/start-all.sh must verify that an existing frontend port serves HTTP.'
  grep -q 'wait_http_ready' "$repo_root/scripts/start-all.sh" || fail 'scripts/start-all.sh must wait for frontend HTTP readiness.'
  grep -Eq "assert_service_port_available 'Vue frontend'.*localhost" "$repo_root/scripts/start-all.sh" || fail 'scripts/start-all.sh must detect unhealthy frontend listeners bound through localhost/IPv6.'
fi

if [[ -f "$repo_root/scripts/stop-all.sh" ]]; then
  if ! grep -Eq '\bdown\b' "$repo_root/scripts/stop-all.sh"; then
    fail 'scripts/stop-all.sh must stop Docker Compose with down.'
  fi
  if grep -Eq '\bdown[[:space:]].*(-v|--volumes)([[:space:]]|$)' "$repo_root/scripts/stop-all.sh"; then
    fail 'scripts/stop-all.sh must not remove Docker volumes.'
  fi
fi

if (( failures > 0 )); then
  exit 1
fi

printf 'PASS: Bash lifecycle script contracts are satisfied.\n'
