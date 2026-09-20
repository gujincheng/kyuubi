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
import java.net.{InetAddress, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import java.time.{Duration, Instant, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.{Collections, Properties, UUID}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig}
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}

import org.apache.kyuubi.{Logging, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.digiwin.security.CredentialAccessor
import org.apache.kyuubi.events.KyuubiEvent
import org.apache.kyuubi.events.handler.{EventHandler, ServerJsonLoggingEventHandler, ServerKafkaLoggingEventHandler}
import org.apache.kyuubi.service.ServiceState

case class AuditKafkaConfig(
    bootstrapServers: String = "",
    topic: String = "",
    securityProtocol: String = "PLAINTEXT",
    saslMechanism: String = "PLAIN",
    username: String = "",
    password: String = "",
    truststoreLocation: String = "",
    truststorePassword: String = "")

case class ManagedAuditConfig(
    enabled: Boolean = false,
    mode: String = "JSON",
    jsonPath: String = "file:///tmp/kyuubi/events",
    retentionDays: Int = 7,
    kafka: AuditKafkaConfig = AuditKafkaConfig())

private[server] case class StoredAuditConfig(
    enabled: Boolean,
    mode: String,
    jsonPath: String,
    retentionDays: Int,
    kafka: AuditKafkaConfig,
    encryptedKafkaPassword: String,
    encryptedTruststorePassword: String)

case class ManagedAuditConfigView(
    enabled: Boolean,
    mode: String,
    jsonPath: String,
    retentionDays: Int,
    kafka: AuditKafkaConfig,
    passwordConfigured: Boolean,
    truststorePasswordConfigured: Boolean,
    updatedAt: Long,
    healthy: Boolean,
    message: String)

case class AuditConnectionTestResult(success: Boolean, message: String, checkedAt: Long)

case class NativeAuditEvent(
    id: String,
    source: String,
    eventType: String,
    eventTime: Long,
    createTime: Long,
    startTime: Long,
    completeTime: Long,
    user: String,
    status: String,
    statement: String,
    sessionId: String,
    operationId: String,
    clientIp: String,
    datasourceLabel: String,
    engineType: String,
    duration: Long,
    error: String,
    rawJson: String,
    note: String = "")

case class NativeAuditEventPage(
    records: Seq[NativeAuditEvent],
    total: Int,
    generatedAt: Long,
    source: String,
    message: String)

/**
 * A user-facing audit activity. Several native events that describe the same operation or
 * session are deliberately represented by one activity, while the event timeline remains
 * available from the activity detail view.
 */
case class NativeAuditActivity(
    id: String,
    eventType: String,
    eventTypes: Seq[String],
    statement: String,
    user: String,
    sessionId: String,
    operationId: String,
    engineType: String,
    state: String,
    createTime: Long,
    startTime: Long,
    completeTime: Long,
    duration: Long,
    error: String,
    eventCount: Int,
    note: String = "")

case class NativeAuditActivityPage(
    records: Seq[NativeAuditActivity],
    page: Int,
    pageSize: Int,
    total: Long,
    auditEnabled: Boolean,
    source: String,
    message: String)

/** Dynamically selects a native Kyuubi event logger and queries its actual output. */
object ManagedAuditEventService extends EventHandler[KyuubiEvent] with Logging {
  private val ConfigFileName = "kyuubi-audit-config.json"
  private val ConfigPathEnvironment = "KYUUBI_AUDIT_CONFIG_PATH"
  private val KafkaProbeKeyPrefix = "audit_configuration_probe"
  private val KafkaQueryRecordLimit = 5000L
  private val KafkaMinimumRecordsPerPartition = 50L
  private val MillisPerDay = 24L * 60 * 60 * 1000
  private val SessionStaleThresholdHours = 6L
  private val SessionStaleThreshold = SessionStaleThresholdHours * 60 * 60 * 1000
  private val EventDateFormatter = DateTimeFormatter.BASIC_ISO_DATE
  private val EventTypeDirectoryPattern = "[A-Za-z0-9_-]+".r
  private val OperationEventType = "kyuubi_operation"
  private val SessionEventType = "kyuubi_session"
  private val ServerEventType = "kyuubi_server_info"
  private val SqlRecordTerminalStates = Set(
    "FINISHED_STATE",
    "TIMEDOUT_STATE",
    "CANCELED_STATE",
    "CLOSED_STATE",
    "ERROR_STATE")
  private val SqlRecordFailureStates = Set("ERROR_STATE", "TIMEDOUT_STATE", "CANCELED_STATE")
  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
  private val SensitiveKey =
    "(?i)(password|passwd|secret|token|credential|access.?key|private.?key)".r

  @volatile private var baseConf: KyuubiConf = _
  @volatile private var activeHandler: Option[EventHandler[KyuubiEvent]] = None
  @volatile private var current: ManagedAuditConfig = ManagedAuditConfig()
  @volatile private var updatedAt: Long = 0L
  @volatile private var healthy: Boolean = true
  @volatile private var statusMessage: String = "Audit logging is disabled"
  private var testConfigFile: Option[File] = None

  def initialize(conf: KyuubiConf): Unit = synchronized {
    baseConf = conf
    val initial = loadStored().getOrElse(fromKyuubiConf(conf))
    activate(initial, persist = false)
  }

  override def apply(event: KyuubiEvent): Unit = synchronized {
    activeHandler.foreach { handler =>
      try handler(event)
      catch {
        case e: Exception =>
          healthy = false
          statusMessage = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
          error("Failed to write a managed audit event", e)
      }
    }
  }

  override def close(): Unit = synchronized {
    activeHandler.foreach(_.close())
    activeHandler = None
  }

  def configView: ManagedAuditConfigView = {
    val kafkaView = current.kafka.copy(password = "", truststorePassword = "")
    ManagedAuditConfigView(
      current.enabled,
      current.mode,
      current.jsonPath,
      current.retentionDays,
      kafkaView,
      current.kafka.password.nonEmpty,
      current.kafka.truststorePassword.nonEmpty,
      updatedAt,
      healthy,
      statusMessage)
  }

  def update(request: ManagedAuditConfig): ManagedAuditConfigView = synchronized {
    ensureInitialized()
    val merged = mergeSecrets(request)
    activate(normalize(merged), persist = true)
    configView
  }

  def test(request: ManagedAuditConfig): AuditConnectionTestResult = {
    val checkedAt = System.currentTimeMillis()
    try {
      val config = synchronized {
        ensureInitialized()
        normalize(mergeSecrets(request))
      }
      config.mode match {
        case "JSON" => testJson(config.jsonPath)
        case "KAFKA" => testKafka(config.kafka)
      }
      AuditConnectionTestResult(
        success = true,
        s"${config.mode} configuration is available",
        checkedAt)
    } catch {
      case e: Exception =>
        AuditConnectionTestResult(
          success = false,
          Option(e.getMessage).getOrElse(e.getClass.getSimpleName),
          checkedAt)
    }
  }

  def query(
      eventType: Option[String],
      user: Option[String],
      status: Option[String],
      from: Option[Long],
      to: Option[Long],
      limit: Int,
      operationId: Option[String] = None,
      sessionId: Option[String] = None): NativeAuditEventPage = {
    ensureInitialized()
    if (!current.enabled) {
      return NativeAuditEventPage(
        Seq.empty,
        0,
        System.currentTimeMillis(),
        current.mode,
        "Audit logging is disabled")
    }
    val boundedLimit = limit.max(1).min(500)
    val retentionStart = System.currentTimeMillis() - current.retentionDays * 24L * 60 * 60 * 1000
    val effectiveFrom = from.orElse(Some(retentionStart))
    val effectiveTo = to.orElse(Some(System.currentTimeMillis()))
    if (effectiveTo.exists(value => effectiveFrom.exists(_ > value))) {
      return NativeAuditEventPage(
        Seq.empty,
        0,
        System.currentTimeMillis(),
        current.mode,
        statusMessage)
    }
    val raw = readCurrentEvents(eventType, effectiveFrom, effectiveTo)
    val filtered = raw.iterator.filter { event =>
      eventType.forall(_.equalsIgnoreCase(event.eventType)) &&
      user.forall(value => event.user.equalsIgnoreCase(value)) &&
      status.forall(value => event.status.equalsIgnoreCase(value)) &&
      operationId.forall(value => event.operationId.equalsIgnoreCase(value)) &&
      sessionId.forall(value => event.sessionId.equalsIgnoreCase(value)) &&
      effectiveFrom.forall(_ <= event.eventTime) &&
      effectiveTo.forall(_ >= event.eventTime)
    }.toList.sortBy(event => -event.eventTime)
    NativeAuditEventPage(
      filtered.take(boundedLimit),
      filtered.size,
      System.currentTimeMillis(),
      current.mode,
      statusMessage)
  }

  /**
   * Produces one SQL record per native operation from the configured audit backend.
   *
   * This intentionally scans the audit retention window rather than maintaining a second
   * process-local history. A restart therefore does not change the visible SQL history.
   */
  def querySqlExecutionRecords(
      page: Int,
      pageSize: Int,
      user: Option[String],
      sessionId: Option[String],
      engineType: Option[String],
      state: Option[String],
      keyword: Option[String],
      fromTime: Option[Long],
      toTime: Option[Long]): SqlExecutionRecordPage = {
    ensureInitialized()
    val generatedAt = System.currentTimeMillis()
    val normalizedPage = math.max(1, page)
    val normalizedPageSize = math.min(200, math.max(1, pageSize))
    if (!current.enabled) {
      return SqlExecutionRecordPage(
        Seq.empty,
        normalizedPage,
        normalizedPageSize,
        0L,
        auditEnabled = false,
        source = current.mode,
        message = statusMessage)
    }
    if (toTime.exists(value => fromTime.exists(_ > value))) {
      return SqlExecutionRecordPage(
        Seq.empty,
        normalizedPage,
        normalizedPageSize,
        0L,
        auditEnabled = true,
        source = current.mode,
        message = statusMessage)
    }

    val retentionStart = generatedAt - current.retentionDays * 24L * 60L * 60L * 1000L
    val records = aggregateSqlExecutionRecords(
      readCurrentEvents(Some("kyuubi_operation"), Some(retentionStart), Some(generatedAt)))
    toSqlExecutionRecordPage(
      records,
      normalizedPage,
      normalizedPageSize,
      user,
      sessionId,
      engineType,
      state,
      keyword,
      fromTime,
      toTime,
      auditEnabled = true,
      source = current.mode,
      message = statusMessage)
  }

  /**
   * Returns one row for an operation, a session, or an independent server event. This is the
   * unified read model used by the Query & Audit page; it does not persist or duplicate events.
   */
  def queryActivities(
      page: Int,
      pageSize: Int,
      eventType: Option[String],
      user: Option[String],
      status: Option[String],
      keyword: Option[String],
      from: Option[Long],
      to: Option[Long]): NativeAuditActivityPage = {
    ensureInitialized()
    val normalizedPage = math.max(1, page)
    val normalizedPageSize = math.min(200, math.max(1, pageSize))
    if (!current.enabled) {
      return NativeAuditActivityPage(
        Seq.empty,
        normalizedPage,
        normalizedPageSize,
        0L,
        auditEnabled = false,
        current.mode,
        statusMessage)
    }
    val now = System.currentTimeMillis()
    val retentionStart = now - current.retentionDays * 24L * 60L * 60L * 1000L
    val effectiveFrom = from.orElse(Some(retentionStart))
    val effectiveTo = to.orElse(Some(now))
    if (effectiveTo.exists(value => effectiveFrom.exists(_ > value))) {
      return NativeAuditActivityPage(
        Seq.empty,
        normalizedPage,
        normalizedPageSize,
        0L,
        auditEnabled = true,
        current.mode,
        statusMessage)
    }
    val activities = aggregateActivities(readCurrentEvents(None, effectiveFrom, effectiveTo))
      .filter { activity =>
        eventType.forall(value => activity.eventTypes.exists(_.equalsIgnoreCase(value))) &&
        matches(activity.user, user) &&
        matches(activity.state, status) &&
        contains(s"${activity.statement} ${activity.eventTypes.mkString(" ")}", keyword) &&
        effectiveFrom.forall(activity.createTime >= _) &&
        effectiveTo.forall(activity.createTime <= _)
      }
    val offset = (normalizedPage - 1) * normalizedPageSize
    NativeAuditActivityPage(
      activities.slice(offset, offset + normalizedPageSize),
      normalizedPage,
      normalizedPageSize,
      activities.size,
      auditEnabled = true,
      current.mode,
      statusMessage)
  }

  private[server] def aggregateActivities(events: Seq[NativeAuditEvent])
      : Seq[NativeAuditActivity] = {
    events.groupBy(activityKey).valuesIterator.flatMap(toActivity).toSeq.sortBy(activity =>
      -activity.createTime)
  }

  private def activityKey(event: NativeAuditEvent): String = {
    event.eventType match {
      case OperationEventType if event.operationId.nonEmpty => s"operation:${event.operationId}"
      case SessionEventType if event.sessionId.nonEmpty => s"session:${event.sessionId}"
      case ServerEventType if event.clientIp.nonEmpty =>
        s"server:${event.clientIp}:${event.createTime}"
      case _ => s"event:${event.eventType}:${event.id}"
    }
  }

  private def toActivity(events: Seq[NativeAuditEvent]): Option[NativeAuditActivity] = {
    events.sortBy(_.eventTime).headOption.map { first =>
      val ordered = events.sortBy(_.eventTime)
      val latest = ordered.last
      val terminal = ordered.filter(event => SqlRecordTerminalStates.contains(event.status))
      val failure =
        terminal.filter(event => SqlRecordFailureStates.contains(event.status)).lastOption
      val stateEvent = failure.orElse(terminal.lastOption).getOrElse(latest)
      val createTime = ordered.map(_.createTime).filter(_ > 0L).sorted.headOption
        .getOrElse(first.eventTime)
      val startTime = ordered.map(_.startTime).filter(_ > 0L).sorted.headOption.getOrElse(0L)
      val completeTime = ordered.map(_.completeTime).filter(_ > 0L).sorted.lastOption
        .orElse(if (SqlRecordTerminalStates.contains(stateEvent.status)) Some(stateEvent.eventTime)
        else None)
        .getOrElse(0L)
      val statement = ordered.iterator.map(_.statement.trim).find(_.nonEmpty)
        .getOrElse(activityTitle(first.eventType, stateEvent.status))
      NativeAuditActivity(
        activityKey(first),
        first.eventType,
        ordered.iterator.map(_.eventType).filter(_.nonEmpty).toSet.toSeq.sorted,
        statement,
        ordered.reverseIterator.map(_.user).find(_.nonEmpty).getOrElse(""),
        ordered.reverseIterator.map(_.sessionId).find(_.nonEmpty).getOrElse(""),
        ordered.reverseIterator.map(_.operationId).find(_.nonEmpty).getOrElse(""),
        ordered.reverseIterator.map(_.engineType).find(_.nonEmpty).getOrElse(""),
        stateEvent.status,
        createTime,
        startTime,
        completeTime,
        ordered.map(_.duration).filter(_ > 0L).sorted.lastOption.getOrElse {
          if (first.eventType == ServerEventType) {
            // Server uptime is only known once the STOPPED record arrives.
            if (completeTime > 0L) completeTime - startTime else 0L
          } else if (startTime > 0L) {
            math.max(0L, (if (completeTime > 0L) completeTime else now()) - startTime)
          } else 0L
        },
        ordered.reverseIterator.map(_.error).find(_.nonEmpty).getOrElse(""),
        ordered.size,
        ordered.reverseIterator.map(_.note).find(_.nonEmpty).getOrElse(""))
    }
  }

  private def activityTitle(eventType: String, status: String): String = {
    val name = eventType.stripPrefix("kyuubi_").replace('_', ' ')
    s"${name.capitalize} ${Option(status).filter(_.nonEmpty).getOrElse("event")}".trim
  }

  private def now(): Long = System.currentTimeMillis()

  private[server] def aggregateSqlExecutionRecords(
      events: Seq[NativeAuditEvent]): Seq[SqlExecutionRecord] = {
    events.groupBy(_.operationId)
      .iterator
      .collect { case (operationId, operationEvents) if operationId.nonEmpty => operationEvents }
      .flatMap(toSqlExecutionRecord)
      .toSeq
      .sortBy(record => -record.createTime)
  }

  // scalastyle:off parameter.number
  private[server] def toSqlExecutionRecordPage(
      records: Seq[SqlExecutionRecord],
      page: Int,
      pageSize: Int,
      user: Option[String],
      sessionId: Option[String],
      engineType: Option[String],
      state: Option[String],
      keyword: Option[String],
      fromTime: Option[Long],
      toTime: Option[Long],
      auditEnabled: Boolean,
      source: String,
      message: String): SqlExecutionRecordPage = {
    val filtered = records.filter { record =>
      matches(record.user, user) &&
      matches(record.sessionId, sessionId) &&
      matches(record.engineType, engineType) &&
      matches(record.state, state) &&
      contains(s"${record.statement} ${record.statementSummary}", keyword) &&
      fromTime.forall(record.createTime >= _) &&
      toTime.forall(record.createTime <= _)
    }
    val offset = (page - 1) * pageSize
    SqlExecutionRecordPage(
      filtered.slice(offset, offset + pageSize),
      page,
      pageSize,
      filtered.size,
      auditEnabled,
      source,
      message)
  }
  // scalastyle:on parameter.number

  def sqlExecutionRecord(id: String): Option[SqlExecutionRecord] = {
    ensureInitialized()
    if (!current.enabled || id.trim.isEmpty) {
      None
    } else {
      val now = System.currentTimeMillis()
      val retentionStart = now - current.retentionDays * 24L * 60L * 60L * 1000L
      aggregateSqlExecutionRecords(
        readCurrentEvents(Some("kyuubi_operation"), Some(retentionStart), Some(now))
          .filter(_.operationId.equalsIgnoreCase(id)))
        .headOption
    }
  }

  private def readCurrentEvents(
      eventType: Option[String],
      from: Option[Long],
      to: Option[Long]): Seq[NativeAuditEvent] = {
    val events = current.mode match {
      case "JSON" =>
        // Session lifecycle records are written into the session creation day partition,
        // so the session directory must be scanned from the retention start to find close
        // records of sessions created before the query range.
        val sessionScanStart = System.currentTimeMillis() - current.retentionDays * MillisPerDay
        readJsonEvents(current.jsonPath, eventType, from, to, sessionScanStart)
      case "KAFKA" => readKafkaEvents(current.kafka)
    }
    inferStaleSessionClosure(events)
  }

  /**
   * A session whose close record was never written (lost logs, retention eviction or a
   * server crash) would stay RUNNING forever. Sessions whose latest record is still
   * non-terminal and older than the stale threshold are marked closed with a note that
   * the closure is inferred rather than observed.
   */
  private def inferStaleSessionClosure(events: Seq[NativeAuditEvent]): Seq[NativeAuditEvent] = {
    val staleSessions = events
      .filter(event => event.eventType == SessionEventType && event.sessionId.nonEmpty)
      .groupBy(_.sessionId)
      .collect {
        case (sessionId, sessionEvents)
            if sessionEvents.map(_.eventTime).max < now() - SessionStaleThreshold &&
              sessionEvents.forall(event => !SqlRecordTerminalStates.contains(event.status)) =>
          sessionId
      }.toSet
    if (staleSessions.isEmpty) {
      events
    } else {
      val note = s"stale-session-inferred-closed-${SessionStaleThresholdHours}h"
      events.map { event =>
        if (event.eventType == SessionEventType && staleSessions.contains(event.sessionId)) {
          event.copy(status = "CLOSED_STATE", note = note)
        } else {
          event
        }
      }
    }
  }

  private def toSqlExecutionRecord(events: Seq[NativeAuditEvent]): Option[SqlExecutionRecord] = {
    val operationEvents = events
      .filter(event =>
        event.statement.trim.nonEmpty && !event.statement.equalsIgnoreCase("LaunchEngine"))
      .sortBy(_.eventTime)
    operationEvents.headOption.map { first =>
      val latest = operationEvents.last
      val terminal = operationEvents.filter(event => SqlRecordTerminalStates.contains(event.status))
      val failure =
        terminal.filter(event => SqlRecordFailureStates.contains(event.status)).lastOption
      val stateEvent = failure.orElse(terminal.lastOption).getOrElse(latest)
      val createTime = operationEvents.map(_.createTime).filter(_ > 0L).sorted.headOption
        .getOrElse(first.eventTime)
      val startTime =
        operationEvents.map(_.startTime).filter(_ > 0L).sorted.headOption.getOrElse(0L)
      val completeTime = operationEvents.map(_.completeTime).filter(_ > 0L).sorted.lastOption
        .orElse(if (SqlRecordTerminalStates.contains(stateEvent.status)) Some(stateEvent.eventTime)
        else None)
        .getOrElse(0L)
      val executionDuration = operationEvents.map(_.duration).filter(_ > 0L).sorted.lastOption
        .getOrElse {
          if (startTime > 0L) {
            math.max(
              0L,
              (if (completeTime > 0L) completeTime else System.currentTimeMillis()) - startTime)
          } else {
            0L
          }
        }
      val statement = operationEvents.iterator.map(_.statement).find(_.nonEmpty).getOrElse("")
      SqlExecutionRecord(
        first.operationId,
        statement,
        summarize(statement),
        operationEvents.reverseIterator.map(_.user).find(_.nonEmpty).getOrElse(""),
        operationEvents.reverseIterator.map(_.sessionId).find(_.nonEmpty).getOrElse(""),
        operationEvents.reverseIterator.map(_.engineType).find(_.nonEmpty).getOrElse(""),
        stateEvent.status,
        createTime,
        startTime,
        completeTime,
        if (createTime > 0L && startTime > createTime) startTime - createTime else 0L,
        executionDuration,
        operationEvents.reverseIterator.map(_.error).find(_.nonEmpty).getOrElse(""))
    }
  }

  private def matches(value: String, filter: Option[String]): Boolean =
    filter.forall(expected => expected.isEmpty || value.equalsIgnoreCase(expected))

  private def contains(value: String, filter: Option[String]): Boolean =
    filter.forall(expected => value.toLowerCase.contains(expected.toLowerCase))

  private def summarize(sql: String): String = {
    val compact = sql.replaceAll("\\s+", " ").trim
    if (compact.length <= 240) compact else compact.take(237) + "..."
  }

  private def activate(config: ManagedAuditConfig, persist: Boolean): Unit = {
    val normalized = normalize(config)
    val candidate = if (normalized.enabled) Some(createHandler(normalized)) else None
    if (persist) persistConfig(normalized)
    val previous = activeHandler
    activeHandler = candidate
    current = normalized
    updatedAt = System.currentTimeMillis()
    healthy = true
    statusMessage = if (normalized.enabled) {
      s"${normalized.mode} audit logging is active"
    } else {
      "Audit logging is disabled"
    }
    previous.foreach(_.close())
  }

  private def createHandler(config: ManagedAuditConfig): EventHandler[KyuubiEvent] = {
    config.mode match {
      case "JSON" =>
        testJson(config.jsonPath)
        val handlerConf = baseConf.clone.set(SERVER_EVENT_JSON_LOG_PATH, config.jsonPath)
        val hostName = InetAddress.getLocalHost.getCanonicalHostName
        new ServerJsonLoggingEventHandler(
          s"server-$hostName-${System.currentTimeMillis()}",
          SERVER_EVENT_JSON_LOG_PATH,
          org.apache.kyuubi.util.KyuubiHadoopUtils.newHadoopConf(handlerConf),
          handlerConf)
      case "KAFKA" =>
        testKafka(config.kafka)
        new ServerKafkaLoggingEventHandler(
          config.kafka.topic,
          kafkaProperties(config.kafka).asScala,
          baseConf,
          baseConf.get(SERVER_EVENT_KAFKA_CLOSE_TIMEOUT))
    }
  }

  private def normalize(config: ManagedAuditConfig): ManagedAuditConfig = {
    val mode = config.mode.trim.toUpperCase
    require(Set("JSON", "KAFKA").contains(mode), "Audit mode must be JSON or KAFKA")
    require(
      config.retentionDays >= 1 && config.retentionDays <= 3650,
      "Retention days must be between 1 and 3650")
    mode match {
      case "JSON" =>
        require(config.jsonPath.trim.nonEmpty, "JSON log path is required")
        val uri = URI.create(config.jsonPath.trim)
        require(uri.getScheme == "file", "The Audit Log page currently supports file:// JSON paths")
      case "KAFKA" =>
        require(config.kafka.bootstrapServers.trim.nonEmpty, "Kafka bootstrap servers are required")
        require(config.kafka.topic.trim.matches("[A-Za-z0-9._-]{1,249}"), "Invalid Kafka topic")
        require(
          Set("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL")
            .contains(config.kafka.securityProtocol.toUpperCase),
          "Unsupported Kafka security protocol")
    }
    config.copy(
      mode = mode,
      jsonPath = config.jsonPath.trim,
      kafka = config.kafka.copy(
        bootstrapServers = config.kafka.bootstrapServers.trim,
        topic = config.kafka.topic.trim,
        securityProtocol = config.kafka.securityProtocol.trim.toUpperCase,
        saslMechanism = config.kafka.saslMechanism.trim.toUpperCase))
  }

  private def testJson(path: String): Unit = {
    val directory = new File(URI.create(path))
    Files.createDirectories(directory.toPath)
    require(directory.isDirectory, s"JSON log path is not a directory: $path")
    val probe = Files.createTempFile(directory.toPath, ".kyuubi-audit-probe-", ".tmp")
    Files.deleteIfExists(probe)
  }

  private def testKafka(kafka: AuditKafkaConfig): Unit = {
    val properties = kafkaProperties(kafka)
    properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
    val admin = AdminClient.create(properties)
    try {
      admin.describeTopics(Collections.singleton(kafka.topic)).allTopicNames().get()
    } finally {
      admin.close(Duration.ofSeconds(5))
    }
    val producerProperties = new Properties()
    producerProperties.putAll(properties)
    producerProperties.put(
      ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
      classOf[StringSerializer].getName)
    producerProperties.put(
      ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
      classOf[StringSerializer].getName)
    val producer = new KafkaProducer[String, String](producerProperties)
    val probeId = UUID.randomUUID().toString
    val probeKey = s"$KafkaProbeKeyPrefix-$probeId"
    val probe = mapper.writeValueAsString(Map(
      "eventType" -> KafkaProbeKeyPrefix,
      "eventTime" -> System.currentTimeMillis(),
      "probeId" -> probeId,
      "probe" -> true))
    val metadata =
      try {
        producer.send(new ProducerRecord[String, String](kafka.topic, probeKey, probe)).get()
      } finally {
        producer.close(Duration.ofSeconds(5))
      }

    val consumerProperties = new Properties()
    consumerProperties.putAll(properties)
    consumerProperties.put(
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
      classOf[StringDeserializer].getName)
    consumerProperties.put(
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
      classOf[StringDeserializer].getName)
    consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, s"kyuubi-audit-probe-$probeId")
    consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    val consumer = new KafkaConsumer[String, String](consumerProperties)
    try {
      val partition = new org.apache.kafka.common.TopicPartition(kafka.topic, metadata.partition())
      consumer.assign(Collections.singleton(partition))
      consumer.seek(partition, metadata.offset())
      val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos
      var received = false
      while (!received && System.nanoTime() < deadline) {
        received = consumer.poll(Duration.ofMillis(500)).iterator().asScala
          .exists(record => record.key() == probeKey && record.value() == probe)
      }
      require(received, "Kafka probe was written but could not be read back within 10 seconds")
    } finally {
      consumer.close(Duration.ofSeconds(5))
    }
  }

  private def kafkaProperties(kafka: AuditKafkaConfig): Properties = {
    val properties = new Properties()
    properties.put("bootstrap.servers", kafka.bootstrapServers)
    properties.put("security.protocol", kafka.securityProtocol)
    if (kafka.securityProtocol.startsWith("SASL")) {
      properties.put("sasl.mechanism", kafka.saslMechanism)
      val loginModule = kafka.saslMechanism match {
        case "SCRAM-SHA-256" | "SCRAM-SHA-512" =>
          "org.apache.kafka.common.security.scram.ScramLoginModule"
        case _ => "org.apache.kafka.common.security.plain.PlainLoginModule"
      }
      properties.put(
        "sasl.jaas.config",
        s"""$loginModule required username="${escapeJaas(kafka.username)}" """ +
          s"""password="${escapeJaas(kafka.password)}";""")
    }
    if (kafka.truststoreLocation.nonEmpty) {
      properties.put("ssl.truststore.location", kafka.truststoreLocation)
    }
    if (kafka.truststorePassword.nonEmpty) {
      properties.put("ssl.truststore.password", kafka.truststorePassword)
    }
    properties
  }

  private def escapeJaas(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

  private def readJsonEvents(
      path: String,
      eventType: Option[String],
      from: Option[Long],
      to: Option[Long],
      sessionScanStart: Long): Seq[NativeAuditEvent] = {
    val root = new File(URI.create(path)).toPath
    if (!Files.isDirectory(root)) return Seq.empty
    val start = from.getOrElse(0L)
    val end = to.getOrElse(Long.MaxValue)
    if (start > end) return Seq.empty

    val eventDirectories = eventType match {
      case Some(value) =>
        value match {
          case EventTypeDirectoryPattern() =>
            val directory = root.resolve(value).normalize()
            if (directory.getParent == root.normalize() && Files.isDirectory(directory)) {
              Seq(directory)
            } else {
              Seq.empty
            }
          case _ => Seq.empty
        }
      case None =>
        val stream = Files.list(root)
        try stream.iterator().asScala.filter(Files.isDirectory(_)).toList
        finally stream.close()
    }

    val lastDay = Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault()).toLocalDate
    eventDirectories.flatMap { directory =>
      // Session records always land in the creation day partition regardless of when the
      // lifecycle milestone happened, so the session directory is scanned from the
      // retention start rather than the query start.
      val directoryStart =
        if (directory.getFileName.toString == SessionEventType) math.min(start, sessionScanStart)
        else start
      val firstDay = Instant.ofEpochMilli(directoryStart)
        .atZone(ZoneId.systemDefault()).toLocalDate
      val dates = Iterator.iterate(firstDay)(_.plusDays(1)).takeWhile(!_.isAfter(lastDay)).toList
      dates.flatMap { date =>
        readJsonPartition(
          directory.resolve(s"day=${EventDateFormatter.format(date)}"),
          directory.getFileName.toString,
          start,
          end)
      }
    }
  }

  private def readJsonPartition(
      directory: java.nio.file.Path,
      eventType: String,
      from: Long,
      to: Long): Seq[NativeAuditEvent] = {
    if (!Files.isDirectory(directory)) return Seq.empty
    val files = Files.list(directory)
    try {
      files.iterator().asScala
        .filter(file => Files.isRegularFile(file) && file.getFileName.toString.endsWith(".json"))
        .toList
        .flatMap { file =>
          val lines = Files.lines(file, StandardCharsets.UTF_8)
          try {
            lines.iterator().asScala
              .flatMap(line => parseEvent(line, "JSON", Some(eventType)))
              .filter(event => event.eventTime >= from && event.eventTime <= to)
              .toList
          } finally {
            lines.close()
          }
        }
    } finally {
      files.close()
    }
  }

  private def readKafkaEvents(kafka: AuditKafkaConfig): Seq[NativeAuditEvent] = {
    val properties = kafkaProperties(kafka)
    properties.put(
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
      classOf[StringDeserializer].getName)
    properties.put(
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
      classOf[StringDeserializer].getName)
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, s"kyuubi-audit-ui-${UUID.randomUUID()}")
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    val consumer = new KafkaConsumer[String, String](properties)
    try {
      val partitions = consumer.partitionsFor(kafka.topic).asScala
        .map(info => new org.apache.kafka.common.TopicPartition(kafka.topic, info.partition()))
      if (partitions.isEmpty) return Seq.empty
      consumer.assign(partitions.asJava)
      val beginningOffsets = consumer.beginningOffsets(partitions.asJava)
      val endOffsets = consumer.endOffsets(partitions.asJava)
      val recordsPerPartition = Math.max(
        KafkaMinimumRecordsPerPartition,
        Math.ceil(KafkaQueryRecordLimit.toDouble / partitions.size).toLong)
      partitions.foreach { partition =>
        consumer.seek(
          partition,
          latestKafkaStartOffset(
            beginningOffsets.get(partition),
            endOffsets.get(partition),
            recordsPerPartition))
      }
      val records = ArrayBuffer.empty[NativeAuditEvent]
      var emptyPolls = 0
      var reachedEnd = false
      while (emptyPolls < 2 && !reachedEnd) {
        val batch = consumer.poll(Duration.ofMillis(500))
        if (batch.isEmpty) emptyPolls += 1 else emptyPolls = 0
        batch.iterator().asScala.foreach { record =>
          if (!Option(record.key()).exists(_.startsWith(KafkaProbeKeyPrefix))) {
            parseEvent(record.value(), "KAFKA", Option(record.key())).foreach(records += _)
          }
        }
        reachedEnd = partitions.forall(partition =>
          consumer.position(partition) >= endOffsets.get(partition))
      }
      records.toSeq
    } finally {
      consumer.close(Duration.ofSeconds(5))
    }
  }

  private[server] def latestKafkaStartOffset(
      beginningOffset: Long,
      endOffset: Long,
      recordLimit: Long): Long = {
    Math.max(beginningOffset, endOffset - recordLimit.max(1L))
  }

  private def parseEvent(
      json: String,
      source: String,
      eventTypeHint: Option[String]): Option[NativeAuditEvent] = Try {
    val node = mapper.readTree(json)
    val eventType = eventTypeHint.filter(_.nonEmpty)
      .orElse(text(node, "eventType")).getOrElse("unknown")
    val sessionEvent = eventType == SessionEventType
    val serverEvent = eventType == ServerEventType
    val recordedEventTime = long(node, "eventTime")
      .orElse(long(node, "createTime"))
      .orElse(long(node, "startTime")).getOrElse(0L)
    // A session event is a mutable instance re-posted at every lifecycle milestone while
    // its eventTime stays frozen at session creation, so use the milestone timestamp that
    // each record actually represents instead.
    val eventTime = if (sessionEvent) {
      long(node, "endTime").filter(_ > 0L)
        .orElse(long(node, "openedTime").filter(_ > 0L))
        .getOrElse(recordedEventTime)
    } else {
      recordedEventTime
    }
    val createTime = if (sessionEvent) {
      long(node, "startTime").orElse(long(node, "createTime")).getOrElse(eventTime)
    } else if (serverEvent) {
      // The STARTED and STOPPED records of one server process share startTime; keeping it
      // as createTime lets the aggregation layer group them into a single lifecycle.
      long(node, "startTime").getOrElse(eventTime)
    } else long(node, "createTime").getOrElse(eventTime)
    val startTime = if (sessionEvent) {
      long(node, "openedTime").filter(_ > 0L).getOrElse(0L)
    } else long(node, "startTime").getOrElse(0L)
    val completeTime = if (sessionEvent) {
      long(node, "endTime").filter(_ > 0L)
        .orElse(long(node, "completeTime")).getOrElse(0L)
    } else if (serverEvent && text(node, "state").contains(ServiceState.STOPPED.toString)) {
      // The STOPPED record's eventTime is the moment the server stopped.
      eventTime
    } else long(node, "completeTime").getOrElse(0L)
    val status = firstText(node, "state", "action") match {
      case value if value.nonEmpty => value
      case _ if sessionEvent && completeTime > 0L => "CLOSED_STATE"
      case _ if sessionEvent && errorText(node).nonEmpty => "ERROR_STATE"
      case _ if sessionEvent => "RUNNING_STATE"
      case _ => ""
    }
    val user = firstText(node, "sessionUser", "user")
    val operationId = firstText(node, "statementId", "operationId")
    val raw = mapper.writeValueAsString(redact(node.deepCopy[JsonNode]()))
    NativeAuditEvent(
      s"$source:$eventType:$eventTime:${json.hashCode}",
      source,
      eventType,
      eventTime,
      createTime,
      startTime,
      completeTime,
      user,
      status,
      firstText(node, "statement", "serverName"),
      firstText(node, "sessionId"),
      operationId,
      firstText(node, "clientIp", "clientIP", "serverIP"),
      firstText(node, "datasourceLabel"),
      firstText(node, "engineType", "engineName"),
      if (serverEvent) 0L
      else if (sessionEvent && completeTime > 0L && startTime > 0L) {
        math.max(0L, completeTime - startTime)
      } else long(node, "executionDuration").getOrElse(0L),
      errorText(node),
      raw)
  }.toOption

  private def text(node: JsonNode, name: String): Option[String] =
    Option(node.get(name)).filterNot(_.isNull).map(_.asText()).filter(_.nonEmpty)

  private def firstText(node: JsonNode, names: String*): String =
    names.iterator.flatMap(name => text(node, name)).toSeq.headOption.getOrElse("")

  private def long(node: JsonNode, name: String): Option[Long] =
    Option(node.get(name)).filterNot(_.isNull).map(_.asLong())

  private def errorText(node: JsonNode): String = Option(node.get("exception")) match {
    case Some(value) if !value.isNull =>
      if (value.isTextual) value.asText() else value.toString
    case _ => firstText(node, "reason", "sqlBlockedReason")
  }

  private def redact(node: JsonNode): JsonNode = {
    if (node.isObject) {
      node.fieldNames().asScala.toSeq.foreach { name =>
        if (SensitiveKey.findFirstIn(name).nonEmpty) {
          node.asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put(name, "******")
        } else {
          redact(node.get(name))
        }
      }
    } else if (node.isArray) {
      node.elements().asScala.foreach(redact)
    }
    node
  }

  private def fromKyuubiConf(conf: KyuubiConf): ManagedAuditConfig = {
    val loggers = conf.get(SERVER_EVENT_LOGGERS)
    if (loggers.contains("KAFKA")) {
      ManagedAuditConfig(
        enabled = true,
        mode = "KAFKA",
        jsonPath = conf.get(SERVER_EVENT_JSON_LOG_PATH),
        kafka = AuditKafkaConfig(
          bootstrapServers = conf.getOption(
            "kyuubi.backend.server.event.kafka.bootstrap.servers").getOrElse(""),
          topic = conf.get(SERVER_EVENT_KAFKA_TOPIC).getOrElse("")))
    } else {
      ManagedAuditConfig(
        enabled = loggers.contains("JSON"),
        mode = "JSON",
        jsonPath = conf.get(SERVER_EVENT_JSON_LOG_PATH))
    }
  }

  private def mergeSecrets(request: ManagedAuditConfig): ManagedAuditConfig = {
    request.copy(kafka = request.kafka.copy(
      password =
        if (request.kafka.password.nonEmpty) request.kafka.password else current.kafka.password,
      truststorePassword = if (request.kafka.truststorePassword.nonEmpty) {
        request.kafka.truststorePassword
      } else {
        current.kafka.truststorePassword
      }))
  }

  private def credentialAccessor: CredentialAccessor =
    CredentialAccessor(baseConf.get(DIGIWIN_DATASOURCE_CREDENTIAL_SECRET))

  private def persistConfig(config: ManagedAuditConfig): Unit = {
    val target = configFile.getOrElse {
      throw new IllegalStateException("Kyuubi audit configuration file cannot be located")
    }
    Option(target.getParentFile).foreach(_.mkdirs())
    val stored = StoredAuditConfig(
      config.enabled,
      config.mode,
      config.jsonPath,
      config.retentionDays,
      config.kafka.copy(password = "", truststorePassword = ""),
      if (config.kafka.password.nonEmpty) credentialAccessor.encrypt(config.kafka.password) else "",
      if (config.kafka.truststorePassword.nonEmpty) {
        credentialAccessor.encrypt(config.kafka.truststorePassword)
      } else "")
    val temporary = new File(target.getParentFile, s".${target.getName}.${System.nanoTime()}.tmp")
    try {
      Files.write(
        temporary.toPath,
        mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(stored),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE)
      try Files.move(
          temporary.toPath,
          target.toPath,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING)
      catch {
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          Files.move(temporary.toPath, target.toPath, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary.toPath)
    }
  }

  private def loadStored(): Option[ManagedAuditConfig] = configFile.filter(_.isFile).map { file =>
    val stored = mapper.readValue(file, classOf[StoredAuditConfig])
    val accessor = credentialAccessor
    StoredAuditConfig.toManaged(stored, accessor)
  }

  private def configFile: Option[File] = testConfigFile
    .orElse(sys.env.get(ConfigPathEnvironment).map(new File(_)))
    .orElse(Utils.getPropertiesFile(KYUUBI_CONF_FILE_NAME)
      .map(file => new File(file.getParentFile, ConfigFileName)))

  private def ensureInitialized(): Unit = {
    if (baseConf == null) {
      throw new IllegalStateException("Managed audit service is not initialized")
    }
  }

  private[server] def setConfigFileForTesting(file: Option[File]): Unit = synchronized {
    testConfigFile = file
  }
}

private[server] object StoredAuditConfig {
  def toManaged(stored: StoredAuditConfig, accessor: CredentialAccessor): ManagedAuditConfig = {
    ManagedAuditConfig(
      stored.enabled,
      stored.mode,
      stored.jsonPath,
      stored.retentionDays,
      stored.kafka.copy(
        password = if (stored.encryptedKafkaPassword.nonEmpty) {
          accessor.decrypt(stored.encryptedKafkaPassword)
        } else "",
        truststorePassword = if (stored.encryptedTruststorePassword.nonEmpty) {
          accessor.decrypt(stored.encryptedTruststorePassword)
        } else ""))
  }
}
