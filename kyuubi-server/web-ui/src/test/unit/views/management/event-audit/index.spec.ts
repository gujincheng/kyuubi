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

import { flushPromises, mount, shallowMount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'

import EventAudit from '@/views/management/event-audit/index.vue'
import * as auditApi from '@/api/audit'

vi.mock('@/api/audit', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/audit')>('@/api/audit')
  return {
    ...actual,
    getNativeAuditConfig: vi.fn(),
    getNativeAuditEvents: vi.fn(),
    testNativeAuditConfig: vi.fn(),
    updateNativeAuditConfig: vi.fn()
  }
})

const config: auditApi.ManagedAuditConfigView = {
  enabled: true,
  mode: 'JSON',
  jsonPath: 'file:///opt/kyuubi/data/events',
  retentionDays: 7,
  kafka: {
    bootstrapServers: '',
    topic: '',
    securityProtocol: 'PLAINTEXT',
    saslMechanism: 'PLAIN',
    username: '',
    password: '',
    truststoreLocation: '',
    truststorePassword: ''
  },
  passwordConfigured: false,
  truststorePasswordConfigured: false,
  updatedAt: 1_700_000_000_000,
  healthy: true,
  message: 'JSON audit logging is active'
}

let wrapper: ReturnType<typeof shallowMount> | undefined

beforeEach(() => {
  vi.spyOn(Date, 'now').mockReturnValue(1_700_000_000_000)
  vi.mocked(auditApi.getNativeAuditConfig).mockResolvedValue(config)
  vi.mocked(auditApi.getNativeAuditEvents).mockResolvedValue({
    records: [],
    total: 0,
    generatedAt: 1_700_000_000_000,
    source: 'JSON',
    message: 'active'
  })
  vi.mocked(auditApi.testNativeAuditConfig).mockResolvedValue({
    success: true,
    message: 'available',
    checkedAt: 1_700_000_000_000
  })
  vi.mocked(auditApi.updateNativeAuditConfig).mockResolvedValue(config)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.clearAllMocks()
  vi.restoreAllMocks()
})

test('loads the active backend and its actual audit events', async () => {
  wrapper = shallowMount(EventAudit, { global: { plugins: [ElementPlus] } })
  await flushPromises()

  expect(auditApi.getNativeAuditConfig).toHaveBeenCalledOnce()
  expect(auditApi.getNativeAuditEvents).toHaveBeenCalledWith({
    eventType: undefined,
    user: undefined,
    status: undefined,
    from: 1_699_395_200_000,
    to: 1_700_000_000_000,
    limit: 200
  })
  expect((wrapper.vm as any).configView.mode).toBe('JSON')
  expect((wrapper.vm as any).statusClass).toBe('healthy')
})

test('reloads events when the time range changes', async () => {
  wrapper = mount(EventAudit, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  vi.mocked(auditApi.getNativeAuditEvents).mockClear()

  const radioGroup = wrapper.findComponent({ name: 'ElRadioGroup' })
  radioGroup.vm.$emit('update:modelValue', '24h')
  radioGroup.vm.$emit('change', '24h')
  await flushPromises()

  expect(auditApi.getNativeAuditEvents).toHaveBeenCalledWith({
    eventType: undefined,
    user: undefined,
    status: undefined,
    from: 1_699_913_600_000,
    to: 1_700_000_000_000,
    limit: 200
  })
})
