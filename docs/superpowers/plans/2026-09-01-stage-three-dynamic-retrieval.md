# Stage Three Dynamic Retrieval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a bounded two-pass knowledge retrieval pipeline that returns evidence sufficiency diagnostics, refuses unsupported conclusions, and guides users to add missing conditions in the existing question box.

**Architecture:** Python owns query normalization, hybrid retrieval, evidence assessment, one bounded rewrite, duplicate detection, degradation, and retrieval diagnostics. Java keeps authorization and orchestration, persists the diagnostics with the QA message, and instructs the local model according to the evidence state. Vue renders the status and a concrete text-box recovery hint without adding new selectors or panels.

**Tech Stack:** Python 3.12/FastAPI/Pydantic/pytest, Java 17/Spring Boot/MyBatis/Flyway/JUnit, Vue 3/TypeScript/Vitest, MySQL 8, local Qwen Embedding/Reranker/Chat.

---

### Task 1: Retrieval contracts and normalization

**Files:**
- Modify: `python-ai-service/app/models/schemas.py`
- Create: `python-ai-service/app/services/retrieval_orchestrator.py`
- Test: `python-ai-service/tests/test_dynamic_retrieval.py`

- [ ] Write failing tests for standard/clause normalization, query fingerprints, evidence states, and duplicate rewrites.
- [ ] Run `python -m pytest -q tests/test_dynamic_retrieval.py` and verify failures describe missing contracts.
- [ ] Add `RetrievalAttempt`, `RetrievalDiagnostics`, and `EvidenceAssessment` response models plus pure normalization/fingerprint functions.
- [ ] Run the focused tests and commit `feat: define dynamic retrieval contracts`.

### Task 2: Bounded two-pass retrieval orchestration

**Files:**
- Modify: `python-ai-service/app/services/rag_service.py`
- Modify: `python-ai-service/app/services/retrieval_orchestrator.py`
- Modify: `python-ai-service/app/api/routes.py`
- Test: `python-ai-service/tests/test_dynamic_retrieval.py`
- Test: `python-ai-service/tests/test_structured_rag_index.py`

- [ ] Write failing async tests proving sufficient evidence stops after one pass, insufficient evidence performs one useful rewrite, identical rewrites are skipped, and candidates are merged without duplicate chunks.
- [ ] Add a retrieval orchestrator that calls existing vector/text/rerank paths, expands adjacent evidence, assesses direct support, and permits at most two attempts.
- [ ] Add component degradation: embedding failure uses text search, reranker failure retains fused ordering, and rewrite failure returns the first-pass assessment.
- [ ] Return selected records plus non-sensitive diagnostics from `/v1/rag/search`.
- [ ] Run focused RAG tests and commit `feat: orchestrate bounded dynamic retrieval`.

### Task 3: Strict evidence generation behavior

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/ai/dto/RagSearchResponse.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/application/QaApplicationService.java`
- Test: `src/test/java/com/xd/smartworksite/qa/application/QaApplicationServiceTest.java`

- [ ] Write failing tests for `SUFFICIENT`, `PARTIAL`, `INSUFFICIENT`, conflict/unknown-validity wording, and no answer-generation retry.
- [ ] Map Python diagnostics into Java DTOs and construct strict system instructions without embedding test questions, document IDs, or fixed answers.
- [ ] For `PARTIAL`, instruct the model to separate confirmed and unconfirmed aspects; for `INSUFFICIENT`, return a deterministic evidence-shortage response without asking the model to invent an answer.
- [ ] Preserve real references and stage-two context budgeting for every generated answer.
- [ ] Run Java focused tests and commit `feat: enforce strict evidence answers`.

### Task 4: Persist retrieval diagnostics

**Files:**
- Create: `src/main/resources/db/migration/V24__persist_qa_retrieval_diagnostics.sql`
- Modify: `src/main/java/com/xd/smartworksite/qa/domain/QaMessage.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/dto/QaMessageResponse.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/mapper/QaMapper.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/repository/QaRepository.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/repository/MyBatisQaRepository.java`
- Modify: `src/main/resources/mapper/qa/QaMapper.xml`
- Test: `src/test/java/com/xd/smartworksite/qa/application/QaApplicationServiceTest.java`

- [ ] Write failing persistence/mapping tests for evidence status, attempts, missing aspects, degraded components, and elapsed time.
- [ ] Add a JSON diagnostics column to `qa_message`; do not duplicate full evidence text.
- [ ] Persist diagnostics on success, partial answer, evidence shortage, timeout, and controlled degradation.
- [ ] Expose diagnostics in message/reference responses without internal endpoints or prompts.
- [ ] Run focused Java tests and commit `feat: persist QA retrieval diagnostics`.

### Task 5: Text-box recovery UI

**Files:**
- Modify: `frontend/src/views/qa/QaView.vue`
- Modify: `frontend/src/api/qa.ts`
- Test: `frontend/src/views/qa/QaView.spec.ts`

- [ ] Write failing component tests for partial/insufficient/conflict/validity/timeout labels and recovery hints.
- [ ] Render confirmed/unconfirmed status and a concise suggestion such as “请在下方问题框补充地区、时间、对象或指定标准名称后重新发送”.
- [ ] Keep the existing text box and send flow; do not add document selectors, knowledge-base buttons, or retrieval-detail panels.
- [ ] Verify a new submission creates a new message and leaves the previous message visible.
- [ ] Run frontend tests/build and commit `feat: guide evidence recovery in QA`.

### Task 6: Automated verification and review

**Files:**
- Modify only files required by findings.

- [ ] Run Python focused tests, then `python -m pytest -q` and `python -m compileall -q app`.
- [ ] Run `mvn test` and aggregate Surefire totals to prove zero failures/errors.
- [ ] Run frontend unit tests and `npm run build`.
- [ ] Run `git diff --check` and inspect the diff for permissions, data leakage, unbounded loops, hard-coded questions, and duplicated retrieval logic.
- [ ] Fix every P0/P1/P2 finding with a failing test first and commit each independent fix.

### Task 7: Remote deployment and real acceptance

**Files:**
- Create: `docs/superpowers/reports/2026-09-01-stage-three-dynamic-retrieval-acceptance.md`

- [ ] Transfer the exact commit to `/home/xidian/sjw/smart_worksite`, deploy with `./scripts/start-all.sh --model-profile h100-fp8`, and verify Java/Python/model health.
- [ ] Use Chrome at `http://172.18.12.6:5173/qa`; click “新建会话” before every independent case.
- [ ] Execute at least 40 real cases across direct clauses, formatting variants, tables, adjacent chunks, partial evidence, absent evidence, useful second retrieval, duplicate rewrite prevention, component degradation, permissions, long evidence, timeout, and text-box recovery.
- [ ] Validate `qa_message.retrieval_diagnostics_json`, references, attempt count, statuses, timing, and stage-two `contextUsage` against every case.
- [ ] Inspect Java, Python, LLM, embedding, reranker, Docker, and GPU logs for HTTP errors, loops, OOM, restarts, and secret/content leakage.
- [ ] Record per-case evidence and pass/fail results in the acceptance report; fix failures with TDD and repeat the affected matrix plus full regression.

### Task 8: Push and handoff

**Files:**
- Modify: `docs/superpowers/reports/2026-09-01-stage-three-dynamic-retrieval-acceptance.md`

- [ ] Re-run all automated suites after the last fix and capture fresh counts.
- [ ] Confirm the remote deployment commit equals the tested local commit and the worktree is clean.
- [ ] Push the verified commit directly to `joker-sxj/smart_worksite` `main`.
- [ ] Confirm `origin/main` resolves to the tested commit and provide the acceptance report and Linux verification commands.
