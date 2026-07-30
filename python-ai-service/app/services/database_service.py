import json
from app.models.schemas import (
    Message,
    DatabaseGenerateQueryRequest,
    DatabaseGenerateQueryData,
    DatabaseSummarizeRequest,
    DatabaseSummarizeData,
)
from .qwen_client import QwenClient


class DatabaseQaService:
    def __init__(self, qwen: QwenClient):
        self.qwen = qwen

    async def generate_query(self, request: DatabaseGenerateQueryRequest) -> tuple[DatabaseGenerateQueryData, dict]:
        system = (
            "你是智慧工地数据库问答SQL生成器。只能返回JSON，字段为sql、parameters、explanation、riskLevel。"
            "只能生成只读SELECT或WITH查询，不允许写入、删除、DDL或多语句。"
        )
        prompt = {
            "question": request.question,
            "schemaSummary": request.schemaSummary,
            "permissionHints": request.permissionHints,
            "projectId": request.projectId,
        }
        data, usage = await self.qwen.json_chat([
            Message(role="system", content=system),
            Message(role="user", content=json.dumps(prompt, ensure_ascii=False)),
        ])
        return DatabaseGenerateQueryData(
            sql=str(data.get("sql", "")),
            parameters=data.get("parameters") or {},
            explanation=str(data.get("explanation", "根据问题生成只读查询。")),
            riskLevel=str(data.get("riskLevel", "LOW")),
        ), usage

    async def summarize_result(self, request: DatabaseSummarizeRequest) -> tuple[DatabaseSummarizeData, dict]:
        system = "你是智慧工地数据库问答结果总结助手。请根据用户问题、SQL和查询结果返回JSON，字段为summary、insights、warnings。"
        prompt = request.model_dump()
        data, usage = await self.qwen.json_chat([
            Message(role="system", content=system),
            Message(role="user", content=json.dumps(prompt, ensure_ascii=False, default=str)),
        ])
        return DatabaseSummarizeData(
            summary=str(data.get("summary", "暂无总结")),
            insights=_string_list(data.get("insights")),
            warnings=_string_list(data.get("warnings")),
        ), usage


def _string_list(value) -> list[str]:
    if value is None or value == "":
        return []
    if isinstance(value, list):
        return [str(item) for item in value if str(item).strip()]
    return [str(value)]
