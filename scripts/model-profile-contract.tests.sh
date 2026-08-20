#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
failures=0

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  failures=$((failures + 1))
}

assert_file() {
  [[ -f "$repo_root/$1" ]] || fail "Missing required file: $1"
}

assert_contains() {
  local file="$1" pattern="$2" message="$3"
  grep -Eq "$pattern" "$repo_root/$file" || fail "$message"
}

load_profile_value() {
  local file="$1" key="$2"
  sed -n "s/^${key}=//p" "$repo_root/$file" | tail -n 1
}

required_files=(
  deploy/model-profiles/h100-fp8.env.example
  deploy/model-profiles/a6000x2-bf16.env.example
  deploy/docker-compose-models.yml
  scripts/check-gpu-runtime.sh
  scripts/check-local-models.sh
)
for file in "${required_files[@]}"; do assert_file "$file"; done

for file in scripts/check-gpu-runtime.sh scripts/check-local-models.sh scripts/start-all.sh scripts/status.sh; do
  [[ -f "$repo_root/$file" ]] && bash -n "$repo_root/$file" || fail "Bash syntax error or missing script: $file"
done

for profile in h100-fp8 a6000x2-bf16; do
  file="deploy/model-profiles/${profile}.env.example"
  [[ -f "$repo_root/$file" ]] || continue
  for key in MODEL_PROFILE_NAME VLLM_IMAGE CHAT_MODEL_ID CHAT_MODEL_REVISION CHAT_TENSOR_PARALLEL_SIZE CHAT_MAX_MODEL_LEN CHAT_MAX_NUM_SEQS CHAT_GPU_COUNT CHAT_CUDA_VISIBLE_DEVICES EMBEDDING_MODEL_ID EMBEDDING_MODEL_REVISION EMBEDDING_GPU_COUNT EMBEDDING_CUDA_VISIBLE_DEVICES RERANK_MODEL_ID RERANK_MODEL_REVISION RERANK_GPU_COUNT RERANK_CUDA_VISIBLE_DEVICES; do
    value="$(load_profile_value "$file" "$key")"
    [[ -n "$value" ]] || fail "$profile must define $key"
  done
  if grep -Eqi '(^|=)https?://([^/]*\.)?(openai\.com|dashscope\.aliyuncs\.com|huggingface\.co)(/|$)' "$repo_root/$file"; then
    fail "$profile must not configure a public inference endpoint"
  fi
  grep -Eq '^AI_DEPLOYMENT_MODE=LOCAL_ONLY$' "$repo_root/$file" || fail "$profile must enforce AI_DEPLOYMENT_MODE=LOCAL_ONLY"
  grep -Eq '^QWEN_BASE_URL=http://local-llm:8000/v1$' "$repo_root/$file" || fail "$profile must route chat to the local-llm service"
  grep -Eq '^QWEN_VL_ENDPOINT=http://local-llm:8000/v1/chat/completions$' "$repo_root/$file" || fail "$profile must route vision to the local multimodal model"
  grep -Eq '^QWEN_EMBEDDING_BASE_URL=http://local-embedding:8000/v1$' "$repo_root/$file" || fail "$profile must route embeddings locally"
  grep -Eq '^QWEN_RERANK_BASE_URL=http://local-reranker:8000/v1/rerank$' "$repo_root/$file" || fail "$profile must route reranking locally"
done

h100="$repo_root/deploy/model-profiles/h100-fp8.env.example"
a6000="$repo_root/deploy/model-profiles/a6000x2-bf16.env.example"
[[ -f "$h100" ]] && {
  [[ "$(load_profile_value deploy/model-profiles/h100-fp8.env.example CHAT_TENSOR_PARALLEL_SIZE)" == 1 ]] || fail 'H100 tensor parallel size must be 1.'
  [[ "$(load_profile_value deploy/model-profiles/h100-fp8.env.example CHAT_GPU_COUNT)" == 1 ]] || fail 'H100 profile must reserve one GPU for the chat model.'
  grep -Eq '^CHAT_MODEL_ID=Qwen/Qwen3\.8-27B-FP8$' "$h100" || fail 'H100 profile must use the approved Qwen3.8 27B FP8 model.'
}
[[ -f "$a6000" ]] && {
  [[ "$(load_profile_value deploy/model-profiles/a6000x2-bf16.env.example CHAT_TENSOR_PARALLEL_SIZE)" == 2 ]] || fail 'A6000x2 tensor parallel size must be 2.'
  [[ "$(load_profile_value deploy/model-profiles/a6000x2-bf16.env.example CHAT_GPU_COUNT)" == 2 ]] || fail 'A6000x2 profile must reserve two GPUs for the chat model.'
  grep -Eq '^CHAT_MODEL_ID=Qwen/Qwen3\.8-27B$' "$a6000" || fail 'A6000x2 profile must use the approved Qwen3.8 27B BF16 model.'
}

compose=deploy/docker-compose-models.yml
if [[ -f "$repo_root/$compose" ]]; then
  for service in local-llm local-embedding local-reranker; do
    assert_contains "$compose" "^  ${service}:" "Compose must define $service."
  done
  assert_contains "$compose" 'VLLM_IMAGE' 'Model images must be configurable.'
  for profile in h100-fp8 a6000x2-bf16; do
    grep -Eq '^VLLM_IMAGE=vllm/vllm-openai:v0\.27\.1-cu129@sha256:[0-9a-f]{64}$' "$repo_root/deploy/model-profiles/${profile}.env.example" || fail "$profile must pin a vLLM CUDA 12.9 image tag."
  done
  assert_contains "$compose" 'CHAT_MODEL_REVISION' 'Chat model revision must be pinned and configurable.'
  assert_contains "$compose" 'EMBEDDING_MODEL_REVISION' 'Embedding model revision must be pinned and configurable.'
  assert_contains "$compose" 'RERANK_MODEL_REVISION' 'Reranker model revision must be pinned and configurable.'
  assert_contains "$compose" 'healthcheck:' 'Every local model service must have a health check.'
  [[ "$(grep -Ec '^    ipc: host$' "$repo_root/$compose")" == 3 ]] || fail 'Every vLLM service must use host IPC for PyTorch/NCCL shared memory.'
  assert_contains "$compose" 'DOCKER_LOG_MAX_SIZE' 'Model container logs must be bounded.'
  assert_contains "$compose" 'reservations:' 'Model containers must declare GPU reservations.'
  [[ "$(grep -Ec '^      CUDA_VISIBLE_DEVICES:' "$repo_root/$compose")" == 3 ]] || fail 'Every model process must select deterministic GPUs inside the container.'
  [[ "$(grep -Ec '^              count: all$' "$repo_root/$compose")" == 3 ]] || fail 'Each model container must receive all GPUs and let CUDA_VISIBLE_DEVICES make deterministic profile selections.'
  assert_contains "$compose" 'capabilities:.*gpu|capabilities: \[gpu\]' 'Model containers must reserve NVIDIA GPU capability.'
  assert_contains "$compose" 'model-cache:' 'Model weights must use a persistent cache volume.'
  assert_contains "$compose" 'CHAT_MAX_MODEL_LEN' 'Chat context length must be configurable.'
  assert_contains "$compose" 'CHAT_MAX_NUM_SEQS' 'Chat concurrency must be configurable.'
fi

[[ -f "$repo_root/scripts/check-gpu-runtime.sh" ]] && {
  grep -q 'nvidia-smi' "$repo_root/scripts/check-gpu-runtime.sh" || fail 'GPU preflight must inspect the host GPU.'
  grep -q 'NVIDIA_RUNTIME_TEST_IMAGE' "$repo_root/scripts/check-gpu-runtime.sh" || fail 'GPU runtime test image must be configurable.'
  grep -Eq 'docker run .*--gpus' "$repo_root/scripts/check-gpu-runtime.sh" || fail 'GPU preflight must non-destructively test Docker GPU access.'
}
[[ -f "$repo_root/scripts/check-local-models.sh" ]] && {
  for dependency in chat vision embedding rerank; do
    grep -q "$dependency" "$repo_root/scripts/check-local-models.sh" || fail "Model health output must identify $dependency separately."
  done
}

grep -q -- '--model-profile' "$repo_root/scripts/start-all.sh" || fail 'Linux startup must accept --model-profile.'
grep -q 'docker-compose-models.yml' "$repo_root/scripts/lib/lifecycle.sh" || fail 'Linux lifecycle must compose model services when a profile is selected.'
grep -q 'check-gpu-runtime.sh' "$repo_root/scripts/start-all.sh" || fail 'Linux startup must run GPU preflight before starting local models.'
grep -q 'check-local-models.sh' "$repo_root/scripts/start-all.sh" || fail 'Linux startup must verify each local model dependency.'
grep -q 'check-local-models.sh' "$repo_root/scripts/status.sh" || fail 'Linux status must report local model dependencies.'


if [[ -f "$repo_root/scripts/lib/lifecycle.sh" ]]; then
  if ! bash -c 'set -euo pipefail; source "$1"; root="$2"; resolved="$(resolve_model_profile "$root" h100-fp8)"; [[ "$resolved" == "$root/deploy/model-profiles/h100-fp8.env.example" ]]; ! resolve_model_profile "$root" missing-profile >/dev/null 2>&1' bash "$repo_root/scripts/lib/lifecycle.sh" "$repo_root"; then
    fail 'Lifecycle must resolve named profiles and reject missing profiles.'
  fi

  compose_test_dir="$(mktemp -d)"
  cat > "$compose_test_dir/docker" <<'DOCKER_TEST'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$DOCKER_CAPTURE"
DOCKER_TEST
  chmod +x "$compose_test_dir/docker"
  if ! DOCKER_CAPTURE="$compose_test_dir/args" PATH="$compose_test_dir:$PATH" bash -c 'set -euo pipefail; source "$1"; root="$2"; configure_model_profile "$root" h100-fp8; docker_compose "$root" config' bash "$repo_root/scripts/lib/lifecycle.sh" "$repo_root"; then
    fail 'Lifecycle must invoke Docker Compose with the selected profile.'
  elif ! grep -Fxq "$repo_root/deploy/docker-compose-models.yml" "$compose_test_dir/args" || ! grep -Fxq "$repo_root/deploy/model-profiles/h100-fp8.env.example" "$compose_test_dir/args"; then
    fail 'Lifecycle Docker Compose invocation must include the model overlay and profile env file.'
  fi
  rm -rf "$compose_test_dir"
fi

if [[ -f "$repo_root/scripts/check-gpu-runtime.sh" && -f "$repo_root/deploy/model-profiles/h100-fp8.env.example" ]]; then
  gpu_test_dir="$(mktemp -d)"
  cat > "$gpu_test_dir/nvidia-smi" <<'NVIDIA_TEST'
#!/usr/bin/env bash
case "$*" in
  *--query-gpu=index*) printf '0\n' ;;
  *--query-gpu=driver_version*) printf '535.309.01\n' ;;
  *) printf 'NVIDIA H100 PCIe, 81559 MiB, 535.309.01\n' ;;
esac
NVIDIA_TEST
  cat > "$gpu_test_dir/docker" <<'DOCKER_TEST'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$DOCKER_CAPTURE"
DOCKER_TEST
  chmod +x "$gpu_test_dir/nvidia-smi" "$gpu_test_dir/docker"
  if ! DOCKER_CAPTURE="$gpu_test_dir/args" PATH="$gpu_test_dir:$PATH" bash "$repo_root/scripts/check-gpu-runtime.sh" "$repo_root/deploy/model-profiles/h100-fp8.env.example" >/dev/null; then
    fail 'GPU preflight must accept the configured H100 count and driver floor when Docker GPU probing succeeds.'
  elif ! grep -Fxq -- '--rm' "$gpu_test_dir/args" || ! grep -Fxq -- '--gpus' "$gpu_test_dir/args" || ! grep -Fxq 'all' "$gpu_test_dir/args"; then
    fail 'GPU preflight Docker probe must be temporary and request all GPUs.'
  fi
  rm -rf "$gpu_test_dir"
fi

if (( failures > 0 )); then
  printf '%s model profile contract check(s) failed.\n' "$failures" >&2
  exit 1
fi
printf 'Model profile contracts passed.\n'
