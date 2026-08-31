import asyncio
from typing import Any
from urllib.parse import urlsplit, urlunsplit

import httpx

from app.core.deployment import is_local_model_endpoint
from app.core.settings import Settings


class ModelReadinessService:
    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None):
        self.settings = settings
        self.transport = transport

    async def snapshot(self) -> dict[str, Any]:
        descriptors = self.settings.ai_dependency_descriptors()
        async with httpx.AsyncClient(
            timeout=self.settings.model_health_timeout_seconds,
            transport=self.transport,
        ) as client:
            results = await asyncio.gather(
                *(self._probe(client, descriptor) for descriptor in descriptors.values())
            )
        dependencies = dict(zip(descriptors, results, strict=True))

        local_results = [
            item for item in dependencies.values() if item["endpointScope"] == "LOCAL"
        ]
        status = "READY" if local_results and all(item["reachable"] for item in local_results) else "DEGRADED"
        if not local_results:
            status = "NOT_APPLICABLE"
        return {
            "status": status,
            "profile": self.settings.model_profile_name,
            "maxContextTokens": self.settings.chat_max_model_len,
            "contextBudget": {
                "outputReserveTokens": self.settings.resolved_context_output_reserve_tokens(),
                "safetyReserveTokens": self.settings.resolved_context_safety_reserve_tokens(),
                "templateOverheadTokens": self.settings.context_template_overhead_tokens,
                "countMode": self._count_mode(),
            },
            "dependencies": dependencies,
        }

    def _count_mode(self) -> str:
        if self.settings.context_tokenizer_path:
            return "LOCAL_TOKENIZER"
        if self.settings.context_tokenizer_endpoint_enabled:
            suffix = "REQUIRED" if self.settings.context_require_exact_tokenizer else "WITH_ESTIMATED_FALLBACK"
            return f"LOCAL_ENDPOINT_{suffix}"
        return "ESTIMATED"

    async def _probe(self, client: httpx.AsyncClient, descriptor: dict[str, Any]) -> dict[str, Any]:
        endpoint = str(descriptor["endpoint"])
        scope = "LOCAL" if is_local_model_endpoint(endpoint) else "REMOTE"
        result = {
            "configured": bool(endpoint and descriptor.get("model")),
            "reachable": None,
            "status": "NOT_PROBED_REMOTE",
            "provider": descriptor.get("provider"),
            "model": descriptor.get("model"),
            "endpointScope": scope,
        }
        if scope != "LOCAL":
            return result

        try:
            response = await client.get(self._models_url(endpoint))
            if response.status_code >= 400:
                result.update(reachable=False, status=f"HTTP_{response.status_code}")
                return result
            model_ids = {
                str(item.get("id"))
                for item in (response.json().get("data") or [])
                if isinstance(item, dict) and item.get("id")
            }
            if descriptor.get("model") not in model_ids:
                result.update(reachable=False, status="MODEL_NOT_FOUND")
                return result
            result.update(reachable=True, status="READY")
            return result
        except (httpx.HTTPError, ValueError, TypeError):
            result.update(reachable=False, status="CONNECT_ERROR")
            return result

    @staticmethod
    def _models_url(endpoint: str) -> str:
        parsed = urlsplit(endpoint)
        path = parsed.path.rstrip("/")
        for suffix in ("/chat/completions", "/rerank", "/embeddings", "/models"):
            if path.endswith(suffix):
                path = path[: -len(suffix)]
                break
        path = f"{path.rstrip('/')}/models"
        return urlunsplit((parsed.scheme, parsed.netloc, path, "", ""))
