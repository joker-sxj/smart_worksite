from __future__ import annotations

import math
import unicodedata
from dataclasses import dataclass
from typing import Iterable, Protocol

from app.models.schemas import Message


@dataclass(frozen=True)
class TokenCount:
    tokens: int
    mode: str
    tokenizer: str


class LocalTokenizationError(RuntimeError):
    """Sanitized failure from a local exact-tokenization attempt."""


class ChatTokenCounter(Protocol):
    def count_text(self, text: str) -> TokenCount: ...

    def count_chat(self, messages: Iterable[Message]) -> TokenCount: ...


class ConservativeTokenCounter:
    """Deterministic upper-biased estimate that never needs model files."""

    MODE = "ESTIMATED"
    TOKENIZER = "conservative-v1"
    _MESSAGE_OVERHEAD = 4
    _CHAT_PRIMING_OVERHEAD = 2

    def count_text(self, text: str) -> TokenCount:
        if not text:
            return self._result(0)

        tokens = 0
        alphanumeric_run = 0

        def flush_run() -> None:
            nonlocal tokens, alphanumeric_run
            if alphanumeric_run:
                tokens += math.ceil(alphanumeric_run / 3)
                alphanumeric_run = 0

        for character in text:
            if character == "\n":
                flush_run()
                tokens += 1
            elif self._is_cjk(character):
                flush_run()
                tokens += 1
            elif character.isalnum() or character == "_":
                alphanumeric_run += 1
            elif character.isspace():
                flush_run()
            else:
                flush_run()
                tokens += 1

        flush_run()
        return self._result(max(1, tokens))

    def count_chat(self, messages: Iterable[Message]) -> TokenCount:
        message_list = list(messages)
        if not message_list:
            return self._result(0)

        tokens = self._CHAT_PRIMING_OVERHEAD
        for message in message_list:
            tokens += self._MESSAGE_OVERHEAD
            tokens += self.count_text(message.role).tokens
            tokens += self.count_text(message.content).tokens
        return self._result(tokens)

    @staticmethod
    def _is_cjk(character: str) -> bool:
        codepoint = ord(character)
        return (
            0x3400 <= codepoint <= 0x4DBF
            or 0x4E00 <= codepoint <= 0x9FFF
            or 0xF900 <= codepoint <= 0xFAFF
            or 0x20000 <= codepoint <= 0x2FA1F
            or unicodedata.name(character, "").startswith(("HIRAGANA", "KATAKANA", "HANGUL"))
        )

    def _result(self, tokens: int) -> TokenCount:
        return TokenCount(tokens=tokens, mode=self.MODE, tokenizer=self.TOKENIZER)


class TokenCounter:
    """Use local vLLM exact counts when available, with policy-controlled fallback."""

    def __init__(self, settings, qwen_client, estimated_counter: ConservativeTokenCounter | None = None):
        self.settings = settings
        self.qwen_client = qwen_client
        self.estimated_counter = estimated_counter or ConservativeTokenCounter()

    async def count_chat(self, messages: Iterable[Message]) -> TokenCount:
        message_list = list(messages)
        if not getattr(self.settings, "context_tokenizer_endpoint_enabled", True):
            return self._estimated_or_raise(message_list, "local tokenizer endpoint is disabled")
        try:
            return await self.qwen_client.count_chat_tokens(message_list)
        except LocalTokenizationError:
            return self._estimated_or_raise(message_list, "local tokenizer endpoint unavailable")

    def _estimated_or_raise(self, messages: list[Message], reason: str) -> TokenCount:
        if getattr(self.settings, "context_require_exact_tokenizer", False):
            raise RuntimeError(f"Exact local tokenization failed: {reason}") from None
        return self.estimated_counter.count_chat(messages)
