#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == '--verify-snapshot' ]]; then
  cache_root="${2:-}"; model_id="${3:-}"; revision="${4:-}"
  [[ -n "$cache_root" && -n "$model_id" && -n "$revision" ]] || { printf 'Usage: %s --verify-snapshot CACHE_ROOT MODEL_ID REVISION\n' "$0" >&2; exit 2; }
  snapshot="$cache_root/hub/models--${model_id%%/*}--${model_id#*/}/snapshots/$revision"
  [[ -d "$snapshot" && -f "$snapshot/config.json" ]] || { printf 'snapshot missing config: %s\n' "$snapshot" >&2; exit 1; }
  find -L "$snapshot" -maxdepth 1 -type f \( -name 'tokenizer.json' -o -name 'tokenizer_config.json' -o -name 'vocab.json' \) -print -quit | grep -q . || { printf 'snapshot missing tokenizer files: %s\n' "$snapshot" >&2; exit 1; }
  index="$snapshot/model.safetensors.index.json"
  if [[ -f "$index" ]]; then
    python3 - "$index" "$snapshot" <<'PY'
import json, os, sys
index, snapshot = sys.argv[1:]
with open(index, encoding="utf-8") as stream:
    weight_map = json.load(stream).get("weight_map", {})
if not weight_map:
    raise SystemExit("safetensors index has no weight_map")
for name in sorted(set(weight_map.values())):
    path = os.path.join(snapshot, name)
    if not os.path.isfile(path):
        raise SystemExit(f"missing indexed shard: {name}")
PY
  elif ! find -L "$snapshot" -maxdepth 1 -type f -name '*.safetensors' -print -quit | grep -q .; then
    printf 'snapshot missing safetensors weights: %s\n' "$snapshot" >&2
    exit 1
  fi
  find -L "$snapshot" -maxdepth 1 -type f -name '*.safetensors' -print -quit | grep -q . || [[ -f "$index" ]] || { printf 'snapshot has no usable weights: %s\n' "$snapshot" >&2; exit 1; }
  exit 0
fi

profile_file="${1:-}"
[[ -n "$profile_file" ]] || { printf 'Usage: %s MODEL_PROFILE_FILE\n' "$0" >&2; exit 2; }
[[ -f "$profile_file" ]] || { printf 'Model profile not found: %s\n' "$profile_file" >&2; exit 1; }

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/lifecycle.sh
source "$script_dir/lib/lifecycle.sh"
load_env "$profile_file"
root="$(project_root)"

: "${VLLM_IMAGE:?VLLM_IMAGE is required in the model profile.}"
: "${MODEL_CACHE_VOLUME:?MODEL_CACHE_VOLUME is required in the model profile.}"
: "${MODEL_ARTIFACT_MANIFEST:?MODEL_ARTIFACT_MANIFEST is required in the model profile.}"
if [[ "${MODEL_ARTIFACT_STATUS:-}" != 'VERIFIED' ]]; then
  printf 'MODEL_ARTIFACT_STATUS must be VERIFIED before startup; profile %s is %s. Generate, review, and approve its own manifest.\n' \
    "${MODEL_PROFILE_NAME:-unknown}" "${MODEL_ARTIFACT_STATUS:-unset}" >&2
  exit 1
fi

manifest_file="$MODEL_ARTIFACT_MANIFEST"
[[ "$manifest_file" == /* ]] || manifest_file="$root/$manifest_file"
[[ -f "$manifest_file" ]] || {
  printf 'Model artifact manifest is missing: %s. Generate and approve the manifest before startup.\n' "$manifest_file" >&2
  exit 1
}

if ! docker image inspect "$VLLM_IMAGE" >/dev/null 2>&1; then
  printf 'Pinned model container image is not present locally: %s. Import or pull it before entering offline mode.\n' "$VLLM_IMAGE" >&2
  exit 1
fi

check_model() {
  local role="$1" model_id="$2" revision="$3"
  [[ "$model_id" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || {
    printf '%s_MODEL_ID must use the owner/name format: %s\n' "$role" "$model_id" >&2
    return 1
  }
  [[ "$revision" =~ ^[0-9a-f]{40}$ ]] || {
    printf '%s_MODEL_REVISION must be a 40-character lowercase commit hash: %s\n' "$role" "$revision" >&2
    return 1
  }

  if ! docker run --rm --pull never --entrypoint sh \
    -v "${MODEL_CACHE_VOLUME}:/cache:ro" \
    "$VLLM_IMAGE" -c '
      set -eu
      model_id="$1"; revision="$2"
      cache_name="models--${model_id%%/*}--${model_id#*/}"
      snapshot="/cache/hub/${cache_name}/snapshots/${revision}"
      test -d "$snapshot" && test -f "$snapshot/config.json"
      find -L "$snapshot" -maxdepth 1 -type f \
        \( -name "tokenizer.json" -o -name "tokenizer_config.json" -o -name "vocab.json" \) \
        -print -quit | grep -q .
      index="$snapshot/model.safetensors.index.json"
      if test -f "$index"; then
        python3 - "$index" "$snapshot" <<'PY'
import json, os, sys
index, snapshot = sys.argv[1:]
with open(index, encoding="utf-8") as stream:
    weight_map = json.load(stream).get("weight_map", {})
if not weight_map:
    raise SystemExit("safetensors index has no weight_map")
for name in sorted(set(weight_map.values())):
    if not os.path.isfile(os.path.join(snapshot, name)):
        raise SystemExit(f"missing indexed shard: {name}")
PY
      elif ! find -L "$snapshot" -maxdepth 1 -type f -name '*.safetensors' -print -quit | grep -q .; then
        exit 1
      fi
    ' offline-cache-check "$model_id" "$revision"; then
    printf '%s model cache is missing or incomplete for %s at revision %s. Preload the complete snapshot before startup.\n' \
      "$role" "$model_id" "$revision" >&2
    return 1
  fi
}

check_model CHAT "${CHAT_MODEL_ID:-}" "${CHAT_MODEL_REVISION:-}"
check_model EMBEDDING "${EMBEDDING_MODEL_ID:-}" "${EMBEDDING_MODEL_REVISION:-}"
check_model RERANK "${RERANK_MODEL_ID:-}" "${RERANK_MODEL_REVISION:-}"

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
if ! docker run --rm --pull never --network none --read-only --entrypoint python3 \
  -v "${MODEL_CACHE_VOLUME}:/cache:ro" \
  -v "${manifest_file}:/manifest.json:ro" \
  -v "${script_dir}/model-artifact-manifest.py:/model-artifact-manifest.py:ro" \
  "$VLLM_IMAGE" /model-artifact-manifest.py verify \
  --cache-root /cache --manifest /manifest.json \
  --expected-profile "${MODEL_PROFILE_NAME:-}" --expected-image "$VLLM_IMAGE" \
  "${runtime_args[@]}" \
  --expected-model "CHAT=${CHAT_MODEL_ID}@${CHAT_MODEL_REVISION}" \
  --expected-model "EMBEDDING=${EMBEDDING_MODEL_ID}@${EMBEDDING_MODEL_REVISION}" \
  --expected-model "RERANK=${RERANK_MODEL_ID}@${RERANK_MODEL_REVISION}"; then
  printf 'Model artifact checksum verification failed for profile %s. Refusing model startup.\n' "${MODEL_PROFILE_NAME:-unknown}" >&2
  exit 1
fi

printf 'Offline model cache checks passed for chat, embedding, and reranker.\n'
