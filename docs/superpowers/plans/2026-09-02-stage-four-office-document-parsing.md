# 阶段四办公文档解析实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完善现有 Java + Apache POI 解析链路，使真实 Excel、CSV、PPT、PPTX 能可靠解析、查看、入库并被知识库问答引用。

**Architecture:** 保持 Java 负责文件下载、格式解析、任务状态和知识库编排；以现有 `PreparedDocument`/`DocumentBlock` 作为统一解析协议。解析结果成功与知识库入库成功保持独立状态，并在后端以最新成功解析记录和项目身份作为入库门禁。

**Tech Stack:** Spring Boot 3, Java 17, Apache POI, MyBatis, Flyway, MySQL, Vue 3, TypeScript, Vitest, 本地 Embedding/Reranker/LLM。

---

### Task 1: 建立真实样本与解析契约回归

**Files:**
- Create: `src/test/java/com/xd/smartworksite/file/infra/OfficeDocumentFixtures.java`
- Modify: `src/test/java/com/xd/smartworksite/file/infra/ExcelDocumentParserTest.java`
- Modify: `src/test/java/com/xd/smartworksite/file/infra/PowerPointDocumentParserTest.java`
- Modify: `src/test/java/com/xd/smartworksite/file/infra/DocumentParserRegistryTest.java`

- [ ] **Step 1: 写失败测试**：增加使用 Apache POI 真实生成字节流的 XLS、XLSX 多 Sheet、合并单元格、公式/日期/数字、PPTX 多页文本框/表格/备注，以及空/仅图片工作簿和演示文稿的用例；断言 Block 文本、位置、表头/行结构、页码和无文本错误。
- [ ] **Step 2: 运行定向测试确认缺口**：执行 `mvn -q -Dtest=ExcelDocumentParserTest,PowerPointDocumentParserTest,DocumentParserRegistryTest test`，记录实际失败断言。
- [ ] **Step 3: 补充 CSV 契约测试**：覆盖 UTF-8 BOM、中文 UTF-8、制表符、空文件、仅表头和异常列数，并明确 CSV 走统一表格 Block。
- [ ] **Step 4: 再次运行定向测试**，确认测试稳定复现待修行为。

### Task 2: 完善 Excel/CSV 原生解析

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/file/infra/ExcelDocumentParser.java`
- Modify: `src/main/java/com/xd/smartworksite/file/infra/DocumentPreparationService.java`
- Modify: `src/main/java/com/xd/smartworksite/file/domain/PreparedDocument.java`
- Modify: `src/main/java/com/xd/smartworksite/file/domain/DocumentBlock.java`
- Test: `src/test/java/com/xd/smartworksite/file/infra/ExcelDocumentParserTest.java`
- Test: `src/test/java/com/xd/smartworksite/file/infra/DocumentPreparationServiceTest.java`

- [ ] **Step 1: 实现最小修复**：让解析输出稳定包含 Sheet 名、有效行列范围、行元数据和统一 Excel/CSV 位置；格式化日期、数字、布尔值和公式缓存值，合并区域只记录一次。
- [ ] **Step 2: 实现 CSV 受控处理**：识别 UTF-8/BOM 和允许编码，解析逗号/制表符，保留行列号；空文件和无有效文本返回明确原因。
- [ ] **Step 3: 增加上限和异常边界**：确保超行、超单元格、超列跨度、损坏/加密工作簿不把堆栈泄露给用户响应。
- [ ] **Step 4: 运行 `mvn -q -Dtest=ExcelDocumentParserTest,DocumentPreparationServiceTest test`，确认全部通过。**

### Task 3: 完善 PowerPoint 原生解析与图片边界

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/file/infra/PowerPointDocumentParser.java`
- Modify: `src/main/java/com/xd/smartworksite/file/domain/DocumentBlock.java`
- Test: `src/test/java/com/xd/smartworksite/file/infra/PowerPointDocumentParserTest.java`

- [ ] **Step 1: 增加失败测试**：断言标题/文本框/表格/备注按阅读顺序生成，Block 带页码和内容类型；原生文本加图片保留图片标记；仅图片明确无可解析文本。
- [ ] **Step 2: 实现最小修复**：在现有 POI 展平、排序、表格和备注读取基础上补齐统一元数据，不调用视觉模型，不把图片内容当正文。
- [ ] **Step 3: 覆盖 PPT 与 PPTX、损坏文件、空演示文稿、页数/形状/单元格上限。**
- [ ] **Step 4: 运行 `mvn -q -Dtest=PowerPointDocumentParserTest test`，确认全部通过。**

### Task 4: 解析结果持久化、详情和入库门禁

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/file/application/FileParseWorker.java`
- Modify: `src/main/java/com/xd/smartworksite/file/application/FileParseApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/knowledge/application/KnowledgeBaseApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/knowledge/dto/KnowledgeDocumentResponse.java`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/views/knowledge/KnowledgeBaseView.vue`
- Test: `src/test/java/com/xd/smartworksite/file/application/FileParseWorkerTest.java`
- Test: `src/test/java/com/xd/smartworksite/knowledge/application/KnowledgeBaseApplicationServiceTest.java`
- Test: `frontend/src/views/knowledge/KnowledgeBaseView.spec.ts`

- [ ] **Step 1: 写失败测试**：验证解析失败、无文本、结果对象缺失、项目身份不一致禁止入库；最新成功解析可入库；旧任务不能覆盖新结果；前端刷新后显示服务端状态和原因。
- [ ] **Step 2: 实现后端门禁**：把“最新成功解析 + 非空正文/Block + 对象存在 + 项目/文件/文档一致”设为唯一入库前置条件，分别保存解析错误和入库错误。
- [ ] **Step 3: 实现详情响应**：输出格式、解析器、页/Sheet/幻灯片数量、Block 数、截断/图片标记、结果状态和可操作错误；禁止输出永久存储地址、堆栈和完整正文。
- [ ] **Step 4: 实现前端状态**：区分未解析、解析中、成功、失败、无文本、入库中、入库成功和入库失败；正确启用/禁用操作。
- [ ] **Step 5: 运行相关 Java 与前端定向测试，确认全部通过。**

### Task 5: 知识库向量入库与问答引用验证

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/knowledge/application/KnowledgeBaseApplicationService.java`
- Modify: `python-ai-service/app/services/vector_store.py` only if a concrete metadata compatibility gap is proven
- Test: `src/test/java/com/xd/smartworksite/knowledge/application/KnowledgeBaseApplicationServiceTest.java`
- Test: Python metadata contract test only if Python changes

- [ ] **Step 1: 增加失败测试**：验证 Excel/CSV/PPT Block 定位元数据进入 RAG 请求，且入库失败不覆盖解析成功状态。
- [ ] **Step 2: 修复最小兼容逻辑**：保证 Sheet/行和幻灯片页码随切片进入引用元数据；不新增 Mock fallback。
- [ ] **Step 3: 运行 Java 定向测试和受影响 Python 测试；若无 Python 缺口则不改 Python。**

### Task 6: 完整自动化门禁与一次审查

**Files:**
- Modify only files required by verified test/review findings.

- [ ] **Step 1: 执行 Python `pytest -q`、`compileall`。**
- [ ] **Step 2: 执行 Java `mvn -q test` 并统计 Surefire。**
- [ ] **Step 3: 执行前端 `npm test -- --run` 和 `npm run build`。**
- [ ] **Step 4: 执行 `git diff --check`，检查 API 文档和敏感日志。**
- [ ] **Step 5: 最多进行一次子智能体审查；只修复可复现的正确性、安全性或需求缺口，并重跑受影响门禁。**

### Task 7: Linux 真实部署与 Chrome 实机验收

**Files:**
- Create: `docs/superpowers/reports/2026-09-02-stage-four-office-document-parsing-acceptance.md`

- [ ] **Step 1: 部署测试提交到 `/home/xidian/sjw/smart_worksite`，确认 Java、Python、本地模型、数据库、对象存储和前端健康。**
- [ ] **Step 2: 在 Chrome 使用真实 XLS/XLSX/CSV/PPT/PPTX，至少完成 30 个场景，覆盖成功、失败、仅图片、空/损坏文件、多 Sheet、多页、刷新、重试、入库和问答引用。**
- [ ] **Step 3: 每个独立问答新建会话，验证 Excel/PPT 来源定位；不使用 Mock 作为验收结论。**
- [ ] **Step 4: 检查 Java、Python、LLM、Embedding、Reranker、MySQL、Redis、MinIO、前端和容器日志；新异常必须修复并回归。**
- [ ] **Step 5: 把任务完成数、业务正确数、失败原因、文件/任务标识和日志时间窗口写入验收报告。**

### Task 8: 推送 joker-sxj/main

**Files:** None.

- [ ] **Step 1: 确认仅包含阶段四变更、`origin` 正确且 `origin/main` 是测试 HEAD 的祖先。**
- [ ] **Step 2: 执行 `git push origin HEAD:main`。**
- [ ] **Step 3: 用 `git ls-remote origin refs/heads/main` 核对 SHA。**
- [ ] **Step 4: Linux 服务器 fast-forward 到同一提交并再次检查健康。**

