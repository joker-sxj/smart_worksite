# Report Module Current Contract

This file records the current implementation contract of the `report` module.

## Current Boundary

- Java owns report orchestration, DOCX template rendering, state records, file persistence, project isolation, and observable failures.
- The QA/RAG application gateway retrieves one selected knowledge base and generates one template variable at a time without creating ordinary QA history.
- `POST /api/reports` and `POST /api/reports/{reportId}/regenerate` create the report, config, task, and `task_outbox` event.
- HTTP creation endpoints do not generate files directly. The Worker claims `REPORT_GENERATION` tasks and renders the Word report.
- If Python AI, template loading, material loading, or persistence fails, production code must mark the report as `FAILED` and record the error. It must not return fake success or fallback content.

## Current APIs

| Method | Path | Current behavior |
| --- | --- | --- |
| POST | `/api/reports` | Creates a report generation task and returns report status `PENDING` plus `taskId`. |
| GET | `/api/reports` | Lists reports in projects accessible to the current user. |
| GET | `/api/reports/{reportId}` | Gets report detail after project access validation. |
| GET | `/api/reports/{reportId}/variables` | Gets ordered per-variable status, description, value, trace, and error details. |
| POST | `/api/reports/{reportId}/regenerate` | Creates a new report and task from the original report config. |
| GET | `/api/reports/{reportId}/download?format=WORD` | Returns a MinIO access URL for the saved Word file. |
| GET | `/api/reports/{reportId}/download-file?format=WORD` | Streams the saved Word file through the Java backend. |

## State Flow

- After creation, report status is `PENDING`.
- After `task_outbox` is written, task status is `QUEUED`.
- After Worker claim, task status is `RUNNING` and report status is `PROCESSING`; variables move through `PENDING`, `RUNNING`, `SUCCESS`, or `FAILED`.
- If Java renders and stores the DOCX successfully, report status becomes `COMPLETED`.
- If template validation, material loading, Python variable generation, DOCX rendering, or persistence fails, the error is recorded and the report becomes `FAILED`.

## Template And Material Contract

- Only DOCX templates are supported for generation.
- Placeholders use `{{ var_xx_xx }}` only and repeated names share one generated value.
- Creation requires exactly one enabled project-owned knowledge base and non-blank descriptions for every template variable.
- Ordered variable snapshots are persisted in `report_variable_value` before the task is queued.
- Worker calls are independent and have no conversation context or visible QA session history.
- Empty RAG results may continue to model generation; external service failures remain visible.
- A failed variable fails the report while preserving prior successes; task retry regenerates only pending or failed rows.

## Download Behavior

- Only `WORD` is supported.
- PDF requests fail explicitly. Word output must not be returned as fake PDF.
- `/download-file` is the browser-facing path and streams the current report version `word_file_id` through Java so remote clients do not need direct MinIO network access.
- `/download` is kept for compatibility and returns a MinIO signed URL for callers that can reach the object-storage endpoint directly.

## Verification

Run after backend changes:

```powershell
mvn clean test
```

Current report module tests cover queued creation, DOCX rendering, mixed placeholder replacement, AI variable generation failures, material isolation and parse-state validation, disabled projects, and write-conflict behavior.
