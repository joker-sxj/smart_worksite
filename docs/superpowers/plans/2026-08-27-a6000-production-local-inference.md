# Dual A6000 Production Local Inference Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a production-oriented local-only AI inference baseline for two customer RTX A6000 48GB GPUs, with a 32K target profile, a tested 16K fallback, explicit network policy separation, runtime health visibility, and customer-executable capacity verification.

**Architecture:** Java business services call only the Python AI service; Python is the sole model-provider boundary. Local inference is enforced by configuration validation and startup/runtime gates, while policy crawling has an independent administrator-controlled network switch. Docker profiles describe the customer hardware target, and benchmark/preflight scripts report evidence rather than making unverified H100-to-A6000 performance claims.

**Tech Stack:** Java/Spring Boot, Python 3.11/FastAPI/Pydantic/httpx/pytest, Docker Compose, vLLM OpenAI-compatible endpoints, Bash, `nvidia-smi`, MySQL/Redis/MinIO existing services.

---

## Scope and non-goals

This plan implements only Stage 1: dual-A6000 local inference baseline and mandatory local-inference gate. It does not implement dynamic RAG, conversation summaries, follow-up questions, Excel/PPT parser redesign, long-document review orchestration, report charts, database permission redesign, custom review fields, project-level isolation, watermark OCR improvements, or model-training provenance. Each later capability remains a separately designed and user-accepted stage.

Production assumptions are explicit: two RTX A6000 48GB cards, no NVLink dependency, Qwen/Qwen3.8-27B BF16 for chat/vision with tensor parallelism, Qwen/Qwen3-Embedding-4B for embeddings, and Qwen/Qwen3-Reranker-0.6B for reranking. The 32K profile is a target configuration and must not be described as A6000-validated until the customer machine produces a benchmark report.

## File map

- `deploy/model-profiles/a6000x2-production-32k.env.example`: customer production target profile, 32K context and conservative concurrency.
- `deploy/model-profiles/a6000x2-stable-16k.env.example`: lower-memory recovery/stability profile.
- `deploy/model-profiles/a6000x2-bf16.env.example`: compatibility profile retained with an explicit migration note or alias behavior.
- `deploy/docker-compose-models.yml`: model service environment/health contracts and profile variable use.
- `deploy/.env.example` and `deploy/docker-compose-env.yml`: safe defaults and separate crawler-network configuration.
- `scripts/check-gpu-runtime.sh`: GPU model/count/VRAM/runtime preflight.
- `scripts/check-local-models.sh`: local model endpoint readiness and generation-boundary checks.
- `scripts/model-profile-contract.tests.sh`: static and mocked contract tests for all profiles and scripts.
- `scripts/benchmark-local-models.py`: customer-side benchmark runner and JSON report writer.
- `scripts/benchmark-local-models.tests.py`: deterministic unit tests for benchmark parsing/statistics/error handling.
- `scripts/lib/lifecycle.sh`, `scripts/start-all.sh`, `scripts/status.sh`: profile resolution, startup gate, and safe status reporting.
- `python-ai-service/app/core/deployment.py`: endpoint classification and deployment-mode policy helpers.
- `python-ai-service/app/core/settings.py`: explicit local-only, fallback, crawler-network, and profile settings.
- `python-ai-service/app/api/routes.py`: health/readiness response and settings-injected crawler service.
- `python-ai-service/app/services/policy_crawler_service.py`: independent crawler network gate.
- `python-ai-service/tests/test_local_only_configuration.py`: local-only and no-cloud-fallback tests.
- `python-ai-service/tests/test_policy_crawler.py`: crawler gate and HTTP behavior tests.
- `python-ai-service/app/services/model_service.py`, `python-ai-service/app/services/document_understanding_service.py`, and related schemas: safe model runtime status probing and provider metadata.
- `src/main/java/com/xd/smartworksite/file/infra/QwenVlDocumentParseAdapter.java`: remove direct model-provider HTTP and delegate through the Python client.
- `src/main/java/com/xd/smartworksite/file/application/FileParseWorker.java`: persist the actual provider/model returned by the Python service rather than hard-coded `QWEN_VL`.
- `src/main/java/com/xd/smartworksite/file/application/FileProperties.java` and `src/main/resources/application.yml`: remove obsolete Java cloud-model settings and retain only Python-service configuration.
- `src/main/java/com/xd/smartworksite/system/application/SystemStatusApplicationService.java`, `src/main/java/com/xd/smartworksite/system/controller/SystemController.java`, and `src/main/java/com/xd/smartworksite/system/dto/SystemDependencyHealthResponse.java`: expose safe AI dependency status.
- `src/test/java/com/xd/smartworksite/file/infra/QwenVlDocumentParseAdapterTest.java` and system service tests: prove Java calls Python only and maps failures safely.
- `src/main/java/com/xd/smartworksite/ocr/API.md`: replace stale cloud-provider delivery instructions with local-only deployment guidance.
- `docs/superpowers/specs/2026-08-27-a6000-production-local-inference-design.md`: approved design reference; do not alter its scope during implementation.

## Verification policy

Run each new test while it is intentionally failing, implement the smallest change, rerun the focused test, then run the affected module suite. Use the remote workspace at `/home/xidian/sjw/smart_worksite`; do not touch or stage the existing user files `deploy/docker-compose-models.yml.bak-20260821` and `deploy/model-profiles/h100-fp8.env.example.bak-20260821`. Never claim A6000 performance validation from the H100 host. Every task ends with a focused commit; push/PR is performed only after the user accepts the complete stage.

### Task 1: Add customer A6000 model profiles and fix profile contracts

**Files:**
- Create: `deploy/model-profiles/a6000x2-production-32k.env.example`
- Create: `deploy/model-profiles/a6000x2-stable-16k.env.example`
- Modify: `deploy/model-profiles/a6000x2-bf16.env.example`
- Modify: `deploy/docker-compose-models.yml`
- Modify: `deploy/.env.example`
- Test: `scripts/model-profile-contract.tests.sh`

- [ ] **Step 1: Write the failing contract assertions** for both profiles: exactly two GPUs, RTX A6000 target declaration, 48GB minimum per GPU, chat context `32768`/`16384`, chat max sequences `2`/`1`, pinned model revisions/images, local endpoints, and reranker port `8000`.
- [ ] **Step 2: Run `bash scripts/model-profile-contract.tests.sh`** and verify it fails because the two profiles and/or the port contract are absent.
- [ ] **Step 3: Add the profiles** with explicit values: `GPU_COUNT=2`, `GPU_MIN_MEMORY_GB=48`, `GPU_EXPECTED_MODEL_REGEX='RTX A6000'`, BF16 chat model, embedding/reranker models, `CHAT_MAX_MODEL_LEN=32768` or `16384`, and conservative memory utilization that leaves headroom for KV cache and other services. Keep `a6000x2-bf16.env.example` as a compatibility entry with a comment and deterministic mapping to the stable profile; do not silently break existing startup arguments.
- [ ] **Step 4: Fix Compose/profile variable names and the default reranker endpoint** so the container port and `QWEN_RERANK_BASE_URL` both use `http://local-reranker:8000/v1/rerank`; run the focused contract test and expect PASS.
- [ ] **Step 5: Commit** with `git add deploy/model-profiles deploy/docker-compose-models.yml deploy/.env.example scripts/model-profile-contract.tests.sh && git commit -m "feat: add dual A6000 production model profiles"`.

### Task 2: Enforce GPU hardware and memory preflight

**Files:**
- Modify: `scripts/check-gpu-runtime.sh`
- Modify: `scripts/lib/lifecycle.sh`
- Test: `scripts/model-profile-contract.tests.sh`
- Test: `scripts/check-gpu-runtime.tests.sh`

- [ ] **Step 1: Add mocked `nvidia-smi` tests** covering success for two A6000 48GB cards, failure for one card, failure for wrong model, failure below 48GB, and failure when Docker GPU runtime cannot see the cards.
- [ ] **Step 2: Run `bash scripts/check-gpu-runtime.tests.sh`** and confirm the new cases fail against the count-only implementation.
- [ ] **Step 3: Implement profile parsing and checks** using `nvidia-smi --query-gpu=index,name,memory.total,driver_version --format=csv,noheader`; compare every visible GPU against profile requirements, validate the profile's GPU count and minimum memory, and print a remediation message naming the failed field. Keep all path handling inside the repository and avoid destructive Docker actions.
- [ ] **Step 4: Run both GPU tests plus `bash scripts/model-profile-contract.tests.sh`**; expect PASS and verify existing H100 mocked tests remain green.
- [ ] **Step 5: Commit** with `git add scripts/check-gpu-runtime.sh scripts/check-gpu-runtime.tests.sh scripts/lib/lifecycle.sh scripts/model-profile-contract.tests.sh && git commit -m "feat: gate startup on model profile hardware"`.

### Task 3: Add explicit Python local-only and crawler-network policy

**Files:**
- Modify: `python-ai-service/app/core/settings.py`
- Modify: `python-ai-service/app/core/deployment.py`
- Modify: `python-ai-service/app/api/routes.py`
- Modify: `python-ai-service/app/services/policy_crawler_service.py`
- Modify: `deploy/.env.example`
- Modify: `deploy/docker-compose-env.yml`
- Test: `python-ai-service/tests/test_local_only_configuration.py`
- Test: `python-ai-service/tests/test_policy_crawler.py`

- [ ] **Step 1: Add failing tests** asserting `AI_ALLOW_REMOTE_INFERENCE=false` and `AI_ALLOW_CLOUD_FALLBACK=false` are required/effective in `LOCAL_ONLY`, remote endpoints are rejected, and a disabled crawler returns a clear configuration error without making an HTTP request. Add a separate enabled-crawler test proving the existing HTTPX behavior remains available.
- [ ] **Step 2: Run `cd python-ai-service && pytest -q tests/test_local_only_configuration.py tests/test_policy_crawler.py`** and confirm failure for missing settings/injection.
- [ ] **Step 3: Add typed settings** `ai_allow_remote_inference: bool = False`, `ai_allow_cloud_fallback: bool = False`, and `policy_crawler_network_enabled: bool = False`; validate that LOCAL_ONLY cannot enable either remote inference or cloud fallback, and pass `Settings` into `PolicyCrawlerService` from the dependency container. Raise a stable `PolicyCrawlerNetworkDisabledError` before constructing/using `httpx.AsyncClient` when disabled.
- [ ] **Step 4: Run the focused Python tests and the complete Python test suite** with `pytest -q`; expect PASS, including no-secret assertions in health/dependency output.
- [ ] **Step 5: Commit** with `git add python-ai-service deploy/.env.example deploy/docker-compose-env.yml && git commit -m "feat: enforce local inference and crawler network policy"`.

### Task 4: Add real local model readiness and safe runtime status

**Files:**
- Modify: `python-ai-service/app/api/routes.py`
- Modify: `python-ai-service/app/services/model_service.py`
- Modify: `python-ai-service/app/services/document_understanding_service.py`
- Modify: `python-ai-service/app/schemas/*.py` (only the existing response schema files used by these routes)
- Test: `python-ai-service/tests/test_api.py`
- Test: `python-ai-service/tests/test_local_only_configuration.py`

- [ ] **Step 1: Write failing API tests** for `/v1/health` configuration status versus model reachability: local endpoint configuration must be reported separately from chat/vision/embedding/rerank readiness; an unreachable model must not be reported healthy; API keys and full secret-bearing URLs must never appear.
- [ ] **Step 2: Run `cd python-ai-service && pytest -q tests/test_api.py tests/test_local_only_configuration.py`** and verify the readiness assertions fail.
- [ ] **Step 3: Implement a bounded, timeout-controlled probe** for each configured local dependency using its OpenAI-compatible health/model endpoint (without generation), return `configured`, `reachable`, `provider`, `model`, `endpointScope`, `profile`, and `maxContextTokens`, and map connection/HTTP errors to safe status strings. Keep `/v1/health` usable for liveness; expose readiness details without leaking credentials.
- [ ] **Step 4: Run focused and full Python tests**; expect PASS. Run `bash scripts/check-local-models.sh --model-profile deploy/model-profiles/a6000x2-stable-16k.env.example --wait 5` against the running services and verify each model is reported separately.
- [ ] **Step 5: Commit** with `git add python-ai-service && git commit -m "feat: expose safe local model readiness"`.

### Task 5: Migrate Java document parsing away from direct model calls

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/file/infra/QwenVlDocumentParseAdapter.java`
- Modify: `src/main/java/com/xd/smartworksite/file/application/FileParseWorker.java`
- Modify: `src/main/java/com/xd/smartworksite/file/application/FileProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/xd/smartworksite/file/infra/QwenVlDocumentParseAdapterTest.java`
- Add/modify: the existing Java `AiPythonServiceClient` tests and DTOs discovered in the file package

- [ ] **Step 1: Write failing Mockito/WireMock tests** that construct the parser with the Python client and assert text/image parsing sends requests to the Python service, never to `QWEN_VL_ENDPOINT`; add a failure test for unavailable image understanding with no cloud fallback.
- [ ] **Step 2: Run the focused Maven test** `./mvnw -Dtest=QwenVlDocumentParseAdapterTest test` and verify the old constructor/direct HTTP expectation fails.
- [ ] **Step 3: Replace provider HTTP in `QwenVlDocumentParseAdapter`** with the existing Python client call (`/v1/document/understand` or `/v1/model/invoke` according to the actual DTO contract), preserve local text extraction fallback, and return provider/model metadata from Python. Remove Java Qwen endpoint/API-key properties while preserving non-breaking configuration aliases only when the application still needs to parse old environments.
- [ ] **Step 4: Change `FileParseWorker.buildMetadata()`** to use returned provider/model values; never hard-code `QWEN_VL` for local text fallback. Run the focused test and then `./mvnw -Dtest='*file*Test' test` (or the repository's exact file-test selector) and expect PASS.
- [ ] **Step 5: Commit** with `git add src/main/java src/main/resources src/test/java && git commit -m "refactor: route document model calls through local AI service"`.

### Task 6: Add AI dependency health to Java system status

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/system/application/SystemStatusApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/system/controller/SystemController.java`
- Modify: `src/main/java/com/xd/smartworksite/system/dto/SystemDependencyHealthResponse.java`
- Modify: existing Java Python-client DTO/client files used for health calls
- Test: `src/test/java/com/xd/smartworksite/system/application/SystemStatusApplicationServiceTest.java`
- Test: controller/client tests in the existing system test package

- [ ] **Step 1: Add failing tests** for AI service reachable/unreachable, `LOCAL_ONLY`, profile/model names, context limit, and secret redaction while preserving MySQL/Redis/MinIO checks.
- [ ] **Step 2: Run `./mvnw -Dtest=SystemStatusApplicationServiceTest test`** and confirm the AI dependency assertions fail.
- [ ] **Step 3: Add a typed safe AI health section** to the existing response or a dedicated nested DTO, call Python's health/readiness endpoint with the existing client timeout, and map timeout/5xx to `DOWN` without failing unrelated dependency checks. Include only model names, local scope, profile, and context limit; omit API keys and authorization headers.
- [ ] **Step 4: Run focused system tests, the full Java test suite `./mvnw test`, and `./mvnw -DskipTests package`**; expect PASS.
- [ ] **Step 5: Commit** with `git add src/main/java src/main/resources src/test/java && git commit -m "feat: include local AI in system dependency health"`.

### Task 7: Add benchmark runner for customer A6000 evidence

**Files:**
- Create: `scripts/benchmark-local-models.py`
- Create: `scripts/benchmark-local-models.tests.py`
- Modify: `scripts/check-local-models.sh` only if it needs a shared endpoint/profile helper
- Test: `scripts/benchmark-local-models.tests.py`

- [ ] **Step 1: Write deterministic tests** for token-count request construction, streaming TTFT parsing, output tokens/sec, percentile calculation, concurrency result grouping, model error/OOM classification, and JSON report schema.
- [ ] **Step 2: Run `python3 scripts/benchmark-local-models.tests.py`** and verify failure because the runner does not exist.
- [ ] **Step 3: Implement a standard-library runner** with CLI options `--profile`, `--base-url`, `--lengths 2000,8000,16000,24000,32000`, `--concurrency 1,2`, `--runs`, `--timeout`, and `--output`. Use streaming chat requests to measure TTFT, record total duration and generated tokens/sec, run embedding and reranker smoke cases, and capture `nvidia-smi` samples when available. Write JSON containing hardware/profile/config, every sample, P50/P95, errors, OOM/timeout/restart indicators, and an explicit `validatedOnHost` field.
- [ ] **Step 4: Run the unit tests, `python3 scripts/benchmark-local-models.py --help`, and a short mocked run**; expect PASS. Do not run a 32K load test on H100 and label it A6000 validation; the full command is for the customer A6000 host after deployment.
- [ ] **Step 5: Commit** with `git add scripts/benchmark-local-models.py scripts/benchmark-local-models.tests.py && git commit -m "feat: add customer GPU inference benchmark"`.

### Task 8: Strengthen local-model startup and status gates

**Files:**
- Modify: `scripts/check-local-models.sh`
- Modify: `scripts/start-all.sh`
- Modify: `scripts/status.sh`
- Modify: `scripts/lib/lifecycle.sh`
- Test: `scripts/model-profile-contract.tests.sh`
- Test: new shell tests under `scripts/`

- [ ] **Step 1: Add failing shell tests** for profile resolution, missing local endpoint, failed 32K boundary request, model container stop, and a safe `status.sh` report that distinguishes configuration from readiness.
- [ ] **Step 2: Run the focused shell tests** and confirm current readiness-only behavior does not catch generation-boundary or profile errors.
- [ ] **Step 3: Make `start-all.sh` sequence**: resolve profile, run GPU preflight, start Compose services, wait for each local model, then run a bounded generation/embedding/rerank smoke check; exit non-zero with the failed dependency and remediation command. Keep `docker compose` file composition compatible with standalone parsing and do not add invalid `depends_on` references to services defined only in another override file.
- [ ] **Step 4: Run all shell contract tests and a controlled `./scripts/start-all.sh --check --model-profile a6000x2-stable-16k`** on the remote host; expect clear output and no lifecycle indirect-expansion error.
- [ ] **Step 5: Commit** with `git add scripts && git commit -m "fix: make local model startup gate actionable"`.

### Task 9: Update delivery documentation and local-only operational runbook

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/ocr/API.md`
- Create: `docs/superpowers/runbooks/a6000-local-inference-operations.md`
- Modify: `deploy/.env.example` if documentation reveals missing variable descriptions

- [ ] **Step 1: Add documentation checks** that fail when the tracked OCR API guide instructs users to configure DashScope/cloud API keys as the production path, or when the runbook omits the local-only gate, crawler switch, profile names, and benchmark command.
- [ ] **Step 2: Run the documentation checks** and confirm they fail against the stale cloud text.
- [ ] **Step 3: Rewrite the tracked guide** to state that Java calls Python, Python calls local model endpoints, cloud fallback is disabled in production, and OCR/document parsing reports the real provider. Add the runbook with exact commands for stable/32K profile startup, status, logs, failure recovery, crawler network enablement, and customer A6000 benchmark acceptance criteria.
- [ ] **Step 4: Run documentation checks plus `git diff --check`**; expect PASS and confirm no secrets or raw tokens are present.
- [ ] **Step 5: Commit** with `git add src/main/java/com/xd/smartworksite/ocr/API.md docs/superpowers/runbooks/a6000-local-inference-operations.md deploy/.env.example && git commit -m "docs: document A6000 local inference operations"`.

### Task 10: Full stage verification and handoff for customer acceptance

**Files:**
- Modify: `scripts/model-profile-contract.tests.sh` only for final integration assertions
- Create: `docs/superpowers/reports/2026-08-27-a6000-local-inference-verification.md`

- [ ] **Step 1: Run Python verification**: `cd python-ai-service && pytest -q`; expect all Python tests PASS, including local-only rejection, crawler switch, health redaction, and model failure behavior.
- [ ] **Step 2: Run Java verification**: `./mvnw test && ./mvnw -DskipTests package`; expect both commands PASS and confirm no Java source retains an active direct Qwen HTTP client.
- [ ] **Step 3: Run shell verification**: `bash scripts/model-profile-contract.tests.sh`, all new shell tests, `bash -n scripts/*.sh scripts/lib/*.sh`, and `git diff --check`; expect PASS.
- [ ] **Step 4: On H100, run only functional/local-gate verification**: start stable profile, stop each model in turn, test local-only rejection of a public endpoint, verify crawler on/off independently, and record that the host is H100 and not an A6000 performance acceptance result.
- [ ] **Step 5: On the customer dual-A6000 host, execute `./scripts/start-all.sh --model-profile a6000x2-production-32k`, then `python3 scripts/benchmark-local-models.py --profile deploy/model-profiles/a6000x2-production-32k.env.example --lengths 2000,8000,16000,24000,32000 --concurrency 1,2 --runs 3 --output reports/a6000-production-benchmark.json`; record TTFT, output tokens/sec, total duration, P50/P95, VRAM peak, OOM, timeout, restart, queueing, and embedding/reranker contention results.
- [ ] **Step 6: Review the report against the explicit acceptance rules**: no public inference request succeeds in LOCAL_ONLY; every model is local and reachable; 32K is either accepted with evidence or the stable 16K profile is selected with the reason; no fixed throughput promise is made without customer measurements.
- [ ] **Step 7: Commit the verification report** with `git add docs/superpowers/reports/2026-08-27-a6000-local-inference-verification.md && git commit -m "test: record local inference stage verification"`; stop here for user acceptance before any later stage or target-repository PR.

## Self-review checklist

- Spec coverage: local-only inference gate (Tasks 3, 4, 5, 6, 8, 10); independent crawler network (Task 3); 32K/16K profiles (Task 1); GPU and memory diagnosis (Task 2); runtime status (Tasks 4 and 6); customer A6000 evidence (Tasks 7 and 10); H100 limitation and no performance overclaim (Task 10); stale cloud documentation (Task 9).
- Completeness: all tasks identify concrete files, commands, expected outcomes, and commit commands; every planned behavior has an explicit verification step.
- Type/name consistency: `policy_crawler_network_enabled` maps to `POLICY_CRAWLER_NETWORK_ENABLED`; `ai_allow_remote_inference` maps to `AI_ALLOW_REMOTE_INFERENCE`; `ai_allow_cloud_fallback` maps to `AI_ALLOW_CLOUD_FALLBACK`; profile names and benchmark CLI names are used consistently throughout.
- Scope boundary: the ten tasks implement only Stage 1 and leave the ten approved later-stage business capabilities out of code changes.

## Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-27-a6000-production-local-inference.md`. Two execution options:

1. **Subagent-Driven (recommended)** - dispatch a fresh subagent per task, review between tasks, and keep each commit small.
2. **Inline Execution** - execute tasks in this session with task-by-task checkpoints and user acceptance gates.

Choose one approach before any feature code is changed.