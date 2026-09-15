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

package org.apache.kyuubi.server

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.Utils

class AdminPermissionStoreSuite extends KyuubiFunSuite {

  private val permissionFile = Utils.createTempDir("kyuubi-admin-permissions")
    .resolve("kyuubi-admin-permissions.json")
    .toFile

  override def beforeEach(): Unit = {
    super.beforeEach()
    AdminPermissionStore.setFileForTesting(Some(permissionFile))
    permissionFile.delete()
  }

  override def afterAll(): Unit = {
    AdminPermissionStore.setFileForTesting(None)
    permissionFile.delete()
    super.afterAll()
  }

  test("persists and reloads user role assignments") {
    val assignments = Seq(
      PermissionAssignment("alice", AdminRole.ViewerName),
      PermissionAssignment("bob", AdminRole.PlatformAdminName))

    AdminPermissionStore.replace(assignments)

    assert(AdminPermissionStore.load() === assignments)
    assert(permissionFile.isFile)
  }

  test("rejects duplicate users and unknown roles") {
    intercept[IllegalArgumentException] {
      AdminPermissionStore.replace(Seq(
        PermissionAssignment("alice", AdminRole.ViewerName),
        PermissionAssignment("alice", AdminRole.PlatformAdminName)))
    }
    intercept[IllegalArgumentException] {
      AdminPermissionStore.replace(Seq(PermissionAssignment("alice", "operator")))
    }
    intercept[IllegalArgumentException] {
      AdminPermissionStore.replace(Seq(PermissionAssignment("alice", "policy-admin")))
    }
  }

  test("exposes only viewer and platform administrator roles") {
    assert(AdminRole.all.map(_.name) === Seq(
      AdminRole.ViewerName,
      AdminRole.PlatformAdminName))
    assert(AdminRole.Viewer.permissions("session") === Set(AdminRole.Read))
    assert(AdminRole.PlatformAdmin.permissions("permissions").contains(AdminRole.Manage))
  }
}
