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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardOpenOption}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf.KYUUBI_HOME_ENV_VAR_NAME

/** A bounded audit buffer with optional JSONL persistence for the management UI. */
object AuditRecordStore extends Logging {
  private val MaxRecords = 2000
  private val RetentionMillis = 24 * 60 * 60 * 1000L
  private val AuditFileName = "kyuubi-audit.jsonl"
  private val SensitiveParameter =
    "(?i)(password|passwd|secret|token|access[.]key|private[.]key|credential)"
      .r
  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)

  private val records = ArrayBuffer.empty[AuditRecord]
  private var loadedFile: Option[File] = None
  private var testPersistenceFile: Option[File] = None

  def append(record: AuditRecord): Unit = {
    appendRecord(record)
  }

  private def appendRecord(record: AuditRecord): Unit = synchronized {
    loadPersisted(record.timestamp)
    cleanup(record.timestamp)
    val stored = record.copy(query = record.query.map(redactQuery))
    records += stored
    while (records.size > MaxRecords) records.remove(0)
    persist(stored, record.timestamp)
  }

  def appendAction(
      user: String,
      ip: String,
      action: String,
      uri: String,
      timestamp: Long = System.currentTimeMillis()): Unit = {
    appendRecord(
      AuditRecord(
        timestamp = timestamp,
        user = user,
        authType = "ADMIN",
        ip = ip,
        proxyIp = "",
        forwardedFor = Seq.empty,
        method = "ACTION",
        uri = uri,
        query = None,
        protocol = "INTERNAL",
        status = 200,
        action = Some(action)))
  }

  def query(
      user: Option[String] = None,
      method: Option[String] = None,
      action: Option[String] = None,
      status: Option[Int] = None,
      from: Option[Long] = None,
      to: Option[Long] = None,
      limit: Int = 100): AuditRecordPage = synchronized {
    val now = System.currentTimeMillis()
    loadPersisted(now)
    cleanup(now)
    val filtered = records.reverseIterator
      .filter { record =>
        user.forall(value => value.isEmpty || record.user == value) &&
        method.forall(value => value.isEmpty || record.method.equalsIgnoreCase(value)) &&
        action.forall(value =>
          value.isEmpty || record.action.exists(_.toLowerCase.contains(value.toLowerCase))) &&
        status.forall(_ == record.status) &&
        from.forall(_ <= record.timestamp) &&
        to.forall(_ >= record.timestamp)
      }
      .toSeq
    AuditRecordPage(
      records = filtered.take(limit.max(1).min(200)),
      total = filtered.size,
      generatedAt = now)
  }

  private[server] def clear(): Unit = synchronized {
    records.clear()
    loadedFile = None
  }

  private[server] def setPersistenceFileForTesting(file: Option[File]): Unit = synchronized {
    testPersistenceFile = file
    loadedFile = None
    records.clear()
  }

  private def persistenceFile: Option[File] = {
    testPersistenceFile
      .orElse(sys.env.get("KYUUBI_AUDIT_LOG_PATH").map(new File(_)))
      .orElse(sys.env.get(KYUUBI_HOME_ENV_VAR_NAME)
        .map(home => new File(new File(home, "logs"), AuditFileName)))
  }

  private def loadPersisted(now: Long): Unit = {
    persistenceFile.foreach { file =>
      if (loadedFile.forall(_ != file)) {
        loadedFile = Some(file)
        records.clear()
      }
      if (file.isFile) {
        val persisted = Files.readAllLines(file.toPath, StandardCharsets.UTF_8)
          .asScala.flatMap(decode)
        records.clear()
        records ++= persisted.filter(_.timestamp >= now - RetentionMillis).takeRight(MaxRecords)
      }
    }
  }

  private def persist(record: AuditRecord, now: Long): Unit = {
    persistenceFile.foreach { file =>
      try {
        Option(file.getParentFile).foreach(_.mkdirs())
        val channel = java.nio.channels.FileChannel.open(
          file.toPath,
          StandardOpenOption.CREATE,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE)
        try {
          val lock = channel.lock()
          try {
            val existing = if (file.isFile) {
              Files.readAllLines(file.toPath, StandardCharsets.UTF_8).asScala.flatMap(decode)
            } else {
              Seq.empty
            }
            val retained = (existing :+ record)
              .filter(_.timestamp >= now - RetentionMillis)
              .takeRight(MaxRecords)
            val content = retained.map(item => mapper.writeValueAsString(item)).mkString("\n")
            channel.truncate(0)
            channel.position(0)
            channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)))
            if (content.nonEmpty) {
              channel.write(ByteBuffer.wrap("\n".getBytes(StandardCharsets.UTF_8)))
            }
          } finally {
            lock.release()
          }
        } finally {
          channel.close()
        }
      } catch {
        case e: Exception => warn(s"Failed to persist audit record to ${file.getAbsolutePath}", e)
      }
    }
  }

  private def decode(line: String): Option[AuditRecord] = {
    try Some(mapper.readValue(line, classOf[AuditRecord]))
    catch {
      case _: Exception => None
    }
  }

  private def cleanup(now: Long): Unit = {
    val earliest = now - RetentionMillis
    records --= records.filter(_.timestamp < earliest)
  }

  private def redactQuery(query: String): String = {
    query.split("&", -1).map { item =>
      item.indexOf('=') match {
        case -1 => item
        case index =>
          val key = item.substring(0, index)
          if (SensitiveParameter.findFirstIn(key).nonEmpty) s"$key=******"
          else item
      }
    }.mkString("&")
  }
}

case class AuditRecord(
    timestamp: Long,
    user: String,
    authType: String,
    ip: String,
    proxyIp: String,
    forwardedFor: Seq[String],
    method: String,
    uri: String,
    query: Option[String],
    protocol: String,
    status: Int,
    action: Option[String] = None)

case class AuditRecordPage(records: Seq[AuditRecord], total: Int, generatedAt: Long)
