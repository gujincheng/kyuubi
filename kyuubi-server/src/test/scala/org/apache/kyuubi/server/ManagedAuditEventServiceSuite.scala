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

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.apache.kyuubi.{KyuubiFunSuite, Utils}
import org.apache.kyuubi.config.KyuubiConf

class ManagedAuditEventServiceSuite extends KyuubiFunSuite {
  private var root: Path = _
  private var configFile: Path = _
  private var events: Path = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    root = Files.createTempDirectory("managed-audit-")
    configFile = root.resolve("kyuubi-audit-config.json")
    events = root.resolve("events")
    ManagedAuditEventService.close()
    ManagedAuditEventService.setConfigFileForTesting(Some(configFile.toFile))
    val conf = KyuubiConf(false)
      .set(KyuubiConf.SERVER_EVENT_LOGGERS, Seq.empty)
      .set(KyuubiConf.DIGIWIN_DATASOURCE_CREDENTIAL_SECRET, "0123456789abcdef")
    ManagedAuditEventService.initialize(conf)
  }

  override def afterEach(): Unit = {
    ManagedAuditEventService.close()
    ManagedAuditEventService.setConfigFileForTesting(None)
    if (root != null && Files.exists(root)) {
      val paths = Files.walk(root)
      try
        paths.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
    super.afterEach()
  }

  test("persists JSON configuration and queries real event files") {
    val view = ManagedAuditEventService.update(ManagedAuditConfig(
      enabled = true,
      mode = "JSON",
      jsonPath = events.toUri.toString,
      retentionDays = 7))

    assert(view.enabled)
    assert(view.mode === "JSON")
    assert(configFile.toFile.isFile)

    val eventTime = System.currentTimeMillis()
    val oldEventTime = eventTime - 2 * 24L * 60 * 60 * 1000
    val eventDirectory = events.resolve("kyuubi_operation")
      .resolve(s"day=${Utils.getDateFromTimestamp(eventTime)}")
    val oldEventDirectory = events.resolve("kyuubi_operation")
      .resolve(s"day=${Utils.getDateFromTimestamp(oldEventTime)}")
    Files.createDirectories(eventDirectory)
    Files.createDirectories(oldEventDirectory)
    val json =
      s"""{"eventTime":$eventTime,"sessionUser":"alice","state":"FINISHED",""" +
        """"statement":"SELECT 1","statementId":"operation-1","sessionId":"session-1",""" +
        """"clientIp":"127.0.0.1","executionDuration":12,"password":"must-not-leak"}"""
    val oldJson =
      s"""{"eventTime":$oldEventTime,"sessionUser":"alice","state":"FINISHED",""" +
        """"statement":"SELECT 0","statementId":"operation-0","sessionId":"session-0"}"""
    Files.write(
      eventDirectory.resolve("server-test.json"),
      (json + "\n").getBytes(StandardCharsets.UTF_8))
    Files.write(
      oldEventDirectory.resolve("server-old.json"),
      (oldJson + "\n").getBytes(StandardCharsets.UTF_8))

    val page = ManagedAuditEventService.query(
      eventType = Some("kyuubi_operation"),
      user = Some("alice"),
      status = Some("FINISHED"),
      from = Some(eventTime - 60 * 60 * 1000),
      to = Some(eventTime),
      limit = 10)

    assert(page.total === 1)
    assert(page.records.head.statement === "SELECT 1")
    assert(page.records.head.operationId === "operation-1")
    assert(page.records.head.rawJson.contains("******"))
    assert(!page.records.head.rawJson.contains("must-not-leak"))

    val crossDayPage = ManagedAuditEventService.query(
      eventType = Some("kyuubi_operation"),
      user = Some("alice"),
      status = Some("FINISHED"),
      from = Some(oldEventTime - 60 * 1000),
      to = Some(eventTime),
      limit = 10)
    assert(crossDayPage.total === 2)
    assert(crossDayPage.records.map(_.statement) === Seq("SELECT 1", "SELECT 0"))

    val operationPage = ManagedAuditEventService.query(
      eventType = Some("kyuubi_operation"),
      user = None,
      status = None,
      from = Some(oldEventTime - 60 * 1000),
      to = Some(eventTime),
      limit = 10,
      operationId = Some("operation-1"),
      sessionId = Some("session-1"))
    assert(operationPage.total === 1)
    assert(operationPage.records.head.statement === "SELECT 1")
  }

  test("derives SQL execution history from JSON audit files after service reinitialization") {
    val conf = KyuubiConf(false)
      .set(KyuubiConf.SERVER_EVENT_LOGGERS, Seq.empty)
      .set(KyuubiConf.DIGIWIN_DATASOURCE_CREDENTIAL_SECRET, "0123456789abcdef")
    ManagedAuditEventService.update(ManagedAuditConfig(
      enabled = true,
      mode = "JSON",
      jsonPath = events.toUri.toString,
      retentionDays = 7))

    val now = System.currentTimeMillis()
    val eventDirectory = events.resolve("kyuubi_operation")
      .resolve(s"day=${Utils.getDateFromTimestamp(now)}")
    Files.createDirectories(eventDirectory)
    val event =
      s"""{"eventTime":${now - 500L},"createTime":${now - 2000L},""" +
        s""""startTime":${now - 1500L},"completeTime":${now - 500L},""" +
        s""""sessionUser":"alice","state":"FINISHED_STATE",""" +
        s""""statement":"SELECT persistent_history",""" +
        s""""statementId":"operation-persistent","sessionId":"session-persistent",""" +
        s""""engineType":"SPARK","executionDuration":1000,"eventType":"kyuubi_operation"}"""
    Files.write(
      eventDirectory.resolve("server-persistent.json"),
      (event + "\n").getBytes(StandardCharsets.UTF_8))

    val beforeRestart = ManagedAuditEventService.querySqlExecutionRecords(
      1,
      10,
      None,
      None,
      None,
      None,
      None,
      None,
      None)
    assert(beforeRestart.records.map(_.id) === Seq("operation-persistent"))

    ManagedAuditEventService.close()
    ManagedAuditEventService.initialize(conf)

    val afterRestart = ManagedAuditEventService.querySqlExecutionRecords(
      1,
      10,
      None,
      None,
      None,
      None,
      None,
      None,
      None)
    assert(afterRestart.auditEnabled)
    assert(afterRestart.source === "JSON")
    assert(afterRestart.records.map(_.id) === Seq("operation-persistent"))
  }

  test("keeps session and server events independent from operation activities") {
    val view = ManagedAuditEventService.update(ManagedAuditConfig(
      enabled = true,
      mode = "JSON",
      jsonPath = events.toUri.toString,
      retentionDays = 7))
    assert(view.enabled)

    val eventTime = System.currentTimeMillis()
    val day = Utils.getDateFromTimestamp(eventTime)
    val operationDirectory = events.resolve("kyuubi_operation").resolve(s"day=$day")
    val sessionDirectory = events.resolve("kyuubi_session").resolve(s"day=$day")
    val serverDirectory = events.resolve("kyuubi_server_info").resolve(s"day=$day")
    Files.createDirectories(operationDirectory)
    Files.createDirectories(sessionDirectory)
    Files.createDirectories(serverDirectory)

    val operation =
      s"""{"eventTime":$eventTime,"createTime":${eventTime - 1000L},""" +
        s""""startTime":${eventTime - 800L},"completeTime":${eventTime - 200L},""" +
        s""""sessionUser":"alice","state":"CLOSED_STATE",""" +
        s""""statement":"SELECT 1","statementId":"operation-isolated",""" +
        s""""sessionId":"session-isolated","executionDuration":600}"""
    val session =
      s"""{"eventTime":$eventTime,"startTime":${eventTime - 5000L},""" +
        s""""openedTime":${eventTime - 4800L},"endTime":${eventTime - 300L},""" +
        """"user":"alice","sessionId":"session-isolated","engineName":"JDBC"}"""
    val server =
      s"""{"eventTime":$eventTime,"startTime":${eventTime - 10000L},""" +
        """"state":"STARTED","serverName":"KyuubiServer"}"""
    Files.write(
      operationDirectory.resolve("operation.json"),
      (operation + "\n").getBytes(StandardCharsets.UTF_8))
    Files.write(
      sessionDirectory.resolve("session.json"),
      (session + "\n").getBytes(StandardCharsets.UTF_8))
    Files.write(
      serverDirectory.resolve("server.json"),
      (server + "\n").getBytes(StandardCharsets.UTF_8))

    val page = ManagedAuditEventService.queryActivities(
      1,
      10,
      None,
      None,
      None,
      None,
      Some(eventTime - 60 * 1000L),
      Some(eventTime + 60 * 1000L))
    assert(page.total === 3)
    val operationActivity = page.records.find(_.operationId == "operation-isolated").get
    val sessionActivity = page.records.find(_.eventType == "kyuubi_session").get
    val serverActivity = page.records.find(_.eventType == "kyuubi_server_info").get
    assert(operationActivity.eventCount === 1)
    assert(sessionActivity.operationId.isEmpty)
    assert(sessionActivity.state === "CLOSED_STATE")
    assert(sessionActivity.duration === 4500L)
    assert(serverActivity.duration === 0L)
  }

  test("aggregates session lifecycle records with milestone event times") {
    ManagedAuditEventService.update(ManagedAuditConfig(
      enabled = true,
      mode = "JSON",
      jsonPath = events.toUri.toString,
      retentionDays = 7))

    val createTime = System.currentTimeMillis() - 60 * 1000L
    val openedTime = createTime + 10 * 1000L
    val endTime = createTime + 45 * 1000L
    val day = Utils.getDateFromTimestamp(createTime)
    val sessionDirectory = events.resolve("kyuubi_session").resolve(s"day=$day")
    Files.createDirectories(sessionDirectory)
    // one JSON line per lifecycle post; eventTime stays frozen at creation in the raw log
    val createRecord =
      s"""{"eventTime":$createTime,"startTime":$createTime,"openedTime":-1,"endTime":-1,""" +
        """"user":"alice","sessionId":"session-lifecycle","engineName":"SPARK"}"""
    val openedRecord =
      s"""{"eventTime":$createTime,"startTime":$createTime,"openedTime":$openedTime,""" +
        """"endTime":-1,"user":"alice","sessionId":"session-lifecycle","engineName":"SPARK"}"""
    val closedRecord =
      s"""{"eventTime":$createTime,"startTime":$createTime,"openedTime":$openedTime,""" +
        s""""endTime":$endTime,"user":"alice","sessionId":"session-lifecycle",""" +
        """"engineName":"SPARK"}"""
    Files.write(
      sessionDirectory.resolve("session.json"),
      (Seq(createRecord, openedRecord, closedRecord).mkString("\n") + "\n")
        .getBytes(StandardCharsets.UTF_8))

    val eventsPage = ManagedAuditEventService.query(
      eventType = Some("kyuubi_session"),
      user = None,
      status = None,
      from = Some(createTime - 1000L),
      to = Some(endTime + 1000L),
      limit = 10)
    assert(eventsPage.total === 3)
    val recordsByEventTime = eventsPage.records.sortBy(_.eventTime)
    assert(recordsByEventTime.map(_.eventTime) === Seq(createTime, openedTime, endTime))
    assert(recordsByEventTime.map(_.status) ===
      Seq("RUNNING_STATE", "RUNNING_STATE", "CLOSED_STATE"))

    val activityPage = ManagedAuditEventService.queryActivities(
      1,
      10,
      Some("kyuubi_session"),
      None,
      None,
      None,
      Some(createTime - 1000L),
      Some(endTime + 1000L))
    assert(activityPage.total === 1)
    val activity = activityPage.records.head
    assert(activity.state === "CLOSED_STATE")
    assert(activity.createTime === createTime)
    assert(activity.startTime === openedTime)
    assert(activity.completeTime === endTime)
    assert(activity.duration === endTime - openedTime)
    assert(activity.eventCount === 3)
  }

  test("derives error state and guards duration for sessions that failed to open") {
    ManagedAuditEventService.update(ManagedAuditConfig(
      enabled = true,
      mode = "JSON",
      jsonPath = events.toUri.toString,
      retentionDays = 7))

    val createTime = System.currentTimeMillis() - 60 * 1000L
    val endTime = createTime + 5 * 1000L
    val day = Utils.getDateFromTimestamp(createTime)
    val sessionDirectory = events.resolve("kyuubi_session").resolve(s"day=$day")
    Files.createDirectories(sessionDirectory)
    // the engine session never opened, so openedTime stays -1 in every posted record
    val failedOpenRecord =
      s"""{"eventTime":$createTime,"startTime":$createTime,"openedTime":-1,"endTime":-1,""" +
        """"user":"bob","sessionId":"session-failed","exception":"engine launch failed"}"""
    val closedRecord =
      s"""{"eventTime":$createTime,"startTime":$createTime,"openedTime":-1,"endTime":$endTime,""" +
        """"user":"bob","sessionId":"session-failed","exception":"engine launch failed"}"""
    Files.write(
      sessionDirectory.resolve("session.json"),
      (Seq(failedOpenRecord, closedRecord).mkString("\n") + "\n")
        .getBytes(StandardCharsets.UTF_8))

    val eventsPage = ManagedAuditEventService.query(
      eventType = Some("kyuubi_session"),
      user = None,
      status = Some("ERROR_STATE"),
      from = Some(createTime - 1000L),
      to = Some(endTime + 1000L),
      limit = 10)
    assert(eventsPage.total === 1)
    assert(eventsPage.records.head.eventTime === createTime)

    val activityPage = ManagedAuditEventService.queryActivities(
      1,
      10,
      Some("kyuubi_session"),
      None,
      None,
      None,
      Some(createTime - 1000L),
      Some(endTime + 1000L))
    assert(activityPage.total === 1)
    val activity = activityPage.records.head
    assert(activity.state === "ERROR_STATE")
    assert(activity.createTime === createTime)
    assert(activity.completeTime === endTime)
    assert(activity.duration === 0L)
    assert(activity.error.contains("engine launch failed"))
  }

  test("aggregates server start and stop records into one lifecycle") {
    ManagedAuditEventService.update(ManagedAuditConfig(
      enabled = true,
      mode = "JSON",
      jsonPath = events.toUri.toString,
      retentionDays = 7))

    val serverStart = System.currentTimeMillis() - 60 * 60 * 1000L
    val stopTime = serverStart + 30 * 60 * 1000L
    val day = Utils.getDateFromTimestamp(serverStart)
    val serverDirectory = events.resolve("kyuubi_server_info").resolve(s"day=$day")
    Files.createDirectories(serverDirectory)
    val startedRecord =
      s"""{"serverName":"KyuubiServer","startTime":$serverStart,"eventTime":$serverStart,""" +
        """"state":"STARTED","serverIP":"10.0.0.1:10009"}"""
    val stoppedRecord =
      s"""{"serverName":"KyuubiServer","startTime":$serverStart,"eventTime":$stopTime,""" +
        """"state":"STOPPED","serverIP":"10.0.0.1:10009"}"""
    Files.write(
      serverDirectory.resolve("server.json"),
      (Seq(startedRecord, stoppedRecord).mkString("\n") + "\n")
        .getBytes(StandardCharsets.UTF_8))

    val activityPage = ManagedAuditEventService.queryActivities(
      1,
      10,
      Some("kyuubi_server_info"),
      None,
      None,
      None,
      Some(serverStart - 1000L),
      Some(stopTime + 1000L))
    assert(activityPage.total === 1)
    val activity = activityPage.records.head
    assert(activity.state === "STOPPED")
    assert(activity.createTime === serverStart)
    assert(activity.completeTime === stopTime)
    assert(activity.duration === stopTime - serverStart)
    assert(activity.eventCount === 2)
  }

  test("finds cross-day session close records within the query range") {
    ManagedAuditEventService.update(ManagedAuditConfig(
      enabled = true,
      mode = "JSON",
      jsonPath = events.toUri.toString,
      retentionDays = 7))

    // the session was created two days ago and closed a minute ago, but every lifecycle
    // record is stored in the creation day partition
    val createTime = System.currentTimeMillis() - 2 * 24L * 60 * 60 * 1000L - 60 * 1000L
    val openedTime = createTime + 1000L
    val endTime = System.currentTimeMillis() - 60 * 1000L
    val day = Utils.getDateFromTimestamp(createTime)
    val sessionDirectory = events.resolve("kyuubi_session").resolve(s"day=$day")
    Files.createDirectories(sessionDirectory)
    val createRecord =
      s"""{"eventTime":$createTime,"startTime":$createTime,"openedTime":-1,"endTime":-1,""" +
        """"user":"carol","sessionId":"session-cross-day","engineName":"SPARK"}"""
    val openedRecord =
      s"""{"eventTime":$createTime,"startTime":$createTime,"openedTime":$openedTime,""" +
        """"endTime":-1,"user":"carol","sessionId":"session-cross-day","engineName":"SPARK"}"""
    val closedRecord =
      s"""{"eventTime":$createTime,"startTime":$createTime,"openedTime":$openedTime,""" +
        s""""endTime":$endTime,"user":"carol","sessionId":"session-cross-day",""" +
        """"engineName":"SPARK"}"""
    Files.write(
      sessionDirectory.resolve("session.json"),
      (Seq(createRecord, openedRecord, closedRecord).mkString("\n") + "\n")
        .getBytes(StandardCharsets.UTF_8))

    val now = System.currentTimeMillis()
    val eventsPage = ManagedAuditEventService.query(
      eventType = Some("kyuubi_session"),
      user = None,
      status = None,
      from = Some(now - 60 * 60 * 1000L),
      to = Some(now),
      limit = 10)
    assert(eventsPage.total === 1)
    assert(eventsPage.records.head.eventTime === endTime)
    assert(eventsPage.records.head.status === "CLOSED_STATE")
  }

  test("marks stale sessions as closed with an inference note") {
    ManagedAuditEventService.update(ManagedAuditConfig(
      enabled = true,
      mode = "JSON",
      jsonPath = events.toUri.toString,
      retentionDays = 7))

    val staleCreateTime = System.currentTimeMillis() - 7 * 60 * 60 * 1000L
    val staleOpenedTime = staleCreateTime + 1000L
    val closedCreateTime = System.currentTimeMillis() - 7 * 60 * 60 * 1000L - 5000L
    val closedEndTime = closedCreateTime + 2000L
    val freshCreateTime = System.currentTimeMillis() - 60 * 1000L

    val staleDay = Utils.getDateFromTimestamp(staleCreateTime)
    val staleDirectory = events.resolve("kyuubi_session").resolve(s"day=$staleDay")
    Files.createDirectories(staleDirectory)
    // the stale session has no close record at all: its close log was lost or evicted
    val staleRecords = Seq(
      s"""{"eventTime":$staleCreateTime,"startTime":$staleCreateTime,"openedTime":-1,""" +
        """"endTime":-1,"user":"dave","sessionId":"session-stale","engineName":"SPARK"}""",
      s"""{"eventTime":$staleCreateTime,"startTime":$staleCreateTime,""" +
        s""""openedTime":$staleOpenedTime,""" +
        """"endTime":-1,"user":"dave","sessionId":"session-stale","engineName":"SPARK"}""")
    // the old session closed properly long ago and must not be marked as inferred
    val closedRecords = Seq(
      s"""{"eventTime":$closedCreateTime,"startTime":$closedCreateTime,"openedTime":-1,""" +
        """"endTime":-1,"user":"frank","sessionId":"session-closed-old","engineName":"SPARK"}""",
      s"""{"eventTime":$closedCreateTime,"startTime":$closedCreateTime,""" +
        s""""openedTime":$closedCreateTime,""" +
        s""""endTime":$closedEndTime,"user":"frank","sessionId":"session-closed-old",""" +
        """"engineName":"SPARK"}""")
    Files.write(
      staleDirectory.resolve("stale.json"),
      (staleRecords ++ closedRecords).mkString("\n").getBytes(StandardCharsets.UTF_8))

    val freshDay = Utils.getDateFromTimestamp(freshCreateTime)
    val freshDirectory = events.resolve("kyuubi_session").resolve(s"day=$freshDay")
    Files.createDirectories(freshDirectory)
    val freshRecord =
      s"""{"eventTime":$freshCreateTime,"startTime":$freshCreateTime,"openedTime":-1,""" +
        """"endTime":-1,"user":"erin","sessionId":"session-fresh","engineName":"SPARK"}"""
    Files.write(
      freshDirectory.resolve("fresh.json"),
      (freshRecord + "\n").getBytes(StandardCharsets.UTF_8))

    val eventsPage = ManagedAuditEventService.query(
      eventType = Some("kyuubi_session"),
      user = None,
      status = None,
      from = Some(closedCreateTime - 1000L),
      to = Some(System.currentTimeMillis() + 1000L),
      limit = 10)
    assert(eventsPage.total === 5)

    val staleEvents = eventsPage.records.filter(_.sessionId == "session-stale")
    assert(staleEvents.map(_.status).toSet === Set("CLOSED_STATE"))
    assert(staleEvents.forall(_.note.contains("inferred-closed")))

    val closedEvents = eventsPage.records.filter(_.sessionId == "session-closed-old")
    assert(closedEvents.map(_.status).toSet === Set("RUNNING_STATE", "CLOSED_STATE"))
    assert(closedEvents.forall(_.note.isEmpty))

    val freshEvents = eventsPage.records.filter(_.sessionId == "session-fresh")
    assert(freshEvents.map(_.status).toSet === Set("RUNNING_STATE"))
    assert(freshEvents.forall(_.note.isEmpty))
  }

  test("rejects unsupported paths without replacing the active configuration") {
    val before = ManagedAuditEventService.update(ManagedAuditConfig(
      enabled = true,
      mode = "JSON",
      jsonPath = events.toUri.toString))

    intercept[IllegalArgumentException] {
      ManagedAuditEventService.update(ManagedAuditConfig(
        enabled = true,
        mode = "JSON",
        jsonPath = "hdfs://namenode/audit"))
    }

    val after = ManagedAuditEventService.configView
    assert(after.jsonPath === before.jsonPath)
    assert(after.enabled)
  }

  test("does not expose configured Kafka passwords") {
    val response = ManagedAuditEventService.test(ManagedAuditConfig(
      enabled = true,
      mode = "KAFKA",
      kafka = AuditKafkaConfig()))
    assert(!response.success)
    assert(!ManagedAuditEventService.configView.passwordConfigured)
  }

  test("queries the recent Kafka tail without seeking before retained data") {
    assert(ManagedAuditEventService.latestKafkaStartOffset(0L, 8000L, 5000L) === 3000L)
    assert(ManagedAuditEventService.latestKafkaStartOffset(5000L, 8000L, 5000L) === 5000L)
    assert(ManagedAuditEventService.latestKafkaStartOffset(0L, 8L, 0L) === 7L)
  }
}
