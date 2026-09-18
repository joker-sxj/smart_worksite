# 合规审查结果恢复与分层展示

## Goal

实现服务端审查记录恢复、最近记录选择和明确问题与待人工确认项分层展示

## Requirements

- 查询并恢复当前项目最近审查记录，localStorage 失效时回退服务端最新记录。
- 将明确问题和待人工确认规则结果分区展示；待人工确认项不可调用问题状态更新接口。
- 离开页面不影响后端任务，返回非终态记录时恢复轮询。

## Acceptance Criteria

- [x] 记录 121 的规则矛盾在页面可见且原始 JSON 仍可查看。
- [x] 本地记录缺失或失效时仍显示服务端最新记录。
- [x] 恢复终态记录不创建轮询定时器，恢复非终态记录会继续轮询。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
