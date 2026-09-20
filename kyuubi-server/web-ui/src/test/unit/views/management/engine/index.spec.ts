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

import EngineManagement from '@/views/management/engine/index.vue'
import * as engineApi from '@/api/engine'
import * as serverApi from '@/api/server'
import { EngineData } from '@/api/engine/types'
import { createI18n, getStore } from '@/test/unit/utils'

vi.mock('@/api/engine', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/engine')>('@/api/engine')
  return { ...actual, getAllEngines: vi.fn(), deleteEngine: vi.fn() }
})
vi.mock('@/api/server', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/server')>('@/api/server')
  return { ...actual, getWebUIConfig: vi.fn() }
})

const engine: EngineData = {
  version: '1.12.0',
  user: 'anonymous',
  engineType: 'SPARK_SQL',
  sharelevel: 'USER',
  instance: '127.0.0.1:10000',
  namespace: 'kyuubi/engine',
  attributes: {
    'kyuubi.engine.id': 'engine-1',
    'kyuubi.engine.url': 'http://127.0.0.1:4040'
  }
}

let activeWrapper: any

beforeEach(() => {
  vi.mocked(engineApi.getAllEngines).mockResolvedValue([engine])
  vi.mocked(engineApi.deleteEngine).mockResolvedValue(undefined)
  vi.mocked(serverApi.getWebUIConfig).mockResolvedValue({
    engineUIProxyEnabled: false
  })
})

afterEach(() => {
  activeWrapper?.unmount()
  activeWrapper = undefined
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

function mountPage() {
  const router = createRouter({ history: createWebHistory(), routes: [] })
  activeWrapper = shallowMount(EngineManagement, {
    global: {
      plugins: [createI18n(), router, getStore(), ElementPlus]
    }
  })
  return activeWrapper
}

test('loads engines and derives runtime summary values', async () => {
  const wrapper = mountPage()
  await flushPromises()

  expect(engineApi.getAllEngines).toHaveBeenCalledWith({
    type: 'SPARK_SQL',
    sharelevel: 'USER',
    'hive.server2.proxy.user': 'anonymous'
  })
  expect((wrapper.vm as any).records).toEqual([engine])
  expect((wrapper.vm as any).onlineCount).toBe(1)
  expect((wrapper.vm as any).engineTypes).toBe(1)
  expect((wrapper.vm as any).engineUsers).toBe(1)
})

test('resets filters and refreshes silently at the configured interval', async () => {
  vi.useFakeTimers()
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.searchParam.type = 'JDBC'
  pageVm.searchParam.sharelevel = 'CONNECTION'
  pageVm.searchParam['hive.server2.proxy.user'] = 'bob'
  await pageVm.resetFilters()
  expect(pageVm.searchParam.type).toBe('SPARK_SQL')
  expect(engineApi.getAllEngines).toHaveBeenLastCalledWith({
    type: 'SPARK_SQL',
    sharelevel: 'USER',
    'hive.server2.proxy.user': 'anonymous'
  })

  pageVm.refreshSeconds = 10
  await vi.advanceTimersByTimeAsync(10_000)
  await flushPromises()
  expect(engineApi.getAllEngines).toHaveBeenCalledTimes(3)
})

test('formats engine identity, status, and UI address', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  expect(pageVm.engineId(engine)).toBe('engine-1')
  expect(pageVm.engineUrl(engine)).toBe('http://127.0.0.1:4040')
  expect(pageVm.statusLabel(engine)).toBe('Online')
  expect(pageVm.statusLabel({ ...engine, instance: '' })).toBe('Unknown')
})

test('removes an engine and reloads the current query', async () => {
  const wrapper = mountPage()
  await flushPromises()
  await (wrapper.vm as any).removeEngine(engine)

  expect(engineApi.deleteEngine).toHaveBeenCalledWith({
    type: 'SPARK_SQL',
    sharelevel: 'USER',
    'hive.server2.proxy.user': 'anonymous',
    subdomain: undefined
  })
  expect(engineApi.getAllEngines).toHaveBeenCalledTimes(2)
})
