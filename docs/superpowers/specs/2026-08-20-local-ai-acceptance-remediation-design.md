# Smart Worksite Local AI Acceptance Remediation Design

## Status

- Date: 2026-08-20
- Target branch: `codex/local-ai-acceptance-20260820`
- Delivery target: Linux production/acceptance deployment, developed and orchestrated from Windows
- Current GPU profile: one NVIDIA H100 PCIe 80 GB
- Customer GPU profile: two NVIDIA RTX A6000 48 GB cards

## Goal

Remediate the twelve issues raised during the 2026-08-13 on-site delivery by making every AI capability local-only, adding reliable structured document parsing, grounding generated results in traceable evidence, enforcing project isolation, and producing reproducible Windows and Linux acceptance evidence before merging and pushing `main`.

## Non-Goals

- Do not replace the established Vue -> Java -> Python architecture.
- Do not expose Python services, models, databases, MinIO, or vector stores directly to the frontend.
- Do not describe RAG ingestion or prompting as model training or fine-tuning.
- Do not introduce cloud fallback in `LOCAL_ONLY` mode.
- Do not require administrators to approve every generated SQL plan.
- Do not restrict user-defined report variable names.
- Do not require all report variables to succeed before allowing report download.
- Do not rewrite unrelated crawler, logging, download, or lifecycle behavior unless a regression test proves the shared change is required.

## First Principles

1. Sensitive project data must not leave the deployment environment. Model inference, embedding, reranking, OCR, and document understanding run on local services.
2. Deterministic code calculates facts. SQL and application code compute counts, percentages, dates, classifications, and chart datasets; the language model explains those results.
3. Generated claims must be traceable. Knowledge answers, database answers, review findings, and report conclusions retain evidence locations and source identities.
4. Project boundaries are server-enforced. Client-supplied project and resource identifiers are untrusted and are revalidated by Java before access or task submission.
5. Windows development tests and Linux GPU tests prove different properties. Neither may be used as a substitute for the other.
6. Partial report success is a valid product outcome. Failed variables are visible and diagnosable while successful content remains downloadable.

## Delivery Strategy

Use shared foundations followed by incremental feature replacement. Each stage must leave a working, testable system and must add regression coverage before changing production behavior.

1. Establish acceptance fixtures and record a pre-remediation baseline.
2. Add local-model provider boundaries and strict local-only validation.
3. Add a shared parser and evidence representation.
4. Upgrade Q&A, database exploration, and project isolation.
5. Upgrade compliance review and custom fields.
6. Upgrade report tables, charts, and deterministic conclusions.
7. Harden OCR, especially watermarked identity cards.
8. Perform adversarial review, Windows black-box validation, Linux GPU validation, offline validation, and documentation alignment.

## Acceptance Workspace

The external acceptance workspace is:

`C:\Users\23883\Desktop\智慧工地验收（宋经纬）\2026-08-20-本地模型整改验收`

Existing customer acceptance files under `C:\Users\23883\Desktop\智慧工地验收（宋经纬）` are read-only inputs. The remediation process must not overwrite or delete them.

The new workspace contains:

```text
00_验收说明/
01_环境与本地模型/
02_知识库解析/
03_智能问答/
04_数据库问答/
05_合规审查/
06_报告生成/
07_自定义审查字段/
08_项目权限隔离/
09_OCR识别/
10_模型与数据治理/
11_对抗性测试/
12_线上演示/
fixtures/
expected/
scripts/
results/
```

Generated results are timestamped. Secrets are loaded from an ignored local environment file and never copied into fixtures, reports, screenshots, or Git.

## Runtime Architecture

```text
Vue frontend
    -> Java business backend
        -> Python AI service
            -> local OpenAI-compatible model endpoint
            -> local embedding endpoint
            -> local reranker endpoint
            -> local OCR/document-layout endpoint

Java backend
    -> MySQL
    -> Redis
    -> MinIO

Python AI service
    -> project-scoped vector storage
```

Java remains authoritative for authentication, authorization, current-project resolution, business state, task orchestration, file metadata, download authorization, and audit records. Python remains authoritative for model adapters, retrieval, reranking, OCR algorithms, and document understanding.

## Model Profiles

### H100 Development and Acceptance Profile

- Main model: `Qwen/Qwen3.8-27B-FP8`
- Main model tensor parallelism: 1
- Initial ordinary context: 16K tokens
- Initial report/review context: 32K tokens
- Exceptional validated context ceiling: 64K tokens
- Initial main-model concurrency: 2
- Embedding: `Qwen/Qwen3-Embedding-4B`
- Reranking: `Qwen/Qwen3-Reranker-0.6B`, upgradeable only after evaluation
- Document layout/OCR: `PaddlePaddle/PaddleOCR-VL-1.6` plus PP-OCR text detection/recognition

### Two-A6000 Customer Profile

- Main model: `Qwen/Qwen3.8-27B` BF16
- Main model tensor parallelism: 2
- Initial context ceiling: 32K tokens
- Background report, review, embedding, and OCR jobs use bounded queues and concurrency limits.
- A lower precision model may replace BF16 only after it passes the same project evaluation set and structural-output tests.

Profiles define non-secret runtime defaults. Credentials and deployment-specific hostnames stay in ignored `.env` files.

## Local-Only Enforcement

Introduce `AI_DEPLOYMENT_MODE=LOCAL_ONLY` and provider interfaces:

```text
ChatModelProvider
VisionModelProvider
EmbeddingProvider
RerankProvider
DocumentUnderstandingProvider
```

In `LOCAL_ONLY` mode:

- Public cloud model endpoints are rejected during configuration validation.
- Cloud API keys are neither required nor used.
- Provider unavailability produces an explicit dependency error; it never triggers cloud fallback.
- Health output identifies each local dependency, configured model name, status, and sanitized failure reason.
- Audit records include provider type, model name, elapsed time, request ID, status, and a non-sensitive request summary.
- Full prompts, document bodies, database passwords, identity numbers, and image payloads are excluded from routine logs.

Allowed model endpoint hosts are loopback addresses, Docker service names, or explicitly configured private-network CIDRs/hostnames. Tests must reject DashScope, OpenAI, and arbitrary public endpoints in local-only mode.

Policy crawling may access configured public policy websites because it is data acquisition rather than model inference. Parsed content, summarization, embedding, and Q&A remain local.

## Unified Document Parsing

Define a shared `DocumentParser` boundary with PDF, Word, Excel, PowerPoint, and image implementations. All knowledge ingestion, review templates, review references, and report references consume the same normalized parse output.

Normalized content consists of ordered blocks:

```json
{
  "documentId": 100,
  "projectId": 1,
  "blocks": [
    {
      "blockId": "sheet-risk!A1:F8",
      "type": "TABLE",
      "text": "...",
      "structuredData": {},
      "location": {
        "page": null,
        "sheet": "风险台账",
        "slide": null,
        "cellRange": "A1:F8",
        "boundingBox": null
      }
    }
  ]
}
```

### PDF

Each page is classified independently. Pages with a usable text layer use direct extraction. Empty, corrupt, or insufficient text pages are rendered and sent to local OCR. Mixed PDFs therefore preserve both native text and OCR results without forcing the entire document down one path.

### Excel

Parsing preserves sheet name, row and column position, merged regions, displayed values, cached formula results, table regions, and hidden-sheet status. Dangerous formulas are not executed by the acceptance tooling.

### PowerPoint

Parsing preserves slide number, text boxes, tables, notes, image OCR, and reading order. Embedded images are processed through local OCR when they contain meaningful text.

### Parse State

Parsing uses explicit states:

```text
PENDING -> PARSING -> PARSED
                    -> FAILED
```

The UI displays `PARSING` as a normal in-progress state. Failure records include a sanitized reason and a retry action. Retries are idempotent and cannot create duplicate chunks.

## Evidence Model

Every retrievable or generated assertion can reference an evidence item:

```json
{
  "sourceType": "KNOWLEDGE_DOCUMENT",
  "projectId": 1,
  "knowledgeBaseId": 10,
  "documentId": 100,
  "chunkId": "...",
  "dataSourceId": null,
  "tableName": null,
  "columnNames": [],
  "location": {},
  "excerpt": "..."
}
```

Database evidence substitutes datasource, table, columns, normalized result rows, and executed read-only SQL. Evidence excerpts are length-limited and permission-checked when displayed.

## Conversational Q&A

Conversation context consists of recent full turns plus a bounded summary of older turns. The backend preserves selected route mode, knowledge bases, data sources, and current project.

Successful responses may include `followUpQuestions`; incomplete requests may include `clarificationQuestions`. These are different concepts and may coexist only when semantically justified.

Each follow-up item contains:

```json
{
  "question": "本月有哪些一级风险？",
  "routeMode": "HYBRID",
  "knowledgeBaseIds": [10],
  "dataSourceIds": [20]
}
```

Selecting a follow-up continues the same authorized session and does not silently broaden resource scope.

## Database Exploration and Q&A

Authorized users can view datasource metadata, tables, columns, descriptions, masked sample rows, row-count estimates, freshness information, and the last successful query status. Credentials are never returned.

Database Q&A follows a controlled tool pipeline:

```text
question
-> schema-scoped retrieval plan
-> one candidate read-only statement
-> lexical and parsed SQL validation
-> bounded execution
-> normalized result dataset
-> deterministic aggregates
-> grounded model explanation
```

Requirements:

- Only one read-only statement is accepted.
- Write operations, stored procedures, outfile operations, comments used for bypass, and unbounded result sets are rejected.
- MySQL `DISTINCT` and `ORDER BY` compatibility is validated or repaired before execution.
- Automatic repair is bounded and uses the concrete database error plus the authorized schema.
- Repeated identical failures stop early instead of consuming all retries.
- The successful SQL, source tables/columns, result summary, repair attempts, and final explanation are persisted for evidence and support.

## Compliance Review

A review consists of one primary file, zero or more reference files, zero or more authorized knowledge bases, and one review template. Reference documents are stored as relations rather than concatenated into one prompt.

For each review rule:

1. Retrieve relevant blocks from the primary file and references.
2. Rerank the bounded evidence set.
3. Ask the local model for structured findings.
4. Validate required output fields.
5. Persist primary-file evidence, reference evidence, page/location, severity, confidence, and manual-confirmation state.

Scanned and mixed PDFs use the unified parser. A failed page does not erase successfully parsed pages; the review indicates incomplete evidence when required material could not be parsed.

## Custom Review Fields

Review templates support three schema-driven field groups:

- Input conditions supplied by the user before review.
- Values extracted from the reviewed documents.
- Finding/result fields displayed after review.

Fields are stored as JSON schema definitions rather than dynamic database columns. Supported behavior includes stable key, display name, type, required flag, options, ordering, extraction instruction, validation rules, evidence, confidence, and manual correction. Removing a field from a later template version does not destroy historical review results.

## Structured Reports

Report variables retain arbitrary user-defined names and gain a content type:

```text
TEXT
TABLE
CHART
TABLE_WITH_ANALYSIS
```

All structured report content uses one canonical dataset:

```text
database or Excel rows
-> normalized typed dataset
-> deterministic statistics
-> Word table
-> chart image
-> model interpretation and recommendations
```

This enforces `table values == chart values == conclusion values`. Counts, percentages, risk levels, dates, and totals are never delegated to free-form model arithmetic.

The first implementation inserts chart PNG files into DOCX rather than creating editable Office chart objects. Structured variables store dataset and provenance metadata so generated conclusions remain auditable.

Variable failures remain isolated. A report with successful and failed variables is downloadable; failed variables show their reason and may be manually completed by the user.

## OCR Hardening

The identity-card pipeline performs quality detection, orientation/perspective correction, multiple local image enhancement variants, text detection, field recognition, visual-model cross-checking, and deterministic validation.

Identity results always contain the complete authoritative schema, including blank low-confidence fields:

- Name
- Sex
- Ethnicity
- Birth date
- Address
- Identity number
- Issuing authority
- Validity period
- Front/back classification
- Field confidence
- Field validation result

Identity-number checksum, encoded birth date, and encoded sex are cross-validated. Watermark suppression is treated as one image variant, not a destructive replacement of the original. Low-confidence or inconsistent fields require manual confirmation or re-upload.

Existing license plate, invoice, contract, and custom OCR behavior receives regression coverage. Preview URLs remain stable during page polling, and detail views render the image rather than only its filename.

## Project Isolation

Every access path must derive or validate the current project on the server. Isolation covers list, detail, upload, preview, download, delete, search, vector insertion/deletion, Q&A selection, datasource selection, reports, reviews, OCR, background tasks, retries, caches, and audit queries.

Vector records and filters contain at least:

```text
projectId
knowledgeBaseId
documentId
chunkId
```

Cache keys and task payloads include project scope. Workers revalidate scope rather than trusting stale task payloads.

Adversarial tests verify that project A cannot infer existence of, list, retrieve, search, use, download, delete, or retry project B resources by guessing identifiers. Responses must avoid resource-existence leaks.

## Model and Data Governance

Documentation accurately distinguishes base-model use, prompts, retrieval, tool calling, deterministic rules, and any actual fine-tuning. Unless fine-tuning is implemented and evidenced, the system must state that no project-specific model training occurred.

The governance package records:

- Model name, source, version/revision, license, and checksum.
- Hardware profile and inference configuration.
- Selection criteria and evaluation results.
- Policy, standard, and customer-document provenance.
- Collection date, version, license/authorization, cleaning, deduplication, redaction, chunking, and indexing.
- RAG versus fine-tuning boundary.
- Evaluation datasets, metrics, commands, and retained result files.
- Known limitations and manual review requirements.

## Security and Privacy Logging

Logs use bounded rotation and the existing thirty-natural-day retention requirement. AI audit records include operational metadata but not full sensitive payloads. Download URLs are authorized through Java and do not expose internal MinIO addresses to remote Windows clients.

Acceptance scans reject committed secrets, public model endpoints in local-only profiles, raw database passwords in API payloads, and identity values in ordinary logs.

## Testing Strategy

### Deterministic Unit Tests

- Parser routing and normalized locations.
- Report statistics and chart datasets.
- SQL single-statement/read-only checks and MySQL `DISTINCT` repair.
- Project-scope authorization.
- Identity-card reconciliation and validation.
- Review schema and report-variable schema validation.

### Contract Tests

- Java/Python request and response compatibility.
- Python/local-model request compatibility.
- Timeout, malformed JSON, missing field, empty response, and provider-unavailable behavior.
- Local-only public endpoint rejection and no-cloud-fallback behavior.

### Windows Black-Box Tests

- Login and current-project selection.
- File upload, parse progress, retry, and preview.
- Q&A context, citations, and selectable follow-ups.
- Datasource metadata, masked samples, SQL evidence, and grounded answers.
- Multi-reference review and custom fields.
- Structured report generation, partial success, download, and remote-host URL behavior.
- OCR upload, stable preview, details, complete fields, and manual confirmation.
- Cross-project tampering.

### Linux GPU Tests

- NVIDIA Container Toolkit and container-visible GPU.
- H100 FP8 model load and health.
- 16K/32K context memory and latency.
- Concurrency 1 and 2, followed by a guarded concurrency 4 probe.
- Simultaneous model, embedding, reranking, and OCR workloads without OOM.
- Restart recovery and model-cache persistence.
- Bounded logs, temporary files, and model cache usage.
- Offline operation with public model endpoints blocked.

### Adversarial Tests

- Prompt injection attempting to override project boundaries.
- Malicious instructions embedded in documents.
- SQL comments, encoded tokens, multi-statements, and write-operation bypass attempts.
- PDF hidden-text/visible-text conflicts.
- Excel formula injection.
- Invalid citations and invented policy clauses.
- Empty database results followed by hallucinated conclusions.
- Oversized files, repeated submission, dependency restart, timeout, and OOM behavior.

## Acceptance Gates

Before merging to `main`, all of the following must be evidenced:

1. Java tests pass.
2. Python tests pass.
3. Frontend type-check/build pass.
4. Acceptance fixture manifest is complete and contains no secrets.
5. Windows black-box critical cases pass against the Linux deployment.
6. Linux H100 health, load, memory, concurrency, restart, and offline tests pass.
7. Cross-project tampering tests pass.
8. Structured report table/chart/conclusion consistency tests pass.
9. Watermarked identity-card required-field and validation tests pass against the agreed fixture set.
10. Adversarial review findings are resolved or explicitly documented as accepted residual risk.
11. README, interface documentation, deployment scripts, and actual routes/configuration agree.
12. Git diff contains no unrelated rollback, generated artifacts, secrets, or customer data.

## Git Strategy

Development occurs on `codex/local-ai-acceptance-20260820` in an isolated worktree. Commits are grouped by testable phase. The branch is merged into `main` only after the acceptance gates pass. `origin/main` is pushed only after the local merge and final verification succeed.

## Rollout and Compatibility

Database migrations are additive and retain existing records. API additions prefer backward-compatible optional fields until the frontend is upgraded. Existing cloud configuration may remain parsable only outside `LOCAL_ONLY`; production local-only profiles cannot use it. Background migrations and reindexing are restartable and idempotent.

## Residual Operational Dependencies

The Windows environment cannot prove CUDA, FP8, GPU memory, or offline Linux behavior. Linux scripts and their captured output are mandatory evidence. The current Linux host must configure NVIDIA Container Toolkit and use a supported, validated driver/runtime combination before production acceptance.