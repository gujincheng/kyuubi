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

import java.nio.file.Files
import javax.ws.rs.client.Entity

import org.apache.kyuubi.{KyuubiFunSuite, RestFrontendTestHelper}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.digiwin.datasource.DatasourceConnectionTestResult
import org.apache.kyuubi.server.http.util.HttpAuthUtils
import org.apache.kyuubi.server.http.util.HttpAuthUtils.AUTHORIZATION_HEADER
import org.apache.kyuubi.service.authentication.AnonymousAuthenticationProviderImpl

class DatasourcesResourceSuite extends KyuubiFunSuite with RestFrontendTestHelper {

  private val dbPath: String = Files.createTempFile("digiwin-ds-test-", ".db").toString
  private val targetDbPath: String = Files.createTempFile("digiwin-ds-target-", ".db").toString

  override protected lazy val conf: KyuubiConf = KyuubiConf()
    .set(AUTHENTICATION_METHOD, Seq("CUSTOM"))
    .set(AUTHENTICATION_CUSTOM_CLASS, classOf[AnonymousAuthenticationProviderImpl].getName)
    .set(SERVER_ADMINISTRATORS, Set("admin001"))
    .set(DIGIWIN_DATASOURCE_STORE_ENABLED, true)
    .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$dbPath")
    .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
    .set(DIGIWIN_DATASOURCE_CREDENTIAL_SECRET, "0123456789abcdef")

  private def adminRequest(path: String) = webTarget.path(path).request()
    .header(AUTHORIZATION_HEADER, HttpAuthUtils.basicAuthorizationHeader("admin001"))

  private def userRequest(path: String) = webTarget.path(path).request()
    .header(AUTHORIZATION_HEADER, HttpAuthUtils.basicAuthorizationHeader("user001"))

  private def req(label: String): DatasourceRequest = DatasourceRequest(
    label,
    "jdbc",
    "starrocks",
    "com.mysql.cj.jdbc.Driver",
    "jdbc:mysql://sr:9030/db",
    "u",
    "pwd",
    connectionPoolParams = Map("maximumPoolSize" -> "10"))

  test("create / get / list via REST; plaintext password never returned") {
    val createResp = adminRequest("api/v1/datasources")
      .post(Entity.json(req("sr-prod")))
    assert(createResp.getStatus === 200)
    val created = createResp.readEntity(classOf[DatasourceView])
    assert(created.label === "sr-prod")
    assert(created.engineType === "jdbc")
    assert(created.connectionPoolParams === Map("maximumPoolSize" -> "10"))
    assert(created.credentialStored)

    val listResp = adminRequest("api/v1/datasources").get()
    assert(listResp.getStatus === 200)
    val listBody = listResp.readEntity(classOf[String])
    assert(listBody.contains("\"label\":\"sr-prod\""))
    assert(!listBody.contains("pwd"), "plaintext password must not appear in list response")

    val getResp = adminRequest("api/v1/datasources/sr-prod").get()
    assert(getResp.getStatus === 200)
    val got = getResp.readEntity(classOf[DatasourceView])
    assert(got.label === "sr-prod")
  }

  test("update via PUT then delete via DELETE") {
    val createResp = adminRequest("api/v1/datasources")
      .post(Entity.json(req("sr-upd")))
    assert(createResp.getStatus === 200)

    val updateReq = req("sr-upd").copy(
      plainPassword = "",
      description = "updated-desc")
    val putResp = adminRequest("api/v1/datasources/sr-upd")
      .put(Entity.json(updateReq))
    assert(putResp.getStatus === 200)
    val updated = putResp.readEntity(classOf[DatasourceView])
    assert(updated.description === "updated-desc")
    assert(updated.credentialStored, "a blank edit must preserve the stored credential")

    val delResp = adminRequest("api/v1/datasources/sr-upd").delete()
    assert(delResp.getStatus === 204 || delResp.getStatus === 200)

    val getAfter = adminRequest("api/v1/datasources/sr-upd").get()
    assert(getAfter.getStatus === 404)
  }

  test("manual refresh endpoint returns ok") {
    val refreshResp = adminRequest("api/v1/datasources/refresh").post(null)
    assert(refreshResp.getStatus === 200)
    assert(refreshResp.readEntity(classOf[String]) === "ok")
  }

  test("validate datasource requests and reject duplicates") {
    val invalidPool = req("invalid-pool").copy(connectionPoolParams =
      Map("kyuubi.engine.type" -> "spark"))
    val invalidResp = adminRequest("api/v1/datasources").post(Entity.json(invalidPool))
    assert(invalidResp.getStatus === 400)

    val nullPoolValue = req("invalid-null-pool").copy(connectionPoolParams =
      Map("maximumPoolSize" -> null))
    val nullPoolResp = adminRequest("api/v1/datasources").post(Entity.json(nullPoolValue))
    assert(nullPoolResp.getStatus === 400)

    val embeddedPassword = req("invalid-url").copy(jdbcUrl = "jdbc:mysql://host/db?password=secret")
    val embeddedResp = adminRequest("api/v1/datasources").post(Entity.json(embeddedPassword))
    assert(embeddedResp.getStatus === 400)
    assert(!embeddedResp.readEntity(classOf[String]).contains("secret"))

    val createResp = adminRequest("api/v1/datasources")
      .post(Entity.json(req("duplicate-label")))
    assert(createResp.getStatus === 200)
    val duplicateResp = adminRequest("api/v1/datasources")
      .post(Entity.json(req("duplicate-label")))
    assert(duplicateResp.getStatus === 409)
  }

  test("test unsaved and saved datasource connections") {
    val sqliteRequest = DatasourceRequest(
      label = "sqlite-live",
      engineType = "jdbc",
      jdbcType = "sqlite",
      driverClass = "org.sqlite.JDBC",
      jdbcUrl = s"jdbc:sqlite:$targetDbPath",
      username = "",
      plainPassword = "")

    val unsavedResp = adminRequest("api/v1/datasources/test")
      .post(Entity.json(sqliteRequest))
    assert(unsavedResp.getStatus === 200)
    val unsavedResult = unsavedResp.readEntity(classOf[DatasourceConnectionTestResult])
    assert(unsavedResult.success)
    assert(unsavedResult.databaseProduct.toLowerCase.contains("sqlite"))

    val createResp = adminRequest("api/v1/datasources").post(Entity.json(sqliteRequest))
    assert(createResp.getStatus === 200)
    val savedResp = adminRequest("api/v1/datasources/sqlite-live/test").post(null)
    assert(savedResp.getStatus === 200)
    assert(savedResp.readEntity(classOf[DatasourceConnectionTestResult]).success)
  }

  test("connection test failure is explicit and requires control permission") {
    val missingDriver = req("missing-driver").copy(
      jdbcType = "missing",
      driverClass = "example.missing.Driver",
      jdbcUrl = "jdbc:missing:test",
      plainPassword = "must-not-leak")
    val failureResp = adminRequest("api/v1/datasources/test")
      .post(Entity.json(missingDriver))
    assert(failureResp.getStatus === 502)
    val failureBody = failureResp.readEntity(classOf[String])
    assert(!failureBody.contains("must-not-leak"))

    val forbiddenResp = userRequest("api/v1/datasources/test")
      .post(Entity.json(missingDriver.copy(plainPassword = "")))
    assert(forbiddenResp.getStatus === 403)
  }

  test("non-administrator cannot modify datasource definitions") {
    val createResp = userRequest("api/v1/datasources").post(Entity.json(req("sr-user")))
    assert(createResp.getStatus === 403)

    val listResp = userRequest("api/v1/datasources").get()
    assert(listResp.getStatus === 403)
  }

  test("create and test an Iceberg datasource through REST") {
    val request = DatasourceRequest(
      label = "iceberg-prod",
      engineType = "spark",
      status = "ENABLED",
      description = "Production lakehouse",
      icebergConfig = IcebergDatasourceConfig(
        catalogName = "lake",
        catalogType = "hive",
        uri = "thrift://127.0.0.1:1",
        warehouse = "s3a://iceberg/",
        s3Endpoint = "http://s3.example:9000"))

    val createResp = adminRequest("api/v1/datasources").post(Entity.json(request))
    assert(createResp.getStatus === 200)
    val created = createResp.readEntity(classOf[DatasourceView])
    assert(created.engineType === "spark")
    assert(created.icebergConfig.catalogName === "lake")
    assert(created.icebergConfig.uri === "thrift://127.0.0.1:1")
    assert(!created.credentialStored)

    val testResp = adminRequest("api/v1/datasources/iceberg-prod/test").post(null)
    assert(testResp.getStatus === 502)
  }

  test("storage credential REST API never returns secrets and protects bound credentials") {
    val credential = StorageCredentialRequest(
      id = "seaweedfs-rest",
      provider = "s3",
      accessKeyId = "access-key",
      secretAccessKey = "secret-key",
      description = "REST E2E")
    val createResp = adminRequest("api/v1/datasources/credentials")
      .post(Entity.json(credential))
    assert(createResp.getStatus === 200)
    val body = createResp.readEntity(classOf[String])
    assert(body.contains("seaweedfs-rest"))
    assert(!body.contains("access-key"))
    assert(!body.contains("secret-key"))

    val iceberg = DatasourceRequest(
      label = "iceberg-bound-rest",
      engineType = "spark",
      icebergConfig = IcebergDatasourceConfig(
        uri = "thrift://127.0.0.1:1",
        warehouse = "s3a://iceberg/",
        credentialRef = "seaweedfs-rest"))
    val datasourceResp = adminRequest("api/v1/datasources").post(Entity.json(iceberg))
    assert(datasourceResp.getStatus === 200)

    val deleteBound = adminRequest("api/v1/datasources/credentials/seaweedfs-rest").delete()
    assert(deleteBound.getStatus === 409)

    val deleteDatasource = adminRequest("api/v1/datasources/iceberg-bound-rest").delete()
    assert(deleteDatasource.getStatus === 204 || deleteDatasource.getStatus === 200)
    val deleteCredential = adminRequest("api/v1/datasources/credentials/seaweedfs-rest").delete()
    assert(deleteCredential.getStatus === 204 || deleteCredential.getStatus === 200)
  }

  test("reject invalid Iceberg catalog configuration") {
    val request = DatasourceRequest(
      label = "invalid-iceberg",
      engineType = "spark",
      icebergConfig = IcebergDatasourceConfig(
        catalogName = "lake-with-dash",
        uri = "http://metastore:9083",
        warehouse = "s3a://iceberg/"))
    val response = adminRequest("api/v1/datasources").post(Entity.json(request))
    assert(response.getStatus === 400)
  }
}
