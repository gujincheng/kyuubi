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

import javax.ws.rs.{DefaultValue, GET, NotFoundException, Path, PathParam, Produces, QueryParam}
import javax.ws.rs.core.MediaType

import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.server.{AdminPermissionService, AdminRole, SqlExecutionRecord, SqlExecutionRecordPage, SqlExecutionRecordService}
import org.apache.kyuubi.server.api.ApiRequestContext

@Tag(name = "SQLRecord")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class SqlRecordsResource extends ApiRequestContext {

  @GET
  def list(
      @QueryParam("page") @DefaultValue("1") page: Int,
      @QueryParam("pageSize") @DefaultValue("20") pageSize: Int,
      @QueryParam("user") user: String,
      @QueryParam("sessionId") sessionId: String,
      @QueryParam("engineType") engineType: String,
      @QueryParam("state") state: String,
      @QueryParam("keyword") keyword: String,
      @QueryParam("fromTime") fromTime: Long,
      @QueryParam("toTime") toTime: Long): SqlExecutionRecordPage = {
    ensureAdministrator()
    SqlExecutionRecordService.list(
      page,
      pageSize,
      nonEmpty(user),
      nonEmpty(sessionId),
      nonEmpty(engineType),
      nonEmpty(state),
      nonEmpty(keyword),
      positive(fromTime),
      positive(toTime))
  }

  @GET
  @Path("{id}")
  def detail(@PathParam("id") id: String): SqlExecutionRecord = {
    ensureAdministrator()
    SqlExecutionRecordService.get(id).getOrElse {
      throw new NotFoundException(s"SQL execution record $id does not exist")
    }
  }

  private def ensureAdministrator(): Unit = {
    AdminPermissionService.require(fe, "sql-record", AdminRole.Read)
  }

  private def nonEmpty(value: String): Option[String] =
    Option(value).map(_.trim).filter(_.nonEmpty)

  private def positive(value: Long): Option[Long] =
    if (value > 0L) Some(value) else None
}
