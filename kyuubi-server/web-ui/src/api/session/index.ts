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

import request from '@/utils/request'

interface SessionData {
  identifier: string
  remoteId?: string
  user?: string
  ipAddr?: string
  conf?: Record<string, string>
  createTime?: number
  duration?: number
  idleTime?: number
  exception?: string
  sessionType?: string
  kyuubiInstance?: string
  engineId?: string
  engineName?: string
  engineUrl?: string
  sessionName?: string
  totalOperations?: number
}

interface SessionSearchParams {
  user?: string
  users?: string
  sessionType?: string
}

export function getAllSessions(params: SessionSearchParams = {}) {
  return request({
    url: 'api/v1/admin/sessions',
    method: 'get',
    params
  })
}

export function closeSession(sessionId: string) {
  return request({
    url: `api/v1/admin/sessions/${sessionId}`,
    method: 'delete'
  })
}

export const deleteSession = closeSession

export function getSession(sessionId: string) {
  return request({
    url: `api/v1/sessions/${sessionId}`,
    method: 'get'
  })
}

export function getAllTypeOperation(sessionId: string) {
  return request({
    url: `api/v1/sessions/${sessionId}/operations`,
    method: 'get'
  })
}

export type { SessionData, SessionSearchParams }
