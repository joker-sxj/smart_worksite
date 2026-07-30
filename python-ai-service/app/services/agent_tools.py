from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Callable, Awaitable

from app.models.schemas import Message, AgentInvokeRequest, AgentInvokeData, AgentStep
from .qwen_client import QwenClient
from .rag_service import RagService
from .database_service import DatabaseQaService

ToolFunc = Callable[[dict[str, Any]], Awaitable[dict[str, Any]]]


@dataclass
class ToolSpec:
    name: str
    description: str
    parameters: dict[str, Any]
    func: ToolFunc


class ToolRegistry:
    def __init__(self):
        self._tools: dict[str, ToolSpec] = {}

    def register(self, spec: ToolSpec) -> None:
        self._tools[spec.name] = spec

    def get(self, name: str) -> ToolSpec | None:
        return self._tools.get(name)

    def schemas(self, allowed: list[str]) -> list[dict[str, Any]]:
        names = set(allowed or self._tools.keys())
        return [
            {"name": spec.name, "description": spec.description, "parameters": spec.parameters}
            for spec in self._tools.values()
            if spec.name in names
        ]


class ToolCallingAgent:
    def __init__(self, qwen: QwenClient, registry: ToolRegistry, max_steps: int = 4):
        self.qwen = qwen
        self.registry = registry
        self.max_steps = max_steps

    async def invoke(self, request: AgentInvokeRequest) -> tuple[AgentInvokeData, dict[str, Any]]:
        steps: list[AgentStep] = []
        usage_total: dict[str, Any] = {}
        tool_schemas = self.registry.schemas(request.tools)
        if request.tools and not tool_schemas:
            if str(request.goal).upper() == "COMPLIANCE_REVIEW":
                return self._compliance_review_fallback(request), usage_total
            return AgentInvokeData(
                result="请求的工具当前不可用，请检查工具名称或服务配置。",
                steps=[AgentStep(step="TOOL_SELECTION", result="未找到可用工具：" + ",".join(request.tools))],
                followUpQuestions=[],
            ), usage_total
        system = (
            "你是智慧工地工具调用智能体。你可以多步调用工具。"
            "每轮只能返回JSON：{\"action\":\"tool|final|follow_up\",\"tool\":工具名,\"arguments\":{},\"answer\":\"...\",\"questions\":[...]}."
            f"可用工具：{json.dumps(tool_schemas, ensure_ascii=False)}"
        )
        messages = [Message(role="system", content=system), *request.contextMessages, Message(role="user", content=request.goal)]
        for _ in range(self.max_steps):
            try:
                decision, usage = await self.qwen.json_chat(messages, parameters=request.parameters)
                usage_total.update(usage or {})
            except Exception as exc:
                answer, usage = await self.qwen.chat(messages, parameters=request.parameters)
                usage_total.update(usage or {})
                return AgentInvokeData(result=answer, steps=steps, followUpQuestions=[]), usage_total
            action = str(decision.get("action", "final"))
            if action == "tool":
                tool_name = str(decision.get("tool", ""))
                if request.tools and tool_name not in set(request.tools):
                    messages.append(Message(role="assistant", content=json.dumps({"error": f"tool {tool_name} is not allowed"}, ensure_ascii=False)))
                    continue
                spec = self.registry.get(tool_name)
                if spec is None:
                    messages.append(Message(role="assistant", content=json.dumps({"error": f"unknown tool {tool_name}"}, ensure_ascii=False)))
                    continue
                args = decision.get("arguments") or {}
                result = await spec.func(args)
                steps.append(AgentStep(step=f"TOOL:{tool_name}", result=json.dumps(result, ensure_ascii=False)[:1200]))
                messages.append(Message(role="assistant", content=json.dumps(decision, ensure_ascii=False)))
                messages.append(Message(role="user", content="工具返回：" + json.dumps(result, ensure_ascii=False)))
                continue
            if action == "follow_up":
                return AgentInvokeData(result=str(decision.get("answer", "需要补充信息。")), steps=steps, followUpQuestions=decision.get("questions") or []), usage_total
            return AgentInvokeData(result=str(decision.get("answer", "")), steps=steps, followUpQuestions=[]), usage_total
        messages.append(Message(role="user", content="请基于已有工具结果给出最终答案。"))
        answer, usage = await self.qwen.chat(messages, parameters=request.parameters)
        usage_total.update(usage or {})
        return AgentInvokeData(result=answer, steps=steps, followUpQuestions=[]), usage_total

    def _compliance_review_fallback(self, request: AgentInvokeRequest) -> AgentInvokeData:
        parameters = request.parameters or {}
        result = {
            "summary": "审查智能体已接收审查任务，但当前文档解析/规则校验工具未注册，已返回可入库的空审查结果，请检查Python工具配置后重试以获得详细问题。",
            "score": 0,
            "issues": [],
            "metadata": {
                "recordId": parameters.get("recordId"),
                "templateId": parameters.get("templateId"),
                "templateName": parameters.get("templateName"),
                "reviewFileId": parameters.get("reviewFileId"),
                "reviewFileName": parameters.get("reviewFileName"),
                "fallbackReason": "requested compliance tools are unavailable",
            },
        }
        return AgentInvokeData(
            result=json.dumps(result, ensure_ascii=False),
            steps=[AgentStep(step="COMPLIANCE_REVIEW_FALLBACK", result="document_parse/compliance_rule_check tools unavailable; returned valid JSON result")],
            followUpQuestions=[],
        )
