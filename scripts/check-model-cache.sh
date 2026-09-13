#!/usr/bin/env bash
set -euo pipefail

profile_file="${1:-}"
[[ -n "$profile_file" ]] || { printf 'Usage: %s MODEL_PROFILE_FILE\n' "$0" >&2; exit 2; }
[[ -f "$profile_file" ]] || { printf 'Model profile not found: %s\n' "$profile_file" >&2; exit 1; }

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/lifecycle.sh
source "$script_dir/lib/lifecycle.sh"
load_env "$profile_file"

: "${VLLM_IMAGE:?VLLM_IMAGE is required in the model profile.}"
: "${MODEL_CACHE_VOLUME:?MODEL_CACHE_VOLUME is required in the model profile.}"

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
      find -L "$snapshot" -maxdepth 1 -type f \
        \( -name "*.safetensors" -o -name "*.safetensors.index.json" \) \
        -print -quit | grep -q .
    ' offline-cache-check "$model_id" "$revision"; then
    printf '%s model cache is missing or incomplete for %s at revision %s. Preload the complete snapshot before startup.\n' \
      "$role" "$model_id" "$revision" >&2
    return 1
  fi
}

check_model CHAT "${CHAT_MODEL_ID:-}" "${CHAT_MODEL_REVISION:-}"
check_model EMBEDDING "${EMBEDDING_MODEL_ID:-}" "${EMBEDDING_MODEL_REVISION:-}"
check_model RERANK "${RERANK_MODEL_ID:-}" "${RERANK_MODEL_REVISION:-}"

printf 'Offline model cache checks passed for chat, embedding, and reranker.\n'
