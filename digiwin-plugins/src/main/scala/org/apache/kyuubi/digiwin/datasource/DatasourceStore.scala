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

import java.sql.{Connection, ResultSet, SQLException}

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
        stmt.execute(
          """
            |CREATE TABLE IF NOT EXISTS digiwin_datasource_property(
            |  label VARCHAR(128) NOT NULL,
            |  property_key VARCHAR(255) NOT NULL,
            |  property_value TEXT NOT NULL,
            |  PRIMARY KEY(label, property_key)
            |)
            |""".stripMargin)
        stmt.execute(
          """
            |CREATE TABLE IF NOT EXISTS digiwin_storage_credential(
            |  credential_id VARCHAR(128) PRIMARY KEY,
            |  provider VARCHAR(32) NOT NULL,
            |  encrypted_access_key_id TEXT NOT NULL,
            |  encrypted_secret_access_key TEXT NOT NULL,
            |  encrypted_session_token TEXT,
            |  description VARCHAR(1024),
            |  version BIGINT NOT NULL
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

  private def properties(conn: Connection, label: String): Map[String, String] =
    query(
      conn,
      "SELECT property_key, property_value FROM digiwin_datasource_property WHERE label = ?",
      label) { rs =>
      rs.getString("property_key") -> rs.getString("property_value")
    }.toMap

  def list(): Seq[DatasourceInfo] = withConnection { conn =>
    query(conn, "SELECT * FROM digiwin_datasource ORDER BY label")(fromResultSet)
      .map(ds => ds.copy(properties = properties(conn, ds.label)))
  }

  def get(label: String): Option[DatasourceInfo] = withConnection { conn =>
    query(conn, "SELECT * FROM digiwin_datasource WHERE label = ?", label)(fromResultSet)
      .headOption.map(ds => ds.copy(properties = properties(conn, ds.label)))
  }

  private def fromStorageCredentialResultSet(rs: ResultSet): StoredStorageCredential =
    StoredStorageCredential(
      id = rs.getString("credential_id"),
      provider = rs.getString("provider"),
      encryptedAccessKeyId = rs.getString("encrypted_access_key_id"),
      encryptedSecretAccessKey = rs.getString("encrypted_secret_access_key"),
      encryptedSessionToken = Option(rs.getString("encrypted_session_token")).getOrElse(""),
      description = Option(rs.getString("description")).getOrElse(""),
      version = rs.getLong("version"))

  def listStorageCredentials(): Seq[StoredStorageCredential] = withConnection { conn =>
    query(conn, "SELECT * FROM digiwin_storage_credential ORDER BY credential_id")(
      fromStorageCredentialResultSet)
  }

  def getStorageCredential(id: String): Option[StoredStorageCredential] = withConnection {
    conn =>
      query(conn, "SELECT * FROM digiwin_storage_credential WHERE credential_id = ?", id)(
        fromStorageCredentialResultSet).headOption
  }

  def upsertStorageCredential(credential: StoredStorageCredential): Unit = withConnection {
    conn =>
      val sql =
        """
          |INSERT INTO digiwin_storage_credential
          |(credential_id, provider, encrypted_access_key_id, encrypted_secret_access_key,
          | encrypted_session_token, description, version)
          |VALUES(?, ?, ?, ?, ?, ?, ?)
          |ON CONFLICT(credential_id) DO UPDATE SET
          |provider=excluded.provider,
          |encrypted_access_key_id=excluded.encrypted_access_key_id,
          |encrypted_secret_access_key=excluded.encrypted_secret_access_key,
          |encrypted_session_token=excluded.encrypted_session_token,
          |description=excluded.description,
          |version=excluded.version
          |""".stripMargin
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, credential.id)
        ps.setString(2, credential.provider)
        ps.setString(3, credential.encryptedAccessKeyId)
        ps.setString(4, credential.encryptedSecretAccessKey)
        ps.setString(5, credential.encryptedSessionToken)
        ps.setString(6, credential.description)
        ps.setLong(7, credential.version)
        ps.executeUpdate()
      } finally {
        ps.close()
      }
  }

  def deleteStorageCredential(id: String): Unit = withConnection { conn =>
    val ps = conn.prepareStatement(
      "DELETE FROM digiwin_storage_credential WHERE credential_id = ?")
    try {
      ps.setString(1, id)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
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
    conn.setAutoCommit(false)
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
      replaceProperties(conn, ds)
      conn.commit()
    } catch {
      case error: SQLException =>
        conn.rollback()
        throw error
    } finally {
      ps.close()
      conn.setAutoCommit(true)
    }
  }

  private def replaceProperties(conn: Connection, ds: DatasourceInfo): Unit = {
    val delete = conn.prepareStatement(
      "DELETE FROM digiwin_datasource_property WHERE label = ?")
    try {
      delete.setString(1, ds.label)
      delete.executeUpdate()
    } finally {
      delete.close()
    }
    if (ds.properties.nonEmpty) {
      val insert = conn.prepareStatement(
        "INSERT INTO digiwin_datasource_property(label, property_key, property_value) " +
          "VALUES(?, ?, ?)")
      try {
        ds.properties.toSeq.sortBy(_._1).foreach { case (key, value) =>
          insert.setString(1, ds.label)
          insert.setString(2, key)
          insert.setString(3, value)
          insert.addBatch()
        }
        insert.executeBatch()
      } finally {
        insert.close()
      }
    }
  }

  def delete(label: String): Unit = withConnection { conn =>
    conn.setAutoCommit(false)
    val properties = conn.prepareStatement(
      "DELETE FROM digiwin_datasource_property WHERE label = ?")
    val datasource = conn.prepareStatement("DELETE FROM digiwin_datasource WHERE label = ?")
    try {
      properties.setString(1, label)
      properties.executeUpdate()
      datasource.setString(1, label)
      datasource.executeUpdate()
      conn.commit()
    } catch {
      case error: SQLException =>
        conn.rollback()
        throw error
    } finally {
      properties.close()
      datasource.close()
      conn.setAutoCommit(true)
    }
  }

  def close(): Unit = dataSource.close()
}
