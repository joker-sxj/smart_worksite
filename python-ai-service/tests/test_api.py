import os
import asyncio
import json
os.environ["AI_SERVICE_API_KEY"] = ""
os.environ["EMBEDDING_PROVIDER"] = "LOCAL_HASH"
os.environ["RAG_PROVIDER"] = "LOCAL"
os.environ["RAG_DATA_DIR"] = "/tmp/smart-worksite-test-rag"

from fastapi.testclient import TestClient
from app.main import app
from app.models.schemas import AgentInvokeRequest
from app.core.settings import Settings, get_settings
from app.services.qwen_client import QwenClient
from app.services.agent_tools import ToolCallingAgent, ToolRegistry, ToolSpec
from app.services.model_service import AgentService
from app.services.rag_service import RagService
from app.services.vector_store import ChunkRecord


def test_health_without_key_when_not_configured(monkeypatch):
    from app.api import routes

    async def fake_snapshot(_self):
        return {"status": "NOT_APPLICABLE", "profile": "unconfigured", "maxContextTokens": 0, "dependencies": {}}

    monkeypatch.setattr(routes.ModelReadinessService, "snapshot", fake_snapshot)
    client = TestClient(app)
    response = client.get("/v1/health")
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["status"] == "UP"


def test_health_without_key_when_service_key_is_configured(monkeypatch):
    from app.api import routes

    async def fake_snapshot(_self):
        return {"status": "NOT_APPLICABLE", "profile": "unconfigured", "maxContextTokens": 0, "dependencies": {}}

    monkeypatch.setattr(routes.ModelReadinessService, "snapshot", fake_snapshot)
    monkeypatch.setenv("AI_SERVICE_API_KEY", "configured-key")
    get_settings.cache_clear()
    try:
        client = TestClient(app)
        response = client.get("/v1/health")
        assert response.status_code == 200
        assert response.json()["data"]["status"] == "UP"
    finally:
        monkeypatch.setenv("AI_SERVICE_API_KEY", "")
        get_settings.cache_clear()


def test_rag_index_and_search_local_hash():
    client = TestClient(app)
    index_response = client.post("/v1/rag/index", json={
        "projectId": 1,
        "knowledgeBaseId": 1,
        "documents": [{
            "documentId": "doc-1",
            "title": "安全规范",
            "content": "施工现场必须正确佩戴安全帽，并按要求进行安全检查。",
            "sourceType": "DOCUMENT"
        }]
    })
    assert index_response.status_code == 200
    assert index_response.json()["success"] is True

    response = client.post("/v1/rag/search", json={"projectId": 1, "query": "安全帽规范", "topK": 1, "knowledgeBaseIds": [1]})
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert len(body["data"]["records"]) == 1


def test_local_rag_delete_removes_matching_old_policy_chunks_only(tmp_path):
    from app.services.vector_store import LocalJsonVectorStore

    store = LocalJsonVectorStore(str(tmp_path))
    chunks = [
        ChunkRecord("old", 1, 4, "10", "old", "policy", "POLICY_ARTICLE", "10", {}, [1.0]),
        ChunkRecord("kept-policy", 1, 6, "10", "new", "policy", "POLICY_ARTICLE", "10", {}, [1.0]),
        ChunkRecord("kept-doc", 1, 4, "10", "doc", "document", "DOCUMENT", "10", {}, [1.0]),
        ChunkRecord("other-project", 2, 4, "10", "other", "policy", "POLICY_ARTICLE", "10", {}, [1.0]),
    ]
    asyncio.run(store.upsert(chunks))

    deleted = asyncio.run(store.delete_sources(1, "POLICY_ARTICLE", ["10"], 6))
    deleted_again = asyncio.run(store.delete_sources(1, "POLICY_ARTICLE", ["10"], 6))

    assert deleted == 1
    assert deleted_again == 0
    assert {record.id for record in store._load()} == {"kept-policy", "kept-doc", "other-project"}

def test_tool_calling_agent_executes_registered_tool():
    class FakeQwen:
        def __init__(self):
            self.calls = 0

        async def json_chat(self, messages, parameters=None):
            self.calls += 1
            if self.calls == 1:
                return {
                    "action": "tool",
                    "tool": "echo_tool",
                    "arguments": {"text": "安全帽"},
                }, {"prompt_tokens": 1}
            return {
                "action": "final",
                "answer": "工具已返回安全帽检查结果",
            }, {"completion_tokens": 1}

    async def echo_tool(args):
        return {"echo": args["text"], "status": "OK"}

    registry = ToolRegistry()
    registry.register(ToolSpec(
        name="echo_tool",
        description="测试工具",
        parameters={"type": "object"},
        func=echo_tool,
    ))

    import asyncio
    data, usage = asyncio.run(ToolCallingAgent(FakeQwen(), registry).invoke(AgentInvokeRequest(
        goal="检查安全帽",
        tools=["echo_tool"],
    )))
    assert data.result == "工具已返回安全帽检查结果"
    assert len(data.steps) == 1
    assert data.steps[0].step == "TOOL:echo_tool"
    assert "安全帽" in data.steps[0].result
    assert usage["completion_tokens"] == 1




def test_compliance_review_agent_reviews_uploaded_document_content_as_json():
    class FakeQwen:
        def __init__(self):
            self.messages = None
            self.parameters = None

        async def json_chat(self, messages, model=None, parameters=None):
            self.messages = messages
            self.parameters = parameters
            return {
                "summary": "发现1项临边防护问题",
                "score": 70,
                "issues": [{
                    "severity": "HIGH",
                    "location": "施工方案第1节",
                    "ruleName": "临边防护",
                    "description": "方案未说明临边防护栏杆设置。",
                    "suggestion": "补充防护栏杆和验收要求。",
                }],
            }, {"prompt_tokens": 3}

    import asyncio
    qwen = FakeQwen()
    data, usage = asyncio.run(AgentService(qwen).invoke(AgentInvokeRequest(
        goal="COMPLIANCE_REVIEW",
        parameters={
            "recordId": 1,
            "templateId": 10,
            "reviewFileId": 99,
            "reviewFileName": "施工方案.docx",
            "reviewFileContent": "施工方案内容：临边未设置防护栏杆。",
            "templateContent": "检查临边洞口防护。",
        },
    )))

    result = json.loads(data.result)
    assert result["summary"] == "发现1项临边防护问题"
    assert result["issues"][0]["issueId"] == "ISSUE-001"
    assert result["issues"][0]["status"] == "OPEN"
    assert "临边未设置防护栏杆" in qwen.messages[-1].content
    assert qwen.parameters == {"response_format": {"type": "json_object"}}
    assert usage["prompt_tokens"] == 3

def test_compliance_review_with_unavailable_tools_returns_json_result():
    client = TestClient(app)

    response = client.post("/v1/agent/invoke", json={
        "goal": "COMPLIANCE_REVIEW",
        "tools": ["document_parse", "compliance_rule_check"],
        "parameters": {
            "recordId": 1,
            "templateId": 10,
            "templateName": "临边洞口审查模板",
            "reviewFileId": 99,
            "reviewFileName": "方案.docx",
        },
    })

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    result = json.loads(body["data"]["result"])
    assert isinstance(result["issues"], list)
    assert result["summary"]
    assert body["data"]["steps"][0]["step"].startswith("COMPLIANCE_REVIEW_")

def test_rag_uses_qwen_rerank_when_configured():
    class FakeSettings:
        rag_provider = "LOCAL"
        rag_data_dir = "/tmp/smart-worksite-test-rag"
        rag_rerank_top_k = 20
        embedding_provider = "LOCAL_HASH"
        rerank_provider = "QWEN"
        qwen_embedding_batch_size = 10

    class FakeQwen:
        async def rerank(self, query, documents, top_n):
            assert query == "helmet"
            assert top_n == 1
            return [
                {"index": 1, "relevance_score": 0.95},
                {"index": 0, "relevance_score": 0.1},
            ], {"rerank_tokens": 2}

    import asyncio
    service = RagService(FakeSettings(), FakeQwen())
    first = ChunkRecord("1", 1, 1, "d1", "weather", "rain today", "DOCUMENT", None, {}, [])
    second = ChunkRecord("2", 1, 1, "d2", "helmet", "wear helmet", "DOCUMENT", None, {}, [])
    records, usage = asyncio.run(service.rerank("helmet", [(first, 0.9, 0.9), (second, 0.2, 0.2)], 1))
    records.sort(key=lambda item: item[2], reverse=True)

    assert records[0][0].id == "2"
    assert usage["rerankProvider"] == "QWEN"
    assert usage["rerank_tokens"] == 2


def test_qwen_rerank_payload_supports_qwen3_and_legacy_styles():
    settings = Settings(qwen_api_key="test-key")
    client = QwenClient(settings)
    verified = client._build_rerank_payload("helmet", ["wear helmet"], 1)
    assert verified["model"] == "qwen3-rerank"
    assert verified["input"]["query"] == "helmet"
    assert verified["input"]["documents"] == ["wear helmet"]
    assert verified["parameters"]["top_n"] == 1

    legacy_settings = Settings(
        qwen_api_key="test-key",
        qwen_rerank_model="gte-rerank-v2",
        qwen_rerank_api_style="LEGACY",
    )
    legacy = QwenClient(legacy_settings)._build_rerank_payload("helmet", ["wear helmet"], 1)
    assert legacy["input"]["query"] == "helmet"
    assert legacy["input"]["documents"] == ["wear helmet"]
    assert legacy["parameters"]["top_n"] == 1

    qwen3_settings = Settings(qwen_api_key="test-key", qwen_rerank_api_style="QWEN3")
    qwen3 = QwenClient(qwen3_settings)._build_rerank_payload("helmet", ["wear helmet"], 1)
    assert qwen3["query"] == "helmet"
    assert qwen3["documents"] == ["wear helmet"]
    assert qwen3["top_n"] == 1


def test_qwen_vl_converts_image_url_to_data_url_before_provider_call(monkeypatch):
    import asyncio
    import base64
    from app.services import qwen_client as qwen_module

    settings = Settings(
        qwen_vl_api_key="test-key",
        qwen_vl_endpoint="https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
    )
    client = QwenClient(settings)
    image_bytes = b"fake-image"
    captured = {}

    class FakeResponse:
        status_code = 200
        text = ""

        def __init__(self, content=b"", headers=None, body=None):
            self.content = content
            self.headers = headers or {}
            self._body = body or {}

        def raise_for_status(self):
            return None

        def json(self):
            return self._body

    class FakeAsyncClient:
        def __init__(self, **kwargs):
            self.kwargs = kwargs

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return None

        async def get(self, url):
            assert url == "http://minio.local/id-card.jpg"
            captured["download_trust_env"] = self.kwargs.get("trust_env")
            return FakeResponse(content=image_bytes, headers={"content-type": "image/jpeg"})

        async def post(self, url, headers=None, json=None):
            assert url == settings.qwen_vl_endpoint
            captured["image_url"] = json["messages"][0]["content"][1]["image_url"]["url"]
            captured["max_tokens"] = json["max_tokens"]
            captured["presence_penalty"] = json["presence_penalty"]
            return FakeResponse(body={
                "choices": [{
                    "message": {
                        "content": "{\"ocrType\":\"ID_CARD\",\"confidence\":1,\"fields\":[]}"
                    }
                }],
                "usage": {"prompt_tokens": 1},
            })

    monkeypatch.setattr(qwen_module.httpx, "AsyncClient", FakeAsyncClient)
    raw, usage = asyncio.run(client.vision_json_chat(
        "请输出JSON",
        "http://minio.local/id-card.jpg",
        "image/jpeg",
    ))

    expected = "data:image/jpeg;base64," + base64.b64encode(image_bytes).decode("ascii")
    assert captured["download_trust_env"] is False
    assert captured["image_url"] == expected
    assert captured["max_tokens"] == settings.qwen_vl_max_tokens
    assert captured["presence_penalty"] == 1.5
    assert raw["ocrType"] == "ID_CARD"
    assert usage["provider"] == "QWEN_VL"


def test_qwen_vl_completes_missing_closing_delimiters_without_another_call(monkeypatch):
    import asyncio
    from app.services import qwen_client as qwen_module

    client = QwenClient(Settings(qwen_vl_api_key="test-key"))
    payloads = []

    class FakeResponse:
        text = ""

        def raise_for_status(self):
            return None

        def json(self):
            return {
                "choices": [{
                    "message": {"content": '{"ocrType":"LICENSE_PLATE","fields":[],"raw":{}   '},
                    "finish_reason": "stop",
                }],
                "usage": {},
            }

    class FakeAsyncClient:
        def __init__(self, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return None

        async def post(self, url, headers=None, json=None):
            payloads.append(json)
            return FakeResponse()

    monkeypatch.setattr(qwen_module.httpx, "AsyncClient", FakeAsyncClient)
    raw, _ = asyncio.run(client.vision_json_chat(
        "return JSON",
        "data:image/jpeg;base64,ZmFrZQ==",
        "image/jpeg",
    ))

    assert len(payloads) == 1
    assert raw == {"ocrType": "LICENSE_PLATE", "fields": [], "raw": {}}

def test_qwen_vl_retries_once_when_provider_json_is_invalid(monkeypatch):
    import asyncio
    from app.services import qwen_client as qwen_module

    settings = Settings(
        qwen_vl_api_key="test-key",
        qwen_vl_endpoint="https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        qwen_vl_max_tokens=99,
    )
    client = QwenClient(settings)
    payloads = []

    class FakeResponse:
        text = ""

        def __init__(self, content=b"", headers=None, body=None):
            self.content = content
            self.headers = headers or {}
            self._body = body or {}

        def raise_for_status(self):
            return None

        def json(self):
            return self._body

    class FakeAsyncClient:
        def __init__(self, **kwargs):
            self.kwargs = kwargs

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return None

        async def get(self, url):
            return FakeResponse(content=b"fake-image", headers={"content-type": "image/jpeg"})

        async def post(self, url, headers=None, json=None):
            payloads.append(json)
            if len(payloads) == 1:
                return FakeResponse(body={
                    "choices": [{
                        "message": {"content": "{\"ocrType\":\"ID_CARD\" \"fields\":[]}"},
                        "finish_reason": "stop",
                    }],
                    "usage": {"prompt_tokens": 1},
                })
            return FakeResponse(body={
                "choices": [{
                    "message": {
                        "content": "{\"ocrType\":\"ID_CARD\",\"confidence\":1,\"fields\":[]}"
                    },
                    "finish_reason": "stop",
                }],
                "usage": {"prompt_tokens": 2},
            })

    monkeypatch.setattr(qwen_module.httpx, "AsyncClient", FakeAsyncClient)
    raw, usage = asyncio.run(client.vision_json_chat(
        "请输出JSON",
        "http://minio.local/id-card.jpg",
        "image/jpeg",
    ))

    assert len(payloads) == 2
    assert payloads[0]["max_tokens"] == 99
    repair_content = payloads[1]["messages"][0]["content"]
    assert [item["type"] for item in repair_content] == ["text"]
    assert "只修复JSON语法" in repair_content[0]["text"]
    assert "{\"ocrType\":\"ID_CARD\" \"fields\":[]}" in repair_content[0]["text"]
    assert raw["ocrType"] == "ID_CARD"
    assert usage["prompt_tokens"] == 2


def test_qwen_vl_retries_vision_after_json_repair_is_still_invalid(monkeypatch):
    import asyncio
    from app.services import qwen_client as qwen_module

    client = QwenClient(Settings(qwen_vl_api_key="test-key"))
    payloads = []

    class FakeResponse:
        text = ""

        def __init__(self, body):
            self._body = body

        def raise_for_status(self):
            return None

        def json(self):
            return self._body

    class FakeAsyncClient:
        def __init__(self, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return None

        async def post(self, url, headers=None, json=None):
            payloads.append(json)
            content = (
                '{"ocrType":"ID_CARD" "fields":[]}'
                if len(payloads) < 3
                else '{"ocrType":"ID_CARD","fields":[]}'
            )
            return FakeResponse({
                "choices": [{"message": {"content": content}, "finish_reason": "stop"}],
                "usage": {},
            })

    monkeypatch.setattr(qwen_module.httpx, "AsyncClient", FakeAsyncClient)
    raw, _ = asyncio.run(client.vision_json_chat(
        "return JSON",
        "data:image/jpeg;base64,ZmFrZQ==",
        "image/jpeg",
    ))

    assert len(payloads) == 3
    assert [item["type"] for item in payloads[1]["messages"][0]["content"]] == ["text"]
    assert [item["type"] for item in payloads[2]["messages"][0]["content"]] == ["text", "image_url"]
    assert raw["ocrType"] == "ID_CARD"

def test_database_summarize_result_fails_fast_when_qwen_fails(monkeypatch):
    from app.api import routes

    class FailingDatabase:
        async def summarize_result(self, request):
            raise RuntimeError("summary service down")

    original_services = routes.services

    def fake_services():
        services = original_services()
        services["database"] = FailingDatabase()
        return services

    monkeypatch.setattr(routes, "services", fake_services)
    client = TestClient(app, raise_server_exceptions=False)
    response = client.post("/v1/database/summarize-result", json={
        "question": "统计项目数量",
        "sql": "select 1",
        "columns": ["value"],
        "rows": [{"value": 1}],
    })

    assert response.status_code == 500
    body = response.json()
    assert body["success"] is False
    assert body["errorCode"] == "INTERNAL_ERROR"
    assert body["errorMessage"] == "Internal service error"


def test_global_500_does_not_leak_internal_url_token_or_path(monkeypatch):
    from app.api import routes
    secret = "https://internal.local?token=secret C:\\private\\model"
    class FailingDatabase:
        async def summarize_result(self, _request):
            raise RuntimeError(secret)
    monkeypatch.setattr(routes, "services", lambda: {"database": FailingDatabase()})

    response = TestClient(app, raise_server_exceptions=False).post("/v1/database/summarize-result", json={
        "question": "统计", "sql": "select 1", "columns": ["value"], "rows": [{"value": 1}],
    })

    assert response.status_code == 500
    assert response.json()["errorCode"] == "INTERNAL_ERROR"
    assert secret not in response.text
    assert "token=secret" not in response.text


def test_request_validation_error_returns_safe_field_summary_without_input_value():
    secret = "https://internal.local?token=secret"
    response = TestClient(app).post("/v1/rag/search", json={
        "query": secret, "projectId": "not-an-int", "knowledgeBaseIds": [1],
    })

    assert response.status_code == 422
    body = response.json()
    assert body["errorCode"] == "VALIDATION_ERROR"
    assert body["errorDetails"]
    assert all(set(item) == {"field", "message", "type"} for item in body["errorDetails"])
    assert secret not in response.text
    assert "input" not in response.text


def test_database_generate_query_endpoint_accepts_list_parameters(monkeypatch):
    from app.api import routes
    from app.services.database_service import DatabaseQaService

    class FakeQwen:
        async def json_chat(self, messages):
            return {
                "sql": "SELECT COUNT(*) AS total FROM project",
                "parameters": [1],
                "explanation": "统计项目数量。",
                "riskLevel": "LOW",
            }, {"prompt_tokens": 1}

    monkeypatch.setattr(routes, "services", lambda: {"database": DatabaseQaService(FakeQwen())})
    client = TestClient(app)

    response = client.post("/v1/database/generate-query", json={
        "question": "查询项目数量",
        "schemaSummary": "project(id, project_name)",
        "permissionHints": {},
        "projectId": 1,
    })

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["parameters"] == {"p1": 1}


def test_database_generate_query_normalizes_list_parameters():
    from app.models.schemas import DatabaseGenerateQueryRequest
    from app.services.database_service import DatabaseQaService

    class FakeQwen:
        async def json_chat(self, messages):
            return {
                "sql": "SELECT * FROM project WHERE id = ?",
                "parameters": [1],
                "explanation": "按项目ID查询。",
                "riskLevel": "LOW",
            }, {"prompt_tokens": 1}

    service = DatabaseQaService(FakeQwen())
    data, usage = asyncio.run(service.generate_query(DatabaseGenerateQueryRequest(
        question="查询项目1",
        schemaSummary="project(id, project_name)",
        permissionHints={},
        projectId=1,
    )))

    assert data.sql == "SELECT * FROM project WHERE id = ?"
    assert data.parameters == {"p1": 1}
    assert data.explanation == "按项目ID查询。"
    assert data.riskLevel == "LOW"
    assert usage["prompt_tokens"] == 1


def test_database_generate_query_returns_structured_evidence_plan():
    from app.models.schemas import DatabaseGenerateQueryRequest
    from app.services.database_service import DatabaseQaService

    class FakeQwen:
        async def json_chat(self, messages):
            return {
                "sql": "SELECT risk_level, COUNT(*) AS risk_count FROM risk_record GROUP BY risk_level",
                "parameters": {},
                "explanation": "按风险等级统计。",
                "riskLevel": "LOW",
                "plan": {
                    "entities": ["risk_record"],
                    "metrics": [{"name": "risk_count", "aggregation": "COUNT"}],
                    "dimensions": ["risk_level"],
                    "filters": [{"field": "project_id", "operator": "=", "valueSource": "projectId"}],
                    "projectScopeField": "project_id",
                    "expectedColumns": ["risk_level", "risk_count"],
                    "expectedShape": "AGGREGATE_ROWS",
                    "ambiguities": [],
                },
            }, {}

    data, _ = asyncio.run(DatabaseQaService(FakeQwen()).generate_query(DatabaseGenerateQueryRequest(
        question="按风险等级统计当前项目风险",
        schemaSummary="risk_record(project_id, risk_level)",
        permissionHints={"projectId": 1, "readOnly": True},
        projectId=1,
        databaseType="MYSQL",
    )))

    assert data.plan.entities == ["risk_record"]
    assert data.plan.projectScopeField == "project_id"
    assert data.plan.expectedColumns == ["risk_level", "risk_count"]
    assert data.plan.expectedShape == "AGGREGATE_ROWS"


def test_database_generate_query_accepts_compact_plan_items():
    from app.models.schemas import DatabaseGenerateQueryRequest
    from app.services.database_service import DatabaseQaService

    class FakeQwen:
        async def json_chat(self, messages):
            return {
                "sql": "SELECT COUNT(*) AS total FROM project WHERE project_id = ?",
                "parameters": {"p1": 1},
                "explanation": "统计当前项目。",
                "riskLevel": "LOW",
                "plan": {
                    "entities": ["project"],
                    "metrics": ["COUNT(*) AS total"],
                    "filters": ["project_id = projectId"],
                    "expectedColumns": ["total"],
                },
            }, {}

    data, _ = asyncio.run(DatabaseQaService(FakeQwen()).generate_query(DatabaseGenerateQueryRequest(
        question="统计当前项目",
        schemaSummary="project(project_id)",
        permissionHints={"projectId": 1},
        projectId=1,
    )))

    assert data.plan.metrics == ["COUNT(*) AS total"]
    assert data.plan.filters == ["project_id = projectId"]


def test_database_generate_query_prompt_includes_mysql_distinct_order_rule():
    from app.models.schemas import DatabaseGenerateQueryRequest
    from app.services.database_service import DatabaseQaService

    class FakeQwen:
        def __init__(self):
            self.messages = None

        async def json_chat(self, messages):
            self.messages = messages
            return {
                "sql": "SELECT id, created_at FROM report ORDER BY created_at DESC",
                "parameters": {},
                "explanation": "查询最近报告。",
                "riskLevel": "LOW",
            }, {}

    qwen = FakeQwen()
    service = DatabaseQaService(qwen)
    asyncio.run(service.generate_query(DatabaseGenerateQueryRequest(
        question="查询最近报告",
        schemaSummary="report(id, created_at)",
        permissionHints={},
        projectId=1,
        databaseType="MYSQL",
    )))

    system_prompt = qwen.messages[0].content
    assert "MySQL 8" in system_prompt
    assert "DISTINCT" in system_prompt
    assert "ORDER BY" in system_prompt
    assert "SELECT列表" in system_prompt
    assert "子查询" in system_prompt
    assert "禁止使用分号" in system_prompt


def test_database_generate_query_prompt_includes_failed_sql_repair_context():
    from app.models.schemas import DatabaseGenerateQueryRequest
    from app.services.database_service import DatabaseQaService

    class FakeQwen:
        def __init__(self):
            self.messages = None

        async def json_chat(self, messages):
            self.messages = messages
            return {
                "sql": "SELECT DISTINCT rv.variable_name, rv.created_at FROM report_variable_value rv ORDER BY rv.created_at DESC",
                "parameters": {},
                "explanation": "补充排序字段后重试。",
                "riskLevel": "LOW",
            }, {}

    qwen = FakeQwen()
    service = DatabaseQaService(qwen)
    asyncio.run(service.generate_query(DatabaseGenerateQueryRequest(
        question="查询最近的报告变量",
        schemaSummary="report_variable_value(variable_name, created_at)",
        permissionHints={},
        projectId=1,
        databaseType="MYSQL",
        failedSql="SELECT DISTINCT rv.variable_name FROM report_variable_value rv ORDER BY rv.created_at DESC",
        databaseError="Expression #1 of ORDER BY clause is not in SELECT list",
        attempt=2,
    )))

    prompt = json.loads(qwen.messages[-1].content)
    assert prompt["databaseType"] == "MYSQL"
    assert prompt["failedSql"].startswith("SELECT DISTINCT")
    assert "ORDER BY clause" in prompt["databaseError"]
    assert prompt["attempt"] == 2
    assert "必须返回与failedSql不同" in qwen.messages[0].content
    assert "本地安全校验失败" in qwen.messages[0].content

def test_database_summarize_result_normalizes_string_lists():
    from app.models.schemas import DatabaseSummarizeRequest
    from app.services.database_service import DatabaseQaService

    class FakeQwen:
        async def json_chat(self, messages):
            return {
                "summary": "共有 1 个项目。",
                "insights": "项目数量较少。",
                "warnings": "样本有限。",
            }, {"prompt_tokens": 1}

    service = DatabaseQaService(FakeQwen())
    data, usage = asyncio.run(service.summarize_result(DatabaseSummarizeRequest(
        question="统计项目数量",
        sql="select count(*) as total from project",
        columns=["total"],
        rows=[{"total": 1}],
    )))

    assert data.summary == "共有 1 个项目。"
    assert data.insights == ["项目数量较少。"]
    assert data.warnings == ["样本有限。"]
    assert usage["prompt_tokens"] == 1


def test_database_summarize_prompt_forbids_unsupported_facts():
    from app.models.schemas import DatabaseSummarizeRequest
    from app.services.database_service import DatabaseQaService

    class FakeQwen:
        def __init__(self):
            self.messages = None

        async def json_chat(self, messages):
            self.messages = messages
            return {"summary": "高风险1条。", "insights": [], "warnings": []}, {}

    qwen = FakeQwen()
    asyncio.run(DatabaseQaService(qwen).summarize_result(DatabaseSummarizeRequest(
        question="统计高风险",
        sql="select count(*) as total from risk_record",
        columns=["total"],
        rows=[{"total": 1}],
    )))

    assert "不得添加查询结果中不存在" in qwen.messages[0].content
    assert "空结果" in qwen.messages[0].content

def test_route_normalizes_model_list_fields():
    from app.models.schemas import RouteRequest
    from app.services.route_context_service import RouteService

    class FakeQwen:
        async def json_chat(self, messages):
            return {
                "routeType": "database",
                "reason": "需要查库",
                "requiredResources": {"type": "DATA_SOURCE", "id": 1},
                "followUpQuestions": "请选择数据源。",
            }, {"prompt_tokens": 1}

    data, usage = asyncio.run(RouteService(FakeQwen()).route(RouteRequest(
        question="统计项目数量",
        availableDataSources=[{"id": 1}],
    )))

    assert data.routeType == "DATABASE"
    assert data.requiredResources == [{"type": "DATA_SOURCE", "id": 1}]
    assert data.followUpQuestions == ["请选择数据源。"]
    assert usage["prompt_tokens"] == 1


def test_tool_agent_normalizes_tool_arguments_and_follow_up_questions():
    class FakeQwen:
        def __init__(self):
            self.calls = 0

        async def json_chat(self, messages, parameters=None):
            self.calls += 1
            if self.calls == 1:
                return {"action": "tool", "tool": "echo_tool", "arguments": ["安全帽"]}, {}
            return {"action": "follow_up", "answer": "需要补充", "questions": "请补充检查范围。"}, {}

    async def echo_tool(args):
        assert args == {"p1": "安全帽"}
        return {"ok": True}

    registry = ToolRegistry()
    registry.register(ToolSpec(name="echo_tool", description="测试工具", parameters={}, func=echo_tool))

    data, usage = asyncio.run(ToolCallingAgent(FakeQwen(), registry).invoke(AgentInvokeRequest(
        goal="检查安全帽",
        tools=["echo_tool"],
    )))

    assert data.followUpQuestions == ["请补充检查范围。"]
    assert len(data.steps) == 1


def test_ocr_normalizes_optional_field_types():
    from app.models.schemas import OcrFilePayload, OcrRecognizeRequest
    from app.services.ocr_service import OcrService

    class FakeQwen:
        async def vision_json_chat(self, prompt, file_url, content_type):
            return {
                "ocrType": "ID_CARD",
                "confidence": "0.9",
                "fields": [{
                    "fieldKey": "name",
                    "fieldName": "姓名",
                    "fieldValue": 123,
                    "confidence": "0.8",
                    "location": [1, 2],
                    "pageNo": "2",
                    "evidence": {"text": "张三"},
                }],
                "extras": ["unexpected"],
            }, {"prompt_tokens": 1}

    data, usage = asyncio.run(OcrService(FakeQwen()).recognize(OcrRecognizeRequest(
        projectId=1,
        recordId=1,
        ocrType="ID_CARD",
        file=OcrFilePayload(fileId=1, fileName="id.jpg", contentType="image/jpeg", downloadUrl="http://minio/id.jpg"),
    )))

    assert data.confidence == 0.9
    assert data.fields[0].fieldValue == "123"
    assert data.fields[0].location is None
    assert data.fields[0].pageNo == 2
    assert data.fields[0].evidence is None
    assert data.extras == {"p1": "unexpected"}
    assert usage["prompt_tokens"] == 1


def test_ocr_reconciles_id_card_fields_against_complete_schema():
    from app.models.schemas import OcrFilePayload, OcrRecognizeRequest
    from app.services.ocr_service import OcrService, STANDARD_FIELDS

    class FakeQwen:
        async def vision_json_chat(self, prompt, file_sources, content_type):
            return {
                "ocrType": "ID_CARD",
                "confidence": 0.92,
                "fields": [
                    {"fieldKey": "", "fieldName": "公民身份号码", "fieldValue": "3702", "confidence": 0.8},
                    {"fieldKey": "name", "fieldName": "姓名", "fieldValue": "低可信", "confidence": 0.2},
                    {"fieldKey": "name", "fieldName": "姓名", "fieldValue": "张三", "confidence": 0.98},
                    {"fieldKey": "unknown", "fieldName": "未知字段", "fieldValue": "extra", "confidence": 0.7},
                ],
                "extras": {"watermark": {"detected": False}},
            }, {}

    request = OcrRecognizeRequest(
        projectId=1,
        recordId=1,
        ocrType="ID_CARD",
        file=OcrFilePayload(
            fileId=1,
            fileName="id-card.jpg",
            contentType="image/jpeg",
            dataUrls=["data:image/jpeg;base64,ZmFrZQ=="],
        ),
    )

    data, _ = asyncio.run(OcrService(FakeQwen()).recognize(request))

    assert [field.fieldKey for field in data.fields] == [item["fieldKey"] for item in STANDARD_FIELDS["ID_CARD"]]
    assert [field.fieldName for field in data.fields] == [item["fieldName"] for item in STANDARD_FIELDS["ID_CARD"]]
    assert data.fields[0].fieldValue == "张三"
    assert data.fields[0].recognized is True
    assert data.fields[5].fieldValue == "3702"
    assert data.fields[5].recognized is True
    assert data.fields[6].fieldKey == "issuingAuthority"
    assert data.fields[6].fieldValue == ""
    assert data.fields[6].confidence == 0
    assert data.fields[6].recognized is False
    assert data.extras["watermark"] == {"detected": False}
    assert data.extras["unmappedFields"][0]["fieldKey"] == "unknown"


def test_ocr_reconciles_all_custom_fields_in_configured_order_and_by_name():
    from app.models.schemas import OcrFilePayload, OcrRecognizeRequest
    from app.services.ocr_service import OcrService

    class FakeQwen:
        async def vision_json_chat(self, prompt, file_sources, content_type):
            return {
                "ocrType": "CUSTOM",
                "confidence": 0.85,
                "fields": [
                    {"fieldKey": "wrong-key", "fieldName": "合同金额", "fieldValue": "100万元", "confidence": 0.9},
                    {"fieldKey": "partyA", "fieldName": "甲方", "fieldValue": "建设单位", "confidence": 0.8},
                ],
            }, {}

    custom_fields = [
        {"fieldKey": "partyA", "fieldName": "甲方"},
        {"fieldKey": "partyB", "fieldName": "乙方"},
        {"fieldKey": "amount", "fieldName": "合同金额"},
    ]
    request = OcrRecognizeRequest(
        projectId=1,
        recordId=2,
        ocrType="CUSTOM",
        file=OcrFilePayload(
            fileId=2,
            fileName="contract.jpg",
            contentType="image/jpeg",
            dataUrls=["data:image/jpeg;base64,ZmFrZQ=="],
        ),
        options={"customFields": custom_fields},
    )

    data, _ = asyncio.run(OcrService(FakeQwen()).recognize(request))

    assert [(field.fieldKey, field.fieldName) for field in data.fields] == [
        ("partyA", "甲方"),
        ("partyB", "乙方"),
        ("amount", "合同金额"),
    ]
    assert data.fields[0].fieldValue == "建设单位"
    assert data.fields[1].fieldValue == ""
    assert data.fields[1].recognized is False
    assert data.fields[2].fieldValue == "100万元"
    assert data.fields[2].recognized is True

def test_qwen_json_chat_rejects_non_object_json():
    from app.models.schemas import Message

    class FakeQwenClient(QwenClient):
        async def chat(self, messages, model=None, parameters=None):
            return "[1]", {}

    client = FakeQwenClient(Settings(qwen_api_key="test-key"))

    try:
        asyncio.run(client.json_chat([Message(role="user", content="json")]))
        assert False, "json_chat should reject non-object JSON roots"
    except ValueError as exc:
        assert "must be an object" in str(exc)


def test_route_does_not_select_unavailable_knowledge_base():
    from app.models.schemas import RouteRequest
    from app.services.route_context_service import RouteService

    class FakeQwen:
        async def json_chat(self, messages):
            return {
                "routeType": "KNOWLEDGE",
                "reason": "法规问题应查询知识库",
            }, {}

    data, _ = asyncio.run(RouteService(FakeQwen()).route(RouteRequest(
        question="未取得资格证书从事建筑施工特种作业会承担什么法律责任？",
        availableKnowledgeBases=[],
        availableDataSources=[{"id": 7}],
    )))

    assert data.routeType == "MODEL"
    assert "知识库" in data.reason


def test_docker_and_local_python_use_the_same_rag_data_directory():
    from pathlib import Path

    compose_file = Path(__file__).resolve().parents[2] / "deploy" / "docker-compose-env.yml"
    compose = compose_file.read_text(encoding="utf-8")

    assert "../python-ai-service/data:/app/data" in compose


def test_qwen_embedding_retries_without_dimensions_on_bad_request(monkeypatch):
    import httpx
    from app.services import qwen_client as qwen_module

    settings = Settings(
        qwen_api_key="test-key",
        qwen_embedding_model="text-embedding-v4",
        qwen_embedding_dimensions=1024,
    )
    client = QwenClient(settings)
    payloads = []

    class FakeResponse:
        def __init__(self, status_code=200, text="", body=None):
            self.status_code = status_code
            self.text = text
            self._body = body or {}
            self.request = httpx.Request("POST", "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings")

        def raise_for_status(self):
            if self.status_code >= 400:
                raise httpx.HTTPStatusError("bad request", request=self.request, response=self)

        def json(self):
            return self._body

    class FakeAsyncClient:
        def __init__(self, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return None

        async def post(self, url, headers=None, json=None):
            payloads.append(dict(json))
            if len(payloads) == 1:
                return FakeResponse(400, '{"code":"InvalidParameter","message":"dimensions is invalid"}')
            return FakeResponse(body={
                "data": [{"index": 0, "embedding": [0.1, 0.2]}],
                "usage": {"total_tokens": 2},
            })

    monkeypatch.setattr(qwen_module.httpx, "AsyncClient", FakeAsyncClient)

    vectors, usage = asyncio.run(client.embed(["policy content"]))

    assert vectors == [[0.1, 0.2]]
    assert usage == {"total_tokens": 2}
    assert payloads[0]["dimensions"] == 1024
    assert "dimensions" not in payloads[1]


def test_qwen_embedding_error_includes_provider_body(monkeypatch):
    import pytest
    import httpx
    from app.services import qwen_client as qwen_module

    settings = Settings(qwen_api_key="test-key", qwen_embedding_dimensions=0)
    client = QwenClient(settings)

    class FakeResponse:
        status_code = 400
        text = '{"code":"InvalidParameter","message":"input is too long"}'

        def __init__(self):
            self.request = httpx.Request("POST", "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings")

        def raise_for_status(self):
            raise httpx.HTTPStatusError("bad request", request=self.request, response=self)

    class FakeAsyncClient:
        def __init__(self, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return None

        async def post(self, url, headers=None, json=None):
            return FakeResponse()

    monkeypatch.setattr(qwen_module.httpx, "AsyncClient", FakeAsyncClient)

    with pytest.raises(RuntimeError) as error:
        asyncio.run(client.embed(["x"] * 3))

    message = str(error.value)
    assert "Qwen embedding call failed" in message
    assert "HTTP 400" in message
    assert "input is too long" in message
    assert "inputCount=3" in message
    assert "api_key" not in message.lower()


def test_qwen_embedding_truncates_oversized_query_before_calling_local_provider(monkeypatch):
    from app.services import qwen_client as qwen_module

    settings = Settings(
        qwen_api_key="test-key",
        qwen_embedding_dimensions=0,
        qwen_embedding_max_input_chars=32,
    )
    client = QwenClient(settings)
    payloads = []

    class FakeResponse:
        status_code = 200
        text = ""

        def raise_for_status(self):
            return None

        def json(self):
            return {"data": [{"index": 0, "embedding": [0.1]}], "usage": {}}

    class FakeAsyncClient:
        def __init__(self, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return None

        async def post(self, url, headers=None, json=None):
            payloads.append(json)
            return FakeResponse()

    monkeypatch.setattr(qwen_module.httpx, "AsyncClient", FakeAsyncClient)

    vectors, _ = asyncio.run(client.embed(["安全巡检记录" * 20]))

    assert vectors == [[0.1]]
    assert len(payloads[0]["input"][0]) == 32


def test_qwen_rerank_truncates_oversized_query_before_calling_local_provider(monkeypatch):
    from app.services import qwen_client as qwen_module

    settings = Settings(
        qwen_api_key="test-key",
        qwen_embedding_max_input_chars=32,
        qwen_rerank_api_style="QWEN3",
    )
    client = QwenClient(settings)
    payloads = []

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"results": [], "usage": {}}

    class FakeAsyncClient:
        def __init__(self, **kwargs): pass
        async def __aenter__(self): return self
        async def __aexit__(self, exc_type, exc, tb): return None
        async def post(self, url, headers=None, json=None):
            payloads.append(json)
            return FakeResponse()

    monkeypatch.setattr(qwen_module.httpx, "AsyncClient", FakeAsyncClient)

    asyncio.run(client.rerank("巡检问题" * 20, ["document"], 1))

    assert len(payloads[0]["query"]) == 32

def test_ocr_accepts_inline_images_for_all_supported_types_without_downloading(monkeypatch):
    from app.models.schemas import OcrFilePayload, OcrRecognizeRequest
    from app.services.ocr_service import OcrService

    captured = []

    class FakeQwen:
        async def vision_json_chat(self, prompt, file_sources, content_type):
            captured.append(file_sources)
            return {"ocrType": "TEST", "confidence": 1, "fields": []}, {}

    data_url = "data:image/jpeg;base64,ZmFrZS1pbWFnZQ=="
    for ocr_type in ("ID_CARD", "LICENSE_PLATE", "INVOICE", "CUSTOM", "CONTRACT"):
        options = {}
        if ocr_type == "INVOICE":
            options["invoiceType"] = "VAT_NORMAL"
        if ocr_type in {"CUSTOM", "CONTRACT"}:
            options["customFields"] = [{"fieldKey": "partyA", "fieldName": "甲方"}]
        request = OcrRecognizeRequest(
            projectId=1,
            recordId=1,
            ocrType=ocr_type,
            file=OcrFilePayload(
                fileId=1,
                fileName="input.jpg",
                contentType="image/jpeg",
                dataUrls=[data_url],
            ),
            options=options,
        )
        asyncio.run(OcrService(FakeQwen()).recognize(request))

    assert captured == [[data_url]] * 5

def test_ocr_accepts_inline_images_for_all_supported_types_without_downloading():
    from app.models.schemas import OcrFilePayload, OcrRecognizeRequest
    from app.services.ocr_service import OcrService

    captured = []

    class FakeQwen:
        async def vision_json_chat(self, prompt, file_sources, content_type):
            captured.append(file_sources)
            return {"ocrType": "TEST", "confidence": 1, "fields": []}, {}

    data_url = "data:image/jpeg;base64,ZmFrZS1pbWFnZQ=="
    for ocr_type in ("ID_CARD", "LICENSE_PLATE", "INVOICE", "CUSTOM", "CONTRACT"):
        options = {}
        if ocr_type == "INVOICE":
            options["invoiceType"] = "VAT_NORMAL"
        if ocr_type in {"CUSTOM", "CONTRACT"}:
            options["customFields"] = [{"fieldKey": "partyA", "fieldName": "甲方"}]
        request = OcrRecognizeRequest(
            projectId=1,
            recordId=1,
            ocrType=ocr_type,
            file=OcrFilePayload(
                fileId=1,
                fileName="input.jpg",
                contentType="image/jpeg",
                dataUrls=[data_url],
            ),
            options=options,
        )
        asyncio.run(OcrService(FakeQwen()).recognize(request))

    assert captured == [[data_url]] * 5



def test_qwen_vl_forwards_multiple_inline_images_without_http_download(monkeypatch):
    from app.services import qwen_client as qwen_module

    settings = Settings(qwen_vl_api_key="test-key")
    client = QwenClient(settings)
    first = "data:image/jpeg;base64,Zmlyc3Q="
    second = "data:image/png;base64,c2Vjb25k"
    captured = {}

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {
                "choices": [{"message": {"content": "{\"ocrType\":\"CUSTOM\",\"fields\":[]}"}}],
                "usage": {},
            }

    class FakeAsyncClient:
        def __init__(self, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return None

        async def get(self, url):
            raise AssertionError("inline OCR images must not be downloaded over HTTP")

        async def post(self, url, headers=None, json=None):
            captured["content"] = json["messages"][0]["content"]
            return FakeResponse()

    monkeypatch.setattr(qwen_module.httpx, "AsyncClient", FakeAsyncClient)
    raw, _ = asyncio.run(client.vision_json_chat("extract", [first, second], "application/pdf"))

    assert [item["image_url"]["url"] for item in captured["content"][1:]] == [first, second]
    assert raw["ocrType"] == "CUSTOM"


def test_ocr_prompt_only_requests_type_specific_extras():
    from app.models.schemas import OcrFilePayload, OcrRecognizeRequest
    from app.services.ocr_service import OcrService

    service = OcrService(object())
    file = OcrFilePayload(fileId=1, fileName="input.jpg", contentType="image/jpeg", downloadUrl="http://example/input.jpg")

    id_prompt = service._build_prompt(OcrRecognizeRequest(projectId=1, recordId=1, ocrType="ID_CARD", file=file), "ID_CARD")
    assert "extras.watermark" in id_prompt
    assert "extras.plate" not in id_prompt
    assert "extras.items" not in id_prompt

    plate_prompt = service._build_prompt(OcrRecognizeRequest(projectId=1, recordId=2, ocrType="LICENSE_PLATE", file=file), "LICENSE_PLATE")
    assert "extras.plate" in plate_prompt
    assert "extras.watermark" not in plate_prompt
    assert "extras.items" not in plate_prompt


def test_health_separates_liveness_configuration_and_model_readiness(monkeypatch):
    from app.api import routes

    async def fake_snapshot(_self):
        return {
            "status": "DEGRADED",
            "profile": "a6000x2-production-32k",
            "maxContextTokens": 32768,
            "dependencies": {
                "chat": {
                    "configured": True,
                    "reachable": False,
                    "status": "CONNECT_ERROR",
                    "provider": "OPENAI_COMPATIBLE",
                    "model": "smart-worksite-chat",
                    "endpointScope": "LOCAL",
                }
            },
        }

    monkeypatch.setattr(routes.ModelReadinessService, "snapshot", fake_snapshot)
    response = TestClient(app).get("/v1/health")
    body = response.json()["data"]

    assert body["status"] == "UP"
    assert body["modelReadiness"]["status"] == "DEGRADED"
    assert body["modelReadiness"]["maxContextTokens"] == 32768
    assert "http://" not in response.text
    assert "api_key" not in response.text.lower()

# Task 4: model API context-budget integration

def _task4_settings(**overrides):
    from types import SimpleNamespace

    values = {
        "chat_max_model_len": 640,
        "context_output_reserve_tokens": 64,
        "context_safety_reserve_tokens": 24,
        "context_template_overhead_tokens": 16,
        "context_history_budget_ratio": 0.30,
        "context_evidence_budget_ratio": 0.70,
        "context_history_candidate_limit": 100,
    }
    values.update(overrides)
    settings = SimpleNamespace(**values)
    settings.resolved_context_output_reserve_tokens = lambda: settings.context_output_reserve_tokens
    settings.resolved_context_safety_reserve_tokens = lambda: settings.context_safety_reserve_tokens
    return settings


def test_context_budget_model_request_schema_keeps_old_callers_and_accepts_structured_evidence():
    from app.models.schemas import ModelInvokeRequest

    legacy = ModelInvokeRequest(prompt="本月安全情况如何？")
    assert legacy.evidenceItems == []

    request = ModelInvokeRequest.model_validate({
        "prompt": "夜间施工噪声限值是多少？",
        "evidenceItems": [{
            "content": "夜间施工应遵守噪声排放限值。",
            "title": "建筑施工噪声排放标准",
            "sourceId": "82",
            "chunkId": "noise-1",
            "score": 0.93,
            "metadata": {"pageNumber": 4, "tableLocation": "表1"},
        }],
    })

    assert request.evidenceItems[0].title == "建筑施工噪声排放标准"
    assert request.evidenceItems[0].sourceId == "82"
    assert request.evidenceItems[0].score == 0.93


def test_context_budget_model_service_preserves_legacy_calls_when_context_limit_is_unconfigured():
    from app.models.schemas import ModelInvokeRequest
    from app.services.context_budget import ContextBudgetPlanner
    from app.services.model_service import ModelService
    from app.services.token_counter import ConservativeTokenCounter

    class RecordingProvider:
        def __init__(self):
            self.messages = None

        async def chat(self, messages, model=None, parameters=None):
            self.messages = messages
            return "legacy response", {}

    provider = RecordingProvider()
    service = ModelService(
        provider,
        ContextBudgetPlanner(ConservativeTokenCounter()),
        _task4_settings(chat_max_model_len=0),
    )

    answer, usage = asyncio.run(service.invoke(ModelInvokeRequest(prompt="旧调用仍应可用")))

    assert answer == "legacy response"
    assert usage == {}
    assert provider.messages[-1].content == "旧调用仍应可用"


def test_context_budget_model_api_keeps_source_id_as_stable_evidence_marker(monkeypatch):
    from app.api import routes
    from app.services.context_budget import ContextBudgetPlanner
    from app.services.model_service import ModelService
    from app.services.token_counter import ConservativeTokenCounter

    class RecordingProvider:
        def __init__(self):
            self.messages = None

        async def chat(self, messages, model=None, parameters=None):
            self.messages = messages
            return "source marker response", {}

    provider = RecordingProvider()
    service = ModelService(
        provider,
        ContextBudgetPlanner(ConservativeTokenCounter()),
        _task4_settings(),
    )
    monkeypatch.setattr(routes, "services", lambda: {"model": service})

    response = TestClient(app).post("/v1/model/invoke", json={
        "prompt": "证据来源是什么？",
        "evidenceItems": [{"content": "来源证据正文。", "sourceId": "source-82"}],
    })

    assert response.status_code == 200
    sent_text = "\n".join(message.content for message in provider.messages)
    assert "sourceId: source-82" in sent_text


def test_context_budget_model_api_plans_messages_and_merges_usage_without_overwriting_provider_tokens(monkeypatch):
    from app.api import routes
    from app.services.context_budget import ContextBudgetPlanner
    from app.services.model_service import ModelService
    from app.services.token_counter import ConservativeTokenCounter

    class AsyncLocalCounter:
        def __init__(self):
            self.calls = 0
            self.delegate = ConservativeTokenCounter()

        async def count_chat(self, messages):
            self.calls += 1
            return self.delegate.count_chat(messages)

    class RecordingProvider:
        def __init__(self):
            self.messages = None
            self.parameters = None

        async def chat(self, messages, model=None, parameters=None):
            self.messages = messages
            self.parameters = parameters
            return "已按预算生成回答", {
                "prompt_tokens": 111,
                "completion_tokens": 22,
                "providerMetric": 7,
            }

    counter = AsyncLocalCounter()
    provider = RecordingProvider()
    settings = _task4_settings()
    service = ModelService(provider, ContextBudgetPlanner(counter), settings)
    monkeypatch.setattr(routes, "services", lambda: {"model": service})
    client = TestClient(app)

    response = client.post("/v1/model/invoke", json={
        "prompt": "夜间施工应满足什么要求？",
        "systemPrompt": "只能依据给定证据回答。",
        "parameters": {"temperature": 0.1, "max_tokens": 999},
        "contextMessages": [
            {"role": "user", "content": "OLD-QUESTION-" + "旧" * 180},
            {"role": "assistant", "content": "OLD-ANSWER-" + "答" * 180},
            {"role": "user", "content": "最近的问题"},
            {"role": "assistant", "content": "最近的回答"},
        ],
        "evidenceItems": [
            {
                "content": "夜间施工应控制噪声并履行审批要求。",
                "title": "噪声标准",
                "sourceId": "82",
                "chunkId": "noise-1",
                "score": 0.98,
                "metadata": {"pageNumber": 4},
            },
            {
                "content": "DROP-EVIDENCE-" + "无关内容" * 180,
                "title": "低优先级资料",
                "sourceId": "83",
                "chunkId": "noise-2",
                "score": 0.10,
            },
        ],
    })

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    sent_text = "\n".join(message.content for message in provider.messages)
    assert "OLD-QUESTION" not in sent_text
    assert "OLD-ANSWER" not in sent_text
    assert "最近的问题" in sent_text
    assert "noise-1" in sent_text
    assert "DROP-EVIDENCE" not in sent_text
    assert provider.parameters["temperature"] == 0.1
    assert provider.parameters["max_tokens"] == 64
    assert counter.calls > 0

    usage = body["usage"]
    assert usage["prompt_tokens"] == 111
    assert usage["completion_tokens"] == 22
    assert usage["providerMetric"] == 7
    assert usage["contextUsage"]["outputReserveTokens"] == 64
    assert usage["contextUsage"]["selectedEvidenceItems"] == 1
    assert "夜间施工应控制噪声" not in json.dumps(usage, ensure_ascii=False)
    assert body["data"]["usage"] == usage


def test_context_budget_model_api_maps_overflow_to_sanitized_validation_error(monkeypatch):
    from app.api import routes
    from app.services.context_budget import ContextBudgetPlanner
    from app.services.model_service import ModelService
    from app.services.token_counter import ConservativeTokenCounter

    class AsyncLocalCounter:
        async def count_chat(self, messages):
            return ConservativeTokenCounter().count_chat(messages)

    class ProviderMustNotRun:
        async def chat(self, messages, model=None, parameters=None):
            raise AssertionError("provider must not be called for an oversized mandatory context")

    settings = _task4_settings(
        chat_max_model_len=96,
        context_output_reserve_tokens=32,
        context_safety_reserve_tokens=16,
        context_template_overhead_tokens=8,
    )
    service = ModelService(ProviderMustNotRun(), ContextBudgetPlanner(AsyncLocalCounter()), settings)
    monkeypatch.setattr(routes, "services", lambda: {"model": service})
    client = TestClient(app, raise_server_exceptions=False)
    secret_source = "SECRET-SOURCE-BODY-" + "超长问题" * 80

    response = client.post("/v1/model/invoke", json={
        "prompt": secret_source,
        "systemPrompt": "安全规则",
    })

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is False
    assert body["errorCode"] == "VALIDATION_ERROR"
    assert body["errorDetails"]["code"] == "CONTEXT_BUDGET_EXCEEDED"
    assert secret_source not in response.text
    assert "SECRET-SOURCE-BODY" not in response.text
