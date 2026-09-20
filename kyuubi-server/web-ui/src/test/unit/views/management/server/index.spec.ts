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

import ServerManagement from '@/views/management/server/index.vue'
import * as serverApi from '@/api/server'
import { ServerData } from '@/api/server/types'
import { createI18n, getStore } from '@/test/unit/utils'

vi.mock('@/api/server', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/server')>('@/api/server')
  return { ...actual, getAllServer: vi.fn() }
})

const server: ServerData = {
  host: '127.0.0.1',
  instance: '127.0.0.1:10009',
  namespace: '/kyuubi',
  nodeName: 'server-1',
  port: 10009,
  status: 'Running',
  attributes: { version: '1.12.0', sequence: '0000000000' }
}

let activeWrapper: any

beforeEach(() => {
  vi.mocked(serverApi.getAllServer).mockResolvedValue([server])
})

afterEach(() => {
  activeWrapper?.unmount()
  activeWrapper = undefined
  vi.useRealTimers()
  vi.clearAllMocks()
})

function mountPage() {
  const router = createRouter({ history: createWebHistory(), routes: [] })
  activeWrapper = shallowMount(ServerManagement, {
    global: {
      plugins: [createI18n(), router, getStore(), ElementPlus]
    }
  })
  return activeWrapper
}

test('loads servers and derives health summary values', async () => {
  const wrapper = mountPage()
  await flushPromises()

  expect(serverApi.getAllServer).toHaveBeenCalledOnce()
  expect((wrapper.vm as any).records).toEqual([server])
  expect((wrapper.vm as any).runningCount).toBe(1)
  expect((wrapper.vm as any).versions).toBe(1)
  expect((wrapper.vm as any).hosts).toBe(1)
})

test('filters hosts and status without changing the server response', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.hostFilter = '127.0.0.1'
  expect(pageVm.filteredRecords).toHaveLength(1)
  pageVm.hostFilter = 'missing'
  expect(pageVm.filteredRecords).toHaveLength(0)
  pageVm.hostFilter = ''
  pageVm.statusFilter = 'UNKNOWN'
  expect(pageVm.filteredRecords).toHaveLength(0)
})

test('shows status and opens server details', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  expect(pageVm.statusLabel('Running')).toBe('Running')
  expect(pageVm.statusLabel('Stopped')).toBe('Unknown')
  pageVm.showDetail(server)
  expect(pageVm.selectedServer).toEqual(server)
  expect(pageVm.detailVisible).toBe(true)
})

test('refreshes server discovery at the configured interval', async () => {
  vi.useFakeTimers()
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.refreshSeconds = 10
  await vi.advanceTimersByTimeAsync(10_000)
  await flushPromises()

  expect(serverApi.getAllServer).toHaveBeenCalledTimes(2)
})
