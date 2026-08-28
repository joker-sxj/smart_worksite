# 合规审查模块接口文档

本文档描述 `review` 模块当前已实现的合规审查记录、问题处理和重试接口。提交接口创建异步任务后立即返回，Worker 再通过 Java AI 适配层调用 Python Agent。审查支持一个待审主文件、多个参考 PDF/Word 文件和多个项目知识库，完成结果包含经过来源白名单校验的 `references`。

所有接口都需要：

```http
Authorization: Bearer <accessToken>
```

权限要求：

| 权限 | 说明 |
| --- | --- |
| `review:view` | 查询审查记录 |
| `review:manage` | 提交、重试、删除、归档和更新问题 |

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
| `POST` | `/api/review/records` | 提交审查文件 |
| `GET` | `/api/review/records` | 分页查询审查记录 |
| `GET` | `/api/review/records/{recordId}` | 查询审查详情 |
| `POST` | `/api/review/records/{recordId}/retry` | 重试失败审查 |
| `DELETE` | `/api/review/records/{recordId}` | 删除审查记录 |
| `POST` | `/api/review/records/{recordId}/archive` | 归档审查记录 |
| `PUT` | `/api/review/records/{recordId}/issues/{issueId}` | 更新审查问题状态 |

## 1. 提交审查

```text
POST /api/review/records
Content-Type: multipart/form-data
```

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| templateId | Long | 是 | 审查模板 ID |
| file | MultipartFile | 是 | 审查文件 |

示例：

```bash
curl --noproxy '*' -X POST "http://127.0.0.1:8080/api/review/records" \
  -H "Authorization: Bearer $TOKEN" \
  -F "projectId=1" \
  -F "templateId=1" \
  -F "file=@/tmp/contract.docx"
```

响应重点字段：`recordId`、`projectId`、`templateId`、`fileId`、`taskId`、`status`、`issues`、`summary`、`errorMessage`。

## 2. 查询审查记录

```text
GET /api/review/records
GET /api/review/records/{recordId}
```

列表查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 否 | 项目 ID |
| status | String | 否 | 审查状态 |
| templateId | Long | 否 | 模板 ID |
| pageNo | int | 否 | 默认 1 |
| pageSize | int | 否 | 默认 20 |

状态通常包括：

```text
PENDING
PROCESSING
SUCCESS
FAILED
ARCHIVED
DELETED
```

## 3. 重试审查

```text
POST /api/review/records/{recordId}/retry
```

说明：用于失败记录重新调用 Python Agent。

## 4. 删除和归档

```text
DELETE /api/review/records/{recordId}
POST /api/review/records/{recordId}/archive
```

删除为逻辑删除，归档会将记录置为归档状态。

## 5. 更新问题状态

```text
PUT /api/review/records/{recordId}/issues/{issueId}
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| status | String | 是 | 问题处理状态 |
| comment | String | 否 | 处理说明 |

示例：

```bash
curl --noproxy '*' -X PUT "http://127.0.0.1:8080/api/review/records/1/issues/ISSUE-1" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"status":"RESOLVED","comment":"已补充整改材料"}'
```

## 执行规则

- 提交审查记录后必须校验生成 ID 并读回持久化记录，失败时不调用 Python Agent。
- Python Agent 返回失败、空结果或无效 JSON 时，审查记录必须标记为 `FAILED` 并记录错误。
- 如果失败状态无法落库，必须返回冲突，不能丢失可观测性。
- 调用 Python Agent 必须记录外部调用日志。
