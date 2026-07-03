# AGENTS.md

This file describes collaboration rules for the Smart Worksite backend. Read `README.md` first for the project overview, startup steps, and module list.

## Project Positioning

This repository is the Spring Boot backend scaffold for the Smart Worksite AI application.

The current phase focuses on engineering foundations, not full implementations of Q&A, compliance review, report generation, or OCR. AI, RAG, OCR, vector database, and large-model capabilities should be integrated later through external service adapters.

## Tech Stack

- Java 17
- Spring Boot 3.3.x
- Maven
- MyBatis + XML
- PageHelper
- MySQL 8
- Redis
- MinIO
- Flyway

## Local Dependencies

```powershell
cd deploy
copy .env.example .env
docker compose -f docker-compose-env.yml --env-file .env up -d
```

Docker starts MySQL, Redis, and MinIO only. Business tables are created by Flyway. Do not create business tables through Docker initialization SQL.

## Package Structure

Root package: `com.xd.smartworksite`.

Main modules:

- `common`: shared response, exception, request ID, MyBatis config, and Redis helpers.
- `system`: ping, version, and runtime status.
- `auth`: users, roles, permissions, and login.
- `project`: worksite projects, members, and project isolation foundation.
- `file`: file metadata and MinIO adapter.
- `knowledge`: knowledge bases and documents.
- `datasource`: business data source configuration.
- `qa`: Q&A.
- `review`: compliance review.
- `report`: report templates, records, and versions.
- `ocr`: OCR records.
- `task`: async tasks and stage logs.
- `audit`: audit logs and external call logs.

Business modules may use these layers as needed: `controller`, `application`, `domain`, `repository`, `mapper`, `dto`, `infra`.

## Layering Rules

- Controllers handle HTTP input, Bean Validation, request context, and response wrapping only.
- Controllers may call only the module's application service or facade.
- Application services own use-case orchestration and transaction boundaries.
- Domain objects express business concepts, enums, state transitions, and core rules.
- Repositories provide business-facing persistence interfaces. MyBatis implementations should be named `MyBatisXxxRepository`.
- Mappers only handle SQL mapping. Prefer XML for complex SQL.
- Infra contains Redis, MinIO, external HTTP, and other technical adapters.
- Controllers must not directly call mappers, Redis, MinIO, or external services.
- Modules must not directly call another module's mapper.
- Cross-module collaboration should go through the other module's application service or facade.

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

## Coding Rules

- New APIs must use DTOs and Bean Validation for input validation.
- Request objects are named `XxxRequest` or `XxxCommand`.
- Response objects are named `XxxResponse`; do not return sensitive database fields.
- Do not put business decisions in controllers or MyBatis XML.
- External service calls must define timeout, error mapping, and call logging.
- Logs must not print passwords, tokens, MinIO secrets, or production credentials.
- Run `mvn test` after adding runnable functionality.

## Agent Rules

- Read `README.md`, this file, and related module code before editing.
- Follow the existing package structure and layer boundaries.
- Keep changes focused and avoid unrelated refactors.
- If changing public contracts, database schema, external APIs, or collaboration rules, update docs as well.
- The workspace may contain user changes; never revert unrelated files.
- Do not write real secrets, accounts, or production addresses into generated SQL, config, or examples.
