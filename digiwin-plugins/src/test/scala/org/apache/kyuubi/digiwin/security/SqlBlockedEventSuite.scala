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

package org.apache.kyuubi.digiwin.security

import org.scalatest.matchers.should.Matchers

import org.apache.kyuubi.KyuubiFunSuite

class SqlBlockedEventSuite extends KyuubiFunSuite with Matchers {

  test("SqlBlockedEvent should serialize to JSON with all fields") {
    val event = SqlBlockedEvent(
      user = "root",
      clientIp = "192.168.1.100",
      datasourceLabel = "sr-prod",
      engineType = "jdbc",
      statement = "DROP TABLE t",
      ruleId = "block-drop",
      // scalastyle:off
      ruleName = "禁止 DROP",
      // scalastyle:on
      ruleType = "KEYWORD",
      reason = "rule=block-drop(KEYWORD:DROP)",
      action = "DENY",
      eventTime = 1721376180000L)

    val json = event.toJson
    json should include("\"user\":\"root\"")
    json should include("\"clientIp\":\"192.168.1.100\"")
    json should include("\"datasourceLabel\":\"sr-prod\"")
    json should include("\"engineType\":\"jdbc\"")
    json should include("\"statement\":\"DROP TABLE t\"")
    json should include("\"ruleId\":\"block-drop\"")
    // scalastyle:off
    json should include("\"ruleName\":\"禁止 DROP\"")
    // scalastyle:on
    json should include("\"ruleType\":\"KEYWORD\"")
    json should include("\"reason\":\"rule=block-drop(KEYWORD:DROP)\"")
    json should include("\"action\":\"DENY\"")
    json should include("\"eventTime\":1721376180000")
    json should include("\"eventType\":\"sql_blocked\"")
  }

  test("SqlBlockedEvent partitions should use day partition") {
    val event = SqlBlockedEvent(
      user = "alice",
      clientIp = "10.0.0.5",
      datasourceLabel = "",
      engineType = "SPARK_SQL",
      statement = "SELECT * FROM t",
      ruleId = "no-star",
      // scalastyle:off
      ruleName = "禁SELECT*",
      // scalastyle:on
      ruleType = "REGEX",
      reason = "rule=no-star(REGEX:SELECT\\s+\\*)",
      action = "DENY",
      eventTime = System.currentTimeMillis())

    event.partitions should have size 1
    event.partitions.head._1 should be("day")
  }

  test("SqlBlockedEvent with empty datasourceLabel") {
    val event = SqlBlockedEvent(
      user = "bob",
      clientIp = "",
      datasourceLabel = "",
      engineType = "",
      statement = "DELETE FROM t",
      ruleId = "block-delete-nowhere",
      // scalastyle:off
      ruleName = "禁无WHERE删除",
      // scalastyle:on
      ruleType = "WITHOUT_WHERE",
      reason = "rule=block-delete-nowhere(WITHOUT_WHERE:DELETE)",
      action = "DENY",
      eventTime = 1000L)

    val json = event.toJson
    json should include("\"datasourceLabel\":\"\"")
    json should include("\"engineType\":\"\"")
    json should include("\"clientIp\":\"\"")
  }
}
