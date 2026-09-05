# 第八阶段数据库详情与数据库问答验收报告

日期：2026-09-06（报告文件名沿用第八阶段冻结日期 2026-09-05）  
开发基线：`b11aa0256ac86041067079a8861c314f5eab059a`  
范围：数据源详情、数据库问答及其直接安全边界；不包含其他阶段功能  
实机服务器：`xidian@172.18.12.6:/home/xidian/sjw/smart_worksite`

## 1. 结论

审查发现并修复一个真实行为缺口：当前基线的数据库问答结果会原样返回密码、手机号等敏感列。修复在 JDBC `ResultSet` 读取边界同时检查列标签和底层列名，对敏感列统一返回 `[MASKED]`，普通业务列保持原值。该缺口严格按 RED -> GREEN 完成，未修改 Python SQL 生成逻辑或其他阶段功能。

其余目标能力已有实现，无生产代码变更：

- `JdbcDataSourceInspector` 使用真实只读 JDBC 连接返回 catalog/schema、表和列详情，并限制表数和单表列数。
- `DataSourceApplicationService` 在详情、连接测试、Schema 检查和状态变更前执行项目授权；停用数据源不能检查 Schema。
- `AiApplicationService` 仅按 `projectId + dataSourceId + ENABLED` 读取数据源；空结果返回确定性限制说明。
- `SafeSqlExecutor` 只允许单条 `SELECT/WITH`，设置最大行数和查询超时。
- 只有 SQLState `42*` 和 MySQL 3065 被判定为可修复 SQL；连接、认证等错误不进入 SQL 修复循环；修复次数有上限并拒绝重复 SQL。
- Python `database_service` 生成结构化取数计划，并要求总结只依据 SQL 结果；空结果不得推断“无风险”“已完成”或“数量为零”。

本报告不把 Mock、stub 或 H2 单元测试写成实机证据。自动化行为门禁、真实服务只读证据、历史真实任务证据和待主控 Chrome 证据分别记录。

## 2. TDD 记录

### RED

新增真实 JDBC/H2 查询测试 `SafeSqlExecutorTest.masksSensitiveColumnsFromDatabaseQueryResults`，执行：

```powershell
mvn '-Dtest=SafeSqlExecutorTest#masksSensitiveColumnsFromDatabaseQueryResults' test
```

结果：`Tests run: 1, Failures: 1`；断言期望 `password=[MASKED]`，实际为 `root-secret`。失败原因正是基线缺少结果脱敏，而非测试配置或语法错误。

### GREEN

在 `SafeSqlExecutor.readResult` 中按列标签和底层列名识别 password/passwd/pwd/secret/token/key、身份证、手机号、电话、邮箱、凭据、cookie/session 等敏感列，并在结果进入 Python 总结、QA 证据和前端前替换为 `[MASKED]`。

同一命令复跑结果：`Tests run: 1, Failures: 0, Errors: 0`，`BUILD SUCCESS`。

## 3. 自动化行为门禁（不能替代实机）

### 初始定向基线

| 层 | 命令 | 结果 |
| --- | --- | --- |
| Java | `mvn '-Dtest=DataSourceApplicationServiceTest,JdbcDataSourceInspectorTest,DataSourcePasswordCipherTest,SafeSqlExecutorTest,AiApplicationServiceTest,QaApplicationServiceTest' test` | 基线 79 tests，0 failure/error |
| Python | `py -m pytest tests/test_api.py -k database -q` | 10 passed，38 deselected；2 个既有 Pydantic 命名空间 warning |
| 前端 | `npm test -- --run src/views/qa/QaView.spec.ts src/views/qa/qaMessagePolling.spec.ts src/utils/qaMarkdown.spec.ts` | 3 files，36 tests passed |

Java 门禁覆盖：真实 JDBC 元数据、无效 JDBC URL、AES-GCM、只读 SQL、多语句/危险语句拒绝、最大行数、参数顺序、PostgreSQL/Kingbase 驱动、SQL 修复上限、重复 SQL、计划列校验、空结果、连接/认证不误修、数据库 QA 失败终态、停用和跨项目数据源拒绝、会话上下文隔离。

Python 门禁覆盖：结构化证据计划、参数归一化、MySQL 方言约束、失败 SQL 修复上下文、严格结果总结和模型失败显式返回。

前端门禁覆盖：QA 历史/刷新恢复、请求中状态、Markdown 安全渲染；当前仓库没有 `DataSourceView` 专用组件测试，因此数据源详情移动布局仍交由主控 Chrome 验收。

最终全量命令和结果见第 7 节。

## 4. 真实服务与真实数据证据（无 Mock、只读）

### 4.1 当前服务快照

2026-09-06 通过 SSH 在服务器读取，不修改数据：

- 服务器 HEAD：`b11aa0256ac86041067079a8861c314f5eab059a`。
- Java `GET http://127.0.0.1:8080/actuator/health`：`{"status":"UP"}`。
- Python `GET http://127.0.0.1:8015/v1/health`：服务 `UP`，`deploymentMode=LOCAL_ONLY`；响应 traceId `e6fe29031d57430e95ba5403131e62e8`。
- MySQL、Redis、MinIO、Python AI、本地 LLM、Embedding、Reranker 容器均为 `running/healthy`。
- 该服务器仍是 H100 功能环境，不能作为客户双 A6000 性能证据。

### 4.2 真实 MySQL 只读检查

通过容器内 MySQL 客户端读取 `information_schema` 和业务计数，凭据只从服务器 `.env` 注入且未输出：

| 检查项 | 真实结果 |
| --- | --- |
| MySQL 版本 | 8.4.11 |
| 当前库表数 | 37 |
| 当前库列数 | 531 |
| 未删除数据源 | 1 |
| 启用数据源 | 1 |
| 数据源密码格式 | `AES_GCM:` 前缀 1/1；未读取或输出密文/明文 |
| 成功 DATABASE QA 消息 | 7 |
| DATABASE QA 会话 | 3 |
| 单会话最多 DATABASE 消息 | 3 |
| 最近数据库外部调用 | 2026-09-05 14:28:05 |

真实元数据样例确认 `data_source` 包含 `id/project_id/name/db_type/jdbc_url/username/password_cipher/status/...`，`qa_message` 包含 `id/project_id/session_id/question/answer/route_mode/...`，同时返回数据类型和 nullable 信息。这证明真实服务器能够提供 Schema/表/列元数据；不展示数据源凭据和值。

真实外部调用日志聚合：

| callType | SUCCESS | FAILED |
| --- | ---: | ---: |
| `DATABASE_GENERATE_QUERY` | 84 | 5 |
| `DATABASE_SUMMARIZE_RESULT` | 38 | 1 |

这些计数证明成功和失败均有持久化记录；本报告没有读取可能包含业务问题的日志正文。

### 4.3 历史真实任务/请求

`docs/superpowers/reports/2026-09-01-stage-three-conversation-acceptance.md` 在真实 MySQL、真实本地模型、真实 Chrome 环境记录：

1. 数据库连续追问“数量 -> 风险分布 -> 负责人和整改日期”，空表时不编造；第 28-30 题通过。
2. “结合数据库和知识库说明本月未闭环安全问题及适用的整改要求。”返回数据库无对应表、知识库仿真数据及证据边界。
3. “查询当前项目最近5条施工日志，列出日期、区域、施工内容和状态；如果没有匹配记录，请说明空结果的可能原因和不能据此推断的结论。”返回无匹配结果且没有执行参数数量不匹配 SQL。
4. “如果数据库没有查询到符合条件的数据，应如何解释，不能推断什么？”明确不能推断项目无风险、系统必然故障或项目已经合规。

历史报告绑定提交 `1b98321b7ead6be95266777817bd3f51cc31a674`，只证明该提交的实机结果；当前提交通过自动化回归保持这些行为。新脱敏修复尚未部署，因此脱敏页面证据必须由主控在本提交部署后重新执行。

### 4.4 真实失败证据边界

- 当前服务器已有 5 次 SQL 生成失败和 1 次总结失败持久化记录，证明失败可观察。
- 当前轮未破坏性修改现有数据源，也未改写有效密码来制造认证失败。
- 连接、认证、超时“不误修 SQL”由 Java 定向测试证明；在主控部署并取得授权账号后，应再通过临时、隔离的数据源配置完成 HTTP/Chrome 负向验收。

## 5. 十九类验收矩阵

状态口径：`PASS` 表示本轮自动化加现有真实证据足以证明行为；`BLOCKED` 表示仍需主控部署/Chrome 或授权隔离资源；`N/A` 表示数据库详情/同步问答没有该类业务契约。

| # | 维度 | 状态 | 证据与判定 |
| ---: | --- | --- | --- |
| 1 | normal | PASS | 真实服务器 7 条成功 DATABASE QA、84 次成功 SQL 生成、38 次成功总结；Java/Python 定向测试通过。 |
| 2 | boundary | PASS | 表/列上限、最大返回行数、1-6 次 SQL 尝试上限、单语句和参数数量均有代码与测试门禁。 |
| 3 | empty result | PASS | Java 空行集不调用总结模型，固定说明“未查询到符合条件的数据”并禁止推断；历史真实请求验证空结果语义。 |
| 4 | partial evidence | PASS | 历史真实混合问答明确区分数据库无对应表与知识库仿真证据；Java mixed-route 测试保留可用数据库证据。 |
| 5 | no evidence | PASS | 历史真实空库/无表请求没有编造；Python 提示和 Java确定性空结果均禁止补造项目事实。 |
| 6 | format variants | PASS | MySQL 真实运行；PostgreSQL/Kingbase 驱动、方言和安全校验自动化通过。真实 PostgreSQL/Kingbase DSN 未配置，不宣称其实机通过。 |
| 7 | long input | N/A | 数据库详情是有界元数据，数据库问答当前没有独立长文件输入；模型全局上下文预算属于已验收的上下文功能。 |
| 8 | multi-turn | PASS | 真实库有 3 个 DATABASE 会话、单会话最多 3 条 DATABASE 消息；历史第 28-30 题连续追问通过，Java 会话隔离测试通过。 |
| 9 | refresh recovery | PASS | QA 消息、SQL/结果摘要和会话均持久化；既有真实 Chrome 报告验证刷新恢复，前端 QA 恢复测试通过。 |
| 10 | concurrent clicks | PASS | QA `clientRequestId/sourceSuggestionMessageId` 幂等已在既有真实验收与 Java/前端测试覆盖；数据库详情 GET 天然只读。 |
| 11 | permission isolation | PASS | Java 在读取 Schema/执行模型前校验项目访问，数据库 QA 仅按项目和启用状态加载数据源；跨项目数据源测试通过。待主控补 Chrome 猜 ID 截图。 |
| 12 | disabled resources | PASS | 停用数据源无法 Schema 检查或数据库问答，均在调用 JDBC/模型前拒绝；Java 测试通过。待主控补真实 UI 状态证据。 |
| 13 | async status | N/A | 数据源详情与数据库问答是同步 API，不创建异步任务；QA 同步失败会持久化 FAILED 消息，已有测试覆盖。 |
| 14 | database failure | PASS | SQLState `42*`/3065 才修复；`28*` 认证、`08*` 连接和其他错误不修复；重复 SQL 和总尝试次数有界。真实日志存在成功/失败记录。 |
| 15 | model failure | PASS | SQL 生成/总结失败显式失败并记录 external call；真实日志有 5/1 次失败，Python 模型失败测试通过。 |
| 16 | retrieval degradation | N/A | 纯数据库详情/数据库问答不调用向量检索；MIXED 路由降级属于 QA/RAG 验收范围，历史报告已有边界证据。 |
| 17 | timeout | PASS | JDBC statement 配置查询超时，AI HTTP 有连接/读取超时且 SQL 重试有上限；连接类异常不误修。当前轮没有故意占用真实库制造长查询。 |
| 18 | download contents | N/A | 当前数据源/数据库问答公开契约返回 JSON，不生成或下载文件；不存在可验证的文件头/格式。若产品后续要求结果导出，应单独定义格式与权限契约。 |
| 19 | mobile layout | BLOCKED | 按任务分工不运行 Chrome；主控需在部署本提交后以移动视口检查数据源列表、Schema 对话框、问答结果、SQL、脱敏行和错误提示。 |

## 6. 主控 Chrome/部署待办

以下项目不能由本报告中的单元测试替代：

1. 部署本提交后，用真实启用数据源打开详情，展开至少一个真实表并核对列名、类型、长度、nullable 和备注。
2. 在只读测试表或授权数据上查询手机号/密码别名列，确认页面、QA 引用、模型总结及日志均不出现原值，只显示 `[MASKED]`。
3. 同会话连续执行数量、分布、负责人追问；刷新后确认问题、答案、SQL 证据和延伸问题恢复，跨会话不串话。
4. 使用隔离的错误地址、错误凭据和可控超时数据源分别触发连接、认证、超时；确认不进入 SQL 修复循环，错误可诊断且不泄密。
5. 使用仅会产生语法/字段错误的只读问题触发可修复 SQL，确认修复次数有界、最终 SQL 与结果可追溯。
6. 停用数据源后确认 Schema 和新问答均被拒绝；使用另一项目成员猜测 dataSourceId，确认在模型/JDBC 前拒绝。
7. 以 390x844 等移动视口检查关键字段、状态、按钮、Schema 内容和横向滚动，不被固定宽度对话框或表格遮挡。
8. 当前没有数据库问答文件下载契约；不要用浏览器页面另存或截图冒充“下载内容验收”。

## 7. 最终验证

本节在提交前以最新工作树重新运行并填写；任何失败均不得改写为通过。

| 门禁 | 命令 | 最终结果 |
| --- | --- | --- |
| Java 定向 | `mvn '-Dtest=DataSourceApplicationServiceTest,JdbcDataSourceInspectorTest,DataSourcePasswordCipherTest,SafeSqlExecutorTest,AiApplicationServiceTest,QaApplicationServiceTest' test` | PASS：80 tests，0 failure/error/skip |
| Python 全量 | `py -m pytest -q` | PASS：375 passed；2 个既有 Pydantic warning |
| Python 编译 | `py -m compileall -q app` | PASS：exit 0 |
| 前端全量 | `npm test -- --run` | PASS：13 files，100 tests |
| 前端构建 | `npm run build` | PASS：`vue-tsc` 与 Vite build exit 0 |
| Java 全量/构建 | `mvn test`、`mvn -DskipTests package` | PASS：405 tests，0 failure/error；package exit 0 |
| Diff | `git diff --check` | PASS：无 whitespace 错误 |

## 8. 遗留阻塞

- **BLOCKED：新脱敏实现的部署后 Chrome/真实 MySQL 页面证据。** 责任方：主控；输入：本提交、授权账号、只读数据源和可展示的脱敏样例。
- **BLOCKED：移动端布局证据。** 责任方：主控；输入：部署后的数据源页面和移动视口。
- **BLOCKED：隔离连接/认证/超时失败的 HTTP/Chrome 证据。** 责任方：主控；需使用可删除的测试数据源，不得改坏现有数据源。
- **N/A：下载内容。** 当前数据库详情/问答无文件下载 API；后续若新增导出需求，必须另做权限、格式、内容和移动下载验收。
