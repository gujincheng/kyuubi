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

/**
 * @param id unique rule identifier, e.g. block-drop
 * @param name display name
 * @param ruleType KEYWORD / WITHOUT_WHERE / REGEX
 * @param pattern KEYWORD -> keyword (DROP); WITHOUT_WHERE -> DELETE/UPDATE; REGEX -> regex
 * @param action DENY (only DENY in P0)
 * @param engineScope optional, restrict to an engine type (e.g. jdbc); empty = all engines
 * @param userScope optional, restrict to listed users; empty = all users
 * @param enabled whether the rule is active
 * @param description free text
 * @param createTime millis
 * @param updateTime millis
 */
case class SqlRule(
    id: String,
    name: String,
    ruleType: String,
    pattern: String,
    action: String = "DENY",
    engineScope: String = "",
    userScope: Seq[String] = Seq.empty,
    enabled: Boolean = true,
    description: String = "",
    createTime: Long = 0L,
    updateTime: Long = 0L)
