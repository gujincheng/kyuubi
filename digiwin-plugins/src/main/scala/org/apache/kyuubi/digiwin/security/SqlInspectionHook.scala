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

import org.apache.kyuubi.{KyuubiSQLException, Logging}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.config.KyuubiReservedKeys.KYUUBI_CLIENT_IP_KEY
import org.apache.kyuubi.digiwin.datasource.DatasourceRegistryHolder
import org.apache.kyuubi.session.{AbstractSession, Session}

/** Error code returned to clients when SQL is blocked (FR-5). */
object SqlBlockedErrorCode {
  val SQL_BLOCKED = "SQL_BLOCKED"
}

/**
 * Inspects a statement before execution; throws KyuubiSQLException(SQL_BLOCKED) on violation.
 * Rules are fetched live from the [[RuleRegistryHolder]] so management-API changes take effect
 * immediately. Wired into KyuubiBackendService.executeStatement.
 */
class SqlInspectionHook(conf: KyuubiConf) extends Logging {

  private val enabled = conf.get(DIGIWIN_SQL_INSPECTION_ENABLED)
  private val whitelist = conf.get(DIGIWIN_SQL_INSPECTION_WHITELIST).toSet
  private val alertNotifier = new LogAlertNotifier()

  def inspect(session: Session, statement: String): Unit = {
    if (!enabled) return
    val user = session.user
    if (whitelist.contains(user)) return

    val rules = RuleRegistryHolder.registryOpt.map(_.snapshot()).getOrElse(Seq.empty)
    if (rules.isEmpty) return

    // normalizedConf has client conf keys normalized (e.g. "set:hiveconf:kyuubi.datasource"
    // -> "kyuubi.datasource"); the raw session.conf keeps the prefixed form.
    val sessionConf = session.asInstanceOf[AbstractSession].normalizedConf
    val label = sessionConf.getOrElse(conf.get(DIGIWIN_DATASOURCE_LABEL_KEY), "")
    val engineType = resolveEngineType(sessionConf, label)
    val clientIp = sessionConf.getOrElse(KYUUBI_CLIENT_IP_KEY, "")

    val hit = SqlInspectionEngine.inspect(statement, rules, user, engineType)
    hit.foreach { rule =>
      val reason = s"rule=${rule.id}(${rule.ruleType}:${rule.pattern})"
      alertNotifier.notify(AlertEvent(user, clientIp, label, statement, rule.name, reason))
      throw new KyuubiSQLException(
        s"SQL is blocked by gateway inspection: $reason",
        SqlBlockedErrorCode.SQL_BLOCKED,
        0,
        null)
    }
  }

  private def resolveEngineType(sessionConf: Map[String, String], label: String): String = {
    if (label.nonEmpty) {
      DatasourceRegistryHolder.registryOpt
        .flatMap(_.get(label).map(_.engineType))
        .getOrElse("")
    } else {
      sessionConf.getOrElse("kyuubi.engine.type", "")
    }
  }
}
