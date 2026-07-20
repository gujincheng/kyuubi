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

package org.apache.kyuubi.digiwin.security

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._

import org.apache.kyuubi.{Logging, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.util.ThreadUtils

class RuleRegistry(conf: KyuubiConf) extends Logging {

  private val store = new RuleStore(conf)
  private val cache = new ConcurrentHashMap[String, SqlRule]()

  private val scheduler =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("digiwin-sql-rule-refresher")

  def start(): Unit = {
    refresh()
    val interval = conf.get(DIGIWIN_DATASOURCE_REFRESH_INTERVAL)
    scheduler.scheduleAtFixedRate(
      new Runnable { override def run(): Unit = Utils.tryLogNonFatalError(refresh()) },
      interval,
      interval,
      TimeUnit.MILLISECONDS)
  }

  def refresh(): Unit = {
    val all = store.list()
    val next = new ConcurrentHashMap[String, SqlRule]()
    all.foreach(r => next.put(r.id, r))
    cache.clear()
    cache.putAll(next)
    info(s"Refreshed SQL rule registry: ${cache.size()} entries.")
  }

  def get(id: String): Option[SqlRule] = Option(cache.get(id))

  def list(): Seq[SqlRule] = cache.values().asScala.toSeq.sortBy(_.id)

  /** Enabled rules, evaluated against incoming SQL. */
  def snapshot(): Seq[SqlRule] = list().filter(_.enabled)

  /** Persists and updates the cache synchronously so the next SQL sees it immediately. */
  def upsert(rule: SqlRule): Unit = {
    store.upsert(rule)
    cache.put(rule.id, rule)
  }

  def delete(id: String): Unit = {
    store.delete(id)
    cache.remove(id)
  }

  def stop(): Unit = {
    scheduler.shutdownNow()
    store.close()
  }
}
