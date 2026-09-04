# 第七阶段报告增强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有报告生成链路中增加可审计的结构化表格、确定性统计、图表和安全结论，并保持本地模型、项目隔离与部分成功语义。

**Architecture:** 先在 Java 报告领域增加纯函数式的表格快照与统计组件，将数据库引用中的真实列/行规范化后生成事实 JSON；报告渲染器消费事实并输出 DOCX 表格、PNG 图表和结论。模型只接收事实生成措辞，Java 校验数字并在失败时使用确定性结论。导出和详情接口沿用现有对象存储、权限及状态机制，PDF 转换失败不伪造 PDF。

**Tech Stack:** Java 21, Spring Boot, Jackson, Apache POI, PDFBox/现有转换能力, JUnit 5, Vue 3/TypeScript, Maven.

---

## 文件地图

- Create: `src/main/java/com/xd/smartworksite/report/domain/StructuredReportTable.java` - 有序列、行、来源和截断快照。
- Create: `src/main/java/com/xd/smartworksite/report/domain/ReportStatistics.java` - 可重算的统计事实。
- Create: `src/main/java/com/xd/smartworksite/report/application/ReportTableAnalysisService.java` - 从数据库引用识别字段并计算统计。
- Create: `src/main/java/com/xd/smartworksite/report/application/ReportConclusionService.java` - 标准结论、模型结论数字校验和安全降级。
- Create: `src/test/java/com/xd/smartworksite/report/application/ReportTableAnalysisServiceTest.java`.
- Create: `src/test/java/com/xd/smartworksite/report/application/ReportConclusionServiceTest.java`.
- Modify: `src/main/java/com/xd/smartworksite/report/application/ReportGenerationApplicationService.java` - 生成变量后构建分析快照并渲染表格/结论/图表；保留变量失败隔离。
- Modify: `src/main/java/com/xd/smartworksite/report/domain/ReportVariableValue.java` - 增加结构化分析 JSON 字段映射前的内存属性。
- Modify: `src/main/java/com/xd/smartworksite/report/dto/ReportVariableResponse.java` - 返回表格、统计、结论及图表元数据。
- Modify: `src/main/java/com/xd/smartworksite/report/repository/ReportRepository.java` and MyBatis mapper/migration - 持久化可审计分析结果。
- Modify: `src/main/java/com/xd/smartworksite/report/controller/ReportController.java` - 允许详情读取结构化结果，并明确 PDF 不可用时的错误。
- Modify: `frontend/src/api/types.ts`, `frontend/src/views/report/ReportDetailView.vue` - 展示结构化表格、统计、图表、结论和导出状态。
- Modify: `python-ai-service/app/models/schemas.py`, `python-ai-service/app/services/model_service.py` - 增加只接受事实 JSON 的本地结论请求/响应，不提供云端回退。
- Test: 对应 Java/Python/前端现有测试目录，新增真实序列化和渲染契约测试。

### Task 1: 表格快照规范化

- [ ] 写测试：给出 `columns=["risk_level","owner"]` 和两行数据，断言列顺序保持、未知键被丢弃、null 显示语义不改变、超过 100 行标记 `truncated`。
- [ ] 运行 `mvn -q -Dtest=ReportTableAnalysisServiceTest#normalizesRowsWithColumnWhitelist test`，应因类/方法不存在而失败。
- [ ] 实现不可变快照对象和 `normalize(columns, rows, source)`，最大展示行数固定为 100，原始总行数单独保存。
- [ ] 运行该测试和完整报告单测，确认通过；不在日志输出完整行内容。
- [ ] 提交 `feat: normalize report table snapshots`。

### Task 2: 确定性统计和空数据安全语义

- [ ] 写测试：覆盖多风险等级、多负责人、按月日期、金额合计、空表；断言同输入结果稳定，空表结论不出现“无风险/全部闭环”。
- [ ] 先运行定向测试确认红色失败。
- [ ] 实现 `ReportTableAnalysisService.statistics(table)`：总行数、非空行数、分组计数、月趋势、数值合计；分组最多 20 项并合并其他。
- [ ] 运行定向测试、Java report/qa 测试，确认绿色。
- [ ] 提交 `feat: add deterministic report statistics`。

### Task 3: 标准结论与数字安全校验

- [ ] 写测试：真实统计事实生成标准结论；模型文本数字全部在允许集合时采用模型文本；出现额外数字、空文本或异常时回退标准结论，并返回原因。
- [ ] 先运行测试确认缺失实现导致失败。
- [ ] 实现 `ReportConclusionService`，仅接收统计对象；数字校验使用阿拉伯数字和百分数 token 集合，空数据禁止调用模型；模型接口通过注入的本地 gateway，异常不会阻断报告。
- [ ] 运行定向测试和 Java 全量测试。
- [ ] 提交 `feat: ground report conclusions in facts`。

### Task 4: DOCX 表格和结论渲染

- [ ] 写渲染契约测试：生成含一个变量的 DOCX，断言解析后存在表头、数据行、结论段和空数据说明。
- [ ] 先运行测试确认现有渲染器不产生结构化表格而失败。
- [ ] 把变量值中的数据库引用解析为分析对象；渲染顺序固定为标题、说明、表格、结论、来源/截断提示。单变量失败仍写占位文本。
- [ ] 用 Apache POI 重新打开生成字节，断言表格和文字存在；运行完整 Java 测试。
- [ ] 提交 `feat: render structured report tables`。

### Task 5: 图表生成和 DOCX 嵌入

- [ ] 写测试：BAR/PIE/LINE 三个白名单类型输出非空 PNG；缺失列、空数据、超过 20 分类返回可读错误而非空图。
- [ ] 先运行定向测试确认失败。
- [ ] 优先使用现有 Apache POI/PDFBox 能力；若无稳定图表 API，新增唯一固定版本 JFreeChart 依赖。固定 1200x675、中文字体探测、无字体降级。
- [ ] 将图表通过 `XWPFRun.addPicture` 嵌入 DOCX，图表失败只标记变量部分成功并保留表格和结论。
- [ ] 运行 Java 全量测试和生产构建。
- [ ] 提交 `feat: embed allowlisted report charts`。

### Task 6: 本地模型契约、结果持久化和前端展示

- [ ] 写 Python schema/service 测试：请求必须包含事实 JSON，响应不得增加事实数字；验证 `AI_ALLOW_CLOUD_FALLBACK=false`。
- [ ] 先运行 Python/前端定向测试确认红色失败。
- [ ] 增加结构化结果 JSON 持久化迁移，扩展变量响应；详情页展示表格、统计卡片、图表和结论，状态轮询与项目权限沿用旧逻辑。
- [ ] 运行 Python 全量、前端 lint/test/build、Java 全量；确认旧接口兼容。
- [ ] 提交 `feat: expose structured report analysis`。

### Task 7: PDF、失败降级和回归

- [ ] 写集成测试覆盖 PDF 转换成功、转换失败保留 DOCX、变量查询失败形成 `PARTIAL_SUCCESS`、权限隔离和重试。
- [ ] 先运行测试确认当前只支持 WORD 的行为与目标不符。
- [ ] 接入既有受控 PDF 转换服务/任务，不在请求线程执行不受控命令；保存 `pdf_file_id`，转换失败明确记录且不返回伪 PDF。
- [ ] 运行 Java、Python、前端全量测试及构建，检查无警告级异常。
- [ ] 提交 `feat: preserve report exports and partial failures`。

### Task 8: 推送、部署和实机验收

- [ ] 记录当前提交，执行 Java/Python/frontend 全量测试和生产构建。
- [ ] 推送 `git -c http.version=HTTP/1.1 push origin HEAD:main` 到 `joker-sxj/smart_worksite`，确认远程 SHA。
- [ ] Linux 执行 `git fetch origin main && git checkout main && git pull --ff-only origin main && ./scripts/start-all.sh --model-profile h100-fp8`；确认服务健康。
- [ ] 使用真实项目数据库风险数据、真实报告模板和本地模型，逐个新建报告任务，覆盖至少 40 个场景：空/单行/多级风险/多负责人/月趋势/金额/长文本/中文字段/缺列/查询失败/模型失败/图表失败/DOCX/PDF/权限/刷新/重试等。
- [ ] 打开并检查真实 DOCX 和 PDF 内容（表格、图表、结论），记录每个场景结果。
- [ ] 汇总 Chrome 控制台、Java、Python、本地 LLM、Embedding、Reranker、MySQL、Redis、MinIO 日志；所有异常要么修复要么记录为阻断项。
- [ ] 生成 `docs/superpowers/reports/2026-09-04-stage-seven-report-enhancement-acceptance.md` 和 `C:\Users\23883\Desktop\第七阶段实机测试验收文档-2026-09-04.md`，仅在证据完整后宣布完成。

