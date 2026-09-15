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

package org.apache.kyuubi.server

import java.util.concurrent.{ScheduledExecutorService, TimeUnit}

import scala.collection.mutable

import com.codahale.metrics.MetricRegistry

import org.apache.kyuubi.metrics.{MetricsConstants, MetricsSystem}
import org.apache.kyuubi.service.AbstractService
import org.apache.kyuubi.util.ThreadUtils

case class OverviewTrendPoint(timestamp: Long, value: Long)

case class OverviewTrend(
    range: String,
    intervalMs: Long,
    points: Seq[OverviewTrendPoint],
    failedPoints: Seq[OverviewTrendPoint],
    latencyPoints: Seq[OverviewTrendPoint] = Seq.empty,
    queuePoints: Seq[OverviewTrendPoint] = Seq.empty)

/**
 * Keeps a small, process-local history for the Overview SQL execution trend.
 *
 * The Prometheus reporter independently exports the same MetricsSystem registry for external
 * scraping. Overview does not query Prometheus or its /metrics endpoint. This bounded history
 * lets the embedded UI provide a useful trend without introducing a Prometheus dependency into
 * the Kyuubi service. It is intentionally reset when the Kyuubi Server restarts.
 */
class OverviewMetrics extends AbstractService("OverviewMetrics") {

  private case class CounterSample(
      timestamp: Long,
      total: Long,
      failed: Long,
      latencyP95: Long,
      queueSize: Long)

  private val executeStatementTotalMetric =
    MetricRegistry.name(MetricsConstants.OPERATION_TOTAL, "ExecuteStatement")
  private val executeStatementFailedMetric =
    MetricRegistry.name(MetricsConstants.OPERATION_FAIL, "ExecuteStatement")
  private val executeStatementLatencyMetric =
    MetricRegistry.name(MetricsConstants.OPERATION_EXEC_TIME, "ExecuteStatement")
  private val sampleIntervalMs = 15 * 1000L
  private val retentionMs = 7 * 24 * 60 * 60 * 1000L
  private val samples = mutable.ArrayBuffer.empty[CounterSample]
  @volatile private var lastSampleTimestamp = 0L
  private var sampler: ScheduledExecutorService = _

  override def start(): Unit = synchronized {
    super.start()
    recordSample(System.currentTimeMillis())
    sampler = ThreadUtils.newDaemonSingleThreadScheduledExecutor("overview-metrics-sampler")
    sampler.scheduleAtFixedRate(
      () => recordSample(System.currentTimeMillis()),
      sampleIntervalMs,
      sampleIntervalMs,
      TimeUnit.MILLISECONDS)
  }

  override def stop(): Unit = synchronized {
    Option(sampler).foreach(_.shutdownNow())
    sampler = null
    super.stop()
  }

  def lastSampleAt: Long = lastSampleTimestamp

  def sampleInterval: Long = sampleIntervalMs

  def trend(range: String): OverviewTrend = {
    val normalizedRange = Option(range).map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("1d")
    val (rangeMs, intervalMs) = normalizedRange match {
      case "1h" => (60 * 60 * 1000L, 60 * 1000L)
      case "1d" => (24 * 60 * 60 * 1000L, 15 * 60 * 1000L)
      case "7d" => (7 * 24 * 60 * 60 * 1000L, 60 * 60 * 1000L)
      case other => throw new IllegalArgumentException(s"Unsupported Overview range: $other")
    }

    val now = System.currentTimeMillis()
    val from = now - rangeMs
    val relevantSamples = synchronized {
      recordSample(now)
      samples.filter(_.timestamp >= from - intervalMs).toSeq
    }
    val values = mutable.Map.empty[Long, Long]
    val failedValues = mutable.Map.empty[Long, Long]
    val latencyValues = mutable.Map.empty[Long, Long]
    val queueValues = mutable.Map.empty[Long, Long]
    relevantSamples.sliding(2).foreach {
      case Seq(previous, current) =>
        val delta = math.max(current.total - previous.total, 0L)
        val failedDelta = math.max(current.failed - previous.failed, 0L)
        val bucket = current.timestamp / intervalMs * intervalMs
        values.update(bucket, values.getOrElse(bucket, 0L) + delta)
        failedValues.update(bucket, failedValues.getOrElse(bucket, 0L) + failedDelta)
        latencyValues.update(bucket, current.latencyP95)
        queueValues.update(bucket, current.queueSize)
      case _ =>
    }
    val points = values.toSeq.sortBy(_._1).map { case (timestamp, value) =>
      OverviewTrendPoint(timestamp, value)
    }
    val failedPoints = failedValues.toSeq.sortBy(_._1).map { case (timestamp, value) =>
      OverviewTrendPoint(timestamp, value)
    }
    val latencyPoints = latencyValues.toSeq.sortBy(_._1).map { case (timestamp, value) =>
      OverviewTrendPoint(timestamp, value)
    }
    val queuePoints = queueValues.toSeq.sortBy(_._1).map { case (timestamp, value) =>
      OverviewTrendPoint(timestamp, value)
    }
    OverviewTrend(normalizedRange, intervalMs, points, failedPoints, latencyPoints, queuePoints)
  }

  private def recordSample(timestamp: Long): Unit = synchronized {
    val total = MetricsSystem.counterValue(executeStatementTotalMetric).getOrElse(0L)
    val failed = MetricsSystem.counterValue(executeStatementFailedMetric).getOrElse(0L)
    val latencyP95 = MetricsSystem.histogramSnapshot(executeStatementLatencyMetric)
      .map(_.get95thPercentile.toLong)
      .getOrElse(0L)
    val queueSize = MetricsSystem.gaugeValue(MetricsConstants.EXEC_POOL_WORK_QUEUE_SIZE).collect {
      case value: Number => value.longValue()
    }.getOrElse(0L)
    samples += CounterSample(timestamp, total, failed, latencyP95, queueSize)
    lastSampleTimestamp = timestamp
    val cutoff = timestamp - retentionMs
    val firstValidIndex = samples.indexWhere(_.timestamp >= cutoff)
    if (firstValidIndex > 0) samples.remove(0, firstValidIndex)
  }
}
