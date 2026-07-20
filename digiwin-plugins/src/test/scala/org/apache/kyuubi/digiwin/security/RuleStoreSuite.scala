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

class RuleStoreSuite extends KyuubiFunSuite {

  private def newConf(): KyuubiConf = {
    val db = Files.createTempFile("digiwin-rule-store-", ".db").toString
    new KyuubiConf(false)
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_URL, s"jdbc:sqlite:$db")
      .set(DIGIWIN_DATASOURCE_STORE_JDBC_DRIVER, "org.sqlite.JDBC")
  }

  private def sampleRule(id: String = "block-drop"): SqlRule =
    SqlRule(
      id,
      "block DROP",
      "KEYWORD",
      "DROP",
      engineScope = "jdbc",
      userScope = Seq("alice", "carol"),
      enabled = true,
      description = "no drop")

  test("upsert / get / list / delete") {
    val store = new RuleStore(newConf())
    val rule = sampleRule()
    store.upsert(rule)
    assert(store.get("block-drop").map(_.ruleType).contains("KEYWORD"))
    assert(store.get("block-drop").map(_.userScope).contains(Seq("alice", "carol")))
    assert(store.get("block-drop").map(_.engineScope).contains("jdbc"))
    assert(store.list().map(_.id).contains("block-drop"))

    // update
    store.upsert(rule.copy(enabled = false, userScope = Seq("bob")))
    val updated = store.get("block-drop").get
    assert(!updated.enabled)
    assert(updated.userScope === Seq("bob"))

    store.delete("block-drop")
    assert(store.get("block-drop").isEmpty)
    store.close()
  }
}
