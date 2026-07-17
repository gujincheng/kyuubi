# Digiwin 数据源功能使用指南

Kyuubi 数据网关的**数据源 label 代入**功能:客户端连接时仅传 `kyuubi.datasource=<label>`,网关自动注入完整的引擎连接配置与解密后的凭据,直连目标数据源(如 StarRocks)。凭据以 AES 加密存储,明文不下发客户端。

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
  /opt/soft/kyuubi-1.11.1/bin/kyuubi-beeline -u "jdbc:kyuubi://..." ...
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
    "plainPassword": "DiGiWin@Sr312",
    "connectionPoolParams": {"maximumPoolSize": "5"},
    "status": "ENABLED",
    "description": "StarRocks 生产库"
  }'
```

- `plainPassword` 为明文,服务端加密后存入 SQLite,**响应与存储均为密文,不下发明文**。
- `PUT /api/v1/datasources/{label}` 用于更新(path 中的 label 必须与 body 一致)。
- `connectionPoolParams` 会被平铺注入引擎配置(如 `maximumPoolSize`、`connectionTimeout`)。

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

用**已安装的 Kyuubi 客户端**自带的 beeline(本文以 `/opt/soft/kyuubi-1.11.1` 为例,换成你自己的安装路径即可):

```bash
/opt/soft/kyuubi-1.11.1/bin/kyuubi-beeline \
  -u "jdbc:kyuubi://192.168.206.212:10009/?kyuubi.datasource=sr-prod" \
  -n root -p "" \
  -e "SELECT 1;"
```

客户端**只需传 `kyuubi.datasource=<label>`**,无需知道 url/user/password:

- URL 中 `?kyuubi.datasource=sr-prod` 是 label 代入的触发条件。
- 网关收到连接后,`DatasourceConfAdvisor` 解析 label,从注册中心取出数据源配置并**解密密码**,注入为引擎连接配置,随后拉起 JDBC 引擎连接 StarRocks。
- 也可进入交互式:把 `-e "SELECT 1;"` 去掉即可。
- 注意:beeline 客户端版本要与 server 匹配(都用 1.11.1)。

### 验证查询

```bash
/opt/soft/kyuubi-1.11.1/bin/kyuubi-beeline \
  -u "jdbc:kyuubi://192.168.206.212:10009/?kyuubi.datasource=sr-prod" \
  -n root -p "" \
  -e "SHOW DATABASES;"
```

返回 StarRocks 的真实库列表(CRM、SMESPROD、TOPPRD…)。

## 五、验收自查

| 测试 | 命令 | 期望 |
|------|------|------|
| 正确 label | `...?kyuubi.datasource=sr-prod` 跑 `SELECT 1` | 返回 `1` |
| 错误 label | `...?kyuubi.datasource=nonexistent` | 报错 `Datasource label nonexistent not found` |
| 不传 label | `jdbc:kyuubi://host:10009/`(无 `?`) | 失败(无引擎配置) |
| 凭据加密 | `sqlite3 ... SELECT encrypted_password` | 密文,无明文 |
| REST 响应 | `curl GET /datasources` | 不含密码字段 |

四项均符合即 label 代入 + 凭据加密功能验收通过。

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
