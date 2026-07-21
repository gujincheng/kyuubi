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

import org.apache.kyuubi.Utils
import org.apache.kyuubi.events.KyuubiEvent

/**
 * Event emitted when a SQL statement is blocked by the gateway inspection engine.
 * This event is posted via EventBus so that JSON/Kafka loggers record it alongside
 * normal operation events. Since blocked statements never create an Operation,
 * they would otherwise be invisible in audit logs.
 *
 * @param user the Kyuubi session user who submitted the statement
 * @param clientIp the client IP address
 * @param datasourceLabel the datasource label (empty if not using label-based routing)
 * @param engineType the resolved engine type (jdbc, SPARK_SQL, etc.)
 * @param statement the SQL statement that was blocked
 * @param ruleId the id of the rule that triggered the block
 * @param ruleName the display name of the rule
 * @param ruleType the rule type (KEYWORD / WITHOUT_WHERE / REGEX)
 * @param reason the detailed reason string (e.g. "rule=block-drop(KEYWORD:DROP)")
 * @param action the action taken (DENY)
 * @param eventTime the timestamp when the block occurred
 */
case class SqlBlockedEvent(
    user: String,
    clientIp: String,
    datasourceLabel: String,
    engineType: String,
    statement: String,
    ruleId: String,
    ruleName: String,
    ruleType: String,
    reason: String,
    action: String,
    eventTime: Long) extends KyuubiEvent {

  override def partitions: Seq[(String, String)] =
    ("day", Utils.getDateFromTimestamp(eventTime)) :: Nil
}
