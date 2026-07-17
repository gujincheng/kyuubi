# 统一数据网关需求文档（Kyuubi 改造）

> 版本：v1.0 ｜ 日期：2026-07-14 ｜ 基于 Apache Kyuubi 1.11.1（`digiwin-1.11.1` 分支）
> 参考内部文档：《Kyuubi 调研文档》、《统一数据网关方案设计与验证》

---

## 一、背景与目标

### 1.1 背景

随着企业数据系统增多，数据呈现多源异构、分布分散、协议多样的特点。传统烟囱式数据访问存在以下痛点：

|   痛点   |                  表现                   |
|--------|---------------------------------------|
| 用户直连引擎 | 各团队直连 StarRocks/Spark，难以统一管理          |
| 凭据外泄   | JDBC 连接串明文暴露在客户端配置/代码中                |
| 无统一审计  | 查询日志散落各系统，难以追溯                        |
| 安全风险   | 用户可直接执行危险 SQL（DROP/TRUNCATE/全表扫描），无拦截 |
| 运维复杂   | 新增引擎即改客户端配置，维护成本高                     |
| 客户端依赖  | 外部系统调用需引入 Kyuubi 依赖，非所有工具支持（"致命问题"）   |

### 1.2 目标

基于 Apache Kyuubi 1.11.1 二次开发，构建公司内部**统一数据网关**，作为数据访问的"交通枢纽"：

|   能力    |                        说明                         |
|---------|---------------------------------------------------|
| 统一接入    | 对外提供一致的多协议接入（MySQL/HiveServer2/REST/Trino）        |
| 多引擎支持   | StarRocks + Spark，未来扩展 Flink/Hive/Trino/其他 JDBC 库 |
| 数据源统一管理 | 网关层用 label 管理数据源，屏蔽连接细节与凭据                        |
| SQL 拦截  | 危险 SQL 拦截 + 白名单 + 告警                              |
| 审计日志    | 全量结构化审计，谁/何时/访问了什么/执行结果                           |
| 限流熔断    | 连接级 + QPS/并发查询级限流，慢查询 Kill，引擎不可用熔断                |
| 查询路由    | 规则路由到最优引擎（简单->StarRocks、复杂->Spark）                |
| 监控可观测   | Prometheus 指标 + Grafana 大盘 + 告警                   |
| 扩展性强    | 插件化接入新引擎/数据源/规则，不改核心                              |
| 安全认证/权限 | 后续迭代支持 LDAP/Kerberos/SSO 与 RBAC + 表/列/行级权限        |

### 1.3 设计原则

|     原则     |                  说明                   |
|------------|---------------------------------------|
| **插件化优先**  | 自研功能以插件形式挂在 Kyuubi 现有扩展点上，**不改核心主流程** |
| **渐进式增强**  | P0 跑通最小治理闭环，P1/P2 逐步增强                |
| **配置驱动**   | 数据源、拦截规则、路由规则通过配置/元数据管理，而非硬编码         |
| **兼容性优先**  | 保持与社区版本兼容，便于后续升级                      |
| **不依赖暂缓项** | P0 各能力互不依赖认证/权限体系，可独立落地               |

---

## 二、范围

### 2.1 本期范围

本文档定义将 Kyuubi 改造为统一数据网关的**全景需求**，采用**方案一：Kyuubi 单中心 + 插件化扩展**（详见第三章）。ProxySQL 不进入 v1 关键路径，仅作为 P2 可选协同项。

### 2.2 不在范围（暂缓/远期）

- 统一认证（LDAP/Kerberos/SSO）—— P2，插件点预留
- 统一权限（RBAC + 表/列/行级 + Ranger 集成）—— P2，插件点预留
- 审计落 Kafka/ES + 审计查询 UI —— P2
- 多引擎扩展（Flink/Hive/Trino/其他 JDBC 库）—— P2
- ProxySQL 协同（StarRocks OLAP 高吞吐前置）—— P2 可选

---

## 三、总体架构

### 3.1 架构图

```
┌─────────────────────────────────────────────────────────────┐
│  接入层（复用 Kyuubi 内置前端，多协议并存）                       │
│  Thrift Binary/HTTP(HiveServer2) │ MySQL协议 │ REST │ Trino  │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  治理层（v1 自研插件，Kyuubi 单中心的核心改造）                  │
│  ① DatasourceConfAdvisor  ② SqlInspectionHook              │
│  ③ QueryRouter            ④ AuditEventHandler(JSON文件)     │
│  ⑤ RateLimitEnhancer      （认证/权限插件点预留，后期接入）      │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  引擎层（复用 Kyuubi Engine，按 label/路由选择）                 │
│  JDBC Engine ──> StarRocks    Spark SQL Engine ──> Spark     │
│  （扩展点：Flink / Hive / Trino / 其他 JDBC 库）               │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌──────────────────────────────┐ ┌────────────────────────────┐
│ 存储：元数据存储扩展            │ │ 可观测：metrics->Prometheus │
│ （数据源注册表/审计索引/配额）   │ │ + Grafana；审计 P2 Kafka->ES│
└──────────────────────────────┘ └────────────────────────────┘
```

### 3.2 分层说明

- **接入层**：复用 Kyuubi 1.11.1 已有的 `KyuubiTBinaryFrontendService`、`KyuubiTHttpFrontendService`、`KyuubiMySQLFrontendService`、`KyuubiRestFrontendService`、`KyuubiTrinoFrontendService`，多协议并存，零外部依赖接入。
- **治理层**：v1 自研插件，全部挂在现有扩展点上（`SessionConfAdvisor`、EventHandler、限流配置等），是本次改造核心。
- **引擎层**：复用 Kyuubi Engine 机制，JDBC Engine 对接 StarRocks，Spark SQL Engine 对接 Spark。
- **存储层**：复用 `JDBCMetadataStore`（MySQL）扩展数据源注册表；审计 P0 落 JSON 文件。
- **可观测层**：复用 `kyuubi-metrics` 输出 Prometheus。

### 3.3 自定义模块结构

```
kyuubi/                              # Fork 自社区 1.11.1
├── kyuubi-server/                   # 核心（不改主流程）
├── kyuubi-common/                   # 核心（不改主流程）
├── digiwin-plugins/                 # 自定义模块（新增）
│   ├── kyuubi-datasource-plugin/    # 数据源元数据 + ConfAdvisor
│   ├── kyuubi-sql-inspection-plugin/# SQL 拦截
│   ├── kyuubi-router-plugin/        # 查询路由（P1）
│   └── kyuubi-audit-extension/      # 审计增强（P2 Kafka）
└── patches/                         # 少量核心修改的 Patch（如 DBeaver 流式 bug 修复）
```

---

## 四、组件边界

### 4.1 组件清单

每个组件单一职责、接口清晰、可独立测试：

|          组件           |                     职责                      |         依赖         |             复用/新建              |
|-----------------------|---------------------------------------------|--------------------|--------------------------------|
| DatasourceRegistry    | 数据源元数据 CRUD（label、引擎类型、JDBC 信息、加密凭据、连接池参数）  | 元数据存储              | 新建                             |
| DatasourceConfAdvisor | 实现 `SessionConfAdvisor`，按 label 注入引擎配置      | DatasourceRegistry | 新建（仿 `FileSessionConfAdvisor`） |
| SqlInspector          | SQL 解析 + 规则匹配，返回 放行/拦截/告警                   | 规则配置               | 新建                             |
| SqlInspectionHook     | 执行链钩子，调用 SqlInspector                       | SqlInspector       | 新建                             |
| QueryRouter           | 按 SQL 特征/用户/profile 决定引擎与数据源                | 路由规则               | 新建（P1）                         |
| AuditEventHandler     | 扩展内置 EventHandler，P0 输出 JSON 文件，P2 输出 Kafka | 文件/Kafka           | 扩展（内置已有 JSON/Kafka）            |
| RateLimitEnhancer     | 连接级（已有）+ QPS/并发查询级限流                        | 配额存储               | 扩展                             |

### 4.2 典型数据流

```
客户端
  │
  ▼
前端协议解析（Thrift/MySQL/REST/Trino）
  │
  ▼
认证（P0 暂用 Kyuubi 内置；P2 接 LDAP/Kerberos）
  │
  ▼
DatasourceConfAdvisor ── 按 label 代入数据源/引擎配置（凭据不外泄）
  │
  ▼
QueryRouter（P1） ── 决定目标引擎
  │
  ▼
SqlInspectionHook ── 拦截危险 SQL（放行/拦截/告警）
  │
  ▼
Engine（JDBC->StarRocks / Spark SQL->Spark）
  │
  ▼
结果回传
  │
  ▼
AuditEventHandler ── 记录结构化审计（P0 JSON 文件）
```

### 4.3 扩展点映射

|         自研组件          |             挂载的 Kyuubi 扩展点             |             说明             |
|-----------------------|----------------------------------------|----------------------------|
| DatasourceConfAdvisor | `SessionConfAdvisor` 接口                | 会话建立时注入数据源配置               |
| SqlInspectionHook     | Operation 执行链 Hook                     | SQL 执行前检查                  |
| AuditEventHandler     | `kyuubi.backend.server.event.loggers`  | 内置 JSON/Kafka EventHandler |
| RateLimitEnhancer     | `kyuubi.server.limit.*` 配置 + 自研 QPS 限流 | 连接级已有，QPS 级扩展              |
| MySQL 协议兼容            | `KyuubiMySQLFrontendService`（已内置）      | 验证 + 修复流式 bug              |
| 元数据存储                 | `JDBCMetadataStore` 扩展表                | 数据源注册表                     |

**关键约束**：所有自研组件均可挂在现有扩展点上，无需改 Kyuubi 核心主流程。`patches/` 仅用于必要的 bug 修复（如 DBeaver 流式结果集）。

---

## 五、功能需求与优先级

### 5.1 优先级定义

- **P0** = v1 第一期必落地（统一网关最小治理闭环）
- **P1** = 紧接第二期
- **P2** = 远期/扩展（含暂缓的认证权限）

### 5.2 功能需求清单

|       能力        |  编号   |                                                               功能需求                                                               | 优先级 |
|-----------------|-------|----------------------------------------------------------------------------------------------------------------------------------|-----|
| **数据源元数据管理**    | FR-1  | 数据源注册：label、引擎类型、JDBC URL、用户、加密凭据、连接池参数；支持 REST（必须）/CLI（可选）CRUD 且热生效                                                             | P0  |
|                 | FR-2  | label 代入：客户端仅传 `kyuubi.datasource=<label>`（兼容 `kyuubi.session.conf.profile=<label>`），`DatasourceConfAdvisor` 自动注入完整引擎配置，客户端不暴露凭据 | P0  |
|                 | FR-3  | 凭据加密存储                                                                                                                           | P0  |
| **SQL 拦截**      | FR-4  | 危险 SQL 拦截规则（DROP/TRUNCATE/无 WHERE 的 UPDATE·DELETE/SELECT \* 大表），支持正则 + AST 规则可配置                                                 | P0  |
|                 | FR-5  | 白名单例外 + 拦截告警（钉钉/邮件）+ 明确错误码                                                                                                       | P0  |
| **审计日志**        | FR-6  | 全量结构化审计：用户、SQL、引擎、数据源、时间、耗时、状态、行数、来源 IP                                                                                          | P0  |
|                 | FR-7  | 直接写文件 JSON（复用内置 JSON EventHandler，按时间分区）                                                                                         | P0  |
|                 | FR-8  | 输出 Kafka -> ES/ClickHouse + 审计查询 UI                                                                                              | P2  |
| **客户端协议兼容**     | FR-9  | MySQL 协议零依赖接入（DBeaver/DataGrip/Navicat/MySQL CLI）                                                                                | P0  |
|                 | FR-10 | 修复 DBeaver 流式结果集 bug；HiveServer2/REST/Trino 前端回归                                                                                 | P0  |
| **限流熔断**        | FR-11 | 连接级限流（复用已有 per user/ip）                                                                                                          | P0  |
|                 | FR-12 | QPS/并发查询级限流 + 慢查询自动 Kill + 引擎不可用熔断降级                                                                                             | P1  |
| **查询路由**        | FR-13 | 规则路由：简单查询->StarRocks、复杂分析->Spark；用户/profile 路由；规则热更新                                                                             | P1  |
| **监控可观测**       | FR-14 | Prometheus 指标（QPS/延迟/错误率/连接数/引擎状态）+ Grafana 大盘 + 告警                                                                              | P1  |
| **统一认证**        | FR-15 | LDAP/Kerberos/SSO 接入（插件点预留，暂缓）                                                                                                   | P2  |
| **统一权限**        | FR-16 | RBAC + 表/列/行级，Ranger 集成或自研（插件点预留，暂缓）                                                                                             | P2  |
| **多引擎扩展**       | FR-17 | Flink/Hive/Trino/其他 JDBC 库接入（扩展点已就绪）                                                                                             | P2  |
| **ProxySQL 协同** | FR-18 | 作为 StarRocks OLAP 高吞吐前置的可选项（非 v1 依赖）                                                                                             | P2  |

### 5.3 各能力详细需求

#### 5.3.1 数据源元数据管理（P0）

- **FR-1 数据源注册**：通过配置文件、CLI、REST API 注册数据源，字段含 label、引擎类型（jdbc/spark）、JDBC 类型（starrocks/doris/mysql）、驱动类、JDBC URL、用户名、加密密码、连接池参数、状态、描述。增删改查热生效，无需重启。
- **FR-2 label 代入**：
  - 改造前：连接串明文暴露 JDBC URL/用户名/密码。
  - 改造后：客户端仅传 `kyuubi.session.conf.profile=sr-prod`（或 `kyuubi.datasource=sr-prod`），`DatasourceConfAdvisor` 自动注入完整引擎配置。
  - 示例：`beeline -u "jdbc:kyuubi://gateway:10009/?kyuubi.datasource=sr-prod" -n username`
- **FR-3 凭据加密**：密码/凭据加密落元数据库，不明文存储、不出现在客户端连接串。
- **缓存策略**：启动加载全量到内存；定时刷新（默认 60s）；API 变更主动刷新；支持手动刷新。
- **数据源管理 API**（REST，P0 必须支持 CRUD 且热生效；CLI 可选）：
  - `GET /api/v1/datasources` 列表
  - `GET /api/v1/datasources/{label}` 详情
  - `POST /api/v1/datasources` 新增
  - `PUT /api/v1/datasources/{label}` 更新
  - `DELETE /api/v1/datasources/{label}` 删除
  - `POST /api/v1/datasources/refresh` 刷新缓存

#### 5.3.2 SQL 拦截（P0）

- **FR-4 拦截规则**：支持正则与 AST 两种规则。默认规则集：
  - 拦截：`DROP`、`TRUNCATE`、无 `WHERE` 的 `UPDATE`/`DELETE`、`SELECT *` 扫描大表（阈值可配）
  - 告警：高频全表扫描、生产库非查询高峰执行 DDL 等
- **FR-5 白名单与告警**：管理员/特定用户例外；拦截触发钉钉/邮件告警；返回明确错误码（如 `SQL_BLOCKED`）与提示。
- **规则配置示例**：

  ```properties
  kyuubi.server.sql.inspection.enabled=true
  kyuubi.server.sql.inspection.rules=deny:DROP,deny:TRUNCATE,deny:DELETE/WITHOUT_WHERE,warn:SELECT.*FROM.{10,}
  kyuubi.server.sql.inspection.whitelist=admin
  ```
- **拦截响应**：

  ```
  Error: SQL blocked by inspection rule: DROP is not allowed (code=SQL_BLOCKED)
  ```

#### 5.3.3 审计日志（P0 文件 / P2 Kafka）

- **FR-6 审计字段**：statementId、sessionId、user、datasource(label)、statement、state、startTime、completeTime、elapsedTime、rowCount、exception、kyuubiInstance、clientIp。
- **FR-7 文件输出**：复用内置 JSON EventHandler，按时间分区落盘。

  ```properties
  kyuubi.backend.server.event.loggers=JSON
  kyuubi.backend.server.event.json.log.path=file:///opt/kyuubi/logs/audit
  ```

  - 目录结构：`logs/audit/kyuubi_operation/day=YYYYMMDD/server-<host>.json`
- **FR-8（P2）**：扩展输出 Kafka -> ES/ClickHouse；审计查询 UI。

#### 5.3.4 客户端协议兼容（P0）

- **FR-9 MySQL 协议**：复用 `KyuubiMySQLFrontendService`，使 DBeaver/DataGrip/Navicat/MySQL CLI 以标准 MySQL 协议零依赖接入，化解"需引入 Kyuubi 依赖"的致命问题。
- **FR-10 bug 修复与回归**：
  - 修复调研文档中 DBeaver 经 JDBC Engine 查询 StarRocks 的 `Streaming result set is still active` 错误（根因：MySQL JDBC 流式结果集未正确关闭）。
  - HiveServer2（beeline/Superset/帆软）、REST、Trino 前端回归测试。

#### 5.3.5 限流熔断（P0 连接级 / P1 QPS 级）

- **FR-11 连接级限流**（复用已有）：

  ```properties
  kyuubi.server.limit.connections.per.user=10
  kyuubi.server.limit.connections.per.ipaddress=50
  kyuubi.server.limit.connections.per.user.ipaddress=20
  kyuubi.server.limit.connections.ip.deny.list=...
  kyuubi.server.limit.connections.user.unlimited.list=admin
  ```
- **FR-12（P1）**：QPS/并发查询级限流；慢查询自动 Kill（`kyuubi.operation.timeout`）；引擎不可用时熔断/降级。

#### 5.3.6 查询路由（P1）

- **FR-13 规则路由**：
  - 简单查询（单表、少量数据）-> StarRocks
  - 复杂分析（多表 JOIN、大数据量）-> Spark
  - 用户/profile 路由（VIP 用户优先 StarRocks）
  - 路由规则热更新（无需重启）
  - 用户仍可显式指定 label/engine 覆盖路由
- v1 阶段用户通过 label 显式选择引擎即可，路由为 P1 增强。

#### 5.3.7 监控可观测（P1）

- **FR-14**：复用 `kyuubi-metrics` 采集 Prometheus 指标（QPS/延迟/错误率/连接数/引擎状态）；Grafana 大盘；告警规则（错误率 >5%、延迟 >10s）。

#### 5.3.8 统一认证（P2，暂缓）

- **FR-15**：LDAP/Kerberos/SSO 接入，插件点预留。v1 暂用 Kyuubi 内置认证。

#### 5.3.9 统一权限（P2，暂缓）

- **FR-16**：RBAC + 表/列/行级访问控制，Ranger 集成或自研，插件点预留。

#### 5.3.10 多引擎扩展（P2）

- **FR-17**：Flink/Hive/Trino/其他 JDBC 库接入，扩展点已就绪。

#### 5.3.11 ProxySQL 协同（P2，可选）

- **FR-18**：作为 StarRocks OLAP 高吞吐前置代理 + SQL 防火墙补充。非 v1 依赖，按需引入。

---

## 六、非功能需求

| 维度  |                          指标                          |
|-----|------------------------------------------------------|
| 性能  | 网关转发开销 < 100ms（对齐调研文档 JDBC 直连模式）；单节点并发连接 500+，集群水平扩展 |
| 高可用 | Kyuubi Server 多实例 + ZK 服务发现（已有）；元数据 MySQL 高可用        |
| 可扩展 | 插件化，新增引擎/数据源/规则不改核心主流程                               |
| 安全  | 凭据加密存储；危险 SQL 拦截；传输加密可选（SASL/Kerberos 后期）            |
| 部署  | 单进程，JDBC 模式轻量；支持 Docker/容器化                          |
| 可测试 | 每个自研插件带单元测试；端到端集成测试覆盖核心链路                            |

---

## 七、阶段规划

### 第一期 P0（统一网关最小治理闭环）

数据源元数据 label 代入 + SQL 拦截 + 审计写文件 + MySQL 协议兼容修复 + 连接级限流。

> 这五项构成"统一网关"的最小可用治理闭环，且互不依赖暂缓的权限体系。

### 第二期 P1

查询路由 + QPS 限流/熔断/慢查询 Kill + 监控 Grafana。

### 第三期 P2

统一认证 + 统一权限 + 审计 Kafka/ES + 查询 UI + 多引擎扩展（Flink/Hive/Trino）+ ProxySQL 协同（可选）。

---

## 八、约束与依赖

- 基于 Kyuubi 1.11.1（`digiwin-1.11.1` 分支）。
- 核心引擎：StarRocks + Spark（其余为扩展点）。
- 认证/权限体系暂缓，插件点预留。
- **不改 Kyuubi 核心主流程**，全部挂现有扩展点（`SessionConfAdvisor`、EventHandler、限流配置、MySQL 前端、`JDBCMetadataStore`）。
- 元数据存储复用 `JDBCMetadataStore`（MySQL）。
- 自研代码置于新增 `digiwin-plugins/` 模块，核心修改以 `patches/` 形式管理。

---

## 九、验收标准（v1 / P0）

1. beeline/MySQL CLI 仅传 label 即可访问 StarRocks 与 Spark，连接串不含明文凭据。
2. `DROP TABLE` / 无 `WHERE` 的 `DELETE` 被拦截，返回明确错误码并触发告警；白名单用户可执行。
3. 每次查询在 JSON 审计文件产生结构化记录（含用户/SQL/引擎/数据源/耗时/状态/来源 IP）。
4. DBeaver 经 MySQL 协议可查看表结构并读取数据（流式 bug 已修复）。
5. 单用户/IP 超出连接阈值被拒绝并返回提示。
6. 所有自研插件带单元测试；端到端集成测试覆盖 label 代入、拦截、审计、协议接入。

---

## 十、风险与应对

|                        风险                         |       影响       |                应对                 |
|---------------------------------------------------|----------------|-----------------------------------|
| MySQL 前端经 JDBC Engine 查询 StarRocks 的流式 bug 影响多客户端 | DBeaver 等工具不可用 | P0 优先定位修复，建立协议兼容回归用例              |
| 数据源元数据热生效与多实例缓存一致性                                | 配置变更延迟/不一致     | 定时刷新 + 变更主动刷新 + 手动刷新 API          |
| 自研 SQL 拦截规则误判阻断正常查询                               | 业务受阻           | 白名单机制 + 告警先于硬拦截 + 规则可灰度           |
| 核心代码改动影响社区版本兼容与升级                                 | 后续升级困难         | 严守"不改核心主流程"，修改走 patches 并最小化      |
| 暂缓认证/权限导致 v1 安全缺口                                 | 凭据/越权风险        | v1 依赖网络隔离 + 连接级限流 + 审计可追溯，P2 尽快补齐 |

---

## 附录

### A. 名词解释

|             术语             |                     说明                      |
|----------------------------|---------------------------------------------|
| Kyuubi                     | Apache Kyuubi，分布式多租户 SQL 网关                 |
| JDBC Engine                | Kyuubi 的 JDBC 引擎，对接 StarRocks/Doris/MySQL 等 |
| Spark Engine               | Kyuubi 的 Spark SQL 引擎                       |
| 数据源 label                  | 数据源唯一标识，如 `sr-prod`，客户端据此引用数据源              |
| SessionConfAdvisor         | Kyuubi 会话配置顾问扩展点，会话建立时注入配置                  |
| KyuubiMySQLFrontendService | Kyuubi 内置 MySQL 协议前端，支持标准 MySQL 客户端零依赖接入    |

### B. 参考文档

- [Apache Kyuubi 官方文档](https://kyuubi.apache.org/)
- [Kyuubi 配置说明](https://kyuubi.readthedocs.io/en/master/deployment/settings.html)
- [Doris 集成 Kyuubi](https://doris.apache.org/zh-CN/docs/dev/ecosystem/kyuubi)
- 内部文档：《Kyuubi 调研文档》
- 内部文档：《统一数据网关方案设计与验证》

