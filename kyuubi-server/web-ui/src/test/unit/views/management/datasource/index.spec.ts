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
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'

import DatasourceManagement from '@/views/management/datasource/index.vue'
import * as datasourceApi from '@/api/datasource'
import { createI18n, getStore } from '@/test/unit/utils'

vi.mock('@/api/datasource', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/datasource')>('@/api/datasource')
  return {
    ...actual,
    listDatasources: vi.fn(),
    listDatasourceProfiles: vi.fn(),
    listStorageCredentials: vi.fn(),
    createDatasource: vi.fn(),
    createStorageCredential: vi.fn(),
    updateDatasource: vi.fn(),
    updateStorageCredential: vi.fn(),
    deleteDatasource: vi.fn(),
    deleteStorageCredential: vi.fn(),
    refreshDatasources: vi.fn(),
    testDatasourceConfiguration: vi.fn(),
    testSavedDatasource: vi.fn()
  }
})

const fixtures: datasourceApi.Datasource[] = [
  {
    label: 'sr-prod',
    engineType: 'jdbc',
    jdbcType: 'starrocks',
    driverClass: 'com.mysql.cj.jdbc.Driver',
    jdbcUrl: 'jdbc:mysql://starrocks:9030/analytics',
    username: 'reporter',
    connectionPoolParams: { maximumPoolSize: '12' },
    status: 'ENABLED',
    description: 'Production analytics',
    credentialStored: true,
    icebergConfig: null
  },
  {
    label: 'pg-archive',
    engineType: 'jdbc',
    jdbcType: 'postgresql',
    driverClass: 'org.postgresql.Driver',
    jdbcUrl: 'jdbc:postgresql://postgres:5432/archive',
    username: 'archive',
    connectionPoolParams: {},
    status: 'DISABLED',
    description: '',
    credentialStored: false,
    icebergConfig: null
  },
  {
    label: 'iceberg-prod',
    engineType: 'spark',
    jdbcType: '',
    driverClass: '',
    jdbcUrl: '',
    username: '',
    connectionPoolParams: {},
    status: 'ENABLED',
    description: 'Lakehouse catalog',
    credentialStored: false,
    icebergConfig: {
      catalogName: 'lake',
      catalogType: 'hive',
      uri: 'thrift://172.16.7.137:9083',
      warehouse: 's3a://iceberg/',
      s3Endpoint: 'http://s3.example:9000',
      s3PathStyleAccess: true,
      s3SslEnabled: false,
      sessionProfile: 'large',
      credentialRef: 'seaweedfs-e2e'
    }
  }
]

let activeWrapper: any

beforeEach(() => {
  vi.mocked(datasourceApi.listDatasources).mockResolvedValue(fixtures)
  vi.mocked(datasourceApi.listDatasourceProfiles).mockResolvedValue(['large'])
  vi.mocked(datasourceApi.listStorageCredentials).mockResolvedValue([
    {
      id: 'seaweedfs-e2e',
      provider: 's3',
      description: 'E2E storage',
      configured: true,
      version: 1
    }
  ])
  vi.mocked(datasourceApi.updateDatasource).mockResolvedValue(fixtures[0])
  vi.mocked(datasourceApi.refreshDatasources).mockResolvedValue(undefined)
  vi.mocked(datasourceApi.testSavedDatasource).mockResolvedValue({
    success: true,
    message: 'Connection successful',
    latencyMillis: 18,
    databaseProduct: 'StarRocks',
    databaseVersion: '3.3'
  })
})

afterEach(() => {
  activeWrapper?.unmount()
  activeWrapper = undefined
  vi.clearAllMocks()
})

function mountPage() {
  activeWrapper = shallowMount(DatasourceManagement, {
    global: { plugins: [createI18n(), getStore(), ElementPlus] }
  })
  return activeWrapper
}

test('loads data sources and computes status summary', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  expect(datasourceApi.listDatasources).toHaveBeenCalledOnce()
  expect(pageVm.summary).toEqual({
    total: 3,
    enabled: 2,
    disabled: 1,
    types: 3
  })
})

test('only exposes JDBC presets backed by a Kyuubi JDBC Engine adapter', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const presetTypes = (wrapper.vm as any).presets.map((preset: any) =>
    preset.value.toLowerCase()
  )

  expect(presetTypes).not.toContain('sqlite')
  expect(presetTypes).not.toContain('sqlserver')
  expect(presetTypes).not.toContain('mssql')
  expect(presetTypes).not.toContain('generic')
})

test('filters data sources by keyword, status, and JDBC type', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.keyword = 'archive'
  expect(pageVm.filteredDatasources.map((item: any) => item.label)).toEqual([
    'pg-archive'
  ])
  pageVm.keyword = ''
  pageVm.statusFilter = 'ENABLED'
  expect(pageVm.filteredDatasources.map((item: any) => item.label)).toEqual([
    'sr-prod',
    'iceberg-prod'
  ])
  pageVm.statusFilter = 'ALL'
  pageVm.typeFilter = 'postgresql'
  expect(pageVm.filteredDatasources.map((item: any) => item.label)).toEqual([
    'pg-archive'
  ])
})

test('editing never places a stored password into the browser form', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  pageVm.openEdit(fixtures[0])
  expect(pageVm.form.label).toBe('sr-prod')
  expect(pageVm.form.plainPassword).toBe('')
  expect(pageVm.form.connectionPoolParams).toEqual({ maximumPoolSize: '12' })
})

test('tests a saved source and preserves credentials while toggling status', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  await pageVm.testSaved(fixtures[0])
  expect(datasourceApi.testSavedDatasource).toHaveBeenCalledWith('sr-prod')

  await pageVm.toggleStatus(fixtures[0])
  expect(datasourceApi.updateDatasource).toHaveBeenCalledWith(
    'sr-prod',
    expect.objectContaining({ status: 'DISABLED', plainPassword: '' })
  )
  expect(datasourceApi.listDatasources).toHaveBeenCalledTimes(2)
})

test('preserves Iceberg catalog settings while toggling status', async () => {
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any

  await pageVm.toggleStatus(fixtures[2])

  expect(datasourceApi.updateDatasource).toHaveBeenCalledWith(
    'iceberg-prod',
    expect.objectContaining({
      engineType: 'spark',
      status: 'DISABLED',
      icebergConfig: expect.objectContaining({
        catalogName: 'lake',
        uri: 'thrift://172.16.7.137:9083',
        sessionProfile: 'large'
      })
    })
  )
})

test('builds a clean Iceberg payload without JDBC credentials', async () => {
  vi.mocked(datasourceApi.testDatasourceConfiguration).mockResolvedValue({
    success: true,
    message: 'Hive Metastore endpoint is reachable',
    latencyMillis: 12,
    databaseProduct: 'Apache Iceberg',
    databaseVersion: 'Hive Catalog 172.16.7.137:9083'
  })
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any
  pageVm.formRef = { validate: vi.fn().mockResolvedValue(true) }
  pageVm.selectEngine('spark')
  Object.assign(pageVm.form, {
    label: 'iceberg-new',
    username: 'must-be-removed',
    plainPassword: 'must-be-removed'
  })
  Object.assign(pageVm.form.icebergConfig, {
    catalogName: 'lake',
    uri: 'thrift://172.16.7.137:9083',
    warehouse: 's3a://iceberg/'
  })

  await pageVm.testDraft()

  expect(datasourceApi.testDatasourceConfiguration).toHaveBeenCalledWith(
    expect.objectContaining({
      engineType: 'spark',
      jdbcUrl: '',
      username: '',
      plainPassword: '',
      icebergConfig: expect.objectContaining({
        catalogName: 'lake',
        uri: 'thrift://172.16.7.137:9083'
      })
    })
  )
})

test('shows a failed result when draft connection testing is rejected', async () => {
  vi.mocked(datasourceApi.testDatasourceConfiguration).mockRejectedValue({
    response: {
      data: {
        success: false,
        message: 'Failed to load driver class example.missing.Driver',
        latencyMillis: 1,
        databaseProduct: '',
        databaseVersion: ''
      }
    }
  })
  const wrapper = mountPage()
  await flushPromises()
  const pageVm = wrapper.vm as any
  pageVm.formRef = { validate: vi.fn().mockResolvedValue(true) }

  await pageVm.testDraft()

  expect(pageVm.draftTestResult).toEqual(
    expect.objectContaining({
      success: false,
      message: 'Failed to load driver class example.missing.Driver'
    })
  )
})
