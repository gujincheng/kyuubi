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

import scala.collection.mutable

import org.apache.kyuubi.events.KyuubiOperationEvent
case class SqlExecutionRecord(
    id: String,
    statement: String,
    statementSummary: String,
    user: String,
    sessionId: String,
    engineType: String,
    state: String,
    createTime: Long,
    startTime: Long,
    completeTime: Long,
    queueWaitTimeMs: Long,
    executionDurationMs: Long,
    errorMessage: String)

case class SqlExecutionRecordPage(
    records: Seq[SqlExecutionRecord],
    page: Int,
    pageSize: Int,
    total: Long)

/**
 * Keeps a bounded, process-local history of SQL operations for the web console.
 *
 * The history intentionally follows KyuubiOperationEvent, which is also the source used by
 * operation audit events. This keeps the SQLRecord status and timing semantics aligned with
 * Kyuubi's operation lifecycle and avoids introducing a second persistence path.
 */
private[kyuubi] object SqlExecutionRecordStore {

  private val MaxRecords = 10000
  private[server] val RetentionMs = 24L * 60L * 60L * 1000L
  private val TerminalStateNames = Set(
    "FINISHED_STATE",
    "TIMEDOUT_STATE",
    "CANCELED_STATE",
    "CLOSED_STATE",
    "ERROR_STATE")
  private val records = mutable.LinkedHashMap.empty[String, SqlExecutionRecord]

  def record(event: KyuubiOperationEvent): Unit = synchronized {
    if (event.statementId != null && event.statementId.nonEmpty) {
      val sql = Option(event.statement).getOrElse("")
      val now = System.currentTimeMillis()
      cleanupExpired(now)
      val duration = if (event.executionDuration > 0L) {
        event.executionDuration
      } else if (event.startTime > 0L && !isTerminal(event.state)) {
        math.max(0L, now - event.startTime)
      } else {
        0L
      }
      val updated = SqlExecutionRecord(
        event.statementId,
        sql,
        summarize(sql),
        Option(event.sessionUser).getOrElse(""),
        Option(event.sessionId).getOrElse(""),
        Option(event.engineType).getOrElse(""),
        Option(event.state).getOrElse(""),
        event.createTime,
        event.startTime,
        event.completeTime,
        queueWait(event.createTime, event.startTime),
        duration,
        event.exception.flatMap(e => Option(e.getMessage)).getOrElse(""))

      records.remove(event.statementId)
      records.put(event.statementId, updated)
      while (records.size > MaxRecords) {
        records.remove(records.head._1)
      }
    }
  }

  def list(
      page: Int,
      pageSize: Int,
      user: Option[String] = None,
      sessionId: Option[String] = None,
      engineType: Option[String] = None,
      state: Option[String] = None,
      keyword: Option[String] = None,
      fromTime: Option[Long] = None,
      toTime: Option[Long] = None): SqlExecutionRecordPage = synchronized {
    cleanupExpired(System.currentTimeMillis())
    val normalizedPage = math.max(1, page)
    val normalizedPageSize = math.min(200, math.max(1, pageSize))
    val filtered = records.values.toSeq.reverse.filter { record =>
      matches(record.user, user) &&
      matches(record.sessionId, sessionId) &&
      matches(record.engineType, engineType) &&
      matches(record.state, state) &&
      contains(s"${record.statement} ${record.statementSummary}", keyword) &&
      fromTime.forall(record.createTime >= _) &&
      toTime.forall(record.createTime <= _)
    }
    val offset = (normalizedPage - 1) * normalizedPageSize
    SqlExecutionRecordPage(
      filtered.slice(offset, offset + normalizedPageSize),
      normalizedPage,
      normalizedPageSize,
      filtered.size)
  }

  def get(id: String): Option[SqlExecutionRecord] = synchronized {
    cleanupExpired(System.currentTimeMillis())
    records.get(id)
  }

  private def cleanupExpired(now: Long): Unit = {
    val expireBefore = now - RetentionMs
    records.retain { case (_, record) =>
      record.createTime <= 0L || record.createTime >= expireBefore
    }
  }

  private[server] def clear(): Unit = synchronized {
    records.clear()
  }

  private def matches(value: String, filter: Option[String]): Boolean =
    filter.forall(expected => expected.isEmpty || value.equalsIgnoreCase(expected))

  private def contains(value: String, filter: Option[String]): Boolean =
    filter.forall(expected => expected.isEmpty || value.toLowerCase.contains(expected.toLowerCase))

  private def queueWait(createTime: Long, startTime: Long): Long =
    if (createTime > 0L && startTime > createTime) startTime - createTime else 0L

  private def isTerminal(state: String): Boolean = TerminalStateNames.contains(state)

  private def summarize(sql: String): String = {
    val compact = sql.replaceAll("\\s+", " ").trim
    if (compact.length <= 240) compact else compact.take(237) + "..."
  }
}
