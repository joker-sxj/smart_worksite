# 文件模块接口文档

本文档描述 `file` 模块当前已实现的接口，包含文件对象管理和已上传文件解析。

统一响应结构：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "requestId": "xxx",
  "timestamp": "2026-07-05T23:00:00+08:00"
}
```

本地测试时，如果终端配置了代理，建议 curl 增加：

```bash
--noproxy '*'
```

## 环境准备

启动依赖：

```bash
cd /home/yangkaiwen/smart_worksite
docker compose -f deploy/docker-compose-env.yml --env-file deploy/.env up -d
```

启动后端：

```bash
mvn spring-boot:run
```

健康检查：

```bash
curl --noproxy '*' http://127.0.0.1:8080/api/system/ping
```

文件解析需要配置 QwenVL：

```env
QWEN_VL_ENDPOINT=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
QWEN_VL_API_KEY=你的DashScope API Key
QWEN_VL_MODEL=qwen-vl-plus
```

修改 `.env` 后需要重启后端。

## 文件对象管理

浏览器查看文件应使用 `GET /api/files/{fileId}/content`。该接口由 Java 后端完成登录和项目权限校验，并以内联响应流式返回文件；Windows 等远程浏览器无需直接访问 Linux 服务器内部的 MinIO 地址。

### 1. 上传文件

```text
POST /api/files
Content-Type: multipart/form-data
```

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| file | MultipartFile | 是 | 上传文件 |
| projectId | Long | 是 | 项目 ID |
| bizType | String | 是 | DOCUMENT、IMAGE、TEMPLATE、REPORT、OCR、OTHER |
| bizId | Long | 否 | 业务 ID |
| metadata | String | 否 | JSON 字符串 |

示例：

```bash
printf 'hello smart worksite file module\n' > /tmp/sw-test.txt

curl --noproxy '*' -X POST http://127.0.0.1:8080/api/files \
  -F 'file=@/tmp/sw-test.txt;type=text/plain' \
  -F 'projectId=1' \
  -F 'bizType=DOCUMENT' \
  -F 'metadata={"source":"manual_test","scene":"curl"}'
```

响应示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "fileId": 1,
    "projectId": 1,
    "bizType": "DOCUMENT",
    "bizId": null,
    "fileName": "sw-test.txt",
    "fileExt": "txt",
    "contentType": "text/plain",
    "fileSize": 33,
    "fileHash": "sha256...",
    "status": "ACTIVE",
    "metadata": "{\"source\":\"manual_test\",\"scene\":\"curl\"}",
    "previewSupported": true
  }
}
```

### 2. 查询文件列表

```text
GET /api/files
```

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| bizType | String | 否 | 业务类型 |
| bizId | Long | 否 | 业务 ID |
| status | String | 否 | ACTIVE、DELETED、DELETE_PENDING |
| keyword | String | 否 | 文件名关键字 |
| createdFrom | DateTime | 否 | 创建开始时间 |
| createdTo | DateTime | 否 | 创建结束时间 |
| pageNo | int | 否 | 默认 1 |
| pageSize | int | 否 | 默认 20，最大 100 |

示例：

```bash
curl --noproxy '*' "http://127.0.0.1:8080/api/files?projectId=1&pageNo=1&pageSize=10"
```

### 3. 查询文件详情

```text
GET /api/files/{fileId}
```

示例：

```bash
curl --noproxy '*' http://127.0.0.1:8080/api/files/1
```

### 4. 生成下载地址

```text
GET /api/files/{fileId}/access-url?usage=DOWNLOAD
```

示例：

```bash
curl --noproxy '*' http://127.0.0.1:8080/api/files/1/access-url?usage=DOWNLOAD
```

响应中的 `data.url` 是 MinIO 预签名地址，有有效期。

### 5. 生成预览地址

```text
GET /api/files/{fileId}/access-url?usage=PREVIEW
```

当前支持预览：

```text
image/png
image/jpeg
image/webp
application/pdf
text/plain
```

示例：

```bash
curl --noproxy '*' http://127.0.0.1:8080/api/files/1/access-url?usage=PREVIEW
```

### 6. 删除文件

```text
DELETE /api/files/{fileId}
```

说明：

- 删除 MinIO 对象。
- 将 `file_object.deleted` 更新为 `1`。
- 将 `file_object.status` 更新为 `DELETED`。

示例：

```bash
curl --noproxy '*' -X DELETE http://127.0.0.1:8080/api/files/1
```

## 文件解析

文件解析针对已经上传的文件。

支持：

```text
图片 -> 中文段落描述，结果格式 TEXT
PDF -> Markdown，结果格式 MARKDOWN
Word -> Markdown，结果格式 MARKDOWN
```

当前实现说明：

- 图片直接发送给 QwenVL 解析。
- Word/PDF 先本地提取文本，再交给 QwenVL 整理成 Markdown。
- 扫描版 PDF 暂不做逐页渲染 OCR，如果 PDF 没有可提取文本，解析会失败。
- 解析异步执行，发起接口会立即返回解析记录。
- 完整解析结果存 MinIO，预览和状态存 `file_parse_record`。

### 1. 发起解析

```text
POST /api/files/{fileId}/parse
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| force | Boolean | 否 | 是否强制重新解析，默认 false |
| targetFormat | String | 否 | 图片用 TEXT，Word/PDF 用 MARKDOWN；不传时自动判断 |
| language | String | 否 | 默认 zh-CN |

图片解析示例：

```bash
curl --noproxy '*' -X POST http://127.0.0.1:8080/api/files/1/parse \
  -H 'Content-Type: application/json' \
  -d '{
    "projectId": 1,
    "force": true,
    "targetFormat": "TEXT",
    "language": "zh-CN"
  }'
```

PDF/Word 解析示例：

```bash
curl --noproxy '*' -X POST http://127.0.0.1:8080/api/files/1/parse \
  -H 'Content-Type: application/json' \
  -d '{
    "projectId": 1,
    "force": true,
    "targetFormat": "MARKDOWN",
    "language": "zh-CN"
  }'
```

响应示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "recordId": 1,
    "projectId": 1,
    "fileId": 1,
    "parseType": "IMAGE_TO_TEXT",
    "resultFormat": "TEXT",
    "parserProvider": "QWEN_VL",
    "parserModel": "qwen-vl-plus",
    "status": "PENDING",
    "progress": 0,
    "currentStage": "CREATED"
  }
}
```

状态枚举：

```text
PENDING
RUNNING
SUCCESS
FAILED
CANCELED
```

阶段枚举：

```text
CREATED
LOADING_SOURCE
PREPARING_INPUT
CALLING_MODEL
NORMALIZING_RESULT
STORING_RESULT
FINISHED
FAILED
```

### 2. 查询解析记录详情

```text
GET /api/file-parse-records/{recordId}
```

示例：

```bash
curl --noproxy '*' http://127.0.0.1:8080/api/file-parse-records/1
```

如果解析成功：

```json
{
  "status": "SUCCESS",
  "progress": 100,
  "currentStage": "FINISHED",
  "contentPreview": "解析结果预览..."
}
```

如果解析失败：

```json
{
  "status": "FAILED",
  "currentStage": "FAILED",
  "errorMessage": "失败原因..."
}
```

### 3. 获取解析结果内容

```text
GET /api/file-parse-records/{recordId}/content
```

解析记录必须是 `SUCCESS`。

示例：

```bash
curl --noproxy '*' http://127.0.0.1:8080/api/file-parse-records/1/content
```

响应示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "recordId": 1,
    "resultFormat": "MARKDOWN",
    "content": "# 文档标题\n\n解析后的 Markdown 内容..."
  }
}
```

### 4. 查询某个文件的解析历史

```text
GET /api/files/{fileId}/parse-records?projectId={projectId}
```

示例：

```bash
curl --noproxy '*' "http://127.0.0.1:8080/api/files/1/parse-records?projectId=1"
```

### 5. 查询某个文件最新解析记录

```text
GET /api/files/{fileId}/parse-records/latest?projectId={projectId}
```

示例：

```bash
curl --noproxy '*' "http://127.0.0.1:8080/api/files/1/parse-records/latest?projectId=1"
```

### 6. 重试解析

```text
POST /api/file-parse-records/{recordId}/retry
```

说明：

- 会基于原解析记录创建一条新的解析记录。
- 原记录不会被覆盖。

示例：

```bash
curl --noproxy '*' -X POST http://127.0.0.1:8080/api/file-parse-records/1/retry
```

## 完整测试流程

### 图片解析流程

上传图片：

```bash
curl --noproxy '*' -X POST http://127.0.0.1:8080/api/files \
  -F 'file=@/tmp/test.jpg;type=image/jpeg' \
  -F 'projectId=1' \
  -F 'bizType=IMAGE' \
  -F 'metadata={"source":"parse_test"}'
```

发起解析：

```bash
curl --noproxy '*' -X POST http://127.0.0.1:8080/api/files/{fileId}/parse \
  -H 'Content-Type: application/json' \
  -d '{"projectId":1,"force":true,"targetFormat":"TEXT","language":"zh-CN"}'
```

轮询状态：

```bash
curl --noproxy '*' http://127.0.0.1:8080/api/file-parse-records/{recordId}
```

获取结果：

```bash
curl --noproxy '*' http://127.0.0.1:8080/api/file-parse-records/{recordId}/content
```

### PDF/Word 解析流程

上传 PDF：

```bash
curl --noproxy '*' -X POST http://127.0.0.1:8080/api/files \
  -F 'file=@/tmp/test.pdf;type=application/pdf' \
  -F 'projectId=1' \
  -F 'bizType=DOCUMENT'
```

上传 DOCX：

```bash
curl --noproxy '*' -X POST http://127.0.0.1:8080/api/files \
  -F 'file=@/tmp/test.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document' \
  -F 'projectId=1' \
  -F 'bizType=DOCUMENT'
```

发起解析：

```bash
curl --noproxy '*' -X POST http://127.0.0.1:8080/api/files/{fileId}/parse \
  -H 'Content-Type: application/json' \
  -d '{"projectId":1,"force":true,"targetFormat":"MARKDOWN","language":"zh-CN"}'
```

查询结果同图片解析流程。

## 数据库检查

查看文件对象：

```bash
docker exec -it smart-worksite-mysql mysql -uroot -proot smart_worksite
```

```sql
SELECT id, project_id, biz_type, file_name, content_type, status, deleted
FROM file_object
ORDER BY id DESC
LIMIT 10;
```

查看解析记录：

```sql
SELECT id, file_id, status, progress, current_stage, result_object_name, error_message
FROM file_parse_record
ORDER BY id DESC
LIMIT 10;
```

## 常见问题

### 没有返回或返回 502

本地终端可能配置了代理。使用：

```bash
curl --noproxy '*' http://127.0.0.1:8080/api/system/ping
```

### 解析失败，提示 QwenVL 未配置

检查 `deploy/.env`：

```env
QWEN_VL_ENDPOINT=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
QWEN_VL_API_KEY=你的DashScope API Key
QWEN_VL_MODEL=qwen-vl-plus
```

修改后重启后端。

### PDF 解析失败，提示文本为空

当前实现先提取 PDF 内嵌文本。扫描版 PDF 没有可提取文本时会失败，后续需要增加 PDF 按页渲染图片并交给 QwenVL 的能力。
