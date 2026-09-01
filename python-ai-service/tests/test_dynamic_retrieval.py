import asyncio

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.main import app
from app.models.schemas import (
    DynamicRetrievalData,
    DynamicRetrievalRequest,
    EvidenceStatus,
    RagRecord,
    RagSearchData,
    RetrievalDiagnostics,
)
from app.services.rag_service import RagService
from app.services.rag_service import MAX_MERGED_CANDIDATES, merge_candidates
from app.services.retrieval_orchestrator import (
    MAX_DYNAMIC_CANDIDATES,
    RetrievalOrchestrator,
    merge_records,
    normalize_query,
    query_fingerprint,
)
from app.services.vector_store import ChunkRecord


def request(**overrides):
    values = {
        "query": "JGJ 59-2011 第 3.1.2 条要求是什么？",
        "projectId": 7,
        "knowledgeBaseIds": [11, 12],
        "documentScope": [],
        "permissionScope": {"roleIds": [3, 1]},
    }
    values.update(overrides)
    return DynamicRetrievalRequest.model_validate(values)


def record(chunk_id, content="证据正文", score=0.9, **metadata):
    return RagRecord(
        title="施工安全检查标准",
        contentSnippet=content,
        sourceType="DOCUMENT",
        sourceId="doc-1",
        score=score,
        metadata={"chunkId": chunk_id, "documentId": "doc-1", **metadata},
    )


def test_query_normalization_tolerates_width_whitespace_dashes_and_clause_format():
    first = normalize_query("ＪＧＪ　５９—２０１１\n第３．１．２ 条")
    second = normalize_query("JGJ 59-2011 第 3.1.2 条")

    assert first == second == "jgj59-2011第3.1.2条"


def test_query_fingerprint_is_stable_for_scope_order_and_format_only_changes():
    first = query_fingerprint(request(documentScope=["doc-b", "doc-a"]))
    second = query_fingerprint(request(
        query="JGJ59—2011 第3.1.2条，要求是什么",
        knowledgeBaseIds=[12, 11],
        documentScope=["doc-a", "doc-b"],
        permissionScope={"roleIds": [1, 3]},
    ))

    assert first == second
    assert query_fingerprint(request(projectId=8)) != first


def test_query_fingerprint_preserves_semantic_character_order():
    assert query_fingerprint(request(query="甲方允许乙方施工吗")) != query_fingerprint(
        request(query="乙方允许甲方施工吗")
    )


def test_top_k_has_a_hard_limit_and_legacy_search_defaults_remain_compatible():
    legacy = request().as_rag_search_request()

    assert legacy.topK == 5
    assert legacy.rerankEnabled is True
    with pytest.raises(ValidationError):
        DynamicRetrievalRequest.model_validate({
            "query": "test", "projectId": 1, "knowledgeBaseIds": [1], "topK": 101,
        })


def test_candidate_merge_has_a_hard_limit():
    def chunk(index):
        return ChunkRecord(
            str(index), 7, 11, "doc", str(index), f"content {index}", "DOCUMENT", None, {}, []
        )

    vector = [(chunk(index), 0.5) for index in range(MAX_MERGED_CANDIDATES)]
    text = [(chunk(index), 1.0) for index in range(MAX_MERGED_CANDIDATES, MAX_MERGED_CANDIDATES + 20)]

    assert len(merge_candidates(vector, text)) == MAX_MERGED_CANDIDATES


def test_cross_round_record_merge_has_a_hard_limit():
    first = [record(f"first-{index}", score=0.4) for index in range(MAX_DYNAMIC_CANDIDATES)]
    second = [record(f"second-{index}", score=0.8) for index in range(MAX_DYNAMIC_CANDIDATES)]

    merged = merge_records(first, second)

    assert len(merged) == MAX_DYNAMIC_CANDIDATES
    assert all(item.score == 0.8 for item in merged)


def test_sufficient_first_round_stops_without_rewrite():
    calls = []

    async def search(search_request):
        calls.append(search_request.query)
        return RagSearchData(records=[record("a", directEvidence=True)]), {}

    async def rewrite(_request, _records):
        raise AssertionError("sufficient evidence must not be rewritten")

    result, _ = asyncio.run(RetrievalOrchestrator(search, rewrite).retrieve(request()))

    assert result.evidenceStatus == EvidenceStatus.SUFFICIENT
    assert result.retrievalRounds == 1
    assert calls == [request().query]


def test_real_record_with_direct_clause_body_is_sufficient_without_test_metadata_flag():
    async def search(_search_request):
        return RagSearchData(records=[record(
            "a", content="第3.1.2条 施工单位应当建立安全检查制度并落实责任。", score=0.86,
        )]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request()))

    assert result.evidenceStatus == EvidenceStatus.SUFFICIENT
    assert result.retrievalRounds == 1


def test_insufficient_evidence_uses_one_effective_rewrite_and_merges_candidates():
    calls = []

    async def search(search_request):
        calls.append(search_request.query)
        if len(calls) == 1:
            return RagSearchData(records=[record("a", score=0.4)]), {}
        return RagSearchData(records=[record("a", score=0.8), record("b", directEvidence=True)]), {}

    async def rewrite(_request, _records):
        return "JGJ 59-2011 第3.1.2条 条文正文"

    result, _ = asyncio.run(RetrievalOrchestrator(search, rewrite).retrieve(request()))

    assert calls == [request().query, "JGJ 59-2011 第3.1.2条 条文正文"]
    assert result.retrievalRounds == 2
    assert result.evidenceStatus == EvidenceStatus.SUFFICIENT
    assert [item.metadata["chunkId"] for item in result.records] == ["a", "b"]
    assert result.records[0].score == 0.8


def test_format_only_rewrite_is_skipped_without_second_search():
    calls = []

    async def search(search_request):
        calls.append(search_request.query)
        return RagSearchData(records=[]), {}

    async def rewrite(_request, _records):
        return "JGJ59—2011 第 3.1.2 条，要求是什么"

    result, _ = asyncio.run(RetrievalOrchestrator(search, rewrite).retrieve(request()))

    assert len(calls) == 1
    assert result.retrievalRounds == 1
    assert result.diagnostics.stopReason == "SKIPPED_DUPLICATE_QUERY"


def test_duplicate_second_round_candidates_are_reported_and_not_duplicated():
    calls = 0

    async def search(_search_request):
        nonlocal calls
        calls += 1
        return RagSearchData(records=[record("same")]), {}

    async def rewrite(_request, _records):
        return "JGJ 59-2011 第3.1.2条 正文"

    result, _ = asyncio.run(RetrievalOrchestrator(search, rewrite).retrieve(request()))

    assert calls == 2
    assert len(result.records) == 1
    assert result.diagnostics.stopReason == "SKIPPED_DUPLICATE_CANDIDATES"


def test_rewrite_failure_degrades_without_leaking_exception_details():
    async def search(_search_request):
        return RagSearchData(records=[]), {}

    async def rewrite(_request, _records):
        raise RuntimeError("token=secret internal-url=http://private")

    result, _ = asyncio.run(RetrievalOrchestrator(search, rewrite).retrieve(request()))

    dumped = result.model_dump_json()
    assert result.evidenceStatus == EvidenceStatus.RETRIEVAL_DEGRADED
    assert result.diagnostics.degradedComponents == ["QUERY_REWRITE"]
    assert "secret" not in dumped
    assert "private" not in dumped


def test_retrieval_timeout_returns_completed_timeout_state_without_rewrite():
    rewrite_calls = 0

    async def search(_search_request):
        raise TimeoutError("internal host and query details")

    async def rewrite(_request, _records):
        nonlocal rewrite_calls
        rewrite_calls += 1
        return "must not run"

    result, usage = asyncio.run(RetrievalOrchestrator(search, rewrite).retrieve(request()))

    assert result.evidenceStatus == EvidenceStatus.TIMEOUT
    assert result.retrievalRounds == 0
    assert result.diagnostics.stopReason == "TIMEOUT"
    assert rewrite_calls == 0
    assert "internal host" not in result.model_dump_json()
    assert usage["retrievalRounds"] == 0


def test_second_round_timeout_returns_first_round_candidates_and_timeout_state():
    calls = 0

    async def search(_search_request):
        nonlocal calls
        calls += 1
        if calls == 1:
            return RagSearchData(records=[record("first")]), {}
        raise TimeoutError("private second round details")

    async def rewrite(_request, _records):
        return "JGJ 59-2011 第3.1.2条 条文正文"

    result, _ = asyncio.run(RetrievalOrchestrator(search, rewrite).retrieve(request()))

    assert result.evidenceStatus == EvidenceStatus.TIMEOUT
    assert result.retrievalRounds == 2
    assert [item.metadata["chunkId"] for item in result.records] == ["first"]
    assert result.diagnostics.stopReason == "TIMEOUT"


@pytest.mark.parametrize("terminal_status", [
    EvidenceStatus.PARTIAL,
    EvidenceStatus.CONFLICT,
    EvidenceStatus.VALIDITY_UNKNOWN,
    EvidenceStatus.TIMEOUT,
])
def test_terminal_evidence_states_never_trigger_rewrite(terminal_status):
    rewrite_calls = 0
    metadata = {
        EvidenceStatus.PARTIAL: {"evidenceStatus": "PARTIAL"},
        EvidenceStatus.CONFLICT: {"conflict": True},
        EvidenceStatus.VALIDITY_UNKNOWN: {"documentValidity": "UNKNOWN"},
    }

    async def search(_search_request):
        if terminal_status == EvidenceStatus.TIMEOUT:
            raise TimeoutError("private")
        return RagSearchData(records=[record("a", **metadata[terminal_status])]), {}

    async def rewrite(_request, _records):
        nonlocal rewrite_calls
        rewrite_calls += 1
        return "rewritten"

    result, _ = asyncio.run(RetrievalOrchestrator(search, rewrite).retrieve(request()))

    assert result.evidenceStatus == terminal_status
    assert rewrite_calls == 0


def test_retrieval_degraded_is_terminal_when_fallback_returns_usable_records():
    rewrite_calls = 0

    async def search(_search_request):
        return RagSearchData(records=[record(
            "a", content="第3.1.2条 施工单位应建立安全检查制度。", score=0.9,
        )]), {"degradedComponents": ["EMBEDDING"]}

    async def rewrite(_request, _records):
        nonlocal rewrite_calls
        rewrite_calls += 1
        return "rewritten"

    result, _ = asyncio.run(RetrievalOrchestrator(search, rewrite).retrieve(request()))

    assert result.evidenceStatus == EvidenceStatus.RETRIEVAL_DEGRADED
    assert result.diagnostics.degradedComponents == ["EMBEDDING"]
    assert rewrite_calls == 0


def test_document_scope_filters_candidates_without_changing_permission_scope():
    seen = []

    async def search(search_request):
        seen.append(search_request)
        return RagSearchData(records=[
            record("allowed", documentId="doc-a", directEvidence=True),
            record("outside", documentId="doc-outside", directEvidence=True),
        ]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request(documentScope=["doc-a"])))

    assert [item.metadata["chunkId"] for item in result.records] == ["allowed"]
    assert seen[0].projectId == 7
    assert seen[0].knowledgeBaseIds == [11, 12]


@pytest.mark.parametrize(("status", "metadata"), [
    (EvidenceStatus.PARTIAL, {"evidenceStatus": "PARTIAL"}),
    (EvidenceStatus.CONFLICT, {"conflict": True}),
    (EvidenceStatus.VALIDITY_UNKNOWN, {"documentValidity": "UNKNOWN"}),
])
def test_explicit_evidence_states_are_preserved(status, metadata):
    async def search(_search_request):
        return RagSearchData(records=[record("a", **metadata)]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request()))

    assert result.evidenceStatus == status


def test_embedding_and_reranker_failures_fall_back_to_text_and_lexical(tmp_path):
    class Settings:
        rag_provider = "LOCAL"
        rag_data_dir = str(tmp_path)
        milvus_uri = ""
        milvus_token = ""
        milvus_collection = ""
        pgvector_dsn = ""
        pgvector_table = ""
        embedding_provider = "QWEN"
        rerank_provider = "QWEN"
        rag_rerank_top_k = 5

    class FailingQwen:
        async def embed(self, _texts):
            raise RuntimeError("embedding unavailable")

        async def rerank(self, *_args):
            raise RuntimeError("reranker unavailable")

    chunk = ChunkRecord("a", 7, 11, "doc-1", "a", "第3.1.2条 施工要求", "DOCUMENT", None, {}, [])

    class Store:
        async def search(self, *_args):
            raise AssertionError("vector search must be skipped after embedding failure")

        async def search_text(self, *_args):
            return [(chunk, 1.0)]

        async def adjacent_many(self, chunks, **_kwargs):
            return {item.id: [] for item in chunks}

    service = RagService(Settings(), FailingQwen())
    service.store = Store()
    result, usage = asyncio.run(service.search(request().as_rag_search_request()))

    assert [item.metadata["chunkId"] for item in result.records] == ["a"]
    assert usage["degradedComponents"] == ["EMBEDDING", "RERANKER"]


def test_dynamic_retrieval_route_returns_evidence_status_and_safe_diagnostics(monkeypatch):
    from app.api import routes

    expected = DynamicRetrievalData(
        records=[record("a", directEvidence=True)],
        evidenceStatus=EvidenceStatus.SUFFICIENT,
        retrievalRounds=1,
        diagnostics=RetrievalDiagnostics(candidateCount=1),
    )

    class FakeOrchestrator:
        async def retrieve(self, _request):
            return expected, {"candidateCount": 1}

    monkeypatch.setattr(routes, "services", lambda: {"retrieval": FakeOrchestrator()})
    response = TestClient(app).post("/v1/rag/dynamic-search", json=request().model_dump())

    assert response.status_code == 200
    body = response.json()
    assert body["data"]["evidenceStatus"] == "SUFFICIENT"
    assert body["data"]["diagnostics"]["candidateCount"] == 1


def test_legacy_rag_search_route_accepts_request_without_dynamic_fields(monkeypatch):
    from app.api import routes

    class FakeRag:
        async def search(self, search_request):
            assert search_request.topK == 5
            return RagSearchData(records=[]), {}

    monkeypatch.setattr(routes, "services", lambda: {"rag": FakeRag()})
    response = TestClient(app).post("/v1/rag/search", json={
        "query": "安全帽规范", "projectId": 7, "knowledgeBaseIds": [11],
    })

    assert response.status_code == 200
    assert response.json()["success"] is True
