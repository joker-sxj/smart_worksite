from __future__ import annotations

import asyncio

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


def test_context_budget_settings_normalizes_blank_tokenizer_path():
    settings = local_settings(context_tokenizer_path="  \t  ")

    assert settings.context_tokenizer_path == ""


def test_context_budget_settings_trims_existing_local_tokenizer_path(tmp_path):
    tokenizer_dir = tmp_path / "tokenizer"
    tokenizer_dir.mkdir()

    settings = local_settings(context_tokenizer_path=f"  {tokenizer_dir}  ")

    assert settings.context_tokenizer_path == str(tokenizer_dir)

def test_context_budget_settings_accept_existing_local_tokenizer_path(tmp_path):
    tokenizer_dir = tmp_path / "tokenizer"
    tokenizer_dir.mkdir()

    settings = local_settings(context_tokenizer_path=str(tokenizer_dir))

    assert settings.context_tokenizer_path == str(tokenizer_dir)


def test_context_budget_settings_accept_existing_local_tokenizer_file(tmp_path):
    tokenizer_file = tmp_path / "tokenizer.json"
    tokenizer_file.write_text("{}", encoding="utf-8")

    settings = local_settings(context_tokenizer_path=str(tokenizer_file))

    assert settings.context_tokenizer_path == str(tokenizer_file)


def test_context_budget_settings_reject_nonexistent_repo_id_in_local_only():
    with pytest.raises(ValidationError, match="CONTEXT_TOKENIZER_PATH"):
        local_settings(context_tokenizer_path="Qwen/Qwen3-32B")


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

# Task 3 mandatory planner tests
from dataclasses import replace

from app.models.schemas import Message
from app.services.context_budget import (
    ContextBudgetExceeded,
    ContextBudgetPlanner,
    ContextBudgetRequest,
    EvidenceItem,
)


class FixedTokenCounter:
    def __init__(self, text_tokens: dict[str, int] | None = None, mode: str = "ESTIMATED"):
        self.text_tokens = text_tokens or {}
        self.mode = mode

    def count_chat(self, messages):
        tokens = 2 + sum(4 + len(message.role) + self.text_tokens.get(message.content, len(message.content)) for message in messages)
        return type("Count", (), {"tokens": tokens, "mode": self.mode, "tokenizer": "test-counter"})()


def budget_request(**overrides):
    values = {
        "system_prompt": "system rules",
        "current_question": "current question",
        "history_messages": [],
        "evidence_items": [],
        "model_context_limit": 2048,
        "requested_output_tokens": 128,
        "template_overhead_tokens": 16,
        "safety_reserve_tokens": 16,
        "history_budget_ratio": 0.30,
        "evidence_budget_ratio": 0.70,
    }
    values.update(overrides)
    return ContextBudgetRequest(**values)


def test_mandatory_content_and_reserves_are_reported_and_optional_budget_is_remaining():
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(budget_request())

    usage = result.context_usage
    assert usage["modelContextLimit"] == 2048
    assert usage["systemTokens"] > 0
    assert usage["questionTokens"] > 0
    assert usage["templateOverheadTokens"] == 16
    assert usage["outputReserveTokens"] == 128
    assert usage["safetyReserveTokens"] == 16
    assert usage["availableOptionalTokens"] == 2048 - usage["systemTokens"] - usage["questionTokens"] - 16 - 128 - 16
    assert result.model_parameters == {"max_tokens": 128}
    assert usage["countMode"] == "ESTIMATED"


def test_oversized_mandatory_content_raises_sanitized_error_without_input_text():
    secret = "DO_NOT_LEAK_THIS_PROMPT"
    request = budget_request(system_prompt=secret, model_context_limit=64, requested_output_tokens=16)

    with pytest.raises(ContextBudgetExceeded) as exc_info:
        ContextBudgetPlanner(FixedTokenCounter()).plan(request)

    assert exc_info.value.code == "CONTEXT_BUDGET_EXCEEDED"
    assert secret not in str(exc_info.value)
    assert "system" in str(exc_info.value).lower()


# Task 3 history tests

def msg(role: str, content: str) -> Message:
    return Message(role=role, content=content)


def test_history_keeps_newest_complete_turns_and_restores_chronological_order():
    history = [
        msg("user", "old-user"), msg("assistant", "old-answer"),
        msg("user", "new-user"), msg("assistant", "new-answer"),
    ]
    counter = FixedTokenCounter({"old-user": 30, "old-answer": 30, "new-user": 5, "new-answer": 5})
    result = ContextBudgetPlanner(counter).plan(budget_request(history_messages=history, model_context_limit=256))

    contents = [item.content for item in result.context_messages]
    assert contents[-3:-1] == ["new-user", "new-answer"]
    assert "old-user" not in contents
    assert result.context_usage["selectedHistoryMessages"] == 2


def test_history_discards_orphan_assistant_incomplete_user_and_invalid_roles():
    history = [
        msg("assistant", "orphan"),
        msg("user", "complete"), msg("assistant", "answer"),
        msg("user", "unfinished"),
        msg("tool", "invalid"),
    ]
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(
        budget_request(history_messages=history, model_context_limit=512)
    )

    contents = [item.content for item in result.context_messages]
    assert "orphan" not in contents
    assert "unfinished" not in contents
    assert "invalid" not in contents
    assert contents.count("complete") == 1
    assert contents.count("answer") == 1


def test_history_system_messages_are_retained_with_their_complete_turn():
    history = [
        msg("system", "history policy"),
        msg("user", "question"), msg("assistant", "answer"),
    ]
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(
        budget_request(history_messages=history, model_context_limit=512)
    )

    contents = [item.content for item in result.context_messages]
    assert "history policy" in contents
    assert contents.index("history policy") < contents.index("question") < contents.index("answer")


def test_history_candidate_limit_only_considers_first_one_hundred_messages():
    history = [msg("user", f"u-{index}") if index % 2 == 0 else msg("assistant", f"a-{index}") for index in range(102)]
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(
        budget_request(history_messages=history, history_candidate_limit=100, model_context_limit=2048)
    )

    contents = [item.content for item in result.context_messages]
    assert "u-100" not in contents
    assert "a-101" not in contents


def test_history_exact_boundary_fit_keeps_the_complete_turn():
    history = [msg("user", "fit-user"), msg("assistant", "fit-answer")]
    counter = FixedTokenCounter({"fit-user": 10, "fit-answer": 10})
    base = budget_request(history_messages=history, model_context_limit=256)
    probe = ContextBudgetPlanner(counter).plan(base)
    history_cost = counter.count_chat(history).tokens
    fixed = probe.context_usage["systemTokens"] + probe.context_usage["questionTokens"] + base.template_overhead_tokens + base.requested_output_tokens + base.safety_reserve_tokens
    exact = replace(base, model_context_limit=fixed + history_cost)

    result = ContextBudgetPlanner(counter).plan(exact)
    assert "fit-user" in [item.content for item in result.context_messages]
    assert result.context_usage["estimatedInputTokens"] + exact.template_overhead_tokens + exact.requested_output_tokens + exact.safety_reserve_tokens <= exact.model_context_limit


def test_history_system_message_between_user_and_assistant_does_not_break_complete_turn():
    history = [
        msg("user", "turn-user"),
        msg("system", "turn-policy"),
        msg("assistant", "turn-answer"),
    ]
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(
        budget_request(history_messages=history, model_context_limit=512)
    )

    contents = [item.content for item in result.context_messages]
    assert contents[1:4] == ["turn-user", "turn-policy", "turn-answer"]

# Task 3 evidence and final validation tests

def test_evidence_deduplicates_chunk_ids_and_normalized_content_while_preserving_rank_order():
    items = [
        EvidenceItem("High ranked passage.", chunk_id="high", relevance=0.9),
        EvidenceItem("Same text", chunk_id="one", relevance=0.8),
        EvidenceItem(" same   text ", chunk_id="two", relevance=0.7),
        EvidenceItem("Low ranked passage.", chunk_id="low", relevance=0.1),
    ]
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(
        budget_request(evidence_items=items, model_context_limit=512)
    )

    assert [item.chunk_id for item in result.evidence_items] == ["high", "one", "low"]
    assert result.context_usage["droppedEvidenceItems"] == 1


def test_evidence_keeps_source_and_table_metadata_and_marks_natural_boundary_truncation():
    item = EvidenceItem(
        "First useful sentence. Second useful sentence. Third sentence that will not fit.",
        chunk_id="table-1", document_id="doc-1", document_title="Safety standard",
        page_number=12, table_location="Table 3, row 2", relevance=0.99,
    )
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(
        budget_request(evidence_items=[item], model_context_limit=325, evidence_budget_ratio=1.0, history_budget_ratio=0.0)
    )

    selected = result.evidence_items[0]
    assert selected.chunk_id == "table-1"
    assert selected.document_title == "Safety standard"
    assert selected.page_number == 12
    assert selected.table_location == "Table 3, row 2"
    assert selected.truncated is True
    assert selected.content.endswith("sentence.")
    assert "Third sentence" not in selected.content
    assert result.context_usage["truncatedEvidenceItems"] == 1


def test_evidence_skips_unreadably_short_remainder_instead_of_filling_budget_with_fragment():
    item = EvidenceItem("A long source sentence with enough context to be useful.", chunk_id="long")
    result = ContextBudgetPlanner(FixedTokenCounter({item.content: 100})).plan(
        budget_request(evidence_items=[item], model_context_limit=220, evidence_budget_ratio=1.0, history_budget_ratio=0.0)
    )

    assert result.evidence_items == []
    assert result.context_usage["droppedEvidenceItems"] == 1


def test_unused_history_capacity_flows_to_evidence_after_first_allocation():
    item = EvidenceItem("Evidence with enough useful detail.", chunk_id="evidence")
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(
        budget_request(
            history_messages=[], evidence_items=[item], model_context_limit=512,
            history_budget_ratio=0.10, evidence_budget_ratio=0.70,
        )
    )

    assert result.evidence_items == [item]


class ExactOverflowCounter(FixedTokenCounter):
    def __init__(self):
        super().__init__(mode="EXACT")

    def count_chat(self, messages):
        base = super().count_chat(messages).tokens
        optional_count = sum(message.content.startswith("[") and "chunkId:" in message.content for message in messages)
        drift = 100 if optional_count > 1 else 0
        return type("Count", (), {"tokens": base + drift, "mode": "EXACT", "tokenizer": "local-test"})()


def test_final_exact_recheck_drops_lowest_priority_optional_evidence_until_it_fits():
    items = [
        EvidenceItem("Top evidence with enough detail.", chunk_id="top", relevance=0.9),
        EvidenceItem("Lower priority evidence with enough detail.", chunk_id="low", relevance=0.1),
    ]
    result = ContextBudgetPlanner(ExactOverflowCounter()).plan(
        budget_request(evidence_items=items, model_context_limit=300, evidence_budget_ratio=1.0, history_budget_ratio=0.0)
    )

    assert [item.chunk_id for item in result.evidence_items] == ["top"]
    assert result.context_usage["countMode"] == "EXACT"
    assert result.context_usage["tokenizer"] == "local-test"



@pytest.mark.parametrize("model_context_limit", [2048, 8192, 16384, 24576, 32768])
def test_context_budget_boundary_matrix_uses_small_fixture_and_respects_window(model_context_limit: int):
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(
        budget_request(model_context_limit=model_context_limit)
    )

    usage = result.context_usage
    assert usage["modelContextLimit"] == model_context_limit
    assert usage["estimatedInputTokens"] + usage["templateOverheadTokens"] + usage["outputReserveTokens"] + usage["safetyReserveTokens"] <= model_context_limit


class FinalHistoryOverflowCounter(FixedTokenCounter):
    def count_chat(self, messages):
        base = super().count_chat(messages).tokens
        contents = {message.content for message in messages}
        drift = 100 if {"old-user", "current question"}.issubset(contents) else 0
        return type("Count", (), {"tokens": base + drift, "mode": "EXACT", "tokenizer": "local-test"})()


def test_final_exact_recheck_drops_oldest_complete_history_turn():
    history = [
        msg("system", "old-policy"), msg("user", "old-user"), msg("assistant", "old-answer"),
        msg("user", "new-user"), msg("assistant", "new-answer"),
    ]
    result = ContextBudgetPlanner(FinalHistoryOverflowCounter()).plan(
        budget_request(history_messages=history, model_context_limit=400, history_budget_ratio=1.0, evidence_budget_ratio=0.0)
    )

    contents = [message.content for message in result.context_messages]
    assert all(value not in contents for value in ["old-policy", "old-user", "old-answer"])
    assert contents[-3:-1] == ["new-user", "new-answer"]


def test_aplan_supports_the_async_token_counter_contract():
    class AsyncCounter(FixedTokenCounter):
        async def count_chat(self, messages):
            return super().count_chat(messages)

    result = asyncio.run(ContextBudgetPlanner(AsyncCounter()).aplan(budget_request()))
    assert result.context_usage["modelContextLimit"] == 2048
