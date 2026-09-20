# Digiwin 数据源功能使用指南

Kyuubi 数据网关的**数据源 label 代入**功能：客户端连接时仅传
`kyuubi.datasource=<label>`，网关根据数据源类型自动选择 JDBC Engine 或 Spark SQL
Engine，并注入完整的连接配置。JDBC 凭据以 AES 加密存储，Iceberg 的 Catalog、
Warehouse 和资源规格由平台统一托管，客户端均无需重复传参。

## 一、配置(一次性)

### 1.1 `conf/kyuubi-defaults.conf`

关键配置项(本地调试示例):

```properties
# 关闭认证,方便本地调试
kyuubi.authentication=NONE
kyuubi.frontend.bind.host=192.168.206.212
kyuubi.frontend.protocols=THRIFT_BINARY,REST
kyuubi.frontend.thrift.binary.bind.port=10009

# 引擎连接配置由 DatasourceConfAdvisor 按 label 自动注入,不要在这里硬编码
# kyuubi.engine.jdbc.connection.url=...
# kyuubi.engine.jdbc.connection.password=...

# Digiwin 构建默认提供 MySQL Connector/J,可直接连接 StarRocks。
# 其他未随发行包提供的 JDBC 驱动,再通过 kyuubi.engine.jdbc.extra.classpath 配置。

# Digiwin 数据源注册中心(独立 SQLite 存储)
kyuubi.digiwin.datasource.store.enabled=true
kyuubi.digiwin.datasource.store.jdbc.url=jdbc:sqlite:/tmp/digiwin-datasources.db
kyuubi.digiwin.datasource.store.jdbc.driver=org.sqlite.JDBC

# 凭据加密密钥(必须 16 字节 UTF-8);不配则进程内随机,重启后旧密文解不开
kyuubi.digiwin.datasource.credential.secret=0123456789abcdef

# 启用 label 代入 SPI
kyuubi.session.conf.advisor=org.apache.kyuubi.digiwin.datasource.DatasourceConfAdvisor
```

> 生产环境务必设置一个稳定的 `kyuubi.digiwin.datasource.credential.secret`,否则重启后已加密的凭据无法解密。SQLite 也可换成 MySQL/PostgreSQL(改 `store.jdbc.url` + `driver`)。

### 1.2 beeline-jars 说明

`bin/kyuubi-beeline` 脚本从 `$KYUUBI_HOME/beeline-jars` 目录加载 beeline 及其依赖 jar。

- **生产部署(用 `./build/dist` 打的 tarball)**:`build/dist` 脚本会**自动创建** `beeline-jars/`(拷贝 `kyuubi-hive-beeline/target/*.jar` + 与 server 共享的依赖用软链指向 `../jars/`)。解压即用,**无需手动复制任何 jar**。digiwin 定制代码会随 `jars/` 一起打入(digiwin-plugins 是 kyuubi-server 的依赖)。
- **本地测试**:推荐用**已安装的 Kyuubi 客户端**自带的 beeline(其 `beeline-jars/` 已就位),例如:

  ```bash
  /opt/soft/kyuubi-1.12.0/bin/kyuubi-beeline -u "jdbc:kyuubi://..." ...
  ```

  不要在 Kyuubi **源码树**里建 `beeline-jars/`--源码原本没有这个目录,它只是 `./build/dist` 的构建产物。源码树里手建 `beeline-jars/` 属于临时产物,不要提交 git。

> 说明:源码树里**不需要** `beeline-jars/`。`bin/kyuubi-beeline` 在源码目录下因找不到 `beeline-jars/` 无法直接用;改用已安装客户端的 beeline,或在源码目录用 `java -cp "kyuubi-hive-beeline/target/classes:<deps>" org.apache.hive.beeline.BeeLine ...` 直跑。

## 二、启动 KyuubiServer

### IDEA 启动

- Main class:`org.apache.kyuubi.server.KyuubiServer`
- Environment variables(必填,否则 `kyuubi-defaults.conf` 不加载):

  ```
  KYUUBI_HOME=/Users/gujc/code/digiwinCode/kyuubi
  ```

### 启动成功的判据

日志中要看到:

```
Loading Kyuubi properties from .../conf/kyuubi-defaults.conf
Digiwin datasource registry initialized.
Exposing REST endpoint at: http://192.168.206.212:10099
```

## 三、管理数据源(REST API)

REST 前端默认 `http://<bind.host>:10099`,API 前缀 `/api/v1/datasources`。

### 创建 / 更新数据源(POST / PUT)

```bash
curl -X POST http://192.168.206.212:10099/api/v1/datasources \
  -H "Content-Type: application/json" \
  -d '{
    "label": "sr-prod",
    "engineType": "jdbc",
    "jdbcType": "starrocks",
    "driverClass": "com.mysql.cj.jdbc.Driver",
    "jdbcUrl": "jdbc:mysql://172.16.101.227:19030",
    "username": "root",
    "plainPassword": "<从安全渠道获取的密码>",
    "connectionPoolParams": {"maximumPoolSize": "5"},
    "status": "ENABLED",
    "description": "StarRocks 生产库"
  }'
```

- `plainPassword` 仅在请求中传递,服务端加密后存入 SQLite；REST 响应不会返回明文或密文字段,只返回 `credentialStored` 状态。
- `PUT /api/v1/datasources/{label}` 用于更新(path 中的 label 必须与 body 一致)。
- 编辑已有数据源时 `plainPassword` 留空会保留原凭据；新建时留空表示该数据源没有密码。
- `connectionPoolParams` 只允许连接池白名单参数,包括 `maximumPoolSize`、`minimumIdle`、`connectionTimeout`、`idleTimeout`、`maxLifetime`、`keepaliveTime`、`validationTimeout` 和 `leakDetectionThreshold`。
- JDBC URL 不允许内嵌 `password`、`passwd` 或 `pwd`,避免凭据进入日志和审计记录。
- StarRocks 使用 MySQL 协议,填写 `jdbc:mysql://<starrocks-host>:<query-port>` 和
  `com.mysql.cj.jdbc.Driver`; Digiwin 构建默认提供 MySQL Connector/J,不需要填写个人电脑的 Maven 路径。

### Web 管理页面

进入 `/ui/management/datasource` 可完成：

- 查看数据源总量、启用/停用数量及数据库类型统计。
- 按关键字、状态和 JDBC 类型筛选。
- 使用 StarRocks、MySQL、PostgreSQL、Oracle 模板创建数据源；不提供 Kyuubi JDBC
  Engine 未内置适配的 SQL Server、SQLite 或 Generic 模板。
- 编辑连接信息和连接池参数,查看详情,启用或停用数据源。
- 在保存前测试草稿配置,或测试已保存的数据源；测试结果会展示数据库产品、版本、耗时或明确失败原因。
- 创建 Iceberg 数据源，统一维护 Hive Metastore、Warehouse、S3 Endpoint 和可选的
  Session Profile；会话会自动切换到 Spark SQL Engine。

停用数据源不会被新 Session 选择,但平台管理员仍可执行连接测试,用于排查恢复前的配置问题。

### 连接测试

```bash
# 测试一份尚未保存的配置
curl -X POST http://192.168.206.212:10099/api/v1/datasources/test \
  -H "Content-Type: application/json" \
  -d '{
    "label": "starrocks-check",
    "engineType": "jdbc",
    "jdbcType": "starrocks",
    "driverClass": "com.mysql.cj.jdbc.Driver",
    "jdbcUrl": "jdbc:mysql://starrocks.example.com:9030/database",
    "username": "<数据库账号>",
    "plainPassword": "<从安全渠道获取的密码>",
    "connectionPoolParams": {},
    "status": "ENABLED",
    "description": "connection check"
  }'

# 测试已保存的数据源,停用状态也允许诊断
curl -X POST \
  http://192.168.206.212:10099/api/v1/datasources/sr-prod/test
```

连接成功返回 HTTP 200；连接失败返回 HTTP 502,响应中包含 `success=false` 和可直接展示的失败原因。

### 查询 / 删除 / 刷新

```bash
# 列表
curl http://192.168.206.212:10099/api/v1/datasources
# 单个
curl http://192.168.206.212:10099/api/v1/datasources/sr-prod
# 删除
curl -X DELETE http://192.168.206.212:10099/api/v1/datasources/sr-prod
# 手动刷新内存缓存(定时也会自动刷新,默认 60s)
curl -X POST http://192.168.206.212:10099/api/v1/datasources/refresh
```

### 验证凭据已加密

```bash
sqlite3 /tmp/digiwin-datasources.db \
  "SELECT label, encrypted_password FROM digiwin_datasource;"
# encrypted_password 形如 "ivBase64:cipherBase64",不含明文密码
```

## 四、通过 kyuubi-beeline 连接(核心用法)

用**已安装的 Kyuubi 客户端**自带的 beeline(本文以 `/opt/soft/kyuubi-1.12.0` 为例,换成你自己的安装路径即可):

```bash
/opt/soft/kyuubi-1.12.0/bin/kyuubi-beeline \
  -u "jdbc:kyuubi://192.168.206.212:10009/?kyuubi.datasource=sr-prod" \
  -n root -p "" \
  -e "SELECT 1;"
```

客户端**只需传 `kyuubi.datasource=<label>`**,无需知道 url/user/password:

- URL 中 `?kyuubi.datasource=sr-prod` 是 label 代入的触发条件。
- 网关收到连接后,`DatasourceConfAdvisor` 解析 label,从注册中心取出数据源配置并**解密密码**,注入为引擎连接配置,随后拉起 JDBC 引擎连接 StarRocks。
- 也可进入交互式:把 `-e "SELECT 1;"` 去掉即可。
- 注意:beeline 客户端版本要与 server 匹配(都用 1.12.0)。

### 验证查询

```bash
/opt/soft/kyuubi-1.12.0/bin/kyuubi-beeline \
  -u "jdbc:kyuubi://192.168.206.212:10009/?kyuubi.datasource=sr-prod" \
  -n root -p "" \
  -e "SHOW DATABASES;"
```

返回 StarRocks 的真实库列表(CRM、SMESPROD、TOPPRD…)。

### StarRocks 端到端案例

页面创建数据源时填写:

|    字段    |                 值                 |
|----------|-----------------------------------|
| 数据源标签    | `starrocks-e2e`                   |
| 引擎类型     | `JDBC`                            |
| 数据库类型    | `starrocks`                       |
| 驱动类      | `com.mysql.cj.jdbc.Driver`        |
| JDBC URL | `jdbc:mysql://172.16.7.136:19030` |
| 用户名      | `root`                            |
| 密码       | 从安全渠道填写                           |
| 状态       | `启用`                              |

保存前点击“测试连接”,页面应返回数据库产品、版本和连接耗时。保存后使用 Beeline 验证:

```bash
kyuubi-beeline \
  -u "jdbc:kyuubi://127.0.0.1:10009/?kyuubi.datasource=starrocks-e2e" \
  -n root \
  -p "" \
  -e "SELECT 1; SHOW DATABASES;"
```

验收要求:

1. 页面测试连接成功,不能出现 `Failed to load driver class com.mysql.cj.jdbc.Driver`。
2. Beeline 能成功创建 Kyuubi JDBC Session。
3. `SELECT 1` 返回 `1`。
4. `SHOW DATABASES` 返回 StarRocks 数据库列表。
5. REST 列表和详情均不返回明文密码或密文字段。

### Iceberg 端到端案例

页面新增数据源时选择 `Apache Iceberg`，填写：

|        字段         |                示例值                 |
|-------------------|------------------------------------|
| 数据源标签             | `iceberg-prod`                     |
| Catalog 名称        | `lake`                             |
| Catalog 类型        | `Hive Metastore`                   |
| Hive Metastore 地址 | `thrift://172.16.7.137:9083`       |
| Warehouse 地址      | `s3a://iceberg/`                   |
| 对象存储地址            | `http://s3.seaweedfs.local:30080`  |
| 对象存储凭据            | 选择已在页面顶部“管理对象存储凭据”中创建的凭据           |
| Path Style        | 开启                                 |
| S3 SSL            | 关闭                                 |
| Session Profile   | 可选；用于绑定 Spark Driver/Executor 资源规格 |

Iceberg 表单使用业务字段，不直接展示全部 `spark.*` 配置。保存数据源并通过
`kyuubi.datasource` 选择它后，平台按以下规则生成 Spark Engine 的运行时配置：

|         页面字段          |                                                运行时 Spark 配置                                                 |
|-----------------------|-------------------------------------------------------------------------------------------------------------|
| Catalog 名称（例如 `lake`） | `spark.sql.catalog.lake.*`                                                                                  |
| Hive Metastore 地址     | `spark.sql.catalog.lake.uri`                                                                                |
| Warehouse 地址          | `spark.sql.catalog.lake.warehouse`                                                                          |
| 对象存储地址                | `spark.hadoop.fs.s3a.endpoint`                                                                              |
| Path Style            | `spark.hadoop.fs.s3a.path.style.access`                                                                     |
| S3 SSL                | `spark.hadoop.fs.s3a.connection.ssl.enabled`                                                                |
| 对象存储凭据                | `spark.hadoop.fs.s3a.access.key`、`spark.hadoop.fs.s3a.secret.key`、可选 Session Token 及对应的 Credential Provider |

平台还会自动注入以下配置，页面无需提供输入框：

```properties
spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions
spark.sql.catalog.lake=org.apache.iceberg.spark.SparkCatalog
spark.hadoop.fs.s3a.impl=org.apache.hadoop.fs.s3a.S3AFileSystem
spark.hadoop.fs.s3a.aws.credentials.provider=<根据绑定凭据自动选择>
```

当前方案使用独立命名的 Iceberg Catalog，不替换 Spark 内置的 `spark_catalog`，因此不要为
同一个数据源重复配置 `spark.sql.catalog.spark_catalog`、`spark.sql.warehouse.dir`、
`spark.sql.catalogImplementation` 或 `spark.hadoop.hive.execution.engine`。如果确有遗留 Spark
应用兼容需求，应通过单独的 Session Profile 管理，而不是写入 Iceberg 数据源。

对象存储凭据应先通过页面顶部“管理对象存储凭据”创建，再由 Iceberg 数据源引用。也可以
通过 REST 创建凭据；请求中的密钥仅用于服务端加密保存，查询接口不会回显：

```bash
curl -X POST http://127.0.0.1:10099/api/v1/datasources/credentials \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "seaweedfs-prod",
    "provider": "s3",
    "accessKeyId": "<从安全渠道获取>",
    "secretAccessKey": "<从安全渠道获取>",
    "sessionToken": "",
    "description": "Iceberg Warehouse 对象存储凭据"
  }'
```

随后可以通过 REST 创建并绑定 Iceberg 数据源：

```bash
curl -X POST http://127.0.0.1:10099/api/v1/datasources \
  -H 'Content-Type: application/json' \
  -d '{
    "label": "iceberg-prod",
    "engineType": "spark",
    "status": "ENABLED",
    "description": "生产 Iceberg 湖仓",
    "icebergConfig": {
      "catalogName": "lake",
      "catalogType": "hive",
      "uri": "thrift://172.16.7.137:9083",
      "warehouse": "s3a://iceberg/",
      "s3Endpoint": "http://s3.seaweedfs.local:30080",
      "s3PathStyleAccess": true,
      "s3SslEnabled": false,
      "sessionProfile": "",
      "credentialRef": "seaweedfs-prod"
    }
  }'
```

客户端连接时仍然只传 label：

```bash
kyuubi-beeline \
  -u "jdbc:kyuubi://127.0.0.1:10009/?kyuubi.datasource=iceberg-prod" \
  -n alice \
  -e "SHOW NAMESPACES IN lake;"
```

完整验收可执行一组可清理的 Iceberg SQL：

```bash
kyuubi-beeline \
  -u "jdbc:kyuubi://127.0.0.1:10009/?kyuubi.datasource=iceberg-prod" \
  -n alice \
  -e "CREATE NAMESPACE IF NOT EXISTS lake.kyuubi_e2e;
      CREATE TABLE lake.kyuubi_e2e.managed_ds (id BIGINT, note STRING) USING iceberg;
      INSERT INTO lake.kyuubi_e2e.managed_ds VALUES (1, 'managed-datasource');
      SELECT id, note FROM lake.kyuubi_e2e.managed_ds;
      DROP TABLE lake.kyuubi_e2e.managed_ds;
      DROP NAMESPACE lake.kyuubi_e2e;"
```

Spark Driver 和 Executor 的 JDK 必须与 Iceberg Runtime 兼容。例如 Iceberg 1.11
需要 Java 17；若沿用当前生产镜像中的 Iceberg 1.9，应继续遵循该镜像已经验证的 JDK
版本。Kyuubi 数据源功能只负责注入 Catalog 配置，不会替换 Spark 镜像中的 Iceberg
Runtime。

运行机制：

1. `DatasourceConfAdvisor` 将引擎切换为 `SPARK_SQL`。
2. 自动注入 `spark.sql.catalog.lake.*`、Iceberg Spark Extension、Warehouse 和 S3A
   配置，客户端不再传一长串 Spark 参数。
3. 如果绑定 Session Profile，先加载 Profile 中的资源配置；连接参数中显式传入的资源
   配置优先于 Profile。
4. 网关按“数据源标签 + 最终配置指纹”生成 engine subdomain。不同数据源或配置版本不会
   错误复用同一个 Spark Engine；同一配置可继续复用。
5. 页面“测试连接”检查 Hive Metastore 端点是否可达；完整可用性必须再通过 Beeline
   执行 Iceberg SQL 验证，因为只有 Spark Engine 能同时校验 Iceberg Runtime、HMS、
   Warehouse 和对象存储凭据。

对象存储密钥不保存在数据源定义中，数据源只保存 `credentialRef`。凭据由服务端独立加密
存储，并只在启动 Spark Engine 时解密注入内存；REST 列表、详情及页面均不回显密钥。
方案 A 要求每个 Iceberg 数据源显式绑定凭据，不再依赖 Driver 或 Executor 的 AWS 环境变量
作为兼容路径。数据源接口会拒绝通过 `plainPassword` 传入 Iceberg 明文密钥。

## 五、验收自查

|    测试    |                      命令                      |                     期望                      |
|----------|----------------------------------------------|---------------------------------------------|
| 正确 label | `...?kyuubi.datasource=sr-prod` 跑 `SELECT 1` | 返回 `1`                                      |
| 错误 label | `...?kyuubi.datasource=nonexistent`          | 报错 `Datasource label nonexistent not found` |
| 不传 label | `jdbc:kyuubi://host:10009/`(无 `?`)           | 失败(无引擎配置)                                   |
| 凭据加密     | `sqlite3 ... SELECT encrypted_password`      | 密文,无明文                                      |
| REST 响应  | `curl GET /datasources`                      | 不含密码字段                                      |

五项均符合即 label 代入 + 凭据加密功能验收通过。

## 六、工作机制

```
客户端(kyuubi-beeline, 传 kyuubi.datasource=sr-prod)
        │  Thrift Binary :10009
        ▼
KyuubiServer
  ├─ KyuubiSessionImpl.optimizedConf 调用 SessionConfAdvisor 链
  ├─ DatasourceConfAdvisor 读 label -> DatasourceRegistryHolder.registry
  │     -> 取 sr-prod 配置 -> 解密密码 -> 返回 overlay:
  │        kyuubi.engine.type=jdbc
  │        kyuubi.engine.jdbc.type=starrocks
  │        kyuubi.engine.jdbc.connection.url=jdbc:mysql://172.16.101.227:19030
  │        kyuubi.engine.jdbc.connection.user=root
  │        kyuubi.engine.jdbc.connection.password=<解密后明文>
  │        kyuubi.engine.jdbc.driver.class=com.mysql.cj.jdbc.Driver
  │        + connectionPoolParams
  ├─ overlay 覆盖 session conf -> 拉起 JDBC Engine
  └─ JDBC Engine 连 StarRocks -> 执行 SQL -> 返回结果
```

## 七、常见问题

- **conf 不生效**:IDEA Run Config 没设 `KYUUBI_HOME` 环境变量。判据:启动日志有 `Loading Kyuubi properties from ...`。
- **`Datasource registry is not initialized`**:没设 `kyuubi.digiwin.datasource.store.enabled=true`,或 conf 没加载。
- **重启后连接失败**:没配 `kyuubi.digiwin.datasource.credential.secret`(或换了密钥),旧密文解不开。删库重建或恢复密钥。
- **源码目录里 `bin/kyuubi-beeline` 报 `ClassNotFoundException: KyuubiBeeLine`**:正常,源码树没有 `beeline-jars/`。改用已安装 Kyuubi 客户端的 beeline(见 1.2、第四节)。
- **错误 label 报 `Datasource label X not found`**:正常,说明 SPI 已接线、label 校验生效。

