# Smart Worksite Local AI Acceptance Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the twelve approved on-site remediation requirements with local-only AI, structured evidence, project isolation, reproducible acceptance fixtures, adversarial review, and Windows/Linux verification.

**Architecture:** Preserve Vue -> Java -> Python boundaries. Add strict local provider configuration and a shared document/evidence foundation, then migrate Q&A, database tools, review, reports, and OCR incrementally. Deterministic code owns facts and permissions; models explain bounded evidence.

**Tech Stack:** Java 17, Spring Boot 3.3, MyBatis, Flyway, Apache POI/PDFBox, Python 3.11 containers, FastAPI, Pydantic, httpx, Vue 3, TypeScript, Vitest, Docker Compose, vLLM/SGLang-compatible endpoints, Qwen3.8-27B, Qwen3 embeddings/reranker, PaddleOCR.

---

## Execution Rules

- Work only in `C:\Users\23883\Documents\智慧工地\smart_worksite-worktrees\local-ai-acceptance-20260820` until final integration.
- Use TDD for every production behavior: add a focused failing test, run it and confirm the expected failure, implement the minimum behavior, then rerun the focused and surrounding suites.
- Do not commit customer acceptance documents or generated test results.
- Keep existing files under `C:\Users\23883\Desktop\智慧工地验收（宋经纬）` read-only.
- Do not claim Linux GPU acceptance from Windows. Linux scripts and captured Linux output are separate required evidence.
- Commit after every numbered task whose tests pass.

## Task 1: Create the External Acceptance Harness

**Files:**
- Create externally: `C:\Users\23883\Desktop\智慧工地验收（宋经纬）\2026-08-20-本地模型整改验收\00_验收说明\README.md`
- Create externally: `C:\Users\23883\Desktop\智慧工地验收（宋经纬）\2026-08-20-本地模型整改验收\fixtures\manifest.json`
- Create externally: `C:\Users\23883\Desktop\智慧工地验收（宋经纬）\2026-08-20-本地模型整改验收\expected\acceptance-cases.json`
- Create externally: `C:\Users\23883\Desktop\智慧工地验收（宋经纬）\2026-08-20-本地模型整改验收\scripts\run-api-tests.ps1`
- Create externally: `C:\Users\23883\Desktop\智慧工地验收（宋经纬）\2026-08-20-本地模型整改验收\scripts\run-linux-gpu-tests.sh`
- Create externally: `C:\Users\23883\Desktop\智慧工地验收（宋经纬）\2026-08-20-本地模型整改验收\scripts\run-offline-tests.sh`
- Create externally: `C:\Users\23883\Desktop\智慧工地验收（宋经纬）\2026-08-20-本地模型整改验收\scripts\scan-sensitive-output.ps1`

- [ ] Create the approved directory tree without overwriting existing customer files.
- [ ] Generate `manifest.json` from existing fixtures with relative source path, SHA-256, size, extension, and intended capability tag.
- [ ] Define machine-readable acceptance cases for local-only inference, XLSX/PPTX/PDF parsing, Q&A follow-ups, database evidence, multi-reference review, report consistency, project isolation, and OCR completeness.
- [ ] Add PowerShell API runner that loads `acceptance.env`, authenticates, executes case definitions, and writes timestamped JSON without printing secrets.
- [ ] Add Linux shell runners that check GPU visibility, local model health, offline model behavior, log bounds, and disk usage; destructive operations are excluded.
- [ ] Run a syntax/dry-run validation and save the baseline result as `NOT_RUN` where a live Linux service is required.

## Task 2: Add Strict Local Model Provider Configuration

**Files:**
- Create: `python-ai-service/app/core/deployment.py`
- Create: `python-ai-service/app/services/providers.py`
- Create: `python-ai-service/tests/test_local_only_configuration.py`
- Modify: `python-ai-service/app/core/settings.py`
- Modify: `python-ai-service/app/services/qwen_client.py`
- Modify: `python-ai-service/app/api/routes.py`
- Modify: `python-ai-service/app/main.py`
- Modify: `python-ai-service/.env.example`
- Modify: `deploy/.env.example`
- Modify: `deploy/docker-compose-env.yml`

- [ ] Write tests proving `LOCAL_ONLY` accepts loopback, Docker service names, and private IP endpoints.
- [ ] Run `python -m pytest tests/test_local_only_configuration.py -q` and confirm failure because deployment mode validation does not exist.
- [ ] Write tests proving public DashScope/OpenAI/arbitrary public hosts are rejected and cloud keys are not required locally.
- [ ] Add `AiDeploymentMode`, endpoint classification, sanitized dependency descriptors, and provider protocols.
- [ ] Convert the existing client into an OpenAI-compatible provider while keeping a temporary `QwenClient` alias for existing service compatibility.
- [ ] Return configured local dependency/model details from health without exposing keys.
- [ ] Change deployment examples to `AI_DEPLOYMENT_MODE=LOCAL_ONLY` and local container endpoints.
- [ ] Run the focused test, all Python tests, and compose configuration rendering.
- [ ] Commit with `feat: enforce local-only AI provider configuration`.

## Task 3: Add Linux Model Deployment Profiles and Validation

**Files:**
- Create: `deploy/model-profiles/h100-fp8.env.example`
- Create: `deploy/model-profiles/a6000x2-bf16.env.example`
- Create: `deploy/docker-compose-models.yml`
- Create: `scripts/check-gpu-runtime.sh`
- Create: `scripts/check-local-models.sh`
- Create: `scripts/model-profile-contract.tests.sh`
- Modify: `scripts/start-all.sh`
- Modify: `scripts/status.sh`
- Modify: `deploy/README.md`

- [ ] Write shell contract tests for required profile values, no public endpoints, H100 tensor parallel size 1, and A6000 tensor parallel size 2.
- [ ] Run the contract test and confirm it fails before profiles exist.
- [ ] Add configurable local model containers/endpoints, cache volumes, health checks, bounded logging, GPU reservation, context limits, and concurrency limits.
- [ ] Add a non-destructive NVIDIA Container Toolkit check using a configurable CUDA image.
- [ ] Make lifecycle scripts select a model profile without embedding secrets and report each model dependency separately.
- [ ] Run shell syntax checks, compose rendering for both profiles, and lifecycle contract tests.
- [ ] Commit with `feat: add H100 and A6000 local model profiles`.

## Task 4: Introduce the Shared Document and Evidence Model

**Files:**
- Create: `src/main/java/com/xd/smartworksite/file/domain/DocumentBlock.java`
- Create: `src/main/java/com/xd/smartworksite/file/domain/DocumentLocation.java`
- Create: `src/main/java/com/xd/smartworksite/file/domain/PreparedDocument.java`
- Create: `src/main/java/com/xd/smartworksite/file/infra/DocumentParser.java`
- Create: `src/main/java/com/xd/smartworksite/file/infra/DocumentParserRegistry.java`
- Create: `src/main/java/com/xd/smartworksite/ai/dto/EvidenceItem.java`
- Create: `src/test/java/com/xd/smartworksite/file/infra/DocumentParserRegistryTest.java`
- Create: `src/test/java/com/xd/smartworksite/ai/dto/EvidenceItemTest.java`
- Modify: `src/main/java/com/xd/smartworksite/file/infra/DocumentPreparationService.java`

- [x] Write failing tests for extension/MIME routing, ordered blocks, source location, and project identifiers.
- [x] Introduce immutable normalized document/evidence records and parser registry.
- [x] Adapt current preparation behavior through compatibility methods so existing PDF/Word flows remain green.
- [x] Run focused tests and existing file/knowledge tests.
- [x] Commit with `refactor: add shared document parsing and evidence model`.

## Task 5: Implement Excel and PowerPoint Parsing

**Files:**
- Create: `src/main/java/com/xd/smartworksite/file/infra/ExcelDocumentParser.java`
- Create: `src/main/java/com/xd/smartworksite/file/infra/PowerPointDocumentParser.java`
- Create: `src/test/java/com/xd/smartworksite/file/infra/ExcelDocumentParserTest.java`
- Create: `src/test/java/com/xd/smartworksite/file/infra/PowerPointDocumentParserTest.java`
- Modify: `src/main/java/com/xd/smartworksite/file/infra/DocumentPreparationService.java`
- Modify: `src/main/java/com/xd/smartworksite/file/domain/FileParseStatus.java`
- Modify: `frontend/src/views/file/fileParseStatus.ts`
- Modify: `frontend/src/views/knowledge/knowledgeDocumentParseState.ts`

- [ ] Create test XLSX/PPTX documents in test code with Apache POI; do not commit binary fixtures unless necessary.
- [ ] Verify failing tests require sheet/cell-range, merged-cell, cached-formula, slide, table, notes, and reading-order metadata.
- [ ] Implement parsers with bounded rows/cells/slides and no external formula execution.
- [ ] Preserve explicit `PARSING` state and idempotent retry behavior.
- [ ] Run Java and frontend parse-state tests.
- [ ] Commit with `feat: parse Excel and PowerPoint knowledge documents`.

## Task 6: Add Page-Level PDF OCR Fallback

**Files:**
- Create: `python-ai-service/app/services/document_understanding_service.py`
- Create: `python-ai-service/tests/test_document_understanding_service.py`
- Create: `src/main/java/com/xd/smartworksite/file/infra/PdfDocumentParser.java`
- Create: `src/test/java/com/xd/smartworksite/file/infra/PdfDocumentParserTest.java`
- Modify: `src/main/java/com/xd/smartworksite/review/application/ReviewDocumentTextExtractor.java`
- Modify: `src/main/java/com/xd/smartworksite/file/infra/DocumentPreparationService.java`
- Modify: `python-ai-service/app/api/routes.py`
- Modify: `python-ai-service/app/models/schemas.py`

- [ ] Write failing tests for native-text, scanned, and mixed PDFs with page-level location.
- [ ] Add local document-understanding request/response contracts and bounded page processing.
- [ ] Implement direct PDFBox extraction with per-page OCR fallback when usable text is below the configured threshold.
- [ ] Route review-template and knowledge parsing through the same parser.
- [ ] Run Python document tests and Java PDF/review parser tests.
- [ ] Commit with `feat: add page-level OCR fallback for PDFs`.

## Task 7: Enforce Project Scope Across Retrieval and Resources

**Files:**
- Create: `src/test/java/com/xd/smartworksite/security/ProjectIsolationAdversarialTest.java`
- Create: `python-ai-service/tests/test_project_scoped_vector_store.py`
- Modify: `python-ai-service/app/services/vector_store.py`
- Modify: `python-ai-service/app/services/rag_service.py`
- Modify: `src/main/java/com/xd/smartworksite/knowledge/application/KnowledgeBaseApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/application/QaApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/report/application/ReportApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/review/application/ComplianceReviewApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/ocr/application/OcrApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/task/application/TaskWorkerApplicationService.java`

- [ ] Add failing tampering tests for guessed knowledge base, file, datasource, report, review, OCR, task, download, search, delete, and retry identifiers.
- [ ] Require project ID, knowledge base ID, document ID, and chunk ID in vector records and filters.
- [ ] Revalidate project ownership at task execution and resource download time.
- [ ] Ensure unauthorized and nonexistent cross-project resources have non-leaking responses.
- [ ] Run security, knowledge, report, review, OCR, task, and Python vector tests.
- [ ] Commit with `fix: enforce project isolation across AI resources`.

## Task 8: Add Contextual Q&A Follow-Ups and Evidence

**Files:**
- Create: `src/main/java/com/xd/smartworksite/qa/dto/QaFollowUpQuestion.java`
- Create: `src/test/java/com/xd/smartworksite/qa/application/QaConversationContextTest.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/application/QaApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/qa/dto/QaAskResponse.java`
- Modify: `python-ai-service/app/models/schemas.py`
- Modify: `python-ai-service/app/services/route_context_service.py`
- Modify: `python-ai-service/app/services/model_service.py`
- Modify: `python-ai-service/tests/test_api.py`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/qa.ts`
- Modify: `frontend/src/views/qa/QaView.vue`

- [ ] Write failing tests distinguishing clarification questions from optional follow-ups.
- [ ] Add bounded recent turns plus summarized older context.
- [ ] Preserve authorized route mode, knowledge bases, data sources, and session when a follow-up is selected.
- [ ] Return and render evidence citations and selectable follow-up chips/buttons.
- [ ] Run Java, Python, and frontend Q&A tests/build.
- [ ] Commit with `feat: add grounded conversational follow-up questions`.

## Task 9: Add Datasource Detail Browsing and Grounded Database Evidence

**Files:**
- Create: `src/main/java/com/xd/smartworksite/datasource/dto/DataSourceTableDetailResponse.java`
- Create: `src/main/java/com/xd/smartworksite/datasource/dto/DataSourceSampleResponse.java`
- Create: `src/test/java/com/xd/smartworksite/datasource/application/DataSourceExplorerTest.java`
- Modify: `src/main/java/com/xd/smartworksite/datasource/application/JdbcDataSourceInspector.java`
- Modify: `src/main/java/com/xd/smartworksite/datasource/application/DataSourceApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/datasource/controller/DataSourceController.java`
- Modify: `src/main/java/com/xd/smartworksite/ai/infra/SafeSqlExecutor.java`
- Modify: `src/main/java/com/xd/smartworksite/ai/application/AiApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/ai/dto/DatabaseQueryResponse.java`
- Modify: `python-ai-service/app/services/database_service.py`
- Modify: `python-ai-service/tests/test_api.py`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/datasource.ts`
- Modify: `frontend/src/views/datasource/DataSourceView.vue`

- [ ] Write failing tests for masked samples, metadata-only credentials, SQL evidence, single-statement enforcement, write rejection, comments, limits, and MySQL `DISTINCT ORDER BY` repair.
- [ ] Add bounded schema/table/sample APIs with project authorization.
- [ ] Persist and return executed SQL, tables, columns, normalized rows, repair attempts, and deterministic aggregates.
- [ ] Stop repeated identical errors early and retry only repairable generation/SQL errors.
- [ ] Render datasource details and evidence without exposing credentials.
- [ ] Run focused Java/Python tests and frontend build.
- [ ] Commit with `feat: add datasource explorer and grounded query evidence`.

## Task 10: Support Multi-Reference Review and Custom Fields

**Files:**
- Create: `src/main/resources/db/migration/V21__add_review_references_and_custom_fields.sql`
- Create: `src/main/java/com/xd/smartworksite/review/domain/ReviewReferenceFile.java`
- Create: `src/main/java/com/xd/smartworksite/review/domain/ReviewFieldDefinition.java`
- Create: `src/test/java/com/xd/smartworksite/review/application/MultiReferenceReviewTest.java`
- Create: `src/test/java/com/xd/smartworksite/review/application/ReviewFieldSchemaTest.java`
- Modify: `src/main/java/com/xd/smartworksite/review/application/ComplianceReviewApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/review/dto/ComplianceReviewCreateRequest.java`
- Modify: `src/main/java/com/xd/smartworksite/review/dto/ComplianceReviewResponse.java`
- Modify: `src/main/java/com/xd/smartworksite/review/repository/ReviewRepository.java`
- Modify: `src/main/java/com/xd/smartworksite/review/repository/MyBatisReviewRepository.java`
- Modify: `src/main/resources/mapper/ReviewMapper.xml`
- Modify: `python-ai-service/app/services/agent_tools.py`
- Modify: `python-ai-service/tests/test_api.py`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/review.ts`
- Modify: `frontend/src/views/review/ComplianceReviewView.vue`

- [ ] Write failing repository and service tests for one primary file, multiple references, multiple knowledge bases, evidence per rule, and historical schema retention.
- [ ] Add additive migrations and repository mappings.
- [ ] Retrieve/rerank evidence per review rule rather than concatenating every document.
- [ ] Add schema-driven input, extraction, and result fields with ordering, validation, confidence, evidence, and manual correction.
- [ ] Add multi-file UI and field designer without changing unrelated modules.
- [ ] Run Flyway, Java, Python, and frontend tests.
- [ ] Commit with `feat: support multi-reference review and custom fields`.

## Task 11: Generate Structured Report Tables, Charts, and Conclusions

**Files:**
- Create: `src/main/resources/db/migration/V22__add_report_variable_content_types.sql`
- Create: `src/main/java/com/xd/smartworksite/report/domain/ReportVariableContentType.java`
- Create: `src/main/java/com/xd/smartworksite/report/domain/ReportDataset.java`
- Create: `src/main/java/com/xd/smartworksite/report/application/ReportDatasetAnalyzer.java`
- Create: `src/main/java/com/xd/smartworksite/report/infra/ReportChartRenderer.java`
- Create: `src/test/java/com/xd/smartworksite/report/application/ReportDatasetAnalyzerTest.java`
- Create: `src/test/java/com/xd/smartworksite/report/application/StructuredReportGenerationTest.java`
- Modify: `pom.xml`
- Modify: `src/main/java/com/xd/smartworksite/report/application/ReportGenerationApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/report/domain/ReportVariableValue.java`
- Modify: `src/main/java/com/xd/smartworksite/report/dto/ReportVariableResponse.java`
- Modify: `src/main/java/com/xd/smartworksite/report/repository/ReportRepository.java`
- Modify: `src/main/java/com/xd/smartworksite/report/repository/MyBatisReportRepository.java`
- Modify: `src/main/resources/mapper/ReportMapper.xml`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/views/report/ReportDetailView.vue`

- [ ] Write failing tests proving table values, chart series, and conclusion inputs come from one canonical typed dataset.
- [ ] Add deterministic count, sum, percentage, risk-level, owner, date, and overdue aggregations.
- [ ] Render DOCX tables and PNG charts and insert them at typed variable placeholders.
- [ ] Send only calculated dataset/statistics to the model for interpretation.
- [ ] Preserve arbitrary variable names, partial success, failure reason, and downloadable output.
- [ ] Run report unit/integration tests and inspect generated DOCX package contents.
- [ ] Commit with `feat: add structured tables charts and report analysis`.

## Task 12: Harden Watermarked Identity-Card OCR and Preview

**Files:**
- Create: `python-ai-service/app/services/image_preprocessing.py`
- Create: `python-ai-service/app/services/identity_validation.py`
- Create: `python-ai-service/tests/test_identity_validation.py`
- Create: `python-ai-service/tests/test_watermarked_identity_pipeline.py`
- Modify: `python-ai-service/app/services/ocr_service.py`
- Modify: `python-ai-service/app/services/qwen_client.py`
- Modify: `src/main/java/com/xd/smartworksite/ocr/application/OcrRecognitionWorker.java`
- Modify: `src/main/java/com/xd/smartworksite/ocr/application/OcrApplicationService.java`
- Modify: `frontend/src/views/ocr/ocrPreview.ts`
- Modify: `frontend/src/views/ocr/ocrPreview.spec.ts`
- Modify: `frontend/src/views/ocr/OcrView.vue`

- [ ] Write failing tests for complete front/back schemas, checksum/date/sex consistency, watermark variants, missing fields, low confidence, and preview URL stability.
- [ ] Add non-destructive original/contrast/denoise/watermark-suppression variants and local OCR/vision cross-checking.
- [ ] Reconcile every result to the authoritative ordered field schema and attach validation/confidence.
- [ ] Mark inconsistent or low-confidence fields for manual confirmation.
- [ ] Keep image preview stable while status polling and render the image in details.
- [ ] Run Python, Java OCR, and frontend OCR tests/build.
- [ ] Commit with `feat: harden watermarked identity OCR`.

## Task 13: Add Governance, Security, and Adversarial Verification

**Files:**
- Create: `docs/本地大模型选型与数据治理说明.md`
- Create: `docs/本地大模型评测与验收方法.md`
- Create: `scripts/adversarial-contract.tests.ps1`
- Create: `scripts/scan-secrets.ps1`
- Modify: `README.md`
- Modify: `docs/智慧工地大模型应用系统-架构设计文档.md`
- Modify: `docs/智慧工地大模型应用系统-接口文档.md`
- Modify: `deploy/README.md`

- [ ] Document model source/revision/license/checksum, hardware profiles, selection criteria, provenance, processing, RAG boundary, and the fact that no project fine-tuning occurred unless evidence is added.
- [ ] Add adversarial tests for prompt injection, document instructions, SQL bypass, formula injection, hidden PDF text, fake citations, empty-result hallucination, oversized inputs, duplicate tasks, timeouts, and dependency restarts.
- [ ] Add repository and acceptance-output scans for secrets and sensitive log patterns.
- [ ] Verify README, route documentation, environment names, scripts, and actual implementation agree.
- [ ] Commit with `docs: add local model governance and adversarial acceptance`.

## Task 14: Full Verification, Integration, and Main Push

**Files:**
- Update externally: `C:\Users\23883\Desktop\智慧工地验收（宋经纬）\2026-08-20-本地模型整改验收\results\<timestamp>\*`
- Modify only if failures prove necessary: files owned by Tasks 2-13

- [ ] Run `mvn test` from the worktree and retain the final summary.
- [ ] Run `python -m pytest -q` from `python-ai-service` and retain the final summary.
- [ ] Run `npm test` and `npm run build` from `frontend` and retain the final summary.
- [ ] Run lifecycle, log rotation, model profile, adversarial, and secret-scan contract tests.
- [ ] Perform a requirement-by-requirement diff audit against the design specification.
- [ ] Perform an adversarial code review ordered by severity and fix every blocking/high finding with a failing regression test.
- [ ] Run Windows black-box tests against the Linux deployment.
- [ ] Run Linux H100 model load, 16K/32K memory, concurrency, restart, cache, disk, log, and offline tests; retain command output.
- [ ] Confirm no customer files, secrets, generated reports, model weights, caches, or runtime logs are staged.
- [ ] Merge the verified branch into local `main`, rerun critical smoke checks, and push `origin/main`.