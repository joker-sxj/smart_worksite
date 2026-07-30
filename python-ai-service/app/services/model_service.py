import json
from typing import Any

from app.models.schemas import Message, ModelInvokeRequest, AgentInvokeRequest, AgentInvokeData, AgentStep
from .agent_tools import ToolCallingAgent, ToolRegistry
from .qwen_client import QwenClient


class ModelService:
    def __init__(self, qwen: QwenClient):
        self.qwen = qwen

    async def invoke(self, request: ModelInvokeRequest):
        messages: list[Message] = []
        if request.systemPrompt:
            messages.append(Message(role="system", content=request.systemPrompt))
        messages.extend(request.contextMessages)
        messages.append(Message(role="user", content=request.prompt))
        return await self.qwen.chat(messages, request.modelName, request.parameters)


class AgentService:
    def __init__(self, qwen: QwenClient, registry: ToolRegistry | None = None):
        self.qwen = qwen
        self.registry = registry or ToolRegistry()

    async def invoke(self, request: AgentInvokeRequest) -> tuple[AgentInvokeData, dict]:
        if str(request.goal).upper() == "COMPLIANCE_REVIEW":
            return await self._invoke_compliance_review(request)
        if request.tools:
            return await ToolCallingAgent(self.qwen, self.registry).invoke(request)
        system = "你是智慧工地智能体。请进行任务拆解，给出简洁可执行结果，并在信息不足时给出主动追问。"
        tool_text = ", ".join(request.tools) if request.tools else "无外部工具"
        messages = [Message(role="system", content=system), *request.contextMessages,
                    Message(role="user", content=f"目标：{request.goal}\n可用工具：{tool_text}")]
        answer, usage = await self.qwen.chat(messages, parameters=request.parameters)
        data = AgentInvokeData(
            result=answer,
            steps=[AgentStep(step="QWEN_AGENT_REASONING", result="已调用Qwen完成任务分析")],
            followUpQuestions=[] if "?" not in answer and "？" not in answer else ["请补充任务所需的关键业务条件。"],
        )
        return data, usage


    async def _invoke_compliance_review(self, request: AgentInvokeRequest) -> tuple[AgentInvokeData, dict]:
        parameters = request.parameters or {}
        system = (
            "你是智慧工地合规审查智能体。必须基于审查模板和被审查文件正文识别问题，"
            "只能返回合法JSON对象，字段为summary、score、issues、metadata。"
            "issues为数组，每项包含issueId、severity、location、ruleName、description、suggestion、status。"
            "不要输出Markdown代码块、解释性前后缀或非JSON文本。"
        )
        prompt = {
            "templateName": parameters.get("templateName"),
            "templateType": parameters.get("templateType"),
            "templateContent": parameters.get("templateContent") or "",
            "templateContentTruncated": bool(parameters.get("templateContentTruncated")),
            "reviewFileName": parameters.get("reviewFileName"),
            "reviewFileContent": parameters.get("reviewFileContent") or "",
            "reviewFileContentTruncated": bool(parameters.get("reviewFileContentTruncated")),
            "expectedResultSchema": parameters.get("expectedResultSchema"),
        }
        messages = [
            Message(role="system", content=system),
            *request.contextMessages,
            Message(role="user", content=json.dumps(prompt, ensure_ascii=False)),
        ]
        try:
            result, usage = await self.qwen.json_chat(messages, parameters={"response_format": {"type": "json_object"}})
        except Exception as exc:
            return self._compliance_review_fallback(request, f"model_json_error: {exc}"), {}
        normalized = self._normalize_compliance_result(result, parameters)
        return AgentInvokeData(
            result=json.dumps(normalized, ensure_ascii=False),
            steps=[AgentStep(step="COMPLIANCE_REVIEW_JSON", result="已基于模板和上传文件正文完成JSON合规审查")],
            followUpQuestions=[],
        ), usage or {}

    def _normalize_compliance_result(self, result: dict[str, Any], parameters: dict[str, Any]) -> dict[str, Any]:
        issues = result.get("issues") if isinstance(result.get("issues"), list) else []
        normalized_issues = []
        for index, issue in enumerate(issues, start=1):
            if not isinstance(issue, dict):
                continue
            normalized = dict(issue)
            normalized.setdefault("issueId", f"ISSUE-{index:03d}")
            normalized.setdefault("status", "OPEN")
            normalized_issues.append(normalized)
        metadata = result.get("metadata") if isinstance(result.get("metadata"), dict) else {}
        metadata.update({
            "recordId": parameters.get("recordId"),
            "templateId": parameters.get("templateId"),
            "reviewFileId": parameters.get("reviewFileId"),
            "reviewFileName": parameters.get("reviewFileName"),
        })
        return {
            "summary": str(result.get("summary") or "合规审查已完成。"),
            "score": result.get("score") if isinstance(result.get("score"), (int, float)) else 0,
            "issues": normalized_issues,
            "metadata": metadata,
        }

    def _compliance_review_fallback(self, request: AgentInvokeRequest, reason: str) -> AgentInvokeData:
        parameters = request.parameters or {}
        result = {
            "summary": "审查智能体未能产出合法JSON，系统已保留任务并返回可落库结果；请检查模型配置或稍后重试。",
            "score": 0,
            "issues": [],
            "metadata": {
                "recordId": parameters.get("recordId"),
                "templateId": parameters.get("templateId"),
                "reviewFileId": parameters.get("reviewFileId"),
                "reviewFileName": parameters.get("reviewFileName"),
                "fallbackReason": reason,
            },
        }
        return AgentInvokeData(
            result=json.dumps(result, ensure_ascii=False),
            steps=[AgentStep(step="COMPLIANCE_REVIEW_FALLBACK", result=reason)],
            followUpQuestions=[],
        )
