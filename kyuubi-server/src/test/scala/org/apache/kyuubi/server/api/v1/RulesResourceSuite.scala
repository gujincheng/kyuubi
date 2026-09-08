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
import org.apache.kyuubi.digiwin.security.SqlRule
import org.apache.kyuubi.server.http.util.HttpAuthUtils
import org.apache.kyuubi.server.http.util.HttpAuthUtils.AUTHORIZATION_HEADER
import org.apache.kyuubi.service.authentication.AnonymousAuthenticationProviderImpl

class RulesResourceSuite extends KyuubiFunSuite with RestFrontendTestHelper {

  private val dbPath: String = Files.createTempFile("digiwin-rule-test-", ".db").toString

  override protected lazy val conf: KyuubiConf = KyuubiConf()
    .set(AUTHENTICATION_METHOD, Seq("CUSTOM"))
    .set(AUTHENTICATION_CUSTOM_CLASS, classOf[AnonymousAuthenticationProviderImpl].getName)
    .set(SERVER_ADMINISTRATORS, Set("admin001"))
    .set(DIGIWIN_DATASOURCE_STORE_ENABLED, true)
    .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$dbPath")
    .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
    .set(DIGIWIN_SQL_INSPECTION_ENABLED, true)

  private def adminRequest(path: String) = webTarget.path(path).request()
    .header(AUTHORIZATION_HEADER, HttpAuthUtils.basicAuthorizationHeader("admin001"))

  private def userRequest(path: String) = webTarget.path(path).request()
    .header(AUTHORIZATION_HEADER, HttpAuthUtils.basicAuthorizationHeader("user001"))

  private def sampleRule(id: String): SqlRule = SqlRule(
    id,
    "block DROP",
    "KEYWORD",
    "DROP",
    engineScope = "jdbc",
    userScope = Seq("alice"),
    enabled = true,
    description = "no drop")

  test("create / get / list / update / delete via REST") {
    val createResp = adminRequest("api/v1/sql-rules")
      .post(Entity.json(sampleRule("block-drop")))
    assert(createResp.getStatus === 200)
    val created = createResp.readEntity(classOf[SqlRule])
    assert(created.id === "block-drop")
    assert(created.ruleType === "KEYWORD")

    val getResp = adminRequest("api/v1/sql-rules/block-drop").get()
    assert(getResp.getStatus === 200)
    assert(getResp.readEntity(classOf[SqlRule]).pattern === "DROP")

    val listResp = adminRequest("api/v1/sql-rules").get()
    assert(listResp.getStatus === 200)
    assert(listResp.readEntity(classOf[String]).contains("block-drop"))

    val updateResp = adminRequest("api/v1/sql-rules/block-drop")
      .put(Entity.json(sampleRule("block-drop").copy(description = "updated")))
    assert(updateResp.getStatus === 200)

    val delResp = adminRequest("api/v1/sql-rules/block-drop").delete()
    assert(delResp.getStatus === 204 || delResp.getStatus === 200)
  }

  test("refresh endpoint returns ok") {
    val resp = adminRequest("api/v1/sql-rules/refresh").post(null)
    assert(resp.getStatus === 200)
    assert(resp.readEntity(classOf[String]) === "ok")
  }

  test("non-administrator cannot modify SQL inspection rules") {
    val createResp = userRequest("api/v1/sql-rules")
      .post(Entity.json(sampleRule("user-rule")))
    assert(createResp.getStatus === 403)

    val listResp = userRequest("api/v1/sql-rules").get()
    assert(listResp.getStatus === 200)
  }
}
