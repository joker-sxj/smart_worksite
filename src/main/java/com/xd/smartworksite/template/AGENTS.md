# Template Module Design Rules

本文件补充根目录 `AGENTS.md`，约束模板预览、模板变量解析和变量描述持久化功能。发生冲突时，以根目录规则和用户当前明确要求为准。

## Scope

- 统一接口同时服务 `REPORT` 和 `REVIEW` 模板。
- “所有模板”表示两个模板类别使用同一套接口，不表示批量返回所有模板内容。
- PDF 支持文件流预览，但不参与报告模板变量解析。
- 当前非 PDF 文件范围为 DOC、DOCX、XLS、XLSX、CSV、TXT、MD。
- 对既有 PDF 模板，预览必须返回真实 `application/pdf` 文件流；变量扫描必须返回明确的格式不支持错误，不能返回空变量。

## Report Template Upload Auto Parsing

- 本阶段只实现报告模板上传自动解析；变量接口改为报告模板专用、变量查询改为数据库读取、重新解析接口和解析状态字段暂不实现。
- 所有进入统一上传应用服务且 `templateCategory = REPORT` 的入口都必须执行相同的自动解析流程，包括 `/api/templates`、`/api/templates/report` 和 `/api/report/templates`；不得只在某一个 Controller 中补逻辑。
- 报告模板必须在上传 MinIO 前使用原始文件名和上传内容调用 `TemplateVariableScanner`，只解析 `{{ var_xx_xx }}` 变量；解析失败时不得上传对象或创建文件、模板记录。
- `REVIEW` 模板不得触发自动变量解析，保持现有上传行为。
- 报告模板文件、`file_object`、`template` 和解析变量必须全部成功后才能返回上传成功。数据库变量写入失败时数据库事务必须回滚，并尽力清理本次已上传的 MinIO 对象。
- 自动解析得到的变量按扫描器返回顺序写入现有 `template_variable_description` 表；本阶段不新增变量表。首次写入时 `description` 使用空字符串表示“变量已解析但尚未配置描述”。
- 合法报告模板没有变量时允许上传成功，不插入变量描述记录；不得伪造变量。
- 自动解析写入必须使用当前模板 ID、当前文件 ID、项目 ID 和当前操作人，逐条检查影响行数和生成 ID，不得返回假成功。
- 自动解析属于应用服务编排，不允许 Java 后端通过 HTTP 调用自己的变量接口，也不允许 Controller 直接调用扫描器、Mapper、MinIO 或变量仓储。

## Preview Contract

- 统一预览接口为 `GET /api/templates/{templateId}/preview`。
- 成功响应直接返回模板文件流，并设置正确的 `Content-Type` 和 `Content-Disposition: inline`，不使用 `ApiResponse` 包装二进制内容。
- 失败响应在尚未写出文件流时使用统一 `ApiResponse` 错误结构。
- 前端必须通过带认证的 Java 接口获取 Blob，再选择 DOCX、表格或文本预览组件。
- Java 负责模板存在性、项目访问权限、模板与文件项目一致性、文件状态和 MinIO 读取校验。
- 接口不得向前端暴露 MinIO Bucket、对象名、访问密钥、永久地址或预签名地址。
- 旧版 DOC 无法保持原排版时允许显式降级为文本预览，但不得伪装成原样预览。

## Variable Contract

- 统一变量接口为 `GET /api/templates/{templateId}/variables`。
- `data` 直接返回变量名称字符串数组，不返回出现次数、占位符形式、描述或解析元数据。
- 模板变量只识别 `{{ var_xx_xx }}` 形式，匹配表达式为 `\{\{\s*(var_[a-z0-9_]+)\s*\}\}`。
- 返回值去掉大括号和内外空白，例如 `{{ var_project_name }}` 返回 `var_project_name`。
- 从文件开头扫描到结尾，按第一次出现顺序返回；同名变量重复出现时只返回一次。
- DOCX 按页眉、正文、页脚扫描；正文内按段落和表格单元格的文档顺序扫描。匹配前必须合并同一段落或单元格中的 Runs，避免变量被 Word 拆分后漏检。
- XLS/XLSX 按工作表、行、单元格顺序扫描；TXT、MD、CSV 按文本顺序扫描。
- 合法模板确实没有变量时返回成功和空数组；文件不存在、内容损坏、格式不支持或存储读取失败必须返回明确错误，不能用空数组隐藏解析失败。
- 现有报告模板变量兼容接口必须委托统一解析能力；新代码不得继续扩展 `${name}` 变量语法。

## Variable Description Contract

- 变量描述查询接口为 `GET /api/templates/{templateId}/variables/descriptions`，按当前模板文件变量顺序返回 `variableName + description`；尚未配置的描述返回空字符串，不能因此丢失变量。
- 描述查询必须重新扫描当前模板文件并按 `template_id + file_id + variable_name` 合并已持久化描述，确保历史模板即使没有自动解析记录也能展示全部变量。
- 变量描述写入接口为 `PUT /api/templates/{templateId}/variables/descriptions`。
- 请求必须提交当前模板文件解析出的全部唯一变量及其描述。
- 保存前必须重新读取当前模板文件并解析变量，校验请求变量集合与文件变量集合完全一致。
- 未知变量、缺失变量、重复变量、空描述和非法变量名必须失败，不得部分保存。
- 同一个模板文件变量已有描述时更新，没有时新增；所有 upsert 在同一事务中执行。
- 当前文件未涉及的历史文件变量描述不在本次请求中删除。
- 成功响应返回本次保存后的完整变量描述数组。
- 描述最大 2000 字符，变量名最大 128 字符；所有文本去除首尾空白后再校验和持久化。
- 描述写入要求项目管理级可写权限，并检查模板、文件和项目归属一致。
- 每次插入和更新必须检查影响行数；写入后必须读回，不允许返回假成功。
- 前端模板中心仅对 `REPORT` 模板展示“模板变量”入口；变量名只读，描述可由具备模板维护权限的用户修改，并一次性提交当前页面全部变量。

## Persistence Design

- `V17__add_template_variable_descriptions.sql` 创建 `template_variable_description`；后续不得修改这个已经使用的迁移。
- 表至少包含 `id`、`project_id`、`template_id`、`file_id`、`variable_name`、`description`、审计字段和 `deleted`。
- 唯一关系为 `template_id + file_id + variable_name`。
- 同一文件重复提交时更新或重新激活原记录；模板绑定新文件后不得错误复用旧文件描述。
- 有效描述查询必须同时限定 `template_id`、`file_id` 和 `deleted = 0`；upsert 为重新激活同一唯一键记录而执行的内部查找可以包含已删除行。

当前表结构如下：

```sql
CREATE TABLE template_variable_description (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  file_id BIGINT NOT NULL,
  variable_name VARCHAR(128) NOT NULL,
  description VARCHAR(2000) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_template_file_variable (template_id, file_id, variable_name),
  KEY idx_template_variable_project (project_id),
  KEY idx_template_variable_template (template_id),
  KEY idx_template_variable_file (file_id),
  KEY idx_template_variable_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

## Layering And Tests

- Controller 只处理 HTTP 参数、校验和响应，不直接访问 Mapper、MinIO 或文件模块 Mapper。
- 模板应用服务通过文件模块应用服务或 Facade 读取文件元数据，通过存储适配器读取文件内容。
- 变量扫描器应作为独立组件，分别测试 Word、Excel 和文本格式。
- 测试必须覆盖正常顺序、重复变量、跨 Run 变量、无变量、非法变量、缺失描述、未知描述变量、重复提交更新、跨项目访问、PDF 变量扫描拒绝、PDF 文件流预览和 MinIO 失败。
- 预览、统一变量查询和变量描述 upsert 接口已经实现；后续契约变更必须同步更新本文件、`API.md`、根文档和测试。
