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

export interface IOverviewEngine {
  engineType: string
  launchCount: number
}

export interface IOverviewExecPool {
  size: number
  active: number
  waiting: number
  alive: number
}

export interface IOverviewLatency {
  p50: number
  p95: number
  p99: number
}

export interface IOverviewOperations {
  open: number
  running: number
  waiting: number
  failed: number
  failureRate: number
  latency: IOverviewLatency
}

export interface IOverviewBatchOperations {
  total: number
  failed: number
  failureRate: number
  pendingMaxElapse: number
}

export interface IOverviewEngineHealth {
  launching: number
  waiting: number
  failed: number
  timeout: number
  startupLatency: IOverviewLatency
}

export interface IOverviewHealthIssue {
  code: string
  severity: 'WARNING' | 'CRITICAL'
  metric: string
  value: number
  threshold: number
  message: string
}

export interface IOverviewHealth {
  status: 'NORMAL' | 'WARNING' | 'CRITICAL' | 'UNKNOWN'
  checkedAt: number
  issues: IOverviewHealthIssue[]
}

export interface IOverviewDataStatus {
  status: 'INITIALIZING' | 'READY' | 'STALE'
  source: string
  lastSampleAt: number
  ageMs: number
  stale: boolean
  sampleIntervalMs: number
  message: string
}

export interface IOverviewAccessHealth {
  activeRequests: number
  requestP95Ms: number
  requestCount: number
  failedRequests: number
  failureRate: number
}

export interface IOverviewRuntimeHealth {
  heapUsage: number
  nonHeapUsage: number
  threadCount: number
  deadlockCount: number
  gcCount: number
  gcTimeMs: number
  sslCertExpirationMs?: number | null
}

export interface IOverviewMetadataHealth {
  opened: number
  total: number
  failed: number
  retrying: number
  failureRate: number
}

export interface IOverviewSummary {
  serverStartCount: number
  liveServerCount: number
  engines: IOverviewEngine[]
  profileCount: number
  activeUserCount: number
  activeSessionCount: number
  execPool: IOverviewExecPool
  operations: IOverviewOperations
  engineHealth: IOverviewEngineHealth
  batchPendingMaxElapse: number
  batchOperations: IOverviewBatchOperations
  health: IOverviewHealth
  dataStatus: IOverviewDataStatus
  accessHealth: IOverviewAccessHealth
  runtimeHealth: IOverviewRuntimeHealth
  metadataHealth: IOverviewMetadataHealth
}

export interface IOverviewTrendPoint {
  timestamp: number
  value: number
}

export interface IOverviewTrend {
  range: string
  intervalMs: number
  points: IOverviewTrendPoint[]
  failedPoints?: IOverviewTrendPoint[]
  latencyPoints?: IOverviewTrendPoint[]
  queuePoints?: IOverviewTrendPoint[]
}

type OverviewSummaryPayload = Omit<Partial<IOverviewSummary>, 'execPool'> & {
  execPool?: Partial<IOverviewExecPool>
}

const emptyLatency = (): IOverviewLatency => ({ p50: 0, p95: 0, p99: 0 })

export function normalizeOverviewSummary(
  payload: OverviewSummaryPayload
): IOverviewSummary {
  const execPool = {
    size: 0,
    active: 0,
    waiting: 0,
    alive: 0,
    ...payload.execPool
  }
  const operations = payload.operations || {
    open: execPool.active + execPool.waiting,
    running: execPool.active,
    waiting: execPool.waiting,
    failed: 0,
    failureRate: 0,
    latency: emptyLatency()
  }
  const engineHealth = payload.engineHealth || {
    launching: 0,
    waiting: 0,
    failed: 0,
    timeout: 0,
    startupLatency: emptyLatency()
  }
  const batchOperations = payload.batchOperations || {
    total: 0,
    failed: 0,
    failureRate: 0,
    pendingMaxElapse: payload.batchPendingMaxElapse || 0
  }
  const health = payload.health || {
    status: 'UNKNOWN' as const,
    checkedAt: 0,
    issues: []
  }
  const dataStatus = payload.dataStatus || {
    status: 'INITIALIZING' as const,
    source: 'in-process',
    lastSampleAt: 0,
    ageMs: 0,
    stale: false,
    sampleIntervalMs: 30_000,
    message: 'Overview metrics are initializing'
  }
  const accessHealth = payload.accessHealth || {
    activeRequests: 0,
    requestP95Ms: 0,
    requestCount: 0,
    failedRequests: 0,
    failureRate: 0
  }
  const runtimeHealth = payload.runtimeHealth || {
    heapUsage: 0,
    nonHeapUsage: 0,
    threadCount: 0,
    deadlockCount: 0,
    gcCount: 0,
    gcTimeMs: 0,
    sslCertExpirationMs: null
  }
  const metadataHealth = payload.metadataHealth || {
    opened: 0,
    total: 0,
    failed: 0,
    retrying: 0,
    failureRate: 0
  }

  return {
    serverStartCount: payload.serverStartCount || 0,
    liveServerCount: payload.liveServerCount || 0,
    engines: payload.engines || [],
    profileCount: payload.profileCount || 0,
    activeUserCount: payload.activeUserCount || 0,
    activeSessionCount: payload.activeSessionCount || 0,
    execPool,
    operations,
    engineHealth,
    batchPendingMaxElapse: payload.batchPendingMaxElapse || 0,
    batchOperations,
    health,
    dataStatus: {
      status:
        payload.dataStatus?.status ||
        (dataStatus.lastSampleAt > 0 ? 'READY' : 'INITIALIZING'),
      source: dataStatus.source,
      lastSampleAt: dataStatus.lastSampleAt,
      ageMs: dataStatus.ageMs,
      stale: dataStatus.stale,
      sampleIntervalMs: dataStatus.sampleIntervalMs,
      message: dataStatus.message
    },
    accessHealth,
    runtimeHealth,
    metadataHealth
  }
}

export function getOverviewSummary(): Promise<IOverviewSummary> {
  return request({
    url: 'api/v1/overview/summary',
    method: 'get'
  }).then((payload) =>
    normalizeOverviewSummary(payload as OverviewSummaryPayload)
  )
}

export function getOverviewTrend(range: string): Promise<IOverviewTrend> {
  return request({
    url: 'api/v1/overview/trend',
    method: 'get',
    params: { range }
  }) as Promise<IOverviewTrend>
}
