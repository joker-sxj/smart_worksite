智慧工地大模型应用系统后端架构设计文档
版本：v1.0
状态：工程骨架
架构形态：模块化单体架构
技术栈：Java 17 / Spring Boot 3.3.x / MyBatis / PageHelper / MySQL / Redis / MinIO / Flyway
适用范围：智慧工地知识问答、合规审查、报告生成、OCR 识别、项目知识库和任务编排等后端开发

---

## 1. 文档概述

### 1.1 文档目的

本文档用于说明智慧工地大模型应用系统后端工程的系统定位、架构约束、模块划分、分层规则、开发环境、数据库迁移和协作规范。

本文档面向后端开发人员、前端联调人员、测试人员和后续接手项目的智能体，用于指导详细设计、接口开发、数据库变更、模块扩展和本地部署。

### 1.2 系统定位

智慧工地大模型应用系统是面向建筑工地管理场景的智能应用平台。

系统围绕工地项目资料、政策标准、业务数据库、现场文档图片、对象文件和外部智能能力，提供知识问答、合规审查、报告生成、OCR 识别、异步任务追踪和操作审计等能力。

当前仓库是后端多人协作工程骨架，重点解决以下问题：

1. 团队成员 clone 后可以快速启动依赖和后端服务。
2. 后端模块具有统一目录结构和分层边界。
3. 接口返回、异常处理、分页、requestId 和日志格式保持一致。
4. 数据库结构通过 Flyway 管理，避免手动改库。
5. 通过 `project` 示例模块给后续业务模块提供参考。

### 1.3 架构目标

1. 支持智慧工地多项目、多角色、多数据源隔离管理。
2. 支持后续扩展知识问答、合规审查、报告生成、OCR 识别等业务模块。
3. 支持 MySQL 存储业务元数据、Redis 承载缓存和轻量队列、MinIO 存储对象文件。
4. 支持通过外部服务接入大模型、RAG、OCR、向量库等智能能力。
5. 支持异步任务、阶段日志、失败重试和全过程留痕。
6. 支持统一接口响应、统一错误码、统一链路追踪和统一日志规范。
7. 保证项目具备可维护性、可扩展性和多人协作一致性。

---

## 2. 架构约束与设计原则

### 2.1 架构约束

1. 后端一期采用模块化单体架构，不拆微服务。
2. 后端使用 Java 17、Spring Boot 3.3.x、MyBatis 和 PageHelper。
3. 不使用 MyBatis Plus。
4. 数据库结构变更必须使用 Flyway。
5. 本地开发依赖通过 Docker Compose 启动 MySQL、Redis 和 MinIO。
6. 业务表默认逻辑删除，SQL 默认过滤 `deleted = 0`。
7. 涉及项目隔离的数据表必须包含 `project_id`。
8. Controller 禁止直接调用 Mapper、Redis、MinIO 和外部服务。
9. 跨模块禁止直接调用对方 Mapper。
10. 大模型、RAG、OCR 和向量库能力通过外部服务适配，不直接写死到业务模块。

### 2.2 设计原则

#### 2.2.1 模块化单体

系统在一个 Spring Boot 应用内按照业务领域划分模块。

每个模块按需包含 Controller、Application Service、Domain、Repository、Mapper、DTO 和 Infra。模块之间通过应用服务或 Facade 协作，避免跨模块直接访问对方 Mapper 和数据库细节。

#### 2.2.2 分层边界清晰

Controller 处理 HTTP 和参数校验，Application Service 负责编排用例和事务，Domain 表达业务规则，Repository 封装数据访问，Mapper 负责 SQL，Infra 封装技术适配。

#### 2.2.3 外部能力适配隔离

MinIO、Redis、外部大模型、外部知识库、OCR 服务等能力统一通过 Infra 或后续 Adapter 封装。业务模块不直接感知外部服务 URL、鉴权方式、请求格式和重试细节。

#### 2.2.4 数据库版本化

所有数据库结构变更必须进入 Flyway 脚本。已经被团队使用的迁移脚本禁止修改，后续调整通过新增版本脚本完成。

#### 2.2.5 全链路可追踪

每个 HTTP 请求生成或继承 `X-Request-Id`，并写入响应头、响应体和日志 MDC。后续任务、外部调用和审计日志均应携带 requestId。

---

## 3. 总体架构设计

### 3.1 系统总体架构图

```text
┌──────────────────────────────────────────────────────────────┐
│                         前端应用层                            │
│  Vue3 Web 管理端 / 报告预览页 / 工地现场终端 / 管理后台        │
└───────────────────────────────┬──────────────────────────────┘
                                │ HTTPS / REST
                                ▼
┌──────────────────────────────────────────────────────────────┐
│              智慧工地后端 Spring Boot 模块化单体应用           │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐    │
│  │ 用户权限模块 │  │ 项目管理模块 │  │ 文件管理模块     │    │
│  └──────────────┘  └──────────────┘  └──────────────────┘    │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐    │
│  │ 知识库模块   │  │ 问答模块     │  │ 合规审查模块     │    │
│  └──────────────┘  └──────────────┘  └──────────────────┘    │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐    │
│  │ 报告模块     │  │ OCR模块      │  │ 任务编排模块     │    │
│  └──────────────┘  └──────────────┘  └──────────────────┘    │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐                         │
│  │ 审计日志模块 │  │ 系统配置模块 │                         │
│  └──────────────┘  └──────────────┘                         │
└───────────────┬────────────────┬────────────────┬───────────┘
                │                │                │
                ▼                ▼                ▼
        ┌────────────┐   ┌────────────┐   ┌──────────────┐
        │   MySQL    │   │   Redis    │   │    MinIO     │
        │ 业务元数据 │   │ 缓存/队列  │   │ 文件对象存储 │
        └────────────┘   └────────────┘   └──────────────┘
                │
                ▼
┌──────────────────────────────────────────────────────────────┐
│                         外部能力层                            │
│  大模型服务 / RAG知识库 / OCR服务 / 向量库 / 业务数据库        │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 架构说明

系统采用单体应用部署，后端主体为一个 Spring Boot 应用。应用内部按照业务领域拆分模块，各模块保持清晰边界。

本地开发依赖由 `deploy` 目录下的 Docker Compose 启动；生产部署可按实际环境另行设计。

---

## 4. 应用分层设计

### 4.1 分层结构

```text
Controller 层
    ↓
Application Service 应用服务层
    ↓
Domain 领域层
    ↓
Repository / Mapper 数据访问层
    ↓
MySQL / Redis / MinIO / 外部 HTTP 服务
```

### 4.2 Controller 层

Controller 层负责对外提供 HTTP 接口，不直接编写复杂业务逻辑。

主要职责：

1. 接收前端请求。
2. 完成参数校验。
3. 获取请求上下文。
4. 调用应用服务。
5. 返回统一响应结构。
6. 不直接调用 Mapper。
7. 不直接调用 Redis、MinIO 和外部服务。

### 4.3 Application Service 层

Application Service 层负责完整业务用例编排，是默认事务边界。

例如“创建报告生成任务”后续会涉及：

1. 校验用户和项目权限。
2. 校验模板和参考文件是否有效。
3. 创建报告记录。
4. 创建任务记录。
5. 写入 Redis 队列。
6. 返回任务 ID。

### 4.4 Domain 领域层

Domain 层负责表达核心业务概念和业务规则。

典型领域对象包括：

1. 项目状态。
2. 文件业务类型。
3. 任务状态。
4. 任务阶段。
5. 报告状态。
6. OCR 类型。

领域层不直接依赖 Spring MVC、MyBatis、MinIO SDK、HTTP Client 等技术框架。

### 4.5 Repository / Mapper 层

Repository 提供面向业务的持久化接口，Mapper 负责具体 SQL 实现。

约束如下：

1. Controller 禁止直接调用 Mapper。
2. 跨模块禁止直接调用对方 Mapper。
3. 写操作必须经过 Application Service。
4. 复杂查询可以放入 XML。
5. SQL 默认过滤 `deleted = 0`。

### 4.6 Infra 层

Infra 层负责封装外部系统和技术组件。

包括：

1. MinIO 文件上传、删除、预签名 URL。
2. Redis 缓存、分布式锁、轻量队列。
3. 外部大模型 HTTP API。
4. 外部知识库 HTTP API。
5. OCR 服务 HTTP API。
6. 业务数据库只读查询适配。

---

## 5. 后端模块设计

### 5.1 包结构设计

```text
com.xd.smartworksite
├── SmartWorksiteApplication.java
│
├── common
│   ├── result
│   ├── exception
│   ├── config
│   └── redis
│
├── system
│   └── controller
│
├── auth
│
├── project
│   ├── controller
│   ├── application
│   ├── domain
│   ├── repository
│   ├── mapper
│   └── dto
│
├── file
│   └── infra
│
├── knowledge
├── datasource
├── qa
├── review
├── report
├── ocr
├── task
└── audit
```

### 5.2 模块职责说明

| 模块 | 职责 |
| --- | --- |
| common | 统一响应、异常处理、requestId、MyBatis 配置、Redis 封装 |
| system | 系统探活、健康检查、运行状态 |
| auth | 用户、角色、权限、登录认证 |
| project | 工地项目、项目成员、项目级数据隔离 |
| file | 文件对象元数据、MinIO 文件访问 |
| knowledge | 项目知识库、知识文档、知识切片 |
| datasource | 业务数据源配置和连接测试 |
| qa | 知识问答会话和消息 |
| review | 合规审查模板和审查记录 |
| report | 报告模板、报告记录和报告版本 |
| ocr | OCR 识别记录和字段结果 |
| task | 异步任务、状态机、阶段日志 |
| audit | 操作审计、外部调用日志 |

### 5.3 模块调用规则

1. Controller 只能调用本模块 Application Service。
2. Application Service 可以调用其他模块暴露的 Application Service 或 Facade。
3. 不允许跨模块直接调用 Mapper。
4. 不允许跨模块直接操作对方数据库表。
5. Domain 层不依赖 Controller、Mapper、HTTP Client。
6. Infra 层负责封装技术细节，业务逻辑不得下沉到 Infra。
7. common 模块只放通用能力，不放业务逻辑。

---

## 6. 当前已实现能力

### 6.1 通用能力

1. `ApiResponse<T>`：统一响应结构。
2. `PageResult<T>`：统一分页结构。
3. `BusinessException`：业务异常。
4. `GlobalExceptionHandler`：全局异常处理。
5. `RequestIdFilter`：请求链路 ID。
6. `MyBatisConfig`：Mapper 扫描配置。
7. Redis 缓存、锁、队列轻量封装。
8. MinIO 文件存储适配接口和默认实现。

### 6.2 示例接口

| 接口 | 说明 |
| --- | --- |
| `GET /api/system/ping` | 系统探活 |
| `GET /actuator/health` | Spring Boot 健康检查 |
| `GET /api/projects` | 分页查询项目列表 |
| `GET /api/projects/{projectId}` | 查询项目详情 |

### 6.3 示例模块

`project` 模块已打通以下链路：

```text
ProjectController
    ↓
ProjectApplicationService
    ↓
ProjectRepository
    ↓
MyBatisProjectRepository
    ↓
ProjectMapper
    ↓
ProjectMapper.xml
    ↓
MySQL
```

---

## 7. 数据架构设计

### 7.1 数据分类

| 数据类型 | 说明 |
| --- | --- |
| 权限数据 | 用户、角色、权限、项目成员 |
| 项目数据 | 工地项目、项目配置 |
| 文件数据 | 文件元数据、对象存储路径、文件哈希 |
| 知识库数据 | 知识库、知识文档、后续知识切片 |
| 任务数据 | 异步任务、任务阶段日志 |
| 审计数据 | 操作审计、外部调用日志 |
| 配置数据 | 系统参数、任务重试、文件限制等配置 |

### 7.2 当前基础表

Flyway 当前基线脚本为：

```text
src/main/resources/db/migration/V1__init_schema.sql
```

当前基础表包括：

1. `user_account`
2. `role`
3. `permission`
4. `user_role`
5. `role_permission`
6. `project`
7. `project_member`
8. `file_object`
9. `knowledge_base`
10. `knowledge_document`
11. `data_source`
12. `generate_task`
13. `task_stage_log`
14. `audit_log`
15. `system_config`
16. `external_call_log`

### 7.3 数据库规则

1. 业务表统一包含 `id`、`created_at`、`updated_at`、`created_by`、`updated_by`、`deleted`。
2. 涉及项目隔离的数据表必须包含 `project_id`。
3. 状态和类型字段使用 `varchar`。
4. JSON 扩展字段使用 MySQL `json`。
5. 金额字段使用 `decimal`。
6. 大文本使用 `text` 或 `longtext`。
7. 已被团队使用的 Flyway 脚本禁止修改，后续调整追加新版本。

---

## 8. 本地开发环境

### 8.1 启动依赖

```powershell
cd deploy
copy .env.example .env
docker compose -f docker-compose-env.yml --env-file .env up -d
```

### 8.2 依赖服务

| 服务 | 地址 |
| --- | --- |
| MySQL | `localhost:3306` |
| Redis | `localhost:6379` |
| MinIO API | `http://localhost:9000` |
| MinIO Console | `http://localhost:9001` |

### 8.3 启动后端

```powershell
mvn spring-boot:run
```

### 8.4 常用命令

```powershell
mvn test
mvn spring-boot:run
mvn clean package
```

---

## 9. 接口规范

### 9.1 统一响应结构

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "requestId": "REQ202607030001",
  "timestamp": "2026-07-03T10:00:00+08:00"
}
```

### 9.2 分页响应结构

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "pageNo": 1,
    "pageSize": 20,
    "total": 100,
    "records": []
  },
  "requestId": "REQ202607030001",
  "timestamp": "2026-07-03T10:00:00+08:00"
}
```

### 9.3 错误码

| code | 含义 |
| --- | --- |
| 0 | 成功 |
| 40000 | 参数错误 |
| 40100 | 未认证 |
| 40300 | 无权限 |
| 40400 | 资源不存在 |
| 40900 | 状态冲突 |
| 42900 | 请求过频 |
| 50000 | 系统异常 |
| 50200 | 外部服务异常 |
| 50300 | 服务不可用 |

---

## 10. 文件存储设计

### 10.1 MinIO 规划

本地开发默认 bucket：

```text
smart-worksite
```

`deploy` 启动时会通过 `minio-init` 自动创建 bucket，并保持私有访问。

### 10.2 文件访问控制

1. 文件不直接暴露真实 MinIO 地址。
2. 后端根据权限生成临时预签名 URL。
3. 预签名 URL 设置有效期。
4. 下载、删除等关键操作后续应写入审计日志。
5. 删除文件时优先逻辑删除元数据，物理文件可由清理任务处理。

---

## 11. 异步任务设计

一期可使用 Redis List 作为轻量任务队列。

推荐流程：

```text
用户提交耗时任务
    ↓
写入 MySQL generate_task
    ↓
写入 Redis 队列
    ↓
后台 Worker 消费任务
    ↓
执行任务阶段
    ↓
写入 task_stage_log
    ↓
更新 generate_task 状态
```

当前工程已提供任务表和 Redis 队列封装，具体 Worker 可由后续任务模块实现。

---

## 12. 可观测性设计

### 12.1 requestId

每次请求由 `RequestIdFilter` 处理：

1. 请求头有 `X-Request-Id` 时沿用。
2. 请求头没有时自动生成。
3. 写入 MDC。
4. 写入响应头。
5. 写入统一响应体。

### 12.2 日志

接口访问日志记录：

1. HTTP 方法。
2. 请求路径。
3. 响应状态码。
4. 接口耗时。
5. requestId。

错误日志记录内部异常堆栈，但接口响应不暴露堆栈。

---

## 13. 模块治理规范

### 13.1 包依赖规范

1. common 可以被所有模块依赖。
2. 各业务模块之间不得直接访问对方 Mapper。
3. 各业务模块之间通过 Application Service 或 Facade 交互。
4. Domain 层不得依赖 Controller、Mapper、Infra。
5. Infra 层负责封装技术细节，业务逻辑不得下沉到 Infra。

### 13.2 代码规范

1. Controller 只做参数接收、校验和响应封装。
2. Application Service 负责编排业务流程。
3. Domain 负责表达业务规则。
4. Repository 负责封装数据访问。
5. Mapper 只负责 SQL 映射。
6. DTO 请求对象命名为 `XxxRequest` 或 `XxxCommand`。
7. DTO 响应对象命名为 `XxxResponse`。
8. 所有关键操作后续应记录操作日志。

### 13.3 配置规范

1. 数据库、Redis、MinIO 等连接信息通过环境变量覆盖。
2. 不同环境可使用不同 Spring Profile。
3. 密钥和生产地址禁止写入代码仓库。
4. Redis key 前缀统一在 `RedisKeys` 中管理。

---

## 14. 总结

本后端工程采用模块化单体架构，在单个 Spring Boot 应用内按照领域模块拆分，当前已具备多人协作开发的基础能力。

该架构具备以下特点：

1. 开发和部署成本低，适合项目一期快速落地。
2. 模块边界清晰，避免形成混乱单体。
3. 数据库结构由 Flyway 管理，适合多人协作。
4. MySQL、Redis、MinIO 形成基础数据、缓存队列和文件支撑。
5. 统一响应、统一异常、requestId 和日志规范便于前后端联调与排查问题。
6. 通过 `project` 示例模块为后续业务模块提供开发参考。
7. 大模型、RAG、OCR 等能力可在后续通过外部服务适配接入。

因此，本工程可以作为智慧工地大模型应用系统后端一期开发的基础框架。
