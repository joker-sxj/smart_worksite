import asyncio

from app.core.settings import Settings
from app.models.schemas import RagIndexRequest, RagSearchRequest
from app.services.rag_service import RagService


class FakeQwen:
    async def embed(self, texts):
        return [[1.0, 0.0] for _ in texts], {"provider": "fake"}


def test_rag_index_preserves_explicit_document_block_location(tmp_path):
    settings = Settings(
        rag_provider="LOCAL",
        rag_data_dir=str(tmp_path),
        embedding_provider="LOCAL_HASH",
    )
    service = RagService(settings, FakeQwen())
    request = RagIndexRequest.model_validate({
        "projectId": 7,
        "knowledgeBaseId": 9,
        "documents": [{
            "documentId": "document-11",
            "title": "Risk Register",
            "content": "fallback text",
            "sourceType": "DOCUMENT",
            "sourceId": "file-22",
            "metadata": {"fileId": 22},
            "blocks": [{
                "blockId": "sheet-risk-row-2",
                "blockType": "TABLE",
                "content": "Level 1 | Alice | 2026-08-20",
                "location": {"sheet": "Risks", "cellRange": "A2:C2"},
                "structuredData": {"values": ["Level 1", "Alice", "2026-08-20"]},
            }],
        }],
    })

    result, _ = asyncio.run(service.index(request))
    stored = service.store._load()

    assert result.indexedDocuments == 1
    assert result.indexedChunks == 1
    assert len(stored) == 1
    assert stored[0].documentId == "document-11"
    assert stored[0].metadata["blockId"] == "sheet-risk-row-2"
    assert stored[0].metadata["blockType"] == "TABLE"
    assert stored[0].metadata["location"] == {"sheet": "Risks", "cellRange": "A2:C2"}
    assert stored[0].metadata["structuredData"]["values"][0] == "Level 1"


def test_rag_reindex_replaces_obsolete_chunks_for_the_same_scoped_document(tmp_path):
    settings = Settings(
        rag_provider="LOCAL",
        rag_data_dir=str(tmp_path),
        embedding_provider="LOCAL_HASH",
        rag_chunk_size=100,
        rag_chunk_overlap=0,
    )
    service = RagService(settings, FakeQwen())

    def request(content: str, *, project_id: int = 7, knowledge_base_id: int = 9):
        return RagIndexRequest.model_validate({
            "projectId": project_id,
            "knowledgeBaseId": knowledge_base_id,
            "documents": [{
                "documentId": "document-11",
                "title": "Risk Register",
                "content": content,
                "sourceType": "DOCUMENT",
                "sourceId": "file-22",
            }],
        })

    asyncio.run(service.index(request("obsolete risk content")))
    asyncio.run(service.index(request("current risk content")))
    asyncio.run(service.index(request("other project content", project_id=8)))
    asyncio.run(service.index(request("other knowledge base content", knowledge_base_id=10)))

    stored = service.store._load()
    target = [
        record for record in stored
        if record.projectId == 7
        and record.knowledgeBaseId == 9
        and record.documentId == "document-11"
    ]

    assert [record.content for record in target] == ["current risk content"]
    assert any(record.projectId == 8 for record in stored)
    assert any(record.knowledgeBaseId == 10 for record in stored)


def test_rag_index_does_not_replace_any_document_when_embedding_preparation_fails(tmp_path):
    class FailingQwen:
        def __init__(self):
            self.calls = 0

        async def embed(self, texts):
            self.calls += 1
            if self.calls == 2:
                raise RuntimeError("embedding failed")
            return [[1.0, 0.0] for _ in texts], {"provider": "fake"}

    class RecordingStore:
        def __init__(self):
            self.replacements = []

        async def replace_document(self, project_id, knowledge_base_id, document_id, chunks):
            self.replacements.append((project_id, knowledge_base_id, document_id, chunks))

    settings = Settings(
        rag_provider="LOCAL",
        rag_data_dir=str(tmp_path),
        embedding_provider="QWEN",
    )
    service = RagService(settings, FailingQwen())
    store = RecordingStore()
    service.store = store
    request = RagIndexRequest.model_validate({
        "projectId": 7,
        "knowledgeBaseId": 9,
        "documents": [
            {"documentId": "doc-1", "title": "One", "content": "first"},
            {"documentId": "doc-2", "title": "Two", "content": "second"},
        ],
    })

    try:
        asyncio.run(service.index(request))
    except RuntimeError as ex:
        assert str(ex) == "embedding failed"
    else:
        raise AssertionError("expected embedding failure")

    assert store.replacements == []


def test_rag_search_expands_a_hit_with_adjacent_pdf_blocks(tmp_path):
    settings = Settings(
        rag_provider="LOCAL",
        rag_data_dir=str(tmp_path),
        embedding_provider="LOCAL_HASH",
        rag_chunk_size=800,
        rag_chunk_overlap=0,
    )
    service = RagService(settings, FakeQwen())
    request = RagIndexRequest.model_validate({
        "projectId": 1,
        "knowledgeBaseId": 5,
        "documents": [{
            "documentId": "doc-36",
            "title": "特种作业人员管理规定",
            "content": "",
            "blocks": [
                {"blockId": "page-2", "blockType": "TEXT", "content": "第七条 （一）年满18周岁；", "location": {"page": 2}},
                {"blockId": "page-3", "blockType": "TEXT", "content": "（二）初中以上学历；", "location": {"page": 3}},
            ],
        }],
    })
    asyncio.run(service.index(request))

    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "报名特种作业考核的年龄和学历要求是什么？",
        "projectId": 1,
        "knowledgeBaseIds": [5],
        "topK": 1,
        "rerankEnabled": False,
    })))

    assert len(result.records) == 2
    assert {record.metadata["location"]["page"] for record in result.records} == {2, 3}
    context = "\n".join(record.contentSnippet for record in result.records)
    assert "年满18周岁" in context
    assert "初中以上学历" in context
    assert sum(bool(record.metadata.get("contextExpansion")) for record in result.records) == 1



def test_local_adjacent_chunks_never_cross_document_or_knowledge_scope(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    def record(chunk_id, project_id, kb_id, document_id, unit_index):
        return ChunkRecord(
            id=chunk_id, projectId=project_id, knowledgeBaseId=kb_id, documentId=document_id,
            title="法规", content=chunk_id, sourceType="DOCUMENT", sourceId=document_id,
            metadata={"unitIndex": unit_index, "chunkIndex": 0}, embedding=[1.0, 0.0],
        )

    store = LocalJsonVectorStore(str(tmp_path))
    target = record("target", 1, 5, "doc-a", 1)
    expected = record("same-doc", 1, 5, "doc-a", 2)
    store._write([
        record("before", 1, 5, "doc-a", 0), target, expected,
        record("other-doc", 1, 5, "doc-b", 2),
        record("other-kb", 1, 6, "doc-a", 2),
        record("other-project", 2, 5, "doc-a", 2),
    ])

    adjacent = asyncio.run(store.adjacent(target, before=0, after=1))

    assert [record.id for record in adjacent] == ["same-doc"]

def test_rag_search_promotes_exact_clause_body_over_toc(tmp_path):
    settings = Settings(rag_provider="LOCAL", rag_data_dir=str(tmp_path), embedding_provider="LOCAL_HASH")
    service = RagService(settings, FakeQwen())
    from app.services.vector_store import ChunkRecord

    def record(chunk_id, content, score):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId="doc-35",
            title="GB 12523", content=content, sourceType="DOCUMENT", sourceId="35",
            metadata={"unitIndex": int(chunk_id), "chunkIndex": 0, "location": {"page": int(chunk_id)}},
            embedding=[score, 0.0],
        )

    class Store:
        async def search(self, query_embedding, project_id, knowledge_base_ids, top_k):
            return [(record("2", "目次 5.4.2 测量仪器", 0.99), 0.99), (record("6", "5.4.2 测量应在无雨雪、无雷电天气，风速5 m/s以下时进行。", 0.40), 0.40)]
        async def adjacent(self, chunk, before=1, after=1):
            return []

    service.store = Store()
    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "GB12523第5.4.2条规定了什么？", "projectId": 1,
        "knowledgeBaseIds": [5], "topK": 1, "rerankEnabled": False,
    })))

    assert result.records[0].metadata["location"] == {"page": 6}
    assert "无雨雪" in result.records[0].contentSnippet
def test_rag_search_promotes_numeric_limit_table_evidence_over_semantic_distractor(tmp_path):
    settings = Settings(rag_provider="LOCAL", rag_data_dir=str(tmp_path), embedding_provider="LOCAL_HASH")
    service = RagService(settings, FakeQwen())
    from app.services.vector_store import ChunkRecord

    def record(chunk_id, content, score):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId="doc-35",
            title="GB 12523", content=content, sourceType="DOCUMENT", sourceId="35",
            metadata={"unitIndex": int(chunk_id), "chunkIndex": 0, "location": {"page": int(chunk_id)}},
            embedding=[score, 0.0],
        )

    class Store:
        async def search(self, query_embedding, project_id, knowledge_base_ids, top_k):
            return [
                (record("1", "建筑施工噪声排放标准适用于场界噪声监测和评价。", 0.99), 0.99),
                (record("5", "表 1 建筑施工场界噪声排放限值 单位：dB（A） 昼间 夜间 70 55。", 0.50), 0.50),
            ]

        async def adjacent(self, chunk, before=1, after=1):
            return []

    service.store = Store()
    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "建筑施工场界噪声排放限值，昼间和夜间分别是多少？", "projectId": 1,
        "knowledgeBaseIds": [5], "topK": 1, "rerankEnabled": False,
    })))

    assert result.records[0].metadata["location"] == {"page": 5}
    assert "昼间 夜间 70 55" in result.records[0].contentSnippet

def test_local_text_search_recalls_exact_policy_evidence_outside_vector_top_k(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    def record(chunk_id, content, unit_index):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId="doc-35",
            title="GB 12523", content=content, sourceType="DOCUMENT", sourceId="35",
            metadata={"unitIndex": unit_index, "chunkIndex": 0}, embedding=[1.0, 0.0],
        )

    store = LocalJsonVectorStore(str(tmp_path))
    store._write([
        record("distractor", "建筑施工噪声排放标准的适用范围和术语定义。", 0),
        record("clause-7-2", "7.2 建设单位、施工单位是实施排放标准的责任主体，应采取必要措施，达到规定的建筑施工噪声排放限值。", 1),
    ])

    results = asyncio.run(store.search_text(
        "GB12523第7.2条规定建设单位和施工单位承担什么责任？", 1, [5], 1
    ))

    assert [record.id for record, _ in results] == ["clause-7-2"]


def test_rag_search_merges_text_candidates_before_reranking(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    settings = Settings(
        rag_provider="LOCAL", rag_data_dir=str(tmp_path), embedding_provider="QWEN",
        rag_rerank_top_k=1,
    )
    service = RagService(settings, FakeQwen())

    def record(chunk_id, content, embedding, unit_index):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId="doc-36",
            title="建筑施工特种作业人员管理规定", content=content,
            sourceType="DOCUMENT", sourceId="36",
            metadata={"unitIndex": unit_index, "chunkIndex": 0}, embedding=embedding,
        )

    store = LocalJsonVectorStore(str(tmp_path))
    store._write([
        record("semantic-distractor", "施工安全保证措施和人员配备。", [1.0, 0.0], 0),
        record("article-27", "第二十七条 建筑施工特种作业人员有权拒绝违章指挥和强令冒险作业。", [0.0, 1.0], 1),
    ])
    service.store = store

    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "建筑施工特种作业人员是否有权拒绝违章指挥和强令冒险作业？",
        "projectId": 1, "knowledgeBaseIds": [5], "topK": 1, "rerankEnabled": False,
    })))

    assert result.records[0].metadata["chunkId"] == "article-27"
    assert "有权拒绝违章指挥" in result.records[0].contentSnippet

def test_rag_search_keeps_direct_text_evidence_above_bad_model_rerank(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    class BadRerankQwen(FakeQwen):
        async def rerank(self, query, documents, top_n):
            return [
                {"index": index, "relevance_score": 0.20 if "每年体检1次" in content else 0.90}
                for index, content in enumerate(documents)
            ], {"provider": "fake-reranker"}

    settings = Settings(
        rag_provider="LOCAL", rag_data_dir=str(tmp_path), embedding_provider="QWEN",
        rerank_provider="QWEN", rag_rerank_top_k=2,
    )
    service = RagService(settings, BadRerankQwen())

    def record(chunk_id, content, embedding, unit_index):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId="doc-36",
            title="建筑施工特种作业人员管理规定", content=content,
            sourceType="DOCUMENT", sourceId="36",
            metadata={"unitIndex": unit_index, "chunkIndex": 0}, embedding=embedding,
        )

    store = LocalJsonVectorStore(str(tmp_path))
    store._write([
        record("semantic-distractor", "施工安全保证措施和人员配备。", [1.0, 0.0], 0),
        record("article-24", "年龄超过60周岁从事建筑施工特种作业的人员，应当每年体检1次。", [0.0, 1.0], 1),
    ])
    service.store = store

    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "建筑施工特种作业人员年龄超过60周岁从事特种作业需要多久体检一次？",
        "projectId": 1, "knowledgeBaseIds": [5], "topK": 1, "rerankEnabled": True,
    })))

    assert result.records[0].metadata["chunkId"] == "article-24"
    assert "每年体检1次" in result.records[0].contentSnippet
