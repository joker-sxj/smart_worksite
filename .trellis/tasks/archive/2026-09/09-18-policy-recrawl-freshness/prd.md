# 政策抓取时间与最新内容修复

## Goal

澄清政策时间语义、提示单篇来源边界并优先抓取较新的栏目链接

## Requirements

- 保持来源发布日期语义，补充首次入库与记录更新时间显示。
- 识别明显单篇来源并提示其不能发现后续文章。
- 栏目候选去重后按可解析 URL 日期优先选择，无法解析时保持原始顺序。

## Acceptance Criteria

- [x] 重抓同 URL 后发布日期和首次入库不变，记录更新时间刷新。
- [x] 单篇文章 URL 在配置或详情中显示边界提示。
- [x] 新日期候选在 max_articles 截断前优先，旧有排序与无日期链接测试通过。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
