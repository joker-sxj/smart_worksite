#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"

cat > "$tmp/profile.env" <<'PROFILE'
MODEL_PROFILE_NAME=test-offline
VLLM_IMAGE=vllm:test@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
MODEL_CACHE_VOLUME=test-model-cache
CHAT_MODEL_ID=Qwen/Test-Chat
CHAT_MODEL_REVISION=1111111111111111111111111111111111111111
EMBEDDING_MODEL_ID=Qwen/Test-Embedding
EMBEDDING_MODEL_REVISION=2222222222222222222222222222222222222222
RERANK_MODEL_ID=Qwen/Test-Reranker
RERANK_MODEL_REVISION=3333333333333333333333333333333333333333
PROFILE

cat > "$tmp/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_DOCKER_LOG"
if [[ "$1 $2" == "image inspect" ]]; then
  [[ "${MOCK_IMAGE_PRESENT:-true}" == true ]]
  exit
fi
if [[ "$1" == run ]]; then
  [[ -z "${MOCK_MISSING_MODEL:-}" || "$*" != *"$MOCK_MISSING_MODEL"* ]]
  exit
fi
exit 1
DOCKER
chmod +x "$tmp/bin/docker"

run_check() {
  PATH="$tmp/bin:$PATH" MOCK_DOCKER_LOG="$tmp/docker.log" \
    bash "$repo_root/scripts/check-model-cache.sh" "$tmp/profile.env"
}

run_check
grep -Fq 'image inspect vllm:test@sha256:' "$tmp/docker.log"
[[ "$(grep -c '^run ' "$tmp/docker.log")" == 3 ]]
grep -Fq -- '--pull never' "$tmp/docker.log"
grep -Fq -- 'test-model-cache:/cache:ro' "$tmp/docker.log"
grep -Fq 'Qwen/Test-Chat 1111111111111111111111111111111111111111' "$tmp/docker.log"

if MOCK_IMAGE_PRESENT=false run_check >"$tmp/missing-image" 2>&1; then
  echo 'expected missing image to fail' >&2; exit 1
fi
grep -Fq 'container image is not present locally' "$tmp/missing-image"

if MOCK_MISSING_MODEL=Qwen/Test-Embedding run_check >"$tmp/missing-model" 2>&1; then
  echo 'expected missing model snapshot to fail' >&2; exit 1
fi
grep -Fq 'Qwen/Test-Embedding' "$tmp/missing-model"
grep -Fq '2222222222222222222222222222222222222222' "$tmp/missing-model"

sed 's/1111111111111111111111111111111111111111/not-a-revision/' "$tmp/profile.env" > "$tmp/invalid.env"
if PATH="$tmp/bin:$PATH" MOCK_DOCKER_LOG="$tmp/docker.log" bash "$repo_root/scripts/check-model-cache.sh" "$tmp/invalid.env" >"$tmp/invalid" 2>&1; then
  echo 'expected invalid revision to fail' >&2; exit 1
fi
grep -Fq 'CHAT_MODEL_REVISION' "$tmp/invalid"

sed 's#Qwen/Test-Chat#invalid model id#' "$tmp/profile.env" > "$tmp/invalid-id.env"
if PATH="$tmp/bin:$PATH" MOCK_DOCKER_LOG="$tmp/docker.log" bash "$repo_root/scripts/check-model-cache.sh" "$tmp/invalid-id.env" >"$tmp/invalid-id" 2>&1; then
  echo 'expected invalid model id to fail' >&2; exit 1
fi
grep -Fq 'CHAT_MODEL_ID' "$tmp/invalid-id"

echo 'Model cache checks passed.'
