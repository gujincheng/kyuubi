/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.api.v1

object OverviewHealthEvaluator {

  val SQL_FAILURE_WARNING = 5.0
  val SQL_FAILURE_CRITICAL = 10.0
  val SQL_P95_WARNING_MS = 5000.0
  val SQL_P95_CRITICAL_MS = 30000.0
  val QUEUE_WARNING = 1.0
  val QUEUE_CRITICAL = 10.0
  val ENGINE_FAILURE_WARNING = 1.0
  val ENGINE_FAILURE_CRITICAL = 5.0
  val ENGINE_WAITING_WARNING = 1.0
  val ENGINE_WAITING_CRITICAL = 5.0
  val REST_FAILURE_WARNING = 1.0
  val REST_FAILURE_CRITICAL = 5.0
  val HEAP_WARNING = 0.75
  val HEAP_CRITICAL = 0.9
  val DEADLOCK_CRITICAL = 1.0
  val METADATA_FAILURE_WARNING = 1.0
  val METADATA_FAILURE_CRITICAL = 5.0
  val SSL_EXPIRATION_WARNING_MS = 30L * 24 * 60 * 60 * 1000
  val SSL_EXPIRATION_CRITICAL_MS = 7L * 24 * 60 * 60 * 1000

  def evaluate(
      operations: OverviewOperations,
      execPool: OverviewExecPool,
      engineHealth: OverviewEngineHealth,
      accessHealth: OverviewAccessHealth,
      runtimeHealth: OverviewRuntimeHealth,
      metadataHealth: OverviewMetadataHealth,
      dataStatus: OverviewDataStatus,
      now: Long): OverviewHealth = {
    val issues = Seq(
      thresholdIssue(
        "SQL_FAILURE_RATE_HIGH",
        "WARNING",
        "kyuubi.operation.failed.ExecuteStatement",
        operations.failureRate,
        SQL_FAILURE_WARNING,
        "SQL 失败率超过警告阈值",
        value => value >= SQL_FAILURE_WARNING),
      thresholdIssue(
        "SQL_FAILURE_RATE_CRITICAL",
        "CRITICAL",
        "kyuubi.operation.failed.ExecuteStatement",
        operations.failureRate,
        SQL_FAILURE_CRITICAL,
        "SQL 失败率超过严重阈值",
        value => value >= SQL_FAILURE_CRITICAL),
      thresholdIssue(
        "SQL_P95_LATENCY_HIGH",
        "WARNING",
        "kyuubi.operation.exec_time.ExecuteStatement",
        operations.latency.p95,
        SQL_P95_WARNING_MS,
        "SQL P95 延迟超过警告阈值",
        value => value >= SQL_P95_WARNING_MS),
      thresholdIssue(
        "SQL_P95_LATENCY_CRITICAL",
        "CRITICAL",
        "kyuubi.operation.exec_time.ExecuteStatement",
        operations.latency.p95,
        SQL_P95_CRITICAL_MS,
        "SQL P95 延迟超过严重阈值",
        value => value >= SQL_P95_CRITICAL_MS),
      thresholdIssue(
        "EXECUTION_QUEUE_WAITING",
        "WARNING",
        "kyuubi.exec.pool.work_queue.size",
        execPool.waiting,
        QUEUE_WARNING,
        "执行队列存在等待任务",
        value => value >= QUEUE_WARNING),
      thresholdIssue(
        "EXECUTION_QUEUE_BACKLOG",
        "CRITICAL",
        "kyuubi.exec.pool.work_queue.size",
        execPool.waiting,
        QUEUE_CRITICAL,
        "执行队列出现严重积压",
        value => value >= QUEUE_CRITICAL),
      thresholdIssue(
        "ENGINE_START_FAILED",
        "WARNING",
        "kyuubi.engine.failed",
        engineHealth.failed,
        ENGINE_FAILURE_WARNING,
        "Engine 启动失败",
        value => value >= ENGINE_FAILURE_WARNING),
      thresholdIssue(
        "ENGINE_START_TIMEOUT",
        "WARNING",
        "kyuubi.engine.timeout",
        engineHealth.timeout,
        ENGINE_FAILURE_WARNING,
        "Engine 启动超时",
        value => value >= ENGINE_FAILURE_WARNING),
      thresholdIssue(
        "ENGINE_START_WAITING",
        "WARNING",
        "kyuubi.engine.startup.permit.waiting",
        engineHealth.waiting,
        ENGINE_WAITING_WARNING,
        "Engine 启动许可存在等待",
        value => value >= ENGINE_WAITING_WARNING),
      thresholdIssue(
        "REST_FAILURE_RATE_HIGH",
        "WARNING",
        "kyuubi.rest.connection.failed",
        accessHealth.failureRate,
        REST_FAILURE_WARNING,
        "REST 请求失败率超过警告阈值",
        value => value >= REST_FAILURE_WARNING),
      thresholdIssue(
        "REST_FAILURE_RATE_CRITICAL",
        "CRITICAL",
        "kyuubi.rest.connection.failed",
        accessHealth.failureRate,
        REST_FAILURE_CRITICAL,
        "REST 请求失败率超过严重阈值",
        value => value >= REST_FAILURE_CRITICAL),
      thresholdIssue(
        "JVM_HEAP_HIGH",
        "WARNING",
        "kyuubi.memory_usage.heap.usage",
        runtimeHealth.heapUsage,
        HEAP_WARNING,
        "JVM 堆内存使用率超过警告阈值",
        value => value >= HEAP_WARNING),
      thresholdIssue(
        "JVM_HEAP_CRITICAL",
        "CRITICAL",
        "kyuubi.memory_usage.heap.usage",
        runtimeHealth.heapUsage,
        HEAP_CRITICAL,
        "JVM 堆内存使用率超过严重阈值",
        value => value >= HEAP_CRITICAL),
      thresholdIssue(
        "JVM_DEADLOCK",
        "CRITICAL",
        "kyuubi.thread_state.deadlock.count",
        runtimeHealth.deadlockCount,
        DEADLOCK_CRITICAL,
        "检测到 JVM 线程死锁",
        value => value >= DEADLOCK_CRITICAL),
      thresholdIssue(
        "METADATA_FAILURE_RATE_HIGH",
        "WARNING",
        "kyuubi.metadata.request.failed",
        metadataHealth.failureRate,
        METADATA_FAILURE_WARNING,
        "Metadata 请求失败率超过警告阈值",
        value => value >= METADATA_FAILURE_WARNING),
      thresholdIssue(
        "METADATA_FAILURE_RATE_CRITICAL",
        "CRITICAL",
        "kyuubi.metadata.request.failed",
        metadataHealth.failureRate,
        METADATA_FAILURE_CRITICAL,
        "Metadata 请求失败率超过严重阈值",
        value => value >= METADATA_FAILURE_CRITICAL),
      runtimeHealth.sslCertExpirationMs.map { expirationMs =>
        if (expirationMs <= SSL_EXPIRATION_CRITICAL_MS) {
          Some(OverviewHealthIssue(
            "SSL_CERT_EXPIRING",
            "CRITICAL",
            "kyuubi.thrift.ssl.cert.expiration",
            expirationMs.toDouble,
            SSL_EXPIRATION_CRITICAL_MS.toDouble,
            "Thrift SSL 证书即将过期"))
        } else if (expirationMs <= SSL_EXPIRATION_WARNING_MS) {
          Some(OverviewHealthIssue(
            "SSL_CERT_EXPIRING",
            "WARNING",
            "kyuubi.thrift.ssl.cert.expiration",
            expirationMs.toDouble,
            SSL_EXPIRATION_WARNING_MS.toDouble,
            "Thrift SSL 证书将在 30 天内过期"))
        } else None
      }.getOrElse(None),
      if (dataStatus.stale) {
        Some(OverviewHealthIssue(
          "METRICS_STALE",
          "CRITICAL",
          "overview.data_status.age_ms",
          dataStatus.ageMs.toDouble,
          dataStatus.sampleIntervalMs.toDouble,
          "Overview 指标数据已停止更新"))
      } else None).flatten

    val status = if (issues.exists(_.severity == "CRITICAL")) {
      "CRITICAL"
    } else if (issues.nonEmpty) {
      "WARNING"
    } else if (dataStatus.lastSampleAt == 0L) {
      "UNKNOWN"
    } else {
      "NORMAL"
    }
    OverviewHealth(status, now, issues)
  }

  private def thresholdIssue(
      code: String,
      severity: String,
      metric: String,
      value: Double,
      threshold: Double,
      message: String,
      matches: Double => Boolean): Option[OverviewHealthIssue] = {
    if (matches(value)) {
      Some(OverviewHealthIssue(code, severity, metric, value, threshold, message))
    } else None
  }
}
