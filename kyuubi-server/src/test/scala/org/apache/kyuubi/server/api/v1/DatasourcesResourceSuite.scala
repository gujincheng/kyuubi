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

class DatasourcesResourceSuite extends KyuubiFunSuite with RestFrontendTestHelper {

  private val dbPath: String = Files.createTempFile("digiwin-ds-test-", ".db").toString

  override protected lazy val conf: KyuubiConf = KyuubiConf()
    .set(DIGIWIN_DATASOURCE_STORE_ENABLED, true)
    .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$dbPath")
    .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
    .set(DIGIWIN_DATASOURCE_CREDENTIAL_SECRET, "0123456789abcdef")

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
    val createResp = webTarget.path("api/v1/datasources").request()
      .post(Entity.json(req("sr-prod")))
    assert(createResp.getStatus === 200)
    val created = createResp.readEntity(classOf[DatasourceView])
    assert(created.label === "sr-prod")
    assert(created.engineType === "jdbc")
    assert(created.connectionPoolParams === Map("maximumPoolSize" -> "10"))

    val listResp = webTarget.path("api/v1/datasources").request().get()
    assert(listResp.getStatus === 200)
    val listBody = listResp.readEntity(classOf[String])
    assert(listBody.contains("\"label\":\"sr-prod\""))
    assert(!listBody.contains("pwd"), "plaintext password must not appear in list response")

    val getResp = webTarget.path("api/v1/datasources/sr-prod").request().get()
    assert(getResp.getStatus === 200)
    val got = getResp.readEntity(classOf[DatasourceView])
    assert(got.label === "sr-prod")
  }

  test("update via PUT then delete via DELETE") {
    val createResp = webTarget.path("api/v1/datasources").request()
      .post(Entity.json(req("sr-upd")))
    assert(createResp.getStatus === 200)

    val updateReq = req("sr-upd").copy(description = "updated-desc")
    val putResp = webTarget.path("api/v1/datasources/sr-upd").request()
      .put(Entity.json(updateReq))
    assert(putResp.getStatus === 200)
    val updated = putResp.readEntity(classOf[DatasourceView])
    assert(updated.description === "updated-desc")

    val delResp = webTarget.path("api/v1/datasources/sr-upd").request().delete()
    assert(delResp.getStatus === 204 || delResp.getStatus === 200)

    val getAfter = webTarget.path("api/v1/datasources/sr-upd").request().get()
    assert(getAfter.getStatus === 500 || getAfter.getStatus === 404)
  }

  test("manual refresh endpoint returns ok") {
    val refreshResp = webTarget.path("api/v1/datasources/refresh").request()
      .post(null)
    assert(refreshResp.getStatus === 200)
    assert(refreshResp.readEntity(classOf[String]) === "ok")
  }
}
