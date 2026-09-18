# 审查政策知识库问题修复

## Goal

统筹修复合规审查恢复与展示、政策抓取时间语义及最新内容发现、知识库文档分页，并完成真实部署验收

## Background

真实部署数据和用户提供的《问题整理》确认三个独立缺陷：合规审查只展示顶层 `issues` 且返回页面依赖本地记录 ID；政策页面把来源发布日期和系统抓取时间混在同一理解中，单篇来源不会发现后续文章；知识库后端已有分页，前端未传页码也未展示分页。

## Requirements

1. 合规审查从服务端恢复最近记录，本地记录只作为偏好；明确问题与待人工确认规则结果分开展示，原始 JSON 保留。
2. 政策资讯明确 `publishDate`、`createdAt`、`updatedAt` 的语义，列表和详情显示相关时间；单篇 URL 给出能力边界提示；栏目抓取在上限前优先较新候选且不绕过 robots。
3. 项目知识库接入已有文档服务端分页、关键字和入库状态筛选，上传、删除及自动刷新保持页码一致。

## Constraints

- 不把抓取时间写入来源发布日期。
- 不把没有可信问题明细的模型结论伪装成可处理问题，不生成虚假的 issueId。
- 不新增数据库迁移，不绕过 robots、登录限制或反爬机制。
- H100 只作为功能验证，不能外推双 A6000 性能。

## Acceptance Criteria

- [x] 真实审查记录 121 返回页面后可恢复；`issues=[]` 但存在 `validationError` 或需人工确认的规则时，页面显示独立待人工确认区域。
- [x] 服务端没有本地记录 ID 或本地 ID 失效时，页面打开项目最新审查记录；非终态记录继续轮询，终态记录不无休止轮询。
- [x] 真实政策文章重复抓取后 `publishDate` 不变，`createdAt` 不变，`updatedAt` 刷新；单篇来源显示边界提示，栏目来源能处理日期较新的候选。
- [x] 知识库超过一页时可翻页并显示总数；筛选、上传、删除和自动刷新符合分页规则。
- [x] 受影响前端单测、Python 爬虫测试、前端构建和完整回归通过；部署提交与验证提交一致。

## Work Packages

- `09-18-review-result-persistence`
- `09-18-policy-recrawl-freshness`
- `09-18-knowledge-document-pagination`

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
