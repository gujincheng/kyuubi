# Kyuubi on Kubernetes 部署指南

首次部署请先阅读 [安装、使用与运维手册](operations-and-user-manual.md)。当前源码中的 `kyuubi.yaml` 已包含 ConfigMap 种子、hostPath 可写配置目录和首次初始化逻辑，本文保留部署背景及专题说明，不能省略完整手册中的凭据、持久化和验收步骤。

本文记录 digiwin 定制版 Kyuubi(分支 `digiwin-1.12.0`,含 digiwin-plugins)在 K8s 上的构建、部署与运维方法。对接集群内 Spark on K8s(3.5.8)+ Iceberg(1.9.0)，并预置 JDBC Engine 的 MySQL、StarRocks、PostgreSQL、Oracle 驱动。

相关文件:

- `docker/Dockerfile.digiwin` — 镜像构建文件(与官方 Dockerfile 的差异见其头部注释)
- `kyuubi.yaml` — 部署清单(ConfigMap + Deployment + NodePort Service),仓库根目录

## 架构

```
客户端 --JDBC--> NodePort:30009 --> Kyuubi Pod(单副本,内嵌 ZK)
                                      | spark-submit client 模式
                                      v
                                  K8s API(spark-sa 认证)
                                      |
                                      v
                                  executor Pods(spark-iceberg:3.5.8)
                                      |
                                      v
                                  Iceberg(HMS + SeaweedFS s3a)
```

要点:Kyuubi 是引擎启动器而非 Spark 客户端,driver(SparkSQLEngine)以 client 模式跑在 Kyuubi Pod 内,因此镜像必须以含完整 Spark 的 `spark-iceberg:3.5.8` 为底座。单副本使用内嵌 ZooKeeper(不配 `kyuubi.ha.addresses` 时自动启动),无外部依赖;多副本才需要外部 ZK。

## 前置依赖

部署 Kyuubi **不需要**预先运行任何常驻 Spark 服务(无 standalone master;executor 由 Kyuubi 按需向 API server 申请,跑完回收)。但以下环境必须就绪:

|                       依赖                       |                用途                 |               本集群现状                |
|------------------------------------------------|-----------------------------------|------------------------------------|
| `spark-iceberg:3.5.8` 镜像(节点本地)                 | ① Kyuubi 镜像的构建底座 ② executor 的启动镜像 | 已在各节点(containerd k8s.io 命名空间)      |
| ServiceAccount `spark-sa` + pods/services RBAC | driver 创建/管理 executor Pod         | 已有(spark-role/spark-rolebinding)   |
| Hive Metastore                                 | Iceberg catalog 元数据               | thrift://172.16.7.137:9083         |
| 数据源管理中的对象存储凭据                                  | Iceberg warehouse(s3a://iceberg/) | SeaweedFS,s3.seaweedfs.local:30080 |
| K8s API 可达                                     | 提交/管理 executor                    | 集群内 in-cluster 天然满足                |

换一个全新集群部署时,需先准备以上五项(其中镜像可参照既有 spark-iceberg 的构建方式:Spark 3.5.8 + iceberg-spark-runtime 1.9.0 + hadoop-aws + aws-sdk-bundle)。

### 两个镜像的角色(常见误解澄清)

本方案需要**两个镜像**,缺一不可,executor 用的不是 kyuubi 镜像:

|                 镜像                  |        谁用         |                                                  干什么                                                   |
|-------------------------------------|-------------------|--------------------------------------------------------------------------------------------------------|
| `kyuubi:1.12.0-digiwin-datasources` | Kyuubi Pod(1 个)   | 跑 Kyuubi server;server 用镜像内(继承自底座的)`/opt/spark` 执行 spark-submit,driver 以子进程跑在同一 Pod 内，并提供 JDBC 数据源驱动   |
| `spark-iceberg:3.5.8`               | executor Pod(N 个) | driver 向 API server 申请 executor 时,按 `spark-defaults.conf` 中 `spark.kubernetes.container.image` 指定的镜像拉起 |

executor 镜像由该配置项决定,与 Kyuubi 自身镜像无关。理论上可以把 executor 也指向 kyuubi 镜像(它 FROM spark-iceberg,/opt/spark 都在),但不建议:kyuubi 镜像只存在于构建节点,executor 调度到其他节点会 ImagePullBackOff;且职责分离后 Kyuubi 升级重建不影响 executor 侧。

## 一、编译打包

```bash
./build/dist --tgz --web-ui -Pspark-3.5 -DskipTests -Dspotless.check.skip=true
# 产物在仓库根目录:apache-kyuubi-1.12.0-bin-spark-3.5.tgz
```

## 二、构建镜像

在能访问 `spark-iceberg:3.5.8` 底座镜像的构建机上(当前为 ddp2),解压 tgz 后:

```bash
cd apache-kyuubi-1.12.0-bin-spark-3.5

# 1. 将以下驱动放入 jdbc 引擎目录(随 COPY 进镜像,引擎 classpath 自动加载):
#    mysql-connector-j、postgresql、ojdbc8。不要把密码或连接串中的密码放进镜像。
wget -P externals/engines/jdbc/ \
  https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar
wget -P externals/engines/jdbc/ \
  https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.11/postgresql-42.7.11.jar
# Oracle 驱动需从公司 Maven 仓库或授权制品库取得 ojdbc8-*.jar 后放入此目录。

# 2. 构建
docker build -f docker/Dockerfile.digiwin -t kyuubi:1.12.0-digiwin-datasources .

# 3. 导入 containerd 的 k8s.io 命名空间(kubelet 只认这里;同名替换先 rm)
docker save kyuubi:1.12.0-digiwin-datasources -o /tmp/kyuubi-1.12.0-digiwin-datasources.tar
ctr -n k8s.io images rm kyuubi:1.12.0-digiwin-datasources 2>/dev/null
ctr -n k8s.io images import /tmp/kyuubi-1.12.0-digiwin-datasources.tar
crictl images | grep kyuubi
```

上面的命令只导入了 Kyuubi Server 镜像。当前清单还会按
`spark.kubernetes.container.image=spark-iceberg:3.5.8` 创建 Executor，因此必须再导入 Spark
Executor 镜像。短镜像名配合 `IfNotPresent` 时，两个镜像都必须存在于对应节点的 containerd
`k8s.io` 命名空间中：

```bash
docker save spark-iceberg:3.5.8 -o /tmp/spark-iceberg-3.5.8.tar
ctr -n k8s.io images rm spark-iceberg:3.5.8 2>/dev/null
ctr -n k8s.io images import /tmp/spark-iceberg-3.5.8.tar
crictl images | grep -E 'kyuubi|spark-iceberg'
```

将 Kyuubi Server 镜像 tar 和 Spark Executor 镜像 tar 分发到对应节点后，分别执行导入命令。
当前清单使用 `hostPath`，将 Kyuubi Server 固定到 `ddp2`；因此 Kyuubi 镜像必须在 `ddp2`
存在，Spark Executor 镜像必须在所有可能运行 Executor 的节点存在。
也可以改用私有镜像仓库，分别推送两个镜像并在清单中使用完整仓库地址。

## 三、部署

```bash
kubectl apply --dry-run=server -f kyuubi.yaml
kubectl apply -f kyuubi.yaml
kubectl -n spark get pods -w
kubectl -n spark logs deploy/kyuubi | tail -20
```

`kyuubi.yaml` 关键设计:

|         项          |                                                          说明                                                           |
|--------------------|-----------------------------------------------------------------------------------------------------------------------|
| namespace          | `spark`,与 executor、secret、SA 同域                                                                                       |
| serviceAccountName | 复用 `spark-sa`(已有 pods/services 权限),driver 建 executor 用                                                                |
| master             | `k8s://https://kubernetes.default.svc:443`,in-cluster + SA token,无需 kubeconfig                                        |
| envFrom            | 不注入数据源凭据；S3/JDBC 凭据由数据源管理维护，加密主密钥配置在 `kyuubi-defaults.conf`                                                           |
| conf 挂载            | ConfigMap 只作种子；initContainer 初始化 `ddp2:/opt/kyuubi-data`，实际配置从 `/opt/kyuubi/data/conf` 挂载到 `/opt/kyuubi/conf` 和 Spark |
| 内嵌 ZK              | 不配 `kyuubi.ha.addresses`,引擎(同 Pod 子进程)经 localhost 注册/发现                                                               |
| 端口                 | thrift binary 10009 → NodePort 30009;REST 10099 → NodePort 30099                                                      |

Iceberg Catalog 不再写死在 `spark-defaults.conf` 或 `hive-site.xml`。方案 A 不使用数据源
启动初始化文件；部署完成后从数据源管理页面或 REST API
创建 Iceberg、StarRocks、MySQL、PostgreSQL、Oracle 数据源。`DatasourceConfAdvisor` 再按
连接使用的 label 动态注入 HMS、Warehouse、S3 Endpoint、JDBC 驱动和凭据。密码与对象存储
密钥加密保存在 `ddp2:/opt/kyuubi-data` 中的数据源注册库，不进入 ConfigMap、镜像或查询接口。

部署前在 `kyuubi.yaml` 的 `kyuubi-defaults.conf` 中设置固定的数据源加密主密钥：

```properties
kyuubi.digiwin.datasource.credential.secret=<固定的16字节密钥>
```

Kyuubi 启动后，进入 Web UI 的“数据源管理”创建存储凭据和各类业务数据源。该密钥只用于解密注册库中保存的凭据，不保存任何数据库密码或对象存储 Access Key；部署后不能随意修改。

## 四、验证

```bash
bin/kyuubi-beeline \
  -u "jdbc:kyuubi://<节点IP>:30009/?kyuubi.datasource=iceberg-hms" \
  -n alice
```

```sql
SHOW NAMESPACES IN iceberg;
CREATE TABLE iceberg.<获准的测试namespace>.kyuubi_k8s_it (id INT) USING iceberg;
INSERT INTO iceberg.<获准的测试namespace>.kyuubi_k8s_it VALUES (1);
SELECT * FROM iceberg.<获准的测试namespace>.kyuubi_k8s_it;
DROP TABLE iceberg.<获准的测试namespace>.kyuubi_k8s_it;
```

`kubectl -n spark get pods` 应出现 `kyuubi-exec-*`。

## 五、配置变更与重启

- 修改源码 `kyuubi.yaml` 中的种子配置后 apply，已有 hostPath 目录中的同名配置不会被覆盖；需要更新实际运行配置时，应通过页面或维护窗口直接修改 `ddp2:/opt/kyuubi-data` 中的配置文件。
- 配置文件使用 hostPath 的 `subPath` 挂载，修改后执行 `kubectl -n spark rollout restart deployment/kyuubi` 使 Kyuubi 重新加载。
- 只改 Spark 侧参数时，应通过受控引擎管理回收旧引擎并重新连接；USER share level 下旧引擎不会自动应用新配置。重建 Kyuubi Pod 会中断 Pod 内 Driver 和关联会话。

## 六、按连接指定引擎资源(JDBC URL 参数的 `;`/`?`/`#` 大坑)

引擎资源(driver/executor 内存核数、maxExecutors 等)通过 JDBC URL 传给服务端,**必须放在 `?` 段**,放 `;` 段会被驱动静默丢弃(只在客户端本地使用,见 `KyuubiConnection.openSession()`):

```bash
# ✅ 正确(? 段):新建独立资源档引擎(subdomain=big)
beeline -u "jdbc:hive2://<节点IP>:30009/?kyuubi.engine.share.level.subdomain=big;spark.driver.memory=4g;spark.executor.memory=4g;spark.executor.cores=4;spark.kubernetes.executor.limit.cores=4;spark.dynamicAllocation.maxExecutors=20"

# ❌ 错误(; 段):参数根本到不了服务端,且因 USER share level 复用 default 引擎,表现为"配置不生效"
beeline -u "jdbc:hive2://<节点IP>:30009/;spark.executor.memory=4g"
```

URL 三段在驱动中的行为:`;` 路径段仅驱动本地用(认证/协议);`?` 段加 `set:hiveconf:` 前缀发服务端;`#` 段加 `set:hivevar:` 前缀发服务端。

资源分档机制:USER share level 下同用户共享引擎,不同资源参数的连接仍复用第一个引擎;用 `kyuubi.engine.share.level.subdomain=<档名>` 让不同档位各建独立引擎(同一用户可同时持有 default/big 两套),复用某档时 URL 带同名 subdomain 即可。注意 driver 跑在 Kyuubi Pod 内,`spark.driver.memory` 受 Pod resources limits 约束。

## 七、已知无害报错

- `PartialGroupNameException: 'anonymous' no such user` — authentication=NONE 时 Hadoop 在容器内查 anonymous 用户组失败,仅日志噪音;涉及组授权时才需处理。
- `GetPrimaryKeys: feature not supported` — DBeaver 浏览表元数据触发,Spark 无主键概念;JDBC 引擎侧的元数据补齐见 P1 计划。

## 七、遗留事项

- MySQL、StarRocks、PostgreSQL、Oracle 和 Iceberg 的凭据必须先在数据源管理页面创建；页面保存的数据位于 `ddp2:/opt/kyuubi-data`。
- datasource 注册的 SQLite 保存在 `ddp2:/opt/kyuubi-data`，Pod 重建后保留；方案 A 不使用初始化文件，不会在启动时覆盖页面配置。
- 当前 hostPath 方案不支持跨节点高可用；多副本 HA 需要改用共享存储，外加 ZooKeeper 并配置 `kyuubi.ha.addresses`。
- spark-thrift-server 与 Kyuubi 功能重叠,客户端迁移完成后可退役。

