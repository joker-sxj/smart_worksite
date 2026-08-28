from __future__ import annotations

import hashlib
import re
from typing import Any

from app.core.settings import Settings
from app.models.schemas import (RagIndexRequest, RagIndexData, RagSearchRequest, RagSearchData, RagRecord,
                                RagDeleteRequest, RagDeleteData)
from .qwen_client import QwenClient
from .vector_store import ChunkRecord, LocalJsonVectorStore, PgVectorStore, MilvusVectorStore, VectorStore


def split_text(text: str, chunk_size: int, overlap: int) -> list[str]:
    clean = re.sub(r"\s+", " ", text).strip()
    if not clean:
        return []
    chunks: list[str] = []
    start = 0
    while start < len(clean):
        end = min(len(clean), start + chunk_size)
        chunks.append(clean[start:end])
        if end >= len(clean):
            break
        start = max(end - overlap, start + 1)
    return chunks


class RagService:
    def __init__(self, settings: Settings, qwen: QwenClient):
        self.settings = settings
        self.qwen = qwen
        self.store = self._build_store(settings)

    def _build_store(self, settings: Settings) -> VectorStore:
        provider = settings.rag_provider.upper()
        if provider == "MILVUS":
            return MilvusVectorStore(settings.milvus_uri, settings.milvus_token, settings.milvus_collection)
        if provider == "PGVECTOR":
            return PgVectorStore(settings.pgvector_dsn, settings.pgvector_table)
        return LocalJsonVectorStore(settings.rag_data_dir, reembed=self._embed_for_legacy)

    async def _embed_for_legacy(self, texts: list[str]) -> list[list[float]]:
        embeddings, _ = await self.embed_batched(texts)
        return embeddings

    async def index(self, request: RagIndexRequest) -> tuple[RagIndexData, dict[str, Any]]:
        chunk_size = request.chunkSize or self.settings.rag_chunk_size
        overlap = request.chunkOverlap if request.chunkOverlap is not None else self.settings.rag_chunk_overlap
        chunk_records: list[ChunkRecord] = []
        usage_total: dict[str, Any] = {}
        for document in request.documents:
            document_records: list[ChunkRecord] = []
            units = [
                (
                    block.content,
                    {
                        "blockId": block.blockId,
                        "blockType": block.blockType,
                        "location": block.location,
                        "structuredData": block.structuredData,
                    },
                )
                for block in document.blocks
                if block.content.strip()
            ] or [(document.content, {})]
            for unit_index, (unit_content, unit_metadata) in enumerate(units):
                chunks = split_text(unit_content, chunk_size, overlap)
                if not chunks:
                    continue
                embeddings, usage = await self.embed_batched(chunks)
                usage_total = merge_usage(usage_total, usage)
                for chunk_index, (chunk, embedding) in enumerate(zip(chunks, embeddings)):
                    block_key = unit_metadata.get("blockId", f"document-{unit_index}")
                    raw_id = f"{request.projectId}:{request.knowledgeBaseId}:{document.documentId}:{block_key}:{chunk_index}:{chunk}"
                    chunk_id = hashlib.sha256(raw_id.encode("utf-8")).hexdigest()
                    document_records.append(ChunkRecord(
                        id=chunk_id,
                        chunkId=chunk_id,
                        projectId=request.projectId,
                        knowledgeBaseId=request.knowledgeBaseId,
                        documentId=document.documentId,
                        title=document.title,
                        content=chunk,
                        sourceType=document.sourceType,
                        sourceId=document.sourceId,
                        metadata={
                            **document.metadata,
                            **unit_metadata,
                            "projectId": request.projectId,
                            "knowledgeBaseId": request.knowledgeBaseId,
                            "documentId": document.documentId,
                            "chunkId": chunk_id,
                            "chunkIndex": chunk_index,
                            "unitIndex": unit_index,
                        },
                        embedding=embedding,
                    ))
            chunk_records.extend(document_records)
        # Prepare every document before replacing any stored chunks, so a failed
        # embedding call cannot leave the index partially refreshed.
        document_chunks: dict[str, list[ChunkRecord]] = {}
        for chunk in chunk_records:
            document_chunks.setdefault(chunk.documentId, []).append(chunk)
        for document in request.documents:
            await self.store.replace_document(
                request.projectId,
                request.knowledgeBaseId,
                document.documentId,
                document_chunks.get(document.documentId, []),
            )
        return RagIndexData(indexedDocuments=len(request.documents), indexedChunks=len(chunk_records), provider=self.settings.rag_provider.upper()), usage_total

    async def delete(self, request: RagDeleteRequest) -> RagDeleteData:
        deleted = await self.store.delete_sources(
            request.projectId, request.sourceType, request.sourceIds, request.excludeKnowledgeBaseId
        )
        return RagDeleteData(deletedChunks=deleted, provider=self.settings.rag_provider.upper())

    async def search(self, request: RagSearchRequest) -> tuple[RagSearchData, dict[str, Any]]:
        vectors, usage = await self.embed([request.query])
        candidates = await self.store.search(
            vectors[0],
            request.projectId,
            request.knowledgeBaseIds,
            max(request.topK, self.settings.rag_rerank_top_k if request.rerankEnabled else request.topK),
        )
        threshold = request.scoreThreshold if request.scoreThreshold is not None else -1.0
        records = []
        for chunk, score in candidates:
            if score < threshold:
                continue
            rerank_score = lexical_rerank(request.query, chunk.content, score) if request.rerankEnabled else score
            records.append((chunk, score, rerank_score))
        if request.rerankEnabled:
            records, rerank_usage = await self.rerank(request.query, records, len(records))
            usage = merge_usage(usage, rerank_usage)
        records = [
            (chunk, score, clause_aware_score(request.query, chunk, rerank_score))
            for chunk, score, rerank_score in records
        ]
        records.sort(key=lambda item: item[2], reverse=True)
        selected = records[: request.topK]
        output: list[RagRecord] = []
        selected_ids = {chunk.id for chunk, _, _ in selected}
        emitted_ids: set[str] = set()
        for chunk, score, rerank_score in selected:
            if chunk.id in emitted_ids:
                continue
            output.append(to_rag_record(chunk, score, rerank_score))
            emitted_ids.add(chunk.id)
            adjacent = await self.store.adjacent(chunk, before=1, after=1)
            for neighbor in adjacent:
                if neighbor.id in selected_ids or neighbor.id in emitted_ids:
                    continue
                neighbor_metadata = {
                    **neighbor.metadata,
                    "projectId": neighbor.projectId,
                    "knowledgeBaseId": neighbor.knowledgeBaseId,
                    "documentId": neighbor.documentId,
                    "chunkId": neighbor.chunkId,
                    "contextExpansion": True,
                    "expandedFromChunkId": chunk.chunkId,
                }
                output.append(RagRecord(
                    title=neighbor.title,
                    contentSnippet=neighbor.content,
                    sourceType=neighbor.sourceType,
                    sourceId=neighbor.sourceId or neighbor.documentId,
                    score=float(score),
                    metadata=neighbor_metadata,
                ))
                emitted_ids.add(neighbor.id)
        return RagSearchData(records=output), usage

    async def embed(self, texts: list[str]) -> tuple[list[list[float]], dict[str, Any]]:
        if self.settings.embedding_provider.upper() == "LOCAL_HASH":
            return [hash_embedding(text) for text in texts], {"provider": "LOCAL_HASH"}
        return await self.qwen.embed(texts)

    async def embed_batched(self, texts: list[str]) -> tuple[list[list[float]], dict[str, Any]]:
        batch_size = max(1, self.settings.qwen_embedding_batch_size)
        embeddings: list[list[float]] = []
        usage_total: dict[str, Any] = {}
        for start in range(0, len(texts), batch_size):
            batch_embeddings, usage = await self.embed(texts[start:start + batch_size])
            embeddings.extend(batch_embeddings)
            usage_total = merge_usage(usage_total, usage)
        return embeddings, usage_total

    async def rerank(
        self,
        query: str,
        records: list[tuple[ChunkRecord, float, float]],
        top_k: int,
    ) -> tuple[list[tuple[ChunkRecord, float, float]], dict[str, Any]]:
        provider = self.settings.rerank_provider.upper()
        if provider != "QWEN" or not records:
            return records, {"rerankProvider": "LEXICAL"}
        try:
            results, usage = await self.qwen.rerank(query, [item[0].content for item in records], max(top_k, 1))
            usage = merge_usage({"rerankProvider": "QWEN"}, usage)
            scored = list(records)
            for item in results:
                index = int(item.get("index", -1))
                if 0 <= index < len(scored):
                    chunk, vector_score, _ = scored[index]
                    relevance = float(item.get("relevance_score", item.get("score", vector_score)))
                    scored[index] = (chunk, vector_score, relevance)
            return scored, usage
        except Exception:
            return records, {"rerankProvider": "LEXICAL_FALLBACK"}


def to_rag_record(chunk: ChunkRecord, score: float, rerank_score: float) -> RagRecord:
    return RagRecord(
        title=chunk.title,
        contentSnippet=chunk.content,
        sourceType=chunk.sourceType,
        sourceId=chunk.sourceId or chunk.documentId,
        score=float(score),
        metadata={
            **chunk.metadata,
            "projectId": chunk.projectId,
            "knowledgeBaseId": chunk.knowledgeBaseId,
            "documentId": chunk.documentId,
            "chunkId": chunk.chunkId,
            "rerankScore": float(rerank_score),
        },
    )


def clause_aware_score(query: str, chunk: ChunkRecord, score: float) -> float:
    clauses = set(re.findall(r"(?:第\s*)?(\d+(?:\.\d+)+)\s*条?", query))
    if not clauses:
        return score
    normalized_content = re.sub(r"\s+", "", chunk.content)
    if not any(clause in normalized_content for clause in clauses):
        return score
    if "目次" in chunk.content or "目录" in chunk.content:
        return score - 0.35
    return score + 1.0

def lexical_rerank(query: str, content: str, vector_score: float) -> float:
    query_terms = set(re.findall(r"[\w\u4e00-\u9fff]+", query.lower()))
    content_terms = set(re.findall(r"[\w\u4e00-\u9fff]+", content.lower()))
    if not query_terms:
        return vector_score
    overlap = len(query_terms & content_terms) / len(query_terms)
    return vector_score * 0.8 + overlap * 0.2


def hash_embedding(text: str, dim: int = 384) -> list[float]:
    vector = [0.0] * dim
    tokens = re.findall(r"[\w\u4e00-\u9fff]+", text.lower())
    for token in tokens or [text]:
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        for i, byte in enumerate(digest):
            idx = (byte + i * 31) % dim
            vector[idx] += 1.0
    norm = sum(x * x for x in vector) ** 0.5
    if norm:
        vector = [x / norm for x in vector]
    return vector


def merge_usage(left: dict[str, Any], right: dict[str, Any]) -> dict[str, Any]:
    merged = dict(left)
    for key, value in (right or {}).items():
        if isinstance(value, (int, float)) and isinstance(merged.get(key), (int, float)):
            merged[key] += value
        else:
            merged[key] = value
    return merged
