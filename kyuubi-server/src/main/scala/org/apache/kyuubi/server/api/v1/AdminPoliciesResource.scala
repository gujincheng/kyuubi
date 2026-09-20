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

import java.io.File
import javax.ws.rs.{BadRequestException, GET, InternalServerErrorException, Path, Produces, PUT, WebApplicationException}
import javax.ws.rs.core.{MediaType, Response}

import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.Utils
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.{KYUUBI_CONF_DIR, KYUUBI_CONF_FILE_NAME, KYUUBI_HOME_ENV_VAR_NAME, USER_DEFAULTS_CONF_QUOTE}
import org.apache.kyuubi.server.{AdminPermissionService, AdminRole, AuditRecordStore}
import org.apache.kyuubi.server.api.ApiRequestContext
import org.apache.kyuubi.session.FileSessionConfAdvisor
import org.apache.kyuubi.session.KyuubiSessionManager

@Tag(name = "Admin Policies")
@Path("policies")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class AdminPoliciesResource extends ApiRequestContext {

  @GET
  def policies(): AdminPolicies = {
    AdminPermissionService.require(fe, "policy", AdminRole.Read)
    val sessionManager = fe.be.sessionManager.asInstanceOf[KyuubiSessionManager]
    AdminPolicies(
      profiles = profiles(),
      userDefaults = userDefaults(),
      access = AccessPolicies(
        unlimitedUsers = sessionManager.getUnlimitedUsers.toSeq.sorted,
        denyUsers = sessionManager.getDenyUsers.toSeq.sorted,
        denyIps = sessionManager.getDenyIps.toSeq.sorted))
  }

  @PUT
  def update(request: AdminPoliciesUpdate): AdminPolicies = {
    val actor = AdminPermissionService.require(fe, "policy", AdminRole.Write)
    if (request == null) {
      throw new javax.ws.rs.BadRequestException("policy update body must not be empty")
    }
    val userDefaults = Option(request.userDefaults).getOrElse(Seq.empty)
    val profiles = Option(request.profiles).getOrElse(Seq.empty)
    val access = Option(request.access)
    val domains = Seq(!userDefaults.isEmpty, !profiles.isEmpty, access.nonEmpty).count(identity)
    if (domains != 1) {
      throw new javax.ws.rs.BadRequestException(
        "exactly one of userDefaults, profiles, or access must be provided")
    }

    var auditAction: Option[String] = None
    try {
      val configFile = writableConfigFile()
      if (userDefaults.nonEmpty) {
        if (userDefaults.size != 1) {
          throw new BadRequestException(
            "userDefaults must contain exactly one user update per request")
        }
        val update = userDefaults.head
        AdminPoliciesFileStore.updateUserDefaults(configFile, update)
        refreshUserDefaultsConf()
        auditAction = Some(if (update.delete) {
          "policy.user-default.delete"
        } else {
          "policy.user-default.upsert"
        })
      } else if (profiles.nonEmpty) {
        if (profiles.size != 1) {
          throw new BadRequestException(
            "profiles must contain exactly one profile update per request")
        }
        val update = profiles.head
        AdminPoliciesFileStore.updateProfile(configFile.getParentFile, update)
        FileSessionConfAdvisor.invalidate(update.name)
        auditAction = Some(if (update.delete) "policy.profile.delete" else "policy.profile.upsert")
      } else {
        val updatedAccess = access.get
        AdminPoliciesFileStore.updateAccess(configFile, updatedAccess)
        refreshAccessPolicies()
        auditAction = Some("policy.access.replace")
      }
    } catch {
      case e: PolicyFileException =>
        e.status match {
          case 400 => throw new BadRequestException(e.message)
          case 409 =>
            throw new WebApplicationException(
              e.message,
              Response.status(Response.Status.CONFLICT).entity(e.message).build())
          case _ => throw new InternalServerErrorException(e.message)
        }
    }
    auditAction.foreach(action =>
      AuditRecordStore.appendAction(actor, fe.getIpAddress, action, "/api/v1/admin/policies"))
    policies()
  }

  private def sessionManager: KyuubiSessionManager =
    fe.be.sessionManager.asInstanceOf[KyuubiSessionManager]

  /**
   * Reload the file-backed defaults into the live session manager. Keeping the refresh on the
   * request's backend avoids relying on the process-wide singleton while the REST service is
   * being initialized or embedded in a test process.
   */
  private def refreshUserDefaultsConf(): Unit = {
    val runtimeConf = sessionManager.getConf
    val refreshedConf = KyuubiConf(loadSysDefault = false).loadFileDefaults()
    val existing = runtimeConf.getAllUserDefaults
    val refreshed = refreshedConf.getAllUserDefaults
    existing.keys.filterNot(refreshed.contains).foreach(runtimeConf.unset)
    refreshed.foreach { case (key, value) => runtimeConf.set(key, value) }
  }

  private def refreshAccessPolicies(): Unit = {
    val refreshedConf = KyuubiConf(loadSysDefault = false).loadFileDefaults()
    sessionManager.refreshUnlimitedUsers(refreshedConf)
    sessionManager.refreshDenyUsers(refreshedConf)
    sessionManager.refreshDenyIps(refreshedConf)
  }

  private def writableConfigFile(): File = {
    Utils.getPropertiesFile(KYUUBI_CONF_FILE_NAME).getOrElse {
      throw PolicyFileException(
        "The Kyuubi defaults configuration file cannot be located; configure " +
          "KYUUBI_CONF_DIR or KYUUBI_HOME before using policy updates.",
        409)
    }
  }

  private def profiles(): Seq[SessionProfile] = {
    configDirectory.toSeq
      .flatMap(directory => Option(directory.listFiles()).toSeq.flatten)
      .filter(file =>
        file.isFile && file.getName.startsWith("kyuubi-session-") &&
          file.getName.endsWith(".conf"))
      .sortBy(_.getName)
      .map { file =>
        val properties = Utils.getPropertiesFromFile(Some(file))
        SessionProfile(
          name = file.getName.stripPrefix("kyuubi-session-").stripSuffix(".conf"),
          fileName = file.getName,
          propertyCount = properties.size,
          modifiedTime = file.lastModified(),
          properties = properties.map { case (key, value) => key -> redact(key, value) })
      }
  }

  private def userDefaults(): Seq[UserDefaults] = {
    val grouped =
      scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, String]]()
    fe.getConf.getAllUserDefaults.foreach { case (key, value) =>
      val end = key.indexOf(USER_DEFAULTS_CONF_QUOTE, USER_DEFAULTS_CONF_QUOTE.length)
      if (key.startsWith(USER_DEFAULTS_CONF_QUOTE) && end > USER_DEFAULTS_CONF_QUOTE.length) {
        val user = key.substring(USER_DEFAULTS_CONF_QUOTE.length, end)
        val property = key.substring(end + USER_DEFAULTS_CONF_QUOTE.length).stripPrefix(".")
        if (property.nonEmpty) {
          val values = grouped.getOrElseUpdate(user, scala.collection.mutable.Map.empty)
          values.put(property, redact(property, value))
        }
      }
    }
    grouped.toSeq.sortBy(_._1).map { case (user, values) =>
      UserDefaults(user, values.toMap)
    }
  }

  private def redact(key: String, value: String): String = {
    if ("(?i).*(password|passwd|secret|token|access[.]key|private[.]key).*".r
        .pattern.matcher(key).matches()) {
      "******"
    } else {
      value
    }
  }

  private def configDirectory: Option[File] = {
    Utils.getPropertiesFile(KYUUBI_CONF_FILE_NAME)
      .map(_.getParentFile)
      .orElse(sys.env.get(KYUUBI_CONF_DIR).map(new File(_)))
      .orElse(sys.env.get(KYUUBI_HOME_ENV_VAR_NAME).map(home => new File(home, "conf")))
  }
}

case class AdminPolicies(
    profiles: Seq[SessionProfile],
    userDefaults: Seq[UserDefaults],
    access: AccessPolicies)

case class SessionProfile(
    name: String,
    fileName: String,
    propertyCount: Int,
    modifiedTime: Long,
    properties: Map[String, String])

case class UserDefaults(user: String, properties: Map[String, String])

case class AccessPolicies(
    unlimitedUsers: Seq[String],
    denyUsers: Seq[String],
    denyIps: Seq[String])

case class AdminPoliciesUpdate(
    userDefaults: Seq[UserDefaultsUpdate] = Seq.empty,
    profiles: Seq[SessionProfileUpdate] = Seq.empty,
    access: AccessPolicies = null)

case class UserDefaultsUpdate(
    user: String,
    properties: Map[String, String] = Map.empty,
    delete: Boolean = false)

case class SessionProfileUpdate(
    name: String,
    properties: Map[String, String] = Map.empty,
    delete: Boolean = false)
