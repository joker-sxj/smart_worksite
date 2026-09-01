import asyncio
import time

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
    RetrievalAttempt,
    EvidenceAssessment,
)
from app.services.rag_service import RagService
from app.services.rag_service import MAX_MERGED_CANDIDATES, hash_embedding, merge_candidates
from app.services.retrieval_orchestrator import (
    MAX_DYNAMIC_CANDIDATES,
    RetrievalOrchestrator,
    merge_records,
    normalize_query,
    query_fingerprint,
)
from app.services.vector_store import ChunkRecord, LocalJsonVectorStore


def request(**overrides):
    values = {
        "query": "JGJ 59-2011 第 3.1.2 条要求是什么？",
        "projectId": 7,
        "knowledgeBaseIds": [11, 12],
        "documentScope": [],
        "permissionScope": {},
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
        permissionScope={
            "enforcement": "PROJECT_KNOWLEDGE_BASES",
            "projectId": 7,
            "knowledgeBaseIds": [12, 11],
        },
    ))

    assert first != second
    first = query_fingerprint(request(
        documentScope=["doc-b", "doc-a"],
        permissionScope={
            "enforcement": "PROJECT_KNOWLEDGE_BASES",
            "projectId": 7,
            "knowledgeBaseIds": [11, 12],
        },
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


def test_retrieval_attempt_and_evidence_assessment_models_are_safe_structured_data():
    attempt = RetrievalAttempt(
        attemptNo=1, queryFingerprint="abc", strategy="HYBRID", candidateCount=2,
        status=EvidenceStatus.PARTIAL, elapsedMs=12, stopReason=None,
    )
    assessment = EvidenceAssessment(
        status=EvidenceStatus.PARTIAL, requiredAspects=["WIDTH", "HEIGHT"],
        coveredAspects=["WIDTH"], missingAspects=["HEIGHT"],
    )

    assert "query" not in attempt.model_dump()
    assert assessment.missingAspects == ["HEIGHT"]


def test_permission_scope_rejects_claims_python_cannot_enforce():
    with pytest.raises(ValidationError, match="permissionScope"):
        request(permissionScope={"roleIds": [1, 3]})
    with pytest.raises(ValidationError, match="permissionScope"):
        request(permissionScope={
            "enforcement": "PROJECT_KNOWLEDGE_BASES",
            "projectId": 8,
            "knowledgeBaseIds": [11, 12],
        })
    with pytest.raises(ValidationError, match="permissionScope"):
        request(permissionScope={
            "enforcement": "PROJECT_KNOWLEDGE_BASES",
            "projectId": 7,
            "knowledgeBaseIds": [{"unexpected": True}],
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


def test_compound_numeric_question_requires_each_dimension_and_reports_height_missing():
    async def search(_search_request):
        return RagSearchData(records=[record(
            "width", content="通道宽度不得小于1.5米。",
        )]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request(
        query="安全通道的宽度和高度分别是多少？",
    )))

    assert result.evidenceStatus in {EvidenceStatus.PARTIAL, EvidenceStatus.INSUFFICIENT}
    assert "高度" in result.diagnostics.missingAspects
    assert result.diagnostics.assessment.requiredAspects == ["TOPIC", "宽度", "高度"]
    assert "宽度" in result.diagnostics.assessment.coveredAspects


def test_compound_process_question_requires_each_requested_step():
    async def search(_search_request):
        return RagSearchData(records=[record(
            "steps", content="隐患排查按照登记、评估、整改、复查四个步骤实施。",
        )]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request(
        query="隐患排查的登记和复查步骤分别是什么？",
    )))

    assert result.evidenceStatus == EvidenceStatus.SUFFICIENT
    assert "登记" not in result.diagnostics.missingAspects
    assert "复查" not in result.diagnostics.missingAspects


def test_compound_process_question_reports_missing_step_when_only_one_step_is_evidenced():
    async def search(_search_request):
        return RagSearchData(records=[record("steps", content="复查确认整改结果。")]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request(
        query="隐患排查的登记和复查步骤分别是什么？",
    )))

    assert result.evidenceStatus in {EvidenceStatus.PARTIAL, EvidenceStatus.INSUFFICIENT}
    assert "登记" in result.diagnostics.missingAspects


def test_compound_responsibility_question_requires_each_actor_across_evidence_records():
    async def search(_search_request):
        return RagSearchData(records=[
            record("builder", content="建设单位负责提供施工现场基础资料。"),
            record("contractor", content="施工单位负责落实安全生产措施。"),
        ]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request(
        query="建设单位和施工单位分别负责什么？",
    )))

    assert result.evidenceStatus == EvidenceStatus.SUFFICIENT


def test_compound_responsibility_question_reports_the_missing_actor():
    async def search(_search_request):
        return RagSearchData(records=[
            record("builder", content="建设单位负责提供施工现场基础资料。"),
        ]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request(
        query="建设单位和施工单位分别负责什么？",
    )))

    assert result.evidenceStatus in {EvidenceStatus.PARTIAL, EvidenceStatus.INSUFFICIENT}
    assert "ENTITY:施工单位" in result.diagnostics.missingAspects


def test_generic_compound_entities_are_assessed_individually_without_phrase_specific_rules():
    async def search(_search_request):
        return RagSearchData(records=[record(
            "strength", content="保温材料强度不得低于0.10MPa。",
        )]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request(
        query="保温材料的强度和耐火等级要求分别是什么？",
    )))

    assert result.evidenceStatus in {EvidenceStatus.PARTIAL, EvidenceStatus.INSUFFICIENT}
    assert "ENTITY:耐火等级" in result.diagnostics.missingAspects


def test_validity_missing_metadata_is_unknown_but_does_not_permanently_reject_legacy_evidence():
    async def search(_search_request):
        return RagSearchData(records=[record(
            "definition", content="临边作业是指工作面边沿无围护设施的高处作业。",
        )]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request(
        query="什么是临边作业？",
    )))

    assert result.evidenceStatus == EvidenceStatus.SUFFICIENT
    assert result.diagnostics.validityStatus == "UNKNOWN"


def test_sufficient_first_round_stops_without_rewrite():
    calls = []

    async def search(search_request):
        calls.append(search_request.query)
        return RagSearchData(records=[record(
            "a", content="第3.1.2条 施工单位必须建立安全检查制度。",
        )]), {}

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


@pytest.mark.parametrize(("query", "content"), [
    ("什么是临边作业？", "临边作业是指工作面边沿无围护设施或者围护设施高度低于规定值的高处作业。"),
    ("安全通道宽度应为多少？", "安全通道净宽度不得小于1.5米。"),
    ("专项方案应在何时完成？", "专项施工方案应当在2026年9月1日前完成审批。"),
    ("本标准适用范围是什么？", "本标准适用于房屋建筑和市政基础设施工程施工现场。"),
    ("谁负责施工现场消防安全？", "施工单位项目负责人承担施工现场消防安全管理责任。"),
    ("隐患排查流程是什么？", "隐患排查按照登记、评估、整改、复查四个步骤实施。"),
    ("一级风险和二级风险有什么区别？", "一级风险高于二级风险，二者分别采取停工和限期整改措施。"),
    ("雨天是否允许露天焊接？", "雨天禁止进行露天焊接作业。"),
])
def test_structured_local_evaluator_covers_common_question_types_without_clause_numbers(query, content):
    async def search(_search_request):
        return RagSearchData(records=[record("a", content=content, score=0.9)]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request(query=query)))

    assert result.evidenceStatus == EvidenceStatus.SUFFICIENT
    assert result.diagnostics.missingAspects == []


def test_matching_clause_number_without_requested_fact_is_not_sufficient():
    async def search(_search_request):
        return RagSearchData(records=[record(
            "a", content="第3.1.2条 本章介绍文明施工检查记录的归档方式。", score=0.95,
        )]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request()))

    assert result.evidenceStatus == EvidenceStatus.INSUFFICIENT
    assert result.diagnostics.missingAspects


def test_diagnostics_explain_the_structured_question_type_and_missing_aspects():
    async def search(_search_request):
        return RagSearchData(records=[record("a", content="安全通道应保持畅通。")]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request(
        query="安全通道宽度应为多少？",
    )))

    assert result.diagnostics.questionType == "NUMERIC"
    assert "NUMBER" in result.diagnostics.missingAspects


def test_insufficient_evidence_uses_one_effective_rewrite_and_merges_candidates():
    calls = []

    async def search(search_request):
        calls.append(search_request.query)
        if len(calls) == 1:
            return RagSearchData(records=[record("a", score=0.4)]), {}
        return RagSearchData(records=[
            record("a", score=0.8),
            record("b", content="第3.1.2条 施工单位必须建立安全检查制度。"),
        ]), {}

    async def rewrite(_request, _records):
        return "JGJ 59-2011 第3.1.2条 条文正文"

    result, _ = asyncio.run(RetrievalOrchestrator(search, rewrite).retrieve(request()))

    assert calls == [request().query, "JGJ 59-2011 第3.1.2条 条文正文"]
    assert result.retrievalRounds == 2
    assert result.evidenceStatus == EvidenceStatus.SUFFICIENT
    assert [item.metadata["chunkId"] for item in result.records] == ["a", "b"]
    assert result.records[0].score == 0.8
    assert [attempt.attemptNo for attempt in result.diagnostics.attempts] == [1, 2]
    assert all(attempt.queryFingerprint for attempt in result.diagnostics.attempts)
    assert result.diagnostics.attempts[1].candidateCount == 2


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
    assert result.diagnostics.attempts[0].status == EvidenceStatus.TIMEOUT
    assert result.diagnostics.attempts[0].stopReason == "TIMEOUT"


def test_hanging_search_is_cancelled_by_injected_round_timeout():
    cancelled = asyncio.Event()

    async def search(_search_request):
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.set()

    result, _ = asyncio.run(RetrievalOrchestrator(
        search, round_timeout_seconds=0.01, total_timeout_seconds=0.02,
    ).retrieve(request()))

    assert result.evidenceStatus == EvidenceStatus.TIMEOUT
    assert cancelled.is_set()


def test_second_round_uses_remaining_total_deadline_not_a_fresh_30_seconds():
    calls = 0

    async def search(_search_request):
        nonlocal calls
        calls += 1
        if calls == 2:
            await asyncio.Event().wait()
        await asyncio.sleep(0.005)
        return RagSearchData(records=[]), {}

    async def rewrite(_request, _records):
        return "补充后的有效检索表达"

    result, _ = asyncio.run(RetrievalOrchestrator(
        search, rewrite, round_timeout_seconds=0.05, total_timeout_seconds=0.02,
    ).retrieve(request()))

    assert calls == 2
    assert result.evidenceStatus == EvidenceStatus.TIMEOUT


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


def test_document_scope_is_forwarded_to_rag_service_request():
    seen = []

    async def search(search_request):
        seen.append(search_request)
        return RagSearchData(records=[]), {}

    asyncio.run(RetrievalOrchestrator(search).retrieve(request(documentScope=["doc-a"])))

    assert seen[0].documentScope == ["doc-a"]


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


def test_vector_store_embedding_failure_falls_back_to_text_search(tmp_path):
    class Settings:
        rag_provider = "LOCAL"
        rag_data_dir = str(tmp_path)
        milvus_uri = milvus_token = milvus_collection = pgvector_dsn = pgvector_table = ""
        embedding_provider = "LOCAL_HASH"
        rerank_provider = "LEXICAL"
        rag_rerank_top_k = 5

    chunk = ChunkRecord("a", 7, 11, "doc-a", "a", "安全通道宽度不得小于1.5米。", "DOCUMENT", None, {}, [])

    class Store:
        async def search(self, *_args, **_kwargs):
            raise RuntimeError("embedding dimension unavailable")

        async def search_text(self, *_args, **_kwargs):
            return [(chunk, 1.0)]

        async def adjacent_many(self, chunks, **_kwargs):
            return {item.id: [] for item in chunks}

    service = RagService(Settings(), object())
    service.store = Store()
    result, usage = asyncio.run(service.search(request(
        query="安全通道宽度应为多少？", knowledgeBaseIds=[11], topK=1,
    ).as_rag_search_request()))

    assert len(result.records) == 1
    assert usage["degradedComponents"] == ["EMBEDDING"]


def test_synchronous_rewrite_is_rejected_without_starting_it():
    called = False

    def blocking_rewrite(_request, _records):
        nonlocal called
        called = True
        time.sleep(0.1)
        return "rewritten"

    async def search(_search_request):
        return RagSearchData(records=[]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(
        search, blocking_rewrite, round_timeout_seconds=0.01, total_timeout_seconds=0.01,
    ).retrieve(request()))

    assert called is False
    assert result.evidenceStatus == EvidenceStatus.RETRIEVAL_DEGRADED
    assert result.diagnostics.stopReason == "QUERY_REWRITE_UNSUPPORTED"


@pytest.mark.parametrize("validity", ["REPEALED", "EXPIRED", "FUTURE", "RESTRICTED"])
def test_explicit_non_current_validity_cannot_be_sufficient(validity):
    async def search(_search_request):
        return RagSearchData(records=[record(
            "old", content="临边作业是指工作面边沿无围护设施的高处作业。",
            documentValidity=validity,
        )]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request(
        query="什么是临边作业？",
    )))

    assert result.evidenceStatus == EvidenceStatus.VALIDITY_UNKNOWN
    assert result.diagnostics.validityStatus == validity


def test_current_evidence_is_preferred_and_repealed_evidence_cannot_override_it():
    async def search(_search_request):
        return RagSearchData(records=[
            record("old", content="安全通道宽度不得小于0.8米。", score=0.99,
                   documentValidity="REPEALED"),
            record("current", content="安全通道宽度不得小于1.5米。", score=0.8,
                   documentValidity="CURRENT"),
        ]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request(
        query="安全通道宽度应为多少？", topK=2,
    )))

    assert result.evidenceStatus == EvidenceStatus.SUFFICIENT
    assert result.records[0].metadata["chunkId"] == "current"
    assert all(item.metadata.get("documentValidity") != "REPEALED" for item in result.records)
    assert result.diagnostics.validityStatus == "CURRENT"


def test_real_rag_service_ranks_current_evidence_ahead_of_repealed_evidence(tmp_path):
    class Settings:
        rag_provider = "LOCAL"
        rag_data_dir = str(tmp_path)
        milvus_uri = milvus_token = milvus_collection = pgvector_dsn = pgvector_table = ""
        embedding_provider = "LOCAL_HASH"
        rerank_provider = "LEXICAL"
        rag_rerank_top_k = 5

    repealed = ChunkRecord(
        "old", 7, 11, "doc-old", "old", "安全通道宽度不得小于0.8米。",
        "DOCUMENT", None, {"documentValidity": "REPEALED"}, [],
    )
    current = ChunkRecord(
        "current", 7, 11, "doc-current", "current", "安全通道宽度不得小于1.5米。",
        "DOCUMENT", None, {"documentValidity": "CURRENT"}, [],
    )

    class Store:
        async def search(self, *_args, **_kwargs):
            return [(repealed, 0.99), (current, 0.8)]

        async def search_text(self, *_args, **_kwargs):
            return []

        async def adjacent_many(self, chunks, **_kwargs):
            return {chunk.id: [] for chunk in chunks}

    service = RagService(Settings(), object())
    service.store = Store()
    result, _ = asyncio.run(RetrievalOrchestrator(service.search).retrieve(request(
        query="安全通道宽度应为多少？", knowledgeBaseIds=[11], topK=1,
    )))

    assert result.evidenceStatus == EvidenceStatus.SUFFICIENT
    assert [item.metadata["chunkId"] for item in result.records] == ["current"]
    assert result.diagnostics.validityStatus == "CURRENT"


def test_rag_service_pushes_document_scope_to_vector_text_and_adjacency_store_calls(tmp_path):
    calls = []

    class Settings:
        rag_provider = "LOCAL"
        rag_data_dir = str(tmp_path)
        milvus_uri = milvus_token = milvus_collection = pgvector_dsn = pgvector_table = ""
        embedding_provider = "LOCAL_HASH"
        rerank_provider = "LEXICAL"
        rag_rerank_top_k = 5

    chunk = ChunkRecord("a", 7, 11, "doc-a", "a", "临边作业是指无围护设施的高处作业。", "DOCUMENT", None, {}, [])

    class Store:
        async def search(self, _embedding, _project, _kbs, _top_k, document_scope=None):
            calls.append(("vector", document_scope))
            return [(chunk, 0.9)]

        async def search_text(self, _query, _project, _kbs, _top_k, document_scope=None):
            calls.append(("text", document_scope))
            return [(chunk, 1.0)]

        async def adjacent_many(self, chunks, before=1, after=1, document_scope=None):
            calls.append(("adjacent", document_scope))
            return {item.id: [] for item in chunks}

    service = RagService(Settings(), object())
    service.store = Store()
    result, _ = asyncio.run(service.search(request(
        query="什么是临边作业？", knowledgeBaseIds=[11], documentScope=["doc-a"], topK=1,
    ).as_rag_search_request()))

    assert len(result.records) == 1
    assert calls == [("vector", ["doc-a"]), ("text", ["doc-a"]), ("adjacent", ["doc-a"])]


def test_real_local_rag_service_dynamic_orchestration_respects_scope_and_top_k(tmp_path):
    class Settings:
        rag_provider = "LOCAL"
        rag_data_dir = str(tmp_path)
        milvus_uri = milvus_token = milvus_collection = pgvector_dsn = pgvector_table = ""
        embedding_provider = "LOCAL_HASH"
        rerank_provider = "LEXICAL"
        rag_rerank_top_k = 10

    service = RagService(Settings(), object())
    service.store = LocalJsonVectorStore(str(tmp_path))
    chunks = [
        ChunkRecord("allowed", 7, 11, "doc-a", "定义", "临边作业是指无围护设施的高处作业。", "DOCUMENT", None, {}, hash_embedding("临边作业是指无围护设施的高处作业。")),
        ChunkRecord("outside", 7, 11, "doc-b", "定义", "临边作业是指另一类无围护作业。", "DOCUMENT", None, {}, hash_embedding("临边作业是指另一类无围护作业。")),
    ]
    asyncio.run(service.store.upsert(chunks))
    orchestrator = RetrievalOrchestrator(service.search)

    result, _ = asyncio.run(orchestrator.retrieve(request(
        query="什么是临边作业？", knowledgeBaseIds=[11], documentScope=["doc-a"], topK=1,
    )))

    assert result.evidenceStatus == EvidenceStatus.SUFFICIENT
    assert len(result.records) == 1
    assert result.records[0].metadata["documentId"] == "doc-a"


def test_dynamic_search_endpoint_contract_uses_real_rag_service_and_local_store(tmp_path, monkeypatch):
    from app.api import routes

    class Settings:
        rag_provider = "LOCAL"
        rag_data_dir = str(tmp_path)
        milvus_uri = milvus_token = milvus_collection = pgvector_dsn = pgvector_table = ""
        embedding_provider = "LOCAL_HASH"
        rerank_provider = "LEXICAL"
        rag_rerank_top_k = 5

    service = RagService(Settings(), object())
    content = "安全通道净宽度不得小于1.5米。"
    asyncio.run(service.store.upsert([
        ChunkRecord(
            "width", 7, 11, "doc-a", "通道要求", content,
            "DOCUMENT", None, {}, hash_embedding(content),
        ),
    ]))
    monkeypatch.setattr(routes, "services", lambda: {
        "retrieval": RetrievalOrchestrator(service.search),
    })

    response = TestClient(app).post("/v1/rag/dynamic-search", json={
        "query": "安全通道宽度应为多少？",
        "projectId": 7,
        "knowledgeBaseIds": [11],
        "documentScope": ["doc-a"],
        "topK": 1,
    })

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["evidenceStatus"] == "SUFFICIENT"
    assert len(body["data"]["records"]) == 1
    assert body["data"]["records"][0]["metadata"]["documentId"] == "doc-a"


def test_rag_service_never_returns_more_than_top_k_after_adjacency_expansion(tmp_path):
    class Settings:
        rag_provider = "LOCAL"
        rag_data_dir = str(tmp_path)
        milvus_uri = milvus_token = milvus_collection = pgvector_dsn = pgvector_table = ""
        embedding_provider = "LOCAL_HASH"
        rerank_provider = "LEXICAL"
        rag_rerank_top_k = 5

    anchor = ChunkRecord("a", 7, 11, "doc-a", "a", "安全通道宽度不得小于1.5米。", "DOCUMENT", None, {}, [])
    neighbors = [
        ChunkRecord(str(index), 7, 11, "doc-a", str(index), f"context {index}", "DOCUMENT", None, {}, [])
        for index in range(2, 5)
    ]

    class Store:
        async def search(self, *_args, **_kwargs):
            return [(anchor, 0.9)]

        async def search_text(self, *_args, **_kwargs):
            return []

        async def adjacent_many(self, chunks, **_kwargs):
            return {chunks[0].id: neighbors}

    service = RagService(Settings(), object())
    service.store = Store()
    result, _ = asyncio.run(service.search(request(
        query="安全通道宽度应为多少？", knowledgeBaseIds=[11], topK=2,
    ).as_rag_search_request()))

    assert len(result.records) <= 2
    assert result.records[0].metadata["evidenceWindowChunkIds"] == ["a", "2", "3", "4"]
    assert all(neighbor.content in result.records[0].contentSnippet for neighbor in neighbors)


def test_attempt_uses_the_requested_strategy():
    async def search(_search_request):
        return RagSearchData(records=[]), {}

    result, _ = asyncio.run(RetrievalOrchestrator(search).retrieve(request(strategy="EXACT_KEYWORD")))

    assert result.diagnostics.attempts[0].strategy == "EXACT_KEYWORD"


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
