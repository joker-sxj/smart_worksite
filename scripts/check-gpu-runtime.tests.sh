#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
failures=0
tmp_root=''

cleanup() {
  if [[ -n "$tmp_root" && -d "$tmp_root" ]]; then
    rm -rf "$tmp_root"
  fi
}
trap cleanup EXIT

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  failures=$((failures + 1))
}

assert_tests_do_not_reference_real_base_env() {
  local forbidden test_body
  forbidden='repo_root/deploy/[.]env|deploy/[.]env'
  test_body="$(sed '/^assert_tests_do_not_reference_real_base_env()/,/^}/d' "${BASH_SOURCE[0]}")"
  if grep -Eq "$forbidden" <<< "$test_body"; then
    fail 'check-gpu-runtime tests must use temp base env fixtures instead of reading or writing the real deploy env file.'
  fi
}

make_base_env_fixture() {
  local file="$1"
  shift
  {
    for line in "$@"; do
      printf '%s\n' "$line"
    done
  } > "$file"
}

make_profile() {
  local file="$1" name="$2" gpu_count="$3" min_memory_gb="$4" model_regex="$5" cuda_visible_devices="$6"
  cat > "$file" <<PROFILE
MODEL_PROFILE_NAME=${name}
AI_DEPLOYMENT_MODE=LOCAL_ONLY
GPU_COUNT=${gpu_count}
GPU_MIN_MEMORY_GB=${min_memory_gb}
GPU_EXPECTED_MODEL_REGEX=${model_regex}
CHAT_GPU_COUNT=${gpu_count}
CHAT_CUDA_VISIBLE_DEVICES=${cuda_visible_devices}
VLLM_IMAGE=test-vllm-image
NVIDIA_RUNTIME_TEST_IMAGE=test-runtime-image
MIN_NVIDIA_DRIVER_MAJOR=535
PROFILE
}

make_legacy_h100_profile() {
  local file="$1"
  cat > "$file" <<'PROFILE'
MODEL_PROFILE_NAME=h100-legacy-test
AI_DEPLOYMENT_MODE=LOCAL_ONLY
CHAT_GPU_COUNT=1
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
  local tmp="$1" profile="$2" output="$3" base_env="$4"
  PROFILE_BASE_ENV_FILE="$base_env" \
  MOCK_NVIDIA_SMI_ROWS="$(cat "$tmp/rows")" \
  MOCK_DOCKER_MODE="$(cat "$tmp/docker-mode")" \
  MOCK_DOCKER_ARGS="$tmp/docker-args" \
  PATH="$tmp/bin:$PATH" \
    bash "$repo_root/scripts/check-gpu-runtime.sh" "$profile" >"$output" 2>&1
}

expect_success() {
  local name="$1" profile="$2" base_env="$3" rows="$4" docker_mode="${5:-ok}"
  local tmp output
  tmp="$(mktemp -d)"
  output="$tmp/output"
  make_mocks "$tmp" "$rows" "$docker_mode"
  if ! run_check "$tmp" "$profile" "$output" "$base_env"; then
    fail "$name expected success; output: $(cat "$output")"
  elif ! grep -Fq -- '--rm' "$tmp/docker-args" || ! grep -Fq -- '--gpus' "$tmp/docker-args" || ! grep -Fq 'all' "$tmp/docker-args"; then
    fail "$name must run a temporary Docker GPU probe with --gpus all."
  fi
  rm -rf "$tmp"
}

expect_failure() {
  local name="$1" profile="$2" base_env="$3" rows="$4" docker_mode="$5"
  shift 5
  local tmp output status expected
  tmp="$(mktemp -d)"
  output="$tmp/output"
  make_mocks "$tmp" "$rows" "$docker_mode"
  set +e
  run_check "$tmp" "$profile" "$output" "$base_env"
  status=$?
  set -e
  if (( status == 0 )); then
    fail "$name expected non-zero status."
  else
    for expected in "$@"; do
      if ! grep -Fq "$expected" "$output"; then
        fail "$name must explain '$expected' in its actionable error; output: $(cat "$output")"
        break
      fi
    done
  fi
  rm -rf "$tmp"
}

main() {
  local rows_a6000 rows_skip rows_h100
  local clean_base_env stale_base_env a6000_profile skip_profile h100_profile

  tmp_root="$(mktemp -d)"
  clean_base_env="$tmp_root/base-clean.env"
  stale_base_env="$tmp_root/base-stale.env"
  a6000_profile="$tmp_root/a6000x2-test.env"
  skip_profile="$tmp_root/a6000x2-skip.env"
  h100_profile="$tmp_root/h100-legacy.env"

  make_base_env_fixture "$clean_base_env" \
    'AI_DEPLOYMENT_MODE=CLOUD_ALLOWED' \
    'MIN_NVIDIA_DRIVER_MAJOR=535' \
    'NVIDIA_RUNTIME_TEST_IMAGE=nvidia/cuda:12.2.2-base-ubuntu22.04@sha256:1a8a738e81d4adbef0c709241f5238cec5bb77186dcb5b2103db293315ed42d1'
  make_base_env_fixture "$stale_base_env" \
    'AI_DEPLOYMENT_MODE=CLOUD_ALLOWED' \
    'GPU_COUNT=2' \
    'GPU_MIN_MEMORY_GB=48' \
    'GPU_EXPECTED_MODEL_REGEX=RTX A6000' \
    'CHAT_CUDA_VISIBLE_DEVICES=1,3' \
    'MIN_NVIDIA_DRIVER_MAJOR=535' \
    'NVIDIA_RUNTIME_TEST_IMAGE=nvidia/cuda:12.2.2-base-ubuntu22.04@sha256:1a8a738e81d4adbef0c709241f5238cec5bb77186dcb5b2103db293315ed42d1'

  make_profile "$a6000_profile" a6000x2-test 2 48 'RTX A6000' '0,1'
  make_profile "$skip_profile" a6000x2-skip 2 48 'RTX A6000' '1,3'
  make_legacy_h100_profile "$h100_profile"

  rows_a6000=$'0, NVIDIA RTX A6000, 49140, 535.309.01\n1, NVIDIA RTX A6000, 49140, 535.309.01\n'
  rows_skip=$'0, NVIDIA H100 PCIe, 81559, 535.309.01\n1, NVIDIA RTX A6000, 49140, 535.309.01\n2, NVIDIA H100 PCIe, 81559, 535.309.01\n3, NVIDIA RTX A6000, 49140, 535.309.01\n'
  rows_h100=$'0, NVIDIA H100 PCIe, 81559, 535.309.01\n'

  expect_success 'two RTX A6000 48GB GPUs' "$a6000_profile" "$clean_base_env" "$rows_a6000"
  expect_success 'non-zero CUDA_VISIBLE_DEVICES selection' "$skip_profile" "$clean_base_env" "$rows_skip"
  expect_failure 'one visible GPU' "$a6000_profile" "$clean_base_env" $'0, NVIDIA RTX A6000, 49140, 535.309.01\n' ok \
    'GPU_COUNT' 'Make the configured GPUs visible'
  expect_failure 'wrong GPU model' "$a6000_profile" "$clean_base_env" $'0, NVIDIA H100 PCIe, 81559, 535.309.01\n1, NVIDIA RTX A6000, 49140, 535.309.01\n' ok \
    'GPU_EXPECTED_MODEL_REGEX' 'Install the expected GPU model'
  expect_failure 'low GPU memory' "$a6000_profile" "$clean_base_env" $'0, NVIDIA RTX A6000, 49140, 535.309.01\n1, NVIDIA RTX A6000, 47000, 535.309.01\n' ok \
    'GPU_MIN_MEMORY_GB' 'Free/replace the GPU or select a smaller profile'
  expect_failure 'Docker GPU runtime failure' "$a6000_profile" "$clean_base_env" $'0, NVIDIA RTX A6000, 49140, 535.309.01\n1, NVIDIA RTX A6000, 49140, 535.309.01\n' run-fail \
    'expected=docker_gpu_runtime_visible' 'actual=probe_failed' 'Install/configure NVIDIA Container Toolkit'
  expect_success 'legacy H100 profile without new hardware fields' "$h100_profile" "$clean_base_env" "$rows_h100"
  expect_success 'legacy H100 profile ignores stale base GPU fields' "$h100_profile" "$stale_base_env" "$rows_h100"


  if (( failures > 0 )); then
    printf '%s GPU runtime check(s) failed.\n' "$failures" >&2
    exit 1
  fi
  printf 'GPU runtime checks passed.\n'
}

main "$@"
