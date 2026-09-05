# 第七阶段实机测试验收文档：报告增强与 PDF 导出

日期：2026-09-05
远程仓库：joker-sxj/smart_worksite:main
验收服务器：xidian@172.18.12.6:/home/xidian/sjw/smart_worksite
提交：eb2b7ea093cfa94700f91ca221940b50a89722dd
模型：h100-fp8（服务器双 H100，仅作功能验收；客户双 A6000 需另行压测）。所有模型请求走本地链路。

## 一、验收结论

报告结构化内容、确定性统计、事实约束结论、DOCX 表格/图表、真实 PDF 导出与 Word/PDF 下载已完成关键链路验证。报告 16（此前实机记录）及本轮新建报告 17 均使用真实项目配置；报告 17 在 Chrome 中创建后显示“已完成”，版本 v9。

本阶段 PDF 不是把 Word 改后缀：服务器下载文件实际识别为 3 页 PDF 1.7，文件头为 `%PDF-1.7`，`pdfinfo` 和 `pdftotext` 可读；Word 是有效 Microsoft Word 2007+ 文件，包含 PNG 图表和结构化表格。

## 二、真实实机步骤

1. Chrome 打开 `http://172.18.12.6:5173/report` 并登录。
2. 新建“第七阶段PDF实机验收-0905”，选择“施工情况报告生成模板”“八月工地资料库”“工地情况数据库”。
3. 任务完成后列表显示“已完成 / v9”。
4. 详情页显示三个变量和“查看真实查询与数据”；下载菜单显示“下载 Word”“下载 PDF”。
5. 通过服务器认证接口下载报告 17 的 PDF/Word，使用 `file`、`pdfinfo`、`pdftotext`、`unzip` 检查格式和内容。

## 三、44 个可验证场景

|编号|场景|结果|证据|
|---:|---|---|---|
|01|真实登录和报告列表|PASS|Chrome|
|02|项目权限访问|PASS|Chrome/Java|
|03|新建表单加载|PASS|Chrome|
|04|选择真实知识库|PASS|Chrome|
|05|选择真实数据库|PASS|Chrome|
|06|创建报告任务|PASS|Chrome/Java|
|07|任务入队|PASS|Java log|
|08|任务完成刷新|PASS|Chrome|
|09|多变量独立生成|PASS|Java/报告17|
|10|变量成功持久化|PASS|Java/DB|
|11|变量失败占位|PASS|Java test|
|12|部分成功下载 Word|PASS|Java test|
|13|PDF 转换成功|PASS|LibreOffice/报告17|
|14|PDF 转换失败保留 Word|PASS|Java test|
|15|非法 PDF 不发布|PASS|Java test|
|16|PDF magic 校验|PASS|Java/服务器|
|17|PDFBox 结构校验|PASS|Java test|
|18|pdfinfo 页面校验|PASS|服务器|
|19|pdftotext 文本校验|PASS|服务器|
|20|DOCX OOXML 校验|PASS|服务器|
|21|DOCX 表格存在|PASS|unzip，8 个表格|
|22|DOCX 图表 PNG 存在|PASS|word/media/image1.png|
|23|详情显示变量|PASS|Chrome|
|24|详情显示数据依据入口|PASS|Chrome|
|25|下载 Word 菜单|PASS|Chrome|
|26|下载 PDF 菜单|PASS|Chrome|
|27|PDF 下载接口|PASS|Java/服务器|
|28|Word 下载接口|PASS|Java/服务器|
|29|非法格式拒绝|PASS|Java test|
|30|缺失 PDF 明确报错|PASS|Java test|
|31|PDF 项目隔离|PASS|Java implementation|
|32|PDF 报告隔离|PASS|Java implementation|
|33|PDF 业务类型隔离|PASS|Java implementation|
|34|空数据不伪造无风险|PASS|Java test|
|35|空表安全结论|PASS|Java test|
|36|多风险等级统计|PASS|Java/真实报告|
|37|多负责人统计|PASS|Java test|
|38|月趋势统计|PASS|Java test|
|39|数值合计|PASS|Java test|
|40|列白名单与顺序|PASS|Java test|
|41|超过 100 行截断|PASS|Java test|
|42|超过 20 分类合并|PASS|Java test|
|43|本地 LLM/Embedding/Reranker 就绪|PASS|scripts/status.sh|
|44|Chrome 控制台 warn/error 审计|PASS|空集合|

## 四、自动化验证

- Java：`mvn -q test`，399 tests，0 failures，0 errors，0 skipped。
- Java：`mvn -q -DskipTests package`，退出码 0。
- Python：`py -m pytest -q`，375 passed；`py -m compileall -q app tests` 通过。
- 前端：12 个测试文件，97 passed；`npm run build` 通过。
- `git diff --check` 通过。

## 五、环境和日志

- Java、Python AI、MySQL、Redis、MinIO、local-llm、local-embedding、local-reranker 均 UP/healthy。
- Java 后端错误日志为空；Python AI 最近报告为本地模型 HTTP 200；Chrome 控制台 error/warn 为 0。
- Flyway 对 MySQL 8.4 的兼容性提示和既有 unchecked 警告不属于本阶段运行故障。

## 六、边界和复现

服务器使用双 H100，不代表双 A6000 的性能指标。报告 17 的部分变量在当前真实数据库无数据时，系统输出“无法基于具体数据生成”，没有把空数据伪装成无风险结论。

```bash
cd /home/xidian/sjw/smart_worksite
git rev-parse HEAD
./scripts/status.sh
tail -n 150 logs/backend.out.log
docker logs --since 30m smart-worksite-python-ai-service
```
