from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.core.settings import Settings


def local_settings(**overrides) -> Settings:
    values = {
        "ai_deployment_mode": "LOCAL_ONLY",
        "chat_max_model_len": 16384,
        "qwen_base_url": "http://local-llm:8000/v1",
        "qwen_vl_endpoint": "http://local-llm:8000/v1/chat/completions",
        "qwen_embedding_base_url": "http://local-embedding:8000/v1",
        "qwen_rerank_base_url": "http://local-reranker:8000/v1/rerank",
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


def test_context_budget_settings_expose_contract_defaults():
    settings = local_settings()

    assert settings.context_output_reserve_tokens == 0
    assert settings.context_safety_reserve_tokens == 0
    assert settings.context_template_overhead_tokens == 256
    assert settings.context_history_budget_ratio == pytest.approx(0.30)
    assert settings.context_evidence_budget_ratio == pytest.approx(0.70)
    assert settings.context_tokenizer_endpoint_enabled is True
    assert settings.context_tokenizer_path == ""
    assert settings.context_require_exact_tokenizer is False
    assert settings.context_history_candidate_limit == 100


@pytest.mark.parametrize(
    ("model_window", "output_reserve", "safety_reserve", "fixed_reserve"),
    [
        (16384, 4096, 512, 4864),
        (32768, 4096, 1024, 5376),
    ],
)
def test_context_budget_settings_derive_reserves_from_model_window(
    model_window: int,
    output_reserve: int,
    safety_reserve: int,
    fixed_reserve: int,
):
    settings = local_settings(chat_max_model_len=model_window)

    assert settings.resolved_context_output_reserve_tokens() == output_reserve
    assert settings.resolved_context_safety_reserve_tokens() == safety_reserve
    assert settings.resolved_context_fixed_reserve_tokens() == fixed_reserve


def test_context_budget_settings_explicit_reserves_override_derived_defaults():
    settings = local_settings(
        chat_max_model_len=16384,
        context_output_reserve_tokens=3072,
        context_safety_reserve_tokens=768,
        context_template_overhead_tokens=384,
    )

    assert settings.resolved_context_output_reserve_tokens() == 3072
    assert settings.resolved_context_safety_reserve_tokens() == 768
    assert settings.resolved_context_fixed_reserve_tokens() == 4224


@pytest.mark.parametrize("model_window", [0, -1])
def test_context_budget_settings_require_positive_local_model_window(model_window: int):
    with pytest.raises(ValidationError, match="CHAT_MAX_MODEL_LEN"):
        local_settings(chat_max_model_len=model_window)


@pytest.mark.parametrize(
    ("history_ratio", "evidence_ratio"),
    [
        (0.0, 0.70),
        (-0.1, 0.70),
        (1.01, 0.0),
        (0.30, 0.0),
        (0.30, -0.1),
        (0.0, 1.01),
        (0.60, 0.50),
    ],
)
def test_context_budget_settings_reject_invalid_ratios(
    history_ratio: float,
    evidence_ratio: float,
):
    with pytest.raises(ValidationError):
        local_settings(
            context_history_budget_ratio=history_ratio,
            context_evidence_budget_ratio=evidence_ratio,
        )


def test_context_budget_settings_allow_unallocated_ratio_capacity():
    settings = local_settings(
        context_history_budget_ratio=0.20,
        context_evidence_budget_ratio=0.60,
    )

    assert settings.context_history_budget_ratio == pytest.approx(0.20)
    assert settings.context_evidence_budget_ratio == pytest.approx(0.60)


@pytest.mark.parametrize(
    "overrides",
    [
        {"context_output_reserve_tokens": -1},
        {"context_safety_reserve_tokens": -1},
        {"context_template_overhead_tokens": -1},
        {"context_history_candidate_limit": 0},
    ],
)
def test_context_budget_settings_reject_invalid_non_ratio_values(overrides: dict[str, int]):
    with pytest.raises(ValidationError):
        local_settings(**overrides)


def test_context_budget_settings_reject_fixed_reserve_that_consumes_model_window():
    with pytest.raises(ValidationError, match="fixed context reserves"):
        local_settings(
            chat_max_model_len=4096,
            context_output_reserve_tokens=3072,
            context_safety_reserve_tokens=768,
            context_template_overhead_tokens=256,
        )


def test_context_budget_settings_accept_local_tokenizer_path():
    settings = local_settings(context_tokenizer_path="/models/smart-worksite-chat")

    assert settings.context_tokenizer_path == "/models/smart-worksite-chat"


@pytest.mark.parametrize(
    "remote_path",
    [
        "https://huggingface.co/example/model",
        "s3://private-bucket/tokenizer",
    ],
)
def test_context_budget_settings_reject_remote_tokenizer_path_in_local_only(remote_path: str):
    with pytest.raises(ValidationError, match="CONTEXT_TOKENIZER_PATH"):
        local_settings(context_tokenizer_path=remote_path)
