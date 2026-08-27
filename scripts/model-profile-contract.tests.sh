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
  deploy/model-profiles/a6000x2-production-32k.env.example
  deploy/model-profiles/a6000x2-stable-16k.env.example
  deploy/docker-compose-models.yml
  scripts/check-gpu-runtime.sh
  scripts/check-local-models.sh
)
for file in "${required_files[@]}"; do assert_file "$file"; done

for file in scripts/check-gpu-runtime.sh scripts/check-local-models.sh scripts/start-all.sh scripts/status.sh; do
  [[ -f "$repo_root/$file" ]] && bash -n "$repo_root/$file" || fail "Bash syntax error or missing script: $file"
done

for profile in h100-fp8 a6000x2-bf16 a6000x2-production-32k a6000x2-stable-16k; do
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
  grep -Eq '^QWEN_VL_ENDPOINT=http://127\.0\.0\.1:18000/v1/chat/completions$' "$repo_root/$file" || fail "$profile must expose vision to the host-side Java parser"
  grep -Eq '^QWEN_VL_CONTAINER_ENDPOINT=http://local-llm:8000/v1/chat/completions$' "$repo_root/$file" || fail "$profile must route container-side vision to the local multimodal model"
  grep -Eq '^QWEN_EMBEDDING_BASE_URL=http://local-embedding:8000/v1$' "$repo_root/$file" || fail "$profile must route embeddings locally"
  grep -Eq '^QWEN_RERANK_BASE_URL=http://local-reranker:8000/v1/rerank$' "$repo_root/$file" || fail "$profile must route reranking locally"
done

grep -Eq '^QWEN_VL_ENDPOINT=http://127\.0\.0\.1:18000/v1/chat/completions$' \
  "$repo_root/deploy/.env.example" \
  || fail 'The default Java vision endpoint must use the host-published model port.'

grep -Eq '^QWEN_VL_CONTAINER_ENDPOINT=http://local-llm:8000/v1/chat/completions$' \
  "$repo_root/deploy/.env.example" \
  || fail 'The default Python vision endpoint must use Compose service DNS.'

grep -Fq 'QWEN_VL_ENDPOINT: ${QWEN_VL_CONTAINER_ENDPOINT:-http://local-llm:8000/v1/chat/completions}' \
  "$repo_root/deploy/docker-compose-env.yml" \
  || fail 'The Python container must receive the container-side vision endpoint.'

grep -Fq 'QWEN_RERANK_BASE_URL: ${QWEN_RERANK_BASE_URL:-http://local-reranker:8000/v1/rerank}' \
  "$repo_root/deploy/docker-compose-env.yml" \
  || fail 'The Python container reranker default must use the local-reranker service port 8000.'

if ! bash -c 'set -euo pipefail; source "$1"; CHAT_HOST_PORT=19000; QWEN_VL_ENDPOINT=http://local-vlm:8000/v1/chat/completions; normalize_host_model_endpoints; [[ "$QWEN_VL_ENDPOINT" == http://127.0.0.1:19000/v1/chat/completions ]]' bash "$repo_root/scripts/lib/lifecycle.sh"; then
  fail 'Lifecycle must migrate the legacy Docker-only vision endpoint for the host-side Java parser.'
fi


for docker_host in local-llm local-vlm smart-worksite-local-llm; do
  if ! bash -c 'set -euo pipefail; source "$1"; CHAT_HOST_PORT=19001; QWEN_VL_ENDPOINT="http://$2:8000/v1/chat/completions"; normalize_host_model_endpoints; [[ "$QWEN_VL_ENDPOINT" == http://127.0.0.1:19001/v1/chat/completions ]]' bash "$repo_root/scripts/lib/lifecycle.sh" "$docker_host"; then
    fail "Lifecycle must map Docker-only host $docker_host to the configured host-published port."
  fi
done

if ! bash -c 'set -euo pipefail; source "$1"; CHAT_HOST_PORT=19002; QWEN_VL_ENDPOINT="  \"http://127.0.0.1:18000/v1/chat/completions\"  "; normalize_host_model_endpoints; [[ "$QWEN_VL_ENDPOINT" == http://127.0.0.1:19002/v1/chat/completions ]]' bash "$repo_root/scripts/lib/lifecycle.sh"; then
  fail 'Lifecycle must trim whitespace/quotes and synchronize the default loopback endpoint with CHAT_HOST_PORT.'
fi

if ! bash -c 'set -euo pipefail; source "$1"; CHAT_HOST_PORT=19002; QWEN_VL_ENDPOINT=http://127.0.0.1:18001/v1/chat/completions; normalize_host_model_endpoints; [[ "$QWEN_VL_ENDPOINT" == http://127.0.0.1:18001/v1/chat/completions ]]' bash "$repo_root/scripts/lib/lifecycle.sh"; then
  fail 'Lifecycle must preserve an explicitly configured non-default loopback vision port.'
fi

if bash -c 'set -euo pipefail; source "$1"; QWEN_VL_ENDPOINT="[http://local-llm:8000/v1/chat/completions](http://local-llm:8000/v1/chat/completions)"; normalize_host_model_endpoints' bash "$repo_root/scripts/lib/lifecycle.sh" >/dev/null 2>&1; then
  fail 'Lifecycle must reject Markdown-formatted endpoint values copied from rendered documentation.'
fi

if ! bash -c 'set -euo pipefail; source "$1"; QWEN_VL_ENDPOINT=https://example.invalid/v1/chat/completions; normalize_host_model_endpoints; [[ "$QWEN_VL_ENDPOINT" == https://example.invalid/v1/chat/completions ]]' bash "$repo_root/scripts/lib/lifecycle.sh"; then
  fail 'Lifecycle must preserve a valid cloud endpoint.'
fi

if ! grep -q 'preflight_host_model_endpoint' "$repo_root/scripts/start-all.sh"; then
  fail 'Linux startup must preflight the normalized host-side vision endpoint before Java starts.'
fi

preflight_test_dir="$(mktemp -d)"
cat > "$preflight_test_dir/curl" <<'CURL_TEST'
#!/usr/bin/env bash
printf '%s\n' "${@: -1}" > "$PREFLIGHT_CAPTURE"
printf '%s\n' '{"data":[{"id":"smart-worksite-chat"}]}'
CURL_TEST
chmod +x "$preflight_test_dir/curl"
if ! PREFLIGHT_CAPTURE="$preflight_test_dir/url" PATH="$preflight_test_dir:$PATH" bash -c 'set -euo pipefail; source "$1"; preflight_host_model_endpoint http://127.0.0.1:19003/v1/chat/completions smart-worksite-chat' bash "$repo_root/scripts/lib/lifecycle.sh"; then
  fail 'Host model preflight must accept an endpoint that advertises the configured model.'
elif [[ "$(cat "$preflight_test_dir/url")" != http://127.0.0.1:19003/v1/models ]]; then
  fail 'Host model preflight must derive /v1/models from the chat-completions endpoint.'
fi
if PREFLIGHT_CAPTURE="$preflight_test_dir/url" PATH="$preflight_test_dir:$PATH" bash -c 'set -euo pipefail; source "$1"; preflight_host_model_endpoint http://127.0.0.1:19003/v1/chat/completions wrong-model' bash "$repo_root/scripts/lib/lifecycle.sh" >/dev/null 2>&1; then
  fail 'Host model preflight must reject an endpoint that does not advertise the configured model.'
fi
cat > "$preflight_test_dir/curl" <<'CURL_TEST'
#!/usr/bin/env bash
printf '%s\n' '{"data":[{"id":"smart-worksite-chat-old"}]}'
CURL_TEST
chmod +x "$preflight_test_dir/curl"
if PATH="$preflight_test_dir:$PATH" bash -c 'set -euo pipefail; source "$1"; preflight_host_model_endpoint http://127.0.0.1:19003/v1/chat/completions smart-worksite-chat' bash "$repo_root/scripts/lib/lifecycle.sh" >/dev/null 2>&1; then
  fail 'Host model preflight must require an exact model id match.'
fi
cat > "$preflight_test_dir/curl" <<'CURL_TEST'
#!/usr/bin/env bash
printf '%s\n' '<html>smart-worksite-chat</html>'
CURL_TEST
chmod +x "$preflight_test_dir/curl"
if PATH="$preflight_test_dir:$PATH" bash -c 'set -euo pipefail; source "$1"; preflight_host_model_endpoint http://127.0.0.1:19003/v1/chat/completions smart-worksite-chat' bash "$repo_root/scripts/lib/lifecycle.sh" >/dev/null 2>&1; then
  fail 'Host model preflight must reject non-JSON responses.'
fi
rm -rf "$preflight_test_dir"

if ! bash -c 'set -euo pipefail; source "$1"; requires_host_model_preflight LOCAL_ONLY ""; requires_host_model_preflight CLOUD_ALLOWED /tmp/profile; ! requires_host_model_preflight CLOUD_ALLOWED ""' bash "$repo_root/scripts/lib/lifecycle.sh"; then
  fail 'Host model preflight must run for LOCAL_ONLY deployments and selected local model profiles.'
fi

if ! bash -c 'set -euo pipefail; source "$1"; validate_host_model_configuration http://127.0.0.1:18000/v1/chat/completions smart-worksite-chat; ! validate_host_model_configuration http://127.0.0.1:18000/v1/bad smart-worksite-chat >/dev/null 2>&1' bash "$repo_root/scripts/lib/lifecycle.sh"; then
  fail 'Static host model validation must reject an invalid chat-completions path without making a network call.'
fi

if ! bash -c 'set -euo pipefail; source "$1"; [[ "$(effective_qwen_vl_model "")" == qwen-vl-plus ]]; [[ "$(effective_qwen_vl_model smart-worksite-chat)" == smart-worksite-chat ]]' bash "$repo_root/scripts/lib/lifecycle.sh"; then
  fail 'Host model validation must use the same default Qwen VL model as the Java application.'
fi

if grep -q '^for command_name in .*python3' "$repo_root/scripts/start-all.sh"; then
  fail 'Cloud-only startup must not require host Python 3 when no local model preflight is needed.'
fi

grep -q 'validate_host_model_configuration' "$repo_root/scripts/start-all.sh" \
  || fail 'Check-only startup must statically validate the host model configuration.'
grep -q 'connectivity preflight was skipped' "$repo_root/scripts/start-all.sh" \
  || fail 'Check-only startup must explicitly report that live model connectivity was not tested.'
h100="$repo_root/deploy/model-profiles/h100-fp8.env.example"
a6000="$repo_root/deploy/model-profiles/a6000x2-bf16.env.example"
a6000_32k="$repo_root/deploy/model-profiles/a6000x2-production-32k.env.example"
a6000_16k="$repo_root/deploy/model-profiles/a6000x2-stable-16k.env.example"
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

for profile in a6000x2-production-32k a6000x2-stable-16k; do
  file="deploy/model-profiles/${profile}.env.example"
  [[ -f "$repo_root/$file" ]] || continue
  [[ "$(load_profile_value "$file" GPU_COUNT)" == 2 ]] || fail "$profile must declare exactly two GPUs."
  [[ "$(load_profile_value "$file" GPU_MIN_MEMORY_GB)" == 48 ]] || fail "$profile must declare a 48GB minimum GPU memory size."
  [[ "$(load_profile_value "$file" GPU_EXPECTED_MODEL_REGEX)" == "RTX A6000" ]] || fail "$profile must declare the A6000 target GPU regex."
  [[ "$(load_profile_value "$file" CHAT_TENSOR_PARALLEL_SIZE)" == 2 ]] || fail "$profile must use tensor parallelism across both A6000 GPUs."
  [[ "$(load_profile_value "$file" CHAT_GPU_COUNT)" == 2 ]] || fail "$profile must reserve two GPUs for chat."
  [[ "$(load_profile_value "$file" CHAT_CUDA_VISIBLE_DEVICES)" == "0,1" ]] || fail "$profile must make both A6000 GPUs visible to chat."
  [[ "$(load_profile_value "$file" CHAT_MODEL_ID)" == "Qwen/Qwen3.8-27B" ]] || fail "$profile must use the approved BF16 Qwen3.8 27B model."
  [[ "$(load_profile_value "$file" CHAT_MODEL_REVISION)" =~ ^[0-9a-f]{40}$ ]] || fail "$profile must pin the chat model revision."
  [[ "$(load_profile_value "$file" EMBEDDING_MODEL_REVISION)" =~ ^[0-9a-f]{40}$ ]] || fail "$profile must pin the embedding model revision to a 40-character hash."
  [[ "$(load_profile_value "$file" RERANK_MODEL_REVISION)" =~ ^[0-9a-f]{40}$ ]] || fail "$profile must pin the reranker model revision to a 40-character hash."
  [[ "$(load_profile_value "$file" VLLM_IMAGE)" =~ ^vllm/vllm-openai:v0\.27\.1-cu129@sha256:[0-9a-f]{64}$ ]] || fail "$profile must pin the vLLM image by digest."
  [[ "$(load_profile_value "$file" NVIDIA_RUNTIME_TEST_IMAGE)" =~ ^nvidia/cuda:12\.2\.2-base-ubuntu22\.04@sha256:[0-9a-f]{64}$ ]] || fail "$profile must pin the NVIDIA runtime test image by digest."
  case "$profile" in
    a6000x2-production-32k) [[ "$(load_profile_value "$file" CHAT_MAX_NUM_SEQS)" == 2 ]] || fail "$profile must set chat max sequences to 2." ;;
    a6000x2-stable-16k) [[ "$(load_profile_value "$file" CHAT_MAX_NUM_SEQS)" == 1 ]] || fail "$profile must set chat max sequences to 1." ;;
  esac
  [[ "$(load_profile_value "$file" EMBEDDING_MAX_NUM_SEQS)" -le 4 ]] || fail "$profile must keep embedding concurrency conservative."
  [[ "$(load_profile_value "$file" RERANK_MAX_NUM_SEQS)" -le 8 ]] || fail "$profile must keep rerank concurrency conservative."
done
[[ -f "$a6000_32k" ]] && [[ "$(load_profile_value deploy/model-profiles/a6000x2-production-32k.env.example CHAT_MAX_MODEL_LEN)" == 32768 ]] || fail 'A6000 production profile must provide 32K chat context.'
[[ -f "$a6000_16k" ]] && [[ "$(load_profile_value deploy/model-profiles/a6000x2-stable-16k.env.example CHAT_MAX_MODEL_LEN)" == 16384 ]] || fail 'A6000 stable profile must provide 16K chat context.'

compose=deploy/docker-compose-models.yml
if [[ -f "$repo_root/$compose" ]]; then
  for service in local-llm local-embedding local-reranker; do
    assert_contains "$compose" "^  ${service}:" "Compose must define $service."
  done
  assert_contains "$compose" 'VLLM_IMAGE' 'Model images must be configurable.'
  for profile in h100-fp8 a6000x2-bf16 a6000x2-production-32k a6000x2-stable-16k; do
    grep -Eq '^VLLM_IMAGE=vllm/vllm-openai:v0\.27\.1-cu129@sha256:[0-9a-f]{64}$' "$repo_root/deploy/model-profiles/${profile}.env.example" || fail "$profile must pin a vLLM CUDA 12.9 image tag."
  done
  assert_contains "$compose" 'CHAT_MODEL_REVISION' 'Chat model revision must be pinned and configurable.'
  assert_contains "$compose" 'EMBEDDING_MODEL_REVISION' 'Embedding model revision must be pinned and configurable.'
  assert_contains "$compose" 'RERANK_MODEL_REVISION' 'Reranker model revision must be pinned and configurable.'
  assert_contains "$compose" 'HF_ENDPOINT:.*HF_ENDPOINT' 'Model containers must receive the configurable Hugging Face download endpoint.'
  if grep -q -- '--task' "$repo_root/$compose"; then
    fail 'Model Compose must not use the removed vLLM --task argument.'
  fi
  grep -A25 '^  local-embedding:' "$repo_root/$compose" | grep -q -- '--runner' || fail 'Embedding service must select the vLLM pooling runner.'
  assert_contains "$compose" 'healthcheck:' 'Every local model service must have a health check.'
  [[ "$(grep -Ec 'test: \["CMD-SHELL", "python3 -c' "$repo_root/$compose")" == 3 ]] || fail 'Every vLLM health check must use python3 from the serving image.'
  [[ "$(grep -Ec 'vllm-cache:/root/.cache/vllm' "$repo_root/$compose")" == 3 ]] || fail 'Every vLLM service must persist its compile cache.'
  [[ "$(grep -Ec '^    ipc: host$' "$repo_root/$compose")" == 3 ]] || fail 'Every vLLM service must use host IPC for PyTorch/NCCL shared memory.'
  assert_contains "$compose" 'DOCKER_LOG_MAX_SIZE' 'Model container logs must be bounded.'
  assert_contains "$compose" 'reservations:' 'Model containers must declare GPU reservations.'
  [[ "$(grep -Ec '^      CUDA_VISIBLE_DEVICES:' "$repo_root/$compose")" == 3 ]] || fail 'Every model process must select deterministic GPUs inside the container.'
  [[ "$(grep -Ec '^              count: all$' "$repo_root/$compose")" == 3 ]] || fail 'Each model container must receive all GPUs and let CUDA_VISIBLE_DEVICES make deterministic profile selections.'
  assert_contains "$compose" 'capabilities:.*gpu|capabilities: \[gpu\]' 'Model containers must reserve NVIDIA GPU capability.'
  assert_contains "$compose" 'model-cache:' 'Model weights must use a persistent cache volume.'
  assert_contains "$compose" 'CHAT_MAX_MODEL_LEN' 'Chat context length must be configurable.'
  assert_contains "$compose" 'CHAT_MAX_NUM_SEQS' 'Chat concurrency must be configurable.'
  grep -A45 '^  local-reranker:' "$repo_root/$compose" | grep -q -- '--port' || fail 'Reranker service must explicitly configure its vLLM port.'
  grep -A45 '^  local-reranker:' "$repo_root/$compose" | grep -q -- '"8000"' || fail 'Reranker container must continue listening on port 8000.'
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
grep -q 'restart_managed_if_running.*Java backend' "$repo_root/scripts/start-all.sh" || fail 'Linux startup must restart its managed Java backend so pulled code and environment changes take effect.'
grep -q 'restart_managed_if_running.*Vue frontend' "$repo_root/scripts/start-all.sh" || fail 'Linux startup must restart its managed Vue frontend so pulled UI changes take effect.'
grep -q 'check-local-models.sh' "$repo_root/scripts/status.sh" || fail 'Linux status must report local model dependencies.'


if [[ -f "$repo_root/scripts/lib/lifecycle.sh" ]]; then
  managed_test_dir="$(mktemp -d)"
  mkdir -p "$managed_test_dir/expected"
  bash -c 'while :; do sleep 5; done' run-with-log-limit.mjs --cwd "$managed_test_dir/expected" 'npm run dev' &
  managed_test_pid=$!
  printf '%s\n' "$managed_test_pid" > "$managed_test_dir/frontend.pid"
  if ! bash -c 'set -euo pipefail; source "$1"; managed_pid "$2/frontend.pid" "$2/expected" "npm run dev" >/dev/null' bash "$repo_root/scripts/lib/lifecycle.sh" "$managed_test_dir"; then
    fail 'Managed PID detection must recognize legacy log-runner processes whose --cwd target differs from the wrapper process cwd.'
  fi
  kill "$managed_test_pid" 2>/dev/null || true
  wait "$managed_test_pid" 2>/dev/null || true
  rm -rf "$managed_test_dir"

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
