from __future__ import annotations

import asyncio
import hashlib
import inspect
import re
from dataclasses import dataclass, field, replace
from typing import Any, Iterable

from app.models.schemas import Message


class ContextBudgetExceeded(RuntimeError):
    """Raised when mandatory context cannot fit in the configured model window."""

    code = "CONTEXT_BUDGET_EXCEEDED"

    def __init__(self, reason: str):
        self.reason = reason
        super().__init__(f"{self.code}: {reason}")


@dataclass(frozen=True)
class EvidenceItem:
    content: str
    chunk_id: str | None = None
    document_id: str | None = None
    document_title: str | None = None
    page_number: int | None = None
    table_location: str | None = None
    relevance: float | None = None
    metadata: dict[str, Any] = field(default_factory=dict)
    truncated: bool = False

    @property
    def normalized_content(self) -> str:
        return " ".join(self.content.split()).casefold()

    def as_message(self) -> Message:
        metadata = []
        if self.document_title:
            metadata.append(f"标题: {self.document_title}")
        if self.page_number is not None:
            metadata.append(f"页码: {self.page_number}")
        if self.table_location:
            metadata.append(f"表格位置: {self.table_location}")
        if self.chunk_id:
            metadata.append(f"chunkId: {self.chunk_id}")
        prefix = "[知识库证据] " + " | ".join(metadata)
        return Message(role="system", content=f"{prefix}\n{self.content}")


@dataclass(frozen=True)
class ContextBudgetRequest:
    system_prompt: str
    current_question: str
    history_messages: list[Message] = field(default_factory=list)
    evidence_items: list[EvidenceItem] = field(default_factory=list)
    model_context_limit: int = 0
    requested_output_tokens: int = 0
    template_overhead_tokens: int = 0
    safety_reserve_tokens: int = 0
    history_budget_ratio: float = 0.30
    evidence_budget_ratio: float = 0.70
    history_candidate_limit: int = 100


@dataclass(frozen=True)
class ContextBudgetResult:
    context_messages: list[Message]
    evidence_items: list[EvidenceItem]
    model_parameters: dict[str, int]
    context_usage: dict[str, Any]

    @property
    def usage(self) -> dict[str, Any]:
        return {"contextUsage": self.context_usage}


class ContextBudgetPlanner:
    def __init__(self, token_counter):
        self.token_counter = token_counter

    def plan(self, request: ContextBudgetRequest) -> ContextBudgetResult:
        if request.model_context_limit <= 0:
            raise ContextBudgetExceeded("model context limit is invalid")
        if request.requested_output_tokens < 0 or request.template_overhead_tokens < 0 or request.safety_reserve_tokens < 0:
            raise ContextBudgetExceeded("reserved token values are invalid")

        system = Message(role="system", content=request.system_prompt)
        question = Message(role="user", content=request.current_question)
        system_tokens = self._count([system])
        question_tokens = self._count([question])
        fixed = system_tokens + question_tokens + request.template_overhead_tokens + request.requested_output_tokens + request.safety_reserve_tokens
        if fixed > request.model_context_limit:
            raise ContextBudgetExceeded("system or question mandatory context exceeds model window")

        optional = request.model_context_limit - fixed
        history_cap = int(optional * request.history_budget_ratio)
        evidence_cap = int(optional * request.evidence_budget_ratio)
        history = self._select_history(request.history_messages, history_cap, request.history_candidate_limit)
        history_tokens = self._count(history)
        evidence, evidence_tokens = self._select_evidence(request.evidence_items, evidence_cap)

        # Let unused capacity flow to the other optional section.
        unused_history = max(0, history_cap - history_tokens)
        evidence, evidence_tokens = self._select_evidence(request.evidence_items, evidence_cap + unused_history)
        unused_evidence = max(0, evidence_cap + unused_history - evidence_tokens)
        history = self._select_history(request.history_messages, history_cap + unused_evidence, request.history_candidate_limit)
        history_tokens = self._count(history)

        selected_messages = [system, *history, *(item.as_message() for item in evidence), question]
        while self._count(selected_messages) + request.template_overhead_tokens + request.requested_output_tokens + request.safety_reserve_tokens > request.model_context_limit:
            if evidence:
                evidence = evidence[:-1]
                selected_messages = [system, *history, *(item.as_message() for item in evidence), question]
                continue
            if history:
                history = self._drop_oldest_turn(history)
                selected_messages = [system, *history, question]
                continue
            raise ContextBudgetExceeded("assembled context exceeds model window")

        final_count = self._count(selected_messages)
        selected_history_messages = len(history)
        original_history = [message for message in request.history_messages if message.role in {"user", "assistant", "system"}]
        selected_evidence_ids = {id(item) for item in evidence}
        usage = {
            "modelContextLimit": request.model_context_limit,
            "estimatedInputTokens": final_count,
            "systemTokens": system_tokens,
            "questionTokens": question_tokens,
            "historyTokens": self._count(history),
            "evidenceTokens": self._count([item.as_message() for item in evidence]),
            "templateOverheadTokens": request.template_overhead_tokens,
            "outputReserveTokens": request.requested_output_tokens,
            "safetyReserveTokens": request.safety_reserve_tokens,
            "availableOptionalTokens": optional,
            "selectedHistoryMessages": selected_history_messages,
            "droppedHistoryMessages": max(0, len(original_history) - selected_history_messages),
            "selectedEvidenceItems": len(evidence),
            "droppedEvidenceItems": max(0, len(request.evidence_items) - len(selected_evidence_ids)),
            "truncatedEvidenceItems": sum(item.truncated for item in evidence),
            "countMode": self._count_result(selected_messages).mode,
            "tokenizer": self._count_result(selected_messages).tokenizer,
        }
        return ContextBudgetResult(selected_messages, evidence, {"max_tokens": request.requested_output_tokens}, usage)

    async def aplan(self, request: ContextBudgetRequest) -> ContextBudgetResult:
        if inspect.iscoroutinefunction(getattr(self.token_counter, "count_chat", None)):
            return await self._async_plan(request)
        return self.plan(request)

    def _count(self, messages: Iterable[Message]) -> int:
        result = self._count_result(list(messages))
        return result.tokens

    def _count_result(self, messages: list[Message]):
        result = self.token_counter.count_chat(messages)
        if inspect.isawaitable(result):
            raise TypeError("use aplan with an asynchronous token counter")
        return result

    async def _async_plan(self, request):
        original = self.token_counter
        class SyncAdapter:
            def count_chat(_, messages):
                return asyncio.run(original.count_chat(messages))
        planner = ContextBudgetPlanner(SyncAdapter())
        return await asyncio.to_thread(planner.plan, request)

    @staticmethod
    def _drop_oldest_turn(messages: list[Message]) -> list[Message]:
        user_index = next((index for index, message in enumerate(messages) if message.role == "user"), None)
        if user_index is None:
            return []
        assistant_index = next(
            (index for index in range(user_index + 1, len(messages)) if messages[index].role == "assistant"),
            None,
        )
        if assistant_index is None:
            return []
        return messages[assistant_index + 1 :]

    def _select_history(self, messages: list[Message], budget: int, limit: int) -> list[Message]:
        valid = messages[:limit]
        turns: list[list[Message]] = []
        pending_system: list[Message] = []
        current: list[Message] | None = None
        for message in valid:
            if message.role == "system":
                if current is None:
                    pending_system.append(message)
                else:
                    current.append(message)
            elif message.role == "user":
                if current and len(current) >= 2 and current[-1].role == "assistant":
                    turns.append(current)
                current = [*pending_system, message]
                pending_system = []
            elif (
                message.role == "assistant"
                and current
                and any(item.role == "user" for item in current)
                and not any(item.role == "assistant" for item in current)
            ):
                current.append(message)
        if current and len(current) >= 2 and current[-1].role == "assistant":
            turns.append(current)
        selected: list[list[Message]] = []
        used = 0
        for turn in reversed(turns):
            cost = self._count(turn)
            if used + cost > budget:
                continue
            selected.append(turn)
            used += cost
        return [message for turn in reversed(selected) for message in turn]

    def _select_evidence(self, items: list[EvidenceItem], budget: int) -> tuple[list[EvidenceItem], int]:
        selected: list[EvidenceItem] = []
        used = 0
        seen_ids: set[str] = set()
        seen_content: set[str] = set()
        for item in items:
            key = item.chunk_id or hashlib.sha256(item.normalized_content.encode("utf-8")).hexdigest()
            if key in seen_ids or item.normalized_content in seen_content:
                continue
            seen_ids.add(key)
            seen_content.add(item.normalized_content)
            full_cost = self._count([item.as_message()])
            if used + full_cost <= budget:
                selected.append(item)
                used += full_cost
                continue
            remaining = budget - used
            truncated = self._truncate(item, remaining)
            if truncated is not None:
                selected.append(truncated)
                used += self._count([truncated.as_message()])
            break
        return selected, used

    def _truncate(self, item: EvidenceItem, budget: int) -> EvidenceItem | None:
        if budget <= 0:
            return None
        words = re.split(r"(?<=[???.!?])\s+|\n+", item.content)
        kept: list[str] = []
        for part in words:
            candidate = " ".join([*kept, part]).strip()
            trial = replace(item, content=candidate, truncated=True)
            if self._count([trial.as_message()]) <= budget:
                kept.append(part)
            else:
                break
        content = " ".join(kept).strip()
        if len(content) < 12:
            return None
        return replace(item, content=content, truncated=True)
