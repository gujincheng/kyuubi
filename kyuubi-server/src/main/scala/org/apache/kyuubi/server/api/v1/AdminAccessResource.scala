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
import java.util.UUID
import javax.ws.rs.{BadRequestException, DELETE, GET, NotFoundException, Path, PathParam, POST, Produces, PUT, QueryParam, WebApplicationException}
import javax.ws.rs.core.{MediaType, Response}

import scala.util.control.NonFatal

import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.{AUTHENTICATION_CUSTOM_BASIC_CLASS, AUTHENTICATION_CUSTOM_CLASS, AUTHENTICATION_METHOD, SERVER_ADMINISTRATORS, SERVER_LIMIT_CONNECTIONS_USER_DENY_LIST, SERVER_LIMIT_CONNECTIONS_USER_UNLIMITED_LIST, SESSION_CONF_PROFILE, USER_DEFAULTS_CONF_QUOTE}
import org.apache.kyuubi.server.{AdminPermissionService, AdminPermissionStore, AdminRole, AuditRecordStore, IdentityAccessFile, IdentityAccessStore, IdentityBinding, IdentityDirectoryService, IdentityProviderConfig, IdentityProviderTestResult, IdentitySubject, ManagedIdentityAuthenticationProvider, PermissionAssignment}
import org.apache.kyuubi.server.api.ApiRequestContext
import org.apache.kyuubi.session.KyuubiSessionManager

@Tag(name = "Access Management")
@Path("access")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class AdminAccessResource extends ApiRequestContext {

  @GET
  def access(): AdminAccess = {
    AdminPermissionService.require(fe, "access", AdminRole.Read)
    view(IdentityAccessStore.load())
  }

  @PUT
  @Path("providers")
  def upsertProvider(request: IdentityProviderUpdate): AdminAccess = {
    val actor = AdminPermissionService.require(fe, "access", AdminRole.Write)
    if (request == null) throw new BadRequestException("identity provider body must not be empty")
    val provider = request.toConfig
    val updated = handleStoreError(IdentityAccessStore.upsertProvider(provider))
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "access.identity-provider.upsert",
      "/api/v1/admin/access/providers")
    view(updated)
  }

  @DELETE
  @Path("providers/{id}")
  def deleteProvider(@PathParam("id") id: String): AdminAccess = {
    val actor = AdminPermissionService.require(fe, "access", AdminRole.Delete)
    val current = IdentityAccessStore.load()
    if (!current.providers.exists(_.id == id)) {
      throw new NotFoundException(s"Identity provider $id was not found")
    }
    val updated = handleStoreError(IdentityAccessStore.deleteProvider(id))
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "access.identity-provider.delete",
      s"/api/v1/admin/access/providers/$id")
    view(updated)
  }

  @POST
  @Path("providers/{id}/test")
  def testProvider(@PathParam("id") id: String): IdentityProviderTestResult = {
    AdminPermissionService.require(fe, "access", AdminRole.Refresh)
    val provider = providerById(id)
    val result = IdentityDirectoryService.test(provider)
    if (!result.success) {
      throw new WebApplicationException(
        result.message,
        Response.status(Response.Status.BAD_GATEWAY).entity(result).build())
    }
    result
  }

  @GET
  @Path("providers/{id}/subjects")
  def subjects(
      @PathParam("id") id: String,
      @QueryParam("query") query: String,
      @QueryParam("subjectType") subjectType: String,
      @QueryParam("limit") requestedLimit: Integer): IdentitySubjects = {
    AdminPermissionService.require(fe, "access", AdminRole.Read)
    val provider = providerById(id)
    val limit = Option(requestedLimit).map(_.intValue()).getOrElse(200)
    try {
      val values = IdentityDirectoryService.subjects(
        provider,
        Option(query).getOrElse(""),
        Option(subjectType).filter(_.nonEmpty).getOrElse("USER"),
        limit)
      IdentitySubjects(provider.id, values, System.currentTimeMillis())
    } catch {
      case e: IllegalArgumentException => throw new BadRequestException(e.getMessage)
      case NonFatal(e) =>
        throw new WebApplicationException(
          Option(e.getMessage).getOrElse("Unable to query identity provider"),
          Response.status(Response.Status.BAD_GATEWAY)
            .entity(Option(e.getMessage).getOrElse("Unable to query identity provider"))
            .build())
    }
  }

  @PUT
  @Path("bindings")
  def upsertBinding(request: IdentityBindingUpdate): AdminAccess = {
    val actor = AdminPermissionService.require(fe, "access", AdminRole.Write)
    if (request == null) throw new BadRequestException("identity binding body must not be empty")
    val id = Option(request.id).map(_.trim).filter(_.nonEmpty)
      .getOrElse(UUID.randomUUID().toString)
    val binding = request.toBinding(id)
    val current = IdentityAccessStore.load()
    val provider = current.providers.find(_.id == binding.providerId).getOrElse {
      throw new BadRequestException(s"Unknown identity provider: ${binding.providerId}")
    }
    ensureSubjectExists(provider, binding)
    ensureProfileExists(binding.profile)
    val previous = current.bindings.find(_.id == id)
    val updated = handleStoreError(IdentityAccessStore.upsertBinding(binding))
    try {
      applyBinding(binding)
    } catch {
      case NonFatal(e) =>
        previous match {
          case Some(value) => IdentityAccessStore.upsertBinding(value)
          case None => IdentityAccessStore.deleteBinding(id)
        }
        throw policyFailure(e)
    }
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "access.binding.upsert",
      "/api/v1/admin/access/bindings")
    view(updated)
  }

  @DELETE
  @Path("bindings/{id}")
  def deleteBinding(@PathParam("id") id: String): AdminAccess = {
    val actor = AdminPermissionService.require(fe, "access", AdminRole.Delete)
    val current = IdentityAccessStore.load()
    val binding = current.bindings.find(_.id == id).getOrElse {
      throw new NotFoundException(s"Identity binding $id was not found")
    }
    val updated = handleStoreError(IdentityAccessStore.deleteBinding(id))
    try {
      removeBinding(binding)
    } catch {
      case NonFatal(e) =>
        IdentityAccessStore.upsertBinding(binding)
        throw policyFailure(e)
    }
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "access.binding.delete",
      s"/api/v1/admin/access/bindings/$id")
    view(updated)
  }

  @POST
  @Path("activate")
  def activate(): ManagedAuthenticationStatus = {
    val actor = AdminPermissionService.require(fe, "access", AdminRole.Manage)
    val access = IdentityAccessStore.load()
    val enabledProviderIds = access.providers.filter(_.enabled).map(_.id).toSet
    if (enabledProviderIds.isEmpty) {
      throw new BadRequestException(
        "Enable at least one identity provider before activating managed authentication")
    }

    val managedAdministrators = access.bindings.collect {
      case binding
          if enabledProviderIds(binding.providerId) && binding.subjectType == "USER" &&
            binding.access == "ENABLED" && binding.role == AdminRole.PlatformAdminName =>
        binding.subjectName.trim
    }.filter(_.nonEmpty).toSet
    if (managedAdministrators.isEmpty) {
      throw new BadRequestException(
        "Bind at least one enabled platform administrator before activating " +
          "managed authentication")
    }

    val configFile = writableConfigFile()
    val className = classOf[ManagedIdentityAuthenticationProvider].getName
    val administrators =
      (fe.getConf.get(SERVER_ADMINISTRATORS) ++ managedAdministrators).toSeq.sorted
    AdminPoliciesFileStore.updateProperties(
      configFile,
      Map(
        AUTHENTICATION_METHOD.key -> "CUSTOM",
        AUTHENTICATION_CUSTOM_CLASS.key -> className,
        AUTHENTICATION_CUSTOM_BASIC_CLASS.key -> className,
        SERVER_ADMINISTRATORS.key -> administrators.mkString(",")))
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "access.managed-authentication.activate",
      "/api/v1/admin/access/activate")
    ManagedAuthenticationStatus(
      active = false,
      restartRequired = true,
      className = className,
      message = "Configuration saved. Restart Kyuubi Server to activate managed authentication.")
  }

  @POST
  @Path("deactivate")
  def deactivate(): ManagedAuthenticationStatus = {
    val actor = AdminPermissionService.require(fe, "access", AdminRole.Manage)
    val configFile = writableConfigFile()
    val className = classOf[ManagedIdentityAuthenticationProvider].getName
    AdminPoliciesFileStore.updateProperties(
      configFile,
      Map(AUTHENTICATION_METHOD.key -> "NONE"))
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "access.managed-authentication.deactivate",
      "/api/v1/admin/access/deactivate")
    ManagedAuthenticationStatus(
      active = false,
      restartRequired = true,
      className = className,
      message = "Configuration saved. Restart Kyuubi Server to deactivate managed authentication.")
  }

  private def view(content: IdentityAccessFile): AdminAccess = {
    AdminAccess(
      providers = content.providers.map { provider =>
        IdentityProviderView(
          provider = provider,
          secretConfigured = provider.secretEnvironment.isEmpty ||
            sys.env.contains(provider.secretEnvironment))
      },
      bindings = content.bindings,
      authentication = authenticationStatus)
  }

  private def authenticationStatus: ManagedAuthenticationStatus = {
    val className = classOf[ManagedIdentityAuthenticationProvider].getName
    val methods = fe.getConf.get(AUTHENTICATION_METHOD)
    val customClass = fe.getConf.get(AUTHENTICATION_CUSTOM_BASIC_CLASS)
      .orElse(fe.getConf.get(AUTHENTICATION_CUSTOM_CLASS))
    val active = methods.contains("CUSTOM") && customClass.contains(className)
    ManagedAuthenticationStatus(
      active = active,
      restartRequired = false,
      className = className,
      message = if (active) "Managed authentication is active"
      else
        "Managed authentication has not been activated")
  }

  private def providerById(id: String): IdentityProviderConfig = {
    IdentityAccessStore.load().providers.find(_.id == id).getOrElse {
      throw new NotFoundException(s"Identity provider $id was not found")
    }
  }

  private def ensureSubjectExists(
      provider: IdentityProviderConfig,
      binding: IdentityBinding): Unit = {
    val exists =
      try {
        IdentityDirectoryService.subjects(provider, binding.subjectName, binding.subjectType, 200)
          .exists(subject => subject.id == binding.subjectId && subject.name == binding.subjectName)
      } catch {
        case NonFatal(e) => throw policyFailure(e)
      }
    if (!exists) throw new BadRequestException("The identity subject no longer exists")
  }

  private def ensureProfileExists(profile: String): Unit = {
    if (profile.nonEmpty) {
      val file = AdminPoliciesFileStore.configurationFile
        .map(config => new File(config.getParentFile, s"kyuubi-session-$profile.conf"))
      if (!file.exists(_.isFile)) {
        throw new BadRequestException(s"Session Profile $profile was not found")
      }
    }
  }

  private def applyBinding(binding: IdentityBinding): Unit = {
    updateRole(binding.subjectName, Option(binding.role).filter(_.nonEmpty))
    updateAccess(binding.subjectName, binding.access == "DENIED", binding.quotaExempt)
    val properties = binding.userDefaults ++
      Option(binding.profile).filter(_.nonEmpty).map(SESSION_CONF_PROFILE.key -> _)
    updateUserDefaults(binding.subjectName, properties)
  }

  private def removeBinding(binding: IdentityBinding): Unit = {
    updateRole(binding.subjectName, None)
    updateAccess(binding.subjectName, denied = false, quotaExempt = false)
    updateUserDefaults(binding.subjectName, Map.empty)
  }

  private def updateRole(user: String, role: Option[String]): Unit = {
    val current = AdminPermissionStore.load().filterNot(_.user == user)
    val updated = role.map(value => current :+ PermissionAssignment(user, value)).getOrElse(current)
    AdminPermissionStore.replace(updated)
  }

  private def updateAccess(user: String, denied: Boolean, quotaExempt: Boolean): Unit = {
    val manager = fe.be.sessionManager.asInstanceOf[KyuubiSessionManager]
    val denyUsers = changed(manager.getDenyUsers.toSeq, user, denied)
    val unlimitedUsers = changed(manager.getUnlimitedUsers.toSeq, user, quotaExempt)
    val refreshed = AccessPolicies(
      unlimitedUsers = unlimitedUsers,
      denyUsers = denyUsers,
      denyIps = manager.getDenyIps.toSeq.sorted)
    AdminPoliciesFileStore.updateAccess(writableConfigFile(), refreshed)
    val updatedConf = KyuubiConf(loadSysDefault = false)
      .set(SERVER_LIMIT_CONNECTIONS_USER_UNLIMITED_LIST, unlimitedUsers.toSet)
      .set(SERVER_LIMIT_CONNECTIONS_USER_DENY_LIST, denyUsers.toSet)
    manager.refreshUnlimitedUsers(updatedConf)
    manager.refreshDenyUsers(updatedConf)
  }

  private def changed(values: Seq[String], user: String, included: Boolean): Seq[String] = {
    val without = values.filterNot(_ == user)
    (if (included) without :+ user else without).distinct.sorted
  }

  private def updateUserDefaults(user: String, properties: Map[String, String]): Unit = {
    AdminPoliciesFileStore.updateUserDefaults(
      writableConfigFile(),
      UserDefaultsUpdate(user, properties, delete = properties.isEmpty))
    val manager = fe.be.sessionManager.asInstanceOf[KyuubiSessionManager]
    val runtimeConf = manager.getConf
    val prefix = s"$USER_DEFAULTS_CONF_QUOTE$user$USER_DEFAULTS_CONF_QUOTE"
    runtimeConf.getAllUserDefaults.keys.filter(_.startsWith(prefix)).foreach(runtimeConf.unset)
    properties.foreach { case (key, value) =>
      runtimeConf.set(s"$prefix.$key", value)
    }
  }

  private def writableConfigFile(): File = {
    AdminPoliciesFileStore.configurationFile.getOrElse {
      throw new IllegalStateException(
        "The Kyuubi defaults configuration file cannot be located; configure " +
          "KYUUBI_CONF_DIR or KYUUBI_HOME")
    }
  }

  private def handleStoreError[T](action: => T): T = {
    try action
    catch {
      case e: IllegalArgumentException => throw new BadRequestException(e.getMessage)
      case e: IllegalStateException =>
        throw new WebApplicationException(
          e.getMessage,
          Response.status(Response.Status.CONFLICT).entity(e.getMessage).build())
    }
  }

  private def policyFailure(error: Throwable): WebApplicationException = {
    new WebApplicationException(
      Option(error.getMessage).getOrElse("Unable to update Kyuubi access policy"),
      Response.status(Response.Status.CONFLICT)
        .entity(Option(error.getMessage).getOrElse("Unable to update Kyuubi access policy"))
        .build())
  }
}

case class AdminAccess(
    providers: Seq[IdentityProviderView],
    bindings: Seq[IdentityBinding],
    authentication: ManagedAuthenticationStatus)

case class IdentityProviderView(
    provider: IdentityProviderConfig,
    secretConfigured: Boolean)

case class IdentitySubjects(
    providerId: String,
    subjects: Seq[IdentitySubject],
    generatedAt: Long)

case class ManagedAuthenticationStatus(
    active: Boolean,
    restartRequired: Boolean,
    className: String,
    message: String)

case class IdentityProviderUpdate(
    id: String,
    name: String,
    providerType: String,
    enabled: Boolean = true,
    endpoint: String,
    baseDn: String = "",
    bindDn: String = "",
    secretEnvironment: String = "",
    userFilter: String = "(objectClass=person)",
    groupFilter: String = "(objectClass=groupOfNames)",
    userNameAttribute: String = "uid",
    displayNameAttribute: String = "cn",
    emailAttribute: String = "mail",
    groupNameAttribute: String = "cn",
    userDnPattern: String = "",
    usersPath: String = "/users",
    groupsPath: String = "/groups",
    authenticationPath: String = "/authenticate",
    idField: String = "id",
    userNameField: String = "username",
    displayNameField: String = "displayName",
    emailField: String = "email",
    groupNameField: String = "name",
    connectTimeoutMillis: Int = 5000,
    readTimeoutMillis: Int = 5000) {

  def toConfig: IdentityProviderConfig = IdentityProviderConfig(
    id = Option(id).map(_.trim).getOrElse(""),
    name = Option(name).map(_.trim).getOrElse(""),
    providerType = Option(providerType).map(_.trim.toUpperCase).getOrElse(""),
    enabled = enabled,
    endpoint = Option(endpoint).map(_.trim).getOrElse(""),
    baseDn = Option(baseDn).map(_.trim).getOrElse(""),
    bindDn = Option(bindDn).map(_.trim).getOrElse(""),
    secretEnvironment = Option(secretEnvironment).map(_.trim).getOrElse(""),
    userFilter = Option(userFilter).filter(_.trim.nonEmpty).getOrElse("(objectClass=person)"),
    groupFilter =
      Option(groupFilter).filter(_.trim.nonEmpty).getOrElse("(objectClass=groupOfNames)"),
    userNameAttribute = Option(userNameAttribute).filter(_.trim.nonEmpty).getOrElse("uid"),
    displayNameAttribute = Option(displayNameAttribute).filter(_.trim.nonEmpty).getOrElse("cn"),
    emailAttribute = Option(emailAttribute).filter(_.trim.nonEmpty).getOrElse("mail"),
    groupNameAttribute = Option(groupNameAttribute).filter(_.trim.nonEmpty).getOrElse("cn"),
    userDnPattern = Option(userDnPattern).map(_.trim).getOrElse(""),
    usersPath = Option(usersPath).filter(_.trim.nonEmpty).getOrElse("/users"),
    groupsPath = Option(groupsPath).filter(_.trim.nonEmpty).getOrElse("/groups"),
    authenticationPath = Option(authenticationPath).filter(_.trim.nonEmpty)
      .getOrElse("/authenticate"),
    idField = Option(idField).filter(_.trim.nonEmpty).getOrElse("id"),
    userNameField = Option(userNameField).filter(_.trim.nonEmpty).getOrElse("username"),
    displayNameField = Option(displayNameField).filter(_.trim.nonEmpty).getOrElse("displayName"),
    emailField = Option(emailField).filter(_.trim.nonEmpty).getOrElse("email"),
    groupNameField = Option(groupNameField).filter(_.trim.nonEmpty).getOrElse("name"),
    connectTimeoutMillis = connectTimeoutMillis,
    readTimeoutMillis = readTimeoutMillis)
}

case class IdentityBindingUpdate(
    id: String = "",
    providerId: String,
    subjectId: String,
    subjectName: String,
    subjectType: String = "USER",
    access: String = "ENABLED",
    role: String = "",
    profile: String = "",
    quotaExempt: Boolean = false,
    userDefaults: Map[String, String] = Map.empty) {

  def toBinding(bindingId: String): IdentityBinding = IdentityBinding(
    id = bindingId,
    providerId = Option(providerId).map(_.trim).getOrElse(""),
    subjectId = Option(subjectId).map(_.trim).getOrElse(""),
    subjectName = Option(subjectName).map(_.trim).getOrElse(""),
    subjectType = Option(subjectType).map(_.trim.toUpperCase).getOrElse("USER"),
    access = Option(access).map(_.trim.toUpperCase).getOrElse("ENABLED"),
    role = Option(role).map(_.trim).getOrElse(""),
    profile = Option(profile).map(_.trim).getOrElse(""),
    quotaExempt = quotaExempt,
    userDefaults = Option(userDefaults).getOrElse(Map.empty))
}
