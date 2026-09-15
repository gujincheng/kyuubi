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

package org.apache.kyuubi.operation

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.engine.EngineType.SPARK_SQL

class KyuubiOperationSuite extends KyuubiFunSuite {

  test("uses the effective engine type when normalized session conf omits defaults") {
    assert(
      KyuubiOperation.resolveEngineType(Map.empty, "", SPARK_SQL.toString) === SPARK_SQL.toString)
  }

  test("preserves an explicitly selected engine type") {
    assert(
      KyuubiOperation.resolveEngineType(
        Map("kyuubi.engine.type" -> "JDBC"),
        "",
        "JDBC") === "JDBC")
  }

  test("falls back to the effective engine type for an unknown datasource label") {
    assert(
      KyuubiOperation.resolveEngineType(
        Map.empty,
        "missing-datasource",
        SPARK_SQL.toString) === SPARK_SQL.toString)
  }
}
