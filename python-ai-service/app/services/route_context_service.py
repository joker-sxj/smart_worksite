import json
import re
from typing import Any
from app.models.schemas import (
    Message, RouteRequest, RouteData, ContextPrepareRequest, ContextPrepareData,
    ContextResolveRequest, ContextResolveData, ContextFinalizeRequest, ContextFinalizeData,
    SessionConstraints, SessionSummary,
)
from .qwen_client import QwenClient
from .normalization import as_dict_list, as_string_list

ROUTE_TYPES = {"MODEL", "KNOWLEDGE", "DATABASE", "HYBRID", "NEED_MORE_INFO"}


class RouteService:
    def __init__(self, qwen: QwenClient):
        self.qwen = qwen

    async def route(self, request: RouteRequest) -> tuple[RouteData, dict]:
        system = (
            "你是智慧工地请求路由器。只能返回JSON，字段为routeType、reason、requiredResources、followUpQuestions。"
            "routeType只能是MODEL、KNOWLEDGE、DATABASE、HYBRID、NEED_MORE_INFO。"
            "只能选择请求中实际提供的资源：没有知识库时禁止选择KNOWLEDGE，没有数据源时禁止选择DATABASE；"
            "HYBRID要求知识库和数据源同时可用。法规条文、制度和文档内容属于KNOWLEDGE，"
            "DATABASE仅用于结构化表数据的明细、筛选、统计和聚合。"
        )
        prompt = {
            "question": request.question,
            "availableKnowledgeBases": request.availableKnowledgeBases,
            "availableDataSources": request.availableDataSources,
        }
        messages = [Message(role="system", content=system), *request.contextMessages,
                    Message(role="user", content=json.dumps(prompt, ensure_ascii=False))]
        try:
            data, usage = await self.qwen.json_chat(messages)
            route_type = str(data.get("routeType", "MODEL")).upper()
            if route_type not in ROUTE_TYPES:
                route_type = "MODEL"
            route_type, availability_reason = constrain_route_to_available_resources(
                route_type, bool(request.availableKnowledgeBases), bool(request.availableDataSources)
            )
            reason = str(data.get("reason", "基于问题内容选择默认模型回答。"))
            if availability_reason:
                reason = f"{reason} {availability_reason}"
            return RouteData(
                routeType=route_type,
                reason=reason,
                requiredResources=as_dict_list(data.get("requiredResources")),
                followUpQuestions=as_string_list(data.get("followUpQuestions")),
            ), usage
        except Exception:
            route_type = "DATABASE" if request.availableDataSources and any(k in request.question for k in ["统计", "数量", "多少", "列表"]) else "KNOWLEDGE" if request.availableKnowledgeBases else "MODEL"
            return RouteData(routeType=route_type, reason="Qwen路由失败，使用本地规则降级。"), {}


def constrain_route_to_available_resources(route_type: str, has_knowledge: bool, has_database: bool) -> tuple[str, str]:
    if route_type == "KNOWLEDGE" and not has_knowledge:
        return "MODEL", "当前未选择可用知识库，已改用模型回答。"
    if route_type == "DATABASE" and not has_database:
        return "MODEL", "当前未选择可用数据源，已改用模型回答。"
    if route_type == "HYBRID":
        if has_knowledge and has_database:
            return route_type, ""
        if has_knowledge:
            return "KNOWLEDGE", "当前未选择可用数据源，已改用知识库回答。"
        if has_database:
            return "DATABASE", "当前未选择可用知识库，已改用数据库回答。"
        return "MODEL", "当前未选择知识库或数据源，已改用模型回答。"
    return route_type, ""


class ContextService:
    def __init__(self, qwen: QwenClient):
        self.qwen = qwen

    async def prepare(self, request: ContextPrepareRequest) -> tuple[ContextPrepareData, dict]:
        total = 0
        selected: list[Message] = []
        for message in reversed(request.messages):
            total += len(message.content)
            if total > request.maxContextLength:
                break
            selected.append(message)
        selected.reverse()
        selected.append(Message(role="user", content=request.currentQuestion))
        missing = [] if len(request.currentQuestion.strip()) >= 6 else ["问题描述"]
        follow = [] if not missing else ["请补充更具体的问题背景或目标。"]
        return ContextPrepareData(
            contextMessages=selected,
            referencedMessageIds=[m.messageId for m in selected if m.messageId],
            missingFields=missing,
            followUpQuestions=follow,
        ), {}

    async def resolve_question(self, request: ContextResolveRequest) -> tuple[ContextResolveData, dict]:
        original = _clean_text(request.currentQuestion, 2000)
        fallback = ContextResolveData(
            standaloneQuestion=original,
            contextDependent=_looks_context_dependent(original),
            usedFallback=True,
        )
        messages = [
            Message(role="system", content=(
                "你是智慧工地对话问题改写器。仅使用提供的会话摘要和最近消息补全指代，禁止补造事实。"
                "只返回JSON对象：standaloneQuestion为可独立检索的问题，contextDependent为布尔值。"
            )),
            Message(role="user", content=json.dumps({
                "currentQuestion": original,
                "summary": request.summary.model_dump(),
                "recentMessages": [
                    {"role": item.role[:20], "content": item.content[:2000]}
                    for item in request.recentMessages[-10:]
                ],
            }, ensure_ascii=False)),
        ]
        try:
            data, usage = await self.qwen.json_chat(messages)
            standalone = data.get("standaloneQuestion") if isinstance(data, dict) else None
            if not isinstance(standalone, str):
                return fallback, usage if isinstance(usage, dict) else {}
            standalone = _clean_text(standalone, 2000)
            if not standalone:
                return fallback, usage if isinstance(usage, dict) else {}
            dependent = data.get("contextDependent")
            if not isinstance(dependent, bool):
                dependent = _looks_context_dependent(original) or standalone != original
            return ContextResolveData(
                standaloneQuestion=standalone,
                contextDependent=dependent,
                usedFallback=False,
            ), usage if isinstance(usage, dict) else {}
        except Exception:
            return fallback, {}

    async def finalize_answer(self, request: ContextFinalizeRequest) -> tuple[ContextFinalizeData, dict]:
        safe_existing = _normalize_summary(request.summary.model_dump())
        messages = [
            Message(role="system", content=(
                "你是智慧工地会话整理器。只根据输入更新安全的结构化摘要并生成相关延伸问题。"
                "只返回JSON对象，字段summary和suggestedFollowUpQuestions。summary仅允许topics、standards、"
                "constraints、confirmedFacts、userCorrections、openQuestions；延伸问题最多3条。"
            )),
            Message(role="user", content=json.dumps({
                "currentQuestion": request.currentQuestion[:2000],
                "answer": request.answer[:8000],
                "summary": safe_existing.model_dump(),
                "alreadyAnsweredQuestions": request.alreadyAnsweredQuestions[-50:],
            }, ensure_ascii=False)),
        ]
        try:
            data, usage = await self.qwen.json_chat(messages)
            raw_summary = data.get("summary") if isinstance(data, dict) else None
            used_fallback = not isinstance(raw_summary, dict)
            summary = _normalize_summary(
                _merge_summary_values(safe_existing.model_dump(), raw_summary)
                if isinstance(raw_summary, dict) else safe_existing.model_dump()
            )
            raw_questions = data.get("suggestedFollowUpQuestions") if isinstance(data, dict) else []
            questions = _normalize_follow_ups(
                raw_questions, request.currentQuestion, request.alreadyAnsweredQuestions
            )
            return ContextFinalizeData(
                summary=summary,
                suggestedFollowUpQuestions=questions,
                usedFallback=used_fallback,
            ), usage if isinstance(usage, dict) else {}
        except Exception:
            return ContextFinalizeData(
                summary=safe_existing,
                suggestedFollowUpQuestions=[],
                usedFallback=True,
            ), {}


def _clean_text(value: str, limit: int) -> str:
    return re.sub(r"\s+", " ", value).strip()[:limit].strip()


def _string_list(value: Any, count: int, length: int) -> list[str]:
    values = value if isinstance(value, list) else [value] if isinstance(value, str) else []
    result: list[str] = []
    seen: set[str] = set()
    for item in values:
        if not isinstance(item, str):
            continue
        cleaned = _clean_text(item, length)
        key = cleaned.casefold()
        if cleaned and key not in seen:
            result.append(cleaned)
            seen.add(key)
        if len(result) >= count:
            break
    return result


def _normalize_summary(value: Any) -> SessionSummary:
    raw = value if isinstance(value, dict) else {}
    constraints = raw.get("constraints") if isinstance(raw.get("constraints"), dict) else {}

    def constraint(name: str, limit: int) -> str | None:
        item = constraints.get(name)
        return _clean_text(item, limit) if isinstance(item, str) and item.strip() else None

    return SessionSummary(
        topics=_string_list(raw.get("topics"), 10, 200),
        standards=_string_list(raw.get("standards"), 10, 200),
        constraints=SessionConstraints(
            region=constraint("region", 100),
            time=constraint("time", 100),
            subject=constraint("subject", 200),
        ),
        confirmedFacts=_string_list(raw.get("confirmedFacts"), 20, 500),
        userCorrections=_string_list(raw.get("userCorrections"), 10, 500),
        openQuestions=_string_list(raw.get("openQuestions"), 10, 500),
    )


def _merge_summary_values(existing: dict[str, Any], update: dict[str, Any]) -> dict[str, Any]:
    merged = dict(existing)
    merged.update({key: value for key, value in update.items() if value is not None})
    existing_constraints = existing.get("constraints") if isinstance(existing.get("constraints"), dict) else {}
    update_constraints = update.get("constraints") if isinstance(update.get("constraints"), dict) else {}
    merged["constraints"] = {**existing_constraints, **update_constraints}
    return merged


def _question_key(value: str) -> str:
    return re.sub(r"[\s，。！？、,.!?;；:：]+", "", value).casefold()


def _normalize_follow_ups(value: Any, current: str, answered: list[str]) -> list[str]:
    excluded = {_question_key(current), *(_question_key(item) for item in answered)}
    result: list[str] = []
    for question in _string_list(value, 20, 300):
        question = re.sub(r"^\s*(?:[-*•]|\d+[.)、])\s*", "", question).strip()
        key = _question_key(question)
        if not key or key in excluded:
            continue
        excluded.add(key)
        result.append(question)
        if len(result) == 3:
            break
    return result


def _looks_context_dependent(question: str) -> bool:
    markers = ("这个", "那个", "它", "上述", "前者", "后者", "刚才", "那", "其")
    return any(marker in question for marker in markers)
