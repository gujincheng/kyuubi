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

import org.apache.kyuubi.KyuubiException

/**
 * Process-wide holder for the [[DatasourceRegistry]] and label key, initialized by KyuubiServer
 * at startup. The [[DatasourceConfAdvisor]] (loaded via SPI with a no-arg constructor) reads
 * from this holder instead of reconstructing its own registry per session.
 */
object DatasourceRegistryHolder {

  @volatile private var registryOpt: Option[DatasourceRegistry] = None
  @volatile private var labelKey: String = "kyuubi.datasource"

  def init(registry: DatasourceRegistry, labelKey: String): Unit = {
    registryOpt = Some(registry)
    this.labelKey = labelKey
  }

  def registry: DatasourceRegistry = registryOpt.getOrElse(
    throw new KyuubiException("Datasource registry is not initialized"))

  def getLabelKey: String = labelKey
}
