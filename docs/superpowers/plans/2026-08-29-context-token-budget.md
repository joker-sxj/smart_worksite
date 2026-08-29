# Context Token Budget and Overflow Protection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure every ordinary and knowledge-grounded QA model request is planned against the configured 16K/32K local-model window, preserves mandatory content, trims history by complete turns, packs structured evidence by budget, exposes non-sensitive usage diagnostics, and fails explicitly instead of silently truncating or reaching the model with an oversized request.

**Architecture:** Java remains responsible for session history, project authorization, RAG lookup, task state, and persistence; it sends bounded history candidates and structured evidence to Python. Python owns token counting, context planning, final prompt construction, and model parameters. A conservative offline estimator always works; the local vLLM `/tokenize` endpoint is used when available to validate the assembled chat request without introducing public-network access.

**Tech Stack:** Java 17, Spring Boot 3.3, Python 3.11, FastAPI/Pydantic/httpx/pytest, Vue 3/TypeScript, Docker Compose, vLLM OpenAI-compatible local model service, Maven.

---

## File map

- Create `python-ai-service/app/services/token_counter.py`: conservative estimation, local vLLM exact counting adapter, count-mode metadata, context-length error recognition.
- Create `python-ai-service/app/services/context_budget.py`: immutable budget inputs/results, turn-safe history selection, evidence deduplication/truncation, final budget validation.
- Modify `python-ai-service/app/core/settings.py`: context-budget configuration and local-only validation.
- Modify `python-ai-service/app/models/schemas.py`: structured evidence request type and context-usage response-compatible data.
- Modify `python-ai-service/app/services/model_service.py`: invoke planner before model calls and merge `contextUsage` into usage.
- Modify `python-ai-service/app/services/qwen_client.py`: local `/tokenize` request and context-length error normalization.
- Modify `python-ai-service/app/api/errors.py` or current exception mapping location: expose `CONTEXT_BUDGET_EXCEEDED` without content leakage.
- Modify `python-ai-service/app/api/routes.py`: construct/inject planner if services are manually instantiated.
- Create `python-ai-service/tests/test_token_counter.py`: estimator and local tokenize contract tests.
- Create `python-ai-service/tests/test_context_budget.py`: 2K/8K/16K/24K/32K boundaries and packing rules.
- Modify `python-ai-service/tests/test_api.py`: model API integration, usage and error contract tests.
- Modify `src/main/java/com/xd/smartworksite/ai/dto/ModelInvokeRequest.java`: structured evidence items.
- Create `src/main/java/com/xd/smartworksite/ai/dto/ModelEvidenceItem.java`: evidence payload DTO.
- Modify `src/main/java/com/xd/smartworksite/qa/application/QaApplicationService.java`: bounded history candidates, evidence conversion, usage persistence.
- Modify `src/main/java/com/xd/smartworksite/qa/domain/QaMessage.java` and repository/migration only if usage is not currently persisted separately; otherwise reuse the existing response JSON field.
- Modify `src/test/java/com/xd/smartworksite/qa/application/QaApplicationServiceTest.java`: history/evidence/usage/error behavior.
- Modify `src/test/java/com/xd/smartworksite/ai/application/AiApplicationServiceTest.java`: DTO forwarding and error mapping.
- Modify `deploy/model-profiles/a6000x2-production-32k.env.example` and `deploy/model-profiles/a6000x2-stable-16k.env.example`: explicit budget defaults.
- Modify `.env.example` or `deploy/.env.example`: documented non-secret defaults.
- Create `scripts/context-budget-acceptance.py`: real API scenario runner and evidence report writer.
- Create `docs/superpowers/runbooks/context-budget-acceptance.md`: Linux/Chrome acceptance procedure.

## Task 1: Configuration contract and failing budget tests

**Files:**
- Modify: `python-ai-service/app/core/settings.py`
- Create: `python-ai-service/tests/test_context_budget.py`
- Modify: `python-ai-service/tests/test_settings.py` if present, otherwise `python-ai-service/tests/test_api.py`

- [ ] **Step 1: Add failing tests for valid 16K/32K defaults**

Test that `CHAT_MAX_MODEL_LEN` is mandatory for LOCAL_ONLY model invocation, defaults derive output/safety reserves from 16384 and 32768, and explicit reserve values override derived defaults.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
cd python-ai-service
pytest -q tests/test_context_budget.py -k settings
```

Expected: failure because context-budget settings and validation do not exist.

- [ ] **Step 3: Add settings without changing runtime behavior**

Add positive integer/ratio fields:

```python
context_output_reserve_tokens: int = 0
context_safety_reserve_tokens: int = 0
context_template_overhead_tokens: int = 256
context_history_budget_ratio: float = 0.30
context_evidence_budget_ratio: float = 0.70
context_tokenizer_endpoint_enabled: bool = True
context_require_exact_tokenizer: bool = False
context_history_candidate_limit: int = 100
```

Expose resolved helper methods for output reserve, safety reserve, and total fixed reserve. Reject invalid ratios, non-positive configured model windows in LOCAL_ONLY, and fixed reserves that consume the entire model window.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run:

```bash
pytest -q tests/test_context_budget.py -k settings
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit the configuration contract**

```bash
git add python-ai-service/app/core/settings.py python-ai-service/tests/test_context_budget.py
git commit -m "feat: define context budget configuration"
```

## Task 2: Token counting with exact-local and conservative modes

**Files:**
- Create: `python-ai-service/app/services/token_counter.py`
- Create: `python-ai-service/tests/test_token_counter.py`
- Modify: `python-ai-service/app/services/qwen_client.py`

- [ ] **Step 1: Write failing conservative-estimator tests**

Cover Chinese prose, English words, numeric clauses, code, Markdown tables, empty input, and monotonicity. Assert the estimator never returns zero for non-empty text and labels results `ESTIMATED/conservative-v1`.

- [ ] **Step 2: Verify estimator tests are RED**

```bash
cd python-ai-service
pytest -q tests/test_token_counter.py -k conservative
```

Expected: import or symbol failure.

- [ ] **Step 3: Implement the deterministic conservative estimator**

Use a Unicode-aware, dependency-free estimator that counts CJK characters, alphanumeric runs, punctuation, newlines and chat-message overhead separately. Bias upward and document the formula in code.

- [ ] **Step 4: Verify conservative tests are GREEN**

```bash
pytest -q tests/test_token_counter.py -k conservative
```

- [ ] **Step 5: Write failing local `/tokenize` contract tests**

Use `respx` to verify:

- the URL is derived from a local `/v1` base URL without contacting public hosts;
- chat messages are sent when supported;
- prompt fallback is accepted for older vLLM response shapes;
- timeout/404 uses estimated mode unless exact mode is required;
- response text or Authorization never appears in raised errors.

- [ ] **Step 6: Verify exact-counter tests are RED**

```bash
pytest -q tests/test_token_counter.py -k vllm
```

- [ ] **Step 7: Implement local vLLM counting**

Add `QwenClient.count_chat_tokens(messages)` calling the local tokenize endpoint and returning `TokenCount(tokens, mode, tokenizer)`. Do not retry public endpoints and do not include request content in errors. Wrap it in a counter that falls back to `ConservativeTokenCounter` only when policy allows.

- [ ] **Step 8: Run all token-counter tests**

```bash
pytest -q tests/test_token_counter.py
```

- [ ] **Step 9: Commit token counting**

```bash
git add python-ai-service/app/services/token_counter.py python-ai-service/app/services/qwen_client.py python-ai-service/tests/test_token_counter.py
git commit -m "feat: count local model context tokens"
```

## Task 3: Context planner core

**Files:**
- Create: `python-ai-service/app/services/context_budget.py`
- Modify: `python-ai-service/tests/test_context_budget.py`

- [ ] **Step 1: Write failing mandatory-budget tests**

Define the desired API with `ContextBudgetRequest`, `EvidenceItem`, `ContextBudgetResult`, and `ContextBudgetExceeded`. Test that system prompt, current question, template overhead, output reserve and safety reserve are mandatory, and oversized mandatory content raises a sanitized error.

- [ ] **Step 2: Verify mandatory tests are RED**

```bash
pytest -q tests/test_context_budget.py -k "mandatory or oversized"
```

- [ ] **Step 3: Implement the minimum budget arithmetic**

Compute available optional tokens only after fixed content. Include resolved limits and count metadata in `context_usage`; never include input content in exceptions or diagnostics.

- [ ] **Step 4: Verify mandatory tests are GREEN**

```bash
pytest -q tests/test_context_budget.py -k "mandatory or oversized"
```

- [ ] **Step 5: Write failing history-turn tests**

Cover complete user/assistant pairs, system messages in supplied history, orphan assistants, incomplete final user messages, newest-first selection, 100-message candidate input, and exact boundary fits.

- [ ] **Step 6: Verify history tests are RED**

```bash
pytest -q tests/test_context_budget.py -k history
```

- [ ] **Step 7: Implement turn-safe history packing**

Normalize history to complete turns, discard invalid roles, choose newest complete turns within the history budget, then restore chronological order. Let unused evidence capacity flow to history only after the first allocation pass.

- [ ] **Step 8: Verify history tests are GREEN**

```bash
pytest -q tests/test_context_budget.py -k history
```

- [ ] **Step 9: Write failing evidence-packing tests**

Cover duplicate chunk IDs, duplicate normalized content, relevance order, oversized single evidence, sentence-boundary truncation, unreadably short remainder, table metadata, source metadata, and unused-history budget flowing to evidence.

- [ ] **Step 10: Verify evidence tests are RED**

```bash
pytest -q tests/test_context_budget.py -k evidence
```

- [ ] **Step 11: Implement evidence packing**

Deduplicate before packing; preserve the input ranking; truncate at paragraph/sentence boundaries with a deterministic minimum useful length; retain immutable metadata and mark truncation. Revalidate the fully assembled chat through exact local counting when available, dropping the lowest-priority optional item until it fits.

- [ ] **Step 12: Run boundary matrix tests**

```bash
pytest -q tests/test_context_budget.py
```

Expected: tests cover synthetic 2048, 8192, 16384, 24576 and 32768 windows without allocating enormous fixture strings.

- [ ] **Step 13: Commit the planner**

```bash
git add python-ai-service/app/services/context_budget.py python-ai-service/tests/test_context_budget.py
git commit -m "feat: plan model context within token budgets"
```

## Task 4: Python model API integration

**Files:**
- Modify: `python-ai-service/app/models/schemas.py`
- Modify: `python-ai-service/app/services/model_service.py`
- Modify: `python-ai-service/app/api/routes.py`
- Modify: `python-ai-service/app/main.py` if dependency construction occurs there
- Modify: `python-ai-service/tests/test_api.py`

- [ ] **Step 1: Write failing API tests for structured evidence and usage**

Submit model requests with history plus evidence and assert the provider receives only planned content, `max_tokens` equals the reserve, and response usage contains `contextUsage` with no source text.

- [ ] **Step 2: Verify integration tests are RED**

```bash
pytest -q tests/test_api.py -k context_budget
```

- [ ] **Step 3: Add backward-compatible request schemas**

Add `ModelEvidenceItem` and optional `evidenceItems` to `ModelInvokeRequest`. Preserve existing callers that send only `prompt` and `contextMessages`.

- [ ] **Step 4: Integrate the planner**

Construct the final messages after planning. For knowledge calls, format only selected evidence with stable source markers. Merge provider usage and `contextUsage`; never overwrite provider prompt/completion token values.

- [ ] **Step 5: Add explicit error mapping**

Map `ContextBudgetExceeded` to `VALIDATION_ERROR` plus stable detail code `CONTEXT_BUDGET_EXCEEDED`, or use the repository's existing application-error envelope without introducing a second envelope. Ensure the body includes limits/counts only.

- [ ] **Step 6: Verify API tests are GREEN**

```bash
pytest -q tests/test_api.py -k context_budget
```

- [ ] **Step 7: Run Python full regression**

```bash
pytest -q
python -m compileall -q app
```

- [ ] **Step 8: Commit Python integration**

```bash
git add python-ai-service/app python-ai-service/tests
git commit -m "feat: enforce context budgets for model calls"
```

## Task 5: Java structured evidence and history candidates

**Files:**
- Create: `src/main/java/com/xd/smartworksite/ai/dto/ModelEvidenceItem.java`
- Modify: `src/main/java/com/xd/smartworksite/ai/dto/ModelInvokeRequest.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/application/QaApplicationService.java`
- Modify: `src/test/java/com/xd/smartworksite/qa/application/QaApplicationServiceTest.java`
- Modify: `src/test/java/com/xd/smartworksite/ai/application/AiApplicationServiceTest.java`

- [ ] **Step 1: Write failing Java tests for candidate history**

Create more than 10 completed messages and assert up to 100 candidates from the current session are forwarded, failed/pending messages are excluded, and a new session forwards no old history.

- [ ] **Step 2: Verify Java history tests are RED**

```bash
mvn -q -Dtest=QaApplicationServiceTest test
```

- [ ] **Step 3: Replace the fixed 10-message model limit with a bounded candidate limit**

Keep the repository read bounded to 100 successful messages. Do not implement token decisions in Java.

- [ ] **Step 4: Verify Java history tests are GREEN**

```bash
mvn -q -Dtest=QaApplicationServiceTest test
```

- [ ] **Step 5: Write failing structured-evidence tests**

Assert each RAG record becomes a `ModelEvidenceItem` with content, title, source/chunk identity, score and location metadata. Assert the legacy giant evidence prompt is not built when evidence items are supplied.

- [ ] **Step 6: Verify evidence tests are RED**

```bash
mvn -q -Dtest=QaApplicationServiceTest,AiApplicationServiceTest test
```

- [ ] **Step 7: Add the DTO and conversion**

Forward evidence in ranking order. Keep references persisted independently of whether the final budget selects every candidate; selected-evidence diagnostics remain in Python usage.

- [ ] **Step 8: Preserve context usage in the QA result**

Reuse the current response/request JSON or message metadata mechanism after inspecting the domain. Add a migration only if no safe existing field can preserve usage. Ensure API consumers remain backward compatible.

- [ ] **Step 9: Run focused Java tests**

```bash
mvn -q -Dtest=QaApplicationServiceTest,AiApplicationServiceTest test
```

- [ ] **Step 10: Commit Java integration**

```bash
git add src/main/java/com/xd/smartworksite/ai src/main/java/com/xd/smartworksite/qa src/test/java/com/xd/smartworksite/ai src/test/java/com/xd/smartworksite/qa src/main/resources/db/migration
git commit -m "feat: send budgetable QA context to local AI"
```

## Task 6: Profile, health, and operations contract

**Files:**
- Modify: `deploy/model-profiles/a6000x2-production-32k.env.example`
- Modify: `deploy/model-profiles/a6000x2-stable-16k.env.example`
- Modify: `deploy/.env.example`
- Modify: `scripts/model-profile-contract.tests.sh`
- Modify: `src/main/java/com/xd/smartworksite/system/application/SystemStatusApplicationService.java` only if budget status is not already visible through AI readiness
- Create: `docs/superpowers/runbooks/context-budget-acceptance.md`

- [ ] **Step 1: Add failing profile-contract assertions**

Assert both A6000 profiles define explicit output/safety/template reserves and that their total fixed reserve is below `CHAT_MAX_MODEL_LEN`.

- [ ] **Step 2: Verify profile contract is RED**

```bash
bash scripts/model-profile-contract.tests.sh
```

- [ ] **Step 3: Add profile values and health metadata**

Use 32K defaults `4096/1024/256` and 16K defaults `3072/512/256`. Expose count mode, model window and reserve values without paths, secrets or content.

- [ ] **Step 4: Write the operations runbook**

Document startup, readiness checks, 16K fallback, log checks, context-usage inspection, and exact-tokenizer failure diagnosis.

- [ ] **Step 5: Run profile and focused health tests**

```bash
bash scripts/model-profile-contract.tests.sh
mvn -q -Dtest=SystemStatusApplicationServiceTest test
```

- [ ] **Step 6: Commit deployment contract**

```bash
git add deploy scripts/model-profile-contract.tests.sh src/main/java/com/xd/smartworksite/system src/test/java/com/xd/smartworksite/system docs/superpowers/runbooks/context-budget-acceptance.md
git commit -m "ops: configure context budgets for A6000 profiles"
```

## Task 7: Real scenario acceptance tooling

**Files:**
- Create: `scripts/context-budget-acceptance.py`
- Create: `python-ai-service/tests/test_context_budget_acceptance_script.py`
- Create at runtime only, do not commit: `reports/context-budget-acceptance-<timestamp>.json`
- Create at runtime only, do not commit: `reports/context-budget-acceptance-<timestamp>.md`

- [ ] **Step 1: Write failing script tests**

Test scenario loading, authentication/header redaction, per-scenario new-session behavior, multi-turn grouping, expected-evidence predicates, context-usage collection and JSON/Markdown report rendering. HTTP behavior may use a local fake server in tests; acceptance data must not be hard-coded into production services.

- [ ] **Step 2: Verify script tests are RED**

```bash
cd python-ai-service
pytest -q tests/test_context_budget_acceptance_script.py
```

- [ ] **Step 3: Implement the acceptance runner**

Support a scenario file supplied at runtime. Require explicit base URL and credentials from ignored environment variables. Do not print secrets. Fail the process if any scenario fails evidence, status, usage or overflow assertions.

- [ ] **Step 4: Verify script tests are GREEN**

```bash
pytest -q tests/test_context_budget_acceptance_script.py
```

- [ ] **Step 5: Commit acceptance tooling**

```bash
git add scripts/context-budget-acceptance.py python-ai-service/tests/test_context_budget_acceptance_script.py
git commit -m "test: add context budget acceptance runner"
```

## Task 8: Full local verification, Linux deployment, Chrome validation, and push

**Files:**
- Modify only if tests reveal defects; every defect requires a failing regression test first.
- Runtime reports remain untracked unless the user explicitly asks to commit sanitized evidence.

- [ ] **Step 1: Run all repository verification locally**

```bash
cd python-ai-service
pytest -q
python -m compileall -q app
cd ..
mvn -q test
npm --prefix frontend test -- --run
npm --prefix frontend run build
git diff --check
```

Expected: zero failed tests, successful frontend build, clean whitespace check.

- [ ] **Step 2: Review production code for prohibited specialization**

Search for the 30 acceptance questions, expected answers, real file names, document IDs and hard-coded knowledge-base IDs outside tests/reports. Any match in production code blocks deployment.

- [ ] **Step 3: Merge the isolated branch into local `main` only after verification**

Use a fast-forward merge where possible. Do not alter or add user `.bak` files.

- [ ] **Step 4: Deploy to the Linux server**

Pull/transfer the exact verified commit, rebuild Python and Java services with the active local-model profile, and wait for all dependency health checks. Confirm the host is the H100 verification host and do not report its throughput as A6000 performance.

- [ ] **Step 5: Run automated real API acceptance**

Run at least 30 scenarios using the two real indexed PDFs, including the original 20-question regression, multi-turn cases, 2K/8K/16K/24K inputs, near-limit inputs and explicit overflow. Record usage, routes, references and expected evidence.

- [ ] **Step 6: Run visible Chrome acceptance**

For each independent scenario click `新建会话` before asking. For each multi-turn scenario, continue only within that scenario, then create a new session. Inspect visible answers, sources, errors and task state.

- [ ] **Step 7: Inspect Linux logs and runtime state**

Check Java, Python AI, chat, embedding and reranker logs for new errors, context-length failures, OOM, restarts, 4xx/5xx and secret/content leakage. Capture sanitized `docker compose ps`, health output and GPU memory status.

- [ ] **Step 8: Re-run regression after any fix**

Every observed defect first gets a failing automated test, then the minimal fix, focused tests, full local tests, redeploy, and the affected real scenarios plus the original 20-question regression.

- [ ] **Step 9: Run final verification on the exact commit to push**

Repeat Python, Java, frontend, `git diff --check`, production-hardcoding scan, service health, 30-scenario report and Chrome critical-path checks. Verify `git status` contains no unintended files.

- [ ] **Step 10: Push directly to the requested repository**

```bash
git push origin main
```

Push only after every preceding gate passes. Report the commit hash, test counts, real scenario counts, service health, residual limitations and exact Linux reproduction commands.

## Self-review

- Spec coverage: Tasks 1-4 implement unified Python budgeting, exact/estimated count modes, no silent current-question truncation, turn-safe history and evidence packing. Task 5 integrates Java without duplicating token logic. Task 6 covers A6000 profiles and observability. Tasks 7-8 cover real, repeatable acceptance.
- Scope control: dynamic topK, query rewriting, conversation summaries, follow-up questions, database-result compression, review/report orchestration and UI configuration are explicitly absent.
- Security: no task logs prompts, credentials or source bodies; Java remains the authorization boundary; exact counting uses only the configured local model endpoint.
- Compatibility: existing model callers can omit `evidenceItems`; response diagnostics reuse `usage`; frontend behavior remains unchanged.
- TDD: every production change is preceded by a focused failing test and a red/green command.
- No placeholders: each task lists exact target files, behavior, commands, expected outcomes and commit boundaries.
