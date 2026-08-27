#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
failures=0
created_env=0

ensure_test_env() {
  if [[ ! -f "$repo_root/deploy/.env" ]]; then
    printf 'AI_DEPLOYMENT_MODE=CLOUD_ALLOWED\n' > "$repo_root/deploy/.env"
    created_env=1
  fi
}

cleanup_test_env() {
  if (( created_env == 1 )); then
    rm -f "$repo_root/deploy/.env"
  fi
}
trap cleanup_test_env EXIT

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  failures=$((failures + 1))
}

make_profile() {
  local dir="$1" name="$2" gpu_count="$3" min_memory_gb="$4" model_regex="$5"
  cat > "$dir/${name}.env" <<PROFILE
MODEL_PROFILE_NAME=${name}
AI_DEPLOYMENT_MODE=LOCAL_ONLY
GPU_COUNT=${gpu_count}
GPU_MIN_MEMORY_GB=${min_memory_gb}
GPU_EXPECTED_MODEL_REGEX=${model_regex}
CHAT_GPU_COUNT=${gpu_count}
VLLM_IMAGE=test-vllm-image
NVIDIA_RUNTIME_TEST_IMAGE=test-runtime-image
MIN_NVIDIA_DRIVER_MAJOR=535
PROFILE
}

make_mocks() {
  local dir="$1" smi_rows="$2" docker_mode="${3:-ok}"
  mkdir -p "$dir/bin"
  cat > "$dir/bin/nvidia-smi" <<'NVIDIA_TEST'
#!/usr/bin/env bash
case "$*" in
  *--query-gpu=index,name,memory.total,driver_version*)
    printf '%b' "$MOCK_NVIDIA_SMI_ROWS"
    ;;
  *--query-gpu=index*)
    printf '%b' "$MOCK_NVIDIA_SMI_ROWS" | awk -F, '{gsub(/^[ \t]+|[ \t]+$/, "", $1); print $1}'
    ;;
  *--query-gpu=driver_version*)
    printf '%b' "$MOCK_NVIDIA_SMI_ROWS" | awk -F, 'NR == 1 {gsub(/^[ \t]+|[ \t]+$/, "", $4); print $4}'
    ;;
  *)
    printf '%b' "$MOCK_NVIDIA_SMI_ROWS"
    ;;
esac
NVIDIA_TEST
  cat > "$dir/bin/docker" <<'DOCKER_TEST'
#!/usr/bin/env bash
printf '%s\n' "$@" >> "$MOCK_DOCKER_ARGS"
if [[ "$1" == compose && "$2" == version ]]; then
  [[ "${MOCK_DOCKER_MODE:-ok}" == compose-fail ]] && exit 1
  printf 'Docker Compose version test\n'
  exit 0
fi
if [[ "$1" == run ]]; then
  [[ "${MOCK_DOCKER_MODE:-ok}" == run-fail ]] && { printf 'could not select device driver with capabilities: [[gpu]]\n' >&2; exit 1; }
  printf 'NVIDIA runtime visible\n'
  exit 0
fi
exit 0
DOCKER_TEST
  chmod +x "$dir/bin/nvidia-smi" "$dir/bin/docker"
  printf '%s' "$smi_rows" > "$dir/rows"
  printf '%s' "$docker_mode" > "$dir/docker-mode"
}

run_check() {
  local tmp="$1" profile="$2" output="$3"
  MOCK_NVIDIA_SMI_ROWS="$(cat "$tmp/rows")" \
  MOCK_DOCKER_MODE="$(cat "$tmp/docker-mode")" \
  MOCK_DOCKER_ARGS="$tmp/docker-args" \
  PATH="$tmp/bin:$PATH" \
    bash "$repo_root/scripts/check-gpu-runtime.sh" "$profile" >"$output" 2>&1
}

expect_success() {
  local name="$1" profile="$2" rows="$3" docker_mode="${4:-ok}"
  local tmp output
  tmp="$(mktemp -d)"
  output="$tmp/output"
  make_mocks "$tmp" "$rows" "$docker_mode"
  if ! run_check "$tmp" "$profile" "$output"; then
    fail "$name expected success; output: $(cat "$output")"
  elif ! grep -Fq -- '--rm' "$tmp/docker-args" || ! grep -Fq -- '--gpus' "$tmp/docker-args" || ! grep -Fq 'all' "$tmp/docker-args"; then
    fail "$name must run a temporary Docker GPU probe with --gpus all."
  fi
  rm -rf "$tmp"
}

expect_failure() {
  local name="$1" profile="$2" rows="$3" docker_mode="$4" expected_status_field="$5" expected_text="$6"
  local tmp output status
  tmp="$(mktemp -d)"
  output="$tmp/output"
  make_mocks "$tmp" "$rows" "$docker_mode"
  set +e
  run_check "$tmp" "$profile" "$output"
  status=$?
  set -e
  if (( status == 0 )); then
    fail "$name expected non-zero status."
  elif ! grep -Fq "$expected_status_field" "$output" || ! grep -Fq "$expected_text" "$output"; then
    fail "$name must explain the failed profile field and remediation; output: $(cat "$output")"
  fi
  rm -rf "$tmp"
}

main() {
  local tmp_profile_dir a6000_profile h100_profile
  ensure_test_env
  tmp_profile_dir="$(mktemp -d)"
  make_profile "$tmp_profile_dir" a6000x2-test 2 48 'RTX A6000'
  a6000_profile="$tmp_profile_dir/a6000x2-test.env"
  h100_profile="$repo_root/deploy/model-profiles/h100-fp8.env.example"

  expect_success 'two RTX A6000 48GB GPUs' "$a6000_profile" $'0, NVIDIA RTX A6000, 49140, 535.309.01\n1, NVIDIA RTX A6000, 49140, 535.309.01\n'
  expect_failure 'one visible GPU' "$a6000_profile" $'0, NVIDIA RTX A6000, 49140, 535.309.01\n' ok 'GPU_COUNT' 'Make the configured GPUs visible'
  expect_failure 'wrong GPU model' "$a6000_profile" $'0, NVIDIA H100 PCIe, 81559, 535.309.01\n1, NVIDIA RTX A6000, 49140, 535.309.01\n' ok 'GPU_EXPECTED_MODEL_REGEX' 'Install the expected GPU model'
  expect_failure 'low GPU memory' "$a6000_profile" $'0, NVIDIA RTX A6000, 49140, 535.309.01\n1, NVIDIA RTX A6000, 47000, 535.309.01\n' ok 'GPU_MIN_MEMORY_GB' 'Free/replace the GPU or select a smaller profile'
  expect_failure 'Docker GPU runtime failure' "$a6000_profile" $'0, NVIDIA RTX A6000, 49140, 535.309.01\n1, NVIDIA RTX A6000, 49140, 535.309.01\n' run-fail 'Docker GPU runtime' 'Install/configure NVIDIA Container Toolkit'
  expect_success 'legacy H100 profile without new hardware fields' "$h100_profile" $'0, NVIDIA H100 PCIe, 81559, 535.309.01\n'

  rm -rf "$tmp_profile_dir"
  if (( failures > 0 )); then
    printf '%s GPU runtime check(s) failed.\n' "$failures" >&2
    exit 1
  fi
  printf 'GPU runtime checks passed.\n'
}

main "$@"