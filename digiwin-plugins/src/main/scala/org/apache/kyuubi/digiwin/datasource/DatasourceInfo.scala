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

/**
 * @param label unique datasource identifier, e.g. sr-prod
 * @param engineType engine type, e.g. jdbc or spark
 * @param jdbcType short jdbc dialect name, e.g. starrocks (only for engineType=jdbc)
 * @param driverClass jdbc driver class name
 * @param jdbcUrl jdbc connection url
 * @param username connection username
 * @param encryptedPassword AES-encrypted password (never plaintext)
 * @param connectionPoolParams extra pool params, e.g. maximumPoolSize=10
 * @param status ENABLED / DISABLED
 * @param description free text
 */
case class DatasourceInfo(
    label: String,
    engineType: String,
    jdbcType: String,
    driverClass: String,
    jdbcUrl: String,
    username: String,
    encryptedPassword: String,
    connectionPoolParams: Map[String, String] = Map.empty,
    status: String = "ENABLED",
    description: String = "") {

  def isEnabled: Boolean = "ENABLED".equalsIgnoreCase(status)
}
