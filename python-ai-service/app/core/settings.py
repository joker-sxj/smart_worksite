from functools import lru_cache
from typing import Any

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.deployment import AiDeploymentMode, is_local_model_endpoint


class Settings(BaseSettings):
    ai_deployment_mode: AiDeploymentMode = AiDeploymentMode.CLOUD_ALLOWED
    ai_service_host: str = "0.0.0.0"
    ai_service_port: int = 8015
    ai_service_api_key: str = ""

    qwen_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    qwen_api_key: str = ""
    qwen_model: str = "qwen-plus"
    qwen_vl_endpoint: str = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    qwen_vl_api_key: str = ""
    qwen_vl_model: str = "qwen-vl-plus"
    qwen_vl_timeout_seconds: int = 120
    qwen_vl_max_image_bytes: int = 10 * 1024 * 1024
    qwen_vl_max_tokens: int = 8192
    qwen_embedding_base_url: str = ""
    qwen_embedding_model: str = "text-embedding-v4"
    qwen_embedding_dimensions: int = 1024
    qwen_embedding_batch_size: int = 10
    qwen_rerank_base_url: str = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
    qwen_rerank_model: str = "qwen3-rerank"
    qwen_rerank_api_style: str = "LEGACY"
    qwen_rerank_instruct: str = "Given a web search query, retrieve relevant passages that answer the query."
    qwen_timeout_seconds: int = 120

    embedding_provider: str = "QWEN"
    rerank_provider: str = "QWEN"
    rag_provider: str = "LOCAL"
    rag_data_dir: str = "data/rag"
    rag_chunk_size: int = 800
    rag_chunk_overlap: int = 120
    rag_rerank_top_k: int = 20

    milvus_uri: str = "http://127.0.0.1:19530"
    milvus_token: str = ""
    milvus_collection: str = "smart_worksite_chunks"

    pgvector_dsn: str = ""
    pgvector_table: str = "smart_worksite_chunks"

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    @model_validator(mode="after")
    def validate_local_only_endpoints(self) -> "Settings":
        if self.ai_deployment_mode != AiDeploymentMode.LOCAL_ONLY:
            return self

        endpoints = {
            "QWEN_BASE_URL": self.qwen_base_url,
            "QWEN_VL_ENDPOINT": self.qwen_vl_endpoint,
            "QWEN_EMBEDDING_BASE_URL": self.qwen_embedding_base_url or self.qwen_base_url,
            "QWEN_RERANK_BASE_URL": self.qwen_rerank_base_url,
        }
        invalid = [name for name, endpoint in endpoints.items() if not is_local_model_endpoint(endpoint)]
        if invalid:
            raise ValueError(
                "AI_DEPLOYMENT_MODE=LOCAL_ONLY requires loopback, private-network, "
                f"or Docker service endpoints; invalid settings: {', '.join(invalid)}"
            )
        return self

    def ai_dependency_descriptors(self) -> dict[str, dict[str, Any]]:
        return {
            "chat": {
                "provider": "OPENAI_COMPATIBLE",
                "model": self.qwen_model,
                "endpoint": self.qwen_base_url,
            },
            "vision": {
                "provider": "OPENAI_COMPATIBLE",
                "model": self.qwen_vl_model,
                "endpoint": self.qwen_vl_endpoint,
            },
            "embedding": {
                "provider": self.embedding_provider,
                "model": self.qwen_embedding_model,
                "endpoint": self.qwen_embedding_base_url or self.qwen_base_url,
            },
            "rerank": {
                "provider": self.rerank_provider,
                "model": self.qwen_rerank_model,
                "endpoint": self.qwen_rerank_base_url,
            },
        }


@lru_cache
def get_settings() -> Settings:
    return Settings()
