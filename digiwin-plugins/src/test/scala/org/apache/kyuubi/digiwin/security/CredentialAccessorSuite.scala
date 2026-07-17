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

import org.apache.kyuubi.KyuubiFunSuite

class CredentialAccessorSuite extends KyuubiFunSuite {

  test("encrypt then decrypt round trip") {
    val accessor = new CredentialAccessor("0123456789abcdef")
    val secret = "p@ssw0rd-secret"
    val enc = accessor.encrypt(secret)
    assert(enc != secret)
    assert(accessor.decrypt(enc) === secret)
  }

  test("two encryptions produce different ciphertext (random iv)") {
    val accessor = new CredentialAccessor("0123456789abcdef")
    assert(accessor.encrypt("x") !== accessor.encrypt("x"))
  }

  test("invalid secret length rejected") {
    intercept[IllegalArgumentException] {
      new CredentialAccessor("short")
    }
  }

  test("apply(None) returns a working fallback accessor") {
    val accessor = CredentialAccessor(None)
    val enc = accessor.encrypt("hello")
    assert(accessor.decrypt(enc) === "hello")
  }
}
