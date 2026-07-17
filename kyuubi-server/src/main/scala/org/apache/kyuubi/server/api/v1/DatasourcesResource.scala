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

import javax.ws.rs.{DELETE, GET, Path, PathParam, POST, Produces, PUT}
import javax.ws.rs.core.MediaType

import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.KyuubiException
import org.apache.kyuubi.digiwin.datasource.{DatasourceInfo, DatasourceRegistry}
import org.apache.kyuubi.server.api.ApiRequestContext

@Tag(name = "datasources")
@Path("datasources")
class DatasourcesResource extends ApiRequestContext {

  private def registry: DatasourceRegistry =
    DatasourcesResource.registryOpt.getOrElse(
      throw new KyuubiException("Datasource registry is not initialized"))

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def list(): Seq[DatasourceView] = registry.list().map(DatasourceView.from)

  @GET
  @Path("{label}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def get(@PathParam("label") label: String): DatasourceView =
    registry.get(label).map(DatasourceView.from)
      .getOrElse(throw new KyuubiException(s"Datasource $label not found"))

  @POST
  @Produces(Array(MediaType.APPLICATION_JSON))
  def create(req: DatasourceRequest): DatasourceView = {
    val ds = req.toInfo()
    registry.upsert(ds, req.plainPassword)
    DatasourceView.from(ds)
  }

  @PUT
  @Path("{label}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def update(@PathParam("label") label: String, req: DatasourceRequest): DatasourceView = {
    if (!label.equals(req.label)) {
      throw new KyuubiException(s"Label in path($label) and body(${req.label}) mismatch")
    }
    val ds = req.toInfo()
    registry.upsert(ds, req.plainPassword)
    DatasourceView.from(ds)
  }

  @DELETE
  @Path("{label}")
  def delete(@PathParam("label") label: String): Unit = registry.delete(label)

  @POST
  @Path("refresh")
  @Produces(Array(MediaType.TEXT_PLAIN))
  def refresh(): String = {
    registry.refresh()
    "ok"
  }
}

object DatasourcesResource {
  @volatile private var registryOpt: Option[DatasourceRegistry] = None
  def init(registry: DatasourceRegistry): Unit = registryOpt = Some(registry)
}

case class DatasourceView(
    label: String,
    engineType: String,
    jdbcType: String,
    driverClass: String,
    jdbcUrl: String,
    username: String,
    connectionPoolParams: Map[String, String],
    status: String,
    description: String)

object DatasourceView {
  def from(ds: DatasourceInfo): DatasourceView =
    DatasourceView(
      ds.label,
      ds.engineType,
      ds.jdbcType,
      ds.driverClass,
      ds.jdbcUrl,
      ds.username,
      ds.connectionPoolParams,
      ds.status,
      ds.description)
}

case class DatasourceRequest(
    label: String,
    engineType: String,
    jdbcType: String,
    driverClass: String,
    jdbcUrl: String,
    username: String,
    plainPassword: String,
    connectionPoolParams: Map[String, String] = Map.empty,
    status: String = "ENABLED",
    description: String = "") {
  def toInfo(): DatasourceInfo =
    DatasourceInfo(
      label,
      engineType,
      jdbcType,
      driverClass,
      jdbcUrl,
      username,
      encryptedPassword = "",
      connectionPoolParams,
      status,
      description)
}
