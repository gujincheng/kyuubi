# Digiwin 限流熔断使用指南

Kyuubi 数据网关的**限流熔断**能力。本期（P0）**全部复用 Kyuubi 原生机制，零开发**，通过配置即可启用。文档同时标注了原生已支持的能力与尚未实现的缺口（QPS/并发查询级限流、引擎失败计数熔断器），后者留待 P1。

## 一、能力总览

|                     能力                     |                   实现方式                    |   状态   |           备注           |
|--------------------------------------------|-------------------------------------------|--------|------------------------|
| 连接级限流（每用户/IP/user+IP）                      | `SessionLimiter`                          | ✅ 原生支持 | 配置即用                   |
| 黑白名单（deny user / deny ip / unlimited user） | `SessionLimiterWithAccessControlListImpl` | ✅ 原生支持 | 支持 REST 热更新            |
| 引擎启动并发限流                                   | `Semaphore`                               | ✅ 原生支持 | internal 配置            |
| 引擎启动超时（快速失败）                               | `ENGINE_INIT_TIMEOUT`                     | ✅ 原生支持 | 默认 180s                |
| 查询超时 + 服务端自动取消                             | `OPERATION_QUERY_TIMEOUT` + monitor       | ✅ 原生支持 | 默认关闭，需开启               |
| 慢查询手动 Kill                                 | REST API                                  | ✅ 原生支持 | cancel/close operation |
| 会话空闲超时回收                                   | `SESSION_IDLE_TIMEOUT`                    | ✅ 原生支持 | 默认 6h                  |
| **QPS / 并发查询级限流**                          | —                                         | ❌ 缺口   | P1 开发                  |
| **引擎失败计数熔断器**                              | —                                         | ❌ 缺口   | P1 评估                  |

## 二、连接级限流（FR-10）

### 2.1 配置项

在 `conf/kyuubi-defaults.conf` 配置：

|                          配置键                          |   默认值   |          说明          |
|-------------------------------------------------------|---------|----------------------|
| `kyuubi.server.limit.connections.per.user`            | 未设置（不限） | 每用户最大连接数，超出拒绝        |
| `kyuubi.server.limit.connections.per.ipaddress`       | 未设置（不限） | 每客户端 IP 最大连接数        |
| `kyuubi.server.limit.connections.per.user.ipaddress`  | 未设置（不限） | 每用户+IP 组合最大连接数       |
| `kyuubi.server.limit.connections.user.unlimited.list` | 空       | 不受限流的白名单用户           |
| `kyuubi.server.limit.connections.user.deny.list`      | 空       | 拒绝连接的黑名单用户（优先级高于白名单） |
| `kyuubi.server.limit.connections.ip.deny.list`        | 空       | 拒绝连接的黑名单 IP          |

> 连接级限流是**计数器**机制：每新建一个 session 计数 +1，关闭 session 计数 -1，超出阈值抛 `KyuubiSQLException` 拒绝连接。

### 2.2 配置示例

```properties
# 每用户最多 10 个并发连接
kyuubi.server.limit.connections.per.user=10

# 每个客户端 IP 最多 50 个并发连接
kyuubi.server.limit.connections.per.ipaddress=50

# 每个用户+IP 组合最多 5 个并发连接（防单用户从单 IP 打满）
kyuubi.server.limit.connections.per.user.ipaddress=5

# 白名单：这些用户不受限流（如运维账号）
kyuubi.server.limit.connections.user.unlimited.list=ops_admin,monitor

# 黑名单：拒绝这些用户（优先级高于白名单）
kyuubi.server.limit.connections.user.deny.list=blocked_user

# 黑名单 IP：拒绝这些 IP
kyuubi.server.limit.connections.ip.deny.list=10.0.0.99
```

### 2.3 触发限流时的错误

客户端会收到明确的错误信息，例如：

```
Connection limit per user reached (user: alice limit: 10)
Connection limit per ipaddress reached (ipaddress: 192.168.1.100 limit: 50)
Connection denied because the user is in the deny user list. (user: blocked_user)
Connection denied because the client ip is in the deny ip list. (ipAddress: 10.0.0.99)
```

## 三、黑白名单热更新（REST API）

黑白名单修改后**无需重启 Kyuubi**，通过 REST API 热加载。流程：先改 `conf/kyuubi-defaults.conf`（或 `kyuubi-users.conf`），再调 refresh 接口。

### 3.1 接口列表

|                       接口                        |       作用       |
|-------------------------------------------------|----------------|
| `POST /api/v1/admin/refresh/unlimited_users`    | 重新加载白名单用户      |
| `POST /api/v1/admin/refresh/deny_users`         | 重新加载黑名单用户      |
| `POST /api/v1/admin/refresh/deny_ips`           | 重新加载黑名单 IP     |
| `POST /api/v1/admin/refresh/hadoop_conf`        | 重新加载 Hadoop 配置 |
| `POST /api/v1/admin/refresh/user_defaults_conf` | 重新加载用户默认配置     |

### 3.2 示例

```bash
# 修改配置文件后，热加载黑名单用户
curl -X POST http://192.168.206.212:10099/api/v1/admin/refresh/deny_users

# 热加载黑名单 IP
curl -X POST http://192.168.206.212:10099/api/v1/admin/refresh/deny_ips

# 热加载白名单用户
curl -X POST http://192.168.206.212:10099/api/v1/admin/refresh/unlimited_users
```

> 接口要求调用者是 admin 用户（通过 `kyuubi.server.admin.users` 配置，默认空表示所有用户均可调用，生产环境建议显式配置 admin 列表）。

## 四、查询超时与慢查询 Kill（FR-11）

### 4.1 查询超时自动取消

|                       配置键                        |      默认值       |                          说明                          |
|--------------------------------------------------|----------------|------------------------------------------------------|
| `kyuubi.operation.query.timeout`                 | 未设置（关闭）        | 服务端查询超时（毫秒），超时自动取消。设置后客户端 `setQueryTimeout` 上限被钳制为此值 |
| `kyuubi.operation.query.timeout.monitor.enabled` | true（internal） | 服务端是否监控查询超时                                          |
| `kyuubi.operation.interrupt.on.cancel`           | true           | 取消查询时是否中断运行中的任务                                      |
| `kyuubi.operation.timeout.pool.size`             | 8              | 超时监控线程池大小                                            |
| `kyuubi.operation.timeout.pool.keepalive.time`   | 60s            | 空闲线程存活时间                                             |

配置示例：

```properties
# 查询最多执行 30 分钟，超时自动取消
kyuubi.operation.query.timeout=30m

# 取消时立即中断任务（而非等任务自然结束）
kyuubi.operation.interrupt.on.cancel=true
```

> `kyuubi.operation.query.timeout` 必须 >= 1s，否则校验失败。设置后即使客户端设了更长的 `setQueryTimeout`，也会被钳制为服务端值。

### 4.2 慢查询手动 Kill

通过 REST API 手动取消/关闭运行中的查询：

```bash
# 取消某个操作（查询）
curl -X PUT http://192.168.206.212:10099/api/v1/operations/{operationHandle} \
  -H "Content-Type: application/json" \
  -d '{"action":"cancel"}'

# 关闭某个操作
curl -X PUT http://192.168.206.212:10099/api/v1/operations/{operationHandle} \
  -H "Content-Type: application/json" \
  -d '{"action":"close"}'
```

admin 也可强制关闭任意用户的操作：

```bash
curl -X DELETE http://192.168.206.212:10099/api/v1/admin/operations/{operationHandle}
```

> 先用 `GET /api/v1/admin/operations` 列出所有运行中的操作，找到慢查询的 `operationHandle`，再 cancel。结合审计日志（`kyuubi_operation` 事件的 `executionDuration`）可定位慢查询。

## 五、引擎启动超时与并发限制

### 5.1 引擎启动超时

|                    配置键                     | 默认值  |                说明                |
|--------------------------------------------|------|----------------------------------|
| `kyuubi.session.engine.initialize.timeout` | 180s | 引擎启动超时，超时快速失败，不会无限等待             |
| `kyuubi.engine.submit.timeout`             | 30s  | 引擎提交后等待 Driver 可见的容忍时间（K8s/YARN） |

```properties
# 引擎启动最多等 3 分钟（默认值，可按需调大）
kyuubi.session.engine.initialize.timeout=3m
```

> 这是"引擎不可用时快速失败"的兜底机制：引擎拉不起来时，用户在超时后收到明确错误，而非无限挂起。配合审计日志可定位是哪个数据源/引擎启动失败。

### 5.2 引擎启动并发限制

|                 配置键                  |   默认值   |               说明                |
|--------------------------------------|---------|---------------------------------|
| `kyuubi.server.limit.engine.startup` | 未设置（不限） | 引擎启动最大并发数（internal，用 Semaphore） |

```properties
# 同时最多 5 个引擎启动进程（防高并发启动打爆服务器）
kyuubi.server.limit.engine.startup=5
```

> 这是 internal 配置，用于防止高并发场景下引擎启动进程打满服务器资源。

## 六、会话空闲回收

|                 配置键                  | 默认值  |         说明          |
|--------------------------------------|------|---------------------|
| `kyuubi.session.idle.timeout`        | 6h   | session 空闲超时，超时自动关闭 |
| `kyuubi.session.close.on.disconnect` | true | 客户端断开后是否关闭 session  |

```properties
# session 空闲 1 小时自动回收（释放连接占用）
kyuubi.session.idle.timeout=1h
```

> 调小此值可更快释放连接配额，让限流计数器更快回落。

## 七、完整配置示例

`conf/kyuubi-defaults.conf` 推荐配置：

```properties
# ===== 连接级限流 =====
kyuubi.server.limit.connections.per.user=10
kyuubi.server.limit.connections.per.ipaddress=50
kyuubi.server.limit.connections.per.user.ipaddress=5
kyuubi.server.limit.connections.user.unlimited.list=ops_admin,monitor

# ===== 查询超时 =====
kyuubi.operation.query.timeout=30m
kyuubi.operation.interrupt.on.cancel=true

# ===== 引擎启动 =====
kyuubi.session.engine.initialize.timeout=3m
kyuubi.server.limit.engine.startup=5

# ===== 会话回收 =====
kyuubi.session.idle.timeout=1h

# ===== Admin 用户（热更新接口权限）=====
kyuubi.server.admin.users=ops_admin
```

## 八、验收自查

|                         测试                          |                         期望                         |
|-----------------------------------------------------|----------------------------------------------------|
| 配置 `per.user=2`，同一用户开第 3 个连接                        | 第 3 个连接被拒绝，错误含 `Connection limit per user reached` |
| 把用户加入 `user.deny.list`，调 `refresh/deny_users`       | 该用户新连接立即被拒（无需重启）                                   |
| 配置 `query.timeout=10s`，执行 `SELECT sleep(60)`        | 10s 后查询被自动取消                                       |
| `GET /api/v1/admin/operations` 找到运行中查询，`PUT cancel` | 查询被取消，审计日志 state 变为 CANCELED                       |
| 配置 `engine.startup=1`，同时触发 2 个引擎启动                  | 第 2 个排队等待，不并行启动                                    |

## 九、已知缺口（P1 待开发）

本期 P0 复用原生能力，以下两项**未实现**，留待 P1：

### 9.1 QPS / 并发查询级限流（缺口）

**现状**：原生只有**连接数**限流（SessionLimiter），没有**并发查询数**或**QPS**限流。一个用户开 1 个连接但疯狂提交查询，不会被限流。

**P1 方向**：新增并发查询数限流（每用户/全局并发查询上限，超过拒绝或排队）；可选 QPS 令牌桶。

**临时缓解**：用 `kyuubi.operation.query.timeout` 限制单查询时长 + 慢查询手动 Kill，避免单查询长时间占用资源。

### 9.2 引擎失败计数熔断器（缺口）

**现状**：原生只有**超时**快速失败，没有**失败计数熔断**（即：引擎连续失败 N 次后自动隔离一段时间，避免雪崩，半开探测恢复）。

**P1 方向**：评估是否需要引入熔断器模式（如 Resilience4j），对引擎健康度做连续失败计数 -> 熔断 -> 半开恢复。

**临时缓解**：依赖引擎启动超时 + 审计日志监控，发现引擎频繁失败时人工介入（deny 该数据源或重启引擎）。

## 十、与审计日志联动

限流熔断事件可结合审计日志分析：

- **被限流拒绝的连接**：不会产生 `kyuubi_operation` 事件（连接都没建立），只能看 KyuubiServer 主日志（`error` 级别含 `Connection limit`）
- **被超时取消的查询**：`kyuubi_operation` 事件 `state=CANCELED` 或 `TIMEOUT`，`executionDuration` 接近超时阈值
- **引擎启动失败**：`kyuubi_session` 事件 `exception` 字段含启动异常
- **慢查询定位**：`kyuubi_operation` 事件 `state=FINISHED` 且 `executionDuration` 大的记录

```bash
# 找出所有被取消/超时的查询
cat /tmp/kyuubi-server-events/kyuubi_operation/day=20260721/*.json \
  | jq 'select(.state=="CANCELED_STATE" or .state=="TIMEOUT_STATE")'

# 找出慢查询（执行超过 5 分钟）
cat /tmp/kyuubi-server-events/kyuubi_operation/day=20260721/*.json \
  | jq 'select(.state=="FINISHED_STATE" and .executionDuration > 300000)'
```

