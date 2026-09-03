# 阶段五多资料合规审查实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持一个主审查文件关联多个项目参考资料，并以规则级检索和本地模型生成可追溯、可恢复的合规审查结果。

**Architecture:** Java 保存审查资料关系、解析状态、规则结果和任务状态；复用现有解析与动态检索链路，将每条规则的有界主文件证据和参考依据发送给本地 AI；程序校验来源和结果，前端展示多资料选择及规则级进度。

**Tech Stack:** Spring Boot 3、Java 17、MyBatis、Flyway、Vue 3、TypeScript、Vitest、本地 Python AI 服务、MySQL、MinIO、Redis。

---

### Task 1: 固化多资料领域模型和迁移

**Files:**
- Create: `src/main/resources/db/migration/V18__review_references_and_rule_results.sql`
- Create: `src/main/java/com/xd/smartworksite/review/domain/ReviewReference.java`
- Create: `src/main/java/com/xd/smartworksite/review/domain/ReviewRuleResult.java`
- Modify: `src/main/java/com/xd/smartworksite/review/dto/ReviewSubmitRequest.java`
- Test: `src/test/java/com/xd/smartworksite/review/application/ReviewApplicationServiceTest.java`

- [ ] **Step 1: 写失败测试**：提交请求可携带多个 `referenceDocumentIds` 和 `referenceFileIds`；重复 ID 被去重；跨项目 ID 被拒绝。
- [ ] **Step 2: 添加迁移**：建立 `review_reference` 和 `review_rule_result`，包含项目 ID、审查记录 ID、来源类型、文档/文件 ID、规则 ID、状态、证据 JSON、置信度、人工确认和错误字段，并添加项目/记录索引。
- [ ] **Step 3: 实现 DTO 和领域对象**：列表默认空集合，限制单次参考资料数量为 20，拒绝空白/重复混合来源。
- [ ] **Step 4: 运行 `mvn -q -Dtest=ReviewApplicationServiceTest test`，确认通过。**
- [ ] **Step 5: 提交 `git commit -m "feat: add review reference domain"`。**

### Task 2: 实现参考资料授权、解析和关系持久化

**Files:**
- Create: `src/main/java/com/xd/smartworksite/review/repository/ReviewReferenceRepository.java`
- Create: `src/main/java/com/xd/smartworksite/review/mapper/ReviewReferenceMapper.java`
- Create: `src/main/resources/mapper/review/ReviewReferenceMapper.xml`
- Modify: `src/main/java/com/xd/smartworksite/review/application/ReviewApplicationService.java`
- Test: `src/test/java/com/xd/smartworksite/review/application/ReviewReferenceAuthorizationTest.java`

- [ ] **Step 1: 写失败测试**：仅当前项目的启用知识库文档和项目临时文件可以绑定；未解析、已删除、跨项目和不存在来源返回明确错误。
- [ ] **Step 2: 实现服务端授权**：使用项目访问服务和现有文件/知识库服务重新查询来源，不信任前端项目 ID；保存去重后的关系。
- [ ] **Step 3: 实现主文件、模板、参考文件解析门禁**：解析失败或无文本时在 `PARSING` 阶段失败，禁止调用模型；记录可操作错误。
- [ ] **Step 4: 运行定向 Java 测试并验证 Flyway 迁移连续性。**
- [ ] **Step 5: 提交 `git commit -m "feat: validate review reference scope"`。**

### Task 3: 规则拆解和分规则检索编排

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/review/application/ReviewApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/review/application/ReviewDocumentTextExtractor.java`
- Modify: `src/main/java/com/xd/smartworksite/review/application/ReviewAiGateway.java`
- Create: `src/main/java/com/xd/smartworksite/review/application/ReviewRuleOrchestrator.java`
- Test: `src/test/java/com/xd/smartworksite/review/application/ReviewRuleOrchestratorTest.java`

- [ ] **Step 1: 写失败测试**：模板规则被拆分；每条规则获得有界主文件证据和参考依据；无证据进入人工确认；补检最多一次且相同失败不重试。
- [ ] **Step 2: 实现规则解析**：优先读取模板结构化 Block；无法结构化时按标题/编号/表格行生成稳定规则 ID，不把整份长文档一次放入模型上下文。
- [ ] **Step 3: 实现检索编排**：复用项目范围和阶段三动态检索，分别检索主文件与参考资料，保留文件、文档、页码、段落、Sheet/行等位置。
- [ ] **Step 4: 扩展本地 AI 请求**：请求包含单一规则、主文件证据和参考依据；响应必须包含 `ruleId`、`decision`、`issues`、`primaryEvidence`、`referenceEvidence`、`confidence` 和 `manualConfirmationRequired`。
- [ ] **Step 5: 运行定向 Java/Python 合约测试，确认非法 JSON、空结果和越权证据均不会成为成功。**
- [ ] **Step 6: 提交 `git commit -m "feat: review documents by rule"`。**

### Task 4: 规则结果持久化、恢复和兼容响应

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/review/application/ReviewApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/review/repository/ReviewRecordRepository.java`
- Modify: `src/main/resources/mapper/review/ReviewRecordMapper.xml`
- Modify: `src/main/java/com/xd/smartworksite/review/dto/ReviewRecordResponse.java`
- Test: `src/test/java/com/xd/smartworksite/review/application/ReviewRuleResultPersistenceTest.java`

- [ ] **Step 1: 写失败测试**：规则级成功、失败和人工确认可独立保存；任务重试只处理失败规则；历史记录仍可读取旧 `issuesJson/resultJson`。
- [ ] **Step 2: 实现状态机**：总体状态使用 `PENDING/PARSING/RULES_READY/REVIEWING/COMPLETED/PARTIAL_SUCCESS/FAILED`；规则状态使用 `PENDING/RETRIEVING/AI_REVIEWING/COMPLETED/NEEDS_MANUAL_CONFIRMATION/FAILED`。
- [ ] **Step 3: 实现汇总**：只统计已完成规则，明确成功、失败和人工确认数量；引用必须来自本次审查绑定的来源。
- [ ] **Step 4: 扩展响应**：返回参考资料、规则结果、阶段进度和错误摘要，不返回永久 URL、完整原文或内部堆栈。
- [ ] **Step 5: 运行审查、任务、项目隔离相关全量 Java 测试。**
- [ ] **Step 6: 提交 `git commit -m "feat: persist review rule results"`。**

### Task 5: 前端多资料交互和实时状态

**Files:**
- Modify: `frontend/src/api/review.ts`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/views/review/ComplianceReviewView.vue`
- Test: `frontend/src/views/review/ComplianceReviewView.spec.ts`

- [ ] **Step 1: 写失败测试**：展示当前项目可用参考文档；支持多选、移除、临时文件；提交载荷包含选择的来源；刷新恢复参考资料和规则状态。
- [ ] **Step 2: 实现 API 类型和提交载荷**：保留旧字段兼容，新增可选来源列表和规则结果。
- [ ] **Step 3: 实现交互**：解析/入库未成功的文档禁选；跨项目文档不展示；临时文件最多 10 个；提交期间禁止重复点击。
- [ ] **Step 4: 实现结果展示**：按规则展示问题、主文件定位、参考依据、置信度、人工确认和失败原因。
- [ ] **Step 5: 运行 `npm test -- --run` 和 `npm run build`。**
- [ ] **Step 6: 提交 `git commit -m "feat: add multi-reference review UI"`。**

### Task 6: 自动化门禁和一次审查

**Files:**
- Modify only files required by verified findings.

- [ ] **Step 1: 运行 `mvn -q test`，统计 Java 失败、错误和跳过。**
- [ ] **Step 2: 运行 Python `python -m pytest -q` 和 `python -m compileall -q app tests`。**
- [ ] **Step 3: 运行前端 `npm test -- --run`、`npm run build` 和 `git diff --check`。**
- [ ] **Step 4: 最多执行一次代码审查；只修复可复现的正确性、安全性或契约问题，重跑受影响测试。**

### Task 7: Linux/Chrome 真实验收和日志闭环

**Files:**
- Create: `docs/superpowers/reports/2026-09-03-stage-five-multi-reference-review-acceptance.md`

- [ ] **Step 1: 推送前将测试提交部署到 `/home/xidian/sjw/smart_worksite`，使用真实本地模型和真实文件。**
- [ ] **Step 2: Chrome 至少执行 40 个不重复场景，覆盖零/一/多参考资料、PDF/Word、长文档、跨页引用、无证据、部分证据、解析失败、刷新恢复、失败重试、权限越权、重复提交、模型异常和并发操作；每个独立问题先新建会话。**
- [ ] **Step 3: 分别统计系统执行通过数和业务内容正确数，记录规则 ID、来源定位、任务 ID 和时间。**
- [ ] **Step 4: 检查 Java、Python、本地模型、Embedding、Reranker、MySQL、Redis、MinIO、前端和容器日志；新 5xx、422、超时、OOM、CUDA 错误、重启和无限重试必须先修复再回归。**
- [ ] **Step 5: 更新验收报告，确认真实文件、本地模型、非 Mock 结果和日志结论。**

### Task 8: 推送目标仓库 main

**Files:** None.

- [ ] **Step 1: 确认工作区只含阶段五变更，远程 `origin` 为 `joker-sxj/smart_worksite`。**
- [ ] **Step 2: 执行 `git push origin HEAD:main`。**
- [ ] **Step 3: 用 `git ls-remote origin refs/heads/main` 核对远端 SHA，并确认 Linux 部署同一提交。**
