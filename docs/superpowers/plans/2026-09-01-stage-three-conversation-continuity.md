# Stage Three Conversation Continuity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add production-grade session-scoped conversation continuity and automatically displayed, one-click suggested follow-up questions to all QA routes.

**Architecture:** Python performs local-model standalone-query resolution and structured conversation finalization. Java remains authoritative for authorization, durable session/message state, idempotency, task orchestration and safe fallbacks. Vue renders persisted suggestions and sends a clicked suggestion through the existing message path exactly once.

**Tech Stack:** Spring Boot 3, MyBatis, Flyway, MySQL 8, FastAPI, Pydantic 2, Vue 3, TypeScript, Vitest, local OpenAI-compatible Qwen endpoints.

---

### Task 1: Python conversation intelligence contracts

**Files:**
- Modify: `python-ai-service/app/models/schemas.py`
- Modify: `python-ai-service/app/services/route_context_service.py`
- Modify: `python-ai-service/app/api/routes.py`
- Test: `python-ai-service/tests/test_route_context.py`

- [ ] Add failing tests for standalone-query resolution, malformed-model fallback, safe structured summary, at-most-three suggestions, duplicate/history filtering and failed suggestion generation.
- [ ] Run `python -m pytest -q tests/test_route_context.py` and confirm the new tests fail for missing contracts.
- [ ] Add typed requests/responses and `/v1/context/resolve` plus `/v1/context/finalize` endpoints. Both use the configured local `QwenClient`; resolve falls back to the original question and finalize returns a deterministic safe summary plus an empty suggestion list on provider failure.
- [ ] Run `python -m pytest -q tests/test_route_context.py` and `python -m compileall -q app tests`.

### Task 2: Durable Java conversation state and AI adapter

**Files:**
- Create: `src/main/resources/db/migration/V25__add_qa_conversation_continuity.sql`
- Create: `src/main/java/com/xd/smartworksite/qa/domain/QaSessionMemory.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/domain/QaMessage.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/repository/QaRepository.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/mapper/QaMapper.java`
- Modify: `src/main/resources/mapper/QaMapper.xml`
- Modify: `src/main/java/com/xd/smartworksite/ai/infra/AiPythonServiceProperties.java`
- Modify: `src/main/java/com/xd/smartworksite/ai/application/AiApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/application/QaAiGateway.java`
- Test: `src/test/java/com/xd/smartworksite/qa/application/QaApplicationServiceTest.java`
- Test: `src/test/java/com/xd/smartworksite/ai/application/AiApplicationServiceTest.java`

- [ ] Add failing repository/service tests proving memory is session/project/user scoped, historical null fields remain compatible, and Python resolve/finalize calls use no-retry safe client paths.
- [ ] Run targeted Maven tests and verify failures describe the missing schema/API behavior.
- [ ] Add `qa_session_memory` and assistant-message suggestion/status/idempotency columns with unique constraints for session submit keys.
- [ ] Add domain, mapper and adapter contracts; persist only whitelisted structured summary and suggestions.
- [ ] Run targeted Maven tests until green.

### Task 3: Integrate context resolution, finalization and idempotent click submission

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/qa/dto/QaMessageSendRequest.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/dto/QaMessageResponse.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/application/QaApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/repository/QaRepository.java`
- Modify: `src/main/resources/mapper/QaMapper.xml`
- Test: `src/test/java/com/xd/smartworksite/qa/application/QaApplicationServiceTest.java`

- [ ] Add failing tests for pronoun follow-up resolution before retrieval, user correction precedence, finalize-after-success, finalize failure preserving the answer, refresh restoration, duplicate submit-key reuse, disabled-resource revalidation and new-session isolation.
- [ ] Run targeted tests and verify the intended failures.
- [ ] Resolve the standalone question before routing/retrieval while preserving the original displayed question; after answer success finalize summary/suggestions and persist them without changing successful answer state.
- [ ] Treat suggestion generation and summary failures as non-fatal; sanitize all persisted diagnostics and enforce bounded counts and lengths.
- [ ] Run all QA and AI Java tests.

### Task 4: Vue automatic suggestions and immediate click-send

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/qa.ts`
- Modify: `frontend/src/views/qa/QaView.vue`
- Modify: `frontend/src/views/qa/QaView.spec.ts`

- [ ] Add failing Vitest cases for automatic display, no empty section, click immediate send, original message preservation, double-click suppression, failure unlock, polling/refresh restoration and mobile-safe markup.
- [ ] Run `npm test -- --run frontend/src/views/qa/QaView.spec.ts` and verify failures.
- [ ] Render up to three persisted suggestions under each successful assistant answer. Clicking sends immediately with a generated idempotency key and source message ID; lock all suggestion controls until submission resolves.
- [ ] Keep manual text submission unchanged and do not show suggestions for pending/failed messages.
- [ ] Run the targeted frontend test and `npm run build`.

### Task 5: Automated gates and one final subagent review

**Files:**
- Modify only files required by concrete review findings.

- [ ] Run Python full tests and compileall.
- [ ] Run Java full tests and collect Surefire totals.
- [ ] Run frontend full tests and production build.
- [ ] Run `git diff --check` and inspect migrations/API compatibility.
- [ ] Perform exactly one final subagent review of the complete diff; fix only verified correctness, security or requirement gaps and rerun affected tests.

### Task 6: Linux deployment and real-machine acceptance

**Files:**
- Create: `docs/superpowers/reports/2026-09-01-stage-three-conversation-acceptance.md`

- [ ] Deploy the exact tested commit to `/home/xidian/sjw/smart_worksite` and start the H100 functional profile.
- [ ] Verify Python, Java, Vue, local LLM, embedding, reranker, MySQL, Redis and MinIO health before testing.
- [ ] Execute at least 40 non-Mock Chrome questions. Cover direct follow-ups, pronouns, corrections, topic switching, long-session summary, DATABASE, MIXED, refresh, new-session isolation, automatic display, click-send, double-click, insufficient evidence, partial evidence, disabled resources and controlled failures.
- [ ] Record system-execution and business-answer results separately with message IDs and expected points.
- [ ] Inspect all Java, Python, local-model, embedding, reranker, database and container logs for the acceptance time window. Fix every new exception, 5xx, timeout, OOM, restart, infinite retry or sensitive leak and rerun affected cases.
- [ ] Write the acceptance report without secrets.

### Task 7: Push verified main

**Files:** None.

- [ ] Confirm the server commit equals local tested HEAD and the worktree contains no unintended changes.
- [ ] Confirm `origin` is `https://github.com/joker-sxj/smart_worksite.git` and `origin/main` is an ancestor of tested HEAD.
- [ ] Push tested HEAD to `origin/main`.
- [ ] Verify `git ls-remote origin refs/heads/main` equals tested HEAD and report tests, real-case totals, log findings and commit.
