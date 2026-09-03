# 阶段五：多参考资料与长文档合规审查验收报告

## 1. 验收结论

阶段五已完成开发、自动化回归、Linux 部署和 Chrome 实机验收。最终功能实现提交为 `7993334509dcce2593ca8e683a2a7efd2e7dee9e`，部署在 `http://172.18.12.6:5173/review`，使用本地模型链路；本报告随最终分支一并推送。

正式实机验收共 40 个场景，均使用真实 PDF、Word、Excel 模板和真实上传文件，不使用 Mock 数据作为结论依据：

- 系统终态：40/40。
- 业务判定：40/40。
- 审查记录：63-102。
- 规则结果：120 条，其中 `COMPLETED` 68 条、`NEEDS_MANUAL_CONFIRMATION` 52 条。
- 记录状态：`COMPLETED` 21 条、`PARTIAL_SUCCESS` 19 条、`FAILED` 0 条。

## 2. 功能范围

- 支持一个主审查文件、一个模板和多个参考依据。
- 参考依据可来自当前项目已成功入库的知识文档，也可来自本次临时上传的 PDF/Word 文件。
- 主文件与参考文件严格区分为 `PRIMARY` 和 `REFERENCE`，参考文件不会被误报为待审文件问题。
- 知识文档与临时文件合计最多 20 项，临时文件最多 10 项；前端提交前和后端均校验上限。
- 模板按规则拆分，逐规则调用本地模型并独立保存结果；单条规则失败不会丢失其他规则结果。
- 支持 PDF 页码、Word 段落、Excel Sheet/单元格等证据定位。
- 长文档最多解析 120,000 字符，并按规则检索有界证据，不将整份文档直接塞入模型上下文。
- 支持 `PARTIAL_SUCCESS` 和人工确认状态，避免模型输出矛盾时伪装为成功，也避免无限重试。
- 页面刷新后可通过项目维度保存的记录 ID恢复审查任务及阶段进度。

## 3. 自动化测试

- Java：372 tests，0 failures，0 errors。
- Python AI：366 passed，2 个既有 Pydantic protected namespace warning。
- 前端：11 个测试文件、94 passed。
- 前端 production build：通过，`vue-tsc --noEmit && vite build`。
- `git diff --check`：通过。

Java 测试中出现的 ERROR/WARN 是主动构造的失败路径日志，用于验证数据库不可用、Redis 失败、非法任务消息、解析失败等处理，并非测试失败。

## 4. 真实场景覆盖

40 个场景覆盖了 PDF/Word 主文件、PDF/Excel 模板、编号和无编号模板、单规则和多规则模板、无参考资料、单参考资料、多 PDF 参考资料、无关资料、冲突旧资料、合规/不合规/混合主文件、长文档后部缺陷、页码证据、规则级结果、人工确认、页面刷新恢复、问题列表和主/参考证据角色展示。

实机资料位于：

`stage5-real-fixtures/`

包括 `primary-compliant.pdf`、`primary-noncompliant.pdf`、`primary-mixed.pdf`、`primary-long-late-defect.pdf`、Word 主文件、五规则 PDF/Excel 模板、损坏文件和三类以上参考 PDF。

## 5. Chrome 实机验收

已在登录态 Chrome 页面完成真实操作：上传不合规 PDF 主文件、上传两个临时参考 PDF、选择 Excel 五规则模板、发起审查、处理过程中刷新页面，并在刷新后恢复 `WORKER_CLAIMED`、`REVIEW_EXTRACTING`、`REVIEW_AI`、`REVIEW_PERSISTING`、`FINISH` 阶段。

页面最终显示 `PARTIAL_SUCCESS`、5 条规则、主文件证据和参考文件证据；展开规则详情可看到文件名称、页码以及 `PRIMARY`/`REFERENCE` 角色。Chrome 控制台 error/warn 数量为 0。

## 6. 服务与日志

最终服务器使用：

```bash
./scripts/start-all.sh --model-profile h100-fp8
```

健康检查结果：Java actuator `UP`；Python AI `UP`，`LOCAL_ONLY`；chat、vision、embedding、rerank 均 `READY`。MySQL、Redis、MinIO、Python AI、本地 LLM、Embedding、Reranker 容器均 running/healthy。

本轮部署后日志未发现新的 5xx、HTTP 422、Java Exception、Python Traceback、timeout、OOM、CUDA 错误或 retry loop。Embedding/Reranker 的历史 `RestartCount=1` 不在本轮日志窗口内，当前无重启。

## 7. 客户环境说明

本次实机服务器为双 H100，`h100-fp8` 只用于当前测试环境验证，不作为客户双 A6000 的性能承诺。客户部署应使用与显存、量化方式、并发和上下文预算匹配的模型 profile，并重新执行健康检查、上下文边界和真实业务回归。

## 8. 已知边界

模型对部分“判定为不合规但未给出问题明细”的结果会进入 `NEEDS_MANUAL_CONFIRMATION`，系统保留其他规则成功结果并明确提示人工确认；这是为保证审查结果可追溯和避免无限重试的通用策略，不是针对单个测试文件的硬编码。
