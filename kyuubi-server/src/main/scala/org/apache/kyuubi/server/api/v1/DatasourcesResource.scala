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

import java.net.URI
import javax.ws.rs.{BadRequestException, DELETE, GET, NotFoundException, Path, PathParam, POST, Produces, PUT, WebApplicationException}
import javax.ws.rs.core.{MediaType, Response}

import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.{KyuubiException, Utils}
import org.apache.kyuubi.digiwin.datasource.{DatasourceConnectionTester, DatasourceConnectionTestResult, DatasourceInfo, DatasourceRegistry, StoredStorageCredential}
import org.apache.kyuubi.server.{AdminPermissionService, AdminRole, AuditRecordStore}
import org.apache.kyuubi.server.api.ApiRequestContext

@Tag(name = "datasources")
@Path("datasources")
class DatasourcesResource extends ApiRequestContext {

  private def registry: DatasourceRegistry =
    DatasourcesResource.registryOpt.getOrElse(
      throw new KyuubiException("Datasource registry is not initialized"))

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def list(): Seq[DatasourceView] = {
    AdminPermissionService.require(fe, "datasource", AdminRole.Read)
    registry.list().map(DatasourceView.from)
  }

  @GET
  @Path("profiles")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def profiles(): Seq[String] = {
    AdminPermissionService.require(fe, "datasource", AdminRole.Read)
    DatasourceRequest.sessionProfiles()
  }

  @GET
  @Path("credentials")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def credentials(): Seq[StorageCredentialView] = {
    AdminPermissionService.require(fe, "datasource", AdminRole.Read)
    registry.listStorageCredentials().map(StorageCredentialView.from)
  }

  @POST
  @Path("credentials")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def createCredential(req: StorageCredentialRequest): StorageCredentialView = {
    val actor = AdminPermissionService.require(fe, "datasource", AdminRole.Write)
    val normalized = StorageCredentialRequest.validate(req)
    if (registry.storageCredentialExists(normalized.id)) {
      throw new WebApplicationException(
        s"Storage credential ${normalized.id} already exists",
        Response.status(Response.Status.CONFLICT)
          .entity(s"Storage credential ${normalized.id} already exists").build())
    }
    if (normalized.accessKeyId.isEmpty || normalized.secretAccessKey.isEmpty) {
      throw new BadRequestException("Access Key ID and Secret Access Key are required")
    }
    registry.upsertStorageCredential(
      normalized.id,
      normalized.provider,
      normalized.description,
      normalized.accessKeyId,
      normalized.secretAccessKey,
      normalized.sessionToken)
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "storage-credential.create",
      s"/api/v1/datasources/credentials/${normalized.id}")
    StorageCredentialView.from(registry.listStorageCredentials().find(_.id == normalized.id).get)
  }

  @PUT
  @Path("credentials/{id}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def updateCredential(
      @PathParam("id") id: String,
      req: StorageCredentialRequest): StorageCredentialView = {
    val actor = AdminPermissionService.require(fe, "datasource", AdminRole.Write)
    val normalized = StorageCredentialRequest.validate(req)
    if (id != normalized.id) {
      throw new BadRequestException("Credential id in path and body mismatch")
    }
    if (!registry.storageCredentialExists(id)) {
      throw new NotFoundException(s"Storage credential $id not found")
    }
    registry.upsertStorageCredential(
      normalized.id,
      normalized.provider,
      normalized.description,
      normalized.accessKeyId,
      normalized.secretAccessKey,
      normalized.sessionToken)
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "storage-credential.update",
      s"/api/v1/datasources/credentials/$id")
    StorageCredentialView.from(registry.listStorageCredentials().find(_.id == id).get)
  }

  @DELETE
  @Path("credentials/{id}")
  def deleteCredential(@PathParam("id") id: String): Unit = {
    val actor = AdminPermissionService.require(fe, "datasource", AdminRole.Delete)
    if (!registry.storageCredentialExists(id)) {
      throw new NotFoundException(s"Storage credential $id not found")
    }
    try registry.deleteStorageCredential(id)
    catch {
      case error: KyuubiException =>
        throw new WebApplicationException(
          error.getMessage,
          Response.status(Response.Status.CONFLICT).entity(error.getMessage).build())
    }
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "storage-credential.delete",
      s"/api/v1/datasources/credentials/$id")
  }

  @GET
  @Path("{label}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def get(@PathParam("label") label: String): DatasourceView = {
    AdminPermissionService.require(fe, "datasource", AdminRole.Read)
    registry.get(label).map(DatasourceView.from)
      .getOrElse(throw new NotFoundException(s"Datasource $label not found"))
  }

  @POST
  @Produces(Array(MediaType.APPLICATION_JSON))
  def create(req: DatasourceRequest): DatasourceView = {
    val actor = AdminPermissionService.require(fe, "datasource", AdminRole.Write)
    val normalized = DatasourceRequest.validate(req)
    validateCredentialReference(normalized)
    if (registry.get(normalized.label).nonEmpty) {
      throw new WebApplicationException(
        s"Datasource ${normalized.label} already exists",
        Response.status(Response.Status.CONFLICT)
          .entity(s"Datasource ${normalized.label} already exists")
          .build())
    }
    val ds = normalized.toInfo()
    registry.upsert(ds, normalized.plainPassword)
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "datasource.create",
      s"/api/v1/datasources/${ds.label}")
    DatasourceView.from(registry.get(ds.label).get)
  }

  @PUT
  @Path("{label}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def update(@PathParam("label") label: String, req: DatasourceRequest): DatasourceView = {
    val actor = AdminPermissionService.require(fe, "datasource", AdminRole.Write)
    val normalized = DatasourceRequest.validate(req)
    validateCredentialReference(normalized)
    if (!label.equals(normalized.label)) {
      throw new BadRequestException(
        s"Label in path($label) and body(${normalized.label}) mismatch")
    }
    if (registry.get(label).isEmpty) {
      throw new NotFoundException(s"Datasource $label not found")
    }
    val ds = normalized.toInfo()
    registry.upsert(ds, normalized.plainPassword)
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "datasource.update",
      s"/api/v1/datasources/$label")
    DatasourceView.from(registry.get(label).get)
  }

  @DELETE
  @Path("{label}")
  def delete(@PathParam("label") label: String): Unit = {
    val actor = AdminPermissionService.require(fe, "datasource", AdminRole.Delete)
    if (registry.get(label).isEmpty) {
      throw new NotFoundException(s"Datasource $label not found")
    }
    registry.delete(label)
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "datasource.delete",
      s"/api/v1/datasources/$label")
  }

  @POST
  @Path("refresh")
  @Produces(Array(MediaType.TEXT_PLAIN))
  def refresh(): String = {
    val actor = AdminPermissionService.require(fe, "datasource", AdminRole.Refresh)
    registry.refresh()
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "datasource.refresh",
      "/api/v1/datasources/refresh")
    "ok"
  }

  @POST
  @Path("test")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def testConfiguration(req: DatasourceRequest): DatasourceConnectionTestResult = {
    val actor = AdminPermissionService.require(fe, "datasource", AdminRole.Control)
    val normalized = DatasourceRequest.validate(req)
    validateCredentialReference(normalized)
    val effectivePassword = if (normalized.plainPassword.nonEmpty) {
      normalized.plainPassword
    } else if (registry.get(normalized.label).nonEmpty) {
      registry.getDecryptedPassword(normalized.label, requireEnabled = false)
    } else {
      ""
    }
    val result = DatasourceConnectionTester.test(normalized.toInfo(), effectivePassword)
    handleTestResult(actor, "/api/v1/datasources/test", result)
  }

  @POST
  @Path("{label}/test")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def testSaved(@PathParam("label") label: String): DatasourceConnectionTestResult = {
    val actor = AdminPermissionService.require(fe, "datasource", AdminRole.Control)
    val datasource = registry.get(label)
      .getOrElse(throw new NotFoundException(s"Datasource $label not found"))
    validateCredentialReference(DatasourceRequest.fromInfo(datasource))
    val password = registry.getDecryptedPassword(label, requireEnabled = false)
    val result = DatasourceConnectionTester.test(datasource, password)
    handleTestResult(actor, s"/api/v1/datasources/$label/test", result)
  }

  private def handleTestResult(
      actor: String,
      uri: String,
      result: DatasourceConnectionTestResult): DatasourceConnectionTestResult = {
    if (!result.success) {
      throw new WebApplicationException(
        result.message,
        Response.status(Response.Status.BAD_GATEWAY).entity(result).build())
    }
    AuditRecordStore.appendAction(actor, fe.getIpAddress, "datasource.test", uri)
    result
  }

  private def validateCredentialReference(request: DatasourceRequest): Unit = {
    Option(request.icebergConfig).map(_.credentialRef).filter(_.nonEmpty).foreach { ref =>
      if (!registry.storageCredentialExists(ref)) {
        throw new BadRequestException(s"Storage credential $ref was not found")
      }
    }
  }
}

object DatasourcesResource {
  @volatile private var registryOpt: Option[DatasourceRegistry] = None
  def init(registry: DatasourceRegistry): Unit = registryOpt = Some(registry)
}

case class DatasourceView(
    label: String,
    engineType: String,
    jdbcType: String,
    driverClass: String,
    jdbcUrl: String,
    username: String,
    connectionPoolParams: Map[String, String],
    status: String,
    description: String,
    credentialStored: Boolean,
    icebergConfig: IcebergDatasourceConfig = null)

object DatasourceView {
  def from(ds: DatasourceInfo): DatasourceView =
    DatasourceView(
      ds.label,
      ds.engineType,
      ds.jdbcType,
      ds.driverClass,
      ds.jdbcUrl,
      ds.username,
      ds.safeConnectionPoolParams,
      ds.status,
      ds.description,
      ds.encryptedPassword.nonEmpty,
      if (ds.isIceberg) IcebergDatasourceConfig.from(ds) else null)
}

case class IcebergDatasourceConfig(
    catalogName: String = "lake",
    catalogType: String = "hive",
    uri: String = "",
    warehouse: String = "",
    s3Endpoint: String = "",
    s3PathStyleAccess: Boolean = true,
    s3SslEnabled: Boolean = false,
    sessionProfile: String = "",
    credentialRef: String = "") {

  def properties: Map[String, String] = Map(
    DatasourceInfo.IcebergCatalogName -> catalogName,
    DatasourceInfo.IcebergCatalogType -> catalogType,
    DatasourceInfo.IcebergCatalogUri -> uri,
    DatasourceInfo.IcebergWarehouse -> warehouse,
    DatasourceInfo.IcebergS3Endpoint -> s3Endpoint,
    DatasourceInfo.IcebergS3PathStyleAccess -> s3PathStyleAccess.toString,
    DatasourceInfo.IcebergS3SslEnabled -> s3SslEnabled.toString,
    DatasourceInfo.SessionProfile -> sessionProfile) ++
    Option(credentialRef).filter(_.nonEmpty).map(
      DatasourceInfo.IcebergCredentialRef -> _).toMap
}

object IcebergDatasourceConfig {
  def from(datasource: DatasourceInfo): IcebergDatasourceConfig = IcebergDatasourceConfig(
    catalogName = datasource.properties.getOrElse(DatasourceInfo.IcebergCatalogName, "lake"),
    catalogType = datasource.properties.getOrElse(DatasourceInfo.IcebergCatalogType, "hive"),
    uri = datasource.properties.getOrElse(DatasourceInfo.IcebergCatalogUri, ""),
    warehouse = datasource.properties.getOrElse(DatasourceInfo.IcebergWarehouse, ""),
    s3Endpoint = datasource.properties.getOrElse(DatasourceInfo.IcebergS3Endpoint, ""),
    s3PathStyleAccess = datasource.properties
      .getOrElse(DatasourceInfo.IcebergS3PathStyleAccess, "true").toBoolean,
    s3SslEnabled = datasource.properties
      .getOrElse(DatasourceInfo.IcebergS3SslEnabled, "false").toBoolean,
    sessionProfile = datasource.sessionProfile,
    credentialRef = datasource.properties.getOrElse(DatasourceInfo.IcebergCredentialRef, ""))
}

case class StorageCredentialView(
    id: String,
    provider: String,
    description: String,
    configured: Boolean,
    version: Long)

object StorageCredentialView {
  def from(credential: StoredStorageCredential): StorageCredentialView =
    StorageCredentialView(
      credential.id,
      credential.provider,
      credential.description,
      credential.encryptedAccessKeyId.nonEmpty && credential.encryptedSecretAccessKey.nonEmpty,
      credential.version)
}

case class StorageCredentialRequest(
    id: String,
    provider: String = "s3",
    accessKeyId: String = "",
    secretAccessKey: String = "",
    sessionToken: String = "",
    description: String = "")

object StorageCredentialRequest {
  private val IdPattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}".r

  def validate(request: StorageCredentialRequest): StorageCredentialRequest = {
    if (request == null) throw new BadRequestException("Storage credential body must not be empty")
    val normalized = request.copy(
      id = clean(request.id),
      provider = clean(request.provider).toLowerCase,
      accessKeyId = Option(request.accessKeyId).getOrElse(""),
      secretAccessKey = Option(request.secretAccessKey).getOrElse(""),
      sessionToken = Option(request.sessionToken).getOrElse(""),
      description = clean(request.description))
    if (!IdPattern.pattern.matcher(normalized.id).matches()) {
      throw new BadRequestException("Invalid storage credential id")
    }
    if (normalized.provider != "s3") {
      throw new BadRequestException("Only s3 storage credentials are supported")
    }
    if (normalized.description.length > 1024) {
      throw new BadRequestException("Storage credential description is too long")
    }
    normalized
  }

  private def clean(value: String): String = Option(value).map(_.trim).getOrElse("")
}

case class DatasourceRequest(
    label: String,
    engineType: String,
    jdbcType: String = "",
    driverClass: String = "",
    jdbcUrl: String = "",
    username: String = "",
    plainPassword: String = "",
    connectionPoolParams: Map[String, String] = Map.empty,
    status: String = "ENABLED",
    description: String = "",
    icebergConfig: IcebergDatasourceConfig = null) {
  def toInfo(): DatasourceInfo =
    DatasourceInfo(
      label,
      engineType,
      jdbcType,
      driverClass,
      jdbcUrl,
      username,
      encryptedPassword = "",
      connectionPoolParams,
      status,
      description,
      Option(icebergConfig).map(_.properties).getOrElse(Map.empty))
}

object DatasourceRequest {
  private val LabelPattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}".r
  private val JdbcTypePattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}".r
  private val DriverPattern = "[A-Za-z_$][A-Za-z0-9_$.]{0,255}".r
  private val CatalogNamePattern = "[A-Za-z][A-Za-z0-9_]{0,63}".r
  private val ProfileNamePattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}".r
  private val CredentialRefPattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}".r
  private val PasswordParameterPattern =
    "(?i).*[?;&](password|passwd|pwd)=[^;&]*.*".r
  private val JdbcUserInfoPattern = "(?i)jdbc:[^:]+://[^/@:]+:[^/@]+@.*".r
  private val PoolBounds: Map[String, (Long, Long)] = Map(
    "maximumPoolSize" -> (1L, 1000L),
    "minimumIdle" -> (0L, 1000L),
    "connectionTimeout" -> (250L, 3600000L),
    "idleTimeout" -> (0L, 86400000L),
    "maxLifetime" -> (0L, 86400000L),
    "keepaliveTime" -> (0L, 86400000L),
    "validationTimeout" -> (250L, 3600000L),
    "leakDetectionThreshold" -> (0L, 86400000L))

  def validate(request: DatasourceRequest): DatasourceRequest = {
    if (request == null) throw new BadRequestException("Datasource body must not be empty")
    val normalizedEngineType = clean(request.engineType).toLowerCase match {
      case "spark" | "spark_sql" | "iceberg" => "spark"
      case other => other
    }
    val normalizedIceberg = Option(request.icebergConfig).map(normalizeIceberg).orNull
    val normalized = request.copy(
      label = clean(request.label),
      engineType = normalizedEngineType,
      jdbcType = clean(request.jdbcType).toLowerCase,
      driverClass = clean(request.driverClass),
      jdbcUrl = clean(request.jdbcUrl),
      username = clean(request.username),
      plainPassword = Option(request.plainPassword).getOrElse(""),
      connectionPoolParams = Option(request.connectionPoolParams).getOrElse(Map.empty)
        .map { case (key, value) => clean(key) -> clean(value) },
      status = clean(request.status).toUpperCase,
      description = clean(request.description),
      icebergConfig = normalizedIceberg)

    requireMatch(normalized.label, LabelPattern, "Invalid datasource label")
    normalized.engineType match {
      case "jdbc" => validateJdbc(normalized)
      case "spark" => validateIceberg(normalized)
      case _ => throw new BadRequestException("engineType must be jdbc or spark")
    }
    if (!Set("ENABLED", "DISABLED").contains(normalized.status)) {
      throw new BadRequestException("Datasource status must be ENABLED or DISABLED")
    }
    if (normalized.description.length > 2000) {
      throw new BadRequestException("Datasource description must contain at most 2000 characters")
    }
    normalized
  }

  def sessionProfiles(): Seq[String] = {
    Utils.getPropertiesFile("kyuubi-defaults.conf").toSeq
      .flatMap(file => Option(file.getParentFile.listFiles()).toSeq.flatten)
      .filter(file =>
        file.isFile && file.getName.startsWith("kyuubi-session-") &&
          file.getName.endsWith(".conf"))
      .map(_.getName.stripPrefix("kyuubi-session-").stripSuffix(".conf"))
      .sorted
  }

  private def validateJdbc(request: DatasourceRequest): Unit = {
    requireMatch(request.jdbcType, JdbcTypePattern, "Invalid JDBC type")
    requireMatch(request.driverClass, DriverPattern, "Invalid JDBC driver class")
    if (!request.jdbcUrl.startsWith("jdbc:") || request.jdbcUrl.length > 4096) {
      throw new BadRequestException(
        "JDBC URL must start with jdbc: and contain at most 4096 characters")
    }
    if (PasswordParameterPattern.pattern.matcher(request.jdbcUrl).matches() ||
      JdbcUserInfoPattern.pattern.matcher(request.jdbcUrl).matches()) {
      throw new BadRequestException("JDBC URL must not contain an embedded password")
    }
    if (request.username.length > 256) {
      throw new BadRequestException("Datasource username must contain at most 256 characters")
    }
    validatePoolParams(request.connectionPoolParams)
  }

  private def validateIceberg(request: DatasourceRequest): Unit = {
    if (request.icebergConfig == null) {
      throw new BadRequestException("icebergConfig is required for spark engineType")
    }
    if (request.connectionPoolParams.nonEmpty) {
      throw new BadRequestException("connectionPoolParams are only supported for JDBC datasources")
    }
    if (request.plainPassword.nonEmpty) {
      throw new BadRequestException(
        "Iceberg storage credentials must be supplied by the runtime credential provider")
    }
    val config = request.icebergConfig
    requireMatch(config.catalogName, CatalogNamePattern, "Invalid Iceberg catalog name")
    if (config.catalogType != "hive") {
      throw new BadRequestException("Only Hive Metastore Iceberg catalogs are supported")
    }
    validateUri(config.uri, Set("thrift"), "Iceberg catalog URI")
    validateUri(config.warehouse, Set("s3a", "hdfs", "file"), "Iceberg warehouse")
    if (config.s3Endpoint.nonEmpty) {
      validateUri(config.s3Endpoint, Set("http", "https"), "S3 endpoint")
    }
    if (config.sessionProfile.nonEmpty) {
      requireMatch(config.sessionProfile, ProfileNamePattern, "Invalid Session Profile name")
      if (Utils.getPropertiesFile(s"kyuubi-session-${config.sessionProfile}.conf").isEmpty) {
        throw new BadRequestException(s"Session Profile ${config.sessionProfile} was not found")
      }
    }
    if (config.credentialRef.nonEmpty) {
      requireMatch(
        config.credentialRef,
        CredentialRefPattern,
        "Invalid storage credential reference")
    }
  }

  def fromInfo(datasource: DatasourceInfo): DatasourceRequest = DatasourceRequest(
    label = datasource.label,
    engineType = datasource.engineType,
    jdbcType = datasource.jdbcType,
    driverClass = datasource.driverClass,
    jdbcUrl = datasource.jdbcUrl,
    username = datasource.username,
    status = datasource.status,
    description = datasource.description,
    icebergConfig = if (datasource.isIceberg) IcebergDatasourceConfig.from(datasource) else null)

  private def normalizeIceberg(config: IcebergDatasourceConfig): IcebergDatasourceConfig =
    config.copy(
      catalogName = clean(config.catalogName),
      catalogType = clean(config.catalogType).toLowerCase,
      uri = clean(config.uri),
      warehouse = clean(config.warehouse),
      s3Endpoint = clean(config.s3Endpoint),
      sessionProfile = clean(config.sessionProfile),
      credentialRef = clean(config.credentialRef))

  private def validateUri(value: String, schemes: Set[String], field: String): Unit = {
    try {
      val uri = new URI(value)
      if (!schemes.contains(Option(uri.getScheme).getOrElse("").toLowerCase) ||
        (uri.getScheme != "file" && uri.getHost == null)) {
        throw new BadRequestException(s"$field must use ${schemes.toSeq.sorted.mkString("/")}")
      }
    } catch {
      case error: BadRequestException => throw error
      case _: Exception => throw new BadRequestException(s"Invalid $field")
    }
  }

  private def clean(value: String): String = Option(value).map(_.trim).getOrElse("")

  private def requireMatch(
      value: String,
      pattern: scala.util.matching.Regex,
      message: String): Unit = {
    if (!pattern.pattern.matcher(value).matches()) throw new BadRequestException(message)
  }

  private def validatePoolParams(params: Map[String, String]): Unit = {
    params.foreach { case (key, value) =>
      val bounds = PoolBounds.getOrElse(
        key,
        throw new BadRequestException(s"Unsupported connection pool parameter: $key"))
      val normalizedValue = Option(value).map(_.trim).getOrElse("")
      val number =
        try normalizedValue.toLong
        catch {
          case _: NumberFormatException =>
            throw new BadRequestException(s"Connection pool parameter $key must be an integer")
        }
      if (number < bounds._1 || number > bounds._2) {
        throw new BadRequestException(
          s"Connection pool parameter $key must be between ${bounds._1} and ${bounds._2}")
      }
    }
    for {
      maximum <- params.get("maximumPoolSize").map(_.toLong)
      minimum <- params.get("minimumIdle").map(_.toLong)
      if minimum > maximum
    } throw new BadRequestException("minimumIdle must not exceed maximumPoolSize")
  }
}
