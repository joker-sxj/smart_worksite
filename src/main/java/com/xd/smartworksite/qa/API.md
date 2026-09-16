# QA 模块接口文档

本文档描述 `qa` 模块当前已实现的问答会话、消息、引用和反馈接口。QA 通过 Java AI 适配层调用 Python 服务，不允许返回假答案或静默兜底。

前端进入或切换已有会话时，在消息渲染后定位到最新回答。生成状态轮询仅在用户原本位于消息底部附近时自动跟随；用户向上查看历史后保持阅读位置，并显示“有新回答，回到底部”操作。

所有接口都需要：

```http
Authorization: Bearer <accessToken>
```

权限要求：

| 权限 | 说明 |
| --- | --- |
| `qa:view` | 查询会话、消息和引用 |
| `qa:manage` | 创建、更新、归档、提问、重新生成、反馈 |

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

## 接口总览

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/qa/sessions` | 创建问答会话 |
| `GET` | `/api/qa/sessions` | 分页查询会话 |
| `GET` | `/api/qa/sessions/{sessionId}` | 查询会话详情 |
| `PUT` | `/api/qa/sessions/{sessionId}` | 更新会话标题 |
| `DELETE` | `/api/qa/sessions/{sessionId}` | 归档会话 |
| `POST` | `/api/qa/sessions/{sessionId}/messages` | 发送问题 |
| `GET` | `/api/qa/sessions/{sessionId}/messages` | 查询会话消息 |
| `POST` | `/api/qa/sessions/{sessionId}/messages/{messageId}/regenerate` | 重新生成答案 |
| `GET` | `/api/qa/messages/{messageId}` | 查询消息详情 |
| `GET` | `/api/qa/messages/{messageId}/references` | 查询答案引用 |
| `POST` | `/api/qa/messages/{messageId}/feedback` | 提交反馈 |

## 1. 创建会话

```text
POST /api/qa/sessions
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| title | String | 否 | 会话标题，空值时使用默认标题 |

## 2. 查询会话

```text
GET /api/qa/sessions
GET /api/qa/sessions/{sessionId}
```

列表查询参数：`projectId`、`status`、`keyword`、`pageNo`、`pageSize`。

## 3. 更新或归档会话

```text
PUT /api/qa/sessions/{sessionId}
DELETE /api/qa/sessions/{sessionId}
```

更新请求体：

```json
{
  "title": "项目安全资料问答"
}
```

删除接口为归档会话。

## 4. 发送问题

```text
POST /api/qa/sessions/{sessionId}/messages
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| question | String | 是 | 用户问题 |
| routeMode | String | 否 | `AUTO`、`MODEL`、`KNOWLEDGE`、`DATABASE`、`MIXED` |
| knowledgeBaseIds | Array<Long> | 否 | 指定知识库 |
| dataSourceIds | Array<Long> | 否 | 指定数据源 |

示例：

```bash
curl --noproxy '*' -X POST "http://127.0.0.1:8080/api/qa/sessions/1/messages" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"question":"本项目有哪些安全风险？","routeMode":"AUTO","knowledgeBaseIds":[1]}'
```

发送前校验：

- `knowledgeBaseIds` 必须存在、属于会话项目且处于 `ENABLED`。
- `dataSourceIds` 必须存在、属于会话项目且处于 `ENABLED`。
- 跨项目或停用引用会在调用 AI 前失败。

## 5. 查询消息和引用

```text
GET /api/qa/sessions/{sessionId}/messages
GET /api/qa/messages/{messageId}
GET /api/qa/messages/{messageId}/references
```

消息响应重点字段：

| 字段 | 说明 |
| --- | --- |
| question | 问题 |
| answer | 答案 |
| routeMode | 路由模式 |
| references | 引用资料 |
| rawResult | AI 原始结果摘要 |
| status | 状态 |
| needClarification | 是否需要澄清 |
| clarificationQuestions | 澄清问题 |
| providerTraceId | Python 服务 trace ID |

## 6. 重新生成答案

```text
POST /api/qa/sessions/{sessionId}/messages/{messageId}/regenerate
```

该接口会基于原问题重新调用 AI。

## 7. 提交反馈

```text
POST /api/qa/messages/{messageId}/feedback
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| feedbackType | String | 是 | 反馈类型 |
| comment | String | 否 | 反馈说明 |
| metadata | Object | 否 | 额外信息 |

## 写入规则

- 问题消息创建后必须持有可读 ID。
- AI 返回后写入答案、引用和状态必须检查影响行数。
- 持久化失败必须返回冲突，不能把未保存的 AI 答案报告为成功。
- 外部 AI 调用必须写入 `external_call_log`。

## 报告生成内部调用

报告 Worker 通过 `ReportQaApplicationService` 复用 QA/RAG 网关，不经过本应用 HTTP Controller，也不创建普通 `qa_session` 或 `qa_message`。Worker 调用网关的 `searchKnowledgeForSystem` 和 `invokeModelForSystem`，仅重新校验项目存在且可写，不依赖 HTTP 请求线程的登录上下文；普通问答仍使用带用户权限校验的入口。每次调用只包含一个报告变量及其描述、一个知识库和报告元数据，上下文消息固定为空。知识库检索为空时仍调用模型生成通用内容；知识库非法、RAG 调用失败、模型失败或空答案必须向报告任务暴露错误。
