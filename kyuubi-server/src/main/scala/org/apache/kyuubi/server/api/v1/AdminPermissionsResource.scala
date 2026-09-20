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

import javax.ws.rs.{BadRequestException, GET, Path, Produces, PUT, WebApplicationException}
import javax.ws.rs.core.{MediaType, Response}

import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.Utils
import org.apache.kyuubi.config.KyuubiConf.SERVER_ADMINISTRATORS
import org.apache.kyuubi.server.{AdminPermissionService, AdminPermissionStore, AdminRole, AuditRecordStore, PermissionAssignment}
import org.apache.kyuubi.server.api.ApiRequestContext

@Tag(name = "Admin Permissions")
@Path("permissions")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class AdminPermissionsResource extends ApiRequestContext {

  @GET
  def permissions(): AdminPermissions = {
    val currentUser = AdminPermissionService.require(
      fe,
      "permissions",
      AdminRole.Read)
    AdminPermissions(
      currentUser = currentUser,
      roles = AdminRole.all.map { role =>
        AdminPermissionRole(
          name = role.name,
          label = role.label,
          description = role.description,
          permissions = role.permissions.toSeq.sortBy(_._1).map {
            case (resource, operations) =>
              AdminPermissionResource(resource, operations.toSeq.sorted)
          })
      },
      assignments = AdminPermissionStore.load().map(assignment =>
        AdminPermissionAssignment(assignment.user, assignment.role)))
  }

  @PUT
  def update(request: AdminPermissionsUpdate): AdminPermissions = {
    val actor = AdminPermissionService.require(
      fe,
      "permissions",
      AdminRole.Manage)
    if (request == null) {
      throw new BadRequestException("permission update body must not be empty")
    }
    val assignments = Option(request.assignments).getOrElse(Seq.empty).map { assignment =>
      PermissionAssignment(assignment.user.trim, assignment.role.trim)
    }
    ensurePlatformAdminSurvives(assignments)
    try {
      AdminPermissionStore.replace(assignments)
    } catch {
      case e: IllegalArgumentException => throw new BadRequestException(e.getMessage)
      case e: IllegalStateException =>
        throw new WebApplicationException(
          e.getMessage,
          Response.status(Response.Status.CONFLICT).entity(e.getMessage).build())
    }
    AuditRecordStore.appendAction(
      actor,
      fe.getIpAddress,
      "permission.assignments.replace",
      "/api/v1/admin/permissions")
    permissions()
  }

  private def ensurePlatformAdminSurvives(assignments: Seq[PermissionAssignment]): Unit = {
    val platformUsers = assignments.collect {
      case PermissionAssignment(user, AdminRole.PlatformAdminName) => user
    }.toSet
    val globalAdministrators = fe.getConf.get(SERVER_ADMINISTRATORS) + Utils.currentUser
    val unassignedGlobalAdministrator = globalAdministrators.exists { user =>
      !assignments.exists(_.user == user)
    }
    if (platformUsers.isEmpty && !unassignedGlobalAdministrator) {
      throw new BadRequestException(
        "At least one platform administrator or unassigned global administrator is required")
    }
  }
}

case class AdminPermissions(
    currentUser: String,
    roles: Seq[AdminPermissionRole],
    assignments: Seq[AdminPermissionAssignment])

case class AdminPermissionRole(
    name: String,
    label: String,
    description: String,
    permissions: Seq[AdminPermissionResource])

case class AdminPermissionResource(resource: String, operations: Seq[String])

case class AdminPermissionAssignment(user: String, role: String)

case class AdminPermissionsUpdate(assignments: Seq[AdminPermissionAssignment])
