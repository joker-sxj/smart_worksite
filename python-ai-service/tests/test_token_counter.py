import asyncio
import inspect
import threading
from concurrent.futures import ThreadPoolExecutor

import httpx
import json
import traceback
import pytest
import respx

from app.core.deployment import AiDeploymentMode
from app.core.settings import Settings
from app.models.schemas import Message
from app.services.qwen_client import QwenClient
from app.services.token_counter import (
    ChatTokenCounter,
    ConservativeTokenCounter,
    LocalHfTokenCounter,
    TokenCount,
    TokenCounter,
)


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
            return_value=httpx.Response(200, json={"count": 17, "tokenizer": "Qwen3-local", "exactChatTemplate": True})
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
    assert result == TokenCount(tokens=3, mode="ESTIMATED", tokenizer="vllm-prompt-v1")


def test_vllm_prompt_fallback_is_exact_only_when_server_confirms_chat_template():
    with respx.mock(assert_all_called=True) as router:
        router.post("http://127.0.0.1:8000/tokenize").mock(
            side_effect=[
                httpx.Response(422, json={"detail": "messages unsupported"}),
                httpx.Response(
                    200,
                    json={
                        "count": 5,
                        "tokenizer": "local-chat-template",
                        "exactChatTemplate": True,
                    },
                ),
            ]
        )

        result = asyncio.run(QwenClient(local_settings()).count_chat_tokens(chat_messages()))

    assert result == TokenCount(tokens=5, mode="EXACT", tokenizer="local-chat-template")


def test_vllm_unconfirmed_prompt_fallback_fails_when_exact_is_required():
    settings = local_settings(context_require_exact_tokenizer=True)
    with respx.mock(assert_all_called=True) as router:
        router.post("http://127.0.0.1:8000/tokenize").mock(
            side_effect=[
                httpx.Response(422, json={"detail": "messages unsupported"}),
                httpx.Response(200, json={"count": 5, "tokenizer": "local-model"}),
            ]
        )

        with pytest.raises(RuntimeError, match="Exact local tokenization failed"):
            asyncio.run(TokenCounter(settings, QwenClient(settings)).count_chat(chat_messages()))


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


@pytest.mark.parametrize(
    "text, minimum",
    [
        ("Safety workers must wear helmets before entering the construction site.", 20),
        ("安全帽 safety helmet 12345", 10),
        ("def stop_work(risk):\n    if risk >= 3:\n        return True", 25),
    ],
)
def test_conservative_estimator_is_an_explicit_upper_biased_mixed_text_estimate(text, minimum):
    result = ConservativeTokenCounter().count_text(text)

    assert result.mode == "ESTIMATED"
    assert result.tokenizer == "conservative-v1"
    assert result.tokens >= minimum


@pytest.mark.parametrize("text", ["space heavy code:    x = 1", "混合 UTF-8 text 🚧"])
def test_conservative_estimator_charges_every_utf8_byte(text):
    result = ConservativeTokenCounter().count_text(text)

    assert result.tokens == len(text.encode("utf-8"))


def test_chat_token_counter_protocol_declares_async_count_chat():
    assert inspect.iscoroutinefunction(ChatTokenCounter.count_chat)


def test_token_counter_offloads_local_hf_count_to_thread(monkeypatch, tmp_path):
    scheduled = []

    class StubLocalCounter:
        def count_chat(self, messages):
            return TokenCount(tokens=7, mode="EXACT", tokenizer="stub-local")

    async def fake_to_thread(function, *args):
        scheduled.append((function, args))
        return function(*args)

    monkeypatch.setattr(asyncio, "to_thread", fake_to_thread)
    settings = local_settings(context_tokenizer_path=str(tmp_path))
    counter = TokenCounter(settings, QwenClient(settings))
    counter.local_counter = StubLocalCounter()

    result = asyncio.run(counter.count_chat(chat_messages()))

    assert result == TokenCount(tokens=7, mode="EXACT", tokenizer="stub-local")
    assert len(scheduled) == 1
    assert scheduled[0][0].__self__ is counter.local_counter
    assert scheduled[0][0].__name__ == "count_chat"


def test_local_hf_tokenizer_load_is_single_flight_across_threads(monkeypatch, tmp_path):
    load_started = threading.Event()
    second_load_started = threading.Event()
    release_load = threading.Event()
    load_count = 0
    count_lock = threading.Lock()

    class FakeTokenizer:
        name_or_path = "single-flight-tokenizer"

        def apply_chat_template(self, messages, **kwargs):
            return [1, 2]

    class FakeAutoTokenizer:
        @classmethod
        def from_pretrained(cls, path, **kwargs):
            nonlocal load_count
            with count_lock:
                load_count += 1
                if load_count == 1:
                    load_started.set()
                else:
                    second_load_started.set()
            assert release_load.wait(timeout=2)
            return FakeTokenizer()

    class FakeTransformers:
        AutoTokenizer = FakeAutoTokenizer

    monkeypatch.setitem(__import__("sys").modules, "transformers", FakeTransformers)
    counter = LocalHfTokenCounter(str(tmp_path))

    with ThreadPoolExecutor(max_workers=2) as executor:
        first = executor.submit(counter.count_chat, chat_messages())
        assert load_started.wait(timeout=1)
        second = executor.submit(counter.count_chat, chat_messages())
        second_load_started.wait(timeout=0.2)
        release_load.set()
        results = [first.result(timeout=2), second.result(timeout=2)]

    assert load_count == 1
    assert results == [
        TokenCount(tokens=2, mode="EXACT", tokenizer="single-flight-tokenizer"),
        TokenCount(tokens=2, mode="EXACT", tokenizer="single-flight-tokenizer"),
    ]


def test_local_hf_tokenizer_is_used_without_network_when_path_is_configured(monkeypatch, tmp_path):
    calls = []

    class FakeTokenizer:
        name_or_path = "local-qwen-tokenizer"

        def apply_chat_template(self, messages, **kwargs):
            calls.append((messages, kwargs))
            return [1, 2, 3, 4]

    class FakeAutoTokenizer:
        @classmethod
        def from_pretrained(cls, path, **kwargs):
            calls.append((path, kwargs))
            return FakeTokenizer()

    class FakeTransformers:
        AutoTokenizer = FakeAutoTokenizer

    monkeypatch.setitem(__import__("sys").modules, "transformers", FakeTransformers)
    settings = local_settings(context_tokenizer_path=str(tmp_path))

    result = asyncio.run(TokenCounter(settings, QwenClient(settings)).count_chat(chat_messages()))

    assert result == TokenCount(tokens=4, mode="EXACT", tokenizer="local-qwen-tokenizer")
    assert calls[0] == (str(tmp_path), {"local_files_only": True})
    assert calls[1][1] == {
        "tokenize": True,
        "add_generation_prompt": True,
        "enable_thinking": False,
    }


def test_local_hf_load_failure_tries_local_vllm_exact_before_fallback(monkeypatch, tmp_path):
    class FakeAutoTokenizer:
        @classmethod
        def from_pretrained(cls, path, **kwargs):
            raise OSError("local tokenizer unavailable")

    class FakeTransformers:
        AutoTokenizer = FakeAutoTokenizer

    monkeypatch.setitem(__import__("sys").modules, "transformers", FakeTransformers)
    settings = local_settings(context_tokenizer_path=str(tmp_path))
    with respx.mock(assert_all_called=True) as router:
        route = router.post("http://127.0.0.1:8000/tokenize").mock(
            return_value=httpx.Response(
                200,
                json={"count": 19, "tokenizer": "vllm-local", "exactChatTemplate": True},
            )
        )

        result = asyncio.run(TokenCounter(settings, QwenClient(settings)).count_chat(chat_messages()))

    assert route.called
    assert result == TokenCount(tokens=19, mode="EXACT", tokenizer="vllm-local")


def test_configured_local_hf_tokenizer_failure_uses_estimate_when_exact_not_required(monkeypatch, tmp_path):
    class FakeAutoTokenizer:
        @classmethod
        def from_pretrained(cls, path, **kwargs):
            raise OSError("network and prompt must not leak")

    class FakeTransformers:
        AutoTokenizer = FakeAutoTokenizer

    monkeypatch.setitem(__import__("sys").modules, "transformers", FakeTransformers)
    settings = local_settings(context_tokenizer_path=str(tmp_path))
    with respx.mock(assert_all_called=True) as router:
        route = router.post("http://127.0.0.1:8000/tokenize").mock(
            return_value=httpx.Response(500, text="Authorization: top-secret private prompt")
        )

        result = asyncio.run(TokenCounter(settings, QwenClient(settings)).count_chat(chat_messages()))

    assert route.called
    assert result.mode == "ESTIMATED"
    assert result.tokenizer == "conservative-v1"


@pytest.mark.parametrize("require_exact", [False, True])
def test_configured_local_hf_chat_template_failure_is_sanitized_and_follows_policy(
    monkeypatch, tmp_path, require_exact
):
    class FakeTokenizer:
        def apply_chat_template(self, messages, **kwargs):
            raise ValueError("Authorization: top-secret private prompt")

    class FakeAutoTokenizer:
        @classmethod
        def from_pretrained(cls, path, **kwargs):
            return FakeTokenizer()

    class FakeTransformers:
        AutoTokenizer = FakeAutoTokenizer

    monkeypatch.setitem(__import__("sys").modules, "transformers", FakeTransformers)
    settings = local_settings(
        context_tokenizer_path=str(tmp_path),
        context_require_exact_tokenizer=require_exact,
    )
    with respx.mock(assert_all_called=True) as router:
        route = router.post("http://127.0.0.1:8000/tokenize").mock(
            return_value=httpx.Response(500, text="Authorization: top-secret private prompt")
        )

        if require_exact:
            with pytest.raises(RuntimeError) as error:
                asyncio.run(TokenCounter(settings, QwenClient(settings)).count_chat(chat_messages()))

            rendered_error = "".join(traceback.format_exception(error.value))
            assert "top-secret" not in rendered_error
            assert "private prompt" not in rendered_error
            assert "tokenizer" in str(error.value).lower()
        else:
            result = asyncio.run(TokenCounter(settings, QwenClient(settings)).count_chat(chat_messages()))
            assert result.mode == "ESTIMATED"
            assert result.tokenizer == "conservative-v1"

    assert route.called
