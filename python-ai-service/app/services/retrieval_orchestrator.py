from __future__ import annotations

import hashlib
import inspect
import json
import re
import unicodedata
from collections.abc import Awaitable, Callable
from typing import Any

from app.models.schemas import (
    DynamicRetrievalData,
    DynamicRetrievalRequest,
    EvidenceStatus,
    RagRecord,
    RagSearchData,
    RetrievalDiagnostics,
)
from app.services.vector_store import extract_clause_numbers

Search = Callable[[Any], Awaitable[tuple[RagSearchData, dict[str, Any]]]]
Rewrite = Callable[[DynamicRetrievalRequest, list[RagRecord]], Awaitable[str] | str]
MAX_DYNAMIC_CANDIDATES = 100


def normalize_query(query: str) -> str:
    text = unicodedata.normalize("NFKC", query).lower()
    text = re.sub(r"[\u2010-\u2015\u2212]", "-", text)
    text = re.sub(r"第\s*([0-9]+(?:\s*[.]\s*[0-9]+)*)\s*条", lambda match: f"第{re.sub(r'\s+', '', match.group(1))}条", text)
    return re.sub(r"[\s,，。；;:：!?！？、()（）\[\]【】]", "", text)


def _canonical_query(query: str) -> str:
    return normalize_query(query)


def _canonical_scope(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: _canonical_scope(value[key]) for key in sorted(value)}
    if isinstance(value, list):
        normalized = [_canonical_scope(item) for item in value]
        return sorted(normalized, key=lambda item: json.dumps(item, ensure_ascii=False, sort_keys=True))
    return value


def query_fingerprint(request: DynamicRetrievalRequest, query: str | None = None) -> str:
    payload = {
        "projectId": request.projectId,
        "knowledgeBaseIds": sorted(request.knowledgeBaseIds),
        "documentScope": sorted(request.documentScope),
        "normalizedQuery": _canonical_query(query or request.query),
        "strategy": request.strategy,
        "permissionScope": _canonical_scope(request.permissionScope),
    }
    serialized = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()


def merge_records(first: list[RagRecord], second: list[RagRecord]) -> list[RagRecord]:
    merged: dict[str, RagRecord] = {}
    order: list[str] = []
    for item in [*first, *second]:
        key = str(item.metadata.get("chunkId") or item.metadata.get("documentId") or item.sourceId or "")
        if not key:
            key = hashlib.sha256(f"{item.title}\0{item.contentSnippet}".encode("utf-8")).hexdigest()
        if key not in merged:
            order.append(key)
        if key not in merged or item.score > merged[key].score:
            merged[key] = item
    retained = {
        key for key, _ in sorted(merged.items(), key=lambda item: item[1].score, reverse=True)[:MAX_DYNAMIC_CANDIDATES]
    }
    return [merged[key] for key in order if key in retained]


def candidate_ids(records: list[RagRecord]) -> set[str]:
    return {
        str(item.metadata.get("chunkId") or item.metadata.get("documentId") or item.sourceId or item.contentSnippet)
        for item in records
    }


def evaluate_evidence(records: list[RagRecord], degraded: list[str], query: str) -> tuple[EvidenceStatus, list[str]]:
    statuses = {str(item.metadata.get("evidenceStatus", "")).upper() for item in records}
    if "CONFLICT" in statuses or any(item.metadata.get("conflict") is True for item in records):
        return EvidenceStatus.CONFLICT, []
    if "VALIDITY_UNKNOWN" in statuses or any(
        str(item.metadata.get("documentValidity", "")).upper() == "UNKNOWN" for item in records
    ):
        return EvidenceStatus.VALIDITY_UNKNOWN, []
    direct = [
        item for item in records
        if item.metadata.get("directEvidence") is True or _has_direct_clause_evidence(query, item)
    ]
    partial = "PARTIAL" in statuses or any(item.metadata.get("partialEvidence") is True for item in records)
    if degraded:
        return EvidenceStatus.RETRIEVAL_DEGRADED, [] if direct else ["DIRECT_EVIDENCE"]
    if direct:
        return EvidenceStatus.SUFFICIENT, []
    if partial:
        return EvidenceStatus.PARTIAL, ["CORE_EVIDENCE"]
    if degraded:
        return EvidenceStatus.RETRIEVAL_DEGRADED, ["DIRECT_EVIDENCE"]
    return EvidenceStatus.INSUFFICIENT, ["DIRECT_EVIDENCE"]


def _has_direct_clause_evidence(query: str, record: RagRecord) -> bool:
    query_clauses = extract_clause_numbers(query)
    content_clauses = extract_clause_numbers(record.contentSnippet)
    content = normalize_query(record.contentSnippet)
    return bool(
        query_clauses.intersection(content_clauses)
        and len(content) >= 12
        and re.search(r"应当|必须|不得|可以|要求|负责", content)
    )


def _safe_components(usage: dict[str, Any]) -> list[str]:
    allowed = {"EMBEDDING", "RERANKER", "QUERY_REWRITE", "RETRIEVAL"}
    values = usage.get("degradedComponents", [])
    return [value for value in values if value in allowed]


class RetrievalOrchestrator:
    def __init__(self, search: Search, rewrite: Rewrite | None = None):
        self.search = search
        self.rewrite = rewrite or self._rule_rewrite

    async def retrieve(self, request: DynamicRetrievalRequest) -> tuple[DynamicRetrievalData, dict[str, Any]]:
        fingerprints = [query_fingerprint(request)]
        try:
            first, first_usage = await self.search(request.as_rag_search_request())
        except TimeoutError:
            diagnostics = RetrievalDiagnostics(
                queryFingerprints=fingerprints, stopReason="TIMEOUT"
            )
            return DynamicRetrievalData(
                evidenceStatus=EvidenceStatus.TIMEOUT,
                retrievalRounds=0,
                normalizedQuery=normalize_query(request.query),
                diagnostics=diagnostics,
            ), {"retrievalRounds": 0, "stopReason": "TIMEOUT"}
        first.records = self._filter_document_scope(first.records, request.documentScope)
        degraded = _safe_components(first_usage)
        status, missing = evaluate_evidence(first.records, degraded, request.query)
        rewritten_query: str | None = None
        stop_reason: str | None = None
        records = first.records
        rounds = 1

        if status == EvidenceStatus.INSUFFICIENT:
            try:
                rewritten = self.rewrite(request, records)
                rewritten_query = await rewritten if inspect.isawaitable(rewritten) else rewritten
                rewritten_query = rewritten_query.strip()
            except Exception:
                degraded = list(dict.fromkeys([*degraded, "QUERY_REWRITE"]))
                status = EvidenceStatus.RETRIEVAL_DEGRADED
                stop_reason = "QUERY_REWRITE_FAILED"
            else:
                second_fingerprint = query_fingerprint(request, rewritten_query)
                if not rewritten_query or second_fingerprint == fingerprints[0]:
                    stop_reason = "SKIPPED_DUPLICATE_QUERY"
                else:
                    fingerprints.append(second_fingerprint)
                    second_timed_out = False
                    try:
                        second, second_usage = await self.search(request.as_rag_search_request(rewritten_query))
                    except TimeoutError:
                        second_timed_out = True
                        stop_reason = "TIMEOUT"
                        second = RagSearchData(records=[])
                        second_usage = {}
                        status = EvidenceStatus.TIMEOUT
                    rounds = 2
                    second.records = self._filter_document_scope(second.records, request.documentScope)
                    degraded = list(dict.fromkeys([*degraded, *_safe_components(second_usage)]))
                    if not second_timed_out and candidate_ids(first.records) == candidate_ids(second.records):
                        stop_reason = "SKIPPED_DUPLICATE_CANDIDATES"
                    records = merge_records(first.records, second.records)
                    if not second_timed_out:
                        status, missing = evaluate_evidence(records, degraded, rewritten_query)

        diagnostics = RetrievalDiagnostics(
            candidateCount=len(records),
            queryFingerprints=fingerprints,
            degradedComponents=degraded,
            missingAspects=missing,
            stopReason=stop_reason,
        )
        data = DynamicRetrievalData(
            records=records,
            evidenceStatus=status,
            retrievalRounds=rounds,
            normalizedQuery=normalize_query(request.query),
            rewrittenQuery=rewritten_query,
            diagnostics=diagnostics,
        )
        usage = {
            "candidateCount": len(records),
            "retrievalRounds": rounds,
            "degradedComponents": degraded,
        }
        return data, usage

    @staticmethod
    def _filter_document_scope(records: list[RagRecord], document_scope: list[str]) -> list[RagRecord]:
        if not document_scope:
            return records
        allowed = set(document_scope)
        return [
            item for item in records
            if item.metadata.get("documentId") in allowed or item.sourceId in allowed
        ]

    @staticmethod
    def _rule_rewrite(request: DynamicRetrievalRequest, _records: list[RagRecord]) -> str:
        query = unicodedata.normalize("NFKC", request.query).strip()
        if re.search(r"第\s*[0-9]+(?:\s*[.]\s*[0-9]+)*\s*条", query):
            return f"{query} 条文正文"
        return f"{query} 规定 依据"
