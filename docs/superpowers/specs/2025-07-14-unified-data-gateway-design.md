# 统一数据网关需求设计文档

## 一、项目背景与目标

### 1.1 背景

随着企业数字化转型加速，业务系统数量激增，数据呈现**多源异构、分布分散、协议多样**的特点。传统烟囱式数据访问模式下，存在以下问题：

|   痛点   |              表现               |
|--------|-------------------------------|
| 用户直连引擎 | 每个团队直连 StarRocks/Spark，难以统一管理 |
| 权限分散   | 各系统权限各自为政                     |
| 无统一审计  | 查询日志散落在各个系统中，难以追溯             |
| 安全风险   | 用户可直接执行危险 SQL，无拦截机制           |
| 运维复杂   | 新增引擎就要改客户端配置，维护成本高            |

### 1.2 目标

基于 Apache Kyuubi 1.11.1 二次开发，构建公司内部**统一数据网关**，作为数据访问的"交通枢纽"：

|   能力    |                 说明                 |
|---------|------------------------------------|
| 统一接入    | 对外提供一致的 JDBC / REST API 接口         |
| 多引擎支持   | 支持 StarRocks、Spark，未来扩展其他 JDBC 数据源 |
| 数据源统一管理 | 网关层面统一管理数据源，屏蔽连接细节                 |
| 安全认证    | 后续迭代支持 LDAP/SSO                    |
| 权限控制    | 后续迭代支持表级、列级访问控制                    |
| 审计日志    | 记录谁、何时、访问了什么数据、执行了什么操作             |
| 查询路由    | 用户显式选择引擎，StarRocks 为默认             |
| 监控告警    | 使用 Kyuubi 自带 Metrics               |
| 扩展性强    | 支持插件化接入新引擎或存储系统                    |

### 1.3 设计原则

|    原则     |             说明             |
|-----------|----------------------------|
| **插件化优先** | 自定义功能以插件形式开发，最小化核心代码修改     |
| **渐进式增强** | 第一版跑通主干，后续迭代增强功能           |
| **配置驱动**  | 数据源、路由规则等通过配置管理，而非硬编码      |
| **兼容性优先** | 保持与 Kyuubi 社区版本的兼容性，便于后续升级 |

---

## 二、总体架构设计

### 2.1 架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                         客户端层                                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐            │
│  │  DBeaver │  │  BI 工具  │  │ 数据开发平台 │  │  自研系统  │            │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘            │
│       │              │              │              │                  │
│       └──────────────┴──────────────┴──────────────┘                 │
│                              │                                       │
│                     JDBC / REST API                                  │
└──────────────────────────────┼───────────────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────────────┐
│                    统一数据网关 (Kyuubi 二开)                          │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │                      Kyuubi Server                              │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐            │ │
│  │  │  Thrift 接口  │  │  REST 接口   │  │  MySQL 接口  │            │ │
│  │  └─────────────┘  └─────────────┘  └─────────────┘            │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │                    自定义插件层 (digiwin-plugins)                  │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐            │ │
│  │  │ 数据源管理插件 │  │  REST 扩展   │  │  审计增强插件 │            │ │
│  │  └─────────────┘  └─────────────┘  └─────────────┘            │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │                      核心能力层                                   │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐            │ │
│  │  │  查询路由    │  │  会话管理    │  │  引擎管理    │            │ │
│  │  └─────────────┘  └─────────────┘  └─────────────┘            │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────┬───────────────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────────────┐
│                         计算引擎层                                     │
│  ┌──────────────────┐              ┌──────────────────┐             │
│  │    StarRocks      │              │      Spark       │             │
│  │  (JDBC Engine)    │              │  (Spark Engine)  │             │
│  └──────────────────┘              └──────────────────┘             │
│                                                                      │
│  ┌──────────────────┐              ┌──────────────────┐             │
│  │   未来: Flink     │              │  未来: 其他 JDBC  │             │
│  └──────────────────┘              └──────────────────┘             │
└──────────────────────────────────────────────────────────────────────┘
```

### 2.2 自定义模块结构

```
kyuubi/                              # Fork 自社区
├── kyuubi-server/                   # 核心（尽量不改）
├── kyuubi-common/                   # 核心（尽量不改）
├── digiwin-plugins/                 # 自定义模块（新增）
│   ├── kyuubi-datasource-plugin/    # 数据源管理插件
│   ├── kyuubi-rest-extension/       # REST API 扩展
│   └── kyuubi-audit-extension/      # 审计增强插件
└── patches/                         # 少量核心修改的 Patch 文件
    └── dbeaver-streaming-fix.patch
```

---

## 三、数据源管理模块

### 3.1 模块定位

数据源管理是统一网关的核心能力，负责：
- 屏蔽底层数据源连接细节
- 支持多环境（开发/测试/生产）多集群
- 通过 label 引用数据源，用户无需感知 JDBC URL
- 支持通过 REST API 动态增删改查

### 3.2 数据库表设计

```sql
-- 数据源配置表
CREATE TABLE datasources (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    label           VARCHAR(64) NOT NULL UNIQUE COMMENT '数据源标识，如 sr-prod',
    type            VARCHAR(32) NOT NULL COMMENT '类型：jdbc/spark',
    engine_type     VARCHAR(32) NOT NULL COMMENT '引擎类型：jdbc/spark/flink',
    jdbc_type       VARCHAR(32) COMMENT 'JDBC类型：starrocks/doris/mysql',
    driver_class    VARCHAR(128) COMMENT '驱动类名',
    jdbc_url        VARCHAR(512) COMMENT 'JDBC连接地址',
    username        VARCHAR(64) COMMENT '用户名',
    password        VARCHAR(256) COMMENT '密码（加密存储）',
    description     VARCHAR(256) COMMENT '描述',
    status          TINYINT DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    created_by      VARCHAR(64) COMMENT '创建人',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_label (label)
) COMMENT '数据源配置表';

-- 数据源变更审计表（可选）
CREATE TABLE datasource_audit_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_label VARCHAR(64) NOT NULL,
    action          VARCHAR(16) NOT NULL COMMENT '操作：CREATE/UPDATE/DELETE',
    operator        VARCHAR(64) COMMENT '操作人',
    old_config      TEXT COMMENT '变更前配置（JSON）',
    new_config      TEXT COMMENT '变更后配置（JSON）',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '数据源变更审计表';
```

### 3.3 用户访问方式

**改造前**（连接串暴露细节）：

```bash
beeline -u "jdbc:kyuubi://gateway:10009/?\
kyuubi.engine.type=jdbc;\
kyuubi.engine.jdbc.type=starrocks;\
kyuubi.engine.jdbc.driver.class=com.mysql.cj.jdbc.Driver;\
kyuubi.engine.jdbc.connection.url=jdbc:mysql://172.16.101.227:19030;\
kyuubi.engine.jdbc.connection.user=root;\
kyuubi.engine.jdbc.connection.password=DiGiWin@Sr312"
```

**改造后**（通过 label 引用）：

```bash
beeline -u "jdbc:kyuubi://gateway:10009/?kyuubi.datasource=sr-prod" -n username
```

**REST API 方式**：

```bash
curl -X POST http://gateway:10099/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "user": "username",
    "datasource": "sr-prod",
    "config": {}
  }'
```

### 3.4 数据源管理 API

|              API              |   方法   |       说明       |
|-------------------------------|--------|----------------|
| `/api/v1/datasources`         | GET    | 获取所有数据源列表      |
| `/api/v1/datasources/{label}` | GET    | 获取指定数据源详情      |
| `/api/v1/datasources`         | POST   | 新增数据源（运行时动态添加） |
| `/api/v1/datasources/{label}` | PUT    | 更新数据源配置        |
| `/api/v1/datasources/{label}` | DELETE | 删除数据源          |
| `/api/v1/datasources/refresh` | POST   | 手动刷新缓存         |

### 3.5 缓存策略

```
┌─────────────┐     定时刷新      ┌─────────────┐
│  本地缓存    │ ◄───────────────  │   数据库     │
│ (Concurrent │                   │ datasources │
│   HashMap)  │ ───────────────►  │             │
└─────────────┘   变更时主动刷新   └─────────────┘
       │
       ▼
  用户查询时直接读缓存，高性能
```

**缓存刷新策略**：
1. 启动时加载全量数据源配置到内存
2. 定时刷新（默认 60 秒）
3. API 变更时主动刷新缓存
4. 支持手动刷新 API

### 3.6 配置示例

**kyuubi-defaults.conf**（新增数据库连接配置）：

```properties
# 数据源管理插件配置
kyuubi.datasource.store.class=org.apache.kyuubi.datasource.JDBCDatasourceStore
kyuubi.datasource.store.jdbc.url=jdbc:mysql://mysql:3306/kyuubi_gateway
kyuubi.datasource.store.jdbc.user=root
kyuubi.datasource.store.jdbc.password=******
kyuubi.datasource.store.jdbc.driver=com.mysql.cj.jdbc.Driver
kyuubi.datasource.store.cache.ttl=60s
```

---

## 四、查询路由与会话管理

### 4.1 查询路由设计

**核心原则**：用户显式选择引擎，StarRocks 为默认。

#### 路由方式

|      方式       |             示例              |        说明        |
|---------------|-----------------------------|------------------|
| **数据源 label** | `kyuubi.datasource=sr-prod` | 推荐方式，通过 label 路由 |
| **引擎类型**      | `kyuubi.engine.type=jdbc`   | 直接指定引擎类型         |
| **默认路由**      | 不指定参数                       | 默认使用 StarRocks   |

#### 路由流程

```
用户连接请求
    │
    ▼
┌─────────────────────────────┐
│  解析连接参数                 │
│  - kyuubi.datasource=sr-prod│
│  - kyuubi.engine.type=...   │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│  查询数据源配置               │
│  1. 优先使用 datasource label│
│  2. 其次使用 engine.type     │
│  3. 默认 StarRocks          │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│  创建对应引擎的 Session       │
│  - JDBC Engine → StarRocks  │
│  - Spark Engine → Spark     │
└─────────────────────────────┘
```

### 4.2 会话管理增强

#### 会话配置注入

通过 `SessionConfAdvisor` 机制，将数据源配置注入到会话中：

```scala
class DatasourceSessionAdvisor extends SessionConfAdvisor {
  override def getConfOverlay(
      user: String,
      sessionConf: Map[String, String]): Map[String, String] = {
    
    val datasource = sessionConf.get("kyuubi.datasource")
    datasource match {
      case Some(label) =>
        // 从数据库/缓存获取数据源配置
        val dsConfig = DatasourceManager.get(label)
        dsConfig.toSessionConf  // 转换为 Kyuubi 配置
      case None =>
        // 默认使用 StarRocks
        DatasourceManager.getDefault.toSessionConf
    }
  }
}
```

#### 会话隔离

|    维度     |        隔离方式        |
|-----------|--------------------|
| **用户隔离**  | 每个用户独立会话，互不影响      |
| **数据源隔离** | 不同数据源使用不同连接池       |
| **资源隔离**  | 通过 Kyuubi 原生的多租户机制 |

### 4.3 多数据源查询场景

**注意**：第一版暂不支持单会话内跨数据源联邦查询，后续迭代考虑。

---

## 五、REST API 扩展

### 5.1 REST API 定位

为不支持 JDBC 的系统（如自研平台、第三方 SaaS）提供 HTTP 接口，与 JDBC 能力对齐。

### 5.2 会话管理 API

|              API               |   方法   |   说明   |
|--------------------------------|--------|--------|
| `/api/v1/sessions`             | POST   | 创建会话   |
| `/api/v1/sessions`             | GET    | 获取会话列表 |
| `/api/v1/sessions/{sessionId}` | GET    | 获取会话详情 |
| `/api/v1/sessions/{sessionId}` | DELETE | 关闭会话   |

**创建会话请求**：

```json
POST /api/v1/sessions
{
  "user": "user1",
  "datasource": "sr-prod",
  "config": {
    "key1": "value1"
  }
}
```

**创建会话响应**：

```json
{
  "sessionId": "abc-123-def",
  "user": "user1",
  "datasource": "sr-prod",
  "engine": "jdbc",
  "createTime": "2025-07-14T10:00:00Z"
}
```

### 5.3 SQL 执行 API

#### 接口设计

|                接口                |   方法   |      说明      | 返回值  |
|----------------------------------|--------|--------------|------|
| `/api/v1/statements/execute`     | POST   | 同步执行，直接返回结果  | 查询结果 |
| `/api/v1/statements/submit`      | POST   | 异步提交，返回任务 ID | 任务信息 |
| `/api/v1/statements/{id}`        | GET    | 查询任务状态       | 任务状态 |
| `/api/v1/statements/{id}/result` | GET    | 获取任务结果       | 查询结果 |
| `/api/v1/statements/{id}`        | DELETE | 取消任务         | 操作结果 |

#### 同步执行（小 SQL）

**请求**：

```json
POST /api/v1/statements/execute
{
  "sessionId": "abc-123",
  "statement": "SELECT * FROM orders LIMIT 10",
  "maxRows": 1000,
  "timeout": 30000
}
```

**响应**（始终返回结果）：

```json
{
  "success": true,
  "data": {
    "columns": [
      {"name": "id", "type": "BIGINT"},
      {"name": "amount", "type": "DECIMAL"}
    ],
    "rows": [
      [1, 100.50],
      [2, 200.00]
    ],
    "rowCount": 2,
    "elapsedTime": 85
  }
}
```

**超时响应**（SQL 执行太慢）：

```json
{
  "success": false,
  "error": {
    "code": "EXECUTION_TIMEOUT",
    "message": "SQL 执行超时，请使用异步接口 submit",
    "suggestion": "POST /api/v1/statements/submit"
  }
}
```

#### 异步提交（大 SQL）

**请求**：

```json
POST /api/v1/statements/submit
{
  "sessionId": "abc-123",
  "statement": "SELECT * FROM large_table JOIN ... GROUP BY ..."
}
```

**响应**（始终返回任务信息）：

```json
{
  "success": true,
  "data": {
    "statementId": "stmt-789",
    "state": "RUNNING",
    "createTime": "2025-07-14T10:00:00Z"
  }
}
```

#### 查询任务状态

**请求**：

```json
GET /api/v1/statements/stmt-789
```

**响应**（始终返回状态）：

```json
{
  "success": true,
  "data": {
    "statementId": "stmt-789",
    "state": "RUNNING",
    "progress": 45,
    "elapsedTime": 5000
  }
}
```

#### 获取任务结果

**请求**：

```json
GET /api/v1/statements/stmt-789/result
```

**响应**（任务完成时返回结果）：

```json
{
  "success": true,
  "data": {
    "columns": [...],
    "rows": [...],
    "rowCount": 1000,
    "elapsedTime": 12000
  }
}
```

**响应**（任务未完成时）：

```json
{
  "success": false,
  "error": {
    "code": "STATEMENT_NOT_FINISHED",
    "message": "查询尚未完成，当前状态: RUNNING",
    "currentState": "RUNNING"
  }
}
```

### 5.4 数据源管理 API

已在第三部分说明。

### 5.5 统一响应格式

所有接口使用统一的响应格式：

```json
{
  "success": true/false,
  "data": { ... },           // 成功时返回
  "error": {                 // 失败时返回
    "code": "ERROR_CODE",
    "message": "错误描述"
  }
}
```

|           错误码            |    说明    |
|--------------------------|----------|
| `DATASOURCE_NOT_FOUND`   | 数据源不存在   |
| `SESSION_NOT_FOUND`      | 会话不存在    |
| `STATEMENT_FAILED`       | SQL 执行失败 |
| `UNAUTHORIZED`           | 未授权      |
| `RATE_LIMITED`           | 超出限流阈值   |
| `EXECUTION_TIMEOUT`      | 执行超时     |
| `STATEMENT_NOT_FINISHED` | 查询尚未完成   |

### 5.6 实现方式

**插件化实现**，新增 Maven 模块：

```
digiwin-plugins/
└── kyuubi-rest-extension/
    ├── src/main/scala/
    │   ├── RestApiV1Servlet.scala      # REST API Servlet
    │   ├── SessionApiHandler.scala     # 会话 API 处理器
    │   ├── StatementApiHandler.scala   # SQL 执行 API 处理器
    │   ├── DatasourceApiHandler.scala  # 数据源 API 处理器
    │   └── models/
    │       ├── SessionModels.scala     # 请求/响应模型
    │       └── ErrorModels.scala       # 错误模型
    └── pom.xml
```

---

## 六、SQL 审计与限流

### 6.1 SQL 审计模块

#### 审计内容

|        字段        |    说明     |
|------------------|-----------|
| `statementId`    | SQL 执行 ID |
| `sessionId`      | 会话 ID     |
| `user`           | 执行用户      |
| `datasource`     | 数据源 label |
| `statement`      | SQL 语句    |
| `state`          | 执行状态      |
| `startTime`      | 开始时间      |
| `completeTime`   | 完成时间      |
| `elapsedTime`    | 耗时（毫秒）    |
| `exception`      | 异常信息（如有）  |
| `kyuubiInstance` | 网关实例地址    |

#### 审计日志格式

**输出位置**：本地 JSON 文件（按时间分区）

**目录结构**：

```
$KYUUBI_HOME/logs/audit/
├── kyuubi_operation/
│   ├── day=20250714/
│   │   ├── server-gateway01.json
│   │   └── server-gateway02.json
│   └── day=20250715/
│       └── ...
├── kyuubi_session/
│   └── ...
└── kyuubi_connection/
    └── ...
```

**日志示例**：

```json
{
  "statementId": "abc-123",
  "sessionId": "sess-456",
  "user": "zhangsan",
  "datasource": "sr-prod",
  "statement": "SELECT * FROM orders WHERE amount > 100",
  "state": "FINISHED_STATE",
  "startTime": 1720934400000,
  "completeTime": 1720934400085,
  "elapsedTime": 85,
  "exception": null,
  "kyuubiInstance": "gateway01:10009",
  "eventType": "kyuubi_operation"
}
```

#### 配置方式

```properties
# conf/kyuubi-defaults.conf
kyuubi.backend.server.event.loggers=JSON
kyuubi.backend.server.event.json.log.path=file:///opt/kyuubi/logs/audit
```

**说明**：
- 第一版使用 Kyuubi 原生的 JSON 审计能力
- 后续迭代可扩展为 Kafka 输出，接入 ELK/ClickHouse

### 6.2 限流模块

#### 限流维度

|      维度      |                       配置项                       | 默认值  |     说明     |
|--------------|-------------------------------------------------|------|------------|
| **每用户连接数**   | `kyuubi.server.limit.connections.per.user`      | 10   | 单用户最大连接数   |
| **每 IP 连接数** | `kyuubi.server.limit.connections.per.ipaddress` | 50   | 单 IP 最大连接数 |
| **慢查询超时**    | `kyuubi.operation.timeout`                      | 300s | 超时自动 Kill  |

#### 配置示例

```properties
# conf/kyuubi-defaults.conf

# 连接数限制
kyuubi.server.limit.connections.per.user=10
kyuubi.server.limit.connections.per.ipaddress=50
kyuubi.server.limit.connections.per.user.ipaddress=20

# IP 黑名单
kyuubi.server.limit.connections.ip.deny.list=192.168.1.100,10.0.0.50

# 用户白名单（不受连接数限制）
kyuubi.server.limit.connections.user.unlimited.list=admin,superuser

# 慢查询超时（秒）
kyuubi.operation.timeout=300
```

#### 限流响应

**连接数超限**：

```
Error: Connection rejected: User 'zhangsan' exceeds maximum connections limit (10)
```

**IP 被封禁**：

```
Error: Connection rejected: IP '192.168.1.100' is in deny list
```

**慢查询被 Kill**：

```
Error: Query timeout after 300 seconds, query has been cancelled
```

### 6.3 危险 SQL 拦截（后续迭代）

**第一版暂不实现**，预留扩展点：

```properties
# 后续迭代配置示例
kyuubi.server.sql.interceptor.enabled=true
kyuubi.server.sql.interceptor.rules=deny:DROP,deny:TRUNCATE,deny:DELETE.*,warn:SELECT \* FROM .{10,}
```

---

## 七、DBeaver 兼容性修复

### 7.1 问题概述

|    项目    |                                    内容                                    |
|----------|--------------------------------------------------------------------------|
| **问题现象** | DBeaver 通过 Kyuubi 查询 StarRocks 报错：`Streaming result set is still active` |
| **影响范围** | 所有使用 MySQL JDBC 驱动流式结果集的客户端工具                                            |
| **根因**   | MySQL JDBC 驱动的流式结果集未正确关闭                                                 |
| **修复目标** | DBeaver 可正常通过 Kyuubi 查询 StarRocks，无流式结果集错误                               |

### 7.2 待定事项

- 具体修复方案待开发阶段验证
- 需要确认是否影响其他 JDBC 客户端工具

---

## 八、部署方案

### 8.1 部署架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Docker Host                            │
│  ┌───────────────────────────────────────────────────────┐ │
│  │                 Kyuubi Gateway 容器                    │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │ │
│  │  │  Kyuubi     │  │  自定义插件   │  │  配置文件    │  │ │
│  │  │  Server     │  │  (挂载卷)    │  │  (挂载卷)   │  │ │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  │ │
│  │                                                      │ │
│  │  端口映射：                                            │ │
│  │  - 10009:10009  (Thrift/JDBC)                        │ │
│  │  - 10099:10099  (REST API)                           │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐ │
│  │                 MySQL 容器 (元数据)                     │ │
│  │  - 数据源配置存储                                        │ │
│  │  - Kyuubi 内部元数据                                     │ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
   ┌──────────┐        ┌──────────┐        ┌──────────┐
   │StarRocks │        │  Spark   │        │  客户端   │
   │  集群    │        │  集群    │        │  工具    │
   └──────────┘        └──────────┘        └──────────┘
```

### 8.2 Docker 部署

#### 目录结构

```
/opt/kyuubi-gateway/
├── docker-compose.yml
├── Dockerfile
├── conf/
│   ├── kyuubi-defaults.conf      # 主配置
│   └── datasources.conf          # 数据源配置（后续改为数据库）
├── plugins/
│   ├── kyuubi-datasource-plugin.jar
│   └── kyuubi-rest-extension.jar
├── logs/
│   └── audit/                    # 审计日志
└── jdbc-drivers/
    └── mysql-connector-j-8.x.jar
```

#### docker-compose.yml

```yaml
version: '3.8'

services:
  kyuubi-gateway:
    build: .
    container_name: kyuubi-gateway
    ports:
      - "10009:10009"   # Thrift/JDBC
      - "10099:10099"   # REST API
    volumes:
      - ./conf:/opt/kyuubi/conf
      - ./plugins:/opt/kyuubi/plugins
      - ./logs:/opt/kyuubi/logs
      - ./jdbc-drivers:/opt/kyuubi/externals/engines/jdbc
    environment:
      - KYUUBI_JAVA_OPTS=-Xmx4g
    depends_on:
      - mysql-metadata
    networks:
      - kyuubi-net

  mysql-metadata:
    image: mysql:8.0
    container_name: kyuubi-mysql
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: kyuubi_gateway
    volumes:
      - mysql-data:/var/lib/mysql
    networks:
      - kyuubi-net

volumes:
  mysql-data:

networks:
  kyuubi-net:
    driver: bridge
```

#### Dockerfile

```dockerfile
FROM apache/kyuubi:1.11.1-bin

# 复制自定义插件
COPY plugins/ /opt/kyuubi/plugins/

# 复制配置文件
COPY conf/ /opt/kyuubi/conf/

# 复制 JDBC 驱动
COPY jdbc-drivers/ /opt/kyuubi/externals/engines/jdbc/

# 创建日志目录
RUN mkdir -p /opt/kyuubi/logs/audit

# 暴露端口
EXPOSE 10009 10099

# 启动 Kyuubi
CMD ["/opt/kyuubi/bin/kyuubi", "run"]
```

### 8.3 配置文件

#### kyuubi-defaults.conf

```properties
# 服务配置
kyuubi.frontend.protocols=THRIFT_BINARY,REST
kyuubi.frontend.thrift.binary.bind.host=0.0.0.0
kyuubi.frontend.thrift.binary.bind.port=10009
kyuubi.frontend.rest.bind.host=0.0.0.0
kyuubi.frontend.rest.bind.port=10099

# 元数据存储
kyuubi.metadata.store.class=org.apache.kyuubi.server.metadata.jdbc.JDBCMetadataStore
kyuubi.metadata.store.jdbc.url=jdbc:mysql://mysql-metadata:3306/kyuubi_gateway
kyuubi.metadata.store.jdbc.user=root
kyuubi.metadata.store.jdbc.password=${MYSQL_ROOT_PASSWORD}
kyuubi.metadata.store.jdbc.driver=com.mysql.cj.jdbc.Driver
kyuubi.metadata.store.jdbc.database.schema.init=true

# 数据源管理插件
kyuubi.datasource.store.class=org.apache.kyuubi.datasource.JDBCDatasourceStore
kyuubi.datasource.store.jdbc.url=jdbc:mysql://mysql-metadata:3306/kyuubi_gateway
kyuubi.datasource.store.jdbc.user=root
kyuubi.datasource.store.jdbc.password=${MYSQL_ROOT_PASSWORD}

# 审计日志
kyuubi.backend.server.event.loggers=JSON
kyuubi.backend.server.event.json.log.path=file:///opt/kyuubi/logs/audit

# 限流配置
kyuubi.server.limit.connections.per.user=10
kyuubi.server.limit.connections.per.ipaddress=50

# JDBC 引擎配置
kyuubi.engine.type=jdbc
kyuubi.engine.jdbc.type=starrocks
kyuubi.engine.jdbc.driver.class=com.mysql.cj.jdbc.Driver
kyuubi.engine.jdbc.fetchSize=1000
```

### 8.4 启动命令

```bash
# 启动服务
docker-compose up -d

# 查看日志
docker logs -f kyuubi-gateway

# 停止服务
docker-compose down
```

---

## 九、开发计划

### 9.1 阶段划分

|   阶段   |     目标      |                主要任务                | 预估工时 |
|--------|-------------|------------------------------------|------|
| **P0** | 基础环境搭建      | Docker 部署、Kyuubi 编译运行、StarRocks 连通 | 3 天  |
| **P1** | 数据源管理       | 数据源插件开发、REST API、数据库存储             | 5 天  |
| **P2** | REST API 扩展 | 会话管理、SQL 执行、结果返回                   | 5 天  |
| **P3** | 审计与限流       | 审计日志配置、限流策略实施                      | 3 天  |
| **P4** | DBeaver 修复  | 流式结果集问题修复、验证                       | 3 天  |
| **P5** | Spark 引擎集成  | Spark Engine 接入、路由配置               | 3 天  |
| **P6** | 测试与文档       | 集成测试、使用文档、部署文档                     | 3 天  |

### 9.2 各阶段详细任务

#### P0：基础环境搭建（3 天）

|       任务       |                说明                |
|----------------|----------------------------------|
| Docker 环境搭建    | 编写 Dockerfile、docker-compose.yml |
| Kyuubi 编译运行    | 源码编译、本地启动验证                      |
| StarRocks 连通测试 | JDBC Engine 连接 StarRocks，执行简单查询  |

**交付物**：可运行的 Kyuubi Docker 镜像

#### P1：数据源管理（5 天）

|     任务      |             说明              |
|-------------|-----------------------------|
| 数据源插件开发     | DatasourceManager、配置解析、缓存机制 |
| 数据库表设计      | datasources 表、创建脚本          |
| REST API 开发 | 数据源 CRUD 接口                 |
| 会话配置注入      | SessionConfAdvisor 集成       |

**交付物**：可通过 REST API 管理数据源

#### P2：REST API 扩展（5 天）

|     任务     |     说明      |
|------------|-------------|
| 会话管理 API   | 创建、查询、关闭会话  |
| SQL 执行 API | 同步执行、异步提交   |
| 结果返回       | 统一响应格式、分页支持 |
| 错误处理       | 错误码定义、异常捕获  |

**交付物**：完整的 REST API 接口

#### P3：审计与限流（3 天）

|    任务    |       说明       |
|----------|----------------|
| 审计日志配置   | JSON 日志输出、目录结构 |
| 限流配置     | 连接数限制、IP 限制    |
| 慢查询 Kill | 超时配置、验证        |

**交付物**：审计日志正常输出，限流生效

#### P4：DBeaver 兼容性修复（3 天）

|  任务  |          说明          |
|------|----------------------|
| 问题复现 | 确认 DBeaver 流式结果集错误   |
| 修复方案 | JDBC Engine 层修复或配置调整 |
| 验证测试 | DBeaver 多场景验证        |

**交付物**：DBeaver 可正常使用

#### P5：Spark 引擎集成（3 天）

|         任务          |          说明           |
|---------------------|-----------------------|
| Spark 环境准备          | Spark 集群配置            |
| Kyuubi Spark Engine | 配置、启动、验证              |
| 路由测试                | 通过数据源 label 路由到 Spark |

**交付物**：可通过网关访问 Spark

#### P6：测试与文档（3 天）

|  任务  |        说明        |
|------|------------------|
| 集成测试 | 端到端测试、多场景覆盖      |
| 使用文档 | 用户接入指南、API 文档    |
| 部署文档 | Docker 部署指南、配置说明 |

**交付物**：可交付的网关服务

### 9.3 总体时间线

```
Week 1: P0 + P1（基础环境 + 数据源管理）
Week 2: P2 + P3（REST API + 审计限流）
Week 3: P4 + P5 + P6（DBeaver 修复 + Spark + 测试文档）
```

**总计**：约 3 周（25 人天）

---

## 十、附录

### 10.1 名词解释

|           术语           |                   说明                    |
|------------------------|-----------------------------------------|
| **Kyuubi**             | Apache Kyuubi，分布式多租户 SQL 网关             |
| **JDBC Engine**        | Kyuubi 的 JDBC 引擎，用于连接 StarRocks/Doris 等 |
| **Spark Engine**       | Kyuubi 的 Spark 引擎，用于执行 Spark SQL        |
| **数据源 label**          | 数据源的唯一标识符，如 `sr-prod`                   |
| **SessionConfAdvisor** | Kyuubi 的会话配置顾问机制                        |

### 10.2 参考文档

- [Apache Kyuubi 官方文档](https://kyuubi.apache.org/)
- [Kyuubi 配置说明](https://kyuubi.readthedocs.io/en/master/deployment/settings.html)
- [Doris 集成 Kyuubi](https://doris.apache.org/zh-CN/docs/dev/ecosystem/kyuubi)
- 内部文档：《Kyuubi 调研文档》
- 内部文档：《统一数据网关方案设计与验证》

