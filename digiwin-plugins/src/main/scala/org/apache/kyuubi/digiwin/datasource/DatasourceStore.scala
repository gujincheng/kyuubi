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

import java.sql.{Connection, ResultSet}

import scala.collection.mutable.ArrayBuffer

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import org.apache.kyuubi.{KyuubiException, Logging}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

class DatasourceStore(conf: KyuubiConf) extends Logging {

  private val hikariConfig = new HikariConfig()
  hikariConfig.setDriverClassName(
    conf.get(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER).getOrElse(defaultDriver(conf)))
  hikariConfig.setJdbcUrl(
    conf.get(DIGIWIN_DATASOURCE_STORE_JDBC_URL).getOrElse(
      throw new KyuubiException(s"${DIGIWIN_DATASOURCE_STORE_JDBC_URL.key} is not set")))
  hikariConfig.setPoolName("digiwin-datasource-store-pool")

  private[digiwin] val dataSource: HikariDataSource = new HikariDataSource(hikariConfig)

  private def withConnection[T](f: Connection => T): T = {
    val conn = dataSource.getConnection
    try {
      f(conn)
    } finally {
      conn.close()
    }
  }

  initSchema()

  private def defaultDriver(conf: KyuubiConf): String = {
    val url = conf.get(DIGIWIN_DATASOURCE_STORE_JDBC_URL).getOrElse("")
    if (url.startsWith("jdbc:sqlite")) "org.sqlite.JDBC"
    else throw new KyuubiException(s"Cannot infer jdbc driver for $url")
  }

  private def initSchema(): Unit = {
    withConnection { conn =>
      val stmt = conn.createStatement()
      try {
        stmt.execute(
          """
            |CREATE TABLE IF NOT EXISTS digiwin_datasource(
            |  label VARCHAR(128) PRIMARY KEY,
            |  engine_type VARCHAR(32) NOT NULL,
            |  jdbc_type VARCHAR(64),
            |  driver_class VARCHAR(255) NOT NULL,
            |  jdbc_url VARCHAR(2048) NOT NULL,
            |  username VARCHAR(255) NOT NULL,
            |  encrypted_password VARCHAR(2048) NOT NULL,
            |  connection_pool_params TEXT,
            |  status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
            |  description VARCHAR(1024),
            |  update_time BIGINT NOT NULL
            |)
            |""".stripMargin)
      } finally {
        stmt.close()
      }
    }
  }

  private def query[T](conn: Connection, sql: String, params: Any*)(f: ResultSet => T): Seq[T] = {
    val ps = conn.prepareStatement(sql)
    try {
      params.zipWithIndex.foreach { case (p, i) => ps.setObject(i + 1, p) }
      val rs = ps.executeQuery()
      try {
        val buf = ArrayBuffer.empty[T]
        while (rs.next()) buf += f(rs)
        buf.toSeq
      } finally {
        rs.close()
      }
    } finally {
      ps.close()
    }
  }

  // Pool params are stored as "k1=v1;k2=v2".
  private def encodePoolParams(params: Map[String, String]): String =
    if (params.isEmpty) null else params.map { case (k, v) => s"$k=$v" }.mkString(";")

  private def decodePoolParams(s: String): Map[String, String] =
    if (s == null || s.isEmpty) Map.empty
    else s.split(";").flatMap { kv =>
      val idx = kv.indexOf('=')
      if (idx > 0) Some(kv.substring(0, idx) -> kv.substring(idx + 1)) else None
    }.toMap

  private def fromResultSet(rs: ResultSet): DatasourceInfo = {
    DatasourceInfo(
      label = rs.getString("label"),
      engineType = rs.getString("engine_type"),
      jdbcType = rs.getString("jdbc_type"),
      driverClass = rs.getString("driver_class"),
      jdbcUrl = rs.getString("jdbc_url"),
      username = rs.getString("username"),
      encryptedPassword = rs.getString("encrypted_password"),
      connectionPoolParams = decodePoolParams(rs.getString("connection_pool_params")),
      status = rs.getString("status"),
      description = rs.getString("description"))
  }

  def list(): Seq[DatasourceInfo] = withConnection { conn =>
    query(conn, "SELECT * FROM digiwin_datasource ORDER BY label")(fromResultSet)
  }

  def get(label: String): Option[DatasourceInfo] = withConnection { conn =>
    query(conn, "SELECT * FROM digiwin_datasource WHERE label = ?", label)(fromResultSet).headOption
  }

  def upsert(ds: DatasourceInfo): Unit = withConnection { conn =>
    val sql =
      """
        |INSERT INTO digiwin_datasource
        |(label, engine_type, jdbc_type, driver_class, jdbc_url,
        | username, encrypted_password, connection_pool_params, status, description, update_time)
        |VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT(label) DO UPDATE SET
        |engine_type=excluded.engine_type, jdbc_type=excluded.jdbc_type,
        |driver_class=excluded.driver_class, jdbc_url=excluded.jdbc_url,
        |username=excluded.username, encrypted_password=excluded.encrypted_password,
        |connection_pool_params=excluded.connection_pool_params,
        |status=excluded.status, description=excluded.description, update_time=excluded.update_time
        |""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, ds.label)
      ps.setString(2, ds.engineType)
      ps.setString(3, ds.jdbcType)
      ps.setString(4, ds.driverClass)
      ps.setString(5, ds.jdbcUrl)
      ps.setString(6, ds.username)
      ps.setString(7, ds.encryptedPassword)
      ps.setString(8, encodePoolParams(ds.connectionPoolParams))
      ps.setString(9, ds.status)
      ps.setString(10, ds.description)
      ps.setLong(11, System.currentTimeMillis())
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def delete(label: String): Unit = withConnection { conn =>
    val ps = conn.prepareStatement("DELETE FROM digiwin_datasource WHERE label = ?")
    try {
      ps.setString(1, label)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def close(): Unit = dataSource.close()
}
