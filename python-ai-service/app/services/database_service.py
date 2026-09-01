import json
from app.models.schemas import (
    Message,
    DatabaseGenerateQueryRequest,
    DatabaseGenerateQueryData,
    DatabaseQueryPlan,
    DatabaseSummarizeRequest,
    DatabaseSummarizeData,
)
from .qwen_client import QwenClient
from .normalization import as_dict, as_string_list, optional_string


class DatabaseQaService:
    def __init__(self, qwen: QwenClient):
        self.qwen = qwen

    async def generate_query(self, request: DatabaseGenerateQueryRequest) -> tuple[DatabaseGenerateQueryData, dict]:
        database_type = (request.databaseType or "UNKNOWN").upper()
        dialect_rules = ""
        if database_type == "MYSQL":
            dialect_rules = (
                "当前数据库方言为MySQL 8。使用SELECT DISTINCT时，ORDER BY中的表达式必须出现在SELECT列表中；"
                "如果排序字段不应展示，优先改写为子查询/聚合查询，外层再ORDER BY；"
                "涉及GROUP BY时只能选择分组字段或聚合表达式；必须使用已提供的表名、列名和别名。"
            )
        repair_instruction = ""
        if request.failedSql and request.databaseError:
            repair_instruction = (
                "上一次SQL执行或本地安全校验失败。请保持原问题语义，根据数据库错误修正SQL并返回完整的新SQL；"
                "必须返回与failedSql不同的一条只读SQL，不要解释错误，不要重复返回已失败的SQL。"
            )
        system = (
            "你是智慧工地数据库问答SQL生成器。先制定可校验的取数计划，再生成SQL。只能返回JSON，字段为sql、parameters、explanation、riskLevel、plan。"
            "plan必须包含entities、metrics、dimensions、filters、projectScopeField、expectedColumns、expectedShape、ambiguities；"
            "expectedColumns必须列出回答问题所需且SQL实际返回的全部列别名；存在projectId时优先识别并使用项目范围字段，禁止跨项目取数；"
            "不确定的业务含义写入ambiguities，但仍应基于schema中的表名、列名、注释、主外键自动选择最合理的只读方案。"
            "parameters必须是JSON对象，不能返回数组；无参数时返回空对象{}；使用?占位符时按顺序使用p1、p2等键名。"
            "只能生成一条只读SELECT或WITH查询；禁止返回多个语句，禁止使用分号，禁止写入、删除或DDL。"
            + dialect_rules
            + repair_instruction
        )
        prompt = {
            "question": request.question,
            "schemaSummary": request.schemaSummary,
            "permissionHints": request.permissionHints,
            "projectId": request.projectId,
            "databaseType": database_type,
            "failedSql": request.failedSql,
            "databaseError": request.databaseError,
            "attempt": request.attempt,
        }
        data, usage = await self.qwen.json_chat([
            Message(role="system", content=system),
            Message(role="user", content=json.dumps(prompt, ensure_ascii=False)),
        ])
        plan = as_dict(data.get("plan"))
        plan["projectScopeField"] = optional_string(plan.get("projectScopeField"))
        shape = plan.get("expectedShape")
        if isinstance(shape, list):
            shape = shape[0] if shape else None
        plan["expectedShape"] = optional_string(shape) or "ROWS"
        plan["expectedColumns"] = as_string_list(plan.get("expectedColumns"))
        return DatabaseGenerateQueryData(
            sql=str(data.get("sql", "")),
            parameters=as_dict(data.get("parameters")),
            explanation=str(data.get("explanation", "根据问题生成只读查询。")),
            riskLevel=str(data.get("riskLevel", "LOW")),
            plan=DatabaseQueryPlan(**plan),
        ), usage

    async def summarize_result(self, request: DatabaseSummarizeRequest) -> tuple[DatabaseSummarizeData, dict]:
        system = (
            "你是智慧工地数据库问答结果总结助手。请只依据用户问题、SQL、columns和rows中的真实证据返回JSON，字段为summary、insights、warnings。"
            "不得添加查询结果中不存在的数字、状态、时间、风险或结论；不得用常识补齐项目事实。"
            "空结果只能表述为未查询到符合当前条件的数据，不能推断不存在风险、已经完成或数量为零。"
        )
        prompt = request.model_dump()
        data, usage = await self.qwen.json_chat([
            Message(role="system", content=system),
            Message(role="user", content=json.dumps(prompt, ensure_ascii=False, default=str)),
        ])
        return DatabaseSummarizeData(
            summary=str(data.get("summary", "暂无总结")),
            insights=as_string_list(data.get("insights")),
            warnings=as_string_list(data.get("warnings")),
        ), usage
