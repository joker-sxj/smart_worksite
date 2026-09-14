#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/lifecycle.sh
source "$script_dir/lib/lifecycle.sh"
root="$(project_root)"
profile_arg="${1:-${MODEL_PROFILE:-}}"
[[ -n "$profile_arg" ]] || { printf 'Usage: %s MODEL_PROFILE\n' "$0" >&2; exit 2; }

configure_model_profile "$root" "$profile_arg"
load_env "$root/deploy/.env"
load_env "$MODEL_PROFILE_FILE"
: "${VLLM_IMAGE:?VLLM_IMAGE is required in the model profile.}"
: "${MODEL_CACHE_VOLUME:?MODEL_CACHE_VOLUME is required in the model profile.}"
: "${MODEL_ARTIFACT_MANIFEST:?MODEL_ARTIFACT_MANIFEST is required in the model profile.}"

output="$MODEL_ARTIFACT_MANIFEST"
[[ "$output" == /* ]] || output="$root/$output"
output_dir="$(dirname "$output")"
mkdir -p "$output_dir" "$root/logs"
output_dir="$(cd "$output_dir" && pwd -P)"
allowed_dir="$(cd "$root/deploy/model-manifests" && pwd -P)"
[[ "$output_dir" == "$allowed_dir" ]] || {
  printf 'Model manifest output must be under %s: %s\n' "$allowed_dir" "$output" >&2
  exit 1
}
staging_dir="$(mktemp -d "$root/logs/model-manifest.XXXXXX")"
trap 'rm -rf "$staging_dir"' EXIT

docker image inspect "$VLLM_IMAGE" >/dev/null
runtime_args=(
  --expected-runtime "CHAT_MODEL_NAME=${CHAT_MODEL_NAME:-}"
  --expected-runtime "CHAT_TENSOR_PARALLEL_SIZE=${CHAT_TENSOR_PARALLEL_SIZE:-}"
  --expected-runtime "CHAT_CUDA_VISIBLE_DEVICES=${CHAT_CUDA_VISIBLE_DEVICES:-}"
  --expected-runtime "CHAT_MAX_MODEL_LEN=${CHAT_MAX_MODEL_LEN:-}"
  --expected-runtime "CHAT_MAX_NUM_SEQS=${CHAT_MAX_NUM_SEQS:-}"
  --expected-runtime "CHAT_GPU_MEMORY_UTILIZATION=${CHAT_GPU_MEMORY_UTILIZATION:-}"
  --expected-runtime "EMBEDDING_MODEL_NAME=${EMBEDDING_MODEL_NAME:-}"
  --expected-runtime "EMBEDDING_CUDA_VISIBLE_DEVICES=${EMBEDDING_CUDA_VISIBLE_DEVICES:-}"
  --expected-runtime "EMBEDDING_MAX_MODEL_LEN=${EMBEDDING_MAX_MODEL_LEN:-}"
  --expected-runtime "EMBEDDING_MAX_NUM_SEQS=${EMBEDDING_MAX_NUM_SEQS:-}"
  --expected-runtime "EMBEDDING_GPU_MEMORY_UTILIZATION=${EMBEDDING_GPU_MEMORY_UTILIZATION:-}"
  --expected-runtime "RERANK_MODEL_NAME=${RERANK_MODEL_NAME:-}"
  --expected-runtime "RERANK_CUDA_VISIBLE_DEVICES=${RERANK_CUDA_VISIBLE_DEVICES:-}"
  --expected-runtime "RERANK_MAX_MODEL_LEN=${RERANK_MAX_MODEL_LEN:-}"
  --expected-runtime "RERANK_MAX_NUM_SEQS=${RERANK_MAX_NUM_SEQS:-}"
  --expected-runtime "RERANK_GPU_MEMORY_UTILIZATION=${RERANK_GPU_MEMORY_UTILIZATION:-}"
  --expected-runtime "RERANK_HF_OVERRIDES=${RERANK_HF_OVERRIDES:-}"
  --expected-runtime "VLLM_ENABLE_CUDA_COMPATIBILITY=${VLLM_ENABLE_CUDA_COMPATIBILITY:-}"
  --expected-runtime "QWEN_MODEL=${QWEN_MODEL:-}"
  --expected-runtime "QWEN_VL_MODEL=${QWEN_VL_MODEL:-}"
  --expected-runtime "QWEN_EMBEDDING_MODEL=${QWEN_EMBEDDING_MODEL:-}"
  --expected-runtime "QWEN_RERANK_MODEL=${QWEN_RERANK_MODEL:-}"
)
docker run --rm --pull never --network none --read-only --user "$(id -u):$(id -g)" --entrypoint python3 \
  -v "${MODEL_CACHE_VOLUME}:/cache:ro" \
  -v "${script_dir}/model-artifact-manifest.py:/model-artifact-manifest.py:ro" \
  -v "${staging_dir}:/output" \
  "$VLLM_IMAGE" /model-artifact-manifest.py generate \
  --cache-root /cache --output "/output/$(basename "$output")" \
  --expected-profile "${MODEL_PROFILE_NAME:-}" --expected-image "$VLLM_IMAGE" \
  "${runtime_args[@]}" \
  --expected-model "CHAT=${CHAT_MODEL_ID}@${CHAT_MODEL_REVISION}" \
  --expected-model "EMBEDDING=${EMBEDDING_MODEL_ID}@${EMBEDDING_MODEL_REVISION}" \
  --expected-model "RERANK=${RERANK_MODEL_ID}@${RERANK_MODEL_REVISION}"

install -m 0644 "${staging_dir}/$(basename "$output")" "$output"

printf 'Review and commit the generated checksum manifest before production startup: %s\n' "$output"
