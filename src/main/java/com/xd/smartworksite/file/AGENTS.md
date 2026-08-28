# File Module Agent Guide

This document describes the intended design for the `file` module. Read the root `README.md` and root `AGENTS.md` before editing this module.

## Module Positioning

The `file` module provides unified file object management for the Smart Worksite backend.

It must support upload, download URL generation, preview URL generation, logical deletion, physical object deletion, and metadata persistence for documents, images, templates, reports, OCR attachments, and other business files.

Use this module as the single backend entry point for file metadata and MinIO object operations. Other modules such as `knowledge`, `report`, `ocr`, `review`, and `qa` should reference files by `file_id` or call this module through its application service or facade. They must not call MinIO or this module's mapper directly.

Template preview and variable parsing use `FileObjectApplicationService.openFileContent(...)`. This internal application-service method validates active status, project access, expected project ownership, and expected business binding before opening the MinIO stream. Callers own and must close the returned stream.

## Storage Model

Use MySQL for file metadata and MinIO for binary object storage.

- MySQL table: `file_object`
- Object storage adapter: `file.infra.StorageAdapter`
- MinIO implementation: `file.infra.MinioStorageAdapter`
- Configuration prefix: `app.storage.minio`
- Default signed URL expiration: `app.file.access-url-expire-seconds`

The object content is stored only in MinIO. The database stores object identity, business ownership, project isolation fields, status, and searchable metadata.

## Recommended Package Structure

When implementing the module, use these layers:

```text
com.xd.smartworksite.file
├── controller
│   └── FileObjectController
├── application
│   └── FileObjectApplicationService
├── domain
│   ├── FileObject
│   ├── FileBizType
│   └── FileStatus
├── dto
│   ├── FileUploadRequest
│   ├── FileQueryRequest
│   ├── FileObjectResponse
│   └── FileAccessUrlResponse
├── repository
│   ├── FileObjectRepository
│   └── MyBatisFileObjectRepository
├── mapper
│   └── FileObjectMapper
└── infra
    ├── StorageAdapter
    ├── StorageObject
    ├── MinioStorageAdapter
    └── MinioStorageProperties
```

Follow the project layering rules:

- Controllers handle HTTP input, Bean Validation, and `ApiResponse` wrapping only.
- Controllers call only `FileObjectApplicationService` or a future file facade.
- Application services own business orchestration and transaction boundaries.
- Repositories expose business-facing persistence methods.
- Mappers only execute SQL and must filter `deleted = 0` by default.
- Infra code encapsulates MinIO SDK details.

## Business Types

Use a domain enum for `biz_type`. Current values include:

```text
DOCUMENT    Project documents, standards, PDF, Word, Excel, and uploaded knowledge files
IMAGE       Site photos, inspection images, and visual evidence
TEMPLATE    Report templates, review templates, and reusable document templates
REPORT      Generated reports and exported result files
OCR         OCR source files or OCR result attachments
OTHER       Other attachments
REVIEW_REFERENCE  Compliance review reference PDF/DOC/DOCX files
```

`biz_id` is optional during upload. It can be null for temporary or unbound files, then associated later by a business use case.

## File Status

Use a domain enum for `status`. Recommended values:

```text
ACTIVE          File is available
DELETED         File has been logically deleted
DELETE_PENDING  Metadata is deleted or hidden, but object deletion needs retry
```

Do not expose physically deleted MinIO object names as accessible URLs.

## Metadata Table

The current `file_object` table already contains the core fields:

```text
id
project_id
biz_type
biz_id
file_name
object_name
content_type
file_size
file_hash
status
metadata
created_at
updated_at
created_by
updated_by
deleted
```

Short-term implementation may reuse this table directly.

If more structured file metadata is required later, add a new Flyway migration instead of changing used migrations. Suggested optional fields:

```sql
ALTER TABLE file_object
  ADD COLUMN file_ext VARCHAR(32) NULL COMMENT 'File extension' AFTER file_name,
  ADD COLUMN storage_bucket VARCHAR(128) NULL COMMENT 'Storage bucket' AFTER object_name,
  ADD COLUMN preview_supported TINYINT NOT NULL DEFAULT 0 COMMENT 'Whether preview is supported' AFTER metadata,
  ADD KEY idx_file_hash (file_hash),
  ADD KEY idx_file_created_at (created_at);
```

Do not store secrets, MinIO credentials, access keys, or permanent public URLs in `metadata`.

## Object Naming

Never use the original file name directly as the MinIO object name. Generate an object key that avoids collisions and does not leak business-sensitive information.

Recommended pattern:

```text
projects/{projectId}/{bizType}/{yyyy}/{MM}/{dd}/{uuid}.{ext}
```

Example:

```text
projects/1001/DOCUMENT/2026/07/05/9b4f1c7c0a8d4a9c.pdf
```

Store the original uploaded name in `file_name`.

## REST API Contract

Recommended endpoints:

```text
POST   /api/files/upload
GET    /api/files
GET    /api/files/{fileId}
GET    /api/files/{fileId}/access-url?usage=DOWNLOAD
GET    /api/files/{fileId}/access-url?usage=PREVIEW
DELETE /api/files/{fileId}
```

Upload request should use `multipart/form-data`:

```text
file       MultipartFile, required
projectId  Project ID, required
bizType    DOCUMENT / IMAGE / TEMPLATE / REPORT / OCR / OTHER, required
bizId      Business ID, optional
metadata   JSON string, optional
```

Responses must use `common.result.ApiResponse`.

Paginated list responses must use `common.result.PageResult`.

Do not return MinIO credentials, internal bucket configuration, or sensitive object storage details.

## Upload Flow

The upload use case should be implemented in `FileObjectApplicationService`:

1. Validate project ID, business type, file presence, file size, file extension, and content type.
2. Calculate a stable file hash, preferably SHA-256.
3. Generate the MinIO object name.
4. Upload the binary stream through `StorageAdapter.upload(...)`.
5. Persist a `file_object` metadata row with `status = ACTIVE` and `deleted = 0`.
6. Return `FileObjectResponse`.

MinIO upload and MySQL insert are not in the same transaction. Prefer this consistency strategy:

- Upload MinIO object first.
- Insert MySQL metadata second.
- If metadata insert fails, try to delete the just-uploaded MinIO object and log the cleanup result.
- Do not log file content, credentials, tokens, or production endpoints.

## Download And Preview URL Flow

Use signed URLs for download and preview access.

The current adapter method is:

```java
String createAccessUrl(String objectName, Duration expire);
```

For basic implementation, both download and preview endpoints may return a signed GET URL with an expiration time.

If download file names or response headers are needed later, extend the adapter with an overload that accepts response headers instead of building MinIO SDK logic in the controller.

Preview support should be conservative:

```text
Images: image/png, image/jpeg, image/webp
PDF: application/pdf
Text: text/plain
Office documents: do not convert in this module during the foundation phase
```

For unsupported preview types, throw `BusinessException` with `ErrorCode.PARAM_ERROR` or return a response that clearly marks preview as unsupported, depending on the API style chosen for the implementation.

## Delete Flow

Default behavior should be logical delete plus object cleanup.

Recommended simple flow:

1. Find the file by `fileId` and verify `deleted = 0`.
2. Verify project isolation and access permission when auth is available.
3. Delete the MinIO object through `StorageAdapter.delete(objectName)`.
4. Mark metadata as `deleted = 1` and `status = DELETED`.

If object deletion fails and asynchronous compensation is introduced later:

- Mark `status = DELETE_PENDING`.
- Hide the file from normal queries.
- Retry physical deletion through a task or scheduled compensation job.

Do not hard-delete metadata rows by default.

## Query Rules

All metadata queries must filter:

```sql
deleted = 0
```

Project-scoped queries must also filter:

```sql
project_id = #{projectId}
```

Common query filters:

- `projectId`
- `bizType`
- `bizId`
- `status`
- keyword matching `file_name`
- created time range

Default sort:

```sql
order by created_at desc, id desc
```

## Permission And Project Isolation

Every public API must enforce project isolation.

Use `project.application.ProjectAccessApplicationService` in application services before reading or mutating project-scoped file and parse records.

Do not implement project access checks in MyBatis XML as hidden business logic. XML should only apply explicit query filters.

## Metadata JSON

Use `metadata` for business-specific extension data that does not need first-class columns.

Example:

```json
{
  "source": "manual_upload",
  "scene": "knowledge_import",
  "width": 1920,
  "height": 1080,
  "templateType": "monthly_report",
  "remark": "safety inspection photo"
}
```

Keep common searchable fields as table columns, not JSON-only fields.

## Error Handling

Use `common.exception.BusinessException` for business errors.

Suggested mappings:

- Missing file, invalid business type, unsupported preview type: `ErrorCode.PARAM_ERROR`
- File not found or logically deleted: `ErrorCode.NOT_FOUND`
- Duplicate or conflicting state if introduced: `ErrorCode.CONFLICT`
- MinIO operation failure: `ErrorCode.EXTERNAL_SERVICE_ERROR`
- Unexpected persistence failure: let global exception handling map it as a system error

MinIO SDK exceptions should be wrapped in the infra adapter or converted by the application service. Do not leak SDK exception messages directly to API consumers.

## Configuration

Recommended future file-specific configuration:

```yaml
app:
  file:
    access-url-expire-seconds: 600
    max-size-bytes: 104857600
    allowed-content-types:
      - application/pdf
      - image/png
      - image/jpeg
      - image/webp
      - text/plain
```

Use environment variables for deployment-specific values. Do not write real secrets into config, SQL, examples, or tests.

## Testing Guidance

After adding runnable file-management functionality, run:

```bash
mvn test
```

Add focused tests around:

- object name generation
- business type and content type validation
- upload metadata persistence
- signed URL generation flow
- logical delete behavior
- repository queries filtering `deleted = 0`

For MinIO integration, prefer adapter-level tests with a controlled test container or mockable adapter boundary. Do not require real production MinIO in unit tests.

## Uploaded File Parsing Design

The `file` module should also provide parsing for already-uploaded files. This feature converts Word and PDF documents to Markdown, and converts images to textual paragraph descriptions.

This capability must be implemented as a file-module use case, not as logic inside knowledge, OCR, report, or review modules. Other modules should consume parsing results through the file module or through a future file facade.

### Scope

Supported input files:

```text
Word: .doc, .docx
PDF: .pdf
Images: .png, .jpg, .jpeg, .webp
```

Supported output:

```text
Word/PDF -> Markdown
Image -> Plain text paragraph description
```

The source file must already exist in `file_object` and must be `ACTIVE`.

### Architecture Position

Parsing is a long-running AI capability. Do not execute model calls inside the controller.

Recommended package additions:

```text
com.xd.smartworksite.file
├── application
│   └── FileParseApplicationService
├── domain
│   ├── FileParseRecord
│   ├── FileParseStatus
│   ├── FileParseStage
│   └── FileParseResultFormat
├── dto
│   ├── FileParseRequest
│   ├── FileParseRecordResponse
│   └── FileParseContentResponse
├── repository
│   ├── FileParseRecordRepository
│   └── MyBatisFileParseRecordRepository
├── mapper
│   └── FileParseRecordMapper
└── infra
    ├── DocumentParseModelAdapter
    ├── QwenVlDocumentParseAdapter
    ├── DocumentPreparationService
    └── ParsedDocumentStorage
```

Controllers may only call `FileParseApplicationService`.

`QwenVlDocumentParseAdapter` must hide provider-specific HTTP request and response details. Do not let application services construct QwenVL JSON payloads directly.

### Storage Access

The current `StorageAdapter` supports upload, signed URL generation, and delete. Parsing needs to read already-uploaded object content.

Extend the adapter instead of using MinIO SDK directly in parsing code:

```java
InputStream openObject(String objectName);
```

If object metadata is needed later, add a storage-facing method such as:

```java
StorageObjectStat statObject(String objectName);
```

Application services and controllers must not depend on MinIO SDK classes.

### Parsing Flow

Recommended asynchronous flow:

1. Client calls parse API with `fileId`.
2. Application service verifies the source file exists, is `ACTIVE`, and belongs to the requested project.
3. Application service checks whether an up-to-date successful parse result already exists for the same `file_id` and `file_hash`.
4. If cache is reusable, return the existing parse record.
5. Otherwise create a `file_parse_record` row with `status = PENDING`.
6. Dispatch parsing through the `task` module, Redis queue, or a local async executor during the foundation phase.
7. Worker reads the source object through `StorageAdapter`.
8. Worker prepares model input based on file type.
9. Worker calls `DocumentParseModelAdapter`.
10. Worker normalizes output to Markdown or text.
11. Worker stores the parse result as a new MinIO object.
12. Worker updates `file_parse_record` with result object name, content preview, stage, progress, and status.

Do not block the HTTP request until QwenVL finishes parsing.

### Model Strategy

Use QwenVL through an external adapter. The project must not hard-code provider credentials, endpoint URLs, or model-specific request bodies in business code.

Recommended interface:

```java
public interface DocumentParseModelAdapter {
    ParsedDocument parse(DocumentParseRequest request);
}
```

Recommended request fields:

```text
projectId
fileId
fileName
contentType
inputFormat
targetFormat
pages or image frames
prompt
requestId
```

Recommended response fields:

```text
content
resultFormat
modelName
usage
confidence
rawResponseObjectName
```

QwenVL prompt requirements:

- For Word/PDF: preserve document hierarchy, headings, paragraphs, lists, tables, and visible figure captions as Markdown.
- For images: describe the image as clear factual paragraphs in Chinese by default.
- Do not invent invisible content.
- Mark unreadable, blurred, or uncertain content explicitly.
- Preserve safety, quality, compliance, and construction-site terms when visible.

### File Preparation

Word, PDF, and image files need different preparation before model calls.

Recommended strategy:

- Images: send the image directly to QwenVL.
- PDF: split or render pages as images when visual layout or scanned content matters; use page batches to avoid model context and payload limits.
- DOCX: extract structural text where possible, and render embedded images/pages for QwenVL when visual content matters.
- DOC: convert to DOCX or PDF through a controlled converter service if required; do not shell out to uncontrolled system commands in request threads.

For the foundation phase, prefer a simple adapter boundary and a small set of supported MIME types. Reject unsupported formats with `BusinessException`.

### Result Storage

Parsing result content may be large. Do not store full Markdown for large documents only in a database column.

Recommended approach:

- Store full Markdown/text result as a MinIO object.
- Store a short preview or first section in MySQL.
- Store provider metadata and parse diagnostics in JSON.

Object naming pattern:

```text
projects/{projectId}/PARSE_RESULT/{yyyy}/{MM}/{dd}/{sourceFileId}-{parseRecordId}.md
projects/{projectId}/PARSE_RESULT/{yyyy}/{MM}/{dd}/{sourceFileId}-{parseRecordId}.txt
```

The parse result may also be registered in `file_object` if other modules need to treat it as a normal file. If doing so, add a new `FileBizType` value:

```text
PARSE_RESULT
```

When only the file module consumes the parsed object, storing the result object name in `file_parse_record` is enough.

### Database Design

Add a new Flyway migration instead of modifying existing migrations.

Recommended table:

```sql
CREATE TABLE file_parse_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Primary key ID',
  project_id BIGINT NOT NULL COMMENT 'Project ID',
  file_id BIGINT NOT NULL COMMENT 'Source file ID',
  source_file_hash VARCHAR(128) NULL COMMENT 'Source file hash at parse time',
  source_content_type VARCHAR(128) NULL COMMENT 'Source content type',
  parse_type VARCHAR(64) NOT NULL COMMENT 'Parse type',
  result_format VARCHAR(32) NOT NULL COMMENT 'MARKDOWN or TEXT',
  parser_provider VARCHAR(64) NOT NULL COMMENT 'Parser provider',
  parser_model VARCHAR(128) NULL COMMENT 'Parser model name',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'Parse status',
  progress INT NOT NULL DEFAULT 0 COMMENT 'Parse progress 0-100',
  current_stage VARCHAR(64) NULL COMMENT 'Current parse stage',
  result_object_name VARCHAR(500) NULL COMMENT 'Parsed result object name',
  result_file_id BIGINT NULL COMMENT 'Optional registered result file ID',
  content_preview TEXT NULL COMMENT 'Short parsed content preview',
  error_message TEXT NULL COMMENT 'Error message',
  metadata JSON NULL COMMENT 'Provider usage, page count, confidence, and diagnostics',
  started_at DATETIME NULL COMMENT 'Start time',
  finished_at DATETIME NULL COMMENT 'Finish time',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  created_by BIGINT NULL COMMENT 'Created by user ID',
  updated_by BIGINT NULL COMMENT 'Updated by user ID',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
  KEY idx_file_parse_project (project_id),
  KEY idx_file_parse_file (file_id),
  KEY idx_file_parse_status (status),
  KEY idx_file_parse_hash (file_id, source_file_hash),
  KEY idx_file_parse_created_at (created_at),
  KEY idx_file_parse_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='File parse record table';
```

Recommended parse status values:

```text
PENDING
RUNNING
SUCCESS
FAILED
CANCELED
```

Recommended parse stages:

```text
CREATED
LOADING_SOURCE
PREPARING_INPUT
CALLING_MODEL
NORMALIZING_RESULT
STORING_RESULT
FINISHED
FAILED
```

### API Contract

Recommended endpoints:

```text
POST   /api/files/{fileId}/parse
GET    /api/files/{fileId}/parse-records
GET    /api/files/{fileId}/parse-records/latest
GET    /api/file-parse-records/{recordId}
GET    /api/file-parse-records/{recordId}/content
POST   /api/file-parse-records/{recordId}/retry
```

Recommended parse request:

```json
{
  "projectId": 1001,
  "force": false,
  "targetFormat": "MARKDOWN",
  "language": "zh-CN"
}
```

For images, `targetFormat` should be `TEXT`.

The create-parse endpoint should return the parse record immediately:

```json
{
  "recordId": 10,
  "fileId": 20,
  "status": "PENDING",
  "progress": 0,
  "currentStage": "CREATED"
}
```

### Configuration

Recommended configuration:

```yaml
app:
  file:
    parse:
      enabled: true
      max-pages: 100
      max-image-count: 100
      result-preview-length: 2000
      qwen-vl:
        endpoint: ${QWEN_VL_ENDPOINT:}
        api-key: ${QWEN_VL_API_KEY:}
        model: ${QWEN_VL_MODEL:qwen-vl-plus}
        connect-timeout-ms: ${QWEN_VL_CONNECT_TIMEOUT_MS:5000}
        read-timeout-ms: ${QWEN_VL_READ_TIMEOUT_MS:120000}
```

Never commit a real QwenVL API key.

Do not log prompts, full document content, raw model responses, access tokens, or signed URLs unless explicitly redacted.

### Error Handling

Suggested mappings:

- Source file not found: `ErrorCode.NOT_FOUND`
- Source file deleted or inactive: `ErrorCode.CONFLICT`
- Unsupported content type or target format: `ErrorCode.PARAM_ERROR`
- QwenVL timeout or provider failure: `ErrorCode.EXTERNAL_SERVICE_ERROR`
- Parse record not found: `ErrorCode.NOT_FOUND`

Failed parse records should remain queryable with `status = FAILED` and an error summary.

### Security And Traceability

Parsing must preserve project isolation:

- Always verify `project_id`.
- Parse records must include `project_id`.
- Result objects must use project-scoped object names.
- Do not expose raw MinIO object names to API consumers unless already accepted elsewhere in file responses.

Traceability metadata should include:

- source `file_id`
- source `file_hash`
- parser provider
- parser model
- request ID
- page count or image count
- usage or token count when available
- confidence when available

### Implementation Notes

This repository is still in the engineering foundation phase. If implementing parsing before the full task module is ready, a local async executor is acceptable, but keep the application-service API compatible with a future task-backed implementation.

Do not add direct Python, database, MinIO, OCR engine, or QwenVL calls to frontend code. Frontend should call only backend file parse APIs.
