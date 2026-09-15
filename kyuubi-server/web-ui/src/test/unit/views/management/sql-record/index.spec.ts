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
import * as sqlRecordApi from '@/api/sql-record'
import { createI18n, getStore } from '@/test/unit/utils'

vi.mock('@/api/sql-record', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/sql-record')>('@/api/sql-record')
  return { ...actual, getSqlExecutionRecords: vi.fn() }
})

const record: sqlRecordApi.SqlExecutionRecord = {
  id: 'op-1',
  statement: 'select 1',
  statementSummary: 'select 1',
  user: 'alice',
  sessionId: 'session-1',
  engineType: 'JDBC',
  state: 'FINISHED_STATE',
  createTime: 1_700_000_000_000,
  startTime: 1_700_000_000_050,
  completeTime: 1_700_000_001_050,
  queueWaitTimeMs: 50,
  executionDurationMs: 1000,
  errorMessage: ''
}

beforeEach(() => {
  vi.mocked(sqlRecordApi.getSqlExecutionRecords).mockResolvedValue({
    records: [record],
    page: 1,
    pageSize: 20,
    total: 1
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

  expect(sqlRecordApi.getSqlExecutionRecords).toHaveBeenCalledWith({
    page: 1,
    pageSize: 20,
    keyword: undefined,
    user: undefined,
    sessionId: undefined,
    engineType: undefined,
    state: undefined,
    fromTime: undefined,
    toTime: undefined
  })
  expect(wrapper.text()).toContain('SQL execution records')
  expect((wrapper.vm as any).records).toEqual([record])
  expect((wrapper.vm as any).total).toBe(1)
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

  expect(sqlRecordApi.getSqlExecutionRecords).toHaveBeenLastCalledWith({
    page: 1,
    pageSize: 20,
    keyword: 'select',
    user: 'alice',
    sessionId: 'session-1',
    engineType: 'JDBC',
    state: 'ERROR_STATE',
    fromTime: undefined,
    toTime: undefined
  })
})

test('auto refreshes records while keeping the active filters', async () => {
  vi.useFakeTimers()
  const wrapper = mountPage()
  await flushPromises()

  const pageVm = wrapper.vm as any
  pageVm.filters.keyword = 'select'
  vi.mocked(sqlRecordApi.getSqlExecutionRecords).mockResolvedValue({
    records: [{ ...record, state: 'RUNNING_STATE' }],
    page: 1,
    pageSize: 20,
    total: 1
  })

  await vi.advanceTimersByTimeAsync(30_000)
  await flushPromises()

  expect(sqlRecordApi.getSqlExecutionRecords).toHaveBeenLastCalledWith({
    page: 1,
    pageSize: 20,
    keyword: 'select',
    user: undefined,
    sessionId: undefined,
    engineType: undefined,
    state: undefined,
    fromTime: undefined,
    toTime: undefined
  })
  expect(pageVm.filters.keyword).toBe('select')
  expect(pageVm.records[0].state).toBe('RUNNING_STATE')
})

test('keeps existing records when a refresh fails', async () => {
  const wrapper = mountPage()
  await flushPromises()

  vi.mocked(sqlRecordApi.getSqlExecutionRecords).mockRejectedValueOnce(
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

  expect(sqlRecordApi.getSqlExecutionRecords).toHaveBeenLastCalledWith({
    page: 1,
    pageSize: 200,
    keyword: 'select',
    user: undefined,
    sessionId: undefined,
    engineType: undefined,
    state: undefined,
    fromTime: undefined,
    toTime: undefined
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
    queueWaitTimeMs: 200,
    executionDurationMs: 1000,
    errorMessage: 'syntax error near FROM'
  }
  vi.mocked(sqlRecordApi.getSqlExecutionRecords).mockResolvedValueOnce({
    records: [failedRecord],
    page: 1,
    pageSize: 20,
    total: 1
  })
  const wrapper = mountPage()
  await flushPromises()

  await (wrapper.vm as any).showDetail(failedRecord)
  await flushPromises()

  expect((wrapper.vm as any).selectedRecord).toEqual(failedRecord)
  expect((wrapper.vm as any).selectedRecord.errorMessage).toBe(
    'syntax error near FROM'
  )
  expect((wrapper.vm as any).diagnosisLabel('RUNNING_STATE')).toBe(
    'In progress'
  )
  expect((wrapper.vm as any).totalDuration(failedRecord)).toBe(1250)
  expect((wrapper.vm as any).durationHint(failedRecord)).toContain('Completed')
})
