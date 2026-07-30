# AI 模块接口文档

本文档描述 `ai` 模块当前已实现的 Java AI 适配层接口。该模块只负责鉴权、项目隔离、调用编排、数据库安全校验和外部调用日志；模型、Agent、RAG、Embedding、OCR 算法能力由 `python-ai-service` 提供。

除 `/api/auth/login`、`/api/system/ping`、`/actuator/health`、`/actuator/info` 外，接口都需要：

```http
Authorization: Bearer <accessToken>
```

统一响应结构：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "requestId": "xxx",
  "timestamp": "2026-07-11T16:00:00+08:00"
}
```

## 前置配置

Java 后端通过 Python 服务密钥调用智能服务：

```env
AI_PYTHON_BASE_URL=http://127.0.0.1:8015
AI_PYTHON_API_KEY=dev-ai-service-key
AI_PYTHON_CONNECT_TIMEOUT_MS=5000
AI_PYTHON_READ_TIMEOUT_MS=120000
AI_PYTHON_RETRY_COUNT=1
```

数据源问答如果需要解密数据源密码，本地开发可使用默认 Key，生产环境必须配置独立 Key：

```env
AI_DATA_SOURCE_PASSWORD_KEY=0123456789abcdef0123456789abcdef
```

Qwen Key 只能配置在 `python-ai-service/.env` 或运行环境变量中，不要写入 Java 配置、SQL、文档或日志。

## 接口总览

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/ai/model/invoke` | 调用 Python 模型能力 |
| `POST` | `/api/ai/agent/invoke` | 调用 Python Agent 能力 |
| `POST` | `/api/ai/knowledge/search` | RAG 检索 |
| `POST` | `/api/ai/knowledge/index` | RAG 索引 |
| `POST` | `/api/ai/database/query` | 数据库问答，只执行安全只读 SQL |
| `POST` | `/api/ai/route` | 智能路由 |
| `POST` | `/api/ai/context/prepare` | 上下文准备 |
| `GET` | `/api/ai/external-call-logs` | 查询外部 AI 调用日志 |

## 1. 调用模型

```text
POST /api/ai/model/invoke
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| prompt | String | 是 | 用户问题或任务 |
| systemPrompt | String | 否 | 系统提示词 |
| modelName | String | 否 | 模型名，未传由 Python 服务默认 |
| options | Object | 否 | 调用参数 |
| contextMessages | Array | 否 | 上下文消息，包含 `messageId`、`role`、`content` |

示例：

```bash
curl --noproxy '*' -X POST "http://127.0.0.1:8080/api/ai/model/invoke" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"projectId":1,"prompt":"总结本项目风险点","systemPrompt":"你是智慧工地助手"}'
```

## 2. 调用 Agent

```text
POST /api/ai/agent/invoke
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| goal | String | 是 | Agent 目标 |
| tools | Array | 否 | 允许使用的工具名 |
| contextMessages | Array | 否 | 上下文消息 |
| options | Object | 否 | 调用参数 |

响应重点字段：

| 字段 | 说明 |
| --- | --- |
| result | Agent 结果 |
| toolCalls | 工具调用摘要 |
| followUpQuestions | 需要追问的问题 |
| providerTraceId | Python 服务 trace ID |

## 3. RAG 检索

```text
POST /api/ai/knowledge/search
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| query | String | 是 | 检索问题 |
| knowledgeBaseIds | Array<Long> | 否 | 限定知识库 |
| libraryTypes | Array<String> | 否 | 知识库类型 |
| topK | Integer | 否 | 返回数量 |
| scoreThreshold | Double | 否 | 分数阈值 |
| rerankEnabled | Boolean | 否 | 是否 rerank |

## 4. RAG 索引

```text
POST /api/ai/knowledge/index
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| knowledgeBaseId | Long | 是 | 知识库 ID |
| documents | Array | 是 | 待索引文档 |
| chunkSize | Integer | 否 | 分块大小 |
| chunkOverlap | Integer | 否 | 分块重叠 |

`documents` 元素字段：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| documentId | String | 是 | 文档业务 ID |
| title | String | 是 | 标题 |
| content | String | 是 | 文档内容 |
| sourceType | String | 否 | 来源类型 |
| sourceId | String | 否 | 来源 ID |
| metadata | Object | 否 | 元数据 |

## 5. 数据库问答

```text
POST /api/ai/database/query
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| dataSourceId | Long | 是 | 数据源 ID |
| question | String | 是 | 用户问题 |
| context | String | 否 | 额外上下文 |

说明：

- Java 会读取数据源配置并调用 Python 生成 SQL。
- Java 只允许执行安全只读 `SELECT` / `WITH` SQL。
- 不返回数据源密码密文或明文。

## 6. 智能路由

```text
POST /api/ai/route
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| question | String | 是 | 用户问题 |
| availableKnowledgeBaseIds | Array<Long> | 否 | 可用知识库 |
| availableDataSourceIds | Array<Long> | 否 | 可用数据源 |
| contextMessages | Array | 否 | 上下文消息 |

响应重点字段：`routeType`、`reason`、`requiredInputs`、`followUpQuestions`、`providerTraceId`。

## 7. 上下文准备

```text
POST /api/ai/context/prepare
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| messages | Array | 否 | 历史消息 |
| currentQuestion | String | 是 | 当前问题 |
| maxContextLength | Integer | 否 | 最大上下文长度 |

## 8. 查询外部调用日志

```text
GET /api/ai/external-call-logs
```

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 否 | 项目 ID |
| serviceName | String | 否 | 服务名 |
| callType | String | 否 | 调用类型 |
| status | String | 否 | `SUCCESS`、`FAILED` |
| pageNo | Integer | 否 | 默认 1 |
| pageSize | Integer | 否 | 默认 20 |

## 可观测性要求

- 每次外部 AI 调用都应写入 `external_call_log`。
- 调用失败时保留原始服务错误；日志写入失败时也必须暴露出来。
- 日志摘要不得包含密码、token、MinIO 密钥、Qwen Key、身份证号、图片 base64 等敏感内容。
