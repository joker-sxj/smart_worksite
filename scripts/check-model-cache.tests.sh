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
MODEL_ARTIFACT_MANIFEST=MANIFEST_PLACEHOLDER
MODEL_ARTIFACT_STATUS=VERIFIED
CHAT_MODEL_ID=Qwen/Test-Chat
CHAT_MODEL_REVISION=1111111111111111111111111111111111111111
EMBEDDING_MODEL_ID=Qwen/Test-Embedding
EMBEDDING_MODEL_REVISION=2222222222222222222222222222222222222222
RERANK_MODEL_ID=Qwen/Test-Reranker
RERANK_MODEL_REVISION=3333333333333333333333333333333333333333
PROFILE
printf '{"schemaVersion":1}\n' > "$tmp/manifest.json"
sed -i "s#MANIFEST_PLACEHOLDER#$tmp/manifest.json#" "$tmp/profile.env"

cat > "$tmp/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_DOCKER_LOG"
if [[ "$1 $2" == "image inspect" ]]; then
  [[ "${MOCK_IMAGE_PRESENT:-true}" == true ]]
  exit
fi
if [[ "$1" == run ]]; then
  if [[ "$*" == *model-artifact-manifest.py* && "${MOCK_MANIFEST_FAIL:-false}" == true ]]; then
    exit 1
  fi
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

snapshot="$tmp/cache/hub/models--Qwen--Test-Chat/snapshots/1111111111111111111111111111111111111111"
mkdir -p "$snapshot"
printf '{}\n' > "$snapshot/config.json"
printf '{}\n' > "$snapshot/tokenizer_config.json"
printf 'weights\n' > "$snapshot/model-00001-of-00002.safetensors"
printf 'weights\n' > "$snapshot/model-00002-of-00002.safetensors"
cat > "$snapshot/model.safetensors.index.json" <<'INDEX'
{"weight_map":{"layer.0":"model-00001-of-00002.safetensors","layer.1":"model-00002-of-00002.safetensors"}}
INDEX

bash "$repo_root/scripts/check-model-cache.sh" --verify-snapshot "$tmp/cache" Qwen/Test-Chat 1111111111111111111111111111111111111111
rm "$snapshot/model-00002-of-00002.safetensors"
if bash "$repo_root/scripts/check-model-cache.sh" --verify-snapshot "$tmp/cache" Qwen/Test-Chat 1111111111111111111111111111111111111111 >"$tmp/missing-shard" 2>&1; then
  echo 'expected a missing indexed shard to fail' >&2; exit 1
fi
grep -Fq 'model-00002-of-00002.safetensors' "$tmp/missing-shard"
ln -s missing-blob "$snapshot/model-00002-of-00002.safetensors"
if bash "$repo_root/scripts/check-model-cache.sh" --verify-snapshot "$tmp/cache" Qwen/Test-Chat 1111111111111111111111111111111111111111 >"$tmp/broken-link" 2>&1; then
  echo 'expected a broken weight symlink to fail' >&2; exit 1
fi
grep -Fq 'model-00002-of-00002.safetensors' "$tmp/broken-link"
rm "$snapshot/model-00002-of-00002.safetensors"
printf 'weights\n' > "$snapshot/model-00002-of-00002.safetensors"

run_check
grep -Fq 'image inspect vllm:test@sha256:' "$tmp/docker.log"
[[ "$(grep -c '^run ' "$tmp/docker.log")" == 4 ]]
grep -Fq -- '--pull never' "$tmp/docker.log"
grep -Fq -- 'test-model-cache:/cache:ro' "$tmp/docker.log"
grep -Fq 'Qwen/Test-Chat 1111111111111111111111111111111111111111' "$tmp/docker.log"
grep -Fq '/manifest.json:ro' "$tmp/docker.log"
grep -Fq 'model-artifact-manifest.py' "$tmp/docker.log"
grep -Fq -- '--network none' "$tmp/docker.log"

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

sed '/MODEL_ARTIFACT_MANIFEST=/d' "$tmp/profile.env" > "$tmp/no-manifest.env"
if PATH="$tmp/bin:$PATH" MOCK_DOCKER_LOG="$tmp/docker.log" bash "$repo_root/scripts/check-model-cache.sh" "$tmp/no-manifest.env" >"$tmp/no-manifest" 2>&1; then
  echo 'expected a missing manifest configuration to fail' >&2; exit 1
fi
grep -Fq 'MODEL_ARTIFACT_MANIFEST' "$tmp/no-manifest"

sed 's/MODEL_ARTIFACT_STATUS=VERIFIED/MODEL_ARTIFACT_STATUS=PENDING_CUSTOMER_CACHE/' "$tmp/profile.env" > "$tmp/pending.env"
if PATH="$tmp/bin:$PATH" MOCK_DOCKER_LOG="$tmp/docker.log" bash "$repo_root/scripts/check-model-cache.sh" "$tmp/pending.env" >"$tmp/pending" 2>&1; then
  echo 'expected an unapproved artifact profile to fail' >&2; exit 1
fi
grep -Fq 'PENDING_CUSTOMER_CACHE' "$tmp/pending"

if MOCK_MANIFEST_FAIL=true run_check >"$tmp/manifest-failure" 2>&1; then
  echo 'expected manifest verifier failure to stop startup' >&2; exit 1
fi
grep -Fq 'checksum verification failed' "$tmp/manifest-failure"

echo 'Model cache checks passed.'
