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

import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._

import org.apache.kyuubi.{KyuubiException, Logging, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.digiwin.security.CredentialAccessor
import org.apache.kyuubi.util.ThreadUtils

class DatasourceRegistry(conf: KyuubiConf) extends Logging {

  private val store = new DatasourceStore(conf)
  private val credentialAccessor =
    CredentialAccessor(conf.get(DIGIWIN_DATASOURCE_CREDENTIAL_SECRET))
  private val cache = new ConcurrentHashMap[String, DatasourceInfo]()

  private val scheduler =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("digiwin-datasource-refresher")

  def start(): Unit = {
    bootstrapFromFile()
    refresh()
    val interval = conf.get(DIGIWIN_DATASOURCE_REFRESH_INTERVAL)
    scheduler.scheduleAtFixedRate(
      new Runnable { override def run(): Unit = Utils.tryLogNonFatalError(refresh()) },
      interval,
      interval,
      TimeUnit.MILLISECONDS)
  }

  private def bootstrapFromFile(): Unit = {
    conf.get(DIGIWIN_DATASOURCE_BOOTSTRAP_FILE).filter(_.nonEmpty).foreach { path =>
      val file = new File(path)
      if (!file.isFile) {
        throw new KyuubiException(s"Datasource bootstrap file not found: $path")
      }
      val properties = new Properties()
      val input = new java.io.FileInputStream(file)
      try properties.load(input)
      finally input.close()

      val keys = properties.stringPropertyNames().asScala.toSeq
      val credentialIds = keys.flatMap(parseCredentialId).toSet.toSeq.sorted
      credentialIds.foreach { id =>
        val prefix = s"credential.$id."
        upsertStorageCredential(
          id,
          properties.getProperty(prefix + "provider", "s3"),
          properties.getProperty(prefix + "description", ""),
          requiredEnv(properties, prefix + "accessKeyEnv"),
          requiredEnv(properties, prefix + "secretKeyEnv"),
          optionalEnv(properties, prefix + "sessionTokenEnv"))
      }

      val datasourceLabels = keys.flatMap(parseDatasourceLabel).toSet.toSeq.sorted
      datasourceLabels.foreach { label =>
        val prefix = s"datasource.$label."
        val propertyPrefix = prefix + "property."
        val datasourceProperties = keys.filter(_.startsWith(propertyPrefix)).map { key =>
          key.stripPrefix(propertyPrefix) -> properties.getProperty(key)
        }.toMap
        val poolPrefix = prefix + "connectionPool."
        val poolParams = keys.filter(_.startsWith(poolPrefix)).map { key =>
          key.stripPrefix(poolPrefix) -> properties.getProperty(key)
        }.toMap
        upsert(
          DatasourceInfo(
            label = label,
            engineType = requiredProperty(properties, prefix + "engineType"),
            jdbcType = properties.getProperty(prefix + "jdbcType", ""),
            driverClass = properties.getProperty(prefix + "driverClass", ""),
            jdbcUrl = propertyOrEnv(properties, prefix + "jdbcUrl", prefix + "jdbcUrlEnv"),
            username = propertyOrEnv(properties, prefix + "username", prefix + "usernameEnv"),
            encryptedPassword = "",
            connectionPoolParams = poolParams,
            status = properties.getProperty(prefix + "status", "ENABLED"),
            description = properties.getProperty(prefix + "description", ""),
            properties = datasourceProperties),
          optionalEnv(properties, prefix + "passwordEnv"))
      }
      info(s"Bootstrapped ${datasourceLabels.size} datasource definitions from $path.")
    }
  }

  private def parseCredentialId(key: String): Option[String] = {
    val prefix = "credential."
    if (key.startsWith(prefix) && key.endsWith(".accessKeyEnv")) {
      Some(key.stripPrefix(prefix).stripSuffix(".accessKeyEnv"))
    } else None
  }

  private def parseDatasourceLabel(key: String): Option[String] = {
    val prefix = "datasource."
    val fields = Seq(
      ".engineType",
      ".jdbcType",
      ".driverClass",
      ".jdbcUrl",
      ".jdbcUrlEnv",
      ".username",
      ".usernameEnv",
      ".passwordEnv",
      ".status",
      ".description",
      ".connectionPool.",
      ".property.")
    if (!key.startsWith(prefix)) None
    else {
      val remainder = key.stripPrefix(prefix)
      fields.flatMap { field =>
        val index = remainder.indexOf(field)
        if (index > 0) Some(remainder.substring(0, index)) else None
      }.headOption
    }
  }

  private def requiredProperty(properties: Properties, key: String): String = {
    Option(properties.getProperty(key)).map(_.trim).filter(_.nonEmpty).getOrElse(
      throw new KyuubiException(s"Datasource bootstrap property is required: $key"))
  }

  private def requiredEnv(properties: Properties, key: String): String = {
    val envName = requiredProperty(properties, key)
    sys.env.getOrElse(
      envName,
      throw new KyuubiException(
        s"Datasource bootstrap environment variable is not set: $envName"))
  }

  private def propertyOrEnv(properties: Properties, propertyKey: String, envKey: String): String = {
    Option(properties.getProperty(envKey)).map(_.trim).filter(_.nonEmpty)
      .map(name =>
        sys.env.getOrElse(
          name,
          throw new KyuubiException(
            s"Datasource bootstrap environment variable is not set: $name")))
      .orElse(Option(properties.getProperty(propertyKey)))
      .getOrElse("")
  }

  private def optionalEnv(properties: Properties, key: String): String = {
    Option(properties.getProperty(key)).map(_.trim).filter(_.nonEmpty).map { envName =>
      sys.env.getOrElse(
        envName,
        throw new KyuubiException(
          s"Datasource bootstrap environment variable is not set: $envName"))
    }.getOrElse("")
  }

  def refresh(): Unit = {
    val all = store.list()
    val next = new ConcurrentHashMap[String, DatasourceInfo]()
    all.foreach(ds => next.put(ds.label, ds))
    cache.clear()
    cache.putAll(next)
    info(s"Refreshed datasource registry: ${cache.size()} entries.")
  }

  def get(label: String): Option[DatasourceInfo] = Option(cache.get(label))

  def list(): Seq[DatasourceInfo] = cache.values().asScala.toSeq.sortBy(_.label)

  /** Encrypts the plaintext password, persists, and updates the cache. */
  def upsert(ds: DatasourceInfo, plainPassword: String): Unit = {
    val encrypted = Option(plainPassword).filter(_.nonEmpty)
      .map(credentialAccessor.encrypt)
      .orElse(get(ds.label).map(_.encryptedPassword))
      .getOrElse("")
    val toStore = ds.copy(encryptedPassword = encrypted)
    store.upsert(toStore)
    cache.put(toStore.label, toStore)
  }

  def delete(label: String): Unit = {
    store.delete(label)
    cache.remove(label)
  }

  /** Returns the decrypted password for the label; throws if missing or disabled when required. */
  def getDecryptedPassword(label: String, requireEnabled: Boolean = true): String = {
    val ds = get(label).getOrElse(
      throw new KyuubiException(s"Datasource $label not found"))
    if (requireEnabled && !ds.isEnabled) {
      throw new KyuubiException(s"Datasource $label is disabled")
    }
    if (ds.encryptedPassword.nonEmpty) {
      credentialAccessor.decrypt(ds.encryptedPassword)
    } else {
      ""
    }
  }

  def listStorageCredentials(): Seq[StoredStorageCredential] = store.listStorageCredentials()

  def storageCredentialExists(id: String): Boolean = store.getStorageCredential(id).nonEmpty

  def getStorageCredential(id: String): Option[ResolvedStorageCredential] =
    store.getStorageCredential(id).map { stored =>
      ResolvedStorageCredential(
        id = stored.id,
        provider = stored.provider,
        accessKeyId = credentialAccessor.decrypt(stored.encryptedAccessKeyId),
        secretAccessKey = credentialAccessor.decrypt(stored.encryptedSecretAccessKey),
        sessionToken = if (stored.encryptedSessionToken.nonEmpty) {
          credentialAccessor.decrypt(stored.encryptedSessionToken)
        } else "",
        version = stored.version)
    }

  def upsertStorageCredential(
      id: String,
      provider: String,
      description: String,
      plainAccessKeyId: String,
      plainSecretAccessKey: String,
      plainSessionToken: String): Unit = {
    val existing = store.getStorageCredential(id)
    val accessKey = Option(plainAccessKeyId).filter(_.nonEmpty)
      .map(credentialAccessor.encrypt)
      .orElse(existing.map(_.encryptedAccessKeyId))
      .getOrElse(throw new KyuubiException("Access key is required"))
    val secretKey = Option(plainSecretAccessKey).filter(_.nonEmpty)
      .map(credentialAccessor.encrypt)
      .orElse(existing.map(_.encryptedSecretAccessKey))
      .getOrElse(throw new KyuubiException("Secret key is required"))
    val sessionToken = Option(plainSessionToken).filter(_.nonEmpty)
      .map(credentialAccessor.encrypt).getOrElse("")
    store.upsertStorageCredential(
      StoredStorageCredential(
        id,
        provider,
        accessKey,
        secretKey,
        sessionToken,
        description,
        System.currentTimeMillis()))
  }

  def deleteStorageCredential(id: String): Unit = {
    if (!storageCredentialExists(id)) {
      throw new KyuubiException(s"Storage credential $id not found")
    }
    val users = list().filter(_.properties.get(DatasourceInfo.IcebergCredentialRef).contains(id))
    if (users.nonEmpty) {
      throw new KyuubiException(
        s"Storage credential $id is still bound to datasource(s): " +
          users.map(_.label).sorted.mkString(", "))
    }
    store.deleteStorageCredential(id)
  }

  def stop(): Unit = {
    scheduler.shutdownNow()
    store.close()
  }
}
