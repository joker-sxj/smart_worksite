from app.models.schemas import Message, ModelInvokeRequest, AgentInvokeRequest, AgentInvokeData, AgentStep
from .agent_tools import ToolCallingAgent, ToolRegistry
from .compliance_review_service import ComplianceReviewService
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
        if request.goal == "COMPLIANCE_REVIEW":
            return await ComplianceReviewService(self.qwen).review(request)
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
