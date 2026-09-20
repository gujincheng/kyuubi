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
import { createRouter, createWebHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'

import SqlRecord from '@/views/management/sql-record/index.vue'
import * as auditApi from '@/api/audit'
import { createI18n, getStore } from '@/test/unit/utils'

vi.mock('@/api/audit', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/audit')>('@/api/audit')
  return {
    ...actual,
    getNativeAuditActivities: vi.fn(),
    getNativeAuditEvents: vi.fn(),
    getNativeAuditConfig: vi.fn()
  }
})

const record: auditApi.NativeAuditActivity = {
  id: 'operation:op-1',
  eventType: 'kyuubi_operation',
  eventTypes: ['kyuubi_operation'],
  statement: 'select 1',
  user: 'alice',
  sessionId: 'session-1',
  operationId: 'op-1',
  engineType: 'JDBC',
  state: 'FINISHED_STATE',
  createTime: 1_700_000_000_000,
  startTime: 1_700_000_000_050,
  completeTime: 1_700_000_001_050,
  duration: 1000,
  error: '',
  eventCount: 3
}

const auditEvent: auditApi.NativeAuditEvent = {
  id: 'audit-1',
  source: 'JSON',
  eventType: 'kyuubi_operation',
  eventTime: 1_700_000_001_050,
  createTime: 1_700_000_000_000,
  startTime: 1_700_000_000_050,
  completeTime: 1_700_000_001_050,
  user: 'alice',
  status: 'FINISHED_STATE',
  statement: 'select 1',
  sessionId: 'session-1',
  operationId: 'op-1',
  clientIp: '127.0.0.1',
  datasourceLabel: '',
  engineType: 'JDBC',
  duration: 1000,
  error: '',
  rawJson: '{}'
}

beforeEach(() => {
  vi.mocked(auditApi.getNativeAuditActivities).mockResolvedValue({
    records: [record],
    page: 1,
    pageSize: 20,
    total: 1,
    auditEnabled: true,
    source: 'JSON',
    message: 'active'
  })
  vi.mocked(auditApi.getNativeAuditConfig).mockResolvedValue({
    enabled: true,
    mode: 'JSON',
    jsonPath: 'file:///tmp/events',
    retentionDays: 7,
    kafka: {} as auditApi.AuditKafkaConfig,
    passwordConfigured: false,
    truststorePasswordConfigured: false,
    updatedAt: 0,
    healthy: true,
    message: 'active'
  })
  vi.mocked(auditApi.getNativeAuditEvents).mockResolvedValue({
    records: [auditEvent],
    total: 1,
    generatedAt: 1_700_000_001_050,
    source: 'JSON',
    message: 'active'
  })
})

let activeWrapper: any

afterEach(() => {
  activeWrapper?.unmount()
  activeWrapper = undefined
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

function mountPage() {
  const router = createRouter({ history: createWebHistory(), routes: [] })
  activeWrapper = shallowMount(SqlRecord, {
    global: {
      plugins: [createI18n(), router, getStore(), ElementPlus]
    }
  })
  return activeWrapper
}

test('loads and renders SQL execution records', async () => {
  const wrapper = mountPage()
  await flushPromises()

  expect(auditApi.getNativeAuditActivities).toHaveBeenCalledWith({
    page: 1,
    pageSize: 20,
    keyword: undefined,
    user: undefined,
    eventType: undefined,
    status: undefined,
    from: undefined,
    to: undefined
  })
  expect(wrapper.text()).toContain('Query & audit')
  expect((wrapper.vm as any).records).toEqual([record])
  expect((wrapper.vm as any).total).toBe(1)
  expect((wrapper.vm as any).formatTime(record.createTime)).toMatch(
    /\.\d{3}$/
  )
})

test('applies filters from the search action', async () => {
  const wrapper = mountPage()
  await flushPromises()

  const pageVm = wrapper.vm as any
  pageVm.filters.keyword = 'select'
  pageVm.filters.user = 'alice'
  pageVm.filters.sessionId = 'session-1'
  pageVm.filters.engineType = 'JDBC'
  pageVm.filters.state = 'ERROR_STATE'
  await pageVm.search()

  expect(auditApi.getNativeAuditActivities).toHaveBeenLastCalledWith({
    page: 1,
    pageSize: 20,
    keyword: 'select',
    user: 'alice',
    eventType: undefined,
    status: 'ERROR_STATE',
    from: undefined,
    to: undefined
  })
})

test('auto refreshes records while keeping the active filters', async () => {
  vi.useFakeTimers()
  const wrapper = mountPage()
  await flushPromises()

  const pageVm = wrapper.vm as any
  pageVm.filters.keyword = 'select'
  vi.mocked(auditApi.getNativeAuditActivities).mockResolvedValue({
    records: [{ ...record, state: 'RUNNING_STATE' }],
    page: 1,
    pageSize: 20,
    total: 1,
    auditEnabled: true,
    source: 'JSON',
    message: 'active'
  })

  await vi.advanceTimersByTimeAsync(30_000)
  await flushPromises()

  expect(auditApi.getNativeAuditActivities).toHaveBeenLastCalledWith({
    page: 1,
    pageSize: 20,
    keyword: 'select',
    user: undefined,
    eventType: undefined,
    status: undefined,
    from: undefined,
    to: undefined
  })
  expect(pageVm.filters.keyword).toBe('select')
  expect(pageVm.records[0].state).toBe('RUNNING_STATE')
})

test('keeps existing records when a refresh fails', async () => {
  const wrapper = mountPage()
  await flushPromises()

  vi.mocked(auditApi.getNativeAuditActivities).mockRejectedValueOnce(
    new Error('temporary unavailable')
  )
  await (wrapper.vm as any).loadRecords()

  expect((wrapper.vm as any).records).toEqual([record])
  expect((wrapper.vm as any).total).toBe(1)
  expect((wrapper.vm as any).loadError).toContain('temporary unavailable')
})

test('exports the active filters with the bounded CSV page', async () => {
  const createObjectURL = vi.fn().mockReturnValue('blob:sql-records')
  const revokeObjectURL = vi.fn()
  vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })
  const clickSpy = vi
    .spyOn(HTMLAnchorElement.prototype, 'click')
    .mockImplementation(() => undefined)
  const wrapper = mountPage()
  await flushPromises()

  const pageVm = wrapper.vm as any
  pageVm.filters.keyword = 'select'
  await pageVm.exportRecords()

  expect(auditApi.getNativeAuditActivities).toHaveBeenLastCalledWith({
    page: 1,
    pageSize: 200,
    keyword: 'select',
    user: undefined,
    eventType: undefined,
    status: undefined,
    from: undefined,
    to: undefined
  })
  expect(createObjectURL).toHaveBeenCalledOnce()
  expect(clickSpy).toHaveBeenCalledOnce()
  expect(revokeObjectURL).toHaveBeenCalledWith('blob:sql-records')
  expect(createObjectURL.mock.calls[0][0].type).toBe('text/csv;charset=utf-8')
  expect(createObjectURL.mock.calls[0][0].size).toBeGreaterThan(0)
})

test('shows diagnostics and timing details for failed records', async () => {
  const failedRecord = {
    ...record,
    state: 'ERROR_STATE',
    completeTime: 1_700_000_001_250,
    duration: 1000,
    error: 'syntax error near FROM'
  }
  vi.mocked(auditApi.getNativeAuditActivities).mockResolvedValueOnce({
    records: [failedRecord],
    page: 1,
    pageSize: 20,
    total: 1,
    auditEnabled: true,
    source: 'JSON',
    message: 'active'
  })
  const wrapper = mountPage()
  await flushPromises()

  await (wrapper.vm as any).showDetail(failedRecord)
  await flushPromises()

  expect((wrapper.vm as any).selectedRecord).toEqual(failedRecord)
  expect((wrapper.vm as any).selectedRecord.error).toBe(
    'syntax error near FROM'
  )
  expect((wrapper.vm as any).diagnosisLabel('RUNNING_STATE')).toBe(
    'In progress'
  )
  expect((wrapper.vm as any).totalDuration(failedRecord)).toBe(1250)
  expect((wrapper.vm as any).durationHint(failedRecord)).toContain('Completed')
  expect(auditApi.getNativeAuditEvents).toHaveBeenCalledWith({
    eventType: 'kyuubi_operation',
    operationId: 'op-1',
    sessionId: 'session-1',
    from: 1_699_999_940_000,
    to: 1_700_000_061_250,
    limit: 100
  })
  expect((wrapper.vm as any).auditTrail).toEqual([auditEvent])
})
