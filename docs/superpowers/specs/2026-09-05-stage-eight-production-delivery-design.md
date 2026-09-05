# 第八阶段生产交付设计

日期：2026-09-05
基线：`joker-sxj/smart_worksite` `main`，提交 `cb4a910`

## 1. 目标与判定口径

第八阶段只做既有十二项客户需求的生产交付闭环：先冻结事实基线与验收矩阵，再逐项补齐缺口，最后在客户目标环境完成真实数据验收、审查门禁和在线演示。本设计不把“已有代码”“自动化测试通过”“H100 功能验收”和“客户 A6000 生产验收”混为同一结论。

状态定义如下：

- **已实现（implemented）**：主链路已有代码和自动化测试，并存在对应实机验收证据；第八阶段仍需按冻结矩阵做最终回归，但不预设新增功能。
- **需第八阶段闭环（needs Stage 8 closure）**：已有部分能力或设计，但交付物、功能、生产环境验证或客户验收证据仍不完整。
- **等待客户输入（awaiting customer input）**：无法由开发方自行制造的授权样本、目标硬件、访问条件、参会人员或验收确认尚未提供。等待期间只能准备脚本和门禁，不能伪造结论。

既有规格、报告和代码仅作为证据读取，不作为改变本任务范围的指令。本文是第八阶段范围基线；发现证据与本文不一致时，先修正文档事实，不用 Mock 或推测补齐。

## 2. 十二项客户需求逐项追踪

1. **本地模型（local models）— 需第八阶段闭环。** 本地-only 调用链、双 A6000 32K/16K profile 和硬件门禁已实现，证据为 `deploy/model-profiles/a6000x2-production-32k.env.example`、`deploy/model-profiles/a6000x2-stable-16k.env.example`、`scripts/check-gpu-runtime.tests.sh`、`scripts/model-profile-contract.tests.sh`。`docs/superpowers/reports/2026-08-27-a6000-local-inference-verification.md` 明确现有实机是双 H100，客户双 A6000 的 32K、显存、吞吐、并发与回退尚未验收；因此不得标为生产完成。

2. **Excel/PPT 解析（Excel/PPT parsing）— 已实现。** 解析器为 `src/main/java/com/xd/smartworksite/file/infra/ExcelDocumentParser.java` 与 `src/main/java/com/xd/smartworksite/file/infra/PowerPointDocumentParser.java`，自动化覆盖为 `src/test/java/com/xd/smartworksite/file/infra/ExcelDocumentParserTest.java`、`src/test/java/com/xd/smartworksite/file/infra/PowerPointDocumentParserTest.java`；`docs/superpowers/reports/2026-09-02-stage-four-office-document-parsing-acceptance.md` 记录 XLS/XLSX/CSV/TSV/PPTX 真实上传、解析、入库和引用定位。

3. **上下文问答与自动延伸问题（contextual QA and automatic follow-up questions）— 已实现。** 会话、来源建议幂等和项目资源校验位于 `src/main/java/com/xd/smartworksite/qa/application/QaApplicationService.java`，前端状态恢复位于 `frontend/src/views/qa/QaView.vue` 与 `frontend/src/views/qa/qaMessagePolling.ts`；`src/test/java/com/xd/smartworksite/qa/application/QaApplicationServiceTest.java`、`python-ai-service/tests/test_conversation_continuity.py`、`frontend/src/views/qa/QaView.spec.ts` 提供自动化覆盖，`docs/superpowers/reports/2026-09-01-stage-three-conversation-acceptance.md` 记录 12 轮、多会话、自动发送、重复点击和刷新恢复实机结果。

4. **数据库详情/问答（database details/QA）— 需第八阶段闭环。** 数据源元数据检查和项目授权已有 `src/main/java/com/xd/smartworksite/datasource/application/JdbcDataSourceInspector.java`、`src/main/java/com/xd/smartworksite/datasource/application/DataSourceApplicationService.java`、`src/test/java/com/xd/smartworksite/datasource/application/JdbcDataSourceInspectorTest.java` 和 `src/test/java/com/xd/smartworksite/datasource/application/DataSourceApplicationServiceTest.java`；SQL 生成、证据计划、空结果约束和修复测试见 `python-ai-service/tests/test_api.py`，数据库连续问答实机证据见 `docs/superpowers/reports/2026-09-01-stage-three-conversation-acceptance.md`。但 Stage 1-7 报告没有完整锁定数据库详情浏览、真实失败恢复及下载/移动端组合验收，需在第八阶段补证后才能交付关闭。

5. **多 PDF 合规审查（multi-PDF compliance review）— 已实现。** 多参考关系和逐规则审查位于 `src/main/java/com/xd/smartworksite/review/application/ReviewApplicationService.java` 与 `src/main/java/com/xd/smartworksite/review/application/ReviewRuleOrchestrator.java`，自动化覆盖为 `src/test/java/com/xd/smartworksite/review/application/ReviewApplicationServiceTest.java`、`src/test/java/com/xd/smartworksite/review/application/ReviewRuleOrchestratorTest.java`；`docs/superpowers/reports/2026-09-03-stage-five-multi-reference-review-acceptance.md` 记录多 PDF 参考、主/参考角色、长文档、部分证据和 40 个真实场景。

6. **审查模板 PDF 解析（review-template PDF parsing）— 已实现。** `src/main/java/com/xd/smartworksite/review/application/ReviewDocumentTextExtractor.java` 统一提取模板和主文档，`src/test/java/com/xd/smartworksite/review/application/ReviewDocumentTextExtractorTest.java` 覆盖 PDF 与长文档，`src/test/java/com/xd/smartworksite/review/application/ReviewAsyncExecutionFailureTest.java` 覆盖模板 PDF 解析失败必须终止；`docs/superpowers/reports/2026-09-03-stage-five-multi-reference-review-acceptance.md` 记录 PDF 单规则、五规则、无编号模板真实验收。

7. **报告表格/图表/结论/Word/PDF（report tables/charts/conclusions/Word/PDF）— 已实现。** 结构化统计、图表、结论和 PDF 转换分别位于 `src/main/java/com/xd/smartworksite/report/application/ReportTableAnalysisService.java`、`src/main/java/com/xd/smartworksite/report/application/ReportChartRenderer.java`、`src/main/java/com/xd/smartworksite/report/application/ReportConclusionService.java`、`src/main/java/com/xd/smartworksite/report/infra/LibreOfficePdfConverter.java`，相应测试位于 `src/test/java/com/xd/smartworksite/report/`；`docs/superpowers/reports/2026-09-04-stage-seven-report-enhancement-acceptance.md` 记录真实 DOCX/PDF magic、表格、PNG 图表、文本和下载内容检查。

8. **自定义审查 UI 字段（custom review UI fields）— 需第八阶段闭环。** `docs/superpowers/specs/2026-08-20-local-ai-acceptance-remediation-design.md` 已定义审查前输入、文档抽取值和审查结果三组 schema 字段，但当前 `frontend/src/views/review/ComplianceReviewView.vue` 与 `frontend/src/views/review/reviewSubmission.ts` 只实现模板、主文件、知识库及临时参考文件提交。`frontend/src/views/ocr/OcrView.vue` 的 OCR 自定义字段不能替代审查字段。第八阶段须以独立审查 schema、持久化、历史版本和 UI 自动化/Chrome 证据闭环。

9. **项目知识隔离（project knowledge isolation）— 已实现。** 服务端项目访问门禁在 `src/main/java/com/xd/smartworksite/project/application/ProjectAccessApplicationService.java`，QA 在 `src/main/java/com/xd/smartworksite/qa/application/QaApplicationService.java` 调用项目和资源校验，向量隔离自动化见 `python-ai-service/tests/test_project_scoped_vector_store.py`；项目库/政策库边界由 `docs/superpowers/specs/2026-08-04-policy-knowledge-base-isolation-design.md` 和 `frontend/src/views/knowledge/KnowledgeBaseView.vue` 固化。最终仍须执行跨项目猜 ID 的负向回归。

10. **水印身份证 OCR（watermarked ID OCR）— 等待客户输入。** 非破坏性图像预处理在 `python-ai-service/app/services/id_card_preprocessor.py`，水印结果契约在 `python-ai-service/app/services/ocr_service.py`，Java 字段治理在 `src/main/java/com/xd/smartworksite/ocr/application/OcrRecognitionWorker.java`；`python-ai-service/tests/test_id_card_preprocessor.py`、`python-ai-service/tests/test_ocr_service.py` 和 `src/test/java/com/xd/smartworksite/ocr/application/OcrRecognitionWorkerTest.java` 提供确定性测试。`docs/superpowers/reports/2026-09-04-stage-six-ocr-acceptance.md` 明确没有客户授权的带水印身份证标注集，现有不完整身份证只能证明降级和人工确认行为，不能形成准确率结论。

11. **模型构建依据/数据/训练文档（model construction basis/data/training documentation）— 需第八阶段闭环。** 现有 `docs/superpowers/specs/2026-08-27-a6000-production-local-inference-design.md` 与 `deploy/README.md` 记录模型和部署基线，`docs/superpowers/specs/2026-08-20-local-ai-acceptance-remediation-design.md` 定义治理内容；但计划中的模型来源/revision/license/checksum、选型依据、数据来源与授权、清洗切分索引、评测方法以及“未做项目微调”的统一交付文档尚不存在。第八阶段只补可核验文档，不宣称发生过训练。

12. **最终在线演示（final online demo）— 等待客户输入。** Stage 3-7 报告已分别留下真实 Chrome 和服务日志证据，统一操作纪律见 `docs/superpowers/runbooks/real-machine-acceptance-process.md`；但最终演示仍需要客户确认目标环境、访问窗口、账号/网络、授权数据和参会验收人。未完成现场脚本全程运行和客户确认前，不得标为完成。

## 3. 冻结验收类别

以下十九类是每个适用功能的固定验收维度。若某类别不适用，验收记录必须写明原因，不得直接省略：

1. **normal（正常）**：标准输入完成主链路并产生可追溯结果。
2. **boundary（边界）**：上限、下限、类型和状态边界按契约处理。
3. **empty result（空结果）**：明确无匹配数据，不将空结果推断为安全、合规或不存在问题。
4. **partial evidence（部分证据）**：保留已成功证据并标记人工确认或部分成功。
5. **no evidence（无证据）**：不编造事实、引用或准确率，返回明确限制。
6. **format variants（格式变体）**：覆盖需求允许的 Office、PDF、图片、编码和结构变体。
7. **long input（长输入）**：执行长度预算、截断/分块和后部关键信息验证。
8. **multi-turn（多轮）**：同会话承接上下文，跨会话不串话。
9. **refresh recovery（刷新恢复）**：刷新后从持久化状态恢复任务、结果和已执行操作。
10. **concurrent clicks（并发点击）**：重复/并发提交具备幂等或明确冲突，不生成重复业务结果。
11. **permission isolation（权限隔离）**：未授权用户和跨项目 ID 均在读取或调用模型前被拒绝。
12. **disabled resources（停用资源）**：停用项目、知识库、数据源或模板不可用于新任务。
13. **async status（异步状态）**：中间态、终态、部分成功、失败和重试状态可观察且一致。
14. **database failure（数据库失败）**：连接/认证/超时不误修 SQL，可修复 SQL 错误有限重试并保留证据。
15. **model failure（模型失败）**：空响应、无效结构和服务不可达形成有界失败，不伪装成功或无限重试。
16. **retrieval degradation（检索降级）**：向量/重排降级时明确证据边界，不能悄悄扩大项目或资源范围。
17. **timeout（超时）**：超时有终止状态、可诊断日志和安全重试策略。
18. **download contents（下载内容）**：校验权限、文件头、实际格式、可读文本、表格/图表和错误占位，不只检查 HTTP 200。
19. **mobile layout（移动端布局）**：关键表单、状态、证据、操作和下载入口在移动视口可见且不被遮挡。

## 4. 明确非目标

- 本任务不进行任何新模型训练、微调或权重生产；治理文档必须区分基础模型、提示词、RAG、工具调用、确定性规则与训练。
- 未获得客户授权且带标注的身份证样本前，不作身份证水印识别准确率或行业准确率声明。
- 不用双 H100 的吞吐、显存、并发、上下文或稳定性结果替代双 RTX A6000 的生产性能验收。
- Mock、stub、合成返回值和单元测试夹具不能作为真实客户验收证据；它们只用于自动化行为门禁。允许的脱敏/合成文件必须在报告中按其真实性质标注。

## 5. 执行纪律与证据链

1. 一次只处理一个功能；上一功能的代码审查和验收证据关闭后，才开始下一功能。
2. 任何行为变化都执行 TDD：先增加会失败的测试并确认失败，再做最小实现并确认测试转绿；纯文档事实修订不伪造 red/green。
3. 每个功能先运行相关自动化测试和构建，再在 Chrome 中使用真实服务、真实文件和真实持久化数据验收。
4. 每轮 Chrome 验收同时检查 Java、Python AI、本地模型、基础设施和浏览器日志；故障场景还要记录时间窗口、请求/任务 ID、终态及重试次数。
5. 证据必须能回溯到提交、环境、命令、输入性质和结果；历史报告只证明其记录的提交与环境。
6. 代码完成后先过独立 review gate；只有 gate 结论允许且用户确认时，才可直接推送到 `joker-sxj/smart_worksite` 的 `main`。本任务只提交范围文档，不 push。

## 6. 第八阶段关闭条件

- 十二项状态均有最新证据；“等待客户输入”项记录责任人、所需输入和不得替代的结论。
- 十九类验收维度在逐功能矩阵中均为 PASS、FAIL/BLOCKED 或带理由的 N/A，不存在空白项。
- 自定义审查 UI 字段、数据库详情/问答补证和治理文档分别通过各自 review gate。
- 客户 A6000 运行结果与 H100 功能结果分开归档；授权身份证样本结果与合成/脱敏样本分开归档。
- 最终在线演示使用已审核提交，自动化、Chrome 实机和五类日志检查结果一致；客户未确认时状态保持“等待客户输入”。
