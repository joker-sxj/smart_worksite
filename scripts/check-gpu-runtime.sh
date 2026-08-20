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
[[ -n "${MODEL_PROFILE_FILE:-}" ]] && load_env "$MODEL_PROFILE_FILE"

require_command docker
require_command nvidia-smi
docker compose version >/dev/null

required_gpus="${CHAT_GPU_COUNT:-1}"
[[ "$required_gpus" =~ ^[1-9][0-9]*$ ]] || { printf 'CHAT_GPU_COUNT must be a positive integer.\n' >&2; exit 1; }
host_gpus="$(nvidia-smi --query-gpu=index --format=csv,noheader 2>/dev/null | sed '/^[[:space:]]*$/d' | wc -l | tr -d '[:space:]')"
host_driver="$(nvidia-smi --query-gpu=driver_version --format=csv,noheader 2>/dev/null | head -n 1 | tr -d '[:space:]')"
minimum_driver_major="${MIN_NVIDIA_DRIVER_MAJOR:-535}"
driver_major="${host_driver%%.*}"
[[ "$minimum_driver_major" =~ ^[1-9][0-9]*$ ]] || minimum_driver_major=535
if [[ ! "$driver_major" =~ ^[0-9]+$ ]] || (( driver_major < minimum_driver_major )); then
  printf 'GPU preflight failed: NVIDIA driver %s is older than profile minimum %s.x required by %s. Upgrade the host driver before starting model containers.\n' "${host_driver:-unknown}" "$minimum_driver_major" "${VLLM_IMAGE:-the selected vLLM image}" >&2
  exit 1
fi
[[ "$host_gpus" =~ ^[0-9]+$ ]] || host_gpus=0
if (( host_gpus < required_gpus )); then
  printf 'GPU preflight failed: profile requires %s GPU(s), but nvidia-smi reports %s.\n' "$required_gpus" "$host_gpus" >&2
  exit 1
fi

runtime_image="${NVIDIA_RUNTIME_TEST_IMAGE:-nvidia/cuda:12.2.2-base-ubuntu22.04}"
printf 'Host NVIDIA GPUs: %s; testing Docker GPU access with %s...\n' "$host_gpus" "$runtime_image"
if ! docker run --rm --gpus all --pull=missing "$runtime_image" nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader; then
  cat >&2 <<'MESSAGE'
Docker cannot access NVIDIA GPUs. Install/configure NVIDIA Container Toolkit, then restart Docker.
This check does not modify project containers, networks, volumes, or application data.
MESSAGE
  exit 1
fi
printf 'NVIDIA Container Toolkit check passed.\n'
