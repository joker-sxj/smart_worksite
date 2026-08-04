# 项目知识库与政策爬虫知识库隔离设计

## 目标

每个项目维护彼此独立的项目资料库和政策资讯库。用户上传文档、报告生成和审查默认只使用项目资料库；政策爬虫只写入系统管理的政策资讯库；问答允许选择项目资料、政策资讯或综合检索。

## 数据模型

在 `knowledge_base` 增加 `knowledge_base_type`：

- `PROJECT`：用户管理的项目资料库，现有知识库迁移后均为该类型。
- `POLICY`：系统管理的政策资讯库，每个项目最多一个。

在项目配置 JSON 增加只读字段 `policyKnowledgeBaseId`。`defaultKnowledgeBaseId` 继续表示项目资料默认库，不再被政策爬虫使用。

数据库通过 `(project_id, knowledge_base_type, deleted)` 约束政策库唯一性。政策库固定名称为“政策资讯库”，固定领域为 `POLICY_CRAWLER`，由系统账号创建和维护。

## 生命周期

1. 首次提交政策爬取任务时解析 `policyKnowledgeBaseId`。
2. 配置指向有效 POLICY 库时直接复用。
3. 配置缺失或指向已删除记录时，查找该项目现存 POLICY 库。
4. 仍不存在时自动创建并回写项目配置。
5. POLICY 库禁止通过普通知识库接口改名、禁用、删除和上传用户文件。
6. 删除项目时仍沿用项目级清理规则。

## 索引隔离与迁移

政策文章继续保存在 `policy_article`，RAG 文档继续使用 `sourceType=POLICY_ARTICLE` 和文章 ID 作为 `sourceId`。

RAG 服务增加按来源删除接口，支持：

- `projectId`
- `sourceType`
- `sourceIds`
- `excludeKnowledgeBaseId`

每篇政策文章迁移/更新时：

1. 先索引到项目的 POLICY 知识库。
2. 索引成功后，删除该项目其他知识库中相同 `POLICY_ARTICLE/sourceId` 的旧向量。
3. 最后标记文章索引成功。

这样现有混入项目库的政策向量会在重新爬取时逐篇迁移，且迁移过程中始终至少保留一份可检索副本。部署验证时主动重新爬取现有启用政策源，完成当前数据迁移。

删除能力需覆盖 LOCAL、PGVECTOR、MILVUS 三种向量存储；没有匹配记录时按幂等成功处理。

## 查询与界面

知识库管理：

- 普通列表默认展示 PROJECT 库。
- POLICY 库以“系统政策库”只读卡片展示状态、文章数和最近更新时间，不提供上传、编辑、禁用和删除操作。

问答范围：

- `PROJECT`：只传 PROJECT 知识库 ID。
- `POLICY`：只传 `policyKnowledgeBaseId`。
- `ALL`：合并上述 ID。

默认范围保持 `PROJECT`，防止政策文章影响项目事实问答。回答引用继续使用 RAG 返回的 `sourceType`、标题和元数据，前端标记“项目资料”或“政策资讯”。

报告生成和审查只列出 PROJECT 库。本次不自动把政策库加入报告或审查，避免改变现有业务结果。

## API 兼容性

- 项目设置响应新增 `policyKnowledgeBaseId`，请求体不开放该字段，避免人工填写 ID。
- 知识库响应新增 `knowledgeBaseType`。
- 知识库查询新增可选 `knowledgeBaseType`；不传时保持兼容，前端显式请求 `PROJECT`。
- RAG 新增系统内部删除接口，浏览器端不直接调用 Python。

## 错误处理

- POLICY 库创建或配置回写失败：爬取任务失败并保留明确错误。
- 新政策索引失败：不删除旧向量。
- 旧向量删除失败：文章标记失败并允许任务重试，避免长期重复召回。
- POLICY 库被异常禁用：系统不静默改用项目默认库，而是重新启用系统政策库或报告冲突。

## 验收标准

- 项目默认知识库被切换、禁用或删除时，政策爬虫不受影响。
- 政策文章只存在于该项目 POLICY 库，不再被 PROJECT-only 问答召回。
- POLICY-only 和 ALL 问答能够召回青岛住房城建现有政策文章。
- 普通上传、报告和审查不能选择 POLICY 库。
- 当前项目自动生成一个政策资讯库，15篇现有政策文章迁移成功。
- 后端、Python、前端测试及 Windows 启动后的实机流程通过。
