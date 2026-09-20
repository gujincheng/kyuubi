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
    total: Long,
    auditEnabled: Boolean = true,
    source: String = "",
    message: String = "")

/**
 * Provides SQL execution views derived from native Kyuubi operation audit events.
 *
 * SQL records deliberately have no local storage. The managed audit source is the single
 * authority for both the SQL record and native audit views, so retained history survives a
 * Kyuubi Server restart whenever the configured audit backend is durable.
 */
private[kyuubi] object SqlExecutionRecordService {

  def list(
      page: Int,
      pageSize: Int,
      user: Option[String] = None,
      sessionId: Option[String] = None,
      engineType: Option[String] = None,
      state: Option[String] = None,
      keyword: Option[String] = None,
      fromTime: Option[Long] = None,
      toTime: Option[Long] = None): SqlExecutionRecordPage =
    ManagedAuditEventService.querySqlExecutionRecords(
      page,
      pageSize,
      user,
      sessionId,
      engineType,
      state,
      keyword,
      fromTime,
      toTime)

  def get(id: String): Option[SqlExecutionRecord] =
    ManagedAuditEventService.sqlExecutionRecord(id)
}
