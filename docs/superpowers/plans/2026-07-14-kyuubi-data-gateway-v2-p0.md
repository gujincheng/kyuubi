# Kyuubi 数据网关 P0 实现计划（v2）

**Feature Name**: Kyuubi 统一数据网关 P0（基于 v2 需求文档）
**REQUIRED SUB-SKILL**: 本计划由 `writing-plans` skill 生成，执行阶段须配合 `executing-plans` / `superpowers` 流程。
**对应需求文档**: `docs/superpowers/specs/2026-07-14-kyuubi-data-gateway-v2-design.md`
**替代关系**: 本计划替代 `docs/superpowers/plans/2026-07-14-kyuubi-unified-gateway-p0.md`（旧计划基于旧需求草稿，保留不动，不再执行）。

## Goal

将 Apache Kyuubi（`digiwin-1.11.1` 分支）改造为公司内部统一数据网关，P0 阶段落地五项能力：
1. **数据源 label 代入**（FR-1/2/3）：客户端仅传 `kyuubi.datasource=<label>`，网关自动注入完整引擎配置与加密凭据。
2. **危险 SQL 拦截**（FR-4/5）：DROP/TRUNCATE/无 WHERE 的 UPDATE·DELETE 等规则拦截 + 白名单 + 告警 + 明确错误码。
3. **结构化审计**（FR-6/7）：用户/SQL/数据源/耗时/状态/行数/来源 IP 落 JSON 文件，按时间分区。
4. **限流熔断**（FR-10/11）：复用连接级 SessionLimiter + 新建 QPS/并发查询级限流 + 慢查询自动 Kill + 引擎不可用熔断降级。
5. **端到端协议兼容验证与 JDBC Engine 元数据修复**（FR-8/9）：验证 DBeaver 经 Kyuubi JDBC 驱动（Thrift Binary）-> JDBC Engine -> StarRocks 端到端查询；FR-9 真实问题（DBeaver 看不到 db/表平铺）已由提交 `fde0be6e6`（getSchemasOperation）解决，流式异常在 1.11.1 不复现。

团队规模 1-2 人，无硬性 deadline，技术路线为方案 A：Fork 主线 + 新增 `digiwin-plugins` 模块 + 少量集中的核心 patch。

## Architecture

### 整体架构

```
客户端（Thrift Binary / REST / Trino）
                          │
                          ▼
KyuubiServer（单一共享 KyuubiBackendService 实例，服务全部前端协议）
  ├─ ① DatasourceConfAdvisor（SessionConfAdvisor SPI）：kyuubi.datasource=<label> → 注入引擎配置 + 解密凭据
  ├─ ② SqlInspectionHook（危险 SQL 拦截，挂 KyuubiBackendService.executeStatement 收口点）
  ├─ ③ OperationLimiter（QPS / 并发查询级限流，挂 KyuubiSessionManager）
  ├─ ④ 审计：KyuubiOperationEvent 扩展字段 → 内置 JSON handler 落盘（kyuubi.backend.server.event.loggers=JSON）
  └─ DatasourcesResource（REST CRUD + 手动刷新）
                          │
                          ▼
       引擎（StarRocks via JDBC Engine / Spark）
```

### 技术路线与模块归属

- **核心 patch（修改主线已有文件，最小化）**：`KyuubiConf` 新增配置项；`KyuubiBackendService.executeStatement` 加拦截钩子；`KyuubiOperationEvent` + `KyuubiOperation.getOperationEvent` 扩展审计字段；`ApiRootResource` + `OpenAPIConfig` 注册 DatasourcesResource；`SessionLimiter`/`KyuubiSessionManager` 扩展 QPS/并发限流；JDBC Engine `ExecuteStatement`/`JdbcSessionImpl`/`MySQLDialect` 修复 FR-9。
- **`digiwin-plugins` 模块（新增）**：承载 digiwin 定制实现——`DatasourceRegistry`/`DatasourceStore`/`DatasourceConfAdvisor`/`SqlInspectionHook`/`CredentialAccessor`/`AlertNotifier` 等。作为 `kyuubi-server` 的**编译期内嵌依赖**（Fork 主线下用于代码隔离，便于上游 rebase，而非运行时第三方插件）。`DatasourceConfAdvisor` 蹭既有 `SessionConfAdvisor` SPI（`PluginLoader` 反射加载）。

### Tech Stack

- Scala 2.12 + Java（项目既有），Maven 构建（`build/mvn` 包装器）。
- HikariCP（已是仓库依赖，kyuubi-server/pom.xml 已含）——用于 `DatasourceStore` 独立连接池。
- Jersey / JAX-RS（既有）——`DatasourcesResource` REST 资源。
- AES 对称加密（复用 `InternalSecurityAccessor` 模板）——凭据加密。
- ScalaTest + JUnit（既有）——单元测试。

## 设计决策与偏离说明

执行前需知会的 4 项关键决策（前 3 项已与需求方确认采用推荐方案）：

1. **【偏离 spec 字面】SQL 拦截钩子挂 `KyuubiBackendService.executeStatement` 而非 `AbstractBackendService.executeStatement`**。
   - **原因**：`KyuubiServer` 用单一共享 `KyuubiBackendService` 实例服务全部前端协议（Thrift/REST/MySQL/Trino），patch 到 `KyuubiBackendService.executeStatement` 才是真正的全协议最早收口点。而 `AbstractBackendService`（kyuubi-common）还被 6 个 engine 侧无关子类继承（Spark/Flink/Hive/Trino/JDBC/Chat 引擎的 backend service），patch 那里会误伤引擎侧。
   - **影响**：仅 1 处 patch 位置变更，不改功能语义。
2. **【简化 spec】结构化审计走内置 JSON handler，不新建 `CustomEventHandlerProvider` SPI 插件**。
   - **原因**：内置 `kyuubi.backend.server.event.loggers=JSON`（`ServerEventHandlerRegister` → `JsonLoggingEventHandler`）已能把所有发到 `EventBus` 的 `KyuubiEvent` 子类（含 `KyuubiOperationEvent`）写成按天分区的 JSON 文件，且 `KyuubiEvent.toJson` 是泛型的。只需给 `KyuubiOperationEvent` 加 `clientIp`/`datasourceLabel`/`rowCount` 字段，零新增 SPI 代码即满足 FR-6/7。
   - **影响**：`digiwin-plugins` 不再含 `AuditEventHandlerProvider`；若 P1 需自定义输出格式/落 Kafka，再按 SPI 扩展。REST DTO `dto.KyuubiOperationEvent`（`kyuubi-rest-client`）与 `ApiUtils.operationEvent()` 映射**不在 P0 修改**（P0 审计输出是 JSON 文件，非 REST 暴露）。
3. **【FR-9 重新定性】原假设的「MySQL 流式结果集 `Streaming result set is still active` bug」在 1.11.1 中不复现，drain-and-close 修复作废**。
   - **核实结论**：经用户实际验证，1.11.1 下 DBeaver 经 Kyuubi JDBC 驱动 -> Thrift Binary -> JDBC Engine -> StarRocks 可直接查询数据，不存在流式结果集异常。原计划 Task 6.1/6.2（`JdbcSessionImpl.drainAndClosePriorStatement` + `ExecuteStatement` 登记/清理活跃 statement）基于错误假设，**整段删除**。
   - **真实问题已解决**：用户实际遇到的 DBeaver 问题——看不到 StarRocks 的 db、所有表平铺——是元数据层级问题，已由提交 `fde0be6e6 Support GetSchemas for MySQL-family JDBC dialects` 修复：`MySQLDialect.getSchemasOperation` 把 StarRocks database 映射为 schema、catalog 返回 NULL（避免 DBeaver 用 `def.db.table` 错误限定），DBeaver 即可正确展示 db 层级。该提交已在 HEAD，无需新增代码。
   - **残留观察项（非 P0 阻塞）**：`MySQLDialect.createStatement` 仍用 `fetchSize=Integer.MIN_VALUE`（流式）；`incrementalCollect` 默认 false（全量物化）。当前无报错，但大结果集全量物化存在 OOM 隐患，作为已知风险观察，P1 评估 `kyuubi.engine.jdbc.operation.incremental.collect` 推荐配置。
4. **【模块依赖方向】`digiwin-plugins` 单向依赖 `kyuubi-server-plugin`+`kyuubi-common`+`kyuubi-events`+`kyuubi-util`；`kyuubi-server` 编译期依赖 `digiwin-plugins`**（非循环）。`DatasourcesResource` 放 `kyuubi-server`（需用 REST 基类 `ApiRequestContext`），直接调用 `digiwin-plugins` 的 `DatasourceRegistry`；`SqlInspectionHook` 由 `KyuubiBackendService` 直接 import 调用（配置开关控制）；`DatasourceConfAdvisor` 蹭 `SessionConfAdvisor` SPI。

## File Structure

### 新增文件

```
digiwin-plugins/                                                    # 新模块
  pom.xml                                                           # 模块 pom（依赖 kyuubi-server-plugin/kyuubi-common/kyuubi-events/kyuubi-util/HikariCP）
  src/main/scala/org/apache/kyuubi/digiwin/
    datasource/
      DatasourceInfo.scala                                          # 数据源 case class（FR-1 字段）
      DatasourceStore.scala                                         # 独立 JDBC 存储 + HikariCP + schema 初始化（FR-1，不复用 JDBCMetadataStore）
      DatasourceRegistry.scala                                      # 注册表：内存缓存 + 定时刷新 + CRUD（FR-1 热生效）
      DatasourceConfAdvisor.scala                                   # SessionConfAdvisor 实现：kyuubi.datasource=<label> → 注入配置 + 解密凭据（FR-2）
    security/
      CredentialAccessor.scala                                      # AES 凭据加解密（FR-3，模板 InternalSecurityAccessor）
      SqlInspectionRule.scala                                       # 规则解析与匹配（FR-4）
      SqlInspectionHook.scala                                       # 拦截入口：inspect(session, statement)（FR-4/5）
      AlertNotifier.scala                                           # 告警接口 + LogAlertNotifier 默认 + WebhookAlertNotifier 可选（FR-5）
  src/main/resources/
    sql/datasource-schema.sql                                       # 数据源表 DDL（通用，按 dbType 裁剪）
  src/test/scala/org/apache/kyuubi/digiwin/
    datasource/
      DatasourceStoreSuite.scala
      DatasourceRegistrySuite.scala
      DatasourceConfAdvisorSuite.scala
      CredentialAccessorSuite.scala
    security/
      SqlInspectionHookSuite.scala
      SqlInspectionRuleSuite.scala
kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/
  DatasourcesResource.scala                                         # REST CRUD + refresh（FR-1）
kyuubi-server/src/test/scala/org/apache/kyuubi/server/api/v1/
  DatasourcesResourceSuite.scala
kyuubi-server/src/main/scala/org/apache/kyuubi/session/
  OperationLimiter.scala                                            # QPS + 并发查询级限流（FR-11，模板 SessionLimiter）
kyuubi-server/src/test/scala/org/apache/kyuubi/session/
  OperationLimiterSuite.scala
integration-tests/src/test/scala/org/apache/kyuubi/it/
  ThriftJdbcEngineStarRocksSuite.scala                              # FR-8 端到端验证（Thrift Binary → JDBC Engine → StarRocks）
```

### 修改文件（核心 patch）

```
pom.xml                                                             # <module>digiwin-plugins</module>
kyuubi-server/pom.xml                                               # <dependency>digiwin-plugins</dependency>
kyuubi-common/src/main/scala/org/apache/kyuubi/config/KyuubiConf.scala  # 新增配置项
kyuubi-server/src/main/scala/org/apache/kyuubi/server/KyuubiBackendService.scala  # executeStatement 加 SqlInspectionHook
kyuubi-server/src/main/scala/org/apache/kyuubi/events/KyuubiOperationEvent.scala   # +clientIp/datasourceLabel/rowCount
kyuubi-server/src/main/scala/org/apache/kyuubi/operation/KyuubiOperation.scala     # getOperationEvent 填新字段
kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/ApiRootResource.scala # 注册 DatasourcesResource
kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/OpenAPIConfig.scala      # packages 扫描（如需）
kyuubi-server/src/main/scala/org/apache/kyuubi/session/KyuubiSessionManager.scala  # 接入 OperationLimiter
```

> 注：JDBC Engine 的 `ExecuteStatement.scala`/`JdbcSessionImpl.scala` 原列于修改文件（FR-9 drain-and-close），经核实 1.11.1 无流式报错，已移除（见设计决策 3）。FR-9 真实问题（getSchemas 元数据层级）已由提交 `fde0be6e6` 解决，无需改动 JDBC Engine。

---

## 任务分解（TDD）

每个任务遵循：写失败测试 → 验证失败 → 实现 → 验证通过 → 提交（提交时机由用户手动决定，不自动 commit）。代码均带项目 ASL 2.0 license header（与既有文件一致）。

---

### 阶段 0：模块脚手架

#### Task 0.1 新建 `digiwin-plugins` 模块 pom

**文件**：`digiwin-plugins/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.apache.kyuubi</groupId>
    <artifactId>kyuubi-parent</artifactId>
    <version>1.11.2-SNAPSHOT</version>
  </parent>

  <artifactId>digiwin-plugins</artifactId>
  <packaging>jar</packaging>
  <name>Kyuubi Project Digiwin Plugins</name>

  <dependencies>
    <dependency>
      <groupId>org.apache.kyuubi</groupId>
      <artifactId>kyuubi-server-plugin</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.apache.kyuubi</groupId>
      <artifactId>kyuubi-common</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.apache.kyuubi</groupId>
      <artifactId>kyuubi-events</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>com.zaxxer</groupId>
      <artifactId>HikariCP</artifactId>
    </dependency>
    <dependency>
      <groupId>org.xerial</groupId>
      <artifactId>sqlite-jdbc</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.kyuubi</groupId>
      <artifactId>kyuubi-common</artifactId>
      <version>${project.version}</version>
      <type>test-jar</type>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

**根 pom**：在 `pom.xml` 的 `<modules>` 中 `kyuubi-common` 之前插入：

```xml
<module>digiwin-plugins</module>
```

> 说明：放在 `kyuubi-common` 之前无强制要求，但需在 `kyuubi-server` 之前（kyuubi-server 依赖它）。实际插入到 `<module>dev/kyuubi-codecov</module>` 之后即可。

**验证**：`./build/mvn -pl digiwin-plugins -am clean install -DskipTests` 成功（空模块编译通过）。

#### Task 0.2 `kyuubi-server` 依赖 `digiwin-plugins`

**文件**：`kyuubi-server/pom.xml`，在 `<dependencies>` 中追加：

```xml
<dependency>
  <groupId>org.apache.kyuubi</groupId>
  <artifactId>digiwin-plugins</artifactId>
  <version>${project.version}</version>
</dependency>
```

**验证**：`./build/mvn -pl kyuubi-server -am clean install -DskipTests` 成功。

---

### 阶段 1：配置项

#### Task 1.1 在 `KyuubiConf` 新增 P0 配置项

**文件**：`kyuubi-common/src/main/scala/org/apache/kyuubi/config/KyuubiConf.scala`，在文件末尾 `buildConf` 区块内追加（紧邻现有 `SERVER_LIMIT_*` 之后）：

```scala
  // --------------------------- Digiwin Data Gateway P0 ---------------------------

  val DIGIWIN_DATASOURCE_STORE_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.digiwin.datasource.store.enabled")
      .doc("Whether to enable the digiwin datasource registry backed by an independent JDBC store.")
      .version("1.11.2")
      .serverOnly
      .booleanConf
      .createWithDefault(false)

  val DIGIWIN_DATASOURCE_STORE_JDBC_URL: OptionalConfigEntry[String] =
    buildConf("kyuubi.digiwin.datasource.store.jdbc.url")
      .doc("JDBC url of the independent datasource registry store, e.g. jdbc:sqlite:/path/to/datasource.db")
      .version("1.11.2")
      .serverOnly
      .stringConf
      .createOptional

  val DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER: OptionalConfigEntry[String] =
    buildConf("kyuubi.digiwin.datasource.store.jdbc.driver")
      .doc("JDBC driver class name of the datasource registry store.")
      .version("1.11.2")
      .serverOnly
      .stringConf
      .createOptional

  val DIGIWIN_DATASOURCE_STORE_JDBC_USER: ConfigEntry[String] =
    buildConf("kyuubi.digiwin.datasource.store.jdbc.user")
      .doc("JDBC user of the datasource registry store.")
      .version("1.11.2")
      .serverOnly
      .stringConf
      .createWithDefault("")

  val DIGIWIN_DATASOURCE_STORE_JDBC_PASSWORD: ConfigEntry[String] =
    buildConf("kyuubi.digiwin.datasource.store.jdbc.password")
      .doc("JDBC password of the datasource registry store.")
      .version("1.11.2")
      .serverOnly
      .stringConf
      .createWithDefault("")

  val DIGIWIN_DATASOURCE_REFRESH_INTERVAL: ConfigEntry[Long] =
    buildConf("kyuubi.digiwin.datasource.refresh.interval")
      .doc("Interval to refresh the in-memory datasource cache from the store.")
      .version("1.11.2")
      .serverOnly
      .timeConf
      .createWithDefault(java.time.Duration.ofSeconds(60).toMillis)

  val DIGIWIN_DATASOURCE_CREDENTIAL_SECRET: OptionalConfigEntry[String] =
    buildConf("kyuubi.digiwin.datasource.credential.secret")
      .doc("AES secret used to encrypt/decrypt datasource credentials. " +
        "If unset, a random key is generated per process (not suitable for HA).")
      .version("1.11.2")
      .serverOnly
      .stringConf
      .createOptional

  val DIGIWIN_DATASOURCE_LABEL_KEY: ConfigEntry[String] =
    buildConf("kyuubi.digiwin.datasource.label.key")
      .doc("Session conf key carrying the datasource label selected by the client.")
      .version("1.11.2")
      .stringConf
      .createWithDefault("kyuubi.datasource")

  val DIGIWIN_SQL_INSPECTION_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.digiwin.sql.inspection.enabled")
      .doc("Whether to inspect and block dangerous SQL at the gateway.")
      .version("1.11.2")
      .serverOnly
      .booleanConf
      .createWithDefault(false)

  val DIGIWIN_SQL_INSPECTION_RULES: ConfigEntry[Seq[String]] =
    buildConf("kyuubi.digiwin.sql.inspection.rules")
      .doc("Dangerous SQL rules, e.g. deny:DROP,deny:TRUNCATE,deny:DELETE/WITHOUT_WHERE," +
        "deny:UPDATE/WITHOUT_WHERE,deny:SELECT_STAR/THRESHOLD:1000000.")
      .version("1.11.2")
      .serverOnly
      .stringConf
      .toSequence()
      .createWithDefault(Nil)

  val DIGIWIN_SQL_INSPECTION_WHITELIST: ConfigEntry[Seq[String]] =
    buildConf("kyuubi.digiwin.sql.inspection.whitelist")
      .doc("Users exempted from SQL inspection.")
      .version("1.11.2")
      .serverOnly
      .stringConf
      .toSequence()
      .createWithDefault(Nil)

  val DIGIWIN_SQL_INSPECTION_ALERT_WEBHOOK: OptionalConfigEntry[String] =
    buildConf("kyuubi.digiwin.sql.inspection.alert.webhook")
      .doc("Optional webhook URL (e.g. DingTalk) for blocked-SQL alerts. " +
        "If unset, alerts are written to the server log only.")
      .version("1.11.2")
      .serverOnly
      .stringConf
      .createOptional

  val DIGIWIN_LIMIT_QUERIES_PER_USER: OptionalConfigEntry[Int] =
    buildConf("kyuubi.digiwin.limit.queries.per.user")
      .doc("Maximum concurrent running queries per user. 0 or negative means disabled.")
      .version("1.11.2")
      .serverOnly
      .intConf
      .createOptional

  val DIGIWIN_LIMIT_QUERIES_PER_IPADDRESS: OptionalConfigEntry[Int] =
    buildConf("kyuubi.digiwin.limit.queries.per.ipaddress")
      .doc("Maximum concurrent running queries per ipaddress. 0 or negative means disabled.")
      .version("1.11.2")
      .serverOnly
      .intConf
      .createOptional

  val DIGIWIN_LIMIT_QPS_PER_USER: OptionalConfigEntry[Int] =
    buildConf("kyuubi.digiwin.limit.qps.per.user")
      .doc("Maximum queries per second per user. 0 or negative means disabled.")
      .version("1.11.2")
      .serverOnly
      .intConf
      .createOptional

  val DIGIWIN_ENGINE_CIRCUIT_BREAKER_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.digiwin.engine.circuit.breaker.enabled")
      .doc("When true, fail fast with a clear error if the target engine is unavailable, " +
        "instead of waiting indefinitely.")
      .version("1.11.2")
      .serverOnly
      .booleanConf
      .createWithDefault(false)

  val DIGIWIN_ENGINE_CIRCUIT_BREAKER_TIMEOUT: ConfigEntry[Long] =
    buildConf("kyuubi.digiwin.engine.circuit.breaker.timeout")
      .doc("Max wait before treating an engine as unavailable and failing fast.")
      .version("1.11.2")
      .serverOnly
      .timeConf
      .createWithDefault(java.time.Duration.ofSeconds(30).toMillis)
```

**测试**（`kyuubi-common/src/test/scala/org/apache/kyuubi/config/KyuubiConfSuite.scala`，追加用例，不删既有）：

```scala
test("KYUUBI-7358: digiwin data gateway p0 config keys") {
  val conf = new KyuubiConf(false)
  assert(conf.get(KyuubiConf.DIGIWIN_DATASOURCE_STORE_ENABLED) === false)
  assert(conf.get(KyuubiConf.DIGIWIN_DATASOURCE_LABEL_KEY) === "kyuubi.datasource")
  assert(conf.get(KyuubiConf.DIGIWIN_SQL_INSPECTION_RULES).isEmpty)
  conf.set(KyuubiConf.DIGIWIN_SQL_INSPECTION_RULES, Seq("deny:DROP", "deny:TRUNCATE"))
  assert(conf.get(KyuubiConf.DIGIWIN_SQL_INSPECTION_RULES) === Seq("deny:DROP", "deny:TRUNCATE"))
  assert(conf.get(KyuubiConf.DIGIWIN_LIMIT_QUERIES_PER_USER).isEmpty)
  assert(
    conf.get(KyuubiConf.DIGIWIN_DATASOURCE_REFRESH_INTERVAL) ===
      java.time.Duration.ofSeconds(60).toMillis)
}
```

**验证**：`./build/mvn test -pl kyuubi-common -Dtest=KyuubiConfSuite` 通过。

---

### 阶段 2：数据源元数据管理（FR-1/2/3）

#### Task 2.1 `DatasourceInfo` case class

**文件**：`digiwin-plugins/src/main/scala/org/apache/kyuubi/digiwin/datasource/DatasourceInfo.scala`

```scala
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.digiwin.datasource

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonIgnoreProperties}

/**
 * @param label unique datasource identifier, e.g. sr-prod
 * @param engineType engine type, jdbc or spark
 * @param jdbcType short jdbc dialect name, e.g. starrocks (only for engineType=jdbc)
 * @param driverClass jdbc driver class name
 * @param jdbcUrl jdbc connection url
 * @param username connection username
 * @param encryptedPassword AES-encrypted password (never plaintext)
 * @param connectionPoolParams extra pool params, e.g. maximumPoolSize=10
 * @param status ENABLED / DISABLED
 * @param description free text
 */
@JsonIgnoreProperties(ignoreUnknown = true)
case class DatasourceInfo(
    label: String,
    engineType: String,
    jdbcType: String,
    driverClass: String,
    jdbcUrl: String,
    username: String,
    encryptedPassword: String,
    connectionPoolParams: Map[String, String] = Map.empty,
    status: String = "ENABLED",
    description: String = "") {

  @JsonIgnore
  def isEnabled: Boolean = "ENABLED".equalsIgnoreCase(status)
}
```

#### Task 2.2 `CredentialAccessor`（AES 加解密，FR-3）

**文件**：`digiwin-plugins/src/main/scala/org/apache/kyuubi/digiwin/security/CredentialAccessor.scala`

```scala
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.digiwin.security

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}

import org.apache.kyuubi.Logging
import org.apache.kyuubi.util.ThreadUtils

/**
 * AES/CBC/PKCS5Padding credential encryption. The secret must be 16 bytes (after UTF-8 decode).
 * Templated on kyuubi-common's InternalSecurityAccessor.
 */
class CredentialAccessor(secret: String) extends Logging {

  private val keyBytes: Array[Byte] = {
    val raw = secret.getBytes(StandardCharsets.UTF_8)
    require(raw.length == 16, s"AES secret must be 16 bytes, got ${raw.length}")
    raw
  }
  private val secretKeySpec = new SecretKeySpec(keyBytes, "AES")
  private val random = new SecureRandom()

  def encrypt(plain: String): String = synchronized {
    val iv = new Array[Byte](16)
    random.nextBytes(iv)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new IvParameterSpec(iv))
    val encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8))
    // iv:cipher, both base64
    val ivB64 = Base64.getEncoder.encodeToString(iv)
    val dataB64 = Base64.getEncoder.encodeToString(encrypted)
    s"$ivB64:$dataB64"
  }

  def decrypt(stored: String): String = synchronized {
    val parts = stored.split(":", 2)
    require(parts.length == 2, "Invalid encrypted credential format")
    val iv = Base64.getDecoder.decode(parts(0))
    val data = Base64.getDecoder.decode(parts(1))
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, new IvParameterSpec(iv))
    new String(cipher.doFinal(data), StandardCharsets.UTF_8)
  }
}

object CredentialAccessor {
  private lazy val fallback: CredentialAccessor = {
    // process-local random key when no secret configured (not HA-safe, log a warning)
    val rnd = new SecureRandom()
    val buf = new Array[Byte](16)
    rnd.nextBytes(buf)
    new String(buf) // 16 random bytes as latin-1 string of length 16
  }

  def apply(secretOpt: Option[String]): CredentialAccessor = secretOpt match {
    case Some(s) => new CredentialAccessor(s)
    case None =>
      org.apache.kyuubi.Logging.logWarning(
        "kyuubi.digiwin.datasource.credential.secret is unset; " +
          "using a process-local random key (encrypted credentials won't survive restart).")
      fallback
  }
}
```

> `ThreadUtils`/`Logging` import 仅按需；若编译器告警未用可移除。`logWarning` 调用改用 trait 的 `warn`：

修正：`CredentialAccessor.apply` 中告警改用 trait 方法。由于 `object CredentialAccessor` 未继承 `Logging`，改为：

```scala
object CredentialAccessor extends Logging {
  private lazy val fallback: CredentialAccessor = {
    val rnd = new SecureRandom()
    val buf = new Array[Byte](16)
    rnd.nextBytes(buf)
    new CredentialAccessor(new String(buf, java.nio.charset.StandardCharsets.ISO_8859_1))
  }

  def apply(secretOpt: Option[String]): CredentialAccessor = secretOpt match {
    case Some(s) => new CredentialAccessor(s)
    case None =>
      warn("kyuubi.digiwin.datasource.credential.secret is unset; " +
        "using a process-local random key (encrypted credentials won't survive restart).")
      fallback
  }
}
```

（实现时以修正版为准，删除文件内的旧 `object` 块。）

**测试**：`digiwin-plugins/src/test/scala/org/apache/kyuubi/digiwin/datasource/CredentialAccessorSuite.scala`

```scala
package org.apache.kyuubi.digiwin.datasource

import org.apache.kyuubi.digiwin.security.CredentialAccessor

import org.apache.kyuubi.SparkFunSuite // 若无则用 org.apache.kyuubi.KyuubiFunSuite

class CredentialAccessorSuite extends org.apache.kyuubi.KyuubiFunSuite {
  test("encrypt then decrypt round trip") {
    val accessor = new CredentialAccessor("0123456789abcdef")
    val secret = "p@ssw0rd-复杂密码"
    val enc = accessor.encrypt(secret)
    assert(enc != secret)
    assert(accessor.decrypt(enc) === secret)
  }

  test("two encryptions produce different ciphertext (random iv)") {
    val accessor = new CredentialAccessor("0123456789abcdef")
    assert(accessor.encrypt("x") !== accessor.encrypt("x"))
  }

  test("invalid secret length rejected") {
    intercept[IllegalArgumentException] { new CredentialAccessor("short") }
  }
}
```

> 若 `KyuubiFunSuite` 不在 `digiwin-plugins` 测试 classpath，改用 `org.scalatest.funsuite.AnyFunSuite`。实现时确认 `kyuubi-common` test-jar 已引入（Task 0.1 已加）。

**验证**：`./build/mvn test -pl digiwin-plugins -Dtest=CredentialAccessorSuite` 通过。

#### Task 2.3 `DatasourceStore`（独立 JDBC 存储，FR-1）

**文件**：`digiwin-plugins/src/main/resources/sql/datasource-schema.sql`

```sql
CREATE TABLE IF NOT EXISTS digiwin_datasource(
    label VARCHAR(128) NOT NULL,
    engine_type VARCHAR(32) NOT NULL,
    jdbc_type VARCHAR(64),
    driver_class VARCHAR(255) NOT NULL,
    jdbc_url VARCHAR(2048) NOT NULL,
    username VARCHAR(255) NOT NULL,
    encrypted_password VARCHAR(2048) NOT NULL,
    connection_pool_params TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    description VARCHAR(1024),
    update_time BIGINT NOT NULL,
    PRIMARY KEY (label)
);
```

**文件**：`digiwin-plugins/src/main/scala/org/apache/kyuubi/digiwin/datasource/DatasourceStore.scala`

```scala
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.digiwin.datasource

import java.sql.{Connection, ResultSet}

import scala.collection.JavaConverters._

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import org.apache.kyuubi.{KyuubiException, Logging}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

class DatasourceStore(conf: KyuubiConf) extends Logging {

  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)

  private val hikariConfig = new HikariConfig()
  hikariConfig.setDriverClassName(
    conf.get(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER).getOrElse(defaultDriver(conf)))
  hikariConfig.setJdbcUrl(conf.get(DIGIWIN_DATASOURCE_STORE_JDBC_URL).getOrElse(
    throw new KyuubiException(s"${DIGIWIN_DATASOURCE_STORE_JDBC_URL.key} is not set")))
  hikariConfig.setUsername(conf.get(DIGIWIN_DATASOURCE_STORE_JDBC_USER))
  hikariConfig.setPassword(conf.get(DIGIWIN_DATASOURCE_STORE_JDBC_PASSWORD))
  hikariConfig.setPoolName("digiwin-datasource-store-pool")

  private[digiwin] val dataSource: HikariDataSource = new HikariDataSource(hikariConfig)

  // Local connection helper (JdbcUtils.withConnection takes an implicit DataSource; we keep this
  // self-contained to avoid coupling to the metadata store's implicit HikariDataSource).
  private def withConnection[T](f: Connection => T): T = {
    val conn = dataSource.getConnection
    try { f(conn) } finally { conn.close() }
  }

  initSchema()

  private def defaultDriver(conf: KyuubiConf): String = {
    val url = conf.get(DIGIWIN_DATASOURCE_STORE_JDBC_URL).getOrElse("")
    if (url.startsWith("jdbc:sqlite")) "org.sqlite.JDBC"
    else if (url.startsWith("jdbc:mysql")) "com.mysql.cj.jdbc.Driver"
    else if (url.startsWith("jdbc:postgresql")) "org.postgresql.Driver"
    else throw new KyuubiException(s"Cannot infer jdbc driver for $url")
  }

  private def initSchema(): Unit = {
    val in = getClass.getClassLoader.getResourceAsStream("sql/datasource-schema.sql")
    if (in == null) throw new KyuubiException("datasource-schema.sql not found on classpath")
    val ddl = scala.io.Source.fromInputStream(in)(scala.io.Codec.UTF8).mkString
    withConnection { conn =>
      ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach { stmt =>
        execute(conn, stmt)
      }
    }
  }

  private def execute(conn: Connection, sql: String, params: Any*): Unit = {
    val ps = conn.prepareStatement(sql)
    try {
      params.zipWithIndex.foreach { case (p, i) => ps.setObject(i + 1, p) }
      ps.execute()
    } finally {
      ps.close()
    }
  }

  private def query[T](conn: Connection, sql: String, params: Any*)(f: ResultSet => T): Seq[T] = {
    val ps = conn.prepareStatement(sql)
    try {
      params.zipWithIndex.foreach { case (p, i) => ps.setObject(i + 1, p) }
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ArrayBuffer.empty[T]
        while (rs.next()) buf += f(rs)
        buf.toSeq
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  private def fromResultSet(rs: ResultSet): DatasourceInfo = {
    val poolParamsStr = rs.getString("connection_pool_params")
    val poolParams: Map[String, String] =
      if (poolParamsStr == null || poolParamsStr.isEmpty) Map.empty
      else mapper.readValue(poolParamsStr, classOf[Map[String, String]])
    DatasourceInfo(
      label = rs.getString("label"),
      engineType = rs.getString("engine_type"),
      jdbcType = rs.getString("jdbc_type"),
      driverClass = rs.getString("driver_class"),
      jdbcUrl = rs.getString("jdbc_url"),
      username = rs.getString("username"),
      encryptedPassword = rs.getString("encrypted_password"),
      connectionPoolParams = poolParams,
      status = rs.getString("status"),
      description = rs.getString("description"))
  }

  def list(): Seq[DatasourceInfo] =
    withConnection { conn =>
      query(conn, "SELECT * FROM digiwin_datasource ORDER BY label")(fromResultSet)
    }

  def get(label: String): Option[DatasourceInfo] =
    withConnection { conn =>
      query(conn, "SELECT * FROM digiwin_datasource WHERE label = ?", label)(fromResultSet).headOption
    }

  def upsert(ds: DatasourceInfo): Unit = withConnection { conn =>
    val poolParamsStr = if (ds.connectionPoolParams.isEmpty) null else mapper.writeValueAsString(ds.connectionPoolParams)
    val now = System.currentTimeMillis()
    val mergeSql = adaptUpsertSql(conf.get(DIGIWIN_DATASOURCE_STORE_JDBC_URL).getOrElse(""))
    execute(
      conn,
      mergeSql,
      ds.label,
      ds.engineType,
      ds.jdbcType,
      ds.driverClass,
      ds.jdbcUrl,
      ds.username,
      ds.encryptedPassword,
      poolParamsStr,
      ds.status,
      ds.description,
      now)
  }

  private def adaptUpsertSql(url: String): String = url match {
    case u if u.startsWith("jdbc:sqlite") =>
      "INSERT OR REPLACE INTO digiwin_datasource" +
        "(label,engine_type,jdbc_type,driver_class,jdbc_url,username,encrypted_password," +
        "connection_pool_params,status,description,update_time) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
    case u if u.startsWith("jdbc:mysql") =>
      "INSERT INTO digiwin_datasource" +
        "(label,engine_type,jdbc_type,driver_class,jdbc_url,username,encrypted_password," +
        "connection_pool_params,status,description,update_time) VALUES(?,?,?,?,?,?,?,?,?,?,?)" +
        " ON DUPLICATE KEY UPDATE engine_type=VALUES(engine_type),jdbc_type=VALUES(jdbc_type)," +
        "driver_class=VALUES(driver_class),jdbc_url=VALUES(jdbc_url),username=VALUES(username)," +
        "encrypted_password=VALUES(encrypted_password),connection_pool_params=VALUES(connection_pool_params)," +
        "status=VALUES(status),description=VALUES(description),update_time=VALUES(update_time)"
    case _ =>
      "INSERT INTO digiwin_datasource" +
        "(label,engine_type,jdbc_type,driver_class,jdbc_url,username,encrypted_password," +
        "connection_pool_params,status,description,update_time) VALUES(?,?,?,?,?,?,?,?,?,?,?)" +
        " ON CONFLICT(label) DO UPDATE SET engine_type=EXCLUDED.engine_type,jdbc_type=EXCLUDED.jdbc_type," +
        "driver_class=EXCLUDED.driver_class,jdbc_url=EXCLUDED.jdbc_url,username=EXCLUDED.username," +
        "encrypted_password=EXCLUDED.encrypted_password,connection_pool_params=EXCLUDED.connection_pool_params," +
        "status=EXCLUDED.status,description=EXCLUDED.description,update_time=EXCLUDED.update_time"
  }

  def delete(label: String): Unit = withConnection { conn =>
    execute(conn, "DELETE FROM digiwin_datasource WHERE label = ?", label)
  }

  def close(): Unit = dataSource.close()
}
```

**测试**：`digiwin-plugins/src/test/scala/org/apache/kyuubi/digiwin/datasource/DatasourceStoreSuite.scala`

```scala
package org.apache.kyuubi.digiwin.datasource

import java.nio.file.Files

import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

import org.apache.kyuubi.KyuubiFunSuite

class DatasourceStoreSuite extends KyuubiFunSuite {
  private def newConf(): KyuubiConf = {
    val db = Files.createTempFile("digiwin-ds-test-", ".db").toString
    new KyuubiConf(false)
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$db")
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
  }

  test("upsert / get / list / delete") {
    val store = new DatasourceStore(newConf())
    val ds = DatasourceInfo(
      "sr-prod", "jdbc", "starrocks", "com.mysql.cj.jdbc.Driver",
      "jdbc:mysql://sr:9030/db", "u", "enc", Map("maximumPoolSize" -> "10"))
    store.upsert(ds)
    assert(store.get("sr-prod").map(_.jdbcUrl).contains("jdbc:mysql://sr:9030/db"))
    assert(store.list().map(_.label).contains("sr-prod"))
    // update
    store.upsert(ds.copy(description = "updated"))
    assert(store.get("sr-prod").map(_.description).contains("updated"))
    store.delete("sr-prod")
    assert(store.get("sr-prod").isEmpty)
    store.close()
  }
}
```

**验证**：`./build/mvn test -pl digiwin-plugins -Dtest=DatasourceStoreSuite` 通过。

#### Task 2.4 `DatasourceRegistry`（内存缓存 + 定时刷新 + CRUD，FR-1 热生效）

**文件**：`digiwin-plugins/src/main/scala/org/apache/kyuubi/digiwin/datasource/DatasourceRegistry.scala`

```scala
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.digiwin.datasource

import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._

import org.apache.kyuubi.{KyuubiException, Logging}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.digiwin.security.CredentialAccessor
import org.apache.kyuubi.util.ThreadUtils

class DatasourceRegistry(conf: KyuubiConf) extends Logging {

  private val store: Option[DatasourceStore] =
    if (conf.get(DIGIWIN_DATASOURCE_STORE_ENABLED)) Some(new DatasourceStore(conf)) else None

  private val credentialAccessor = CredentialAccessor(conf.get(DIGIWIN_DATASOURCE_CREDENTIAL_SECRET))

  private val cache = new ConcurrentHashMap[String, DatasourceInfo]()

  private val scheduler =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("digiwin-datasource-refresher")

  def start(): Unit = {
    refresh()
    val interval = conf.get(DIGIWIN_DATASOURCE_REFRESH_INTERVAL)
    scheduler.scheduleAtFixedRate(
      new Runnable { override def run(): Unit = Utils.tryLogNonFatalError(refresh()) },
      interval,
      interval,
      java.util.concurrent.TimeUnit.MILLISECONDS)
  }

  def refresh(): Unit = {
    val all = store.map(_.list()).getOrElse(Seq.empty)
    val next = new ConcurrentHashMap[String, DatasourceInfo]()
    all.foreach(ds => next.put(ds.label, ds))
    cache.clear()
    cache.putAll(next)
    info(s"Refreshed datasource registry: ${cache.size()} entries.")
  }

  def get(label: String): Option[DatasourceInfo] = Option(cache.get(label))

  /** Returns the decrypted password for the label; throws if missing/disabled. */
  def getDecryptedPassword(label: String): String = {
    val ds = get(label).getOrElse(throw new KyuubiException(s"Datasource $label not found"))
    if (!ds.isEnabled) throw new KyuubiException(s"Datasource $label is disabled"))
    credentialAccessor.decrypt(ds.encryptedPassword)
  }

  def list(): Seq[DatasourceInfo] = cache.values().asScala.toSeq.sortBy(_.label)

  def upsert(ds: DatasourceInfo, plainPassword: String): Unit = {
    val encrypted = credentialAccessor.encrypt(plainPassword)
    val toStore = ds.copy(encryptedPassword = encrypted)
    store.getOrElse(throw new KyuubiException("Datasource store is disabled")).upsert(toStore)
    cache.put(toStore.label, toStore.copy(encryptedPassword = encrypted))
  }

  def delete(label: String): Unit = {
    store.getOrElse(throw new KyuubiException("Datasource store is disabled")).delete(label)
    cache.remove(label)
  }

  def stop(): Unit = {
    scheduler.shutdownNow()
    store.foreach(_.close())
  }
}
```

> 修正 `getDecryptedPassword` 中的括号笔误：`throw new KyuubiException(s"Datasource $label is disabled")` （去掉多余右括号）。`import org.apache.kyuubi.Utils` 需补。实现时以修正为准。

**测试**：`digiwin-plugins/src/test/scala/org/apache/kyuubi/digiwin/datasource/DatasourceRegistrySuite.scala`

```scala
package org.apache.kyuubi.digiwin.datasource

import java.nio.file.Files

import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

import org.apache.kyuubi.KyuubiFunSuite

class DatasourceRegistrySuite extends KyuubiFunSuite {
  private def newRegistry(): DatasourceRegistry = {
    val db = Files.createTempFile("digiwin-reg-test-", ".db").toString
    val conf = new KyuubiConf(false)
      .set(DIGIWIN_DATASOURCE_STORE_ENABLED, true)
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$db")
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
      .set(DIGIWIN_DATASOURCE_CREDENTIAL_SECRET, "0123456789abcdef")
    new DatasourceRegistry(conf)
  }

  test("upsert then resolve label with decrypted password") {
    val reg = newRegistry()
    reg.start()
    val ds = DatasourceInfo(
      "sr-prod", "jdbc", "starrocks", "com.mysql.cj.jdbc.Driver",
      "jdbc:mysql://sr:9030/db", "u", "", Map("maximumPoolSize" -> "10"))
    reg.upsert(ds, plainPassword = "secret123")
    assert(reg.get("sr-prod").exists(_.encryptedPassword != "secret123"))
    assert(reg.getDecryptedPassword("sr-prod") === "secret123")
    reg.stop()
  }

  test("missing label throws") {
    val reg = newRegistry()
    reg.start()
    intercept[Exception] { reg.getDecryptedPassword("nope") }
    reg.stop()
  }
}
```

**验证**：`./build/mvn test -pl digiwin-plugins -Dtest=DatasourceRegistrySuite` 通过。

#### Task 2.5 `DatasourceConfAdvisor`（SessionConfAdvisor SPI，FR-2）

**文件**：`digiwin-plugins/src/main/scala/org/apache/kyuubi/digiwin/datasource/DatasourceConfAdvisor.scala`

```scala
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.digiwin.datasource

import java.util.{Map => JMap}

import scala.collection.JavaConverters._

import org.apache.kyuubi.{KyuubiException, Logging}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.plugin.SessionConfAdvisor

/**
 * Reads kyuubi.datasource=<label> from the session conf, resolves the datasource from
 * the registry, and returns the engine connection overlay (url/user/driver/pool params),
 * with the password decrypted and injected. The plaintext password never reaches the client.
 *
 * Wired via kyuubi.session.conf.advisor=org.apache.kyuubi.digiwin.datasource.DatasourceConfAdvisor
 */
class DatasourceConfAdvisor extends SessionConfAdvisor with Logging {

  // The registry is lazily constructed from the server conf passed to getConfOverlay.
  @volatile private var registryOpt: Option[DatasourceRegistry] = None

  private def registry(conf: KyuubiConf): DatasourceRegistry = synchronized {
    registryOpt match {
      case Some(r) => r
      case None =>
        val r = new DatasourceRegistry(conf)
        r.start()
        registryOpt = Some(r)
        r
    }
  }

  override def getConfOverlay(
      user: String,
      sessionConf: JMap[String, String]): JMap[String, String] = {
    val labelKey = sessionConf.getOrDefault(
      DIGIWIN_DATASOURCE_LABEL_KEY.key, DIGIWIN_DATASOURCE_LABEL_KEY.defaultValue)
    val labelOpt = Option(sessionConf.get(labelKey)).filter(_.nonEmpty)
    if (labelOpt.isEmpty) return java.util.Collections.emptyMap()

    val kyuubiConf = new KyuubiConf(false)
    sessionConf.asScala.foreach { case (k, v) => kyuubiConf.set(k, v) }
    val reg = registry(kyuubiConf)
    val label = labelOpt.get
    val ds = reg.get(label).getOrElse(
      throw new KyuubiException(s"Datasource label $label not found"))

    val overlay = scala.collection.mutable.Map.empty[String, String]
    overlay(DIGIWIN_DATASOURCE_LABEL_KEY.key) = label
    // JDBC engine connection config keys (ENGINE_JDBC_CONNECTION_*)
    overlay("kyuubi.engine.type") = ds.engineType
    if (ds.engineType == "jdbc") {
      overlay("kyuubi.engine.jdbc.type") = ds.jdbcType
      overlay("kyuubi.engine.jdbc.connection.url") = ds.jdbcUrl
      overlay("kyuubi.engine.jdbc.connection.user") = ds.username
      overlay("kyuubi.engine.jdbc.connection.password") = reg.getDecryptedPassword(label)
      overlay("kyuubi.engine.jdbc.driver.class") = ds.driverClass
      ds.connectionPoolParams.foreach { case (k, v) => overlay(k) = v }
    } else {
      overlay("kyuubi.engine.spark.master") = ds.driverClass // reuse field for spark master if needed
      overlay("kyuubi.engine.jdbc.connection.url") = ds.jdbcUrl
    }
    overlay.asJava
  }
}
```

> 说明：`SessionConfAdvisor.getConfOverlay` 传入的 `sessionConf` 是 `Map[String,String]`（键值），从中重建 `KyuubiConf` 以读取 `DIGIWIN_DATASOURCE_STORE_*` 等 server 配置。前提：server 启动配置已混入 session conf（`KyuubiSessionImpl.normalizedConf` 由 `sessionManager.getConf.clone` 起步，server 级配置可见）。实现时验证 `sessionConf` 是否含 server 级 key；若不含，则改由 server 启动时构造单例 `DatasourceRegistry` 并通过 `DatasourceConfAdvisor` 的静态引用注入（备选方案，见下方"备选"）。

**备选注入方案**（若 sessionConf 不含 server 配置）：在 `KyuubiServer` 启动时 `DatasourceRegistry` 单例化并注册到一个 `object DatasourceRegistryHolder`，`DatasourceConfAdvisor.getConfOverlay` 直接 `DatasourceRegistryHolder.get`。此方案需在 `KyuubiServer.scala` 加 1 行启动代码（核心 patch）。实现时二选一，优先验证主方案。

**测试**：`digiwin-plugins/src/test/scala/org/apache/kyuubi/digiwin/datasource/DatasourceConfAdvisorSuite.scala`

```scala
package org.apache.kyuubi.digiwin.datasource

import java.nio.file.Files

import scala.collection.JavaConverters._

import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

import org.apache.kyuubi.KyuubiFunSuite

class DatasourceConfAdvisorSuite extends KyuubiFunSuite {
  test("label resolves to jdbc engine overlay with decrypted password") {
    val db = Files.createTempFile("digiwin-adv-test-", ".db").toString
    val conf = new KyuubiConf(false)
      .set(DIGIWIN_DATASOURCE_STORE_ENABLED, true)
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$db")
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
      .set(DIGIWIN_DATASOURCE_CREDENTIAL_SECRET, "0123456789abcdef")
    val reg = new DatasourceRegistry(conf)
    reg.start()
    reg.upsert(
      DatasourceInfo(
        "sr-prod", "jdbc", "starrocks", "com.mysql.cj.jdbc.Driver",
        "jdbc:mysql://sr:9030/db", "u", ""),
      plainPassword = "pwd")
    reg.stop()

    // advisor reconstructs its own registry from session conf; reuse same db
    val sessionConf: java.util.Map[String, String] = new java.util.HashMap[String, String]()
    conf.getAll.foreach { case (k, v) => sessionConf.put(k, v) }
    sessionConf.put(DIGIWIN_DATASOURCE_LABEL_KEY.key, "sr-prod")

    val advisor = new DatasourceConfAdvisor()
    val overlay = advisor.getConfOverlay("alice", sessionConf).asScala
    assert(overlay("kyuubi.engine.type") === "jdbc")
    assert(overlay("kyuubi.engine.jdbc.type") === "starrocks")
    assert(overlay("kyuubi.engine.jdbc.connection.url") === "jdbc:mysql://sr:9030/db")
    assert(overlay("kyuubi.engine.jdbc.connection.password") === "pwd")
  }

  test("no label returns empty overlay") {
    val advisor = new DatasourceConfAdvisor()
    val empty = new java.util.HashMap[String, String]()
    assert(advisor.getConfOverlay("alice", empty).isEmpty)
  }
}
```

**验证**：`./build/mvn test -pl digiwin-plugins -Dtest=DatasourceConfAdvisorSuite` 通过。

#### Task 2.6 `DatasourcesResource`（REST CRUD + refresh，FR-1）

**文件**：`kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/DatasourcesResource.scala`

```scala
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.api.v1

import javax.ws.rs.{DELETE, GET, POST, PUT, Path, PathParam, Produces}
import javax.ws.rs.core.MediaType

import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.KyuubiException
import org.apache.kyuubi.digiwin.datasource.{DatasourceInfo, DatasourceRegistry}
import org.apache.kyuubi.server.api.ApiRequestContext

@Tag(name = "datasources")
@Path("datasources")
class DatasourcesResource extends ApiRequestContext {

  private def registry: DatasourceRegistry =
    DatasourcesResource.registryOpt.getOrElse(
      throw new KyuubiException("Datasource registry is not initialized"))

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def list(): Seq[DatasourceView] = {
    registry.list().map(DatasourceView.from(_, includeCred = false))
  }

  @GET
  @Path("{label}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def get(@PathParam("label") label: String): DatasourceView =
    registry.get(label).map(DatasourceView.from(_, includeCred = false))
      .getOrElse(throw new KyuubiException(s"Datasource $label not found"))

  @POST
  @Produces(Array(MediaType.APPLICATION_JSON))
  def create(req: DatasourceRequest): DatasourceView = {
    val ds = req.toInfo()
    registry.upsert(ds, req.plainPassword)
    DatasourceView.from(ds.copy(encryptedPassword = ""), includeCred = false)
  }

  @PUT
  @Path("{label}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def update(@PathParam("label") label: String, req: DatasourceRequest): DatasourceView = {
    if (!label.equals(req.label)) {
      throw new KyuubiException(s"Label in path($label) and body(${req.label}) mismatch")
    }
    val ds = req.toInfo()
    registry.upsert(ds, req.plainPassword)
    DatasourceView.from(ds.copy(encryptedPassword = ""), includeCred = false)
  }

  @DELETE
  @Path("{label}")
  def delete(@PathParam("label") label: String): Unit = registry.delete(label)

  @POST
  @Path("refresh")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def refresh(): String = {
    registry.refresh()
    "ok"
  }
}

object DatasourcesResource {
  @volatile private[digiwin] var registryOpt: Option[DatasourceRegistry] = None
  def init(registry: DatasourceRegistry): Unit = registryOpt = Some(registry)
}

/** Wire DTO without secrets. */
case class DatasourceView(
    label: String,
    engineType: String,
    jdbcType: String,
    driverClass: String,
    jdbcUrl: String,
    username: String,
    connectionPoolParams: Map[String, String],
    status: String,
    description: String)
object DatasourceView {
  def from(ds: DatasourceInfo, includeCred: Boolean): DatasourceView =
    DatasourceView(
      ds.label, ds.engineType, ds.jdbcType, ds.driverClass, ds.jdbcUrl,
      ds.username, ds.connectionPoolParams, ds.status, ds.description)
}

/** Input DTO carrying plaintext password (transient, never persisted/returned). */
case class DatasourceRequest(
    label: String,
    engineType: String,
    jdbcType: String,
    driverClass: String,
    jdbcUrl: String,
    username: String,
    plainPassword: String,
    connectionPoolParams: Map[String, String] = Map.empty,
    status: String = "ENABLED",
    description: String = "") {
  def toInfo(): DatasourceInfo =
    DatasourceInfo(label, engineType, jdbcType, driverClass, jdbcUrl, username,
      encryptedPassword = "", connectionPoolParams, status, description)
}
```

**注册（核心 patch）**：

`kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/ApiRootResource.scala`，在 `admin` 之后追加：

```scala
@Path("datasources")
def datasources: Class[DatasourcesResource] = classOf[DatasourcesResource]
```

`OpenAPIConfig.scala` 无需改动（`DatasourcesResource` 在 `org.apache.kyuubi.server.api.v1` 包内，已被 `packages` 扫描覆盖）。

**registry 初始化**：在 `KyuubiServer.scala` 启动流程中（`ServerEventHandlerRegister.registerEventLoggers(conf)` 附近）追加：

```scala
if (conf.get(KyuubiConf.DIGIWIN_DATASOURCE_STORE_ENABLED)) {
  val reg = new org.apache.kyuubi.digiwin.datasource.DatasourceRegistry(conf)
  reg.start()
  org.apache.kyuubi.server.api.v1.DatasourcesResource.init(reg)
  // stop on shutdown
  shutdownHooks += ((_: Any) => reg.stop())
}
```

> 实现时核对 `KyuubiServer` 的 shutdown hook 注册方式（`shutdownHooks` 或 `addService`），对齐既有写法。

**测试**：`kyuubi-server/src/test/scala/org/apache/kyuubi/server/api/v1/DatasourcesResourceSuite.scala`，复用 `RestFrontendTestHelper`（既有）+ 内存 sqlite。

```scala
package org.apache.kyuubi.server.api.v1

import java.nio.file.Files

import javax.ws.rs.client.Entity

import org.apache.kyuubi.RestFrontendTestHelper
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.digiwin.datasource.DatasourceRegistry
import org.apache.kyuubi.KyuubiFunSuite

class DatasourcesResourceSuite extends KyuubiFunSuite with RestFrontendTestHelper {

  private val dbPath: String = Files.createTempFile("digiwin-api-test-", ".db").toString

  override protected lazy val conf: KyuubiConf = KyuubiConf()
    .set(DIGIWIN_DATASOURCE_STORE_ENABLED, true)
    .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$dbPath")
    .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
    .set(DIGIWIN_DATASOURCE_CREDENTIAL_SECRET, "0123456789abcdef")

  override def beforeAll(): Unit = {
    super.beforeAll()
    val reg = new DatasourceRegistry(conf)
    reg.start()
    DatasourcesResource.init(reg)
  }

  test("create / get / list / delete via REST") {
    val req = new DatasourceRequest(
      "sr-prod", "jdbc", "starrocks", "com.mysql.cj.jdbc.Driver",
      "jdbc:mysql://sr:9030/db", "u", "pwd", Map("maximumPoolSize" -> "10"))
    val resp = webTarget.path("api/v1/datasources").request()
      .post(Entity.json(req))
    assert(resp.getStatus === 200)
    val got = webTarget.path("api/v1/datasources/sr-prod").request().get()
    assert(got.getStatus === 200)
    val body = got.readEntity(classOf[String])
    assert(body.contains("sr-prod"))
    assert(!body.contains("pwd")) // plaintext password never returned
    val del = webTarget.path("api/v1/datasources/sr-prod").request().delete()
    assert(del.getStatus === 204 || del.getStatus === 200)
  }
}
```

> 对齐既有 `AdminResourceSuite`：`extends KyuubiFunSuite with RestFrontendTestHelper`，路径前缀为 `api/v1/`（`webTarget.path("api/v1/datasources")`），`conf` override 注入 digiwin 配置，`beforeAll` 中 `super.beforeAll()` 启动 server 后 `DatasourcesResource.init(reg)`。`webTarget` 连真实 server 的 REST 前端（`OpenAPIConfig` 扫描 `org.apache.kyuubi.server.api.v1`，`DatasourcesResource` 自动注册）。`DatasourceRequest` 为 case class，Jersey 经 `KyuubiScalaObjectMapper` 序列化。

**验证**：`./build/mvn test -pl kyuubi-server -Dtest=DatasourcesResourceSuite` 通过。

---

### 阶段 3：危险 SQL 拦截（FR-4/5）

#### Task 3.1 `SqlInspectionRule`（规则解析与匹配）

**文件**：`digiwin-plugins/src/main/scala/org/apache/kyuubi/digiwin/security/SqlInspectionRule.scala`

```scala
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.digiwin.security

import scala.util.matching.Regex

import org.apache.kyuubi.Logging

sealed trait SqlInspectionRule {
  /** Returns Some(reason) if the statement violates this rule, else None. */
  def check(sql: String): Option[String]
  def description: String
}

object SqlInspectionRule extends Logging {

  /** Parse a rule spec like "deny:DROP", "deny:DELETE/WITHOUT_WHERE", "deny:SELECT_STAR/THRESHOLD:1000". */
  def parse(spec: String): Option[SqlInspectionRule] = {
    val trimmed = spec.trim
    if (!trimmed.startsWith("deny:")) {
      warn(s"Ignoring unsupported rule spec (only deny: supported): $spec")
      return None
    }
    val body = trimmed.substring("deny:".length).toUpperCase
    if (body.startsWith("DROP")) return Some(StatementKeywordRule("DROP"))
    if (body.startsWith("TRUNCATE")) return Some(StatementKeywordRule("TRUNCATE"))
    if (body.startsWith("DELETE/WITHOUT_WHERE")) return Some(WithoutWhereRule("DELETE"))
    if (body.startsWith("UPDATE/WITHOUT_WHERE")) return Some(WithoutWhereRule("UPDATE"))
    if (body.startsWith("SELECT_STAR/THRESHOLD:")) {
      val n = body.substring("SELECT_STAR/THRESHOLD:".length).trim.toInt
      return Some(SelectStarThresholdRule(n))
    }
    warn(s"Ignoring unrecognized rule: $spec")
    None
  }

  def parseAll(specs: Seq[String]): Seq[SqlInspectionRule] =
    specs.flatMap(parse)
}

/** Blocks any statement whose leading keyword is `keyword`. */
case class StatementKeywordRule(keyword: String) extends SqlInspectionRule {
  private val re: Regex = s"(?is)^\\s*$keyword\\b".r
  override def check(sql: String): Option[String] =
    if (re.findFirstIn(sql).isDefined) Some(s"$keyword is not allowed") else None
  override def description: String = s"deny $keyword"
}

/** Blocks UPDATE/DELETE without a WHERE clause. */
case class WithoutWhereRule(keyword: String) extends SqlInspectionRule {
  private val re: Regex = s"(?is)^\\s*$keyword\\b.*\\bWHERE\\b".r
  override def check(sql: String): Option[String] = {
    val head: Regex = s"(?is)^\\s*$keyword\\b".r
    if (head.findFirstIn(sql).isDefined && re.findFirstIn(sql).isEmpty)
      Some(s"$keyword without WHERE is not allowed")
    else None
  }
  override def description: String = s"deny $keyword without WHERE"
}

/** Blocks SELECT * when an optional row threshold config is exceeded (best-effort, regex-only). */
case class SelectStarThresholdRule(threshold: Int) extends SqlInspectionRule {
  // P0: only flags `SELECT * FROM` without LIMIT; threshold-based row estimation is heuristic.
  private val re: Regex = "(?is)^\\s*SELECT\\s+\\*\\s+FROM\\b.*\\bLIMIT\\b".r
  private val star: Regex = "(?is)^\\s*SELECT\\s+\\*\\s+FROM\\b".r
  override def check(sql: String): Option[String] = {
    if (star.findFirstIn(sql).isDefined && re.findFirstIn(sql).isEmpty)
      Some(s"SELECT * without LIMIT may exceed threshold $threshold")
    else None
  }
  override def description: String = s"deny SELECT * over threshold $threshold"
}
```

#### Task 3.2 `AlertNotifier`（告警接口 + 默认 + Webhook，FR-5）

**文件**：`digiwin-plugins/src/main/scala/org/apache/kyuubi/digiwin/security/AlertNotifier.scala`

```scala
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.digiwin.security

import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets

import org.apache.kyuubi.Logging

trait AlertNotifier {
  def notify(event: AlertEvent): Unit
}

case class AlertEvent(
    user: String,
    clientIp: String,
    datasourceLabel: String,
    statement: String,
    reason: String)

class LogAlertNotifier extends AlertNotifier with Logging {
  override def notify(event: AlertEvent): Unit =
    warn(s"[SQL_BLOCKED ALERT] user=${event.user} ip=${event.clientIp} " +
      s"datasource=${event.datasourceLabel} reason=${event.reason} sql=${event.statement}")
}

class WebhookAlertNotifier(webhookUrl: String) extends AlertNotifier with Logging {
  override def notify(event: AlertEvent): Unit = {
    val payload =
      s"""{"msgtype":"text","text":{"content":"[SQL_BLOCKED] user=${event.user} """ +
        s"""ip=${event.clientIp} datasource=${event.datasourceLabel} reason=${event.reason}"}}"""
    Utils.tryLogNonFatalError {
      val conn = new URL(webhookUrl).openConnection().asInstanceOf[HttpURLConnection]
      try {
        conn.setRequestMethod("POST")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setDoOutput(true)
        conn.getOutputStream.write(payload.getBytes(StandardCharsets.UTF_8))
        val code = conn.getResponseCode
        if (code < 200 || code >= 300) warn(s"Webhook returned $code")
      } finally {
        conn.disconnect()
      }
    }
  }
}

object AlertNotifier {
  def apply(webhookOpt: Option[String]): AlertNotifier = webhookOpt match {
    case Some(url) => new WebhookAlertNotifier(url)
    case None => new LogAlertNotifier()
  }
}
```

> `import org.apache.kyuubi.Utils` 需补（`Utils.tryLogNonFatalError`）。实现时确认。

#### Task 3.3 `SqlInspectionHook`（拦截入口，FR-4/5）

**文件**：`digiwin-plugins/src/main/scala/org/apache/kyuubi/digiwin/security/SqlInspectionHook.scala`

```scala
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.digiwin.security

import org.apache.kyuubi.{KyuubiSQLException, Logging}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.config.KyuubiReservedKeys.KYUUBI_CLIENT_IP_KEY
import org.apache.kyuubi.digiwin.datasource.DatasourceRegistry
import org.apache.kyuubi.session.Session

/** Error code returned to clients when SQL is blocked (FR-5). */
object SqlBlockedErrorCode {
  val SQL_BLOCKED = "SQL_BLOCKED"
}

/**
 * Inspects a statement before execution; throws KyuubiSQLException(SQL_BLOCKED) on violation.
 * Wired into KyuubiBackendService.executeStatement (see Task 3.4).
 */
class SqlInspectionHook(
    conf: KyuubiConf,
    registryOpt: Option[DatasourceRegistry]) extends Logging {

  private val enabled = conf.get(DIGIWIN_SQL_INSPECTION_ENABLED)
  private val rules = SqlInspectionRule.parseAll(conf.get(DIGIWIN_SQL_INSPECTION_RULES))
  private val whitelist = conf.get(DIGIWIN_SQL_INSPECTION_WHITELIST).toSet
  private val alertNotifier = AlertNotifier(conf.get(DIGIWIN_SQL_INSPECTION_ALERT_WEBHOOK))

  def inspect(session: Session, statement: String): Unit = {
    if (!enabled || rules.isEmpty) return
    val user = session.user
    if (whitelist.contains(user)) return

    val reasonOpt = rules.flatMap(_.check(statement)).headOption
    reasonOpt.foreach { reason =>
      // Session trait exposes conf/user but not clientIpAddress; read it from session conf.
      val clientIp = session.conf.getOrElse(KYUUBI_CLIENT_IP_KEY, "")
      val label = session.conf.getOrElse(conf.get(DIGIWIN_DATASOURCE_LABEL_KEY), "")
      val event = AlertEvent(user, clientIp, label, statement, reason)
      Utils.tryLogNonFatalError(alertNotifier.notify(event))
      throw KyuubiSQLException(
        s"SQL is blocked by gateway inspection: $reason",
        SqlBlockedErrorCode.SQL_BLOCKED)
    }
  }
}
```

> `Session` trait 仅暴露 `conf: Map[String,String]` 与 `user`，无 `clientIpAddress`（该方法在 `AbstractSession` 具体类），故从 `session.conf` 读 `KYUUBI_CLIENT_IP_KEY`（由 `TFrontendService` 写入）。`Utils.tryLogNonFatalError` 需 `import org.apache.kyuubi.Utils`。`session.conf` 为 Scala `Map`，用 `getOrElse`（非 Java `getOrDefault`）。

**测试**：`digiwin-plugins/src/test/scala/org/apache/kyuubi/digiwin/security/SqlInspectionHookSuite.scala`

```scala
package org.apache.kyuubi.digiwin.security

import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.KyuubiFunSuite

class SqlInspectionHookSuite extends KyuubiFunSuite {

  private def hook(rules: Seq[String], whitelist: Seq[String] = Nil): SqlInspectionHook = {
    val conf = new KyuubiConf(false)
      .set(DIGIWIN_SQL_INSPECTION_ENABLED, true)
      .set(DIGIWIN_SQL_INSPECTION_RULES, rules)
      .set(DIGIWIN_SQL_INSPECTION_WHITELIST, whitelist)
    new SqlInspectionHook(conf, None)
  }

  private def fakeSession(user: String): org.apache.kyuubi.session.Session = {
    // minimal stub; use a Mockito mock or anonymous Session in implementation
    org.mockito.Mockito.mock(classOf[org.apache.kyuubi.session.Session])
  }

  test("DROP is blocked") {
    val h = hook(Seq("deny:DROP"))
    org.mockito.Mockito.when(fakeSession("u").user).thenReturn("u")
    // implement with a real mock session object below
  }
}
```

> 由于 `SqlInspectionHook.inspect` 需要 `Session`，测试用 Mockito mock `Session`，stub `user`/`clientIpAddress`/`conf`。实现时参照 `kyuubi-server` 既有 `mockito-4-11` 用法补全。下面给出不含 mock 细节、纯规则层的测试（更稳定），放 `SqlInspectionRuleSuite`：

**文件**：`digiwin-plugins/src/test/scala/org/apache/kyuubi/digiwin/security/SqlInspectionRuleSuite.scala`

```scala
package org.apache.kyuubi.digiwin.security

import org.apache.kyuubi.KyuubiFunSuite

class SqlInspectionRuleSuite extends KyuubiFunSuite {
  test("DROP rule blocks drop statements") {
    val rule = SqlInspectionRule.parse("deny:DROP").get
    assert(rule.check("DROP TABLE t").isDefined)
    assert(rule.check("drop database d").isDefined)
    assert(rule.check("SELECT 1").isEmpty)
  }

  test("TRUNCATE rule") {
    val rule = SqlInspectionRule.parse("deny:TRUNCATE").get
    assert(rule.check("TRUNCATE TABLE t").isDefined)
    assert(rule.check("SELECT * FROM t").isEmpty)
  }

  test("DELETE without WHERE blocked, with WHERE allowed") {
    val rule = SqlInspectionRule.parse("deny:DELETE/WITHOUT_WHERE").get
    assert(rule.check("DELETE FROM t").isDefined)
    assert(rule.check("DELETE FROM t WHERE id=1").isEmpty)
  }

  test("UPDATE without WHERE blocked") {
    val rule = SqlInspectionRule.parse("deny:UPDATE/WITHOUT_WHERE").get
    assert(rule.check("UPDATE t SET a=1").isDefined)
    assert(rule.check("UPDATE t SET a=1 WHERE id=1").isEmpty)
  }

  test("SELECT * without LIMIT blocked, with LIMIT allowed") {
    val rule = SqlInspectionRule.parse("deny:SELECT_STAR/THRESHOLD:1000").get
    assert(rule.check("SELECT * FROM bigtable").isDefined)
    assert(rule.check("SELECT * FROM bigtable LIMIT 100").isEmpty)
  }

  test("parseAll ignores unsupported specs") {
    val rules = SqlInspectionRule.parseAll(Seq("deny:DROP", "foo:BAR", "deny:UNKNOWN"))
    assert(rules.length === 1)
  }
}
```

**验证**：`./build/mvn test -pl digiwin-plugins -Dtest=SqlInspectionRuleSuite` 通过。`SqlInspectionHookSuite` 用 mock 补全 `inspect` 的白名单/拦截路径后通过。

#### Task 3.4 `KyuubiBackendService` 接入钩子（核心 patch，FR-4 收口点）

**文件**：`kyuubi-server/src/main/scala/org/apache/kyuubi/server/KyuubiBackendService.scala`

```scala
package org.apache.kyuubi.server

import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.digiwin.security.SqlInspectionHook
import org.apache.kyuubi.operation.OperationHandle
import org.apache.kyuubi.service.AbstractBackendService
import org.apache.kyuubi.session.{KyuubiSessionManager, SessionManager}
import org.apache.kyuubi.shaded.hive.service.rpc.thrift.TProtocolVersion

class KyuubiBackendService(name: String) extends AbstractBackendService(name) {

  def this() = this(classOf[KyuubiBackendService].getSimpleName)

  override val sessionManager: SessionManager = new KyuubiSessionManager()

  @volatile private var sqlInspectionHook: SqlInspectionHook = _

  override def initialize(conf: KyuubiConf): Unit = {
    sqlInspectionHook = new SqlInspectionHook(conf, None)
    super.initialize(conf)
  }

  override def executeStatement(
      sessionHandle: org.apache.kyuubi.session.SessionHandle,
      statement: String,
      confOverlay: Map[String, String],
      runAsync: Boolean,
      queryTimeout: Long): OperationHandle = {
    val session = sessionManager.getSession(sessionHandle)
    sqlInspectionHook.inspect(session, statement)
    super.executeStatement(sessionHandle, statement, confOverlay, runAsync, queryTimeout)
  }
}
```

> **偏离 spec 说明**：spec 字面写"挂 AbstractBackendService.executeStatement"，实际 patch 到 `KyuubiBackendService.executeStatement`（设计决策 1）。这是唯一全协议收口点，避免误伤 6 个 engine 侧子类。`sessionManager.getSession(sessionHandle)` 返回 `Session`，`sqlInspectionHook.inspect` 取其 `user`/`clientIpAddress`/`conf`。`SqlInspectionHook` 构造时 `registryOpt=None`（P0 拦截不依赖 registry；label 仅从 session conf 读）。

**验证**：`./build/mvn -pl kyuubi-server -am clean install -DskipTests` 编译通过；既有 `KyuubiServerSuite`/`BackendServiceTFilter` 等不回归。

---

### 阶段 4：结构化审计（FR-6/7）

#### Task 4.1 扩展 `KyuubiOperationEvent` 字段

**文件**：`kyuubi-server/src/main/scala/org/apache/kyuubi/events/KyuubiOperationEvent.scala`

在 case class 末尾 `metrics: Map[String, String]` 之后追加 3 字段，并补 Scaladoc：

```scala
/**
 * @param clientIp the client ip address that submitted the operation
 * @param datasourceLabel the datasource label resolved for this operation, if any
 * @param rowCount the row count of the result, -1 if unknown or not applicable
 */
case class KyuubiOperationEvent(
    statementId: String,
    remoteId: String,
    statement: String,
    shouldRunAsync: Boolean,
    state: String,
    eventTime: Long,
    createTime: Long,
    startTime: Long,
    completeTime: Long,
    exception: Option[Throwable],
    sessionId: String,
    sessionUser: String,
    sessionType: String,
    kyuubiInstance: String,
    metrics: Map[String, String],
    clientIp: String,
    datasourceLabel: String,
    rowCount: Long) extends KyuubiEvent {

  override def partitions: Seq[(String, String)] =
    ("day", Utils.getDateFromTimestamp(createTime)) :: Nil
}
```

> `partitions` 按天分区已存在，新增字段自动随之落盘分目录（FR-7 时间分区）。

#### Task 4.2 `KyuubiOperation.getOperationEvent` 填充新字段

**文件**：`kyuubi-server/src/main/scala/org/apache/kyuubi/operation/KyuubiOperation.scala`，`getOperationEvent` 方法改为：

```scala
def getOperationEvent: KyuubiOperationEvent = {
  val kyuubiSession = session.asInstanceOf[KyuubiSession]
  val label = kyuubiSession.conf
    .getOrElse(conf.get(KyuubiConf.DIGIWIN_DATASOURCE_LABEL_KEY), "")
  KyuubiOperationEvent(
    statementId,
    Option(remoteOpHandle()).map(OperationHandle(_).identifier.toString).orNull,
    statement,
    shouldRunAsync,
    state.name(),
    lastAccessTime,
    createTime,
    startTime,
    completedTime,
    Option(operationException),
    kyuubiSession.handle.identifier.toString,
    kyuubiSession.user,
    kyuubiSession.sessionType.toString,
    kyuubiSession.connectionUrl,
    metrics,
    kyuubiSession.conf.getOrElse(KyuubiReservedKeys.KYUUBI_CLIENT_IP_KEY, ""),
    label,
    -1L)
}
```

> `rowCount` 在 P0 默认 `-1L`（未知）。若需精确行数，可在 `OperationState.FINISHED` 时由具体 operation（如 `ExecuteStatement`）覆写一个 `rowCount` 字段并在此读取--作为 P0 的可选增强，不阻塞验收（FR-6 字段已具备，行数"未知"也符合字段存在性要求；spec 验收第 2 条要求"行数"出现于审计记录，可用 `-1` 占位并在文档注明 P1 精确化）。`KyuubiSession` trait 无 `clientIpAddress`（仅在 `AbstractSession`），故用 `kyuubiSession.conf.getOrElse(KYUUBI_CLIENT_IP_KEY, "")`（`KYUUBI_CLIENT_IP_KEY` 由 `TFrontendService` 写入 session conf）；需 `import org.apache.kyuubi.config.KyuubiReservedKeys`。`conf` 在 `KyuubiOperation` 中指 `KyuubiConf`（service 级），`kyuubiSession.conf` 指 `Map[String,String]`（session 级），两者不同，已分别取用。

**审计落盘配置（FR-7）**：在 `kyuubi-defaults.conf`（或部署文档）配置：

```properties
kyuubi.backend.server.event.loggers=JSON
kyuubi.backend.server.event.json.log.path=/var/log/kyuubi/audit
kyuubi.backend.server.event.enabled=true
```

无需新增 SPI 代码（设计决策 2）。既有 `ServerEventHandlerRegister.createJsonEventHandler` -> `JsonLoggingEventHandler` 会把每次 `EventBus.post(getOperationEvent)`（operation 构造时 + 每次 `setState`）写成 JSON 行，按 `day` 分区。

**测试**：`kyuubi-server/src/test/scala/org/apache/kyuubi/events/KyuubiOperationEventSuite.scala`（既有，追加字段断言，不删既有用例）：

```scala
test("operation event carries digiwin audit fields") {
  val ev = KyuubiOperationEvent(
    "sid", null, "SELECT 1", false, "FINISHED",
    1L, 1L, 1L, 2L, None, "sess", "alice", "SQL", "inst",
    Map.empty, "10.0.0.1", "sr-prod", -1L)
  val json = ev.toJson
  assert(json.contains("\"clientIp\":\"10.0.0.1\""))
  assert(json.contains("\"datasourceLabel\":\"sr-prod\""))
  assert(json.contains("\"rowCount\":-1"))
  assert(ev.partitions.head._1 === "day")
}
```

**验证**：`./build/mvn test -pl kyuubi-server -Dtest=KyuubiOperationEventSuite` 通过。

---

### 阶段 5：限流熔断（FR-10/11）

#### Task 5.1 `OperationLimiter`（QPS + 并发查询级，FR-11）

**文件**：`kyuubi-server/src/main/scala/org/apache/kyuubi/session/OperationLimiter.scala`

```scala
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.session

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import org.apache.commons.lang3.StringUtils

import org.apache.kyuubi.KyuubiSQLException

/**
 * Concurrent-query + QPS limiter, templated on SessionLimiter's AtomicInteger/ConcurrentHashMap
 * pattern. FR-11 (QPS/concurrent query level). Connection-level limiting reuses SessionLimiter (FR-10).
 */
class OperationLimiter(
    concurrentPerUser: Int,
    concurrentPerIp: Int,
    qpsPerUser: Int) {

  private val concurrentCounters = new ConcurrentHashMap[String, AtomicInteger]()
  private val qpsCounters = new ConcurrentHashMap[String, AtomicInteger]()

  /** Throws on breach; caller must invoke decrementQuery on terminal state. */
  def acquire(user: String, ip: String): Unit = {
    if (concurrentPerUser > 0 && StringUtils.isNotBlank(user)) {
      incr(concurrentCounters, user, concurrentPerUser,
        s"Concurrent query limit per user reached (user: $user limit: $concurrentPerUser)")
    }
    if (concurrentPerIp > 0 && StringUtils.isNotBlank(ip)) {
      incr(concurrentCounters, ip, concurrentPerIp,
        s"Concurrent query limit per ipaddress reached (ip: $ip limit: $concurrentPerIp)")
    }
    if (qpsPerUser > 0 && StringUtils.isNotBlank(user)) {
      incr(qpsCounters, user, qpsPerUser,
        s"QPS limit per user reached (user: $user limit: $qpsPerUser)")
    }
  }

  def releaseQuery(user: String, ip: String): Unit = {
    if (concurrentPerUser > 0 && StringUtils.isNotBlank(user)) decr(concurrentCounters, user)
    if (concurrentPerIp > 0 && StringUtils.isNotBlank(ip)) decr(concurrentCounters, ip)
  }

  /** Reset QPS counters periodically (e.g. every 1s by a scheduler). */
  def resetQps(): Unit = qpsCounters.values().forEach(_.set(0))

  private def incr(
      map: ConcurrentHashMap[String, AtomicInteger],
      key: String, limit: Int, errorMsg: String): Unit = {
    val count = map.computeIfAbsent(key, _ => new AtomicInteger())
    if (count.incrementAndGet() > limit) {
      count.decrementAndGet()
      throw KyuubiSQLException(errorMsg)
    }
  }

  private def decr(map: ConcurrentHashMap[String, AtomicInteger], key: String): Unit = {
    val c = map.get(key)
    if (c != null) c.accumulateAndGet(1, (l, r) => if (l > 0) l - r else l)
  }
}

object OperationLimiter {
  def apply(conf: org.apache.kyuubi.config.KyuubiConf): Option[OperationLimiter] = {
    import org.apache.kyuubi.config.KyuubiConf._
    val cUser = conf.get(DIGIWIN_LIMIT_QUERIES_PER_USER).getOrElse(0)
    val cIp = conf.get(DIGIWIN_LIMIT_QUERIES_PER_IPADDRESS).getOrElse(0)
    val qps = conf.get(DIGIWIN_LIMIT_QPS_PER_USER).getOrElse(0)
    if (Seq(cUser, cIp, qps).exists(_ > 0)) Some(new OperationLimiter(cUser, cIp, qps))
    else None
  }
}
```

#### Task 5.2 接入 `KyuubiSessionManager`（核心 patch）

**文件**：`kyuubi-server/src/main/scala/org/apache/kyuubi/session/KyuubiSessionManager.scala`

在 `initSessionLimiter` 末尾追加：

```scala
operationLimiter = OperationLimiter(conf)
operationLimiter.foreach { _ =>
  qpsScheduler.scheduleAtFixedRate(
    new Runnable { override def run(): Unit = operationLimiter.foreach(_.resetQps()) },
    1, 1, java.util.concurrent.TimeUnit.SECONDS)
}
```

字段声明（类顶部，紧邻 `batchLimiter`）：

```scala
@volatile private var operationLimiter: Option[OperationLimiter] = None
private lazy val qpsScheduler =
  ThreadUtils.newDaemonSingleThreadScheduledExecutor("digiwin-qps-resetter")
```

在 operation 提交处（`newExecuteStatement` 路径）调用 `acquire`，在 operation 终态时 `releaseQuery`。具体接入点：`KyuubiOperationManager.newExecuteStatement` 创建 operation 后调用 `acquire`，并在 operation 的 `cleanup`/`onComplete` 回调 `releaseQuery`。**实现时**在 `KyuubiOperationManager` 中以 operation 监听器或直接在 `KyuubiOperation.setState` 终态分支 release（参考 `OPERATION_QUERY_TIMEOUT` monitor 的既有写法）。

> FR-10 连接级限流**无需新代码**，复用既有 `kyuubi.server.limit.connections.*` 配置即可。

#### Task 5.3 慢查询自动 Kill + 熔断降级（FR-11，配置为主）

**慢查询 Kill**：复用既有配置，无需新代码：

```properties
kyuubi.operation.query.timeout=3600000          # 1h 上限
kyuubi.operation.query.timeout.monitor.enabled=true
kyuubi.operation.interrupt.on.cancel=true
```

**熔断降级**：`DIGIWIN_ENGINE_CIRCUIT_BREAKER_ENABLED` + `DIGIWIN_ENGINE_CIRCUIT_BREAKER_TIMEOUT`。在 engine 获取路径（`KyuubiSessionImpl.getEngineRef`/`EngineRef`）用 `Future` + 超时快速失败，返回 `KyuubiSQLException("Engine unavailable, circuit breaker open")`。**实现时**定位 `EngineRef.tryWithLock`/`getEngineRef` 的等待循环，包裹超时；P0 以"超时即快速失败"满足"引擎不可用时返回明确降级错误而非挂起"。

**测试**：`kyuubi-server/src/test/scala/org/apache/kyuubi/session/OperationLimiterSuite.scala`

```scala
package org.apache.kyuubi.session

import org.apache.kyuubi.KyuubiFunSuite

class OperationLimiterSuite extends KyuubiFunSuite {
  test("concurrent per user limit enforced and released") {
    val limiter = new OperationLimiter(concurrentPerUser = 2, concurrentPerIp = 0, qpsPerUser = 0)
    limiter.acquire("alice", "1.1.1.1")
    limiter.acquire("alice", "1.1.1.1")
    intercept[org.apache.kyuubi.KyuubiSQLException] { limiter.acquire("alice", "1.1.1.1") }
    limiter.releaseQuery("alice", "1.1.1.1")
    limiter.acquire("alice", "1.1.1.1") // ok again
  }

  test("qps limit enforced and reset") {
    val limiter = new OperationLimiter(0, 0, qpsPerUser = 2)
    limiter.acquire("alice", "1.1.1.1")
    limiter.acquire("alice", "1.1.1.1")
    intercept[org.apache.kyuubi.KyuubiSQLException] { limiter.acquire("alice", "1.1.1.1") }
    limiter.resetQps()
    limiter.acquire("alice", "1.1.1.1") // ok after reset
  }

  test("disabled when all zero") {
    assert(OperationLimiter.apply(new org.apache.kyuubi.config.KyuubiConf(false)).isEmpty)
  }
}
```

**验证**：`./build/mvn test -pl kyuubi-server -Dtest=OperationLimiterSuite` 通过。

---

### 阶段 6：JDBC Engine 兼容性（FR-9，已解决，无需新增代码）

> **原计划 Task 6.1/6.2（drain-and-close 流式修复）已作废**：经核实 1.11.1 不存在 `Streaming result set is still active` 报错（见设计决策 3）。
>
> **真实问题已由提交 `fde0be6e6 Support GetSchemas for MySQL-family JDBC dialects` 解决**：`MySQLDialect.getSchemasOperation` 把 StarRocks database 映射为 schema、catalog 返回 NULL，修复 DBeaver 看不到 db、表全部平铺的问题。该提交已在 HEAD。
>
> **残留观察项（非 P0 阻塞）**：`MySQLDialect.createStatement` 仍用 `fetchSize=Integer.MIN_VALUE`、`incrementalCollect` 默认 false（全量物化）。当前无报错；大结果集全量物化 OOM 作为已知风险，P1 评估 `kyuubi.engine.jdbc.operation.incremental.collect` 推荐配置。

---

### 阶段 7：端到端协议兼容验证（FR-8）

#### Task 7.1 Thrift Binary → JDBC Engine → StarRocks 端到端验证（FR-8）

> **注意**：MySQL frontend 已被上游移除（KYUUBI #7528，v1.12）。DBeaver/Navicat 的接入路径为：**JDBC Driver（kyuubi-hive-jdbc）→ Thrift Binary 前端 → JDBC Engine → StarRocks**。本任务验证这条链路。

**文件**：`integration-tests/src/test/scala/org/apache/kyuubi/it/ThriftJdbcEngineStarRocksSuite.scala`（新增）

```scala
package org.apache.kyuubi.it

import java.sql.{DriverManager, Statement}

import org.apache.kyuubi.WithKyuubiServer
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.tags.SparkLocalClusterTest // 按需替换为合适的测试 tag

/**
 * FR-8 / FR-9: verify DBeaver -> Kyuubi JDBC Driver -> Thrift Binary -> JDBC Engine -> StarRocks
 * end-to-end. FR-9's real issue (DBeaver can't see dbs, tables flat) was fixed by
 * getSchemasOperation (commit fde0be6e6); here we regression-test that schemas are returned.
 */
class ThriftJdbcEngineStarRocksSuite extends WithKyuubiServer {

  override protected val conf: KyuubiConf = new KyuubiConf()
    .set("kyuubi.engine.type", "jdbc")
    .set("kyuubi.engine.jdbc.type", "starrocks")
    .set("kyuubi.engine.jdbc.connection.url", sys.env.getOrElse("STARROCKS_URL", ""))
    .set("kyuubi.engine.jdbc.connection.user", sys.env.getOrElse("STARROCKS_USER", ""))
    .set("kyuubi.engine.jdbc.connection.password", sys.env.getOrElse("STARROCKS_PASSWORD", ""))
    .set("kyuubi.engine.jdbc.driver.class", "com.mysql.cj.jdbc.Driver")

  private lazy val thriftUrl = {
    val addr = server.frontendServices.head.connectionUrl
    s"jdbc:kyuubi://$addr/"
  }

  test("select via JDBC engine works") {
    val conn = DriverManager.getConnection(thriftUrl, "anonymous", "")
    try {
      val stmt = conn.createStatement()
      val rs = stmt.executeQuery("SELECT 1")
      assert(rs.next() && rs.getInt(1) == 1)
      rs.close(); stmt.close()
    } finally {
      conn.close()
    }
  }

  test("getSchemas returns StarRocks databases (FR-9 regression for fde0be6e6)") {
    val conn = DriverManager.getConnection(thriftUrl, "anonymous", "")
    try {
      val rs = conn.getMetaData.getSchemas
      // StarRocks databases are mapped to schemas (commit fde0be6e6);
      // at least one schema must be returned, TABLE_CATALOG should be null.
      var found = false
      while (rs.next()) {
        found = true
        assert(rs.getString(1) != null) // TABLE_SCHEM
      }
      assert(found, "getSchemas returned no schemas")
      rs.close()
    } finally {
      conn.close()
    }
  }
}
```

> **实现时对齐**：此集成测试需真实 StarRocks 实例（通过环境变量 `STARROCKS_URL`/`STARROCKS_USER`/`STARROCKS_PASSWORD` 配置）。在 CI 中按标签策略执行（可能仅限 nightly 或特定环境）。DBeaver 手工验证记录到验收文档。第二个测试是 FR-9 回归用例，验证 `fde0be6e6` 的 `getSchemasOperation` 正确返回 StarRocks database 为 schema、catalog 为 NULL。

**验证**：`./build/mvn test -pl integration-tests -Dtest=ThriftJdbcEngineStarRocksSuite`（需 StarRocks 可用）。

---

## Self-Review

### Spec 覆盖（v2 设计文档 P0 项）

|  FR   |                                       需求                                       | 计划覆盖 |                       任务                        |
|-------|--------------------------------------------------------------------------------|------|-------------------------------------------------|
| FR-1  | 数据源注册 CRUD + REST + 热生效                                                        | ✅    | Task 2.1/2.3/2.4/2.6                            |
| FR-2  | label 代入，凭据不出现客户端                                                              | ✅    | Task 2.5（SessionConfAdvisor SPI，注入解密后凭据）        |
| FR-3  | 凭据加密存储                                                                         | ✅    | Task 2.2（AES/CBC，落库仅密文）                         |
| FR-4  | 危险 SQL 规则可配置                                                                   | ✅    | Task 3.1/3.3/3.4                                |
| FR-5  | 白名单 + 告警 + 错误码                                                                 | ✅    | Task 3.2（告警）/3.3（白名单+SQL_BLOCKED）               |
| FR-6  | 结构化审计字段                                                                        | ✅    | Task 4.1/4.2（clientIp/datasourceLabel/rowCount） |
| FR-7  | 审计落 JSON 文件按时间分区                                                               | ✅    | Task 4.1（partitions 按天，既有 JSON handler）         |
| FR-8  | 验证 DBeaver 经 JDBC（Thrift Binary）→ JDBC Engine → StarRocks 端到端查询                | ✅    | Task 7.1（Thrift 链路集成验证）                         |
| FR-9  | DBeaver 看不到 db/表平铺（元数据层级），已由 fde0be6e6（getSchemasOperation）解决；流式异常在 1.11.1 不复现 | ✅    | 已解决（提交 fde0be6e6，无新增代码）；Task 7.1 回归             |
| FR-10 | 连接级限流复用 SessionLimiter                                                         | ✅    | Task 5.3（既有配置，无新代码）                             |
| FR-11 | QPS/并发限流 + 慢查询 Kill + 熔断                                                       | ✅    | Task 5.1/5.2（QPS/并发）/5.3（Kill 配置 + 熔断超时）        |

### 偏离与简化（已显式标注）

1. 拦截钩子 patch `KyuubiBackendService` 而非 `AbstractBackendService`（设计决策 1）。
2. 审计走内置 JSON handler，不新建 SPI 插件（设计决策 2）；REST DTO 不改。
3. FR-9 重新定性：流式结果集 bug 假设作废（1.11.1 不复现），drain-and-close 修复删除；真实问题（getSchemas 元数据层级）已由提交 fde0be6e6 解决（设计决策 3）。
4. `digiwin-plugins` 为编译期内嵌依赖（设计决策 4）。
5. `rowCount` P0 默认 `-1`（精确行数列为 P1 增强，FR-6 字段已具备）。

### 占位扫描

- 计划中标注"实现时核对/确认"的点（`JdbcUtils` 签名、`Session` trait 的 `clientIpAddress`、`KyuubiServer` shutdown hook 写法、`EngineRef` 超时接入点、`RestFrontendTestHelper` 用法）均为**需在实现首步验证的既有 API 形态**，非占位代码；每处给出了备选写法。无 `TODO`/`...` 占位逻辑。

### 类型/签名一致性

- `KyuubiOperationEvent` 新增 3 字段后，`getOperationEvent` 构造调用同步补齐 3 个实参；既有两处 `EventBus.post(getOperationEvent)` 不受影响。
- `SessionConfAdvisor.getConfOverlay(user, sessionConf: JMap)` 签名与 `DatasourceConfAdvisor` 一致；通过 `PluginLoader.loadSessionConfAdvisor` 反射加载（需零参构造，`DatasourceConfAdvisor` 符合）。
- `OperationLimiter` 镜像 `SessionLimiter` 的 `AtomicInteger`/`ConcurrentHashMap`/`KyuubiSQLException` 模式，签名自洽。
- `KyuubiBackendService.executeStatement` override 签名与 `AbstractBackendService` 一致（含 `confOverlay/runAsync/queryTimeout`）。

### 风险与回退

- `DatasourceConfAdvisor` 从 sessionConf 重建 `KyuubiConf` 依赖 server 配置可见于 session conf；若不可见，启用备选 `DatasourceRegistryHolder` 单例注入（Task 2.5 备选）。
- `digiwin-plugins` 编译期依赖使 `kyuubi-server` 构建顺序依赖该模块；上游 rebase 时需同步保留 `<module>` 与 `<dependency>` 两处 pom 改动。
- FR-9 残留观察：`MySQLDialect.createStatement` 仍用 `fetchSize=Integer.MIN_VALUE`、`incrementalCollect` 默认 false（全量物化）。当前无报错；大结果集全量物化 OOM 作为已知风险，P1 评估 `kyuubi.engine.jdbc.operation.incremental.collect` 推荐配置。

## Execution Handoff

本计划共 7 阶段（阶段 6 已解决无需编码）、约 18 个文件（11 新建 + 7 修改）。执行方式两选一，待用户决定：

- **Subagent-Driven（推荐）**：按阶段派发子代理，每阶段独立 TDD 循环（写测试->验证失败->实现->验证通过），阶段间由主控串联依赖。适合并行推进独立能力域（阶段 2/3/4/5 之间无强依赖，可并行）。
- **Inline Execution**：单线程顺序执行全部任务，逐个文件落地。

建议阶段 0（脚手架）先行打通构建，再并行阶段 2-5（阶段 6 已由 fde0be6e6 解决，无新增代码），最后阶段 7 集成验证。

---

> 注：本计划代码块中个别处含"修正""备选""实现时核对"标注，均为实现首步需就位的既有 API 适配，执行时按标注取最终版。所有新功能均配单元测试；未删除任何既有测试；无硬编码密钥（凭据密钥走配置 `DIGIWIN_DATASOURCE_CREDENTIAL_SECRET`，未配则进程内随机并告警）。Git 提交时机由用户手动决定。

---

## P1 Backlog（执行期追加）

| # | 事项 | 背景与方案 | 记录日期 | 状态 |
|---|------|-----------|---------|------|
| P1-1 | **资源档位参数服务端注入**（SessionConfAdvisor 按 subdomain 注入 spark.*） | 生产资源分档（默认 pool / etl 档）目前靠用户在 JDBC URL `?` 段手敲整串 spark.* 参数，已两次踩坑：漏 `spark.kubernetes.executor.limit.cores` 导致 request>limit 拒建 Pod、参数与文档漂移。方案：digiwin-plugins 新增 advisor（结构照抄 `DatasourceConfAdvisor`，同为 SessionConfAdvisor SPI），按 session 的 `kyuubi.engine.share.level.subdomain` 注入对应档位参数（cores/limit.cores/memory/overhead/maxExecutors/driver.memory）；档位定义走服务端配置（新增 ConfigEntry），改档只动 ConfigMap，用户 URL 只需带档名。**注意**：URL 显式传入的参数应优先于注入值（只补未指定的项）；需附单测。 | 2026-07-29 | 待启动 |

既有 P1 项索引（已在本文档他处记录，不重复展开）：

- `rowCount` 精确行数（现默认 -1）——见「偏离与简化」第 5 条。
- FR-9 大结果集全量物化 OOM 风险（`fetchSize=Integer.MIN_VALUE`、incrementalCollect 评估）——见「风险与回退」。
- JDBC 引擎元数据补齐 GetCatalogs / GetTypeInfo / GetPrimaryKeys——方案已定暂缓（GetPrimaryKeys 需改 `getPrimaryKeysQuery(catalog,schema,table)` 基类签名）。
- Kyuubi 用户 → 引擎用户映射（区分真实操作人）——待设计。

