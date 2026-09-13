param(
    [switch]$SkipQwen,
    [switch]$SkipKingbase
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$PythonService = Join-Path $Root "python-ai-service"

Write-Host "[1/7] Python unit tests"
Push-Location $PythonService
try {
    .\.venv\Scripts\python.exe -m pytest -q
    .\.venv\Scripts\python.exe -m compileall app
} finally {
    Pop-Location
}

Write-Host "[2/7] Java unit tests"
Push-Location $Root
try {
    mvn test -q
} finally {
    Pop-Location
}

Write-Host "[3/7] Start vector dependencies"
Push-Location $Root
try {
    docker compose --profile vector -p deploy -f deploy\docker-compose-env.yml --env-file deploy\.env up -d pgvector milvus
} finally {
    Pop-Location
}

Write-Host "[4/7] Real pgvector and Milvus RAG verification"
Push-Location $PythonService
try {
    $env:PYTHONIOENCODING = "utf-8"
    $env:EMBEDDING_PROVIDER = "LOCAL_HASH"
    $env:RERANK_PROVIDER = "LOCAL"
    $ragScript = @'
import asyncio
import os
from app.core.settings import Settings
from app.services.qwen_client import QwenClient
from app.services.rag_service import RagService
from app.models.schemas import RagIndexRequest, RagDocument, RagSearchRequest

async def verify(provider: str):
    if provider == "PGVECTOR":
        os.environ["RAG_PROVIDER"] = "PGVECTOR"
        os.environ.setdefault("PGVECTOR_DSN", "postgresql://worksite:worksite@127.0.0.1:15432/smart_worksite_vector")
        project = 3991
    else:
        os.environ["RAG_PROVIDER"] = "MILVUS"
        os.environ.setdefault("MILVUS_URI", "http://127.0.0.1:19530")
        project = 3992
    settings = Settings()
    service = RagService(settings, QwenClient(settings))
    kb = project * 1000 + 1
    data, _ = await service.index(RagIndexRequest(projectId=project, knowledgeBaseId=kb, documents=[RagDocument(documentId=f"{provider.lower()}-verify", title=f"{provider} verification", content="Workers must wear safety helmets and safety belts as required.")]))
    result, usage = await service.search(RagSearchRequest(query="safety helmets", projectId=project, knowledgeBaseIds=[kb], topK=2, scoreThreshold=0))
    print(provider, data.indexedChunks, len(result.records), usage)
    assert data.indexedChunks >= 1
    assert len(result.records) >= 1

async def main():
    await verify("PGVECTOR")
    await verify("MILVUS")

asyncio.run(main())
'@
    $ragScript | .\.venv\Scripts\python.exe -
} finally {
    Pop-Location
}

Write-Host "[5/7] Real PostgreSQL SQL execution verification"
Push-Location $Root
try {
    if (-not $env:AI_TEST_POSTGRES_JDBC_URL) { $env:AI_TEST_POSTGRES_JDBC_URL = "jdbc:postgresql://127.0.0.1:15432/smart_worksite_vector" }
    if (-not $env:AI_TEST_POSTGRES_USERNAME) { $env:AI_TEST_POSTGRES_USERNAME = "worksite" }
    if (-not $env:AI_TEST_POSTGRES_PASSWORD) { $env:AI_TEST_POSTGRES_PASSWORD = "worksite" }
    mvn -q -Dtest=SafeSqlExecutorTest#executesRealPostgresqlReadOnlyQueryWhenDsnProvided test
} finally {
    Pop-Location
}

if (-not $SkipKingbase) {
    Write-Host "[6/7] Optional Kingbase SQL execution verification"
    if (-not $env:AI_TEST_KINGBASE_JDBC_URL) {
        Write-Host "SKIP Kingbase: AI_TEST_KINGBASE_JDBC_URL is not set"
    } else {
        Push-Location $Root
        try {
            mvn -q -Dtest=SafeSqlExecutorTest#executesRealKingbaseReadOnlyQueryWhenDsnProvided test
        } finally {
            Pop-Location
        }
    }
}

if (-not $SkipQwen) {
    Write-Host "[7/7] Optional Qwen chat/embedding/rerank verification"
    Push-Location $PythonService
    try {
        $qwenScript = @'
import asyncio
import httpx
from app.core.settings import Settings
from app.services.qwen_client import QwenClient
from app.models.schemas import Message

async def main():
    client = QwenClient(Settings())
    checks = [
        ("CHAT", lambda: client.chat([Message(role="user", content="Return only OK")], parameters={"temperature": 0.1})),
        ("EMBED", lambda: client.embed(["safety helmet"])),
        ("RERANK", lambda: client.rerank("helmet", ["wear helmet", "weather"], 2)),
    ]
    for name, func in checks:
        try:
            _, usage = await func()
            print(name, "OK", usage)
        except httpx.HTTPStatusError as exc:
            print(name, "HTTP_ERROR", exc.response.status_code, exc.response.text[:300])
            raise

asyncio.run(main())
'@
        $qwenScript | .\.venv\Scripts\python.exe -
    } finally {
        Pop-Location
    }
}

Write-Host "AI adaptation verification finished."
