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

import java.nio.file.Files

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._

class RuleRegistrySuite extends KyuubiFunSuite {

  private def newRegistry(): RuleRegistry = {
    val db = Files.createTempFile("digiwin-rule-reg-", ".db").toString
    val conf = new KyuubiConf(false)
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$db")
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
    new RuleRegistry(conf)
  }

  test("upsert updates cache synchronously (hot reload)") {
    val reg = newRegistry()
    reg.start()
    try {
      assert(reg.snapshot().isEmpty)
      reg.upsert(SqlRule("block-drop", "block DROP", "KEYWORD", "DROP"))
      // immediately visible, no refresh needed
      assert(reg.snapshot().map(_.id).contains("block-drop"))
      assert(reg.snapshot().length === 1)
    } finally {
      reg.stop()
    }
  }

  test("disabled rules are excluded from snapshot") {
    val reg = newRegistry()
    reg.start()
    try {
      reg.upsert(SqlRule("r1", "r1", "KEYWORD", "DROP", enabled = true))
      reg.upsert(SqlRule("r2", "r2", "KEYWORD", "TRUNCATE", enabled = false))
      assert(reg.snapshot().map(_.id).toSet === Set("r1"))
    } finally {
      reg.stop()
    }
  }

  test("delete removes from cache") {
    val reg = newRegistry()
    reg.start()
    try {
      reg.upsert(SqlRule("r1", "r1", "KEYWORD", "DROP"))
      reg.delete("r1")
      assert(reg.snapshot().isEmpty)
    } finally {
      reg.stop()
    }
  }
}
