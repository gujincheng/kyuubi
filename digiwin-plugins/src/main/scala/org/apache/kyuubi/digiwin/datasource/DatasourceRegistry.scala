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

package org.apache.kyuubi.digiwin.datasource

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._

import org.apache.kyuubi.{KyuubiException, Logging, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.digiwin.security.CredentialAccessor
import org.apache.kyuubi.util.ThreadUtils

class DatasourceRegistry(conf: KyuubiConf) extends Logging {

  private val store = new DatasourceStore(conf)
  private val credentialAccessor =
    CredentialAccessor(conf.get(DIGIWIN_DATASOURCE_CREDENTIAL_SECRET))
  private val cache = new ConcurrentHashMap[String, DatasourceInfo]()

  private val scheduler =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("digiwin-datasource-refresher")

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
    val next = new ConcurrentHashMap[String, DatasourceInfo]()
    all.foreach(ds => next.put(ds.label, ds))
    cache.clear()
    cache.putAll(next)
    info(s"Refreshed datasource registry: ${cache.size()} entries.")
  }

  def get(label: String): Option[DatasourceInfo] = Option(cache.get(label))

  def list(): Seq[DatasourceInfo] = cache.values().asScala.toSeq.sortBy(_.label)

  /** Encrypts the plaintext password, persists, and updates the cache. */
  def upsert(ds: DatasourceInfo, plainPassword: String): Unit = {
    val encrypted = credentialAccessor.encrypt(plainPassword)
    val toStore = ds.copy(encryptedPassword = encrypted)
    store.upsert(toStore)
    cache.put(toStore.label, toStore)
  }

  def delete(label: String): Unit = {
    store.delete(label)
    cache.remove(label)
  }

  /** Returns the decrypted password for the label; throws if missing/disabled. */
  def getDecryptedPassword(label: String): String = {
    val ds = get(label).getOrElse(
      throw new KyuubiException(s"Datasource $label not found"))
    if (!ds.isEnabled) throw new KyuubiException(s"Datasource $label is disabled")
    credentialAccessor.decrypt(ds.encryptedPassword)
  }

  def stop(): Unit = {
    scheduler.shutdownNow()
    store.close()
  }
}
