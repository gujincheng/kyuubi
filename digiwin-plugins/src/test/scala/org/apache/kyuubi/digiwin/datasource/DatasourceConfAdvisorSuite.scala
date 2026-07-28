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

import scala.collection.JavaConverters._

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

class DatasourceConfAdvisorSuite extends KyuubiFunSuite {

  private def initHolder(): DatasourceRegistry = {
    val db = Files.createTempFile("digiwin-adv-test-", ".db").toString
    val conf = new KyuubiConf(false)
      .set(DIGIWIN_DATASOURCE_STORE_ENABLED, true)
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$db")
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
      .set(DIGIWIN_DATASOURCE_CREDENTIAL_SECRET, "0123456789abcdef")
      .set(DIGIWIN_DATASOURCE_LABEL_KEY, "kyuubi.datasource")
    val reg = new DatasourceRegistry(conf)
    reg.start()
    DatasourceRegistryHolder.init(reg, conf.get(DIGIWIN_DATASOURCE_LABEL_KEY))
    reg.upsert(
      DatasourceInfo(
        "sr-prod",
        "jdbc",
        "starrocks",
        "com.mysql.cj.jdbc.Driver",
        "jdbc:mysql://172.16.101.227:19030",
        "root",
        ""),
      plainPassword = "DiGiWin@Sr312")
    reg
  }

  test("label resolves to jdbc engine overlay with decrypted password") {
    val reg = initHolder()
    try {
      val sessionConf = new java.util.HashMap[String, String]()
      sessionConf.put("kyuubi.datasource", "sr-prod")

      val overlay = new DatasourceConfAdvisor().getConfOverlay("alice", sessionConf).asScala

      assert(overlay("kyuubi.engine.type") === "jdbc")
      assert(overlay("kyuubi.engine.jdbc.type") === "starrocks")
      assert(overlay("kyuubi.engine.jdbc.connection.url") === "jdbc:mysql://172.16.101.227:19030")
      assert(overlay("kyuubi.engine.jdbc.connection.user") === "root")
      assert(overlay("kyuubi.engine.jdbc.connection.password") === "DiGiWin@Sr312")
      assert(overlay("kyuubi.engine.jdbc.driver.class") === "com.mysql.cj.jdbc.Driver")
    } finally {
      reg.stop()
    }
  }

  test("connection pool params are injected into overlay") {
    val db = Files.createTempFile("digiwin-adv-pool-", ".db").toString
    val conf = new KyuubiConf(false)
      .set(DIGIWIN_DATASOURCE_STORE_ENABLED, true)
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$db")
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
      .set(DIGIWIN_DATASOURCE_CREDENTIAL_SECRET, "0123456789abcdef")
    val reg = new DatasourceRegistry(conf)
    reg.start()
    DatasourceRegistryHolder.init(reg, "kyuubi.datasource")
    try {
      reg.upsert(
        DatasourceInfo(
          "sr-pool",
          "jdbc",
          "starrocks",
          "com.mysql.cj.jdbc.Driver",
          "jdbc:mysql://sr:9030/db",
          "u",
          "",
          connectionPoolParams = Map("maximumPoolSize" -> "5", "connectionTimeout" -> "30000")),
        plainPassword = "pwd")
      val sessionConf = new java.util.HashMap[String, String]()
      sessionConf.put("kyuubi.datasource", "sr-pool")
      val overlay = new DatasourceConfAdvisor().getConfOverlay("alice", sessionConf).asScala
      assert(overlay("maximumPoolSize") === "5")
      assert(overlay("connectionTimeout") === "30000")
    } finally {
      reg.stop()
    }
  }

  test("no label returns empty overlay") {
    val reg = initHolder()
    try {
      val empty = new java.util.HashMap[String, String]()
      assert(new DatasourceConfAdvisor().getConfOverlay("alice", empty).isEmpty)
    } finally {
      reg.stop()
    }
  }

  test("unknown label throws") {
    val reg = initHolder()
    try {
      val sessionConf = new java.util.HashMap[String, String]()
      sessionConf.put("kyuubi.datasource", "does-not-exist")
      intercept[Exception] {
        new DatasourceConfAdvisor().getConfOverlay("alice", sessionConf)
      }
    } finally {
      reg.stop()
    }
  }
}
