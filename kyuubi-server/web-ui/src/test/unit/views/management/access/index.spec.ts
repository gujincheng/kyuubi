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
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'

import AccessManagement from '@/views/management/access/index.vue'
import * as accessApi from '@/api/access'
import * as policyApi from '@/api/policy'
import * as permissionsApi from '@/api/permissions'
import { createI18n, getStore } from '@/test/unit/utils'

vi.mock('@/api/access', async () => ({
  ...(await vi.importActual<typeof import('@/api/access')>('@/api/access')),
  getAdminAccess: vi.fn(),
  saveIdentityProvider: vi.fn(),
  deleteIdentityProvider: vi.fn(),
  testIdentityProvider: vi.fn(),
  getIdentitySubjects: vi.fn(),
  saveIdentityBinding: vi.fn(),
  deleteIdentityBinding: vi.fn(),
  activateManagedAuthentication: vi.fn(),
  deactivateManagedAuthentication: vi.fn()
}))
vi.mock('@/api/policy', async () => ({
  ...(await vi.importActual<typeof import('@/api/policy')>('@/api/policy')),
  getAdminPolicies: vi.fn(),
  updateAdminPolicies: vi.fn()
}))
vi.mock('@/api/permissions', async () => ({
  ...(await vi.importActual<typeof import('@/api/permissions')>(
    '@/api/permissions'
  )),
  getAdminPermissions: vi.fn()
}))

const provider: accessApi.IdentityProvider = {
  id: 'corp-ldap',
  name: '公司 LDAP',
  providerType: 'LDAP',
  enabled: true,
  endpoint: 'ldap://127.0.0.1:10389',
  baseDn: 'dc=example,dc=com',
  bindDn: '',
  secretEnvironment: '',
  userFilter: '(objectClass=person)',
  groupFilter: '(objectClass=groupOfNames)',
  userNameAttribute: 'uid',
  displayNameAttribute: 'cn',
  emailAttribute: 'mail',
  groupNameAttribute: 'cn',
  userDnPattern: 'uid={0},ou=users,dc=example,dc=com',
  usersPath: '/users',
  groupsPath: '/groups',
  authenticationPath: '/authenticate',
  idField: 'id',
  userNameField: 'username',
  displayNameField: 'displayName',
  emailField: 'email',
  groupNameField: 'name',
  connectTimeoutMillis: 5000,
  readTimeoutMillis: 5000
}
const emptyAccess: accessApi.AdminAccess = {
  providers: [{ provider, secretConfigured: true }],
  bindings: [],
  authentication: {
    active: false,
    restartRequired: false,
    className: 'Managed',
    message: 'not active'
  }
}
const policies: policyApi.AdminPolicies = {
  profiles: [],
  userDefaults: [],
  access: { unlimitedUsers: [], denyUsers: [], denyIps: [] }
}
const permissions: permissionsApi.AdminPermissions = {
  currentUser: 'admin',
  roles: [
    {
      name: 'viewer',
      label: '只读管理员',
      description: '只读',
      permissions: [{ resource: 'overview', operations: ['read'] }]
    }
  ],
  assignments: []
}
const alice: accessApi.IdentitySubject = {
  providerId: provider.id,
  id: 'uid=alice,dc=example,dc=com',
  name: 'alice',
  displayName: 'Alice',
  email: 'alice@example.com',
  subjectType: 'USER',
  groups: ['analysts'],
  members: []
}
let wrapper: any

beforeEach(() => {
  vi.mocked(accessApi.getAdminAccess).mockResolvedValue(emptyAccess)
  vi.mocked(policyApi.getAdminPolicies).mockResolvedValue(policies)
  vi.mocked(permissionsApi.getAdminPermissions).mockResolvedValue(permissions)
  vi.mocked(accessApi.getIdentitySubjects).mockResolvedValue({
    providerId: provider.id,
    subjects: [alice],
    generatedAt: 1
  })
  vi.mocked(accessApi.saveIdentityProvider).mockResolvedValue(emptyAccess)
  vi.mocked(accessApi.saveIdentityBinding).mockResolvedValue({
    ...emptyAccess,
    bindings: [
      {
        id: 'binding-1',
        providerId: provider.id,
        subjectId: alice.id,
        subjectName: alice.name,
        subjectType: 'USER',
        access: 'ENABLED',
        role: 'viewer',
        profile: '',
        quotaExempt: false,
        userDefaults: {}
      }
    ]
  })
  vi.mocked(accessApi.activateManagedAuthentication).mockResolvedValue({
    active: false,
    restartRequired: true,
    className: 'ManagedIdentityAuthenticationProvider',
    message:
      'Configuration saved. Restart Kyuubi Server to activate managed authentication.'
  })
  vi.mocked(accessApi.deactivateManagedAuthentication).mockResolvedValue({
    active: false,
    restartRequired: true,
    className: 'ManagedIdentityAuthenticationProvider',
    message:
      'Configuration saved. Restart Kyuubi Server to deactivate managed authentication.'
  })
  vi.mocked(policyApi.updateAdminPolicies).mockResolvedValue(policies)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.clearAllMocks()
})

async function mountPage() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/management/access', component: AccessManagement }]
  })
  await router.push('/management/access')
  await router.isReady()
  wrapper = shallowMount(AccessManagement, {
    global: { plugins: [createI18n(), router, getStore(), ElementPlus] }
  })
  await flushPromises()
  return wrapper
}

test('loads identity, policy and permission snapshots together', async () => {
  const page = await mountPage()
  expect(accessApi.getAdminAccess).toHaveBeenCalledOnce()
  expect(policyApi.getAdminPolicies).toHaveBeenCalledOnce()
  expect(permissionsApi.getAdminPermissions).toHaveBeenCalledOnce()
  expect((page.vm as any).access.providers[0].provider.name).toBe('公司 LDAP')
  const testButton = page.find('.test-connection-button')
  expect(testButton.exists()).toBe(true)
  expect(testButton.attributes('icon')).toBe('Connection')
})

test('explains managed authentication scope and requires a bound platform administrator', async () => {
  const page = await mountPage()
  const vm = page.vm as any

  expect(page.text()).toContain('企业身份认证未启用')
  expect(vm.activationAdministrators).toHaveLength(0)

  await vm.activate()
  expect(accessApi.activateManagedAuthentication).not.toHaveBeenCalled()

  vm.access.bindings = [
    {
      id: 'binding-1',
      providerId: provider.id,
      subjectId: alice.id,
      subjectName: alice.name,
      subjectType: 'USER',
      access: 'ENABLED',
      role: 'platform-admin',
      profile: '',
      quotaExempt: false,
      userDefaults: {}
    }
  ]
  vm.openActivation()

  expect(vm.activationAdministrators).toHaveLength(1)
  await vm.activate()
  expect(accessApi.activateManagedAuthentication).toHaveBeenCalledWith()
  expect(vm.access.authentication.restartRequired).toBe(true)
})

test('allows administrators to deactivate managed authentication', async () => {
  const page = await mountPage()
  const vm = page.vm as any
  vm.access.authentication.active = true
  const confirm = vi
    .spyOn(ElMessageBox, 'confirm')
    .mockResolvedValue({ action: 'confirm' } as any)

  try {
    await vm.deactivate()

    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('LDAP/IAM 身份源、访问授权和会话模板不会被删除'),
      '停用企业身份认证',
      expect.objectContaining({ confirmButtonText: '确认停用' })
    )
    expect(accessApi.deactivateManagedAuthentication).toHaveBeenCalledWith()
    expect(vm.access.authentication.restartRequired).toBe(true)
  } finally {
    confirm.mockRestore()
  }
})

test('shows a visible error when identity provider test fails', async () => {
  const page = await mountPage()
  const errorSpy = vi.spyOn(ElMessage, 'error')
  vi.mocked(accessApi.testIdentityProvider).mockRejectedValueOnce(
    new Error('连接被拒绝')
  )

  try {
    await (page.vm as any).testProvider(provider.id)

    expect((page.vm as any).testResults[provider.id]).toEqual({
      ok: false,
      message: '连接被拒绝'
    })
    expect(page.find('.test-result').text()).toBe('连接被拒绝')
    expect(errorSpy).toHaveBeenCalledWith('公司 LDAP 连接失败：连接被拒绝')
  } finally {
    errorSpy.mockRestore()
  }
})

test('queries an external directory and creates a Kyuubi access binding', async () => {
  const page = await mountPage()
  const vm = page.vm as any
  await vm.querySubjects()
  expect(accessApi.getIdentitySubjects).toHaveBeenCalledWith(
    provider.id,
    'USER',
    ''
  )
  expect(vm.subjects[0].name).toBe('alice')

  vm.openBinding(alice)
  expect(vm.bindingDialog).toBe(true)
  vm.bindingForm.role = 'viewer'
  vm.bindingDefaults = [{ key: 'spark.sql.shuffle.partitions', value: '8' }]
  await vm.saveBinding()
  expect(accessApi.saveIdentityBinding).toHaveBeenCalledWith(
    expect.objectContaining({
      providerId: provider.id,
      subjectName: 'alice',
      role: 'viewer',
      userDefaults: { 'spark.sql.shuffle.partitions': '8' }
    })
  )
})

test('manages bound accounts from one authorization details view', async () => {
  const page = await mountPage()
  const vm = page.vm as any
  vm.access.bindings = [
    {
      id: 'binding-1',
      providerId: provider.id,
      subjectId: alice.id,
      subjectName: alice.name,
      subjectType: 'USER',
      access: 'ENABLED',
      role: 'viewer',
      profile: '',
      quotaExempt: false,
      userDefaults: {}
    },
    {
      id: 'binding-2',
      providerId: provider.id,
      subjectId: 'uid=bob,dc=example,dc=com',
      subjectName: 'bob',
      subjectType: 'USER',
      access: 'DENIED',
      role: '',
      profile: 'analyst',
      quotaExempt: false,
      userDefaults: {}
    }
  ]
  vm.activeTab = 'authorizations'
  await flushPromises()

  expect(page.text()).toContain('授权详情')
  const addButton = page.find('.add-authorization-button')
  expect(addButton.exists()).toBe(true)
  expect(addButton.attributes('type')).toBe('primary')
  expect(page.text()).toContain('角色只控制管理页面和管理接口')
  expect(page.text()).not.toContain('overviewread')

  vm.authorizationRole = '__normal__'
  await flushPromises()
  expect(
    vm.filteredBindings.map(
      (item: accessApi.IdentityBinding) => item.subjectName
    )
  ).toEqual(['bob'])

  vm.authorizationRole = ''
  vm.authorizationStatus = 'ENABLED'
  await flushPromises()
  expect(
    vm.filteredBindings.map(
      (item: accessApi.IdentityBinding) => item.subjectName
    )
  ).toEqual(['alice'])

  vm.viewBinding(vm.access.bindings[0])
  expect(vm.bindingDetailDrawer).toBe(true)
  expect(vm.selectedBinding.subjectName).toBe('alice')

  await vm.openDirectory()
  expect(vm.directoryDialog).toBe(true)
  expect(accessApi.getIdentitySubjects).toHaveBeenCalledWith(
    provider.id,
    'USER',
    ''
  )
})

test('updates the IP deny list through the existing policy source of truth', async () => {
  const page = await mountPage()
  const vm = page.vm as any
  vm.policies.access.denyIps.push('10.0.0.8')
  await vm.saveDenyIps()
  expect(policyApi.updateAdminPolicies).toHaveBeenCalledWith({
    access: expect.objectContaining({ denyIps: ['10.0.0.8'] })
  })
})

test('explains how session profiles are applied to external accounts', async () => {
  const page = await mountPage()
  const vm = page.vm as any
  vm.activeTab = 'profiles'
  await flushPromises()

  expect(page.text()).toContain('Session Profile 是一组可复用的会话默认配置')
  expect(page.text()).toContain('定义参数→绑定账号→建连时自动应用')
  expect(page.text()).toContain('它只管理会话配置，不负责账号认证和角色权限')
})
