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

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}

import org.apache.kyuubi.Logging

/**
 * AES/CBC/PKCS5Padding credential encryption with a random IV per encryption.
 * The secret must be 16 bytes (after UTF-8 decode). The stored form is "ivBase64:cipherBase64".
 */
class CredentialAccessor(secret: String) extends Logging {

  private val keyBytes: Array[Byte] = {
    val raw = secret.getBytes(StandardCharsets.UTF_8)
    require(raw.length == 16, s"AES secret must be 16 bytes, got ${raw.length}")
    raw
  }
  private val secretKeySpec = new SecretKeySpec(keyBytes, "AES")
  private val random = new SecureRandom()

  def encrypt(plain: String): String = synchronized {
    val iv = new Array[Byte](16)
    random.nextBytes(iv)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new IvParameterSpec(iv))
    val encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8))
    val ivB64 = Base64.getEncoder.encodeToString(iv)
    val dataB64 = Base64.getEncoder.encodeToString(encrypted)
    s"$ivB64:$dataB64"
  }

  def decrypt(stored: String): String = synchronized {
    val parts = stored.split(":", 2)
    require(parts.length == 2, "Invalid encrypted credential format")
    val iv = Base64.getDecoder.decode(parts(0))
    val data = Base64.getDecoder.decode(parts(1))
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, new IvParameterSpec(iv))
    new String(cipher.doFinal(data), StandardCharsets.UTF_8)
  }
}

object CredentialAccessor extends Logging {
  private lazy val fallback: CredentialAccessor = {
    // 16 ASCII chars => 16 UTF-8 bytes, satisfying the 16-byte AES requirement.
    val rnd = new SecureRandom()
    val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val sb = new StringBuilder(16)
    for (_ <- 0 until 16) sb.append(chars.charAt(rnd.nextInt(chars.length)))
    new CredentialAccessor(sb.toString)
  }

  def apply(secretOpt: Option[String]): CredentialAccessor = secretOpt match {
    case Some(s) => new CredentialAccessor(s)
    case None =>
      warn("kyuubi.digiwin.datasource.credential.secret is unset; " +
        "using a process-local random key (encrypted credentials won't survive restart).")
      fallback
  }
}
