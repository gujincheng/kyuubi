# Kyuubi on Kubernetes 部署指南

本文记录 digiwin 定制版 Kyuubi(分支 `digiwin-1.12.0`,含 digiwin-plugins)在 K8s 上的构建、部署与运维方法。对接集群内 Spark on K8s(3.5.8)+ Iceberg(1.9.0)。

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
| 对象存储 + secret `aws-creds`                      | Iceberg warehouse(s3a://iceberg/) | SeaweedFS,s3.seaweedfs.local:30080 |
| K8s API 可达                                     | 提交/管理 executor                    | 集群内 in-cluster 天然满足                |

换一个全新集群部署时,需先准备以上五项(其中镜像可参照既有 spark-iceberg 的构建方式:Spark 3.5.8 + iceberg-spark-runtime 1.9.0 + hadoop-aws + aws-sdk-bundle)。

### 两个镜像的角色(常见误解澄清)

本方案需要**两个镜像**,缺一不可,executor 用的不是 kyuubi 镜像:

|           镜像            |        谁用         |                                                  干什么                                                   |
|-------------------------|-------------------|--------------------------------------------------------------------------------------------------------|
| `kyuubi:1.12.0-digiwin` | Kyuubi Pod(1 个)   | 跑 Kyuubi server;server 用镜像内(继承自底座的)`/opt/spark` 执行 spark-submit,driver 以子进程跑在同一 Pod 内                  |
| `spark-iceberg:3.5.8`   | executor Pod(N 个) | driver 向 API server 申请 executor 时,按 `spark-defaults.conf` 中 `spark.kubernetes.container.image` 指定的镜像拉起 |

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

# 1. mysql 驱动放入 jdbc 引擎目录(随 COPY 进镜像,引擎 classpath 自动加载,
#    省去 kyuubi.engine.jdbc.extra.classpath 配置)
wget -P externals/engines/jdbc/ \
  https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar

# 2. 构建
docker build -f docker/Dockerfile.digiwin -t kyuubi:1.12.0-digiwin .

# 3. 导入 containerd 的 k8s.io 命名空间(kubelet 只认这里;同名替换先 rm)
docker save kyuubi:1.12.0-digiwin -o /tmp/kyuubi-1.12.0-digiwin.tar
ctr -n k8s.io images rm kyuubi:1.12.0-digiwin 2>/dev/null
ctr -n k8s.io images import /tmp/kyuubi-1.12.0-digiwin.tar
crictl images | grep kyuubi
```

镜像为节点本地镜像,只存在于构建节点。多节点调度需先分发(`ctr images export` + 各节点 `import`),或使用 `kyuubi.yaml` 中的 `nodeSelector` 固定节点。

## 三、部署

```bash
kubectl apply -f kyuubi.yaml
kubectl -n spark get pods -w
kubectl -n spark logs deploy/kyuubi | tail -20
```

`kyuubi.yaml` 关键设计:

|         项          |                                             说明                                             |
|--------------------|--------------------------------------------------------------------------------------------|
| namespace          | `spark`,与 executor、secret、SA 同域                                                            |
| serviceAccountName | 复用 `spark-sa`(已有 pods/services 权限),driver 建 executor 用                                     |
| master             | `k8s://https://kubernetes.default.svc:443`,in-cluster + SA token,无需 kubeconfig             |
| envFrom            | secret `aws-creds`,driver 读写 s3a 的凭证(executor 由 spark.kubernetes.executor.secretKeyRef 注入) |
| conf 挂载            | `/opt/kyuubi/conf` 整目录;`spark-defaults.conf`、`hive-site.xml` subPath 单文件挂载                 |
| 内嵌 ZK              | 不配 `kyuubi.ha.addresses`,引擎(同 Pod 子进程)经 localhost 注册/发现                                    |
| 端口                 | thrift binary 10009 → NodePort 30009;REST 10099 → NodePort 30099                           |

注意:hive-site.xml 必须显式挂载——它**不在** spark-iceberg 镜像里(原 thrift-server 也是 ConfigMap 挂载的),缺了引擎会回退本地 Derby,元数据只剩 default。

## 四、验证

```bash
beeline -u "jdbc:hive2://<节点IP>:30009/"
```

```sql
SHOW DATABASES;
CREATE TABLE spark_catalog.default.kyuubi_k8s_it (id INT) USING iceberg;
INSERT INTO spark_catalog.default.kyuubi_k8s_it VALUES (1);
SELECT * FROM spark_catalog.default.kyuubi_k8s_it;
```

`kubectl -n spark get pods` 应出现 `kyuubi-exec-*`。

## 五、配置变更与重启

- 改 `kyuubi.yaml` 中任何配置后:`kubectl apply -f kyuubi.yaml && kubectl -n spark delete pod -l app=kyuubi`(ConfigMap/subPath 均不热更新)。
- 只改 Spark 侧参数时,重建引擎即可(杀掉 Pod 内引擎进程或重建 Pod);USER share level 下旧引擎不会自动应用新配置。

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

- datasource 注册的 SQLite 存于 Pod `/tmp`,Pod 重建即丢:生产需挂 PVC 或改 MySQL 存储。
- `kyuubi.yaml` 中 `kyuubi.digiwin.datasource.credential.secret` 为占位值,生产替换为强随机 16 字节密钥(更换后需重录已注册数据源的凭据)。
- 多副本 HA:外加 ZooKeeper 并配置 `kyuubi.ha.addresses`,同时去掉 nodeSelector 并分发镜像(或搭 registry)。
- spark-thrift-server 与 Kyuubi 功能重叠,客户端迁移完成后可退役。
