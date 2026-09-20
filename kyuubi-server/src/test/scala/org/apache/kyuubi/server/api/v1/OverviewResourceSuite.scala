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

import java.util.concurrent.{CountDownLatch, TimeUnit}
import javax.ws.rs.core.Response

import com.codahale.metrics.MetricRegistry

import org.apache.kyuubi.{KyuubiFunSuite, RestFrontendTestHelper}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.metrics.{MetricsConstants, MetricsSystem}
import org.apache.kyuubi.metrics.MetricsConf
import org.apache.kyuubi.session.KyuubiSessionManager

class OverviewResourceSuite extends KyuubiFunSuite with RestFrontendTestHelper {

  override protected lazy val conf: KyuubiConf =
    KyuubiConf()
      .set(MetricsConf.METRICS_REPORTERS, Set.empty[String])
      .set(KyuubiConf.SERVER_EXEC_POOL_SIZE, 2)
      .set(KyuubiConf.SERVER_EXEC_WAIT_QUEUE_SIZE, 1)

  test("returns overview summary") {
    val response = webTarget.path("api/v1/overview/summary").request().get()
    try {
      assert(response.getStatus === 200)
      val body = response.readEntity(classOf[String])
      assert(body.contains("\"serverStartCount\""))
      assert(body.contains("\"activeSessionCount\""))
      assert(body.contains("\"execPool\""))
      assert(body.contains("\"operations\""))
      assert(body.contains("\"engineHealth\""))
      assert(body.contains("\"batchPendingMaxElapse\""))
      assert(body.contains("\"health\""))
      assert(body.contains("\"dataStatus\""))
      assert(body.contains("\"status\":\"READY\""))
      assert(body.contains("\"accessHealth\""))
      assert(body.contains("\"runtimeHealth\""))
      assert(body.contains("\"metadataHealth\""))
    } finally {
      response.close()
    }
  }

  test("returns overview trend for supported ranges") {
    val response = webTarget.path("api/v1/overview/trend")
      .queryParam("range", "1h")
      .request()
      .get()
    try {
      assert(response.getStatus === 200)
      val body = response.readEntity(classOf[String])
      assert(body.contains("\"range\":\"1h\""))
      assert(body.contains("\"intervalMs\""))
      assert(body.contains("\"points\""))
    } finally {
      response.close()
    }
  }

  test("rejects unsupported overview trend ranges") {
    val response = webTarget.path("api/v1/overview/trend")
      .queryParam("range", "30d")
      .request()
      .get()
    try {
      assert(response.getStatus === Response.Status.BAD_REQUEST.getStatusCode)
      assert(response.readEntity(classOf[String]).contains("Unsupported Overview range"))
    } finally {
      response.close()
    }
  }

  test("reports the background operation wait queue size") {
    val sessionManager = fe.be.sessionManager.asInstanceOf[KyuubiSessionManager]
    val activeTasksStarted = new CountDownLatch(2)
    val releaseActiveTasks = new CountDownLatch(1)
    val activeTasks = (1 to 2).map { _ =>
      sessionManager.submitBackgroundOperation(new Runnable {
        override def run(): Unit = {
          activeTasksStarted.countDown()
          releaseActiveTasks.await(5, TimeUnit.SECONDS)
        }
      })
    }

    try {
      assert(activeTasksStarted.await(5, TimeUnit.SECONDS))
      val queuedTask = sessionManager.submitBackgroundOperation(new Runnable {
        override def run(): Unit = ()
      })
      try {
        assert(sessionManager.getWorkQueueSize === 1)
        val response = webTarget.path("api/v1/overview/summary").request().get()
        try {
          val body = response.readEntity(classOf[String])
          val queueSize = "\"execPool\":\\{\"size\":(\\d+)".r
            .findFirstMatchIn(body)
            .map(_.group(1).toInt)
          assert(queueSize.contains(1))
          assert(body.contains("\"waiting\":1"))
        } finally {
          response.close()
        }
      } finally {
        releaseActiveTasks.countDown()
        queuedTask.get(5, TimeUnit.SECONDS)
      }
    } finally {
      releaseActiveTasks.countDown()
      activeTasks.foreach(_.get(5, TimeUnit.SECONDS))
    }
  }

  test("calculates SQL failure rate from SQL operation totals") {
    val operationTotalBefore = MetricsSystem.counterValue(
      MetricRegistry.name(MetricsConstants.OPERATION_TOTAL, "ExecuteStatement")).getOrElse(0L)
    val operationFailedBefore = MetricsSystem.counterValue(
      MetricRegistry.name(MetricsConstants.OPERATION_FAIL, "ExecuteStatement")).getOrElse(0L)

    MetricsSystem.tracing { metrics =>
      metrics.incCount(MetricRegistry.name(MetricsConstants.OPERATION_TOTAL, "LaunchEngine"))
      metrics.incCount(MetricRegistry.name(MetricsConstants.OPERATION_TOTAL, "ExecuteStatement"))
      metrics.incCount(MetricRegistry.name(MetricsConstants.OPERATION_FAIL, "ExecuteStatement"))
      metrics.incCount(MetricRegistry.name(MetricsConstants.OPERATION_TOTAL, "BatchJobSubmission"))
      metrics.incCount(MetricRegistry.name(MetricsConstants.OPERATION_FAIL, "BatchJobSubmission"))
    }

    val response = webTarget.path("api/v1/overview/summary").request().get()
    try {
      val body = response.readEntity(classOf[String])
      val failureRate = "\"failureRate\":([0-9.]+)".r
        .findFirstMatchIn(body)
        .map(_.group(1).toDouble)
      val expected = (operationFailedBefore + 1).toDouble /
        (operationTotalBefore + 1).toDouble * 100
      assert(failureRate.contains(expected))
      assert(body.contains("\"batchOperations\""))
    } finally {
      response.close()
    }
  }
}
