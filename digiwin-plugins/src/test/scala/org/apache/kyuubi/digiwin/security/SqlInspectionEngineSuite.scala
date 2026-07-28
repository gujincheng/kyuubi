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

import org.apache.kyuubi.KyuubiFunSuite

class SqlInspectionEngineSuite extends KyuubiFunSuite {

  private val dropRule = SqlRule("block-drop", "block DROP", "KEYWORD", "DROP")
  private val truncateRule = SqlRule("block-truncate", "block TRUNCATE", "KEYWORD", "TRUNCATE")
  private val deleteNoWhere =
    SqlRule("block-delete-nowhere", "block DELETE without WHERE", "WITHOUT_WHERE", "DELETE")
  private val updateNoWhere =
    SqlRule("block-update-nowhere", "block UPDATE without WHERE", "WITHOUT_WHERE", "UPDATE")
  private val allRules = Seq(dropRule, truncateRule, deleteNoWhere, updateNoWhere)

  test("KEYWORD DROP blocks drop statements, case-insensitive, with leading comment") {
    assert(SqlInspectionEngine.inspect("DROP TABLE t", allRules, "u", "jdbc").contains(dropRule))
    assert(SqlInspectionEngine.inspect("drop database d", allRules, "u", "jdbc").contains(dropRule))
    assert(SqlInspectionEngine.inspect("-- comment\nDROP TABLE t", allRules, "u", "jdbc")
      .contains(dropRule))
    assert(SqlInspectionEngine.inspect("SELECT 1", allRules, "u", "jdbc").isEmpty)
  }

  test("TRUNCATE blocks truncate") {
    assert(SqlInspectionEngine.inspect("TRUNCATE TABLE t", allRules, "u", "jdbc")
      .contains(truncateRule))
  }

  test("WITHOUT_WHERE: DELETE/UPDATE without WHERE blocked, with WHERE allowed") {
    assert(SqlInspectionEngine.inspect("DELETE FROM t", allRules, "u", "jdbc")
      .contains(deleteNoWhere))
    assert(SqlInspectionEngine.inspect("UPDATE t SET a=1", allRules, "u", "jdbc")
      .contains(updateNoWhere))
    assert(SqlInspectionEngine.inspect("DELETE FROM t WHERE id=1", allRules, "u", "jdbc").isEmpty)
    assert(SqlInspectionEngine.inspect(
      "UPDATE t SET a=1 WHERE id=1",
      allRules,
      "u",
      "jdbc").isEmpty)
  }

  test("REGEX rule matches custom pattern") {
    val regexRule = SqlRule("block-select-star", "block SELECT *", "REGEX", "(?is)SELECT\\s+\\*")
    val rules = Seq(regexRule)
    assert(SqlInspectionEngine.inspect("SELECT * FROM t", rules, "u", "jdbc").contains(regexRule))
    assert(SqlInspectionEngine.inspect("SELECT a FROM t", rules, "u", "jdbc").isEmpty)
  }

  test("engineScope: rule only applies to scoped engine") {
    val jdbcOnly = dropRule.copy(engineScope = "jdbc")
    val rules = Seq(jdbcOnly)
    assert(SqlInspectionEngine.inspect("DROP TABLE t", rules, "u", "jdbc").contains(jdbcOnly))
    assert(SqlInspectionEngine.inspect("DROP TABLE t", rules, "u", "spark_sql").isEmpty)
  }

  test("userScope: rule only applies to scoped users") {
    val aliceOnly = dropRule.copy(userScope = Seq("alice"))
    val rules = Seq(aliceOnly)
    assert(SqlInspectionEngine.inspect("DROP TABLE t", rules, "alice", "jdbc").contains(aliceOnly))
    assert(SqlInspectionEngine.inspect("DROP TABLE t", rules, "bob", "jdbc").isEmpty)
  }

  test("disabled rule is skipped") {
    val disabled = dropRule.copy(enabled = false)
    val rules = Seq(disabled)
    assert(SqlInspectionEngine.inspect("DROP TABLE t", rules, "u", "jdbc").isEmpty)
  }

  test("multi-statement: any hit blocks") {
    assert(SqlInspectionEngine.inspect("SELECT 1; DROP TABLE t", allRules, "u", "jdbc")
      .contains(dropRule))
  }

  test("block comment stripped before matching") {
    assert(SqlInspectionEngine.inspect("/* drop hint */ DROP TABLE t", allRules, "u", "jdbc")
      .contains(dropRule))
  }

  test("unknown rule type never matches") {
    val bad = SqlRule("bad", "bad", "UNKNOWN", "x")
    assert(SqlInspectionEngine.inspect("DROP TABLE t", Seq(bad), "u", "jdbc").isEmpty)
  }
}
