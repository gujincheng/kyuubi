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

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.{Executors, ExecutorService}
import javax.ws.rs.client.Entity
import javax.ws.rs.core.MediaType

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import com.unboundid.ldap.listener.{InMemoryDirectoryServer, InMemoryDirectoryServerConfig}

import org.apache.kyuubi.{KyuubiFunSuite, RestFrontendTestHelper, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.{AUTHENTICATION_CUSTOM_CLASS, AUTHENTICATION_METHOD, FRONTEND_REST_BIND_PORT, SERVER_ADMINISTRATORS, SERVER_LIMIT_CONNECTIONS_PER_USER}
import org.apache.kyuubi.metrics.MetricsConf
import org.apache.kyuubi.server.{AdminPermissionStore, IdentityAccessStore}
import org.apache.kyuubi.server.http.util.HttpAuthUtils
import org.apache.kyuubi.server.http.util.HttpAuthUtils.AUTHORIZATION_HEADER
import org.apache.kyuubi.service.authentication.AnonymousAuthenticationProviderImpl

class AdminAccessResourceSuite extends KyuubiFunSuite with RestFrontendTestHelper {
  private val testDirectory = Utils.createTempDir("admin-access-resource")
  private val identityFile = testDirectory.resolve("kyuubi-identity-access.json").toFile
  private val permissionFile = testDirectory.resolve("kyuubi-admin-permissions.json").toFile
  private val configurationFile = testDirectory.resolve("kyuubi-defaults.conf").toFile
  private var ldapServer: InMemoryDirectoryServer = _
  private var iamServer: HttpServer = _
  private var iamExecutor: ExecutorService = _

  override protected lazy val conf: KyuubiConf = KyuubiConf()
    .set(AUTHENTICATION_METHOD, Seq("CUSTOM"))
    .set(AUTHENTICATION_CUSTOM_CLASS, classOf[AnonymousAuthenticationProviderImpl].getName)
    .set(SERVER_ADMINISTRATORS, Set(Utils.currentUser))
    .set(SERVER_LIMIT_CONNECTIONS_PER_USER, 10)
    .set(MetricsConf.METRICS_REPORTERS, Set.empty[String])
    .set(FRONTEND_REST_BIND_PORT, 0)

  override def beforeAll(): Unit = {
    Files.write(configurationFile.toPath, Array.emptyByteArray)
    IdentityAccessStore.setFileForTesting(Some(identityFile))
    AdminPermissionStore.setFileForTesting(Some(permissionFile))
    AdminPoliciesFileStore.setConfigurationFileForTesting(Some(configurationFile))
    startLdap()
    startIam()
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      IdentityAccessStore.setFileForTesting(None)
      AdminPermissionStore.setFileForTesting(None)
      AdminPoliciesFileStore.setConfigurationFileForTesting(None)
      if (iamServer != null) iamServer.stop(0)
      if (iamExecutor != null) iamExecutor.shutdownNow()
      if (ldapServer != null) ldapServer.close()
    }
  }

  test("manage LDAP and IAM identity providers through the REST API") {
    val unauthorized = webTarget.path("api/v1/admin/access").request().get()
    assert(unauthorized.getStatus === 401)

    val ldap =
      s"""{"id":"corp-ldap","name":"Corporate LDAP","providerType":"LDAP","enabled":true,"endpoint":"ldap://127.0.0.1:${ldapServer.getListenPort}","baseDn":"dc=example,dc=com","userDnPattern":"uid={0},ou=users,dc=example,dc=com"}"""
    assert(put("api/v1/admin/access/providers", ldap).getStatus === 200)

    val iam =
      s"""{"id":"corp-iam","name":"Corporate IAM","providerType":"IAM","enabled":true,"endpoint":"http://127.0.0.1:${iamServer.getAddress.getPort}"}"""
    assert(put("api/v1/admin/access/providers", iam).getStatus === 200)

    val ldapTest = post("api/v1/admin/access/providers/corp-ldap/test", null)
    assert(ldapTest.getStatus === 200)
    assert(ldapTest.readEntity(classOf[String]).contains("Connection successful"))

    val ldapUsers = get("api/v1/admin/access/providers/corp-ldap/subjects", "subjectType", "USER")
    assert(ldapUsers.getStatus === 200)
    assert(ldapUsers.readEntity(classOf[String]).contains("alice@example.com"))

    val iamGroups = get("api/v1/admin/access/providers/corp-iam/subjects", "subjectType", "GROUP")
    assert(iamGroups.getStatus === 200)
    assert(iamGroups.readEntity(classOf[String]).contains("analysts"))
  }

  test("bind an external user and project the binding into Kyuubi policies") {
    val binding =
      """{"providerId":"corp-ldap","subjectId":"uid=alice,ou=users,dc=example,dc=com","subjectName":"alice","subjectType":"USER","access":"DENIED","role":"viewer","quotaExempt":true,"userDefaults":{"spark.sql.shuffle.partitions":"8"}}"""
    val response = put("api/v1/admin/access/bindings", binding)
    assert(response.getStatus === 200)
    val body = response.readEntity(classOf[String])
    assert(body.contains("alice"))
    assert(body.contains("viewer"))

    val policy = get("api/v1/admin/policies").readEntity(classOf[String])
    assert(policy.contains("alice"))
    val configuration =
      new String(Files.readAllBytes(configurationFile.toPath), StandardCharsets.UTF_8)
    assert(configuration.contains("kyuubi.server.limit.connections.user.deny.list=alice"))
    assert(configuration.contains("kyuubi.server.limit.connections.user.unlimited.list=alice"))
    assert(configuration.contains("spark.sql.shuffle.partitions=8"))

    val access = get("api/v1/admin/access").readEntity(classOf[String])
    val id = "\"id\":\"([^\"]+)\",\"providerId\":\"corp-ldap\"".r
      .findFirstMatchIn(access).map(_.group(1)).get
    val deleted = delete(s"api/v1/admin/access/bindings/$id")
    assert(deleted.getStatus === 200)
    assert(!deleted.readEntity(classOf[String]).contains("\"subjectName\":\"alice\""))

    val afterDelete =
      new String(Files.readAllBytes(configurationFile.toPath), StandardCharsets.UTF_8)
    assert(!afterDelete.contains("___alice___"))
  }

  test("require a bound platform administrator before activating managed authentication") {
    val missingAdministrator = post("api/v1/admin/access/activate", null)
    assert(missingAdministrator.getStatus === 400)
    assert(missingAdministrator.readEntity(classOf[String]).contains("platform administrator"))

    val binding =
      """{"providerId":"corp-ldap","subjectId":"uid=alice,ou=users,dc=example,dc=com","subjectName":"alice","subjectType":"USER","access":"ENABLED","role":"platform-admin"}"""
    assert(put("api/v1/admin/access/bindings", binding).getStatus === 200)

    val valid = post("api/v1/admin/access/activate", null)
    assert(valid.getStatus === 200)
    val body = valid.readEntity(classOf[String])
    assert(body.contains("restartRequired"))
    assert(body.contains("ManagedIdentityAuthenticationProvider"))

    val configuration =
      new String(Files.readAllBytes(configurationFile.toPath), StandardCharsets.UTF_8)
    assert(configuration.contains("kyuubi.authentication=CUSTOM"))
    assert(configuration.contains("ManagedIdentityAuthenticationProvider"))
    assert(configuration.contains("kyuubi.server.administrators=alice"))

    val deactivated = post("api/v1/admin/access/deactivate", null)
    assert(deactivated.getStatus === 200)
    val deactivatedBody = deactivated.readEntity(classOf[String])
    assert(deactivatedBody.contains("restartRequired"))
    val deactivatedConfiguration =
      new String(Files.readAllBytes(configurationFile.toPath), StandardCharsets.UTF_8)
    assert(deactivatedConfiguration.contains("kyuubi.authentication=NONE"))
  }

  private def auth = HttpAuthUtils.basicAuthorizationHeader(Utils.currentUser)

  private def get(path: String, queryName: String = "", queryValue: String = "") = {
    val target = if (queryName.isEmpty) webTarget.path(path)
    else webTarget.path(path).queryParam(queryName, queryValue)
    target.request().header(AUTHORIZATION_HEADER, auth).get()
  }

  private def put(path: String, body: String) = webTarget.path(path)
    .request(MediaType.APPLICATION_JSON_TYPE)
    .header(AUTHORIZATION_HEADER, auth)
    .put(Entity.entity(body, MediaType.APPLICATION_JSON_TYPE))

  private def post(path: String, body: String) = webTarget.path(path)
    .request(MediaType.APPLICATION_JSON_TYPE)
    .header(AUTHORIZATION_HEADER, auth)
    .post(if (body == null) null else Entity.entity(body, MediaType.APPLICATION_JSON_TYPE))

  private def delete(path: String) = webTarget.path(path).request()
    .header(AUTHORIZATION_HEADER, auth).delete()

  private def startLdap(): Unit = {
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
  }

  private def startIam(): Unit = {
    iamServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    iamServer.createContext(
      "/users",
      jsonHandler(
        """{"users":[{"id":"iam-1","username":"bob","displayName":"Bob Li","email":"bob@example.com"}]}"""))
    iamServer.createContext(
      "/groups",
      jsonHandler(
        """{"groups":[{"id":"group-1","name":"analysts","displayName":"Analysts","members":["bob"]}]}"""))
    iamServer.createContext(
      "/authenticate",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          val body = scala.io.Source.fromInputStream(exchange.getRequestBody).mkString
          respond(exchange, if (body.contains("alice-secret")) 200 else 401, "{}")
        }
      })
    iamExecutor = Executors.newCachedThreadPool()
    iamServer.setExecutor(iamExecutor)
    iamServer.start()
  }

  private def jsonHandler(body: String): HttpHandler = new HttpHandler {
    override def handle(exchange: HttpExchange): Unit = respond(exchange, 200, body)
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
