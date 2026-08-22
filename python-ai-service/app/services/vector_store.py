from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any, Protocol

import numpy as np


logger = logging.getLogger(__name__)


@dataclass
class ChunkRecord:
    id: str
    projectId: int
    knowledgeBaseId: int
    documentId: str
    title: str
    content: str
    sourceType: str
    sourceId: str | None
    metadata: dict[str, Any]
    embedding: list[float]
    chunkId: str | None = None

    def __post_init__(self) -> None:
        if not self.chunkId:
            self.chunkId = self.id
        elif self.chunkId != self.id:
            raise ValueError("chunkId must equal id")
        required_text = {
            "chunkId": self.chunkId,
            "documentId": self.documentId,
            "title": self.title,
            "sourceType": self.sourceType,
        }
        for field, value in required_text.items():
            if not value or not str(value).strip():
                raise ValueError(f"{field} must not be blank")
        if not self.id or not self.id.strip():
            raise ValueError("id must not be blank")
        if self.projectId <= 0:
            raise ValueError("projectId must be positive")
        if self.knowledgeBaseId is None or self.knowledgeBaseId <= 0:
            raise ValueError("knowledgeBaseId must be positive")


class VectorStore(Protocol):
    async def upsert(self, chunks: list[ChunkRecord]) -> None: ...
    async def replace_document(self, project_id: int, knowledge_base_id: int, document_id: str, chunks: list[ChunkRecord]) -> None: ...
    async def search(self, query_embedding: list[float], project_id: int, knowledge_base_ids: list[int], top_k: int) -> list[tuple[ChunkRecord, float]]: ...
    async def delete_sources(self, project_id: int, source_type: str, source_ids: list[str], exclude_knowledge_base_id: int | None) -> int: ...


def cosine(a: list[float], b: list[float]) -> float:
    av = np.array(a, dtype=np.float32)
    bv = np.array(b, dtype=np.float32)
    denom = float(np.linalg.norm(av) * np.linalg.norm(bv))
    if denom == 0:
        return 0.0
    return float(np.dot(av, bv) / denom)


class LocalJsonVectorStore:
    def __init__(self, data_dir: str):
        self.path = Path(data_dir) / "chunks.jsonl"
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def _load(self) -> list[ChunkRecord]:
        if not self.path.exists():
            return []
        records: list[ChunkRecord] = []
        for line in self.path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                records.append(ChunkRecord(**json.loads(line)))
        return records

    async def upsert(self, chunks: list[ChunkRecord]) -> None:
        existing = {item.id: item for item in self._load()}
        for chunk in chunks:
            existing[chunk.id] = chunk
        text = "\n".join(json.dumps(asdict(item), ensure_ascii=False) for item in existing.values())
        self.path.write_text(text + ("\n" if text else ""), encoding="utf-8")

    async def replace_document(self, project_id: int, knowledge_base_id: int, document_id: str, chunks: list[ChunkRecord]) -> None:
        validate_document_scope(project_id, knowledge_base_id, document_id, chunks)
        existing = {
            item.id: item
            for item in self._load()
            if not (
                item.projectId == project_id
                and item.knowledgeBaseId == knowledge_base_id
                and item.documentId == document_id
            )
        }
        for chunk in chunks:
            existing[chunk.id] = chunk
        text = "\n".join(json.dumps(asdict(item), ensure_ascii=False) for item in existing.values())
        self.path.write_text(text + ("\n" if text else ""), encoding="utf-8")

    async def delete_sources(self, project_id: int, source_type: str, source_ids: list[str], exclude_knowledge_base_id: int | None) -> int:
        validate_delete_scope(project_id, source_type, source_ids, exclude_knowledge_base_id)
        source_filter = set(source_ids)
        records = self._load()
        kept = [item for item in records if not (
            item.projectId == project_id
            and item.sourceType == source_type
            and item.sourceId in source_filter
            and (exclude_knowledge_base_id is None or item.knowledgeBaseId != exclude_knowledge_base_id)
        )]
        deleted = len(records) - len(kept)
        text = "\n".join(json.dumps(asdict(item), ensure_ascii=False) for item in kept)
        self.path.write_text(text + ("\n" if text else ""), encoding="utf-8")
        return deleted

    async def search(self, query_embedding: list[float], project_id: int, knowledge_base_ids: list[int], top_k: int) -> list[tuple[ChunkRecord, float]]:
        validate_search_scope(project_id, knowledge_base_ids)
        results: list[tuple[ChunkRecord, float]] = []
        kb_filter = set(knowledge_base_ids)
        query_dimension = len(query_embedding)
        skipped_dimensions: dict[int, int] = {}
        for chunk in self._load():
            if chunk.projectId != project_id:
                continue
            if chunk.knowledgeBaseId not in kb_filter:
                continue
            chunk_dimension = len(chunk.embedding)
            if chunk_dimension != query_dimension:
                skipped_dimensions[chunk_dimension] = skipped_dimensions.get(chunk_dimension, 0) + 1
                continue
            results.append((chunk, cosine(query_embedding, chunk.embedding)))
        if skipped_dimensions:
            logger.warning(
                "skipped local RAG chunks with incompatible embedding dimensions: "
                "project_id=%s knowledge_base_ids=%s query_dimension=%s skipped=%s; reindex affected documents",
                project_id,
                knowledge_base_ids,
                query_dimension,
                skipped_dimensions,
            )
            if not results:
                raise RuntimeError(
                    "Local RAG index embedding dimension is incompatible with the current model; "
                    "reindex the selected knowledge base documents"
                )
        results.sort(key=lambda item: item[1], reverse=True)
        return results[:top_k]


class PgVectorStore:
    def __init__(self, dsn: str, table: str):
        self.dsn = dsn
        self.table = safe_identifier(table, "PGVECTOR_TABLE")

    def _connect(self):
        import psycopg
        if not self.dsn:
            raise RuntimeError("PGVECTOR_DSN is not configured")
        return psycopg.connect(self.dsn)

    def _ensure_table(self, cur, dimension: int) -> None:
        cur.execute("create extension if not exists vector")
        cur.execute(f"""
        create table if not exists {self.table} (
          id text primary key,
          chunk_id text not null,
          project_id bigint not null,
          knowledge_base_id bigint not null,
          document_id text not null,
          title text not null,
          content text not null,
          source_type text not null,
          source_id text null,
          metadata jsonb not null,
          embedding vector({dimension}) not null
        )
        """)
        cur.execute(f"alter table {self.table} add column if not exists chunk_id text")
        cur.execute(f"update {self.table} set chunk_id = id where chunk_id is null")
        cur.execute(f"alter table {self.table} alter column chunk_id set not null")

    def _upsert_chunks(self, cur, chunks: list[ChunkRecord]) -> None:
        for chunk in chunks:
            cur.execute(
                f"""
                insert into {self.table} (id, chunk_id, project_id, knowledge_base_id, document_id, title, content, source_type, source_id, metadata, embedding)
                values (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                on conflict (id) do update set chunk_id=excluded.chunk_id, project_id=excluded.project_id,
                  knowledge_base_id=excluded.knowledge_base_id, document_id=excluded.document_id,
                  title=excluded.title, content=excluded.content, source_type=excluded.source_type,
                  source_id=excluded.source_id, metadata=excluded.metadata, embedding=excluded.embedding
                """,
                (chunk.id, chunk.chunkId, chunk.projectId, chunk.knowledgeBaseId, chunk.documentId, chunk.title, chunk.content,
                 chunk.sourceType, chunk.sourceId, json.dumps(chunk.metadata, ensure_ascii=False), chunk.embedding),
            )

    async def upsert(self, chunks: list[ChunkRecord]) -> None:
        if not chunks:
            return
        with self._connect() as conn:
            with conn.cursor() as cur:
                self._ensure_table(cur, len(chunks[0].embedding))
                self._upsert_chunks(cur, chunks)
            conn.commit()

    async def replace_document(self, project_id: int, knowledge_base_id: int, document_id: str, chunks: list[ChunkRecord]) -> None:
        validate_document_scope(project_id, knowledge_base_id, document_id, chunks)
        with self._connect() as conn:
            with conn.cursor() as cur:
                if chunks:
                    self._ensure_table(cur, len(chunks[0].embedding))
                    self._upsert_chunks(cur, chunks)
                    cur.execute(
                        f"delete from {self.table} where project_id = %s and knowledge_base_id = %s and document_id = %s and not (id = any(%s))",
                        [project_id, knowledge_base_id, document_id, [chunk.id for chunk in chunks]],
                    )
                else:
                    cur.execute("select to_regclass(%s)", [self.table])
                    if cur.fetchone()[0] is None:
                        return
                    cur.execute(
                        f"delete from {self.table} where project_id = %s and knowledge_base_id = %s and document_id = %s",
                        [project_id, knowledge_base_id, document_id],
                    )
            conn.commit()

    async def delete_sources(self, project_id: int, source_type: str, source_ids: list[str], exclude_knowledge_base_id: int | None) -> int:
        validate_delete_scope(project_id, source_type, source_ids, exclude_knowledge_base_id)
        filters = ["project_id = %s", "source_type = %s", "source_id = any(%s)"]
        params: list[Any] = [project_id, source_type, source_ids]
        if exclude_knowledge_base_id is not None:
            filters.append("knowledge_base_id is distinct from %s")
            params.append(exclude_knowledge_base_id)
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(f"delete from {self.table} where " + " and ".join(filters), params)
                deleted = cur.rowcount
            conn.commit()
        return deleted

    async def search(self, query_embedding: list[float], project_id: int, knowledge_base_ids: list[int], top_k: int) -> list[tuple[ChunkRecord, float]]:
        validate_search_scope(project_id, knowledge_base_ids)
        params: list[Any] = [project_id, knowledge_base_ids]
        where = " where project_id = %s and knowledge_base_id = any(%s)"
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    f"select id, chunk_id, project_id, knowledge_base_id, document_id, title, content, source_type, source_id, metadata, embedding <=> %s::vector as distance from {self.table}{where} order by embedding <=> %s::vector limit %s",
                    [query_embedding] + params + [query_embedding, top_k],
                )
                rows = cur.fetchall()
        results = []
        for row in rows:
            record = ChunkRecord(
                id=row[0], chunkId=row[1], projectId=row[2], knowledgeBaseId=row[3], documentId=row[4], title=row[5], content=row[6],
                sourceType=row[7], sourceId=row[8], metadata=row[9] or {}, embedding=[]
            )
            distance = float(row[10])
            results.append((record, 1.0 - distance))
        return results


class MilvusVectorStore:
    def __init__(self, uri: str, token: str, collection: str):
        self.uri = uri
        self.token = token
        self.collection = collection

    async def upsert(self, chunks: list[ChunkRecord]) -> None:
        from pymilvus import MilvusClient, DataType
        if not chunks:
            return
        client = MilvusClient(uri=self.uri, token=self.token or None)
        if not client.has_collection(self.collection):
            schema = client.create_schema(auto_id=False, enable_dynamic_field=True)
            schema.add_field("id", DataType.VARCHAR, is_primary=True, max_length=128)
            schema.add_field("chunkId", DataType.VARCHAR, max_length=128)
            schema.add_field("embedding", DataType.FLOAT_VECTOR, dim=len(chunks[0].embedding))
            schema.add_field("projectId", DataType.INT64)
            schema.add_field("knowledgeBaseId", DataType.INT64)
            schema.add_field("documentId", DataType.VARCHAR, max_length=128)
            schema.add_field("title", DataType.VARCHAR, max_length=512)
            schema.add_field("content", DataType.VARCHAR, max_length=8192)
            schema.add_field("sourceType", DataType.VARCHAR, max_length=64)
            schema.add_field("sourceId", DataType.VARCHAR, max_length=128, nullable=True)
            client.create_collection(self.collection, schema=schema)
            index_params = client.prepare_index_params()
            index_params.add_index(
                field_name="embedding",
                index_type="AUTOINDEX",
                metric_type="COSINE",
            )
            client.create_index(collection_name=self.collection, index_params=index_params)
        data = [asdict(chunk) for chunk in chunks]
        client.upsert(collection_name=self.collection, data=data)
        client.flush(collection_name=self.collection)
        client.load_collection(collection_name=self.collection)

    async def replace_document(self, project_id: int, knowledge_base_id: int, document_id: str, chunks: list[ChunkRecord]) -> None:
        validate_document_scope(project_id, knowledge_base_id, document_id, chunks)
        if chunks:
            await self.upsert(chunks)
        from pymilvus import MilvusClient
        client = MilvusClient(uri=self.uri, token=self.token or None)
        if not client.has_collection(self.collection):
            return
        expr = (
            f"projectId == {project_id} and knowledgeBaseId == {knowledge_base_id} "
            f"and documentId == {json.dumps(document_id)}"
        )
        if chunks:
            quoted_ids = ",".join(json.dumps(chunk.id) for chunk in chunks)
            expr += f" and id not in [{quoted_ids}]"
        client.delete(collection_name=self.collection, filter=expr)

    async def delete_sources(self, project_id: int, source_type: str, source_ids: list[str], exclude_knowledge_base_id: int | None) -> int:
        validate_delete_scope(project_id, source_type, source_ids, exclude_knowledge_base_id)
        from pymilvus import MilvusClient
        if not source_ids:
            return 0
        client = MilvusClient(uri=self.uri, token=self.token or None)
        if not client.has_collection(self.collection):
            return 0
        quoted_ids = ",".join(json.dumps(value) for value in source_ids)
        expr = f'projectId == {project_id} and sourceType == {json.dumps(source_type)} and sourceId in [{quoted_ids}]'
        if exclude_knowledge_base_id is not None:
            expr += f" and knowledgeBaseId != {exclude_knowledge_base_id}"
        result = client.delete(collection_name=self.collection, filter=expr)
        return int((result or {}).get("delete_count", 0))

    async def search(self, query_embedding: list[float], project_id: int, knowledge_base_ids: list[int], top_k: int) -> list[tuple[ChunkRecord, float]]:
        validate_search_scope(project_id, knowledge_base_ids)
        from pymilvus import MilvusClient
        client = MilvusClient(uri=self.uri, token=self.token or None)
        client.load_collection(collection_name=self.collection)
        expr = f"projectId == {project_id} and knowledgeBaseId in [" + ",".join(str(x) for x in knowledge_base_ids) + "]"
        rows = client.search(collection_name=self.collection, data=[query_embedding], limit=top_k, filter=expr, output_fields=["*"])
        results = []
        for hit in rows[0]:
            entity = hit.get("entity", {})
            record = ChunkRecord(
                id=entity.get("id"), chunkId=entity.get("chunkId") or entity.get("id"), projectId=entity.get("projectId"), knowledgeBaseId=entity.get("knowledgeBaseId"),
                documentId=entity.get("documentId"), title=entity.get("title"), content=entity.get("content"),
                sourceType=entity.get("sourceType"), sourceId=entity.get("sourceId"), metadata=entity.get("metadata") or {}, embedding=[]
            )
            results.append((record, float(hit.get("distance", 0.0))))
        return results


def safe_identifier(value: str, name: str) -> str:
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", value or ""):
        raise RuntimeError(f"{name} must be a simple SQL identifier")
    return value


def validate_document_scope(project_id: int | None, knowledge_base_id: int | None, document_id: str | None, chunks: list[ChunkRecord]) -> None:
    if project_id is None or project_id <= 0:
        raise ValueError("project_id must be positive")
    if knowledge_base_id is None or knowledge_base_id <= 0:
        raise ValueError("knowledge_base_id must be positive")
    if not document_id or not document_id.strip():
        raise ValueError("document_id must not be blank")
    for chunk in chunks:
        if (
            chunk.projectId != project_id
            or chunk.knowledgeBaseId != knowledge_base_id
            or chunk.documentId != document_id
        ):
            raise ValueError("replacement chunks must match the document scope")


def validate_delete_scope(project_id: int | None, source_type: str | None, source_ids: list[str] | None, exclude_knowledge_base_id: int | None) -> None:
    if project_id is None or project_id <= 0:
        raise ValueError("project_id must be positive")
    if not source_type or not source_type.strip():
        raise ValueError("source_type must not be blank")
    if not source_ids:
        raise ValueError("source_ids must not be empty")
    if any(not value or not value.strip() for value in source_ids):
        raise ValueError("source_ids must not contain blank values")
    if exclude_knowledge_base_id is not None and exclude_knowledge_base_id <= 0:
        raise ValueError("exclude_knowledge_base_id must be positive")


def validate_search_scope(project_id: int | None, knowledge_base_ids: list[int] | None) -> None:
    if project_id is None or project_id <= 0:
        raise ValueError("project_id must be positive")
    if not knowledge_base_ids:
        raise ValueError("knowledge_base_ids must not be empty")
    if any(value is None or value <= 0 for value in knowledge_base_ids):
        raise ValueError("knowledge_base_ids must contain positive values")
