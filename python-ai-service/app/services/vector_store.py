from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import tempfile
import threading
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any, Awaitable, Callable, Protocol

import numpy as np


logger = logging.getLogger(__name__)

_LOCAL_MIGRATION_LOCKS: dict[Path, asyncio.Lock] = {}
_LOCAL_WRITE_LOCKS: dict[Path, threading.RLock] = {}
_LOCAL_LOCK_REGISTRY_GUARD = threading.Lock()
LEGACY_MIGRATION_BATCH_SIZE = 32
LEGACY_MIGRATION_MAX_CHUNKS_PER_SEARCH = 64


def local_store_locks(path: Path) -> tuple[asyncio.Lock, threading.RLock]:
    resolved = path.resolve()
    with _LOCAL_LOCK_REGISTRY_GUARD:
        migration_lock = _LOCAL_MIGRATION_LOCKS.setdefault(resolved, asyncio.Lock())
        write_lock = _LOCAL_WRITE_LOCKS.setdefault(resolved, threading.RLock())
    return migration_lock, write_lock


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
    async def search(self, query_embedding: list[float], project_id: int, knowledge_base_ids: list[int], top_k: int, document_scope: list[str] | None = None) -> list[tuple[ChunkRecord, float]]: ...
    async def search_text(self, query: str, project_id: int, knowledge_base_ids: list[int], top_k: int, document_scope: list[str] | None = None) -> list[tuple[ChunkRecord, float]]: ...
    async def adjacent(self, chunk: ChunkRecord, before: int = 1, after: int = 1, document_scope: list[str] | None = None) -> list[ChunkRecord]: ...
    async def adjacent_many(self, chunks: list[ChunkRecord], before: int = 1, after: int = 1, document_scope: list[str] | None = None) -> dict[str, list[ChunkRecord]]: ...
    async def delete_sources(self, project_id: int, source_type: str, source_ids: list[str], exclude_knowledge_base_id: int | None) -> int: ...


def cosine(a: list[float], b: list[float]) -> float:
    av = np.array(a, dtype=np.float32)
    bv = np.array(b, dtype=np.float32)
    denom = float(np.linalg.norm(av) * np.linalg.norm(bv))
    if denom == 0:
        return 0.0
    return float(np.dot(av, bv) / denom)


class LocalJsonVectorStore:
    def __init__(self, data_dir: str, reembed: Callable[[list[str]], Awaitable[list[list[float]]]] | None = None):
        self.path = Path(data_dir) / "chunks.jsonl"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.reembed = reembed
        self._migration_lock, self._write_lock = local_store_locks(self.path)

    def _load(self) -> list[ChunkRecord]:
        if not self.path.exists():
            return []
        records: list[ChunkRecord] = []
        for line in self.path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                records.append(ChunkRecord(**json.loads(line)))
        return records

    def _write(self, records: list[ChunkRecord]) -> None:
        text = "\n".join(json.dumps(asdict(item), ensure_ascii=False) for item in records)
        payload = text + ("\n" if text else "")
        fd, temporary_path = tempfile.mkstemp(prefix=f".{self.path.name}.", dir=self.path.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary_path, self.path)
            self._sync_parent_directory()
        except Exception:
            try:
                os.unlink(temporary_path)
            except FileNotFoundError:
                pass
            raise

    def _sync_parent_directory(self) -> None:
        if os.name != "posix":
            return
        directory_fd = os.open(self.path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)

    async def upsert(self, chunks: list[ChunkRecord]) -> None:
        with self._write_lock:
            existing = {item.id: item for item in self._load()}
            for chunk in chunks:
                existing[chunk.id] = chunk
            self._write(list(existing.values()))

    async def replace_document(self, project_id: int, knowledge_base_id: int, document_id: str, chunks: list[ChunkRecord]) -> None:
        validate_document_scope(project_id, knowledge_base_id, document_id, chunks)
        with self._write_lock:
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
            self._write(list(existing.values()))

    async def delete_sources(self, project_id: int, source_type: str, source_ids: list[str], exclude_knowledge_base_id: int | None) -> int:
        validate_delete_scope(project_id, source_type, source_ids, exclude_knowledge_base_id)
        source_filter = set(source_ids)
        with self._write_lock:
            records = self._load()
            kept = [item for item in records if not (
                item.projectId == project_id
                and item.sourceType == source_type
                and item.sourceId in source_filter
                and (exclude_knowledge_base_id is None or item.knowledgeBaseId != exclude_knowledge_base_id)
            )]
            deleted = len(records) - len(kept)
            self._write(kept)
            return deleted

    async def search(self, query_embedding: list[float], project_id: int, knowledge_base_ids: list[int], top_k: int, document_scope: list[str] | None = None) -> list[tuple[ChunkRecord, float]]:
        validate_search_scope(project_id, knowledge_base_ids)
        kb_filter = set(knowledge_base_ids)
        query_dimension = len(query_embedding)
        records = self._load()
        scoped = [
            chunk for chunk in records
            if chunk.projectId == project_id and chunk.knowledgeBaseId in kb_filter
            and (not document_scope or chunk.documentId in document_scope)
        ]
        skipped_dimensions = dimension_counts(scoped, query_dimension)
        if skipped_dimensions and self.reembed:
            logger.warning(
                "local RAG contains legacy embedding dimensions; attempting scoped migration: "
                "project_id=%s knowledge_base_ids=%s query_dimension=%s skipped=%s",
                project_id,
                knowledge_base_ids,
                query_dimension,
                skipped_dimensions,
            )
            async with self._migration_lock:
                migrated_total = 0
                while migrated_total < LEGACY_MIGRATION_MAX_CHUNKS_PER_SEARCH:
                    records = self._load()
                    selected_legacy = [
                        record for record in records
                        if record.projectId == project_id
                        and record.knowledgeBaseId in kb_filter
                        and len(record.embedding) != query_dimension
                        and (not document_scope or record.documentId in document_scope)
                    ][:LEGACY_MIGRATION_BATCH_SIZE]
                    if not selected_legacy:
                        break
                    try:
                        embeddings = await self.reembed([record.content for record in selected_legacy])
                        if len(embeddings) != len(selected_legacy) or any(
                            len(vector) != query_dimension for vector in embeddings
                        ):
                            raise RuntimeError("Legacy RAG migration returned an incompatible embedding dimension")
                    except Exception:
                        has_compatible = any(
                            record.projectId == project_id
                            and record.knowledgeBaseId in kb_filter
                            and len(record.embedding) == query_dimension
                            and (not document_scope or record.documentId in document_scope)
                            for record in records
                        )
                        if migrated_total == 0 and not has_compatible:
                            raise
                        logger.exception(
                            "legacy local RAG migration stopped; using compatible chunks: "
                            "project_id=%s knowledge_base_ids=%s migrated=%s",
                            project_id,
                            knowledge_base_ids,
                            migrated_total,
                        )
                        break
                    snapshots = {record.id: record.content for record in selected_legacy}
                    embeddings_by_id = {
                        record.id: embedding for record, embedding in zip(selected_legacy, embeddings)
                    }
                    migrated_count = 0
                    with self._write_lock:
                        current = self._load()
                        for record in current:
                            embedding = embeddings_by_id.get(record.id)
                            if (
                                embedding is not None
                                and snapshots.get(record.id) == record.content
                                and record.projectId == project_id
                                and record.knowledgeBaseId in kb_filter
                                and len(record.embedding) != query_dimension
                                and (not document_scope or record.documentId in document_scope)
                            ):
                                record.embedding = embedding
                                migrated_count += 1
                        if migrated_count:
                            self._write(current)
                    migrated_total += migrated_count
                    if migrated_count == 0:
                        break
                if migrated_total:
                    logger.info(
                        "migrated legacy local RAG chunks: project_id=%s knowledge_base_ids=%s chunks=%s",
                        project_id,
                        knowledge_base_ids,
                        migrated_total,
                    )
            records = self._load()
            scoped = [
                chunk for chunk in records
                if chunk.projectId == project_id and chunk.knowledgeBaseId in kb_filter
                and (not document_scope or chunk.documentId in document_scope)
            ]
            skipped_dimensions = dimension_counts(scoped, query_dimension)

        results = [
            (chunk, cosine(query_embedding, chunk.embedding))
            for chunk in scoped
            if len(chunk.embedding) == query_dimension
        ]
        if skipped_dimensions:
            logger.warning(
                "skipped local RAG chunks with incompatible embedding dimensions: "
                "project_id=%s knowledge_base_ids=%s query_dimension=%s skipped=%s",
                project_id,
                knowledge_base_ids,
                query_dimension,
                skipped_dimensions,
            )
            if not results:
                raise RuntimeError(
                    "Local RAG index embedding dimension is incompatible with the current model; "
                    "legacy document migration did not produce searchable chunks"
                )
        results.sort(key=lambda item: item[1], reverse=True)
        return results[:top_k]

    async def search_text(self, query: str, project_id: int, knowledge_base_ids: list[int], top_k: int, document_scope: list[str] | None = None) -> list[tuple[ChunkRecord, float]]:
        validate_search_scope(project_id, knowledge_base_ids)
        scored: list[tuple[ChunkRecord, float]] = []
        kb_filter = set(knowledge_base_ids)
        for record in self._load():
            if record.projectId != project_id or record.knowledgeBaseId not in kb_filter:
                continue
            if document_scope and record.documentId not in document_scope:
                continue
            score = text_match_score(query, record.content)
            if score > 0:
                scored.append((record, score))
        scored.sort(key=lambda item: item[1], reverse=True)
        return scored[:max(0, top_k)]

    async def adjacent(self, chunk: ChunkRecord, before: int = 1, after: int = 1, document_scope: list[str] | None = None) -> list[ChunkRecord]:
        return (await self.adjacent_many([chunk], before, after, document_scope)).get(chunk.id, [])

    async def adjacent_many(self, chunks: list[ChunkRecord], before: int = 1, after: int = 1, document_scope: list[str] | None = None) -> dict[str, list[ChunkRecord]]:
        if not chunks:
            return {}
        records = self._load()
        result: dict[str, list[ChunkRecord]] = {}
        for chunk in chunks:
            if document_scope and chunk.documentId not in document_scope:
                result[chunk.id] = []
                continue
            scoped = [record for record in records if same_document_scope(record, chunk)]
            result[chunk.id] = select_adjacent(scoped, chunk, before, after)
        return result


def dimension_counts(records: list[ChunkRecord], expected_dimension: int) -> dict[int, int]:
    counts: dict[int, int] = {}
    for record in records:
        dimension = len(record.embedding)
        if dimension != expected_dimension:
            counts[dimension] = counts.get(dimension, 0) + 1
    return counts


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

    async def search(self, query_embedding: list[float], project_id: int, knowledge_base_ids: list[int], top_k: int, document_scope: list[str] | None = None) -> list[tuple[ChunkRecord, float]]:
        validate_search_scope(project_id, knowledge_base_ids)
        params: list[Any] = [project_id, knowledge_base_ids]
        where = " where project_id = %s and knowledge_base_id = any(%s)"
        if document_scope:
            where += " and document_id = any(%s)"
            params.append(document_scope)
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

    async def adjacent(self, chunk: ChunkRecord, before: int = 1, after: int = 1, document_scope: list[str] | None = None) -> list[ChunkRecord]:
        return (await self.adjacent_many([chunk], before, after, document_scope)).get(chunk.id, [])

    async def adjacent_many(self, chunks: list[ChunkRecord], before: int = 1, after: int = 1, document_scope: list[str] | None = None) -> dict[str, list[ChunkRecord]]:
        if not chunks:
            return {}
        if document_scope:
            chunks = [chunk for chunk in chunks if chunk.documentId in document_scope]
        scopes = {(chunk.projectId, chunk.knowledgeBaseId, chunk.documentId) for chunk in chunks}
        records_by_scope: dict[tuple[int, int, str], list[ChunkRecord]] = {}
        with self._connect() as conn:
            with conn.cursor() as cur:
                for project_id, knowledge_base_id, document_id in scopes:
                    cur.execute(
                        f"select id, chunk_id, project_id, knowledge_base_id, document_id, title, content, source_type, source_id, metadata "
                        f"from {self.table} where project_id = %s and knowledge_base_id = %s and document_id = %s",
                        [project_id, knowledge_base_id, document_id],
                    )
                    records_by_scope[(project_id, knowledge_base_id, document_id)] = [
                        ChunkRecord(id=row[0], chunkId=row[1], projectId=row[2], knowledgeBaseId=row[3], documentId=row[4],
                                    title=row[5], content=row[6], sourceType=row[7], sourceId=row[8], metadata=row[9] or {}, embedding=[])
                        for row in cur.fetchall()
                    ]
        return {chunk.id: select_adjacent(records_by_scope.get((chunk.projectId, chunk.knowledgeBaseId, chunk.documentId), []), chunk, before, after) for chunk in chunks}


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

    async def search(self, query_embedding: list[float], project_id: int, knowledge_base_ids: list[int], top_k: int, document_scope: list[str] | None = None) -> list[tuple[ChunkRecord, float]]:
        validate_search_scope(project_id, knowledge_base_ids)
        from pymilvus import MilvusClient
        client = MilvusClient(uri=self.uri, token=self.token or None)
        client.load_collection(collection_name=self.collection)
        expr = f"projectId == {project_id} and knowledgeBaseId in [" + ",".join(str(x) for x in knowledge_base_ids) + "]"
        if document_scope:
            expr += " and documentId in [" + ",".join(json.dumps(value) for value in document_scope) + "]"
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

    async def adjacent(self, chunk: ChunkRecord, before: int = 1, after: int = 1, document_scope: list[str] | None = None) -> list[ChunkRecord]:
        return (await self.adjacent_many([chunk], before, after, document_scope)).get(chunk.id, [])

    async def adjacent_many(self, chunks: list[ChunkRecord], before: int = 1, after: int = 1, document_scope: list[str] | None = None) -> dict[str, list[ChunkRecord]]:
        from pymilvus import MilvusClient
        if not chunks:
            return {}
        if document_scope:
            chunks = [chunk for chunk in chunks if chunk.documentId in document_scope]
        client = MilvusClient(uri=self.uri, token=self.token or None)
        if not client.has_collection(self.collection):
            return {chunk.id: [] for chunk in chunks}
        scopes = {(chunk.projectId, chunk.knowledgeBaseId, chunk.documentId) for chunk in chunks}
        records_by_scope: dict[tuple[int, int, str], list[ChunkRecord]] = {}
        for project_id, knowledge_base_id, document_id in scopes:
            expr = (f"projectId == {project_id} and knowledgeBaseId == {knowledge_base_id} "
                    f"and documentId == {json.dumps(document_id)}")
            rows: list[dict[str, Any]] = []
            # Query until exhaustion so long documents do not lose neighbors past the first page.
            iterator = getattr(client, "query_iterator", None)
            if iterator is not None:
                handle = iterator(collection_name=self.collection, filter=expr, output_fields=["*"], batch_size=256)
                try:
                    while True:
                        page = handle.next()
                        if not page:
                            break
                        rows.extend(page)
                finally:
                    close = getattr(handle, "close", None)
                    if close:
                        close()
            else:
                offset = 0
                while True:
                    page = client.query(collection_name=self.collection, filter=expr, output_fields=["*"], limit=256, offset=offset)
                    if not page:
                        break
                    rows.extend(page)
                    if len(page) < 256:
                        break
                    offset += len(page)
            records_by_scope[(project_id, knowledge_base_id, document_id)] = [
                ChunkRecord(id=row.get("id"), chunkId=row.get("chunkId") or row.get("id"), projectId=row.get("projectId"),
                            knowledgeBaseId=row.get("knowledgeBaseId"), documentId=row.get("documentId"), title=row.get("title"),
                            content=row.get("content"), sourceType=row.get("sourceType"), sourceId=row.get("sourceId"),
                            metadata=row.get("metadata") or {}, embedding=[])
                for row in rows
            ]
        return {chunk.id: select_adjacent(records_by_scope.get((chunk.projectId, chunk.knowledgeBaseId, chunk.documentId), []), chunk, before, after) for chunk in chunks}



def compact_search_text(value: str) -> str:
    return re.sub(r"[^0-9a-zA-Z\u4e00-\u9fff.]", "", value or "").lower()


def iter_bigrams(value: str):
    for index in range(len(value) - 1):
        yield value[index:index + 2]


def extract_clause_numbers(value: str) -> set[str]:
    """Extract dotted and article-style clause identifiers without substring collisions."""
    text = value or ""
    clauses = set(re.findall(r"(?<![\d.])(\d+(?:\.\d+)+)(?![\d.])", text))
    clauses.update(re.findall(r"第\s*(\d+)\s*条", text))
    for chinese in re.findall(r"第\s*([零〇一二两三四五六七八九十百千万]+)\s*条", text):
        number = chinese_numeral_to_int(chinese)
        if number is not None:
            clauses.add(str(number))
    return clauses


def chinese_numeral_to_int(value: str) -> int | None:
    digits = {"零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
              "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}
    if value.isdigit():
        return int(value)
    if not value or any(char not in digits and char not in {"十", "百", "千", "万"} for char in value):
        return None
    total = 0
    section = 0
    number = 0
    units = {"十": 10, "百": 100, "千": 1000, "万": 10000}
    for char in value:
        if char in digits:
            number = digits[char]
        else:
            unit = units[char]
            if unit == 10000:
                section = (section + number) * unit
                total += section
                section = 0
            else:
                section += (number or 1) * unit
            number = 0
    return total + section + number


def text_match_score(query: str, content: str) -> float:
    query_compact = compact_search_text(query)
    content_compact = compact_search_text(content)
    if not query_compact or not content_compact:
        return 0.0
    query_bigrams = set(iter_bigrams(query_compact))
    overlap = sum(1 for bigram in query_bigrams if bigram in content_compact) / max(len(query_bigrams), 1)
    clauses = extract_clause_numbers(query)
    content_clauses = extract_clause_numbers(content_compact)
    clause_hits = len(clauses & content_clauses)
    return overlap + clause_hits * 2.0


def same_document_scope(left: ChunkRecord, right: ChunkRecord) -> bool:
    return (
        left.projectId == right.projectId
        and left.knowledgeBaseId == right.knowledgeBaseId
        and left.documentId == right.documentId
    )


def chunk_order(record: ChunkRecord) -> tuple[int, int] | None:
    unit_index = record.metadata.get("unitIndex")
    chunk_index = record.metadata.get("chunkIndex")
    if not isinstance(unit_index, int) or not isinstance(chunk_index, int):
        return None
    return unit_index, chunk_index


def select_adjacent(
    records: list[ChunkRecord],
    target: ChunkRecord,
    before: int,
    after: int,
) -> list[ChunkRecord]:
    target_order = chunk_order(target)
    if target_order is None:
        return []
    ordered = sorted(
        (record for record in records if chunk_order(record) is not None),
        key=lambda record: chunk_order(record),
    )
    target_index = next((index for index, record in enumerate(ordered) if record.id == target.id), None)
    if target_index is None:
        return []
    start = max(0, target_index - max(0, before))
    end = min(len(ordered), target_index + max(0, after) + 1)
    neighbors: list[ChunkRecord] = []
    for record in ordered[start:end]:
        if record.id == target.id:
            continue
        record_order = chunk_order(record)
        if record_order is not None and are_adjacent_chunks(target_order, record_order):
            neighbors.append(record)
    return neighbors


def are_adjacent_chunks(left: tuple[int, int], right: tuple[int, int]) -> bool:
    """Only join contiguous parser units; do not bridge missing blocks."""
    left_unit, left_chunk = left
    right_unit, right_chunk = right
    if left_unit == right_unit:
        return abs(left_chunk - right_chunk) == 1
    if abs(left_unit - right_unit) != 1:
        return False
    if left_unit < right_unit:
        return right_chunk == 0
    return left_chunk == 0


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
