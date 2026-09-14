# 合规审查模块接口文档

本文档描述 `review` 模块当前已实现的合规审查记录、问题处理和重试接口。审查执行通过 Java AI 适配层调用 Python Agent。

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
| file | MultipartFile | 是 | 主审查文件 |
| referenceDocumentIds | Long[] | 否 | 当前项目已解析成功、已入库的知识文档 ID |
| referenceFileIds | Long[] | 否 | 当前项目已授权的临时参考文件 ID |
| referenceFiles | MultipartFile[] | 否 | 本次临时参考文件，最多 10 个；所有参考资料合计最多 20 个 |
| schemaVersion | Integer | 否 | 自定义审查字段配置版本 |
| fieldValues | JSON string | 否 | 自定义审查输入字段值 |

示例：

```bash
curl --noproxy '*' -X POST "http://127.0.0.1:8080/api/review/records" \
  -H "Authorization: Bearer $TOKEN" \
  -F "projectId=1" \
  -F "templateId=1" \
  -F "file=@/tmp/contract.docx"
```

响应重点字段：`recordId`、`projectId`、`templateId`、`templateName`、`templateVersion`、`fileId`、`taskId`、`status`、`issues`、`result.ruleResults`、`references`、`errorMessage`。模板名称和版本是提交时快照，不随模板后续编辑变化。

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
PARSING
RULES_READY
REVIEWING
COMPLETED
PARTIAL_SUCCESS
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
- 每条规则独立保存 `COMPLETED`、`NEEDS_MANUAL_CONFIRMATION` 或 `FAILED` 状态，并区分主文件证据与参考资料证据；证据不足时不得伪造结论。
- `COMPLETED` 和 `PARTIAL_SUCCESS` 记录中的已生成问题均可更新处理状态。
