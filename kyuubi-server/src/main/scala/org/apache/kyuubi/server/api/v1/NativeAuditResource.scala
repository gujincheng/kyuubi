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

import javax.ws.rs.{BadRequestException, GET, Path, POST, Produces, PUT, QueryParam}
import javax.ws.rs.core.MediaType

import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.server.{AdminPermissionService, AdminRole, AuditConnectionTestResult, AuditRecordStore, ManagedAuditConfig, ManagedAuditConfigView, ManagedAuditEventService, NativeAuditActivityPage, NativeAuditEventPage}
import org.apache.kyuubi.server.api.ApiRequestContext

@Tag(name = "Native Audit Log")
@Path("event-audit")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class NativeAuditResource extends ApiRequestContext {

  @GET
  @Path("config")
  def config(): ManagedAuditConfigView = {
    AdminPermissionService.require(fe, "audit", AdminRole.Read)
    ManagedAuditEventService.configView
  }

  @PUT
  @Path("config")
  def updateConfig(request: ManagedAuditConfig): ManagedAuditConfigView = {
    val actor = AdminPermissionService.require(fe, "audit", AdminRole.Manage)
    try {
      val view = ManagedAuditEventService.update(request)
      AuditRecordStore.appendAction(
        actor,
        fe.getIpAddress,
        s"event-audit.${if (view.enabled) "enable" else "disable"}.${view.mode.toLowerCase}",
        "/api/v1/admin/event-audit/config")
      view
    } catch {
      case e: IllegalArgumentException => throw new BadRequestException(e.getMessage)
    }
  }

  @POST
  @Path("test")
  def testConfig(request: ManagedAuditConfig): AuditConnectionTestResult = {
    AdminPermissionService.require(fe, "audit", AdminRole.Manage)
    ManagedAuditEventService.test(request)
  }

  @GET
  @Path("events")
  def events(
      @QueryParam("eventType") eventType: String,
      @QueryParam("user") user: String,
      @QueryParam("status") status: String,
      @QueryParam("operationId") operationId: String,
      @QueryParam("sessionId") sessionId: String,
      @QueryParam("from") from: String,
      @QueryParam("to") to: String,
      @QueryParam("limit") limit: String): NativeAuditEventPage = {
    AdminPermissionService.require(fe, "audit", AdminRole.Read)
    ManagedAuditEventService.query(
      nonEmpty(eventType),
      nonEmpty(user),
      nonEmpty(status),
      parseLong(from, "from"),
      parseLong(to, "to"),
      parseInt(limit, "limit").getOrElse(100),
      nonEmpty(operationId),
      nonEmpty(sessionId))
  }

  @GET
  @Path("activities")
  def activities(
      @QueryParam("page") page: String,
      @QueryParam("pageSize") pageSize: String,
      @QueryParam("eventType") eventType: String,
      @QueryParam("user") user: String,
      @QueryParam("status") status: String,
      @QueryParam("keyword") keyword: String,
      @QueryParam("from") from: String,
      @QueryParam("to") to: String): NativeAuditActivityPage = {
    AdminPermissionService.require(fe, "audit", AdminRole.Read)
    ManagedAuditEventService.queryActivities(
      parseInt(page, "page").getOrElse(1),
      parseInt(pageSize, "pageSize").getOrElse(20),
      nonEmpty(eventType),
      nonEmpty(user),
      nonEmpty(status),
      nonEmpty(keyword),
      parseLong(from, "from"),
      parseLong(to, "to"))
  }

  private def nonEmpty(value: String): Option[String] = Option(value).map(_.trim).filter(_.nonEmpty)

  private def parseInt(value: String, name: String): Option[Int] = nonEmpty(value).map { raw =>
    try raw.toInt
    catch { case _: NumberFormatException => throw new BadRequestException(s"Invalid $name") }
  }

  private def parseLong(value: String, name: String): Option[Long] = nonEmpty(value).map { raw =>
    try raw.toLong
    catch { case _: NumberFormatException => throw new BadRequestException(s"Invalid $name") }
  }
}
