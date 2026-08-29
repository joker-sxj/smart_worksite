# 智慧工地统一 Token 预算与超限保护设计

## 状态

- 日期：2026-08-29
- 阶段：第二阶段 A
- 目标分支：`main`
- 生产基线：双 NVIDIA RTX A6000 48GB，本地模型，32K 目标档与 16K 稳定回退档

## 目标

为普通模型问答和知识库问答建立统一、可配置、可观测的 Token 预算边界，取代当前按字符数或固定消息条数控制上下文的方式。任何发送给本地模型的请求都必须在调用前完成预算规划；系统优先保留当前问题、系统约束和高价值证据，按完整对话轮次裁剪旧历史，并为输出和模型模板开销预留空间。无法安全装入模型窗口的请求必须明确失败，禁止静默截断。

本阶段提供后续动态检索、多轮摘要、合规审查和报告编排可复用的预算组件，但只把普通模型问答和知识库问答接入生产链路。

## 范围

本阶段实现：

- 使用与实际本地模型匹配的 tokenizer 统计聊天消息 Token 数。
- 当 tokenizer 本地文件暂不可用时，使用保守估算器并暴露 `ESTIMATED` 计数模式；不得把估算值伪装成精确值。
- 在 Python 智能服务内建立单一 `ContextBudgetPlanner`。
- 支持 32K 和 16K Profile，通过现有 `CHAT_MAX_MODEL_LEN` 获取模型窗口上限。
- 在普通模型问答和知识库问答调用模型前进行预算规划。
- 历史消息按完整用户/助手轮次裁剪，不产生孤立助手消息。
- 知识库证据按相关度顺序装入剩余预算；单条证据过长时按安全边界截取并标记。
- 为模型输出、聊天模板和安全余量保留显式预算。
- 返回非敏感 `contextUsage` 诊断信息，并写入现有问答响应的 `usage`，便于实机验收。
- 预算不足时返回明确的业务错误类型和原因。
- 保持项目、知识库和会话权限边界不变。

本阶段不实现：

- 根据问题复杂度动态调整检索候选数量。
- 检索查询改写、HyDE 或多跳检索。
- 会话摘要、长期记忆或跨会话记忆。
- 延伸问题生成和前端交互。
- 数据库 Schema/结果压缩。
- 合规审查和报告任务的章节化预算编排。
- 前端新增预算配置页面。

这些能力分别属于第二阶段 B、C、D 或后续业务阶段。

## 已确认的基本事实

1. 客户生产目标是双 RTX A6000 48GB，本地模型推理，不使用公网模型 Token。
2. 生产模型 Profile 已定义 32K 目标档和 16K 回退档。
3. 当前 Java 问答链路固定读取最近 10 条成功消息，没有按 Token 预算筛选。
4. 当前 `/v1/context/prepare` 使用 Python `len(content)` 统计字符，不等于模型 Token。
5. 当前 RAG 最终使用固定 `topK`，知识证据和历史消息没有共享统一预算。
6. 本地 OpenAI 兼容服务会在请求超出模型窗口时失败；应用必须在调用前阻止可预测的超限请求。
7. Java 继续负责权限、会话和业务状态，Python 负责模型、RAG 和上下文编排。

## 设计原则

### 单一预算真相源

所有 Token 计算和裁剪决策在 Python 智能服务完成。Java 不复制 tokenizer 逻辑，也不根据字符数二次裁剪。Java只传递业务上下文并保存 Python 返回的预算诊断。

### Token 而非字符

精确模式使用本地模型 tokenizer。Tokenizer 从本地模型目录或明确配置的 tokenizer 路径加载，启动时不允许为了加载 tokenizer 自动访问公网。

如果运行环境没有可用 tokenizer，保守估算器按文本类型计算上界，并在结果中返回：

```json
{
  "countMode": "ESTIMATED",
  "tokenizer": "conservative-v1"
}
```

估算器必须偏向高估，避免请求在应用侧通过、到模型侧才超限。

### 不静默丢失当前请求

以下内容为硬保留项：

1. 安全和业务系统提示词。
2. 当前用户问题。
3. 输出预算。
4. 聊天模板开销和安全余量。

如果硬保留项本身超过模型窗口，返回 `CONTEXT_BUDGET_EXCEEDED`。不得截短当前问题后继续生成。

### 历史以完整轮次裁剪

历史上下文按时间从近到远选择，但以完整对话轮次为单位：

- 用户问题与对应助手回答共同保留或共同删除。
- 最后一个只有用户消息的未完成轮次不进入历史。
- 不产生开头为孤立 `assistant` 的上下文。
- 被裁剪的轮次数和消息数写入诊断信息，不记录消息正文。

### 证据优先级与完整性

知识库证据已按检索相关性排序。预算规划器按顺序选择：

1. 优先装入完整证据。
2. 相同 `chunkId` 或内容哈希的重复证据只保留一次。
3. 单条证据大于剩余证据预算时，可在自然段或句子边界截取一次，并标记 `truncated=true`。
4. 截取后不足以提供可读证据时跳过，不填充无意义碎片。
5. 引用元数据、文档标题、页码或表格位置必须随被选证据保留。

本阶段不改变 RAG 的候选生成和排序算法，只控制最终送入模型的证据集合。

## 预算模型

模型窗口记为 `W`：

```text
W = system + currentQuestion + history + evidence + templateOverhead + outputReserve + safetyReserve
```

配置项：

```text
CHAT_MAX_MODEL_LEN              模型窗口，必须大于 0
CONTEXT_OUTPUT_RESERVE_TOKENS   普通问答输出预留，默认 min(4096, W / 4)
CONTEXT_SAFETY_RESERVE_TOKENS   安全余量，默认 max(512, W / 32)
CONTEXT_TEMPLATE_OVERHEAD_TOKENS 聊天模板保守开销，默认 256
CONTEXT_HISTORY_BUDGET_RATIO    可分配输入中历史最大比例，默认 0.30
CONTEXT_EVIDENCE_BUDGET_RATIO   可分配输入中证据目标比例，默认 0.70
CONTEXT_TOKENIZER_PATH          可选，本地 tokenizer 路径
CONTEXT_REQUIRE_EXACT_TOKENIZER 是否强制精确 tokenizer，默认 false
```

比例只用于划定历史和证据的初始上限。某一侧未使用的预算可让给另一侧，但硬保留项、输出预留和安全余量不可借用。

### 32K 默认档

在 `W=32768` 时：

- 输出预留默认 4096。
- 安全余量默认 1024。
- 模板开销默认 256。
- 剩余空间用于系统提示、当前问题、历史和证据。

### 16K 回退档

在 `W=16384` 时：

- 输出预留默认 4096，但允许 Profile 显式设为 3072。
- 安全余量默认 512。
- 模板开销默认 256。
- 使用同一算法，不维护另一套业务代码。

## 组件设计

### Python：TokenCounter

新建独立 Token 计数组件，职责：

- 对单段文本计数。
- 对 OpenAI Chat 消息计数。
- 返回计数模式和 tokenizer 标识。
- 缓存 tokenizer 实例。
- 绝不下载远程模型文件。

优先使用本地 Hugging Face tokenizer；依赖以固定版本加入 Python requirements。测试使用仓库内极小 tokenizer fixture 或确定性 fake counter，不依赖网络和生产模型。

### Python：ContextBudgetPlanner

输入：

```json
{
  "systemPrompt": "...",
  "currentQuestion": "...",
  "historyMessages": [],
  "evidenceItems": [],
  "modelContextLimit": 32768,
  "requestedOutputTokens": 4096
}
```

输出：

```json
{
  "contextMessages": [],
  "evidenceItems": [],
  "modelParameters": {"max_tokens": 4096},
  "usage": {
    "contextUsage": {
      "modelContextLimit": 32768,
      "estimatedInputTokens": 6214,
      "systemTokens": 326,
      "questionTokens": 42,
      "historyTokens": 1130,
      "evidenceTokens": 4450,
      "templateOverheadTokens": 256,
      "outputReserveTokens": 4096,
      "safetyReserveTokens": 1024,
      "selectedHistoryMessages": 4,
      "droppedHistoryMessages": 8,
      "selectedEvidenceItems": 5,
      "droppedEvidenceItems": 7,
      "truncatedEvidenceItems": 1,
      "countMode": "EXACT",
      "tokenizer": "Qwen3.8-27B"
    }
  }
}
```

### Python：模型调用入口

`ModelService` 在调用 `QwenClient.chat()` 前调用规划器。规划后的：

- 系统提示词
- 历史消息
- 当前问题
- `max_tokens`

构成最终模型请求。

知识库问答不能再先把全部证据拼成一个无限增长的 Java 字符串。Java 需要把检索引用以结构化字段传入模型请求，Python 规划器选择证据后再构造最终提示词。

为控制本阶段范围，原有 `prompt` 字段继续兼容；新增可选 `evidenceItems`。没有证据项的普通模型调用按历史预算执行。

### Java：问答编排

Java 保留：

- 会话读取与权限校验。
- 知识库、数据源权限校验。
- RAG 搜索调用。
- 问答任务和消息状态。
- 引用持久化。

Java 调整：

- 不再固定只读取最后 10 条；读取当前会话成功消息的有界集合，默认最多 100 条，作为候选交给 Python。
- 知识证据以结构化 `evidenceItems` 传递。
- 保存并返回 Python 的 `contextUsage`。
- 将 `CONTEXT_BUDGET_EXCEEDED` 映射为用户可理解的失败原因。

100 条是数据库读取防护上限，不是模型上下文上限；最终选择由 Python Token 预算决定。

## API 与兼容性

### ModelInvokeRequest 扩展

新增可选字段：

```json
{
  "evidenceItems": [
    {
      "content": "...",
      "title": "...",
      "sourceId": "82",
      "chunkId": "...",
      "score": 0.83,
      "metadata": {}
    }
  ]
}
```

旧调用方不传该字段时保持兼容。

### ModelInvokeResponse

复用现有 `usage` Map 返回 `contextUsage`，避免本阶段扩大前端 API。前端现有答案显示行为不变。

### 错误

新增 Python 业务错误：

```text
CONTEXT_BUDGET_EXCEEDED
```

错误信息只包含计数和限制，不包含问题、历史或证据正文。

## 安全与隐私

- `contextUsage` 只记录数量、模式、配置档位和截取计数。
- 日志禁止输出完整 Prompt、历史正文、证据正文、数据库密码、Token、身份证号和图片数据。
- 项目与知识库权限仍由 Java 服务端重新校验。
- Python 不信任前端，也不直接接收前端请求。
- Tokenizer 路径必须为本地路径；LOCAL_ONLY 模式不允许自动下载。

## 失败与降级行为

| 场景 | 行为 |
| --- | --- |
| `CHAT_MAX_MODEL_LEN<=0` | 启动/健康检查失败，禁止用猜测窗口运行 |
| 精确 tokenizer 可用 | 使用 `EXACT` 模式 |
| 精确 tokenizer 不可用且未强制 | 使用保守 `ESTIMATED` 模式并暴露状态 |
| 精确 tokenizer 不可用且强制 | 服务依赖状态 DOWN，模型请求失败 |
| 硬保留项超限 | `CONTEXT_BUDGET_EXCEEDED` |
| 历史超限 | 按完整轮次从旧到新裁剪 |
| 证据超限 | 去重、按排序选择、必要时截取单条证据 |
| 本地模型仍返回 context length error | 映射为预算漂移错误，记录非敏感计数并失败，不自动重试更小隐式请求 |

## 测试策略

所有生产行为遵循测试先行。自动化测试不得调用公网服务，不使用为答案预设的 Mock 文档数据来证明业务准确率；组件单元测试可以使用确定性计数器隔离 tokenizer，端到端验收必须使用真实本地模型和真实入库资料。

### Python 单元与集成测试

至少覆盖：

1. 中文、英文、数字、代码和 Markdown 表格计数。
2. 精确与估算模式标识。
3. 2K、8K、16K、24K、32K预算边界。
4. 系统提示和当前问题硬保留。
5. 当前问题本身超限。
6. 输出预留导致可用输入不足。
7. 完整历史轮次选择。
8. 孤立助手消息过滤。
9. 重复证据去重。
10. 单条长证据自然边界截取。
11. 表格证据保留标题和位置元数据。
12. 证据与历史竞争预算。
13. 16K/32K同算法不同配置。
14. `max_tokens` 与输出预留一致。
15. 日志与错误不包含正文。

### Java 测试

至少覆盖：

1. 读取超过10条但不超过100条历史候选。
2. 只传递成功消息。
3. 新建会话不带旧会话历史。
4. RAG引用转换为结构化证据项。
5. Python usage 原样保存和返回。
6. 上下文超限错误映射。
7. 项目与知识库越权仍被拒绝。
8. 异步问答失败状态和错误原因可查询。

### 真实实机验收

使用当前远程 Linux 服务、Chrome 页面和已入库的两份真实 PDF：

- `09_建筑施工噪声排放标准_GB12523-2025.pdf`
- `10_建筑施工特种作业人员管理规定_2025.pdf`

构造不少于30个真实场景，其中至少包括：

- 10个单轮知识问答，覆盖精确条款、日期、限值、资格条件和否定性问题。
- 8个多轮追问，验证相关历史保留和无关旧历史淘汰；本阶段不要求摘要。
- 4个长问题或长粘贴输入，覆盖约2K、8K、16K、24K级别。
- 4个证据竞争场景，覆盖多条款、多页和两文档。
- 2个接近Profile上限场景。
- 2个明确超限场景，验证可理解失败且模型服务无500/OOM。

每个独立场景在 Chrome 中新建会话。多轮场景只在该场景内部继续追问，结束后重新新建会话。验收同时检查：

- 浏览器可见答案和来源。
- Java、Python和本地模型日志。
- `contextUsage` 实际值。
- 模型服务无 OOM、重启、context length 500或静默截断。
- Python、Java和前端全量测试。

真实验收结果输出为带时间戳的 JSON/Markdown 报告，记录问题、预期要点、实际答案、来源、上下文计数、通过/失败和失败原因；不得记录秘密配置。

## 验收标准

本阶段通过必须同时满足：

1. 所有发送给模型的普通问答和知识问答都经过统一预算规划器。
2. 16K和32K Profile均有自动化边界测试。
3. 当前问题不被静默截短。
4. 历史按完整轮次裁剪。
5. 知识证据按预算选择且保留引用元数据。
6. 超限返回明确错误，不造成模型OOM或容器重启。
7. 响应中可以观察非敏感 `contextUsage`。
8. 既有20题知识库回归准确率不得下降。
9. 新增不少于30个真实实机场景全部达到预期；如存在模型非确定性，必须给出逐题证据判定而不是仅看字符串相等。
10. Python、Java和前端全量自动化测试通过。
11. LOCAL_ONLY策略保持生效，无公网模型调用。
12. 代码中不存在针对题目、答案、文件名或文档ID的生产硬编码。

## 推送与验收门

- 代码完成后先在本地和Linux服务器完成自动化与实机验收。
- 测试失败时不推送。
- 验收通过后提交到 `joker-sxj/smart_worksite` 的 `main` 分支。
- 不提交服务器上的用户备份文件。
- 本阶段用户验收通过后才进入第二阶段 B：动态检索和证据预算感知召回。
