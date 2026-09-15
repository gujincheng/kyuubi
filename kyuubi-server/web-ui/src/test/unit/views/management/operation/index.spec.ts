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

import OperationManagement from '@/views/management/operation/index.vue'
import * as operationApi from '@/api/operation'
import { createI18n, getStore } from '@/test/unit/utils'

vi.mock('@/api/operation', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/operation')>('@/api/operation')
  return { ...actual, getAllOperations: vi.fn(), actionOnOperation: vi.fn() }
})

const operation: operationApi.OperationData = {
  identifier: 'operation-1',
  statement: 'select 1',
  state: 'RUNNING_STATE',
  createTime: 1_700_000_000_000,
  startTime: 1_700_000_000_100,
  sessionId: 'session-1',
  sessionUser: 'alice',
  sessionType: 'INTERACTIVE'
}

let activeWrapper: any

beforeEach(() => {
  vi.mocked(operationApi.getAllOperations).mockResolvedValue([operation])
  vi.mocked(operationApi.actionOnOperation).mockResolvedValue(undefined)
})

afterEach(() => {
  activeWrapper?.unmount()
  activeWrapper = undefined
  vi.useRealTimers()
  vi.clearAllMocks()
})

function mountPage() {
  const router = createRouter({ history: createWebHistory(), routes: [] })
  activeWrapper = shallowMount(OperationManagement, {
    global: {
      plugins: [createI18n(), router, getStore(), ElementPlus]
    }
  })
  return activeWrapper
}

test('loads operations and derives state counters', async () => {
  const wrapper = mountPage()
  await flushPromises()

  expect(operationApi.getAllOperations).toHaveBeenCalledWith({
    users: undefined,
    sessionHandle: undefined,
    sessionType: undefined
  })
  expect((wrapper.vm as any).records).toEqual([operation])
  expect((wrapper.vm as any).activeCount).toBe(1)
  expect((wrapper.vm as any).runningCount).toBe(1)
  expect((wrapper.vm as any).failedCount).toBe(0)
})

test('passes filters and applies the selected state', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.filters.user = 'alice'
  pageVm.filters.sessionHandle = 'session-1'
  pageVm.filters.sessionType = 'INTERACTIVE'
  pageVm.filters.state = 'ERROR_STATE'
  vi.mocked(operationApi.getAllOperations).mockResolvedValue([
    operation,
    {
      ...operation,
      identifier: 'operation-2',
      state: 'ERROR_STATE',
      exception: 'bad sql'
    }
  ])
  await pageVm.loadOperations()

  expect(operationApi.getAllOperations).toHaveBeenLastCalledWith({
    users: 'alice',
    sessionHandle: 'session-1',
    sessionType: 'INTERACTIVE'
  })
  expect(pageVm.records).toHaveLength(1)
  expect(pageVm.records[0].state).toBe('ERROR_STATE')
})

test('formats operation state and duration details', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  expect(pageVm.stateLabel('RUNNING_STATE')).toBe('Running')
  expect(pageVm.stateLabel('PENDING_STATE')).toBe('Pending')
  expect(
    pageVm.operationDuration({ ...operation, completeTime: 1_700_000_001_100 })
  ).toBe('1 sec')
  expect(pageVm.operationDuration({ ...operation, startTime: undefined })).toBe(
    '-'
  )
  expect(pageVm.isTerminalState('ERROR_STATE')).toBe(true)
  expect(pageVm.isTerminalState('RUNNING_STATE')).toBe(false)
})

test('opens details and applies cancel action before reloading', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.showDetail(operation)
  expect(pageVm.selectedOperation).toEqual(operation)
  expect(pageVm.detailVisible).toBe(true)

  await pageVm.operate(operation, 'CANCEL')
  expect(operationApi.actionOnOperation).toHaveBeenCalledWith('operation-1', {
    action: 'CANCEL'
  })
  expect(operationApi.getAllOperations).toHaveBeenCalledTimes(2)
})

test('refreshes operations silently at the configured interval', async () => {
  vi.useFakeTimers()
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.refreshSeconds = 10
  await vi.advanceTimersByTimeAsync(10_000)
  await flushPromises()

  expect(operationApi.getAllOperations).toHaveBeenCalledTimes(2)
})
