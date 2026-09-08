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
package org.apache.kyuubi.engine.jdbc.dialect
import java.sql.{Connection, ResultSet, Statement}

import org.apache.kyuubi.engine.jdbc.mysql.{MySQLSchemaHelper, MySQLTRowSetGenerator}
import org.apache.kyuubi.engine.jdbc.schema.{JdbcTRowSetGenerator, SchemaHelper}
import org.apache.kyuubi.operation.meta.ResultSetSchemaConstant._

class MySQLDialect extends JdbcDialect {
  override def createStatement(connection: Connection, fetchSize: Int): Statement = {
    val statement =
      connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
    statement.setFetchSize(Integer.MIN_VALUE)
    statement
  }

  // MySQL Connector/J's setCatalog/setSchema are mutually exclusive via the `databaseTerm`
  // driver property; write both so the update always lands regardless of user config.
  // Inherited by Doris/StarRocks.
  override def setSchema(conn: Connection, schema: String): Unit = setDatabase(conn, schema)

  override def setCatalog(conn: Connection, catalog: String): Unit = setDatabase(conn, catalog)

  override def getCurrentSchema(conn: Connection): String = readDatabase(conn)

  override def getCatalog(conn: Connection): String = readDatabase(conn)

  private def setDatabase(conn: Connection, db: String): Unit = {
    conn.setCatalog(db)
    conn.setSchema(db)
  }

  private def readDatabase(conn: Connection): String = {
    val c = conn.getCatalog
    if (c != null && c.nonEmpty) c else conn.getSchema
  }

  /**
   * MySQL-family databases (MySQL, StarRocks, Doris) treat database as schema, and their JDBC
   * drivers report no schemas via `DatabaseMetaData.getSchemas`. List databases from
   * INFORMATION_SCHEMA.SCHEMATA instead so metadata clients (e.g. DBeaver) can browse the
   * schema layer.
   *
   * The catalog argument is intentionally ignored: these databases report a fixed, unusable
   * catalog (e.g. 'def'), which clients would otherwise use to qualify table references
   * (e.g. `def.db.table`).
   */
  override def getSchemas(conn: Connection, catalog: String, schemaPattern: String): ResultSet = {
    val statement = conn.prepareStatement(getSchemasQuery(schemaPattern))
    if (schemaPattern != null && schemaPattern.trim.nonEmpty) {
      statement.setString(1, schemaPattern)
    }
    statement.executeQuery()
  }

  private[dialect] def getSchemasQuery(schemaPattern: String): String = {
    val query =
      s"""
         |SELECT SCHEMA_NAME AS $TABLE_SCHEM, NULL AS $TABLE_CATALOG
         |FROM INFORMATION_SCHEMA.SCHEMATA""".stripMargin
    if (schemaPattern != null && schemaPattern.trim.nonEmpty) {
      query + " WHERE SCHEMA_NAME LIKE ?"
    } else {
      query
    }
  }

  override def getTRowSetGenerator(): JdbcTRowSetGenerator = new MySQLTRowSetGenerator

  override def getSchemaHelper(): SchemaHelper = {
    new MySQLSchemaHelper
  }

  override def name(): String = {
    "mysql"
  }
}
