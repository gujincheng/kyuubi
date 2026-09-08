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

import javax.ws.rs.{DELETE, ForbiddenException, GET, Path, PathParam, POST, Produces, PUT}
import javax.ws.rs.core.MediaType

import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.KyuubiException
import org.apache.kyuubi.digiwin.security.{RuleRegistry, SqlRule}
import org.apache.kyuubi.server.api.ApiRequestContext

@Tag(name = "sql-rules")
@Path("sql-rules")
class RulesResource extends ApiRequestContext {

  private def requireAdministrator(): Unit = {
    val userName = fe.getSessionUser(Map.empty[String, String])
    if (!fe.isAdministrator(userName)) {
      throw new ForbiddenException(
        s"$userName is not allowed to modify SQL inspection rules")
    }
  }

  private def registry: RuleRegistry =
    RulesResource.registryOpt.getOrElse(
      throw new KyuubiException("SQL rule registry is not initialized"))

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def list(): Seq[SqlRule] = registry.list()

  @GET
  @Path("{id}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def get(@PathParam("id") id: String): SqlRule =
    registry.get(id).getOrElse(throw new KyuubiException(s"SQL rule $id not found"))

  @POST
  @Produces(Array(MediaType.APPLICATION_JSON))
  def create(req: SqlRule): SqlRule = {
    requireAdministrator()
    registry.upsert(req)
    req
  }

  @PUT
  @Path("{id}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def update(@PathParam("id") id: String, req: SqlRule): SqlRule = {
    requireAdministrator()
    if (!id.equals(req.id)) {
      throw new KyuubiException(s"Rule id in path($id) and body(${req.id}) mismatch")
    }
    registry.upsert(req)
    req
  }

  @DELETE
  @Path("{id}")
  def delete(@PathParam("id") id: String): Unit = {
    requireAdministrator()
    registry.delete(id)
  }

  @POST
  @Path("refresh")
  @Produces(Array(MediaType.TEXT_PLAIN))
  def refresh(): String = {
    requireAdministrator()
    registry.refresh()
    "ok"
  }
}

object RulesResource {
  @volatile private var registryOpt: Option[RuleRegistry] = None
  def init(registry: RuleRegistry): Unit = registryOpt = Some(registry)
}
