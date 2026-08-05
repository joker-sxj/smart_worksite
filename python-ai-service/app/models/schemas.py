from typing import Any, Generic, TypeVar
from pydantic import BaseModel, Field

T = TypeVar("T")


class StandardResponse(BaseModel, Generic[T]):
    success: bool = True
    traceId: str
    data: T | None = None
    usage: dict[str, Any] = Field(default_factory=dict)
    errorCode: str | None = None
    errorMessage: str | None = None


class Message(BaseModel):
    role: str
    content: str
    messageId: str | None = None


class ModelInvokeRequest(BaseModel):
    prompt: str
    systemPrompt: str | None = None
    modelName: str | None = None
    parameters: dict[str, Any] = Field(default_factory=dict)
    contextMessages: list[Message] = Field(default_factory=list)


class ModelInvokeData(BaseModel):
    answer: str
    usage: dict[str, Any] = Field(default_factory=dict)


class AgentInvokeRequest(BaseModel):
    goal: str
    tools: list[str] = Field(default_factory=list)
    contextMessages: list[Message] = Field(default_factory=list)
    parameters: dict[str, Any] = Field(default_factory=dict)


class AgentStep(BaseModel):
    step: str
    result: str


class AgentInvokeData(BaseModel):
    result: str
    steps: list[AgentStep] = Field(default_factory=list)
    followUpQuestions: list[str] = Field(default_factory=list)


class RagSearchRequest(BaseModel):
    query: str
    projectId: int | None = None
    knowledgeBaseIds: list[int] = Field(default_factory=list)
    libraryTypes: list[str] = Field(default_factory=list)
    topK: int = 5
    scoreThreshold: float | None = None
    rerankEnabled: bool = True


class RagRecord(BaseModel):
    title: str
    contentSnippet: str
    sourceType: str
    sourceId: str | None = None
    score: float
    metadata: dict[str, Any] = Field(default_factory=dict)


class RagSearchData(BaseModel):
    records: list[RagRecord] = Field(default_factory=list)


class RagDocument(BaseModel):
    documentId: str
    title: str
    content: str
    sourceType: str = "DOCUMENT"
    sourceId: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class RagIndexRequest(BaseModel):
    projectId: int
    knowledgeBaseId: int | None = None
    documents: list[RagDocument] = Field(default_factory=list)
    chunkSize: int | None = None
    chunkOverlap: int | None = None


class RagIndexData(BaseModel):
    indexedDocuments: int
    indexedChunks: int
    provider: str


class RagDeleteRequest(BaseModel):
    projectId: int
    sourceType: str
    sourceIds: list[str] = Field(default_factory=list)
    excludeKnowledgeBaseId: int | None = None


class RagDeleteData(BaseModel):
    deletedChunks: int
    provider: str


class RouteRequest(BaseModel):
    question: str
    availableKnowledgeBases: list[dict[str, Any]] = Field(default_factory=list)
    availableDataSources: list[dict[str, Any]] = Field(default_factory=list)
    contextMessages: list[Message] = Field(default_factory=list)


class RouteData(BaseModel):
    routeType: str
    reason: str
    requiredResources: list[dict[str, Any]] = Field(default_factory=list)
    followUpQuestions: list[str] = Field(default_factory=list)


class ContextPrepareRequest(BaseModel):
    messages: list[Message] = Field(default_factory=list)
    currentQuestion: str
    maxContextLength: int = 6000


class ContextPrepareData(BaseModel):
    contextMessages: list[Message] = Field(default_factory=list)
    referencedMessageIds: list[str] = Field(default_factory=list)
    missingFields: list[str] = Field(default_factory=list)
    followUpQuestions: list[str] = Field(default_factory=list)


class DatabaseGenerateQueryRequest(BaseModel):
    question: str
    schemaSummary: str
    permissionHints: dict[str, Any] = Field(default_factory=dict)
    projectId: int | None = None
    databaseType: str | None = None
    failedSql: str | None = None
    databaseError: str | None = None
    attempt: int = 1


class DatabaseGenerateQueryData(BaseModel):
    sql: str
    parameters: dict[str, Any] = Field(default_factory=dict)
    explanation: str
    riskLevel: str = "LOW"


class DatabaseSummarizeRequest(BaseModel):
    question: str
    sql: str
    columns: list[str] = Field(default_factory=list)
    rows: list[dict[str, Any]] = Field(default_factory=list)


class DatabaseSummarizeData(BaseModel):
    summary: str
    insights: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class OcrFilePayload(BaseModel):
    fileId: int
    fileName: str
    contentType: str | None = None
    downloadUrl: str


class OcrRecognizeRequest(BaseModel):
    projectId: int
    recordId: int
    ocrType: str
    file: OcrFilePayload
    options: dict[str, Any] = Field(default_factory=dict)


class OcrFieldData(BaseModel):
    fieldKey: str
    fieldName: str
    fieldValue: str = ""
    confidence: float = 0
    location: str | None = None
    pageNo: int | None = None
    evidence: str | None = None


class OcrRecognizeData(BaseModel):
    ocrType: str
    confidence: float = 0
    fields: list[OcrFieldData] = Field(default_factory=list)
    extras: dict[str, Any] = Field(default_factory=dict)
    raw: dict[str, Any] = Field(default_factory=dict)


class PolicyCrawlRequest(BaseModel):
    projectId: int
    sourceId: int
    url: str
    lastCrawledAt: str | None = None


class PolicyCrawlArticle(BaseModel):
    title: str
    url: str
    summary: str = ""
    content: str
    publishDate: str | None = None
    category: str | None = None
    policyNo: str | None = None
    sourceName: str | None = None


class PolicyCrawlData(BaseModel):
    fetchedCount: int = 0
    message: str = ""
    articles: list[PolicyCrawlArticle] = Field(default_factory=list)
