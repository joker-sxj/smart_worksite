# 数据源模块接口文档

本文档描述 `datasource` 模块当前已实现的数据源配置、连接测试和库表结构检查接口。数据源用于数据库问答等场景，前端不能直接连接数据库。

所有接口都需要：

```http
Authorization: Bearer <accessToken>
```

统一响应结构：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "requestId": "xxx",
  "timestamp": "2026-07-11T16:00:00+08:00"
}
```

## 前置配置

本地开发内置默认加密 Key；生产环境创建或更新数据源密码前必须配置独立 Key：

```env
AI_DATA_SOURCE_PASSWORD_KEY=0123456789abcdef0123456789abcdef
```

Key 长度必须是 16、24 或 32 字节，也可以使用 `base64:` 前缀。API 响应不会返回密码明文或密文。

## 接口总览

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| `POST` | `/api/data-sources` | 创建数据源 | `datasource:manage` |
| `GET` | `/api/data-sources` | 分页查询数据源 | 登录 |
| `GET` | `/api/data-sources/{dataSourceId}` | 查询数据源详情 | 登录 |
| `POST` | `/api/data-sources/{dataSourceId}/test` | 测试真实 JDBC 连接 | 登录 |
| `GET` | `/api/data-sources/{dataSourceId}/schema` | 查询真实库表结构 | 登录 |
| `PUT` | `/api/data-sources/{dataSourceId}` | 更新数据源 | `datasource:manage` |
| `POST` | `/api/data-sources/{dataSourceId}/enable` | 启用数据源 | `datasource:manage` |
| `POST` | `/api/data-sources/{dataSourceId}/disable` | 停用数据源 | `datasource:manage` |
| `DELETE` | `/api/data-sources/{dataSourceId}` | 删除数据源 | `datasource:manage` |

## 1. 创建数据源

```text
POST /api/data-sources
Content-Type: application/json
```

请求体：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 是 | 项目 ID |
| name | String | 是 | 数据源名称 |
| dbType | String | 是 | `MYSQL`、`POSTGRESQL`、`KINGBASE` |
| jdbcUrl | String | 是 | JDBC URL |
| username | String | 是 | 用户名 |
| password | String | 是 | 密码，后端 AES-GCM 加密存储 |

示例：

```bash
curl --noproxy '*' -X POST "http://127.0.0.1:8080/api/data-sources" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"projectId":1,"name":"本地只读库","dbType":"MYSQL","jdbcUrl":"jdbc:mysql://127.0.0.1:3306/demo","username":"readonly","password":"password"}'
```

## 2. 查询数据源列表

```text
GET /api/data-sources
```

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectId | Long | 否 | 项目 ID |
| dbType | String | 否 | 数据库类型 |
| status | String | 否 | `ENABLED`、`DISABLED` |
| keyword | String | 否 | 名称关键字 |
| pageNo | int | 否 | 默认 1 |
| pageSize | int | 否 | 默认 20 |

## 3. 查询详情

```text
GET /api/data-sources/{dataSourceId}
```

响应不会包含密码字段。

## 4. 测试连接

```text
POST /api/data-sources/{dataSourceId}/test
```

该接口使用真实 JDBC 连接，不返回假成功。响应重点字段：

| 字段 | 说明 |
| --- | --- |
| success | 是否连接成功 |
| databaseProductName | 数据库产品名 |
| databaseProductVersion | 数据库版本 |
| driverName | 驱动名 |
| driverVersion | 驱动版本 |
| elapsedMs | 耗时 |

## 5. 查询 Schema

```text
GET /api/data-sources/{dataSourceId}/schema
```

该接口使用真实 JDBC 元数据查询，返回 `tables` 和 `columns`。

## 6. 更新数据源

```text
PUT /api/data-sources/{dataSourceId}
Content-Type: application/json
```

请求体字段：`name`、`dbType`、`jdbcUrl`、`username`、`password`。如果传入新密码，会重新加密保存。

## 7. 启用、停用、删除

```text
POST /api/data-sources/{dataSourceId}/enable
POST /api/data-sources/{dataSourceId}/disable
DELETE /api/data-sources/{dataSourceId}
```

## 写入规则

- 创建成功后必须读回持久化记录再返回。
- 更新、启用、停用、删除必须检查影响行数。
- 数据源必须遵守项目隔离，跨项目访问应失败。
- 数据库问答执行只允许安全只读 SQL。
