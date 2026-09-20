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

import PolicyManagement from '@/views/management/policy/index.vue'
import * as policyApi from '@/api/policy'
import { createI18n, getStore } from '@/test/unit/utils'

vi.mock('@/api/policy', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/policy')>('@/api/policy')
  return {
    ...actual,
    getAdminPolicies: vi.fn(),
    refreshPolicy: vi.fn(),
    updateAdminPolicies: vi.fn()
  }
})

const policies: policyApi.AdminPolicies = {
  profiles: [
    {
      name: 'prod',
      fileName: 'kyuubi-session-prod.conf',
      propertyCount: 2,
      modifiedTime: 1_700_000_000_000,
      properties: {
        'spark.app.name': 'prod',
        'spark.sql.shuffle.partitions': '4'
      }
    }
  ],
  userDefaults: [
    {
      user: 'alice',
      properties: { 'kyuubi.engine.type': 'JDBC', password: '******' }
    }
  ],
  access: {
    unlimitedUsers: ['admin'],
    denyUsers: ['blocked'],
    denyIps: ['10.0.0.1']
  }
}

let activeWrapper: any

beforeEach(() => {
  vi.mocked(policyApi.getAdminPolicies).mockResolvedValue(policies)
  vi.mocked(policyApi.refreshPolicy).mockResolvedValue(undefined)
  vi.mocked(policyApi.updateAdminPolicies).mockResolvedValue(policies)
})

afterEach(() => {
  activeWrapper?.unmount()
  activeWrapper = undefined
  vi.clearAllMocks()
})

function mountPage() {
  const router = createRouter({ history: createWebHistory(), routes: [] })
  activeWrapper = shallowMount(PolicyManagement, {
    global: {
      plugins: [createI18n(), router, getStore(), ElementPlus]
    }
  })
  return activeWrapper
}

test('loads policy snapshot and exposes all policy domains', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  expect(policyApi.getAdminPolicies).toHaveBeenCalledOnce()
  expect(pageVm.policies).toEqual(policies)
  expect(pageVm.policies.profiles).toHaveLength(1)
  expect(pageVm.policies.userDefaults[0].properties.password).toBe('******')
})

test('reloads one policy domain and refreshes the view', async () => {
  const wrapper = mountPage()
  await flushPromises()

  await (wrapper.vm as any).refreshDomain('deny_users')
  expect(policyApi.refreshPolicy).toHaveBeenCalledWith('deny_users')
  expect(policyApi.getAdminPolicies).toHaveBeenCalledTimes(2)
})

test('reloads all supported policy domains', async () => {
  const wrapper = mountPage()
  await flushPromises()

  await (wrapper.vm as any).refreshAll()
  expect(policyApi.refreshPolicy).toHaveBeenCalledTimes(4)
  expect(policyApi.refreshPolicy).toHaveBeenCalledWith('user_defaults_conf')
  expect(policyApi.refreshPolicy).toHaveBeenCalledWith('unlimited_users')
  expect(policyApi.refreshPolicy).toHaveBeenCalledWith('deny_users')
  expect(policyApi.refreshPolicy).toHaveBeenCalledWith('deny_ips')
  expect(policyApi.getAdminPolicies).toHaveBeenCalledTimes(2)
})

test('formats profile timestamps and preserves old data on load failure', async () => {
  const wrapper = mountPage()
  await flushPromises()
  vi.mocked(policyApi.getAdminPolicies).mockRejectedValueOnce(
    new Error('temporary unavailable')
  )

  await (wrapper.vm as any).loadPolicies()
  expect((wrapper.vm as any).policies).toEqual(policies)
  expect((wrapper.vm as any).loadError).toContain('temporary unavailable')
  expect((wrapper.vm as any).formatTime(1_700_000_000_000)).toContain('2023')
})

test('saves access policies and user defaults through the admin update API', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.newAccessValues['deny-users'] = 'new-user'
  pageVm.addAccessValue('deny-users')
  await pageVm.saveAccessPolicies()
  expect(policyApi.updateAdminPolicies).toHaveBeenCalledWith({
    access: expect.objectContaining({ denyUsers: ['blocked', 'new-user'] })
  })

  pageVm.openUserDefaultsEditor()
  pageVm.editorName = 'bob'
  pageVm.editorPropertiesText = 'spark.app.name=e2e'
  await pageVm.saveEditor()
  expect(policyApi.updateAdminPolicies).toHaveBeenCalledWith({
    userDefaults: [{ user: 'bob', properties: { 'spark.app.name': 'e2e' } }]
  })
})
