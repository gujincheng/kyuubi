# 统一数据网关需求文档（Kyuubi 改造）v2

> 版本：v2.0 ｜ 日期：2026-07-14 ｜ 基于 Apache Kyuubi 1.11.1（`digiwin-1.11.1` 分支）
> 参考内部文档：《Kyuubi 调研文档》《统一数据网关方案设计与验证》
> 说明：本版本为重新梳理版，**替代**此前未提交的两份草稿（`2025-07-14-unified-data-gateway-design.md`、`2026-07-14-kyuubi-unified-gateway-design.md`），是本项目的第一份正式决策依据——目前 ProxySQL 与 Kyuubi 两条线均尚未有任何上线动作。

---

## 一、背景与目标

### 1.1 背景

随着企业数据系统增多，数据呈现多源异构、分布分散、协议多样的特点。传统烟囱式数据访问存在以下痛点（按本次改造优先级排序）：

|    痛点    |                         表现                          |                                                  本次是否优先解决                                                   |
|----------|-----------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| 安全风险     | 用户可直接执行危险 SQL（DROP/TRUNCATE/无 WHERE 更新删除/全表扫描），无人拦截 | 是（P0）                                                                                                       |
| 凭据泄露     | JDBC 连接串、密码明文暴露在客户端配置/连接串里                          | 是（P0）                                                                                                       |
| 无统一审计    | 查询日志散落各系统，出问题无法追溯                                   | 是（P0）                                                                                                       |
| 稳定性/资源争抢 | 并发高时集群过载，缺少限流熔断机制                                   | 是（P0）                                                                                                       |
| 客户端依赖    | 曾认为"第三方工具需引入 Kyuubi 依赖"是致命问题                        | 已确认 Kyuubi JDBC 驱动（kyuubi-hive-jdbc）经 Thrift Binary 协议可满足第三方工具（DBeaver/Navicat 等）接入，不需 MySQL frontend 或额外方案 |
| 运维复杂     | 新增引擎即改客户端配置                                         | 部分缓解（label 机制），完全解决留待后续                                                                                     |

### 1.2 目标

基于 Apache Kyuubi 1.11.1 二次开发，构建公司内部**统一数据网关**：

|   能力    |                                                  说明                                                   |
|---------|-------------------------------------------------------------------------------------------------------|
| 统一接入    | 复用 Kyuubi 已有多协议前端（Thrift Binary/REST/Trino），零外部依赖接入；DBeaver/Navicat 经 Kyuubi JDBC 驱动（Thrift Binary）接入 |
| 数据源统一管理 | 网关层用 label 管理数据源，客户端不接触连接细节与凭据                                                                        |
| SQL 拦截  | 危险 SQL 拦截 + 白名单 + 告警                                                                                  |
| 审计日志    | 全量结构化审计，谁/何时/访问了什么/执行结果                                                                               |
| 限流熔断    | 连接级 + QPS/并发查询级限流、慢查询 Kill、引擎不可用熔断降级                                                                  |
| 扩展性     | 插件化接入新引擎/数据源/规则，不改核心主流程                                                                               |
| 认证/权限   | 本期不做，预留插件点                                                                                            |

### 1.3 设计原则

|      原则      |                          说明                           |
|--------------|-------------------------------------------------------|
| 插件化优先        | 自研功能挂在 Kyuubi 现有扩展点上，尽量不改核心主流程                        |
| 核心 Patch 最小化 | 确实无 SPI 可挂的能力（如 SQL 拦截钩子），接受少量、集中、加法式的核心改动，改动点在本文档中列全 |
| 渐进式增强        | P0 跑通最小治理闭环，P1/P2 逐步增强                                |
| 配置驱动         | 数据源、拦截规则通过配置/元数据管理，而非硬编码                              |
| 兼容性优先        | 保持与社区版本兼容，便于后续升级合并                                    |
| 不掩盖风险        | 认证/权限暂缓是显式接受的风险，而非默认安全                                |

---

## 二、范围

### 2.1 v1（P0）范围

- 数据源：仅 **StarRocks（JDBC Engine）+ Spark**。
- 能力：数据源 label 代入、危险 SQL 拦截、结构化审计（落 JSON 文件）、连接级+QPS/并发级限流熔断、端到端协议兼容验证（DBeaver 经 JDBC/Thrift → JDBC Engine → StarRocks）与已知 JDBC Engine 流式 bug 修复。
- 技术路线：**方案 A——Fork 主线 + 插件化扩展（`digiwin-plugins` 新模块）+ 少量必要核心 Patch**（不采用字节码/Agent 方式，也不采用独立代理层方式，详见第三章"关键决策"）。

### 2.2 不在范围（P1/P2，明确暂缓）

- 自动查询路由（P1）：v1 由用户显式传 label 选择引擎。
- 监控可观测 Prometheus/Grafana（P1）。
- 统一认证 LDAP/Kerberos/SSO（P2，仅预留插件点）。
- 统一权限 RBAC/表列行级（P2，仅预留插件点）。
- 审计输出 Kafka/ES + 审计查询 UI（P2）。
- 多引擎扩展 Flink/Hive/Trino/其他 JDBC 库（P2）。
- **ProxySQL 不再作为本项目依赖项**：第三方工具（DBeaver/Navicat 等）通过 Kyuubi JDBC 驱动（kyuubi-hive-jdbc）经 Thrift Binary 协议接入，或通过 REST API 接入，均可满足需求。MySQL frontend 已被上游移除（KYUUBI #7528，v1.12），本文档不再规划引入 ProxySQL 或 MySQL 前端。

---

## 三、总体架构

### 3.1 架构图

```
┌───────────────────────────────────────────────────────────────┐
│ 接入层（复用 Kyuubi 内置前端，多协议并存，零外部依赖接入）           │
│ Thrift Binary/HTTP │ REST │ Trino                              │
└───────────────────────────┬─────────────────────────────────────┘
                            ▼
┌───────────────────────────────────────────────────────────────┐
│ 治理层（v1 自研，本次改造核心）                                    │
│ ① DatasourceConfAdvisor（label 代入，凭据不出现在客户端）           │
│ ② SqlInspectionHook（危险 SQL 拦截，P0 新建）                      │
│ ③ RateLimiter 增强（连接级已有 + QPS/并发级新建，P0）               │
│ ④ AuditEventHandler（结构化审计落 JSON 文件，P0）                  │
│ （查询路由、认证/权限：插件点预留，不在 v1 实现）                    │
└───────────────────────────┬─────────────────────────────────────┘
                            ▼
┌───────────────────────────────────────────────────────────────┐
│ 引擎层（复用 Kyuubi Engine，v1 仅 StarRocks + Spark）              │
│ JDBC Engine ──> StarRocks        Spark SQL Engine ──> Spark     │
└───────────────────────────┬─────────────────────────────────────┘
                            ▼
┌────────────────────────────────┐  ┌───────────────────────────┐
│ 存储：数据源注册表（新建元数据表）  │  │ 可观测：kyuubi-metrics     │
│                                 │  │ -> Prometheus（P1）        │
└────────────────────────────────┘  └───────────────────────────┘
```

### 3.2 分层说明

- **接入层**：复用 Kyuubi 内置前端：`KyuubiTBinaryFrontendService`（Thrift Binary，主力协议）、`KyuubiTHttpFrontendService`（Thrift HTTP）、`KyuubiRestFrontendService`（REST API）、`KyuubiTrinoFrontendService`（Trino 兼容）。MySQL frontend 已被上游移除（KYUUBI #7528，v1.12），不再可用。DBeaver/Navicat 等第三方工具通过 Kyuubi JDBC 驱动（kyuubi-hive-jdbc）经 Thrift Binary 协议接入。
- **治理层**：v1 自研核心，全部尽量挂在现有扩展点上。
- **引擎层**：复用 Kyuubi Engine 机制，v1 仅 JDBC Engine（StarRocks）与 Spark SQL Engine。
- **存储层**：数据源注册表为**新建独立存储**（不复用 `JDBCMetadataStore`——已确认该存储只保存会话/批任务生命周期状态，没有数据源静态配置的位置）。

### 3.3 关键决策点（相较更早期草稿设想的调整）

1. **不引入 ProxySQL**：第三方工具通过 Kyuubi JDBC 驱动（kyuubi-hive-jdbc）经 Thrift Binary 协议接入，或通过 REST API 接入，零依赖接入的诉求已满足（MySQL frontend 已被上游移除，KYUUBI #7528）。
2. **QPS/并发限流从 P1 提升到 P0**：确认内部业务系统会程序化直连网关，并发/资源争抢是真实风险，且代码库现状此能力完全空白（仅有连接数级限流）。
3. **查询路由不做**：v1 阶段用户显式指定 label 即可满足需求，自动路由推迟到 P1，降低 v1 复杂度，匹配 1-2 人团队的交付节奏。
4. **技术路线选定"方案 A"**：对比过"Fork+插件化+少量核心 Patch"（推荐，已采纳）、"字节码/Agent 零核心修改"（复杂度和维护风险对小团队不划算）、"独立代理层不改 Kyuubi"（工作量更大且丧失原生扩展能力）三个方案。

---

## 四、组件边界

### 4.1 组件清单

|          组件           |                               职责                               |             依赖              |                                                 挂载方式                                                 |
|-----------------------|----------------------------------------------------------------|-----------------------------|------------------------------------------------------------------------------------------------------|
| DatasourceRegistry    | 数据源元数据 CRUD（label、引擎类型、JDBC URL、用户名、加密凭据、连接池参数）                | 新建独立元数据表                    | 新建                                                                                                   |
| DatasourceConfAdvisor | 会话建立时按 label 查 DatasourceRegistry，注入真实引擎配置                     | DatasourceRegistry          | 挂官方 `SessionConfAdvisor` SPI（参照 `FileSessionConfAdvisor` 实现方式）                                       |
| SqlInspectionHook     | SQL 执行前做危险规则匹配，放行/拦截/告警                                        | 规则配置                        | 挂 `AbstractBackendService.executeStatement`——已确认这是所有协议（Thrift/MySQL/REST）共用的唯一收口点，确保不论客户端走哪种协议都会被拦截到 |
| RateLimiter 增强        | 连接级限流（复用 `SessionLimiter`）+ 新增 QPS/并发查询级限流 + 慢查询自动 Kill + 熔断降级 | 同上收口点 + 现有 `SessionLimiter` | 扩展现有机制，QPS/熔断部分是全新代码                                                                                 |
| AuditEventHandler     | 输出结构化审计（用户/SQL/数据源/耗时/状态/行数/来源 IP）                             | 事件字段需扩展                     | 挂官方 `CustomEventHandlerProvider` SPI，配置 `kyuubi.backend.server.event.loggers=CUSTOM`                 |

### 4.2 需要核心 Patch 的点（无法用纯 SPI 覆盖）

|                    文件                     |                                     改动性质                                     |
|-------------------------------------------|------------------------------------------------------------------------------|
| `AbstractBackendService.executeStatement` | 加一处调用：执行前调 SqlInspectionHook + RateLimiter 检查（加法式，不改变原有转发逻辑）                 |
| `KyuubiOperationEvent`（或对应事件类）            | 补充审计所需字段：`clientIp`、`datasourceLabel`、`rowCount`                             |
| JDBC Engine `ExecuteStatement.scala`      | 修复流式结果集问题；明确大结果集场景下 `incrementalCollect` 的默认行为（当前默认 `false`，走全量物化，存在 OOM 风险） |

### 4.3 典型数据流

```
客户端（Thrift Binary/REST/Trino）
  │
  ▼
AbstractBackendService.executeStatement  ← 唯一收口点
  │  ├─ RateLimiter 检查（连接级 + QPS/并发级，超限直接拒绝/排队）
  │  ├─ SqlInspectionHook 检查（拦截规则命中 → 直接报错返回，不下发引擎）
  │  ▼
DatasourceConfAdvisor（会话建立阶段已注入好 label 对应的真实引擎配置）
  │
  ▼
Engine（JDBC → StarRocks / Spark SQL → Spark）
  │
  ▼
结果回传 + AuditEventHandler 记录结构化审计（含耗时/状态/行数/来源 IP）
```

---

## 五、功能需求与优先级

### 5.1 优先级定义

- **P0** = 本次改造第一期必须落地，直接对应四大痛点（危险 SQL、凭据泄露、无审计、稳定性）。
- **P1** = 紧接第二期，增强性但非当前痛点驱动。
- **P2** = 远期/已明确暂缓项（含认证权限）。

### 5.2 功能需求清单

|   能力域    |  编号   |                                                                需求                                                                | 优先级 |
|----------|-------|----------------------------------------------------------------------------------------------------------------------------------|-----|
| 数据源元数据管理 | FR-1  | 数据源注册 CRUD：label、引擎类型、JDBC URL、用户名、加密凭据、连接池参数；REST API 必须支持且热生效                                                                  | P0  |
|          | FR-2  | label 代入：客户端仅传 `kyuubi.datasource=<label>`，`DatasourceConfAdvisor` 自动注入完整引擎配置，凭据不出现在客户端                                          | P0  |
|          | FR-3  | 凭据加密存储（不落明文）                                                                                                                     | P0  |
| SQL 拦截   | FR-4  | 危险 SQL 拦截规则（DROP/TRUNCATE/无 WHERE 的 UPDATE·DELETE/大表 SELECT * 等），规则可配置                                                           | P0  |
|          | FR-5  | 白名单例外 + 拦截告警（钉钉/邮件）+ 明确错误码                                                                                                       | P0  |
| 审计日志     | FR-6  | 结构化审计字段：用户、SQL、数据源(label)、引擎、时间、耗时、状态、行数、来源 IP                                                                                   | P0  |
|          | FR-7  | 审计落 JSON 文件（按时间分区）                                                                                                               | P0  |
| 客户端协议兼容  | FR-8  | 验证 DBeaver/Navicat 经 Kyuubi JDBC 驱动（Thrift Binary）→ JDBC Engine → StarRocks 端到端查询的协议兼容性                                          | P0  |
|          | FR-9  | JDBC Engine 元数据层级兼容（getSchemas，已由 fde0be6e6 解决）；流式结果集异常在 1.11.1 不复现，大结果集 OOM 作为已知风险观察 | P0  |
| 限流熔断     | FR-10 | 连接级限流（复用现有 `SessionLimiter`）                                                                                                     | P0  |
|          | FR-11 | QPS/并发查询级限流 + 慢查询自动 Kill + 引擎不可用熔断降级                                                                                             | P0  |
| 监控可观测    | FR-12 | Prometheus 指标 + Grafana 大盘 + 告警规则                                                                                                | P1  |
| 查询路由     | FR-13 | 规则路由（简单→StarRocks、复杂→Spark），用户仍可用 label 显式覆盖                                                                                     | P1  |
| 统一认证     | FR-14 | LDAP/Kerberos/SSO 接入，插件点预留                                                                                                       | P2  |
| 统一权限     | FR-15 | RBAC + 表/列/行级，插件点预留                                                                                                              | P2  |
| 审计增强     | FR-16 | 审计输出 Kafka → ES/ClickHouse + 查询 UI                                                                                               | P2  |
| 多引擎扩展    | FR-17 | Flink/Hive/Trino/其他 JDBC 库接入                                                                                                     | P2  |

### 5.3 各能力详细需求

#### 5.3.1 数据源元数据管理（P0）

- **FR-1**：字段含 label（唯一标识）、引擎类型（jdbc/spark）、JDBC 类型（starrocks）、驱动类、JDBC URL、用户名、加密密码、连接池参数、状态、描述。增删改查热生效，无需重启。
  - REST API：`GET/POST/PUT/DELETE /api/v1/datasources`、`GET /api/v1/datasources/{label}`、`POST /api/v1/datasources/refresh`（手动刷新缓存）。
  - 缓存策略：启动加载全量到内存；定时刷新（默认 60s）；API 变更主动刷新。
- **FR-2**：示例：`beeline -u "jdbc:kyuubi://gateway:10009/?kyuubi.datasource=sr-prod" -n username`。
- **FR-3**：密码等凭据加密后落库，任何时候不以明文形式出现在日志、连接串、API 响应中。

#### 5.3.2 SQL 拦截（P0）

- **FR-4**：默认规则集：拦截 `DROP`、`TRUNCATE`、无 `WHERE` 的 `UPDATE`/`DELETE`、超阈值的 `SELECT *`；规则以正则为主，AST 规则作为增强可选项。

  ```properties
  kyuubi.server.sql.inspection.enabled=true
  kyuubi.server.sql.inspection.rules=deny:DROP,deny:TRUNCATE,deny:DELETE/WITHOUT_WHERE
  kyuubi.server.sql.inspection.whitelist=admin
  ```
- **FR-5**：拦截触发钉钉/邮件告警；返回明确错误码（如 `SQL_BLOCKED`）。

#### 5.3.3 审计日志（P0）

- **FR-6/7**：`statementId`、`sessionId`、`user`、`datasourceLabel`、`statement`、`state`、`startTime`、`completeTime`、`elapsedTime`、`rowCount`、`exception`、`clientIp`；输出到 `kyuubi.backend.server.event.loggers=CUSTOM` 对应的 JSON 文件，按时间分区落盘。

#### 5.3.4 客户端协议兼容（P0）

- **FR-8**：验证 DBeaver/Navicat 经 Kyuubi JDBC 驱动（`kyuubi-hive-jdbc`）→ Thrift Binary 前端 → JDBC Engine → StarRocks 端到端查询。MySQL frontend 已被上游移除（KYUUBI #7528，v1.12），不再作为客户端接入方式。
- **FR-9**：DBeaver 看不到 StarRocks 的 db、所有表平铺的根因是 `MySQLDialect` 未实现 `getSchemasOperation`（基类默认 `featureNotSupported()`），已由提交 `fde0be6e6 Support GetSchemas for MySQL-family JDBC dialects` 解决：把 StarRocks database 映射为 schema、catalog 返回 NULL（避免 DBeaver 用 `def.db.table` 错误限定）。原假设的 `Streaming result set is still active` 流式异常在 1.11.1 不复现，drain-and-close 修复作废。`MySQLDialect.createStatement` 仍用 `fetchSize=Integer.MIN_VALUE`、`incrementalCollect` 默认 false（全量物化），大结果集 OOM 作为已知风险，P1 评估 `kyuubi.engine.jdbc.operation.incremental.collect` 推荐配置。

#### 5.3.5 限流熔断（P0）

- **FR-10**：复用现有 `kyuubi.server.limit.connections.*` 系列配置。
- **FR-11**：新增 QPS/并发查询级限流（当前代码库完全空白，需新建）；慢查询自动 Kill（基于 `kyuubi.operation.timeout` 或新增专用超时配置）；引擎不可用时快速失败并返回明确错误，而非无限等待。

#### 5.3.6 监控可观测（P1）

- **FR-12**：复用 `kyuubi-metrics` 采集 Prometheus 指标；Grafana 大盘；告警规则。

#### 5.3.7 查询路由（P1）

- **FR-13**：规则路由 + 用户/label 显式覆盖；路由规则热更新。

#### 5.3.8～5.3.10 统一认证/权限/多引擎扩展（P2）

- 插件点预留，具体方案留待 P2 单独立项设计。

---

## 六、非功能需求

| 维度  |                         指标                         |
|-----|----------------------------------------------------|
| 性能  | 网关转发开销 < 100ms（对齐 JDBC 直连模式实测）；单节点支持 500+ 并发连接     |
| 高可用 | 复用 `kyuubi-ha`（ZooKeeper 服务发现）多实例部署；数据源注册表存储需支持高可用 |
| 可扩展 | 新增引擎/数据源/规则不改核心主流程，走插件+配置                          |
| 安全  | 凭据加密存储；危险 SQL 拦截；认证/权限本期不实现，作为已知风险接受               |
| 可测试 | 每个自研插件带单元测试；端到端集成测试覆盖 label 代入→拦截→限流→审计整条链路        |

---

## 七、验收标准（v1/P0）

1. beeline 仅传 label 即可访问 StarRocks 与 Spark，连接串不含明文凭据。
2. `DROP TABLE`/无 `WHERE` 的 `DELETE` 被拦截并返回明确错误码、触发告警；白名单用户可正常执行。
3. 每次查询产生结构化 JSON 审计记录（用户/SQL/数据源/引擎/耗时/状态/行数/来源 IP）。
4. DBeaver 经 Kyuubi JDBC 驱动（Thrift Binary）可正常查看 StarRocks 的 db 层级与表结构并读取数据（getSchemas 已由 fde0be6e6 支持）。
5. 单用户/IP 超出连接数或 QPS 阈值被拒绝并返回明确提示；慢查询超时自动 Kill；引擎不可用时返回明确降级错误而非挂起。
6. 所有自研插件有单元测试覆盖，端到端集成测试覆盖 P0 全部五项能力。

---

## 八、风险与应对

|                          风险                          |                                   应对                                   |
|------------------------------------------------------|------------------------------------------------------------------------|
| DBeaver 看不到 StarRocks db/表平铺（getSchemas 未实现） | 已由提交 fde0be6e6 解决；端到端回归用例见 Task 7.1（DBeaver -> Thrift Binary -> JDBC Engine -> StarRocks） |
| JDBC Engine `incrementalCollect` 默认 false，大结果集全量物化 OOM 隐患 | P1 评估 `kyuubi.engine.jdbc.operation.incremental.collect` 推荐配置；当前 1.11.1 无流式报错 |
| 数据源元数据热生效与多实例缓存一致性                                   | 定时刷新 + 变更主动刷新 + 手动刷新 API                                               |
| SQL 拦截规则误判阻断正常查询                                     | 白名单机制 + 告警先行于硬拦截 + 规则灰度发布                                              |
| 核心 patch 影响社区版本合并/后续升级                               | 严守"少量、集中、加法式"原则，改动点已在第四章列清单                                            |
| 认证/权限本期暂缓导致安全缺口                                      | v1 依赖网络隔离 + 限流 + 审计可追溯作为补偿控制，P2 尽快补齐，风险显式接受而非掩盖                        |
| 1-2 人团队 P0 范围仍偏大（5 大类能力）                             | 需求文档只定义 P0 范围，具体内部实施顺序留给实现计划阶段决定                                       |

---

## 九、约束与依赖

- 基于 Apache Kyuubi 1.11.1（`digiwin-1.11.1` 分支）二次开发。
- v1 核心引擎仅 StarRocks + Spark，其余为扩展点。
- 认证/权限暂缓，仅预留插件点，不在本期实现。
- 不改 Kyuubi 核心主流程，仅限第四章列出的少量集中 patch。
- 数据源注册表为新建独立存储，不复用 `JDBCMetadataStore`。
- 自研代码集中在新增 `digiwin-plugins` 模块。
- 团队规模 1-2 人，无硬性 deadline，按 P0/P1/P2 分期渐进交付。

---

## 十、阶段规划

|   阶段   |                                        内容                                         |
|--------|-----------------------------------------------------------------------------------|
| v1（P0） | 数据源 label 代入 + SQL 拦截 + 审计落文件 + 连接级/QPS/并发限流熔断 + 端到端协议兼容验证与 JDBC Engine 流式 bug 修复 |
| P1     | 查询路由 + 监控可观测（Prometheus/Grafana）                                                  |
| P2     | 统一认证 + 统一权限 + 审计 Kafka/ES + 查询 UI + 多引擎扩展（Flink/Hive/Trino）                       |

> P0 内部具体实施顺序（先做哪个组件、如何拆分任务）由后续实现计划（implementation plan）阶段决定，本文档只定义范围与优先级。

---

## 附录

### A. 名词解释

|               术语               |                                   说明                                    |
|--------------------------------|-------------------------------------------------------------------------|
| Kyuubi                         | Apache Kyuubi，分布式多租户 SQL 网关                                             |
| JDBC Engine                    | Kyuubi 的 JDBC 引擎，v1 用于对接 StarRocks                                      |
| Spark Engine                   | Kyuubi 的 Spark SQL 引擎                                                   |
| 数据源 label                      | 数据源唯一标识，如 `sr-prod`，客户端据此引用数据源                                          |
| SessionConfAdvisor             | Kyuubi 会话配置顾问扩展点，会话建立时注入配置                                              |
| ~~KyuubiMySQLFrontendService~~ | 已被上游移除（KYUUBI #7528，v1.12），不再可用。第三方工具改经 Kyuubi JDBC 驱动（Thrift Binary）接入 |

### B. 参考文档

- [Apache Kyuubi 官方文档](https://kyuubi.apache.org/)
- [Kyuubi 配置说明](https://kyuubi.readthedocs.io/en/master/deployment/settings.html)
- 内部文档：《Kyuubi 调研文档》
- 内部文档：《统一数据网关方案设计与验证》

