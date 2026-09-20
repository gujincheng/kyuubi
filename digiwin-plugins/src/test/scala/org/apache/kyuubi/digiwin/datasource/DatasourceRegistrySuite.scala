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

import java.nio.charset.StandardCharsets
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

  test("blank password on update preserves the stored credential") {
    val reg = newRegistry()
    reg.start()
    val datasource = DatasourceInfo(
      "sr-preserve",
      "jdbc",
      "starrocks",
      "com.mysql.cj.jdbc.Driver",
      "jdbc:mysql://sr:9030/db",
      "u",
      "")
    reg.upsert(datasource, plainPassword = "secret123")
    val encryptedBefore = reg.get("sr-preserve").get.encryptedPassword

    reg.upsert(datasource.copy(description = "updated"), plainPassword = "")

    assert(reg.get("sr-preserve").get.encryptedPassword === encryptedBefore)
    assert(reg.getDecryptedPassword("sr-preserve") === "secret123")
    reg.stop()
  }

  test("empty password on create is not reported as a stored credential") {
    val reg = newRegistry()
    reg.start()
    val datasource = DatasourceInfo(
      "sqlite-no-credential",
      "jdbc",
      "sqlite",
      "org.sqlite.JDBC",
      "jdbc:sqlite:/tmp/no-credential.db",
      "",
      "")

    reg.upsert(datasource, plainPassword = "")

    assert(reg.get("sqlite-no-credential").get.encryptedPassword.isEmpty)
    assert(reg.getDecryptedPassword("sqlite-no-credential").isEmpty)
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

  test("Iceberg properties survive persistence and refresh") {
    val reg = newRegistry()
    reg.start()
    val properties = Map(
      DatasourceInfo.IcebergCatalogName -> "lake",
      DatasourceInfo.IcebergCatalogType -> "hive",
      DatasourceInfo.IcebergCatalogUri -> "thrift://metastore:9083",
      DatasourceInfo.IcebergWarehouse -> "s3a://iceberg/")
    reg.upsert(
      DatasourceInfo(
        label = "iceberg-prod",
        engineType = "spark",
        jdbcType = "",
        driverClass = "",
        jdbcUrl = "",
        username = "",
        encryptedPassword = "",
        properties = properties),
      plainPassword = "")

    reg.refresh()

    val stored = reg.get("iceberg-prod").get
    assert(stored.isIceberg)
    assert(stored.properties === properties)
    assert(stored.encryptedPassword.isEmpty)
    reg.stop()
  }

  test("storage credentials are encrypted, resolved in memory, and protected while bound") {
    val reg = newRegistry()
    reg.start()
    reg.upsertStorageCredential(
      "seaweedfs",
      "s3",
      "E2E object storage",
      "access-key",
      "secret-key",
      "")

    val stored = reg.listStorageCredentials().head
    assert(stored.encryptedAccessKeyId !== "access-key")
    assert(stored.encryptedSecretAccessKey !== "secret-key")
    assert(reg.getStorageCredential("seaweedfs").get.accessKeyId === "access-key")
    assert(reg.getStorageCredential("seaweedfs").get.secretAccessKey === "secret-key")

    reg.upsert(
      DatasourceInfo(
        "iceberg-bound",
        "spark",
        "",
        "",
        "",
        "",
        "",
        properties = Map(
          DatasourceInfo.IcebergCredentialRef -> "seaweedfs")),
      plainPassword = "")
    intercept[Exception] { reg.deleteStorageCredential("seaweedfs") }
    reg.delete("iceberg-bound")
    reg.deleteStorageCredential("seaweedfs")
    assert(!reg.storageCredentialExists("seaweedfs"))
    reg.stop()
  }

  test("bootstrap file registers datasource and storage credentials from environment variables") {
    val db = Files.createTempFile("digiwin-bootstrap-test-", ".db").toString
    val bootstrap = Files.createTempFile("digiwin-bootstrap-", ".properties")
    Files.write(
      bootstrap,
      """credential.test.provider=s3
        |credential.test.accessKeyEnv=PATH
        |credential.test.secretKeyEnv=PATH
        |datasource.pg.engineType=jdbc
        |datasource.pg.jdbcType=postgresql
        |datasource.pg.driverClass=org.postgresql.Driver
        |datasource.pg.jdbcUrlEnv=PATH
        |datasource.pg.usernameEnv=PATH
        |datasource.pg.passwordEnv=PATH
        |datasource.pg.property.iceberg.catalog.name=ignored
        |""".stripMargin.getBytes(StandardCharsets.UTF_8))
    val conf = new KyuubiConf(false)
      .set(DIGIWIN_DATASOURCE_STORE_ENABLED, true)
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$db")
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
      .set(DIGIWIN_DATASOURCE_CREDENTIAL_SECRET, "0123456789abcdef")
      .set(DIGIWIN_DATASOURCE_BOOTSTRAP_FILE, bootstrap.toString)
    val reg = new DatasourceRegistry(conf)
    reg.start()

    assert(reg.get("pg").exists(_.jdbcType == "postgresql"))
    assert(reg.getDecryptedPassword("pg") === sys.env("PATH"))
    assert(reg.getStorageCredential("test").exists(_.accessKeyId == sys.env("PATH")))

    reg.stop()
    Files.deleteIfExists(bootstrap)
    Files.deleteIfExists(java.nio.file.Paths.get(db))
  }
}
