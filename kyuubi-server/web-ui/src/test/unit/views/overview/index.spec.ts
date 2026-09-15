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

import { flushPromises, shallowMount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'

import Overview from '@/views/overview/index.vue'
import * as overviewApi from '@/api/overview'
import { createI18n, getStore } from '@/test/unit/utils'

vi.mock('@/api/overview', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/overview')>('@/api/overview')
  return {
    ...actual,
    getOverviewSummary: vi.fn(),
    getOverviewTrend: vi.fn()
  }
})

const summary: overviewApi.IOverviewSummary = {
  serverStartCount: 1,
  liveServerCount: 1,
  engines: [
    { engineType: 'JDBC', launchCount: 2 },
    { engineType: 'SPARK_SQL', launchCount: 1 },
    { engineType: 'FLINK_SQL', launchCount: 0 },
    { engineType: 'TRINO', launchCount: 0 },
    { engineType: 'HIVE_SQL', launchCount: 0 },
    { engineType: 'DATA_AGENT', launchCount: 0 }
  ],
  profileCount: 3,
  activeUserCount: 2,
  activeSessionCount: 4,
  execPool: { size: 5, active: 2, waiting: 1, alive: 3 },
  operations: {
    open: 3,
    running: 2,
    waiting: 1,
    failed: 1,
    failureRate: 2.5,
    latency: { p50: 120, p95: 450, p99: 800 }
  },
  engineHealth: {
    launching: 1,
    waiting: 0,
    failed: 0,
    timeout: 0,
    startupLatency: { p50: 500, p95: 1000, p99: 1500 }
  },
  batchPendingMaxElapse: 0,
  batchOperations: {
    total: 0,
    failed: 0,
    failureRate: 0,
    pendingMaxElapse: 0
  },
  health: { status: 'NORMAL', checkedAt: 1, issues: [] },
  dataStatus: {
    status: 'READY',
    source: 'in-process',
    lastSampleAt: 1,
    ageMs: 500,
    stale: false,
    sampleIntervalMs: 30_000,
    message: 'Overview metrics are ready'
  },
  accessHealth: {
    activeRequests: 1,
    requestP95Ms: 12,
    requestCount: 10,
    failedRequests: 0,
    failureRate: 0
  },
  runtimeHealth: {
    heapUsage: 0.4,
    nonHeapUsage: 0.2,
    threadCount: 12,
    deadlockCount: 0,
    gcCount: 4,
    gcTimeMs: 35,
    sslCertExpirationMs: null
  },
  metadataHealth: {
    opened: 1,
    total: 8,
    failed: 0,
    retrying: 0,
    failureRate: 0
  }
}

beforeEach(() => {
  vi.mocked(overviewApi.getOverviewSummary).mockResolvedValue(summary)
  vi.mocked(overviewApi.getOverviewTrend).mockResolvedValue({
    range: '1h',
    intervalMs: 60000,
    points: [
      { timestamp: 1, value: 2 },
      { timestamp: 2, value: 5 }
    ],
    failedPoints: [{ timestamp: 2, value: 1 }]
  })
})

afterEach(() => {
  vi.clearAllMocks()
})

function mountOverview() {
  const router = createRouter({ history: createWebHistory(), routes: [] })
  return shallowMount(Overview, {
    global: {
      plugins: [createI18n(), router, getStore(), ElementPlus]
    }
  })
}

test('loads summary and renders the monitoring dashboard', async () => {
  const wrapper = mountOverview()
  await flushPromises()

  expect(overviewApi.getOverviewSummary).toHaveBeenCalledOnce()
  expect(overviewApi.getOverviewTrend).toHaveBeenCalledWith('1h')
  expect(wrapper.text()).toContain('Gateway overview')
  expect(wrapper.text()).toContain('Active users')
  expect(wrapper.text()).toContain('Spark SQL')
  expect(wrapper.text()).toContain('Running')
  expect(wrapper.text()).toContain('Waiting')
  expect(wrapper.text()).toContain('Tasks being processed now')
  expect(wrapper.text()).toContain('Overall status')
  expect(wrapper.text()).toContain('Runtime health')
  expect(wrapper.text()).toContain('Metadata health')
  expect((wrapper.vm as any).trendStatusLabel).toBe('Data ready')
  expect(wrapper.find('.trend-status').exists()).toBe(true)
  expect(wrapper.text()).not.toContain('/ 5')
  expect(wrapper.find('.pool-primary-stats').exists()).toBe(true)
  expect(wrapper.find('.chart-line').exists()).toBe(true)
  expect(wrapper.find('.chart-guide').exists()).toBe(true)
  expect(wrapper.find('.chart-axis-label').exists()).toBe(true)
  expect(wrapper.find('.chart-hover-overlay').exists()).toBe(true)
})

test('loads a new trend when the time range changes', async () => {
  const wrapper = mountOverview()
  await flushPromises()

  vi.mocked(overviewApi.getOverviewTrend).mockResolvedValue({
    range: '7d',
    intervalMs: 3600000,
    points: [{ timestamp: 3, value: 1 }]
  })
  const overviewVm = wrapper.vm as any
  await overviewVm.changeRange('7d')

  expect(overviewApi.getOverviewTrend).toHaveBeenLastCalledWith('7d')
  expect(overviewVm.selectedRange).toBe('7d')
})

test('resizes the trend chart to fill its container', async () => {
  const wrapper = mountOverview()
  await flushPromises()

  const overviewVm = wrapper.vm as any
  overviewVm.resizeChart(1120)
  await nextTick()

  expect(wrapper.find('.trend-chart').attributes('viewBox')).toBe(
    '0 0 1120 208'
  )
})

test('measures the chart after loading and adapts ticks on window resize', async () => {
  const width = vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get')
  width.mockReturnValue(1120)
  const wrapper = mountOverview()

  try {
    expect(wrapper.find('.trend-chart').exists()).toBe(false)
    await flushPromises()
    expect(wrapper.find('.trend-chart').attributes('viewBox')).toBe(
      '0 0 1120 208'
    )
    expect(wrapper.findAll('.chart-x-label')).toHaveLength(5)

    width.mockReturnValue(300)
    window.dispatchEvent(new Event('resize'))
    await nextTick()
    expect(wrapper.find('.trend-chart').attributes('viewBox')).toBe(
      '0 0 300 208'
    )
    expect(wrapper.findAll('.chart-x-label')).toHaveLength(2)
  } finally {
    wrapper.unmount()
    width.mockRestore()
  }
})

test('normalizes a legacy summary without extended metrics', () => {
  const normalized = overviewApi.normalizeOverviewSummary({
    serverStartCount: 1,
    liveServerCount: 1,
    engines: [],
    profileCount: 0,
    activeUserCount: 1,
    activeSessionCount: 1,
    execPool: { size: 5, active: 2, waiting: 1 }
  })

  expect(normalized.operations.open).toBe(3)
  expect(normalized.operations.running).toBe(2)
  expect(normalized.engineHealth.startupLatency.p95).toBe(0)
  expect(normalized.execPool.alive).toBe(0)
  expect(normalized.health.status).toBe('UNKNOWN')
  expect(normalized.dataStatus.source).toBe('in-process')
  expect(normalized.dataStatus.status).toBe('INITIALIZING')
})

test('keeps an explicit label for an engine type added by the backend', async () => {
  const wrapper = mountOverview()
  await flushPromises()

  expect((wrapper.vm as any).engineLabel('CUSTOM_ENGINE')).toBe('CUSTOM_ENGINE')
})

test('shows an initializing state when the trend has no samples yet', async () => {
  vi.mocked(overviewApi.getOverviewSummary).mockResolvedValue({
    ...summary,
    dataStatus: {
      ...summary.dataStatus,
      status: 'INITIALIZING',
      lastSampleAt: 0,
      ageMs: 0,
      message: 'Overview metrics are initializing'
    }
  })
  vi.mocked(overviewApi.getOverviewTrend).mockResolvedValue({
    range: '1h',
    intervalMs: 60000,
    points: [],
    failedPoints: []
  })

  const wrapper = mountOverview()
  await flushPromises()

  expect(wrapper.find('.trend-chart').exists()).toBe(false)
  expect(wrapper.text()).toContain('Collecting trend data')
  expect(wrapper.text()).toContain(
    'SQL execution trend will appear after metrics are collected'
  )
})

test('keeps the previous trend and reports a range loading error', async () => {
  const wrapper = mountOverview()
  await flushPromises()

  vi.mocked(overviewApi.getOverviewTrend).mockRejectedValueOnce(
    new Error('range unavailable')
  )
  const overviewVm = wrapper.vm as any
  await overviewVm.changeRange('7d')
  await nextTick()

  expect(overviewVm.selectedRange).toBe('1h')
  expect(overviewVm.trendErrorMessage).toContain('range unavailable')
  expect(wrapper.find('.chart-line').exists()).toBe(true)
})

test('labels stale metric data instead of presenting it as current', async () => {
  vi.mocked(overviewApi.getOverviewSummary).mockResolvedValue({
    ...summary,
    dataStatus: {
      ...summary.dataStatus,
      status: 'STALE',
      stale: true,
      ageMs: 90_000,
      message: 'Overview metrics are stale'
    }
  })

  const wrapper = mountOverview()
  await flushPromises()

  expect(wrapper.text()).toContain('Metrics stopped updating')
})

test('changes the automatic refresh interval without creating duplicate timers', async () => {
  const setIntervalSpy = vi.spyOn(window, 'setInterval')
  const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
  const wrapper = mountOverview()
  await flushPromises()

  const overviewVm = wrapper.vm as any
  overviewVm.refreshIntervalSeconds = 5
  overviewVm.restartRefreshTimer()

  expect(clearIntervalSpy).toHaveBeenCalled()
  expect(setIntervalSpy).toHaveBeenLastCalledWith(expect.any(Function), 5000)

  setIntervalSpy.mockRestore()
  clearIntervalSpy.mockRestore()
})
