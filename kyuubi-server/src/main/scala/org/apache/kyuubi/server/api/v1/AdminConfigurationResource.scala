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

import javax.ws.rs.{GET, Path, Produces}
import javax.ws.rs.core.MediaType

import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.Utils
import org.apache.kyuubi.config.KyuubiConf.{AUTHENTICATION_METHOD, SERVER_ADMINISTRATORS, USER_DEFAULTS_CONF_QUOTE}
import org.apache.kyuubi.server.{AdminPermissionService, AdminRole}
import org.apache.kyuubi.server.api.ApiRequestContext

@Tag(name = "Admin Configuration")
@Path("configuration")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class AdminConfigurationResource extends ApiRequestContext {

  @GET
  def configuration(): AdminConfiguration = {
    val userName = AdminPermissionService.require(fe, "configuration", AdminRole.Read)

    val entries = fe.getConf.getAll
      .filterNot { case (key, _) => key.startsWith(USER_DEFAULTS_CONF_QUOTE) }
      .toSeq
      .sortBy(_._1)
      .map { case (key, value) =>
        ConfigurationEntry(key, redact(key, value), sensitive(key), category(key))
      }

    val categories = entries
      .groupBy(_.category)
      .toSeq
      .sortBy(_._1)
      .map { case (name, values) =>
        ConfigurationCategory(name, values.size)
      }

    val administrators = (fe.getConf.get(SERVER_ADMINISTRATORS) + Utils.currentUser).toSeq.sorted
    AdminConfiguration(
      currentUser = userName,
      securityEnabled = fe.securityEnabled,
      authenticationMethods = fe.getConf.get(AUTHENTICATION_METHOD),
      administrators = administrators,
      categories = categories,
      entries = entries,
      reloads = Seq(
        ReloadAction("hadoop_conf", "Hadoop configuration", "admin/refresh/hadoop_conf"),
        ReloadAction(
          "kubernetes_conf",
          "Kubernetes configuration",
          "admin/refresh/kubernetes_conf"),
        ReloadAction("user_defaults_conf", "User defaults", "admin/refresh/user_defaults_conf"),
        ReloadAction("access_policies", "Access policies", "admin/refresh/unlimited_users")))
  }

  private def category(key: String): String = {
    val parts = key.split("\\.")
    if (parts.length >= 2) parts.take(2).mkString(".") else parts.headOption.getOrElse("other")
  }

  private def sensitive(key: String): Boolean =
    "(?i)(password|passwd|secret|token|access[.]key|private[.]key|credential)".r
      .findFirstIn(key)
      .nonEmpty

  private def redact(key: String, value: String): String = if (sensitive(key)) "******" else value
}

case class AdminConfiguration(
    currentUser: String,
    securityEnabled: Boolean,
    authenticationMethods: Seq[String],
    administrators: Seq[String],
    categories: Seq[ConfigurationCategory],
    entries: Seq[ConfigurationEntry],
    reloads: Seq[ReloadAction])

case class ConfigurationCategory(name: String, entryCount: Int)

case class ConfigurationEntry(key: String, value: String, sensitive: Boolean, category: String)

case class ReloadAction(id: String, label: String, endpoint: String)
