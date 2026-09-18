# 技术设计

复用 `/api/review/records` 和 `/api/review/records/{id}`。新增前端纯函数选择恢复记录和派生待人工确认项；页面将 localStorage 降级为选择偏好，服务端记录为事实来源。明确问题继续走现有更新接口，待确认规则只读展示。
