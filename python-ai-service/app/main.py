import logging
from uuid import uuid4
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from app.api.routes import router
from app.core.settings import get_settings
from app.services.context_budget import ContextBudgetExceeded
from app.core.deployment import ModelPolicyViolation
from app.services.policy_crawler_service import (
    PolicyCrawlerBlockedError,
    PolicyCrawlerNetworkDisabledError,
    PolicyCrawlerResponseError,
    PolicyCrawlerTimeoutError,
    PolicyCrawlerUrlError,
)

logging.basicConfig(level=logging.INFO)
logging.getLogger("httpx").setLevel(logging.WARNING)
logger = logging.getLogger("python-ai-service")


@asynccontextmanager
async def lifespan(_: FastAPI):
    # Construct settings during startup so an invalid LOCAL_ONLY endpoint fails
    # before the service accepts requests.
    settings = get_settings()
    logger.info(
        "AI deployment configured mode=%s dependencies=%s",
        settings.ai_deployment_mode.value,
        settings.safe_ai_dependency_descriptors(),
    )
    yield


app = FastAPI(
    title="Smart Worksite Python AI Service",
    version="1.0.0",
    lifespan=lifespan,
)
app.include_router(router)


@app.exception_handler(ContextBudgetExceeded)
async def handle_context_budget_exceeded(request: Request, exc: ContextBudgetExceeded):
    logger.warning("context budget exceeded path=%s reason=%s", request.url.path, exc.reason)
    return JSONResponse(
        status_code=200,
        content={
            "success": False,
            "traceId": "",
            "data": None,
            "usage": {},
            "errorCode": "VALIDATION_ERROR",
            "errorMessage": "Model context budget exceeded",
            "errorDetails": {"code": exc.code, "reason": exc.reason},
        },
    )


@app.exception_handler(ModelPolicyViolation)
async def handle_model_policy_violation(request: Request, exc: ModelPolicyViolation):
    trace_id = uuid4().hex
    logger.warning("model policy rejected request path=%s code=%s traceId=%s", request.url.path, exc.code, trace_id)
    public_message = (
        "Model request contains protected fields"
        if exc.code == "MODEL_REQUEST_INVALID"
        else "Requested model is not allowed by deployment policy"
    )
    return JSONResponse(
        status_code=422,
        content={
            "success": False,
            "traceId": trace_id,
            "data": None,
            "usage": {},
            "errorCode": exc.code,
            "errorMessage": public_message,
        },
    )


@app.exception_handler(PolicyCrawlerNetworkDisabledError)
@app.exception_handler(PolicyCrawlerUrlError)
@app.exception_handler(PolicyCrawlerResponseError)
async def handle_policy_crawler_error(request: Request, exc: Exception):
    if isinstance(exc, PolicyCrawlerNetworkDisabledError):
        status_code, error_code, message = 503, "POLICY_CRAWLER_NETWORK_DISABLED", "政策爬虫网络访问未启用"
    elif isinstance(exc, PolicyCrawlerUrlError) and "robots.txt" in str(exc):
        status_code, error_code, message = 422, "POLICY_CRAWLER_ROBOTS_DENIED", "目标网站的 robots.txt 禁止抓取"
    elif isinstance(exc, PolicyCrawlerUrlError):
        status_code, error_code, message = 422, "POLICY_CRAWLER_URL_REJECTED", "目标地址不符合安全抓取规则"
    elif isinstance(exc, PolicyCrawlerBlockedError):
        status_code, error_code, message = 502, "POLICY_CRAWLER_TARGET_BLOCKED", "目标网站拒绝了抓取请求"
    elif isinstance(exc, PolicyCrawlerTimeoutError):
        status_code, error_code, message = 504, "POLICY_CRAWLER_TIMEOUT", "目标网站响应超时"
    else:
        status_code, error_code, message = 502, "POLICY_CRAWLER_INVALID_RESPONSE", "目标网站未返回可解析的 HTML 内容"
    logger.warning("policy crawler request rejected path=%s code=%s", request.url.path, error_code)
    return JSONResponse(
        status_code=status_code,
        content={
            "success": False,
            "traceId": uuid4().hex,
            "data": None,
            "usage": {},
            "errorCode": error_code,
            "errorMessage": message,
        },
    )


@app.exception_handler(Exception)
async def handle_exception(request: Request, exc: Exception):
    logger.exception("ai service error path=%s", request.url.path)
    return JSONResponse(
        status_code=500,
        content={
            "success": False,
            "traceId": "",
            "data": None,
            "usage": {},
            "errorCode": "INTERNAL_ERROR",
            "errorMessage": "Internal service error",
        },
    )


def safe_validation_details(exc: ValidationError | RequestValidationError):
    return [
        {
            "field": ".".join(str(part) for part in error.get("loc", ()) if part != "body"),
            "message": "Invalid value",
            "type": error.get("type", "validation_error"),
        }
        for error in exc.errors()
    ]


async def handle_validation_error(request: Request, exc: ValidationError | RequestValidationError):
    return JSONResponse(
        status_code=422,
        content={
            "success": False,
            "traceId": "",
            "data": None,
            "usage": {},
            "errorCode": "VALIDATION_ERROR",
            "errorMessage": "Request validation failed",
            "errorDetails": safe_validation_details(exc),
        },
    )


app.add_exception_handler(ValidationError, handle_validation_error)
app.add_exception_handler(RequestValidationError, handle_validation_error)
