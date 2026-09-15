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

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.{Executors, ExecutorService}
import javax.security.sasl.AuthenticationException

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import com.unboundid.ldap.listener.{InMemoryDirectoryServer, InMemoryDirectoryServerConfig}

import org.apache.kyuubi.{KyuubiFunSuite, Utils}

class IdentityDirectoryServiceSuite extends KyuubiFunSuite {
  private var ldapServer: InMemoryDirectoryServer = _
  private var iamServer: HttpServer = _
  private var iamExecutor: ExecutorService = _
  private val identityFile = Utils.createTempDir("identity-directory")
    .resolve("identity-access.json").toFile

  private def ldapProvider = IdentityProviderConfig(
    id = "test-ldap",
    name = "Test LDAP",
    providerType = "LDAP",
    endpoint = s"ldap://127.0.0.1:${ldapServer.getListenPort}",
    baseDn = "dc=example,dc=com",
    userDnPattern = "uid={0},ou=users,dc=example,dc=com")

  private def iamProvider = IdentityProviderConfig(
    id = "test-iam",
    name = "Test IAM",
    providerType = "IAM",
    endpoint = s"http://127.0.0.1:${iamServer.getAddress.getPort}")

  override def beforeAll(): Unit = {
    super.beforeAll()
    val ldapConfig = new InMemoryDirectoryServerConfig("dc=example,dc=com")
    ldapConfig.setSchema(null)
    ldapServer = new InMemoryDirectoryServer(ldapConfig)
    ldapServer.add(
      "dn: dc=example,dc=com",
      "objectClass: top",
      "objectClass: domain",
      "dc: example")
    ldapServer.add(
      "dn: ou=users,dc=example,dc=com",
      "objectClass: top",
      "objectClass: organizationalUnit",
      "ou: users")
    ldapServer.add(
      "dn: uid=alice,ou=users,dc=example,dc=com",
      "objectClass: top",
      "objectClass: person",
      "objectClass: organizationalPerson",
      "objectClass: inetOrgPerson",
      "uid: alice",
      "cn: Alice Chen",
      "sn: Chen",
      "mail: alice@example.com",
      "userPassword: alice-secret")
    ldapServer.startListening()

    iamServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    iamServer.createContext(
      "/users",
      jsonHandler(
        200,
        """{"users":[{"id":"iam-1","username":"bob","displayName":"Bob Li","email":"bob@example.com","groups":["analysts"]}]}"""))
    iamServer.createContext(
      "/groups",
      jsonHandler(
        200,
        """{"groups":[{"id":"group-1","name":"analysts","displayName":"Analysts","members":["bob"]}]}"""))
    iamServer.createContext(
      "/authenticate",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          val body = scala.io.Source.fromInputStream(exchange.getRequestBody).mkString
          if (body.contains("\"username\":\"bob\"") &&
            body.contains("\"password\":\"bob-secret\"")) {
            respond(exchange, 200, "{\"authenticated\":true}")
          } else {
            respond(exchange, 401, "{\"authenticated\":false}")
          }
        }
      })
    iamExecutor = Executors.newCachedThreadPool()
    iamServer.setExecutor(iamExecutor)
    iamServer.start()
  }

  override def afterAll(): Unit = {
    IdentityAccessStore.setFileForTesting(None)
    if (iamServer != null) iamServer.stop(0)
    if (iamExecutor != null) iamExecutor.shutdownNow()
    if (ldapServer != null) ldapServer.close()
    super.afterAll()
  }

  test("query and authenticate against a real in-memory LDAP server") {
    val subjects = IdentityDirectoryService.subjects(ldapProvider, "alice", "USER")
    assert(subjects.map(_.name) === Seq("alice"))
    assert(subjects.head.displayName === "Alice Chen")
    assert(subjects.head.email === "alice@example.com")
    assert(IdentityDirectoryService.test(ldapProvider).success)

    IdentityDirectoryService.authenticate(ldapProvider, "alice", "alice-secret")
    intercept[AuthenticationException] {
      IdentityDirectoryService.authenticate(ldapProvider, "alice", "wrong")
    }
  }

  test("query and authenticate against a lightweight IAM REST service") {
    val users = IdentityDirectoryService.subjects(iamProvider, "bob", "USER")
    assert(users.map(_.name) === Seq("bob"))
    assert(users.head.groups === Seq("analysts"))
    val groups = IdentityDirectoryService.subjects(iamProvider, "", "GROUP")
    assert(groups.map(_.name) === Seq("analysts"))

    IdentityDirectoryService.authenticate(iamProvider, "bob", "bob-secret")
    intercept[AuthenticationException] {
      IdentityDirectoryService.authenticate(iamProvider, "bob", "wrong")
    }
  }

  test("managed authentication tries all enabled identity providers") {
    identityFile.delete()
    IdentityAccessStore.setFileForTesting(Some(identityFile))
    IdentityAccessStore.upsertProvider(ldapProvider)
    IdentityAccessStore.upsertProvider(iamProvider)
    IdentityAccessStore.upsertBinding(IdentityBinding(
      id = "alice-binding",
      providerId = ldapProvider.id,
      subjectId = "uid=alice,ou=users,dc=example,dc=com",
      subjectName = "alice"))
    IdentityAccessStore.upsertBinding(IdentityBinding(
      id = "bob-binding",
      providerId = iamProvider.id,
      subjectId = "iam-1",
      subjectName = "bob"))
    val authenticator = new ManagedIdentityAuthenticationProvider

    authenticator.authenticate("alice", "alice-secret")
    authenticator.authenticate("bob", "bob-secret")
    intercept[AuthenticationException] {
      authenticator.authenticate("missing", "wrong")
    }
    IdentityAccessStore.upsertBinding(IdentityBinding(
      id = "bob-binding",
      providerId = iamProvider.id,
      subjectId = "iam-1",
      subjectName = "bob",
      access = "DENIED"))
    intercept[AuthenticationException] {
      authenticator.authenticate("bob", "bob-secret")
    }
  }

  private def jsonHandler(status: Int, body: String): HttpHandler = new HttpHandler {
    override def handle(exchange: HttpExchange): Unit = respond(exchange, status, body)
  }

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length)
    val output = exchange.getResponseBody
    try output.write(bytes)
    finally output.close()
  }
}
