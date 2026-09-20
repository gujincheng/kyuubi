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

import SessionManagement from '@/views/management/session/index.vue'
import * as sessionApi from '@/api/session'
import { createI18n, getStore } from '@/test/unit/utils'

vi.mock('@/api/session', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/session')>('@/api/session')
  return { ...actual, getAllSessions: vi.fn(), closeSession: vi.fn() }
})

const session: sessionApi.SessionData = {
  identifier: 'session-1',
  user: 'alice',
  sessionType: 'INTERACTIVE',
  engineId: 'engine-1',
  engineName: 'spark',
  ipAddr: '127.0.0.1',
  createTime: 1_700_000_000_000,
  duration: 12_000,
  idleTime: 1_000,
  totalOperations: 3
}

let activeWrapper: any

beforeEach(() => {
  vi.mocked(sessionApi.getAllSessions).mockResolvedValue([session])
  vi.mocked(sessionApi.closeSession).mockResolvedValue(undefined)
})

afterEach(() => {
  activeWrapper?.unmount()
  activeWrapper = undefined
  vi.useRealTimers()
  vi.clearAllMocks()
})

function mountPage() {
  const router = createRouter({ history: createWebHistory(), routes: [] })
  activeWrapper = shallowMount(SessionManagement, {
    global: {
      plugins: [createI18n(), router, getStore(), ElementPlus]
    }
  })
  return activeWrapper
}

test('loads sessions and derives management summary values', async () => {
  const wrapper = mountPage()
  await flushPromises()

  expect(sessionApi.getAllSessions).toHaveBeenCalledWith({
    users: undefined,
    sessionType: undefined
  })
  expect((wrapper.vm as any).records).toEqual([session])
  expect((wrapper.vm as any).activeUsers).toBe(1)
  expect((wrapper.vm as any).boundEngines).toBe(1)
  expect((wrapper.vm as any).totalOperations).toBe(3)
})

test('passes server-side filters and resets them', async () => {
  const wrapper = mountPage()
  await flushPromises()

  const pageVm = wrapper.vm as any
  pageVm.filters.user = 'alice'
  pageVm.filters.sessionType = 'BATCH'
  await pageVm.loadSessions()
  expect(sessionApi.getAllSessions).toHaveBeenLastCalledWith({
    users: 'alice',
    sessionType: 'BATCH'
  })

  await pageVm.resetFilters()
  expect(pageVm.filters.user).toBe('')
  expect(pageVm.filters.sessionType).toBe('')
  expect(sessionApi.getAllSessions).toHaveBeenLastCalledWith({
    users: undefined,
    sessionType: undefined
  })
})

test('classifies active, idle, and failed sessions', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  expect(pageVm.statusLabel(session)).toBe('Active')
  expect(pageVm.statusLabel({ ...session, idleTime: 5 * 60 * 1000 + 1 })).toBe(
    'Idle'
  )
  expect(pageVm.statusLabel({ ...session, exception: 'engine failed' })).toBe(
    'Error'
  )
  expect(pageVm.formatDuration(12_000)).toBe('12 sec')
  expect(pageVm.formatDuration(undefined)).toBe('-')
})

test('opens detail and closes a live session before reloading', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.showDetail(session)
  expect(pageVm.selectedSession).toEqual(session)
  expect(pageVm.detailVisible).toBe(true)

  await pageVm.closeSession(session)
  expect(sessionApi.closeSession).toHaveBeenCalledWith('session-1')
  expect(sessionApi.getAllSessions).toHaveBeenCalledTimes(2)
})

test('refreshes silently at the configured interval', async () => {
  vi.useFakeTimers()
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.refreshSeconds = 10
  await vi.advanceTimersByTimeAsync(10_000)
  await flushPromises()

  expect(sessionApi.getAllSessions).toHaveBeenCalledTimes(2)
})
