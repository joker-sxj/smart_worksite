import json
from typing import Any

from app.models.schemas import Message, ModelInvokeRequest, AgentInvokeRequest, AgentInvokeData, AgentStep
from .context_budget import ContextBudgetRequest, ContextBudgetPlanner, EvidenceItem
from .agent_tools import ToolCallingAgent, ToolRegistry
from .qwen_client import QwenClient


class ModelService:
    def __init__(self, qwen: QwenClient, planner: ContextBudgetPlanner | None = None, settings: Any | None = None):
        self.qwen = qwen
        self.settings = settings
        self.planner = planner

    async def invoke(self, request: ModelInvokeRequest):
        if self.planner is None or self.settings is None or self.settings.chat_max_model_len <= 0:
            messages: list[Message] = []
            if request.systemPrompt:
                messages.append(Message(role="system", content=request.systemPrompt))
            messages.extend(request.contextMessages)
            messages.append(Message(role="user", content=request.prompt))
            return await self.qwen.chat(messages, request.modelName, request.parameters)

        settings = self.settings
        budget = ContextBudgetRequest(
            system_prompt=request.systemPrompt or "你是智慧工地智能问答助手。",
            current_question=request.prompt,
            history_messages=request.contextMessages,
            evidence_items=[EvidenceItem(
                content=item.content,
                source_id=item.sourceId,
                chunk_id=item.chunkId,
                document_id=item.documentId,
                document_title=item.title,
                page_number=item.pageNumber,
                slide_number=item.slideNumber,
                table_location=item.tableLocation,
                relevance=item.score,
                metadata=item.metadata,
            ) for item in request.evidenceItems],
            model_context_limit=settings.chat_max_model_len,
            requested_output_tokens=settings.resolved_context_output_reserve_tokens(),
            template_overhead_tokens=settings.context_template_overhead_tokens,
            safety_reserve_tokens=settings.resolved_context_safety_reserve_tokens(),
            history_budget_ratio=settings.context_history_budget_ratio,
            evidence_budget_ratio=settings.context_evidence_budget_ratio,
            history_candidate_limit=settings.context_history_candidate_limit,
        )
        planned = await self.planner.aplan(budget)
        parameters = dict(request.parameters or {})
        parameters.update(planned.model_parameters)
        answer, provider_usage = await self.qwen.chat(planned.context_messages, request.modelName, parameters)
        usage = dict(provider_usage or {})
        usage["contextUsage"] = planned.context_usage
        return answer, usage


class AgentService:
    def __init__(self, qwen: QwenClient, registry: ToolRegistry | None = None):
        self.qwen = qwen
        self.registry = registry or ToolRegistry()

    async def invoke(self, request: AgentInvokeRequest) -> tuple[AgentInvokeData, dict]:
        if str(request.goal).upper() == "COMPLIANCE_REVIEW":
            return await self._invoke_compliance_review(request)
        if str(request.goal).upper() == "COMPLIANCE_REVIEW_RULE":
            return await self._invoke_compliance_review_rule(request)
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

    async def _invoke_compliance_review_rule(self, request: AgentInvokeRequest) -> tuple[AgentInvokeData, dict]:
        parameters = request.parameters or {}
        system = (
            "你是智慧工地规则级合规审查智能体。主文件证据是被审查对象，参考依据只能用于解释规则，"
            "不得把参考资料中的描述误判为主文件问题。只能返回JSON对象，包含ruleId、decision、issues、"
            "confidence、manualConfirmationRequired。证据不足时必须要求人工确认，不得编造。"
        )
        prompt = {
            "ruleId": parameters.get("ruleId"),
            "ruleName": parameters.get("ruleName"),
            "ruleContent": parameters.get("ruleContent") or "",
            "primaryFileName": parameters.get("primaryFileName"),
            "primaryEvidence": parameters.get("primaryEvidence") or "",
            "referenceEvidence": parameters.get("referenceEvidence") or [],
        }
        result, usage = await self.qwen.json_chat(
            [Message(role="system", content=system), Message(role="user", content=json.dumps(prompt, ensure_ascii=False))],
            parameters={"response_format": {"type": "json_object"}},
        )
        normalized = self._normalize_compliance_rule_result(result, parameters)
        return AgentInvokeData(
            result=json.dumps(normalized, ensure_ascii=False),
            steps=[AgentStep(step="COMPLIANCE_REVIEW_RULE_JSON", result="已完成单条规则审查")],
            followUpQuestions=[],
        ), usage or {}

    def _normalize_compliance_rule_result(self, result: dict[str, Any], parameters: dict[str, Any]) -> dict[str, Any]:
        rule_id = str(parameters.get("ruleId") or result.get("ruleId") or "RULE-UNKNOWN")
        issues = []
        for index, issue in enumerate(result.get("issues") if isinstance(result.get("issues"), list) else [], start=1):
            if not isinstance(issue, dict):
                continue
            normalized = dict(issue)
            normalized.setdefault("issueId", f"{rule_id}-I{index:03d}")
            normalized.setdefault("severity", "MEDIUM")
            normalized.setdefault("location", str(parameters.get("primaryFileName") or "主文件"))
            normalized.setdefault("ruleName", str(parameters.get("ruleName") or rule_id))
            normalized.setdefault("suggestion", "请按审查规则整改并留存复核记录。")
            normalized.setdefault("status", "OPEN")
            issues.append(normalized)
        confidence = result.get("confidence")
        confidence = float(confidence) if isinstance(confidence, (int, float)) else 0.0
        manual = bool(result.get("manualConfirmationRequired")) or not parameters.get("primaryEvidence")
        return {
            "ruleId": rule_id,
            "decision": str(result.get("decision") or ("NEEDS_MANUAL_CONFIRMATION" if manual else "UNKNOWN")),
            "issues": issues,
            "confidence": max(0.0, min(1.0, confidence)),
            "manualConfirmationRequired": manual,
        }


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
