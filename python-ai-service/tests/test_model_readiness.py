import asyncio

import httpx

from app.core.settings import Settings
from app.services.model_readiness_service import ModelReadinessService


def local_settings(**overrides):
    values = {
        "ai_deployment_mode": "LOCAL_ONLY",
        "qwen_base_url": "http://local-llm:8000/v1",
        "qwen_model": "smart-worksite-chat",
        "qwen_vl_endpoint": "http://local-llm:8000/v1/chat/completions",
        "qwen_vl_model": "smart-worksite-chat",
        "qwen_embedding_base_url": "http://local-embedding:8000/v1",
        "qwen_embedding_model": "smart-worksite-embedding",
        "qwen_rerank_base_url": "http://local-reranker:8000/v1/rerank",
        "qwen_rerank_model": "smart-worksite-reranker",
        "model_profile_name": "a6000x2-stable-16k",
        "chat_max_model_len": 16384,
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


def test_model_readiness_reports_reachable_models_without_endpoint_or_secrets():
    async def handler(request: httpx.Request):
        models = {
            "local-llm": "smart-worksite-chat",
            "local-embedding": "smart-worksite-embedding",
            "local-reranker": "smart-worksite-reranker",
        }
        return httpx.Response(200, json={"data": [{"id": models[request.url.host]}]})

    service = ModelReadinessService(local_settings(), transport=httpx.MockTransport(handler))
    result = asyncio.run(service.snapshot())

    assert result["status"] == "READY"
    assert result["profile"] == "a6000x2-stable-16k"
    assert result["maxContextTokens"] == 16384
    assert all(item["configured"] and item["reachable"] for item in result["dependencies"].values())
    assert all(item["endpointScope"] == "LOCAL" for item in result["dependencies"].values())
    assert "http://" not in str(result)
    assert "secret" not in str(result)


def test_model_readiness_distinguishes_unreachable_and_missing_model():
    async def handler(request: httpx.Request):
        if request.url.host == "local-embedding":
            raise httpx.ConnectError("secret-internal-address", request=request)
        if request.url.host == "local-reranker":
            return httpx.Response(200, json={"data": [{"id": "another-model"}]})
        return httpx.Response(200, json={"data": [{"id": "smart-worksite-chat"}]})

    result = asyncio.run(
        ModelReadinessService(local_settings(), transport=httpx.MockTransport(handler)).snapshot()
    )

    assert result["status"] == "DEGRADED"
    assert result["dependencies"]["embedding"]["status"] == "CONNECT_ERROR"
    assert result["dependencies"]["rerank"]["status"] == "MODEL_NOT_FOUND"
    assert "secret-internal-address" not in str(result)


def test_remote_dependencies_are_configured_but_not_probed():
    settings = Settings(_env_file=None)
    result = asyncio.run(ModelReadinessService(settings).snapshot())

    assert result["dependencies"]["chat"]["endpointScope"] == "REMOTE"
    assert result["dependencies"]["chat"]["reachable"] is None
    assert result["dependencies"]["chat"]["status"] == "NOT_PROBED_REMOTE"

def test_models_url_normalizes_supported_openai_compatible_endpoints():
    normalize = ModelReadinessService._models_url

    assert normalize("http://local-llm:8000/v1") == "http://local-llm:8000/v1/models"
    assert normalize("http://local-llm:8000/v1/chat/completions") == "http://local-llm:8000/v1/models"
    assert normalize("http://local-reranker:8000/v1/rerank") == "http://local-reranker:8000/v1/models"
    assert normalize("http://local-embedding:8000/v1/embeddings") == "http://local-embedding:8000/v1/models"
    assert normalize("http://local-llm:8000/v1/models?token=secret") == "http://local-llm:8000/v1/models"


def test_model_health_timeout_must_be_positive_and_bounded():
    import pytest
    from pydantic import ValidationError

    with pytest.raises(ValidationError):
        local_settings(model_health_timeout_seconds=0)
    with pytest.raises(ValidationError):
        local_settings(model_health_timeout_seconds=31)


def test_model_readiness_probes_local_dependencies_concurrently():
    async def handler(request: httpx.Request):
        await asyncio.sleep(0.05)
        models = {
            "local-llm": "smart-worksite-chat",
            "local-embedding": "smart-worksite-embedding",
            "local-reranker": "smart-worksite-reranker",
        }
        return httpx.Response(200, json={"data": [{"id": models[request.url.host]}]})

    async def measure():
        loop = asyncio.get_running_loop()
        started = loop.time()
        result = await ModelReadinessService(
            local_settings(), transport=httpx.MockTransport(handler)
        ).snapshot()
        return result, loop.time() - started

    result, elapsed = asyncio.run(measure())

    assert result["status"] == "READY"
    assert elapsed < 0.15
