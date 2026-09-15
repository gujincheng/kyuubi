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

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import org.apache.kyuubi.Utils
import org.apache.kyuubi.config.KyuubiConf.KYUUBI_CONF_FILE_NAME

private[server] case class PermissionAssignment(user: String, role: String)

private[server] case class PermissionFile(assignments: Seq[PermissionAssignment])

private[server] sealed abstract class AdminRole(
    val name: String,
    val label: String,
    val description: String,
    val permissions: Map[String, Set[String]])

private[server] object AdminRole {
  val ViewerName = "viewer"
  val OperatorName = "operator"
  val PolicyAdminName = "policy-admin"
  val PlatformAdminName = "platform-admin"

  val Read = "read"
  val Write = "write"
  val Control = "control"
  val Delete = "delete"
  val Refresh = "refresh"
  val Manage = "manage"

  private val Resources = Seq(
    "overview",
    "session",
    "operation",
    "engine",
    "server",
    "batch",
    "sql-record",
    "policy",
    "configuration",
    "audit",
    "datasource",
    "sql-rule",
    "permissions")

  private val readOnly = Resources.map(_ -> Set(Read)).toMap

  private val operatorPermissions = readOnly ++ Map(
    "session" -> Set(Read, Control),
    "operation" -> Set(Read, Control),
    "engine" -> Set(Read, Control, Delete),
    "server" -> Set(Read),
    "batch" -> Set(Read, Control))

  private val policyAdminPermissions = readOnly ++ Map(
    "policy" -> Set(Read, Write, Delete, Refresh),
    "configuration" -> Set(Read, Refresh))

  private val platformPermissions = Resources.map(_ ->
    Set(Read, Write, Control, Delete, Refresh, Manage)).toMap

  case object Viewer extends AdminRole(
    ViewerName,
    "只读管理员",
    "可以查看管理数据，但不能改变运行状态",
    readOnly)

  case object Operator extends AdminRole(
    OperatorName,
    "运维管理员",
    "可以查看并控制会话、操作和引擎",
    operatorPermissions)

  case object PolicyAdmin extends AdminRole(
    PolicyAdminName,
    "策略管理员",
    "可以维护用户配置、Session Profile 和访问策略",
    policyAdminPermissions)

  case object PlatformAdmin extends AdminRole(
    PlatformAdminName,
    "平台管理员",
    "拥有所有管理资源和操作权限",
    platformPermissions)

  val all: Seq[AdminRole] = Seq(Viewer, Operator, PolicyAdmin, PlatformAdmin)

  def fromName(name: String): Option[AdminRole] = all.find(_.name == name)
}

private[server] object AdminPermissionStore {
  private val FileName = "kyuubi-admin-permissions.json"
  private val PathEnvironment = "KYUUBI_ADMIN_PERMISSIONS_PATH"
  private val UserPattern = "[^\\s,]{1,256}".r
  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
  private var testFile: Option[File] = None

  def load(): Seq[PermissionAssignment] = synchronized {
    file.flatMap(read).map(_.assignments).getOrElse(Seq.empty)
  }

  def replace(assignments: Seq[PermissionAssignment]): Unit = synchronized {
    validate(assignments)
    val target = file.getOrElse {
      throw new IllegalStateException(
        "The Kyuubi admin permission file cannot be located; configure KYUUBI_CONF_DIR " +
          "or KYUUBI_HOME before updating permissions.")
    }
    Option(target.getParentFile).foreach(_.mkdirs())
    val temporary = new File(target.getParentFile, s".${target.getName}.${System.nanoTime()}.tmp")
    val content = mapper.writeValueAsString(PermissionFile(assignments.sortBy(_.user)))
    try {
      Files.write(
        temporary.toPath,
        content.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE)
      try {
        Files.move(
          temporary.toPath,
          target.toPath,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING)
      } catch {
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          Files.move(
            temporary.toPath,
            target.toPath,
            StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary.toPath)
    }
  }

  private[server] def setFileForTesting(value: Option[File]): Unit = synchronized {
    testFile = value
  }

  private def file: Option[File] = {
    testFile
      .orElse(sys.env.get(PathEnvironment).map(new File(_)))
      .orElse(Utils.getPropertiesFile(KYUUBI_CONF_FILE_NAME)
        .map(config => new File(config.getParentFile, FileName)))
  }

  private def read(target: File): Option[PermissionFile] = {
    if (!target.isFile) {
      None
    } else {
      try {
        Some(mapper.readValue(target, classOf[PermissionFile]))
      } catch {
        case e: Exception =>
          throw new IllegalStateException(
            s"Unable to read Kyuubi admin permission file ${target.getAbsolutePath}",
            e)
      }
    }
  }

  private def validate(assignments: Seq[PermissionAssignment]): Unit = {
    val users = assignments.map(_.user)
    if (users.distinct.size != users.size) {
      throw new IllegalArgumentException("Each user can have only one admin role")
    }
    assignments.foreach { assignment =>
      if (!UserPattern.pattern.matcher(assignment.user).matches()) {
        throw new IllegalArgumentException(s"Invalid admin permission user: ${assignment.user}")
      }
      if (AdminRole.fromName(assignment.role).isEmpty) {
        throw new IllegalArgumentException(s"Unknown admin role: ${assignment.role}")
      }
    }
  }
}

private[server] object AdminPermissionService {
  def roleFor(fe: KyuubiRestFrontendService, user: String): Option[AdminRole] = {
    if (!fe.securityEnabled) {
      Some(AdminRole.PlatformAdmin)
    } else {
      AdminPermissionStore.load().find(_.user == user)
        .flatMap(assignment => AdminRole.fromName(assignment.role))
        .orElse {
          if (fe.isAdministrator(user)) Some(AdminRole.PlatformAdmin) else None
        }
    }
  }

  def isAllowed(
      fe: KyuubiRestFrontendService,
      user: String,
      resource: String,
      operation: String): Boolean = {
    roleFor(fe, user).exists(_.permissions.get(resource).exists(_.contains(operation)))
  }

  def isPlatformAdmin(fe: KyuubiRestFrontendService, user: String): Boolean =
    roleFor(fe, user).exists(_.name == AdminRole.PlatformAdminName)

  def require(
      fe: KyuubiRestFrontendService,
      resource: String,
      operation: String): String = {
    val user = fe.getSessionUser(Map.empty[String, String])
    if (!isAllowed(fe, user, resource, operation)) {
      throw new javax.ws.rs.ForbiddenException(
        s"$user is not allowed to $operation $resource")
    }
    user
  }
}
