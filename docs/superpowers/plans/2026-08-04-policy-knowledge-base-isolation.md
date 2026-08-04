# Policy Knowledge Base Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every project a system-managed policy knowledge base and migrate crawler vectors out of user project knowledge bases.

**Architecture:** Add an explicit knowledge-base type and a read-only policy knowledge-base project setting. Java owns policy-library provisioning and migration orchestration; Python owns provider-specific vector deletion. Frontend selectors use the type to separate project, policy, and combined retrieval.

**Tech Stack:** Java 17, Spring Boot, MyBatis, Flyway, Vue 3, TypeScript, FastAPI, pytest, local/pgvector/Milvus vector stores.

---

### Task 1: Persist knowledge-base type and policy setting

**Files:**
- Create: `src/main/resources/db/migration/V19__isolate_policy_knowledge_base.sql`
- Modify: `src/main/java/com/xd/smartworksite/knowledge/domain/KnowledgeBase.java`
- Modify: `src/main/resources/mapper/knowledge/KnowledgeBaseMapper.xml`
- Modify: `src/main/java/com/xd/smartworksite/knowledge/dto/KnowledgeBaseResponse.java`
- Modify: `src/main/java/com/xd/smartworksite/project/dto/ProjectSettingsResponse.java`
- Modify: `src/main/java/com/xd/smartworksite/project/application/ProjectApplicationService.java`
- Test: `src/test/java/com/xd/smartworksite/project/application/ProjectApplicationServiceTest.java`

- [ ] Add failing mapping/settings tests for `knowledgeBaseType` and preserved `policyKnowledgeBaseId`.
- [ ] Run targeted Maven tests and confirm failure.
- [ ] Add `knowledge_base_type`, backfill `PROJECT`, add indexes, and map the new fields.
- [ ] Ensure project settings updates preserve the read-only policy ID.
- [ ] Run targeted tests and commit.

### Task 2: Automatically provision the project policy library

**Files:**
- Create: `src/main/java/com/xd/smartworksite/policy/application/PolicyKnowledgeBaseApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/policy/application/PolicyApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/knowledge/application/KnowledgeBaseApplicationService.java`
- Test: `src/test/java/com/xd/smartworksite/policy/application/PolicyKnowledgeBaseApplicationServiceTest.java`
- Test: `src/test/java/com/xd/smartworksite/knowledge/application/KnowledgeBaseApplicationServiceTest.java`

- [ ] Add failing tests for reuse, automatic creation, stale-setting repair, and system-library mutation rejection.
- [ ] Run tests and confirm the expected failures.
- [ ] Implement transactional resolve/create/configure behavior with the system user.
- [ ] Replace policy crawler use of `defaultKnowledgeBaseId` with the resolver.
- [ ] Reject upload/edit/disable/delete operations for POLICY libraries.
- [ ] Run targeted tests and commit.

### Task 3: Add idempotent RAG source deletion

**Files:**
- Modify: `python-ai-service/app/models/schemas.py`
- Modify: `python-ai-service/app/api/routes.py`
- Modify: `python-ai-service/app/services/rag_service.py`
- Modify: `python-ai-service/app/services/vector_store.py`
- Test: `python-ai-service/tests/test_rag.py`

- [ ] Add failing tests that delete matching policy chunks while preserving the excluded policy library and unrelated chunks.
- [ ] Run pytest and confirm failure.
- [ ] Add `RagDeleteRequest/Data`, route, service operation, and LOCAL/PGVECTOR/MILVUS implementations.
- [ ] Verify deletion is idempotent and provider filters include project, source type, source IDs, and excluded library.
- [ ] Run Python tests and commit.

### Task 4: Migrate policy vectors after successful indexing

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/ai/infra/AiPythonServiceProperties.java`
- Modify: `src/main/java/com/xd/smartworksite/ai/infra/AiPythonServiceClient.java`
- Create: `src/main/java/com/xd/smartworksite/ai/dto/RagDeleteRequest.java`
- Create: `src/main/java/com/xd/smartworksite/ai/dto/RagDeleteResponse.java`
- Modify: `src/main/java/com/xd/smartworksite/ai/application/AiApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/policy/application/PolicyApplicationService.java`
- Test: policy and AI application-service tests.

- [ ] Add failing tests proving cleanup happens only after new indexing succeeds.
- [ ] Add Java client/configuration support for `/v1/rag/delete`.
- [ ] Delete old `POLICY_ARTICLE` vectors excluding the resolved POLICY library.
- [ ] Treat cleanup failure as retryable article indexing failure.
- [ ] Run targeted and full Maven tests, then commit.

### Task 5: Separate frontend selection and question scope

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/views/knowledge/KnowledgeBaseView.vue`
- Modify: `frontend/src/views/qa/QaView.vue`
- Modify: `frontend/src/views/report/ReportListView.vue`
- Add/update Vitest tests beside the affected views.

- [ ] Add failing tests for PROJECT/POLICY/ALL knowledge-base ID selection.
- [ ] Add type badges and read-only policy-library presentation.
- [ ] Add question scope control defaulting to PROJECT and build request IDs by scope.
- [ ] Filter report selectors to PROJECT libraries.
- [ ] Run frontend tests, typecheck, and production build; commit.

### Task 6: Deploy and migrate current data

**Files:**
- Modify documentation only if startup/API behavior needs clarification.

- [ ] Run `mvn test` and `mvn -DskipTests package`.
- [ ] Run Python pytest suite.
- [ ] Run frontend tests, typecheck, and build.
- [ ] Restart services with the lifecycle scripts and verify health.
- [ ] Trigger source 10 crawl; verify a POLICY library is created and all 15 articles index successfully.
- [ ] Verify PROJECT-only search does not return `POLICY_ARTICLE`, while POLICY-only search does.
- [ ] Verify default project library changes do not affect another crawl.
- [ ] Commit any final documentation, merge to `main`, push, and verify local/remote SHAs match.
