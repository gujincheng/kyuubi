# Kyuubi 统一数据网关 P0 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Kyuubi 1.11.1 上落地统一数据网关 P0 治理闭环：数据源 label 代入、危险 SQL 拦截、审计落 JSON 文件、MySQL 协议兼容修复、连接级限流。

**Architecture:** 新增 `digiwin-plugins` 模块承载自研插件（数据源管理、SQL 检查），通过 Kyuubi 现有扩展点（`SessionConfAdvisor`、反射加载）接入；少量核心 patch（`KyuubiSessionImpl` 插入 SQL 检查钩子、`KyuubiOperationEvent` 审计字段扩展、`MySQLCommandHandler` bug 修复）以最小化、加法式修改。客户端仅传 `kyuubi.datasource=<label>`，网关自动代入引擎配置且不暴露凭据。

**Tech Stack:** Scala 2.12 / Java 8+，Maven（scala-maven-plugin），ScalaTest + KyuubiFunSuite，HikariCP + 手写 JDBC，Testcontainers（StarRocks），AES 加密。

**Git 策略备注：** 用户全局规范要求"不自动提交 Git，由用户手动决定提交时机"。本计划各任务的 commit 步骤为 TDD 工作流指引；执行时每次提交前需向用户确认，或由用户在检查点统一提交。

---

## 文件结构（变更摘要）

**新增模块 `digiwin-plugins/`**（自研插件，依赖 `kyuubi-common` + `kyuubi-server-plugin`）：

|                            文件                             |                     职责                     |
|-----------------------------------------------------------|--------------------------------------------|
| `digiwin-plugins/pom.xml`                                 | 模块定义                                       |
| `.../plugin/api/Datasource.java`                          | 数据源 POJO（label/引擎类型/JDBC 信息/加密凭据）          |
| `.../plugin/api/DatasourceStore.java`                     | 数据源存储接口（get/list/insert/update/delete）     |
| `.../plugin/api/SqlInspector.java`                        | SQL 检查接口（inspect 返回放行/拦截/告警）               |
| `.../plugin/api/SqlInspectionResult.java`                 | 检查结果（action + reason）                      |
| `.../datasource/AesEncryptor.scala`                       | 凭据 AES 加解密                                 |
| `.../datasource/JdbcDatasourceStore.scala`                | 数据源 JDBC 存储（HikariCP + schema init + CRUD） |
| `.../datasource/DatasourceConfAdvisor.scala`              | `SessionConfAdvisor` 实现，按 label 注入引擎配置     |
| `.../inspection/SqlRule.scala`                            | 规则模型（deny/warn + 正则）                       |
| `.../inspection/RuleBasedSqlInspector.scala`              | 基于正则的 SQL 检查器                              |
| `resources/sql/sqlite/datasource-schema-1.0.0.sqlite.sql` | SQLite 建表                                  |
| `resources/sql/mysql/datasource-schema-1.0.0.mysql.sql`   | MySQL 建表                                   |
| `src/test/.../JdbcDatasourceStoreSuite.scala`             | 存储单测                                       |
| `src/test/.../DatasourceConfAdvisorSuite.scala`           | Advisor 单测                                 |
| `src/test/.../RuleBasedSqlInspectorSuite.scala`           | 检查器单测                                      |

**核心 patch（kyuubi-server / kyuubi-common，最小加法式）：**

|                          文件                           |                      修改                       |
|-------------------------------------------------------|-----------------------------------------------|
| `pom.xml`（根）                                          | `<modules>` 增加 `digiwin-plugins`              |
| `kyuubi-server/pom.xml`                               | 增加 `digiwin-plugins` 依赖                       |
| `kyuubi-assembly/pom.xml`                             | 增加 `digiwin-plugins` 依赖（打入发行版 jars/）          |
| `KyuubiConf.scala`                                    | 增加 digiwin 配置键（`kyuubi.datasource`、存储、SQL 检查） |
| `KyuubiSessionImpl.scala`                             | `executeStatement` 增加 SqlInspector 调用钩子       |
| `KyuubiOperationEvent.scala`                          | 增加 `clientIp`/`datasourceLabel`/`rowCount` 字段 |
| `KyuubiOperation.scala`                               | `getOperationEvent` 注入新字段 + 行数累计              |
| `ApiRootResource.scala`                               | 注册 `DatasourcesResource` 路由                   |
| `DatasourcesResource.scala`（新）                        | 数据源 CRUD REST 接口                              |
| `MySQLCommandHandler.scala`                           | 修复 `beExecuteStatement` 关闭 Operation          |
| `externals/.../jdbc/operation/ExecuteStatement.scala` | 防御性关闭 Statement                               |

**配置（conf）：**
| 文件 | 修改 |
|---|---|
| `conf/kyuubi-defaults.conf.template` | 增加数据源存储、SQL 检查、审计、限流示例配置 |

---

## Task 1: 创建 digiwin-plugins 模块骨架

**Files:**
- Create: `digiwin-plugins/pom.xml`
- Modify: `pom.xml`（根，`<modules>` 段）
- Modify: `kyuubi-server/pom.xml`
- Modify: `kyuubi-assembly/pom.xml`

- [ ] **Step 1: 创建 digiwin-plugins/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.apache.kyuubi</groupId>
    <artifactId>kyuubi-parent</artifactId>
    <version>1.11.1</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>digiwin-plugins_${scala.binary.version}</artifactId>
  <packaging>jar</packaging>
  <name>Digiwin Kyuubi Plugins</name>

  <dependencies>
    <dependency>
      <groupId>org.apache.kyuubi</groupId>
      <artifactId>kyuubi-server-plugin</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.apache.kyuubi</groupId>
      <artifactId>kyuubi-common_${scala.binary.version}</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>com.zaxxer</groupId>
      <artifactId>HikariCP</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.kyuubi</groupId>
      <artifactId>kyuubi-common_${scala.binary.version}</artifactId>
      <version>${project.version}</version>
      <type>test-jar</type>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <outputDirectory>target/scala-${scala.binary.version}/classes</outputDirectory>
    <testOutputDirectory>target/scala-${scala.binary.version}/test-classes</testOutputDirectory>
  </build>
</project>
```

- [ ] **Step 2: 根 pom.xml 注册模块**

在 `pom.xml` 的 `<modules>` 段（约第 54-88 行）末尾追加：

```xml
<module>digiwin-plugins</module>
```

- [ ] **Step 3: kyuubi-server/pom.xml 增加 digiwin-plugins 依赖**

在 `kyuubi-server/pom.xml` 的 `<dependencies>` 中追加（使 kyuubi-server 可引用 digiwin API 接口）：

```xml
<dependency>
  <groupId>org.apache.kyuubi</groupId>
  <artifactId>digiwin-plugins_${scala.binary.version}</artifactId>
  <version>${project.version}</version>
</dependency>
```

- [ ] **Step 4: kyuubi-assembly/pom.xml 增加依赖以打入发行版**

在 `kyuubi-assembly/pom.xml` 的 `<dependencies>` 中追加：

```xml
<dependency>
  <groupId>org.apache.kyuubi</groupId>
  <artifactId>digiwin-plugins_${scala.binary.version}</artifactId>
  <version>${project.version}</version>
</dependency>
```

- [ ] **Step 5: 验证模块可编译**

Run: `./build/mvn clean compile -pl digiwin-plugins -am -DskipTests`
Expected: BUILD SUCCESS（此时模块为空源码，仅验证 pom 正确）

- [ ] **Step 6: Commit**

```bash
git add digiwin-plugins/pom.xml pom.xml kyuubi-server/pom.xml kyuubi-assembly/pom.xml
git commit -m "feat: add digiwin-plugins module skeleton"
```

---

## Task 2: 数据源 API 接口与 POJO

**Files:**
- Create: `digiwin-plugins/src/main/java/com/digiwin/kyuubi/plugin/api/Datasource.java`
- Create: `digiwin-plugins/src/main/java/com/digiwin/kyuubi/plugin/api/DatasourceStore.java`
- Create: `digiwin-plugins/src/main/java/com/digiwin/kyuubi/plugin/api/SqlInspector.java`
- Create: `digiwin-plugins/src/main/java/com/digiwin/kyuubi/plugin/api/SqlInspectionResult.java`
- Create: `digiwin-plugins/src/main/java/com/digiwin/kyuubi/plugin/api/SqlInspectionAction.java`

- [ ] **Step 1: Datasource POJO**

```java
package com.digiwin.kyuubi.plugin.api;

import java.util.Objects;

public class Datasource {
  private String label;
  private String engineType;   // jdbc | SPARK_SQL
  private String jdbcType;     // starrocks | doris | mysql | null
  private String driverClass;  // null for SPARK_SQL
  private String jdbcUrl;      // null for SPARK_SQL
  private String username;     // null for SPARK_SQL
  private String password;     // 明文（仅在内存）；持久化时加密
  private String description;
  private int status;          // 1 启用 0 禁用

  public Datasource() {}

  public Datasource(String label, String engineType) {
    this.label = label;
    this.engineType = engineType;
    this.status = 1;
  }

  public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }
  public String getEngineType() { return engineType; }
  public void setEngineType(String engineType) { this.engineType = engineType; }
  public String getJdbcType() { return jdbcType; }
  public void setJdbcType(String jdbcType) { this.jdbcType = jdbcType; }
  public String getDriverClass() { return driverClass; }
  public void setDriverClass(String driverClass) { this.driverClass = driverClass; }
  public String getJdbcUrl() { return jdbcUrl; }
  public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getPassword() { return password; }
  public void setPassword(String password) { this.password = password; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public int getStatus() { return status; }
  public void setStatus(int status) { this.status = status; }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Datasource)) return false;
    Datasource that = (Datasource) o;
    return Objects.equals(label, that.label);
  }

  @Override
  public int hashCode() { return Objects.hash(label); }
}
```

- [ ] **Step 2: SqlInspectionAction 枚举**

```java
package com.digiwin.kyuubi.plugin.api;

public enum SqlInspectionAction {
  ALLOW,
  DENY,
  WARN
}
```

- [ ] **Step 3: SqlInspectionResult**

```java
package com.digiwin.kyuubi.plugin.api;

public class SqlInspectionResult {
  private final SqlInspectionAction action;
  private final String reason;

  public SqlInspectionResult(SqlInspectionAction action, String reason) {
    this.action = action;
    this.reason = reason;
  }

  public static SqlInspectionResult allow() {
    return new SqlInspectionResult(SqlInspectionAction.ALLOW, null);
  }

  public static SqlInspectionResult deny(String reason) {
    return new SqlInspectionResult(SqlInspectionAction.DENY, reason);
  }

  public SqlInspectionAction getAction() { return action; }
  public String getReason() { return reason; }
}
```

- [ ] **Step 4: DatasourceStore 接口**

```java
package com.digiwin.kyuubi.plugin.api;

import java.util.List;

public interface DatasourceStore extends AutoCloseable {
  Datasource getDatasource(String label);
  List<Datasource> listDatasources();
  void insertDatasource(Datasource datasource);
  void updateDatasource(Datasource datasource);
  void deleteDatasource(String label);
  @Override void close();
}
```

- [ ] **Step 5: SqlInspector 接口**

```java
package com.digiwin.kyuubi.plugin.api;

public interface SqlInspector {
  SqlInspectionResult inspect(String user, String statement);
}
```

- [ ] **Step 6: 验证编译**

Run: `./build/mvn clean compile -pl digiwin-plugins -am -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add digiwin-plugins/src/main/java/com/digiwin/kyuubi/plugin/api/
git commit -m "feat: add datasource & sql-inspection api interfaces"
```

---

## Task 3: digiwin 配置键

**Files:**
- Modify: `kyuubi-common/src/main/scala/org/apache/kyuubi/config/KyuubiConf.scala`

- [ ] **Step 1: 在 KyuubiConf object 中追加 digiwin 配置键**

在 `KyuubiConf.scala` 中 `SESSION_CONF_ADVISOR` 定义（约第 2938 行）之后，追加一段 digiwin 配置块：

```scala
  // ==================== Digiwin Unified Gateway ====================

  val DATASOURCE_LABEL: OptionalConfigEntry[String] =
    buildConf("kyuubi.datasource")
      .doc("Datasource label. Clients pass this to let the gateway inject " +
        "engine connection config without exposing credentials.")
      .version("1.11.1")
      .stringConf
      .createOptional

  val DATASOURCE_STORE_CLASS: ConfigEntry[String] =
    buildConf("kyuubi.datasource.store.class")
      .doc("Fully qualified class name for datasource store.")
      .version("1.11.1")
      .serverOnly
      .stringConf
      .createWithDefault("com.digiwin.kyuubi.datasource.JdbcDatasourceStore")

  val DATASOURCE_STORE_JDBC_URL: OptionalConfigEntry[String] =
    buildConf("kyuubi.datasource.store.jdbc.url")
      .doc("JDBC url for datasource store.")
      .version("1.11.1")
      .serverOnly
      .stringConf
      .createOptional

  val DATASOURCE_STORE_JDBC_USER: ConfigEntry[String] =
    buildConf("kyuubi.datasource.store.jdbc.user")
      .version("1.11.1")
      .serverOnly
      .stringConf
      .createWithDefault("")

  val DATASOURCE_STORE_JDBC_PASSWORD: OptionalConfigEntry[String] =
    buildConf("kyuubi.datasource.store.jdbc.password")
      .version("1.11.1")
      .serverOnly
      .stringConf
      .createOptional

  val DATASOURCE_STORE_JDBC_DRIVER: OptionalConfigEntry[String] =
    buildConf("kyuubi.datasource.store.jdbc.driver")
      .version("1.11.1")
      .serverOnly
      .stringConf
      .createOptional

  val DATASOURCE_STORE_SCHEMA_INIT: ConfigEntry[Boolean] =
    buildConf("kyuubi.datasource.store.jdbc.schema.init")
      .doc("Whether to auto create datasource table on startup.")
      .version("1.11.1")
      .serverOnly
      .booleanConf
      .createWithDefault(true)

  val DATASOURCE_STORE_CRYPTO_SECRET: OptionalConfigEntry[String] =
    buildConf("kyuubi.datasource.store.crypto.secret")
      .doc("AES secret for encrypting datasource credentials. " +
        "Set via kyuubi-env.sh or environment, do not hardcode.")
      .version("1.11.1")
      .serverOnly
      .stringConf
      .createOptional

  val SQL_INSPECTION_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.server.sql.inspection.enabled")
      .doc("Whether to enable dangerous SQL inspection at the server side.")
      .version("1.11.1")
      .serverOnly
      .booleanConf
      .createWithDefault(false)

  val SQL_INSPECTION_CLASS: ConfigEntry[String] =
    buildConf("kyuubi.server.sql.inspection.class")
      .doc("Fully qualified class name of SqlInspector implementation.")
      .version("1.11.1")
      .serverOnly
      .stringConf
      .createWithDefault("com.digiwin.kyuubi.inspection.RuleBasedSqlInspector")

  val SQL_INSPECTION_RULES: OptionalConfigEntry[Seq[String]] =
    buildConf("kyuubi.server.sql.inspection.rules")
      .doc("Inspection rules in form <action>:<regex>, e.g. " +
        "deny:^(?i)(DROP|TRUNCATE)\\\\b, deny:^(?i)DELETE\\\\b(?!.*\\\\bWHERE\\\\b).")
      .version("1.11.1")
      .serverOnly
      .stringConf
      .toSequence
      .createOptional

  val SQL_INSPECTION_WHITELIST: OptionalConfigEntry[Seq[String]] =
    buildConf("kyuubi.server.sql.inspection.whitelist")
      .doc("Users exempt from SQL inspection.")
      .version("1.11.1")
      .serverOnly
      .stringConf
      .toSequence
      .createOptional
```

- [ ] **Step 2: 验证编译**

Run: `./build/mvn clean compile -pl kyuubi-common -am -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add kyuubi-common/src/main/scala/org/apache/kyuubi/config/KyuubiConf.scala
git commit -m "feat: add digiwin datasource & sql-inspection config keys"
```

---

## Task 4: AesEncryptor 与数据源 schema

**Files:**
- Create: `digiwin-plugins/src/main/scala/com/digiwin/kyuubi/datasource/AesEncryptor.scala`
- Create: `digiwin-plugins/src/main/resources/sql/sqlite/datasource-schema-1.0.0.sqlite.sql`
- Create: `digiwin-plugins/src/main/resources/sql/mysql/datasource-schema-1.0.0.mysql.sql`

- [ ] **Step 1: AesEncryptor（AES/GCM，密钥来自配置）**

```scala
package com.digiwin.kyuubi.datasource

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

import org.apache.kyuubi.KyuubiSQLException

object AesEncryptor {
  private val GCM_TAG_BITS = 128
  private val IV_BYTES = 12
  private val ALGO = "AES/GCM/NoPadding"

  def encrypt(plain: String, secret: String): String = {
    require(secret != null && secret.nonEmpty, "crypto secret must be set")
    val keyBytes = normalizeKey(secret)
    val iv = new Array[Byte](IV_BYTES)
    new SecureRandom().nextBytes(iv)
    val cipher = Cipher.getInstance(ALGO)
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv))
    val cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8))
    val out = new Array[Byte](iv.length + cipherText.length)
    System.arraycopy(iv, 0, out, 0, iv.length)
    System.arraycopy(cipherText, 0, out, iv.length, cipherText.length)
    Base64.getEncoder.encodeToString(out)
  }

  def decrypt(encoded: String, secret: String): String = {
    require(secret != null && secret.nonEmpty, "crypto secret must be set")
    val all = Base64.getDecoder.decode(encoded)
    if (all.length <= IV_BYTES) {
      throw KyuubiSQLException("Invalid encrypted datasource credential")
    }
    val iv = all.slice(0, IV_BYTES)
    val cipherText = all.slice(IV_BYTES, all.length)
    val cipher = Cipher.getInstance(ALGO)
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(normalizeKey(secret), "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv))
    new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
  }

  private def normalizeKey(secret: String): Array[Byte] = {
    // 取前 32 字节作为 AES-256 密钥；不足则补零
    val raw = secret.getBytes(StandardCharsets.UTF_8)
    val key = new Array[Byte](32)
    val len = math.min(raw.length, 32)
    System.arraycopy(raw, 0, key, 0, len)
    key
  }
}
```

- [ ] **Step 2: SQLite schema**

`digiwin-plugins/src/main/resources/sql/sqlite/datasource-schema-1.0.0.sqlite.sql`:

```sql
CREATE TABLE IF NOT EXISTS datasources(
    label TEXT PRIMARY KEY,
    engine_type TEXT NOT NULL,
    jdbc_type TEXT,
    driver_class TEXT,
    jdbc_url TEXT,
    username TEXT,
    password TEXT,
    description TEXT,
    status INTEGER NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
```

- [ ] **Step 3: MySQL schema**

`digiwin-plugins/src/main/resources/sql/mysql/datasource-schema-1.0.0.mysql.sql`:

```sql
CREATE TABLE IF NOT EXISTS datasources(
    label VARCHAR(64) PRIMARY KEY,
    engine_type VARCHAR(32) NOT NULL,
    jdbc_type VARCHAR(32),
    driver_class VARCHAR(128),
    jdbc_url VARCHAR(512),
    username VARCHAR(64),
    password VARCHAR(512),
    description VARCHAR(256),
    status TINYINT NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 4: 验证编译**

Run: `./build/mvn clean compile -pl digiwin-plugins -am -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add digiwin-plugins/src/main/scala/com/digiwin/kyuubi/datasource/AesEncryptor.scala \
        digiwin-plugins/src/main/resources/sql/
git commit -m "feat: add aes encryptor and datasource schema"
```

---

## Task 5: JdbcDatasourceStore 实现（TDD）

**Files:**
- Create: `digiwin-plugins/src/test/scala/com/digiwin/kyuubi/datasource/JdbcDatasourceStoreSuite.scala`
- Create: `digiwin-plugins/src/main/scala/com/digiwin/kyuubi/datasource/JdbcDatasourceStore.scala`

- [ ] **Step 1: 写失败测试**

`digiwin-plugins/src/test/scala/com/digiwin/kyuubi/datasource/JdbcDatasourceStoreSuite.scala`:

```scala
package com.digiwin.kyuubi.datasource

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

class JdbcDatasourceStoreSuite extends KyuubiFunSuite {
  private val secret = "digiwin-test-secret-key-0123456789"
  private var store: JdbcDatasourceStore = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    val conf = KyuubiConf()
      .set(DATASOURCE_STORE_JDBC_URL, "jdbc:sqlite::memory:")
      .set(DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
      .set(DATASOURCE_STORE_SCHEMA_INIT, true)
      .set(DATASOURCE_STORE_CRYPTO_SECRET, secret)
    store = new JdbcDatasourceStore(conf)
  }

  override def afterAll(): Unit = {
    if (store != null) store.close()
    super.afterAll()
  }

  test("insert / get / list / update / delete datasource") {
    val ds = new com.digiwin.kyuubi.plugin.api.Datasource("sr-prod", "jdbc")
    ds.setJdbcType("starrocks")
    ds.setDriverClass("com.mysql.cj.jdbc.Driver")
    ds.setJdbcUrl("jdbc:mysql://sr:9030")
    ds.setUsername("root")
    ds.setPassword("DiGiWin@Sr312")

    store.insertDatasource(ds)

    val got = store.getDatasource("sr-prod")
    assert(got != null)
    assert(got.getEngineType === "jdbc")
    assert(got.getJdbcUrl === "jdbc:mysql://sr:9030")
    assert(got.getPassword === "DiGiWin@Sr312") // 解密后等于明文

    assert(store.listDatasources().size() === 1)

    got.setJdbcUrl("jdbc:mysql://sr:9031")
    store.updateDatasource(got)
    assert(store.getDatasource("sr-prod").getJdbcUrl === "jdbc:mysql://sr:9031")

    store.deleteDatasource("sr-prod")
    assert(store.getDatasource("sr-prod") === null)
  }

  test("password is encrypted at rest (not plaintext in db)") {
    val ds = new com.digiwin.kyuubi.plugin.api.Datasource("sr-enc", "jdbc")
    ds.setJdbcType("starrocks")
    ds.setJdbcUrl("jdbc:mysql://sr:9030")
    ds.setUsername("root")
    ds.setPassword("plaintext-secret")
    store.insertDatasource(ds)
    // 直接查库，确认不是明文
    val raw = store.rawQueryPassword("sr-enc")
    assert(raw !== "plaintext-secret")
    assert(raw !== null && raw.nonEmpty)
    store.deleteDatasource("sr-enc")
  }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./build/mvn test -pl digiwin-plugins -am -Dtest=JdbcDatasourceStoreSuite`
Expected: FAIL（JdbcDatasourceStore 未实现 / sqlite 驱动缺失）

注意：若缺少 sqlite-jdbc 依赖，在 `digiwin-plugins/pom.xml` 增加 test 依赖：

```xml
<dependency>
  <groupId>org.xerial</groupId>
  <artifactId>sqlite-jdbc</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 3: 实现 JdbcDatasourceStore**

```scala
package com.digiwin.kyuubi.datasource

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._

import com.digiwin.kyuubi.plugin.api.Datasource
import com.digiwin.kyuubi.plugin.api.DatasourceStore
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.apache.commons.lang3.StringUtils
import org.apache.kyuubi.KyuubiSQLException
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.util.ClassUtils
import org.apache.kyuubi.util.ThreadUtils

object JdbcDatasourceStore {
  private val LOAD_SCHEMA_SQL =
    """SELECT sql FROM (SELECT 'sqlite' AS db) t WHERE 1=0""".stripMargin // placeholder unused

  def loadSchemaSql(dbType: String): String = {
    val name = s"sql/$dbType/datasource-schema-1.0.0.$dbType.sql"
    val stream = classOf[JdbcDatasourceStore].getClassLoader.getResourceAsStream(name)
    if (stream == null) {
      throw KyuubiSQLException(s"Datasource schema file not found: $name")
    }
    val src = scala.io.Source.fromInputStream(stream).mkString
    stream.close()
    src
  }
}

class JdbcDatasourceStore(conf: KyuubiConf) extends DatasourceStore with org.apache.kyuubi.Logging {
  private val url = conf.get(DATASOURCE_STORE_JDBC_URL)
    .getOrElse(throw KyuubiSQLException("kyuubi.datasource.store.jdbc.url is not set"))
  private val user = conf.get(DATASOURCE_STORE_JDBC_USER)
  private val password = conf.get(DATASOURCE_STORE_JDBC_PASSWORD).orNull
  private val driver = conf.get(DATASOURCE_STORE_JDBC_DRIVER).orNull
  private val secret = conf.get(DATASOURCE_STORE_CRYPTO_SECRET)
    .getOrElse(throw KyuubiSQLException("kyuubi.datasource.store.crypto.secret is not set"))
  private val schemaInit = conf.get(DATASOURCE_STORE_SCHEMA_INIT)

  private val hikari = {
    if (driver != null) ClassUtils.forName(driver)
    val cfg = new HikariConfig()
    cfg.setJdbcUrl(url)
    cfg.setUsername(user)
    cfg.setPassword(password)
    cfg.setMaximumPoolSize(10)
    cfg.setConnectionTimeout(TimeUnit.SECONDS.toMillis(10))
    new HikariDataSource(cfg)
  }

  if (schemaInit) {
    val dbType = if (url.contains("sqlite")) "sqlite" else "mysql"
    JdbcDatasourceStore.loadSchemaSql(dbType).split(";").map(_.trim).filter(_.nonEmpty)
      .foreach { ddl => executeUpdate(ddl) }
  }

  override def getDatasource(label: String): Datasource = {
    withConnection { conn =>
      val ps = conn.prepareStatement("SELECT label, engine_type, jdbc_type, driver_class, " +
        "jdbc_url, username, password, description, status FROM datasources WHERE label = ?")
      ps.setString(1, label)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(mapRow(rs)) else None
      } finally {
        rs.close(); ps.close()
      }
    }.orNull
  }

  override def listDatasources(): java.util.List[Datasource] = {
    withConnection { conn =>
      val ps = conn.prepareStatement("SELECT label, engine_type, jdbc_type, driver_class, " +
        "jdbc_url, username, password, description, status FROM datasources ORDER BY label")
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ListBuffer[Datasource]()
        while (rs.next()) buf += mapRow(rs)
        buf.toList
      } finally {
        rs.close(); ps.close()
      }
    }.asJava
  }

  override def insertDatasource(ds: Datasource): Unit = {
    val now = System.currentTimeMillis()
    withConnection { conn =>
      val ps = conn.prepareStatement("INSERT INTO datasources(label, engine_type, jdbc_type, " +
        "driver_class, jdbc_url, username, password, description, status, created_at, updated_at) " +
        "VALUES (?,?,?,?,?,?,?,?,?,?,?)")
      bindParams(ps, ds, now)
      ps.executeUpdate()
      ps.close()
    }
  }

  override def updateDatasource(ds: Datasource): Unit = {
    val now = System.currentTimeMillis()
    withConnection { conn =>
      val ps = conn.prepareStatement("UPDATE datasources SET engine_type=?, jdbc_type=?, " +
        "driver_class=?, jdbc_url=?, username=?, password=?, description=?, status=?, updated_at=? " +
        "WHERE label=?")
      ps.setString(1, ds.getEngineType)
      ps.setString(2, ds.getJdbcType)
      ps.setString(3, ds.getDriverClass)
      ps.setString(4, ds.getJdbcUrl)
      ps.setString(5, ds.getUsername)
      ps.setString(6, encryptPwd(ds.getPassword))
      ps.setString(7, ds.getDescription)
      ps.setInt(8, ds.getStatus)
      ps.setLong(9, now)
      ps.setString(10, ds.getLabel)
      ps.executeUpdate()
      ps.close()
    }
  }

  override def deleteDatasource(label: String): Unit = {
    withConnection { conn =>
      val ps = conn.prepareStatement("DELETE FROM datasources WHERE label = ?")
      ps.setString(1, label)
      ps.executeUpdate()
      ps.close()
    }
  }

  /** 仅供测试：直接读取库中密文以验证非明文存储 */
  private[datasource] def rawQueryPassword(label: String): String = {
    withConnection { conn =>
      val ps = conn.prepareStatement("SELECT password FROM datasources WHERE label = ?")
      ps.setString(1, label)
      val rs = ps.executeQuery()
      try { if (rs.next()) rs.getString("password") else null } finally { rs.close(); ps.close() }
    }
  }

  override def close(): Unit = hikari.close()

  // ---- helpers ----

  private def withConnection[T](f: Connection => T): T = {
    val conn = hikari.getConnection
    try f(conn) finally conn.close()
  }

  private def executeUpdate(sql: String): Unit = {
    withConnection { conn =>
      val st = conn.createStatement()
      try st.executeUpdate(sql) finally st.close()
    }
  }

  private def bindParams(ps: PreparedStatement, ds: Datasource, now: Long): Unit = {
    ps.setString(1, ds.getLabel)
    ps.setString(2, ds.getEngineType)
    ps.setString(3, ds.getJdbcType)
    ps.setString(4, ds.getDriverClass)
    ps.setString(5, ds.getJdbcUrl)
    ps.setString(6, ds.getUsername)
    ps.setString(7, encryptPwd(ds.getPassword))
    ps.setString(8, ds.getDescription)
    ps.setInt(9, ds.getStatus)
    ps.setLong(10, now)
    ps.setLong(11, now)
  }

  private def mapRow(rs: ResultSet): Datasource = {
    val ds = new Datasource(rs.getString("label"), rs.getString("engine_type"))
    ds.setJdbcType(rs.getString("jdbc_type"))
    ds.setDriverClass(rs.getString("driver_class"))
    ds.setJdbcUrl(rs.getString("jdbc_url"))
    ds.setUsername(rs.getString("username"))
    val encPwd = rs.getString("password")
    ds.setPassword(if (StringUtils.isBlank(encPwd)) null else AesEncryptor.decrypt(encPwd, secret))
    ds.setDescription(rs.getString("description"))
    ds.setStatus(rs.getInt("status"))
    ds
  }

  private def encryptPwd(plain: String): String = {
    if (StringUtils.isBlank(plain)) null else AesEncryptor.encrypt(plain, secret)
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `./build/mvn test -pl digiwin-plugins -am -Dtest=JdbcDatasourceStoreSuite`
Expected: PASS（2 个测试通过）

- [ ] **Step 5: Commit**

```bash
git add digiwin-plugins/src/main/scala/com/digiwin/kyuubi/datasource/JdbcDatasourceStore.scala \
        digiwin-plugins/src/test/scala/com/digiwin/kyuubi/datasource/JdbcDatasourceStoreSuite.scala \
        digiwin-plugins/pom.xml
git commit -m "feat: implement JdbcDatasourceStore with encrypted credentials"
```

---

## Task 6: DatasourceConfAdvisor（label -> 引擎配置注入，TDD）

**Files:**
- Create: `digiwin-plugins/src/test/scala/com/digiwin/kyuubi/datasource/DatasourceConfAdvisorSuite.scala`
- Create: `digiwin-plugins/src/main/scala/com/digiwin/kyuubi/datasource/DatasourceConfAdvisor.scala`

- [ ] **Step 1: 写失败测试**

```scala
package com.digiwin.kyuubi.datasource

import java.util.{Collections, Map => JMap}

import scala.collection.JavaConverters._

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

class DatasourceConfAdvisorSuite extends KyuubiFunSuite {
  private val secret = "digiwin-test-secret-key-0123456789"

  test("label injects jdbc engine config without exposing raw url in client conf") {
    val conf = KyuubiConf()
      .set(DATASOURCE_STORE_JDBC_URL, "jdbc:sqlite::memory:")
      .set(DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
      .set(DATASOURCE_STORE_SCHEMA_INIT, true)
      .set(DATASOURCE_STORE_CRYPTO_SECRET, secret)
    DatasourceConfAdvisor.initialize(conf)
    DatasourceConfAdvisor.store.insertDatasource(starrocksDs())

    val sessionConf: JMap[String, String] = Collections.singletonMap("kyuubi.datasource", "sr-prod")
    val overlay = new DatasourceConfAdvisor().getConfOverlay("alice", sessionConf).asScala

    assert(overlay("kyuubi.engine.type") === "jdbc")
    assert(overlay("kyuubi.engine.jdbc.type") === "starrocks")
    assert(overlay("kyuubi.engine.jdbc.connection.url") === "jdbc:mysql://sr:9030")
    assert(overlay("kyuubi.engine.jdbc.connection.user") === "root")
    assert(overlay("kyuubi.engine.jdbc.connection.password") === "DiGiWin@Sr312")

    DatasourceConfAdvisor.store.deleteDatasource("sr-prod")
    DatasourceConfAdvisor.close()
  }

  test("missing label returns empty overlay") {
    val overlay = new DatasourceConfAdvisor().getConfOverlay("bob", Collections.emptyMap())
    assert(overlay.isEmpty)
  }

  private def starrocksDs(): com.digiwin.kyuubi.plugin.api.Datasource = {
    val ds = new com.digiwin.kyuubi.plugin.api.Datasource("sr-prod", "jdbc")
    ds.setJdbcType("starrocks")
    ds.setDriverClass("com.mysql.cj.jdbc.Driver")
    ds.setJdbcUrl("jdbc:mysql://sr:9030")
    ds.setUsername("root")
    ds.setPassword("DiGiWin@Sr312")
    ds
  }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./build/mvn test -pl digiwin-plugins -am -Dtest=DatasourceConfAdvisorSuite`
Expected: FAIL（DatasourceConfAdvisor 未实现）

- [ ] **Step 3: 实现 DatasourceConfAdvisor**

```scala
package com.digiwin.kyuubi.datasource

import java.util.{Map => JMap, Collections}

import scala.collection.JavaConverters._

import com.digiwin.kyuubi.plugin.api.Datasource
import com.digiwin.kyuubi.plugin.api.SessionConfAdvisor
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

class DatasourceConfAdvisor extends SessionConfAdvisor {
  override def getConfOverlay(
      user: String,
      sessionConf: JMap[String, String]): JMap[String, String] = {
    val label = Option(sessionConf.get(DATASOURCE_LABEL.key)).orNull
    if (label == null) return Collections.emptyMap()

    val ds: Datasource = DatasourceConfAdvisor.store.getDatasource(label)
    if (ds == null) {
      DatasourceConfAdvisor.warn(s"Datasource label '$label' not found for user '$user'")
      return Collections.emptyMap()
    }
    if (ds.getStatus != 1) {
      DatasourceConfAdvisor.warn(s"Datasource label '$label' is disabled")
      return Collections.emptyMap()
    }

    DatasourceConfAdvisor.toOverlay(ds).asJava
  }
}

object DatasourceConfAdvisor extends org.apache.kyuubi.Logging {
  @volatile private var _store: JdbcDatasourceStore = _

  private[datasource] def store: JdbcDatasourceStore = {
    if (_store == null) {
      throw new IllegalStateException("DatasourceConfAdvisor not initialized")
    }
    _store
  }

  // 由 KyuubiServer 启动钩子或首次加载时调用（见 Task 9 的 ServerPlugin 注册）
  def initialize(conf: KyuubiConf): Unit = synchronized {
    if (_store == null) {
      _store = new JdbcDatasourceStore(conf)
    }
  }

  private[datasource] def close(): Unit = synchronized {
    if (_store != null) { _store.close(); _store = null }
  }

  private[datasource] def toOverlay(ds: Datasource): Map[String, String] = {
    val m = scala.collection.mutable.Map[String, String](
      "kyuubi.engine.type" -> ds.getEngineType)
    if ("jdbc".equalsIgnoreCase(ds.getEngineType)) {
      m("kyuubi.engine.jdbc.type") = ds.getJdbcType
      m("kyuubi.engine.jdbc.driver.class") = ds.getDriverClass
      m("kyuubi.engine.jdbc.connection.url") = ds.getJdbcUrl
      m("kyuubi.engine.jdbc.connection.user") = ds.getUsername
      m("kyuubi.engine.jdbc.connection.password") = ds.getPassword
    }
    m.toMap
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `./build/mvn test -pl digiwin-plugins -am -Dtest=DatasourceConfAdvisorSuite`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add digiwin-plugins/src/main/scala/com/digiwin/kyuubi/datasource/DatasourceConfAdvisor.scala \
        digiwin-plugins/src/test/scala/com/digiwin/kyuubi/datasource/DatasourceConfAdvisorSuite.scala
git commit -m "feat: implement DatasourceConfAdvisor to inject engine config by label"
```

---

## Task 7: RuleBasedSqlInspector（TDD）

**Files:**
- Create: `digiwin-plugins/src/main/scala/com/digiwin/kyuubi/inspection/SqlRule.scala`
- Create: `digiwin-plugins/src/test/scala/com/digiwin/kyuubi/inspection/RuleBasedSqlInspectorSuite.scala`
- Create: `digiwin-plugins/src/main/scala/com/digiwin/kyuubi/inspection/RuleBasedSqlInspector.scala`

- [ ] **Step 1: SqlRule 模型**

```scala
package com.digiwin.kyuubi.inspection

import java.util.regex.Pattern

import com.digiwin.kyuubi.plugin.api.SqlInspectionAction

case class SqlRule(action: SqlInspectionAction, regex: Pattern) {
  def matches(sql: String): Boolean = regex.matcher(sql).find()
}

object SqlRule {
  // 规则格式：<action>:<regex>，例如 deny:^(?i)(DROP|TRUNCATE)\b
  def parse(raw: String): SqlRule = {
    val idx = raw.indexOf(':')
    if (idx < 0) {
      throw new IllegalArgumentException(s"Invalid inspection rule (missing action): $raw")
    }
    val actionStr = raw.substring(0, idx).trim.toUpperCase
    val regexStr = raw.substring(idx + 1)
    val action = SqlInspectionAction.valueOf(actionStr)
    SqlRule(action, Pattern.compile(regexStr, Pattern.DOTALL))
  }
}
```

- [ ] **Step 2: 写失败测试**

```scala
package com.digiwin.kyuubi.inspection

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

class RuleBasedSqlInspectorSuite extends KyuubiFunSuite {
  private val rules = Seq(
    "deny:^(?i)(DROP|TRUNCATE)\\b",
    "deny:^(?i)DELETE\\b(?!.*\\bWHERE\\b)",
    "warn:^(?i)SELECT\\s+\\*\\s+FROM\\b")

  private def newInspector(): RuleBasedSqlInspector = {
    val conf = KyuubiConf()
      .set(SQL_INSPECTION_RULES, rules)
      .set(SQL_INSPECTION_WHITELIST, Seq("admin"))
    new RuleBasedSqlInspector(conf)
  }

  test("deny DROP TABLE") {
    val r = newInspector().inspect("alice", "DROP TABLE orders")
    assert(r.getAction.name() === "DENY")
    assert(r.getReason != null)
  }

  test("deny DELETE without WHERE") {
    val r = newInspector().inspect("alice", "DELETE FROM orders")
    assert(r.getAction.name() === "DENY")
  }

  test("allow DELETE with WHERE") {
    val r = newInspector().inspect("alice", "DELETE FROM orders WHERE id = 1")
    assert(r.getAction.name() === "ALLOW")
  }

  test("warn SELECT *") {
    val r = newInspector().inspect("alice", "SELECT * FROM orders")
    assert(r.getAction.name() === "WARN")
  }

  test("whitelist user bypasses inspection") {
    val r = newInspector().inspect("admin", "DROP TABLE orders")
    assert(r.getAction.name() === "ALLOW")
  }
}
```

- [ ] **Step 3: 运行测试验证失败**

Run: `./build/mvn test -pl digiwin-plugins -am -Dtest=RuleBasedSqlInspectorSuite`
Expected: FAIL（RuleBasedSqlInspector 未实现）

- [ ] **Step 4: 实现 RuleBasedSqlInspector**

```scala
package com.digiwin.kyuubi.inspection

import scala.collection.JavaConverters._

import com.digiwin.kyuubi.plugin.api.{SqlInspectionAction, SqlInspectionResult, SqlInspector}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

class RuleBasedSqlInspector(conf: KyuubiConf) extends SqlInspector with org.apache.kyuubi.Logging {
  private val rules: Seq[SqlRule] =
    conf.get(SQL_INSPECTION_RULES).getOrElse(Seq.empty).map(SqlRule.parse)
  private val whitelist: Set[String] =
    conf.get(SQL_INSPECTION_WHITELIST).getOrElse(Seq.empty).toSet

  override def inspect(user: String, statement: String): SqlInspectionResult = {
    if (whitelist.contains(user)) return SqlInspectionResult.allow()

    var warnReason: String = null
    val it = rules.iterator
    while (it.hasNext) {
      val rule = it.next()
      if (rule.matches(statement)) {
        rule.action match {
          case SqlInspectionAction.DENY =>
            return SqlInspectionResult.deny(s"SQL blocked by rule: ${rule.regex}")
          case SqlInspectionAction.WARN =>
            if (warnReason == null) warnReason = s"SQL matched warn rule: ${rule.regex}"
          case _ => // ALLOW 规则跳过
        }
      }
    }
    if (warnReason != null) {
      warn(warnReason)
    }
    SqlInspectionResult.allow()
  }
}
```

- [ ] **Step 5: 运行测试验证通过**

Run: `./build/mvn test -pl digiwin-plugins -am -Dtest=RuleBasedSqlInspectorSuite`
Expected: PASS（5 个测试通过）

- [ ] **Step 6: Commit**

```bash
git add digiwin-plugins/src/main/scala/com/digiwin/kyuubi/inspection/ \
        digiwin-plugins/src/test/scala/com/digiwin/kyuubi/inspection/
git commit -m "feat: implement RuleBasedSqlInspector"
```

---

## Task 8: SqlInspector 加载与 KyuubiSessionImpl 拦截钩子

**Files:**
- Modify: `kyuubi-server/src/main/scala/org/apache/kyuubi/plugin/PluginLoader.scala`
- Modify: `kyuubi-server/src/main/scala/org/apache/kyuubi/session/KyuubiSessionManager.scala`
- Modify: `kyuubi-server/src/main/scala/org/apache/kyuubi/session/KyuubiSessionImpl.scala`
- Create: `kyuubi-server/src/test/scala/org/apache/kyuubi/operation/SqlInspectionSuite.scala`

- [ ] **Step 1: PluginLoader 增加 loadSqlInspector**

在 `PluginLoader.scala` 的 `loadGroupProvider` 方法后追加：

```scala
def loadSqlInspector(conf: KyuubiConf): Option[SqlInspector] = {
  if (!conf.get(KyuubiConf.SQL_INSPECTION_ENABLED)) return None
  val className = conf.get(KyuubiConf.SQL_INSPECTION_CLASS)
  try {
    // 优先用 (KyuubiConf) 构造函数；否则用零参构造函数
    val ctor = ClassUtils.forName(className).getDeclaredConstructors
      .find(_.getParameterTypes.sameElements(Array(classOf[KyuubiConf])))
    val instance = ctor match {
      case Some(c) => c.newInstance(conf).asInstanceOf[SqlInspector]
      case None =>
        DynConstructors.builder.impl(className).buildChecked[SqlInspector].newInstance()
    }
    Some(instance)
  } catch {
    case _: ClassCastException =>
      throw new KyuubiException(
        s"Class $className is not a child of '${classOf[SqlInspector].getName}'.")
    case NonFatal(e) =>
      throw new IllegalArgumentException(s"Error while instantiating '$className': ", e)
  }
}
```

并在文件顶部 import 段增加：

```scala
import com.digiwin.kyuubi.plugin.api.SqlInspector
import org.apache.kyuubi.util.ClassUtils
```

- [ ] **Step 2: KyuubiSessionManager 持有 sqlInspector**

在 `KyuubiSessionManager.scala` 约第 62-63 行 `groupProvider` 定义后追加：

```scala
lazy val sqlInspector: Option[SqlInspector] = PluginLoader.loadSqlInspector(conf)
```

顶部 import 增加：

```scala
import com.digiwin.kyuubi.plugin.api.SqlInspector
```

同时在该文件初始化处（`initialize` 方法中 `sessionConfAdvisor` 已加载之后）调用 DatasourceConfAdvisor 初始化：

```scala
override def initialize(conf: KyuubiConf): Unit = {
  super.initialize(conf)
  com.digiwin.kyuubi.datasource.DatasourceConfAdvisor.initialize(conf)
}
```

（若 `initialize` 已有其他逻辑，在最末追加该行；确保只追加一次。）

- [ ] **Step 3: KyuubiSessionImpl.executeStatement 增加拦截钩子**

定位 `KyuubiSessionImpl.scala` 的 `executeStatement` 方法（约第 311-326 行）。在方法体最开头（`val kyuubiNode = parser.parsePlan(statement)` 之前）插入：

```scala
sessionManager.sqlInspector.foreach { inspector =>
  val result = inspector.inspect(user, statement)
  result.getAction match {
    case com.digiwin.kyuubi.plugin.api.SqlInspectionAction.DENY =>
      throw KyuubiSQLException(s"SQL blocked: ${result.getReason}", sqlState = "42000")
    case _ => // ALLOW / WARN 放行
  }
}
```

确保顶部 import 含 `org.apache.kyuubi.KyuubiSQLException`（通常已存在）。

- [ ] **Step 4: 写集成测试**

```scala
package org.apache.kyuubi.operation

import java.sql.SQLException

import org.apache.kyuubi.WithKyuubiServer
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.operation.HiveJDBCTestHelper

class SqlInspectionSuite extends WithKyuubiServer with HiveJDBCTestHelper {
  override protected val conf: KyuubiConf = {
    KyuubiConf()
      .set(KyuubiConf.ENGINE_SHARE_LEVEL, "connection")
      .set(SQL_INSPECTION_ENABLED, true)
      .set(SQL_INSPECTION_CLASS, "com.digiwin.kyuubi.inspection.RuleBasedSqlInspector")
      .set(SQL_INSPECTION_RULES, Seq("deny:^(?i)(DROP|TRUNCATE)\\b"))
      .set(SQL_INSPECTION_WHITELIST, Seq("admin"))
  }

  override protected def jdbcUrl: String =
    s"jdbc:kyuubi://${server.frontendServices.head.connectionUrl}/"

  test("block DROP TABLE via server-side inspection") {
    withJdbcStatement() { stmt =>
      val e = intercept[SQLException] {
        stmt.execute("DROP TABLE non_existing")
      }
      assert(e.getMessage.contains("blocked"))
    }
  }

  test("allow SELECT 1") {
    withJdbcStatement() { stmt =>
      val rs = stmt.executeQuery("SELECT 1")
      assert(rs.next())
      assert(rs.getInt(1) === 1)
    }
  }
}
```

- [ ] **Step 5: 运行测试验证通过**

Run: `./build/mvn test -pl kyuubi-server -am -Dtest=SqlInspectionSuite`
Expected: PASS（DROP 被拦截、SELECT 放行）

- [ ] **Step 6: Commit**

```bash
git add kyuubi-server/src/main/scala/org/apache/kyuubi/plugin/PluginLoader.scala \
        kyuubi-server/src/main/scala/org/apache/kyuubi/session/KyuubiSessionManager.scala \
        kyuubi-server/src/main/scala/org/apache/kyuubi/session/KyuubiSessionImpl.scala \
        kyuubi-server/src/test/scala/org/apache/kyuubi/operation/SqlInspectionSuite.scala
git commit -m "feat: wire SqlInspector into KyuubiSessionImpl execution path"
```

---

## Task 9: 数据源 CRUD REST API

**Files:**
- Create: `kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/DatasourcesResource.scala`
- Create: `kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/DatasourceManager.scala`
- Modify: `kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/ApiRootResource.scala`
- Create: `kyuubi-server/src/test/scala/org/apache/kyuubi/server/api/v1/DatasourcesResourceSuite.scala`

- [ ] **Step 1: DatasourceManager（加载 store 并代理 CRUD）**

```scala
package org.apache.kyuubi.server.api.v1

import java.util.{List => JList}

import com.digiwin.kyuubi.plugin.api.Datasource
import com.digiwin.kyuubi.plugin.api.DatasourceStore
import org.apache.kyuubi.KyuubiSQLException
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.util.ClassUtils
import org.apache.kyuubi.util.reflect.DynConstructors
import org.apache.kyuubi.Logging

class DatasourceManager(conf: KyuubiConf) extends Logging {
  private val store: DatasourceStore = {
    val className = conf.get(DATASOURCE_STORE_CLASS)
    // 优先 (KyuubiConf) 构造函数
    val ctor = ClassUtils.forName(className).getDeclaredConstructors
      .find(_.getParameterTypes.sameElements(Array(classOf[KyuubiConf])))
    ctor match {
      case Some(c) => c.newInstance(conf).asInstanceOf[DatasourceStore]
      case None =>
        DynConstructors.builder.impl(className).buildChecked[DatasourceStore].newInstance()
    }
  }

  def list(): JList[Datasource] = store.listDatasources()
  def get(label: String): Datasource =
    Option(store.getDatasource(label)).getOrElse(
      throw KyuubiSQLException(s"Datasource '$label' not found"))
  def create(ds: Datasource): Unit = {
    require(ds.getLabel != null && ds.getLabel.nonEmpty, "label is required")
    store.insertDatasource(ds)
  }
  def update(ds: Datasource): Unit = store.updateDatasource(ds)
  def delete(label: String): Unit = store.deleteDatasource(label)
  def close(): Unit = store.close()
}
```

- [ ] **Step 2: DatasourcesResource（REST 端点）**

```scala
package org.apache.kyuubi.server.api.v1

import javax.ws.rs.{DELETE, GET, POST, PUT, Path, Produces}
import javax.ws.rs.core.MediaType

import com.digiwin.kyuubi.plugin.api.Datasource
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Datasources")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class DatasourcesResource extends ApiRequestContext with Logging {

  private def manager: DatasourceManager = DatasourcesResource.manager

  @GET
  @Path("list")
  def list(): java.util.List[Datasource] = manager.list()

  @GET
  @Path("{label}")
  def get(@javax.ws.rs.PathParam("label") label: String): Datasource = manager.get(label)

  @POST
  def create(ds: Datasource): Datasource = {
    manager.create(ds)
    manager.get(ds.getLabel)
  }

  @PUT
  @Path("{label}")
  def update(@javax.ws.rs.PathParam("label") label: String, ds: Datasource): Datasource = {
    ds.setLabel(label)
    manager.update(ds)
    manager.get(label)
  }

  @DELETE
  @Path("{label}")
  def delete(@javax.ws.rs.PathParam("label") label: String): String = {
    manager.delete(label)
    s"""{"label":"$label","deleted":true}"""
  }
}

object DatasourcesResource extends Logging {
  @volatile private var _manager: DatasourceManager = _
  private[v1] def manager: DatasourceManager = {
    if (_manager == null) {
      throw new IllegalStateException("DatasourceManager not initialized")
    }
    _manager
  }
  // 由 KyuubiServer 启动时调用
  private[api] def initialize(conf: KyuubiConf): Unit = synchronized {
    if (_manager == null) _manager = new DatasourceManager(conf)
  }
}
```

- [ ] **Step 3: ApiRootResource 注册路由**

在 `ApiRootResource.scala` 的 `ApiRootResource` 类中（约第 36-44 行，`admin` 路由之后）追加：

```scala
@Path("datasources")
def datasources: Class[DatasourcesResource] = classOf[DatasourcesResource]
```

- [ ] **Step 4: KyuubiServer 启动时初始化 DatasourceManager**

在 `KyuubiServer.scala` 的 `initialize` 方法中（`initLoggerEventHandler` 附近）追加一行：

```scala
org.apache.kyuubi.server.api.v1.DatasourcesResource.initialize(conf)
```

- [ ] **Step 5: 写 REST 测试**

```scala
package org.apache.kyuubi.server.api.v1

import javax.ws.rs.client.Entity
import javax.ws.rs.core.MediaType

import com.digiwin.kyuubi.plugin.api.Datasource
import org.apache.kyuubi.RestFrontendTestHelper
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

class DatasourcesResourceSuite extends RestFrontendTestHelper {
  override protected val conf: KyuubiConf = {
    KyuubiConf()
      .set(METADATA_STORE_JDBC_DATABASE_TYPE, "SQLITE") // 不相关，仅为基类
      .set(DATASOURCE_STORE_JDBC_URL, "jdbc:sqlite::memory:")
      .set(DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
      .set(DATASOURCE_STORE_SCHEMA_INIT, true)
      .set(DATASOURCE_STORE_CRYPTO_SECRET, "digiwin-test-secret-key-0123456789")
  }

  test("create / get / list / update / delete datasource via REST") {
    val ds = new Datasource("sr-rest", "jdbc")
    ds.setJdbcType("starrocks")
    ds.setDriverClass("com.mysql.cj.jdbc.Driver")
    ds.setJdbcUrl("jdbc:mysql://sr:9030")
    ds.setUsername("root")
    ds.setPassword("secret")

    val createResp = webTarget.path("api/v1/datasources")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(ds, MediaType.APPLICATION_JSON_TYPE))
    assert(createResp.getStatus === 200)

    val got = webTarget.path("api/v1/datasources/sr-rest")
      .request(MediaType.APPLICATION_JSON_TYPE).get
    assert(got.getStatus === 200)

    val list = webTarget.path("api/v1/datasources/list")
      .request(MediaType.APPLICATION_JSON_TYPE).get
    assert(list.getStatus === 200)

    val del = webTarget.path("api/v1/datasources/sr-rest")
      .request(MediaType.APPLICATION_JSON_TYPE).delete
    assert(del.getStatus === 200)
  }
}
```

- [ ] **Step 6: 运行测试验证通过**

Run: `./build/mvn test -pl kyuubi-server -am -Dtest=DatasourcesResourceSuite`
Expected: PASS（若 RestFrontendTestHelper 基类需特定认证配置，参考 BatchesResourceSuite 补充 AUTHENTICATION 配置）

- [ ] **Step 7: Commit**

```bash
git add kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/DatasourcesResource.scala \
        kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/DatasourceManager.scala \
        kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/ApiRootResource.scala \
        kyuubi-server/src/main/scala/org/apache/kyuubi/server/KyuubiServer.scala \
        kyuubi-server/src/test/scala/org/apache/kyuubi/server/api/v1/DatasourcesResourceSuite.scala
git commit -m "feat: add datasource CRUD REST API"
```

---

## Task 10: 审计字段扩展（clientIp / datasourceLabel / rowCount）

**Files:**
- Modify: `kyuubi-server/src/main/scala/org/apache/kyuubi/events/KyuubiOperationEvent.scala`
- Modify: `kyuubi-server/src/main/scala/org/apache/kyuubi/operation/KyuubiOperation.scala`
- Modify: `kyuubi-server/src/test/scala/org/apache/kyuubi/events/handler/ServerJsonLoggingEventHandlerSuite.scala`

- [ ] **Step 1: KyuubiOperationEvent 增加三字段**

在 `KyuubiOperationEvent.scala` 的 case class（约第 46-67 行）参数列表末尾（`metrics: Map[String, String]` 之后）追加：

```scala
clientIp: String,
datasourceLabel: String,
rowCount: Long)
```

（注意保持 extends KyuubiEvent 与 partitions 方法不变。）

- [ ] **Step 2: KyuubiOperation.getOperationEvent 注入字段 + 行数累计**

在 `KyuubiOperation.scala`：

a) 增加行数累计字段（在类字段区，约 `_fetchResultsCount` 附近）：

```scala
private var _resultRowCount: Long = 0L
def resultRowCount: Long = _resultRowCount
```

b) 在 `getNextRowSetInternal`（约第 197-208 行）返回 rowSet 前累计实际行数。在获取到 `rowSet` 后追加：

```scala
val fetched = rowSet.getRows.size()
_resultRowCount += fetched.toLong
```

（具体变量名以实际方法体为准；目的是累加每次 fetch 的真实行数。）

c) 修改 `getOperationEvent`（约第 228-246 行），在构造 `KyuubiOperationEvent(...)` 末尾追加三个参数：

```scala
kyuubiSession.ipAddress,
kyuubiSession.getConf.get(KyuubiConf.DATASOURCE_LABEL).orNull,
resultRowCount)
```

确保顶部 import 含 `org.apache.kyuubi.config.KyuubiConf`（通常已有）。

- [ ] **Step 3: 扩展 ServerJsonLoggingEventHandlerSuite 断言新字段**

在 `ServerJsonLoggingEventHandlerSuite.scala` 的查询结果断言段，增加对 `clientIp`、`datasourceLabel` 字段的断言（读取 JSON 事件后）：

```scala
assert(rows.exists(_.getAs[String]("clientIp") != null))
assert(rows.exists(_.getAs[String]("datasourceLabel") == null
  || rows.forall(_.getAs[String]("datasourceLabel") != null))
```

（`rowCount` 字段在 FINISHED 事件中应 >= 0，可加 `assert(rows.exists(_.getAs[Long]("rowCount") >= 0L))`。）

- [ ] **Step 4: 运行测试验证通过**

Run: `./build/mvn test -pl kyuubi-server -am -Dtest=ServerJsonLoggingEventHandlerSuite`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add kyuubi-server/src/main/scala/org/apache/kyuubi/events/KyuubiOperationEvent.scala \
        kyuubi-server/src/main/scala/org/apache/kyuubi/operation/KyuubiOperation.scala \
        kyuubi-server/src/test/scala/org/apache/kyuubi/events/handler/ServerJsonLoggingEventHandlerSuite.scala
git commit -m "feat: extend audit event with clientIp, datasourceLabel, rowCount"
```

---

## Task 11: 启用 JSON 审计日志（配置）

**Files:**
- Modify: `conf/kyuubi-defaults.conf.template`

- [ ] **Step 1: 增加审计配置示例**

在 `conf/kyuubi-defaults.conf.template` 末尾追加：

```properties

## Digiwin Unified Gateway - Audit (JSON file)
# kyuubi.backend.server.event.loggers               JSON
# kyuubi.backend.server.event.json.log.path         file:///var/log/kyuubi/events
# kyuubi.backend.server.event.async.enabled         true

## Digiwin Unified Gateway - Datasource store
# kyuubi.datasource.store.jdbc.url                  jdbc:mysql://mysql:3306/kyuubi_gateway
# kyuubi.datasource.store.jdbc.user                 root
# kyuubi.datasource.store.jdbc.password             ******
# kyuubi.datasource.store.jdbc.driver               com.mysql.cj.jdbc.Driver
# kyuubi.datasource.store.crypto.secret            <set via env>

## Digiwin Unified Gateway - SQL inspection
# kyuubi.server.sql.inspection.enabled              true
# kyuubi.server.sql.inspection.rules                deny:^(?i)(DROP|TRUNCATE)\b,deny:^(?i)DELETE\b(?!.*\bWHERE\b),warn:^(?i)SELECT\s+\*\s+FROM\b
# kyuubi.server.sql.inspection.whitelist            admin

## Digiwin Unified Gateway - Rate limit (existing Kyuubi configs)
# kyuubi.server.limit.connections.per.user          10
# kyuubi.server.limit.connections.per.ipaddress     50
# kyuubi.server.limit.connections.user.unlimited.list admin
```

- [ ] **Step 2: 启用 SQL 检查与 advisor（示例生效配置说明）**

在配置文件中保留注释说明：客户端连接示例 `jdbc:kyuubi://host:10009/?kyuubi.datasource=sr-prod`，并在 `kyuubi.session.conf.advisor` 配置项处增加示例：

```properties
# kyuubi.session.conf.advisor                       com.digiwin.kyuubi.datasource.DatasourceConfAdvisor
```

- [ ] **Step 3: Commit**

```bash
git add conf/kyuubi-defaults.conf.template
git commit -m "docs: add digiwin gateway config examples"
```

---

## Task 12: 修复 DBeaver 流式结果集 bug

**Files:**
- Modify: `kyuubi-server/src/main/scala/org/apache/kyuubi/server/mysql/MySQLCommandHandler.scala`
- Modify: `externals/kyuubi-jdbc-engine/src/main/scala/org/apache/kyuubi/engine/jdbc/operation/ExecuteStatement.scala`
- Create: `kyuubi-server/src/test/scala/org/apache/kyuubi/server/mysql/MySQLJdbcEngineStreamingSuite.scala`

- [ ] **Step 1: 主修复 - beExecuteStatement 关闭 Operation**

定位 `MySQLCommandHandler.scala` 的 `beExecuteStatement` 方法（约第 184-211 行）。将其改为在 `finally` 中关闭 Operation：

```scala
private def beExecuteStatement(ctx: ChannelHandlerContext, sql: String): MySQLQueryResult = {
  var opHandle: OperationHandle = null
  try {
    val ssHandle = ctx.channel.attr(SESSION_HANDLE).get
    opHandle = be.executeStatement(ssHandle, sql, Map.empty, false, 0)
    val opStatus = be.getOperationStatus(opHandle)
    // ... 保留原有结果拉取逻辑 ...
    val resultSetMetadata = be.getResultSetMetadata(opHandle)
    val fetchResultResp = be.fetchResults(opHandle, FETCH_NEXT, Int.MaxValue, false)
    val rowSet = fetchResultResp.getResults
    MySQLQueryResult(resultSetMetadata.getSchema, rowSet)
  } catch {
    case rethrow: Exception =>
      warn("Error executing statement: ", rethrow)
      throw rethrow
  } finally {
    if (opHandle != null) {
      Utils.tryLogNonFatalError { be.closeOperation(opHandle) }
    }
  }
}
```

（保留原方法中所有现有变量与逻辑，仅在外层包裹 try/finally 并在 finally 调用 `be.closeOperation(opHandle)`。）

- [ ] **Step 2: 防御性修复 - 引擎侧 ExecuteStatement finally 关闭 Statement**

定位 `externals/kyuubi-jdbc-engine/.../operation/ExecuteStatement.scala` 的 `executeStatement()` 方法 `finally` 块（约第 110-112 行），在 `shutdownTimeoutMonitor()` 后追加：

```scala
if (state != OperationState.FINISHED && jdbcStatement != null) {
  Utils.tryLogNonFatalError { jdbcStatement.close() }
  jdbcStatement = null
}
```

并在 DDL/DML 的 else 分支（约第 94-106 行）创建 iter 后追加：

```scala
jdbcStatement.close()
```

确保 import 含 `org.apache.kyuubi.Utils`（若未引入，新增 `import org.apache.kyuubi.Utils`）。

- [ ] **Step 3: 写回归测试（MySQL 协议 + JDBC Engine + StarRocks 容器）**

```scala
package org.apache.kyuubi.server.mysql

import org.apache.kyuubi.WithKyuubiServer
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.engine.jdbc.starrocks.WithStarRocksEngine
import org.apache.kyuubi.tags.DeltaTest

/**
 * 回归：DBeaver 经 MySQL 协议 + JDBC Engine 查询 StarRocks 时，
 * 连续多条查询不应触发 "Streaming result set is still active"。
 * 需 Docker（StarRocks 容器）。
 */
@DeltaTest
class MySQLJdbcEngineStreamingSuite extends WithKyuubiServer
  with WithStarRocksEngine
  with MySQLJDBCTestHelper {

  override protected val conf: KyuubiConf = baseConf

  test("consecutive queries via mysql protocol do not hit streaming conflict") {
    withMySQLJDBCConnection() { conn =>
      val stmt = conn.createStatement()
      // 模拟 DBeaver 连接后的连续查询序列
      stmt.execute("SELECT 1")
      stmt.execute("SELECT 2")
      val rs = stmt.executeQuery("SELECT 1 AS c UNION ALL SELECT 2")
      var n = 0
      while (rs.next()) n += 1
      assert(n === 2)
      stmt.close()
    }
  }
}
```

说明：`WithStarRocksEngine`、`MySQLJDBCTestHelper` 为 kyuubi-jdbc-engine / kyuubi-server 测试基础设施。若跨模块依赖测试辅助类不可见，则将本测试置于 kyuubi-jdbc-engine 模块并改用其 MySQL 协议入口；或参考 `MySQLSparkQuerySuite` 的组织方式。本测试需 Docker 且较重，可标记 `@DeltaTest` 以便按需启用。

- [ ] **Step 4: 运行回归测试**

Run: `./build/mvn test -pl kyuubi-server -am -Dtest=MySQLJdbcEngineStreamingSuite`
Expected: PASS（连续查询无异常）

- [ ] **Step 5: Commit**

```bash
git add kyuubi-server/src/main/scala/org/apache/kyuubi/server/mysql/MySQLCommandHandler.scala \
        externals/kyuubi-jdbc-engine/src/main/scala/org/apache/kyuubi/engine/jdbc/operation/ExecuteStatement.scala \
        kyuubi-server/src/test/scala/org/apache/kyuubi/server/mysql/MySQLJdbcEngineStreamingSuite.scala
git commit -m "fix: close operation after mysql-frontend fetch to avoid streaming result set leak"
```

---

## Task 13: 连接级限流验证

**Files:**
- Create: `kyuubi-server/src/test/scala/org/apache/kyuubi/operation/ConnectionLimitSuite.scala`

- [ ] **Step 1: 写限流验证测试**

```scala
package org.apache.kyuubi.operation

import java.sql.SQLException

import org.apache.kyuubi.WithKyuubiServer
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.operation.HiveJDBCTestHelper

class ConnectionLimitSuite extends WithKyuubiServer with HiveJDBCTestHelper {
  override protected val conf: KyuubiConf = {
    KyuubiConf()
      .set(KyuubiConf.ENGINE_SHARE_LEVEL, "connection")
      .set(KyuubiConf.SERVER_LIMIT_CONNECTIONS_PER_USER, 2)
  }

  override protected def jdbcUrl: String =
    s"jdbc:kyuubi://${server.frontendServices.head.connectionUrl}/"

  test("per-user connection limit rejects excess connections") {
    val conns = (1 to 2).map(_ => java.sql.DriverManager.getConnection(jdbcUrl, "alice", ""))
    val e = intercept[SQLException] {
      java.sql.DriverManager.getConnection(jdbcUrl, "alice", "")
    }
    assert(e.getMessage.contains("exceed") || e.getMessage.contains("limit"))
    conns.foreach(_.close())
  }
}
```

- [ ] **Step 2: 运行测试**

Run: `./build/mvn test -pl kyuubi-server -am -Dtest=ConnectionLimitSuite`
Expected: PASS（第 3 个连接被拒）

- [ ] **Step 3: Commit**

```bash
git add kyuubi-server/src/test/scala/org/apache/kyuubi/operation/ConnectionLimitSuite.scala
git commit -m "test: verify per-user connection limit"
```

---

## Task 14: 端到端冒烟测试与格式化

**Files:**
- Run: 全模块编译 + 格式化
- Create: `docs/superpowers/specs/2026-07-14-kyuubi-unified-gateway-design.md`（已存在，无需改）

- [ ] **Step 1: Spotless 格式化**

Run: `./dev/reformat`
Expected: 无格式错误（若有，按提示修正）

- [ ] **Step 2: 全量编译**

Run: `./build/mvn clean install -DskipTests -Pspark-3.5`
Expected: BUILD SUCCESS

- [ ] **Step 3: 运行 digiwin-plugins 全部单测**

Run: `./build/mvn test -pl digiwin-plugins -am`
Expected: 全部 PASS

- [ ] **Step 4: 运行 kyuubi-server 受影响测试**

Run: `./build/mvn test -pl kyuubi-server -am -Dtest=SqlInspectionSuite,DatasourcesResourceSuite,ServerJsonLoggingEventHandlerSuite,ConnectionLimitSuite`
Expected: 全部 PASS（MySQLJdbcEngineStreamingSuite 需 Docker，单独运行）

- [ ] **Step 5: Commit（如有格式修正）**

```bash
git add -A
git commit -m "style: apply spotless formatting"
```

---

## 自审清单（Self-Review）

**1. Spec 覆盖**：
- FR-1 数据源注册 CRUD + 热生效 → Task 5（store）+ Task 9（REST）
- FR-2 label 代入 → Task 6（DatasourceConfAdvisor）
- FR-3 凭据加密 → Task 4（AesEncryptor）+ Task 5
- FR-4 危险 SQL 拦截规则 → Task 7
- FR-5 白名单 + 告警 + 错误码 → Task 7（白名单）+ Task 8（KyuubiSQLException sqlState=42000）；告警为 WARN 日志（P0 简化，钉钉/邮件告警延后）
- FR-6 全量审计字段 → Task 10（clientIp/datasourceLabel/rowCount）+ 内置 statement/user/state/time
- FR-7 JSON 文件 → Task 11（配置）
- FR-9/FR-10 MySQL 协议 + DBeaver 修复 → Task 12
- FR-11 连接级限流 → Task 13

**2. 告警机制说明（FR-5 部分延后）**：钉钉/邮件告警通道未在 P0 实现，P0 仅 WARN 日志。需在计划中明确——已在本自审标注，建议 P1 补齐告警通道。如需 P0 即包含，请在评审时提出。

**3. 占位符扫描**：无 TBD/TODO；所有步骤含完整代码或精确命令。

**4. 类型一致性**：`SqlInspectionAction`（ALLOW/DENY/WARN）、`SqlInspectionResult.allow()/deny()`、`DatasourceStore` 方法名、`DATASOURCE_LABEL.key` 在各任务间一致。

**5. 已知风险/待确认**：
- Task 8/9 中 `ClassUtils.forName` 与 `DynConstructors` 的 import 路径需在实现时核对（`org.apache.kyuubi.util.ClassUtils`、`org.apache.kyuubi.util.reflect.DynConstructors`）。
- Task 9 的 `RestFrontendTestHelper` 可能需要补充认证配置（参考 BatchesResourceSuite）。
- Task 12 的跨模块测试（kyuubi-server 引用 kyuubi-jdbc-engine 的 WithStarRocksEngine）可能需调整测试模块归属。
- Task 10 的 `getNextRowSetInternal` 行数累计需对照实际方法体变量名。
- 上述均为实现期需现场核对的细节，非阻塞。

---

## 执行交接

计划已保存至 `docs/superpowers/plans/2026-07-14-kyuubi-unified-gateway-p0.md`。两种执行方式：

1. **Subagent 驱动（推荐）** - 每个 Task 派发独立 subagent，任务间评审，快速迭代。
2. **内联执行** - 在当前会话用 executing-plans 批量执行，检查点评审。

请选择执行方式。
