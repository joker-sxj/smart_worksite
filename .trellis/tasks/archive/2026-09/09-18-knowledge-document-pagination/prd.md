# 知识库文档分页

## Goal

接入已有服务端分页、筛选和自动刷新页码保持行为

## Requirements

- 前端传递并保存 pageNo、pageSize、keyword、indexStatus，展示服务端 total 与分页控件。
- 切换知识库或筛选回到第一页；上传后第一页刷新；删除当前页最后一条时回退上一页；自动刷新不重置页码。

## Acceptance Criteria

- [x] 多页文档可翻页并显示正确总数。
- [x] 搜索和状态筛选只影响当前结果集并回到第一页。
- [x] 解析/入库轮询只刷新当前页，删除最后一条后页码有效。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
