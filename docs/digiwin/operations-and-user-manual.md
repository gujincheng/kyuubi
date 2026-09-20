# Digiwin Kyuubi 安装、使用与运维手册

适用版本：基于 Apache Kyuubi 1.12.0 的 `codex/digiwin-1.12.0-admin-governance` 分支，包含当前工作区的数据源与容器部署改动。核对日期：2026-09-16。

本文面向平台运维、平台管理员和数据使用人员。命令中的 `<...>` 必须替换为本环境的真实值；文中不提供生产密码。内网 IP 是现有联调环境地址，部署到其他环境时必须修改。

**交付状态说明：**本文依据当前源码和配置编写。本地已完成部分后端测试与模块打包，但新容器镜像尚未实际构建完成，新部署清单尚未在目标集群完成五类数据源端到端验收。部署人员必须完成第 15 节验收，不能将“驱动已打包”或“页面测试连接成功”视为生产验收通过。

## 1. 阅读顺序和职责

|    人员    |     建议阅读      |           交付结果            |
|----------|---------------|---------------------------|
| 安装运维     | 第 2～5、13～15 节 | 服务可启动、配置可写、数据可恢复、客户端可连接   |
| 平台管理员    | 第 6～12 节      | 身份接入、账号授权、数据源、模板、查询治理配置完成 |
| SQL 使用人员 | 第 7～9、14 节    | 使用自己的企业账号和数据源标签完成查询       |
| 值班人员     | 第 10、12～15 节  | 定位异常、终止异常任务、备份恢复、升级回滚     |

目录：

- [2. 产品能力、概念和入口](#2-产品能力概念和入口)
- [3. 安装准备](#3-安装准备)
- [4. Kubernetes 安装](#4-kubernetes-安装)
- [5. Linux 单机安装](#5-linux-单机安装)
- [6. 企业身份认证与访问授权](#6-企业身份认证与访问授权)
- [7. 数据源和对象存储凭据](#7-数据源和对象存储凭据)
- [8. 用户连接和查询](#8-用户连接和查询)
- [9. 会话模板和用户默认配置](#9-会话模板和用户默认配置)
- [10. 监控、SQL 记录和资源管理](#10-监控sql-记录和资源管理)
- [11. SQL 拦截、连接限额和超时](#11-sql-拦截连接限额和超时)
- [12. 系统配置、审计和可选功能](#12-系统配置审计和可选功能)
- [13. 日常运维、备份和升级](#13-日常运维备份和升级)
- [14. 故障排查](#14-故障排查)
- [15. 上线验收与交接](#15-上线验收与交接)
- [16. REST 接口速查与资料](#16-rest-接口速查与资料)

## 2. 产品能力、概念和入口

Kyuubi 提供统一 SQL 接入：用户连接网关，由网关选择引擎访问下游数据库。它不替代下游数据库，也不创建 LDAP/IAM 企业账号。

```text
用户（Web / Beeline / JDBC / REST）
                  │
             Kyuubi Server ── LDAP / IAM：校验企业身份
                  │           本地绑定：准入、管理角色、会话默认值
          数据源标签 kyuubi.datasource
                  ├─ JDBC Engine ── StarRocks / MySQL / PostgreSQL / Oracle
                  └─ Spark SQL Engine ── Iceberg Hive Catalog
                                            ├─ Hive Metastore
                                            └─ S3 / SeaweedFS 数据文件
```

|       概念        |                      含义                      |          不要混淆           |
|-----------------|----------------------------------------------|-------------------------|
| 企业账号            | LDAP/IAM 中的用户名和密码                            | 不是数据源中保存的数据库账号          |
| 授权绑定            | 外部账号进入 Kyuubi 的准入和管理权限                       | 不会创建或删除外部账号             |
| 数据源标签           | 例如 `oracle-prod`，客户端选择目标数据源的标识               | 不是数据库/schema 名称         |
| 存储凭据            | Iceberg 对象存储的 Access Key、Secret Key、可选 Token | 不是企业登录密码，也不是 JDBC 数据库密码 |
| Session         | 一次客户端连接建立的会话                                 | 同一会话可以提交多条 SQL          |
| Operation       | 查询、元数据请求等操作                                  | 不一定是一条业务 SQL            |
| Engine          | 实际执行 SQL 的进程                                 | 一个 Engine 可以被多个会话复用     |
| Session Profile | 一组会话/引擎配置的模板                                 | 不授予表权限，不存放用户账号          |

默认入口（端口以实际配置为准）：

|       用途        |          单机 / Pod 内           |             当前 K8s NodePort             |
|-----------------|-------------------------------|-----------------------------------------|
| Web UI          | `http://<host>:10099/ui/`     | `http://<node-ip>:30099/ui/`            |
| REST            | `http://<host>:10099/api/v1`  | `http://<node-ip>:30099/api/v1`         |
| JDBC/Thrift     | `jdbc:kyuubi://<host>:10009/` | `jdbc:kyuubi://<node-ip>:30009/`        |
| Prometheus 格式指标 | `http://<host>:10019/metrics` | 当前 Service 未暴露 10019；运维可临时 port-forward |

Web UI 由 Kyuubi Server 提供，正式安装不需要单独启动 Vite/Node 前端服务。开发模式的 9090 端口不作为生产入口。

菜单包括 Overview、Session、Operation、SQL Record、Data Source、Batch、Engine、Server、Access Management、System Setting、Management Audit、Audit Log、Swagger、SQL Editor 和 Data Agent。Data Agent 需要额外引擎与模型配置，当前定制 Dockerfile 未打入该引擎，不能仅凭菜单判断已经可用。

## 3. 安装准备

### 3.1 部署前填写环境表

|          参数          |                         当前参考值 / 需填写内容                          |
|----------------------|----------------------------------------------------------------|
| K8s namespace        | `spark`                                                        |
| Kyuubi 镜像标签          | `kyuubi:1.12.0-digiwin-datasources`；发布时建议改为不可变的版本标签            |
| Spark 基础/Executor 镜像 | `spark-iceberg:3.5.8`                                          |
| Kyuubi 调度节点          | `ddp2`；Kyuubi 数据保存在该节点的 `/opt/kyuubi-data`                     |
| ServiceAccount       | `spark-sa`                                                     |
| 本地数据目录               | `ddp2:/opt/kyuubi-data`，由 `hostPath DirectoryOrCreate` 自动创建    |
| Hive Metastore       | `thrift://172.16.7.137:9083`                                   |
| Iceberg Warehouse    | `s3a://iceberg/`                                               |
| S3 Endpoint          | `http://s3.seaweedfs.local:30080`；必须能从 Driver 和 Executor 解析并访问 |
| StarRocks            | `172.16.7.136:19030`，MySQL 协议                                  |
| PostgreSQL           | `172.16.101.223:5432/postgres`                                 |
| Oracle               | `172.16.101.223:1521/ORCL`，此处 ORCL 按 Service Name 使用           |
| MySQL                | 需提供真实 host、端口、库、用户及密码；当前未提供实际测试端点                              |
| 企业身份源                | LDAP/LDAPS 或约定 HTTP JSON 接口的 IAM 地址                            |
| 加密密钥                 | 固定的 16 字节 UTF-8 密钥；与数据备份一并保管，不能重启时重新生成                         |

**Kubernetes 镜像前置条件：集群内必须准备两个镜像。** `kyuubi:1.12.0-digiwin-datasources` 运行 Kyuubi Server，`spark-iceberg:3.5.8` 运行 Spark Executor。虽然前者是基于后者构建的，但 Kubernetes 仍会按照 `spark.kubernetes.container.image` 单独创建 Executor Pod；只加载 Kyuubi 镜像不能替代 Executor 镜像。两个镜像都需要推送到集群可访问的镜像仓库，或预加载到所有可能调度对应 Pod 的节点。

**数据源凭据前置条件：不创建 `aws-creds`、`kyuubi-datasource-secrets` 或 `kyuubi-credential-key`。** S3/SeaweedFS 凭据和 JDBC 数据库凭据统一通过数据源管理页面或 REST API 创建并保存；数据源凭据的加密主密钥直接配置在 `kyuubi.yaml` 的 `kyuubi-defaults.conf` 中。

网络需要覆盖：用户到网关、Server 到 LDAP/IAM/JDBC/HMS/S3、Driver 到 K8s API、Executor 到 Driver 和存储。只在运维电脑执行 `curl` 成功，不能证明 Pod 网络可达。容器内 `127.0.0.1` 指向该 Pod，不能用于访问运维电脑上的测试 LDAP。

当前安装方案是**单副本**：SQLite 和本地配置文件不能直接扩展为多副本共享写。不要只把 `replicas` 改成 2；高可用改造需要独立验证外部服务发现、状态存储和配置一致性。

### 3.2 获取完整发行包

优先使用发布人员交付的同版本二进制包和镜像。源码目录不能代替安装目录。

从源码构建时，在已配置 Java/Maven 依赖下载环境的构建机执行：

```bash
./build/dist --tgz --web-ui -Pspark-3.5 -DskipTests
```

该命令跳过测试，仅生成发行包，不代表验收通过。前端工具链需满足 `web-ui/package.json` 的 Node 要求：`^20.19.0` 或 `>=22.12.0`。Spark 3.5 运行环境须与基础镜像内 Java、Scala、Hadoop 版本匹配；沿用已经验证过的 `spark-iceberg:3.5.8` 基础镜像。构建脚本报错时，先解决并取得成功产物，不能混用旧 `dist` 和新源码当作正式发布。

完整包应包含 `bin/`、`conf/`、`jars/`、`beeline-jars/`、`web-ui/dist/`、`externals/engines/spark/` 和 `externals/engines/jdbc/`。Server `jars/` 应包含二开插件和 SQLite 驱动。

**JDBC 驱动要检查两个位置：**页面“测试连接”在 Server 进程执行，实际 SQL 查询在 JDBC Engine 执行。页面测试要求驱动在 Server 的 `jars/` 中，实际查询要求驱动在 `externals/engines/jdbc/` 中；两处都缺少时，不能认为数据源已经可用。当前 JDBC Engine POM 已将 MySQL、PG、Oracle 纳入非 test 依赖，但构建后仍须分别检查两个 classpath。

本分支使用的驱动版本：MySQL Connector/J 8.4.0、PostgreSQL 42.7.11、Oracle ojdbc8 23.2.0.0。在已解压的发行包内检查：

```bash
# 复制apache-kyuubi-1.12.0-bin-spark-3.5.tgz 到构建容器的服务器
tar zxvf apache-kyuubi-1.12.0-bin-spark-3.5.tgz -C .
cd apache-kyuubi-1.12.0-bin-spark-3.5
# 验证安装包完整性
ls jars/digiwin-plugins-*.jar jars/sqlite-jdbc-*.jar
find externals/engines/jdbc -maxdepth 1 -type f \
  \( -name 'mysql-connector-j-*.jar' -o -name 'postgresql-*.jar' -o -name 'ojdbc*.jar' \) \
  -print | sort
find jars -maxdepth 1 -type f \
  \( -name 'mysql-connector-j-*.jar' -o -name 'postgresql-*.jar' -o -name 'ojdbc*.jar' \) \
  -print | sort
```

如果页面测试需要某个驱动而 `jars/` 中没有，应将 `externals/engines/jdbc/` 中同版本的驱动补入 `jars/`，并删除重复版本；特别是 Oracle 页面测试不能只检查 JDBC Engine 目录。不要把驱动复制到 Spark 的 `/opt/spark/jars` 来代替 JDBC Engine classpath，也不要在镜像中写入个人电脑的 Maven 路径。向外分发镜像前应随制品保留对应驱动许可证和第三方声明。

## 4. Kubernetes 安装

### 4.0 标准安装闭环：Kyuubi + Spark on Kubernetes + Iceberg

本节是新环境的推荐执行顺序。完成后，用户只连接 Kyuubi 的 Thrift/JDBC 地址；Kyuubi 内的 Spark Driver 会通过 Kubernetes API 自动创建 Spark Executor，Executor 再访问 Hive Metastore 和 S3/SeaweedFS 中的 Iceberg 数据。

```text
Beeline / JDBC
      │
      ▼
Kyuubi Server Pod（Spark SQL Driver，使用 spark-sa）
      │  Kubernetes API
      ├── 创建/管理 Executor Pod（spark-iceberg:3.5.8）
      │
      ├── Hive Metastore：thrift://172.16.7.137:9083
      └── S3/SeaweedFS：s3a://iceberg/
```

`spark-sa` 不是企业登录账号，也不是 Web UI 管理员角色。它是 Spark Driver 在 K8s 中创建、查看和回收 Executor Pod 所使用的 ServiceAccount。没有它，Kyuubi Server 仍可能启动，但执行 Spark SQL 时会出现 `Forbidden`，无法创建 Executor。

#### 4.0.1 准备安装目录和两个镜像

使用最新构建的 Spark 发行包，不要使用旧的无 Spark 安装包：

```bash
tar -zxf apache-kyuubi-1.12.0-bin-spark-3.5.tgz
cd apache-kyuubi-1.12.0-bin-spark-3.5

# 确认 Spark SQL 和 JDBC Engine 目录存在
test -d externals/engines/spark
test -d externals/engines/jdbc
find externals/engines/jdbc -maxdepth 1 -type f | sort
```

Kubernetes 部署需要两个不同用途的镜像：

|                 镜像                  |                                用途                                 |                        配置位置                        |
|-------------------------------------|-------------------------------------------------------------------|----------------------------------------------------|
| `kyuubi:1.12.0-digiwin-datasources` | Kyuubi Server、Web UI、REST、JDBC Engine，以及 Kyuubi 进程内的 Spark Driver | `Deployment.spec.template.spec.containers[].image` |
| `spark-iceberg:3.5.8`               | Spark Executor，包含 Spark、Iceberg、Hadoop AWS/S3A 等运行依赖              | `spark.kubernetes.container.image`                 |

Kyuubi 镜像的构建上下文必须是解压后的发行包根目录，并且基础镜像 `spark-iceberg:3.5.8` 在构建机上可用：

```bash
docker image inspect spark-iceberg:3.5.8
docker build -f docker/Dockerfile.digiwin \
  -t kyuubi:1.12.0-digiwin-datasources .
docker image inspect kyuubi:1.12.0-digiwin-datasources
```

上面的 Dockerfile 只是使用 `spark-iceberg:3.5.8` 作为基础镜像构建 Kyuubi Server 镜像，不会改变两个镜像在 Kubernetes 中的职责。Executor 镜像仍由 `spark.kubernetes.container.image` 指定为 `spark-iceberg:3.5.8`，并且必须在所有可能调度 Executor 的节点可用。

#### 4.0.2 创建 Namespace、spark-sa 和 K8s 权限

```bash
kubectl create namespace spark --dry-run=client -o yaml | kubectl apply -f -
kubectl -n spark get serviceaccount spark-sa
```

如果 `spark-sa` 不存在，将以下资源保存为 `spark-rbac.yaml` 并执行：

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spark-sa
  namespace: spark
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: spark-role
  namespace: spark
rules:
  - apiGroups: [""]
    resources:
      - pods
      - pods/log
      - services
      - configmaps
      - persistentvolumeclaims
    verbs:
      - create
      - get
      - list
      - watch
      - update
      - patch
      - delete
      - deletecollection
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: spark-rolebinding
  namespace: spark
subjects:
  - kind: ServiceAccount
    name: spark-sa
    namespace: spark
roleRef:
  kind: Role
  name: spark-role
  apiGroup: rbac.authorization.k8s.io
```

```bash
kubectl apply -f spark-rbac.yaml
kubectl auth can-i create pods \
  --as=system:serviceaccount:spark:spark-sa -n spark
kubectl auth can-i create services \
  --as=system:serviceaccount:spark:spark-sa -n spark
kubectl auth can-i create configmaps \
  --as=system:serviceaccount:spark:spark-sa -n spark
```

上述三个检查都必须返回 `yes`。企业集群已有等价权限时，先核对权限再复用，不要重复创建或覆盖企业 RBAC。

#### 4.0.3 配置数据源加密主密钥和持久化资源

真实的 S3/JDBC 凭据不在 Kubernetes Secret 中维护，而是在 Kyuubi 启动后通过数据源管理页面或 REST API 写入加密数据库。数据源加密主密钥直接配置在 `kyuubi.yaml` 的 `kyuubi-defaults.conf`：

```properties
kyuubi.digiwin.datasource.credential.secret=<固定的16字节密钥>
```

该值必须是固定的 16 字节字符串，首次部署前替换 `<固定的16字节密钥>`，后续不能随意修改，否则已有数据源凭据无法解密。`kyuubi.yaml` 使用 `ddp2:/opt/kyuubi-data` 的 hostPath 保存 SQLite 注册库、可写配置和审计数据。该目录丢失会导致数据源定义和加密凭据丢失。

#### 4.0.4 部署 Kyuubi Server

`kyuubi.yaml` 应至少包含以下关键配置。完整资源以仓库根目录的清单为准：

注意：下面的 `image` 是 Kyuubi Server 镜像；Spark Executor 使用的镜像不写在 Deployment 的容器字段中，而是写在 `spark-defaults.conf` 的 `spark.kubernetes.container.image` 中。两处配置共同生效，不能只配置其中一个。

```yaml
spec:
  template:
    spec:
      serviceAccountName: spark-sa
      containers:
        - name: kyuubi
          image: kyuubi:1.12.0-digiwin-datasources
```

`spark-defaults.conf` 中必须明确 Spark on Kubernetes 和 Executor 镜像；对象存储凭据由数据源管理按数据源标签注入，不配置 Kubernetes Secret 映射：

```properties
spark.master=k8s://https://kubernetes.default.svc:443
spark.kubernetes.namespace=spark
spark.kubernetes.container.image=spark-iceberg:3.5.8
spark.kubernetes.container.image.pullPolicy=IfNotPresent
spark.kubernetes.authenticate.driver.serviceAccountName=spark-sa
spark.kubernetes.executor.podNamePrefix=kyuubi-exec
spark.executor.cores=2
spark.executor.memory=2g
spark.kubernetes.executor.limit.cores=2
spark.dynamicAllocation.enabled=true
spark.dynamicAllocation.shuffleTracking.enabled=true
```

执行部署和启动检查：

```bash
kubectl apply --dry-run=server -f kyuubi.yaml
kubectl apply -f kyuubi.yaml
kubectl -n spark rollout status deployment/kyuubi --timeout=300s
kubectl -n spark get pods,pvc,svc
kubectl -n spark logs deployment/kyuubi --tail=100
```

Kyuubi Pod `Running` 只表示 Server 启动成功，此时 Executor 可能还没有创建。Executor 是用户提交需要计算的 Spark SQL 后按需拉起的。

#### 4.0.5 连接 Kyuubi 并验证 Iceberg

先通过 NodePort 或 port-forward 暴露 Kyuubi：

```bash
kubectl -n spark port-forward service/kyuubi 10009:10009 10099:10099
```

使用 Kyuubi Beeline 连接 Iceberg 数据源。`kyuubi.datasource=iceberg-hms` 是数据源标签，不是数据库名；Iceberg 的 Catalog、HMS、Warehouse、S3 Endpoint 和凭据由服务端按标签注入：

```bash
bin/kyuubi-beeline \
  -u "jdbc:kyuubi://127.0.0.1:10009/?kyuubi.datasource=iceberg-hms" \
  -n <企业账号>
```

先执行元数据检查，再执行实际数据读写：

```sql
SHOW CATALOGS;
SHOW NAMESPACES IN iceberg;
SHOW TABLES IN iceberg.<业务namespace>;
SELECT * FROM iceberg.<业务namespace>.<非空测试表> LIMIT 10;
```

如果有批准的测试 namespace 和写权限，再执行完整读写验证：

```sql
CREATE TABLE iceberg.<测试namespace>.kyuubi_k8s_it (
  id INT,
  note STRING
) USING iceberg;

INSERT INTO iceberg.<测试namespace>.kyuubi_k8s_it VALUES (1, 'k8s-ok');
SELECT * FROM iceberg.<测试namespace>.kyuubi_k8s_it;
DROP TABLE iceberg.<测试namespace>.kyuubi_k8s_it;
```

执行 `SELECT`、`INSERT` 或其他需要计算的 SQL 时，在另一个终端观察 Executor：

```bash
kubectl -n spark get pods -w
kubectl -n spark get pods -l spark-role=executor
kubectl -n spark describe pod <kyuubi-exec-pod>
```

通过标准的判定链路：

1. Kyuubi Server 能接收 Beeline 连接。
2. Spark SQL Driver 能通过 `spark-sa` 调用 K8s API。
3. K8s 中出现 `kyuubi-exec-*` Executor Pod，状态最终为 `Running`/`Succeeded`。
4. Driver 和 Executor 均能访问 `172.16.7.137:9083`、S3 Endpoint 及 Iceberg Warehouse。
5. `SELECT` 能读到真实数据；写入后再次查询结果正确。
6. 关闭会话并等待动态资源回收后，Executor 按配置缩容，不影响 Kyuubi Server。

只看到 `SHOW TABLES` 成功不能证明 Iceberg 读写链路完整，因为它可能只访问了 Metastore 元数据；必须有一次真实文件读取，最好再完成一次受控写入。

### 4.1 构建和分发镜像

需要同时分发两个镜像：Kyuubi Server 镜像 `kyuubi:1.12.0-digiwin-datasources` 和 Spark Executor 镜像 `spark-iceberg:3.5.8`。基础镜像 `/opt/spark/jars` 应包含适配 Spark 3.5/Scala 2.12 的 Iceberg runtime，以及版本匹配的 Hadoop S3A/AWS SDK 依赖；仅安装 HMS 客户端不能读取 S3 文件。

在拥有基础镜像的 Linux 构建机，进入完整发行包目录执行：

```bash

docker image inspect spark-iceberg:3.5.8
docker build -f docker/Dockerfile.digiwin -t kyuubi:1.12.0-digiwin-datasources .
docker image inspect kyuubi:1.12.0-digiwin-datasources
docker save kyuubi:1.12.0-digiwin-datasources -o kyuubi-datasources.tar
```

如果使用私有镜像仓库，应分别推送两个镜像，并在 `kyuubi.yaml` 中填写可被集群访问的完整地址：

```bash
docker tag kyuubi:1.12.0-digiwin-datasources <registry>/kyuubi:1.12.0-digiwin-datasources
docker tag spark-iceberg:3.5.8 <registry>/spark-iceberg:3.5.8
docker push <registry>/kyuubi:1.12.0-digiwin-datasources
docker push <registry>/spark-iceberg:3.5.8
```

如果不使用镜像仓库而采用节点预加载，必须在所有可能运行 Kyuubi Server 或 Executor 的节点分别确认两个镜像均已存在。只在构建机或单个节点存在镜像，不代表其他节点可以启动 Pod。

当前 `kyuubi.yaml` 使用短镜像名并设置 `imagePullPolicy: IfNotPresent`，因此离线或无镜像仓库部署时，必须把镜像导入 containerd 的 `k8s.io` 命名空间。下面示例将两个镜像分别保存，然后在每个目标节点执行导入：

```bash
docker save kyuubi:1.12.0-digiwin-datasources \
  -o /tmp/kyuubi-1.12.0-digiwin-datasources.tar
docker save spark-iceberg:3.5.8 \
  -o /tmp/spark-iceberg-3.5.8.tar
```

将两个 tar 文件复制到 Kyuubi 节点和所有可能调度 Executor 的节点，在每个节点执行：

```bash
sudo ctr -n k8s.io images import /tmp/kyuubi-1.12.0-digiwin-datasources.tar
sudo ctr -n k8s.io images import /tmp/spark-iceberg-3.5.8.tar
sudo crictl images | grep -E 'kyuubi|spark-iceberg'
```

如果使用镜像仓库，则不需要逐节点导入，但必须分别推送两个镜像，并把 `kyuubi.yaml` 中的
Kyuubi Server 镜像和 `spark-defaults.conf` 中的 Executor 镜像都改成完整仓库地址。Executor
镜像尤其不能只存在于某一个 Kyuubi Server 节点。

将镜像文件传到运行 Kyuubi 的节点，再在该节点执行：

```bash
sudo ctr -n k8s.io images import kyuubi-datasources.tar
sudo crictl images
```

确认节点 CPU 架构与镜像一致。Spark Executor 镜像必须在所有可能调度的节点可用。使用企业镜像仓库时，将清单中 Kyuubi 镜像和 Spark 配置中的 Executor 镜像分别替换为完整仓库地址，并配置拉取凭据。

### 4.2 Namespace、服务账号和存储

```bash
kubectl create namespace spark --dry-run=client -o yaml | kubectl apply -f -
kubectl -n spark get serviceaccount spark-sa
kubectl get storageclass
```

新集群没有 `spark-sa` 时，保存以下内容为 `spark-rbac.yaml` 后执行 `kubectl apply -f spark-rbac.yaml`。这是本安装方式在 `spark` namespace 的资源权限，不是 Kyuubi 页面管理员角色。

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spark-sa
  namespace: spark
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: spark-role
  namespace: spark
rules:
  - apiGroups: [""]
    resources: ["pods", "services", "configmaps", "persistentvolumeclaims"]
    verbs: ["create", "get", "list", "watch", "update", "patch", "delete", "deletecollection"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: spark-rolebinding
  namespace: spark
subjects:
  - kind: ServiceAccount
    name: spark-sa
    namespace: spark
roleRef:
  kind: Role
  name: spark-role
  apiGroup: rbac.authorization.k8s.io
```

已有 RBAC 时先核查，不要覆盖企业已有角色。没有默认 StorageClass 时，在 `kyuubi.yaml` 的 PVC `spec` 中补充真实的 `storageClassName`。当前 hostPath shuffle 路径 `/opt/spark_shuffle_data` 也须符合节点目录权限和集群策略，否则 Executor 无法正常运行。

### 4.3 创建数据源加密主密钥

通过企业密钥管理工具或受保护的本地文件创建 Secret。方案 A 只创建数据源加密主密钥；下面的 env 文件为**格式模板**，值必须填写 16 字节密钥，且不要再包一层引号。

方案 A 不配置 `kyuubi.digiwin.datasource.bootstrap.file`，也不在 ConfigMap 中保存数据源定义。部署完成后从数据源管理页面或 REST API 创建存储凭据和数据源；重启只从 PVC 中的注册库加载，不会用初始化文件覆盖页面修改。

该加密主密钥不是数据库密码，也不是 S3 Access Key。数据源管理页面收到 JDBC 密码或 S3 密钥后，服务端使用它加密，再保存到 PVC 中的 SQLite 注册库；创建 Spark/JDBC 会话时，服务端临时解密并注入运行时配置，页面和 REST 查询不会回显明文。

### 4.4 为页面管理准备可写配置目录

源码中的 `kyuubi.yaml` 已经包含生产所需的可写配置部署逻辑，无需再复制或维护其他安装清单。ConfigMap 只作为首次安装种子，`initContainer` 只复制 PVC 中尚不存在的配置文件；Kyuubi 实际运行配置位于 PVC 的 `/opt/kyuubi/data/conf`，因此后续重启不会覆盖企业认证、访问授权、Session Profile 和审计配置。

该清单使用单副本 `Recreate` 策略，因为数据源注册库和管理配置使用同一个节点本地 hostPath。若企业禁止 root initContainer，应由存储管理员在 `ddp2:/opt/kyuubi-data` 预置 `conf`、`audit` 和 `events` 目录并授权 UID 10009。

### 4.5 启动和首次检查

源码根目录的 `kyuubi.yaml` 已包含 ConfigMap、Deployment、Service、hostPath、可写配置初始化和双镜像配置，直接执行：

```bash
kubectl apply --dry-run=server -f kyuubi.yaml
kubectl apply -f kyuubi.yaml
kubectl -n spark rollout status deployment/kyuubi --timeout=300s
kubectl -n spark get pods,pvc,svc
kubectl -n spark logs deployment/kyuubi --tail=100
kubectl -n spark exec deployment/kyuubi -- sh -c \
  'test -w /opt/kyuubi/conf && test -w /opt/kyuubi/data && echo writable'
kubectl -n spark port-forward service/kyuubi 10099:10099 10009:10009
```

最后一个命令保持运行；浏览器访问 `http://127.0.0.1:10099/ui/`。也可使用允许访问的 NodePort。检查启动日志中的 REST/Thrift 端口和 PVC 可写状态；数据源页面初始为空，完成数据源创建后再执行连接测试。

Pod Running 只代表进程启动，不代表下游数据源已可查询。还需完成第 8、15 节。

默认配置为 `kyuubi.authentication=NONE`。首次配置期间限制网关访问范围，完成企业认证再开放给使用人员。需要加密传输的环境应配置企业认可的 HTTPS/Thrift TLS 接入，不能直接将当前明文 NodePort 当作安全公网入口。

## 5. Linux 单机安装

适用于测试或单机运行。使用完整发行包，创建普通运行账号并授予安装目录 `conf`、`logs`、`pid`、`work`、数据目录的读写权限。

设定环境变量，实际路径按安装位置调整：

```bash
export KYUUBI_HOME=/opt/kyuubi
export KYUUBI_CONF_DIR=/opt/kyuubi/conf
export JAVA_HOME=<本机Java安装路径>
export SPARK_HOME=/opt/spark
export KYUUBI_IDENTITY_ACCESS_PATH=/opt/kyuubi/conf/kyuubi-identity-access.json
export KYUUBI_ADMIN_PERMISSIONS_PATH=/opt/kyuubi/conf/kyuubi-admin-permissions.json
export KYUUBI_AUDIT_LOG_PATH=/opt/kyuubi/data/audit/kyuubi-audit.jsonl
```

LDAP/IAM 服务凭据仍由服务管理器安全注入环境变量；数据源加密主密钥直接写在下面的 `kyuubi-defaults.conf` 中，不再通过环境变量传递。

编辑 `conf/kyuubi-defaults.conf`：

```properties
kyuubi.authentication=NONE
kyuubi.frontend.bind.host=0.0.0.0
kyuubi.frontend.protocols=THRIFT_BINARY,REST
kyuubi.frontend.thrift.binary.bind.port=10009
kyuubi.frontend.rest.bind.port=10099
kyuubi.engine.type=SPARK_SQL
kyuubi.engine.share.level=USER
kyuubi.digiwin.datasource.store.enabled=true
kyuubi.digiwin.datasource.store.jdbc.url=jdbc:sqlite:/opt/kyuubi/data/digiwin-datasources.db
kyuubi.digiwin.datasource.store.jdbc.driver=org.sqlite.JDBC
# 固定的 16 字节数据源凭据加密主密钥；部署前替换为本环境专用值，后续不可随意修改。
kyuubi.digiwin.datasource.credential.secret=CHANGE_16_BYTE!!
kyuubi.session.conf.advisor=org.apache.kyuubi.digiwin.datasource.DatasourceConfAdvisor
kyuubi.digiwin.sql.inspection.enabled=true
kyuubi.backend.server.event.loggers=JSON
kyuubi.backend.server.event.json.log.path=file:///opt/kyuubi/data/events
kyuubi.metrics.reporters=PROMETHEUS
kyuubi.metrics.prometheus.port=10019
kyuubi.server.redaction.regex=(?i)password|secret|token|credential|access\\.key
```

单机 Spark 测试可在 Spark 配置中使用 `spark.master local[2]`；正式集群按实际部署设置。上述配置不启用数据源初始化文件，启动后通过页面创建数据源。

```bash
mkdir -p /opt/kyuubi/data/audit /opt/kyuubi/data/events
"$KYUUBI_HOME/bin/kyuubi" start
curl -f http://127.0.0.1:10099/api/v1/overview/summary
"$KYUUBI_HOME/bin/kyuubi" stop
```

生产使用 systemd 等进程管理器时，采用 `bin/kyuubi run` 前台运行，配置运行用户、工作目录、环境变量和日志策略。不要同时用 systemd 和 `kyuubi start` 启动两个实例占用同一端口。

## 6. 企业身份认证与访问授权

入口：Access Management（访问管理）。当前有“认证接入”“授权详情”“会话模板”三个页签。旧 Policy、Permissions 链接会跳转到此处。

### 6.1 认证状态与管理角色

|    状态     |                                             实际含义                                             |
|-----------|----------------------------------------------------------------------------------------------|
| 未启用企业身份认证 | 仍由当前 `kyuubi.authentication` 决定认证；默认 NONE 不校验密码。本地绑定不构成企业认证的登录准入名单，已有 Session 黑名单/限额仍可能限制建会话 |
| 已保存、等待重启  | 文件已变更，但当前进程仍按旧认证方式运行                                                                         |
| 已启用并重启    | 登录必须有启用身份源、允许访问的账号绑定，并通过该 LDAP/IAM 的密码校验                                                     |
| 已停用并重启    | 当前停用实现写回 NONE，不会自动恢复此前的 LDAP/Kerberos 等认证配置                                                  |

|          账号类型          |              能力               |
|------------------------|-------------------------------|
| 平台管理员 `platform-admin` | 管理身份、策略、数据源和平台资源              |
| 只读管理员 `viewer`         | 查看获准的管理信息，不能修改、刷新、删除或终止资源     |
| 普通账号（不分配管理角色）          | 通过认证使用自己的查询会话；管理页面/接口可能返回 403 |

数据库的表、列、行级权限由下游引擎和数据库控制。当前管理角色也不是“按数据源标签授权”的 ACL。若多个用户使用同一个 JDBC 数据源，他们实际连接下游的是该数据源保存的同一数据库账号；应为数据源配置适当权限的专用数据库账号。

### 6.2 接入 LDAP / LDAPS

1. 运维提供目录地址、Base DN、查询账号 Bind DN、用户过滤器、用户名属性及目录查询密码。
2. 将查询密码注入 Server 环境变量，例如 `CORP_LDAP_BIND_PASSWORD`；重建服务使环境变量生效。
3. 在“认证接入 → 添加身份源”选择 LDAP，填写下表。
4. 保存，点击“测试连接”。成功后到“授权详情”查询目录，确认能检索预期账号。

|       字段        |               OpenLDAP 示例               |                     说明                     |
|-----------------|-----------------------------------------|--------------------------------------------|
| 标识              | `corp-ldap`                             | 稳定的身份源 ID                                  |
| 地址              | `ldaps://ldap.example.com:636`          | LDAP 明文常用 389；LDAPS 证书需被 Server JVM 信任     |
| Base DN         | `dc=example,dc=com`                     | 查询范围                                       |
| Bind DN         | `cn=directory-reader,dc=example,dc=com` | 目录查询服务账号，不是启用认证时选择的管理员                     |
| 凭据环境变量          | `CORP_LDAP_BIND_PASSWORD`               | 填变量名，不填密码，也不加 `env:`                       |
| 用户过滤器           | `(objectClass=inetOrgPerson)`           | 按公司目录实际对象类型调整                              |
| 用户名 / 姓名 / 邮箱属性 | `uid` / `cn` / `mail`                   | Active Directory 常用 `sAMAccountName` 作为用户名 |
| 用户 DN 模板        | `uid={0},ou=people,dc=example,dc=com`   | 可留空，使用 Bind DN 查询实际用户 DN                   |

“测试连接”测试的是目录查询链路，不证明每个用户密码正确。启用认证前应通过实际账号完成登录演练。当前目录检索存在返回数量限制（最多 500 条）且在获取结果后过滤关键字，不是完整企业目录分页；超大目录需缩小 Base DN/过滤器后验证。

### 6.3 接入公司 IAM

当前支持自定义 HTTP JSON 目录及密码校验接口，不包含 OIDC/OAuth 浏览器单点登录跳转。公司 IAM 接口结构不一致时，应提供适配接口。

页面填写 Endpoint、用户路径（默认 `/users`）、用户组路径（默认 `/groups`）、认证路径（默认 `/authenticate`）及 JSON 字段映射。需要服务 Token 时，将 Token 放入服务端环境变量，页面只填变量名；请求会带 `Authorization: Bearer <token>`。

默认接口约定：

```http
GET /users
```

```json
{"users":[{"id":"u-1001","username":"alice","displayName":"Alice","email":"alice@example.com","groups":["analysts"]}]}
```

也接受顶层数组。用户组响应为数组或 `{"groups":[{"id":"g-1","name":"analysts","members":["alice"]}]}`。

```http
POST /authenticate
Content-Type: application/json
```

```json
{"username":"alice","password":"<用户本次输入的密码>"}
```

**认证接口必须用 HTTP 状态码表示成功/失败：2xx 视为通过，错误密码返回 401/403 等非 2xx。当前实现不解析 `{"success":false}` 判断认证结果，因此失败时返回 HTTP 200 会被误判通过。**目录连接测试通过之后，必须验收正确密码、错误密码和不存在账号三种情况。

### 6.4 给用户授予访问

1. 在“授权详情”打开目录授权，选择身份源，选择用户类型，输入账号并查询。
2. 对目标账号点击“授予访问”。普通用户选允许访问，不分配管理角色；运维账号选平台管理员；监控账号可选只读管理员。
3. 按需要选择会话模板、用户默认配置、免连接配额标记后保存。
4. 在授权列表检查身份源、用户名、角色、允许/拒绝状态。

当前只支持用户直接绑定，用户组查询不等于支持组授权。每个 Kyuubi 用户名只能绑定一个外部身份，避免多个身份源的同名用户混淆。

“拒绝访问”阻止后续访问/建会话；“解除绑定”移除 Kyuubi 本地授权及对应投影配置，不删除 LDAP/IAM 账号。需要立即切断用户业务时，还应检查并关闭存量 Session/Operation，不能假设解绑会回收所有既有连接。

### 6.5 启用、停用和应急恢复

启用前至少有一个启用的身份源，以及其下“允许访问”的平台管理员绑定。点击“启用企业身份认证”不再要求填写管理员用户名密码；后端检查绑定列表，将 CUSTOM 认证配置写入运行文件。

```bash
kubectl -n spark rollout restart deployment/kyuubi
kubectl -n spark rollout status deployment/kyuubi --timeout=300s
```

重启后，用已绑定账号的 LDAP/IAM 密码登录 UI 和 Beeline。所有满足条件的已绑定账号均能认证，不只限于管理员 alice；是否能操作管理功能由角色决定。浏览器可能显示 HTTP Basic 认证框或缓存此前认证信息，切换账号测试可使用独立隐私窗口。

停用时在页面点击停用并重启，状态应变为未启用。账号绑定、身份源和模板保留。不要在访问已开放的情况下随意切回 NONE。

管理员无法登录时：运维先限制入口，备份实际配置与身份文件，修复 LDAP/IAM 地址、服务凭据或绑定；确需临时恢复 NONE 时只改实际运行的 `kyuubi-defaults.conf` 后重启，恢复授权后重新启用并复测。修改 ConfigMap 种子不会自动修改 PVC 内运行配置。

## 7. 数据源和对象存储凭据

### 7.1 创建 JDBC 数据源

在 Data Source 页面点击新增，选择类型，填写唯一标签、连接地址、数据库账号、密码和可选连接池参数；先测试连接，再保存。标签建议只使用英文字母、数字、点、下划线、短横线，长度不超过 64。

|     类型     |       示例标签        |                   JDBC URL 示例                    |            驱动类             |
|------------|-------------------|--------------------------------------------------|----------------------------|
| StarRocks  | `starrocks-prod`  | `jdbc:mysql://172.16.7.136:19030`                | `com.mysql.cj.jdbc.Driver` |
| MySQL      | `mysql-prod`      | `jdbc:mysql://<host>:3306/<database>`            | `com.mysql.cj.jdbc.Driver` |
| PostgreSQL | `postgresql-prod` | `jdbc:postgresql://172.16.101.223:5432/postgres` | `org.postgresql.Driver`    |
| Oracle     | `oracle-prod`     | `jdbc:oracle:thin:@//172.16.101.223:1521/ORCL`   | `oracle.jdbc.OracleDriver` |

Oracle 的 Service Name 与 SID 连接格式不同，须由 DBA 确认；schema 不等于 Service Name。示例表实际属于 `C##FLINKUSER`，不要未经核对写成 `FLINKUSER`。当前页面不提供 SQL Server、SQLite、Generic 模板；SQLite 在本产品中用于内部配置存储，不是页面数据源类型。

连接池支持 `maximumPoolSize`、`minimumIdle`、`connectionTimeout`、`idleTimeout`、`maxLifetime`、`keepaliveTime`、`validationTimeout`、`leakDetectionThreshold`；时间参数按对应字段的毫秒数填写。连接池规模不是 SQL 执行队列容量。

### 7.2 创建 Iceberg 数据源

先在数据源页面的存储凭据管理中创建 S3 凭据：填写凭据 ID、Access Key、Secret Key；使用临时凭据时填写 Session Token，并由运维安排过期前更新。再新增 Iceberg 数据源：

|        字段         |                与当前部署一致的示例                |
|-------------------|------------------------------------------|
| 标签                | `iceberg-hms`                            |
| Catalog 名称        | `iceberg`                                |
| Catalog 类型        | `hive`                                   |
| Metastore URI     | `thrift://172.16.7.137:9083`             |
| Warehouse         | `s3a://iceberg/`                         |
| S3 Endpoint       | `http://s3.seaweedfs.local:30080`        |
| Path-style access | `true`                                   |
| S3 SSL            | 此示例 HTTP endpoint 对应 `false`；HTTPS 按环境设置 |
| 存储凭据引用            | 例如 `seaweedfs`                           |
| 会话模板              | 可选，例如 `analyst`                          |

网关会注入 Iceberg Spark 扩展、Catalog、HMS、Warehouse 和 S3A 配置，客户端只传标签。当前使用命名 `SparkCatalog`；不要求用户再配置 `spark_catalog` 为 `SparkSessionCatalog`，也不需要在全局 `hive-site.xml` 写死该数据源。

有凭据引用时，网关注入 S3A Simple/Temporary 凭据提供器，并将数据源管理中保存的凭据传递给本次 Spark 会话；方案 A 不依赖 AWS 环境变量回退。凭据保存在数据源注册库中，不能把 Key 放入会话模板、JDBC URL、SQL 或普通备注。

Iceberg“测试连接”主要检查 HMS/S3 端点网络可达，并不会启动 Spark 验证真实表读写，也不等于 S3 权限验证。必须按第 8 节读取数据文件；仅 `SHOW TABLES` 成功不够。

### 7.3 编辑、停用、删除及生效范围

- JDBC 编辑时密码留空保留原密码，不表示清空；响应不回显明文或密文。
- 停用/删除数据源影响新会话，存量会话和复用引擎可能继续运行；先识别关联连接，再按需终止。
- 修改 JDBC 地址/账号/密码后，须用新会话验证；如复用旧引擎仍未生效，安排关联 Engine 回收。
- Iceberg 按数据源标签、有效配置和凭据版本生成引擎隔离标识；配置变更后新会话可启动新引擎，旧会话不自动迁移。
- 已绑定的存储凭据不能直接删除，先解除数据源引用。更新共享凭据会影响所有引用它的数据源。
- 连接测试成功证明当次 Server 连接测试通过，不证明所有 SQL、数据类型和数据库权限均可用。

### 7.4 方案 A 的数据源维护方式

方案 A 只有一个数据源配置来源：数据源管理页面或对应 REST API。

1. 先在“存储凭据”中创建 S3/SeaweedFS 凭据，例如 `seaweedfs`。
2. 再创建 Iceberg 数据源，在 Iceberg 配置中选择 `credentialRef=seaweedfs`。
3. 创建 StarRocks、MySQL、PostgreSQL、Oracle 数据源时，直接在表单中填写 JDBC URL、用户名和密码。
4. 保存后执行“测试连接”，再通过 Beeline 创建新会话验证真实查询。
5. 重启或滚动升级后，数据源从 `/opt/kyuubi/data/digiwin-datasources.db` 恢复，不会被 ConfigMap 或环境变量覆盖。

不要重新添加 `kyuubi.digiwin.datasource.bootstrap.file`。如果未来确实需要 GitOps 初始化，应另行设计一次性导入流程，不能与页面维护同时作为同名数据源的写入来源。

注册库当前使用 SQLite 及 `ON CONFLICT` 等实现，不能按旧文档直接把内部存储 JDBC URL 换成 MySQL 就宣称兼容。这与业务查询支持 MySQL 是两回事。

## 8. 用户连接和查询

### 8.1 Beeline：最常用的连接方式

向平台管理员获取网关地址、已启用的数据源标签、企业账号及授权范围。用匹配版本发行包的 `bin/kyuubi-beeline`，密码使用交互提示，不写在命令行。

```bash
# K8s NodePort；省略 -p 后按客户端提示输入企业账号密码
bin/kyuubi-beeline \
  -u "jdbc:kyuubi://<node-ip>:30009/?kyuubi.datasource=starrocks-prod" \
  -n alice

# 本机或 port-forward
bin/kyuubi-beeline \
  -u "jdbc:kyuubi://127.0.0.1:10009/?kyuubi.datasource=iceberg-hms" \
  -n alice
```

`-n alice` 是网关用户，数据库账号由数据源配置注入。NONE 模式下该名字并没有通过密码认证。连接参数 `kyuubi.datasource` 要放在 JDBC URL 的 `?` 段，多个会话配置用 `;` 分隔。

### 8.2 五类数据源的查询案例

先连接相应 label，再执行其 SQL。下面涉及真实表的语句均为只读，表名需以目标数据库实际结果为准。

**StarRocks（`starrocks-prod`）：**

```sql
SELECT 1;
SHOW DATABASES;
USE <业务数据库>;
SHOW TABLES;
SELECT * FROM <获准读取的表> LIMIT 10;
```

**MySQL（`mysql-prod`）：**

```sql
SELECT 1;
SELECT DATABASE(), CURRENT_USER();
SHOW TABLES;
SELECT * FROM <获准读取的表> LIMIT 10;
```

**PostgreSQL（`postgresql-prod`）：**

```sql
SELECT current_database(), current_user;
SELECT table_schema, table_name
FROM information_schema.tables WHERE table_schema='public';
SELECT * FROM public.gjc_test_cdc LIMIT 10;
```

现有联调环境验证过 `public.gjc_test_cdc`；不要默认 `gjc_test_cdc_1` 一定存在。

**Oracle（`oracle-prod`）：**

```sql
SELECT 1 FROM DUAL;
SELECT owner, table_name FROM all_tables WHERE table_name='GJC_TEST_CDC';
SELECT * FROM C##FLINKUSER.GJC_TEST_CDC WHERE ROWNUM <= 10;
```

Oracle 查询不能照抄 MySQL 的 `LIMIT` 语法，初始化查询也需使用 `SELECT 1 FROM DUAL`；当前数据源适配器已提供该默认初始化 SQL。

**Iceberg（`iceberg-hms`，Catalog=`iceberg`）：**

```sql
SHOW CATALOGS;
SHOW NAMESPACES IN iceberg;
SHOW TABLES IN iceberg.<业务namespace>;
SELECT * FROM iceberg.<业务namespace>.<获准读取的表> LIMIT 10;
```

第三条只访问元数据；第四条成功且确实读取现有非空表数据，才覆盖对象存储读取链路。

有测试库写入权限时，可在批准的专用 namespace 验证完整读写；以下只清理本例自行创建的测试表，若同名表已存在应换一个唯一名称：

```sql
CREATE TABLE iceberg.<测试namespace>.kyuubi_acceptance_20260916 (id INT, note STRING) USING iceberg;
INSERT INTO iceberg.<测试namespace>.kyuubi_acceptance_20260916 VALUES (1, 'ok');
SELECT * FROM iceberg.<测试namespace>.kyuubi_acceptance_20260916;
DROP TABLE iceberg.<测试namespace>.kyuubi_acceptance_20260916;
```

若平台启用了 DROP 拦截，清理由 DBA 按审批流程执行。Iceberg 删除表不应被理解为一定物理清除全部对象文件，存储清理由对应生命周期策略处理。

### 8.3 JDBC 应用与桌面 SQL 工具

应用或 DBeaver 等客户端使用 Kyuubi JDBC 驱动及其发行依赖，驱动类 `org.apache.kyuubi.jdbc.KyuubiHiveDriver`，URL 与 Beeline 相同，用户名密码填写企业账号。不要用 MySQL 驱动连接网关 10009/30009；MySQL 驱动是 JDBC Engine 连接下游使用的。

确认桌面工具保留 URL `?` 后的会话参数；先执行 `SELECT 1`（Oracle 使用 DUAL）再查看业务表。某些客户端会自动查询主键、事务等元数据，下游不支持的元数据请求不一定代表普通 SELECT 失败。

### 8.4 SQL Editor

打开 SQL Editor，新增标签页，选择已经配置可用的引擎类型，输入 SQL 后点击运行，在 Result/Log 中查看结果和日志。Limit 10/50/100 控制页面结果获取/展示，不应当作下游计算资源上限。

**当前 Editor 建 Session 只传引擎类型，没有数据源标签选择入口。**连接某个托管数据源应使用 Beeline/JDBC 或下面的 REST 会话配置。不要假设在 Data Source 页面点击数据源会自动切换 Editor 连接；切换引擎时也应新建会话标签并核对 Session。

### 8.5 REST 完整查询生命周期

以下请求使用已授权用户。设定 `API=http://127.0.0.1:10099`（正式环境使用实际安全地址），`curl --user alice` 会提示输入密码。NONE 模式测试可省略 `--user`。返回结构以 Swagger 为准。

```bash
API=http://127.0.0.1:10099
curl --fail-with-body --user alice -H 'Content-Type: application/json' \
  -X POST "$API/api/v1/sessions" \
  -d '{"configs":{"kyuubi.datasource":"starrocks-prod"}}'
```

记录响应的 `identifier` 为 `<session-id>`：

```bash
curl --fail-with-body --user alice -H 'Content-Type: application/json' \
  -X POST "$API/api/v1/sessions/<session-id>/operations/statement" \
  -d '{"statement":"SELECT 1","runAsync":true}'
```

记录返回 Operation 的 `identifier` 为 `<operation-id>`，查看事件和日志，等待 FINISHED；若 ERROR/TIMEOUT/CANCELED，先排查错误，不直接当作成功取数。

```bash
curl --fail-with-body --user alice "$API/api/v1/operations/<operation-id>/event"
curl --fail-with-body --user alice "$API/api/v1/operations/<operation-id>/log"
curl --fail-with-body --user alice "$API/api/v1/operations/<operation-id>/resultsetmetadata"
curl --fail-with-body --user alice "$API/api/v1/operations/<operation-id>/rowset?maxrows=100"
curl --fail-with-body --user alice -X PUT -H 'Content-Type: application/json' \
  "$API/api/v1/operations/<operation-id>" -d '{"action":"CLOSE"}'
curl --fail-with-body --user alice -X DELETE "$API/api/v1/sessions/<session-id>"
```

结果较多时按接口支持的 fetch 参数继续读取；完成后关闭 Operation 和 Session，避免长期占用连接。

## 9. 会话模板和用户默认配置

Session Profile 用来集中复用一组参数。例如分析用户通常需要较小的 Executor 和较短查询超时，可以保存名为 `analyst` 的模板：

```properties
spark.executor.memory=2g
spark.executor.cores=2
spark.kubernetes.executor.limit.cores=2
spark.dynamicAllocation.maxExecutors=4
kyuubi.operation.query.timeout=10m
```

在“访问管理 → 会话模板 → 新建”填写名称，通过“添加配置项”添加 key/value 后保存。模板不会凭空安装 Spark、Flink、Trino 引擎或驱动，选用的引擎仍需预先部署。

使用方式：在授权绑定中选择模板、在 Iceberg 数据源选择模板，或在客户端会话参数指定：

```text
jdbc:kyuubi://<host>:10009/?kyuubi.datasource=iceberg-hms;kyuubi.session.conf.profile=analyst
```

用户默认配置只针对该用户的新会话。模板变更不保证现有 Engine 自动重建；更新后建立新连接，并在 Session 详情检查最终生效值。Iceberg 数据源适配器会固定注入其 Catalog/凭据相关参数，模板适合维护资源和通用执行参数。

“免配额”仅表示不受连接数量限额影响，不等于允许访问、不等于 SQL 拦截豁免，也不等于数据库管理员。IP 拒绝名单在授权详情中维护；使用代理/NAT 时先核对 Kyuubi 实际记录的客户端 IP。

## 10. 监控、SQL 记录和资源管理

### 10.1 Overview 指标怎么读

Overview 直接读当前 Kyuubi 进程的 MetricsSystem；与 Prometheus 导出同源，不调用外部 Prometheus。少数资源概览通过当前 Session/配置/服务发现取得。页面刷新不表示全部数据都是整个集群汇总。

|           指标域           |                          解读与操作                           |
|-------------------------|----------------------------------------------------------|
| Server 启动计数             | 当前进程计数，不是跨重启保存的历史启动次数；存活节点数来自服务发现，异常时存在本地回退              |
| Engine 启动计数             | 各引擎累计拉起次数，不等于当前在线数；在线详情看 Engine 页面                       |
| 活动用户 / Session          | 当前 Server 的连接活动；多个 Session 可以属于同一用户                      |
| Profile 数量              | 当前配置目录中的会话模板数量                                           |
| 执行队列 `size` / `waiting` | 当前等待工作队列长度，两者同一个 Gauge；不是总容量，不能相加                        |
| 执行队列 `active` / `alive` | 正在执行任务的线程数 / 存活工作线程数；线程池任务不只包含用户业务 SQL                   |
| SQL running / waiting   | ExecuteStatement 的运行/等待状态计数；不能直接等同于执行线程池的 active/waiting |
| SQL 失败次数 / 失败率          | 基于当前进程 SQL 操作累计总数计算；零请求时显示 0 不代表已经有成功业务样本                |
| P50 / P95 / P99         | 延迟分位数，按显示单位读；P95 不是平均时间                                  |
| Engine 健康               | 启动中、等待启动许可、累计失败/超时和启动延迟                                  |
| REST / Metadata / Batch | 各自请求或操作的统计，不与 SQL 次数一一对应                                 |
| JVM                     | 堆/非堆、线程、死锁、GC；不能代替 Executor/下游数据库资源监控                    |
| 证书有效期                   | 仅在相关 SSL 指标存在时有意义；无值需检查是否配置 SSL                          |

SQL 趋势默认约每 15 秒采样，1H/1D/7D 分别按 1 分钟/15 分钟/1 小时聚合。SQL 执行曲线是时间桶内计数增量，**不是每秒 QPS**；切换时间范围后每个点覆盖时长不同。队列及 P95 趋势使用桶内采样快照，不是整桶所有查询重新计算的 P95。

趋势最多保留 7 天且只在内存中，重启清空；没有采样的历史不能当成真实的零流量。`INITIALIZING`、`READY`、`STALE` 表示采样状态。健康卡片用于定位异常，查看具体阈值/异常说明后再处理。

临时核对底层指标：

```bash
kubectl -n spark port-forward deployment/kyuubi 10019:10019
# 另开终端
curl -f http://127.0.0.1:10019/metrics
```

对账时固定同一个 Server、采样时间和指标类型；累计值、实时值、时间桶增量不能直接相等比较。长期趋势和告警可由外部监控平台采集，Overview 不提供外部告警通知系统。

### 10.2 SQL Record

按时间、用户、Session、Engine、状态或 SQL 关键字筛选；打开详情查看 SQL、错误、排队时间和执行时长，必要时定位对应 Session/Operation。支持自动刷新和 CSV 导出，导出按当前条件最多获取前 200 条，不是完整历史备份。

保存范围是单 Server 内存中的 ExecuteStatement：最多 10,000 条、24 小时，重启清空。提交前已被 SQL 规则拦截的请求可能没有 Operation/SQL Record，应查看 `sql_blocked` 事件日志。耗时统计也不等于客户端完整耗时，后者可能包含建引擎和结果传输时间。

### 10.3 Session、Operation、Engine、Server、Batch

|    页面     |             常用操作              |                    影响范围                    |
|-----------|-------------------------------|--------------------------------------------|
| Session   | 按用户筛选，查看配置、Engine、客户端和操作；关闭会话 | 关闭会话会影响其运行操作，先联系业务确认                       |
| Operation | 找运行中的 SQL，查看错误和进度；取消/关闭       | CANCEL 请求终止执行；CLOSE 释放操作及结果资源              |
| Engine    | 按类型和用户筛选，查看地址/版本；代理 UI、移除     | 移除注册节点与终止应用不是同一动作；检查是否选择 kill，复用引擎可能影响多个会话 |
| Server    | 查看节点、版本、地址、命名空间和属性            | 用于确认版本/服务发现，详情里的长节点名称是注册信息                 |
| Batch     | 查看批处理状态、日志及跟随日志；受控终止          | 是批任务接口对象，不是交互式 SQL 历史；不替代定时调度平台            |

遇到慢查询，先在 SQL Record/Operation 定位用户、SQL 和持续时间，确认后取消具体 Operation；只有需要释放全部用户连接时才关闭 Session，影响多个会话的 Engine 操作应安排维护窗口。

## 11. SQL 拦截、连接限额和超时

### 11.1 SQL 规则

当前 SQL 规则通过 REST 管理，没有独立规则菜单。需开启数据源注册库与 `kyuubi.digiwin.sql.inspection.enabled=true`；规则与数据源同库保存。

|     规则类型      |         示例          |        意义         |
|---------------|---------------------|-------------------|
| KEYWORD       | `DROP`              | 拦截指定开头关键字         |
| WITHOUT_WHERE | `DELETE` / `UPDATE` | 拦截缺少 WHERE 的删除/更新 |
| REGEX         | `SELECT\\s+\\*`     | 自定义正则匹配           |

管理员创建规则示例：

```bash
curl --fail-with-body --user <平台管理员> \
  -X POST "$API/api/v1/sql-rules" -H 'Content-Type: application/json' \
  -d '{"id":"block-drop","name":"禁止DROP","ruleType":"KEYWORD","pattern":"DROP","action":"DENY","engineScope":"","userScope":[],"enabled":true}'
```

`engineScope`/`userScope` 为空表示所有引擎/用户；指定引擎时按会话实际类型核对。创建、修改、删除规则会更新当前 Server 缓存，多节点需单独考虑刷新。命中返回 `SQL_BLOCKED`，并记录拦截事件。

规则采用字符串/正则检查，不是完整 SQL 语法权限系统，不能取代数据库权限。上线前在隔离环境验证注释、多语句、子查询、大小写、正常 SQL 和恶意 SQL。SQL 豁免使用 `kyuubi.digiwin.sql.inspection.whitelist`，与连接免配额名单不同。

### 11.2 连接配额和超时

以下为示例值，写入实际运行 `kyuubi-defaults.conf`，按容量调整并安排生效验证：

```properties
kyuubi.server.limit.connections.per.user=10
kyuubi.server.limit.connections.per.ipaddress=50
kyuubi.server.limit.connections.per.user.ipaddress=5
kyuubi.operation.query.timeout=30m
kyuubi.operation.interrupt.on.cancel=true
kyuubi.session.engine.initialize.timeout=3m
kyuubi.session.idle.timeout=1h
```

连接限制按 Session 数计算，不是 SQL QPS 限流。`kyuubi.server.limit.engine.startup` 是引擎启动并发控制配置；Engine pool size、连接数和 SQL 执行线程池大小是不同层次。

黑名单、免连接配额名单和用户默认值支持专用刷新接口；一般启动/资源配置不能仅点击系统刷新就生效。管理员名单键是 `kyuubi.server.administrators`，不是旧文档中的 `kyuubi.server.admin.users`。

当前没有交付通用的 SQL QPS 令牌桶或连续失败自动熔断器，不应将连接限额和引擎启动超时称为已实现这些功能。

## 12. 系统配置、审计和可选功能

### 12.1 System Setting

该页面查看运行时配置快照、认证方式和管理员信息，敏感值脱敏；不支持任意配置在线编辑。选择 Hadoop、Kubernetes、用户默认值或访问策略等受控刷新入口前，先更新其对应配置来源。

“已保存配置”和“进程已加载配置”可能不同。认证启停必须重启；Secret 环境变量更新必须重建 Pod；模板/数据源配置应在新会话和实际 Engine 中验证。

### 12.2 Management Audit 与 Audit Log

|         数据          |                     用途                      |                          当前保存方式                          |
|---------------------|---------------------------------------------|----------------------------------------------------------|
| Management Audit 页面 | 管理员配置变更、操作者、目标 URI、客户端 IP 和执行结果             | `KYUUBI_AUDIT_LOG_PATH` 指定 JSONL；默认日志目录；最多 2,000 条、24 小时 |
| Audit Log 页面        | Kyuubi 原生 Server、Session、Operation、SQL 拦截事件 | 页面选择 JSON 事件目录或 Kafka Topic                              |
| SQL Record 页面       | 近期查询诊断、失败原因和耗时                              | 内存，不作为持久化审计库                                             |

Audit Log 的“审计配置”页签支持以下操作：

1. 选择 **JSON 文件** 时填写 Kyuubi 容器内的 `file://` 持久化目录，例如 `file:///opt/kyuubi/data/events`。
2. 选择 **Kafka** 时填写 Bootstrap Servers、Topic 和安全协议；SASL/SSL 场景再填写用户名、密码和 Truststore。
3. 先点击“测试配置”。JSON 会验证目录可写，Kafka 会写入探针并从准确的分区和偏移回读，只有真实读写成功才返回成功。
4. 点击“保存并生效”。新事件立即切换到新存储，无需重启 Kyuubi Server。
5. 回到“审计记录”，可按事件类型、用户和状态筛选，并点击记录查看已脱敏的原始事件。

页面配置保存在可写配置目录的 `kyuubi-audit-config.json`。Kafka 密码与 Truststore 密码加密保存，页面重新打开时只显示“已配置”，不会回显明文。切换存储方式不会迁移或删除旧存储中的历史数据。

JSON 模式按 `day=yyyyMMdd` 日期分区读取，页面默认查询最近 7 天，也支持最近 24 小时、30 天和自定义日期范围，只扫描对应日期目录；Kafka 模式则按消息时间过滤。

同一 Operation 会记录 INITIALIZED、PENDING、RUNNING、FINISHED、CLOSED 等状态变化。统计最终查询量时应按 operationId 和最终状态去重，不能直接统计消息数或文件行数。事件包含 SQL、用户和业务上下文，需要受控访问；长期留存建议使用 Kafka 接入企业审计平台。

### 12.3 Swagger、Batch 和 Data Agent

Swagger 用于核对本服务实际 API 与请求模型，测试写接口需使用相应角色并确认目标对象。接口 404 时先检查部署版本，不要把所有 404 当成账号权限问题。

Batch 的提交模型和例子见 [REST API](../client/rest/rest_api.md) 与 [Batch UI](../client/ui/batch_ui.md)。引擎文件、资源、依赖和集群管理器需要先就绪；当前手册的五类数据源验收不覆盖所有 Spark/Flink/Trino 批任务。

Data Agent 用自然语言调用模型并执行 SQL，是可选能力。启用前需安装 `externals/engines/data-agent` 及其依赖，配置企业模型服务与 JDBC 连接；当前定制镜像只复制 Spark/JDBC Engine，不能直接使用 Data Agent。

配置就绪后，进入 Data Agent 创建对话，填写可访问的 JDBC URL/模型，先采用 STRICT 模式逐条确认工具 SQL，再输入只读查询需求。NORMAL 通常要求确认变更类工具，AUTO_APPROVE 会自动执行；输出应核对实际 SQL 和结果。模型密钥按企业秘密管理方式注入其支持的配置来源，不能假定任意配置项都支持本手册凭据密钥专用的 `env:` 语法。详见 [Data Agent 配置及界面说明](../quick_start/quick_start_with_data_agent.md)。

## 13. 日常运维、备份和升级

### 13.1 每日巡检

检查 Pod 重启次数、PVC 可用空间、Overview 数据新鲜度和异常项、执行队列积压、Engine 拉起失败、REST 失败和 GC；结合 SQL Record 处理异常长查询。核对身份源连接、Secret/证书/临时 Token 到期时间，并检查最近管理员配置变更的审计记录。

常用只读命令：

```bash
kubectl -n spark get pods -o wide
kubectl -n spark get pvc
kubectl -n spark get events --sort-by=.metadata.creationTimestamp
kubectl -n spark logs deployment/kyuubi --tail=200
kubectl -n spark describe deployment kyuubi
```

### 13.2 持久化和备份清单

|             对象             |                                         本文可写配置部署方式的路径                                          |            要求            |
|----------------------------|------------------------------------------------------------------------------------------------|--------------------------|
| 数据源/SQL 规则/存储凭据库           | `/opt/kyuubi/data/digiwin-datasources.db`                                                      | 数据库一致性备份                 |
| 默认配置和会话模板                  | `/opt/kyuubi/conf/kyuubi-defaults.conf`、`kyuubi-session-*.conf`                                | 与身份/权限文件同期备份             |
| 身份源与绑定                     | `/opt/kyuubi/conf/kyuubi-identity-access.json`                                                 | 不丢失管理员绑定                 |
| 角色配置                       | `/opt/kyuubi/conf/kyuubi-admin-permissions.json`                                               | 与授权绑定同步                  |
| 数据源加密主密钥                   | `/opt/kyuubi/conf/kyuubi-defaults.conf` 中的 `kyuubi.digiwin.datasource.credential.secret`       | 必须可恢复，数据库备份不能替代密钥备份      |
| 管理审计、Audit Log 配置与 JSON 事件 | `/opt/kyuubi/data/audit`、`/opt/kyuubi/conf/kyuubi-audit-config.json`、`/opt/kyuubi/data/events` | 按企业保留期归档；Kafka 数据在外部集群备份 |
| Overview / SQL Record      | 进程内存                                                                                           | 重启消失，不能靠 PVC 恢复          |

运行中不要只复制 SQLite 主文件而忽略 WAL/事务一致性。使用存储一致性快照，或在有 sqlite3 客户端且有权限的维护环境运行 SQLite 在线备份（示例路径）：

```bash
sqlite3 /opt/kyuubi/data/digiwin-datasources.db \
  ".backup '/backup/digiwin-datasources-20260916.db'"
```

镜像未保证提供 sqlite3，须使用已准备好的维护环境；无在线备份工具时在停写/停止服务后对 PVC 做完整备份，并同期备份配置文件。备份复制到 PVC 之外的受控存储，并实际做一次恢复演练。

恢复顺序：停止写入 → 恢复数据库、配置、身份和角色文件 → 恢复同一加密密钥及环境 Secret → 检查 UID 10009 读写权限 → 检查 bootstrap 是否会覆盖恢复数据 → 启动 → 验证管理员登录和五类数据源读取。

### 13.3 密码轮换

JDBC 数据库密码通过页面更新数据源；绑定的 S3 凭据通过凭据页面更新；LDAP/IAM 服务凭据按企业认证配置更新后重启。当前方案不启用数据源 bootstrap 模式。

数据库密码、S3 Key 的轮换与注册库加密主密钥的轮换不同。当前没有一键重新加密全部存量凭据的迁移功能，不要直接替换 16 字节主密钥。

### 13.4 重启、升级和回滚

1. 记录当前镜像标签/摘要、实际配置版本、加密主密钥配置版本及 PVC；完成一致性备份。
2. 通知业务并排空查询；当前 Spark Driver 在 Kyuubi Pod 内，重建 Pod 会中断它及相关会话。
3. 将新镜像发布到目标节点/仓库，更新部署用清单；按新增版本说明核查库表和配置兼容性。
4. apply 后等待 rollout 完成，核对 UI/API 版本、认证状态及第 15 节冒烟验收。
5. 失败时恢复上一个镜像；有不兼容状态变化时还要恢复对应备份，不能只回滚镜像。

```bash
kubectl -n spark rollout history deployment/kyuubi
kubectl -n spark rollout restart deployment/kyuubi
kubectl -n spark rollout status deployment/kyuubi --timeout=300s
# 回滚前确认状态存储与前一版本兼容
kubectl -n spark rollout undo deployment/kyuubi
```

不要删除 PVC 作为常规排障手段。复用镜像标签配合 `IfNotPresent` 可能继续运行旧镜像，发布时使用新标签/摘要，并检查 Pod 实际 imageID。

## 14. 故障排查

先记录时间、用户名、数据源标签、Session/Operation ID、请求路径和错误原文；分享日志前去除密码和 Token。

|                      现象                       |                       重点检查                       |                                          处理及复验                                           |
|-----------------------------------------------|--------------------------------------------------|------------------------------------------------------------------------------------------|
| 页面 404 / API 404                              | 是否为当前分支镜像；UI `/ui/` 与 REST `/api/v1` 路径；代理路由     | 核对 imageID 和 Server 版本，部署匹配前后端后再请求                                                       |
| `providerId, user, and password are required` | 运行的启用认证 API 是否仍为旧代码                              | 当前启用 API 无需这三个字段；确认新 Server 已实际部署                                                        |
| HTTP 500                                      | Server 对应时间日志、配置目录写权限、数据库及密钥                     | 根据异常修复后复测；不要仅反复刷新页面                                                                      |
| 保存认证/模板失败                                     | `/opt/kyuubi/conf` 是否只读 ConfigMap，UID 权限         | 完成 4.4 的 PVC 可写配置部署                                                                      |
| 认证停用/启用后重启又变回                                 | 是否修改了错误文件；启动是否覆盖 PVC 配置                          | 核对 KYUUBI_CONF_DIR 和配置来源，停止每次启动强制覆盖                                                      |
| 401                                           | 错误企业密码、未绑定、拒绝访问、身份源停用或不可达                        | 管理员核查身份源及绑定，用独立客户端复验                                                                     |
| 403                                           | 已认证但无相应管理权限                                      | 检查 viewer/普通账号与平台管理员角色，不用数据库密码替代                                                         |
| LDAP `127.0.0.1:10389` 不通                     | 这是此前本地测试服务，容器内回环地址不指向宿主机                         | 使用正式目录可达地址；验证查询账号、DN、证书                                                                  |
| IAM 错误密码也成功                                   | 适配服务是否失败仍返回 HTTP 200                             | 失败改用非 2xx，再验收错误密码拒绝                                                                      |
| `Failed to load driver class`                 | Server 和 JDBC Engine 两套 classpath                | 将相同版本驱动纳入两者，重建镜像，再页面测试及 Beeline 查询                                                       |
| `Datasource label ... not found/disabled`     | 标签拼写、注册中心开关、初始化、停用状态                             | 查询数据源列表确认；检查 URL 参数是否位于 `?` 段                                                            |
| Pod 启动时报环境变量缺失                                | bootstrap 引用的所有变量、Secret 名与 namespace            | 完整注入，或移除不用的数据源初始化段落                                                                      |
| 重启后凭据解密失败                                     | 加密密钥变化/丢失或此前用了随机密钥                               | 恢复原主密钥；无法恢复时重新录入对应外部凭据                                                                   |
| Iceberg 能列表但 SELECT 失败                        | HMS 已通，但 S3 凭据、对象权限、Endpoint/DNS 或 Executor 依赖异常 | 用非空表读取验证；检查 Driver 和 Executor 两端                                                         |
| AWS environment credentials 报错                | 没绑定存储凭据且进程无 AWS 环境变量                             | 绑定正确凭据，或给 Driver/Executor 同时注入变量，再建新会话                                                   |
| Oracle ORA-00942                              | 实际表 owner、大小写和权限                                 | 查询 all_tables 并使用正确 schema；当前案例为 C##FLINKUSER                                            |
| PG relation does not exist                    | 数据库、schema、表名、大小写                                | 查 information_schema；不要混淆 gjc_test_cdc 与 gjc_test_cdc_1                                  |
| ImagePullBackOff                              | 镜像未导入对应节点，仓库认证或平台架构不匹配                           | 检查 describe pod 事件；分发 Server/Executor 各自镜像                                               |
| PVC Pending / Pod Pending                     | StorageClass、容量、卷绑定/节点亲和性或 ResourceQuota         | 核查 PVC 和调度事件，不反复删除卷                                                                      |
| Executor 创建 Forbidden                         | spark-sa 的 namespace 和 RBAC                      | 用 `kubectl auth can-i create pods --as=system:serviceaccount:spark:spark-sa -n spark` 核查 |
| 执行队列持续增长                                      | 下游慢、线程繁忙、引擎启动慢、配额不足                              | 对照 SQL/Engine/Pod 事件定位；不要只增加队列长度                                                         |
| `SQL_BLOCKED`                                 | 命中的规则、用户/引擎作用域及豁免                                | 查看 sql_blocked 事件，按审批修改规则，不要求用户盲目重试                                                      |
| Overview 全零/历史缺失                              | 刚重启、无采样、访问了另一 Server、指标缺失                        | 检查数据状态和当前实例，提交测试查询后按采样周期复核                                                               |
| 改数据源后仍连旧地址                                    | bootstrap 覆盖页面配置，或复用既有 JDBC Engine               | 统一配置来源，维护窗口回收旧连接/引擎后复测                                                                   |

## 15. 上线验收与交接

以下是待执行的验收标准，执行人应填写日期、镜像摘要、环境和证据，不能直接当作已经通过的结果。

|   验收项   |                       步骤                        |                      通过标准                      |
|---------|-------------------------------------------------|------------------------------------------------|
| 制品      | 从本次发布源码完整构建，检查 Server/Engine 驱动                 | 构建成功、版本明确、无旧新 JAR 混用                           |
| 安装      | 全新部署并查看 Pod/PVC/日志/UI                           | 服务启动，卷已绑定，页面与接口可达                              |
| 配置持久化   | 保存模板/授权/测试数据源后重建 Pod                            | 配置仍存在，主密钥未变，解密正常                               |
| 企业认证    | 正确密码、错误密码、不存在用户、未绑定用户分别登录                       | 正确绑定账号成功；其余被拒；Web 和 Beeline 都覆盖                |
| 管理权限    | 平台管理员、viewer、普通账号分别访问读写接口                       | 管理员可管理；viewer 写操作拒绝；普通账号不能越权                   |
| 认证启停    | 保存启用→重启→校验；保存停用→重启→校验                           | 状态和真实登录行为一致；恢复目标认证状态                           |
| 四类 JDBC | 页面创建/测试 → Beeline 连标签 → 查询真实授权表                 | StarRocks、MySQL、PG、Oracle 分别读取到预期数据；与数据库直查结果一致 |
| Iceberg | 页面配置 → Beeline 查元数据 → 读非空表 → 测试 namespace 写入并读取 | Driver/Executor 均成功访问 HMS/S3；写入查询一致；清理有记录      |
| 数据源隔离   | 同一用户依次连接多个标签，对照数据库身份和数据                         | 不误连前一个数据源；改配置后的新会话确实生效                         |
| 凭据轮换    | 修改测试数据源密码/S3 凭据并新建会话                            | 新凭据生效，页面/接口不回显密钥；bootstrap 不回写旧值               |
| 停用与撤权   | 停用标签、拒绝绑定、移除凭据引用                                | 新连接按策略拒绝；存量会话影响已记录并处理                          |
| 指标正确性   | 固定 Server，分别做成功/失败/并发查询，对照 API/metrics/记录       | 同口径增量一致，队列变化可解释，重启清空符合设计                       |
| 查询管理    | 取消测试长查询、关闭 Session                              | 业务查询停止/资源释放，其他用户无意外影响                          |
| SQL 拦截  | 专用测试对象验证命中/放行和动态修改                              | 命中阻断、正常 SQL 放行，拦截事件可查                          |
| 限额/超时   | 达到连接阈值，再增加连接；执行超时查询                             | 超额拒绝、释放后可连接；超时按预期取消                            |
| 故障恢复    | 身份源/数据库短时不可达后恢复；Pod 重启                          | 错误清晰，无凭据泄漏，恢复后新连接可用                            |
| 备份恢复    | 将备份恢复到隔离环境                                      | 管理员可登录、数据源和模板完整、凭据可解密                          |
| 升级回滚    | 新镜像升级及回退演练                                      | 版本明确，配置/状态兼容，数据源可再次查询                          |

上线交接至少提供：镜像标签及摘要、部署清单、访问地址、管理员联系人、身份源责任人、数据源标签与权限范围、Secret 保管位置、备份位置与恢复记录、告警入口、未通过项及限制。当前 MySQL 缺真实测试端点、新镜像未完成集群验收，必须补齐后再签署“五类数据源可用”。

## 16. REST 接口速查与资料

`$API` 指网关 REST 根地址；管理调用使用平台管理员认证。PUT 整体替换类接口调用前先 GET 并保留已有内容，避免误删其他配置。

|           用途           |                                                   方法和路径                                                   |
|------------------------|-----------------------------------------------------------------------------------------------------------|
| 监控概览 / 趋势              | `GET /api/v1/overview/summary`、`GET /api/v1/overview/trend?range=1h`                                      |
| SQL 记录 / 详情            | `GET /api/v1/sql-records`、`GET /api/v1/sql-records/{id}`                                                  |
| 数据源列表 / 新增             | `GET/POST /api/v1/datasources`                                                                            |
| 数据源查看 / 修改 / 删除        | `GET/PUT/DELETE /api/v1/datasources/{label}`                                                              |
| 草稿 / 已保存连接测试           | `POST /api/v1/datasources/test`、`POST /api/v1/datasources/{label}/test`                                   |
| 刷新数据源缓存                | `POST /api/v1/datasources/refresh`                                                                        |
| 存储凭据                   | `GET/POST /api/v1/datasources/credentials`；`PUT/DELETE /api/v1/datasources/credentials/{id}`              |
| 身份源及绑定视图               | `GET /api/v1/admin/access`                                                                                |
| 身份源保存 / 删除             | `PUT /api/v1/admin/access/providers`；`DELETE /api/v1/admin/access/providers/{id}`                         |
| 身份源测试 / 目录             | `POST .../providers/{id}/test`；`GET .../providers/{id}/subjects`（前缀 `/api/v1/admin/access`）               |
| 授权保存 / 解除              | `PUT /api/v1/admin/access/bindings`；`DELETE /api/v1/admin/access/bindings/{id}`                           |
| 认证启用 / 停用              | `POST /api/v1/admin/access/activate`、`POST /api/v1/admin/access/deactivate`；无需账号密码请求体                     |
| 策略 / 权限                | `GET/PUT /api/v1/admin/policies`、`GET/PUT /api/v1/admin/permissions`                                      |
| 系统快照 / 管理审计            | `GET /api/v1/admin/configuration`、`GET /api/v1/admin/audit`                                               |
| 原生 Audit Log 配置        | `GET/PUT /api/v1/admin/event-audit/config`、`POST /api/v1/admin/event-audit/test`                          |
| 原生 Audit Log 查询        | `GET /api/v1/admin/event-audit/events`                                                                    |
| 管理 Session / Operation | `GET /api/v1/admin/sessions`、`GET /api/v1/admin/operations`                                               |
| 关闭 Session             | `DELETE /api/v1/admin/sessions/{id}`                                                                      |
| 取消 / 关闭 Operation      | `PUT /api/v1/operations/{id}`，action 为 `CANCEL` / `CLOSE`                                                 |
| SQL 规则                 | `GET/POST /api/v1/sql-rules`；`GET/PUT/DELETE /api/v1/sql-rules/{id}`；`POST /api/v1/sql-rules/refresh`     |
| 刷新名单 / 用户默认值           | `POST /api/v1/admin/refresh/{type}`，type 为 `deny_users`、`deny_ips`、`unlimited_users`、`user_defaults_conf` |

实现核对依据：`DatasourceRegistry`、`DatasourceConfAdvisor`、`DatasourceConnectionTester`、`AdminAccessResource`、`IdentityDirectoryService`、`OverviewResource`、`OverviewMetrics`、`SqlExecutionRecordStore`、`AuditRecordStore`、`ManagedAuditEventService` 以及当前前端路由/菜单。

其他资料：

- [分支功能实现说明](admin-governance-branch-features.md)：开发与历史验证记录，版本统计以文内日期为准。
- [数据源专题](datasource-usage.md)：更多数据源 API 和既有联调案例。
- [Kubernetes 专题](kyuubi-on-k8s.md)：镜像、Spark Executor 和部署背景。
- [SQL 拦截专题](sql-inspection-usage.md)、[连接限额专题](rate-limit-usage.md)、[审计专题](audit-usage.md)。
- [完整配置参考](../configuration/settings.md)、[REST API](../client/rest/rest_api.md)。

旧专题中的个人路径、测试 IP、明文示例密钥、内部存储可直接替换数据库等描述不作为生产部署依据；安装和操作顺序以本手册及当前源码为准。
