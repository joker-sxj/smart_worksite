from __future__ import annotations

import asyncio
import warnings

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


def test_history_system_messages_are_dropped_but_complete_turn_is_retained():
    history = [
        msg("system", "history policy"),
        msg("user", "question"), msg("assistant", "answer"),
    ]
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(
        budget_request(history_messages=history, model_context_limit=512)
    )

    contents = [item.content for item in result.context_messages]
    assert "history policy" not in contents
    assert contents.index("question") < contents.index("answer")


def test_history_candidate_limit_uses_the_latest_messages():
    history = [msg("user", f"u-{index}") if index % 2 == 0 else msg("assistant", f"a-{index}") for index in range(102)]
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(
        budget_request(history_messages=history, history_candidate_limit=100, model_context_limit=2048)
    )

    contents = [item.content for item in result.context_messages]
    assert "u-0" not in contents
    assert "a-1" not in contents
    assert "u-100" in contents
    assert "a-101" in contents


def test_history_candidate_slice_discards_partial_old_turn_but_keeps_complete_latest_turn():
    history = [
        msg("user", "old-user"), msg("assistant", "old-answer"),
        msg("user", "middle-user"), msg("assistant", "middle-answer"),
        msg("user", "latest-user"), msg("assistant", "latest-answer"),
    ]
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(
        budget_request(history_messages=history, history_candidate_limit=3, model_context_limit=512)
    )

    contents = [item.content for item in result.context_messages]
    assert "middle-answer" not in contents
    assert contents[-3:-1] == ["latest-user", "latest-answer"]
    assert result.context_usage["selectedHistoryTurns"] == 1


def test_history_selection_is_newest_first_and_contiguous_when_newest_turn_does_not_fit():
    history = [
        msg("user", "old-user"), msg("assistant", "old-answer"),
        msg("user", "new-user"), msg("assistant", "new-answer"),
    ]
    counter = FixedTokenCounter({"old-user": 5, "old-answer": 5, "new-user": 80, "new-answer": 80})
    base = budget_request(history_messages=history, history_budget_ratio=1.0, evidence_budget_ratio=0.0)
    probe = ContextBudgetPlanner(counter).plan(base)
    fixed = probe.context_usage["systemTokens"] + probe.context_usage["questionTokens"] + base.template_overhead_tokens + base.requested_output_tokens + base.safety_reserve_tokens
    old_turn_cost = counter.count_chat(history[:2]).tokens

    result = ContextBudgetPlanner(counter).plan(replace(base, model_context_limit=fixed + old_turn_cost))

    contents = [item.content for item in result.context_messages]
    assert "new-user" not in contents
    assert "new-answer" not in contents
    assert "old-user" not in contents
    assert "old-answer" not in contents
    assert result.context_usage["selectedHistoryTurns"] == 0
    assert result.context_usage["droppedHistoryTurns"] == 2
    assert result.context_usage["selectedHistoryMessages"] == 0
    assert result.context_usage["droppedHistoryMessages"] == 4


def test_history_usage_reports_complete_turns_after_candidate_limit_and_invalid_entries():
    history = [
        msg("assistant", "orphan"),
        msg("system", "policy"), msg("user", "first-user"), msg("assistant", "first-answer"),
        msg("user", "unfinished"), msg("tool", "invalid"),
        msg("user", "second-user"), msg("assistant", "second-answer"),
    ]
    counter = FixedTokenCounter({"first-user": 5, "first-answer": 5, "second-user": 5, "second-answer": 5})
    base = budget_request(
        history_messages=history,
        history_candidate_limit=6,
        history_budget_ratio=1.0,
        evidence_budget_ratio=0.0,
    )
    result = ContextBudgetPlanner(counter).plan(base)

    usage = result.context_usage
    assert usage["selectedHistoryTurns"] == 2
    assert usage["droppedHistoryTurns"] == 0
    assert usage["selectedHistoryMessages"] == 4
    assert usage["droppedHistoryMessages"] == 0

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


def test_history_system_message_between_user_and_assistant_is_dropped_without_breaking_turn():
    history = [
        msg("user", "turn-user"),
        msg("system", "turn-policy"),
        msg("assistant", "turn-answer"),
    ]
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(
        budget_request(history_messages=history, model_context_limit=512)
    )

    contents = [item.content for item in result.context_messages]
    assert contents[1:3] == ["turn-user", "turn-answer"]
    assert "turn-policy" not in contents


class QwenChatTemplateCounter(FixedTokenCounter):
    """Mirror the role-order constraints enforced by the deployed Qwen template."""

    def count_chat(self, messages):
        roles = [message.role for message in messages]
        if "system" in roles[1:]:
            raise ValueError("System message must be at the beginning")
        non_system = roles[1:] if roles and roles[0] == "system" else roles
        if not non_system or non_system[0] != "user":
            raise ValueError("No user query found in messages")
        if any(role not in {"user", "assistant"} for role in non_system):
            raise ValueError("Unsupported role")
        return super().count_chat(messages)


def test_planner_emits_qwen_compatible_roles_with_evidence_in_current_user_message():
    history = [msg("user", "earlier question"), msg("assistant", "earlier answer")]
    evidence = EvidenceItem(
        "昼间不得超过70 dB(A)，夜间不得超过55 dB(A)。",
        source_id="noise-standard",
        chunk_id="gb12523-4.1",
        document_title="GB 12523-2025",
        page_number=4,
    )

    result = ContextBudgetPlanner(QwenChatTemplateCounter()).plan(
        budget_request(history_messages=history, evidence_items=[evidence])
    )

    assert [message.role for message in result.context_messages] == [
        "system", "user", "assistant", "user"
    ]
    current = result.context_messages[-1].content
    assert "[知识库证据]" in current
    assert "sourceId: noise-standard" in current
    assert "chunkId: gb12523-4.1" in current
    assert "[用户问题]\ncurrent question" in current


def test_planner_drops_historical_system_messages_instead_of_emitting_mid_conversation_system():
    history = [
        msg("system", "stale historical instruction"),
        msg("user", "earlier question"),
        msg("assistant", "earlier answer"),
    ]

    result = ContextBudgetPlanner(QwenChatTemplateCounter()).plan(
        budget_request(history_messages=history)
    )

    assert [message.role for message in result.context_messages] == [
        "system", "user", "assistant", "user"
    ]
    assert all("stale historical instruction" not in message.content for message in result.context_messages)


def test_planner_never_sends_empty_message_lists_to_strict_tokenizer():
    result = ContextBudgetPlanner(QwenChatTemplateCounter()).plan(budget_request())

    assert [message.role for message in result.context_messages] == ["system", "user"]

def test_system_between_complete_turns_is_dropped_and_both_turns_remain_complete():
    history = [
        msg("user", "first-user"), msg("assistant", "first-answer"),
        msg("system", "next-policy"),
        msg("user", "second-user"), msg("assistant", "second-answer"),
    ]
    result = ContextBudgetPlanner(FixedTokenCounter()).plan(
        budget_request(history_messages=history, model_context_limit=512)
    )

    contents = [item.content for item in result.context_messages]
    assert contents[1:5] == ["first-user", "first-answer", "second-user", "second-answer"]
    assert "next-policy" not in contents
    assert result.context_usage["selectedHistoryTurns"] == 2


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
    assert result.context_usage["selectedEvidenceSourceIds"] == [selected.source_id]
    assert result.context_usage["selectedEvidenceChunkIds"] == ["table-1"]


def test_evidence_skips_unreadably_short_remainder_instead_of_filling_budget_with_fragment():
    item = EvidenceItem("A long source sentence with enough context to be useful.", chunk_id="long")
    result = ContextBudgetPlanner(FixedTokenCounter({item.content: 100})).plan(
        budget_request(evidence_items=[item], model_context_limit=220, evidence_budget_ratio=1.0, history_budget_ratio=0.0)
    )

    assert result.evidence_items == []
    assert result.context_usage["droppedEvidenceItems"] == 1


def test_evidence_skips_unreadable_truncation_and_tries_later_shorter_item():
    too_long = EvidenceItem("A single oversized source sentence that cannot be kept as a readable fragment.", chunk_id="long", relevance=0.9)
    shorter = EvidenceItem("Short useful evidence.", chunk_id="short", relevance=0.8)
    planner = ContextBudgetPlanner(FixedTokenCounter({too_long.content: 100}))
    request = budget_request(evidence_items=[too_long, shorter], evidence_budget_ratio=1.0, history_budget_ratio=0.0)
    fixed = (
        planner._count([msg("system", request.system_prompt)])
        + planner._count([msg("user", request.current_question)])
        + request.template_overhead_tokens + request.requested_output_tokens + request.safety_reserve_tokens
    )
    shorter_cost = planner._count([shorter.as_message()])

    result = planner.plan(replace(request, model_context_limit=fixed + shorter_cost + 2))

    # Evidence arrives in upstream relevance order; the planner preserves that order while selecting items that fit.
    assert [item.chunk_id for item in result.evidence_items] == ["short"]
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
        optional_count = sum(message.content.count("chunkId:") for message in messages)
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
        drift = 200 if {"old-user", "current question"}.issubset(contents) else 0
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


def test_final_exact_recheck_removes_a_whole_turn_with_system_orphans_and_incomplete_user():
    class Counter(FixedTokenCounter):
        def count_chat(self, messages):
            base = super().count_chat(messages).tokens
            contents = {message.content for message in messages}
            drift = 200 if {"old-user", "current question"}.issubset(contents) else 0
            return type("Count", (), {"tokens": base + drift, "mode": "EXACT", "tokenizer": "local-test"})()

    history = [
        msg("assistant", "orphan-before"),
        msg("system", "old-policy"), msg("user", "old-user"), msg("assistant", "old-answer"),
        msg("user", "new-user"), msg("assistant", "new-answer"),
        msg("user", "incomplete"),
    ]
    result = ContextBudgetPlanner(Counter()).plan(
        budget_request(history_messages=history, model_context_limit=400, history_budget_ratio=1.0, evidence_budget_ratio=0.0)
    )

    contents = [message.content for message in result.context_messages]
    assert all(value not in contents for value in ["orphan-before", "old-policy", "old-user", "old-answer", "incomplete"])
    assert contents[-3:-1] == ["new-user", "new-answer"]


def test_chinese_sentence_boundary_without_spaces_keeps_the_first_sentence():
    planner = ContextBudgetPlanner(FixedTokenCounter())
    first = EvidenceItem("第一条安全要求。", chunk_id="zh")
    item = EvidenceItem("第一条安全要求。第二条安全要求。", chunk_id="zh")
    request = budget_request(evidence_items=[item], evidence_budget_ratio=1.0, history_budget_ratio=0.0)
    fixed = (
        planner._count([msg("system", request.system_prompt)])
        + planner._count([msg("user", request.current_question)])
        + request.template_overhead_tokens + request.requested_output_tokens + request.safety_reserve_tokens
    )
    first_cost = planner._count([first.as_message()])
    result = planner.plan(replace(request, model_context_limit=fixed + first_cost + 2))

    assert result.evidence_items[0].content == first.content
    assert result.evidence_items[0].truncated is True


def test_aplan_awaits_async_counter_on_the_callers_running_event_loop():
    expected_loop = None

    class AsyncCounter(FixedTokenCounter):
        async def count_chat(self, messages):
            assert asyncio.get_running_loop() is expected_loop
            return super().count_chat(messages)

    async def run():
        nonlocal expected_loop
        expected_loop = asyncio.get_running_loop()
        return await ContextBudgetPlanner(AsyncCounter()).aplan(budget_request())

    result = asyncio.run(run())
    assert result.context_usage["modelContextLimit"] == 2048


def test_english_sentence_boundaries_preserve_abbreviations_files_and_urls():
    text = (
        "Use e.g. barriers, i.e. acoustic screens; see No. 5, Fig. 1, "
        "report.v2.pdf, or https://example.com/report.v2.pdf. Second complete sentence."
    )

    assert ContextBudgetPlanner(FixedTokenCounter())._natural_segments(text) == [
        (
            "Use e.g. barriers, i.e. acoustic screens; see No. 5, Fig. 1, "
            "report.v2.pdf, or https://example.com/report.v2.pdf. "
        ),
        "Second complete sentence.",
    ]


def test_plain_english_sentences_still_split_at_clear_boundaries():
    assert ContextBudgetPlanner(FixedTokenCounter())._natural_segments(
        "First complete sentence. Second complete sentence."
    ) == ["First complete sentence. ", "Second complete sentence."]

def test_decimal_and_unit_period_is_not_treated_as_a_sentence_boundary():
    planner = ContextBudgetPlanner(FixedTokenCounter())
    assert planner._natural_segments("执行3.1.2条，噪声限值为70.5dB。夜间应进一步降噪。") == [
        "执行3.1.2条，噪声限值为70.5dB。",
        "夜间应进一步降噪。",
    ]
    first = EvidenceItem("噪声限值为70.5dB。", chunk_id="decimal")
    item = EvidenceItem("噪声限值为70.5dB。夜间应进一步降噪。", chunk_id="decimal")
    request = budget_request(evidence_items=[item], evidence_budget_ratio=1.0, history_budget_ratio=0.0)
    fixed = (
        planner._count([msg("system", request.system_prompt)])
        + planner._count([msg("user", request.current_question)])
        + request.template_overhead_tokens + request.requested_output_tokens + request.safety_reserve_tokens
    )
    first_cost = planner._count([first.as_message()])

    result = planner.plan(replace(request, model_context_limit=fixed + first_cost + 2))

    assert result.evidence_items[0].content == first.content


@pytest.mark.parametrize(
    ("overrides", "parameter_name"),
    [
        ({"history_budget_ratio": -0.1}, "history_budget_ratio"),
        ({"history_budget_ratio": 1.1}, "history_budget_ratio"),
        ({"evidence_budget_ratio": -0.1}, "evidence_budget_ratio"),
        ({"evidence_budget_ratio": 1.1}, "evidence_budget_ratio"),
        ({"history_budget_ratio": 0.6, "evidence_budget_ratio": 0.5}, "history_budget_ratio/evidence_budget_ratio"),
        ({"history_candidate_limit": 0}, "history_candidate_limit"),
    ],
)
def test_planner_validates_budget_ratios_and_history_limit_without_leaking_content(overrides, parameter_name):
    secret = "SECRET_CONTEXT_BODY"
    request = budget_request(
        system_prompt=secret,
        current_question=secret,
        evidence_items=[EvidenceItem(secret)],
        **overrides,
    )

    with pytest.raises(ContextBudgetExceeded) as exc_info:
        ContextBudgetPlanner(FixedTokenCounter()).plan(request)

    assert parameter_name in str(exc_info.value)
    assert secret not in str(exc_info.value)


def test_plan_cleanly_rejects_a_counter_returning_an_awaitable_without_runtime_warning():
    class AwaitableCounter:
        def __init__(self):
            self.awaitable = None

        def count_chat(self, messages):
            async def count():
                return FixedTokenCounter().count_chat(messages)

            self.awaitable = count()
            return self.awaitable

    counter = AwaitableCounter()
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        with pytest.raises(TypeError, match="aplan"):
            ContextBudgetPlanner(counter).plan(budget_request())

    assert counter.awaitable.cr_frame is None
    assert not any("was never awaited" in str(item.message) for item in caught)


def _count_key(messages):
    return tuple((message.role, message.content) for message in messages)


def _cache_request():
    history = [
        message
        for index in range(3)
        for message in (msg("user", f"history-user-{index}"), msg("assistant", f"history-answer-{index}"))
    ]
    evidence = [
        EvidenceItem(f"Evidence sentence {index} with enough production detail.", chunk_id=f"e-{index}")
        for index in range(4)
    ]
    return budget_request(
        history_messages=history,
        evidence_items=evidence,
        model_context_limit=4096,
    )


def test_plan_caches_identical_message_counts_within_one_request():
    class CountingCounter(FixedTokenCounter):
        def __init__(self):
            super().__init__()
            self.calls = {}

        def count_chat(self, messages):
            messages = list(messages)
            key = _count_key(messages)
            self.calls[key] = self.calls.get(key, 0) + 1
            return super().count_chat(messages)

    counter = CountingCounter()
    ContextBudgetPlanner(counter).plan(_cache_request())

    assert max(counter.calls.values()) == 1
    assert sum(counter.calls.values()) <= 14


def test_aplan_shares_the_same_request_level_count_cache_semantics():
    class AsyncCountingCounter(FixedTokenCounter):
        def __init__(self):
            super().__init__()
            self.calls = {}

        async def count_chat(self, messages):
            messages = list(messages)
            key = _count_key(messages)
            self.calls[key] = self.calls.get(key, 0) + 1
            return FixedTokenCounter.count_chat(self, messages)

    counter = AsyncCountingCounter()
    asyncio.run(ContextBudgetPlanner(counter).aplan(_cache_request()))

    assert max(counter.calls.values()) == 1
    assert sum(counter.calls.values()) <= 14
