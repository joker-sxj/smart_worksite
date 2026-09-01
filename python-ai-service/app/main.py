import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from app.api.routes import router
from app.core.settings import get_settings
from app.services.context_budget import ContextBudgetExceeded

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("python-ai-service")


@asynccontextmanager
async def lifespan(_: FastAPI):
    # Construct settings during startup so an invalid LOCAL_ONLY endpoint fails
    # before the service accepts requests.
    settings = get_settings()
    logger.info(
        "AI deployment configured mode=%s dependencies=%s",
        settings.ai_deployment_mode.value,
        settings.ai_dependency_descriptors(),
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
