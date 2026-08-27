from uuid import uuid4
from fastapi import APIRouter, Depends
from app.core.security import verify_service_key
from app.core.settings import get_settings
from app.models.schemas import (
    StandardResponse,
    ModelInvokeRequest,
    ModelInvokeData,
    AgentInvokeRequest,
    AgentInvokeData,
    RagSearchRequest,
    RagSearchData,
    RagIndexRequest,
    RagIndexData,
    RagDeleteRequest,
    RagDeleteData,
    RouteRequest,
    RouteData,
    ContextPrepareRequest,
    ContextPrepareData,
    DatabaseGenerateQueryRequest,
    DatabaseGenerateQueryData,
    DatabaseSummarizeRequest,
    DatabaseSummarizeData,
    OcrRecognizeRequest,
    OcrRecognizeData,
    PolicyCrawlRequest,
    PolicyCrawlData,
    DocumentUnderstandingRequest,
    DocumentUnderstandingData,
)
from app.services.qwen_client import QwenClient
from app.services.model_readiness_service import ModelReadinessService
from app.services.model_service import ModelService, AgentService
from app.services.rag_service import RagService
from app.services.route_context_service import RouteService, ContextService
from app.services.database_service import DatabaseQaService
from app.services.agent_tools import ToolRegistry, ToolSpec
from app.services.ocr_service import OcrService
from app.services.policy_crawler_service import PolicyCrawlerService
from app.services.document_understanding_service import DocumentUnderstandingService

router = APIRouter(prefix="/v1", dependencies=[Depends(verify_service_key)])


def trace_id() -> str:
    return uuid4().hex


def ok(data, usage=None):
    return StandardResponse(traceId=trace_id(), data=data, usage=usage or {})


def services():
    settings = get_settings()
    qwen = QwenClient(settings)
    rag = RagService(settings, qwen)
    db = DatabaseQaService(qwen)
    registry = ToolRegistry()

    async def rag_search_tool(args):
        data, usage = await rag.search(RagSearchRequest(**args))
        return {"data": data.model_dump(), "usage": usage}

    async def database_query_plan_tool(args):
        data, usage = await db.generate_query(DatabaseGenerateQueryRequest(**args))
        return {"data": data.model_dump(), "usage": usage}

    registry.register(ToolSpec(
        name="rag_search",
        description="Search project knowledge chunks with vector retrieval and rerank.",
        parameters={"query": "string", "projectId": "integer", "knowledgeBaseIds": "array"},
        func=rag_search_tool,
    ))
    registry.register(ToolSpec(
        name="database_query_plan",
        description="Generate a safe read-only SQL query plan for database Q&A. Java must validate and execute the SQL.",
        parameters={"question": "string", "schemaSummary": "string", "permissionHints": "object", "projectId": "integer"},
        func=database_query_plan_tool,
    ))
    return {
        "model": ModelService(qwen),
        "agent": AgentService(qwen, registry),
        "rag": rag,
        "route": RouteService(qwen),
        "context": ContextService(qwen),
        "database": db,
        "ocr": OcrService(qwen),
        "policy": PolicyCrawlerService(settings),
        "documentUnderstanding": DocumentUnderstandingService(qwen),
    }


@router.get("/health")
async def health():
    settings = get_settings()
    readiness = await ModelReadinessService(settings).snapshot()
    return ok({
        "status": "UP",
        "service": "python-ai-service",
        "deploymentMode": settings.ai_deployment_mode.value,
        "dependencies": settings.safe_ai_dependency_descriptors(),
        "modelReadiness": readiness,
    })


@router.post("/model/invoke", response_model=StandardResponse[ModelInvokeData])
async def model_invoke(request: ModelInvokeRequest):
    answer, usage = await services()["model"].invoke(request)
    return ok(ModelInvokeData(answer=answer, usage=usage), usage)


@router.post("/agent/invoke", response_model=StandardResponse[AgentInvokeData])
async def agent_invoke(request: AgentInvokeRequest):
    data, usage = await services()["agent"].invoke(request)
    return ok(data, usage)


@router.post("/rag/search", response_model=StandardResponse[RagSearchData])
async def rag_search(request: RagSearchRequest):
    data, usage = await services()["rag"].search(request)
    return ok(data, usage)


@router.post("/rag/index", response_model=StandardResponse[RagIndexData])
async def rag_index(request: RagIndexRequest):
    data, usage = await services()["rag"].index(request)
    return ok(data, usage)


@router.post("/rag/delete", response_model=StandardResponse[RagDeleteData])
async def rag_delete(request: RagDeleteRequest):
    data = await services()["rag"].delete(request)
    return ok(data)


@router.post("/route", response_model=StandardResponse[RouteData])
async def route(request: RouteRequest):
    data, usage = await services()["route"].route(request)
    return ok(data, usage)


@router.post("/context/prepare", response_model=StandardResponse[ContextPrepareData])
async def context_prepare(request: ContextPrepareRequest):
    data, usage = await services()["context"].prepare(request)
    return ok(data, usage)


@router.post("/database/generate-query", response_model=StandardResponse[DatabaseGenerateQueryData])
async def database_generate_query(request: DatabaseGenerateQueryRequest):
    data, usage = await services()["database"].generate_query(request)
    return ok(data, usage)


@router.post("/database/summarize-result", response_model=StandardResponse[DatabaseSummarizeData])
async def database_summarize_result(request: DatabaseSummarizeRequest):
    data, usage = await services()["database"].summarize_result(request)
    return ok(data, usage)


@router.post("/ocr/recognize", response_model=StandardResponse[OcrRecognizeData])
async def ocr_recognize(request: OcrRecognizeRequest):
    data, usage = await services()["ocr"].recognize(request)
    return ok(data, usage)


@router.post("/policy/crawl", response_model=StandardResponse[PolicyCrawlData])
async def policy_crawl(request: PolicyCrawlRequest):
    data, usage = await services()["policy"].crawl(request)
    return ok(data, usage)


@router.post("/document/understand", response_model=StandardResponse[DocumentUnderstandingData])
async def document_understand(request: DocumentUnderstandingRequest):
    data, usage = await services()["documentUnderstanding"].understand(request)
    return ok(data, usage)
