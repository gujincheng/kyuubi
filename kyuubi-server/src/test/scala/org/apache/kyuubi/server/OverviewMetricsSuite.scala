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

import com.codahale.metrics.MetricRegistry

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.metrics.{MetricsConf, MetricsConstants, MetricsSystem}

class OverviewMetricsSuite extends KyuubiFunSuite {

  test("samples the MetricsSystem counter without a Prometheus reporter") {
    val conf = KyuubiConf().set(MetricsConf.METRICS_REPORTERS, Set.empty[String])
    val metricsSystem = new MetricsSystem()
    val overviewMetrics = new OverviewMetrics()
    val executeStatementMetric =
      MetricRegistry.name(MetricsConstants.OPERATION_TOTAL, "ExecuteStatement")

    try {
      metricsSystem.initialize(conf)
      metricsSystem.start()
      overviewMetrics.initialize(conf)
      overviewMetrics.start()

      metricsSystem.incCount(executeStatementMetric)
      metricsSystem.incCount(
        MetricRegistry.name(MetricsConstants.OPERATION_FAIL, "LaunchEngine"))
      metricsSystem.incCount(
        MetricRegistry.name(MetricsConstants.OPERATION_FAIL, "ExecuteStatement"))
      val trend = overviewMetrics.trend("1h")

      assert(trend.range === "1h")
      assert(trend.points.map(_.value).sum >= 1)
      assert(trend.failedPoints.map(_.value).sum === 1)
    } finally {
      overviewMetrics.stop()
      metricsSystem.stop()
    }
  }
}
