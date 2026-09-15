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
import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, StandardCopyOption}

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.apache.kyuubi.Utils
import org.apache.kyuubi.config.KyuubiConf.{KYUUBI_CONF_FILE_NAME, SERVER_LIMIT_CONNECTIONS_IP_DENY_LIST, SERVER_LIMIT_CONNECTIONS_USER_DENY_LIST, SERVER_LIMIT_CONNECTIONS_USER_UNLIMITED_LIST, USER_DEFAULTS_CONF_QUOTE}

private[v1] case class PolicyFileException(message: String, status: Int)
  extends RuntimeException(message)

/** File-backed policy updates with atomic replacement of each target file. */
private[v1] object AdminPoliciesFileStore {

  private val ProfilePrefix = "kyuubi-session-"
  private var testConfigurationFile: Option[File] = None

  def configurationFile: Option[File] = synchronized {
    testConfigurationFile.orElse(Utils.getPropertiesFile(KYUUBI_CONF_FILE_NAME))
  }

  private[v1] def setConfigurationFileForTesting(file: Option[File]): Unit = synchronized {
    testConfigurationFile = file
  }

  def validateAccess(access: AccessPolicies): Unit = {
    validateValues(access.unlimitedUsers, "unlimitedUsers")
    validateValues(access.denyUsers, "denyUsers")
    validateValues(access.denyIps, "denyIps")
  }

  def validateUserDefaults(update: UserDefaultsUpdate): Unit = {
    validateUser(update.user)
    validateProperties(update.properties, "userDefaults.properties")
  }

  def validateProfile(update: SessionProfileUpdate): Unit = {
    validateProfileName(update.name)
    validateProperties(update.properties, "profile.properties")
  }

  def updateAccess(configFile: File, access: AccessPolicies): Unit = {
    validateAccess(access)
    rewriteProperties(
      writableFile(configFile),
      Map(
        SERVER_LIMIT_CONNECTIONS_USER_UNLIMITED_LIST.key -> values(access.unlimitedUsers),
        SERVER_LIMIT_CONNECTIONS_USER_DENY_LIST.key -> values(access.denyUsers),
        SERVER_LIMIT_CONNECTIONS_IP_DENY_LIST.key -> values(access.denyIps)))
  }

  def updateUserDefaults(configFile: File, update: UserDefaultsUpdate): Unit = {
    validateUserDefaults(update)
    val prefix = s"$USER_DEFAULTS_CONF_QUOTE${update.user}$USER_DEFAULTS_CONF_QUOTE"
    val replacements =
      if (update.delete) {
        Map.empty[String, String]
      } else {
        update.properties.map { case (key, value) =>
          s"$prefix.$key" -> value
        }
      }
    rewriteProperties(writableFile(configFile), replacements, Seq(prefix))
  }

  def updateProfile(configDirectory: File, update: SessionProfileUpdate): Unit = {
    validateProfile(update)
    val directory = writableDirectory(configDirectory)
    val profileFile = new File(directory, s"$ProfilePrefix${update.name}.conf")
    if (update.delete) {
      try {
        Files.deleteIfExists(profileFile.toPath)
      } catch {
        case e: java.io.IOException =>
          throw PolicyFileException(
            s"Failed to delete Session Profile ${update.name}: ${e.getMessage}",
            500)
      }
    } else {
      val lines = update.properties.toSeq.sortBy(_._1).map { case (key, value) =>
        s"${escape(key)}=${escape(value)}"
      }
      writeAtomically(profileFile, lines)
    }
  }

  def updateProperties(configFile: File, replacements: Map[String, String]): Unit = {
    replacements.foreach { case (key, value) =>
      validateToken(key, "configuration key")
      validateValue(value, s"configuration value for $key")
    }
    rewriteProperties(writableFile(configFile), replacements)
  }

  private def writableFile(file: File): File = {
    if (file == null || !file.isFile || !file.canWrite) {
      throw PolicyFileException(
        "The Kyuubi defaults configuration file is not writable; configure " +
          "KYUUBI_CONF_DIR or KYUUBI_HOME before using policy updates.",
        409)
    }
    file
  }

  private def writableDirectory(directory: File): File = {
    if (directory == null || !directory.isDirectory || !directory.canWrite) {
      throw PolicyFileException(
        "The Kyuubi configuration directory is not writable; configure " +
          "KYUUBI_CONF_DIR or KYUUBI_HOME before using policy updates.",
        409)
    }
    directory
  }

  private def validateUser(user: String): Unit = {
    validateToken(user, "user")
    if (user.contains(USER_DEFAULTS_CONF_QUOTE)) {
      throw PolicyFileException("user must not contain the reserved delimiter ___", 400)
    }
  }

  private def validateProfileName(name: String): Unit = {
    validateToken(name, "profile name")
    if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      throw PolicyFileException(
        "profile name may contain only letters, numbers, '.', '_' and '-'",
        400)
    }
  }

  private def validateValues(values: Seq[String], field: String): Unit = {
    Option(values).getOrElse(Seq.empty).foreach { value =>
      validateToken(value, s"$field entry")
      if (value.contains(",")) {
        throw PolicyFileException(s"$field entries must not contain commas", 400)
      }
    }
  }

  private def validateProperties(properties: Map[String, String], field: String): Unit = {
    Option(properties).getOrElse(Map.empty).foreach { case (key, value) =>
      validateToken(key, s"$field key")
      if (key.exists(_.isWhitespace) || key.exists(c => c == '=' || c == ':')) {
        throw PolicyFileException(s"$field key '$key' contains an invalid character", 400)
      }
      validateValue(value, s"$field value for $key")
    }
  }

  private def validateToken(value: String, field: String): Unit = {
    if (value == null || value.trim.isEmpty || value.exists(_.isWhitespace) ||
      value.exists(c => c == '\r' || c == '\n')) {
      throw PolicyFileException(s"$field must be a non-empty single-line value", 400)
    }
  }

  private def validateValue(value: String, field: String): Unit = {
    if (value == null || value.exists(c => c == '\r' || c == '\n')) {
      throw PolicyFileException(s"$field must not contain line breaks", 400)
    }
  }

  private def values(values: Seq[String]): String =
    Option(values).getOrElse(Seq.empty).map(_.trim).filter(_.nonEmpty).distinct.sorted.mkString(",")

  private def rewriteProperties(
      file: File,
      replacements: Map[String, String],
      removePrefixes: Seq[String] = Seq.empty): Unit = {
    val lines = Files.readAllLines(file.toPath, StandardCharsets.UTF_8).asScala.toSeq
    val pending = mutable.LinkedHashMap(replacements.toSeq: _*)
    val output = mutable.ArrayBuffer.empty[String]
    lines.foreach { line =>
      propertyKey(line) match {
        case Some(key) if removePrefixes.exists(key.startsWith) =>
        case Some(key) if pending.contains(key) =>
          output += s"${escape(key)}=${escape(pending.remove(key).get)}"
        case _ =>
          output += line
      }
    }
    pending.toSeq.sortBy(_._1).foreach { case (key, value) =>
      output += s"${escape(key)}=${escape(value)}"
    }
    writeAtomically(file, output.toSeq)
  }

  private def propertyKey(line: String): Option[String] = {
    val value = line.trim
    if (value.isEmpty || value.startsWith("#") || value.startsWith("!")) {
      None
    } else {
      val index = value.indexWhere(c => c == '=' || c == ':' || c.isWhitespace)
      Some(if (index < 0) value else value.substring(0, index))
    }
  }

  private def escape(value: String): String =
    value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

  private def writeAtomically(file: File, lines: Seq[String]): Unit = {
    val directory = file.getParentFile.toPath
    val temporary = Files.createTempFile(directory, s".${file.getName}.", ".tmp")
    try {
      Files.write(
        temporary,
        lines.mkString(System.lineSeparator()).getBytes(StandardCharsets.UTF_8))
      try {
        Files.move(
          temporary,
          file.toPath,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING)
      } catch {
        case _: AtomicMoveNotSupportedException =>
          Files.move(temporary, file.toPath, StandardCopyOption.REPLACE_EXISTING)
      }
    } catch {
      case e: java.io.IOException =>
        throw PolicyFileException(s"Failed to write ${file.getName}: ${e.getMessage}", 500)
    } finally {
      Files.deleteIfExists(temporary)
    }
  }
}
