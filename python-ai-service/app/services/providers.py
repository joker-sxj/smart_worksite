from typing import Any, Protocol

from app.models.schemas import Message


class ChatModelProvider(Protocol):
    async def chat(
        self,
        messages: list[Message],
        model: str | None = None,
        parameters: dict[str, Any] | None = None,
    ) -> tuple[str, dict[str, Any]]: ...


class VisionModelProvider(Protocol):
    async def vision_json_chat(
        self,
        prompt: str,
        file_sources: str | list[str],
        content_type: str | None = None,
    ) -> tuple[dict[str, Any], dict[str, Any]]: ...


class EmbeddingProvider(Protocol):
    async def embed(
        self,
        texts: list[str],
        model: str | None = None,
    ) -> tuple[list[list[float]], dict[str, Any]]: ...


class RerankProvider(Protocol):
    async def rerank(
        self,
        query: str,
        documents: list[str],
        top_n: int,
    ) -> tuple[list[dict[str, Any]], dict[str, Any]]: ...


class DocumentUnderstandingProvider(VisionModelProvider, Protocol):
    pass
