from __future__ import annotations

import hashlib
import re
from typing import Any

from app.core.settings import Settings
from app.models.schemas import (RagIndexRequest, RagIndexData, RagSearchRequest, RagSearchData, RagRecord,
                                RagDeleteRequest, RagDeleteData)
from .qwen_client import QwenClient
from .vector_store import (ChunkRecord, LocalJsonVectorStore, PgVectorStore, MilvusVectorStore, VectorStore,
                           chinese_numeral_to_int, compact_search_text, extract_clause_numbers, iter_bigrams, text_match_score)


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
        candidate_limit = max(request.topK, self.settings.rag_rerank_top_k if request.rerankEnabled else request.topK)
        candidates = await self.store.search(
            vectors[0], request.projectId, request.knowledgeBaseIds, candidate_limit
        )
        text_search = getattr(self.store, "search_text", None)
        text_candidates = await text_search(
            request.query, request.projectId, request.knowledgeBaseIds, max(candidate_limit, request.topK * 4)
        ) if text_search else []
        candidates = merge_candidates(candidates, text_candidates)
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
        candidate_chunks = [chunk for chunk, _, _ in records]
        adjacent_many = getattr(self.store, "adjacent_many", None)
        if adjacent_many is not None:
            adjacent_by_chunk = await adjacent_many(candidate_chunks, before=1, after=1)
        else:
            # Keep compatibility with external VectorStore implementations while the protocol rolls out.
            adjacent_by_chunk = {
                chunk.id: await self.store.adjacent(chunk, before=1, after=1)
                for chunk in candidate_chunks
            }
        records.sort(
            key=lambda item: evidence_sort_key(
                request.query,
                item[0],
                item[2],
                evidence_content=join_evidence_window(item[0], adjacent_by_chunk.get(item[0].id, [])),
            ),
            reverse=True,
        )
        selected = records[: request.topK]
        output: list[RagRecord] = []
        selected_ids = {chunk.id for chunk, _, _ in selected}
        emitted_ids: set[str] = set()
        for chunk, score, rerank_score in selected:
            if chunk.id in emitted_ids:
                continue
            output.append(to_rag_record(chunk, score, rerank_score))
            emitted_ids.add(chunk.id)
            adjacent = adjacent_by_chunk.get(chunk.id, [])
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



def merge_candidates(
    vector_candidates: list[tuple[ChunkRecord, float]],
    text_candidates: list[tuple[ChunkRecord, float]],
) -> list[tuple[ChunkRecord, float]]:
    merged: dict[str, tuple[ChunkRecord, float]] = {chunk.id: (chunk, score) for chunk, score in vector_candidates}
    if text_candidates:
        max_text_score = max(score for _, score in text_candidates)
        for chunk, score in text_candidates:
            # Keep exact text evidence competitive with semantic distractors while
            # retaining the original vector score for diagnostics.
            normalized = 0.80 + 0.25 * score / max_text_score if max_text_score > 0 else 0.80
            current = merged.get(chunk.id)
            if current is None or normalized > current[1]:
                merged[chunk.id] = (chunk, normalized)
    return sorted(merged.values(), key=lambda item: item[1], reverse=True)


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
    score = structured_evidence_score(query, chunk.content, score, chunk.metadata)
    clauses = extract_clause_numbers(query)
    if not clauses:
        return score
    content_clauses = extract_clause_numbers(chunk.content)
    if not clauses.intersection(content_clauses):
        return score
    if is_directory_content(chunk.content):
        return score - 0.35
    return score + 1.0


def evidence_sort_key(
    query: str, chunk: ChunkRecord, rerank_score: float, evidence_content: str | None = None
) -> tuple[int, float, int, float, float]:
    """Rank an answer-bearing anchor first, then let contiguous context complete it."""
    anchor_tier, anchor_strength = direct_evidence_priority(query, chunk, chunk.content)
    if anchor_tier <= 0:
        return 0, 0.0, 0, 0.0, rerank_score
    window_tier, window_strength = direct_evidence_priority(
        query, chunk, evidence_content or chunk.content
    )
    return anchor_tier, anchor_strength, window_tier, window_strength, rerank_score


def direct_evidence_priority(
    query: str, chunk: ChunkRecord, evidence_content: str | None = None
) -> tuple[int, float]:
    evidence_content = evidence_content or chunk.content
    query_clauses = extract_clause_numbers(query)
    content_clauses = extract_clause_numbers(chunk.content)
    exact_clauses = query_clauses.intersection(content_clauses)
    body_clauses = {clause for clause in exact_clauses if contains_clause_body(chunk.content, clause)}
    source_strength = source_scope_strength(query, chunk.title)
    source_identity_strength = identified_source_strength(query, chunk.title)
    if body_clauses and not is_directory_content(chunk.content):
        # An exact clause from the source identified by the user outranks the same clause elsewhere.
        clause_bonus = 2.0 * len(exact_clauses)
        lexical_strength = min(max(text_match_score(query, chunk.content) - clause_bonus, 0.0), 1.0)
        if source_identity_strength > 0 or source_strength > 0:
            return 6, float(len(body_clauses)) + source_identity_strength + source_strength + lexical_strength
        return 5, float(len(body_clauses)) + lexical_strength

    numeric_anchors = extract_numeric_anchors(query) - query_clauses
    content_compact = compact_search_text(evidence_content)
    matched_numeric = {anchor for anchor in numeric_anchors if anchor in content_compact}
    if matched_numeric:
        coverage = len(matched_numeric) / max(len(numeric_anchors), 1)
        strength = coverage + min(len("".join(matched_numeric)) / 20.0, 1.0) + source_strength
        return (4 if source_strength > 0 else 3), strength

    focus_terms = extract_query_focus_terms(query)
    matched_focus = {term for term in focus_terms if focus_term_matches(term, content_compact)}
    if matched_focus:
        coverage = len(matched_focus) / max(len(focus_terms), 1)
        specificity = min(sum(len(term) for term in matched_focus) / 24.0, 1.0)
        return (4 if source_strength > 0 else 2), coverage + specificity + source_strength

    lexical_strength = text_match_score(query, evidence_content)
    if lexical_strength >= 0.35:
        return (4 if source_strength > 0 else 2), lexical_strength + source_strength

    if source_strength > 0:
        return 3, source_strength
    if exact_clauses:
        return 1, float(len(exact_clauses))
    if is_structured_query(query) and looks_like_table(chunk.content, chunk.metadata):
        return 1, max(0.0, text_match_score(query, chunk.content))
    return 0, 0.0


def join_evidence_window(chunk: ChunkRecord, adjacent: list[ChunkRecord]) -> str:
    records = [*adjacent, chunk]
    records.sort(key=lambda record: (
        record.metadata.get("unitIndex", 0), record.metadata.get("chunkIndex", 0)
    ))
    return "\n".join(record.content for record in records)


def contains_clause_body(content: str, clause: str) -> bool:
    # Normalize article headings so Chinese and Arabic numbering share one boundary-safe matcher.
    content = re.sub(
        r"第\s*([零〇一二两三四五六七八九十百千万]+)\s*条",
        lambda match: f"第{chinese_numeral_to_int(match.group(1))}条",
        content or "",
    )
    escaped = re.escape(clause)
    heading = re.compile(
        rf"(?:^|[\n\r。！？；;])\s*(?:#{{1,6}}\s*)?(?:第\s*)?{escaped}\s*条?(?=\s|[\u4e00-\u9fff])"
    )
    if heading.search(content):
        return True
    for match in re.finditer(rf"(?<![\d.]){escaped}(?![\d.])", content):
        prefix = content[max(0, match.start() - 12):match.start()]
        if not re.search(r"(?:参照|依据|按照|符合|引用|详见|见|执行|对应)\s*$", prefix):
            suffix = content[match.end():match.end() + 30]
            if re.search(r"(?:条\s*)?[\u4e00-\u9fff]", suffix):
                return True
    return False


def identified_source_strength(query: str, title: str) -> float:
    """Match stable document identifiers (for example, standard codes) generically."""
    query_identifiers = extract_source_identifiers(query)
    title_identifiers = extract_source_identifiers(title)
    if not query_identifiers or not title_identifiers:
        return 0.0
    matched = [identifier for identifier in query_identifiers if any(
        title_identifier.startswith(identifier) or identifier.startswith(title_identifier)
        for title_identifier in title_identifiers
    )]
    return min(2.0, max((len(identifier) / 8.0 for identifier in matched), default=0.0))


def extract_source_identifiers(value: str) -> set[str]:
    compact = compact_search_text(value)
    # Avoid treating clause numbers and standalone years as source identifiers.
    return {
        match.group(0).replace("-", "")
        for match in re.finditer(r"(?<![a-z])[a-z]{1,8}-?\d{2,}(?:-?\d{2,4})?", compact)
    }


def source_scope_strength(query: str, title: str) -> float:
    query_compact = compact_search_text(query)
    title_compact = compact_search_text(title)
    title_core = re.sub(r"^(?:\d+[_-]?)+", "", title_compact)
    title_core = re.sub(r"(?:20\d{2}|19\d{2})?\.?(?:pdf|docx?|xlsx?|pptx?|txt)$", "", title_core)
    if len(title_core) >= 6 and title_core in query_compact:
        return min(2.0, len(title_core) / 12.0)
    quoted = [compact_search_text(value) for value in re.findall(r"[《〈]([^》〉]+)[》〉]", query)]
    matches = [
        value for value in quoted
        if len(value) >= 4 and (
            value in title_compact
            or (len(title_core) >= 6 and title_core in value)
        )
    ]
    return min(2.0, max((len(value) / 12.0 for value in matches), default=0.0))


def is_directory_content(content: str) -> bool:
    return "目次" in content or "目录" in content


def extract_numeric_anchors(value: str) -> set[str]:
    compact = compact_search_text(value)
    return set(re.findall(r"(?<![0-9.])\d+(?:\.\d+)*(?:db(?:a)?|ms|min|h|小时|分钟|秒|周岁|岁|年|月|日|次|%|％)?", compact))


def extract_query_focus_terms(query: str) -> set[str]:
    compact = re.sub(r"[^0-9A-Za-z\u4e00-\u9fff.]", "", query or "")
    if not compact:
        return set()
    markers = ("哪些", "多少", "如何", "怎么", "是否", "多久", "多长", "为什么", "为何", "分别", "什么")
    positions = [(compact.rfind(marker), marker) for marker in markers if marker in compact]
    if not positions:
        return set()
    position, marker = max(positions, key=lambda item: item[0])
    suffix = compact[position + len(marker):]
    focus = suffix if len(suffix) >= 2 else compact[max(0, position - 28):position]
    focus = re.sub(r"(?:是什么|有什么|怎么做|应当|应该|需要|规定|要求|情况|内容|信息|资料|问题|结果|是)+$", "", focus)
    parts = re.split(r"(?:以及|或者|并且|其中|和|与|及|、|或|中|的)", focus)
    generic = {"什么", "哪些", "多少", "如何", "怎么", "是否", "多久", "多长", "为什么", "为何", "分别"}
    return {part for part in parts if len(part) >= 2 and part not in generic}


def focus_term_matches(term: str, content_compact: str) -> bool:
    compact_term = compact_search_text(term)
    if compact_term in content_compact:
        return True
    aliases = {
        "年龄": ("年满", "周岁"),
        "学历": ("文化程度",),
        "时间": ("日期", "时长", "期限"),
        "地点": ("位置", "场所"),
        "数量": ("数目", "个数"),
    }
    return any(alias in content_compact for alias in aliases.get(compact_term, ()))


def is_structured_query(query: str) -> bool:
    compact = compact_search_text(query)
    return any(marker in compact for marker in ("表", "分别", "各", "对应", "列出", "多少", "哪些"))


def looks_like_table(content: str, metadata: dict[str, Any] | None = None) -> bool:
    metadata = metadata or {}
    if str(metadata.get("blockType", "")).upper() == "TABLE":
        return True
    structured = metadata.get("structuredData")
    if isinstance(structured, dict) and any(key in structured for key in ("rows", "columns", "headers", "cells", "values")):
        return True
    if len(re.findall(r"(?m)^\s*\|.*\|\s*$", content)) >= 2:
        return True
    has_caption = bool(re.search(r"(?:^|\s)表\s*[0-9A-Za-z一二三四五六七八九十]+(?:\s|$)", content))
    has_tabular_values = "单位" in content or len(re.findall(r"(?<![A-Za-z])\d+(?:\.\d+)?", content)) >= 2
    return has_caption and has_tabular_values


def structured_evidence_score(
    query: str,
    content: str,
    score: float,
    metadata: dict[str, Any] | None = None,
) -> float:
    """Boost actual structured evidence when it covers concepts requested by the query."""
    query_compact = compact_search_text(query)
    content_compact = compact_search_text(content)
    if not query_compact or not content_compact:
        return score
    if not is_structured_query(query) or not looks_like_table(content, metadata):
        return score

    generic_bigrams = {"什么", "的是", "多少", "哪些", "分别", "如何", "规定", "是否"}
    query_bigrams = {bigram for bigram in iter_bigrams(query_compact) if bigram not in generic_bigrams}
    matched = {bigram for bigram in query_bigrams if bigram in content_compact}
    if len(matched) < 2:
        return score

    coverage = len(matched) / max(len(query_bigrams), 1)
    if coverage < 0.12:
        return score
    return score + min(1.0, 0.35 + coverage * 1.5)


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
