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

package org.apache.kyuubi.digiwin.datasource

import java.util.{Collections, Map => JMap}

import scala.collection.JavaConverters._

import org.apache.kyuubi.{KyuubiException, Logging}
import org.apache.kyuubi.plugin.SessionConfAdvisor

/**
 * Reads the datasource label from the session conf (key configurable via
 * `kyuubi.digiwin.datasource.label.key`, default `kyuubi.datasource`), resolves it against the
 * [[DatasourceRegistry]], and returns the engine connection overlay (url/user/driver/pool params)
 * with the password decrypted and injected. The plaintext password never reaches the client.
 *
 * Wired via `kyuubi.session.conf.advisor` =
 * `org.apache.kyuubi.digiwin.datasource.DatasourceConfAdvisor`.
 */
class DatasourceConfAdvisor extends SessionConfAdvisor with Logging {

  override def getConfOverlay(
      user: String,
      sessionConf: JMap[String, String]): JMap[String, String] = {
    val labelKey = DatasourceRegistryHolder.getLabelKey
    val labelOpt = Option(sessionConf.get(labelKey)).filter(_.nonEmpty)
    if (labelOpt.isEmpty) return Collections.emptyMap()

    val label = labelOpt.get
    val reg = DatasourceRegistryHolder.registry
    val ds = reg.get(label).getOrElse(
      throw new KyuubiException(s"Datasource label $label not found"))
    if (!ds.isEnabled) throw new KyuubiException(s"Datasource label $label is disabled")

    val overlay = scala.collection.mutable.Map.empty[String, String]
    overlay("kyuubi.engine.type") = ds.engineType
    if (ds.engineType == "jdbc") {
      overlay("kyuubi.engine.jdbc.type") = ds.jdbcType
      overlay("kyuubi.engine.jdbc.connection.url") = ds.jdbcUrl
      overlay("kyuubi.engine.jdbc.connection.user") = ds.username
      overlay("kyuubi.engine.jdbc.connection.password") = reg.getDecryptedPassword(label)
      overlay("kyuubi.engine.jdbc.driver.class") = ds.driverClass
      ds.connectionPoolParams.foreach { case (k, v) => overlay(k) = v }
    } else {
      throw new KyuubiException(
        s"Datasource label $label has unsupported engine type: ${ds.engineType}")
    }
    overlay.asJava
  }
}
