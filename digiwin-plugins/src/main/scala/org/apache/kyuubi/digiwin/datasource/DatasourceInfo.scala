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

/**
 * @param label unique datasource identifier, e.g. sr-prod
 * @param engineType engine type, e.g. jdbc or spark
 * @param jdbcType short jdbc dialect name, e.g. starrocks (only for engineType=jdbc)
 * @param driverClass jdbc driver class name
 * @param jdbcUrl jdbc connection url
 * @param username connection username
 * @param encryptedPassword AES-encrypted password (never plaintext)
 * @param connectionPoolParams extra pool params, e.g. maximumPoolSize=10
 * @param status ENABLED / DISABLED
 * @param description free text
 * @param properties engine-specific properties; only validated keys are persisted by the REST API
 */
case class DatasourceInfo(
    label: String,
    engineType: String,
    jdbcType: String,
    driverClass: String,
    jdbcUrl: String,
    username: String,
    encryptedPassword: String,
    connectionPoolParams: Map[String, String] = Map.empty,
    status: String = "ENABLED",
    description: String = "",
    properties: Map[String, String] = Map.empty) {

  def isEnabled: Boolean = "ENABLED".equalsIgnoreCase(status)

  def isJdbc: Boolean = "jdbc".equalsIgnoreCase(engineType)

  def isIceberg: Boolean = "spark".equalsIgnoreCase(engineType)

  def sessionProfile: String = properties.getOrElse(DatasourceInfo.SessionProfile, "")

  /** Only connection-pool properties are allowed to enter the engine session overlay. */
  def safeConnectionPoolParams: Map[String, String] =
    connectionPoolParams.filter { case (key, _) => DatasourceInfo.ConnectionPoolKeys.contains(key) }
}

object DatasourceInfo {
  val IcebergCatalogName = "iceberg.catalog.name"
  val IcebergCatalogType = "iceberg.catalog.type"
  val IcebergCatalogUri = "iceberg.catalog.uri"
  val IcebergWarehouse = "iceberg.warehouse"
  val IcebergS3Endpoint = "iceberg.s3.endpoint"
  val IcebergS3PathStyleAccess = "iceberg.s3.pathStyleAccess"
  val IcebergS3SslEnabled = "iceberg.s3.ssl.enabled"
  val IcebergCredentialRef = "iceberg.credential.ref"
  val SessionProfile = "session.profile"

  val IcebergPropertyKeys: Set[String] = Set(
    IcebergCatalogName,
    IcebergCatalogType,
    IcebergCatalogUri,
    IcebergWarehouse,
    IcebergS3Endpoint,
    IcebergS3PathStyleAccess,
    IcebergS3SslEnabled,
    IcebergCredentialRef,
    SessionProfile)

  val ConnectionPoolKeys: Set[String] = Set(
    "maximumPoolSize",
    "minimumIdle",
    "connectionTimeout",
    "idleTimeout",
    "maxLifetime",
    "keepaliveTime",
    "validationTimeout",
    "leakDetectionThreshold")
}

/** Encrypted object-storage credential as persisted by the datasource store. */
case class StoredStorageCredential(
    id: String,
    provider: String,
    encryptedAccessKeyId: String,
    encryptedSecretAccessKey: String,
    encryptedSessionToken: String,
    description: String,
    version: Long) {
  override def toString: String =
    s"StoredStorageCredential(id=$id, provider=$provider, version=$version)"
}

/** Decrypted object-storage credential used only while building one engine session. */
case class ResolvedStorageCredential(
    id: String,
    provider: String,
    accessKeyId: String,
    secretAccessKey: String,
    sessionToken: String,
    version: Long) {
  override def toString: String =
    s"ResolvedStorageCredential(id=$id, provider=$provider, version=$version)"
}
