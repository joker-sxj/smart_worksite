# Upload and Parse Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Accept legitimate files up to 100 MiB and make Linux document parsing reliable when Java runs on the host and local models run in Docker.

**Architecture:** Keep upload validation byte-based and consistent across frontend and backend. Separate host and container model endpoints at lifecycle startup, preflight the host model service, and preserve deterministic office-document extraction as a successful fallback when model enrichment is unavailable. Image-only parsing remains model-dependent.

**Tech Stack:** Vue 3/TypeScript/Vitest, Java 17/Spring Boot/JUnit 5, Bash contract tests, Docker Compose/vLLM.

---

### Task 1: Unified frontend upload limit

**Files:**
- Modify: `frontend/src/components/common/AppUpload.vue`
- Modify: `frontend/src/components/common/AppUpload.spec.ts`
- Modify: `frontend/src/views/knowledge/KnowledgeBaseView.vue`
- Modify: `frontend/src/views/file/FileManagementView.vue`
- Modify: `frontend/src/views/review/ComplianceReviewView.vue`

- [ ] Add failing component tests proving a 20,770 KiB file is accepted by the default uploader and a file larger than 100 MiB is rejected with both measured and allowed sizes.
- [ ] Run `npm test -- AppUpload.spec.ts` from `frontend` and confirm the new assertions fail against the 20 MiB default/current message.
- [ ] Change the shared default to 100 MiB, compare integer bytes with `maxSizeMb * 1024 * 1024`, and format the rejection message from the same byte values.
- [ ] Pass `:max-size-mb="100"` explicitly from knowledge, file, and review upload entry points; preserve the template center's explicit 50 MiB rule and OCR's existing intentional rule.
- [ ] Re-run the focused test and confirm it passes.

### Task 2: Host/container endpoint validation

**Files:**
- Modify: `scripts/lib/lifecycle.sh`
- Modify: `scripts/start-all.sh`
- Modify: `scripts/model-profile-contract.tests.sh`
- Modify: `deploy/.env.example`
- Modify: `deploy/model-profiles/h100-fp8.env.example`
- Modify: `deploy/model-profiles/a6000x2-bf16.env.example`
- Modify: `deploy/README.md`

- [ ] Add failing shell contract cases for `local-llm`, `smart-worksite-local-llm`, a custom `CHAT_HOST_PORT`, Markdown-formatted copied URLs, and a correct host endpoint.
- [ ] Run `bash scripts/model-profile-contract.tests.sh` and confirm the new cases fail because only two exact Docker names are currently normalized and malformed values are not rejected.
- [ ] Implement endpoint parsing/normalization that trims quotes/whitespace, rejects Markdown link syntax, maps Docker-local names to `127.0.0.1:${CHAT_HOST_PORT}`, and leaves valid cloud endpoints unchanged.
- [ ] Add a local-profile preflight that derives `/v1/models` from the chat-completions endpoint and verifies it before Java startup without changing model settings.
- [ ] Update examples and deployment documentation so Java host and Python container variables remain distinct.
- [ ] Re-run shell contract tests and Compose config rendering for H100 and A6000 profiles.

### Task 3: Deterministic document parse fallback

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/file/infra/QwenVlDocumentParseAdapter.java`
- Modify: `src/test/java/com/xd/smartworksite/file/infra/QwenVlDocumentParseAdapterTest.java`
- Modify: `src/main/java/com/xd/smartworksite/file/application/FileParseWorker.java` only if metadata propagation requires it
- Modify: `src/test/java/com/xd/smartworksite/file/application/FileParseWorkerTest.java` only if worker metadata changes

- [ ] Add failing adapter tests proving extracted text falls back on connect failure, non-2xx response, malformed/empty model response, and that image-only input still fails.
- [ ] Run the focused Maven test and confirm failures occur because configured model errors currently abort every parse.
- [ ] Refactor model invocation into a narrow method and return normalized `LOCAL_TEXT_FALLBACK` output only when nonblank deterministic text exists.
- [ ] Record the failed provider/model and a bounded failure reason in metadata without leaking credentials or request content.
- [ ] Keep empty/scanned PDF and image input model-dependent; do not convert them to false successes.
- [ ] Re-run focused adapter and worker tests.

### Task 4: Verification and integration

**Files:**
- Modify: `README.md` only if root startup/test guidance differs from the corrected deployment behavior

- [ ] Run all Java tests with `mvn test`.
- [ ] Run frontend tests and build with `npm test -- --run` and `npm run build`.
- [ ] Run Python tests with the repository's existing pytest command to ensure integration contracts remain intact.
- [ ] Run lifecycle/model-profile shell tests and both Compose profile config renders.
- [ ] Inspect `git diff --check`, `git status --short`, and the final diff for unrelated changes.
- [ ] Commit the implementation and push the reviewed result to `main` only after all verification commands succeed.
