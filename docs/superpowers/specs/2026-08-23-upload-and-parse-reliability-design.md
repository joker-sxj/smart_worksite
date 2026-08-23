# Upload Limit and Document Parse Reliability Design

Date: 2026-08-23

## Problem

Linux acceptance testing exposed two independent failures:

1. Knowledge and file uploads use the shared frontend uploader's implicit 20 MiB limit, while the Java multipart and business limits are 100 MiB. A 20,770 KiB PDF is therefore rejected by the browser even though the backend accepts it.
2. The Java backend runs on the Linux host but receives `QWEN_VL_ENDPOINT=http://local-llm:8000/...`, a Docker-only hostname. The host-local vLLM endpoint at `127.0.0.1:18000` is healthy, so document parse jobs fail before inference with `UnresolvedAddressException`.
3. Text-bearing office documents currently depend on the model call after deterministic extraction. A temporary model endpoint failure turns otherwise usable extracted content into a complete parse failure.

## Scope

Change only shared upload validation, file parsing, lifecycle preflight, related configuration/docs, and their tests. Do not alter OCR, review, report generation, RAG retrieval, database data, MinIO objects, Redis state, or model performance settings.

## Design

### Unified upload limit

- Use 100 MiB as the explicit default for the shared frontend uploader and for knowledge/file/review upload entry points.
- Compare integer bytes against `maxSizeMb * 1024 * 1024`.
- Show the measured file size and configured limit in rejection messages.
- Keep template-specific limits explicit where the business flow intentionally differs.
- Keep Java multipart and `app.file.max-size-bytes` at 100 MiB and document this as the source-of-truth deployment default.

### Host/container model endpoint separation

- Java host endpoint: `http://127.0.0.1:${CHAT_HOST_PORT}/v1/chat/completions`.
- Python container endpoint: `http://local-llm:8000/v1/chat/completions`.
- Normalize any Docker-local hostname supplied for the Java process, including legacy and Compose service/container names.
- Reject malformed Markdown-style copied URLs and unsupported host-local endpoint values during startup with an actionable message.
- Before starting Java, verify the configured host endpoint's model service through `/v1/models` when a local model profile is active.
- Never modify user backup files or persisted application data.

### Deterministic parse fallback

- Image-only input still requires the vision/model endpoint and fails visibly when unavailable.
- If DOC/DOCX/XLS/XLSX/CSV/PPT/PPTX/TXT/MD or a text-bearing PDF was extracted successfully, a model transport/configuration failure falls back to normalized extracted text/Markdown.
- A non-success model HTTP response or invalid model response also falls back only when deterministic text exists.
- The parse record succeeds with metadata identifying `LOCAL_TEXT_FALLBACK` and the model failure reason.
- Empty/scanned PDFs do not produce false success; they continue through the OCR/vision-required failure path.

## Testing

- Frontend tests cover a 20,770 KiB PDF being accepted under the 100 MiB default and a file over 100 MiB being rejected with measured size.
- Shell contract tests cover Docker hostnames, custom `CHAT_HOST_PORT`, malformed Markdown URLs, and correct host/container separation.
- Java adapter tests cover text fallback for connection/HTTP/response failures and no fallback for image-only input.
- Run focused tests first, then complete Java, frontend, Python, shell contract, build, and Compose configuration verification.

## Deployment acceptance

After pulling the fixed commit, Linux startup must show a Java endpoint using `127.0.0.1:18000`, `/v1/models` must return `smart-worksite-chat`, the supplied 20,770 KiB PDF must upload, and the supplied DOCX/PDF materials must reach either successful model-enhanced parsing or explicit deterministic fallback without `UnresolvedAddressException`.
