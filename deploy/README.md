# deploy

本目录用于启动 Docker 依赖服务，包括 MySQL、Redis、MinIO、Python AI 服务，以及可选的 pgvector/Milvus。

## 文件说明

| 文件 | 说明 |
| --- | --- |
| `.env.example` | 本地环境变量示例 |
| `.env` | 本地实际环境变量，复制 `.env.example` 后生成 |
| `docker-compose-env.yml` | MySQL、Redis、MinIO、Python AI 和可选向量数据库的 Docker Compose 编排文件 |

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

## 外部AI服务配置

`.env.example` 中包含文档解析和报告生成相关配置：

```env
QWEN_VL_ENDPOINT=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
QWEN_VL_API_KEY=
QWEN_VL_MODEL=qwen-vl-plus
AI_DATABASE_QUERY_MAX_ATTEMPTS=4
```

说明：

- Qwen-VL 用于文件解析模块的模型解析适配。
- 报告生成模块使用 Java DOCX 模板渲染，并通过 Python AI 服务生成缺失模板变量。
- 数据库问答和报告变量生成会对可修复 SQL 错误进行有限自动修正，默认最多 4 次；认证和连接失败不会重试。
- 智能体和复杂 AI 能力由 Python 服务实现，Java 后端通过 HTTP 调用。
- 如果本机没有启动对应 Python 服务，相关接口会返回外部服务调用失败。
