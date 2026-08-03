import json
from app.models.schemas import Message, RouteRequest, RouteData, ContextPrepareRequest, ContextPrepareData
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
