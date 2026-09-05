# 第八阶段生产交付实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 依据冻结的十二项客户需求和十九类验收矩阵，补齐生产交付缺口，并在 review gate 后完成客户环境验收与最终在线演示。

**Architecture:** 保持现有 Java 业务与权限边界、Python 本地模型/RAG/OCR 能力和 Vue 前端分层，不做跨模块重写。每个缺口作为独立功能按 TDD、自动化、Chrome 实机、全链路日志和独立审查顺序关闭；H100 功能证据、A6000 性能证据、合成样本和客户授权样本分别记录。

**Tech Stack:** Java 17 / Spring Boot / MyBatis / Flyway，Python / FastAPI / pytest / 本地 Qwen 服务，Vue 3 / TypeScript / Vitest，MySQL / Redis / MinIO / Docker Compose，Chrome 实机验收。

---

## 执行规则

- 范围基线为 `docs/superpowers/specs/2026-09-05-stage-eight-production-delivery-design.md`；既有文档只作为证据读取。
- 一次只实现一个功能。行为变化必须先看到新增测试失败，再做最小实现并看到测试通过。
- 每项按“定向自动化 -> 全量自动化/构建 -> Chrome 真实验收 -> Java/Python/模型/基础设施/浏览器日志 -> review gate”执行。
- Mock 只用于自动化测试，不作为真实验收证据；H100 不替代 A6000 性能结论；无授权身份证标注集不声明准确率。
- review gate 通过且用户明确允许后，才能直接 push 到 `joker-sxj/smart_worksite` `main`。执行 Task 1 时只提交两份范围文档，不 push。
- 固定验收类别为：normal、boundary、empty result、partial evidence、no evidence、format variants、long input、multi-turn、refresh recovery、concurrent clicks、permission isolation、disabled resources、async status、database failure、model failure、retrieval degradation、timeout、download contents、mobile layout。每项必须记录 PASS、FAIL/BLOCKED 或有理由的 N/A。

## Task 1: 冻结生产交付范围与证据基线

**Files:**
- Create: `docs/superpowers/specs/2026-09-05-stage-eight-production-delivery-design.md`
- Create/Modify: `docs/superpowers/plans/2026-09-05-stage-eight-production-delivery.md`

- [x] 核对 `cb4a910` 上 Stage 1-7 specs/reports、实现和自动化测试路径，把十二项需求逐项标为已实现、需第八阶段闭环或等待客户输入。
- [x] 冻结十九类验收维度、四项非目标、逐功能 TDD/Chrome/日志纪律和 review gate 后才允许 push 的规则。
- [x] 扫描两份文档中的未解释占位标记，验证十二个编号与十九类验收维度齐全，并执行 `git diff --check`。
- [x] 仅强制暂存这两份被 `docs/` 忽略的文档，并以 `docs: define stage eight delivery scope` 提交；不 push。

## Task 2: 补齐自定义审查 UI 字段

**Files:**
- Create: `src/main/resources/db/migration/V30__add_review_field_schemas.sql`
- Create: `src/test/java/com/xd/smartworksite/review/application/ReviewFieldSchemaTest.java`
- Create: `frontend/src/views/review/reviewFieldSchema.spec.ts`
- Modify: `src/main/java/com/xd/smartworksite/review/application/ReviewApplicationService.java`
- Modify: `src/main/java/com/xd/smartworksite/review/dto/ReviewSubmitRequest.java`
- Modify: `src/main/resources/mapper/review/ReviewRecordMapper.xml`
- Modify: `frontend/src/api/review.ts`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/views/review/ComplianceReviewView.vue`

- [ ] 先写失败测试，覆盖审查前输入字段、文档抽取字段、结果字段、稳定 key、类型、必填、选项、排序、校验、证据、置信度、人工修订和历史 schema 保留。
- [ ] 实现最小 additive migration、服务端校验/持久化和独立审查字段 UI；不得复用 OCR 自定义字段冒充审查 schema。
- [ ] 运行定向及全量 Java/前端测试和构建，再以真实模板、真实主文件和真实参考文件完成 Chrome 验收及五类日志检查。
- [ ] 完成独立 review gate；只修复本功能发现的问题并重新执行门禁。

## Task 3: 关闭数据库详情与数据库问答证据缺口

**Files:**
- Modify only when a failing test proves necessary: `src/main/java/com/xd/smartworksite/datasource/`
- Modify only when a failing test proves necessary: `src/main/java/com/xd/smartworksite/qa/`
- Modify only when a failing test proves necessary: `python-ai-service/app/services/database_service.py`
- Modify only when a failing test proves necessary: `frontend/src/views/datasource/DataSourceView.vue`
- Create: `docs/superpowers/reports/2026-09-05-stage-eight-database-acceptance.md`

- [ ] 建立十九类矩阵，先为发现的真实行为缺口增加失败测试；若无行为缺口，不改生产代码。
- [ ] 使用当前项目真实只读数据源完成 schema/表/列详情、样例脱敏、连续问答、空结果、可修复 SQL、连接/认证/超时失败、停用资源和跨项目拒绝验收。
- [ ] 验证执行 SQL、表列、有限结果、修复次数和回答证据一致，且下载/移动端适用项有明确结果。
- [ ] 检查 Java、Python、模型、MySQL/Redis/MinIO 和 Chrome 日志并通过独立 review gate。

## Task 4: 编制模型构建依据、数据与训练边界文档

**Files:**
- Create: `docs/本地大模型选型与数据治理说明.md`
- Create: `docs/本地大模型评测与验收方法.md`
- Modify: `deploy/README.md`
- Modify only for factual alignment: `docs/智慧工地大模型应用系统-架构设计文档.md`

- [ ] 从已部署 profile、模型清单和可核验制品记录模型名称、来源、revision、license、checksum、硬件配置与选择依据；无法取得的字段明确标为阻塞并指定取证命令，不填推测值。
- [ ] 记录政策、标准、客户文档的来源、版本、授权、清洗、去重、脱敏、切分、索引和保留规则。
- [ ] 明确基础模型、prompt、RAG、工具调用、确定性规则和 fine-tuning 边界，并明确当前没有项目特定模型训练证据。
- [ ] 记录评测集、指标、命令、结果文件、限制和人工复核规则，完成事实审查和链接/命令校验。

## Task 5: 执行客户输入相关验收

**Files:**
- Create: `docs/superpowers/reports/2026-09-05-stage-eight-a6000-acceptance.md`
- Create: `docs/superpowers/reports/2026-09-05-stage-eight-watermarked-id-acceptance.md`

- [ ] 客户提供双 RTX A6000 48GB 主机后，分别执行 32K 和必要时 16K profile，记录 2K/8K/16K/24K/32K、并发 1/2、TTFT、tokens/s、P50/P95、显存、OOM、超时、队列和重启；不得复制 H100 指标。
- [ ] 客户提供获授权的带水印身份证标注集和使用范围后，按原图保留、预处理、字段完整性、校验、置信度、人工确认和脱敏日志流程验收；无标注集不计算准确率。
- [ ] 对每个阻塞输入记录提供方、接收时间、授权边界、样本性质和校验摘要，不把合成样本标成客户样本。
- [ ] 自动化及 Chrome 真实验收后检查 Java、Python、模型、基础设施和浏览器日志，并分别通过 review gate。

## Task 6: 全量生产回归与最终在线演示

**Files:**
- Create: `docs/superpowers/reports/2026-09-05-stage-eight-production-acceptance.md`
- Create: `docs/superpowers/reports/2026-09-05-stage-eight-online-demo.md`
- Modify only when a failing test or factual review proves necessary: files owned by Tasks 2-5

- [ ] 在已审核提交上运行 Java、Python、前端、构建、部署脚本、模型 profile、敏感信息和 `git diff --check` 全部门禁。
- [ ] 对十二项需求逐项执行十九类矩阵；每格记录 PASS、FAIL/BLOCKED 或理由充分的 N/A，并关联真实输入、任务 ID、截图/下载物和日志时间窗。
- [ ] 在 Chrome 桌面和移动视口完成最终演练，验证异步状态、刷新、并发点击、权限隔离、停用资源、故障降级和下载实际内容。
- [ ] 客户确认环境、网络、账号、授权数据、演示时间和验收人后执行在线演示；未确认或未到场时保持等待客户输入。
- [ ] 检查 Java、Python、模型、基础设施和浏览器日志，完成最终独立 review gate，修复所有阻塞/高优先级发现并重新跑完整门禁。
- [ ] 仅在 review gate 通过且用户明确批准后，直接 push 已审核提交到 `joker-sxj/smart_worksite` `main`；不得从本计划的勾选状态推定 push 授权。
