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
  docker_test_dir="$(mktemp -d)"
  cat > "$docker_test_dir/docker" <<'DOCKER_TEST'
#!/usr/bin/env bash
if [[ "$1" == inspect && "$2" == -f ]]; then
  if [[ "$3" == *'.Mounts'* ]]; then
    printf '%s\n' "${MOCK_MOUNTS:-}"
  else
    printf '%s|%s|%s\n' "${MOCK_COMPOSE_PROJECT:-}" "${MOCK_COMPOSE_SERVICE:-}" "${MOCK_WORKING_DIR:-}"
  fi
  exit 0
fi
if [[ "$1" == inspect ]]; then
  [[ "$2" == "${MOCK_EXISTING_CONTAINER:-}" ]]
  exit
fi
exit 1
DOCKER_TEST
  chmod +x "$docker_test_dir/docker"
  if ! PATH="$docker_test_dir:$PATH" MOCK_EXISTING_CONTAINER=smart-worksite-redis MOCK_COMPOSE_PROJECT=deploy \
    MOCK_COMPOSE_SERVICE=redis MOCK_WORKING_DIR="$repo_root/deploy" MOCK_MOUNTS=deploy_redis-data:/data \
    bash -c 'set -euo pipefail; source "$1"; assert_legacy_container_migration_safe "$2"' bash "$repo_root/scripts/lib/lifecycle.sh" "$repo_root"; then
    fail 'Legacy containers already owned by the deploy project must be accepted for volume-preserving migration.'
  fi
  for unsafe_case in wrong-project wrong-service wrong-volume; do
    project=deploy service=redis mounts=deploy_redis-data:/data
    [[ "$unsafe_case" == wrong-project ]] && project=another-project
    [[ "$unsafe_case" == wrong-service ]] && service=mysql
    [[ "$unsafe_case" == wrong-volume ]] && mounts=foreign-data:/data
    if PATH="$docker_test_dir:$PATH" MOCK_EXISTING_CONTAINER=smart-worksite-redis MOCK_COMPOSE_PROJECT="$project" \
      MOCK_COMPOSE_SERVICE="$service" MOCK_WORKING_DIR="$repo_root/deploy" MOCK_MOUNTS="$mounts" \
      bash -c 'set -euo pipefail; source "$1"; assert_legacy_container_migration_safe "$2"' bash "$repo_root/scripts/lib/lifecycle.sh" "$repo_root" >/dev/null 2>&1; then
      fail "Legacy container migration must reject $unsafe_case metadata."
    fi
  done
  rm -rf "$docker_test_dir"
  java_test_dir="$(mktemp -d)"
  cat > "$java_test_dir/java" <<'JAVA_TEST'
#!/usr/bin/env bash
printf '%s\n' 'Picked up JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=/dev/shm' >&2
printf '%s\n' 'OpenJDK 64-Bit Server VM warning: Insufficient space for shared memory file' >&2
printf '%s\n' 'openjdk version "17.0.19" 2026-04-21' >&2
JAVA_TEST
  chmod +x "$java_test_dir/java"
  if ! PATH="$java_test_dir:$PATH" bash -c 'set -euo pipefail; source "$1"; [[ "$(java_major_version)" == "17" ]]' bash "$repo_root/scripts/lib/lifecycle.sh"; then
    fail 'java_major_version must ignore JVM warnings and JAVA_TOOL_OPTIONS output before the version line.'
  fi
  rm -rf "$java_test_dir"
fi

if [[ -f "$repo_root/scripts/start-all.sh" ]] && ! grep -q -- '--check' "$repo_root/scripts/start-all.sh"; then
  fail 'scripts/start-all.sh must expose the --check option.'
fi

if [[ -f "$repo_root/scripts/start-all.sh" ]]; then
  grep -Eq 'docker_compose "\$root" up -d --build' "$repo_root/scripts/start-all.sh" || fail 'scripts/start-all.sh must rebuild local Docker images before detached startup.'
  grep -q 'assert_service_port_available' "$repo_root/scripts/start-all.sh" || fail 'scripts/start-all.sh must reject occupied but unhealthy host-service ports.'
  grep -q 'http_ready' "$repo_root/scripts/start-all.sh" || fail 'scripts/start-all.sh must verify that an existing frontend port serves HTTP.'
  grep -q 'wait_http_ready' "$repo_root/scripts/start-all.sh" || fail 'scripts/start-all.sh must wait for frontend HTTP readiness.'
  grep -q 'wait_model_ready' "$repo_root/scripts/start-all.sh" || fail 'scripts/start-all.sh must wait for the READY response contract without treating it as liveness UP.'
  grep -Eq "assert_service_port_available 'Vue frontend'.*localhost" "$repo_root/scripts/start-all.sh" || fail 'scripts/start-all.sh must detect unhealthy frontend listeners bound through localhost/IPv6.'
  grep -Eq "npm run dev -- --host 0\.0\.0\.0" "$repo_root/scripts/start-all.sh" || fail 'Linux frontend must listen on all interfaces for remote access.'
  grep -q 'Remote frontend:' "$repo_root/scripts/start-all.sh" || fail 'Linux startup must print a remote frontend URL.'
  grep -Eq "host: ['\"]0\.0\.0\.0['\"]" "$repo_root/frontend/vite.config.ts" || fail 'Vite must listen on all interfaces even when started manually.'
fi

if [[ -f "$repo_root/scripts/stop-all.sh" ]]; then
  if ! grep -Eq '\bdown\b' "$repo_root/scripts/stop-all.sh"; then
    fail 'scripts/stop-all.sh must stop Docker Compose with down.'
  fi
  if grep -Eq '\bdown[[:space:]].*(-v|--volumes)([[:space:]]|$)' "$repo_root/scripts/stop-all.sh"; then
    fail 'scripts/stop-all.sh must not remove Docker volumes.'
  fi
fi

log_runner="$repo_root/scripts/lib/run-with-log-limit.mjs"
log_runner_tests="$repo_root/scripts/log-rotation.tests.mjs"
if [[ ! -f "$log_runner" ]]; then
  fail 'Missing bounded host log runner: scripts/lib/run-with-log-limit.mjs'
elif [[ ! -f "$log_runner_tests" ]]; then
  fail 'Missing bounded host log runner tests: scripts/log-rotation.tests.mjs'
else
  node "$log_runner_tests" || fail 'Bounded host log rotation test failed.'
fi

grep -q 'assert_minimum_free_disk' "$repo_root/scripts/start-all.sh" || fail 'Linux startup must reject critically low disk space before launching services.'
grep -q 'MIN_FREE_DISK_MB' "$repo_root/scripts/lib/lifecycle.sh" || fail 'Linux minimum free disk threshold must be configurable.'
grep -q 'run-with-log-limit.mjs' "$repo_root/scripts/lib/lifecycle.sh" || fail 'Linux managed processes must use the bounded log runner.'
grep -q 'HOST_LOG_MAX_SIZE_MB' "$repo_root/scripts/lib/lifecycle.sh" || fail 'Linux lifecycle must configure host log size limits.'
grep -q 'HOST_LOG_MAX_FILES' "$repo_root/scripts/lib/lifecycle.sh" || fail 'Linux lifecycle must configure host log file-count limits.'
grep -q -- '-size +"${max_size_mb}"M' "$repo_root/scripts/lib/lifecycle.sh" || fail 'Linux stale-log cleanup must remove oversized noncanonical logs.'
grep -q '^x-logging: &default-logging' "$repo_root/deploy/docker-compose-env.yml" || fail 'Docker Compose must define a shared bounded logging policy.'
grep -q 'DOCKER_LOG_MAX_SIZE' "$repo_root/deploy/docker-compose-env.yml" || fail 'Docker log max size must be configurable.'
grep -q 'DOCKER_LOG_MAX_FILES' "$repo_root/deploy/docker-compose-env.yml" || fail 'Docker log max file count must be configurable.'
for compose_file in deploy/docker-compose-env.yml deploy/docker-compose-models.yml; do
  if grep -Eq '^[[:space:]]*container_name:' "$repo_root/$compose_file"; then
    fail "$compose_file must let Compose scope container names by project and service."
  fi
done
grep -Fq 'name: deploy' "$repo_root/deploy/docker-compose-env.yml" \
  || fail 'Docker Compose must preserve the deploy project and its existing named-volume namespace.'
grep -q 'assert_legacy_container_migration_safe' "$repo_root/scripts/lib/lifecycle.sh" \
  || fail 'Linux startup must guard legacy fixed-name containers before migration.'
grep -q 'assert_legacy_container_migration_safe' "$repo_root/scripts/start-all.sh" \
  || fail 'Linux startup must run the legacy fixed-name container migration guard.'
grep -q 'docker compose --project-name deploy' "$repo_root/scripts/lib/lifecycle.sh" \
  || fail 'All Linux Compose calls must preserve the deploy project namespace.'
grep -Eq 'docker compose .*\-p deploy ' "$repo_root/scripts/verify_ai_adaptation.ps1" \
  || fail 'AI adaptation verification must use the deploy project namespace.'
grep -q 'AI_ACCESS_LOG' "$repo_root/deploy/Dockerfile.python-ai-service" || fail 'Python AI access logging must be configurable.'
grep -q -- '--no-access-log' "$repo_root/deploy/Dockerfile.python-ai-service" || fail 'Python AI access logs must be disabled by default.'
grep -q 'ProcessBuilder.Redirect.INHERIT' "$repo_root/src/main/java/com/xd/smartworksite/ai/infra/AiPythonServiceAutoStarter.java" || fail 'Auto-started Python output must flow through the bounded backend log stream.'
! grep -q 'Redirect.appendTo' "$repo_root/src/main/java/com/xd/smartworksite/ai/infra/AiPythonServiceAutoStarter.java" || fail 'Auto-started Python must not append to an unbounded standalone log.'
grep -q 'log.debug("http request' "$repo_root/src/main/java/com/xd/smartworksite/common/config/RequestIdFilter.java" || fail 'Per-request Java logging must be DEBUG instead of INFO.'

if (( failures > 0 )); then
  exit 1
fi

printf 'PASS: Bash lifecycle script contracts are satisfied.\n'
