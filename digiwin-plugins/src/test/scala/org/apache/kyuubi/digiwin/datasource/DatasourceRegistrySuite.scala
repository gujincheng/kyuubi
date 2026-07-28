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

import java.nio.file.Files

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

class DatasourceRegistrySuite extends KyuubiFunSuite {

  private def newRegistry(): DatasourceRegistry = {
    val db = Files.createTempFile("digiwin-reg-test-", ".db").toString
    val conf = new KyuubiConf(false)
      .set(DIGIWIN_DATASOURCE_STORE_ENABLED, true)
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$db")
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
      .set(DIGIWIN_DATASOURCE_CREDENTIAL_SECRET, "0123456789abcdef")
    new DatasourceRegistry(conf)
  }

  test("upsert stores encrypted password; getDecryptedPassword returns plaintext") {
    val reg = newRegistry()
    reg.start()
    val ds = DatasourceInfo(
      "sr-prod",
      "jdbc",
      "starrocks",
      "com.mysql.cj.jdbc.Driver",
      "jdbc:mysql://sr:9030/db",
      "u",
      "")
    reg.upsert(ds, plainPassword = "secret123")

    val cached = reg.get("sr-prod").get
    assert(cached.encryptedPassword !== "secret123")
    assert(cached.encryptedPassword !== "")
    assert(reg.getDecryptedPassword("sr-prod") === "secret123")
    reg.stop()
  }

  test("missing label throws") {
    val reg = newRegistry()
    reg.start()
    intercept[Exception] { reg.getDecryptedPassword("nope") }
    reg.stop()
  }

  test("refresh reloads from store") {
    val reg = newRegistry()
    reg.start()
    reg.upsert(
      DatasourceInfo(
        "sr-prod",
        "jdbc",
        "starrocks",
        "com.mysql.cj.jdbc.Driver",
        "jdbc:mysql://sr:9030/db",
        "u",
        ""),
      plainPassword = "pwd")
    assert(reg.list().map(_.label).contains("sr-prod"))
    reg.refresh()
    assert(reg.list().map(_.label).contains("sr-prod"))
    reg.stop()
  }
}
