import asyncio

from app.core.settings import Settings
from app.models.schemas import RagIndexRequest
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
