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

class RulesResourceSuite extends KyuubiFunSuite with RestFrontendTestHelper {

  private val dbPath: String = Files.createTempFile("digiwin-rule-test-", ".db").toString

  override protected lazy val conf: KyuubiConf = KyuubiConf()
    .set(DIGIWIN_DATASOURCE_STORE_ENABLED, true)
    .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$dbPath")
    .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
    .set(DIGIWIN_SQL_INSPECTION_ENABLED, true)

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
    val createResp = webTarget.path("api/v1/sql-rules").request()
      .post(Entity.json(sampleRule("block-drop")))
    assert(createResp.getStatus === 200)
    val created = createResp.readEntity(classOf[SqlRule])
    assert(created.id === "block-drop")
    assert(created.ruleType === "KEYWORD")

    val getResp = webTarget.path("api/v1/sql-rules/block-drop").request().get()
    assert(getResp.getStatus === 200)
    assert(getResp.readEntity(classOf[SqlRule]).pattern === "DROP")

    val listResp = webTarget.path("api/v1/sql-rules").request().get()
    assert(listResp.getStatus === 200)
    assert(listResp.readEntity(classOf[String]).contains("block-drop"))

    val updateResp = webTarget.path("api/v1/sql-rules/block-drop").request()
      .put(Entity.json(sampleRule("block-drop").copy(description = "updated")))
    assert(updateResp.getStatus === 200)

    val delResp = webTarget.path("api/v1/sql-rules/block-drop").request().delete()
    assert(delResp.getStatus === 204 || delResp.getStatus === 200)
  }

  test("refresh endpoint returns ok") {
    val resp = webTarget.path("api/v1/sql-rules/refresh").request().post(null)
    assert(resp.getStatus === 200)
    assert(resp.readEntity(classOf[String]) === "ok")
  }
}
