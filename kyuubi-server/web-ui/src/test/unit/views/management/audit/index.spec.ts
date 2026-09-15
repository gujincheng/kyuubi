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

import AuditManagement from '@/views/management/audit/index.vue'
import * as auditApi from '@/api/audit'
import { createI18n, getStore } from '@/test/unit/utils'

vi.mock('@/api/audit', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/audit')>('@/api/audit')
  return { ...actual, getAuditRecords: vi.fn() }
})

const auditPage: auditApi.AuditRecordPage = {
  records: [
    {
      timestamp: 1_700_000_000_000,
      user: 'admin',
      authType: 'BASIC',
      ip: '127.0.0.1',
      proxyIp: '',
      forwardedFor: [],
      method: 'GET',
      uri: '/api/v1/admin/audit',
      query: 'password=******',
      protocol: 'HTTP/1.1',
      status: 200
    },
    {
      timestamp: 1_700_000_001_000,
      user: 'admin',
      authType: 'BASIC',
      ip: '127.0.0.1',
      proxyIp: '',
      forwardedFor: [],
      method: 'POST',
      uri: '/api/v1/admin/refresh/hadoop_conf',
      protocol: 'HTTP/1.1',
      status: 500
    }
  ],
  total: 2,
  generatedAt: 1_700_000_002_000
}

let activeWrapper: any

beforeEach(() => {
  vi.mocked(auditApi.getAuditRecords).mockResolvedValue(auditPage)
})

afterEach(() => {
  activeWrapper?.unmount()
  activeWrapper = undefined
  vi.clearAllMocks()
})

function mountPage() {
  const router = createRouter({ history: createWebHistory(), routes: [] })
  activeWrapper = shallowMount(AuditManagement, {
    global: { plugins: [createI18n(), router, getStore(), ElementPlus] }
  })
  return activeWrapper
}

test('loads audit records and exposes the summary page data', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  expect(auditApi.getAuditRecords).toHaveBeenCalledOnce()
  expect(pageVm.records).toHaveLength(2)
  expect(pageVm.total).toBe(2)
})

test('applies user, method, status, and time filters', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.user = 'admin'
  pageVm.method = 'POST'
  pageVm.status = '500'
  pageVm.timeRange = [new Date(1_700_000_000_000), new Date(1_700_000_003_000)]
  await pageVm.loadAudit()

  expect(auditApi.getAuditRecords).toHaveBeenLastCalledWith({
    user: 'admin',
    method: 'POST',
    status: 500,
    from: 1_700_000_000_000,
    to: 1_700_000_003_000,
    limit: 100
  })
})

test('resets filters and reloads the audit page', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any
  pageVm.user = 'admin'
  pageVm.method = 'GET'
  pageVm.status = '200'
  pageVm.timeRange = [new Date(), new Date()]

  await pageVm.resetFilters()
  expect(pageVm.user).toBe('')
  expect(pageVm.method).toBe('')
  expect(pageVm.status).toBe('')
  expect(pageVm.timeRange).toBeNull()
  expect(auditApi.getAuditRecords).toHaveBeenCalledTimes(2)
})

test('preserves old data and exposes an error when loading fails', async () => {
  const wrapper = mountPage()
  await flushPromises()
  vi.mocked(auditApi.getAuditRecords).mockRejectedValueOnce(
    new Error('temporary unavailable')
  )

  await (wrapper.vm as any).loadAudit()
  expect((wrapper.vm as any).records).toEqual(auditPage.records)
  expect((wrapper.vm as any).loadError).toContain('temporary unavailable')
})
