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

package org.apache.kyuubi.digiwin.security

import java.sql.{Connection, ResultSet}

import scala.collection.mutable.ArrayBuffer

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import org.apache.kyuubi.{KyuubiException, Logging}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

/**
 * Persists SQL inspection rules in the same JDBC store as the datasource registry
 * (reuses kyuubi.digiwin.datasource.store.jdbc.* config), in a separate table.
 */
class RuleStore(conf: KyuubiConf) extends Logging {

  private val hikariConfig = new HikariConfig()
  hikariConfig.setDriverClassName(
    conf.get(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER).getOrElse(defaultDriver(conf)))
  hikariConfig.setJdbcUrl(
    conf.get(DIGIWIN_DATASOURCE_STORE_JDBC_URL).getOrElse(
      throw new KyuubiException(s"${DIGIWIN_DATASOURCE_STORE_JDBC_URL.key} is not set")))
  hikariConfig.setPoolName("digiwin-sql-rule-store-pool")

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
            |CREATE TABLE IF NOT EXISTS digiwin_sql_rule(
            |  id VARCHAR(128) PRIMARY KEY,
            |  name VARCHAR(255) NOT NULL,
            |  rule_type VARCHAR(32) NOT NULL,
            |  pattern VARCHAR(2048) NOT NULL,
            |  action VARCHAR(32) NOT NULL DEFAULT 'DENY',
            |  engine_scope VARCHAR(32),
            |  user_scope TEXT,
            |  enabled INTEGER NOT NULL,
            |  description VARCHAR(1024),
            |  create_time BIGINT NOT NULL,
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

  // userScope stored as comma-joined.
  private def encodeUserScope(users: Seq[String]): String =
    if (users.isEmpty) null else users.mkString(",")

  private def decodeUserScope(s: String): Seq[String] =
    if (s == null || s.isEmpty) Seq.empty
    else s.split(",").map(_.trim).filter(_.nonEmpty).toSeq

  private def fromResultSet(rs: ResultSet): SqlRule = {
    SqlRule(
      id = rs.getString("id"),
      name = rs.getString("name"),
      ruleType = rs.getString("rule_type"),
      pattern = rs.getString("pattern"),
      action = rs.getString("action"),
      engineScope = Option(rs.getString("engine_scope")).getOrElse(""),
      userScope = decodeUserScope(rs.getString("user_scope")),
      enabled = rs.getInt("enabled") != 0,
      description = Option(rs.getString("description")).getOrElse(""),
      createTime = rs.getLong("create_time"),
      updateTime = rs.getLong("update_time"))
  }

  def list(): Seq[SqlRule] = withConnection { conn =>
    query(conn, "SELECT * FROM digiwin_sql_rule ORDER BY id")(fromResultSet)
  }

  def get(id: String): Option[SqlRule] = withConnection { conn =>
    query(conn, "SELECT * FROM digiwin_sql_rule WHERE id = ?", id)(fromResultSet).headOption
  }

  def upsert(rule: SqlRule): Unit = withConnection { conn =>
    val sql =
      """
        |INSERT INTO digiwin_sql_rule
        |(id, name, rule_type, pattern, action, engine_scope, user_scope,
        | enabled, description, create_time, update_time)
        |VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT(id) DO UPDATE SET
        |name=excluded.name, rule_type=excluded.rule_type, pattern=excluded.pattern,
        |action=excluded.action, engine_scope=excluded.engine_scope,
        |user_scope=excluded.user_scope, enabled=excluded.enabled,
        |description=excluded.description, update_time=excluded.update_time
        |""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      val now = System.currentTimeMillis()
      ps.setString(1, rule.id)
      ps.setString(2, rule.name)
      ps.setString(3, rule.ruleType)
      ps.setString(4, rule.pattern)
      ps.setString(5, rule.action)
      ps.setString(6, if (rule.engineScope.isEmpty) null else rule.engineScope)
      ps.setString(7, encodeUserScope(rule.userScope))
      ps.setInt(8, if (rule.enabled) 1 else 0)
      ps.setString(9, if (rule.description.isEmpty) null else rule.description)
      ps.setLong(10, if (rule.createTime > 0) rule.createTime else now)
      ps.setLong(11, now)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def delete(id: String): Unit = withConnection { conn =>
    val ps = conn.prepareStatement("DELETE FROM digiwin_sql_rule WHERE id = ?")
    try {
      ps.setString(1, id)
      ps.executeUpdate()
    } finally {
      ps.close()
    }
  }

  def close(): Unit = dataSource.close()
}
