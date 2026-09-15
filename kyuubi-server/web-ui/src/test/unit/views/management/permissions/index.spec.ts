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

import PermissionsManagement from '@/views/management/permissions/index.vue'
import * as permissionsApi from '@/api/permissions'
import { createI18n, getStore } from '@/test/unit/utils'

vi.mock('@/api/permissions', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/permissions')>(
      '@/api/permissions'
    )
  return {
    ...actual,
    getAdminPermissions: vi.fn(),
    updateAdminPermissions: vi.fn()
  }
})

const snapshot: permissionsApi.AdminPermissions = {
  currentUser: 'admin',
  roles: [
    {
      name: 'viewer',
      label: '只读管理员',
      description: '查看运行数据',
      permissions: [{ resource: 'overview', operations: ['read'] }]
    },
    {
      name: 'platform-admin',
      label: '平台管理员',
      description: '全部管理权限',
      permissions: [
        { resource: 'overview', operations: ['read', 'manage'] },
        { resource: 'permissions', operations: ['read', 'manage'] }
      ]
    }
  ],
  assignments: [{ user: 'alice', role: 'viewer' }]
}

let activeWrapper: any

beforeEach(() => {
  vi.mocked(permissionsApi.getAdminPermissions).mockResolvedValue(snapshot)
  vi.mocked(permissionsApi.updateAdminPermissions).mockResolvedValue(snapshot)
})

afterEach(() => {
  activeWrapper?.unmount()
  activeWrapper = undefined
  vi.clearAllMocks()
})

function mountPage() {
  const router = createRouter({ history: createWebHistory(), routes: [] })
  activeWrapper = shallowMount(PermissionsManagement, {
    global: {
      plugins: [createI18n(), router, getStore(), ElementPlus]
    }
  })
  return activeWrapper
}

test('loads permission roles and assignments', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  expect(permissionsApi.getAdminPermissions).toHaveBeenCalledOnce()
  expect(pageVm.permissions).toEqual(snapshot)
  expect(pageVm.assignments).toHaveLength(1)
  expect(pageVm.roleAssignmentCount('viewer')).toBe(1)
})

test('saves a new user role while preserving existing assignments', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.openEditor()
  pageVm.editorUser = 'bob'
  pageVm.editorRole = 'platform-admin'
  await pageVm.saveAssignment()

  expect(permissionsApi.updateAdminPermissions).toHaveBeenCalledWith([
    { user: 'alice', role: 'viewer' },
    { user: 'bob', role: 'platform-admin' }
  ])
})

test('replaces an existing user role instead of creating a duplicate', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.openEditor(snapshot.assignments[0])
  pageVm.editorRole = 'platform-admin'
  await pageVm.saveAssignment()

  expect(permissionsApi.updateAdminPermissions).toHaveBeenCalledWith([
    { user: 'alice', role: 'platform-admin' }
  ])
})

test('keeps the previous snapshot when loading fails', async () => {
  const wrapper = mountPage()
  await flushPromises()
  vi.mocked(permissionsApi.getAdminPermissions).mockRejectedValueOnce(
    new Error('temporary unavailable')
  )

  await (wrapper.vm as any).loadPermissions()
  expect((wrapper.vm as any).permissions).toEqual(snapshot)
  expect((wrapper.vm as any).error).toContain('temporary unavailable')
})
