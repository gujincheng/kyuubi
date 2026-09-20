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

package org.apache.kyuubi.server.api.v1

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.apache.kyuubi.{KyuubiFunSuite, Utils}

class AdminPoliciesFileStoreSuite extends KyuubiFunSuite {

  private def read(file: java.io.File): String =
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)

  test("update access policies while preserving unrelated configuration") {
    val file = Utils.createTempDir("admin-policies").resolve("kyuubi-defaults.conf").toFile
    Files.write(
      file.toPath,
      "unrelated.setting=keep\nkyuubi.server.limit.connections.user.deny.list=old\n"
        .getBytes(StandardCharsets.UTF_8))

    AdminPoliciesFileStore.updateAccess(
      file,
      AccessPolicies(Seq("z-user", "a-user", "a-user"), Seq("blocked"), Seq("10.0.0.1")))

    val content = read(file)
    assert(content.contains("unrelated.setting=keep"))
    assert(content.contains("kyuubi.server.limit.connections.user.unlimited.list=a-user,z-user"))
    assert(content.contains("kyuubi.server.limit.connections.user.deny.list=blocked"))
    assert(content.contains("kyuubi.server.limit.connections.ip.deny.list=10.0.0.1"))
  }

  test("update and delete user defaults without affecting another user") {
    val file = Utils.createTempDir("admin-policies").resolve("kyuubi-defaults.conf").toFile
    Files.write(
      file.toPath,
      "___alice___.spark.master=old\n___bob___.spark.master=keep\n"
        .getBytes(StandardCharsets.UTF_8))

    AdminPoliciesFileStore.updateUserDefaults(
      file,
      UserDefaultsUpdate("alice", Map("spark.master" -> "local[*]", "spark.app.name" -> "e2e")))
    var content = read(file)
    assert(content.contains("___alice___.spark.master=local[*]"))
    assert(content.contains("___alice___.spark.app.name=e2e"))
    assert(content.contains("___bob___.spark.master=keep"))

    AdminPoliciesFileStore.updateUserDefaults(file, UserDefaultsUpdate("alice", delete = true))
    content = read(file)
    assert(!content.contains("___alice___"))
    assert(content.contains("___bob___.spark.master=keep"))
  }

  test("create and delete a session profile and reject unsafe names") {
    val directory = Utils.createTempDir("admin-policies").toFile
    val update = SessionProfileUpdate(
      "analyst",
      Map("spark.sql.shuffle.partitions" -> "4", "spark.app.name" -> "gateway"))
    AdminPoliciesFileStore.updateProfile(directory, update)

    val profile = new java.io.File(directory, "kyuubi-session-analyst.conf")
    assert(profile.isFile)
    val content = read(profile)
    assert(content.contains("spark.app.name=gateway"))
    assert(content.contains("spark.sql.shuffle.partitions=4"))

    AdminPoliciesFileStore.updateProfile(
      directory,
      SessionProfileUpdate("analyst", delete = true))
    assert(!profile.exists())

    val error = intercept[PolicyFileException] {
      AdminPoliciesFileStore.updateProfile(directory, SessionProfileUpdate("../unsafe"))
    }
    assert(error.status === 400)
  }
}
