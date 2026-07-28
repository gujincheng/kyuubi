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

class DatasourceStoreSuite extends KyuubiFunSuite {

  private def newConf(): KyuubiConf = {
    val db = Files.createTempFile("digiwin-store-test-", ".db").toString
    new KyuubiConf(false)
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$db")
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
  }

  private def sampleDs(label: String = "sr-prod"): DatasourceInfo =
    DatasourceInfo(
      label,
      "jdbc",
      "starrocks",
      "com.mysql.cj.jdbc.Driver",
      "jdbc:mysql://sr:9030/db",
      "u",
      "enc-pwd",
      connectionPoolParams = Map("maximumPoolSize" -> "10"))

  test("upsert / get / list / delete") {
    val store = new DatasourceStore(newConf())
    val ds = sampleDs()
    store.upsert(ds)
    assert(store.get("sr-prod").map(_.jdbcUrl).contains("jdbc:mysql://sr:9030/db"))
    assert(store.get("sr-prod").map(_.encryptedPassword).contains("enc-pwd"))
    assert(
      store.get("sr-prod").map(_.connectionPoolParams).contains(Map("maximumPoolSize" -> "10")))
    assert(store.list().map(_.label).contains("sr-prod"))

    // update
    store.upsert(ds.copy(
      description = "updated",
      connectionPoolParams = Map("a" -> "1", "b" -> "2")))
    val updated = store.get("sr-prod").get
    assert(updated.description === "updated")
    assert(updated.connectionPoolParams === Map("a" -> "1", "b" -> "2"))

    store.delete("sr-prod")
    assert(store.get("sr-prod").isEmpty)
    store.close()
  }
}
