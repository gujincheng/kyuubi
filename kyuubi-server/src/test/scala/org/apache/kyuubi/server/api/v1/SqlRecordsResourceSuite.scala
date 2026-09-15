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

import org.apache.kyuubi.{KyuubiFunSuite, RestFrontendTestHelper}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.events.KyuubiOperationEvent
import org.apache.kyuubi.server.SqlExecutionRecordStore

class SqlRecordsResourceSuite extends KyuubiFunSuite with RestFrontendTestHelper {

  override protected lazy val conf: KyuubiConf = KyuubiConf()

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    SqlExecutionRecordStore.clear()
  }

  override protected def afterEach(): Unit = {
    SqlExecutionRecordStore.clear()
    super.afterEach()
  }

  test("lists and returns SQL execution records through REST") {
    val now = System.currentTimeMillis()
    SqlExecutionRecordStore.record(KyuubiOperationEvent(
      "rest-op-1",
      "",
      "select 42",
      shouldRunAsync = true,
      "FINISHED_STATE",
      now - 2000L,
      now - 1000L,
      now - 900L,
      900L,
      900L,
      None,
      "rest-session",
      "rest-user",
      "INTERACTIVE",
      "jdbc:test",
      Map.empty,
      "127.0.0.1",
      "",
      "JDBC",
      ""))

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
  }
}
