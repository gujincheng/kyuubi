# Digiwin 危险 SQL 拦截使用指南

Kyuubi 数据网关的**危险 SQL 拦截**功能:通过一个**动态规则引擎**,在 SQL 下发引擎前按规则匹配,命中则拦截(不执行)并返回明确错误码 `SQL_BLOCKED`。规则通过 REST API 增删改,**改完立即生效,无需重启**。

## 一、配置

### 1.1 `conf/kyuubi-defaults.conf`

```properties
# 开启 SQL 拦截(默认 false)
kyuubi.digiwin.sql.inspection.enabled=true

# 豁免用户(静态配置;这些用户的 SQL 完全不受拦截)
# kyuubi.digiwin.sql.inspection.whitelist=admin,dba
```

规则存储复用数据源的 JDBC 配置(同库不同表 `digiwin_sql_rule`),因此还需:

```properties
kyuubi.digiwin.datasource.store.enabled=true
kyuubi.digiwin.datasource.store.jdbc.url=jdbc:sqlite:/tmp/digiwin-datasources.db
kyuubi.digiwin.datasource.store.jdbc.driver=org.sqlite.JDBC
```

> 即:SQL 规则表与数据源表在同一个 SQLite/MySQL/PostgreSQL 库里。无需单独的存储配置。

### 1.2 CLI 起服务测试(可选,IDEA 调试可跳过)

CLI 起 server 时需加 `-Dkyuubi.testing=true`,让 JDBC 引擎用 dev classpath(否则引擎缺依赖起不来):

```bash
KYUUBI_HOME=/Users/gujc/code/digiwinCode/kyuubi \
  java -Dkyuubi.testing=true \
  -cp "kyuubi-server/target/scala-2.12/classes:digiwin-plugins/target/scala-2.12/classes:$(cat /tmp/cp.txt)" \
  org.apache.kyuubi.server.KyuubiServer
```

若 JDBC 引擎连目标库时报 `No suitable driver`,补一行驱动路径:

```properties
kyuubi.engine.jdbc.extra.classpath=/Users/gujc/.m2/repository/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar
```

## 二、启动确认

启动日志里要看到:

```
Digiwin SQL inspection registry initialized.
```

## 三、管理规则(REST API)

REST 前端 `http://<bind.host>:10099`,API 前缀 `/api/v1/sql-rules`。

### 规则模型

|      字段       |                               说明                                |
|---------------|-----------------------------------------------------------------|
| `id`          | 规则唯一标识,如 `block-drop`                                           |
| `name`        | 展示名,如 `禁止 DROP`                                                 |
| `ruleType`    | `KEYWORD` / `WITHOUT_WHERE` / `REGEX` 三选一(见下)                   |
| `pattern`     | KEYWORD->关键字(`DROP`);WITHOUT_WHERE->`DELETE`/`UPDATE`;REGEX->正则 |
| `action`      | `DENY`(P0 仅此一种)                                                 |
| `engineScope` | 可选,限定引擎类型(如 `jdbc`);空=对所有引擎生效                                   |
| `userScope`   | 可选,用户列表(如 `["alice","carol"]`);空=对所有用户生效                        |
| `enabled`     | `true`/`false`                                                  |
| `description` | 说明                                                              |

### 三种规则类型

|       类型        |              匹配逻辑              |                典型用法                |
|-----------------|--------------------------------|------------------------------------|
| `KEYWORD`       | SQL 归一化后以关键字开头                 | `DROP`、`TRUNCATE`、`ALTER`、`CREATE` |
| `WITHOUT_WHERE` | 以 DELETE/UPDATE 开头**且无 WHERE** | 防全表删除/更新                           |
| `REGEX`         | 用户自定义正则匹配(大小写不敏感)              | 任意复杂规则,如 `SELECT\s+\*`             |

> 匹配前会对 SQL 归一化:去掉块注释 `/* */`、行注释 `--`、trim。多语句(`;` 分隔)逐条检查,任一命中整条拦截。

### 创建规则

```bash
# 1) 禁止 DROP(对所有用户、jdbc 引擎)
curl -X POST http://192.168.206.212:10099/api/v1/sql-rules \
  -H "Content-Type: application/json" \
  -d '{
    "id": "block-drop",
    "name": "禁止 DROP",
    "ruleType": "KEYWORD",
    "pattern": "DROP",
    "action": "DENY",
    "engineScope": "jdbc",
    "enabled": true,
    "description": "不允许 DROP 生产表"
  }'

# 2) 禁止无 WHERE 的 DELETE
curl -X POST http://192.168.206.212:10099/api/v1/sql-rules \
  -H "Content-Type: application/json" \
  -d '{
    "id": "block-delete-nowhere",
    "name": "禁无WHERE删除",
    "ruleType": "WITHOUT_WHERE",
    "pattern": "DELETE",
    "engineScope": "jdbc",
    "enabled": true
  }'

# 3) 只对 alice 禁 SELECT *(REGEX + userScope)
curl -X POST http://192.168.206.212:10099/api/v1/sql-rules \
  -H "Content-Type: application/json" \
  -d '{
    "id": "alice-no-star",
    "name": "alice禁SELECT*",
    "ruleType": "REGEX",
    "pattern": "SELECT\\s+\\*",
    "engineScope": "jdbc",
    "userScope": ["alice"],
    "enabled": true
  }'
```

### 查询 / 更新 / 删除 / 刷新

```bash
# 列表
curl http://192.168.206.212:10099/api/v1/sql-rules
# 单个
curl http://192.168.206.212:10099/api/v1/sql-rules/block-drop
# 更新(PUT,body 的 id 必须与 path 一致)
curl -X PUT http://192.168.206.212:10099/api/v1/sql-rules/block-drop \
  -H "Content-Type: application/json" \
  -d '{"id":"block-drop","name":"禁止 DROP","ruleType":"KEYWORD","pattern":"DROP","enabled":false}'
# 删除
curl -X DELETE http://192.168.206.212:10099/api/v1/sql-rules/block-drop
# 手动刷新内存缓存(定时也会自动刷新,默认 60s)
curl -X POST http://192.168.206.212:10099/api/v1/sql-rules/refresh
```

### 热生效

`POST/PUT/DELETE` 会**同步**写库 + 更新内存缓存,下一条 SQL 立即按新规则判定,**无需重启 Kyuubi**。

## 四、作用域语义

|                场景                 |                          配置                          |
|-----------------------------------|------------------------------------------------------|
| 对所有人、所有引擎禁 DROP                   | `engineScope=""`, `userScope=[]`                     |
| 只对 JDBC(StarRocks)禁 DROP,Spark 不限 | `engineScope="jdbc"`                                 |
| 只对 alice、carol 禁 SELECT *         | `userScope=["alice","carol"]`                        |
| bob 完全不受任何拦截                      | 把 `bob` 加进 `kyuubi.digiwin.sql.inspection.whitelist` |

> `engineScope`/`userScope`(规则级,动态,REST 可改)与 `whitelist`(全局,静态配置)职责分开:前者精细到单条规则对哪些用户/引擎生效,后者是某些用户完全不受拦截。

## 五、验证拦截(kyuubi-beeline)

用已安装的 Kyuubi 客户端 beeline(以 `/opt/soft/kyuubi-1.11.1` 为例):

```bash
# 应被拦截(DROP)
/opt/soft/kyuubi-1.11.1/bin/kyuubi-beeline \
  -u "jdbc:kyuubi://192.168.206.212:10009/?kyuubi.datasource=sr-prod" \
  -n root -p "" -e "DROP TABLE t;"
# 期望:Error: SQL is blocked by gateway inspection: rule=block-drop(KEYWORD:DROP) (state=SQL_BLOCKED,code=0)

# 应放行(普通查询)
/opt/soft/kyuubi-1.11.1/bin/kyuubi-beeline \
  -u "jdbc:kyuubi://192.168.206.212:10009/?kyuubi.datasource=sr-prod" \
  -n root -p "" -e "SELECT 1;"
# 期望:返回 1

# 应放行(DELETE 带 WHERE)
/opt/soft/kyuubi-1.11.1/bin/kyuubi-beeline \
  -u "jdbc:kyuubi://192.168.206.212:10009/?kyuubi.datasource=sr-prod" \
  -n root -p "" -e "DELETE FROM t WHERE id=1;"
# 期望:不被拦截(走引擎)

# 应被拦(DELETE 无 WHERE)
/opt/soft/kyuubi-1.11.1/bin/kyuubi-beeline \
  -u "jdbc:kyuubi://192.168.206.212:10009/?kyuubi.datasource=sr-prod" \
  -n root -p "" -e "DELETE FROM t;"
# 期望:SQL_BLOCKED
```

拦截时服务端日志会输出告警:

```
WARN ... [SQL_BLOCKED] user=root ip=127.0.0.1 datasource=sr-prod rule=禁止 DROP reason=rule=block-drop(KEYWORD:DROP) sql=DROP TABLE t
```

## 六、验收自查

|              测试               |          期望           |
|-------------------------------|-----------------------|
| 创建规则后,匹配的 SQL                 | 返回 `SQL_BLOCKED`      |
| 不匹配的 SQL                      | 正常执行                  |
| REST 改规则后(不重启)                | 下一条 SQL 立即按新规则        |
| `engineScope=jdbc` + label 会话 | jdbc 命中、spark 不命中     |
| `userScope=["alice"]`         | alice 命中、其他用户不命中      |
| whitelist 用户                  | 所有 SQL 放行             |
| DELETE 带 WHERE                | 放行(WITHOUT_WHERE 不命中) |

## 七、工作机制

```
客户端(kyuubi-beeline)
        │  Thrift Binary :10009
        ▼
KyuubiServer.KyuubiBackendService.executeStatement
  ├─ SqlInspectionHook.inspect(session, statement)
  │     ├ enabled=false -> 放行
  │     ├ whitelist 用户 -> 放行
  │     ├ rules = RuleRegistryHolder.snapshot()  // 当前 enabled 规则(内存,实时)
  │     ├ 读 normalizedConf 拿 label -> 解析 engineType
  │     ├ 按 ; 拆分,逐条归一化后遍历 rules:
  │     │    engineScope/userScope 不符 -> 跳过该规则
  │     │    按 ruleType 匹配 -> 命中记下
  │     └ 命中: LogAlertNotifier.warn(...) + 抛 KyuubiSQLException(SQL_BLOCKED)
  └─ 未命中 -> super.executeStatement -> 引擎执行
```

## 八、常见问题

- **规则不生效**:`kyuubi.digiwin.sql.inspection.enabled=true` 设了吗?启动日志有 `Digiwin SQL inspection registry initialized.` 吗?
- **`engineScope` 没匹配上**:label 会话的引擎类型通过数据源注册中心解析,确认 `kyuubi.digiwin.datasource.store.enabled=true` 且数据源 `engineType` 正确。hook 读的是 `normalizedConf`(归一化后的 conf),不是原始 `session.conf`。
- **规则改了没生效**:REST 改完是同步生效的;若仍不生效,`POST /api/v1/sql-rules/refresh` 手动刷新。
- **CLI 起服务引擎报 `ClassNotFoundException`**:加 `-Dkyuubi.testing=true`。
- **引擎报 `No suitable driver`**:设 `kyuubi.engine.jdbc.extra.classpath` 指向目标库的 JDBC 驱动 jar。
- **拦截告警在哪**:服务端日志,搜 `SQL_BLOCKED`(WARN 级别)。
- **拦截事件是否进审计**:P0 暂未接入结构化审计日志(FR-6/7),目前仅在服务端日志输出;后续审计功能会记录。

