import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from app.api.routes import router
from app.core.settings import get_settings

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


@app.exception_handler(Exception)
async def handle_exception(request: Request, exc: Exception):
    logger.exception("ai service error path=%s", request.url.path)
    return JSONResponse(
        status_code=200,
        content={
            "success": False,
            "traceId": "",
            "data": None,
            "usage": {},
            "errorCode": exc.__class__.__name__,
            "errorMessage": str(exc),
        },
    )


@app.exception_handler(ValidationError)
async def handle_validation_error(request: Request, exc: ValidationError):
    return JSONResponse(
        status_code=422,
        content={
            "success": False,
            "traceId": "",
            "data": None,
            "usage": {},
            "errorCode": "VALIDATION_ERROR",
            "errorMessage": str(exc),
        },
    )
