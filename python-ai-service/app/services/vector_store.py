from __future__ import annotations

import json
import re
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any, Protocol

import numpy as np


@dataclass
class ChunkRecord:
    id: str
    projectId: int
    knowledgeBaseId: int | None
    documentId: str
    title: str
    content: str
    sourceType: str
    sourceId: str | None
    metadata: dict[str, Any]
    embedding: list[float]


class VectorStore(Protocol):
    async def upsert(self, chunks: list[ChunkRecord]) -> None: ...
    async def search(self, query_embedding: list[float], project_id: int | None, knowledge_base_ids: list[int], top_k: int) -> list[tuple[ChunkRecord, float]]: ...
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

    async def delete_sources(self, project_id: int, source_type: str, source_ids: list[str], exclude_knowledge_base_id: int | None) -> int:
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

    async def search(self, query_embedding: list[float], project_id: int | None, knowledge_base_ids: list[int], top_k: int) -> list[tuple[ChunkRecord, float]]:
        results: list[tuple[ChunkRecord, float]] = []
        kb_filter = set(knowledge_base_ids or [])
        for chunk in self._load():
            if project_id is not None and chunk.projectId != project_id:
                continue
            if kb_filter and chunk.knowledgeBaseId not in kb_filter:
                continue
            results.append((chunk, cosine(query_embedding, chunk.embedding)))
        results.sort(key=lambda item: item[1], reverse=True)
        return results[:top_k]


class PgVectorStore:
    def __init__(self, dsn: str, table: str):
        self.dsn = dsn
        self.table = safe_identifier(table, "PGVECTOR_TABLE")

    async def upsert(self, chunks: list[ChunkRecord]) -> None:
        import psycopg
        if not self.dsn:
            raise RuntimeError("PGVECTOR_DSN is not configured")
        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute("create extension if not exists vector")
                if chunks:
                    dim = len(chunks[0].embedding)
                    cur.execute(f"""
                    create table if not exists {self.table} (
                      id text primary key,
                      project_id bigint not null,
                      knowledge_base_id bigint null,
                      document_id text not null,
                      title text not null,
                      content text not null,
                      source_type text not null,
                      source_id text null,
                      metadata jsonb not null,
                      embedding vector({dim}) not null
                    )
                    """)
                for chunk in chunks:
                    cur.execute(
                        f"""
                        insert into {self.table} (id, project_id, knowledge_base_id, document_id, title, content, source_type, source_id, metadata, embedding)
                        values (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                        on conflict (id) do update set title=excluded.title, content=excluded.content, metadata=excluded.metadata, embedding=excluded.embedding
                        """,
                        (chunk.id, chunk.projectId, chunk.knowledgeBaseId, chunk.documentId, chunk.title, chunk.content,
                         chunk.sourceType, chunk.sourceId, json.dumps(chunk.metadata, ensure_ascii=False), chunk.embedding),
                    )
            conn.commit()

    async def delete_sources(self, project_id: int, source_type: str, source_ids: list[str], exclude_knowledge_base_id: int | None) -> int:
        import psycopg
        if not self.dsn:
            raise RuntimeError("PGVECTOR_DSN is not configured")
        if not source_ids:
            return 0
        filters = ["project_id = %s", "source_type = %s", "source_id = any(%s)"]
        params: list[Any] = [project_id, source_type, source_ids]
        if exclude_knowledge_base_id is not None:
            filters.append("knowledge_base_id is distinct from %s")
            params.append(exclude_knowledge_base_id)
        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(f"delete from {self.table} where " + " and ".join(filters), params)
                deleted = cur.rowcount
            conn.commit()
        return deleted

    async def search(self, query_embedding: list[float], project_id: int | None, knowledge_base_ids: list[int], top_k: int) -> list[tuple[ChunkRecord, float]]:
        import psycopg
        if not self.dsn:
            raise RuntimeError("PGVECTOR_DSN is not configured")
        filters = []
        params: list[Any] = []
        if project_id is not None:
            filters.append("project_id = %s")
            params.append(project_id)
        if knowledge_base_ids:
            filters.append("knowledge_base_id = any(%s)")
            params.append(knowledge_base_ids)
        where = " where " + " and ".join(filters) if filters else ""
        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    f"select id, project_id, knowledge_base_id, document_id, title, content, source_type, source_id, metadata, embedding <=> %s::vector as distance from {self.table}{where} order by embedding <=> %s::vector limit %s",
                    [query_embedding] + params + [query_embedding, top_k],
                )
                rows = cur.fetchall()
        results = []
        for row in rows:
            record = ChunkRecord(
                id=row[0], projectId=row[1], knowledgeBaseId=row[2], documentId=row[3], title=row[4], content=row[5],
                sourceType=row[6], sourceId=row[7], metadata=row[8] or {}, embedding=[]
            )
            distance = float(row[9])
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
            schema.add_field("embedding", DataType.FLOAT_VECTOR, dim=len(chunks[0].embedding))
            schema.add_field("projectId", DataType.INT64)
            schema.add_field("knowledgeBaseId", DataType.INT64, nullable=True)
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

    async def delete_sources(self, project_id: int, source_type: str, source_ids: list[str], exclude_knowledge_base_id: int | None) -> int:
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

    async def search(self, query_embedding: list[float], project_id: int | None, knowledge_base_ids: list[int], top_k: int) -> list[tuple[ChunkRecord, float]]:
        from pymilvus import MilvusClient
        client = MilvusClient(uri=self.uri, token=self.token or None)
        client.load_collection(collection_name=self.collection)
        expr_parts = []
        if project_id is not None:
            expr_parts.append(f"projectId == {project_id}")
        if knowledge_base_ids:
            expr_parts.append("knowledgeBaseId in [" + ",".join(str(x) for x in knowledge_base_ids) + "]")
        expr = " and ".join(expr_parts) if expr_parts else ""
        rows = client.search(collection_name=self.collection, data=[query_embedding], limit=top_k, filter=expr, output_fields=["*"])
        results = []
        for hit in rows[0]:
            entity = hit.get("entity", {})
            record = ChunkRecord(
                id=entity.get("id"), projectId=entity.get("projectId"), knowledgeBaseId=entity.get("knowledgeBaseId"),
                documentId=entity.get("documentId"), title=entity.get("title"), content=entity.get("content"),
                sourceType=entity.get("sourceType"), sourceId=entity.get("sourceId"), metadata=entity.get("metadata") or {}, embedding=[]
            )
            results.append((record, float(hit.get("distance", 0.0))))
        return results


def safe_identifier(value: str, name: str) -> str:
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", value or ""):
        raise RuntimeError(f"{name} must be a simple SQL identifier")
    return value
