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

import org.apache.kyuubi.KyuubiFunSuite

class MySQLDialectSuite extends KyuubiFunSuite {

  private val dialect: MySQLDialect = new MySQLDialect()

  private def normalize(sql: String): String = sql.replaceAll("\\s+", " ").trim

  test("mysql dialect - get schemas query without schema pattern") {
    val expected =
      "SELECT SCHEMA_NAME AS TABLE_SCHEM, NULL AS TABLE_CATALOG " +
        "FROM INFORMATION_SCHEMA.SCHEMATA"
    assert(normalize(dialect.getSchemasQuery(null)) == expected)
  }

  test("mysql dialect - get schemas query with schema pattern uses a bound parameter") {
    val expected =
      "SELECT SCHEMA_NAME AS TABLE_SCHEM, NULL AS TABLE_CATALOG " +
        "FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME LIKE ?"
    assert(normalize(dialect.getSchemasQuery("test_db")) == expected)
  }

  test("mysql dialect - get schemas query treats blank schema pattern as no filter") {
    val noFilter = normalize(dialect.getSchemasQuery(null))
    assert(normalize(dialect.getSchemasQuery("")) == noFilter)
    assert(normalize(dialect.getSchemasQuery("  ")) == noFilter)
  }

  test("mysql dialect - get schemas query is inherited by StarRocks and Doris dialects") {
    assert(new StarRocksDialect().getSchemasQuery("test_db") == dialect.getSchemasQuery("test_db"))
    assert(new DorisDialect().getSchemasQuery("test_db") == dialect.getSchemasQuery("test_db"))
  }
}
