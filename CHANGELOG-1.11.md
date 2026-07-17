# Kyuubi v1.10.2 → v1.11.1 变更清单

> 基于 git 提交记录（v1.10.2..v1.11.1，共 280 个提交）整理。

## 一、重要里程碑

- **v1.11.0**：支持 Spark 4.0/4.1、Java 21、Scala 2.13 全面适配
- **v1.11.1**：bug 修复与依赖升级版本

---

## 二、新功能（New Features）

### 1. 引擎版本支持
- **Spark 4.0/4.1 全面支持**（#6920, #7174, #8baaba56b, #7275）
- TPC-H/TPC-DS connector 兼容 Spark 4.2（#7350）
- **移除 Spark 3.2 支持**（#7198）
- **废弃 Flink 1.17/1.18**（#7199）

### 2. Engine / Session
- 新增 `SERVER_LOCAL` 引擎共享级别（#6926）
- 支持 batch 重新分配到其他 Kyuubi 实例（实例丢失时容灾）（#6884）
- 支持等待 batch 恢复应用提交以做负载限流（#7226）
- 支持自定义 session 协议版本以支持 binary 类型（#7217）
- Spark engine 支持 session 级别 idle timeout 阈值（#7158）
- 共享引擎模式下尊重客户端设置的 `initialize.sql`（#7138）
- 引入 RuleFunctionAuthorization 用于持久化函数调用授权（#7186）
- 支持 HBase delegation token 更新（#7156）
- 新增 UUID v7 生成器（#7277）
- 支持异步 post event（#7281）
- **JDBC Engine 支持 Oracle**（#6815）

### 3. 授权（AUTHZ）
- Paimon 支持 DELETE FROM / UPDATE / MERGE INTO（#6973）
- Iceberg 支持 partition field check（#7065）、branch/tag DDL（#7068）、Alter Table 命令（#7099）
- 支持 Paimon system producers 检查（#6979）

### 4. 血缘（LINEAGE）
- 按 plan 收集所有 input tables（#7183）
- row level catalog 支持 merge into 语法（#7126）
- 自适应 authz plugin 引入的 PermanentViewMarker（#7168）

### 5. Spark 扩展
- 支持 PARQUET / ORC hive table pushdown filter（#7129, #7122）
- Spark 3.3/3.4/3.5: MaxScanStrategy 支持 DSv2（#6862, #6857, #7077）
- InsertIntoHiveDirCommand 前增加 rebalance（#2080c2186）
- 基于扫描表大小计算 join 分区数（#6989）

### 6. Kubernetes / Helm
- 实现全新 Helm 配置方案（#6521）
- 支持 Hadoop 配置文件（#6875）
- 支持 PrometheusRule / Pod 额外标签（#7105, #7098）
- 支持 rolling `spark.kubernetes.file.upload.path`（#6876）
- Kyuubi server 启动时初始化 k8s clients（#7027）

### 7. 监控 / 指标
- 新增 Grafana dashboard 模板（#5834）
- SSL keystore 过期时间、batch pending max elapse、engine startup permit 等指标（#6866, #6829, #7072）
- Prometheus metrics 支持 instance label（#6864）

### 8. 其他
- 支持 trino stage progress（#6726）
- 支持 spark app url 模式 `http://{{SPARK_DRIVER_POD_IP}}:{{SPARK_UI_PORT}}`（#7141）

---

## 三、Bug 修复（重要）

| 编号 | 修复内容 |
|------|----------|
| #7317 | parsePropertyFromUrl 未去除 query string |
| #7304 | session idle timeout 解析 ISO 8601 duration 时 NumberFormatException |
| #7290 | Kyuubi session 关闭后 engine session 泄漏 |
| #7245 | arrow batch converter 报错 |
| #7248 | JDBC engine 收到 cancel 操作时未取消 statement |
| #7229 | countMetadata 参数为空时出错 |
| #7219 | swagger openapi.json security items 死循环 |
| #7214 | kubernetes container state 错误 |
| #7190 | Presto SQLAlchemy dialect 未实现 get_view_names |
| #7171 | etcd 作为服务注册时 engine 结果列表为空 |
| #7148 | spark.kubernetes.file.upload.path 权限问题 |
| #7135 | 无法访问 /tmp/engine-archives |
| #7110 | serverOnlyPrefixConfigKeys iterator 问题 |
| #7051 | JDBC driver 无 sslTrustStore 时使用报错 |
| #7048 | schema inspection 解析未知 Hive type_id 时 KeyError |
| #7041 | KubernetesApplicationOperation 获取 metadata manager 时 NPE |
| #6828 | KyuubiBaseResultSet::getBigDecimal NPE |
| #7026 | k8s pod DELETE 事件处理逻辑错误 |
| #6984 | 渲染 MapType 数据时 ValueError |
| #6891 | get existing gauge 问题 |
| #6843 | query-timeout-thread 线程泄漏 |
| #6840 | PodMonitor pods 选择错误 |
| #6722 | Engine 连接终止时 AppState 错误 |
| #6790 | engine 优雅停止时无法退出 |
| #7163 | engine terminating checker 未检查 context 是否停止 |
| #6884 | 内部 kyuubi 实例 ping 失败 |

---

## 四、废弃 / 移除

- 移除 Spark 3.2 支持（#7198）
- 废弃 Flink 1.17/1.18（#7199）
- 移除 `spark.sql.watchdog.forcedMaxOutputRows`（#6983）
- 移除直接使用 `sun.misc.Signal`（#7144）

---

## 五、主要依赖升级

- **Spark**：4.0.0 / 4.0.1 / 4.1.1（+ 3.5.4/3.5.5 补丁）
- **Iceberg**：1.6.1 → **1.10.0**
- **Delta**：3.3.0/3.3.1/4.0.0
- **Hudi**：1.0.1
- **Java**：支持 21
- **Scala**：2.13.16/2.13.17
- **Netty**：4.1.128 / 4.2.7
- **Jackson**：2.20.1
- **kubernetes-client**：6.13.5 → 6.14.0
- **Spark Ranger plugin**：升级到 2.6.0（#6924）
- **log4j**：2.24.3（修复 ConcurrentModificationException）
