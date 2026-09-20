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

import org.apache.kyuubi.KyuubiFunSuite

class OverviewHealthEvaluatorSuite extends KyuubiFunSuite {

  private val now = 1000L
  private val healthyOperations = OverviewOperations(
    open = 0,
    running = 0,
    waiting = 0,
    failed = 0,
    failureRate = 0,
    latency = OverviewLatency(0, 0, 0))
  private val healthyPool = OverviewExecPool(size = 2, active = 0, waiting = 0, alive = 2)
  private val healthyEngines = OverviewEngineHealth(0, 0, 0, 0, OverviewLatency(0, 0, 0))
  private val healthyAccess = OverviewAccessHealth(0, 0, 0, 0, 0)
  private val healthyRuntime = OverviewRuntimeHealth(0, 0, 0, 0, 0, 0, None)
  private val healthyMetadata = OverviewMetadataHealth(0, 0, 0, 0, 0)
  private val freshData = OverviewDataStatus(
    status = "READY",
    source = "in-process",
    lastSampleAt = now,
    ageMs = 0,
    stale = false,
    sampleIntervalMs = 30000,
    message = "Overview metrics are ready")

  private def evaluate(
      operations: OverviewOperations = healthyOperations,
      execPool: OverviewExecPool = healthyPool,
      engineHealth: OverviewEngineHealth = healthyEngines,
      accessHealth: OverviewAccessHealth = healthyAccess,
      runtimeHealth: OverviewRuntimeHealth = healthyRuntime,
      metadataHealth: OverviewMetadataHealth = healthyMetadata,
      dataStatus: OverviewDataStatus = freshData): OverviewHealth = {
    OverviewHealthEvaluator.evaluate(
      operations,
      execPool,
      engineHealth,
      accessHealth,
      runtimeHealth,
      metadataHealth,
      dataStatus,
      now)
  }

  test("reports normal when all data is fresh and below thresholds") {
    assert(evaluate().status === "NORMAL")
    assert(evaluate().issues.isEmpty)
  }

  test("applies warning and critical SQL failure thresholds") {
    val warning = evaluate(operations = healthyOperations.copy(failureRate = 5))
    val critical = evaluate(operations = healthyOperations.copy(failureRate = 10))

    assert(warning.status === "WARNING")
    assert(warning.issues.exists(_.code === "SQL_FAILURE_RATE_HIGH"))
    assert(critical.status === "CRITICAL")
    assert(critical.issues.exists(_.code === "SQL_FAILURE_RATE_CRITICAL"))
  }

  test("marks queue backlog, JVM deadlock, and stale data as critical when applicable") {
    val queueCritical = evaluate(execPool = healthyPool.copy(waiting = 10))
    val deadlockCritical = evaluate(runtimeHealth = healthyRuntime.copy(deadlockCount = 1))
    val stale = evaluate(dataStatus = freshData.copy(stale = true, ageMs = 60000))

    assert(queueCritical.status === "CRITICAL")
    assert(queueCritical.issues.exists(_.code === "EXECUTION_QUEUE_BACKLOG"))
    assert(deadlockCritical.status === "CRITICAL")
    assert(deadlockCritical.issues.exists(_.code === "JVM_DEADLOCK"))
    assert(stale.status === "CRITICAL")
    assert(stale.issues.exists(_.code === "METRICS_STALE"))
  }

  test("reports unknown before the first metrics sample") {
    val result = evaluate(dataStatus = freshData.copy(lastSampleAt = 0L))
    assert(result.status === "UNKNOWN")
  }
}
