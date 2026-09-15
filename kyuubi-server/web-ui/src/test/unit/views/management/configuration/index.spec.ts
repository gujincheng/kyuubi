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

import ConfigurationManagement from '@/views/management/configuration/index.vue'
import * as configurationApi from '@/api/configuration'
import { createI18n, getStore } from '@/test/unit/utils'

vi.mock('@/api/configuration', async () => {
  const actual = await vi.importActual<typeof import('@/api/configuration')>(
    '@/api/configuration'
  )
  return {
    ...actual,
    getAdminConfiguration: vi.fn(),
    refreshConfiguration: vi.fn()
  }
})

const configuration: configurationApi.AdminConfiguration = {
  currentUser: 'admin',
  securityEnabled: true,
  authenticationMethods: ['NONE'],
  administrators: ['admin'],
  categories: [
    { name: 'kyuubi.server', entryCount: 2 },
    { name: 'spark', entryCount: 1 }
  ],
  entries: [
    {
      key: 'kyuubi.server.frontend.bind.host',
      value: '127.0.0.1',
      sensitive: false,
      category: 'kyuubi.server'
    },
    {
      key: 'kyuubi.server.auth.password',
      value: '******',
      sensitive: true,
      category: 'kyuubi.server'
    },
    {
      key: 'spark.master',
      value: 'local[*]',
      sensitive: false,
      category: 'spark'
    }
  ],
  reloads: [
    {
      id: 'hadoop_conf',
      label: 'Hadoop configuration',
      endpoint: 'admin/refresh/hadoop_conf'
    }
  ]
}

let activeWrapper: any

beforeEach(() => {
  vi.mocked(configurationApi.getAdminConfiguration).mockResolvedValue(
    configuration
  )
  vi.mocked(configurationApi.refreshConfiguration).mockResolvedValue(undefined)
})

afterEach(() => {
  activeWrapper?.unmount()
  activeWrapper = undefined
  vi.clearAllMocks()
})

function mountPage() {
  const router = createRouter({ history: createWebHistory(), routes: [] })
  activeWrapper = shallowMount(ConfigurationManagement, {
    global: {
      plugins: [createI18n(), router, getStore(), ElementPlus]
    }
  })
  return activeWrapper
}

test('loads configuration snapshot and exposes security metadata', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  expect(configurationApi.getAdminConfiguration).toHaveBeenCalledOnce()
  expect(pageVm.configuration).toEqual(configuration)
  expect(pageVm.configuration.securityEnabled).toBe(true)
  expect(pageVm.configuration.entries).toHaveLength(3)
})

test('reloads a supported configuration domain and refreshes the snapshot', async () => {
  const wrapper = mountPage()
  await flushPromises()

  await (wrapper.vm as any).reload('hadoop_conf', 'admin/refresh/hadoop_conf')
  expect(configurationApi.refreshConfiguration).toHaveBeenCalledWith(
    'admin/refresh/hadoop_conf'
  )
  expect(configurationApi.getAdminConfiguration).toHaveBeenCalledTimes(2)
})

test('filters configuration entries by category and keyword', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.category = 'spark'
  expect(pageVm.filteredEntries).toHaveLength(1)
  pageVm.category = 'all'
  pageVm.query = 'password'
  expect(pageVm.filteredEntries[0].sensitive).toBe(true)
})

test('preserves the previous snapshot when reloading fails', async () => {
  const wrapper = mountPage()
  await flushPromises()
  vi.mocked(configurationApi.getAdminConfiguration).mockRejectedValueOnce(
    new Error('temporary unavailable')
  )

  await (wrapper.vm as any).loadConfiguration()
  expect((wrapper.vm as any).configuration).toEqual(configuration)
  expect((wrapper.vm as any).error).toContain('temporary unavailable')
})
