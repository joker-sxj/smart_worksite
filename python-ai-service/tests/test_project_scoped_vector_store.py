import asyncio
import sys
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.services import vector_store
from app.services.vector_store import ChunkRecord, LocalJsonVectorStore, MilvusVectorStore, PgVectorStore


def chunk(
    chunk_id: str,
    *,
    project_id: int = 1,
    knowledge_base_id: int = 10,
    document_id: str = "doc-1",
    source_id: str = "file-1",
) -> ChunkRecord:
    return ChunkRecord(
        id=chunk_id,
        chunkId=chunk_id,
        projectId=project_id,
        knowledgeBaseId=knowledge_base_id,
        documentId=document_id,
        title=f"title-{chunk_id}",
        content=f"content-{chunk_id}",
        sourceType="DOCUMENT",
        sourceId=source_id,
        metadata={"fileId": source_id, "page": 1},
        embedding=[1.0, 0.0],
    )


def test_rag_search_rejects_missing_project_scope():
    response = TestClient(app).post(
        "/v1/rag/search",
        json={"query": "helmet", "knowledgeBaseIds": [10]},
    )

    assert response.status_code == 422


def test_rag_search_rejects_empty_knowledge_base_scope():
    response = TestClient(app).post(
        "/v1/rag/search",
        json={"projectId": 1, "query": "helmet", "knowledgeBaseIds": []},
    )

    assert response.status_code == 422


def test_rag_index_requires_knowledge_base_scope():
    response = TestClient(app).post(
        "/v1/rag/index",
        json={
            "projectId": 1,
            "documents": [{
                "documentId": "doc-1",
                "title": "Safety",
                "content": "Wear a helmet",
                "sourceType": "DOCUMENT",
            }],
        },
    )

    assert response.status_code == 422


def test_local_vector_search_is_scoped_by_project_and_knowledge_base(tmp_path):
    store = LocalJsonVectorStore(str(tmp_path))
    asyncio.run(store.upsert([
        chunk("wanted"),
        chunk("other-kb", knowledge_base_id=11),
        chunk("other-project", project_id=2),
    ]))

    results = asyncio.run(store.search([1.0, 0.0], 1, [10], 10))

    assert [record.chunkId for record, _ in results] == ["wanted"]


def test_local_vector_search_rejects_unscoped_calls(tmp_path):
    store = LocalJsonVectorStore(str(tmp_path))

    with pytest.raises(ValueError, match="project_id"):
        asyncio.run(store.search([1.0, 0.0], None, [10], 10))
    with pytest.raises(ValueError, match="knowledge_base_ids"):
        asyncio.run(store.search([1.0, 0.0], 1, [], 10))


def test_chunk_record_requires_complete_identity():
    with pytest.raises(ValueError, match="chunkId"):
        chunk("")
    with pytest.raises(ValueError, match="knowledgeBaseId"):
        chunk("missing-kb", knowledge_base_id=None)
    with pytest.raises(ValueError, match="documentId"):
        chunk("missing-document", document_id="")


def test_local_delete_cannot_remove_same_source_from_another_project(tmp_path):
    store = LocalJsonVectorStore(str(tmp_path))
    asyncio.run(store.upsert([
        chunk("project-one", project_id=1, source_id="shared"),
        chunk("project-two", project_id=2, source_id="shared"),
    ]))

    deleted = asyncio.run(store.delete_sources(1, "DOCUMENT", ["shared"], None))

    assert deleted == 1
    assert [record.chunkId for record in store._load()] == ["project-two"]


def test_chunk_record_rejects_conflicting_chunk_identity():
    with pytest.raises(ValueError, match="chunkId must equal id"):
        ChunkRecord(
            id="canonical",
            chunkId="different",
            projectId=1,
            knowledgeBaseId=10,
            documentId="doc-1",
            title="title",
            content="content",
            sourceType="DOCUMENT",
            sourceId="file-1",
            metadata={},
            embedding=[1.0, 0.0],
        )


def test_local_store_reads_legacy_records_without_chunk_id(tmp_path):
    store = LocalJsonVectorStore(str(tmp_path))
    store.path.write_text(
        '{"id":"legacy","projectId":1,"knowledgeBaseId":10,"documentId":"doc-1",'
        '"title":"legacy","content":"content","sourceType":"DOCUMENT","sourceId":"file-1",'
        '"metadata":{},"embedding":[1.0,0.0]}\n',
        encoding="utf-8",
    )

    records = store._load()

    assert records[0].chunkId == "legacy"


def test_pgvector_search_binds_project_and_knowledge_base_scope(monkeypatch):
    calls = []

    class Cursor:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def execute(self, sql, params):
            calls.append((" ".join(sql.split()), params))

        def fetchall(self):
            return []

    class Connection:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def cursor(self):
            return Cursor()

    monkeypatch.setitem(sys.modules, "psycopg", SimpleNamespace(connect=lambda _, **__: Connection()))
    store = PgVectorStore("postgresql://local", "chunks")

    asyncio.run(store.search([1.0, 0.0], 7, [10, 11], 5))

    sql, params = calls[1]
    assert "project_id = %s" in sql
    assert "knowledge_base_id = any(%s)" in sql
    assert params == [[1.0, 0.0], 7, [10, 11], [1.0, 0.0], 5]


def test_pgvector_search_sets_server_timeout_below_round_deadline(monkeypatch):
    calls = []
    class Cursor:
        def __enter__(self): return self
        def __exit__(self, *_): return False
        def execute(self, sql, params=None): calls.append((" ".join(sql.split()), params))
        def fetchall(self): return []
    class Connection:
        def __enter__(self): return self
        def __exit__(self, *_): return False
        def cursor(self): return Cursor()
    connect_calls = []
    monkeypatch.setitem(sys.modules, "psycopg", SimpleNamespace(
        connect=lambda dsn, **kwargs: connect_calls.append((dsn, kwargs)) or Connection()
    ))

    asyncio.run(PgVectorStore("postgresql://local", "chunks", timeout_seconds=25).search(
        [1.0, 0.0], 7, [10], 5,
    ))

    assert 0 < connect_calls[0][1]["connect_timeout"] <= 25
    assert 0 < calls[0][1][0] <= 25000


def test_pgvector_uses_each_calls_remaining_timeout(monkeypatch):
    calls = []
    class Cursor:
        def __enter__(self): return self
        def __exit__(self, *_): return False
        def execute(self, sql, params=None): calls.append((" ".join(sql.split()), params))
        def fetchall(self): return []
    class Connection:
        def __enter__(self): return self
        def __exit__(self, *_): return False
        def cursor(self): return Cursor()
    monkeypatch.setitem(sys.modules, "psycopg", SimpleNamespace(connect=lambda *_args, **_kwargs: Connection()))
    store = PgVectorStore("postgresql://local", "chunks", timeout_seconds=25)

    asyncio.run(store.search([1.0], 7, [10], 1, timeout_seconds=7.5))

    assert 0 < calls[0][1][0] <= 7500


def test_pgvector_library_types_filter_vector_text_and_adjacent_sql(monkeypatch):
    calls = []
    class Cursor:
        def __enter__(self): return self
        def __exit__(self, *_): return False
        def execute(self, sql, params=None): calls.append((" ".join(sql.split()), params))
        def fetchall(self): return []
    class Connection:
        def __enter__(self): return self
        def __exit__(self, *_): return False
        def cursor(self): return Cursor()
    monkeypatch.setitem(sys.modules, "psycopg", SimpleNamespace(connect=lambda *_args, **_kwargs: Connection()))
    store = PgVectorStore("postgresql://local", "chunks")

    asyncio.run(store.search([1.0], 7, [10], 1, library_types=["POLICY"]))
    asyncio.run(store.search_text("helmet", 7, [10], 1, library_types=["POLICY"]))

    sql = " ".join(item[0] for item in calls)
    assert sql.count("metadata->>'libraryType'") >= 2


def test_pgvector_delete_binds_project_scope(monkeypatch):
    calls = []

    class Cursor:
        rowcount = 1

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def execute(self, sql, params):
            calls.append((" ".join(sql.split()), params))

    class Connection:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def cursor(self):
            return Cursor()

        def commit(self):
            pass

    monkeypatch.setitem(sys.modules, "psycopg", SimpleNamespace(connect=lambda _, **__: Connection()))
    store = PgVectorStore("postgresql://local", "chunks")

    deleted = asyncio.run(store.delete_sources(7, "DOCUMENT", ["shared"], None))

    assert deleted == 1
    sql, params = calls[0]
    assert "project_id = %s" in sql
    assert params[0] == 7


def test_milvus_search_and_delete_apply_project_scope(monkeypatch):
    calls = []

    class Client:
        def __init__(self, **_):
            pass

        def load_collection(self, **kwargs):
            calls.append(("load", kwargs))

        def search(self, **kwargs):
            calls.append(("search", kwargs))
            return [[]]

        def has_collection(self, _):
            return True

        def delete(self, **kwargs):
            calls.append(("delete", kwargs))
            return {"delete_count": 1}

    monkeypatch.setitem(sys.modules, "pymilvus", SimpleNamespace(MilvusClient=Client))
    store = MilvusVectorStore("http://milvus:19530", "", "chunks")

    asyncio.run(store.search([1.0, 0.0], 7, [10, 11], 5))
    deleted = asyncio.run(store.delete_sources(7, "DOCUMENT", ["shared"], None))

    search_filter = next(value[1]["filter"] for value in calls if value[0] == "search")
    delete_filter = next(value[1]["filter"] for value in calls if value[0] == "delete")
    assert search_filter == "projectId == 7 and knowledgeBaseId in [10,11]"
    assert "projectId == 7" in delete_filter
    assert deleted == 1


def test_milvus_retrieval_calls_receive_client_timeout(monkeypatch):
    calls = []
    class Client:
        def __init__(self, **_): pass
        def load_collection(self, **kwargs): calls.append(("load", kwargs))
        def search(self, **kwargs): calls.append(("search", kwargs)); return [[]]
    monkeypatch.setitem(sys.modules, "pymilvus", SimpleNamespace(MilvusClient=Client))

    asyncio.run(MilvusVectorStore(
        "http://milvus:19530", "", "chunks", timeout_seconds=25,
    ).search([1.0, 0.0], 7, [10], 5))

    assert 0 < next(kwargs for name, kwargs in calls if name == "load")["timeout"] <= 25
    assert 0 < next(kwargs for name, kwargs in calls if name == "search")["timeout"] <= 25


def test_milvus_uses_each_calls_remaining_timeout(monkeypatch):
    calls = []
    class Client:
        def __init__(self, **_): pass
        def load_collection(self, **kwargs): calls.append(("load", kwargs))
        def search(self, **kwargs): calls.append(("search", kwargs)); return [[]]
    monkeypatch.setitem(sys.modules, "pymilvus", SimpleNamespace(MilvusClient=Client))

    asyncio.run(MilvusVectorStore("http://milvus", "", "chunks", 25).search(
        [1.0], 7, [10], 1, timeout_seconds=6.25,
    ))

    timeouts = [kwargs["timeout"] for _, kwargs in calls]
    assert 0 < timeouts[1] < timeouts[0] <= 6.25


def test_milvus_library_types_filter_vector_and_text(monkeypatch):
    calls = []
    class Client:
        def __init__(self, **_): pass
        def load_collection(self, **_kwargs): pass
        def search(self, **kwargs): calls.append(kwargs); return [[]]
        def query(self, **kwargs): calls.append(kwargs); return []
    monkeypatch.setitem(sys.modules, "pymilvus", SimpleNamespace(MilvusClient=Client))
    store = MilvusVectorStore("http://milvus", "", "chunks")

    asyncio.run(store.search([1.0], 7, [10], 1, library_types=["POLICY"]))
    asyncio.run(store.search_text("helmet", 7, [10], 1, library_types=["POLICY"]))

    assert all('metadata["libraryType"]' in item["filter"] for item in calls)


def test_vector_store_deletes_reject_missing_project_scope(tmp_path, monkeypatch):
    local = LocalJsonVectorStore(str(tmp_path))
    with pytest.raises(ValueError, match="project_id"):
        asyncio.run(local.delete_sources(0, "DOCUMENT", ["source"], None))

    pg = PgVectorStore("postgresql://local", "chunks")
    with pytest.raises(ValueError, match="project_id"):
        asyncio.run(pg.delete_sources(0, "DOCUMENT", ["source"], None))

    milvus = MilvusVectorStore("http://milvus:19530", "", "chunks")
    with pytest.raises(ValueError, match="project_id"):
        asyncio.run(milvus.delete_sources(0, "DOCUMENT", ["source"], None))

def test_rag_delete_rejects_invalid_scope_before_store_call():
    from app.models.schemas import RagDeleteRequest

    with pytest.raises(Exception):
        RagDeleteRequest(projectId=0, sourceType="DOCUMENT", sourceIds=["source"])
    with pytest.raises(Exception):
        RagDeleteRequest(projectId=1, sourceType="DOCUMENT", sourceIds=[])

def test_pgvector_search_validates_scope_before_connection_configuration():
    store = PgVectorStore("", "chunks")
    with pytest.raises(ValueError, match="project_id"):
        asyncio.run(store.search([1.0, 0.0], 0, [10], 5))

def test_milvus_new_collection_requires_knowledge_base_identity(monkeypatch):
    fields = []

    class Schema:
        def add_field(self, name, _type, **kwargs):
            fields.append((name, kwargs))

    class IndexParams:
        def add_index(self, **_):
            pass

    class Client:
        def __init__(self, **_):
            pass

        def has_collection(self, _):
            return False

        def create_schema(self, **_):
            return Schema()

        def create_collection(self, *_, **__):
            pass

        def prepare_index_params(self):
            return IndexParams()

        def create_index(self, **_):
            pass

        def upsert(self, **_):
            pass

        def flush(self, **_):
            pass

        def load_collection(self, **_):
            pass

    data_type = SimpleNamespace(VARCHAR="VARCHAR", FLOAT_VECTOR="FLOAT_VECTOR", INT64="INT64")
    monkeypatch.setitem(sys.modules, "pymilvus", SimpleNamespace(MilvusClient=Client, DataType=data_type))
    store = MilvusVectorStore("http://milvus:19530", "", "chunks")

    asyncio.run(store.upsert([chunk("strict-kb")]))

    knowledge_base = next(kwargs for name, kwargs in fields if name == "knowledgeBaseId")
    assert knowledge_base.get("nullable", False) is False


def test_pgvector_new_table_requires_knowledge_base_identity(monkeypatch):
    calls = []

    class Cursor:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def execute(self, sql, params=None):
            calls.append((" ".join(sql.split()), params))

    class Connection:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def cursor(self):
            return Cursor()

        def commit(self):
            pass

    monkeypatch.setitem(sys.modules, "psycopg", SimpleNamespace(connect=lambda _, **__: Connection()))
    store = PgVectorStore("postgresql://local", "chunks")

    asyncio.run(store.upsert([chunk("strict-kb")]))

    create_table = next(sql for sql, _ in calls if "create table if not exists" in sql)
    assert "knowledge_base_id bigint not null" in create_table


def test_pgvector_replace_document_deletes_only_obsolete_chunks_in_exact_scope(monkeypatch):
    calls = []

    class Cursor:
        rowcount = 1

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def execute(self, sql, params=None):
            calls.append((" ".join(sql.split()), params))

    class Connection:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def cursor(self):
            return Cursor()

        def commit(self):
            pass

    monkeypatch.setitem(sys.modules, "psycopg", SimpleNamespace(connect=lambda _, **__: Connection()))
    store = PgVectorStore("postgresql://local", "chunks")
    replacement = chunk("current", project_id=7, knowledge_base_id=10, document_id="doc-1")

    asyncio.run(store.replace_document(7, 10, "doc-1", [replacement]))

    delete_sql, delete_params = next((sql, params) for sql, params in calls if sql.startswith("delete from"))
    assert "project_id = %s" in delete_sql
    assert "knowledge_base_id = %s" in delete_sql
    assert "document_id = %s" in delete_sql
    assert "not (id = any(%s))" in delete_sql
    assert delete_params == [7, 10, "doc-1", ["current"]]


def test_milvus_replace_document_deletes_only_obsolete_chunks_in_exact_scope(monkeypatch):
    calls = []

    class Client:
        def __init__(self, **_):
            pass

        def has_collection(self, _):
            return True

        def upsert(self, **kwargs):
            calls.append(("upsert", kwargs))

        def flush(self, **_):
            pass

        def load_collection(self, **_):
            pass

        def delete(self, **kwargs):
            calls.append(("delete", kwargs))
            return {"delete_count": 1}

    monkeypatch.setitem(
        sys.modules,
        "pymilvus",
        SimpleNamespace(MilvusClient=Client, DataType=SimpleNamespace()),
    )
    store = MilvusVectorStore("http://milvus:19530", "", "chunks")
    replacement = chunk("current", project_id=7, knowledge_base_id=10, document_id="doc-1")

    asyncio.run(store.replace_document(7, 10, "doc-1", [replacement]))

    delete_filter = next(kwargs["filter"] for action, kwargs in calls if action == "delete")
    assert delete_filter == (
        'projectId == 7 and knowledgeBaseId == 10 and documentId == "doc-1" '
        'and id not in ["current"]'
    )


def test_replace_document_rejects_chunks_from_another_scope(tmp_path):
    store = LocalJsonVectorStore(str(tmp_path))

    with pytest.raises(ValueError, match="must match the document scope"):
        asyncio.run(store.replace_document(1, 10, "doc-1", [chunk("foreign", project_id=2)]))


def test_pgvector_empty_document_replacement_is_safe_before_table_creation(monkeypatch):
    calls = []

    class Cursor:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def execute(self, sql, params=None):
            calls.append((" ".join(sql.split()), params))

        def fetchone(self):
            return (None,)

    class Connection:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def cursor(self):
            return Cursor()

        def commit(self):
            pass

    monkeypatch.setitem(sys.modules, "psycopg", SimpleNamespace(connect=lambda _, **__: Connection()))
    store = PgVectorStore("postgresql://local", "chunks")

    asyncio.run(store.replace_document(7, 10, "doc-empty", []))

    assert calls == [("select to_regclass(%s)", ["chunks"])]


def test_rag_index_rejects_blank_or_duplicate_document_identity():
    client = TestClient(app)
    base = {"projectId": 1, "knowledgeBaseId": 10}

    blank = client.post("/v1/rag/index", json={
        **base,
        "documents": [{"documentId": " ", "title": "Safety", "content": "helmet"}],
    })
    duplicate = client.post("/v1/rag/index", json={
        **base,
        "documents": [
            {"documentId": "doc-1", "title": "Safety", "content": "first"},
            {"documentId": "doc-1", "title": "Safety", "content": "second"},
        ],
    })
    empty = client.post("/v1/rag/index", json={**base, "documents": []})

    assert blank.status_code == 422
    assert duplicate.status_code == 422
    assert empty.status_code == 422


def test_local_vector_search_skips_records_from_an_old_embedding_dimension(tmp_path):
    store = LocalJsonVectorStore(str(tmp_path))
    compatible = chunk("compatible")
    incompatible = chunk("old-dimension")
    incompatible.embedding = [1.0, 0.0, 0.0]
    asyncio.run(store.upsert([compatible, incompatible]))

    results = asyncio.run(store.search([1.0, 0.0], 1, [10], 10))

    assert [record.chunkId for record, _ in results] == ["compatible"]


def test_local_vector_search_reembeds_scoped_records_from_an_old_dimension(tmp_path):
    async def reembed(texts):
        return [[1.0, 0.0] for _ in texts]

    store = LocalJsonVectorStore(str(tmp_path), reembed=reembed)
    incompatible = chunk("old-dimension")
    incompatible.embedding = [1.0, 0.0, 0.0]
    asyncio.run(store.upsert([incompatible]))

    results = asyncio.run(store.search([1.0, 0.0], 1, [10], 10))

    assert [record.chunkId for record, _ in results] == ["old-dimension"]
    assert store._load()[0].embedding == [1.0, 0.0]


def test_local_vector_search_only_reembeds_incompatible_records_in_selected_scope(tmp_path):
    calls = []

    async def reembed(texts):
        calls.append(texts)
        return [[1.0, 0.0] for _ in texts]

    store = LocalJsonVectorStore(str(tmp_path), reembed=reembed)
    selected = chunk("selected-old")
    selected.embedding = [1.0, 0.0, 0.0]
    other_project = chunk("other-project-old", project_id=2)
    other_project.embedding = [1.0, 0.0, 0.0]
    asyncio.run(store.upsert([selected, other_project]))

    asyncio.run(store.search([1.0, 0.0], 1, [10], 10))

    stored = {record.chunkId: record for record in store._load()}
    assert calls == [[selected.content]]
    assert stored["selected-old"].embedding == [1.0, 0.0]
    assert stored["other-project-old"].embedding == [1.0, 0.0, 0.0]

def test_legacy_migration_preserves_chunks_written_while_embedding_is_running(tmp_path):
    migration_started = asyncio.Event()
    allow_migration_to_finish = asyncio.Event()

    async def reembed(texts):
        migration_started.set()
        await allow_migration_to_finish.wait()
        return [[1.0, 0.0] for _ in texts]

    async def scenario():
        store = LocalJsonVectorStore(str(tmp_path), reembed=reembed)
        writer = LocalJsonVectorStore(str(tmp_path))
        legacy = chunk("legacy")
        legacy.embedding = [1.0, 0.0, 0.0]
        await store.upsert([legacy])

        search_task = asyncio.create_task(store.search([1.0, 0.0], 1, [10], 10))
        await migration_started.wait()
        await writer.upsert([chunk("added-during-migration")])
        allow_migration_to_finish.set()
        await search_task
        return {record.chunkId: record for record in store._load()}

    stored = asyncio.run(scenario())

    assert set(stored) == {"legacy", "added-during-migration"}
    assert stored["legacy"].embedding == [1.0, 0.0]

def test_legacy_migration_persists_completed_batches_before_a_later_failure(tmp_path):
    calls = 0

    async def reembed(texts):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise RuntimeError("embedding unavailable")
        return [[1.0, 0.0] for _ in texts]

    store = LocalJsonVectorStore(str(tmp_path), reembed=reembed)
    records = []
    for index in range(33):
        record = chunk(f"legacy-{index}")
        record.embedding = [1.0, 0.0, 0.0]
        records.append(record)
    asyncio.run(store.upsert(records))

    results = asyncio.run(store.search([1.0, 0.0], 1, [10], 50))

    dimensions = [len(record.embedding) for record in store._load()]
    assert len(results) == 32
    assert dimensions.count(2) == 32
    assert dimensions.count(3) == 1


def test_legacy_migration_failure_uses_existing_compatible_chunks(tmp_path):
    async def reembed(_texts):
        raise RuntimeError("embedding unavailable")

    store = LocalJsonVectorStore(str(tmp_path), reembed=reembed)
    compatible = chunk("compatible")
    legacy = chunk("legacy")
    legacy.embedding = [1.0, 0.0, 0.0]
    asyncio.run(store.upsert([compatible, legacy]))

    results = asyncio.run(store.search([1.0, 0.0], 1, [10], 10))

    assert [record.chunkId for record, _ in results] == ["compatible"]


def test_local_vector_store_syncs_parent_directory_on_posix(tmp_path, monkeypatch):
    store = LocalJsonVectorStore(str(tmp_path))
    synced = []
    closed = []
    monkeypatch.setattr(vector_store.os, "name", "posix")
    monkeypatch.setattr(vector_store.os, "open", lambda path, flags: 9876)
    monkeypatch.setattr(vector_store.os, "fsync", synced.append)
    monkeypatch.setattr(vector_store.os, "close", closed.append)

    store._sync_parent_directory()

    assert synced == [9876]
    assert closed == [9876]
