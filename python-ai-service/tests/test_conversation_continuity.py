import os

os.environ["AI_SERVICE_API_KEY"] = ""
os.environ["EMBEDDING_PROVIDER"] = "LOCAL_HASH"
os.environ["RAG_PROVIDER"] = "LOCAL"

from fastapi.testclient import TestClient

from app.main import app
from app.services.qwen_client import QwenClient


client = TestClient(app)


def test_resolve_question_uses_structured_context_once(monkeypatch):
    calls = []

    async def fake_json_chat(self, messages, model=None, parameters=None):
        calls.append(messages)
        return {
            "standaloneQuestion": "GB 12523-2025《建筑施工噪声排放标准》什么时候实施？",
            "contextDependent": True,
        }, {"completion_tokens": 12}

    monkeypatch.setattr(QwenClient, "json_chat", fake_json_chat)
    response = client.post("/v1/context/resolve-question", json={
        "currentQuestion": "这个标准什么时候实施？",
        "summary": {
            "topics": ["建筑施工噪声"],
            "standards": ["GB 12523-2025"],
            "constraints": {"region": "全国"},
        },
        "recentMessages": [{"role": "user", "content": "请介绍GB 12523-2025。"}],
    })

    assert response.status_code == 200
    assert response.json()["data"] == {
        "standaloneQuestion": "GB 12523-2025《建筑施工噪声排放标准》什么时候实施？",
        "contextDependent": True,
        "usedFallback": False,
    }
    assert len(calls) == 1


def test_resolve_question_falls_back_without_leaking_model_error(monkeypatch):
    calls = 0

    async def failing_json_chat(self, messages, model=None, parameters=None):
        nonlocal calls
        calls += 1
        raise RuntimeError("token=secret-value http://internal-model/v1")

    monkeypatch.setattr(QwenClient, "json_chat", failing_json_chat)
    response = client.post("/v1/context/resolve-question", json={
        "currentQuestion": "  那夜间呢？  ",
        "summary": {},
        "recentMessages": [],
    })

    assert response.status_code == 200
    body = response.json()
    assert body["data"]["standaloneQuestion"] == "那夜间呢？"
    assert body["data"]["usedFallback"] is True
    assert body["data"]["contextDependent"] is True
    assert "secret-value" not in response.text
    assert "internal-model" not in response.text
    assert calls == 1


def test_resolve_question_rejects_blank_or_oversized_model_output(monkeypatch):
    async def malformed_json_chat(self, messages, model=None, parameters=None):
        return {"standaloneQuestion": ["not", "a", "string"], "contextDependent": "yes"}, {}

    monkeypatch.setattr(QwenClient, "json_chat", malformed_json_chat)
    response = client.post("/v1/context/resolve-question", json={
        "currentQuestion": "它有哪些要求？",
        "summary": {"topics": ["安全管理"]},
    })

    assert response.status_code == 200
    assert response.json()["data"] == {
        "standaloneQuestion": "它有哪些要求？",
        "contextDependent": True,
        "usedFallback": True,
    }


def test_finalize_answer_normalizes_summary_and_follow_ups(monkeypatch):
    async def fake_json_chat(self, messages, model=None, parameters=None):
        return {
            "summary": {
                "topics": [" 建筑施工噪声 ", "建筑施工噪声", 7],
                "standards": "GB 12523-2025",
                "constraints": {"region": " 全国 ", "time": ["夜间"], "secret": "drop-me"},
                "confirmedFacts": ["夜间限值为55 dB(A)", "夜间限值为55 dB(A)"],
                "userCorrections": None,
                "openQuestions": "夜间最大声级是多少？",
                "prompt": "must-not-leak",
            },
            "suggestedFollowUpQuestions": [
                "夜间最大声级是多少？",
                "夜间最大声级是多少？",
                "依据GB 12523-2025，夜间限值是多少？",
                "噪声测量记录应包含哪些字段？",
                "该标准适用于哪些施工活动？",
                "第四个问题应被截断？",
            ],
        }, {"completion_tokens": 30}

    monkeypatch.setattr(QwenClient, "json_chat", fake_json_chat)
    response = client.post("/v1/context/finalize-answer", json={
        "currentQuestion": "依据GB 12523-2025，夜间限值是多少？",
        "answer": "夜间限值为55 dB(A)。",
        "summary": {},
        "alreadyAnsweredQuestions": ["该标准什么时候实施？"],
    })

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["summary"] == {
        "topics": ["建筑施工噪声"],
        "standards": ["GB 12523-2025"],
        "constraints": {"region": "全国", "time": None, "subject": None},
        "confirmedFacts": ["夜间限值为55 dB(A)"],
        "userCorrections": [],
        "openQuestions": ["夜间最大声级是多少？"],
    }
    assert data["suggestedFollowUpQuestions"] == [
        "夜间最大声级是多少？",
        "噪声测量记录应包含哪些字段？",
        "该标准适用于哪些施工活动？",
    ]
    assert data["usedFallback"] is False
    assert "must-not-leak" not in response.text
    assert "drop-me" not in response.text


def test_finalize_answer_dedupes_current_and_answered_questions(monkeypatch):
    async def fake_json_chat(self, messages, model=None, parameters=None):
        return {
            "summary": "malformed",
            "suggestedFollowUpQuestions": [
                " 本月未闭环安全问题有多少？ ",
                "哪个负责人未闭环问题最多？",
                "这些问题分别逾期多少天？",
            ],
        }, {}

    monkeypatch.setattr(QwenClient, "json_chat", fake_json_chat)
    response = client.post("/v1/context/finalize-answer", json={
        "currentQuestion": "本月未闭环安全问题有多少？",
        "answer": "共有3项。",
        "summary": {"topics": ["安全隐患"]},
        "alreadyAnsweredQuestions": ["哪个负责人未闭环问题最多?"],
    })

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["summary"]["topics"] == ["安全隐患"]
    assert data["suggestedFollowUpQuestions"] == ["这些问题分别逾期多少天？"]
    assert data["usedFallback"] is True


def test_finalize_answer_model_failure_preserves_safe_existing_summary(monkeypatch):
    async def failing_json_chat(self, messages, model=None, parameters=None):
        raise RuntimeError("password=database-secret")

    monkeypatch.setattr(QwenClient, "json_chat", failing_json_chat)
    response = client.post("/v1/context/finalize-answer", json={
        "currentQuestion": "那夜间呢？",
        "answer": "夜间限值为55 dB(A)。",
        "summary": {
            "topics": ["建筑施工噪声"],
            "standards": ["GB 12523-2025"],
            "constraints": {"time": "夜间"},
            "confirmedFacts": ["昼间限值为70 dB(A)"],
        },
    })

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["summary"]["standards"] == ["GB 12523-2025"]
    assert data["suggestedFollowUpQuestions"] == []
    assert data["usedFallback"] is True
    assert "database-secret" not in response.text
