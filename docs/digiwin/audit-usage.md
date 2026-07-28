# Digiwin 结构化审计日志使用指南

Kyuubi 数据网关的**结构化审计日志**功能：在已有的 `KyuubiOperationEvent` JSON 事件中扩展 5 个审计字段（clientIp、datasourceLabel、engineType、sqlBlockedReason、executionDuration），并为 SQL 拦截新增专用事件 `SqlBlockedEvent`。开启 JSON 事件日志后，所有审计信息自动写入 JSON 文件，无需额外配置。

## 一、配置

### 1.1 `conf/kyuubi-defaults.conf`

```properties
# 开启服务端 JSON 事件日志（审计的基础）
kyuubi.backend.server.event.loggers=JSON

# JSON 日志输出路径（本地文件系统或 HDFS）
kyuubi.backend.server.event.json.log.path=file:///tmp/kyuubi-server-events

# 异步写入（可选，提高性能，但可能丢失最后几条事件）
# kyuubi.backend.server.event.async.enabled=true
```

> 审计字段扩展跟随 JSON 事件日志自动生效，**无需单独开关**。只要 `kyuubi.backend.server.event.loggers=JSON` 开启，新字段就会出现在 JSON 日志中。
>
> 数据源和 SQL 拦截功能也需要开启（否则 datasourceLabel/engineType/sqlBlockedReason 为空）：

```properties
kyuubi.digiwin.datasource.store.enabled=true
kyuubi.digiwin.datasource.store.jdbc.url=jdbc:sqlite:/tmp/digiwin-datasources.db
kyuubi.digiwin.datasource.store.jdbc.driver=org.sqlite.JDBC
kyuubi.digiwin.datasource.credential.secret=0123456789abcdef
kyuubi.session.conf.advisor=org.apache.kyuubi.digiwin.datasource.DatasourceConfAdvisor
kyuubi.digiwin.sql.inspection.enabled=true
```

## 二、JSON 日志输出路径

开启后，事件日志按以下目录结构写入：

```
${kyuubi.backend.server.event.json.log.path}/
  kyuubi_operation/day=2026-07-21/server-hostname.json     ← 操作事件（含审计字段）
  kyuubi_session/day=2026-07-21/server-hostname.json       ← 会话事件（已有）
  kyuubi_server_info/day=2026-07-21/server-hostname.json   ← 服务信息事件（已有）
  sql_blocked/day=2026-07-21/server-hostname.json          ← SQL 拦截事件（新增）
```

每个文件每行一个 JSON 对象，可用 `jq`、Spark、Hive 等工具解析。

## 三、操作事件新增字段

`KyuubiOperationEvent` 新增 5 个审计字段：

|         字段          |   类型   |             说明             |                              来源                               |
|---------------------|--------|----------------------------|---------------------------------------------------------------|
| `clientIp`          | String | 客户端 IP 地址                  | Thrift 前端注入 → `AbstractSession.clientIpAddress`               |
| `datasourceLabel`   | String | 数据源标签（如 `sr-prod`）         | `normalizedConf["kyuubi.datasource"]`；空串=未使用数据源               |
| `engineType`        | String | 引擎类型（如 `jdbc`、`SPARK_SQL`） | label→数据源注册中心取；无 label→`normalizedConf["kyuubi.engine.type"]` |
| `sqlBlockedReason`  | String | SQL 拦截原因                   | 空=正常执行；非空=被拦截原因（如 `rule=block-drop(KEYWORD:DROP)`）            |
| `executionDuration` | Long   | 执行时长（毫秒）                   | `completeTime - startTime`；未完成时为 0                            |

### 3.1 正常执行事件样例

```json
{
  "statementId": "5f3a8c01-0000-0000-0000-000000000001",
  "remoteId": "e2b7d4f0-0000-0000-0000-000000000002",
  "statement": "SELECT 1",
  "shouldRunAsync": false,
  "state": "FINISHED",
  "eventTime": 1721376123000,
  "createTime": 1721376120000,
  "startTime": 1721376121000,
  "completeTime": 1721376123000,
  "executionDuration": 2000,
  "exception": null,
  "sessionId": "a1c2e3f4-0000-0000-0000-000000000003",
  "sessionUser": "root",
  "sessionType": "SQL",
  "kyuubiInstance": "192.168.206.212:10009",
  "metrics": {"fetchLogCount":"0","fetchResultsCount":"1"},
  "clientIp": "192.168.1.100",
  "datasourceLabel": "sr-prod",
  "engineType": "jdbc",
  "sqlBlockedReason": ""
}
```

### 3.2 无数据源 label 的操作（直连 Spark）

```json
{
  "clientIp": "10.0.0.5",
  "datasourceLabel": "",
  "engineType": "SPARK_SQL",
  "sqlBlockedReason": "",
  "executionDuration": 5000
}
```

## 四、SQL 拦截专用事件（SqlBlockedEvent）

SQL 拦截发生在引擎执行之前，**不会创建 Operation**，因此不会产生 `KyuubiOperationEvent`。拦截信息由独立的 `SqlBlockedEvent` 记录。

|        字段         |   类型   |                说明                 |
|-------------------|--------|-----------------------------------|
| `user`            | String | Kyuubi 会话用户                       |
| `clientIp`        | String | 客户端 IP                            |
| `datasourceLabel` | String | 数据源标签                             |
| `engineType`      | String | 引擎类型                              |
| `statement`       | String | 被拦截的 SQL                          |
| `ruleId`          | String | 触发拦截的规则 ID                        |
| `ruleName`        | String | 规则名称                              |
| `ruleType`        | String | 规则类型（KEYWORD/WITHOUT_WHERE/REGEX） |
| `reason`          | String | 详细原因                              |
| `action`          | String | 动作（DENY）                          |
| `eventTime`       | Long   | 拦截时间戳                             |

### 4.1 拦截事件样例

```json
{
  "user": "root",
  "clientIp": "192.168.1.100",
  "datasourceLabel": "sr-prod",
  "engineType": "jdbc",
  "statement": "DROP TABLE t",
  "ruleId": "block-drop",
  "ruleName": "禁止 DROP",
  "ruleType": "KEYWORD",
  "reason": "rule=block-drop(KEYWORD:DROP)",
  "action": "DENY",
  "eventTime": 1721376180000,
  "eventType": "sql_blocked"
}
```

## 五、审计分析示例

### 5.1 用 jq 查询慢查询（executionDuration > 5s）

```bash
cat /tmp/kyuubi-server-events/kyuubi_operation/day=2026-07-21/server-*.json \
  | jq 'select(.executionDuration > 5000)'
```

### 5.2 统计各数据源的查询次数

```bash
cat /tmp/kyuubi-server-events/kyuubi_operation/day=2026-07-21/server-*.json \
  | jq -r '.datasourceLabel' | sort | uniq -c | sort -rn
```

### 5.3 查看所有被拦截的 SQL

```bash
cat /tmp/kyuubi-server-events/sql_blocked/day=2026-07-21/server-*.json \
  | jq '{user, clientIp, datasourceLabel, statement, ruleName, reason}'
```

### 5.4 查看某用户的操作历史

```bash
cat /tmp/kyuubi-server-events/kyuubi_operation/day=2026-07-21/server-*.json \
  | jq 'select(.sessionUser == "alice") | {statement, state, executionDuration, datasourceLabel}'
```

## 六、REST API 查询

操作事件可通过 REST API 查询（新增字段自动包含在返回的 JSON 中）：

```bash
curl http://192.168.206.212:10099/api/v1/operations/{operationHandle}/event
```

返回的 JSON 会包含 `clientIp`、`datasourceLabel`、`engineType`、`sqlBlockedReason`、`executionDuration` 字段。

> 注意：`SqlBlockedEvent` 不通过 REST API 查询，仅在 JSON 日志文件中记录。

## 七、验收自查

|              测试              |                                               期望                                               |
|------------------------------|------------------------------------------------------------------------------------------------|
| 开启 JSON logger，执行 `SELECT 1` | JSON 日志中出现 `clientIp`、`datasourceLabel`、`engineType`、`sqlBlockedReason=""`、`executionDuration` |
| 执行 `DROP TABLE t`（被拦截）       | `sql_blocked/day=.../*.json` 中出现 `SqlBlockedEvent`                                             |
| 无数据源 label 的操作               | `datasourceLabel=""`、`engineType` 取 `kyuubi.engine.type`                                       |
| REST API 查询操作事件              | 返回 JSON 包含新增字段                                                                                 |
| 慢查询                          | `executionDuration` > 0，值 = `completeTime - startTime`                                         |

## 八、常见问题

- **JSON 日志没有输出**：确认 `kyuubi.backend.server.event.loggers=JSON` 和 `kyuubi.backend.server.event.json.log.path` 配置正确。启动日志应显示 `Logging kyuubi events to ...`。
- **新增字段为空**：`datasourceLabel`/`engineType` 为空说明未使用数据源功能或未开启 `kyuubi.digiwin.datasource.store.enabled`。`sqlBlockedReason` 为空说明未被拦截（正常）。
- **拦截事件在哪**：拦截事件写入 `sql_blocked/day=.../*.json`，与操作事件分开存储。
- **executionDuration 为 0**：操作未完成（`state` 不是终态）时 duration 为 0。
- **Kafka 输出**：如果配置了 `kyuubi.backend.server.event.loggers=JSON,KAFKA`，新增字段也会自动出现在 Kafka 消息中。

