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

import java.net.{InetSocketAddress, Socket, URI}
import java.sql.Connection

import scala.util.control.NonFatal

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

case class DatasourceConnectionTestResult(
    success: Boolean,
    message: String,
    latencyMillis: Long,
    databaseProduct: String,
    databaseVersion: String)

object DatasourceConnectionTester {
  private[datasource] type EndpointConnector = (String, Int, Int) => Unit

  private val DefaultTimeoutMillis = 5000L
  private val MinimumHikariTimeoutMillis = 250L
  private val MaximumMessageLength = 500

  def test(
      datasource: DatasourceInfo,
      plainPassword: String,
      timeoutMillis: Long = DefaultTimeoutMillis): DatasourceConnectionTestResult = {
    test(datasource, plainPassword, timeoutMillis, connectEndpoint)
  }

  private[datasource] def test(
      datasource: DatasourceInfo,
      plainPassword: String,
      timeoutMillis: Long,
      endpointConnector: EndpointConnector): DatasourceConnectionTestResult = {
    if (datasource.isIceberg) {
      testIceberg(datasource, timeoutMillis, endpointConnector)
    } else if (datasource.isJdbc) {
      testJdbc(datasource, plainPassword, timeoutMillis)
    } else {
      DatasourceConnectionTestResult(
        success = false,
        message = s"Unsupported datasource engine type: ${datasource.engineType}",
        latencyMillis = 0L,
        databaseProduct = "",
        databaseVersion = "")
    }
  }

  private def testJdbc(
      datasource: DatasourceInfo,
      plainPassword: String,
      timeoutMillis: Long): DatasourceConnectionTestResult = {
    val startedAt = System.nanoTime()
    var pool: HikariDataSource = null
    var connection: Connection = null
    try {
      val effectiveTimeout = math.max(MinimumHikariTimeoutMillis, timeoutMillis)
      val config = new HikariConfig()
      config.setPoolName(s"datasource-test-${datasource.label}")
      config.setJdbcUrl(datasource.jdbcUrl)
      config.setDriverClassName(datasource.driverClass)
      config.setMaximumPoolSize(1)
      config.setMinimumIdle(0)
      config.setConnectionTimeout(effectiveTimeout)
      config.setValidationTimeout(math.min(effectiveTimeout, DefaultTimeoutMillis))
      config.setInitializationFailTimeout(effectiveTimeout)
      if (datasource.username.nonEmpty) config.setUsername(datasource.username)
      config.setPassword(Option(plainPassword).getOrElse(""))

      pool = new HikariDataSource(config)
      connection = pool.getConnection
      val metadata = connection.getMetaData
      DatasourceConnectionTestResult(
        success = true,
        message = "Connection successful",
        latencyMillis = elapsedMillis(startedAt),
        databaseProduct = Option(metadata.getDatabaseProductName).getOrElse(""),
        databaseVersion = Option(metadata.getDatabaseProductVersion).getOrElse(""))
    } catch {
      case NonFatal(error) =>
        DatasourceConnectionTestResult(
          success = false,
          message = safeMessage(error, plainPassword),
          latencyMillis = elapsedMillis(startedAt),
          databaseProduct = "",
          databaseVersion = "")
    } finally {
      if (connection != null) {
        try connection.close()
        catch { case NonFatal(_) => }
      }
      if (pool != null) {
        try pool.close()
        catch { case NonFatal(_) => }
      }
    }
  }

  private def testIceberg(
      datasource: DatasourceInfo,
      timeoutMillis: Long,
      endpointConnector: EndpointConnector): DatasourceConnectionTestResult = {
    val startedAt = System.nanoTime()
    try {
      val uri = new URI(datasource.properties(DatasourceInfo.IcebergCatalogUri))
      if (!"thrift".equalsIgnoreCase(uri.getScheme) || uri.getHost == null || uri.getPort <= 0) {
        throw new IllegalArgumentException(
          "Hive Metastore URI must use thrift://host:port")
      }
      endpointConnector(
        uri.getHost,
        uri.getPort,
        math.min(Int.MaxValue.toLong, math.max(1L, timeoutMillis)).toInt)
      DatasourceConnectionTestResult(
        success = true,
        message = "Hive Metastore endpoint is reachable",
        latencyMillis = elapsedMillis(startedAt),
        databaseProduct = "Apache Iceberg",
        databaseVersion = s"Hive Catalog ${uri.getHost}:${uri.getPort}")
    } catch {
      case NonFatal(error) =>
        DatasourceConnectionTestResult(
          success = false,
          message = safeMessage(error, ""),
          latencyMillis = elapsedMillis(startedAt),
          databaseProduct = "",
          databaseVersion = "")
    }
  }

  private def connectEndpoint(host: String, port: Int, timeoutMillis: Int): Unit = {
    val socket = new Socket()
    try socket.connect(new InetSocketAddress(host, port), timeoutMillis)
    finally socket.close()
  }

  private def elapsedMillis(startedAt: Long): Long =
    (System.nanoTime() - startedAt) / 1000000L

  private def safeMessage(error: Throwable, plainPassword: String): String = {
    val fallback = error.getClass.getSimpleName
    val raw = Option(error.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(fallback)
    val redacted = Option(plainPassword)
      .filter(_.nonEmpty)
      .map(raw.replace(_, "******"))
      .getOrElse(raw)
    redacted.take(MaximumMessageLength)
  }
}
