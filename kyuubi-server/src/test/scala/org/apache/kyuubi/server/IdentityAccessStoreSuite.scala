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

import org.apache.kyuubi.{KyuubiFunSuite, Utils}

class IdentityAccessStoreSuite extends KyuubiFunSuite {
  private val storeFile = Utils.createTempDir("identity-access-store")
    .resolve("identity-access.json").toFile

  override def beforeEach(): Unit = {
    storeFile.delete()
    IdentityAccessStore.setFileForTesting(Some(storeFile))
    super.beforeEach()
  }

  override def afterEach(): Unit = {
    IdentityAccessStore.setFileForTesting(None)
    storeFile.delete()
    super.afterEach()
  }

  test("persist identity providers and access bindings") {
    val provider = IdentityProviderConfig(
      id = "corp-ldap",
      name = "Corporate LDAP",
      providerType = "LDAP",
      endpoint = "ldap://127.0.0.1:1389",
      baseDn = "dc=example,dc=com")
    IdentityAccessStore.upsertProvider(provider)
    IdentityAccessStore.upsertBinding(IdentityBinding(
      id = "binding-1",
      providerId = provider.id,
      subjectId = "uid=alice,dc=example,dc=com",
      subjectName = "alice",
      role = AdminRole.ViewerName,
      profile = "analyst",
      quotaExempt = true,
      userDefaults = Map("spark.sql.shuffle.partitions" -> "8")))

    val loaded = IdentityAccessStore.load()
    assert(loaded.providers === Seq(provider))
    assert(loaded.bindings.map(_.subjectName) === Seq("alice"))
    assert(loaded.bindings.head.role === AdminRole.ViewerName)
    assert(storeFile.isFile)
  }

  test("reject ambiguous principals and provider deletion with bindings") {
    val ldap = IdentityProviderConfig(
      id = "corp-ldap",
      name = "Corporate LDAP",
      providerType = "LDAP",
      endpoint = "ldap://127.0.0.1:1389",
      baseDn = "dc=example,dc=com")
    val iam = IdentityProviderConfig(
      id = "corp-iam",
      name = "Corporate IAM",
      providerType = "IAM",
      endpoint = "http://127.0.0.1:8080")
    IdentityAccessStore.upsertProvider(ldap)
    IdentityAccessStore.upsertProvider(iam)
    IdentityAccessStore.upsertBinding(IdentityBinding(
      id = "binding-1",
      providerId = ldap.id,
      subjectId = "uid=alice,dc=example,dc=com",
      subjectName = "alice"))

    val ambiguous = intercept[IllegalArgumentException] {
      IdentityAccessStore.upsertBinding(IdentityBinding(
        id = "binding-2",
        providerId = iam.id,
        subjectId = "iam-user-1",
        subjectName = "alice"))
    }
    assert(ambiguous.getMessage.contains("already bound"))

    val referenced = intercept[IllegalStateException] {
      IdentityAccessStore.deleteProvider(ldap.id)
    }
    assert(referenced.getMessage.contains("remove the bindings first"))
  }

  test("validate provider endpoints and secret references") {
    val invalidScheme = intercept[IllegalArgumentException] {
      IdentityAccessStore.upsertProvider(IdentityProviderConfig(
        id = "corp-ldap",
        name = "Corporate LDAP",
        providerType = "LDAP",
        endpoint = "http://127.0.0.1:8080",
        baseDn = "dc=example,dc=com"))
    }
    assert(invalidScheme.getMessage.contains("endpoint"))

    val invalidSecret = intercept[IllegalArgumentException] {
      IdentityAccessStore.upsertProvider(IdentityProviderConfig(
        id = "corp-iam",
        name = "Corporate IAM",
        providerType = "IAM",
        endpoint = "http://127.0.0.1:8080",
        secretEnvironment = "invalid-secret"))
    }
    assert(invalidSecret.getMessage.contains("environment"))
  }
}
