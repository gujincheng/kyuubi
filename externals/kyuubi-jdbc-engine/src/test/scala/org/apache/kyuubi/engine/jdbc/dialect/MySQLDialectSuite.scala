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

  test("mysql dialect - get schemas operation without filter") {
    val expected =
      "SELECT SCHEMA_NAME AS TABLE_SCHEM, NULL AS TABLE_CATALOG " +
        "FROM INFORMATION_SCHEMA.SCHEMATA"
    assert(normalize(dialect.getSchemasOperation(null, null)) == expected)
  }

  test("mysql dialect - get schemas operation with schema filter") {
    val expected =
      "SELECT SCHEMA_NAME AS TABLE_SCHEM, NULL AS TABLE_CATALOG " +
        "FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME LIKE 'test_db'"
    assert(normalize(dialect.getSchemasOperation(null, "test_db")) == expected)
  }

  test("mysql dialect - get schemas operation ignores catalog filter") {
    // catalog is intentionally ignored (MySQL/StarRocks/Doris catalog is not meaningful),
    // so passing a catalog must not add a CATALOG_NAME filter.
    val expected =
      "SELECT SCHEMA_NAME AS TABLE_SCHEM, NULL AS TABLE_CATALOG " +
        "FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME LIKE 'test_db'"
    assert(normalize(dialect.getSchemasOperation("def", "test_db")) == expected)
  }
}
