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

import java.io.{BufferedReader, InputStream, InputStreamReader, OutputStream}
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets
import java.util.Hashtable
import javax.naming.{Context, NamingEnumeration}
import javax.naming.directory.{Attributes, InitialDirContext, SearchControls, SearchResult}
import javax.naming.ldap.Rdn
import javax.security.sasl.AuthenticationException

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule

private[server] case class IdentitySubject(
    providerId: String,
    id: String,
    name: String,
    displayName: String,
    email: String,
    subjectType: String,
    groups: Seq[String] = Seq.empty,
    members: Seq[String] = Seq.empty)

private[server] case class IdentityProviderTestResult(
    success: Boolean,
    message: String,
    latencyMillis: Long,
    subjectCount: Int)

private[server] object IdentityDirectoryService {
  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
  private val MaxSubjects = 500

  def test(provider: IdentityProviderConfig): IdentityProviderTestResult = {
    val startedAt = System.nanoTime()
    try {
      val count = subjects(provider, "", "USER", 1).size
      IdentityProviderTestResult(
        success = true,
        message = "Connection successful",
        latencyMillis = elapsedMillis(startedAt),
        subjectCount = count)
    } catch {
      case NonFatal(e) =>
        IdentityProviderTestResult(
          success = false,
          message = Option(e.getMessage).getOrElse(e.getClass.getSimpleName),
          latencyMillis = elapsedMillis(startedAt),
          subjectCount = 0)
    }
  }

  def subjects(
      provider: IdentityProviderConfig,
      query: String,
      subjectType: String,
      limit: Int = 200): Seq[IdentitySubject] = {
    if (!provider.enabled) {
      throw new IllegalStateException(s"Identity provider ${provider.id} is disabled")
    }
    val normalizedType = Option(subjectType).getOrElse("USER").toUpperCase
    if (!Set("USER", "GROUP").contains(normalizedType)) {
      throw new IllegalArgumentException("subjectType must be USER or GROUP")
    }
    val boundedLimit = Math.max(1, Math.min(limit, MaxSubjects))
    val result = provider.providerType.toUpperCase match {
      case "LDAP" => ldapSubjects(provider, normalizedType, boundedLimit)
      case "IAM" => iamSubjects(provider, normalizedType, boundedLimit)
      case other =>
        throw new IllegalArgumentException(s"Unsupported identity provider type: $other")
    }
    val normalizedQuery = Option(query).getOrElse("").trim.toLowerCase
    result.filter { subject =>
      normalizedQuery.isEmpty || Seq(subject.name, subject.displayName, subject.email, subject.id)
        .exists(value => Option(value).exists(_.toLowerCase.contains(normalizedQuery)))
    }.take(boundedLimit)
  }

  def authenticate(provider: IdentityProviderConfig, user: String, password: String): Unit = {
    if (!provider.enabled) {
      throw new AuthenticationException(s"Identity provider ${provider.id} is disabled")
    }
    provider.providerType.toUpperCase match {
      case "LDAP" => authenticateLdap(provider, user, password)
      case "IAM" => authenticateIam(provider, user, password)
      case other => throw new AuthenticationException(s"Unsupported identity provider type: $other")
    }
  }

  private def ldapSubjects(
      provider: IdentityProviderConfig,
      subjectType: String,
      limit: Int): Seq[IdentitySubject] = {
    val context = ldapContext(provider, provider.bindDn, configuredSecret(provider))
    val results = ArrayBuffer.empty[IdentitySubject]
    var enumeration: NamingEnumeration[SearchResult] = null
    try {
      val controls = new SearchControls
      controls.setSearchScope(SearchControls.SUBTREE_SCOPE)
      controls.setCountLimit(limit.toLong)
      controls.setReturningAttributes(Array(
        provider.userNameAttribute,
        provider.displayNameAttribute,
        provider.emailAttribute,
        provider.groupNameAttribute,
        "memberOf",
        "member"))
      val filter = if (subjectType == "USER") provider.userFilter else provider.groupFilter
      enumeration = context.search(provider.baseDn, filter, controls)
      while (enumeration.hasMore && results.size < limit) {
        val result = enumeration.next()
        val attributes = result.getAttributes
        val isUser = subjectType == "USER"
        val nameAttribute = if (isUser) provider.userNameAttribute else provider.groupNameAttribute
        val name = attribute(attributes, nameAttribute)
        if (name.nonEmpty) {
          val id =
            try {
              result.getNameInNamespace
            } catch {
              case _: UnsupportedOperationException => s"${result.getName},${provider.baseDn}"
            }
          results += IdentitySubject(
            providerId = provider.id,
            id = id,
            name = name,
            displayName = attribute(attributes, provider.displayNameAttribute, name),
            email = if (isUser) attribute(attributes, provider.emailAttribute) else "",
            subjectType = subjectType,
            groups = if (isUser) attributesValues(attributes, "memberOf") else Seq.empty,
            members = if (isUser) Seq.empty else attributesValues(attributes, "member"))
        }
      }
      results.toSeq
    } finally {
      if (enumeration != null) enumeration.close()
      context.close()
    }
  }

  private def authenticateLdap(
      provider: IdentityProviderConfig,
      user: String,
      password: String): Unit = {
    if (user == null || user.trim.isEmpty || password == null || password.isEmpty) {
      throw new AuthenticationException("LDAP username and password must not be empty")
    }
    var searchContext: InitialDirContext = null
    var userContext: InitialDirContext = null
    try {
      val userDn = if (provider.userDnPattern.nonEmpty) {
        provider.userDnPattern.replace("{0}", Rdn.escapeValue(user).toString)
      } else if (provider.bindDn.nonEmpty) {
        searchContext = ldapContext(provider, provider.bindDn, configuredSecret(provider))
        findUserDn(searchContext, provider, user)
      } else {
        s"${provider.userNameAttribute}=${Rdn.escapeValue(user)},${provider.baseDn}"
      }
      userContext = ldapContext(provider, userDn, password)
    } catch {
      case NonFatal(e) =>
        val error = new AuthenticationException("LDAP authentication failed")
        error.initCause(e)
        throw error
    } finally {
      if (searchContext != null) searchContext.close()
      if (userContext != null) userContext.close()
    }
  }

  private def findUserDn(
      context: InitialDirContext,
      provider: IdentityProviderConfig,
      user: String): String = {
    val controls = new SearchControls
    controls.setSearchScope(SearchControls.SUBTREE_SCOPE)
    controls.setCountLimit(2)
    val filter =
      s"(&${provider.userFilter}(${provider.userNameAttribute}=${escapeLdapFilter(user)}))"
    val matches = context.search(provider.baseDn, filter, controls)
    try {
      if (!matches.hasMore) {
        throw new IllegalArgumentException(s"LDAP user $user was not found")
      }
      val result = matches.next()
      if (matches.hasMore) {
        throw new IllegalArgumentException(s"LDAP user $user is not unique")
      }
      result.getNameInNamespace
    } finally {
      matches.close()
    }
  }

  private def ldapContext(
      provider: IdentityProviderConfig,
      principal: String,
      password: String): InitialDirContext = {
    val environment = new Hashtable[String, String]()
    environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
    environment.put(Context.PROVIDER_URL, provider.endpoint)
    environment.put("com.sun.jndi.ldap.connect.timeout", provider.connectTimeoutMillis.toString)
    environment.put("com.sun.jndi.ldap.read.timeout", provider.readTimeoutMillis.toString)
    if (principal.nonEmpty) {
      environment.put(Context.SECURITY_AUTHENTICATION, "simple")
      environment.put(Context.SECURITY_PRINCIPAL, principal)
      environment.put(Context.SECURITY_CREDENTIALS, password)
    } else {
      environment.put(Context.SECURITY_AUTHENTICATION, "none")
    }
    new InitialDirContext(environment)
  }

  private def iamSubjects(
      provider: IdentityProviderConfig,
      subjectType: String,
      limit: Int): Seq[IdentitySubject] = {
    val path = if (subjectType == "USER") provider.usersPath else provider.groupsPath
    val response = iamRequest(provider, "GET", path, None)
    val root = mapper.readTree(response)
    val array =
      if (root.isArray) root else root.path(if (subjectType == "USER") "users" else "groups")
    if (!array.isArray) {
      throw new IllegalStateException("IAM response must be an array or contain users/groups array")
    }
    array.elements().asScala.take(limit).flatMap { node =>
      val isUser = subjectType == "USER"
      val nameField = if (isUser) provider.userNameField else provider.groupNameField
      val name = text(node, nameField)
      if (name.isEmpty) None
      else Some(IdentitySubject(
        providerId = provider.id,
        id = text(node, provider.idField, name),
        name = name,
        displayName = text(node, provider.displayNameField, name),
        email = if (isUser) text(node, provider.emailField) else "",
        subjectType = subjectType,
        groups = if (isUser) stringArray(node.path("groups")) else Seq.empty,
        members = if (isUser) Seq.empty else stringArray(node.path("members"))))
    }.toSeq
  }

  private def authenticateIam(
      provider: IdentityProviderConfig,
      user: String,
      password: String): Unit = {
    if (user == null || user.trim.isEmpty || password == null || password.isEmpty) {
      throw new AuthenticationException("IAM username and password must not be empty")
    }
    try {
      val body = mapper.writeValueAsString(Map("username" -> user, "password" -> password))
      iamRequest(provider, "POST", provider.authenticationPath, Some(body))
    } catch {
      case NonFatal(e) =>
        val error = new AuthenticationException("IAM authentication failed")
        error.initCause(e)
        throw error
    }
  }

  private def iamRequest(
      provider: IdentityProviderConfig,
      method: String,
      path: String,
      body: Option[String]): String = {
    val connection = new URL(provider.endpoint.stripSuffix("/") + path).openConnection()
      .asInstanceOf[HttpURLConnection]
    connection.setRequestMethod(method)
    connection.setConnectTimeout(provider.connectTimeoutMillis)
    connection.setReadTimeout(provider.readTimeoutMillis)
    connection.setRequestProperty("Accept", "application/json")
    val token = configuredSecret(provider)
    if (token.nonEmpty) connection.setRequestProperty("Authorization", s"Bearer $token")
    body.foreach { value =>
      connection.setDoOutput(true)
      connection.setRequestProperty("Content-Type", "application/json")
      withOutput(connection.getOutputStream)(_.write(value.getBytes(StandardCharsets.UTF_8)))
    }
    try {
      val status = connection.getResponseCode
      val stream = if (status >= 200 && status < 300) connection.getInputStream
      else Option(connection.getErrorStream).getOrElse(connection.getInputStream)
      val response = read(stream)
      if (status < 200 || status >= 300) {
        throw new IllegalStateException(
          s"IAM returned HTTP $status${if (response.nonEmpty) s": $response" else ""}")
      }
      response
    } finally {
      connection.disconnect()
    }
  }

  private def configuredSecret(provider: IdentityProviderConfig): String = {
    if (provider.secretEnvironment.isEmpty) ""
    else sys.env.getOrElse(
      provider.secretEnvironment,
      throw new IllegalStateException(
        s"Environment variable ${provider.secretEnvironment} is not configured"))
  }

  private def attribute(attributes: Attributes, name: String, default: String = ""): String = {
    Option(attributes.get(name)).flatMap(attribute => Option(attribute.get())).map(_.toString)
      .getOrElse(default)
  }

  private def attributesValues(attributes: Attributes, name: String): Seq[String] = {
    Option(attributes.get(name)).toSeq.flatMap { attribute =>
      val values = attribute.getAll
      val result = ArrayBuffer.empty[String]
      try while (values.hasMore) result += values.next().toString
      finally values.close()
      result
    }
  }

  private def text(node: JsonNode, field: String, default: String = ""): String = {
    val value = node.path(field)
    if (value.isMissingNode || value.isNull) default else value.asText(default)
  }

  private def stringArray(node: JsonNode): Seq[String] = {
    if (!node.isArray) Seq.empty else node.elements().asScala.map(_.asText()).toSeq
  }

  private def read(stream: InputStream): String = {
    val reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
    try Iterator.continually(reader.readLine()).takeWhile(_ != null).mkString("\n")
    finally reader.close()
  }

  private def withOutput(stream: OutputStream)(write: OutputStream => Unit): Unit = {
    try write(stream)
    finally stream.close()
  }

  private def escapeLdapFilter(value: String): String = {
    value.flatMap {
      case '\\' => "\\5c"
      case '*' => "\\2a"
      case '(' => "\\28"
      case ')' => "\\29"
      case '\u0000' => "\\00"
      case character => character.toString
    }
  }

  private def elapsedMillis(startedAt: Long): Long =
    (System.nanoTime() - startedAt) / 1000000L
}

/**
 * CUSTOM authentication provider backed by the enabled identity sources configured in the
 * access management console. Configure this class for both Thrift and HTTP basic authentication.
 */
class ManagedIdentityAuthenticationProvider
  extends org.apache.kyuubi.service.authentication.PasswdAuthenticationProvider {

  override def authenticate(user: String, password: String): Unit = {
    val access = IdentityAccessStore.load()
    val providerIds = access.bindings.collect {
      case binding
          if binding.subjectType == "USER" && binding.subjectName == user &&
            binding.access == "ENABLED" => binding.providerId
    }.toSet
    if (providerIds.isEmpty) {
      throw new AuthenticationException("The user does not have an active Kyuubi access binding")
    }
    val providers =
      access.providers.filter(provider => provider.enabled && providerIds(provider.id))
    var lastError: Throwable = null
    providers.iterator.foreach { provider =>
      try {
        IdentityDirectoryService.authenticate(provider, user, password)
        return
      } catch {
        case NonFatal(e) => lastError = e
      }
    }
    val error =
      new AuthenticationException("Authentication failed for all enabled identity providers")
    if (lastError != null) error.initCause(lastError)
    throw error
  }
}
