from __future__ import annotations

import hashlib
import inspect
import json
import re
import unicodedata
import asyncio
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from app.models.schemas import (
    DynamicRetrievalData,
    DynamicRetrievalRequest,
    EvidenceStatus,
    RagRecord,
    RagSearchData,
    RetrievalDiagnostics,
)
from app.services.vector_store import compact_search_text, extract_clause_numbers, iter_bigrams

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


def merge_records(
    first: list[RagRecord], second: list[RagRecord], limit: int = MAX_DYNAMIC_CANDIDATES
) -> list[RagRecord]:
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
        key for key, _ in sorted(merged.items(), key=lambda item: item[1].score, reverse=True)
        [:min(limit, MAX_DYNAMIC_CANDIDATES)]
    }
    return [merged[key] for key in order if key in retained]


def candidate_ids(records: list[RagRecord]) -> set[str]:
    return {
        str(item.metadata.get("chunkId") or item.metadata.get("documentId") or item.sourceId or item.contentSnippet)
        for item in records
    }


@dataclass(frozen=True)
class QuestionAnalysis:
    kind: str
    required_aspects: tuple[str, ...]


QUESTION_RULES = (
    ("DEFINITION", r"什么是|何谓|定义|指什么", ("TOPIC", "DEFINITION")),
    ("NUMERIC", r"多少|多大|数值|数量|宽度|高度|距离|比例|限值", ("TOPIC", "NUMBER")),
    ("DATE", r"何时|什么时候|日期|期限|多久|时间", ("TOPIC", "DATE")),
    ("SCOPE", r"适用|范围|哪些|对象", ("TOPIC", "SCOPE")),
    ("RESPONSIBILITY", r"谁|负责|责任|主体", ("TOPIC", "ACTOR", "RESPONSIBILITY")),
    ("PROCESS", r"流程|步骤|程序|如何|怎么", ("TOPIC", "PROCESS")),
    ("COMPARISON", r"区别|比较|不同|相比|高于|低于", ("TOPIC", "COMPARISON")),
    ("PERMISSION", r"是否|能否|可否|允许|禁止", ("TOPIC", "MODALITY")),
    ("REQUIREMENT", r"要求|规定", ("TOPIC", "MODALITY")),
)


def analyze_question(query: str) -> QuestionAnalysis:
    for kind, pattern, aspects in QUESTION_RULES:
        if re.search(pattern, query):
            return QuestionAnalysis(kind, aspects)
    return QuestionAnalysis("FACT", ("TOPIC", "ASSERTION"))


def evaluate_evidence(records: list[RagRecord], degraded: list[str], query: str) -> tuple[EvidenceStatus, list[str]]:
    statuses = {str(item.metadata.get("evidenceStatus", "")).upper() for item in records}
    if "CONFLICT" in statuses or any(item.metadata.get("conflict") is True for item in records):
        return EvidenceStatus.CONFLICT, []
    if "VALIDITY_UNKNOWN" in statuses or any(
        str(item.metadata.get("documentValidity", "")).upper() == "UNKNOWN" for item in records
    ):
        return EvidenceStatus.VALIDITY_UNKNOWN, []
    analysis = analyze_question(query)
    coverage = [_evidence_aspects(query, item.contentSnippet, analysis) for item in records]
    covered = set().union(*coverage) if coverage else set()
    missing = [aspect for aspect in analysis.required_aspects if aspect not in covered]
    partial = "PARTIAL" in statuses or any(item.metadata.get("partialEvidence") is True for item in records)
    if degraded:
        return EvidenceStatus.RETRIEVAL_DEGRADED, missing
    required = set(analysis.required_aspects)
    if any(required.issubset(item_coverage) for item_coverage in coverage):
        return EvidenceStatus.SUFFICIENT, []
    if partial or (covered and covered != {"TOPIC"}):
        return EvidenceStatus.PARTIAL, missing
    return EvidenceStatus.INSUFFICIENT, missing


def _evidence_aspects(query: str, content: str, analysis: QuestionAnalysis) -> set[str]:
    aspects: set[str] = set()
    query_clauses = extract_clause_numbers(query)
    content_clauses = extract_clause_numbers(content)
    if _topic_coverage(query, content) or (query_clauses and query_clauses.intersection(content_clauses)):
        aspects.add("TOPIC")
    if query_clauses and not query_clauses.intersection(content_clauses):
        return aspects
    patterns = {
        "DEFINITION": r"是指|定义为|称为|是.{0,12}(?:作业|行为|状态|设施|过程)",
        "NUMBER": r"\d+(?:\.\d+)?\s*(?:毫米|厘米|米|千米|平方米|立方米|%|％|个|人|天|小时|级)",
        "DATE": r"\d{4}\s*年\s*\d{1,2}\s*月(?:\s*\d{1,2}\s*日)?|\d+\s*(?:日|天|月|年|小时)(?:前|内|后)|之前|之后|期限",
        "SCOPE": r"适用于|适用范围|适用对象|包括|不适用于",
        "ACTOR": r"(?:单位|负责人|人员|部门|机构|建设方|施工方|监理方)",
        "RESPONSIBILITY": r"负责|承担|履行|责任|职责",
        "PROCESS": r"按照|依次|首先|然后|最后|步骤|流程|程序|、.{1,20}、",
        "COMPARISON": r"高于|低于|大于|小于|不同|区别|分别|相比|而",
        "MODALITY": r"可以|允许|应当|应|必须|不得|禁止|严禁|不允许",
        "ASSERTION": r"是|为|有|采用|执行|实施|要求|规定",
    }
    if re.search(patterns.get(analysis.required_aspects[-1], r"$^"), content):
        aspects.add(analysis.required_aspects[-1])
    for aspect in analysis.required_aspects[1:-1]:
        if re.search(patterns[aspect], content):
            aspects.add(aspect)
    return aspects


def _topic_coverage(query: str, content: str) -> bool:
    query_text = compact_search_text(query)
    content_text = compact_search_text(content)
    removable = (
        "什么", "多少", "多大", "何时", "时候", "日期", "期限", "范围", "适用", "哪些",
        "对象", "谁", "负责", "责任", "流程", "步骤", "如何", "怎么", "区别", "比较",
        "不同", "是否", "能否", "可否", "允许", "要求", "应为", "是什么", "有什么",
    )
    for value in removable:
        query_text = query_text.replace(value, "")
    query_clauses = extract_clause_numbers(query)
    for clause in query_clauses:
        query_text = query_text.replace(clause, "").replace(f"第{clause}条", "")
    bigrams = set(iter_bigrams(query_text))
    return bool(bigrams) and sum(item in content_text for item in bigrams) / len(bigrams) >= 0.35


def _safe_components(usage: dict[str, Any]) -> list[str]:
    allowed = {"EMBEDDING", "RERANKER", "QUERY_REWRITE", "RETRIEVAL"}
    values = usage.get("degradedComponents", [])
    return [value for value in values if value in allowed]


class RetrievalOrchestrator:
    def __init__(
        self,
        search: Search,
        rewrite: Rewrite | None = None,
        round_timeout_seconds: float = 30.0,
        total_timeout_seconds: float = 45.0,
    ):
        self.search = search
        self.rewrite = rewrite or self._rule_rewrite
        self.round_timeout_seconds = round_timeout_seconds
        self.total_timeout_seconds = total_timeout_seconds

    async def retrieve(self, request: DynamicRetrievalRequest) -> tuple[DynamicRetrievalData, dict[str, Any]]:
        fingerprints = [query_fingerprint(request)]
        deadline = asyncio.get_running_loop().time() + self.total_timeout_seconds
        try:
            first, first_usage = await self._search_with_deadline(
                request.as_rag_search_request(), deadline
            )
        except TimeoutError:
            diagnostics = RetrievalDiagnostics(
                questionType=analyze_question(request.query).kind,
                queryFingerprints=fingerprints,
                stopReason="TIMEOUT",
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
        records = first.records[:request.topK]
        rounds = 1

        if status == EvidenceStatus.INSUFFICIENT:
            try:
                async with asyncio.timeout(self._remaining(deadline)):
                    rewritten = self.rewrite(request, records)
                    rewritten_query = await rewritten if inspect.isawaitable(rewritten) else rewritten
                rewritten_query = rewritten_query.strip()
            except TimeoutError:
                status = EvidenceStatus.TIMEOUT
                stop_reason = "TIMEOUT"
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
                        second, second_usage = await self._search_with_deadline(
                            request.as_rag_search_request(rewritten_query), deadline
                        )
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
                    records = merge_records(first.records, second.records, request.topK)
                    if not second_timed_out:
                        # Rewriting improves recall; it must not change the question's evidence obligations.
                        status, missing = evaluate_evidence(records, degraded, request.query)

        diagnostics = RetrievalDiagnostics(
            candidateCount=len(records),
            questionType=analyze_question(request.query).kind,
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

    async def _search_with_deadline(self, search_request: Any, deadline: float):
        timeout_seconds = min(self.round_timeout_seconds, self._remaining(deadline))
        async with asyncio.timeout(timeout_seconds):
            return await self.search(search_request)

    @staticmethod
    def _remaining(deadline: float) -> float:
        return max(0.0, deadline - asyncio.get_running_loop().time())

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
