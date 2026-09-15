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

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import org.apache.kyuubi.Utils
import org.apache.kyuubi.config.KyuubiConf.KYUUBI_CONF_FILE_NAME

private[server] case class IdentityProviderConfig(
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
    readTimeoutMillis: Int = 5000)

private[server] case class IdentityBinding(
    id: String,
    providerId: String,
    subjectId: String,
    subjectName: String,
    subjectType: String = "USER",
    access: String = "ENABLED",
    role: String = "",
    profile: String = "",
    quotaExempt: Boolean = false,
    userDefaults: Map[String, String] = Map.empty)

private[server] case class IdentityAccessFile(
    providers: Seq[IdentityProviderConfig] = Seq.empty,
    bindings: Seq[IdentityBinding] = Seq.empty)

private[server] object IdentityAccessStore {
  private val FileName = "kyuubi-identity-access.json"
  private val PathEnvironment = "KYUUBI_IDENTITY_ACCESS_PATH"
  private val TokenPattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}".r
  private val EnvironmentPattern = "[A-Za-z_][A-Za-z0-9_]{0,127}".r
  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
  private var testFile: Option[File] = None

  def load(): IdentityAccessFile = synchronized {
    file.filter(_.isFile).map(read).getOrElse(IdentityAccessFile())
  }

  def upsertProvider(provider: IdentityProviderConfig): IdentityAccessFile = synchronized {
    validateProvider(provider)
    val current = load()
    val next = current.copy(providers =
      (current.providers.filterNot(_.id == provider.id) :+ provider).sortBy(_.name))
    write(next)
    next
  }

  def deleteProvider(id: String): IdentityAccessFile = synchronized {
    validateToken(id, "identity provider id")
    val current = load()
    if (current.bindings.exists(_.providerId == id)) {
      throw new IllegalStateException(
        s"Identity provider $id still has access bindings; remove the bindings first")
    }
    val next = current.copy(providers = current.providers.filterNot(_.id == id))
    write(next)
    next
  }

  def upsertBinding(binding: IdentityBinding): IdentityAccessFile = synchronized {
    validateBinding(binding)
    val current = load()
    if (!current.providers.exists(_.id == binding.providerId)) {
      throw new IllegalArgumentException(s"Unknown identity provider: ${binding.providerId}")
    }
    val duplicate = current.bindings.exists { candidate =>
      candidate.id != binding.id && candidate.providerId == binding.providerId &&
      candidate.subjectType == binding.subjectType && candidate.subjectId == binding.subjectId
    }
    if (duplicate) {
      throw new IllegalArgumentException("The identity subject already has an access binding")
    }
    val ambiguousPrincipal = current.bindings.exists { candidate =>
      candidate.id != binding.id && candidate.subjectType == "USER" &&
      candidate.subjectName == binding.subjectName
    }
    if (ambiguousPrincipal) {
      throw new IllegalArgumentException(
        s"Kyuubi principal ${binding.subjectName} is already bound to another identity")
    }
    val next = current.copy(bindings =
      (current.bindings.filterNot(_.id == binding.id) :+ binding).sortBy(_.subjectName))
    write(next)
    next
  }

  def deleteBinding(id: String): IdentityAccessFile = synchronized {
    validateToken(id, "identity binding id")
    val current = load()
    val next = current.copy(bindings = current.bindings.filterNot(_.id == id))
    write(next)
    next
  }

  private[server] def setFileForTesting(value: Option[File]): Unit = synchronized {
    testFile = value
  }

  private def file: Option[File] = {
    testFile
      .orElse(sys.env.get(PathEnvironment).map(new File(_)))
      .orElse(Utils.getPropertiesFile(KYUUBI_CONF_FILE_NAME)
        .map(config => new File(config.getParentFile, FileName)))
  }

  private def read(target: File): IdentityAccessFile = {
    try {
      mapper.readValue(target, classOf[IdentityAccessFile])
    } catch {
      case e: Exception =>
        throw new IllegalStateException(
          s"Unable to read identity access file ${target.getAbsolutePath}",
          e)
    }
  }

  private def write(content: IdentityAccessFile): Unit = {
    val target = file.getOrElse {
      throw new IllegalStateException(
        "The identity access file cannot be located; configure KYUUBI_CONF_DIR, " +
          "KYUUBI_HOME, or KYUUBI_IDENTITY_ACCESS_PATH")
    }
    Option(target.getParentFile).foreach(_.mkdirs())
    val temporary = new File(target.getParentFile, s".${target.getName}.${System.nanoTime()}.tmp")
    try {
      Files.write(
        temporary.toPath,
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(content)
          .getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE)
      try {
        Files.move(
          temporary.toPath,
          target.toPath,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING)
      } catch {
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          Files.move(
            temporary.toPath,
            target.toPath,
            StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary.toPath)
    }
  }

  private def validateProvider(provider: IdentityProviderConfig): Unit = {
    validateToken(provider.id, "identity provider id")
    if (provider.name == null || provider.name.trim.isEmpty || provider.name.length > 128) {
      throw new IllegalArgumentException(
        "Identity provider name must be between 1 and 128 characters")
    }
    val providerType = Option(provider.providerType).map(_.toUpperCase).getOrElse("")
    if (!Set("LDAP", "IAM").contains(providerType)) {
      throw new IllegalArgumentException(s"Unsupported identity provider type: $providerType")
    }
    val endpoint =
      try {
        new URI(provider.endpoint)
      } catch {
        case _: Exception =>
          throw new IllegalArgumentException("Identity provider endpoint is invalid")
      }
    val schemes = if (providerType == "LDAP") Set("ldap", "ldaps") else Set("http", "https")
    if (!schemes.contains(Option(endpoint.getScheme).map(_.toLowerCase).orNull) ||
      endpoint.getHost == null) {
      throw new IllegalArgumentException(
        s"$providerType endpoint must use ${schemes.toSeq.sorted.mkString(" or ")}")
    }
    if (providerType == "LDAP" && provider.baseDn.trim.isEmpty) {
      throw new IllegalArgumentException("LDAP base DN must not be empty")
    }
    if (provider.secretEnvironment.nonEmpty &&
      !EnvironmentPattern.pattern.matcher(provider.secretEnvironment).matches()) {
      throw new IllegalArgumentException("Secret environment variable name is invalid")
    }
    Seq(provider.usersPath, provider.groupsPath, provider.authenticationPath).foreach { path =>
      if (providerType == "IAM" && (path == null || !path.startsWith("/") || path.contains(".."))) {
        throw new IllegalArgumentException("IAM paths must be absolute paths without '..'")
      }
    }
    if (provider.connectTimeoutMillis < 100 || provider.connectTimeoutMillis > 60000 ||
      provider.readTimeoutMillis < 100 || provider.readTimeoutMillis > 60000) {
      throw new IllegalArgumentException(
        "Identity provider timeouts must be between 100 and 60000 ms")
    }
  }

  private def validateBinding(binding: IdentityBinding): Unit = {
    validateToken(binding.id, "identity binding id")
    validateToken(binding.providerId, "identity provider id")
    if (binding.subjectType != "USER") {
      throw new IllegalArgumentException("Only USER bindings are supported")
    }
    if (binding.subjectId == null || binding.subjectId.trim.isEmpty || binding.subjectId.length > 512) {
      throw new IllegalArgumentException("Identity subject id must be between 1 and 512 characters")
    }
    if (binding.subjectName == null || binding.subjectName.trim.isEmpty ||
      binding.subjectName.exists(_.isWhitespace) || binding.subjectName.length > 256) {
      throw new IllegalArgumentException("Identity subject name is invalid")
    }
    if (!Set("ENABLED", "DENIED").contains(binding.access)) {
      throw new IllegalArgumentException("Binding access must be ENABLED or DENIED")
    }
    if (binding.role.nonEmpty && AdminRole.fromName(binding.role).isEmpty) {
      throw new IllegalArgumentException(s"Unknown admin role: ${binding.role}")
    }
    binding.userDefaults.foreach { case (key, value) =>
      if (key == null || key.trim.isEmpty || key.exists(_.isWhitespace) || key.contains("=") ||
        value == null || value.exists(c => c == '\r' || c == '\n')) {
        throw new IllegalArgumentException(s"Invalid user default property: $key")
      }
    }
  }

  private def validateToken(value: String, label: String): Unit = {
    if (value == null || !TokenPattern.pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(s"Invalid $label: $value")
    }
  }
}
