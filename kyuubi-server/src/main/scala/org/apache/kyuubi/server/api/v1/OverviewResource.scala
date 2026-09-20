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

import java.io.File
import javax.ws.rs.{BadRequestException, DefaultValue, GET, Path, Produces, QueryParam}
import javax.ws.rs.core.MediaType

import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry
import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.Utils
import org.apache.kyuubi.config.KyuubiConf.{KYUUBI_CONF_DIR, KYUUBI_CONF_FILE_NAME, KYUUBI_HOME_ENV_VAR_NAME}
import org.apache.kyuubi.engine.EngineType
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_NAMESPACE
import org.apache.kyuubi.ha.client.DiscoveryClientProvider.withDiscoveryClient
import org.apache.kyuubi.ha.client.DiscoveryPaths
import org.apache.kyuubi.metrics.{MetricsConstants, MetricsSystem}
import org.apache.kyuubi.server.{OverviewMetrics, OverviewTrend}
import org.apache.kyuubi.server.api.ApiRequestContext

@Tag(name = "Overview")
@Path("overview")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class OverviewResource extends ApiRequestContext {

  private val sqlOperationTypes = Seq("ExecuteStatement")
  private val batchOperationType = "BatchJobSubmission"
  private val supportedTrendRanges = Set("1h", "1d", "7d")

  @GET
  @Path("summary")
  def summary(): OverviewSummary = {
    val sessionManager = fe.be.sessionManager
    val overviewMetrics = fe.getOverviewMetrics
    val now = System.currentTimeMillis()
    val activeSessions = sessionManager.allSessions().filterNot(_.isForAliveProbe).toSeq
    val engines = EngineType.values.toSeq.sortBy(_.toString).map { engineType =>
      OverviewEngineSummary(
        engineType.toString,
        MetricsSystem.counterValue(
          MetricRegistry.name(MetricsConstants.ENGINE_TOTAL, engineType.toString)).getOrElse(0L))
    }
    // Read each gauge once so the overview is a consistent point-in-time snapshot. In
    // particular, size and waiting intentionally share the same queue metric and must not
    // diverge when a task is submitted between two reads.
    val queueSize = gaugeInt(
      MetricsConstants.EXEC_POOL_WORK_QUEUE_SIZE,
      sessionManager.getWorkQueueSize)
    val activeThreads = gaugeInt(MetricsConstants.EXEC_POOL_ACTIVE, sessionManager.getActiveCount)
    val aliveThreads = gaugeInt(MetricsConstants.EXEC_POOL_ALIVE, sessionManager.getExecPoolSize)
    val execPool = OverviewExecPool(
      size = queueSize,
      active = activeThreads,
      waiting = queueSize,
      alive = aliveThreads)
    val operations = OverviewOperations(
      open = gaugeInt(
        MetricsConstants.OPERATION_OPEN,
        sessionManager.operationManager.getOperationCount),
      running = operationStateCount("running"),
      waiting = operationStateCount("pending"),
      failed = sqlOperationFailureCount(),
      failureRate = failureRate(),
      latency = histogramSummary(MetricsConstants.OPERATION_EXEC_TIME, "ExecuteStatement"))
    val engineHealth = OverviewEngineHealth(
      launching = operationStateCount("running", "LaunchEngine"),
      waiting = gaugeLong(MetricsConstants.ENGINE_STARTUP_PERMIT_WAITING),
      failed = MetricsSystem.counterValue(MetricsConstants.ENGINE_FAIL).getOrElse(0L),
      timeout = MetricsSystem.counterValue(MetricsConstants.ENGINE_TIMEOUT).getOrElse(0L),
      startupLatency = histogramSummary(MetricsConstants.ENGINE_STARTUP_TIME))
    val batchOperationsSnapshot: OverviewBatchOperations = batchOperations()
    val accessHealthSnapshot: OverviewAccessHealth = accessHealth()
    val runtimeHealthSnapshot: OverviewRuntimeHealth = runtimeHealth()
    val metadataHealthSnapshot: OverviewMetadataHealth = metadataHealth()
    val dataStatusSnapshot: OverviewDataStatus = dataStatus(overviewMetrics, now)
    val health = OverviewHealthEvaluator.evaluate(
      operations,
      execPool,
      engineHealth,
      accessHealthSnapshot,
      runtimeHealthSnapshot,
      metadataHealthSnapshot,
      dataStatusSnapshot,
      now)
    OverviewSummary(
      serverStartCount = MetricsSystem.counterValue(MetricsConstants.SERVER_START).getOrElse(0L),
      liveServerCount = liveServerCount(),
      engines = engines,
      profileCount = profileCount(),
      activeUserCount = activeSessions.map(_.user).distinct.size,
      activeSessionCount = gaugeInt(
        MetricsConstants.CONN_OPEN,
        sessionManager.getActiveUserSessionCount),
      execPool = execPool,
      operations = operations,
      engineHealth = engineHealth,
      batchPendingMaxElapse = gaugeLong(MetricsConstants.OPERATION_BATCH_PENDING_MAX_ELAPSE),
      batchOperations = batchOperationsSnapshot,
      health = health,
      dataStatus = dataStatusSnapshot,
      accessHealth = accessHealthSnapshot,
      runtimeHealth = runtimeHealthSnapshot,
      metadataHealth = metadataHealthSnapshot)
  }

  @GET
  @Path("trend")
  def trend(@QueryParam("range") @DefaultValue("1d") range: String): OverviewTrend = {
    val normalizedRange = Option(range).map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("1d")
    if (!supportedTrendRanges.contains(normalizedRange)) {
      throw new BadRequestException(
        s"Unsupported Overview range: $normalizedRange. Supported ranges: 1h, 1d, 7d")
    }
    fe.getOverviewMetrics.trend(normalizedRange)
  }

  private def liveServerCount(): Int = {
    try {
      val conf = fe.getConf
      val serverSpec = DiscoveryPaths.makePath(null, conf.get(HA_NAMESPACE))
      withDiscoveryClient(conf)(_.getServiceNodesInfo(serverSpec).size)
    } catch {
      case NonFatal(_) => 1
    }
  }

  private def profileCount(): Int = {
    val confDirectory = Utils.getPropertiesFile(KYUUBI_CONF_FILE_NAME)
      .map(_.getParentFile)
      .orElse(sys.env.get(KYUUBI_CONF_DIR).map(new File(_)))
      .orElse(sys.env.get(KYUUBI_HOME_ENV_VAR_NAME).map(home => new File(home, "conf")))
    confDirectory.toSeq.flatMap(directory => Option(directory.listFiles()).toSeq.flatten)
      .count(file =>
        file.isFile && file.getName.startsWith("kyuubi-session-") &&
          file.getName.endsWith(".conf"))
  }

  private def operationStateCount(state: String, operationType: String = ""): Long = {
    val operationTypes = if (operationType.nonEmpty) {
      Seq(operationType)
    } else {
      sqlOperationTypes
    }
    operationTypes.map { name =>
      MetricsSystem.meterValue(
        MetricRegistry.name(MetricsConstants.OPERATION_STATE, name, state)).getOrElse(0L)
    }.sum
  }

  private def failureRate(): Double = {
    val total = sqlOperationTypes.map(operationTotalCount).sum
    val failed = sqlOperationFailureCount()
    if (total == 0L) 0.0 else failed.toDouble / total.toDouble * 100
  }

  private def operationTotalCount(operationType: String): Long = {
    MetricsSystem.counterValue(
      MetricRegistry.name(MetricsConstants.OPERATION_TOTAL, operationType)).getOrElse(0L)
  }

  private def batchOperations(): OverviewBatchOperations = {
    val total = operationTotalCount(batchOperationType)
    val failed = MetricsSystem.counterValue(
      MetricRegistry.name(MetricsConstants.OPERATION_FAIL, batchOperationType)).getOrElse(0L)
    OverviewBatchOperations(
      total = total,
      failed = failed,
      failureRate = if (total == 0L) 0.0 else failed.toDouble / total.toDouble * 100,
      pendingMaxElapse = gaugeLong(MetricsConstants.OPERATION_BATCH_PENDING_MAX_ELAPSE))
  }

  private def sqlOperationFailureCount(): Long = {
    sqlOperationTypes.map { operationType =>
      MetricsSystem.counterValue(
        MetricRegistry.name(MetricsConstants.OPERATION_FAIL, operationType)).getOrElse(0L)
    }.sum
  }

  private def accessHealth(): OverviewAccessHealth = {
    val requestCount = MetricsSystem.counterValue(MetricsConstants.REST_CONN_TOTAL).getOrElse(0L)
    val failedRequests = MetricsSystem.counterValue(MetricsConstants.REST_CONN_FAIL).getOrElse(0L)
    val failureRate = if (requestCount == 0L) 0.0
    else {
      failedRequests.toDouble / requestCount.toDouble * 100
    }
    OverviewAccessHealth(
      activeRequests = gaugeLong(
        MetricRegistry.name(MetricsConstants.JETTY_API_V1, "active_requests")),
      requestP95Ms = timerP95Ms(MetricRegistry.name(MetricsConstants.JETTY_API_V1, "requests")),
      requestCount = requestCount,
      failedRequests = failedRequests,
      failureRate = failureRate)
  }

  private def runtimeHealth(): OverviewRuntimeHealth = {
    OverviewRuntimeHealth(
      heapUsage = gaugeDouble(MetricRegistry.name(MetricsConstants.MEMORY_USAGE, "heap", "usage")),
      nonHeapUsage = gaugeDouble(
        MetricRegistry.name(MetricsConstants.MEMORY_USAGE, "non_heap", "usage")),
      threadCount = gaugeLong(MetricRegistry.name(MetricsConstants.THREAD_STATE, "count")),
      deadlockCount = gaugeLong(
        MetricRegistry.name(MetricsConstants.THREAD_STATE, "deadlock", "count")),
      gcCount = gaugeSum(MetricsConstants.GC_METRIC, ".count"),
      gcTimeMs = gaugeSum(MetricsConstants.GC_METRIC, ".time"),
      sslCertExpirationMs = MetricsSystem.gaugeValue(
        MetricsConstants.THRIFT_SSL_CERT_EXPIRATION).collect {
        case value: Number => value.longValue()
      })
  }

  private def metadataHealth(): OverviewMetadataHealth = {
    val total = MetricsSystem.meterValue(MetricsConstants.METADATA_REQUEST_TOTAL).getOrElse(0L)
    val failed = MetricsSystem.meterValue(MetricsConstants.METADATA_REQUEST_FAIL).getOrElse(0L)
    OverviewMetadataHealth(
      opened = MetricsSystem.counterValue(MetricsConstants.METADATA_REQUEST_OPENED).getOrElse(0L),
      total = total,
      failed = failed,
      retrying = MetricsSystem.meterValue(MetricsConstants.METADATA_REQUEST_RETRYING).getOrElse(0L),
      failureRate = if (total == 0L) 0.0 else failed.toDouble / total.toDouble * 100)
  }

  private def dataStatus(overviewMetrics: OverviewMetrics, now: Long): OverviewDataStatus = {
    val lastSampleAt = overviewMetrics.lastSampleAt
    val ageMs = if (lastSampleAt == 0L) 0L else math.max(now - lastSampleAt, 0L)
    val stale = lastSampleAt > 0L && ageMs > overviewMetrics.sampleInterval * 2
    val status = if (lastSampleAt == 0L) {
      "INITIALIZING"
    } else if (stale) {
      "STALE"
    } else {
      "READY"
    }
    OverviewDataStatus(
      status = status,
      source = "in-process",
      lastSampleAt = lastSampleAt,
      ageMs = ageMs,
      stale = stale,
      sampleIntervalMs = overviewMetrics.sampleInterval,
      message = status match {
        case "INITIALIZING" => "Overview metrics are initializing"
        case "STALE" => "Overview metrics data has stopped updating"
        case _ => "Overview metrics data is up to date"
      })
  }

  private def gaugeInt(name: String, fallback: => Int = 0): Int = {
    gaugeLong(name, fallback.toLong).toInt
  }

  private def gaugeLong(name: String, fallback: => Long = 0L): Long = {
    MetricsSystem.gaugeValue(name).collect {
      case value: Number => value.longValue()
    }.getOrElse(fallback)
  }

  private def gaugeDouble(name: String): Double = {
    MetricsSystem.gaugeValue(name).collect {
      case value: Number => value.doubleValue()
    }.getOrElse(0.0)
  }

  private def gaugeSum(prefix: String, suffix: String): Long = {
    MetricsSystem.gaugeValues(prefix).collect {
      case (name, value: Number) if name.endsWith(suffix) => value.longValue()
    }.sum
  }

  private def timerP95Ms(name: String): Double = {
    MetricsSystem.timerSnapshot(name)
      .map(snapshot => snapshot.get95thPercentile / 1000000.0)
      .getOrElse(0.0)
  }

  private def histogramSummary(name: String, operationType: String = ""): OverviewLatency = {
    val metricName = if (operationType.nonEmpty) {
      MetricRegistry.name(name, operationType)
    } else {
      name
    }
    MetricsSystem.histogramSnapshot(metricName).map { snapshot =>
      OverviewLatency(
        p50 = snapshot.getMedian,
        p95 = snapshot.get95thPercentile,
        p99 = snapshot.get99thPercentile)
    }.getOrElse(OverviewLatency(0d, 0d, 0d))
  }
}

case class OverviewSummary(
    serverStartCount: Long,
    liveServerCount: Int,
    engines: Seq[OverviewEngineSummary],
    profileCount: Int,
    activeUserCount: Int,
    activeSessionCount: Int,
    execPool: OverviewExecPool,
    operations: OverviewOperations,
    engineHealth: OverviewEngineHealth,
    batchPendingMaxElapse: Long,
    batchOperations: OverviewBatchOperations,
    health: OverviewHealth,
    dataStatus: OverviewDataStatus,
    accessHealth: OverviewAccessHealth,
    runtimeHealth: OverviewRuntimeHealth,
    metadataHealth: OverviewMetadataHealth)

case class OverviewEngineSummary(engineType: String, launchCount: Long)

case class OverviewExecPool(size: Int, active: Int, waiting: Int, alive: Int)

case class OverviewOperations(
    open: Int,
    running: Long,
    waiting: Long,
    failed: Long,
    failureRate: Double,
    latency: OverviewLatency)

case class OverviewBatchOperations(
    total: Long,
    failed: Long,
    failureRate: Double,
    pendingMaxElapse: Long)

case class OverviewEngineHealth(
    launching: Long,
    waiting: Long,
    failed: Long,
    timeout: Long,
    startupLatency: OverviewLatency)

case class OverviewLatency(p50: Double, p95: Double, p99: Double)

case class OverviewHealth(
    status: String,
    checkedAt: Long,
    issues: Seq[OverviewHealthIssue])

case class OverviewHealthIssue(
    code: String,
    severity: String,
    metric: String,
    value: Double,
    threshold: Double,
    message: String)

case class OverviewDataStatus(
    status: String,
    source: String,
    lastSampleAt: Long,
    ageMs: Long,
    stale: Boolean,
    sampleIntervalMs: Long,
    message: String)

case class OverviewAccessHealth(
    activeRequests: Long,
    requestP95Ms: Double,
    requestCount: Long,
    failedRequests: Long,
    failureRate: Double)

case class OverviewRuntimeHealth(
    heapUsage: Double,
    nonHeapUsage: Double,
    threadCount: Long,
    deadlockCount: Long,
    gcCount: Long,
    gcTimeMs: Long,
    sslCertExpirationMs: Option[Long])

case class OverviewMetadataHealth(
    opened: Long,
    total: Long,
    failed: Long,
    retrying: Long,
    failureRate: Double)
