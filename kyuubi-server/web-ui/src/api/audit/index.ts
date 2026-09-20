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

export interface AuditRecord {
  timestamp: number
  user: string
  authType: string
  ip: string
  proxyIp: string
  forwardedFor: string[]
  method: string
  uri: string
  query?: string
  protocol: string
  status: number
  action?: string
}

export interface AuditRecordPage {
  records: AuditRecord[]
  total: number
  generatedAt: number
}

export interface AuditQuery {
  user?: string
  method?: string
  action?: string
  status?: number
  from?: number
  to?: number
  limit?: number
}

export function getAuditRecords(params: AuditQuery = {}) {
  return request({
    url: 'api/v1/admin/audit',
    method: 'get',
    params
  }) as Promise<AuditRecordPage>
}

export interface AuditKafkaConfig {
  bootstrapServers: string
  topic: string
  securityProtocol: 'PLAINTEXT' | 'SSL' | 'SASL_PLAINTEXT' | 'SASL_SSL'
  saslMechanism: string
  username: string
  password: string
  truststoreLocation: string
  truststorePassword: string
}

export interface ManagedAuditConfig {
  enabled: boolean
  mode: 'JSON' | 'KAFKA'
  jsonPath: string
  retentionDays: number
  kafka: AuditKafkaConfig
}

export interface ManagedAuditConfigView extends ManagedAuditConfig {
  passwordConfigured: boolean
  truststorePasswordConfigured: boolean
  updatedAt: number
  healthy: boolean
  message: string
}

export interface AuditConnectionTestResult {
  success: boolean
  message: string
  checkedAt: number
}

export interface NativeAuditEvent {
  id: string
  source: string
  eventType: string
  eventTime: number
  createTime: number
  startTime: number
  completeTime: number
  user: string
  status: string
  statement: string
  sessionId: string
  operationId: string
  clientIp: string
  datasourceLabel: string
  engineType: string
  duration: number
  error: string
  rawJson: string
  note?: string
}

export interface NativeAuditEventPage {
  records: NativeAuditEvent[]
  total: number
  generatedAt: number
  source: string
  message: string
}

export interface NativeAuditActivity {
  id: string
  eventType: string
  eventTypes: string[]
  statement: string
  user: string
  sessionId: string
  operationId: string
  engineType: string
  state: string
  createTime: number
  startTime: number
  completeTime: number
  duration: number
  error: string
  eventCount: number
  note?: string
}

export interface NativeAuditActivityPage {
  records: NativeAuditActivity[]
  page: number
  pageSize: number
  total: number
  auditEnabled: boolean
  source: string
  message: string
}

export interface NativeAuditActivityQuery {
  page: number
  pageSize: number
  eventType?: string
  user?: string
  status?: string
  keyword?: string
  from?: number
  to?: number
}

export interface NativeAuditQuery {
  eventType?: string
  user?: string
  status?: string
  operationId?: string
  sessionId?: string
  from?: number
  to?: number
  limit?: number
}

export function getNativeAuditConfig() {
  return request({
    url: 'api/v1/admin/event-audit/config',
    method: 'get'
  }) as Promise<ManagedAuditConfigView>
}

export function updateNativeAuditConfig(data: ManagedAuditConfig) {
  return request({
    url: 'api/v1/admin/event-audit/config',
    method: 'put',
    data
  }) as Promise<ManagedAuditConfigView>
}

export function testNativeAuditConfig(data: ManagedAuditConfig) {
  return request({
    url: 'api/v1/admin/event-audit/test',
    method: 'post',
    data
  }) as Promise<AuditConnectionTestResult>
}

export function getNativeAuditEvents(params: NativeAuditQuery = {}) {
  return request({
    url: 'api/v1/admin/event-audit/events',
    method: 'get',
    params
  }) as Promise<NativeAuditEventPage>
}

export function getNativeAuditActivities(params: NativeAuditActivityQuery) {
  return request({
    url: 'api/v1/admin/event-audit/activities',
    method: 'get',
    params
  }) as Promise<NativeAuditActivityPage>
}

export type { AuditQuery as AdminAuditQuery }
