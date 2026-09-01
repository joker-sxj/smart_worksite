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

    assert len(result.records) == 1
    assert {record.metadata["location"]["page"] for record in result.records} == {3}
    context = "\n".join(record.contentSnippet for record in result.records)
    assert "初中以上学历" in context
    assert sum(bool(record.metadata.get("contextExpansion")) for record in result.records) == 0



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


def test_local_adjacent_chunks_do_not_jump_missing_document_units(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    def record(chunk_id, unit_index):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId="doc-a",
            title="法规", content=chunk_id, sourceType="DOCUMENT", sourceId="doc-a",
            metadata={"unitIndex": unit_index, "chunkIndex": 0}, embedding=[1.0, 0.0],
        )

    store = LocalJsonVectorStore(str(tmp_path))
    target = record("target", 1)
    store._write([target, record("distant", 4)])

    adjacent = asyncio.run(store.adjacent(target, before=0, after=1))

    assert adjacent == []


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


def test_text_match_score_requires_exact_clause_number_boundaries():
    from app.services.vector_store import text_match_score

    query = "GB12523第4.4条规定室内测量时评价限值如何调整？"
    exact = "4.4 当户外不满足测量条件时，应在室内测量，并将相应限值减10 dB。"
    nested = "5.4.4 需要测量背景噪声时，测量连续10 min的等效声级。"

    assert text_match_score(query, nested) < 1.0
    assert text_match_score(query, exact) > text_match_score(query, nested)


def test_clause_aware_score_requires_exact_clause_number_boundaries():
    from app.services.rag_service import clause_aware_score
    from app.services.vector_store import ChunkRecord

    def record(content):
        return ChunkRecord(
            id=content, projectId=1, knowledgeBaseId=5, documentId="doc-35",
            title="GB 12523", content=content, sourceType="DOCUMENT", sourceId="35",
            metadata={}, embedding=[1.0, 0.0],
        )

    query = "GB12523第4.4条规定室内测量时评价限值如何调整？"
    exact_score = clause_aware_score(query, record("4.4 当户外不满足测量条件时，应在室内测量，并将相应限值减10 dB。"), 0.1)
    nested_score = clause_aware_score(query, record("5.4.4 需要测量背景噪声时，测量连续10 min的等效声级。"), 0.1)

    assert exact_score > nested_score


def test_structured_evidence_scoring_is_domain_independent():
    from app.services.rag_service import structured_evidence_score

    query = "项目风险预警表中各风险等级和负责人分别是什么？"
    matching_table = "表 2 项目风险预警 风险等级 负责人 日期 描述 一级 张三 2026-08-28 高处作业防护缺失"
    unrelated_table = "表 3 设备保养记录 设备编号 保养日期 B-17 2026-08-27"
    base_score = 0.25

    assert structured_evidence_score(query, matching_table, base_score) > base_score
    assert structured_evidence_score(query, unrelated_table, base_score) == base_score


def test_rag_search_prioritizes_exact_clause_body_over_higher_model_score(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    class MisleadingReranker(FakeQwen):
        async def rerank(self, query, documents, top_n):
            return [
                {"index": index, "relevance_score": 0.1 if content.startswith("5.4.2") else 9.0}
                for index, content in enumerate(documents)
            ], {"provider": "fake-reranker"}

    settings = Settings(
        rag_provider="LOCAL", rag_data_dir=str(tmp_path), embedding_provider="QWEN",
        rerank_provider="QWEN", rag_rerank_top_k=2,
    )
    service = RagService(settings, MisleadingReranker())

    def record(chunk_id, document_id, content, embedding):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId=document_id,
            title="施工标准", content=content, sourceType="DOCUMENT", sourceId=document_id,
            metadata={"unitIndex": 0, "chunkIndex": 0, "blockType": "TEXT"}, embedding=embedding,
        )

    store = LocalJsonVectorStore(str(tmp_path))
    store._write([
        record("semantic-distractor", "unrelated", "本管理方案参照5.4.2条记录施工现场噪声测量和气象条件。", [1.0, 0.0]),
        record("exact-clause", "standard", "5.4.2 测量应在无雨雪、无雷电天气，风速为5 m/s以下时进行。", [0.0, 1.0]),
    ])
    service.store = store

    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "第5.4.2条对天气和风速有什么要求？",
        "projectId": 1, "knowledgeBaseIds": [5], "topK": 1, "rerankEnabled": True,
    })))

    assert result.records[0].metadata["chunkId"] == "exact-clause"


def test_rag_search_prefers_exact_clause_from_identified_source(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    class MisleadingReranker(FakeQwen):
        async def rerank(self, query, documents, top_n):
            return [
                {"index": index, "relevance_score": 9.0 if "其他标准" in content else 0.1}
                for index, content in enumerate(documents)
            ], {"provider": "fake-reranker"}

    settings = Settings(
        rag_provider="LOCAL", rag_data_dir=str(tmp_path), embedding_provider="QWEN",
        rerank_provider="QWEN", rag_rerank_top_k=2,
    )
    service = RagService(settings, MisleadingReranker())

    def record(chunk_id, document_id, title, content, embedding):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId=document_id,
            title=title, content=content, sourceType="DOCUMENT", sourceId=document_id,
            metadata={"unitIndex": 0, "chunkIndex": 0, "blockType": "TEXT"}, embedding=embedding,
        )

    store = LocalJsonVectorStore(str(tmp_path))
    store._write([
        record("wrong-source", "other", "其他标准.pdf", "7.2 其他标准规定由项目负责人负责。", [1.0, 0.0]),
        record("identified-source", "target", "施工噪声排放标准_GB12523-2025.pdf", "7.2 建设单位、施工单位是实施排放标准的责任主体。", [0.0, 1.0]),
    ])
    service.store = store

    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "GB12523第7.2条规定的责任主体是什么？",
        "projectId": 1, "knowledgeBaseIds": [5], "topK": 1, "rerankEnabled": True,
    })))

    assert result.records[0].metadata["chunkId"] == "identified-source"

def test_rag_search_uses_query_focus_to_disambiguate_same_clause_number(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    class MisleadingReranker(FakeQwen):
        async def rerank(self, query, documents, top_n):
            return [
                {"index": index, "relevance_score": 9.0 if "材料进场" in content else 0.1}
                for index, content in enumerate(documents)
            ], {"provider": "fake-reranker"}

    settings = Settings(
        rag_provider="LOCAL", rag_data_dir=str(tmp_path), embedding_provider="QWEN",
        rerank_provider="QWEN", rag_rerank_top_k=2,
    )
    service = RagService(settings, MisleadingReranker())

    def record(chunk_id, document_id, content, embedding):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId=document_id,
            title=f"资料-{document_id}", content=content, sourceType="DOCUMENT", sourceId=document_id,
            metadata={"unitIndex": 0, "chunkIndex": 0, "blockType": "TEXT"}, embedding=embedding,
        )

    store = LocalJsonVectorStore(str(tmp_path))
    store._write([
        record("wrong-topic", "materials", "4.4 材料进场后应检查产品合格证和检验报告。", [1.0, 0.0]),
        record("matching-topic", "acoustics", "4.4 室内测量时，评价限值应按规定降低。", [0.0, 1.0]),
    ])
    service.store = store

    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "第4.4条规定室内测量时评价限值如何调整？",
        "projectId": 1, "knowledgeBaseIds": [5], "topK": 1, "rerankEnabled": True,
    })))

    assert result.records[0].metadata["chunkId"] == "matching-topic"

def test_rag_search_prioritizes_direct_fact_evidence_over_generic_domain_terms(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    class MisleadingReranker(FakeQwen):
        async def rerank(self, query, documents, top_n):
            return [
                {"index": index, "relevance_score": 0.1 if "每年体检1次" in content else 9.0}
                for index, content in enumerate(documents)
            ], {"provider": "fake-reranker"}

    settings = Settings(
        rag_provider="LOCAL", rag_data_dir=str(tmp_path), embedding_provider="QWEN",
        rerank_provider="QWEN", rag_rerank_top_k=2,
    )
    service = RagService(settings, MisleadingReranker())

    def record(chunk_id, document_id, content, embedding):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId=document_id,
            title="人员管理资料", content=content, sourceType="DOCUMENT", sourceId=document_id,
            metadata={"unitIndex": 0, "chunkIndex": 0, "blockType": "TEXT"}, embedding=embedding,
        )

    store = LocalJsonVectorStore(str(tmp_path))
    store._write([
        record(
            "generic-distractor", "unrelated",
            "建筑施工特种作业人员安全管理要求包括人员配备、作业管理、施工措施和现场检查。",
            [1.0, 0.0],
        ),
        record(
            "direct-fact", "regulation",
            "年龄超过60周岁从事建筑施工特种作业的人员，应当每年体检1次。",
            [0.0, 1.0],
        ),
    ])
    service.store = store

    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "建筑施工特种作业人员年龄超过60周岁后多久体检一次？",
        "projectId": 1, "knowledgeBaseIds": [5], "topK": 1, "rerankEnabled": True,
    })))

    assert result.records[0].metadata["chunkId"] == "direct-fact"


def test_structured_evidence_does_not_treat_long_plain_paragraph_as_table():
    from app.services.rag_service import structured_evidence_score

    query = "项目风险预警表中各风险等级和负责人分别是什么？"
    paragraph = (
        "项目风险管理应建立定期检查制度，各部门按照岗位职责开展隐患排查，"
        "负责人需要及时组织整改并记录处理过程，确保施工活动符合管理要求。"
    )
    base_score = 0.25

    assert structured_evidence_score(query, paragraph, base_score) == base_score



def test_rag_search_uses_mentioned_document_title_as_generic_source_scope(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    class MisleadingReranker(FakeQwen):
        async def rerank(self, query, documents, top_n):
            return [
                {"index": index, "relevance_score": 0.1 if "初中以上学历" in content else 9.0}
                for index, content in enumerate(documents)
            ], {"provider": "fake-reranker"}

    settings = Settings(
        rag_provider="LOCAL", rag_data_dir=str(tmp_path), embedding_provider="QWEN",
        rerank_provider="QWEN", rag_rerank_top_k=2,
    )
    service = RagService(settings, MisleadingReranker())

    def record(chunk_id, document_id, title, content, embedding):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId=document_id,
            title=title, content=content, sourceType="DOCUMENT", sourceId=document_id,
            metadata={"unitIndex": 0, "chunkIndex": 0, "blockType": "TEXT"}, embedding=embedding,
        )

    store = LocalJsonVectorStore(str(tmp_path))
    store._write([
        record(
            "wrong-document", "safety-plan", "施工组织设计.pdf",
            "施工人员配备应满足项目安全管理和岗位学历要求。", [1.0, 0.0],
        ),
        record(
            "named-regulation", "regulation", "10_建筑施工特种作业人员管理规定_2025.pdf",
            "申请从事建筑施工特种作业的人员，应当年满18周岁且具有初中以上学历。", [0.0, 1.0],
        ),
    ])
    service.store = store

    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "建筑施工特种作业人员管理规定中申请人员的年龄和学历条件是什么？",
        "projectId": 1, "knowledgeBaseIds": [5], "topK": 1, "rerankEnabled": True,
    })))

    assert result.records[0].metadata["chunkId"] == "named-regulation"



def test_rag_search_uses_distinctive_partial_document_title_as_scope(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    class MisleadingReranker(FakeQwen):
        async def rerank(self, query, documents, top_n):
            return [
                {"index": index, "relevance_score": 9.0 if "无关制度" in content else 0.1}
                for index, content in enumerate(documents)
            ], {"provider": "fake-reranker"}

    settings = Settings(
        rag_provider="LOCAL", rag_data_dir=str(tmp_path), embedding_provider="QWEN",
        rerank_provider="QWEN", rag_rerank_top_k=2,
    )
    service = RagService(settings, MisleadingReranker())

    def record(chunk_id, document_id, title, content, embedding):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId=document_id,
            title=title, content=content, sourceType="DOCUMENT", sourceId=document_id,
            metadata={"unitIndex": 0, "chunkIndex": 0, "blockType": "TEXT"}, embedding=embedding,
        )

    store = LocalJsonVectorStore(str(tmp_path))
    store._write([
        record("distractor", "other", "其他制度.pdf", "无关制度规定项目人员应接受培训。", [1.0, 0.0]),
        record(
            "target", "target", "建筑施工特种作业人员管理规定_2025.pdf",
            "报名参加建筑施工特种作业考核，应当具备规定的年龄和学历条件。", [0.0, 1.0],
        ),
    ])
    service.store = store

    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "报名建筑施工特种作业考核需要具备哪些条件？",
        "projectId": 1, "knowledgeBaseIds": [5], "topK": 1, "rerankEnabled": True,
    })))

    assert result.records[0].metadata["chunkId"] == "target"


def test_source_scope_ignores_short_generic_title_overlap():
    from app.services.rag_service import source_scope_strength

    assert source_scope_strength("建设单位承担什么责任？", "建设工程材料管理制度.pdf") == 0.0

def test_rag_search_prioritizes_multi_condition_evidence_over_single_related_fact(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    class MisleadingReranker(FakeQwen):
        async def rerank(self, query, documents, top_n):
            return [
                {"index": index, "relevance_score": 9.0 if "超过60周岁" in content else 0.1}
                for index, content in enumerate(documents)
            ], {"provider": "fake-reranker"}

    settings = Settings(
        rag_provider="LOCAL", rag_data_dir=str(tmp_path), embedding_provider="QWEN",
        rerank_provider="QWEN", rag_rerank_top_k=3,
    )
    service = RagService(settings, MisleadingReranker())

    def record(chunk_id, document_id, content, embedding, unit_index):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId=document_id,
            title="建筑施工特种作业人员管理规定.pdf", content=content,
            sourceType="DOCUMENT", sourceId=document_id,
            metadata={"unitIndex": unit_index, "chunkIndex": 0, "blockType": "TEXT"}, embedding=embedding,
        )

    store = LocalJsonVectorStore(str(tmp_path))
    store._write([
        record("eligibility-age", "rule", "第七条 报名参加考核的人员应当年满18周岁。", [0.0, 1.0], 0),
        record("eligibility-education", "rule", "（二）申请人员应当具有初中以上学历。", [0.0, 1.0], 1),
        record("related-age", "rule", "年龄超过60周岁从事特种作业的人员，应当每年体检1次。", [1.0, 0.0], 4),
    ])
    service.store = store

    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "报名建筑施工特种作业考核的年龄和学历要求是什么？",
        "projectId": 1, "knowledgeBaseIds": [5], "topK": 2, "rerankEnabled": True,
    })))

    assert result.records[0].metadata["chunkId"] == "eligibility-age"
    assert any(record.metadata["chunkId"] == "eligibility-education" for record in result.records)


def test_rag_search_prioritizes_enumerated_action_evidence(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    class MisleadingReranker(FakeQwen):
        async def rerank(self, query, documents, top_n):
            return [
                {"index": index, "relevance_score": 9.0 if "监督检查" in content else 0.1}
                for index, content in enumerate(documents)
            ], {"provider": "fake-reranker"}

    settings = Settings(
        rag_provider="LOCAL", rag_data_dir=str(tmp_path), embedding_provider="QWEN",
        rerank_provider="QWEN", rag_rerank_top_k=2,
    )
    service = RagService(settings, MisleadingReranker())

    def record(chunk_id, content, embedding, unit_index):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId="rule",
            title="特种作业人员管理规定.pdf", content=content, sourceType="DOCUMENT", sourceId="rule",
            metadata={"unitIndex": unit_index, "chunkIndex": 0, "blockType": "TEXT"}, embedding=embedding,
        )

    store = LocalJsonVectorStore(str(tmp_path))
    store._write([
        record("distractor", "主管部门应当开展持证上岗监督检查。", [1.0, 0.0], 4),
        record("enumeration", "考核发证机关应当依法注销资格证书：（一）未按期复核；（二）死亡；（三）证书被撤销、吊销。", [0.0, 1.0], 0),
    ])
    service.store = store

    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "哪些情形下考核发证机关应当依法注销特种作业人员资格证书？",
        "projectId": 1, "knowledgeBaseIds": [5], "topK": 1, "rerankEnabled": True,
    })))

    assert result.records[0].metadata["chunkId"] == "enumeration"

def test_structured_evidence_accepts_parser_table_metadata_without_caption():
    from app.services.rag_service import structured_evidence_score

    query = "项目风险预警中各风险等级和负责人分别是什么？"
    content = "风险等级 负责人 日期 描述 一级 张三 2026-08-28 高处作业防护缺失"
    base_score = 0.25

    assert structured_evidence_score(
        query, content, base_score, {"blockType": "TABLE"}
    ) > base_score


def test_local_adjacent_many_reuses_one_snapshot_for_multiple_candidates(tmp_path):
    from app.services.vector_store import ChunkRecord, LocalJsonVectorStore

    class CountingStore(LocalJsonVectorStore):
        def __init__(self, data_dir):
            super().__init__(data_dir)
            self.load_count = 0

        def _load(self):
            self.load_count += 1
            return super()._load()

    def record(chunk_id, unit_index):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId="doc",
            title="规则.pdf", content=chunk_id, sourceType="DOCUMENT", sourceId="doc",
            metadata={"unitIndex": unit_index, "chunkIndex": 0}, embedding=[1.0, 0.0],
        )

    store = CountingStore(str(tmp_path))
    records = [record(f"c{index}", index) for index in range(4)]
    store._write(records)
    result = asyncio.run(store.adjacent_many(records[1:3]))

    assert set(result) == {"c1", "c2"}
    assert [item.id for item in result["c1"]] == ["c0", "c2"]
    assert [item.id for item in result["c2"]] == ["c1", "c3"]
    assert store.load_count == 1


def test_chinese_title_scope_breaks_same_clause_tie():
    from app.services.rag_service import direct_evidence_priority
    from app.services.vector_store import ChunkRecord

    def record(chunk_id, title):
        return ChunkRecord(
            id=chunk_id, projectId=1, knowledgeBaseId=5, documentId=chunk_id,
            title=title, content="第7.2条 申请人员应当完成安全培训。", sourceType="DOCUMENT", sourceId=chunk_id,
            metadata={"unitIndex": 0, "chunkIndex": 0}, embedding=[1.0, 0.0],
        )

    query = "《建筑施工特种作业人员管理规定》第7.2条规定了什么？"
    named = direct_evidence_priority(query, record("named", "建筑施工特种作业人员管理规定.pdf"))
    other = direct_evidence_priority(query, record("other", "施工现场安全管理制度.pdf"))
    assert named[0] > other[0] or named[1] > other[1]


def test_clause_parser_supports_integer_and_chinese_article_numbers():
    from app.services.vector_store import extract_clause_numbers

    assert extract_clause_numbers("第7条和第七条") == {"7"}
    assert extract_clause_numbers("第七条") == {"7"}
    assert extract_clause_numbers("附录A") == set()


def test_rag_search_uses_one_batch_adjacency_call_for_all_candidates(tmp_path):
    from app.services.vector_store import ChunkRecord

    class Store:
        def __init__(self):
            self.batch_calls = 0

        async def search(self, query_embedding, project_id, knowledge_base_ids, top_k):
            return [(self.record(f"c{index}", index), 1.0 - index / 10) for index in range(3)]

        async def adjacent_many(self, chunks, before=1, after=1):
            self.batch_calls += 1
            return {chunk.id: [] for chunk in chunks}

        async def adjacent(self, chunk, before=1, after=1):
            raise AssertionError("RagService must not issue per-candidate adjacency queries")

        @staticmethod
        def record(chunk_id, unit_index):
            return ChunkRecord(
                id=chunk_id, projectId=1, knowledgeBaseId=5, documentId="doc",
                title="规则.pdf", content=f"证据 {chunk_id}", sourceType="DOCUMENT", sourceId="doc",
                metadata={"unitIndex": unit_index, "chunkIndex": 0}, embedding=[1.0, 0.0],
            )

    settings = Settings(rag_provider="LOCAL", rag_data_dir=str(tmp_path), embedding_provider="LOCAL_HASH")
    service = RagService(settings, FakeQwen())
    store = Store()
    service.store = store
    asyncio.run(service.search(RagSearchRequest.model_validate({
        "query": "证据是什么？", "projectId": 1, "knowledgeBaseIds": [5],
        "topK": 2, "rerankEnabled": False,
    })))
    assert store.batch_calls == 1


def test_milvus_adjacent_many_reads_past_256_blocks(monkeypatch):
    import sys
    from types import SimpleNamespace
    from app.services.vector_store import ChunkRecord, MilvusVectorStore

    def record(index):
        return {
            "id": f"c{index}", "chunkId": f"c{index}", "projectId": 1,
            "knowledgeBaseId": 5, "documentId": "long-doc", "title": "长文档.pdf",
            "content": f"第{index}块", "sourceType": "DOCUMENT", "sourceId": "long-doc",
            "metadata": {"unitIndex": index, "chunkIndex": 0},
        }

    pages = [[record(index) for index in range(0, 128)],
             [record(index) for index in range(128, 256)],
             [record(index) for index in range(256, 320)], []]

    class Iterator:
        def __init__(self):
            self.pages = iter(pages)
            self.closed = False

        def next(self):
            return next(self.pages)

        def close(self):
            self.closed = True

    iterator = Iterator()

    class Client:
        def __init__(self, **kwargs):
            pass

        def has_collection(self, collection):
            return True

        def query_iterator(self, **kwargs):
            assert kwargs["batch_size"] == 256
            return iterator

    monkeypatch.setitem(sys.modules, "pymilvus", SimpleNamespace(MilvusClient=Client))
    target = ChunkRecord(**{**record(280), "embedding": []})
    store = MilvusVectorStore("http://milvus", "", "chunks")
    result = asyncio.run(store.adjacent_many([target]))

    assert [item.id for item in result["c280"]] == ["c279", "c281"]
    assert iterator.closed is True


def test_chinese_article_heading_counts_as_exact_clause_body():
    from app.services.rag_service import direct_evidence_priority
    from app.services.vector_store import ChunkRecord

    chunk = ChunkRecord(
        id="article-7", projectId=1, knowledgeBaseId=5, documentId="rule",
        title="建筑施工特种作业人员管理规定.pdf",
        content="第七条 报名参加考核的人员应当年满十八周岁。",
        sourceType="DOCUMENT", sourceId="rule",
        metadata={"unitIndex": 0, "chunkIndex": 0}, embedding=[1.0, 0.0],
    )
    tier, _ = direct_evidence_priority("《建筑施工特种作业人员管理规定》第七条规定了什么？", chunk)
    assert tier == 6


def test_source_scope_does_not_match_empty_or_generic_title_core():
    from app.services.rag_service import source_scope_strength

    query = '《建筑施工特种作业人员管理规定》第七条规定了什么？'

    assert source_scope_strength(query, '2025.pdf') == 0.0
    assert source_scope_strength(query, '规定.pdf') == 0.0


def test_rag_search_tolerates_missing_batch_adjacency_entries(tmp_path):
    from app.services.vector_store import ChunkRecord

    class SparseBatchStore:
        async def search(self, query_embedding, project_id, knowledge_base_ids, top_k):
            return [(ChunkRecord(
                id='candidate', projectId=1, knowledgeBaseId=5, documentId='doc',
                title='规则.pdf', content='第七条 应当完成安全培训。',
                sourceType='DOCUMENT', sourceId='doc',
                metadata={'unitIndex': 0, 'chunkIndex': 0}, embedding=[1.0, 0.0],
            ), 1.0)]

        async def adjacent_many(self, chunks, before=1, after=1):
            return {}

    settings = Settings(rag_provider='LOCAL', rag_data_dir=str(tmp_path), embedding_provider='LOCAL_HASH')
    service = RagService(settings, FakeQwen())
    service.store = SparseBatchStore()

    result, _ = asyncio.run(service.search(RagSearchRequest.model_validate({
        'query': '第七条规定了什么？', 'projectId': 1,
        'knowledgeBaseIds': [5], 'topK': 1, 'rerankEnabled': False,
    })))

    assert result.records[0].metadata['chunkId'] == 'candidate'
