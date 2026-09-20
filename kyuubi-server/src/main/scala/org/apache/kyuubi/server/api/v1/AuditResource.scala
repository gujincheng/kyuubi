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

import javax.ws.rs.{BadRequestException, GET, Path, Produces, QueryParam}
import javax.ws.rs.core.MediaType

import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.server.{AdminPermissionService, AdminRole, AuditRecordPage, AuditRecordStore}
import org.apache.kyuubi.server.api.ApiRequestContext

@Tag(name = "Admin Audit")
@Path("audit")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class AuditResource extends ApiRequestContext {

  @GET
  def audit(
      @QueryParam("user") user: String,
      @QueryParam("method") method: String,
      @QueryParam("action") action: String,
      @QueryParam("status") status: String,
      @QueryParam("from") from: String,
      @QueryParam("to") to: String,
      @QueryParam("limit") limit: String): AuditRecordPage = {
    AdminPermissionService.require(fe, "audit", AdminRole.Read)
    AuditRecordStore.query(
      user = nonEmpty(user),
      method = nonEmpty(method),
      action = nonEmpty(action),
      status = parseInt(status, "status"),
      from = parseLong(from, "from"),
      to = parseLong(to, "to"),
      limit = parseInt(limit, "limit").getOrElse(100))
  }

  private def nonEmpty(value: String): Option[String] = Option(value).filter(_.nonEmpty)

  private def parseInt(value: String, name: String): Option[Int] =
    nonEmpty(value).map { raw =>
      try raw.toInt
      catch {
        case _: NumberFormatException => throw new BadRequestException(s"Invalid $name")
      }
    }

  private def parseLong(value: String, name: String): Option[Long] =
    nonEmpty(value).map { raw =>
      try raw.toLong
      catch {
        case _: NumberFormatException => throw new BadRequestException(s"Invalid $name")
      }
    }
}
