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

import org.apache.kyuubi.Logging

trait AlertNotifier {
  def notify(event: AlertEvent): Unit
}

case class AlertEvent(
    user: String,
    clientIp: String,
    datasourceLabel: String,
    statement: String,
    ruleName: String,
    reason: String)

class LogAlertNotifier extends AlertNotifier with Logging {
  override def notify(event: AlertEvent): Unit =
    warn(s"[SQL_BLOCKED] user=${event.user} ip=${event.clientIp} " +
      s"datasource=${event.datasourceLabel} rule=${event.ruleName} " +
      s"reason=${event.reason} sql=${event.statement}")
}
