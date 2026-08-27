#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/lifecycle.sh
source "$script_dir/lib/lifecycle.sh"
root="$(project_root)"
profile_arg="${1:-${MODEL_PROFILE:-}}"

if [[ -n "$profile_arg" ]]; then
  configure_model_profile "$root" "$profile_arg"
fi
load_env "$root/deploy/.env"

profile_file_has_key() {
  local env_file="$1" key="$2"
  [[ -f "$env_file" ]] && grep -Eq "^(export[[:space:]]+)?${key}[[:space:]]*=" "$env_file"
}

if [[ -n "${MODEL_PROFILE_FILE:-}" ]]; then
  profile_file_has_key "$MODEL_PROFILE_FILE" GPU_COUNT || unset GPU_COUNT
  profile_file_has_key "$MODEL_PROFILE_FILE" GPU_MIN_MEMORY_GB || unset GPU_MIN_MEMORY_GB
  profile_file_has_key "$MODEL_PROFILE_FILE" GPU_EXPECTED_MODEL_REGEX || unset GPU_EXPECTED_MODEL_REGEX
  load_env "$MODEL_PROFILE_FILE"
fi

require_command docker
require_command nvidia-smi
docker compose version >/dev/null

profile_label="${MODEL_PROFILE_NAME:-${MODEL_PROFILE:-${MODEL_PROFILE_FILE:-deploy/.env}}}"
required_gpus="${GPU_COUNT:-${CHAT_GPU_COUNT:-1}}"
[[ "$required_gpus" =~ ^[1-9][0-9]*$ ]] || { printf 'GPU preflight failed: profile field GPU_COUNT/CHAT_GPU_COUNT must be a positive integer, actual=%s. Fix the selected model profile before startup.\n' "${required_gpus:-unset}" >&2; exit 1; }

minimum_memory_gb="${GPU_MIN_MEMORY_GB:-0}"
[[ "$minimum_memory_gb" =~ ^[0-9]+$ ]] || { printf 'GPU preflight failed: profile field GPU_MIN_MEMORY_GB must be an integer number of GB, actual=%s. Fix the selected model profile before startup.\n' "$minimum_memory_gb" >&2; exit 1; }

expected_model_regex="${GPU_EXPECTED_MODEL_REGEX:-}"
minimum_driver_major="${MIN_NVIDIA_DRIVER_MAJOR:-535}"
[[ "$minimum_driver_major" =~ ^[1-9][0-9]*$ ]] || minimum_driver_major=535

trim() {
  local value="$1"
  value="${value#${value%%[![:space:]]*}}"
  value="${value%${value##*[![:space:]]}}"
  printf '%s' "$value"
}

query_output="$(nvidia-smi --query-gpu=index,name,memory.total,driver_version --format=csv,noheader,nounits 2>/dev/null)" || {
  printf 'GPU preflight failed: nvidia-smi query failed for profile %s. Install NVIDIA drivers and make GPUs visible before startup.\n' "$profile_label" >&2
  exit 1
}

mapfile -t gpu_rows < <(printf '%s\n' "$query_output" | sed '/^[[:space:]]*$/d')
host_gpus="${#gpu_rows[@]}"
if (( host_gpus < required_gpus )); then
  printf 'GPU preflight failed: profile field GPU_COUNT expected=%s GPU(s), actual=%s visible via nvidia-smi for profile %s. Make the configured GPUs visible with nvidia-smi/CUDA_VISIBLE_DEVICES or select a smaller profile.\n' "$required_gpus" "$host_gpus" "$profile_label" >&2
  exit 1
fi

min_memory_mib=$((minimum_memory_gb * 1000))
host_driver=''
for ((i = 0; i < required_gpus; i++)); do
  row="${gpu_rows[$i]}"
  IFS=',' read -r gpu_index gpu_name gpu_memory_mib gpu_driver _ <<< "$row"
  gpu_index="$(trim "${gpu_index:-}")"
  gpu_name="$(trim "${gpu_name:-}")"
  gpu_memory_mib="$(trim "${gpu_memory_mib:-}")"
  gpu_driver="$(trim "${gpu_driver:-}")"
  [[ -z "$host_driver" ]] && host_driver="$gpu_driver"

  if [[ -n "$expected_model_regex" && ! "$gpu_name" =~ $expected_model_regex ]]; then
    printf 'GPU preflight failed: profile field GPU_EXPECTED_MODEL_REGEX expected=%s, actual GPU %s model=%s for profile %s. Install the expected GPU model or select a model profile that matches this host.\n' "$expected_model_regex" "${gpu_index:-$i}" "${gpu_name:-unknown}" "$profile_label" >&2
    exit 1
  fi
  if [[ ! "$gpu_memory_mib" =~ ^[0-9]+$ ]]; then
    printf 'GPU preflight failed: nvidia-smi reported non-numeric memory for GPU %s, actual=%s. Check NVIDIA driver output before startup.\n' "${gpu_index:-$i}" "${gpu_memory_mib:-unknown}" >&2
    exit 1
  fi
  if (( minimum_memory_gb > 0 && gpu_memory_mib < min_memory_mib )); then
    printf 'GPU preflight failed: profile field GPU_MIN_MEMORY_GB expected >=%sGB (%s MiB), actual GPU %s memory=%s MiB for profile %s. Free/replace the GPU or select a smaller profile.\n' "$minimum_memory_gb" "$min_memory_mib" "${gpu_index:-$i}" "$gpu_memory_mib" "$profile_label" >&2
    exit 1
  fi
done

driver_major="${host_driver%%.*}"
if [[ ! "$driver_major" =~ ^[0-9]+$ ]] || (( driver_major < minimum_driver_major )); then
  printf 'GPU preflight failed: NVIDIA driver %s is older than profile field MIN_NVIDIA_DRIVER_MAJOR expected=%s.x required by %s. Upgrade the host driver before starting model containers.\n' "${host_driver:-unknown}" "$minimum_driver_major" "${VLLM_IMAGE:-the selected vLLM image}" >&2
  exit 1
fi

runtime_image="${NVIDIA_RUNTIME_TEST_IMAGE:-nvidia/cuda:12.2.2-base-ubuntu22.04}"
printf 'Host NVIDIA GPUs: %s; validating first %s GPU(s) for profile %s; testing Docker GPU access with %s...\n' "$host_gpus" "$required_gpus" "$profile_label" "$runtime_image"
if ! docker run --rm --gpus all --pull=missing "$runtime_image" nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader,nounits; then
  cat >&2 <<'MESSAGE'
GPU preflight failed: Docker GPU runtime command failed: expected=docker_gpu_runtime_visible, actual=probe_failed. Install/configure NVIDIA Container Toolkit, ensure the nvidia runtime is available to Docker, then restart Docker.
This check does not modify project containers, networks, volumes, or application data.
MESSAGE
  exit 1
fi
printf 'NVIDIA Container Toolkit check passed.\n'
