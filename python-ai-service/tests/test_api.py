import os
import json
os.environ["AI_SERVICE_API_KEY"] = ""
os.environ["EMBEDDING_PROVIDER"] = "LOCAL_HASH"
os.environ["RAG_PROVIDER"] = "LOCAL"
os.environ["RAG_DATA_DIR"] = "data/test-rag"

from fastapi.testclient import TestClient
from app.main import app
from app.models.schemas import AgentInvokeRequest
from app.core.settings import Settings
from app.services.qwen_client import QwenClient
from app.services.agent_tools import ToolCallingAgent, ToolRegistry, ToolSpec
from app.services.rag_service import RagService
from app.services.vector_store import ChunkRecord


def test_health_without_key_when_not_configured():
    client = TestClient(app)
    response = client.get("/v1/health")
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["status"] == "UP"


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

    response = client.post("/v1/rag/search", json={"projectId": 1, "query": "安全帽规范", "topK": 1})
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert len(body["data"]["records"]) == 1


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
    assert result["issues"] == []
    assert result["summary"]
    assert body["data"]["steps"][0]["step"] == "COMPLIANCE_REVIEW_FALLBACK"

def test_rag_uses_qwen_rerank_when_configured():
    class FakeSettings:
        rag_provider = "LOCAL"
        rag_data_dir = "data/test-rag"
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
    assert raw["ocrType"] == "ID_CARD"
    assert usage["provider"] == "QWEN_VL"


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
    assert "上一次输出不是合法JSON" in payloads[1]["messages"][0]["content"][0]["text"]
    assert raw["ocrType"] == "ID_CARD"
    assert usage["prompt_tokens"] == 2


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

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is False
    assert body["errorCode"] == "RuntimeError"
    assert "summary service down" in body["errorMessage"]
