from __future__ import annotations

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
    source_id: str | None = None
    chunk_id: str | None = None
    document_id: str | None = None
    document_title: str | None = None
    page_number: int | None = None
    slide_number: int | None = None
    table_location: str | None = None
    relevance: float | None = None
    metadata: dict[str, Any] = field(default_factory=dict)
    truncated: bool = False

    @property
    def normalized_content(self) -> str:
        return " ".join(self.content.split()).casefold()

    def as_message(self) -> Message:
        return Message(role="user", content=self.as_block())

    def as_block(self) -> str:
        metadata = []
        if self.document_title:
            metadata.append(f"标题: {self.document_title}")
        if self.source_id:
            metadata.append(f"sourceId: {self.source_id}")
        if self.page_number is not None:
            metadata.append(f"页码: {self.page_number}")
        if self.slide_number is not None:
            metadata.append(f"幻灯片: {self.slide_number}")
        if self.table_location:
            metadata.append(f"表格位置: {self.table_location}")
        if self.chunk_id:
            metadata.append(f"chunkId: {self.chunk_id}")
        prefix = "[知识库证据] " + " | ".join(metadata)
        return f"{prefix}\n{self.content}"


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
        self._validate_request(request)
        if inspect.iscoroutinefunction(getattr(self.token_counter, "count_chat", None)):
            raise TypeError("use aplan with an asynchronous token counter")
        return self._drive_sync(self._plan_steps(request), {})

    async def aplan(self, request: ContextBudgetRequest) -> ContextBudgetResult:
        self._validate_request(request)
        return await self._drive_async(self._plan_steps(request), {})

    @staticmethod
    def _message_key(messages: Iterable[Message]) -> tuple[tuple[str, str], ...]:
        return tuple((message.role, message.content) for message in messages)

    def _drive_sync(self, operation, cache: dict | None = None):
        cache = {} if cache is None else cache
        try:
            messages = next(operation)
            while True:
                key = self._message_key(messages)
                result = cache.get(key)
                if result is None:
                    result = self._count_result(messages)
                    cache[key] = result
                messages = operation.send(result)
        except StopIteration as completed:
            return completed.value

    async def _drive_async(self, operation, cache: dict):
        try:
            messages = next(operation)
            while True:
                key = self._message_key(messages)
                result = cache.get(key)
                if result is None:
                    result = self.token_counter.count_chat(messages)
                    if inspect.isawaitable(result):
                        result = await result
                    cache[key] = result
                messages = operation.send(result)
        except StopIteration as completed:
            return completed.value

    def _plan_steps(self, request: ContextBudgetRequest):
        if request.model_context_limit <= 0:
            raise ContextBudgetExceeded("model context limit is invalid")
        if request.requested_output_tokens < 0 or request.template_overhead_tokens < 0 or request.safety_reserve_tokens < 0:
            raise ContextBudgetExceeded("reserved token values are invalid")

        system = Message(role="system", content=request.system_prompt)
        question = Message(role="user", content=request.current_question)
        mandatory_tokens = (yield [system, question]).tokens
        question_tokens = (yield [question]).tokens
        system_tokens = max(0, mandatory_tokens - question_tokens)
        fixed = mandatory_tokens + request.template_overhead_tokens + request.requested_output_tokens + request.safety_reserve_tokens
        if fixed > request.model_context_limit:
            raise ContextBudgetExceeded("system or question mandatory context exceeds model window")

        optional = request.model_context_limit - fixed
        history_cap = int(optional * request.history_budget_ratio)
        evidence_cap = int(optional * request.evidence_budget_ratio)
        history = yield from self._select_history_steps(
            request.history_messages, history_cap, request.history_candidate_limit
        )
        history_tokens = (yield history).tokens if history else 0
        evidence, evidence_tokens = yield from self._select_evidence_steps(
            request.evidence_items, evidence_cap
        )

        # Let unused capacity flow to the other optional section.
        unused_history = max(0, history_cap - history_tokens)
        evidence, evidence_tokens = yield from self._select_evidence_steps(
            request.evidence_items, evidence_cap + unused_history
        )
        unused_evidence = max(0, evidence_cap + unused_history - evidence_tokens)
        history = yield from self._select_history_steps(
            request.history_messages,
            history_cap + unused_evidence,
            request.history_candidate_limit,
        )

        selected_messages = [system, *history, self._current_user_message(evidence, request.current_question)]
        final_result = yield selected_messages
        reserved = request.template_overhead_tokens + request.requested_output_tokens + request.safety_reserve_tokens
        while final_result.tokens + reserved > request.model_context_limit:
            if evidence:
                evidence = evidence[:-1]
                selected_messages = [system, *history, self._current_user_message(evidence, request.current_question)]
            elif history:
                history = self._drop_oldest_turn(history)
                selected_messages = [system, *history, question]
            else:
                raise ContextBudgetExceeded("assembled context exceeds model window")
            final_result = yield selected_messages

        history_tokens = (yield history).tokens if history else 0
        evidence_tokens = (yield [self._evidence_message(evidence)]).tokens if evidence else 0
        candidate_turns = self._complete_history_turns(
            request.history_messages[-request.history_candidate_limit:]
        )
        selected_turns = self._complete_history_turns(history)
        candidate_history_messages = sum(len(turn) for turn in candidate_turns)
        selected_history_messages = sum(len(turn) for turn in selected_turns)
        selected_keys = {
            item.chunk_id or hashlib.sha256(item.normalized_content.encode("utf-8")).hexdigest()
            for item in evidence
        }
        usage = {
            "modelContextLimit": request.model_context_limit,
            "estimatedInputTokens": final_result.tokens,
            "systemTokens": system_tokens,
            "questionTokens": question_tokens,
            "historyTokens": history_tokens,
            "evidenceTokens": evidence_tokens,
            "templateOverheadTokens": request.template_overhead_tokens,
            "outputReserveTokens": request.requested_output_tokens,
            "safetyReserveTokens": request.safety_reserve_tokens,
            "availableOptionalTokens": optional,
            "selectedHistoryMessages": selected_history_messages,
            "droppedHistoryMessages": max(0, candidate_history_messages - selected_history_messages),
            "selectedHistoryTurns": len(selected_turns),
            "droppedHistoryTurns": max(0, len(candidate_turns) - len(selected_turns)),
            "selectedEvidenceItems": len(evidence),
            "selectedEvidenceSourceIds": [item.source_id for item in evidence],
            "selectedEvidenceChunkIds": [item.chunk_id for item in evidence],
            "droppedEvidenceItems": max(0, len(request.evidence_items) - len(selected_keys)),
            "truncatedEvidenceItems": sum(item.truncated for item in evidence),
            "countMode": final_result.mode,
            "tokenizer": final_result.tokenizer,
        }
        return ContextBudgetResult(
            selected_messages,
            evidence,
            {"max_tokens": request.requested_output_tokens},
            usage,
        )

    @staticmethod
    def _validate_request(request: ContextBudgetRequest) -> None:
        if not 0 <= request.history_budget_ratio <= 1:
            raise ContextBudgetExceeded("history_budget_ratio must be in [0, 1]")
        if not 0 <= request.evidence_budget_ratio <= 1:
            raise ContextBudgetExceeded("evidence_budget_ratio must be in [0, 1]")
        if request.history_budget_ratio + request.evidence_budget_ratio > 1:
            raise ContextBudgetExceeded("history_budget_ratio/evidence_budget_ratio sum must be <= 1")
        if request.history_candidate_limit <= 0:
            raise ContextBudgetExceeded("history_candidate_limit must be > 0")

    def _count(self, messages: Iterable[Message]) -> int:
        return self._count_result(list(messages)).tokens

    def _count_result(self, messages: list[Message]):
        result = self.token_counter.count_chat(messages)
        if inspect.isawaitable(result):
            close = getattr(result, "close", None)
            if close is not None:
                close()
            raise TypeError("use aplan with an asynchronous token counter")
        return result

    @staticmethod
    def _drop_oldest_turn(messages: list[Message]) -> list[Message]:
        turns = ContextBudgetPlanner._complete_history_turns(messages)
        return [message for turn in turns[1:] for message in turn]

    @staticmethod
    def _complete_history_turns(messages: list[Message]) -> list[list[Message]]:
        turns: list[list[Message]] = []
        current: list[Message] | None = None
        for message in messages:
            if message.role == "system":
                # Historical instructions are untrusted conversation data and Qwen only
                # accepts a system message at the beginning of the whole request.
                continue
            elif message.role == "user":
                if current and current[-1].role == "assistant":
                    turns.append(current)
                current = [message]
            elif (
                message.role == "assistant"
                and current
                and any(item.role == "user" for item in current)
                and not any(item.role == "assistant" for item in current)
            ):
                current.append(message)
        if current and current[-1].role == "assistant":
            turns.append(current)
        return turns

    @staticmethod
    def _evidence_message(items: list[EvidenceItem]) -> Message:
        return Message(role="user", content="\n\n".join(item.as_block() for item in items))

    @staticmethod
    def _current_user_message(items: list[EvidenceItem], question: str) -> Message:
        if not items:
            return Message(role="user", content=question)
        evidence = "\n\n".join(item.as_block() for item in items)
        return Message(role="user", content=f"{evidence}\n\n[用户问题]\n{question}")

    def _select_history(self, messages: list[Message], budget: int, limit: int) -> list[Message]:
        return self._drive_sync(self._select_history_steps(messages, budget, limit))

    def _select_history_steps(self, messages: list[Message], budget: int, limit: int):
        # Take the newest candidate messages, then discard any partial first turn.
        turns = self._complete_history_turns(messages[-limit:])
        selected: list[list[Message]] = []
        used = 0
        for turn in reversed(turns):
            cost = (yield turn).tokens
            if used + cost > budget:
                break
            selected.append(turn)
            used += cost
        return [message for turn in reversed(selected) for message in turn]

    def _select_evidence(self, items: list[EvidenceItem], budget: int) -> tuple[list[EvidenceItem], int]:
        return self._drive_sync(self._select_evidence_steps(items, budget))

    def _select_evidence_steps(self, items: list[EvidenceItem], budget: int):
        # Items are already ranked by the caller; preserve that order rather than re-sorting relevance here.
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
            full_cost = (yield [item.as_message()]).tokens
            if used + full_cost <= budget:
                selected.append(item)
                used += full_cost
                continue
            truncated = yield from self._truncate_steps(item, budget - used)
            if truncated is None:
                continue
            selected.append(truncated)
            used += (yield [truncated.as_message()]).tokens
            break
        return selected, used

    def _truncate(self, item: EvidenceItem, budget: int) -> EvidenceItem | None:
        return self._drive_sync(self._truncate_steps(item, budget))

    def _truncate_steps(self, item: EvidenceItem, budget: int):
        if budget <= 0:
            return None
        kept: list[str] = []
        for part in self._natural_segments(item.content):
            candidate = "".join([*kept, part]).strip()
            trial = replace(item, content=candidate, truncated=True)
            if (yield [trial.as_message()]).tokens <= budget:
                kept.append(part)
            else:
                break
        content = "".join(kept).strip()
        if self._readable_length(content) < 12:
            return None
        return replace(item, content=content, truncated=True)

    @staticmethod
    def _natural_segments(content: str) -> list[str]:
        segments: list[str] = []
        start = 0
        for index, char in enumerate(content):
            if char == "." and not ContextBudgetPlanner._is_english_period_boundary(content, index):
                continue
            if char not in "。！？.!?\n":
                continue
            end = index + 1
            while end < len(content) and content[end].isspace() and content[end] != "\n":
                end += 1
            segment = content[start:end]
            if segment:
                segments.append(segment)
            start = end
        if start < len(content):
            segments.append(content[start:])
        return segments

    @staticmethod
    def _is_english_period_boundary(content: str, index: int) -> bool:
        if index > 0 and index + 1 < len(content):
            if content[index - 1].isdigit() and content[index + 1].isdigit():
                return False
        if index + 1 < len(content) and not content[index + 1].isspace():
            return False

        next_index = index + 1
        while next_index < len(content) and content[next_index].isspace():
            next_index += 1
        if next_index < len(content) and not content[next_index].isupper():
            return False

        prefix = content[: index + 1].casefold()
        if any(prefix.endswith(abbreviation) for abbreviation in ("e.g.", "i.e.", "no.", "fig.")):
            return False
        token_match = re.search(r"([a-z]+)\.$", prefix)
        if token_match and len(token_match.group(1)) == 1:
            return False
        return True

    @staticmethod
    def _readable_length(content: str) -> int:
        return sum(2 if "\u4e00" <= char <= "\u9fff" else 1 for char in content if not char.isspace())
