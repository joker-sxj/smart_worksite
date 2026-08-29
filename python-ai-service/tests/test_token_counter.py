import asyncio

import httpx
import json
import traceback
import pytest
import respx

from app.core.deployment import AiDeploymentMode
from app.core.settings import Settings
from app.models.schemas import Message
from app.services.qwen_client import QwenClient
from app.services.token_counter import ConservativeTokenCounter, TokenCount, TokenCounter


@pytest.mark.parametrize(
    "text",
    [
        "施工现场必须佩戴安全帽。",
        "Workers must wear helmets on site.",
        "GB 12523-2025 第 4.2 条限值为 55.5 dB。",
        "if (risk >= 3) { stop_work(); }",
        "| 项目 | 限值 |\n| --- | ---: |\n| 夜间 | 55 dB |",
    ],
)
def test_conservative_text_count_is_nonzero_and_labeled(text: str):
    result = ConservativeTokenCounter().count_text(text)

    assert isinstance(result, TokenCount)
    assert result.tokens > 0
    assert result.mode == "ESTIMATED"
    assert result.tokenizer == "conservative-v1"


def test_conservative_empty_text_is_zero():
    assert ConservativeTokenCounter().count_text("").tokens == 0


def test_conservative_count_is_monotonic():
    counter = ConservativeTokenCounter()

    short = counter.count_text("安全帽 required").tokens
    long = counter.count_text("安全帽 required；进入施工现场前必须正确佩戴。").tokens

    assert long > short


def test_conservative_chat_count_adds_message_overhead():
    counter = ConservativeTokenCounter()
    chat_messages = [
        Message(role="system", content="You are a safety assistant."),
        Message(role="user", content="高处作业有什么要求？"),
    ]

    result = counter.count_chat(chat_messages)
    content_tokens = sum(counter.count_text(message.content).tokens for message in chat_messages)

    assert result.tokens > content_tokens
    assert result.mode == "ESTIMATED"
    assert result.tokenizer == "conservative-v1"


def local_settings(**overrides):
    values = {
        "_env_file": None,
        "ai_deployment_mode": AiDeploymentMode.LOCAL_ONLY,
        "chat_max_model_len": 16384,
        "qwen_base_url": "http://127.0.0.1:8000/v1",
        "qwen_api_key": "",
        "qwen_vl_endpoint": "http://127.0.0.1:8001/v1/chat/completions",
        "qwen_embedding_base_url": "http://127.0.0.1:8002/v1",
        "qwen_rerank_base_url": "http://127.0.0.1:8003/v1/rerank",
    }
    values.update(overrides)
    return Settings(**values)


def chat_messages():
    return [
        Message(role="system", content="You are a safety assistant."),
        Message(role="user", content="高处作业有什么要求？"),
    ]


def test_vllm_count_chat_tokens_uses_local_tokenize_and_chat_messages_without_auth():
    settings = local_settings(qwen_api_key="top-secret")
    with respx.mock(assert_all_called=True) as router:
        route = router.post("http://127.0.0.1:8000/tokenize").mock(
            return_value=httpx.Response(200, json={"count": 17, "tokenizer": "Qwen3-local"})
        )

        result = asyncio.run(QwenClient(settings).count_chat_tokens(chat_messages()))

    request = route.calls[0].request
    assert request.url == "http://127.0.0.1:8000/tokenize"
    assert json.loads(request.content)["model"] == settings.qwen_model
    assert "Authorization" not in request.headers
    assert json.loads(request.content)["messages"] == [
        {"role": "system", "content": "You are a safety assistant."},
        {"role": "user", "content": "高处作业有什么要求？"},
    ]
    assert result == TokenCount(tokens=17, mode="EXACT", tokenizer="Qwen3-local")


def test_vllm_count_chat_tokens_retries_with_prompt_for_older_server():
    with respx.mock(assert_all_called=True) as router:
        route = router.post("http://127.0.0.1:8000/tokenize").mock(
            side_effect=[
                httpx.Response(422, json={"detail": "messages unsupported"}),
                httpx.Response(200, json={"tokens": [1, 2, 3], "model": "local-model"}),
            ]
        )

        result = asyncio.run(QwenClient(local_settings()).count_chat_tokens(chat_messages()))

    assert len(route.calls) == 2
    assert "messages" in json.loads(route.calls[0].request.content)
    assert "prompt" in json.loads(route.calls[1].request.content)
    assert result == TokenCount(tokens=3, mode="EXACT", tokenizer="local-model")


@pytest.mark.parametrize("failure", [404, "timeout"])
def test_vllm_token_counter_falls_back_when_exact_is_unavailable(failure):
    settings = local_settings(qwen_api_key="top-secret")
    with respx.mock(assert_all_called=True) as router:
        route = router.post("http://127.0.0.1:8000/tokenize")
        if failure == "timeout":
            route.mock(side_effect=httpx.ReadTimeout("private prompt", request=httpx.Request("POST", "http://127.0.0.1:8000/tokenize")))
        else:
            route.mock(return_value=httpx.Response(failure, text="Authorization: top-secret private prompt"))

        result = asyncio.run(TokenCounter(settings, QwenClient(settings)).count_chat(chat_messages()))

    assert result.mode == "ESTIMATED"
    assert result.tokenizer == "conservative-v1"
    assert "top-secret" not in str(result)
    assert "private prompt" not in str(result)


@pytest.mark.parametrize("failure", [500, "timeout"])
def test_vllm_exact_mode_raises_sanitized_error_when_tokenize_unavailable(failure):
    settings = local_settings(context_require_exact_tokenizer=True, qwen_api_key="top-secret")
    with respx.mock(assert_all_called=True) as router:
        route = router.post("http://127.0.0.1:8000/tokenize")
        if failure == "timeout":
            route.mock(side_effect=httpx.ReadTimeout("Authorization: top-secret private prompt"))
        else:
            route.mock(return_value=httpx.Response(failure, text="Authorization: top-secret private prompt"))

        with pytest.raises(RuntimeError) as error:
            asyncio.run(TokenCounter(settings, QwenClient(settings)).count_chat(chat_messages()))

    rendered_error = "".join(traceback.format_exception(error.value))
    assert "top-secret" not in rendered_error
    assert "private prompt" not in rendered_error
    assert "tokenize" in str(error.value).lower()


def test_vllm_direct_error_traceback_is_sanitized():
    settings = local_settings(qwen_api_key="top-secret")
    with respx.mock(assert_all_called=True) as router:
        router.post("http://127.0.0.1:8000/tokenize").mock(
            side_effect=httpx.ReadTimeout("Authorization: top-secret private prompt")
        )
        with pytest.raises(RuntimeError) as error:
            asyncio.run(QwenClient(settings).count_chat_tokens(chat_messages()))

    rendered_error = "".join(traceback.format_exception(error.value))
    assert "top-secret" not in rendered_error
    assert "private prompt" not in rendered_error



def test_vllm_count_chat_tokens_rejects_public_endpoint_before_request():
    settings = Settings(_env_file=None, qwen_base_url="https://api.example.com/v1")

    with respx.mock(assert_all_called=False) as router:
        route = router.post("https://api.example.com/tokenize").mock(
            return_value=httpx.Response(200, json={"count": 1})
        )
        with pytest.raises(RuntimeError, match="local"):
            asyncio.run(QwenClient(settings).count_chat_tokens(chat_messages()))

    assert not route.called
