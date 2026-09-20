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

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.apache.kyuubi.{KyuubiFunSuite, RestFrontendTestHelper}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.metrics.MetricsConf
import org.apache.kyuubi.server.{ManagedAuditConfig, ManagedAuditEventService}

class SqlRecordsResourceSuite extends KyuubiFunSuite with RestFrontendTestHelper {
  private var root: Path = _
  private var events: Path = _

  override protected lazy val conf: KyuubiConf =
    KyuubiConf().set(MetricsConf.METRICS_REPORTERS, Set.empty[String])

  override def beforeAll(): Unit = {
    root = Files.createTempDirectory("sql-records-resource-")
    events = root.resolve("events")
    ManagedAuditEventService.setConfigFileForTesting(Some(root.resolve("audit-config.json").toFile))
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      ManagedAuditEventService.close()
      ManagedAuditEventService.setConfigFileForTesting(None)
      if (root != null && Files.exists(root)) {
        val paths = Files.walk(root)
        try paths.iterator().asScala.toList.sortBy(
            _.getNameCount).reverse.foreach(Files.deleteIfExists)
        finally paths.close()
      }
    }
  }

  test("lists and returns SQL execution records through REST") {
    val now = System.currentTimeMillis()
    ManagedAuditEventService.update(ManagedAuditConfig(
      enabled = true,
      mode = "JSON",
      jsonPath = events.toUri.toString,
      retentionDays = 7))
    val eventDirectory = events.resolve("kyuubi_operation")
      .resolve(s"day=${org.apache.kyuubi.Utils.getDateFromTimestamp(now)}")
    Files.createDirectories(eventDirectory)
    val json =
      s"""{"eventTime":${now - 900L},"createTime":${now - 2000L},""" +
        s""""startTime":${now - 1000L},"completeTime":${now - 900L},""" +
        s""""sessionUser":"rest-user","state":"FINISHED_STATE",""" +
        s""""statement":"select 42","statementId":"rest-op-1","sessionId":"rest-session",""" +
        s""""engineType":"JDBC","executionDuration":900,"eventType":"kyuubi_operation"}"""
    Files.write(
      eventDirectory.resolve("server-test.json"),
      (json + "\n").getBytes(StandardCharsets.UTF_8))

    val listResponse = webTarget.path("api/v1/sql-records")
      .queryParam("user", "rest-user")
      .queryParam("pageSize", "10")
      .request()
      .get()
    try {
      assert(listResponse.getStatus === 200)
      val body = listResponse.readEntity(classOf[String])
      assert(body.contains("\"total\":1"))
      assert(body.contains("\"statementSummary\":\"select 42\""))
      assert(body.contains("\"queueWaitTimeMs\":100"))
      assert(body.contains("\"auditEnabled\":true"))
    } finally {
      listResponse.close()
    }

    val detailResponse = webTarget.path("api/v1/sql-records/rest-op-1")
      .request()
      .get()
    try {
      assert(detailResponse.getStatus === 200)
      assert(detailResponse.readEntity(classOf[String]).contains("\"engineType\":\"JDBC\""))
    } finally {
      detailResponse.close()
    }

    val activityResponse = webTarget.path("api/v1/admin/event-audit/activities")
      .queryParam("user", "rest-user")
      .queryParam("pageSize", "10")
      .request()
      .get()
    try {
      assert(activityResponse.getStatus === 200)
      val body = activityResponse.readEntity(classOf[String])
      assert(body.contains("\"total\":1"))
      assert(body.contains("\"eventCount\":1"))
      assert(body.contains("\"operationId\":\"rest-op-1\""))
    } finally {
      activityResponse.close()
    }
  }
}
