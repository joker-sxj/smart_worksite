# Smart Worksite Python AI Service

This service wraps Qwen model calls for the Java backend. The frontend must not call it directly.

## Start

```powershell
cd python-ai-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy .env.example .env
# set QWEN_API_KEY and QWEN_VL_API_KEY in .env when using provider-backed features
# Usually started automatically by Java local startup or deploy/docker-compose-env.yml.
# For standalone debugging only:
uvicorn app.main:app --host 0.0.0.0 --port 8015
```

## API Key

Set `AI_SERVICE_API_KEY` in Python and `AI_PYTHON_API_KEY` in Java. Java sends it as `X-AI-Service-Key`.

Qwen secrets stay in this Python service. Do not configure Qwen API keys in Java.


## RAG and Agent

RAG indexing is available at `POST /v1/rag/index`; search is available at `POST /v1/rag/search`. The service chunks documents, creates embeddings, writes them to the configured vector store, retrieves by vector similarity, and applies Qwen rerank when configured, with lexical fallback for offline tests or provider failures.

Vector providers:

- `RAG_PROVIDER=LOCAL`: local JSONL vector store for development.
- `RAG_PROVIDER=PGVECTOR`: PostgreSQL with pgvector extension.
- `RAG_PROVIDER=MILVUS`: Milvus collection.

Embedding providers:

- `EMBEDDING_PROVIDER=QWEN`: use Qwen embeddings.
- `EMBEDDING_PROVIDER=LOCAL_HASH`: deterministic local embeddings for offline tests only.

Rerank providers:

- `RERANK_PROVIDER=QWEN`: use the DashScope text-rerank endpoint, configured by `QWEN_RERANK_BASE_URL`, `QWEN_RERANK_MODEL`, and `QWEN_RERANK_API_STYLE`. `LEGACY` uses the DashScope `input/parameters` body, which is the verified request shape for the current text-rerank endpoint.
- Any non-`QWEN` value uses the built-in lexical rerank fallback.

Agent tool execution uses a registry. The built-in `rag_search` tool lets the Agent retrieve indexed project knowledge during reasoning; `database_query_plan` lets the Agent ask the database-Q&A planner for read-only SQL plans.

## OCR

OCR recognition is available at `POST /v1/ocr/recognize`. Java sends project ID, OCR record ID, OCR type, a temporary file URL, and optional invoice/custom field definitions. For image inputs, this service downloads the temporary URL locally, converts the image to a `data:image/...;base64,...` URL, and then calls Qwen VL. This avoids sending MinIO signed URLs to DashScope and avoids requiring DashScope to access local or private MinIO endpoints.

The service returns normalized `ocrType`, `confidence`, `fields`, `extras`, and `raw` data.

Qwen VL configuration:

```env
QWEN_VL_ENDPOINT=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
QWEN_VL_API_KEY=
QWEN_VL_MODEL=qwen-vl-plus
QWEN_VL_TIMEOUT_SECONDS=120
QWEN_VL_MAX_IMAGE_BYTES=10485760
QWEN_VL_MAX_TOKENS=8192
```

Supported OCR types are `ID_CARD`, `LICENSE_PLATE`, `INVOICE`, and `CUSTOM`. Contract extraction should use `CUSTOM` with field definitions such as party A, party B, contract amount, and payment terms. PDF inputs still require provider-supported file URLs or a future PDF-to-image preprocessing path.


## Policy Crawler

Policy/news HTML extraction is available at `POST /v1/policy/crawl`. Java sends the project ID, source ID, URL, and optional last crawl time. The Python service downloads public HTML, removes common page noise, extracts title, content, publish date, and policy number, then returns structured articles. It does not write Java databases, MinIO, or vector stores directly; Java owns persistence, external-call logs, and RAG indexing.

Document page OCR is available at `POST /v1/document/understand`. Bounded PDF recovery is available at `POST /v1/document/pdf/recover`: it validates decoded size, PDF signature, encryption, and page count, then uses `pypdf` strict-disabled reading to rewrite only a recoverable in-memory copy. It never overwrites the uploaded source and returns stable classifications such as `PASSWORD_REQUIRED`, `RECOVERED`, and `UNRECOVERABLE`.
