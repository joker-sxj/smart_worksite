from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable, Protocol

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
    """Deterministic upper-biased estimate that never needs model files.

    Each UTF-8 byte is charged as one token, including whitespace. Since local
    model tokenizers encode text into tokens spanning one or more source bytes,
    this is a deterministic upper-biased estimate for Latin text, CJK, numbers,
    punctuation, code, and mixed content without using model files.
    """

    MODE = "ESTIMATED"
    TOKENIZER = "conservative-v1"
    _MESSAGE_OVERHEAD = 4
    _CHAT_PRIMING_OVERHEAD = 2

    def count_text(self, text: str) -> TokenCount:
        if not text:
            return self._result(0)

        return self._result(len(text.encode("utf-8")))

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

    def _result(self, tokens: int) -> TokenCount:
        return TokenCount(tokens=tokens, mode=self.MODE, tokenizer=self.TOKENIZER)


class LocalHfTokenCounter:
    """Exact chat-template counter loaded only from an explicit local path."""

    def __init__(self, tokenizer_path: str):
        self.tokenizer_path = tokenizer_path
        self._tokenizer: Any | None = None

    def count_chat(self, messages: Iterable[Message]) -> TokenCount:
        tokenizer = self._load_tokenizer()
        payload = [{"role": item.role, "content": item.content} for item in messages]
        try:
            encoded = tokenizer.apply_chat_template(
                payload,
                tokenize=True,
                add_generation_prompt=True,
            )
            tokens = self._encoded_length(encoded)
        except LocalTokenizationError:
            raise
        except Exception:
            raise LocalTokenizationError("local Hugging Face tokenizer count failed") from None
        tokenizer_name = str(getattr(tokenizer, "name_or_path", None) or self.tokenizer_path)
        return TokenCount(tokens=tokens, mode="EXACT", tokenizer=tokenizer_name)

    def _load_tokenizer(self):
        if self._tokenizer is None:
            try:
                from transformers import AutoTokenizer

                self._tokenizer = AutoTokenizer.from_pretrained(
                    self.tokenizer_path,
                    local_files_only=True,
                )
            except Exception:
                raise LocalTokenizationError("local Hugging Face tokenizer load failed") from None
        return self._tokenizer

    @staticmethod
    def _encoded_length(encoded: Any) -> int:
        if isinstance(encoded, dict):
            encoded = encoded.get("input_ids")
        if encoded is None:
            raise LocalTokenizationError("local Hugging Face tokenizer returned no input ids")
        try:
            if hasattr(encoded, "shape") and len(encoded.shape) > 1:
                return int(encoded.shape[-1])
            return len(encoded)
        except (TypeError, ValueError, IndexError):
            raise LocalTokenizationError("local Hugging Face tokenizer returned invalid input ids") from None


class TokenCounter:
    """Prefer configured local HF or local vLLM counts, with policy fallback."""

    def __init__(self, settings, qwen_client, estimated_counter: ConservativeTokenCounter | None = None):
        self.settings = settings
        self.qwen_client = qwen_client
        self.estimated_counter = estimated_counter or ConservativeTokenCounter()
        tokenizer_path = getattr(settings, "context_tokenizer_path", "")
        self.local_counter = LocalHfTokenCounter(tokenizer_path) if tokenizer_path else None

    async def count_chat(self, messages: Iterable[Message]) -> TokenCount:
        message_list = list(messages)
        if self.local_counter is not None:
            try:
                return self.local_counter.count_chat(message_list)
            except LocalTokenizationError:
                pass

        if not getattr(self.settings, "context_tokenizer_endpoint_enabled", True):
            return self._estimated_or_raise(message_list, "local tokenizer endpoint is disabled")
        try:
            result = await self.qwen_client.count_chat_tokens(message_list)
            if result.mode != "EXACT" and getattr(self.settings, "context_require_exact_tokenizer", False):
                return self._estimated_or_raise(message_list, "local tokenizer endpoint returned an estimate")
            return result
        except LocalTokenizationError:
            return self._estimated_or_raise(message_list, "local tokenizer endpoint unavailable")

    def _estimated_or_raise(self, messages: list[Message], reason: str) -> TokenCount:
        if getattr(self.settings, "context_require_exact_tokenizer", False):
            raise RuntimeError(f"Exact local tokenization failed: {reason}") from None
        return self.estimated_counter.count_chat(messages)
