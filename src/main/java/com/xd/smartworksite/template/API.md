# 模板模块接口文档

本文档描述 `template` 模块当前已实现的通用模板、报告模板兼容接口、审查模板兼容接口、模板预览、统一变量解析和变量描述写入接口，并提供完整测试契约。

所有接口都需要：

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

## 接口总览

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/templates` | 上传通用模板 |
| `GET` | `/api/templates` | 分页查询模板 |
| `GET` | `/api/templates/{templateId}` | 查询模板详情 |
| `PUT` | `/api/templates/{templateId}` | 更新模板元数据 |
| `POST` | `/api/templates/{templateId}/enable` | 启用模板 |
| `POST` | `/api/templates/{templateId}/disable` | 停用模板 |
| `DELETE` | `/api/templates/{templateId}` | 删除模板 |
| `GET` | `/api/templates/{templateId}/preview` | 获取模板预览文件流 |
| `GET` | `/api/templates/{templateId}/variables` | 按文件顺序获取变量名称列表 |
| `GET` | `/api/templates/{templateId}/variables/descriptions` | 按文件顺序获取变量及已有描述 |
| `PUT` | `/api/templates/{templateId}/variables/descriptions` | 新增或修改全部变量描述 |
| `POST` | `/api/templates/report` | 上传报告模板并自动解析变量 |
| `POST` | `/api/templates/review` | 上传审查模板 |
| `POST` | `/api/report/templates` | 上传报告模板兼容接口，并自动解析变量 |
| `GET` | `/api/report/templates` | 查询报告模板 |
| `GET` | `/api/report/templates/{templateId}/variables` | 解析报告模板变量 |
| `POST` | `/api/review/templates` | 上传审查模板兼容接口 |
| `GET` | `/api/review/templates` | 查询审查模板 |
| `GET` | `/api/review/templates/{templateId}` | 查询审查模板详情 |
| `PUT` | `/api/review/templates/{templateId}` | 更新审查模板 |
| `POST` | `/api/review/templates/{templateId}/enable` | 启用审查模板 |
| `POST` | `/api/review/templates/{templateId}/disable` | 停用审查模板 |
| `DELETE` | `/api/review/templates/{templateId}` | 删除审查模板 |

## 1. 上传通用模板

```text
POST /api/templates
Content-Type: multipart/form-data
```

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| templateCategory | String | 是 | `REPORT` 或 `REVIEW` |
| templateName | String | 是 | 模板名称 |
| templateType | String | 是 | 模板类型 |
| scenario | String | 否 | 使用场景 |
| versionNo | String | 否 | 版本号 |
| description | String | 否 | 描述 |
| file | MultipartFile | 是 | 模板文件 |

示例：

```bash
curl --noproxy '*' -X POST "http://127.0.0.1:8080/api/templates" \
  -H "Authorization: Bearer $TOKEN" \
  -F "projectId=1" \
  -F "templateCategory=REPORT" \
  -F "templateName=周报模板" \
  -F "templateType=WEEKLY" \
  -F "file=@/tmp/template.docx"
```

当 `templateCategory=REPORT` 时，上传应用服务会在写入 MinIO 前扫描模板中的 `{{ var_xx_xx }}`，并在文件及模板记录生成 ID 后按扫描顺序写入 `template_variable_description`；初始 `description` 为空字符串。解析或变量写入失败时整个上传失败。`REVIEW` 模板不执行该自动解析流程。

## 2. 查询模板

```text
GET /api/templates
GET /api/templates/{templateId}
```

列表查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 否 | 项目 ID |
| templateCategory | String | 否 | `REPORT`、`REVIEW` |
| templateType | String | 否 | 模板类型 |
| status | String | 否 | `ENABLED`、`DISABLED` |
| keyword | String | 否 | 名称关键字 |
| pageNo | int | 否 | 默认 1 |
| pageSize | int | 否 | 默认 20 |

无效 `templateCategory` 或 `status` 必须返回参数错误，不能静默查询空结果。

## 3. 更新、启停、删除模板

```text
PUT /api/templates/{templateId}
POST /api/templates/{templateId}/enable
POST /api/templates/{templateId}/disable
DELETE /api/templates/{templateId}
```

更新请求体字段：`templateName`、`templateType`、`scenario`、`versionNo`、`description`。

## 4. 报告模板接口

上传报告模板：

```text
POST /api/templates/report
POST /api/report/templates
Content-Type: multipart/form-data
```

请求参数：`projectId`、`templateName`、`templateType`、`scenario`、`versionNo`、`description`、`file`。前端必须显式传 `templateName` 和 `templateType`，不能依赖文件名兜底。

报告模板上传自动解析规则：

- 三个可上传 `REPORT` 的入口统一由应用服务执行，不能只在某一个 Controller 生效。
- 在上传 MinIO 前解析真实文件；格式不支持或文件损坏时不创建对象、文件元数据和模板记录。
- 变量按第一次出现顺序去重，写入现有 `template_variable_description` 表，初始描述为 `""`。
- 合法模板没有变量时允许上传成功，不写入变量记录。
- 变量插入必须检查影响行数和生成 ID；写入失败时数据库事务回滚并清理本次上传对象。
- 审查模板上传不触发变量自动解析。

查询报告模板：

```text
GET /api/report/templates
```

解析报告模板变量：

```text
GET /api/report/templates/{templateId}/variables
```

变量解析规则：

- 后端读取已上传模板文件真实内容。
- 只识别 `{{ var_xx_xx }}` 占位符，按第一次出现顺序去重。
- 当前支持 DOC、DOCX、XLS、XLSX、CSV、TXT、MD，不支持 PDF。
- 文件缺失、跨项目不一致、格式损坏、格式不支持或对象存储读取失败时直接返回错误。
- 合法模板没有变量时返回空列表。
- 不允许返回假空列表。

兼容接口已经委托第 6 节统一变量解析服务，并继续限制只能操作 `REPORT` 模板。

## 5. 审查模板接口

上传审查模板：

```text
POST /api/templates/review
POST /api/review/templates
Content-Type: multipart/form-data
```

审查模板兼容接口只允许操作 `REVIEW` 类别模板：

```text
GET /api/review/templates
GET /api/review/templates/{templateId}
PUT /api/review/templates/{templateId}
POST /api/review/templates/{templateId}/enable
POST /api/review/templates/{templateId}/disable
DELETE /api/review/templates/{templateId}
```

## 6. 模板预览与变量描述接口

以下接口已经实现。所有测试示例假设：

```bash
export BASE_URL="http://127.0.0.1:8080"
export TOKEN="<登录后获取的 accessToken>"
export TEMPLATE_ID="<已上传模板 ID>"
```

测试模板内容至少包含：

```text
项目名称：{{ var_project_name }}
报告日期：{{ var_report_date }}
安全总结：{{ var_safety_summary }}
页脚项目：{{ var_project_name }}
```

变量只允许 `{{ var_xx_xx }}` 格式，变量名匹配：

```regex
\{\{\s*(var_[a-z0-9_]+)\s*\}\}
```

同名变量按第一次出现的位置返回一次，因此示例模板应返回三个变量。

### 6.1 获取模板预览

```http
GET /api/templates/{templateId}/preview
Authorization: Bearer <accessToken>
```

成功响应直接返回文件流，不使用统一 JSON 包装：

```http
HTTP/1.1 200 OK
Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document
Content-Disposition: inline; filename*=UTF-8''template.docx
X-Request-Id: REQ-xxx
```

测试命令：

```bash
curl --noproxy '*' --fail-with-body \
  -D /tmp/template-preview.headers \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/templates/$TEMPLATE_ID/preview" \
  -o /tmp/template-preview.docx
```

验收点：

- 响应状态为 200，文件长度大于 0。
- 返回的 `Content-Type` 与模板文件类型一致。
- 返回 `Content-Disposition: inline` 和原始文件名。
- 下载文件可以被对应的 PDF、DOCX、表格或文本预览组件读取。
- 前端请求目标是 Java 接口，响应不包含 MinIO 对象名或访问地址。
- PDF 模板以 `application/pdf` 文件流返回；PDF 仍不参与报告变量扫描。
- 模板不存在、跨项目访问、文件不存在和 MinIO 读取失败返回统一错误 JSON。

不支持的其他格式仍返回统一错误 JSON；PDF 预览不再返回格式不支持错误。

### 6.2 获取模板变量列表

```http
GET /api/templates/{templateId}/variables
Authorization: Bearer <accessToken>
```

测试命令：

```bash
curl --noproxy '*' --fail-with-body \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/templates/$TEMPLATE_ID/variables"
```

成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    "var_project_name",
    "var_report_date",
    "var_safety_summary"
  ],
  "requestId": "REQ-xxx",
  "timestamp": "2026-07-16T23:00:00+08:00"
}
```

扫描规则：

- 从文件开头扫描到结尾，按第一次出现顺序返回。
- 同名变量重复出现只返回一次。
- DOCX 按页眉、正文、页脚处理；段落和表格单元格必须先合并 Runs 再匹配。
- XLS/XLSX 按工作表、行、单元格顺序处理。
- TXT、MD、CSV 按文本顺序处理。
- 当前支持 DOC、DOCX、XLS、XLSX、CSV、TXT、MD，不支持 PDF。
- 合法模板没有变量时返回 `data: []`。
- 文件损坏、格式不支持或存储读取失败返回错误，不能返回假空数组。

无变量成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": [],
  "requestId": "REQ-xxx",
  "timestamp": "2026-07-16T23:00:00+08:00"
}
```

### 6.3 查询全部变量描述

```http
GET /api/templates/{templateId}/variables/descriptions
Authorization: Bearer <accessToken>
```

接口重新扫描当前模板文件，按文件变量顺序合并 `template_id + file_id + variable_name` 对应的已持久化描述。历史模板或尚未配置的变量返回空字符串，不得丢失。

测试命令：

```bash
curl --noproxy '*' --fail-with-body \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/templates/$TEMPLATE_ID/variables/descriptions"
```

成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "variableName": "var_project_name",
      "description": "当前报告所属项目的正式名称"
    },
    {
      "variableName": "var_report_date",
      "description": ""
    }
  ],
  "requestId": "REQ-xxx",
  "timestamp": "2026-07-17T00:00:00+08:00"
}
```

### 6.4 新增或修改全部变量描述

```http
PUT /api/templates/{templateId}/variables/descriptions
Content-Type: application/json
Authorization: Bearer <accessToken>
```

请求体：

```json
{
  "variables": [
    {
      "variableName": "var_project_name",
      "description": "当前报告所属项目的正式名称"
    },
    {
      "variableName": "var_report_date",
      "description": "报告日期，格式为 yyyy-MM-dd"
    },
    {
      "variableName": "var_safety_summary",
      "description": "根据本周期安全检查记录生成的总结"
    }
  ]
}
```

测试命令：

```bash
curl --noproxy '*' --fail-with-body \
  -X PUT "$BASE_URL/api/templates/$TEMPLATE_ID/variables/descriptions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary '{
    "variables": [
      {
        "variableName": "var_project_name",
        "description": "当前报告所属项目的正式名称"
      },
      {
        "variableName": "var_report_date",
        "description": "报告日期，格式为 yyyy-MM-dd"
      },
      {
        "variableName": "var_safety_summary",
        "description": "根据本周期安全检查记录生成的总结"
      }
    ]
  }'
```

保存行为：

- 后端重新解析当前模板文件，提交变量必须覆盖解析出的全部唯一变量。
- 数据库已存在 `template_id + file_id + variable_name` 时更新描述，不存在时新增。
- 所有新增和修改在一个事务中完成，任意一项失败时全部回滚。
- 不删除其他历史文件对应的变量描述。
- 变量名最长 128，描述去除首尾空白后非空且最长 2000。
- 未知变量、缺失变量、重复变量、空描述和 PDF 文件必须返回错误。

成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "variableName": "var_project_name",
      "description": "当前报告所属项目的正式名称"
    },
    {
      "variableName": "var_report_date",
      "description": "报告日期，格式为 yyyy-MM-dd"
    },
    {
      "variableName": "var_safety_summary",
      "description": "根据本周期安全检查记录生成的总结"
    }
  ],
  "requestId": "REQ-xxx",
  "timestamp": "2026-07-16T23:00:00+08:00"
}
```

重复执行 PUT，并修改其中一个 `description`，必须更新原记录，不能插入重复变量。

缺少变量的失败请求：

```json
{
  "variables": [
    {
      "variableName": "var_project_name",
      "description": "当前报告所属项目的正式名称"
    }
  ]
}
```

对应失败响应示例：

```json
{
  "code": 40000,
  "message": "变量描述缺少模板变量: var_report_date, var_safety_summary",
  "data": null,
  "requestId": "REQ-xxx",
  "timestamp": "2026-07-16T23:00:00+08:00"
}
```

未知变量的失败请求：

```json
{
  "variables": [
    {
      "variableName": "var_not_in_template",
      "description": "模板中不存在的变量"
    }
  ]
}
```

对应失败响应示例：

```json
{
  "code": 40000,
  "message": "变量描述包含未知模板变量: var_not_in_template",
  "data": null,
  "requestId": "REQ-xxx",
  "timestamp": "2026-07-16T23:00:00+08:00"
}
```

### 6.5 联调验收清单

| 场景 | 预期结果 |
| --- | --- |
| 上传含变量的报告模板 | 上传成功，并按扫描顺序写入空描述变量记录 |
| 上传审查模板 | 不执行变量自动解析，不写入空描述变量记录 |
| 报告模板解析失败 | MinIO、文件记录和模板记录均不创建 |
| 自动变量写入失败 | 上传失败、数据库事务回滚并清理 MinIO 对象 |
| DOCX 正常预览 | 返回非空文件流和正确响应头 |
| 重复变量扫描 | 按首次出现顺序去重 |
| 变量跨 Word Run | 合并文本后能够识别 |
| 合法模板没有变量 | 成功返回空数组 |
| PDF 模板预览 | 返回 `application/pdf` 文件流；变量扫描仍返回格式不支持错误 |
| 查询变量描述 | 按文件顺序返回变量名，未配置描述返回空字符串 |
| 首次保存描述 | 为所有变量新增记录 |
| 再次保存描述 | 更新已有记录，不产生重复数据 |
| 少传变量 | 整个请求失败，不部分写入 |
| 多传未知变量 | 整个请求失败 |
| 描述为空或超长 | 参数校验失败 |
| 无项目权限 | 返回无权限错误 |
| 模板与文件项目不一致 | 返回冲突错误 |
| MinIO 不可用 | 返回可见存储错误，不返回假成功 |

## 写入规则

- 模板上传必须要求非空原始文件名、显式 `templateName` 和 `templateType`。
- 报告模板上传必须在对象存储写入前自动解析变量，并在模板、文件 ID 生成后持久化空描述变量；审查模板不执行该流程。
- 存储上传失败必须返回可见错误，不允许创建兜底模板元数据。
- 创建后必须读回持久化记录再返回。
- 更新、启用、停用、删除必须检查影响行数。
