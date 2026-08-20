from typing import Any, Generic, TypeVar
from pydantic import BaseModel, Field, PositiveInt, model_validator

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
    projectId: PositiveInt
    knowledgeBaseIds: list[PositiveInt] = Field(min_length=1)
    libraryTypes: list[str] = Field(default_factory=list)
    topK: int = 5
    scoreThreshold: float | None = None
    rerankEnabled: bool = True

    @model_validator(mode="after")
    def normalize_scope(self):
        self.knowledgeBaseIds = list(dict.fromkeys(self.knowledgeBaseIds))
        return self


class RagRecord(BaseModel):
    title: str
    contentSnippet: str
    sourceType: str
    sourceId: str | None = None
    score: float
    metadata: dict[str, Any] = Field(default_factory=dict)


class RagSearchData(BaseModel):
    records: list[RagRecord] = Field(default_factory=list)


class RagDocumentBlock(BaseModel):
    blockId: str = Field(min_length=1)
    blockType: str = Field(min_length=1)
    content: str = Field(min_length=1)
    location: dict[str, Any] = Field(default_factory=dict)
    structuredData: dict[str, Any] = Field(default_factory=dict)


class RagDocument(BaseModel):
    documentId: str = Field(min_length=1)
    title: str = Field(min_length=1)
    content: str
    sourceType: str = "DOCUMENT"
    sourceId: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    blocks: list[RagDocumentBlock] = Field(default_factory=list)


class RagIndexRequest(BaseModel):
    projectId: PositiveInt
    knowledgeBaseId: PositiveInt
    documents: list[RagDocument] = Field(min_length=1)
    chunkSize: int | None = None
    chunkOverlap: int | None = None

    @model_validator(mode="after")
    def normalize_documents(self):
        document_ids: list[str] = []
        for document in self.documents:
            document.documentId = document.documentId.strip()
            document.title = document.title.strip()
            if not document.documentId or not document.title:
                raise ValueError("document identity must not be blank")
            document_ids.append(document.documentId)
        if len(document_ids) != len(set(document_ids)):
            raise ValueError("documentId must be unique within an index request")
        return self


class RagIndexData(BaseModel):
    indexedDocuments: int
    indexedChunks: int
    provider: str


class RagDeleteRequest(BaseModel):
    projectId: PositiveInt
    sourceType: str = Field(min_length=1)
    sourceIds: list[str] = Field(min_length=1)
    excludeKnowledgeBaseId: PositiveInt | None = None

    @model_validator(mode="after")
    def normalize_scope(self):
        self.sourceType = self.sourceType.strip()
        self.sourceIds = list(dict.fromkeys(value.strip() for value in self.sourceIds))
        if not self.sourceType or any(not value for value in self.sourceIds):
            raise ValueError("delete scope must not contain blank values")
        return self


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


class DatabaseQueryPlan(BaseModel):
    entities: list[str] = Field(default_factory=list)
    metrics: list[dict[str, Any] | str] = Field(default_factory=list)
    dimensions: list[str] = Field(default_factory=list)
    filters: list[dict[str, Any] | str] = Field(default_factory=list)
    projectScopeField: str | None = None
    expectedColumns: list[str] = Field(default_factory=list)
    expectedShape: str = "ROWS"
    ambiguities: list[str] = Field(default_factory=list)


class DatabaseGenerateQueryData(BaseModel):
    sql: str
    parameters: dict[str, Any] = Field(default_factory=dict)
    explanation: str
    riskLevel: str = "LOW"
    plan: DatabaseQueryPlan = Field(default_factory=DatabaseQueryPlan)


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
    downloadUrl: str | None = None
    dataUrls: list[str] = Field(default_factory=list)


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
    recognized: bool = False
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


class DocumentUnderstandingPageInput(BaseModel):
    pageNo: int
    nativeText: str = ""
    imageDataUrl: str | None = None


class DocumentUnderstandingRequest(BaseModel):
    pages: list[DocumentUnderstandingPageInput] = Field(default_factory=list)
    minNativeTextChars: int = 20
    maxPages: int = 100
    maxTextChars: int = 120000


class DocumentUnderstandingPageData(BaseModel):
    pageNo: int
    source: str
    text: str = ""
    truncated: bool = False


class DocumentUnderstandingData(BaseModel):
    text: str = ""
    totalTextChars: int = 0
    truncated: bool = False
    pages: list[DocumentUnderstandingPageData] = Field(default_factory=list)
