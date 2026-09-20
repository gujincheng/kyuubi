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

      assert(overlay("kyuubi.engine.type") === "JDBC")
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

  test("oracle datasource uses an Oracle-compatible initialization query") {
    val reg = initHolder()
    try {
      reg.upsert(
        DatasourceInfo(
          "oracle-prod",
          "jdbc",
          "oracle",
          "oracle.jdbc.OracleDriver",
          "jdbc:oracle:thin:@//oracle:1521/ORCL",
          "system",
          ""),
        plainPassword = "pwd")
      val sessionConf = new java.util.HashMap[String, String]()
      sessionConf.put("kyuubi.datasource", "oracle-prod")

      val overlay = new DatasourceConfAdvisor().getConfOverlay("alice", sessionConf).asScala

      assert(overlay("kyuubi.engine.jdbc.initialize.sql") === "SELECT 1 FROM DUAL")
    } finally {
      reg.stop()
    }
  }

  test("explicit initialization query is preserved for oracle datasource") {
    val reg = initHolder()
    try {
      reg.upsert(
        DatasourceInfo(
          "oracle-custom-init",
          "jdbc",
          "oracle",
          "oracle.jdbc.OracleDriver",
          "jdbc:oracle:thin:@//oracle:1521/ORCL",
          "system",
          ""),
        plainPassword = "pwd")
      val sessionConf = new java.util.HashMap[String, String]()
      sessionConf.put("kyuubi.datasource", "oracle-custom-init")
      sessionConf.put("kyuubi.engine.jdbc.initialize.sql", "SELECT CURRENT_DATE FROM DUAL")

      val overlay = new DatasourceConfAdvisor().getConfOverlay("alice", sessionConf).asScala

      assert(!overlay.contains("kyuubi.engine.jdbc.initialize.sql"))
    } finally {
      reg.stop()
    }
  }

  test("connection pool params cannot override protected engine configuration") {
    val db = Files.createTempFile("digiwin-adv-protected-", ".db").toString
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
          "sr-protected",
          "jdbc",
          "starrocks",
          "com.mysql.cj.jdbc.Driver",
          "jdbc:mysql://safe:9030/db",
          "u",
          "",
          connectionPoolParams = Map(
            "maximumPoolSize" -> "5",
            "kyuubi.engine.jdbc.connection.url" -> "jdbc:mysql://evil/db")),
        plainPassword = "pwd")
      val sessionConf = new java.util.HashMap[String, String]()
      sessionConf.put("kyuubi.datasource", "sr-protected")
      val overlay = new DatasourceConfAdvisor().getConfOverlay("alice", sessionConf).asScala
      assert(overlay("maximumPoolSize") === "5")
      assert(overlay("kyuubi.engine.jdbc.connection.url") === "jdbc:mysql://safe:9030/db")
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

  test("Iceberg label resolves to an isolated Spark catalog overlay") {
    val reg = initHolder()
    try {
      reg.upsert(
        DatasourceInfo(
          label = "iceberg-prod",
          engineType = "spark",
          jdbcType = "",
          driverClass = "",
          jdbcUrl = "",
          username = "",
          encryptedPassword = "",
          properties = Map(
            DatasourceInfo.IcebergCatalogName -> "lake",
            DatasourceInfo.IcebergCatalogType -> "hive",
            DatasourceInfo.IcebergCatalogUri -> "thrift://172.16.7.137:9083",
            DatasourceInfo.IcebergWarehouse -> "s3a://iceberg/",
            DatasourceInfo.IcebergS3Endpoint -> "http://s3.example:9000",
            DatasourceInfo.IcebergS3PathStyleAccess -> "true",
            DatasourceInfo.IcebergS3SslEnabled -> "false")),
        plainPassword = "")
      val sessionConf = new java.util.HashMap[String, String]()
      sessionConf.put("kyuubi.datasource", "iceberg-prod")
      sessionConf.put("spark.executor.memory", "4g")

      val overlay = new DatasourceConfAdvisor().getConfOverlay("alice", sessionConf).asScala

      assert(overlay("kyuubi.engine.type") === "SPARK_SQL")
      assert(overlay("spark.sql.catalog.lake") === "org.apache.iceberg.spark.SparkCatalog")
      assert(overlay("spark.sql.catalog.lake.type") === "hive")
      assert(overlay("spark.sql.catalog.lake.uri") === "thrift://172.16.7.137:9083")
      assert(overlay("spark.sql.catalog.lake.warehouse") === "s3a://iceberg/")
      assert(overlay("spark.sql.defaultCatalog") === "lake")
      assert(overlay("spark.hadoop.fs.s3a.endpoint") === "http://s3.example:9000")
      assert(overlay("kyuubi.engine.share.level.subdomain").startsWith("ds-iceberg-prod-"))
      assert(!overlay.contains("spark.executor.memory"))
    } finally {
      reg.stop()
    }
  }

  test("Iceberg datasource injects bound storage credentials without using environment provider") {
    val reg = initHolder()
    try {
      reg.upsertStorageCredential("seaweedfs", "s3", "", "access-key", "secret-key", "")
      reg.upsert(
        DatasourceInfo(
          label = "iceberg-credential",
          engineType = "spark",
          jdbcType = "",
          driverClass = "",
          jdbcUrl = "",
          username = "",
          encryptedPassword = "",
          properties = Map(
            DatasourceInfo.IcebergCatalogName -> "lake",
            DatasourceInfo.IcebergCatalogType -> "hive",
            DatasourceInfo.IcebergCatalogUri -> "thrift://metastore:9083",
            DatasourceInfo.IcebergWarehouse -> "s3a://iceberg/",
            DatasourceInfo.IcebergCredentialRef -> "seaweedfs")),
        plainPassword = "")
      val sessionConf = new java.util.HashMap[String, String]()
      sessionConf.put("kyuubi.datasource", "iceberg-credential")

      val overlay = new DatasourceConfAdvisor().getConfOverlay("alice", sessionConf).asScala

      assert(overlay("spark.hadoop.fs.s3a.access.key") === "access-key")
      assert(overlay("spark.hadoop.fs.s3a.secret.key") === "secret-key")
      assert(overlay("spark.hadoop.fs.s3a.aws.credentials.provider") ===
        "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider")
      assert(overlay.contains("kyuubi.digiwin.storage.credential.version"))
      assert(!overlay("kyuubi.engine.share.level.subdomain").contains("secret-key"))
    } finally {
      reg.stop()
    }
  }
}
