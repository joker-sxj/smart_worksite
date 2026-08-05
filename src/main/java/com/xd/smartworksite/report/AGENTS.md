# Report Module Design Rules

This file supplements the root `AGENTS.md` for knowledge- and database-based report generation.

## Creation Contract

- `POST /api/reports` requires `projectId`, `reportName`, `reportType`, and `templateId`; `knowledgeBaseIds` and `dataSourceIds` are multi-select lists and at least one list must be non-empty. Legacy `knowledgeBaseId` remains compatible when `knowledgeBaseIds` is empty.
- Every knowledge base must exist, belong to the report project, be `ENABLED`, and have type `PROJECT`. Every data source must exist, belong to the report project, and be `ENABLED`.
- The template must be an enabled project-owned `REPORT` DOCX template.
- Creation reads the template module's ordered `{{ var_xx_xx }}` variables and descriptions. Templates without variables or with any blank description fail before report/task/outbox persistence succeeds.
- A `report_variable_value` row is created for every unique ordered variable. Variable name, description, template/file IDs, knowledge-base IDs, data-source IDs, task, and creator are immutable generation snapshots. A template variable data-source whitelist is intersected with the report selection.

## Worker Contract

- Report creation remains asynchronous. The Worker must claim the `REPORT_GENERATION` task and re-check project writability.
- Variables run sequentially by `sort_no`. Each call receives only report metadata, one variable name/description, and its source snapshots; conversation context is always empty.
- When both source types are available, AI routing chooses `KNOWLEDGE`, `DATABASE`, or `HYBRID` and may narrow database IDs through `requiredResources`. Missing or invalid routing safely falls back to all allowed sources. Database execution must remain protected by Java `SafeSqlExecutor` validation and read-only execution.
- Report generation reuses the QA/RAG application gateway through a system-safe application service. It does not create `qa_session` or `qa_message` rows and must not call the Java application's own HTTP controllers.
- Worker-side routing, RAG retrieval, database query, and model generation must call `routeForSystem`, `searchKnowledgeForSystem`, `queryDatabaseForSystem`, and `invokeModelForSystem` as applicable. These paths validate project existence/writability without depending on a logged-in request `SecurityContext`; user-facing QA continues to use the normal access-checked methods.
- Empty RAG results are allowed to continue to the model. The prompt must forbid fabricated concrete project data when only general model knowledge is available.
- Each variable is persisted immediately. `RUNNING`, `SUCCESS`, and `FAILED` updates must check affected rows and retain provider trace and retrieval references when available.
- A variable failure marks the whole report task failed but preserves prior successes. Retrying the same task skips non-blank `SUCCESS` variables and regenerates only `PENDING` or `FAILED` rows.
- DOCX rendering starts only after all variables have non-blank successful values. Body, table, header, and footer placeholders use the same generated value for repeated variable names.

## Query And Frontend Contract

- `GET /api/reports/{reportId}/variables` returns ordered variable name, description snapshot, value, status, trace, timing, and error fields after project access validation.
- The frontend creation dialog contains multi-select controls for enabled project knowledge bases and enabled data sources, and requires at least one source. Template variables expose an optional data-source whitelist multi-select.
- The report detail page polls non-terminal reports and displays per-variable progress and failures.

## Persistence

- `V18__add_report_variable_values.sql` creates `report_variable_value`; `V20__add_report_multi_sources.sql` adds multi-source snapshots and template-variable data-source whitelists. Do not modify old migrations after team use.
- The unique key is `report_id + variable_name`; a repeated placeholder is generated once per report.
- JSON references are application-serialized and passed as normal MyBatis parameters without mapper-level casts.

## Verification

- Backend tests cover ordered multi-source snapshots, blank-description rejection, source validation, variable whitelist intersection, routed knowledge/database/hybrid generation, route fallback, failure persistence, retry resume, empty-source model fallback, and DOCX rendering.
- Run `mvn clean test` and frontend `npm run build` after contract changes.
