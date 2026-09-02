# 阶段四办公文档解析验收记录

## 验收范围

本阶段验证 Excel、CSV、PPT/PPTX 原生内容解析、解析结果状态、知识库入库门禁和 RAG 来源定位。解析链路使用 Java Apache POI；不识别 Office 内嵌图片文字，纯图片或扫描内容应提示使用 OCR。

## 自动化门禁

- Java 定向回归：通过，包含新增 TSV 扩展名、上传 MIME 归一化和解析任务覆盖；完整回归执行完成，Surefire 无失败和错误。
- 前端 Vitest：91 passed。
- 前端 production build：通过。
- `git diff --check`：通过。
- Python 服务：Linux 本机环境缺少 pytest，但使用项目 Docker 运行环境执行 `363 passed`；容器健康检查和本地模型 smoke check 通过。

## Linux 实机版本

- 地址：`http://172.18.12.6:5173/knowledge`
- 提交：`8d3a0a9` 基线，当前部署包含未提交的 TSV MIME/扩展名兼容修复（待推送提交）
- Java：`/actuator/health` 返回 `{"status":"UP"}`。
- Python：`/v1/health` 返回 `LOCAL_ONLY`，chat、vision、embedding、rerank 均 `READY`。
- Docker：MySQL、Redis、MinIO、Python AI、local LLM、embedding、reranker 均运行；MinIO init 退出码为 0。

## 真实文件验收

| 文件 | 内容类型 | 解析结果 | 入库结果 | 引用定位 |
|---|---|---|---|---|
| `safety_legacy.xls` | Excel 97-2003 | `PARSED` | `SUCCESS` | `Legacy Risk!A1:B2`，`EXCEL_SHEET` |
| `safety_multi.xlsx` | XLSX，多 Sheet、数字 | `PARSED` | `SUCCESS` | `Safety Risks`、`Progress`，`EXCEL_SHEET` |
| `safety_review.pptx` | PPTX，多页文本和表格 | `PARSED` | `SUCCESS` | 第 1、2 页，`PPT_TEXT`、`PPT_TABLE` |
| `safety_utf8_bom.csv` | UTF-8 BOM CSV | `PARSED` | `SUCCESS` | `A1:E3`，`CSV_TABLE` |
| `safety_gb18030.csv` | GB18030 CSV | `PARSED` | `SUCCESS` | `A1:D2`，`CSV_TABLE` |
| `progress.tsv` | UTF-8 TSV | `PARSED` | `SUCCESS` | `A1:D2`，`TSV_TABLE` |

上述文件均使用真实文件内容上传到服务器对象存储，经过真实 Java、Python AI、本地模型、向量入库链路处理，没有使用 Mock 作为结论依据。

## 已覆盖场景

已实际覆盖 30 个 Chrome 实机场景，均使用真实文件和本地模型：6 类文件选择/上传（XLS、XLSX、PPTX、UTF-8 BOM CSV、GB18030 CSV、TSV）、6 类解析成功状态、6 类入库成功状态、5 类详情/来源定位、TSV 解析中和入库中状态、解析失败损坏 XLSX、空 CSV 上传校验、未解析/解析失败时入库按钮门禁、重复历史文件状态刷新、Excel 多 Sheet 问答、XLS 问答、两种 CSV 问答、TSV 问答、PPTX 文本/表格问答、延伸问题自动发送。问答独立会话均先新建会话。

代码回归还覆盖：空工作簿、仅空白单元格、解析错误、结构化 Block、公式/日期/数字、合并区域、隐藏 Sheet、稀疏列限制、CSV MIME 类型识别、无成功解析结果时禁止创建入库任务、项目身份校验和失败状态持久化。

## 未完成项与阻塞

Chrome 已开启本地文件上传权限，真实办公文件已经完成 UI 上传、解析和入库，状态均为“解析成功/成功”。本轮发现并修复 TSV 在浏览器上传时被错误判定为不支持的问题，复测状态为“解析成功/成功”。问答实机验证覆盖 Excel、XLS、CSV、TSV、PPTX 内容及定位；延伸问题已自动发送并产生下一轮消息。

问答实机补充验证：PPTX 点名检索返回 `Edge protection`、`Zhang San` 和幻灯片 1 定位；此前目标 PPTX 被无关 PDF 挤出候选集的问题已修复。

本阶段不将仅图片文档 OCR 识别准确率、跨项目权限隔离、数据库问答和合规审查作为办公解析完成条件；这些属于其他阶段的验收范围。

## 本轮修复

- Java 将 RAG `location.page/slide/sheet/cellRange` 通用映射到模型证据，不依赖特定文件或问题。
- Python 模型上下文显式携带页码、幻灯片和表格位置；本地词法检索对用户明确点名的文件名加权，避免向量结果把目标文件挤出候选集。
- 自动化回归：Java `353 tests, failures=0, errors=0`；前端 `90 passed`；前端构建通过；Linux Python 容器 `363 passed`。
- 修复浏览器上传 TSV 被拒绝：前端支持集合和 `accept` 增加 `.tsv`；Java 文件上传服务将带 `.tsv` 的 `application/octet-stream` 归一化为 `text/tab-separated-values`；Java 解析器保留 `tsv` 输入格式和 `TSV_TABLE` 来源类型。新增 Java/前端回归测试。
- 本轮验证：Java 完整回归通过；Python Docker 回归 `364 passed`；前端 Vitest `91 passed`；前端 production build 通过。

## 日志结论

修复版本启动后的服务健康检查通过。日志检查窗口为 2026-09-02 16:14-16:40（Asia/Shanghai）：本次办公文档操作未产生新的办公解析 5xx、422、OOM、CUDA 或无限重试；Java 错误文件为空，Python AI 仅有本地模型健康检查的 200 日志，Chrome 控制台错误/警告为 0。历史日志仍包含之前数据库问答参数 422、旧进程重启和测试主动模拟异常，未归因于本次办公解析。
