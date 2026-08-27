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
load_env "${PROFILE_BASE_ENV_FILE:-$root/deploy/.env}"

profile_file_has_key() {
  local env_file="$1" key="$2"
  [[ -f "$env_file" ]] && grep -Eq "^(export[[:space:]]+)?${key}[[:space:]]*=" "$env_file"
}

if [[ -n "${MODEL_PROFILE_FILE:-}" ]]; then
  profile_file_has_key "$MODEL_PROFILE_FILE" GPU_COUNT || unset GPU_COUNT
  profile_file_has_key "$MODEL_PROFILE_FILE" GPU_MIN_MEMORY_GB || unset GPU_MIN_MEMORY_GB
  profile_file_has_key "$MODEL_PROFILE_FILE" GPU_EXPECTED_MODEL_REGEX || unset GPU_EXPECTED_MODEL_REGEX
  profile_file_has_key "$MODEL_PROFILE_FILE" CHAT_CUDA_VISIBLE_DEVICES || unset CHAT_CUDA_VISIBLE_DEVICES
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

declare -A gpu_name_by_index=()
declare -A gpu_memory_by_index=()
declare -A gpu_driver_by_index=()
visible_gpu_count=0
while IFS= read -r row; do
  [[ -n "$(trim "$row")" ]] || continue
  IFS=',' read -r gpu_index gpu_name gpu_memory_mib gpu_driver _ <<< "$row"
  gpu_index="$(trim "${gpu_index:-}")"
  gpu_name="$(trim "${gpu_name:-}")"
  gpu_memory_mib="$(trim "${gpu_memory_mib:-}")"
  gpu_driver="$(trim "${gpu_driver:-}")"
  [[ -n "$gpu_index" ]] || continue
  gpu_name_by_index["$gpu_index"]="$gpu_name"
  gpu_memory_by_index["$gpu_index"]="$gpu_memory_mib"
  gpu_driver_by_index["$gpu_index"]="$gpu_driver"
  visible_gpu_count=$((visible_gpu_count + 1))
done < <(printf '%s\n' "$query_output" | sed '/^[[:space:]]*$/d')

selected_gpu_indices=()
if [[ -n "${CHAT_CUDA_VISIBLE_DEVICES:-}" ]]; then
  IFS=',' read -r -a raw_selected_gpu_indices <<< "$CHAT_CUDA_VISIBLE_DEVICES"
  for raw_gpu_index in "${raw_selected_gpu_indices[@]}"; do
    gpu_index="$(trim "$raw_gpu_index")"
    if [[ -z "$gpu_index" || ! "$gpu_index" =~ ^[0-9]+$ ]]; then
      printf 'GPU preflight failed: profile field CHAT_CUDA_VISIBLE_DEVICES expected=comma-separated non-negative integers, actual=%s for profile %s. Fix CHAT_CUDA_VISIBLE_DEVICES or select a matching model profile.\n' "$CHAT_CUDA_VISIBLE_DEVICES" "$profile_label" >&2
      exit 1
    fi
    selected_gpu_indices+=("$gpu_index")
  done
else
  for ((i = 0; i < required_gpus; i++)); do
    selected_gpu_indices+=("$i")
  done
fi

if (( ${#selected_gpu_indices[@]} != required_gpus )); then
  printf 'GPU preflight failed: profile field GPU_COUNT expected=%s selected GPU(s), actual=%s from CHAT_CUDA_VISIBLE_DEVICES=%s for profile %s. Adjust CHAT_CUDA_VISIBLE_DEVICES or GPU_COUNT so they match.\n' "$required_gpus" "${#selected_gpu_indices[@]}" "${CHAT_CUDA_VISIBLE_DEVICES:-0..$((required_gpus - 1))}" "$profile_label" >&2
  exit 1
fi

declare -A seen_selected=()
for gpu_index in "${selected_gpu_indices[@]}"; do
  if [[ -n "${seen_selected[$gpu_index]+x}" ]]; then
    printf 'GPU preflight failed: profile field CHAT_CUDA_VISIBLE_DEVICES expected unique GPU indices, actual duplicate index=%s for profile %s. Remove duplicate entries or reduce GPU_COUNT.\n' "$gpu_index" "$profile_label" >&2
    exit 1
  fi
  seen_selected["$gpu_index"]=1
  if [[ -z "${gpu_name_by_index[$gpu_index]+x}" ]]; then
    printf 'GPU preflight failed: profile fields GPU_COUNT/CHAT_CUDA_VISIBLE_DEVICES expected=selected GPU index %s visible, actual=missing in nvidia-smi output for profile %s. Make the configured GPUs visible with nvidia-smi/CUDA_VISIBLE_DEVICES, update CHAT_CUDA_VISIBLE_DEVICES, or select a smaller profile.\n' "$gpu_index" "$profile_label" >&2
    exit 1
  fi
done

host_driver=''
for gpu_index in "${selected_gpu_indices[@]}"; do
  gpu_name="${gpu_name_by_index[$gpu_index]}"
  gpu_memory_mib="${gpu_memory_by_index[$gpu_index]}"
  gpu_driver="${gpu_driver_by_index[$gpu_index]}"
  [[ -z "$host_driver" ]] && host_driver="$gpu_driver"

  if [[ -n "$expected_model_regex" && ! "$gpu_name" =~ $expected_model_regex ]]; then
    printf 'GPU preflight failed: profile field GPU_EXPECTED_MODEL_REGEX expected=%s, actual GPU %s model=%s for profile %s. Install the expected GPU model or select a model profile that matches this host.\n' "$expected_model_regex" "$gpu_index" "${gpu_name:-unknown}" "$profile_label" >&2
    exit 1
  fi
  if [[ ! "$gpu_memory_mib" =~ ^[0-9]+$ ]]; then
    printf 'GPU preflight failed: nvidia-smi reported non-numeric memory for GPU %s, actual=%s. Check NVIDIA driver output before startup.\n' "$gpu_index" "${gpu_memory_mib:-unknown}" >&2
    exit 1
  fi
  min_memory_mib=$((minimum_memory_gb * 1000))
  if (( minimum_memory_gb > 0 && gpu_memory_mib < min_memory_mib )); then
    printf 'GPU preflight failed: profile field GPU_MIN_MEMORY_GB expected >=%sGB (%s MiB), actual GPU %s memory=%s MiB for profile %s. Free/replace the GPU or select a smaller profile.\n' "$minimum_memory_gb" "$min_memory_mib" "$gpu_index" "$gpu_memory_mib" "$profile_label" >&2
    exit 1
  fi
done

driver_major="${host_driver%%.*}"
if [[ ! "$driver_major" =~ ^[0-9]+$ ]] || (( driver_major < minimum_driver_major )); then
  printf 'GPU preflight failed: NVIDIA driver %s is older than profile field MIN_NVIDIA_DRIVER_MAJOR expected=%s.x required by %s. Upgrade the host driver before starting model containers.\n' "${host_driver:-unknown}" "$minimum_driver_major" "${VLLM_IMAGE:-the selected vLLM image}" >&2
  exit 1
fi

runtime_image="${NVIDIA_RUNTIME_TEST_IMAGE:-nvidia/cuda:12.2.2-base-ubuntu22.04}"
printf 'Host NVIDIA GPUs: %s; validating %s selected GPU(s) for profile %s; testing Docker GPU access with %s...\n' "$visible_gpu_count" "$required_gpus" "$profile_label" "$runtime_image"
if ! docker run --rm --gpus all --pull=missing "$runtime_image" nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader,nounits; then
  cat >&2 <<'MESSAGE'
GPU preflight failed: Docker GPU runtime command failed: expected=docker_gpu_runtime_visible, actual=probe_failed. Install/configure NVIDIA Container Toolkit, ensure the nvidia runtime is available to Docker, then restart Docker.
This check does not modify project containers, networks, volumes, or application data.
MESSAGE
  exit 1
fi
printf 'NVIDIA Container Toolkit check passed.\n'
