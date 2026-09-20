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

class DatasourceConnectionTesterSuite extends KyuubiFunSuite {

  test("test a real SQLite connection and return database metadata") {
    val target = Files.createTempFile("digiwin-datasource-target-", ".db")
    val result = DatasourceConnectionTester.test(
      DatasourceInfo(
        label = "sqlite-test",
        engineType = "jdbc",
        jdbcType = "sqlite",
        driverClass = "org.sqlite.JDBC",
        jdbcUrl = s"jdbc:sqlite:$target",
        username = "",
        encryptedPassword = ""),
      plainPassword = "")

    assert(result.success)
    assert(result.message === "Connection successful")
    assert(result.databaseProduct.toLowerCase.contains("sqlite"))
    assert(result.latencyMillis >= 0)
  }

  test("return a safe failure when the JDBC driver is unavailable") {
    val result = DatasourceConnectionTester.test(
      DatasourceInfo(
        label = "missing-driver",
        engineType = "jdbc",
        jdbcType = "missing",
        driverClass = "example.missing.Driver",
        jdbcUrl = "jdbc:missing:test",
        username = "user",
        encryptedPassword = ""),
      plainPassword = "top-secret")

    assert(!result.success)
    assert(result.message.nonEmpty)
    assert(!result.message.contains("top-secret"))
  }

  test("test an Iceberg Hive Metastore endpoint") {
    var connectedEndpoint = Option.empty[(String, Int, Int)]
    val result = DatasourceConnectionTester.test(
      DatasourceInfo(
        label = "iceberg-test",
        engineType = "spark",
        jdbcType = "",
        driverClass = "",
        jdbcUrl = "",
        username = "",
        encryptedPassword = "",
        properties = Map(
          DatasourceInfo.IcebergCatalogUri -> "thrift://metastore.internal:9083")),
      plainPassword = "",
      timeoutMillis = 3210L,
      endpointConnector = (host, port, timeout) => {
        connectedEndpoint = Some((host, port, timeout))
      })

    assert(result.success)
    assert(result.databaseProduct === "Apache Iceberg")
    assert(result.message.contains("reachable"))
    assert(connectedEndpoint.contains(("metastore.internal", 9083, 3210)))
  }
}
