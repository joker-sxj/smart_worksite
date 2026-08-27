# OCR API 测试指南

本文档说明如何测试 `com.xd.smartworksite.ocr` 模块下已实现的 OCR 接口。

OCR 识别是异步流程：

```text
前端或测试客户端
  -> Java /api/ocr/records
  -> Java 保存 OCR_INPUT 文件，并创建 ocr_record + generate_task
  -> Java Worker 调用 python-ai-service /v1/ocr/recognize
  -> python-ai-service 下载图片临时 URL，并转成 base64 data URL 调用 Qwen VL
  -> Java 保存 fields_json 并更新状态
```

## 1. 前置条件

### 1.1 启动本地依赖

在仓库根目录执行：

```bash
cd deploy
cp .env.example .env
docker compose -f docker-compose-env.yml --env-file .env up -d
```

Docker 会启动 MySQL、Redis 和 MinIO。业务表由 Java 后端启动时通过 Flyway 自动创建。

本地种子数据：

| 项目 | 值 |
| --- | --- |
| 用户名 | `admin` |
| 密码 | `admin123` |
| 默认项目 ID | `1` |

### 1.2 配置本地模型 Profile

生产环境不在 Java 或 Python 服务中配置公网模型 Token。Java 只调用 Python AI 服务，Python 再调用本机通过 vLLM 暴露的 OpenAI-compatible 接口。双 RTX A6000 48GB 优先使用 32K 目标 Profile；如果客户机实测边界请求失败，切换到 16K 稳定 Profile。

```bash
cd /home/xidian/sjw/smart_worksite
cp deploy/.env.example deploy/.env  # 仅首次执行
./scripts/start-all.sh --check --model-profile a6000x2-stable-16k
```

可选 Profile：

| Profile | 用途 | 上下文目标 |
| --- | --- | --- |
| `a6000x2-production-32k` | 客户双 A6000 生产目标，必须以实际冒烟和 benchmark 结果验收 | 32K |
| `a6000x2-stable-16k` | 显存、延迟或并发不足时的稳定回退 | 16K |

爬虫联网开关与模型推理开关独立：

```env
AI_DEPLOYMENT_MODE=LOCAL_ONLY
AI_ALLOW_REMOTE_INFERENCE=false
AI_ALLOW_CLOUD_FALLBACK=false
POLICY_CRAWLER_NETWORK_ENABLED=false
```

需要爬取已批准的政策源时，只启用 `POLICY_CRAWLER_NETWORK_ENABLED=true`，不要因此开启公网模型推理。

### 1.3 启动和检查服务

推荐统一启动，不需要另开 Python 模型脚本：

```bash
cd /home/xidian/sjw/smart_worksite
./scripts/start-all.sh --model-profile a6000x2-production-32k
./scripts/status.sh
```

启动流程会依次执行 GPU/Profile 检查、Docker 模型服务就绪检查、32K/16K 上下文边界生成、Embedding 和 Rerank 冒烟。失败时先查看：

```bash
docker compose -f deploy/docker-compose-env.yml -f deploy/docker-compose-models.yml --env-file deploy/.env logs --tail=200 local-llm local-embedding local-reranker
```

Java 依赖健康接口会显示 `localAi` 的 `UP`、`DEGRADED` 或 `DOWN`、当前 Profile、上下文上限和模型可达性，但不会返回 endpoint、API Key 或 Authorization。

### 1.4 OCR 调用链

```text
Java /api/ocr/records
  -> Java 保存 OCR_INPUT 文件和任务
  -> Java 调用 python-ai-service /v1/ocr/recognize
  -> Python 从本地 MinIO 下载图片并转为 data URL
  -> Python 调用本地模型服务
  -> Java 保存 fields_json，并记录实际 provider/model
```

本地模型未就绪时，OCR 任务不会偷偷切换公网模型：有可用文本时可使用受控文本回退；纯图片识别会明确失败并进入 `FAILED`，应先恢复本地模型。

## 2. 登录并设置 Token

登录：

```bash
TOKEN=$(curl --noproxy '*' -s -X POST "http://127.0.0.1:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')

echo "$TOKEN"
```

快速验证当前用户：

```bash
curl --noproxy '*' -s "http://127.0.0.1:8080/api/auth/me" \
  -H "Authorization: Bearer $TOKEN"
```

所有 OCR 接口都需要请求头：

```http
Authorization: Bearer <accessToken>
```

当前用户需要拥有 `ocr:view` 权限，并且需要有目标项目访问权限。本地 `admin` 用户可以测试项目 `1`。

## 3. 接口总览

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/ocr/records` | 提交 OCR 识别任务 |
| `GET` | `/api/ocr/records` | 分页查询 OCR 记录 |
| `GET` | `/api/ocr/records/{recordId}` | 查询 OCR 详情 |
| `PUT` | `/api/ocr/records/{recordId}/fields` | 保存人工修订字段 |
| `POST` | `/api/ocr/records/{recordId}/retry` | 重试识别 |
| `DELETE` | `/api/ocr/records/{recordId}` | 逻辑删除 OCR 记录 |
| `GET` | `/api/ocr/records/{recordId}/download` | 查询 OCR 结果 JSON |
| `GET` | `/api/ocr/types` | 查询支持的 OCR 类型 |

统一响应结构：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "requestId": "...",
  "timestamp": "..."
}
```

## 4. 查询支持的 OCR 类型

```bash
curl --noproxy '*' -s "http://127.0.0.1:8080/api/ocr/types" \
  -H "Authorization: Bearer $TOKEN"
```

预期 `data` 包含：

```json
[
  {"ocrType": "ID_CARD", "name": "身份证识别"},
  {"ocrType": "LICENSE_PLATE", "name": "车牌识别"},
  {"ocrType": "INVOICE", "name": "发票识别"},
  {"ocrType": "CUSTOM", "name": "自定义字段识别"}
]
```

## 5. 提交 OCR 任务

提交接口使用 `multipart/form-data`。

支持的 `ocrType`：

```text
ID_CARD
LICENSE_PLATE
INVOICE
CUSTOM
```

`CONTRACT` 作为旧前端兼容别名仍可传入，后端会规范化为 `CUSTOM`。新的合同字段抽取建议统一使用 `CUSTOM`。

### 5.1 身份证 OCR

```bash
curl --noproxy '*' -s -X POST "http://127.0.0.1:8080/api/ocr/records" \
  -H "Authorization: Bearer $TOKEN" \
  -F "projectId=1" \
  -F "ocrType=ID_CARD" \
  -F "file=@/tmp/id-card.jpg"
```

预期响应：

```json
{
  "code": 0,
  "data": {
    "recordId": 1,
    "taskId": 1,
    "status": "PENDING"
  }
}
```

识别成功后预期包含字段：

```text
name, gender, nation, birthDate, address, idNumber,
issuingAuthority, validPeriod, hasWatermark
```

水印详情预期位于：

```json
{
  "rawResult": {
    "extras": {
      "watermark": {
        "detected": true,
        "type": "TEXT",
        "text": "仅用于...",
        "confidence": 0.88
      }
    }
  }
}
```

### 5.2 车牌 OCR

```bash
curl --noproxy '*' -s -X POST "http://127.0.0.1:8080/api/ocr/records" \
  -H "Authorization: Bearer $TOKEN" \
  -F "projectId=1" \
  -F "ocrType=LICENSE_PLATE" \
  -F "file=@/tmp/plate.jpg"
```

预期字段：

```text
plateNumber, backgroundColor, fontColor, plateType
```

典型颜色值：

```text
BLUE, YELLOW, GREEN, GRADIENT_GREEN, WHITE, BLACK, OTHER, UNKNOWN
```

### 5.3 发票 OCR

`ocrType=INVOICE` 时必须传 `invoiceType`。

支持的发票类型：

```text
VAT_SPECIAL
VAT_NORMAL
```

```bash
curl --noproxy '*' -s -X POST "http://127.0.0.1:8080/api/ocr/records" \
  -H "Authorization: Bearer $TOKEN" \
  -F "projectId=1" \
  -F "ocrType=INVOICE" \
  -F "invoiceType=VAT_SPECIAL" \
  -F "file=@/tmp/invoice.jpg"
```

预期字段：

```text
invoiceType, invoiceCode, invoiceNumber, issueDate,
buyerName, buyerTaxNumber, sellerName, sellerTaxNumber,
amountWithoutTax, taxAmount, totalAmount
```

如果识别到了发票明细行，会返回到：

```json
{
  "rawResult": {
    "extras": {
      "items": [],
      "validation": {}
    }
  }
}
```

### 5.4 自定义 OCR / 合同字段抽取

`ocrType=CUSTOM` 时必须传 `customFields`，且 `customFields` 必须是 JSON 数组字符串。

Bash 示例：

```bash
CUSTOM_FIELDS='[{"fieldKey":"partyA","fieldName":"甲方","description":"合同中的甲方名称","required":true,"valueType":"TEXT"},{"fieldKey":"partyB","fieldName":"乙方","description":"合同中的乙方名称","required":true,"valueType":"TEXT"},{"fieldKey":"contractAmount","fieldName":"合同金额","description":"合同总金额，保留币种和大小写金额","required":true,"valueType":"AMOUNT"},{"fieldKey":"paymentTerms","fieldName":"付款条件","description":"付款节点、比例、触发条件和期限","required":false,"valueType":"TEXT"}]'

curl --noproxy '*' -s -X POST "http://127.0.0.1:8080/api/ocr/records" \
  -H "Authorization: Bearer $TOKEN" \
  -F "projectId=1" \
  -F "ocrType=CUSTOM" \
  -F "customFields=$CUSTOM_FIELDS" \
  -F "file=@/tmp/contract.pdf"
```

预期字段：

```text
partyA, partyB, contractAmount, paymentTerms
```

每个字段建议包含：

```json
{
  "fieldKey": "partyA",
  "fieldName": "甲方",
  "fieldValue": "某某建设有限公司",
  "confidence": 0.91,
  "location": "第1页 第3段",
  "pageNo": 1,
  "evidence": "甲方：某某建设有限公司"
}
```

## 6. 轮询识别结果

OCR 提交接口会很快返回。提交后需要轮询详情接口，直到 `status` 变为 `SUCCESS` 或 `FAILED`。

```bash
RECORD_ID=1

curl --noproxy '*' -s "http://127.0.0.1:8080/api/ocr/records/$RECORD_ID" \
  -H "Authorization: Bearer $TOKEN"
```

重点关注字段：

| 字段 | 含义 |
| --- | --- |
| `status` | `PENDING`、`PROCESSING`、`SUCCESS`、`FAILED`、`CANCELED` |
| `progress` | 简单进度值：`0`、`60` 或 `100` |
| `fields` | 标准化 OCR 字段 |
| `rawResult.summary.providerTraceId` | Python 服务返回的 trace ID |
| `rawResult.summary.provider` | `QWEN_VL` |
| `errorMessage` | `FAILED` 时的失败原因 |

成功响应示例：

```json
{
  "code": 0,
  "data": {
    "recordId": 1,
    "projectId": 1,
    "taskId": 1,
    "fileId": 1,
    "ocrType": "CUSTOM",
    "status": "SUCCESS",
    "progress": 100,
    "fields": [
      {
        "fieldKey": "partyA",
        "fieldName": "甲方",
        "fieldValue": "某某建设有限公司",
        "confidence": 0.91,
        "location": "第1页 第3段",
        "pageNo": 1,
        "evidence": "甲方：某某建设有限公司",
        "revised": false
      }
    ]
  }
}
```

## 7. 查询 OCR 记录列表

```bash
curl --noproxy '*' -s "http://127.0.0.1:8080/api/ocr/records?projectId=1&pageNo=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
```

按类型和状态筛选：

```bash
curl --noproxy '*' -s "http://127.0.0.1:8080/api/ocr/records?projectId=1&ocrType=CUSTOM&status=SUCCESS&pageNo=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
```

关键字搜索会匹配 `fields_json`：

```bash
curl --noproxy '*' -s "http://127.0.0.1:8080/api/ocr/records?projectId=1&keyword=甲方&pageNo=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
```

## 8. 保存人工修订字段

```bash
RECORD_ID=1

curl --noproxy '*' -s -X PUT "http://127.0.0.1:8080/api/ocr/records/$RECORD_ID/fields" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"fields":[{"fieldKey":"partyA","fieldName":"甲方","fieldValue":"杭州某某建设有限公司","confidence":1.0,"location":"人工修订","pageNo":1,"evidence":"人工复核","revised":true}]}'
```

预期行为：

- `fields_json.fields` 会被提交的字段列表替换。
- 返回字段会带有 `revised=true`。
- 该接口不会改变 OCR 记录的 `status`。

## 9. 重试失败识别

```bash
RECORD_ID=1

curl --noproxy '*' -s -X POST "http://127.0.0.1:8080/api/ocr/records/$RECORD_ID/retry" \
  -H "Authorization: Bearer $TOKEN"
```

预期响应：

```json
{
  "code": 0,
  "data": {
    "recordId": 1,
    "taskId": 2,
    "status": "PENDING"
  }
}
```

重试会创建新的 `generate_task`，绑定到原 OCR 记录，并再次启动识别。

## 10. 查询 OCR 结果 JSON

```bash
RECORD_ID=1

curl --noproxy '*' -s "http://127.0.0.1:8080/api/ocr/records/$RECORD_ID/download" \
  -H "Authorization: Bearer $TOKEN"
```

该接口通过统一响应结构返回已保存的 `fields_json`。当前不会生成物理下载文件。

## 11. 删除 OCR 记录

```bash
RECORD_ID=1

curl --noproxy '*' -s -X DELETE "http://127.0.0.1:8080/api/ocr/records/$RECORD_ID" \
  -H "Authorization: Bearer $TOKEN"
```

预期行为：

- OCR 记录被逻辑删除。
- 后续查询详情返回 `40400`。
- 该 OCR 接口不会物理删除源文件对象。

## 12. 直接测试 Python OCR 接口

通常该接口由 Java 调用。直接测试它可以用来排查 Python 服务或 Qwen VL 调用问题。

首先需要准备一个 Python 服务可访问的临时文件 URL。最简单的方式是先通过 Java 上传文件，再查询文件访问 URL；也可以使用任何 Python 服务能访问到的临时 URL。

图片 OCR 会由 Python 先下载该 URL，再转成 `data:image/...;base64,...` 发送给 Qwen VL。Qwen 云端不会直接访问或看到 MinIO 临时签名 URL。

请求示例：

```bash
curl --noproxy '*' -s -X POST "http://127.0.0.1:8015/v1/ocr/recognize" \
  -H "Content-Type: application/json" \
  -H "X-AI-Service-Key: dev-ai-service-key" \
  -d '{"projectId":1,"recordId":1,"ocrType":"LICENSE_PLATE","file":{"fileId":1,"fileName":"plate.jpg","contentType":"image/jpeg","downloadUrl":"https://example.com/plate.jpg"},"options":{}}'
```

预期响应：

```json
{
  "success": true,
  "traceId": "...",
  "data": {
    "ocrType": "LICENSE_PLATE",
    "confidence": 0.9,
    "fields": []
  },
  "usage": {
    "provider": "QWEN_VL",
    "model": "qwen-vl-plus"
  }
}
```

## 13. 验证数据库记录

以下 SQL 只建议本地调试使用。正常业务流程不要绕过 Java API 直接操作数据库。

```sql
select id, project_id, ocr_type, file_id, task_id, status, error_message, created_at, updated_at
from ocr_record
where deleted = 0
order by id desc;

select id, project_id, task_type, biz_type, biz_id, status, current_stage, error_message
from generate_task
where task_type = 'OCR_RECOGNITION'
order by id desc;

select id, project_id, task_id, stage_code, status, error_message, cost_ms
from task_stage_log
where stage_code = 'OCR_RECOGNITION'
order by id desc;

select id, project_id, service_name, call_type, status, cost_ms, error_message
from external_call_log
where call_type = 'OCR_RECOGNIZE'
order by id desc;
```

## 14. 预期错误场景

### 14.1 缺少 token

不传 `Authorization` 请求头：

```json
{
  "code": 40100,
  "message": "未登录或登录已过期"
}
```

### 14.2 发票类型缺失

提交 `ocrType=INVOICE` 但不传 `invoiceType`：

```json
{
  "code": 40000,
  "message": "invoiceType must be VAT_SPECIAL or VAT_NORMAL"
}
```

### 14.3 自定义字段缺失

提交 `ocrType=CUSTOM` 但不传 `customFields`：

```json
{
  "code": 40000,
  "message": "customFields is required when ocrType is CUSTOM"
}
```

### 14.4 Python 服务不可用

如果 Python 服务未启动，或 `AI_PYTHON_BASE_URL` 配置错误：

- OCR 记录会被创建。
- Worker 会把记录更新为 `FAILED`。
- 详情接口显示 `status=FAILED`。
- `errorMessage` 中会包含 Python OCR 服务调用失败信息。

如果本地异步任务显示 `errorMessage=未登录`，优先确认后端版本包含 OCR Worker 的系统安全文件访问逻辑；OCR Worker 不应依赖提交请求线程的登录态。

### 14.5 Qwen VL 密钥缺失

如果 Python 中 `QWEN_VL_API_KEY` 和 `QWEN_API_KEY` 都为空：

- Python `/v1/ocr/recognize` 返回 `success=false`。
- Java OCR 记录变为 `FAILED`。
- `external_call_log.call_type = OCR_RECOGNIZE` 会记录失败信息。

### 14.6 Qwen VL 返回非合法 JSON

如果详情接口中的 `errorMessage` 类似 `Expecting ',' delimiter` 或 `Qwen VL returned invalid JSON`：

- 说明 Qwen VL 已返回内容，但内容不是合法 JSON，常见原因是输出被截断、字段证据文本过长，或字符串中出现未转义双引号。
- Python 服务会自动使用同一张图片重试一次，并通过 `QWEN_VL_MAX_TOKENS` 控制最大输出长度。
- 修改 `python-ai-service/.env` 后需要重启 Python 服务，再调用 OCR 重试接口。

## 15. PDF 说明

当前 Python OCR 路径只会对图片执行“下载临时 URL -> 转 base64 data URL -> 调用 Qwen VL”。这样可以避免 Qwen 云端直接访问本地或内网 MinIO URL。

在 `contentType=application/pdf` 时，当前仍会把 PDF 作为 `file_url` 发送给 Qwen VL。

这只有在当前配置的 Qwen VL endpoint 支持 PDF 文件 URL 时才可用。如果模型服务拒绝 PDF 输入，需要在 `python-ai-service` 增加 PDF 预处理：

```text
PDF 类型判断
  -> 文本型 PDF 提取文本
  -> 扫描型 PDF 渲染页面图片
  -> 按页或分批调用 Qwen VL
  -> 合并字段，并保留 pageNo/location/evidence
```

这个优化不需要改变 Java OCR API。

## 16. 测试清单

- `GET /api/ocr/types` 返回四种 OCR 类型。
- `POST /api/ocr/records` 返回 `recordId`、`taskId` 和 `PENDING`。
- 详情接口最终返回 `SUCCESS` 或 `FAILED`。
- 成功记录包含标准化 `fields`。
- 失败记录包含用户可读的 `errorMessage`。
- 列表接口支持按 `projectId`、`ocrType`、`status` 筛选。
- 人工修订能更新 `fields`，并标记字段为已修订。
- 重试会生成新的 `taskId`。
- 删除后，详情和列表接口不再返回该 OCR 记录。
- `external_call_log` 中存在 `OCR_RECOGNIZE` 调用记录。
- 日志和调用摘要中不暴露 Qwen 密钥、服务密钥、MinIO 密钥、图片 base64 或身份证号。
