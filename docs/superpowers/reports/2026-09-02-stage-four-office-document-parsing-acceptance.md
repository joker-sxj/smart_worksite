# 阶段四办公文档解析验收记录

## 验收范围

本阶段验证 Excel、CSV、PPT/PPTX 原生内容解析、解析结果状态、知识库入库门禁和 RAG 来源定位。解析链路使用 Java Apache POI；不识别 Office 内嵌图片文字，纯图片或扫描内容应提示使用 OCR。

## 自动化门禁

- Java 定向回归：通过，包含 38 个相关测试用例。
- Java 完整回归：执行完成，Surefire 无失败和错误；测试中主动模拟的异常会输出 ERROR/WARN 日志，但断言通过。
- 前端 Vitest：90 passed。
- 前端 production build：通过。
- `git diff --check`：通过。
- Python 服务：Linux 当前安装环境缺少 pytest，未能在本机执行 Python pytest；容器健康检查和本地模型 smoke check 通过。

## Linux 实机版本

- 地址：`http://172.18.12.6:5173/knowledge`
- 提交：`f367398`（问答引用定位与显式文件名检索修复后）
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
| `progress.tsv` | UTF-8 TSV | `PARSED` | `SUCCESS` | `A1:D2`，`CSV_TABLE` |

上述文件均使用真实文件内容上传到服务器对象存储，经过真实 Java、Python AI、本地模型、向量入库链路处理，没有使用 Mock 作为结论依据。

## 已覆盖场景

已实际覆盖：旧版 XLS、多 Sheet XLSX、PPTX 多页文本、PPTX 表格、CSV BOM、GB18030 编码、TSV、中文内容、解析状态轮询、解析成功后入库、入库成功后来源元数据回传、五类文件的知识库状态刷新。

代码回归还覆盖：空工作簿、仅空白单元格、解析错误、结构化 Block、公式/日期/数字、合并区域、隐藏 Sheet、稀疏列限制、CSV MIME 类型识别、无成功解析结果时禁止创建入库任务、项目身份校验和失败状态持久化。

## 未完成项与阻塞

Chrome 已开启本地文件上传权限，五个真实办公文件已经完成 UI 上传、解析和入库，状态均为“解析成功/成功”。问答实机已验证 Excel 题目能返回问题、负责人、Sheet 和单元格范围；此前同题的定位缺失已修复。尚未完成用户要求的至少 30 个 Chrome UI 场景，当前不能把已完成的接口级覆盖冒充为 Chrome 全量验收。

仍需继续覆盖：失败重试、刷新期间状态、仅图片/空文件/损坏文件、重复上传、详情查看、跨项目隔离、CSV/PPT/PPTX/Excel 问答引用；每个独立问答先新建会话。

## 本轮修复

- Java 将 RAG `location.page/slide/sheet/cellRange` 通用映射到模型证据，不依赖特定文件或问题。
- Python 模型上下文显式携带页码、幻灯片和表格位置；本地词法检索对用户明确点名的文件名加权，避免向量结果把目标文件挤出候选集。
- 自动化回归：Java `353 tests, failures=0, errors=0`；前端 `90 passed`；前端构建通过；Linux Python 容器 `363 passed`。

## 日志结论

修复版本启动后的服务健康检查通过。历史日志仍包含之前数据库问答参数 422 和旧进程重启记录；本次办公文档窗口内未发现新的办公解析 5xx、422、OOM、CUDA 或无限重试。服务启动过程中出现的 Flyway 对 MySQL 8.4 的兼容性提示属于已有警告，不影响当前迁移。
