# 管理治理分支新增功能说明

面向运维和使用人员的安装及操作入口见 [安装、使用与运维手册](operations-and-user-manual.md)。本文主要记录分支实现和历史验收，不代表最新镜像已在目标集群验收通过。

本文记录 `codex/digiwin-1.12.0-admin-governance` 分支相较 `digiwin-1.12.0` 的新增功能、实现方式、数据边界和验收情况，供开发、测试、部署及后续维护使用。

## 1. 对比范围

|  项目  |                                内容                                 |
|------|-------------------------------------------------------------------|
| 基线分支 | `digiwin-1.12.0`                                                  |
| 基线提交 | `b38f3232750dec839262eb6ac542d6eb75eedd6e`                        |
| 当前分支 | `codex/digiwin-1.12.0-admin-governance`                           |
| 当前提交 | `6a1b7fb267f49cdcb1f3cfa471724a6aa3f5942b`                        |
| 对比方式 | `git diff digiwin-1.12.0...codex/digiwin-1.12.0-admin-governance` |
| 功能提交 | `eb4021068`、`6a1b7fb26`                                           |
| 变更规模 | 87 个文件，新增约 19,591 行，删除约 643 行                                     |

上述统计截至 2026-09-15，不包含本说明文档自身，也不包含本地运行时生成的身份源、账号绑定或权限配置文件。

## 2. 功能总览

|     模块     |                             新增能力                             |              主要入口              |
|------------|--------------------------------------------------------------|--------------------------------|
| Overview   | Kyuubi 运行状态、SQL、Engine、队列、REST、JVM、Metadata 健康监控及趋势          | `/ui/overview`                 |
| SQL Record | SQL 执行记录、筛选、详情、失败诊断、自动刷新和 CSV 导出                             | `/ui/management/sql-record`    |
| 数据源管理      | JDBC 数据源、凭据、连接池、连接测试及启停管理                                    | `/ui/management/datasource`    |
| 管理工作台      | Session、Operation、Engine、Server 的查询、筛选、详情与受控操作               | `/ui/management/*`             |
| 系统配置       | 运行时配置快照、敏感值脱敏、受控配置刷新                                         | `/ui/management/configuration` |
| 管理审计       | 管理员配置变更、操作者、目标对象和结果查询                                        | `/ui/management/audit`         |
| Audit Log  | Kyuubi 原生 Server/Session/Operation/SQL 拦截事件，JSON/Kafka 可配置查询 | `/ui/management/event-audit`   |
| 管理员权限      | 只读管理员、平台管理员及管理接口资源级鉴权                                        | 后端权限接口；前端整合进访问管理               |
| 企业访问管理     | LDAP/IAM 身份源、账号绑定、访问策略、管理员角色、Session Profile、托管认证            | `/ui/management/access`        |

## 3. 总体实现关系

```text
LDAP / IAM
    │ 目录查询、账号密码验证
    ▼
企业身份源 ──► 账号授权绑定 ──┬──► 管理员角色
                             ├──► 用户拒绝/免配额名单
                             └──► Session Profile / 用户默认配置
                                      │
JDBC / Thrift / Web REST ─────────────┼──► Kyuubi Server
                                      │
MetricsSystem ──► Overview 实时快照 ──┤
        └──────► 进程内趋势采样        │
                                      │
Operation 生命周期 ──► SQL Record ────┤
HTTP / Thrift 请求 ──► Audit Log ─────┘
```

实现仍遵守 Kyuubi Server 与各 Engine 的模块边界。监控、认证、审计和管理能力位于 Server 侧，没有把 Spark、Flink、Trino 等 Engine 运行时逻辑引入 `kyuubi-server`。

## 4. Overview 监控首页

### 4.1 监控范围

Overview 新增以下指标域：

- Kyuubi Server 启动次数和当前存活节点数。
- 各 Engine 类型累计拉起次数和 Session Profile 数量。
- 活动用户数、活动 Session 数。
- 执行队列大小、正在执行、等待执行和存活工作线程数。
- SQL Operation 的打开、运行、等待、失败、失败率和 P50/P95/P99 延迟。
- Engine 启动中、等待许可、失败、超时和启动延迟。
- Batch 总量、失败量、失败率和最长等待时间。
- REST 活跃请求、累计请求、失败请求、失败率和 P95 延迟。
- JVM 堆/非堆内存、线程、死锁、GC 次数和 GC 时间。
- Metadata 请求打开数、总量、失败、重试和失败率。
- Thrift SSL 证书剩余有效期（指标存在时展示）。

“执行队列”使用 Kyuubi 已有执行线程池指标：

- `size`：队列中尚未开始执行的任务数。
- `active`：当前正在执行任务的线程数。
- `waiting`：与 `size` 使用同一个工作队列 Gauge，避免出现两个统计口径。
- `alive`：当前执行线程池中的存活线程数。

### 4.2 指标来源与趋势采样

Overview 不依赖 Prometheus 服务，也不会通过 HTTP 反向抓取 `/metrics`。它直接读取 Kyuubi 进程内的 `MetricsSystem` 注册表，因此页面与 Prometheus Reporter 导出的指标来自同一数据源。

`OverviewMetrics` 每 15 秒采样一次 SQL 总量、失败量、P95 延迟和队列长度：

| 时间范围 |  聚合粒度 |
|------|------:|
| 1 小时 |  1 分钟 |
| 1 天  | 15 分钟 |
| 7 天  |  1 小时 |

趋势样本最多保留 7 天，仅保存在当前 Kyuubi Server 进程内，服务重启后重新开始采集。页面会区分：

- `INITIALIZING`：指标正在初始化。
- `READY`：指标正常更新。
- `STALE`：超过两个采样周期未更新。

### 4.3 健康评估

`OverviewHealthEvaluator` 根据 SQL 失败率和延迟、队列积压、Engine 失败、REST 失败率、JVM 堆使用率、线程死锁、Metadata 失败率、SSL 证书有效期及指标新鲜度，输出：

- `NORMAL`
- `WARNING`
- `CRITICAL`
- `UNKNOWN`

页面展示具体异常项、当前值和阈值，并可从 SQL 趋势或队列状态下钻到 SQL Record。

### 4.4 REST API

| 方法  |                路径                 |                   说明                    |
|-----|-----------------------------------|-----------------------------------------|
| GET | `/api/v1/overview/summary`        | 获取当前监控快照及健康状态                           |
| GET | `/api/v1/overview/trend?range=1h` | 获取 SQL、失败、P95 延迟和队列趋势；支持 `1h`、`1d`、`7d` |

## 5. SQL 执行记录

### 5.1 数据采集

在 `KyuubiOperation` 状态变更时，将 `ExecuteStatement` 对应的 `KyuubiOperationEvent` 写入 `SqlExecutionRecordStore`。记录字段包括：

- SQL 原文和最多 240 字符的摘要。
- 用户、Session ID、Engine 类型和 Operation 状态。
- 创建、开始、完成时间。
- 排队等待时间和执行耗时。
- 失败原因。

Engine 类型优先使用数据源标签对应的 Engine；没有数据源标签或标签无法解析时，回退到 Session 实际生效的 Engine 类型，避免页面显示 `-`。

### 5.2 保存边界

SQL Record 是服务内运行诊断数据，不是持久化审计库：

- 单进程最多保存 10,000 条。
- 数据保留 24 小时。
- 超出容量时淘汰最早记录。
- Kyuubi Server 重启时清空。
- 多 Server 部署时，每个节点仅保存自身记录。

### 5.3 页面能力

- 按时间、用户、Session、Engine、状态和 SQL 关键字筛选。
- 分页查询，服务端单次最多返回 200 条。
- 5 秒、10 秒、30 秒自动刷新，或关闭自动刷新。
- 刷新失败时保留已有数据，并显示错误提示。
- 查看 SQL、错误、排队时间、执行时间及生命周期时间线。
- 按当前筛选条件导出 CSV；导出最多重新获取前 200 条。
- CSV 使用 UTF-8 BOM，并处理逗号、引号和换行，兼容 Excel。

### 5.4 REST API

| 方法  |             路径             |                                      说明                                       |
|-----|----------------------------|-------------------------------------------------------------------------------|
| GET | `/api/v1/sql-records`      | 分页查询；支持 `user`、`sessionId`、`engineType`、`state`、`keyword`、`fromTime`、`toTime` |
| GET | `/api/v1/sql-records/{id}` | 查询单条 SQL 执行详情                                                                 |

## 6. Session、Operation、Engine、Server 管理增强

本分支将原有基础表格改造为完整管理工作台，数据仍来自 Kyuubi 原有管理 REST API。

### 6.1 Session

- 用户、Session 类型等条件筛选。
- 活动数量和状态统计。
- 自动刷新和手动刷新。
- 详情抽屉展示客户端、配置、Engine、时长和 Operation 数量。
- 管理员确认后关闭 Session。
- 从 Session 跳转并筛选其 Operation。

### 6.2 Operation

- 按用户、Session 和状态筛选。
- 展示运行、完成、失败等状态统计。
- 详情抽屉展示 SQL、进度、时间、错误和指标。
- 对运行中 Operation 执行取消或关闭。

### 6.3 Engine

- 按用户、Engine 类型和状态筛选。
- 展示在线数量、类型和用户统计。
- 详情抽屉展示 Engine ID、地址、版本、命名空间和节点属性。
- 在开启代理能力时进入 Engine UI。
- 管理员确认后移除 Engine。

### 6.4 Server

- 按主机和状态筛选。
- 展示节点总量、运行状态和版本分布。
- 详情抽屉展示服务地址、实例、命名空间、版本和节点属性。

### 6.5 数据源管理

- 新增独立 Data Source 页面,展示数据源总量、启用/停用数量、数据源类型和连接配置卡片。
- 支持 StarRocks、MySQL、PostgreSQL、Oracle 模板,并支持关键字、状态和类型筛选；不展示 Kyuubi JDBC Engine 未内置适配的 SQL Server、SQLite、Generic 模板。
- 支持 Iceberg Hive Catalog，统一维护 Metastore、Warehouse、S3 Endpoint 和可选 Session Profile；客户端只传 `kyuubi.datasource=<label>`，网关自动选择 Spark SQL Engine 并注入 Catalog 配置。
- Iceberg Spark Engine 按数据源标签及最终配置指纹隔离；相同配置允许复用，不同 Catalog 或资源规格不会串用 Engine。
- 支持新增、编辑、详情、启停和删除；编辑时密码留空会保持原凭据,服务端不会把明文或密文返回浏览器。
- 提供保存前草稿测试及已保存数据源测试,成功时展示数据库产品、版本和耗时,失败时展示明确原因并返回 HTTP 502。
- 停用数据源不会进入新 Session,但仍可由管理员执行连接诊断。
- 连接池参数使用白名单和数值范围校验,不能覆盖 Kyuubi Engine 类型、连接地址或凭据等保留配置。
- JDBC URL 拒绝内嵌 `password`、`passwd`、`pwd`,避免敏感信息进入日志、审计和页面。

## 7. 配置与策略管理

### 7.1 运行时配置快照

新增 `GET /api/v1/admin/configuration`，返回：

- 当前用户及安全模式。
- 当前认证方式和全局管理员。
- 按 Key 前缀分类的运行时配置。
- Hadoop、Kubernetes、User Defaults、Access Policies 的受控刷新入口。

包含 `password`、`passwd`、`secret`、`token`、`access.key`、`private.key`、`credential` 等关键字的配置值统一返回 `******`。系统配置页面为只读快照，不提供任意配置写入能力。

### 7.2 管理策略

新增 `GET/PUT /api/v1/admin/policies`，统一管理：

- Session Profile。
- 用户默认配置。
- 不受连接配额限制的用户。
- 拒绝访问的用户。
- 拒绝访问的 IP。

策略写入采用临时文件加原子替换，避免部分写入破坏配置。Session Profile 保存为 `kyuubi-session-<profile>.conf`；用户默认配置和访问名单更新到 `kyuubi-defaults.conf`。支持的策略会同步刷新到当前 SessionManager，无需为每次名单变更重启服务。

当前前端已将策略入口整合进“访问管理”：

- Session Profile 在“会话模板”页签维护。
- 用户默认配置、Profile、拒绝访问、免配额通过账号授权绑定维护。
- IP 拒绝名单在“授权详情”页签维护。
- 旧 `/management/policy` 路径保留，并重定向到会话模板页签。

## 8. 审计

### 8.1 管理审计

管理审计复用认证过滤链和请求上下文，记录：

- 用户和认证类型。
- 客户端 IP、代理 IP、`X-Forwarded-For` 链。
- HTTP 方法、URI、查询参数、协议和响应状态。
- 策略、权限、身份源和授权变更等管理员动作。

查询参数中的密码、Secret、Token、Credential 等敏感字段会在写入前脱敏。

管理审计的持久化规则如下：

- 默认路径：`$KYUUBI_HOME/logs/kyuubi-audit.jsonl`。
- 可通过 `KYUUBI_AUDIT_LOG_PATH` 指定文件。
- 最多保留 2,000 条、24 小时。
- 使用文件锁更新 JSONL，多个 Kyuubi Server 可读取同一个共享审计文件。
- 持久化失败不会阻断业务请求，但会记录警告日志。

### 8.2 Kyuubi 原生 Audit Log

Audit Log 直接消费 Kyuubi `KyuubiEvent`，记录 Server、Session、Operation 和
`sql_blocked` 事件。页面可选择 JSON 文件或 Kafka 作为存储方式，保存后立即切换新事件的写入和查询，无需重启 Server。

- JSON 模式仅接受 `file://` 目录，页面直接读取原生事件文件。
- Kafka 模式支持 PLAINTEXT、SSL、SASL_PLAINTEXT 和 SASL_SSL，页面直接消费配置的 Topic。
- “测试配置”会验证目录可写性，或向 Kafka 写入探针并按分区和偏移回读。
- Kafka 密码和 Truststore 密码使用现有 CredentialAccessor 加密保存，响应中不返回明文。
- 原始事件返回页面前会递归脱敏；切换存储方式不会迁移或删除旧存储中的事件。
- 页面配置保存在可写配置目录的 `kyuubi-audit-config.json`。

### 8.3 REST API

|  方法  |                 路径                 |                   说明                    |
|------|------------------------------------|-----------------------------------------|
| GET  | `/api/v1/admin/audit`              | 查询管理审计，支持按用户、动作、结果和时间范围筛选               |
| GET  | `/api/v1/admin/event-audit/config` | 查询原生 Audit Log 当前配置和健康状态                |
| PUT  | `/api/v1/admin/event-audit/config` | 保存 JSON/Kafka 配置并立即生效                   |
| POST | `/api/v1/admin/event-audit/test`   | 测试 JSON 目录或 Kafka Topic 的真实读写           |
| GET  | `/api/v1/admin/event-audit/events` | 从当前 JSON 目录或 Kafka Topic 查询真实 Kyuubi 事件 |

## 9. 管理员权限模型

### 9.1 角色

当前仅保留两类管理角色：

|  角色   |        标识        |           能力           |
|-------|------------------|------------------------|
| 只读管理员 | `viewer`         | 查看管理数据，不能修改、刷新、关闭或删除资源 |
| 平台管理员 | `platform-admin` | 拥有全部平台管理能力             |

普通用户不分配管理员角色。其数据库、表、列等数据权限仍由计算引擎、LDAP/IAM 或企业数据权限系统决定，不由本模块管理。

### 9.2 鉴权方式

后端按资源和操作执行权限判断。内部操作标识包括 `read`、`write`、`control`、`delete`、`refresh` 和 `manage`，仅用于 REST API 鉴权，不再作为前端权限矩阵展示。

权限资源模型定义了 Overview、Session、Operation、Engine、Server、Batch、SQL Record、Policy、Configuration、Audit、Datasource、SQL Rule、Access 和 Permissions，用于统一前端能力表达与后端鉴权语义。

当前后端已在 Access、Configuration、Permissions、Policy、Audit、SQL Record、Session、Operation、Server、Batch、Datasource 和 SQL Rule 等管理接口执行显式权限校验；Engine 继续沿用 Kyuubi 原有的管理员/代理用户校验逻辑，Overview 作为只读监控入口提供运行状态数据。

兼容规则：

- `kyuubi.server.administrators` 中的用户继续视为平台管理员。
- 未启用安全认证时，保持 Kyuubi 原有行为，管理请求按平台管理员处理。
- 权限更新时校验用户、角色和重复分配，并防止移除最后一个可用平台管理员。

### 9.3 存储与接口

- 默认文件：与 `kyuubi-defaults.conf` 同目录的 `kyuubi-admin-permissions.json`。
- 可通过 `KYUUBI_ADMIN_PERMISSIONS_PATH` 指定路径。
- 写入使用原子替换。
- `GET /api/v1/admin/permissions` 获取角色和绑定。
- `PUT /api/v1/admin/permissions` 整体替换角色绑定。

权限接口继续作为后端数据源存在，但独立权限页面已取消。旧 `/management/permissions` 路径会迁移到“访问管理”的“授权详情”页签。

## 10. 企业访问管理

### 10.1 设计原则

Kyuubi 不创建或维护企业用户账号。LDAP、IAM 或统一认证中心仍是账号主数据源；Kyuubi 只维护：

- 身份源连接信息。
- 外部账号与 Kyuubi Principal 的绑定。
- 是否允许访问。
- 管理员角色。
- Session Profile、用户默认配置和免配额标记。

当前版本只支持用户账号绑定，不支持将用户组直接绑定为 Kyuubi 授权主体。

### 10.2 身份源

支持两类身份源：

- LDAP/LDAPS：Base DN、Bind DN、用户/用户组过滤器、DN 模板及属性映射。
- IAM HTTP/HTTPS：用户、用户组、认证接口路径及 JSON 字段映射。

支持连接测试、目录搜索、超时配置和启用/停用。LDAP 服务密码或 IAM Token 不保存在 JSON 文件中，仅保存环境变量名称，运行时从服务端环境变量读取。

身份源配置默认保存到与 `kyuubi-defaults.conf` 同目录的 `kyuubi-identity-access.json`，也可通过 `KYUUBI_IDENTITY_ACCESS_PATH` 指定。存在账号绑定时禁止删除对应身份源。

### 10.3 授权绑定

绑定账号前会重新查询身份源，确认外部账号仍然存在。每个 Kyuubi Principal 只能绑定一个外部身份，避免同名账号产生认证歧义。

保存绑定时会同步更新已有 Kyuubi 配置来源：

|        绑定字段        |                  投影目标                  |
|--------------------|----------------------------------------|
| `role`             | `kyuubi-admin-permissions.json`        |
| `access=DENIED`    | 用户拒绝名单                                 |
| `quotaExempt=true` | 用户无限制名单                                |
| `profile`          | 用户默认配置中的 `kyuubi.session.conf.profile` |
| `userDefaults`     | `kyuubi-defaults.conf` 用户默认配置          |

解除绑定会同步删除上述本地投影，但不会删除 LDAP/IAM 中的账号。

### 10.4 托管认证

启用前必须满足：

1. 至少存在一个已启用的 LDAP/IAM 身份源。
2. 至少存在一个“允许访问”的平台管理员绑定。

启用操作不要求管理员再次输入 LDAP/IAM 用户名和密码。它将以下配置写入 `kyuubi-defaults.conf`：

- `kyuubi.authentication=CUSTOM`
- HTTP Basic 和 Thrift 使用 `ManagedIdentityAuthenticationProvider`
- 有效平台管理员同步到 `kyuubi.server.administrators`

配置保存后必须重启 Kyuubi Server 才会切换认证方式。重启后：

- 只有已绑定、允许访问且所属身份源已启用的用户才能进入认证流程。
- 用户密码由对应 LDAP/IAM 实时验证，Kyuubi 不保存用户密码。
- Web 管理接口和 JDBC/Thrift 使用同一个托管认证提供者。
- 未绑定、被拒绝、身份源停用或外部账号验证失败的用户无法登录。

停用企业认证会把 `kyuubi.authentication` 写回 `NONE`，同样需要重启生效。身份源、账号绑定、角色和 Session Profile 不会被删除。

### 10.5 前端页面

“访问管理”包含三个页签：

- 认证接入：身份源新增、编辑、删除和连接测试。
- 授权详情：授权账号列表、搜索、身份源/角色/状态筛选、目录授权、详情、编辑、解除、角色说明和 IP 拒绝名单。
- 会话模板：Session Profile 的解释、新增、编辑和删除。

页面会明确显示企业认证是否启用、授权配置当前是否参与登录校验，以及启用或停用后需要重启。

### 10.6 REST API

|   方法   |                       路径                       |        说明         |
|--------|------------------------------------------------|-------------------|
| GET    | `/api/v1/admin/access`                         | 获取身份源、账号绑定和托管认证状态 |
| PUT    | `/api/v1/admin/access/providers`               | 新增或修改身份源          |
| DELETE | `/api/v1/admin/access/providers/{id}`          | 删除无绑定的身份源         |
| POST   | `/api/v1/admin/access/providers/{id}/test`     | 测试身份源连接           |
| GET    | `/api/v1/admin/access/providers/{id}/subjects` | 查询外部用户或用户组目录      |
| PUT    | `/api/v1/admin/access/bindings`                | 新增或修改用户授权绑定       |
| DELETE | `/api/v1/admin/access/bindings/{id}`           | 解除本地授权绑定          |
| POST   | `/api/v1/admin/access/activate`                | 保存托管认证启用配置        |
| POST   | `/api/v1/admin/access/deactivate`              | 保存托管认证停用配置        |

## 11. 前端工程改进

- 补充中英文管理页面文案。
- 统一请求错误处理和页面错误提示。
- 增加 Vitest/JSDOM 测试环境隔离。
- 为 Overview、SQL Record、Session、Operation、Engine、Server、Configuration、Audit 和 Access Management 增加单元测试。
- 新增 `pnpm-workspace.yaml` 并更新前端锁文件。
- 管理页面采用统计卡片、状态标签、趋势图、详情抽屉、确认弹窗、空状态和响应式布局，不再只是基础表单或原始表格。

## 12. 数据与持久化边界

|         数据          |              保存位置               |        生命周期         |      集群范围      |
|---------------------|---------------------------------|---------------------|----------------|
| Overview 当前快照       | `MetricsSystem`                 | 实时                  | 当前 Server      |
| Overview 趋势         | 进程内存                            | 7 天；重启清空            | 当前 Server      |
| SQL Record          | 进程内存                            | 24 小时/10,000 条；重启清空 | 当前 Server      |
| 管理审计                | JSONL 文件                        | 24 小时/2,000 条       | 可使用共享文件        |
| Kyuubi 原生 Audit Log | JSON 事件目录或 Kafka Topic          | 页面配置的保留天数           | 共享目录或 Kafka 集群 |
| 身份源与账号绑定            | `kyuubi-identity-access.json`   | 持久化                 | 取决于配置文件是否共享    |
| 管理员角色绑定             | `kyuubi-admin-permissions.json` | 持久化                 | 取决于配置文件是否共享    |
| 用户默认配置、黑白名单         | `kyuubi-defaults.conf`          | 持久化并支持热刷新           | 取决于配置文件分发方式    |
| Session Profile     | `kyuubi-session-*.conf`         | 持久化                 | 取决于配置文件分发方式    |

多 Server 部署时，Overview 趋势和 SQL Record 不做跨节点聚合。身份源、角色和策略文件需要放在共享存储，或通过部署系统保证每个节点配置一致。

## 13. 兼容性和安全边界

- 没有修改 Hive Thrift IDL，不影响现有 JDBC/Thrift 协议字段。
- 没有新增数据库 Schema 或数据库迁移。
- 不依赖外部 Prometheus 服务；已有 Prometheus Reporter 可以继续独立抓取同一指标注册表。
- 不在 Server 侧引入 Spark/Flink/Trino 运行时依赖。
- 不在页面、REST 响应或身份源 JSON 中保存 LDAP 密码或 IAM Token。
- 生产环境启用托管认证前，必须确认至少一个平台管理员绑定有效，并完成重启演练。
- `kyuubi.authentication=NONE` 时，企业授权配置不会限制现有用户登录。
- 数据库、表、列和 SQL 权限仍由下游计算引擎或企业权限系统负责。

## 14. 测试与验收

### 14.1 后端测试

新增或扩展以下测试套件：

- `OverviewMetricsSuite`
- `OverviewHealthEvaluatorSuite`
- `OverviewResourceSuite`
- `SqlExecutionRecordStoreSuite`
- `SqlRecordsResourceSuite`
- `AuditRecordStoreSuite`
- `AdminPoliciesFileStoreSuite`
- `AdminPermissionStoreSuite`
- `AdminResourceSuite`
- `IdentityAccessStoreSuite`
- `IdentityDirectoryServiceSuite`
- `AdminAccessResourceSuite`
- `DatasourceConnectionTesterSuite`
- `DatasourceRegistrySuite`
- `DatasourceConfAdvisorSuite`
- `DatasourcesResourceSuite`
- `KyuubiOperationSuite`

身份访问测试会启动真实的进程内 LDAP 和轻量 IAM HTTP 服务，覆盖连接测试、目录查询、正确/错误密码、多身份源认证、绑定投影、解绑清理和认证启停配置。

### 14.2 前端测试

- Access Management 定向单元测试：8/8 通过。
- Overview、SQL Record 和各管理页面均有对应单元测试。
- ESLint、`vue-tsc --noEmit` 和生产构建通过。
- 提交前已执行 `dev/reformat`。

### 14.3 浏览器端到端验证

已验证：

- Overview 空闲、成功 SQL、失败 SQL、并发排队、时间范围切换和 SQL Record 下钻。
- 页面指标与 `/api/v1/overview/*`、`/metrics` 同源指标对账。
- SQL Record 的筛选、详情、刷新、失败原因、耗时和导出请求。
- Session、Operation、Engine、Server 的真实数据、详情和管理动作。
- System Setting、Management Audit 和 Audit Log 的真实 REST 数据及筛选。
- Audit Log 的 JSON/Kafka 在线配置、探针测试、热切换及 Beeline SQL 五阶段原生事件对账。
- LDAP 身份源连接、alice/bob/carol 目录查询、授权筛选、授权详情和编辑入口。
- 数据源新增、StarRocks 真实连接、编辑、连接池、启停、筛选、详情、失败提示及凭据持久化对账。
- 从页面创建 Iceberg 数据源并测试真实 HMS `172.16.7.137:9083`，随后仅通过数据源
  label 启动 Spark 3.5.8 Engine，完成 Iceberg 建库、建表、写入、查询和清理。
- 验证数据源配置变化会生成新的配置指纹和 Engine subdomain，避免复用旧 Catalog 配置。
- 旧 Policy/Permissions 路由兼容跳转。
- 浏览器控制台无运行错误。

## 15. 部署注意事项

1. 确保 `KYUUBI_HOME` 或 `KYUUBI_CONF_DIR` 指向可写配置目录。
2. 身份源服务密码或 IAM Token 必须通过 `secretEnvironment` 引用的服务端环境变量提供。
3. 生产环境建议显式设置：
   - `KYUUBI_IDENTITY_ACCESS_PATH`
   - `KYUUBI_ADMIN_PERMISSIONS_PATH`
   - `KYUUBI_AUDIT_LOG_PATH`
4. 多节点部署需要统一分发身份源、角色、默认配置和 Session Profile 文件。
5. 启用或停用企业认证后必须重启所有 Kyuubi Server 节点。
6. 重启前确认授权详情中至少有一个可用的平台管理员。
7. SQL Record 和 Overview 趋势在重启后清空属于当前设计，不应作为长期审计或容量报表使用。

## 16. 当前限制

- Overview 趋势和 SQL Record 尚未持久化，也未做跨 Server 聚合。
- SQL Record 单次导出最多 200 条。
- 授权绑定当前只支持用户，不支持用户组直接授权。
- 管理员角色当前只有只读管理员和平台管理员，不支持页面自定义角色。
- IAM 接口采用约定式 HTTP/JSON 映射，不包含 OAuth/OIDC 浏览器跳转流程。
- 托管认证切换依赖修改配置文件并重启，不支持运行时无损切换。

