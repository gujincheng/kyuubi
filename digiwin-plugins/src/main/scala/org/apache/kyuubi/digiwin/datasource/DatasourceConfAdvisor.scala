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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.{Collections, Map => JMap}

import scala.collection.JavaConverters._

import org.apache.kyuubi.{KyuubiException, Logging, Utils}
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

  private val JdbcInitializeSqlKey = "kyuubi.engine.jdbc.initialize.sql"
  private val OracleInitializeSql = "SELECT 1 FROM DUAL"

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
    if (ds.isJdbc) {
      overlay("kyuubi.engine.type") = "JDBC"
      overlay("kyuubi.engine.jdbc.type") = ds.jdbcType
      overlay("kyuubi.engine.jdbc.connection.url") = ds.jdbcUrl
      overlay("kyuubi.engine.jdbc.connection.user") = ds.username
      overlay("kyuubi.engine.jdbc.connection.password") = reg.getDecryptedPassword(label)
      overlay("kyuubi.engine.jdbc.driver.class") = ds.driverClass
      ds.safeConnectionPoolParams.foreach { case (k, v) => overlay(k) = v }
      if (ds.jdbcType.equalsIgnoreCase("oracle") && !sessionConf.containsKey(
          JdbcInitializeSqlKey)) {
        overlay(JdbcInitializeSqlKey) = OracleInitializeSql
      }
    } else if (ds.isIceberg) {
      val profile = loadProfile(ds.sessionProfile)
      profile.foreach { case (key, value) =>
        if (!sessionConf.containsKey(key)) overlay(key) = value
      }
      addIcebergOverlay(ds, sessionConf.asScala.toMap ++ overlay, overlay)
    } else {
      throw new KyuubiException(
        s"Datasource label $label has unsupported engine type: ${ds.engineType}")
    }
    overlay.asJava
  }

  private def loadProfile(profile: String): Map[String, String] = {
    if (profile.isEmpty) return Map.empty
    if (!profile.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      throw new KyuubiException(s"Datasource session profile has invalid name: $profile")
    }
    val fileName = s"kyuubi-session-$profile.conf"
    val file = Utils.getPropertiesFile(fileName).getOrElse(
      throw new KyuubiException(s"Datasource session profile not found: $profile"))
    Utils.getPropertiesFromFile(Some(file))
  }

  private def addIcebergOverlay(
      datasource: DatasourceInfo,
      effectiveBase: Map[String, String],
      overlay: scala.collection.mutable.Map[String, String]): Unit = {
    val catalog = datasource.properties(DatasourceInfo.IcebergCatalogName)
    val catalogPrefix = s"spark.sql.catalog.$catalog"
    val icebergExtension = "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions"
    val extensions = (effectiveBase.get("spark.sql.extensions").toSeq
      .flatMap(_.split(",")) :+ icebergExtension).map(_.trim).filter(_.nonEmpty).distinct

    overlay("kyuubi.engine.type") = "SPARK_SQL"
    overlay("spark.sql.extensions") = extensions.mkString(",")
    overlay(catalogPrefix) = "org.apache.iceberg.spark.SparkCatalog"
    overlay(s"$catalogPrefix.type") =
      datasource.properties(DatasourceInfo.IcebergCatalogType)
    overlay(s"$catalogPrefix.uri") = datasource.properties(DatasourceInfo.IcebergCatalogUri)
    overlay(s"$catalogPrefix.warehouse") =
      datasource.properties(DatasourceInfo.IcebergWarehouse)
    overlay(s"$catalogPrefix.default-namespace") = "default"
    overlay("spark.sql.defaultCatalog") = catalog

    datasource.properties.get(DatasourceInfo.IcebergS3Endpoint)
      .filter(_.nonEmpty).foreach { endpoint =>
        overlay("spark.hadoop.fs.s3a.endpoint") = endpoint
      }
    val credentialRef = datasource.properties.get(DatasourceInfo.IcebergCredentialRef)
      .filter(_.nonEmpty)
    credentialRef match {
      case Some(ref) =>
        val credential = DatasourceRegistryHolder.registry.getStorageCredential(ref).getOrElse(
          throw new KyuubiException(s"Storage credential $ref not found"))
        overlay("spark.hadoop.fs.s3a.access.key") = credential.accessKeyId
        overlay("spark.hadoop.fs.s3a.secret.key") = credential.secretAccessKey
        if (credential.sessionToken.nonEmpty) {
          overlay("spark.hadoop.fs.s3a.session.token") = credential.sessionToken
          overlay("spark.hadoop.fs.s3a.aws.credentials.provider") =
            "org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider"
        } else {
          overlay("spark.hadoop.fs.s3a.aws.credentials.provider") =
            "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider"
        }
        overlay("spark.hadoop.fs.s3a.impl") = "org.apache.hadoop.fs.s3a.S3AFileSystem"
      case None if datasource.properties.get(DatasourceInfo.IcebergS3Endpoint).exists(_.nonEmpty) =>
        overlay("spark.hadoop.fs.s3a.impl") = "org.apache.hadoop.fs.s3a.S3AFileSystem"
        overlay("spark.hadoop.fs.s3a.aws.credentials.provider") =
          "com.amazonaws.auth.EnvironmentVariableCredentialsProvider"
      case _ =>
    }
    overlay("spark.hadoop.fs.s3a.path.style.access") =
      datasource.properties.getOrElse(DatasourceInfo.IcebergS3PathStyleAccess, "true")
    overlay("spark.hadoop.fs.s3a.connection.ssl.enabled") =
      datasource.properties.getOrElse(DatasourceInfo.IcebergS3SslEnabled, "false")

    val sensitiveCredentialKeys = Set(
      "spark.hadoop.fs.s3a.access.key",
      "spark.hadoop.fs.s3a.secret.key",
      "spark.hadoop.fs.s3a.session.token")
    credentialRef.flatMap(DatasourceRegistryHolder.registry.getStorageCredential).foreach {
      credential =>
        overlay("kyuubi.digiwin.storage.credential.version") = credential.version.toString
    }
    val fingerprintValues = effectiveBase ++ overlay -- sensitiveCredentialKeys -
      "kyuubi.engine.share.level.subdomain"
    overlay("kyuubi.engine.share.level.subdomain") =
      datasourceSubdomain(datasource.label, fingerprintValues)
  }

  private def datasourceSubdomain(label: String, values: Map[String, String]): String = {
    val payload =
      values.toSeq.sortBy(_._1).map { case (key, value) => s"$key=$value" }.mkString("\n")
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(payload.getBytes(StandardCharsets.UTF_8))
      .take(6).map("%02x".format(_)).mkString
    s"ds-${label.take(32)}-$digest"
  }
}
