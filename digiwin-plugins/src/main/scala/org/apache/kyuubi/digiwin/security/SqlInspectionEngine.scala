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

import java.util.regex.Pattern

object SqlInspectionEngine {

  /** Strip block/line comments and trim. */
  def normalize(sql: String): String = {
    val noBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ")
    val noLine = noBlock.replaceAll("(?m)--.*$", " ")
    noLine.trim
  }

  /** Returns true if the rule matches the (already normalized) single statement. */
  def matches(rule: SqlRule, statement: String): Boolean = {
    if (statement.isEmpty) return false
    rule.ruleType match {
      case "KEYWORD" =>
        val p = Pattern.compile("(?is)^\\s*" + Pattern.quote(rule.pattern) + "\\b")
        p.matcher(statement).find()
      case "WITHOUT_WHERE" =>
        val head = Pattern.compile("(?is)^\\s*" + Pattern.quote(rule.pattern) + "\\b")
        val hasWhere = Pattern.compile("(?is)\\bWHERE\\b").matcher(statement).find()
        head.matcher(statement).find() && !hasWhere
      case "REGEX" =>
        Pattern.compile(rule.pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
          .matcher(statement).find()
      case _ => false
    }
  }

  /**
   * Test a (possibly multi-statement) SQL against the rules.
   * A rule applies only if its engineScope/userScope match the session context
   * (empty scope = match all). Returns the first matching rule, or None.
   */
  def inspect(
      sql: String,
      rules: Seq[SqlRule],
      user: String,
      engineType: String): Option[SqlRule] = {
    val normalized = normalize(sql)
    val statements = normalized.split(";").map(_.trim).filter(_.nonEmpty)
    statements.flatMap { stmt =>
      rules.find { rule =>
        rule.enabled &&
        (rule.engineScope.isEmpty || rule.engineScope == engineType) &&
        (rule.userScope.isEmpty || rule.userScope.contains(user)) &&
        matches(rule, stmt)
      }
    }.headOption
  }
}
