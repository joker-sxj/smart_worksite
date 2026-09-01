# 阶段三：动态检索与严格证据问答设计

## 目标

在阶段二统一 Token 预算的基础上，建立面向知识库问答的动态检索编排能力：宽召回、严结论，第一轮证据不足时最多自动补检一次，仍不足时明确反馈并允许用户直接在文本框补充条件后重新提问。

本阶段只实现动态检索、证据充分性判定、有限补检和文本框补救；会话摘要、长期记忆、延伸问题、指定文档选择器、知识库范围调整器和检索详情面板存档到后续阶段。

## 已确认的产品规则

### 严格证据模式

- 只有检索证据直接支持的内容才能作为确定性结论。
- 证据不足时不得使用模型常识、训练记忆或推测补齐法规条款、数值、日期和责任主体。
- 能确认的部分正常回答，不能确认的部分单独标记为“现有资料暂时无法确认”。
- “没有检索到依据”和“文档明确禁止”必须分开表达。
- 每个关键结论都应关联真实文档、章节/页码和片段。

### 检索轮次

```text
FIRST_RETRIEVAL
  ├─ SUFFICIENT → ANSWER
  ├─ PARTIAL → PARTIAL_ANSWER
  ├─ NO_EVIDENCE → SECOND_RETRIEVAL
  └─ CONFLICT → CONFLICT_ANSWER

SECOND_RETRIEVAL
  ├─ SUFFICIENT → ANSWER
  ├─ PARTIAL → PARTIAL_ANSWER
  ├─ NO_EVIDENCE → EVIDENCE_NOT_FOUND
  └─ CONFLICT → CONFLICT_ANSWER
```

- 单个请求最多两轮检索。
- 最多一次查询改写。
- 第二轮不得再次改写或递归调用检索。
- 答案生成失败不得重新触发检索。
- 达到检索时间上限后立即停止，不产生后台重试。
- 改写后查询指纹与上一轮相同时跳过第二轮。

### 有效性与版本

- 优先现行、生效且适用范围匹配的资料。
- 同等条件下优先版本日期最新的资料。
- 已废止资料只能作为历史依据，不能覆盖现行文件。
- 未来生效资料必须标明生效日期。
- 有效性元数据缺失的资料标记为 `UNKNOWN`，不能静默丢弃，也不能直接伪装成现行依据。
- 多份现行资料冲突时并列展示适用条件和依据。

## 系统架构

Java 负责认证、项目/知识库权限、会话和消息状态；Python 负责问题分析、动态检索、证据评估和阶段二预算装配。

```text
Java QA API
  │ current question + project/user scope + selected knowledge bases
  ▼
Python retrieval orchestrator
  ├─ problem analyzer
  ├─ first hybrid retrieval
  ├─ evidence sufficiency evaluator
  ├─ one query rewrite when justified
  ├─ second hybrid retrieval
  ├─ validity/version resolver
  └─ context budget planner from stage two
  ▼
Local model answer with evidence references
```

检索编排器不得获得或推断额外权限；所有检索始终携带 Java 传入的项目、用户可见知识库和文档范围。模型不能直接决定权限或绕过范围。

## 动态检索策略

### 问题分析

抽取以下可审计实体：

- 标准号、法规名称和条款号；
- 对象、行为、责任主体、时间和地区；
- 数值、单位和比较关系；
- 问题类型：定义、数值、流程、责任、比较、是否允许或范围。

模型分析失败时使用本地规则降级，不因分析失败重复调用模型。

### 第一轮召回

根据问题类型动态组合：

- 标准号/条款号/关键短语的归一化精确检索；
- 关键词交集检索；
- 语义向量检索；
- 已配置的全文检索；
- 命中片段的相邻片段、同章节和同表格上下文扩展。

召回阶段容忍全角/半角符号、空格、换行、OCR 断词、中文数字、破折号和条款格式差异。召回宽松不代表最终结论宽松。

### 证据评估

评估结果分为：

- `SUFFICIENT`：关键结论均有直接、适用的证据；
- `PARTIAL`：部分结论有直接证据，其他部分缺失；
- `INSUFFICIENT`：只有主题相关片段，没有直接支持结论的依据；
- `CONFLICT`：多个现行适用资料对同一结论存在冲突；
- `VALIDITY_UNKNOWN`：存在相关证据，但文件有效性无法确认；
- `RETRIEVAL_DEGRADED`：某一路检索故障，使用可用通道完成了降级召回；
- `TIMEOUT`：达到检索时限。

判定必须关注问题中的核心实体和要求，不能因缺少次要字段而丢弃已经确认的结论。

### 第二轮补检

只有以下情况允许补检：

- 没有直接证据；
- 缺少标准号、条款号、对象、时间或数值等核心实体覆盖；
- 找到条款引用但没有条款正文；
- 找到表格说明但没有表格内容；
- 跨章节问题只覆盖了部分问题要点；
- 存在明确的同义词或格式归一化机会。

改写只能增加已识别的标准号、条款号、实体、同义词或法规检索表达，不得改变原意、扩大权限范围或引入猜测条件。

### 防重试机制

查询指纹由以下字段生成：

```text
projectId + knowledgeBaseIds + documentScope + normalizedQuery + strategy + permissionScope
```

如果改写查询只改变空格、标点、顺序，或两轮候选集合完全相同，则标记 `SKIPPED_DUPLICATE_QUERY`，不执行重复调用。

Embedding、Reranker 或查询改写失败时分别降级到可用通道；同一组件在一个请求中失败后不得无限重试。

## 用户交互范围

本阶段只实现一个补救入口：**用户在当前问题文本框中补充条件后重新发送**。

页面要求：

- `PARTIAL`、`INSUFFICIENT`、`VALIDITY_UNKNOWN`、`CONFLICT` 和 `TIMEOUT` 状态必须有清晰文字说明；
- 展示已确认内容、无法确认内容以及建议补充的信息；
- 用户直接编辑当前文本框并点击发送，创建新的问答消息；
- 原问题和补充后的新问题都保留，便于审计；
- 新请求重新执行最多两轮检索，不恢复旧请求的后台任务；
- 前端不自动扩大知识库、项目或文档权限范围。

以下内容只存档，不在本阶段实现：

- “调整知识库范围”按钮；
- “指定文档”选择器；
- “补充条件”结构化表单；
- “查看已检索证据”详情面板；
- 会话摘要、长期记忆和延伸问题。

## 错误、超时和安全

- 单轮检索目标 P95 不超过 30 秒。
- 触发第二轮的请求目标 P95 不超过 45 秒。
- 检索编排器使用有限超时；超时后返回已完成阶段状态。
- 任何错误响应不得泄露 Token、密钥、模型路径、内部 URL、完整提示词或服务器文件路径。
- 用户问题正文只有在正常业务消息中返回；错误信息使用脱敏后的通用描述。
- 超限继续复用阶段二 `CONTEXT_BUDGET_EXCEEDED` 机制，不静默截断关键问题。
- 证据不足不是系统异常，不应标记为普通 `FAILED`。

## 可观测性

每次检索保存结构化摘要，建议增加 `qa_retrieval_run` 和 `qa_retrieval_evidence`：

```text
qa_retrieval_run
- message_id, attempt_no, query_fingerprint
- strategy, normalized_query, rewrite_reason
- candidate_count, selected_count, sufficiency_status
- missing_aspects, degraded_components
- started_at, completed_at, elapsed_ms

qa_retrieval_evidence
- retrieval_run_id, document_id, chunk_id
- document_validity, keyword_score, vector_score, rerank_score
- selection_status, exclusion_reason
```

不重复保存完整原文；使用现有 `documentId/chunkId` 追溯片段。用户界面本阶段不展示完整检索诊断面板，但后端数据必须可供后续阶段使用。

## 测试与验收

### 自动化测试

- 问题实体提取成功和规则降级；
- 标准号、条款号、全角符号、空格、OCR 断词归一化；
- 精确、关键词、向量和全文结果合并去重；
- 相邻章节和同表格上下文扩展；
- `SUFFICIENT`、`PARTIAL`、`INSUFFICIENT`、`CONFLICT`、`VALIDITY_UNKNOWN`、`RETRIEVAL_DEGRADED` 和 `TIMEOUT`；
- 查询指纹相同或候选集合相同时跳过重复检索；
- 最多两轮、最多一次改写和无后台重试；
- 有效/废止/未来/未知版本判定；
- Embedding、Reranker、改写模型失败时降级；
- 最终证据仍受阶段二 Token 预算控制；
- 权限范围不因改写而扩大；
- 用户文本框补充问题后生成独立新消息。

### 真实实机验收

至少 40 个真实案例，所有独立问题先新建会话，覆盖：

- 现有两份建筑施工 PDF 的明确条款、数值、定义、责任和流程问题；
- 标准号和条款号格式变体；
- 跨页、跨片段和表格证据；
- 多文档组合问题；
- 现行与历史文件冲突；
- 元数据不完整；
- 证据部分充分；
- 确实不存在依据的问题；
- 第一轮不足、第二轮补检成功；
- 改写无增益、候选重复和组件降级；
- 8K、16K 及接近预算上限的证据上下文；
- 文本框补充条件后重新提问；
- 权限和项目隔离；
- 检索超时、模型不可用和超限错误。

每题记录问题、预期关键点、实际答案、证据、检索轮次、状态、耗时和失败原因。不得使用 Mock 数据替代真实模型、真实知识库或真实数据库链路。

## 阶段边界与后续阶段

阶段三验收通过后，进入阶段四：多轮会话摘要、记忆和延伸问题。Excel/PPT 解析、多资料合规审查、报告增强、数据库问答强化、自定义审查字段、项目权限隔离、身份证水印识别和模型构建依据文档化继续按既定阶段单独设计和验收。

