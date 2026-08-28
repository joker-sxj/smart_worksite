import asyncio
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.core.settings import Settings, get_settings
from app.main import app
from app.models.schemas import Message
from app.services.qwen_client import OpenAICompatibleProvider, QwenClient


def local_settings(**overrides) -> Settings:
    values = {
        "ai_deployment_mode": "LOCAL_ONLY",
        "qwen_base_url": "http://local-llm:8000/v1",
        "qwen_vl_endpoint": "http://local-vlm:8000/v1/chat/completions",
        "qwen_embedding_base_url": "http://local-embedding:8000/v1",
        "qwen_rerank_base_url": "http://local-reranker:8000/v1/rerank",
        "qwen_api_key": "",
        "qwen_vl_api_key": "",
        "qwen_rerank_api_style": "QWEN3",
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


@pytest.mark.parametrize(
    "url",
    [
        "http://127.0.0.1:8000/v1",
        "http://localhost:8000/v1",
        "http://local-llm:8000/v1",
        "http://host.docker.internal:8000/v1",
        "http://10.20.0.10:8000/v1",
        "http://172.20.0.10:8000/v1",
        "http://192.168.1.10:8000/v1",
        "http://[::1]:8000/v1",
    ],
)
def test_local_only_accepts_local_or_private_model_endpoints(url):
    settings = local_settings(qwen_base_url=url)

    assert settings.ai_deployment_mode == "LOCAL_ONLY"


def test_example_env_uses_local_reranker_container_port():
    env_file = Path(__file__).resolve().parents[1] / ".env.example"

    assert "QWEN_RERANK_BASE_URL=http://local-reranker:8000/v1/rerank" in env_file.read_text()


def test_example_env_matches_local_chat_topology():
    env_file = Path(__file__).resolve().parents[1] / ".env.example"
    env = env_file.read_text()

    assert "QWEN_VL_ENDPOINT=http://local-llm:8000/v1/chat/completions" in env
    assert "QWEN_VL_MODEL=smart-worksite-chat" in env


@pytest.mark.parametrize(
    "field,url",
    [
        ("qwen_base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        ("qwen_base_url", "https://api.openai.com/v1"),
        ("qwen_base_url", "http://8.8.8.8:8000/v1"),
        ("qwen_base_url", "http://203.0.113.10:8000/v1"),
        ("qwen_base_url", "http://169.254.169.254/latest/meta-data"),
        ("qwen_base_url", "http://134744072:8000/v1"),
        ("qwen_vl_endpoint", "https://example.com/v1/chat/completions"),
        ("qwen_rerank_base_url", "https://dashscope.aliyuncs.com/api/v1/rerank"),
        ("qwen_base_url", "file:///etc/passwd"),
        ("qwen_base_url", "http://user:password@local-llm:8000/v1"),
    ],
)
def test_local_only_rejects_public_or_unsafe_model_endpoints(field, url):
    with pytest.raises(ValidationError, match="LOCAL_ONLY"):
        local_settings(**{field: url})


class FakeResponse:
    text = ""

    def __init__(self, body):
        self._body = body
        self.content = b""
        self.headers = {}
        self.status_code = 200

    def raise_for_status(self):
        return None

    def json(self):
        return self._body


class FakeAsyncClient:
    calls = []
    responses = []

    def __init__(self, **kwargs):
        self.kwargs = kwargs

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return None

    async def post(self, url, headers=None, json=None):
        self.__class__.calls.append({"url": url, "headers": headers or {}, "json": json})
        return FakeResponse(self.__class__.responses.pop(0))


def install_fake_http(monkeypatch, *responses):
    from app.services import qwen_client as qwen_module

    FakeAsyncClient.calls = []
    FakeAsyncClient.responses = list(responses)
    monkeypatch.setattr(qwen_module.httpx, "AsyncClient", FakeAsyncClient)


def test_local_openai_compatible_chat_forces_thinking_off(monkeypatch):
    install_fake_http(monkeypatch, {
        "choices": [{"message": {"content": "LOCAL_OK"}}],
        "usage": {},
    })

    asyncio.run(QwenClient(local_settings()).chat(
        [Message(role="user", content="ping")],
        parameters={"chat_template_kwargs": {"enable_thinking": True}},
    ))

    payload = FakeAsyncClient.calls[0]["json"]
    assert payload["chat_template_kwargs"] == {"enable_thinking": False}

def test_local_openai_compatible_chat_does_not_require_api_key(monkeypatch):
    install_fake_http(monkeypatch, {
        "choices": [{"message": {"content": "LOCAL_OK"}}],
        "usage": {"prompt_tokens": 1, "completion_tokens": 1},
    })

    answer, usage = asyncio.run(QwenClient(local_settings()).chat([Message(role="user", content="ping")]))

    assert answer == "LOCAL_OK"
    assert usage["completion_tokens"] == 1
    assert FakeAsyncClient.calls[0]["url"] == "http://local-llm:8000/v1/chat/completions"
    assert "Authorization" not in FakeAsyncClient.calls[0]["headers"]


def test_all_local_model_calls_omit_authorization_without_key(monkeypatch):
    install_fake_http(
        monkeypatch,
        {"data": [{"index": 0, "embedding": [0.1, 0.2]}], "usage": {}},
        {"results": [{"index": 0, "relevance_score": 0.9}], "usage": {}},
        {"choices": [{"message": {"content": '{"fields":[]}'}}], "usage": {}},
    )
    client = QwenClient(local_settings(qwen_embedding_dimensions=0))

    asyncio.run(client.embed(["document"]))
    asyncio.run(client.rerank("query", ["document"], 1))
    asyncio.run(client.vision_json_chat("extract", "data:image/jpeg;base64,ZmFrZQ==", "image/jpeg"))

    assert [call["url"] for call in FakeAsyncClient.calls] == [
        "http://local-embedding:8000/v1/embeddings",
        "http://local-reranker:8000/v1/rerank",
        "http://local-vlm:8000/v1/chat/completions",
    ]
    assert all("Authorization" not in call["headers"] for call in FakeAsyncClient.calls)


def test_cloud_compatible_mode_still_requires_api_key():
    settings = Settings(_env_file=None, qwen_api_key="")

    with pytest.raises(RuntimeError, match="QWEN_API_KEY"):
        asyncio.run(QwenClient(settings).chat([Message(role="user", content="ping")]))


def test_dependency_descriptors_do_not_expose_keys():
    settings = local_settings(qwen_api_key="local-secret", qwen_vl_api_key="vision-secret")

    descriptors = settings.ai_dependency_descriptors()

    assert descriptors["chat"]["model"] == settings.qwen_model
    assert descriptors["chat"]["endpoint"] == "http://local-llm:8000/v1"
    assert descriptors["embedding"]["endpoint"] == "http://local-embedding:8000/v1"
    assert "local-secret" not in str(descriptors)
    assert "vision-secret" not in str(descriptors)


def test_health_exposes_sanitized_local_dependency_configuration():
    settings = local_settings(qwen_api_key="must-not-leak")
    app.dependency_overrides = {}
    get_settings.cache_clear()
    app.dependency_overrides[get_settings] = lambda: settings
    try:
        # The route calls get_settings directly, so replace the cached object too.
        from app.api import routes

        original = routes.get_settings
        routes.get_settings = lambda: settings
        response = TestClient(app).get("/v1/health")
    finally:
        routes.get_settings = original
        app.dependency_overrides.clear()
        get_settings.cache_clear()

    body = response.json()["data"]
    assert body["deploymentMode"] == "LOCAL_ONLY"
    assert body["dependencies"]["chat"]["model"] == settings.qwen_model
    assert "must-not-leak" not in response.text


def test_local_only_validates_dedicated_embedding_endpoint():
    with pytest.raises(ValidationError, match="QWEN_EMBEDDING_BASE_URL"):
        local_settings(qwen_embedding_base_url="https://api.example.com/v1")


def test_local_deployment_examples_have_no_cloud_fallbacks():
    from pathlib import Path

    repository = Path(__file__).resolve().parents[2]
    examples = [
        repository / "python-ai-service" / ".env.example",
        repository / "deploy" / ".env.example",
        repository / "deploy" / "docker-compose-env.yml",
    ]
    for example in examples:
        content = example.read_text(encoding="utf-8")
        assert "AI_DEPLOYMENT_MODE=LOCAL_ONLY" in content or "AI_DEPLOYMENT_MODE:" in content
        assert "dashscope.aliyuncs.com" not in content
        assert "api.openai.com" not in content


def test_start_scripts_only_require_model_key_when_cloud_is_allowed():
    from pathlib import Path

    repository = Path(__file__).resolve().parents[2]
    for relative_path in ("scripts/start-all.ps1", "scripts/start-all.sh"):
        content = (repository / relative_path).read_text(encoding="utf-8")
        assert "AI_DEPLOYMENT_MODE" in content
        assert "CLOUD_ALLOWED" in content

def test_qwen_client_remains_a_compatibility_alias():
    assert QwenClient is OpenAICompatibleProvider

def test_app_startup_validates_ai_configuration(monkeypatch):
    import importlib

    main_module = importlib.import_module("app.main")

    def fail_validation():
        raise RuntimeError("invalid local AI configuration")

    monkeypatch.setattr(main_module, "get_settings", fail_validation, raising=False)
    with pytest.raises(RuntimeError, match="invalid local AI configuration"):
        with TestClient(main_module.app):
            pass


@pytest.mark.parametrize("field", ["ai_allow_remote_inference", "ai_allow_cloud_fallback"])
def test_local_only_rejects_remote_inference_policy_flags(field):
    with pytest.raises(ValidationError, match="LOCAL_ONLY"):
        local_settings(**{field: True})


def test_cloud_allowed_accepts_remote_inference_policy_flags():
    settings = Settings(
        _env_file=None,
        ai_allow_remote_inference=True,
        ai_allow_cloud_fallback=True,
    )

    assert settings.ai_allow_remote_inference is True
    assert settings.ai_allow_cloud_fallback is True


def test_policy_crawler_network_is_disabled_by_default():
    settings = local_settings()

    assert settings.policy_crawler_network_enabled is False


def test_local_only_allows_policy_crawler_network_independently():
    settings = local_settings(policy_crawler_network_enabled=True)

    assert settings.policy_crawler_network_enabled is True


def test_deploy_examples_disable_remote_inference_and_crawler_network_by_default():
    repository = Path(__file__).resolve().parents[2]
    expected = (
        "AI_ALLOW_REMOTE_INFERENCE",
        "AI_ALLOW_CLOUD_FALLBACK",
        "POLICY_CRAWLER_NETWORK_ENABLED",
    )

    env_content = (repository / "deploy" / ".env.example").read_text(encoding="utf-8")
    compose_content = (repository / "deploy" / "docker-compose-env.yml").read_text(encoding="utf-8")
    for name in expected:
        assert f"{name}=false" in env_content
        assert f"{name}: ${{{name}:-false}}" in compose_content
