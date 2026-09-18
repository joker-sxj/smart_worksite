# 审查政策知识库问题修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复合规审查恢复与结果可见性、政策资讯时间和最新内容发现、项目知识库文档分页，并完成真实部署验收。

**Architecture:** 复用现有审查记录列表、政策文章时间字段和知识文档分页接口。以纯函数承载前端选择/派生/分页状态规则，避免把页面生命周期逻辑直接写进测试；Python 爬虫仅调整候选链接稳定排序，不改变 robots 或 URL 去重契约。

**Tech Stack:** Vue 3 + TypeScript + Vitest、Python 3 + pytest、Spring Boot + MyBatis、PowerShell/SSH 部署。

---

### Task 1: 合规审查视图模型

**Files:**
- Create: `frontend/src/views/review/reviewResultViewModel.ts`
- Test: `frontend/src/views/review/reviewResultViewModel.spec.ts`

- [ ] 写失败测试：本地记录 ID 命中服务端记录时优先；本地 ID 无效时选择最新服务端记录；空列表返回 null；规则包含 `manualConfirmationRequired`、`validationError` 或失败且没有明确 issue 时派生待确认项；已有明确 issue 的规则不重复派生。
- [ ] 运行 `cd frontend; npm test -- reviewResultViewModel.spec.ts`，确认因模块缺失或行为缺失失败。
- [ ] 实现最小纯函数和导出类型，不访问网络或 localStorage。
- [ ] 重跑定向测试，确认通过。

### Task 2: 合规审查页面恢复与分层展示

**Files:**
- Modify: `frontend/src/api/review.ts`
- Modify: `frontend/src/views/review/ComplianceReviewView.vue`
- Test: `frontend/src/views/review/reviewPolling.spec.ts`

- [ ] 在页面恢复前请求当前项目最近记录，保留本地 ID 作为偏好并清理失效值。
- [ ] 增加最近记录选择入口；加载完整记录后仅对非终态记录创建轮询。
- [ ] 将问题区域拆成“明确问题”和“待人工确认”，后者展示规则、判定、置信度、原因和证据，不调用状态更新接口；增加可离开提示。
- [ ] 运行 `cd frontend; npm test -- reviewResultViewModel.spec.ts reviewPolling.spec.ts` 和 `npm run build`。
- [ ] 提交 `fix: restore review records and expose manual confirmations`。

### Task 3: 政策爬虫候选排序

**Files:**
- Modify: `python-ai-service/app/services/policy_crawler_service.py`
- Test: `python-ai-service/tests/test_policy_crawler.py`

- [ ] 写失败测试：可解析日期的新链接在 `max_articles` 截断前优先；同日期保持原顺序；无日期保持原顺序；规范化 URL 去重后再排序/截断。
- [ ] 运行 `python -m pytest python-ai-service/tests/test_policy_crawler.py -q`，确认新测试先失败。
- [ ] 实现稳定排序辅助函数并在 `crawl` 中于截断前调用。
- [ ] 重跑政策爬虫测试，确认全部通过。

### Task 4: 政策页面时间语义与来源边界

**Files:**
- Create: `frontend/src/views/policy/policyViewModel.ts`
- Test: `frontend/src/views/policy/policyViewModel.spec.ts`
- Modify: `frontend/src/views/policy/PolicyInfoView.vue`
- Modify: `frontend/src/api/types.ts`

- [ ] 写失败测试：文章 URL `/202609/...` 识别为单篇提示；栏目 URL 不提示；时间字段缺失显示 `未提取` 或 `-`，不使用当前时间。
- [ ] 运行 `cd frontend; npm test -- policyViewModel.spec.ts` 确认失败。
- [ ] 实现单篇提示、列表“发布日期/最近抓取”列、详情“发布日期/首次入库/最近抓取”字段和时间说明。
- [ ] 运行政策定向测试与前端构建。
- [ ] 提交 `fix: clarify policy dates and crawl boundaries`。

### Task 5: 知识库分页视图模型

**Files:**
- Create: `frontend/src/views/knowledge/knowledgeDocumentPaging.ts`
- Test: `frontend/src/views/knowledge/knowledgeDocumentPaging.spec.ts`

- [ ] 写失败测试：构造查询参数；筛选改变回到第一页；删除当前页最后记录时回退页码；自动刷新保留页码。
- [ ] 运行 `cd frontend; npm test -- knowledgeDocumentPaging.spec.ts` 确认失败。
- [ ] 实现纯分页状态转换函数并通过测试。

### Task 6: 知识库页面接入分页

**Files:**
- Modify: `frontend/src/views/knowledge/KnowledgeBaseView.vue`

- [ ] 让 `loadDocs` 传递 pageNo/pageSize/keyword/indexStatus，并保存返回 total。
- [ ] 接入关键字、状态筛选和分页控件；切换知识库/筛选回第一页；上传和删除按设计刷新或回退。
- [ ] 让解析状态刷新只处理当前页文档。
- [ ] 运行知识库定向测试、`npm run build` 和前端完整测试。
- [ ] 提交 `fix: paginate knowledge documents`。

### Task 7: 集成验证与 Trellis 检查

**Files:**
- Modify: `.trellis/tasks/09-18-review-policy-knowledge-remediation/*` 验收产物
- Create: `docs/superpowers/reports/2026-09-18-review-policy-knowledge-remediation-acceptance.md`

- [ ] 运行 Java 相关测试、Python 完整测试、前端完整测试和构建；运行 `git diff --check`。
- [ ] 执行 `.agents/skills/trellis-check` 要求的质量检查，记录失败项和修复结果。
- [ ] 推送精确验证提交，部署到 `xidian@172.18.12.6:/home/xidian/sjw/smart_worksite`。
- [ ] 通过真实 API 验证记录 121 恢复、政策时间字段/重复抓取、知识库多页分页；记录任务 ID、提交 SHA 和时间。
- [ ] 追加 Trellis 工作日志与验收报告，完成父任务提交和归档前检查。

---

## 回滚点

- 每个子任务单独提交，定向测试不通过不合并后续子任务。
- 部署前记录服务器当前 SHA；部署异常时只回滚到该精确 SHA，不执行破坏性数据库命令。
- 数据库没有迁移，时间字段和文章 URL 哈希契约保持兼容。
