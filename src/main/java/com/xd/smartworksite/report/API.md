# 报告模块接口文档

本文档描述 `report` 模块当前已实现的知识库和数据库报告生成接口。Java 创建报告、变量快照和异步任务；Worker 按模板顺序逐个通过 AI 路由选择 RAG、只读数据库查询或混合能力生成变量，保存后使用 Java DOCX 模板引擎渲染报告。

所有接口都需要：

```http
Authorization: Bearer <accessToken>
```

## 前置配置

异步生成需要开启任务 outbox 和 Worker：

```env
TASK_OUTBOX_DISPATCHER_ENABLED=true
TASK_WORKER_ENABLED=true
```

Python AI 服务用于知识库检索和模型生成：

```env
AI_PYTHON_BASE_URL=http://127.0.0.1:8015
AI_PYTHON_API_KEY=dev-ai-service-key
```

## 接口总览

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/reports` | 创建报告生成任务 |
| `GET` | `/api/reports` | 分页查询报告 |
| `GET` | `/api/reports/{reportId}` | 查询报告详情 |
| `GET` | `/api/reports/{reportId}/variables` | 查询报告变量生成状态和结果 |
| `POST` | `/api/reports/{reportId}/regenerate` | 重新生成报告 |
| `GET` | `/api/reports/{reportId}/download` | 获取下载地址 |

## 1. 创建报告

```text
POST /api/reports
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| reportName | String | 是 | 报告名称 |
| reportType | String | 是 | 报告类型 |
| templateId | Long | 是 | DOCX 报告模板 ID |
| knowledgeBaseIds | Array<Long> | 条件必填 | 当前项目内已启用 `PROJECT` 知识库 ID，可多选 |
| dataSourceIds | Array<Long> | 条件必填 | 当前项目内已启用数据源 ID，可多选 |
| knowledgeBaseId | Long | 否 | 旧版单知识库兼容字段，仅当 `knowledgeBaseIds` 为空时生效 |

示例：

```bash
curl --noproxy '*' -X POST "http://127.0.0.1:8080/api/reports" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"projectId":1,"reportName":"周安全报告","reportType":"WEEKLY","templateId":1,"knowledgeBaseIds":[10,11],"dataSourceIds":[20]}'
```

响应重点字段：`reportId`、`taskId`、`status`。创建接口返回 `PENDING`，实际生成由 Worker 异步执行。

校验规则：

- `reportName` 必须显式传入。
- `templateId` 必须是当前项目启用的 DOCX 报告模板。
- `knowledgeBaseIds` 与 `dataSourceIds` 至少一项非空；知识库只允许当前项目已启用的 `PROJECT` 库，数据源必须属于当前项目且已启用。
- 模板变量可配置 `dataSourceIds` 白名单；留空时 AI 可在报告所选数据源中自动选择，非空时只使用两者交集。
- 同时存在知识库与数据源时，AI 按变量路由到知识检索、数据库查询或混合生成；数据库 SQL 始终经过 Java 只读安全校验。
- 模板只支持 `{{ var_xx_xx }}` 占位符；同名变量重复出现时只生成一次。
- 模板必须包含至少一个变量，且全部变量都已配置非空描述；错误会列出缺少描述的变量名。
- 知识库必须存在、属于当前项目并处于 `ENABLED`。
- Worker 按变量顺序逐项执行，每次只使用当前变量描述和知识库，不共享问答上下文，也不创建普通 QA 会话历史。
- 检索结果为空时允许模型基于通用知识生成，但不得伪造具体项目数据。
- 项目必须处于可写状态。

## 2. 查询报告

```text
GET /api/reports
GET /api/reports/{reportId}
```

报告状态：`DRAFT`、`PENDING`、`PROCESSING`、`COMPLETED`、`FAILED`、`ARCHIVED`、`DELETED`。

报告变量：

```text
GET /api/reports/{reportId}/variables
```

按模板顺序返回 `variableName`、`variableDescription`、`variableValue`、`sortNo`、`status`、`providerTraceId`、`errorMessage` 和执行时间。变量状态为 `PENDING`、`RUNNING`、`SUCCESS` 或 `FAILED`。

## 3. 重新生成

```text
POST /api/reports/{reportId}/regenerate
```

说明：基于原报告的知识库和数据源配置创建新的报告和任务。失败任务通过任务重试接口重试时，保留已成功变量，仅重新生成失败或未处理变量。

## 4. 下载报告

```text
GET /api/reports/{reportId}/download?format=WORD
```

当前支持 Word 下载。报告必须已完成，接口返回 MinIO 预签名下载地址字符串。前端先携带 JWT 调用本接口获取地址，再使用不携带 `Authorization` 和 `X-Request-Id` 的独立请求读取该预签名地址；签名查询参数本身就是 MinIO 鉴权，不能同时附加 Bearer Token。`format=PDF` 明确不支持。

## 写入规则

- 报告创建必须写入报告记录、任务、outbox。
- 报告、任务、文件和版本状态流转必须检查影响行数或生成 ID。
- Worker 执行前必须重新校验项目可写。
- 每个变量状态写入都必须检查影响行数；单变量失败时整份报告失败，但已成功变量保留用于任务重试。
- Python AI 服务不可用、知识库或数据源不可用、变量缺失、模板不合法时，必须记录变量、任务和报告失败原因，不能返回假成功。
