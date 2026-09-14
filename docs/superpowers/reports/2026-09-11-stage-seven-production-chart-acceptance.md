# 第七阶段生产级报告图表实机验收

日期：2026-09-11  
仓库：`joker-sxj/smart_worksite:main`  
最终代码提交：`256d6b096f2df88b0c29f93cfdb39ae97370a86f`  
服务器：`xidian@172.18.12.6:/home/xidian/sjw/smart_worksite`

## 验收结论

原报告中的“大块蓝色矩形”并非图片分辨率不足，而是技术字段被误选为图表维度，且旧图缺少标题、维度、单位、标签、数值、刻度和来源。本轮已完成业务维度规划、技术字段排除、聚合计数加权、风险等级排序、单柱限宽、清晰标注以及聚合结论口径修复。

真实数据正向报告 23（任务 650，版本 v15）使用本地模型、真实 MySQL 数据源和 12 条明确标注的验收业务记录生成。最终 PDF 为 3 页有效 PDF 1.7。风险等级图为一级 2、二级 4、三级 4、四级 2；整改状态图为已闭环 6、待整改 3、整改中 3，均与数据库及报告表格一致。无数据/技术元数据报告 20 则不再生成误导性的 `RUNNING` 状态图。

## 真实验收数据

验收表：`stage7_acceptance_risk_record`  
批次：`STAGE7-20260911`  
数据：12 条施工风险记录，覆盖 4 个风险等级、8 类隐患、3 个整改状态、5 名负责人和多个施工区域。数据不是 Mock 响应；系统通过真实 JDBC 查询、真实本地模型分析、Java 渲染、LibreOffice 转换和下载接口完成整条链路。

## 24 个可验证场景

|编号|场景|结果|证据|
|---:|---|---|---|
|01|服务器代码为最终提交|PASS|`git rev-parse HEAD`|
|02|启动脚本完整执行|PASS|`Smart Worksite is ready`|
|03|Java 健康检查|PASS|`status=UP`|
|04|Python AI 健康检查|PASS|`status=UP`、`LOCAL_ONLY`|
|05|本地聊天模型可达|PASS|AI health: chat READY|
|06|本地 Embedding 可达|PASS|AI health: embedding READY|
|07|本地 Reranker 可达|PASS|AI health: rerank READY|
|08|真实数据库连接|PASS|数据源 1，MySQL|
|09|真实风险明细录入|PASS|批次共 12 条|
|10|四级风险分类覆盖|PASS|一级/二级/三级/四级|
|11|三种整改状态覆盖|PASS|已闭环/待整改/整改中|
|12|本地模型生成真实 SQL|PASS|报告变量 `referencesJson`|
|13|SQL 参数化项目隔离|PASS|查询含 `project_id = ?`|
|14|报告异步任务完成|PASS|报告 23 / 任务 650 / COMPLETED|
|15|空数据不伪造业务图|PASS|报告 20 不绘制技术状态图|
|16|风险等级业务排序|PASS|一级到四级固定顺序|
|17|聚合结果按计数字段加权|PASS|2/4/4/2，不是 1/1/1/1|
|18|整改状态按计数字段加权|PASS|6/3/3，总计 12|
|19|小整数刻度无重复|PASS|0-4 与 0-6 清晰刻度|
|20|单柱宽度受限|PASS|无铺满绘图区的蓝色矩形|
|21|中文标题、单位、来源可读|PASS|逐页 PNG 视觉检查|
|22|表格、图表、模型摘要一致|PASS|风险总数均为 12|
|23|聚合结论区分组数与业务条数|PASS|“4组/10组，合计12条”|
|24|Word/PDF 下载链路|PASS|有效 DOCX 与 3 页 PDF 1.7|

## 自动化验证

- Java 全量：426 tests，0 failures，0 errors，0 skipped。
- Java 打包：`mvn -DskipTests package`，BUILD SUCCESS。
- 图表定向测试覆盖：业务字段优先级、技术字段排除、风险排序、月份限制、聚合计数、明细计数、小整数刻度、单柱限宽及异常降级。
- `git diff --check` 通过。

## 视觉验收文件

- `C:/Users/23883/Documents/智慧工地/stage7-real-risk-chart-accepted.pdf`
- `C:/Users/23883/Documents/智慧工地/tmp/pdfs/stage7-accepted/page-1.png`
- `C:/Users/23883/Documents/智慧工地/tmp/pdfs/stage7-accepted/page-2.png`
- `C:/Users/23883/Documents/智慧工地/tmp/pdfs/stage7-accepted/page-3.png`

## 日志与边界

最终报告生成时间窗内，Java 错误文件为空，未发现图表渲染异常；Python AI 报告调用完成。健康检查同时暴露了既有 OCR 视觉模型 `qwen-vl-plus` 未部署，AI 总体 readiness 为 DEGRADED；该问题与本次报告图表链路无关，不作为本缺陷的通过依据，需在 OCR 功能阶段单独关闭。

测试服务器为双 H100，仅证明功能链路；不代表客户双 A6000 的吞吐和延迟指标。
