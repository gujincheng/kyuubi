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
import org.apache.kyuubi.events.KyuubiOperationEvent

class SqlExecutionRecordStoreSuite extends KyuubiFunSuite {

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    SqlExecutionRecordStore.clear()
  }

  override protected def afterEach(): Unit = {
    SqlExecutionRecordStore.clear()
    super.afterEach()
  }

  test("records lifecycle fields and supports filters and pagination") {
    val now = System.currentTimeMillis()
    val first = event(
      "op-1",
      "select  1\nfrom dual",
      "alice",
      "session-1",
      "JDBC",
      "FINISHED_STATE",
      now - 2000L,
      now - 1750L,
      now - 1500L,
      250L)
    val second = event(
      "op-2",
      "select 2",
      "bob",
      "session-2",
      "SPARK",
      "ERROR_STATE",
      now - 1000L,
      now - 800L,
      now - 600L,
      200L,
      Some(new IllegalArgumentException("bad SQL")))

    SqlExecutionRecordStore.record(first)
    SqlExecutionRecordStore.record(second)

    val all = SqlExecutionRecordStore.list(1, 10)
    assert(all.total === 2)
    assert(all.records.map(_.id) === Seq("op-2", "op-1"))
    assert(all.records.last.statementSummary === "select 1 from dual")
    assert(all.records.last.queueWaitTimeMs === 250L)
    assert(all.records.last.executionDurationMs === 250L)
    assert(all.records.head.errorMessage === "bad SQL")

    val filtered = SqlExecutionRecordStore.list(
      1,
      1,
      user = Some("alice"),
      keyword = Some("dual"))
    assert(filtered.total === 1)
    assert(filtered.records.head.id === "op-1")

    val secondPage = SqlExecutionRecordStore.list(2, 1)
    assert(secondPage.records.map(_.id) === Seq("op-1"))
  }

  test("records in-flight operations without requiring terminal state") {
    SqlExecutionRecordStore.record(event(
      "op-running",
      "select 3",
      "alice",
      "session-1",
      "JDBC",
      "RUNNING_STATE",
      System.currentTimeMillis() - 100L,
      System.currentTimeMillis(),
      0L,
      0L))

    val running = SqlExecutionRecordStore.get("op-running")
    assert(running.exists(_.state === "RUNNING_STATE"))
    assert(running.exists(_.executionDurationMs >= 0L))
  }

  test("removes records older than the process retention window") {
    val old = event(
      "op-old",
      "select old",
      "alice",
      "session-old",
      "JDBC",
      "FINISHED_STATE",
      System.currentTimeMillis() - SqlExecutionRecordStore.RetentionMs - 1L,
      0L,
      0L,
      0L)
    val recent = event(
      "op-recent",
      "select recent",
      "alice",
      "session-recent",
      "JDBC",
      "FINISHED_STATE",
      System.currentTimeMillis(),
      0L,
      0L,
      0L)

    SqlExecutionRecordStore.record(old)
    SqlExecutionRecordStore.record(recent)

    val page = SqlExecutionRecordStore.list(1, 10)
    assert(page.total === 1)
    assert(page.records.head.id === "op-recent")
    assert(SqlExecutionRecordStore.get("op-old").isEmpty)
  }

  test("bounds the in-memory history to the configured capacity") {
    val now = System.currentTimeMillis()
    (1 to 10001).foreach { index =>
      SqlExecutionRecordStore.record(event(
        s"op-$index",
        s"select $index",
        "alice",
        "session-1",
        "JDBC",
        "FINISHED_STATE",
        now + index,
        0L,
        0L,
        0L))
    }

    val page = SqlExecutionRecordStore.list(1, 10001)
    assert(page.total === 10000)
    assert(page.records.head.id === "op-10001")
    assert(SqlExecutionRecordStore.get("op-1").isEmpty)
  }

  private def event(
      id: String,
      statement: String,
      user: String,
      sessionId: String,
      engineType: String,
      state: String,
      createTime: Long,
      startTime: Long,
      completeTime: Long,
      executionDuration: Long,
      exception: Option[Throwable] = None): KyuubiOperationEvent = {
    KyuubiOperationEvent(
      id,
      "",
      statement,
      shouldRunAsync = true,
      state,
      eventTime = completeTime,
      createTime,
      startTime,
      completeTime,
      executionDuration,
      exception,
      sessionId,
      user,
      "INTERACTIVE",
      "jdbc:test",
      Map.empty,
      "127.0.0.1",
      "",
      engineType,
      "")
  }
}
