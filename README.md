# 智慧工地大模型应用系统

智慧工地大模型应用系统面向建筑工地管理场景，提供项目资料管理、知识库管理、知识问答、合规审查、报告生成、OCR 识别、任务编排、权限安全和审计追踪等能力。

本仓库当前包含 Java Spring Boot 后端主系统、Vue 3 + TypeScript 前端工程、Docker Compose 本地依赖环境，以及需求文档、架构设计文档、接口文档。

智能体、大模型、RAG、OCR 算法等 AI 能力由 Python 智能算法服务实现，Java 后端通过 REST API 调用，不在 Java 主系统中实现智能体核心逻辑。前端只调用 Java 后端 REST API，不直接访问 Python 服务、数据库、MinIO、向量库或 OCR 引擎。

## 技术栈

### 后端主系统

| 技术 | 版本/说明 | 用途 |
| --- | --- | --- |
| Java | 17 | 后端开发语言 |
| Spring Boot | 3.3.7 | 后端主框架 |
| Spring Web | Spring Boot Starter Web | REST API |
| Spring Security | JWT + 方法权限 | 登录认证、接口鉴权 |
| Spring Validation | Spring Boot Starter Validation | 参数校验 |
| Spring Data Redis | Spring Boot Starter Data Redis | Redis 访问、缓存、JWT 黑名单、登录失败锁定 |
| Spring Boot Actuator | health、info | 健康检查 |
| MyBatis | 3.0.4 starter + XML | 数据访问 |
| PageHelper | 2.1.0 | 分页查询 |
| MySQL Connector/J | runtime | MySQL 访问 |
| Flyway | flyway-core + flyway-mysql | 数据库迁移 |
| MinIO Java SDK | 8.5.12 | 对象存储访问 |
| Apache PDFBox | 2.0.31 | PDF 处理 |
| Apache POI | 5.2.5 | Word、Excel、PPT 处理 |
| Maven | 项目构建 | 依赖管理和打包 |

### 前端应用

| 技术 | 用途 |
| --- | --- |
| Vue 3 | 前端框架 |
| TypeScript | 类型约束 |
| Vite | 构建工具和开发服务 |
| Pinia | 状态管理 |
| Vue Router | 路由和权限守卫 |
| Axios | HTTP 请求 |
| Element Plus | UI 组件库 |
| @element-plus/icons-vue | 图标库 |

### Python 智能算法服务

Python 智能算法服务属于整体架构的一部分，位于 `python-ai-service/`。 Java 本地启动时会自动检测 `AI_PYTHON_BASE_URL` 指向的本机服务；如果未启动，会使用 `python-ai-service/.venv` 自动拉起，不需要再单独打开脚本。Docker 部署时 `deploy/docker-compose-env.yml` 会统一启动 `python-ai-service` 容器。

| 技术方向 | 用途 |
| --- | --- |
| 大模型调用 | 智能问答、合规审查、报告内容生成 |
| Agent 智能体 | 任务拆解、工具调用、多步骤推理和业务编排 |
| RAG 检索增强 | 项目知识库、政策标准库、行业资料库检索增强 |
| Embedding 向量化 | 文档切片向量化和语义检索 |
| OCR 识别 | 身份证、车牌、发票、合同和自定义字段识别 |
| 文档解析 | 内容抽取、版面理解、表格识别 |
| 报告变量生成 | Python 模型根据模板变量和材料生成报告内容 |

### 数据与基础设施

| 技术 | 版本/说明 | 用途 |
| --- | --- | --- |
| MySQL | 8.4 | 业务元数据、权限、任务、审计、文件元数据 |
| Redis | 7.2-alpine | 缓存、轻量队列、分布式锁、JWT 黑名单、登录失败锁定 |
| MinIO | RELEASE.2025-04-22T22-12-26Z | 文档、图片、模板、报告文件存储 |
| Docker Compose | 本地环境编排 | 启动 MySQL、Redis、MinIO |
| LOCAL / pgvector / Milvus | 三种 RAG Provider 均已实现；外部向量库需单独部署 | 知识库向量检索，由 Python 服务访问 |

## 系统边界

```text
Vue 3 + TypeScript 前端
        ↓ REST API
Java + Spring Boot 后端主系统
        ↓ REST API
Python 智能算法服务
        ↓
大模型 / Agent / RAG / OCR / 文档解析 / 报告变量生成 / 向量化
```

- Java 后端负责统一鉴权、项目隔离、业务编排、状态记录、文件保存、下载 URL、审计追踪和外部调用日志。
- Python 服务负责大模型、Agent、RAG、Embedding、OCR 和文档解析等智能算法能力。
- Qwen API Key 只允许进入 Python 服务运行环境；本地 Python 进程可使用 `python-ai-service/.env`，当前 Docker Compose 使用 `deploy/.env` 注入。Java 配置、SQL、文档和日志中不得写入密钥。

## 当前实现状态

### 已实现

后端：

- Spring Boot 工程骨架、统一响应、统一异常、请求 ID。
- Spring Security + JWT 登录、退出、当前用户信息、当前用户改密。
- 用户、角色、权限、项目成员管理基础接口。
- 项目列表、详情、创建、修改、启停、逻辑删除和项目级访问隔离。
- 文件上传、列表、详情、访问 URL、删除和项目级访问校验。
- 文件解析任务创建、记录查询、内容查询、重试和项目级访问校验。
- 模板上传、列表、详情、修改、启用、停用、删除和项目级访问校验。
- 报告模板上传前自动扫描并持久化 `{{ var_xx_xx }}` 变量、模板文件流预览、变量顺序查询，以及按模板文件新增或修改全部变量描述；审查模板上传不执行变量自动解析。
- 报告模板和审查模板兼容接口。
- 报告创建、列表、详情、逐变量状态查询、重新生成、下载 URL、版本记录、多知识库/多数据库逐变量 AI 路由生成和异步 Java DOCX 模板渲染链路。
- Java AI 适配层：模型调用、Agent 调用、RAG 检索/索引、数据库问答、路由、上下文准备、外部调用日志和项目级访问校验。
- 知识库基础管理、文档上传、索引任务创建、任务 outbox 投递、Worker 异步调用 Python RAG 索引和失败状态记录。知识库按 `PROJECT` 项目资料库和 `POLICY` 系统政策库隔离。
- 政策资讯源配置、真实爬取任务、文章入库与重建索引；每个项目自动维护一个系统政策库，不再依赖默认项目知识库。
- 任务管理接口：任务列表、详情、阶段日志、状态统计、失败任务重试、等待/运行中任务取消请求和项目级访问校验。
- 任务 outbox 基础投递：以 MySQL `task_outbox` 为事实源，按配置投递任务事件到 Redis 队列，并记录失败原因和重试时间。
- 任务 Worker 基础状态机：领取 `QUEUED` 任务、写入 worker 租约和心跳、按 owner 校验完成成功或失败；执行业务前校验项目仍为可写状态；Redis 队列坏消息会记录原因和 payload 摘要后拒绝，不 claim 任务。
- 报告创建、列表、详情、重新生成、下载 URL、版本记录和 Java DOCX 模板生成集成。
- OCR 识别后端接口：提交识别、列表、详情、重试、删除、字段修订、结果 JSON 查询和类型模板。
- Java AI 适配层：模型调用、Agent 调用、RAG 检索/索引、数据库问答、路由、上下文准备和外部调用日志。
- Python 智能算法服务：新增 `/v1/ocr/recognize`，封装 Qwen VL 完成身份证、车牌、发票和自定义字段 OCR 抽取。
- Redis 基础封装、MinIO 适配、Flyway 迁移、MyBatis XML、PageHelper 分页。

前端：

- Vue 3 + TypeScript + Vite 工程。
- Pinia、Vue Router、Axios 请求封装和权限路由。
- 登录页、首页工作台、知识库、知识问答、合规审查、报告、OCR、数据源、任务、审计页面。
- 项目管理页面内集成项目成员抽屉，另有用户管理、角色权限页面。
- 403、404 页面。
- 通用上传、表格、搜索、弹窗、状态、进度、JSON 查看、下载组件。
- 文件管理页只提供审查文档上传、下载和解析记录；知识库文档上传统一走知识库页面，模板上传统一走模板中心，报告结果统一由报告任务生成，避免重复功能入口。
- 知识问答页支持模型、知识库、数据库、混合路由选择；知识检索可按“项目资料”、“政策资讯”或“项目 + 政策”范围选择，默认仅使用项目资料。
- 报告生成页仅展示 `PROJECT` 项目资料库，后端同步拒绝使用系统政策库生成报告。
- 前端长任务与状态机操作会按后端允许状态禁用非法按钮：任务只在 `FAILED` 可重试、等待/运行状态可取消；文件解析仅成功可查看内容、失败可重试；报告仅完成可下载，生成中不可重复生成。

### 规划中

- 登录失败锁定、密码强度策略、登录审计、刷新令牌等完整安全策略。
- OCR 算法生产化和模型额度、模板字段准确率联调。
- 数据库问答历史、数据源细粒度权限管理和更多数据库类型的生产联调。
- 知识问答业务页面与 Java/Python AI 适配层联调完善。
- 合规审查 Python Agent 结果结构稳定性和生产联调完善。
- Python 智能算法服务生产化。
- Agent 工具注册、业务工具执行审计和多步骤任务编排完善。
- 生产部署脚本、监控告警和审计报表。

## 目录结构

```text
smart_worksite/
  deploy/                 本地依赖环境，MySQL、Redis、MinIO
  docs/                   需求文档、架构设计文档、接口文档
  frontend/               Vue 3 + TypeScript 前端工程
  python-ai-service/      Python 智能算法服务
  src/main/java/          Java 后端源码
  src/main/resources/     后端配置、Mapper XML、Flyway 脚本
  src/test/java/          后端测试
  pom.xml                 Maven 配置
  README.md               项目总览
```

后端主要包结构：

```text
com.xd.smartworksite
  common                  通用响应、异常、请求 ID、MyBatis、Redis、安全工具
  system                  系统探活
  auth                    登录认证、用户、角色、权限、项目成员管理
  project                 项目管理
  file                    文件管理和文件解析
  template                模板管理
  report                  报告生成、变量值持久化、知识库逐变量生成和DOCX模板渲染
  knowledge               知识库基础管理、文档生命周期和异步 RAG 索引任务
  datasource              数据源基础管理和数据库问答支撑
  qa                      Knowledge QA sessions, messages, references, feedback, and AI routing loop
  review                  Compliance review records, issues, status handling, and Python Agent review loop
  ocr                     OCR foundation tables; OCR business APIs are outside the current P0 backend scope
  task                    任务查询、统计、重试、取消和阶段日志
  audit                   审计和外部调用日志基础表
  ai                      Java AI 适配层，调用 Python 智能算法服务
```

## 本地启动

推荐使用仓库内的跨平台生命周期脚本启动完整项目。脚本会统一管理以下服务：

- Docker Compose：MySQL、Redis、MinIO、Python AI 服务
- 宿主机后台进程：Java Spring Boot 后端、Vue 前端
- 运行日志和 PID：统一写入已忽略的 `logs/` 目录；宿主机日志和 Docker 日志均默认限额轮转

### 启动前准备

请先安装并启动：

- Docker Desktop（Windows）或 Docker Engine + Compose v2（Linux）
- Java 17 或更高版本
- Maven
- Node.js 和 npm

完整项目必须在 `deploy/.env` 中配置有效的 `QWEN_API_KEY`。脚本不会输出密钥，也不会覆盖已经存在的 `deploy/.env`。

### Windows：复制后直接执行

首次启动：

```powershell
cd smart_worksite

# 首次运行时创建本地配置；已有 deploy/.env 时不会覆盖
if (-not (Test-Path .\deploy\.env)) {
    Copy-Item .\deploy\.env.example .\deploy\.env
}

# 打开配置文件，至少填写 QWEN_API_KEY
notepad .\deploy\.env

# 检查 Docker、Java、Maven、Node.js、npm 和配置，不启动服务
.\scripts\start-all.ps1 -Check

# 一键启动 MySQL、Redis、MinIO、Python AI、Java 后端和 Vue 前端
.\scripts\start-all.ps1

# 查看所有服务状态
.\scripts\status.ps1
```

后续日常启动只需：

```powershell
cd smart_worksite
.\scripts\start-all.ps1
```

停止完整项目并保留数据库、MinIO 等 Docker volumes：

```powershell
.\scripts\stop-all.ps1
```

如果 PowerShell 禁止执行本地脚本，可以使用：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\status.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-all.ps1
```

### Linux：复制后直接执行

首次启动：

```bash
cd smart_worksite

# 首次运行时创建本地配置；已有 deploy/.env 时不会覆盖
cp -n deploy/.env.example deploy/.env

# 使用你习惯的编辑器填写 QWEN_API_KEY
${EDITOR:-vi} deploy/.env

# 首次克隆后赋予脚本执行权限
chmod +x scripts/start-all.sh scripts/status.sh scripts/stop-all.sh

# 检查依赖和配置，不启动服务
./scripts/start-all.sh --check

# 一键启动完整项目
./scripts/start-all.sh

# 查看所有服务状态
./scripts/status.sh
```

后续日常启动只需：

```bash
cd smart_worksite
./scripts/start-all.sh
```

停止完整项目并保留数据库、MinIO 等 Docker volumes：

```bash
./scripts/stop-all.sh
```

### 脚本行为

| 脚本 | Windows | Linux | 说明 |
| --- | --- | --- | --- |
| 启动 | `scripts/start-all.ps1` | `scripts/start-all.sh` | 启动完整项目；已健康的服务不会重复启动 |
| 环境检查 | `start-all.ps1 -Check` | `start-all.sh --check` | 只检查依赖、Java 版本和 `deploy/.env` |
| 状态检查 | `scripts/status.ps1` | `scripts/status.sh` | 检查容器、PID、端口和健康接口；异常时返回非零退出码 |
| 停止 | `scripts/stop-all.ps1` | `scripts/stop-all.sh` | 停止项目进程和容器，但不删除 Docker volumes |

启动脚本具有幂等性：项目已经运行时可以再次执行，不会重复启动由脚本管理的 Java 或 Vue 进程。首次缺少 `deploy/.env` 时，脚本会从 `.env.example` 创建文件并停止，配置完成后再次运行即可。

### 默认地址和账号

| 服务 | 默认地址 |
| --- | --- |
| 前端 | `http://localhost:5173` |
| Java 后端 | `http://127.0.0.1:8080` |
| Java 健康检查 | `http://127.0.0.1:8080/actuator/health` |
| Python AI 健康检查 | `http://127.0.0.1:8015/v1/health` |
| MySQL | `127.0.0.1:13306` |
| Redis | `127.0.0.1:16379` |
| MinIO API | `http://127.0.0.1:19000` |
| MinIO 控制台 | `http://127.0.0.1:19001` |

端口以 `deploy/.env` 的实际配置为准。默认管理员账号为 `admin / admin123`，仅用于本地联调和演示；生产环境必须修改或禁用默认账号。

### 日志与状态排查

后台日志：

```text
logs/backend.out.log
logs/backend.err.log
logs/frontend.out.log
logs/frontend.err.log
```

日志不会无限增长。默认策略可在 `deploy/.env` 调整：

```env
# Java/Vue stdout、stderr 按本地自然日归档；单个分卷 10 MB，每个日志流每天最多 3 个归档
HOST_LOG_MAX_SIZE_MB=10
HOST_LOG_MAX_FILES=3
# 包含当天在内保留最近 30 个自然日，启动和跨日写入时自动清理更早归档
HOST_LOG_RETENTION_DAYS=30
# 项目所在文件系统低于 2 GB 可用空间时拒绝启动，避免数据库和构建继续写满磁盘
MIN_FREE_DISK_MB=2048

# Docker json-file 不支持按自然日清理；以容量兜底：单文件 10 MB，最多保留 30 个文件
DOCKER_LOG_MAX_SIZE=10m
DOCKER_LOG_MAX_FILES=30
# 默认关闭 Uvicorn 每请求访问日志；临时排查流量时才改为 true
AI_ACCESS_LOG=false
```

宿主机归档使用 `.YYYY-MM-DD`、`.YYYY-MM-DD.2` 等后缀，包含当天在内保留最近 30 个自然日；单日异常刷屏仍受单文件大小和每日分卷数限制。Java 自动拉起的本地 Python 服务输出会并入后端受限日志，不再追加独立的 `python-ai-service.log`。Docker `json-file` 驱动不支持精确按自然日删除，因此按默认每容器约 300 MB 容量封顶，作为磁盘安全兜底；修改 Docker 日志参数后必须重建容器才能生效，数据卷不会被删除：

```bash
./scripts/stop-all.sh
./scripts/start-all.sh

# 检查某个容器实际采用的日志策略
docker inspect -f '{{json .HostConfig.LogConfig}}' smart-worksite-python-ai-service
```

进程 PID 文件：

```text
logs/run/backend.pid
logs/run/frontend.pid
```

推荐按以下顺序排查启动问题：

```powershell
# Windows
.\scripts\start-all.ps1 -Check
.\scripts\status.ps1
Get-Content .\logs\backend.err.log -Tail 100
Get-Content .\logs\frontend.err.log -Tail 100
```

```bash
# Linux
./scripts/start-all.sh --check
./scripts/status.sh
tail -n 100 logs/backend.err.log
tail -n 100 logs/frontend.err.log
```

常见问题：

- 提示 `QWEN_API_KEY is empty`：编辑 `deploy/.env`，填写有效的 `QWEN_API_KEY` 后重新启动。
- 前端显示 502：先运行状态脚本，确认 Java 后端 `8080` 和 Python AI `8015` 均正常。
- 文件解析或问答失败：检查 `scripts/status.*` 输出、`logs/backend.err.log`，以及 Python AI 容器日志。
- 端口被占用：状态脚本会显示异常端口；停止冲突进程或修改 `deploy/.env` 后重启。
- 再次启动时提示已运行：这是正常的幂等保护，可直接访问前端或运行状态脚本确认。
- Linux 明明是 Java 17 却提示版本不足：新版脚本会跳过 `JAVA_TOOL_OPTIONS` 和共享内存警告后解析真正的 `version` 行；拉取更新后执行 `./scripts/start-all.sh --check`。
- 从旧版本升级后如存在 `python-ai-service/python-ai-service.log`：先执行 `./scripts/stop-all.sh`，再删除该历史日志；新版已将自动拉起的 Python 输出并入受限后端日志，不会继续生成此文件。
- 提示 `Insufficient disk space`：这是启动保护，不要继续强制启动。先运行 `df -h`、`du -xhd1 ~ | sort -h`、`docker system df -v` 找出占用；不要删除本项目的 MySQL、MinIO 数据卷。

### 手动故障排查启动

正常使用不需要执行以下命令。只有在排查脚本或单个服务问题时，才分别启动组件。

先启动 Docker Compose。该 Compose 已包含 Python AI 服务，不要再重复启动本地 `uvicorn`：

```powershell
cd deploy
docker compose -f docker-compose-env.yml --env-file .env up -d
docker compose -f docker-compose-env.yml --env-file .env ps
cd ..
```

Windows 手动启动 Java 前，需要先加载 `deploy/.env`：

```powershell
. .\scripts\lib\lifecycle.ps1
Import-DotEnv -Path .\deploy\.env
mvn spring-boot:run
```

Linux 手动启动 Java 前，需要先导出 `deploy/.env`：

```bash
set -a
source deploy/.env
set +a
mvn spring-boot:run
```

手动启动前端：

```bash
cd frontend
npm install
npm run dev
```

Java 后端通过 `AI_PYTHON_BASE_URL` 和 `AI_PYTHON_API_KEY` 调用 Python 服务。

## 当前接口

除 `/api/auth/login`、`/api/system/ping`、`/actuator/health`、`/actuator/info` 外，当前接口默认需要 `Authorization: Bearer <accessToken>`。

JWT 鉴权会回查当前用户状态；用户被停用或删除后，旧 token 不再注入认证上下文，后续请求按未登录处理。

### 系统

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/system/ping` | 系统探活 |
| GET | `/api/system/version` | Query version and service time |
| GET | `/api/system/runtime` | Query JVM, OS, and runtime status |
| GET | `/api/system/dependencies/health` | Query MySQL, Redis, and MinIO dependency health |
| GET | `/actuator/health` | 健康检查 |

### 认证与当前用户

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 登录并获取 JWT |
| POST | `/api/auth/logout` | 退出登录并拉黑当前 JWT |
| GET | `/api/auth/me` | 获取当前用户、角色、权限和默认项目 |
| PUT | `/api/auth/me/password` | 修改当前用户密码 |

### 用户、角色、权限

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/system/users` | 分页查询用户 |
| POST | `/api/system/users` | 创建用户 |
| GET | `/api/system/users/{userId}` | 查询用户详情 |
| PUT | `/api/system/users/{userId}` | 更新用户信息和角色 |
| PUT | `/api/system/users/{userId}/status?status=ENABLED|DISABLED` | 启用或停用用户 |
| PUT | `/api/system/users/{userId}/password` | 管理员重置用户密码 |
| GET | `/api/system/roles` | 查询角色列表 |
| POST | `/api/system/roles` | 创建角色 |
| PUT | `/api/system/roles/{roleId}` | 更新角色基础信息和权限 |
| PUT | `/api/system/roles/{roleId}/status` | 启用或停用角色，查询参数 `status=ENABLED|DISABLED`，内置角色受保护 |
| DELETE | `/api/system/roles/{roleId}` | 删除未被用户使用的非内置角色 |
| GET | `/api/system/roles/permissions` | 查询权限列表 |
| PUT | `/api/system/roles/{roleId}/permissions` | 更新角色权限 |

### 项目与项目成员

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/projects` | 分页查询项目列表 |
| POST | `/api/projects` | 创建项目 |
| GET | `/api/projects/{projectId}` | 查询项目详情 |
| PUT | `/api/projects/{projectId}` | 修改项目 |
| PUT | `/api/projects/{projectId}/status` | 更新项目状态：`ENABLED`、`DISABLED`、`ARCHIVED`，请求值大小写不敏感，后端统一保存为大写 |
| DELETE | `/api/projects/{projectId}` | 逻辑删除项目 |
| POST | `/api/projects/{projectId}/enable` | 启用项目 |
| POST | `/api/projects/{projectId}/disable` | 停用项目 |
| POST | `/api/projects/{projectId}/archive` | 归档项目 |
| GET | `/api/projects/{projectId}/settings` | 查询项目配置 |
| PUT | `/api/projects/{projectId}/settings` | 更新项目配置 |
| GET | `/api/projects/{projectId}/statistics` | 查询项目统计 |

项目状态写入规则：`DISABLED` 或 `ARCHIVED` 项目只允许读取和重新启用；创建/修改项目业务数据、成员、文件、模板、知识库、数据源、QA、审查、报告、任务重试/取消、AI 调用等写操作会直接返回冲突错误，不做静默兜底。

项目配置校验规则：`defaultKnowledgeBaseId` 必须指向当前项目已启用的 `PROJECT` 知识库；`policyKnowledgeBaseId` 由系统在政策爬取时自动创建或修复，普通项目配置更新不接受手工指定。默认报告模板必须存在、属于当前项目、类别为 `REPORT` 且处于 `ENABLED`；默认问答路由只允许 `AUTO`、`MODEL`、`KNOWLEDGE`、`DATABASE`、`MIXED`；默认导出格式只允许 `WORD` 或 `PDF`。
| GET | `/api/projects/{projectId}/members` | 查询项目成员 |
| POST | `/api/projects/{projectId}/members` | 添加项目成员 |
| PUT | `/api/projects/{projectId}/members/{userId}` | 修改项目成员角色 |
| DELETE | `/api/projects/{projectId}/members/{userId}` | 移除项目成员 |

### 文件

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/files` | 上传文件，`multipart/form-data` |
| GET | `/api/files` | 分页查询文件列表 |
| GET | `/api/files/{fileId}` | 查询文件详情 |
| GET | `/api/files/{fileId}/access-url?usage=DOWNLOAD\|PREVIEW` | 获取访问 URL |
| DELETE | `/api/files/{fileId}` | 删除文件 |

### 文件解析

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/files/{fileId}/parse` | 创建文件解析任务 |
| GET | `/api/files/{fileId}/parse-records` | 查询文件解析记录 |
| GET | `/api/files/{fileId}/parse-records/latest` | 查询最新解析记录 |
| GET | `/api/files/{fileId}/parse-records/latest-successful` | 查询最新成功的解析记录 |
| GET | `/api/file-parse-records/{recordId}` | 查询解析记录详情 |
| GET | `/api/file-parse-records/{recordId}/content` | 查询解析结果内容 |
| POST | `/api/file-parse-records/{recordId}/retry` | 重试解析 |

文件上传和解析任务创建必须在写入后读回到可追踪记录；读回失败时直接返回错误，已上传对象必须尽力清理，不允许返回内存对象假成功。

### 模板

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/templates` | 上传通用模板 |
| GET | `/api/templates` | 分页查询模板 |
| GET | `/api/templates/{templateId}` | 查询模板详情 |
| PUT | `/api/templates/{templateId}` | 修改模板元数据 |
| POST | `/api/templates/{templateId}/enable` | 启用模板 |
| POST | `/api/templates/{templateId}/disable` | 停用模板 |
| DELETE | `/api/templates/{templateId}` | 删除模板 |
| GET | `/api/templates/{templateId}/preview` | 通过 Java 后端获取模板预览文件流，不暴露 MinIO 地址 |
| GET | `/api/templates/{templateId}/variables` | 扫描 DOC、DOCX、XLS、XLSX、CSV、TXT、MD 中的 `{{ var_xx_xx }}` 变量并按首次出现顺序去重 |
| GET | `/api/templates/{templateId}/variables/descriptions` | 按模板变量顺序查询变量名和已有描述，未配置描述返回空字符串 |
| PUT | `/api/templates/{templateId}/variables/descriptions` | 对当前模板文件的全部变量描述和可选数据源白名单执行新增或修改 |
| POST | `/api/templates/report` | 上传报告模板 |
| POST | `/api/templates/review` | 上传审查模板 |
| POST | `/api/report/templates` | 上传报告模板兼容接口 |
| GET | `/api/report/templates` | 查询报告模板列表 |
| GET | `/api/report/templates/{templateId}/variables` | 报告模板变量兼容接口，委托统一 `{{ var_xx_xx }}` 解析能力 |
| POST | `/api/review/templates` | 上传审查模板兼容接口 |
| GET | `/api/review/templates` | 查询审查模板列表 |
| GET | `/api/review/templates/{templateId}` | 查询审查模板详情 |
| PUT | `/api/review/templates/{templateId}` | 修改审查模板元数据 |
| DELETE | `/api/review/templates/{templateId}` | 删除审查模板 |
| POST | `/api/review/templates/{templateId}/enable` | 启用审查模板 |
| POST | `/api/review/templates/{templateId}/disable` | 停用审查模板 |

模板变量解析规则：变量接口必须读取模板文件真实内容，只识别 `{{ var_xx_xx }}` 占位符，当前支持 DOC、DOCX、XLS、XLSX、CSV、TXT、MD，不支持 PDF；合法模板没有变量时返回空列表，模板文件缺失、跨项目不一致、格式损坏、格式不支持或对象存储读取失败时直接返回错误，不允许用空列表隐藏解析失败。

模板写入规则：报告模板上传在写入 MinIO 前自动扫描真实文件变量，并在文件、模板记录生成 ID 后将变量以空描述写入 `template_variable_description`；解析或变量持久化失败时上传失败，数据库写入回滚并清理本次 MinIO 对象。审查模板不执行自动变量解析。模板上传创建后必须读回持久化记录再返回成功；模板修改、启用、停用和删除必须检查数据库影响行数，记录不存在或状态已变化时直接返回冲突错误，不允许静默成功。

### 报告

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/reports` | 使用多个已启用 `PROJECT` 知识库和/或多个已启用数据源创建报告任务；两类来源至少选择一项，返回 `PENDING` 和 `taskId` |
| GET | `/api/reports` | 分页查询报告列表 |
| GET | `/api/reports/{reportId}` | 查询报告详情 |
| GET | `/api/reports/{reportId}/variables` | 按模板顺序查询变量描述、生成值、状态和错误 |
| POST | `/api/reports/{reportId}/regenerate` | 重新生成报告 |
| GET | `/api/reports/{reportId}/download-file?format=WORD` | 经 Java 后端流式下载 Word 报告，适合 Windows 浏览器访问 Linux 服务器部署 |
| GET | `/api/reports/{reportId}/download?format=WORD` | 兼容接口：获取 MinIO 预签名 Word 报告下载 URL |

报告生成规则：创建时可多选当前项目已启用的 `PROJECT` 知识库和已启用数据库数据源，两类来源至少选择一项；旧字段 `knowledgeBaseId` 仍兼容。`POLICY` 系统政策库会被拒绝。模板必须包含 `{{ var_xx_xx }}` 变量且每个变量都已配置非空描述；变量可配置 `dataSourceIds` 白名单，留空表示允许 AI 在报告所选数据源中自动选择。Worker 按变量调用 AI 路由决定知识检索、只读数据库查询或混合生成，并仅执行路由要求且属于变量快照的数据源；路由不可用时安全回退为混合。数据库 SQL 会按数据源方言生成，由 Java 安全校验并以只读方式执行；可修复 SQL 错误默认最多进行 4 次 SQL 生成/修复尝试，可通过 `AI_DATABASE_QUERY_MAX_ATTEMPTS` 调整。连接、认证和超时错误不会触发 SQL 修正重试。各变量不共享上下文且不写入普通问答会话历史。变量值和状态实时保存到 `report_variable_value`；单变量失败会使报告失败，任务重试时保留成功值并只补失败或未处理变量。资料为空时允许模型生成通用内容，但不得伪造具体项目数据。报告列表 `status` 查询只允许 `DRAFT`、`PENDING`、`PROCESSING`、`COMPLETED`、`FAILED`、`ARCHIVED`、`DELETED`。

Report write rule: report generation must check affected rows for report-task linking, task status, processing, success, failed, and version file binding. A zero-row update is a conflict and must not be reported as completed generation.

P0 write confirmation addendum: project creates/updates/status/settings, file-object inserts, file-parse-record inserts, template file/template inserts and file business-ID binding, report config/report/task/output-file/version inserts, and review-record inserts must check affected rows or generated IDs and read back records where the API returns persisted data. Missing effects are conflicts or system errors, not successful operations.

### 任务

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/tasks` | 分页查询当前用户可访问项目内的任务 |
| GET | `/api/tasks/statistics` | 查询任务状态统计和待处理数量 |
| GET | `/api/tasks/{taskId}` | 查询任务详情 |
| GET | `/api/tasks/{taskId}/stages` | 查询任务阶段日志 |
| POST | `/api/tasks/{taskId}/retry` | 重试失败且未超过重试次数的任务 |
| POST | `/api/tasks/{taskId}/cancel` | 取消等待中的任务，或对运行中任务写入取消请求 |

任务 outbox 调度和 Worker 默认关闭，避免本地未启动 Redis 时影响后端启动。需要投递并消费 Redis 异步任务时同时设置：

```properties
TASK_OUTBOX_DISPATCHER_ENABLED=true
TASK_OUTBOX_DISPATCHER_BATCH_SIZE=20
TASK_OUTBOX_DISPATCHER_FIXED_DELAY_MS=5000
TASK_WORKER_ENABLED=true
TASK_WORKER_ID=smart-worksite-worker
```

Task write rule: task state transitions, stage logs, and task outbox events must confirm affected rows or generated IDs. Retry/cancel/worker/outbox operations fail with conflict when status records, stage traces, or durable outbox failure states cannot be persisted.

Auth write rule: user, password, role, role-permission, project-member, and last-login writes must check affected rows. A zero-row write is treated as conflict and must not be reported as successful account or permission management.

### 知识库

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/projects/{projectId}/knowledge-bases` | 创建项目知识库 |
| GET | `/api/projects/{projectId}/knowledge-bases` | 分页查询项目知识库 |
| GET | `/api/knowledge-bases/{knowledgeBaseId}` | 查询知识库详情 |
| PUT | `/api/knowledge-bases/{knowledgeBaseId}` | 修改知识库元数据 |
| POST | `/api/knowledge-bases/{knowledgeBaseId}/enable` | 启用知识库 |
| POST | `/api/knowledge-bases/{knowledgeBaseId}/disable` | 停用知识库 |
| DELETE | `/api/knowledge-bases/{knowledgeBaseId}` | 删除知识库 |
| POST | `/api/knowledge-bases/{knowledgeBaseId}/documents` | 上传知识库文档，创建待入库文档记录 |
| GET | `/api/knowledge-bases/{knowledgeBaseId}/documents` | 分页查询知识库文档 |
| GET | `/api/knowledge-documents/{documentId}` | 查询知识库文档详情 |
| DELETE | `/api/knowledge-documents/{documentId}` | 删除知识库文档 |
| POST | `/api/knowledge-documents/{documentId}/index` | 创建知识库文档入库任务，仅允许 `PENDING`、`FAILED` 文档提交；返回 `INDEXING` 与 `taskId`，由任务 outbox/Worker 异步调用 Python RAG 索引 |

Knowledge write rule: knowledge-base updates must check affected rows; document uploads must verify generated IDs and read back persisted records before success; `INDEXING`, `SUCCESS`, and `FAILED` indexing status writes must check affected rows, and failure-state persistence failures must surface conflict errors with the original error retained.

知识库隔离规则：

- 知识库类型存储在 `knowledge_base.knowledge_base_type`。`PROJECT` 是用户管理的项目资料库，支持创建、修改、启停、删除和文档上传；旧数据在 Flyway `V19` 中自动回填为 `PROJECT`。
- `POLICY` 是每个项目至多一个的系统政策库，首次执行政策爬取时自动创建，名称为“政策资讯库”、领域为 `POLICY_CRAWLER`，并写入项目配置 `policyKnowledgeBaseId`。
- `POLICY` 库由系统管理：普通知识库接口不允许修改、启停、删除或手工上传文档。
- 政策文章成功写入当前项目政策库后，系统会按 `POLICY_ARTICLE` 来源标识清理该文章在其他知识库中的旧向量，避免项目资料检索命中政策爬虫内容。

### 政策资讯

政策源、爬取任务和政策文章查询需要 `knowledge:view`；创建、修改、删除政策源以及创建爬取任务需要 `policy:manage`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/policy/sources` | 分页查询当前项目的政策源 |
| POST | `/api/policy/sources` | 创建政策源 |
| PUT | `/api/policy/sources/{sourceId}` | 修改政策源 |
| DELETE | `/api/policy/sources/{sourceId}` | 删除政策源 |
| POST | `/api/policy/crawl-tasks` | 创建真实政策爬取任务 |
| GET | `/api/policy/crawl-tasks` | 分页查询爬取任务及索引进度 |
| GET | `/api/policy/articles` | 分页查询已爬取政策文章及入库状态 |

爬取任务不使用 `defaultKnowledgeBaseId`。系统会校验并修复 `policyKnowledgeBaseId`，必要时自动重新启用或创建系统政策库；文章只在政策库索引成功并完成旧向量清理后才标记入库成功。

### 数据源

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/data-sources` | 创建数据源配置 |
| GET | `/api/data-sources` | 分页查询数据源 |
| GET | `/api/data-sources/{dataSourceId}` | 查询数据源详情 |
| POST | `/api/data-sources/{dataSourceId}/test` | Test data source connectivity with real JDBC |
| GET | `/api/data-sources/{dataSourceId}/schema` | Inspect data source schema with real JDBC |
| PUT | `/api/data-sources/{dataSourceId}` | 编辑数据源配置 |
| POST | `/api/data-sources/{dataSourceId}/enable` | 启用数据源 |
| POST | `/api/data-sources/{dataSourceId}/disable` | 停用数据源 |
| DELETE | `/api/data-sources/{dataSourceId}` | 删除数据源 |

数据源密码使用 AES-GCM 存储，`AI_DATA_SOURCE_PASSWORD_KEY` 可覆盖本地开发默认 Key；生产环境必须显式配置独立 Key，长度为 16、24 或 32 字节，或使用 `base64:` 前缀。
数据源写入规则：创建后必须读回持久化记录；修改、启用、停用和删除必须检查数据库影响行数，记录不存在或已变更时直接返回错误，不允许静默成功。

### AI 适配接口

以下接口通过 Java AI 适配层统一调用 Python 智能算法服务。除全局登录鉴权外，项目相关请求仍会执行项目访问和项目状态校验。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/ai/model/invoke` | 调用大模型 |
| POST | `/api/ai/agent/invoke` | 调用 Agent 智能体 |
| POST | `/api/ai/knowledge/search` | 执行 RAG 知识检索 |
| POST | `/api/ai/knowledge/index` | 执行 RAG 文档索引 |
| POST | `/api/ai/database/query` | 执行数据库问答 |
| POST | `/api/ai/route` | 获取问答路由决策 |
| POST | `/api/ai/context/prepare` | 准备模型调用上下文 |
| GET | `/api/ai/external-call-logs` | 分页查询 AI 外部调用日志 |

数据库问答和报告变量生成会对可修复 SQL 错误进行有限自动修正，默认最多 4 次 SQL 生成/修复尝试，可通过 `AI_DATABASE_QUERY_MAX_ATTEMPTS` 调整。修复范围包括 MySQL `DISTINCT` + `ORDER BY` 规则、本地安全校验发现的多语句 SQL、语法错误和字段错误；数据库认证、连接失败等非 SQL 问题不会重试。

### QA

QA read APIs require `qa:view`; create/update/archive/send/regenerate/feedback APIs require `qa:manage`.

QA 提问时，`knowledgeBaseIds` 必须存在、属于当前会话项目且处于 `ENABLED`；`dataSourceIds` 必须存在、属于当前会话项目且处于 `ENABLED`。跨项目、停用或不存在的引用会在调用 AI 前直接失败，避免把其他项目资料或数据源传给模型。前端将范围映射为具体知识库 ID：`PROJECT` 仅项目资料、`POLICY` 仅政策资讯、`ALL` 同时检索两类知识库。

QA 消息写入规则：问题消息创建后必须持有可读 ID；AI 返回后写入答案、引用和状态时必须检查数据库影响行数，写入失败直接返回冲突错误，不允许把未持久化的 AI 答案报告为成功。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/qa/sessions` | Create QA session; default title is used when blank |
| GET | `/api/qa/sessions` | List QA sessions |
| GET | `/api/qa/sessions/{sessionId}` | Get QA session detail |
| PUT | `/api/qa/sessions/{sessionId}` | Update QA session title |
| DELETE | `/api/qa/sessions/{sessionId}` | Archive QA session |
| POST | `/api/qa/sessions/{sessionId}/messages` | Send question through Java AI adapter |
| GET | `/api/qa/sessions/{sessionId}/messages` | List QA messages |
| POST | `/api/qa/sessions/{sessionId}/messages/{messageId}/regenerate` | Regenerate answer |
| GET | `/api/qa/messages/{messageId}` | Get QA message detail |
| GET | `/api/qa/messages/{messageId}/references` | List answer references |
| POST | `/api/qa/messages/{messageId}/feedback` | Submit answer feedback |

### OCR

当前 OCR Controller 统一要求 `ocr:view` 权限，包括提交识别、字段修订、重试和删除操作。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/ocr/records` | 上传文件并提交 OCR 识别 |
| GET | `/api/ocr/records` | 分页查询 OCR 记录 |
| GET | `/api/ocr/records/{recordId}` | 查询 OCR 记录详情 |
| PUT | `/api/ocr/records/{recordId}/fields` | 修订 OCR 识别字段 |
| POST | `/api/ocr/records/{recordId}/retry` | 重试 OCR 识别 |
| DELETE | `/api/ocr/records/{recordId}` | 删除 OCR 记录 |
| GET | `/api/ocr/records/{recordId}/download` | 获取 OCR 结果下载信息 |
| GET | `/api/ocr/types` | 查询支持的 OCR 类型和字段模板 |

### Review

Review read APIs require `review:view`; submit/retry/delete/archive/update-issue APIs require `review:manage`.

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/review/records` | Submit review file and create review record via Python Agent |
| GET | `/api/review/records` | List review records |
| GET | `/api/review/records/{recordId}` | Get review record detail and issues |
| POST | `/api/review/records/{recordId}/retry` | Retry failed review |
| DELETE | `/api/review/records/{recordId}` | Delete review record |
| POST | `/api/review/records/{recordId}/archive` | Archive review record |
| PUT | `/api/review/records/{recordId}/issues/{issueId}` | Update review issue status |

审查执行失败写入规则：Python Agent 返回失败、空结果或无效 JSON 时，审查记录必须标记为 `FAILED` 并记录错误信息；如果失败状态无法落库，必须直接返回冲突错误，不允许丢失可观测性。
审查创建写入规则：提交审查记录后必须校验生成 ID 并读回持久化记录；读回失败时不调用 Python Agent，直接返回系统错误。

### 审计

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/audit/logs` | Query operation audit logs |
| GET | `/api/audit/external-call-logs` | Query external service call logs |

### 报告生成

报告创建接口不直接阻塞生成文件。开启任务 outbox 调度和 Worker 后，Worker 会领取 `REPORT_GENERATION` 任务，确认项目、所选 `PROJECT` 知识库和数据源仍可用，按 `sort_no` 逐个根据变量描述执行 AI 路由、RAG 检索和/或只读数据库查询，全部成功后在 Java 中渲染 Word 文件。

模板占位符只支持 `{{ var_xx_xx }}`。同名变量只生成一次，正文、表格、页眉和页脚中的同名占位符使用同一个值。

### Python AI Service

```env
AI_PYTHON_BASE_URL=http://127.0.0.1:8015
AI_PYTHON_API_KEY=
AI_PYTHON_CONNECT_TIMEOUT_MS=5000
AI_PYTHON_READ_TIMEOUT_MS=120000
AI_PYTHON_RETRY_COUNT=1
```

Qwen 密钥必须配置在 `python-ai-service/.env` 或运行环境变量中，不得配置到 Java。

## Python AI 能力配置

知识库文档入库由 Java 创建 `KNOWLEDGE_INDEXING` 异步任务并通过任务 outbox/Worker 执行。Worker 执行前必须确认项目仍为 `ENABLED`；只读取已成功解析的文件内容并调用 Python RAG 索引接口；解析内容未就绪、内容为空或 Python 索引失败时，文档状态写为 `FAILED` 并记录错误，不做静默兜底或假成功。

Knowledge indexing write rule: `INDEXING`, `SUCCESS`, and `FAILED` status updates must check affected rows. If persisting `FAILED` also affects zero rows, the worker must fail visibly instead of losing the original Python/parse error.

Python AI 服务支持 RAG 索引和检索。文档会在 Python 服务中切片、向量化、存入向量提供方并 rerank。常用配置：

```env
EMBEDDING_PROVIDER=QWEN
QWEN_EMBEDDING_MODEL=text-embedding-v4
QWEN_EMBEDDING_DIMENSIONS=1024
QWEN_EMBEDDING_BATCH_SIZE=10
RERANK_PROVIDER=QWEN
QWEN_RERANK_BASE_URL=https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
QWEN_RERANK_MODEL=qwen3-rerank
QWEN_RERANK_API_STYLE=LEGACY
RAG_PROVIDER=LOCAL
RAG_DATA_DIR=data/rag
PGVECTOR_DSN=
PGVECTOR_TABLE=smart_worksite_chunks
MILVUS_URI=http://127.0.0.1:19530
MILVUS_TOKEN=
MILVUS_COLLECTION=smart_worksite_chunks
```

`EMBEDDING_PROVIDER=QWEN` 是生产路径，`LOCAL_HASH` 仅用于离线测试和无模型额度开发。Java 不直接访问向量数据库。

`RAG_PROVIDER` 控制 Python 服务使用的向量存储实现：

- `LOCAL`：默认本地开发选项，使用 `RAG_DATA_DIR` 下的 JSON/文件存储，不需要额外向量数据库。
- `PGVECTOR`：使用已实现的 PostgreSQL/pgvector Provider，必须配置可访问的 `PGVECTOR_DSN`；当前 Docker Compose 不包含 pgvector 服务。
- `MILVUS`：使用已实现的 Milvus Provider，必须部署 Milvus 并配置 `MILVUS_URI`、`MILVUS_TOKEN` 等参数；当前 Docker Compose 不包含 Milvus 服务。

## 文档

| 文档 | 说明 |
| --- | --- |
| `docs/智慧工地大模型应用系统-需求文档.md` | 系统需求文档，包含详细需求 |
| `docs/智慧工地大模型应用系统-架构设计文档.md` | 架构设计文档 |
| `docs/智慧工地大模型应用系统-接口文档.md` | 接口设计文档 |
| `docs/任务分工.xlsx` | 任务分工表 |
| `智慧工地前端UI风格指南.md` | 前端 UI 风格指南 |

注意：`docs` 描述的是完整目标系统，当前代码是阶段性实现。判断已实现接口时，以当前 Controller 和本 README 的当前接口列表为准。

## 测试

后端改动后运行：

```powershell
mvn clean test
```

当前 P0 后端测试门禁包含：基础安全、Flyway 迁移连续性、MyBatis JSON 参数规则、项目隔离、任务状态机、任务 outbox、知识库索引、QA、合规审查、报告生成、模板/文件上传 fail-fast 行为。新增或调整后端核心链路时，必须保证这些测试继续通过。

前端改动后运行：

```powershell
cd frontend
npm run build
```

当前 P0 验证还要求：非 OCR Controller 路由在 README 和接口文档中可追踪；前端非 OCR API 调用能匹配 Java 后端路由；文档编码检查通过；并确认 OCR 后端目录没有被修改。

Frontend report-template upload calls must send explicit `templateName` and `templateType` with the file; the backend does not derive fallback metadata from filenames.
