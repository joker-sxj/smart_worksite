import json
from typing import Any

from app.models.schemas import AgentInvokeRequest, AgentInvokeData, AgentStep, Message
from .qwen_client import QwenClient


class ComplianceReviewService:
    REQUIRED_ISSUE_FIELDS = ["issueId", "severity", "location", "ruleName", "description", "suggestion"]
    ALLOWED_SEVERITIES = {"LOW", "MEDIUM", "HIGH", "CRITICAL"}

    def __init__(self, qwen: QwenClient):
        self.qwen = qwen

    async def review(self, request: AgentInvokeRequest) -> tuple[AgentInvokeData, dict[str, Any]]:
        system = (
            "你是智慧工地合规审查专家。必须依据行业准则或用户选择的审查模板，对上传文档进行审查。"
            "只返回JSON对象，字段为summary、score、issues。issues数组中每项必须包含"
            "issueId、severity、location、ruleName、description、suggestion、status。"
            "location要能定位到页码/章节/条款，ruleName写明行业准则或模板条款，suggestion给出可直接修改的建议。"
            "severity只能为LOW、MEDIUM、HIGH、CRITICAL，status默认为OPEN。"
        )
        payload = {
            "goal": request.goal,
            "parameters": request.parameters,
            "contextMessages": [item.model_dump() for item in request.contextMessages],
            "requiredIssueFields": self.REQUIRED_ISSUE_FIELDS,
        }
        data, usage = await self.qwen.json_chat([
            Message(role="system", content=system),
            Message(role="user", content=json.dumps(payload, ensure_ascii=False)),
        ])
        normalized = self._normalize_result(data)
        return AgentInvokeData(
            result=json.dumps(normalized, ensure_ascii=False),
            steps=[AgentStep(step="COMPLIANCE_REVIEW", result="已按审查模板和行业准则生成结构化审查结果")],
            followUpQuestions=[],
        ), usage

    def _normalize_result(self, data: dict[str, Any]) -> dict[str, Any]:
        issues = data.get("issues")
        if not isinstance(issues, list):
            raise RuntimeError("compliance review result missing issues array")
        normalized_issues: list[dict[str, Any]] = []
        for index, issue in enumerate(issues, start=1):
            if not isinstance(issue, dict):
                raise RuntimeError("compliance review issue must be object")
            normalized_issue = dict(issue)
            for field in self.REQUIRED_ISSUE_FIELDS:
                if not str(normalized_issue.get(field, "")).strip():
                    raise RuntimeError(f"compliance review issue missing required field: {field}")
            severity = str(normalized_issue.get("severity", "")).upper()
            if severity not in self.ALLOWED_SEVERITIES:
                raise RuntimeError("compliance review issue severity must be LOW, MEDIUM, HIGH or CRITICAL")
            normalized_issue["severity"] = severity
            normalized_issue.setdefault("issueId", f"ISSUE-{index:03d}")
            normalized_issue.setdefault("status", "OPEN")
            normalized_issues.append(normalized_issue)
        score = data.get("score", 100 if not normalized_issues else 80)
        return {
            "summary": str(data.get("summary", "审查完成")),
            "score": score,
            "issues": normalized_issues,
        }
