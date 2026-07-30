# 智慧工地前端

Vue 3 + TypeScript + Vite + Pinia + Vue Router + Axios + Element Plus，Element Plus 通过自动按需导入减少首屏包体积。

## 技术栈

| 技术 | 用途 |
| --- | --- |
| Vue 3 | 前端框架 |
| TypeScript | 类型约束 |
| Vite | 开发服务和构建工具 |
| Pinia | 全局状态管理 |
| Vue Router | 路由和权限守卫 |
| Axios | HTTP请求 |
| Element Plus | UI组件库 |
| unplugin-auto-import / unplugin-vue-components | Element Plus按需导入 |
| @element-plus/icons-vue | 图标库 |

前端只调用 Java Spring Boot 后端接口，不直接调用 Python 智能算法服务、数据库、对象存储或向量库。智能体、RAG、OCR算法和文档解析等AI能力由 Python 服务实现，并由 Java 后端统一编排。

## 启动方式

```powershell
cd F:\xd_project\smart_worksite\frontend
npm install
npm run dev
```

默认访问：`http://localhost:5173`。

## 构建方式

```powershell
npm run build
```

构建产物输出到 `dist/`。

## 环境变量

开发环境配置在 `.env.development`：

```env
VITE_API_BASE_URL=/api
VITE_USE_MOCK=false
```

- `VITE_API_BASE_URL`：后端 API 基础地址，开发代理默认使用 `/api`。
- `VITE_USE_MOCK`：默认 `false`，请求真实 Java 后端；只有显式设为 `true` 时才允许 API service 使用本地 mock 数据。
- 模块级 `VITE_USE_xxx_MOCK` 默认也必须为 `false`，仅用于离线演示。
- `VITE_USE_POLICY_MOCK=true`：仅用于离线演示政策资讯模块；默认关闭，前端会调用 Java 后端 `/api/policy/**`，由 Java 后端调度 Python `/v1/policy/crawl` 完成真实网页抓取、入库和知识库索引。

## Mock 模式

mock 数据统一放在 `src/mocks/`，页面通过 `src/api/` service 间接获取数据，不在页面中拼接 URL。真实联调和交付验收不得依赖 mock；接口失败必须显示后端错误，不返回假成功或假空数据。

## 目录结构

```text
src/
  api/                 接口 service 和类型
  assets/              全局样式和静态资源
  components/common/   通用上传、表格、状态、JSON、下载等组件
  components/business/ 业务组件预留
  layouts/             主框架布局
  mocks/               本地 mock 数据
  router/              路由和权限守卫
  stores/              Pinia 状态
  utils/               request、requestId 等工具
  views/               登录、首页、智能问答、审查、报告、OCR、知识资产、运营中心、基础配置、错误页
```

## 页面布局与导航

前端导航按用户任务组织，不按后台表或技术模块平铺。当前左侧菜单分为：

- 工作入口：工作台。
- 智能应用：智能问答、合规审查、报告生成、OCR识别。
- 知识资产：项目知识库、政策资讯、数据源管理。
- 运营中心：任务中心、审计日志。
- 基础配置：项目管理、模板管理、用户管理、角色管理。

工作台首屏固定突出四个主操作：问知识/查数据、审文档、生成报告、识别材料；知识库、数据源、模板、任务作为支撑入口展示，避免用户一进入系统就面对大量一级菜单。左侧导航和顶部项目栏固定在应用壳内，页面内容区独立滚动，长菜单自身滚动，避免页面下滑后导航缺失。审查文件上传并入合规审查，不再作为独立菜单入口；合规审查页提供“上传审查模板”入口，跳转 `/templates?category=REVIEW&action=upload` 并打开审查模板上传弹窗；项目成员并入项目管理行内抽屉；旧 `/files` 地址重定向到 `/review`，旧 `/project/members` 地址重定向到 `/projects`。


## 功能清单对齐说明

本前端已按 `系统功能清单.xlsx` 的 11-14 项提供可验收入口：

| 清单项 | 前端入口 | 当前说明 |
| --- | --- | --- |
| 11 知识问答 | `/qa` | 支持自动路由、模型、知识库、数据库、混合模式，展示引用、路由和追问信息。 |
| 11.1 互联网政策资讯爬取 | `/policy` | 前端 Mock 演示政策源配置、爬取任务、资讯列表和入库状态；真实爬虫待后端政策模块实现。 |
| 11.2 本地知识库 | `/knowledge` | 支持项目隔离知识库、文档上传、解析和入库；PPT/Excel/CSV 可作为前端清单入口，后端解析失败会直接显示错误。 |
| 11.3 数据库问答 | `/datasources`、`/qa` | 数据源管理、连接测试、Schema 查看和数据库问答走 Java 后端接口。 |
| 11.4 智能路由上下文 | `/qa` | 默认自动路由，可展示后端返回的追问、引用和 trace 信息。 |
| 12 合规审查 | `/review`、`/templates` | 审查模板、文档上传、问题建议和 JSON 结果展示。 |
| 13 报告生成 | `/report`、`/templates` | 报告模板、报告任务、状态、详情、下载；数据来源配置先展示，真实填充依赖后端报告链路。 |
| 14 OCR识别 | `/ocr` | 身份证、车牌、发票、自定义字段入口、自动刷新进度和结果状态展示。 |

## History 路由部署说明

前端使用 `createWebHistory()`。生产部署时 Nginx 或静态资源服务器必须把未知前端路由回退到 `index.html`，否则刷新 `/qa`、`/report/10001` 会返回 404。

```nginx
location / {
  try_files $uri $uri/ /index.html;
}
```

## 后续联调说明

- 登录：`POST /api/auth/login`、`GET /api/auth/me`、退出登录接口。
- 项目：`GET /api/projects`，项目切换后刷新当前页面数据。
- 知识库：知识库列表、文档列表、上传文档、触发入库；文档仅在 `PENDING` 或 `FAILED` 状态显示可提交入库，`INDEXING` 和 `SUCCESS` 禁止重复提交。
- 问答：会话、消息、反馈接口；历史记录会把后端返回的 `question + answer` 还原为右侧用户消息和左侧 AI 回复，用户气泡靠右、AI 气泡靠左且使用更宽的阅读版面；会话列表和对话内容各自滚动，输入区固定在问答卡片底部；答案通过 `utils/qaMarkdown.ts` 做安全转义和轻量 Markdown 渲染，支持表格、列表、标题、代码块和加粗文本，并容错处理缺少标准分隔线的表格文本；发送后立即显示“AI 正在生成回答”占位反馈，发送中允许撤回到输入框重新编辑，已发出的旧请求返回后会被前端忽略；发送问题和重新生成答案使用 120 秒专用请求超时，其他普通接口继续使用全局 15 秒超时；发送问题前可选择模型、知识库、数据库或混合路由，数据库路由必须选择一个已启用数据源，知识库路由必须选择已启用知识库。
- 合规审查：只加载已启用审查模板，提交审查、查询审查结果；页面提供上传模板按钮，跳转模板中心并预筛选审查模板、打开上传弹窗；已完成审查的问题状态可按后端枚举更新为 `OPEN`、`PROCESSING`、`RESOLVED` 或 `IGNORED`。
- 模板中心：列表展示后端模板 ID，并通过 Java `GET /api/templates/{templateId}/preview` 获取文件 Blob；DOCX 在弹窗内还原文档版式，XLSX/CSV 以表格展示，TXT/MD 以文本展示，旧版 DOC/XLS 等无法安全解析的格式显示明确提示。报告模板操作列提供“模板变量”弹窗，通过 GET 接口回显变量名和描述，变量名只读，具备模板维护权限的用户可编辑全部非空描述并通过 PUT 接口统一保存。
- 报告：列表、详情、重新生成、下载 Word；新建报告只加载已启用报告模板，仅已完成报告允许下载，生成中报告禁止重复发起生成。
- OCR：提交识别后自动轮询记录详情和列表状态，识别类型使用后端 OCR 类型接口返回的中文名称并记住上一次选择；OCR 记录表格和字段表格使用局部滚动，避免长表格拖动整页；图片文件选择后完整本地预览，再次选择单文件会替换旧文件；打开历史详情时通过文件预览 URL 展示原图；页面展示可修订的结构化识别字段，不再展示独立识别进度卡片和原始 JSON 结果卡片。
- 数据源：列表、连接测试、Schema 查看和数据库问答为登录可见；新增、编辑、启用、停用、删除按钮按后端 `datasource:manage` 权限禁用，真实写入仍由后端 fail-fast 校验。
- 权限按钮：项目、文件、模板、知识库、问答、审查、报告、数据源等页面的写操作只在当前用户具备对应后端权限时展示或可点击；前端只做可见性和交互保护，后端权限校验仍是最终事实来源。
- 任务中心：提供唯一的任务列表、统计、详情、阶段日志、重试、取消入口；项目管理只保留 `/projects` 一个主入口，成员管理从项目行内“成员管理”打开，旧 `/project/manage` 和 `/project/members` 重定向到 `/projects`。
- 长任务：报告生成、OCR、知识库入库通过任务详情和阶段日志接口展示进度；任务操作按钮必须按状态机禁用非法操作，例如仅 `FAILED` 可重试，`PENDING`、`QUEUED`、`RUNNING`、`RETRYING` 可取消。
- 文件管理：只提供审查文档上传、下载、解析记录和解析内容入口；知识库文档上传必须走知识库页面，报告模板/审查模板上传必须走模板中心，报告结果只能由报告生成任务产生，避免重复入口和孤儿业务文件。
- 文件解析：仅 `SUCCESS` 解析记录允许查看内容，仅 `FAILED` 解析记录允许重试解析；模板上传限制为 DOCX、TXT、MD，避免上传后端变量解析不支持的模板文件。
- 数据质量：前端会对项目、模板、问答历史中的疑似历史乱码文本打标提示，但不静默修改或隐藏后端返回的数据。
- 下载：统一使用 `downloadFile`，可解析 Blob 中的 JSON 错误和 `Content-Disposition` 文件名。
