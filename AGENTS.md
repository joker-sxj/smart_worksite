# AGENTS.md

This file describes collaboration rules for the Smart Worksite backend. Read `README.md` first for the project overview, startup steps, and module list.

## Project Positioning

This repository contains the Smart Worksite large-model application. The root-level `src/`, `pom.xml`, and `deploy/` are the Java Spring Boot backend main system. Frontend work must live under `frontend/`.

The target system covers project data, knowledge bases, Q&A, compliance review, report generation, OCR recognition, async tasks, permission security, and audit tracing. Enterprise business capabilities belong in the Java backend; large-model, Agent, RAG, Embedding, OCR algorithm, vector retrieval, and document-parsing capabilities belong in external Python intelligent algorithm services and are integrated by the Java backend through REST/internal service adapters. Frontend must not directly call Python services, databases, MinIO, vector databases, or OCR engines.

## Tech Stack

Backend main system:

- Java 17
- Spring Boot 3.3.x
- Spring Web for REST APIs
- Spring Validation for Bean Validation
- Spring Data Redis for Redis access
- Spring Boot Actuator for health checks and runtime status
- Maven for build and dependency management
- MyBatis + XML for data access
- PageHelper for pagination
- MySQL Connector/J for MySQL access
- Flyway for database migrations
- MinIO Java SDK for object storage
- Apache PDFBox for PDF processing
- Apache POI for Word, Excel, and PPT processing

Frontend application:

- Vue 3
- TypeScript
- Vite
- Pinia
- Vue Router
- Axios
- Element Plus
- `@element-plus/icons-vue`
- `unplugin-auto-import` and `unplugin-vue-components` for Element Plus on-demand imports
- npm

Python intelligent algorithm service:

- Python
- Large-model calls for Q&A, compliance review, and report generation
- Agent orchestration for task decomposition, tool calling, multi-step reasoning, and business workflow coordination
- RAG retrieval augmentation for project knowledge bases, policy/standard libraries, and industry material libraries
- Embedding vectorization for document chunks and semantic retrieval
- OCR recognition for ID cards, license plates, invoices, contracts, and custom fields
- Document parsing for content extraction, layout understanding, and table recognition
- Java DOCX template rendering with Python model-based variable generation as the current report-generation integration

Data and infrastructure:

- MySQL 8 for business metadata, permission data, task data, audit logs, and file metadata
- Redis 7 for cache, lightweight queues, distributed locks, and task status acceleration
- MinIO for documents, images, templates, and generated reports
- Docker Compose for local MySQL, Redis, and MinIO
- Milvus or pgvector for planned knowledge-base vector retrieval

Integration boundaries:

- Frontend calls only Java backend REST APIs.
- Java backend owns authentication, authorization, project isolation, business orchestration, status records, file persistence, and audit tracing.
- Java backend integrates Python intelligent algorithm services through REST APIs or internal service interfaces.
- Large models, OCR engines, vector databases, business databases, and object storage are not directly exposed to the frontend. Qwen API keys must be configured only in `python-ai-service/`; Java calls the Python service and must not call Qwen directly for the AI adapter module.
- Cross-service calls must record request summary, response summary, elapsed time, status, and error information.
- Audit log writes must check affected rows and generated IDs; audit actions must not be reported as recorded if the audit row cannot be persisted.
- AI external call logs must check affected rows and generated IDs. If log persistence fails after a Python-service failure, preserve the original service error and attach the log failure for diagnosis; successful AI calls must not return success when their required external-call log cannot be persisted.
- System dependency health checks must be observable: report each dependency status and error reason instead of throwing a generic failure or hiding unavailable infrastructure.
- Project create, update, delete, status, and settings changes must record operation audit logs with project ID, operator ID, action, object type, object ID, request ID, IP, and detail JSON.

## Local Dependencies

```powershell
cd deploy
copy .env.example .env
docker compose -f docker-compose-env.yml --env-file .env up -d
```

Docker starts MySQL, Redis, and MinIO only. Business tables are created by Flyway. Do not create business tables through Docker initialization SQL. Vector retrieval components such as Milvus or pgvector are planned separately and are not part of the current local Docker dependency set unless explicitly added.

## Package Structure

Root package: `com.xd.smartworksite`.

Main modules:

- `common`: shared response, exception, request ID, MyBatis config, and Redis helpers.
- `system`: ping, health, version, and runtime status.
- `auth`: users, roles, permissions, login, and project-level access control.
- `project`: worksite projects, members, and project isolation foundation.
- `file`: file metadata, upload/download, parsing records, and MinIO adapter.
- `template`: report/review/common template metadata and file binding.
- `knowledge`: knowledge base CRUD, project isolation, documents, indexing status, and retrieval-facing metadata.
- `datasource`: business data source configuration, project isolation, encrypted password metadata, and database Q&A foundations.
- `qa`: Q&A sessions, messages, answer references, feedback, project isolation, and model/RAG/database QA integration through the AI adapter.
- `review`: compliance templates, review records, AI Agent review execution, issue lists, suggestions, issue handling status, and JSON results.
- `report`: report templates, records, durable per-variable values, knowledge-based generation, versions, downloads, and DOCX rendering.
- `ocr`: OCR records, recognition types, structured fields, and result JSON.
- `policy`: policy/news source configuration, crawl task orchestration, crawled article persistence, and RAG indexing through the AI adapter.
- `task`: async task query, status statistics, retries, cancellation requests, runtime leases, outbox foundation, and stage logs.
- `audit`: operation audit log persistence/query APIs, project operation tracing, access logs, model calls, retrieval logs, OCR calls, and external call logs.
- `ai`: Java intelligent capability adapter for Python AI services, including model/Agent calls, RAG search, routing, context preparation, safe database Q&A, and external call logs.

Business modules may use these layers as needed: `controller`, `application`, `domain`, `repository`, `mapper`, `dto`, `infra`.

## Layering Rules

- Controllers handle HTTP input, Bean Validation, request context, and response wrapping only.
- Controllers may call only the module's application service or facade.
- Application services own use-case orchestration and transaction boundaries.
- Domain objects express business concepts, enums, state transitions, and core rules.
- Repositories provide business-facing persistence interfaces. MyBatis implementations should be named `MyBatisXxxRepository`.
- Mappers only handle SQL mapping. Prefer XML for complex SQL.
- Infra contains Redis, MinIO, external HTTP, Python algorithm service, model, OCR, vector retrieval, and other technical adapters.
- Controllers must not directly call mappers, Redis, MinIO, or external services.
- Modules must not directly call another module's mapper.
- Cross-module collaboration should go through the other module's application service or facade.
- Project-scoped reads and writes should use `project.application.ProjectAccessApplicationService` for project existence, member access, and project administrator checks.
- Project-scoped write operations must use `requireProjectWritableAccess` or `requireProjectWritableManage`; `DISABLED` or `ARCHIVED` projects are read-only except status changes back to `ENABLED`.
- Project settings updates must validate referenced default knowledge bases and report templates against same-project ownership, enabled status, and template category; do not persist dangling or cross-project default IDs.
- Report creation requires exactly one enabled knowledge base from the report project. The Worker must revalidate that knowledge base before each system-safe report QA call; cross-project or disabled knowledge bases fail visibly.
- Report Workers must use the QA gateway's system-safe RAG/model methods. Those methods re-check project existence and writability through `requireProjectWritableForSystem` and must not read request-thread `SecurityContext`; ordinary QA methods keep user-access validation.
- Report list queries must respect project isolation: platform administrators may query across projects without member-project filters, while non-admin users without a requested project must be restricted to their accessible project IDs or receive an empty page when none are accessible.
- Project-scoped list/statistics queries that accept optional `projectId` must use the same isolation rule: platform administrators pass no member-project filter for cross-project queries; non-admin users must be constrained to accessible project IDs and receive an empty result when none are accessible.
- Template list filters must validate enum values fail-fast: `templateCategory` only accepts `REVIEW` or `REPORT`, and `status` only accepts `ENABLED` or `DISABLED`; invalid filters must return parameter errors instead of silently querying empty results.
- User management status filters and status updates must validate enum values fail-fast: user `status` only accepts `ENABLED` or `DISABLED`; invalid values must not be persisted or silently query empty results.
- Built-in roles `PLATFORM_ADMIN`, `PROJECT_ADMIN`, `BUSINESS_USER`, and `VIEWER` are seed-level security contracts; they must not be edited, disabled, deleted, or reassigned permissions through role management APIs.
- Project settings live in `project.settings` JSON and are exposed through project application services; modules should read project defaults through the project module instead of duplicating default configuration rules.
- Data source passwords must be stored as `AES_GCM:` ciphertext using `AI_DATA_SOURCE_PASSWORD_KEY`; API responses must never return password ciphertext or raw passwords.
- Data source connection tests and schema inspection must use real JDBC connections with decrypted credentials. Do not return fake connectivity success or mock schema metadata in production code.
- Data source create APIs must read back the persisted record before success; update, enable, disable, and delete operations must check affected rows and fail visibly on stale or missing records.
- QA APIs must call the AI adapter/Python service for generated answers. Do not create fake answers, canned fallback text, or silent success when model, RAG, or database QA calls fail.
- QA message requests must validate `knowledgeBaseIds` and `dataSourceIds` before calling AI: each referenced knowledge base or data source must exist, belong to the QA session project, and be `ENABLED`; cross-project or disabled references must fail fast.
- QA message creation and answer persistence must be observable: inserted message IDs must be usable, answer updates must check affected rows, and failed persistence must return conflict instead of reporting a successful AI answer.
- QA frontend history must preserve user questions when reloading sessions; if the backend returns combined `question` and `answer` records, render the question as the user's right-side message and the answer as the assistant's left-side message.
- QA frontend answers should render readable Markdown-style structure after escaping content, and question submission must show an in-progress answer placeholder while the backend is generating.
- QA frontend in-progress question editing should be handled by withdrawing the local pending message and ignoring stale responses; do not pretend a backend request was canceled unless cancellation is actually supported.
- QA frontend answer-format parsing should stay in a dedicated utility instead of growing the page component; unsafe or non-standard model text must be escaped first and degraded to readable paragraphs when structure cannot be parsed reliably.
- QA frontend message send and answer regeneration calls use a dedicated 120-second timeout; ordinary QA CRUD and query calls keep the shared 15-second timeout.
- Review APIs must call the AI adapter/Python Agent for compliance results. Do not create fake issue lists, default pass results, or silent success when the Agent returns empty, invalid, or failed results. Persist failed review records with observable error details.
- Review execution failure handling must check failed-state persistence; if a failed review record cannot be marked `FAILED` with error details, return a conflict instead of losing observability.
- Review submit APIs must read back the inserted review record before calling the Agent; missing generated IDs or unreadable records must fail before external AI execution.
- Report generation must check affected rows for report/task state transitions, including report-task linking, task status, processing, success, failed, and version file binding. Zero-row updates must fail with conflict instead of returning fake success.
- Report creation must snapshot every ordered template variable and its non-blank description into `report_variable_value`; templates without variables or with blank descriptions must be rejected before the async task is queued.
- Report variable generation must reuse the QA/RAG application gateway without creating ordinary QA sessions or messages. Variables are generated sequentially with empty conversation context; empty retrieval results may continue to the model, but RAG/model service failures remain visible.
- Every report variable transition to `RUNNING`, `SUCCESS`, or `FAILED` must check affected rows. Task retry preserves successful values and regenerates only `PENDING` or `FAILED` variables before rendering the DOCX.
- Knowledge base updates must check database affected rows; zero-row updates mean the record was concurrently changed or missing and must return a conflict instead of silently succeeding.
- Knowledge document uploads must verify generated IDs and read back inserted records before returning success; missing IDs or unreadable inserts must fail before any indexing work starts.
- Knowledge document indexing must create `KNOWLEDGE_INDEXING` async tasks and call Python RAG indexing through the AI adapter. Java may only orchestrate task state, parse-content loading, project isolation, and error recording; it must not access vector databases, run embeddings, or mark success when parsing or Python indexing fails.
- Knowledge indexing state transitions to `INDEXING`, `SUCCESS`, and `FAILED` must check affected rows. If failure-state persistence itself fails, the worker must fail visibly with the original error included instead of losing observability.
- Knowledge document index task creation is allowed only from `PENDING` or `FAILED`. `INDEXING` and `SUCCESS` documents must not expose a repeat-submit frontend action; if a stale client submits anyway, the backend conflict is the correct fail-fast result.
- OCR module implementation is owned outside this workstream; do not add or refactor OCR business code unless the user explicitly reassigns it.

## Responses And Exceptions

Use `common.result.ApiResponse` for unified responses and `common.result.PageResult` for pagination.

Use `common.exception.BusinessException` for business errors. Global exception handling lives in `common.exception.GlobalExceptionHandler`.

Request IDs are handled by `common.config.RequestIdFilter`. The response header is `X-Request-Id`.

## Database Rules

- Database migrations must use Flyway scripts under `src/main/resources/db/migration`.
- Do not modify migrations that have already been used by the team. Add a new version instead.
- Business tables should include `id`, `created_at`, `updated_at`, `created_by`, `updated_by`, and `deleted`.
- Tables with project-scoped data must include `project_id`.
- SQL should filter `deleted = 0` by default.
- Business data uses logical delete by default.
- JSON columns should receive application-validated JSON strings through MyBatis parameters; avoid mapper-level `CAST(? AS JSON)` patterns that can fail with prepared statements or dialect differences.

## Coding Rules

- New APIs must use DTOs and Bean Validation for input validation.
- Request objects are named `XxxRequest` or `XxxCommand`.
- Response objects are named `XxxResponse`; do not return sensitive database fields.
- Do not put business decisions in controllers or MyBatis XML.
- External service calls must define timeout, error mapping, retry/timeout policy where applicable, and call logging.
- Template uploads must require explicit `templateName`, `templateType`, and a non-blank original filename; storage upload failures must return visible errors and must not generate default filenames or fallback template metadata.
- Report template uploads must scan real `{{ var_xx_xx }}` placeholders before object storage upload and persist the ordered unique variable names into `template_variable_description` with an empty initial description after template/file IDs exist. Review template uploads must not run this automatic parsing path; parse or persistence failures must fail the upload visibly, roll back database writes, and clean up the uploaded object where applicable.
- Report template variable APIs must read the persisted template file and parse real placeholders; missing files, unsupported formats, empty content, or storage read failures must fail visibly instead of returning fake empty variable lists.
- Template create APIs must read back the persisted template before success; update, enable, disable, and delete operations must check affected rows and fail visibly on stale or missing records.
- Template preview, `{{ var_xx_xx }}` parsing, and variable-description persistence changes must also follow `src/main/java/com/xd/smartworksite/template/AGENTS.md`.
- File uploads must require a non-blank original filename; do not silently replace missing filenames with generic names such as `file`.
- File upload and parse-task creation must read back persisted records before returning success; if records are not readable, fail visibly and clean up uploaded storage objects where applicable.
- AI, RAG, OCR, Embedding, vector retrieval, and document-parsing integrations must be adapter-based; do not implement algorithm core logic in Java controllers or application services.
- Policy/news crawling must be orchestrated by Java and executed through `python-ai-service`; Java persists sources, tasks, articles, external-call logs, and RAG index state, while frontend never crawls external websites or calls Python directly.
- Long-running operations such as report generation, OCR recognition, knowledge indexing, and document parsing must use async tasks or status records instead of blocking HTTP requests for the whole job. Task status values are `PENDING`, `QUEUED`, `RUNNING`, `SUCCESS`, `FAILED`, `RETRYING`, and `CANCELED`.
- Async workers such as OCR recognition must not depend on request-thread `SecurityContext`; they must re-check the target project through system-safe project/file access methods before reading files or calling external services.
- Report creation APIs must return the created report in `PENDING` state and create a `QUEUED` task after writing `task_outbox`; actual knowledge retrieval, per-variable model generation, and DOCX rendering belong to the worker path. If Python AI, templates, knowledge retrieval, or storage are unavailable, production code must fail visibly and record task/report/variable errors instead of falling back silently.
- Report creation requires explicit `reportName`; do not derive a default report name from `reportType`. Generated DOCX files must be persisted through MinIO and file metadata; persistence failures must fail the task visibly instead of creating fallback success records.
- Task retry and cancel APIs must fail fast on stale or invalid states. Retrying is allowed only for `FAILED` tasks within the retry limit. Canceling terminal tasks must return a conflict instead of silently succeeding. Running tasks record `cancel_requested=true` and must be stopped by the worker cooperatively.
- Task stage logs and task outbox events must check insert affected rows and generated IDs where applicable. State transitions must not be reported as successful if their trace or durable outbox record cannot be persisted.
- Authentication and authorization management writes must check affected rows for user updates, password changes, role changes, role-permission links, project member changes, and last-login updates. Missing write effects must fail with conflict instead of silent success.
- P0 create/update paths must check affected rows or generated IDs for project records and creator members, file objects and parse records, template files/templates and file business-ID binding, report configs/reports/tasks/output files/versions, and review records. APIs that return persisted data must read back the row before success.
- Task queue delivery must use MySQL `task_outbox` as the durable source of truth. Redis is a delivery channel only; delivery failures must record error details, retry counters, and the next delivery time instead of being swallowed.
- Task workers must claim `QUEUED` tasks before execution, verify the target project is still writable, write `worker_id`, `lease_until`, and heartbeat timestamps, and complete tasks with owner checks. Stale, canceled, or non-owner completions must fail fast with conflict. Invalid Redis queue messages must be rejected with observable logs before claiming tasks.
- Conditional background components with multiple constructors must explicitly identify the Spring injection constructor so enabling the component cannot fail due to ambiguous constructor selection.
- Login failure counters and temporary account locks must use Redis keys under `RedisKeys`; corrupted counters must fail fast instead of being silently reset.
- JWT authentication must re-check the current user record on each request; disabled or deleted users must not be authenticated by stale tokens.
- Logs must not print passwords, tokens, MinIO secrets, or production credentials.
- Compliance review results must remain structured: each issue requires `issueId`, `severity`, `location`, `ruleName`, `description`, and `suggestion`; incomplete AI output must fail visibly instead of being persisted as completed.
- Local development seeds `admin / admin123` through Flyway for interface testing only; production deployments must reset or disable the seeded administrator password.
- Run `mvn test` after adding runnable functionality.
- P0 backend validation must keep the following gates green: `mvn clean test`, frontend `npm run build` when frontend contracts change, documentation encoding guard, non-OCR route coverage against README and interface docs, frontend non-OCR API route matching, and OCR backend diff check.
- Contract tests such as `MigrationContractTest` and `SecurityUtilsTest` are part of the backend foundation; do not delete or weaken them to make unrelated changes pass.

## Agent Rules

- Read `README.md`, this file, and related module code before editing.
- Follow the existing package structure and layer boundaries.
- Keep changes focused and avoid unrelated refactors.
- For every project content change, update `AGENTS.md` and the relevant files under `docs/` in the same change set.
- Documentation updates are required for changes to code, APIs, configuration, technical stack, database schema, module boundaries, startup steps, deployment, or collaboration rules.
- Documentation updates are not required for read-only investigation, temporary debugging commands, log inspection, or other actions that do not change project content.
- If changing public contracts, database schema, external APIs, technical stack decisions, or collaboration rules, update docs as well.
- The workspace may contain user changes; never revert unrelated files.
- Do not write real secrets, accounts, or production addresses into generated SQL, config, or examples.

## Frontend Rules

- Frontend code must be placed in `frontend/`.
- Do not modify backend `src/`, `pom.xml`, or `deploy/` for frontend-only tasks unless explicitly requested.
- Use `npm` for frontend package management.
- Frontend source files containing Chinese text must be saved as UTF-8 and must not contain mojibake or `????` placeholders.
- API base URL must be read from `.env` as `VITE_API_BASE_URL`.
- Development may use mock data when the backend API is not available, but mock mode must be explicitly enabled through environment variables and must not be the default for real integration.
- Frontend API failures must remain visible to users; do not return fake success, fake empty lists, or mock fallback data after a real backend call fails.
- Project management must have a single primary frontend entry; avoid duplicate pages that implement the same project CRUD flow.
- Project member management should be reached from project management context, such as a project-row drawer or detail section, rather than a separate left-menu entry.
- All HTTP calls must go through `frontend/src/utils/request.ts`.
- Requests should automatically attach `Authorization` when a token exists.
- Requests should automatically attach `X-Request-Id`.
- Fetching a MinIO/S3 presigned download URL is an explicit exception to authenticated API requests: use the clean download path in `frontend/src/utils/request.ts` and never attach the application JWT or `X-Request-Id` to the signed object-storage request.
- Handle unified backend responses with `code`, `message`, `data`, `requestId`, and `timestamp`.
- `401` should redirect to `/login`.
- `403` should redirect to `/403`.
- Do not let frontend code directly call Python services, databases, MinIO, vector databases, or OCR engines.
- Reusable upload, table, search, dialog form, status tag, progress, JSON viewer, empty state, and download behavior should be implemented as shared components.
- Every page must handle loading, empty, and error states.
- Long-running tasks such as report generation, OCR recognition, and knowledge indexing must show status, progress, or stage logs.
- Frontend action buttons for state-machine APIs must follow backend allowed transitions: disable retry/cancel/download/content/regenerate actions when the current row status cannot accept that operation, while leaving backend fail-fast conflicts intact for stale clients.
- Knowledge document indexing actions must be state-aware: allow submit/retry only for `PENDING` and `FAILED`, disable repeat submission for `INDEXING` or `SUCCESS`, and require a successful file parse result before triggering indexing.
- OCR invoice submission must expose and send `invoiceType` as `VAT_SPECIAL` or `VAT_NORMAL`; do not let invoice recognition submit without this backend-required option.
- OCR frontend submissions should automatically refresh the submitted record and list status until a terminal state; OCR type labels should use the same type metadata as the OCR type selector.
- Report and review creation pages must load only enabled templates. Review issue status updates must use backend enum values `OPEN`, `PROCESSING`, `RESOLVED`, and `IGNORED`.
- The report creation page must expose one required enabled knowledge-base selector and must not expose the retired report source checkboxes or reference-file selector. The report detail page must show ordered variable descriptions, generated values, status, and errors while polling non-terminal reports.
- The compliance review page must provide an upload-template entry that navigates to template management with the review-template category selected.
- Template upload UI should restrict report/review template files to formats the backend can parse for variables or review context; do not present unsupported template formats as normal upload options.
- Single-file business flows such as template upload, review submit, and OCR submit must render upload controls as single-file controls; do not allow multi-select and then silently submit only the first file.
- Report and review creation pages must load only enabled templates. Review issue status updates must use backend enum values `OPEN`, `PROCESSING`, `RESOLVED`, and `IGNORED`.
- Template upload UI should restrict report/review template files to formats the backend can parse for variables or review context; do not present unsupported template formats as normal upload options.
- Single-file business flows such as template upload, review submit, and OCR submit must render upload controls as single-file controls; do not allow multi-select and then silently submit only the first file.
- Single-file upload controls should replace the existing selected file when the user selects another file, instead of keeping stale preview or upload state.
- Policy/news crawler UI may use an explicit `VITE_USE_POLICY_MOCK` switch until Java backend policy APIs exist; frontend must still never crawl external websites or call Python services directly.
- AI results should expose traceable information where available, such as sources, confidence, raw JSON, or document references.
- Frontend report-template upload APIs must pass explicit `templateName` and `templateType`; do not derive them from the filename or rely on backend fallback metadata.
- Template center must display the backend `templateId` and fetch preview content only through the Java `GET /api/templates/{templateId}/preview` API. DOCX, XLSX/CSV, and text previews should be rendered locally from the returned Blob, and unsupported formats or preview failures must remain visible to users. REPORT rows must expose a template-variable dialog that loads current variable descriptions from Java, keeps variable names read-only, and submits the complete non-blank description list through the backend update API.

## Frontend UI Style

- Follow `智慧工地前端UI风格指南.md`.
- Use an enterprise smart worksite admin style with a lightweight data cockpit feel.
- Prefer a light theme.
- Use industrial blue as the primary color, with teal and construction orange as accent colors.
- Use a left-side menu, top project switcher, and card-based content sections.
- The frontend navigation should follow user task flow instead of implementation modules: keep `工作台`, group `智能问答`、`合规审查`、`报告生成`、`OCR识别` under intelligent applications, group knowledge bases/data sources under knowledge assets, and keep tasks/audit/configuration as supporting areas. Review document upload belongs inside `合规审查`, not as a separate left-menu entry.
- The workbench should make the four primary user actions obvious on first screen: ask questions, review documents, generate reports, and recognize materials.
- The main frontend shell should keep the left navigation and top project bar fixed while page content scrolls independently; long local panels such as QA session/message lists and OCR tables should use their own scroll areas when that preserves context.
- Do not use a pure big-screen dashboard style.
- Do not use dark mode as the default.
- Do not use a flashy consumer AI chat product style.



## Python Intelligent Service Rules

- The Python intelligent algorithm service lives under `python-ai-service/`.
- Qwen API keys must stay in the Python service `.env` or environment variables and must not be written to Java config, docs, SQL, or logs.
- Java backend calls Python through `app.ai.python-service.*` and sends `X-AI-Service-Key` when configured.
- Policy/news crawler extraction is exposed by `python-ai-service` at `/v1/policy/crawl`; Python only downloads and extracts public HTML content and must not write Java databases, MinIO, or vector stores directly.
- OCR recognition may use Qwen VL, but Qwen VL must be wrapped by `python-ai-service`; Java OCR modules must call the Python OCR API instead of calling `QWEN_VL_ENDPOINT` directly.
- For image OCR, Python should download Java-generated temporary MinIO URLs and send Qwen VL `data:image/...;base64,...` image URLs instead of forwarding signed MinIO URLs to the cloud provider.
- Database Q&A uses Python to generate SQL and summaries, but Java must validate and execute only safe MySQL read-only SQL.
- RAG, Agent, model reasoning, context compression, and semantic routing are Python responsibilities; Java owns project isolation, logging, error mapping, and API responses.


## Advanced AI Adapter Rules

- RAG indexing must use the Python service: document chunking, embedding, vector storage, retrieval, and rerank belong in `python-ai-service/`. Java knowledge indexing must require existing successful file parse content; missing, blank, or unavailable parse content must fail visibly and record the document error.
- Supported vector providers are `LOCAL`, `PGVECTOR`, and `MILVUS`; Java must not directly access vector databases.
- `EMBEDDING_PROVIDER=QWEN` is the production path; `LOCAL_HASH` is only for offline tests and development without model quota.
- Agent tool execution is coordinated by Python through a tool registry; Java exposes stable APIs and logs calls.
- Database Q&A supports MySQL, PostgreSQL, and Kingbase through JDBC, but still only permits read-only `SELECT`/`WITH` statements.

- PostgreSQL and Kingbase database Q&A execution requires JDBC drivers and the same read-only SQL safety checks as MySQL. Real Kingbase execution tests use `AI_TEST_KINGBASE_JDBC_URL`, `AI_TEST_KINGBASE_USERNAME`, and `AI_TEST_KINGBASE_PASSWORD`; do not fake production credentials in repository files.
