# deploy

本目录用于启动 Docker 依赖服务，包括 MySQL、Redis、MinIO、Python AI 服务，以及可选的 pgvector/Milvus。

## 文件说明

| 文件 | 说明 |
| --- | --- |
| `.env.example` | 本地环境变量示例 |
| `.env` | 本地实际环境变量，复制 `.env.example` 后生成 |
| `docker-compose-env.yml` | MySQL、Redis、MinIO、Python AI 和可选向量数据库的 Docker Compose 编排文件 |
| `docker-compose-models.yml` | Linux 本地模型服务 Compose 叠加文件 |
| `model-profiles/*.env.example` | H100 和双 A6000 的无密钥模型部署配置 |

## 启动依赖

```powershell
cd deploy
copy .env.example .env
docker compose -f docker-compose-env.yml --env-file .env up -d
```

## 查看服务状态

```powershell
docker compose -f docker-compose-env.yml --env-file .env ps
```

## 查看日志

```powershell
docker compose -f docker-compose-env.yml --env-file .env logs -f mysql
docker compose -f docker-compose-env.yml --env-file .env logs -f redis
docker compose -f docker-compose-env.yml --env-file .env logs -f minio
```

Compose 为所有容器统一设置 `json-file` 日志轮转。该驱动不能按自然日清理，默认以每个容器单文件 10 MB、最多 30 个文件作为容量兜底：

```env
DOCKER_LOG_MAX_SIZE=10m
DOCKER_LOG_MAX_FILES=30
AI_ACCESS_LOG=false
```

`AI_ACCESS_LOG=false` 会关闭 Python AI 服务的 Uvicorn 每请求访问日志，错误和应用日志仍会保留。修改日志参数后需要重建容器，`down` 不带 `-v`，不会删除业务数据：

```bash
docker compose -f docker-compose-env.yml --env-file .env down
docker compose -f docker-compose-env.yml --env-file .env up -d --build
docker inspect -f '{{json .HostConfig.LogConfig}}' smart-worksite-python-ai-service
```

## 停止服务

```powershell
docker compose -f docker-compose-env.yml --env-file .env down
```

## 清理数据

以下命令会删除 MySQL、Redis、MinIO 的 volume 数据。执行后数据库、缓存和文件对象都会被清空。

```powershell
docker compose -f docker-compose-env.yml --env-file .env down -v
```

## 默认服务地址

| 服务 | 地址 |
| --- | --- |
| MySQL | `localhost:3306` |
| Redis | `localhost:6379` |
| MinIO API | `http://localhost:9000` |
| MinIO Console | `http://localhost:9001` |

## 默认账号

| 服务 | 用户名 | 密码 |
| --- | --- | --- |
| MySQL业务用户 | `worksite` | `worksite` |
| MySQL root | `root` | `root` |
| MinIO | `minioadmin` | `minioadmin` |

## MinIO bucket

启动时 `minio-init` 会自动创建 `.env` 中配置的 bucket：

```env
MINIO_BUCKET=smart-worksite
```

bucket 默认设置为私有访问。后端通过 MinIO SDK 生成临时预签名 URL 进行下载和预览。

## Linux 本地模型部署

本地模型服务使用独立的 Compose 叠加文件 `docker-compose-models.yml`，模型权重下载到持久化 `model-cache` 数据卷，不进入 Git 仓库。

| 配置 | 主模型 | 目标硬件 | Tensor Parallel | 初始上下文 / 并发 |
| --- | --- | --- | ---: | ---: |
| `h100-fp8` | `Qwen/Qwen3.8-27B-FP8` | 单张 H100 80GB | 1 | 16K / 2 |
| `a6000x2-bf16` | `Qwen/Qwen3.8-27B` BF16 | 两张 RTX A6000 48GB | 2 | 16K / 2 |

两套配置均固定官方 Qwen 仓库的模型 revision，并分别运行：

- 主对话及视觉理解：Qwen3.8 27B；
- 向量模型：`Qwen/Qwen3-Embedding-4B`；
- 重排序模型：`Qwen/Qwen3-Reranker-0.6B`。

当前固定推理镜像为 `vllm/vllm-openai:v0.27.1-cu129`，配置文件同时固定 Docker Hub manifest digest。vLLM 容器启用受支持的数据中心/专业显卡 CUDA Forward Compatibility。启动脚本不会仅凭驱动版本假定兼容：它会先检查驱动下限，再通过临时的 `docker run --rm --gpus all` 容器实测 NVIDIA Container Toolkit。随后启动的 vLLM 容器及模型健康检查才是所选 CUDA 12.9 推理镜像的最终兼容性门禁。任一环节失败都会在启动 Java 和前端前终止，并提示升级驱动或改用经验证的推理镜像。

### Linux 启动

```bash
cp deploy/.env.example deploy/.env
# 在 deploy/.env 中配置数据库、MinIO 等本机密码，不要提交该文件。

# 当前单张 H100 服务器
./scripts/start-all.sh --model-profile h100-fp8

# 用户现场两张 A6000 服务器
./scripts/start-all.sh --model-profile a6000x2-bf16

./scripts/status.sh
./scripts/stop-all.sh
```

`start-all.sh` 按以下顺序执行：

1. 检查主模型所需 GPU 数量和 NVIDIA 驱动；
2. 用与当前服务器驱动兼容的可配置 CUDA 镜像执行一次无数据破坏的 Docker GPU/Container Toolkit 探测；
3. 启动本地主模型、Embedding、Reranker；
4. 分别等待 chat、vision、embedding、rerank 健康；
5. 再启动 MySQL、Redis、MinIO、Python AI、Java 和前端。

模型宿主机端口仅绑定 `127.0.0.1`。Python AI 容器通过 Compose 网络访问：

```text
http://local-llm:8000/v1
http://local-embedding:8000/v1
http://local-reranker:8000/v1/rerank
```

`AI_DEPLOYMENT_MODE=LOCAL_ONLY` 时不需要云端模型 API Key，也不允许配置公共推理端点。模型容器沿用有界 `json-file` 日志策略。

### 单独预检

```bash
./scripts/check-gpu-runtime.sh deploy/model-profiles/h100-fp8.env.example
./scripts/check-local-models.sh --model-profile h100-fp8 --wait 30
```

以上脚本不会删除项目容器、网络、数据卷或业务数据。

## AI 服务配置

`deploy/.env.example` 默认使用本地服务地址：

```env
AI_DEPLOYMENT_MODE=LOCAL_ONLY
QWEN_BASE_URL=http://local-llm:8000/v1
# Java backend runs on the host and reaches the published vLLM port.
QWEN_VL_ENDPOINT=http://127.0.0.1:18000/v1/chat/completions
# Python AI runs in Compose and uses the service DNS name.
QWEN_VL_CONTAINER_ENDPOINT=http://local-llm:8000/v1/chat/completions
QWEN_EMBEDDING_BASE_URL=http://local-embedding:8000/v1
QWEN_RERANK_BASE_URL=http://local-reranker:8000/v1/rerank
```

Java 后端通过 Python AI 服务使用这些模型，前端不直接访问模型、数据库、MinIO 或向量数据库。
