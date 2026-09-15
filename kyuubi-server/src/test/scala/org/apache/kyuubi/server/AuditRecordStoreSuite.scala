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

import java.nio.file.Files

import org.apache.kyuubi.KyuubiFunSuite

class AuditRecordStoreSuite extends KyuubiFunSuite {

  private val auditFile = Files.createTempFile("kyuubi-audit-store-", ".jsonl").toFile

  override def beforeEach(): Unit = {
    super.beforeEach()
    AuditRecordStore.setPersistenceFileForTesting(Some(auditFile))
    Files.write(auditFile.toPath, Array.emptyByteArray)
    AuditRecordStore.clear()
  }

  override def afterAll(): Unit = {
    AuditRecordStore.setPersistenceFileForTesting(None)
    Files.deleteIfExists(auditFile.toPath)
    super.afterAll()
  }

  test("stores newest records and redacts sensitive query parameters") {
    val now = System.currentTimeMillis()
    AuditRecordStore.append(
      user = "alice",
      authType = "BASIC",
      ip = "127.0.0.1",
      proxyIp = "",
      forwardedFor = Seq.empty,
      method = "GET",
      uri = "/api/v1/admin/audit",
      query = Some("user=alice&password=plain-text&limit=10"),
      protocol = "HTTP/1.1",
      status = 200,
      timestamp = now)

    val page = AuditRecordStore.query(user = Some("alice"), method = Some("get"))
    assert(page.total === 1)
    val redactedQuery = page.records.head.query.getOrElse("")
    assert(redactedQuery.contains("password=******"))
    assert(!redactedQuery.contains("plain-text"))
  }

  test("drops records outside the retention window") {
    val now = System.currentTimeMillis()
    AuditRecordStore.append(
      user = "expired",
      authType = "BASIC",
      ip = "127.0.0.1",
      proxyIp = "",
      forwardedFor = Seq.empty,
      method = "GET",
      uri = "/expired",
      query = None,
      protocol = "HTTP/1.1",
      status = 200,
      timestamp = now - 25 * 60 * 60 * 1000L)
    AuditRecordStore.append(
      user = "fresh",
      authType = "BASIC",
      ip = "127.0.0.1",
      proxyIp = "",
      forwardedFor = Seq.empty,
      method = "GET",
      uri = "/fresh",
      query = None,
      protocol = "HTTP/1.1",
      status = 200,
      timestamp = now)

    val page = AuditRecordStore.query()
    assert(page.total === 1)
    assert(page.records.head.user === "fresh")
  }

  test("loads records written by another server process") {
    AuditRecordStore.append(
      user = "persisted",
      authType = "BASIC",
      ip = "127.0.0.1",
      proxyIp = "",
      forwardedFor = Seq.empty,
      method = "PUT",
      uri = "/api/v1/admin/policies",
      query = None,
      protocol = "HTTP/1.1",
      status = 200)

    AuditRecordStore.setPersistenceFileForTesting(None)
    AuditRecordStore.setPersistenceFileForTesting(Some(auditFile))
    val page = AuditRecordStore.query(user = Some("persisted"))
    assert(page.total === 1)
    assert(page.records.head.uri === "/api/v1/admin/policies")
  }

  test("stores administrator actions separately from HTTP request records") {
    AuditRecordStore.appendAction(
      user = "admin",
      ip = "127.0.0.1",
      action = "policy.access.replace",
      uri = "/api/v1/admin/policies")

    val page = AuditRecordStore.query(method = Some("ACTION"))
    assert(page.total === 1)
    assert(page.records.head.action === Some("policy.access.replace"))
    assert(page.records.head.protocol === "INTERNAL")
  }
}
