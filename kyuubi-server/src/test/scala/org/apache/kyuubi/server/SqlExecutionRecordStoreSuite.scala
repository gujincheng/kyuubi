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

import org.apache.kyuubi.KyuubiFunSuite

class SqlExecutionRecordStoreSuite extends KyuubiFunSuite {

  test("derives lifecycle fields, filters, and pagination from native audit events") {
    val now = System.currentTimeMillis()
    val first = events(
      "op-1",
      "select  1\nfrom dual",
      "alice",
      "session-1",
      "JDBC",
      now - 2000L,
      now - 1750L,
      now - 1500L,
      Seq("INITIALIZED_STATE", "PENDING_STATE", "RUNNING_STATE", "FINISHED_STATE", "CLOSED_STATE"))
    val second = events(
      "op-2",
      "select 2",
      "bob",
      "session-2",
      "SPARK",
      now - 1000L,
      now - 800L,
      now - 600L,
      Seq("INITIALIZED_STATE", "PENDING_STATE", "RUNNING_STATE", "ERROR_STATE", "CLOSED_STATE"),
      "bad SQL")

    val records = ManagedAuditEventService.aggregateSqlExecutionRecords(first ++ second)
    val all = ManagedAuditEventService.toSqlExecutionRecordPage(
      records,
      page = 1,
      pageSize = 10,
      user = None,
      sessionId = None,
      engineType = None,
      state = None,
      keyword = None,
      fromTime = None,
      toTime = None,
      auditEnabled = true,
      source = "JSON",
      message = "active")

    assert(all.total === 2)
    assert(all.records.map(_.id) === Seq("op-2", "op-1"))
    assert(all.records.last.statementSummary === "select 1 from dual")
    assert(all.records.last.queueWaitTimeMs === 250L)
    assert(all.records.last.executionDurationMs === 250L)
    assert(all.records.head.errorMessage === "bad SQL")
    assert(all.records.head.state === "ERROR_STATE")

    val filtered = ManagedAuditEventService.toSqlExecutionRecordPage(
      records,
      page = 1,
      pageSize = 1,
      user = Some("alice"),
      sessionId = None,
      engineType = None,
      state = None,
      keyword = Some("dual"),
      fromTime = None,
      toTime = None,
      auditEnabled = true,
      source = "JSON",
      message = "active")
    assert(filtered.total === 1)
    assert(filtered.records.head.id === "op-1")

    val secondPage = ManagedAuditEventService.toSqlExecutionRecordPage(
      records,
      page = 2,
      pageSize = 1,
      user = None,
      sessionId = None,
      engineType = None,
      state = None,
      keyword = None,
      fromTime = None,
      toTime = None,
      auditEnabled = true,
      source = "JSON",
      message = "active")
    assert(secondPage.records.map(_.id) === Seq("op-1"))
  }

  test("derives in-flight operations without requiring terminal events") {
    val records = ManagedAuditEventService.aggregateSqlExecutionRecords(events(
      "op-running",
      "select 3",
      "alice",
      "session-1",
      "JDBC",
      System.currentTimeMillis() - 100L,
      System.currentTimeMillis(),
      0L,
      Seq("INITIALIZED_STATE", "PENDING_STATE", "RUNNING_STATE")))

    assert(records.headOption.exists(_.state === "RUNNING_STATE"))
    assert(records.headOption.exists(_.executionDurationMs >= 0L))
  }

  test("keeps history supplied by the audit source instead of applying process-local retention") {
    val old = events(
      "op-old",
      "select old",
      "alice",
      "session-old",
      "JDBC",
      System.currentTimeMillis() - 90L * 24L * 60L * 60L * 1000L,
      0L,
      0L,
      Seq("FINISHED_STATE"))
    val recent = events(
      "op-recent",
      "select recent",
      "alice",
      "session-recent",
      "JDBC",
      System.currentTimeMillis(),
      0L,
      0L,
      Seq("FINISHED_STATE"))

    val records = ManagedAuditEventService.aggregateSqlExecutionRecords(old ++ recent)
    assert(records.map(_.id) === Seq("op-recent", "op-old"))
  }

  test("groups native operation and session events into separate audit activities") {
    val now = System.currentTimeMillis()
    val operation = events(
      "op-activity",
      "select grouped_activity",
      "alice",
      "session-activity",
      "JDBC",
      now - 1000L,
      now - 800L,
      now - 500L,
      Seq("RUNNING_STATE", "FINISHED_STATE"))
    val session = NativeAuditEvent(
      "session-activity-1",
      "JSON",
      "kyuubi_session",
      now - 1200L,
      now - 1200L,
      0L,
      now - 400L,
      "alice",
      "CLOSED_STATE",
      "",
      "session-activity",
      "",
      "127.0.0.1",
      "",
      "JDBC",
      800L,
      "",
      "{}")
    val operationWithoutId = operation.head.copy(
      id = "operation-without-id",
      operationId = "",
      eventTime = now - 300L)

    val activities = ManagedAuditEventService.aggregateActivities(
      operation :+ session :+ operationWithoutId)
    assert(activities.size === 3)
    assert(activities.find(_.operationId == "op-activity").exists(_.eventCount === 2))
    assert(activities.find(_.eventType == "kyuubi_session").exists { activity =>
      activity.sessionId === "session-activity" && activity.eventCount === 1
    })
    assert(activities.exists(_.id == "event:kyuubi_operation:operation-without-id"))
  }

  // mirrors the event data persisted by native Kyuubi operation audit logging
  // scalastyle:off parameter.number
  private def events(
      id: String,
      statement: String,
      user: String,
      sessionId: String,
      engineType: String,
      createTime: Long,
      startTime: Long,
      completeTime: Long,
      states: Seq[String],
      error: String = ""): Seq[NativeAuditEvent] = states.zipWithIndex.map { case (state, index) =>
    val eventTime = createTime + index * 50L
    NativeAuditEvent(
      s"event-$id-$index",
      "JSON",
      "kyuubi_operation",
      eventTime,
      createTime,
      if (index >= 2) startTime else 0L,
      if (state == "FINISHED_STATE" || state == "ERROR_STATE" || state == "CLOSED_STATE") {
        completeTime
      } else {
        0L
      },
      user,
      state,
      statement,
      sessionId,
      id,
      "127.0.0.1",
      "",
      engineType,
      if (completeTime > startTime && completeTime > 0L) completeTime - startTime else 0L,
      if (state == "ERROR_STATE") error else "",
      "{}")
  }
  // scalastyle:on parameter.number
}
