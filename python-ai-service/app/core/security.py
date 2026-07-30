from fastapi import Header, HTTPException, Request
from .settings import get_settings


async def verify_service_key(request: Request, x_ai_service_key: str | None = Header(default=None)) -> None:
    if request.url.path == "/v1/health":
        return
    expected = get_settings().ai_service_api_key
    if expected and x_ai_service_key != expected:
        raise HTTPException(status_code=401, detail="invalid ai service key")
