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

import request from '@/utils/request'

export type DatasourceStatus = 'ENABLED' | 'DISABLED'
export type DatasourceEngineType = 'jdbc' | 'spark'

export interface IcebergDatasourceConfig {
  catalogName: string
  catalogType: 'hive'
  uri: string
  warehouse: string
  s3Endpoint: string
  s3PathStyleAccess: boolean
  s3SslEnabled: boolean
  sessionProfile: string
  credentialRef: string
}

export interface StorageCredential {
  id: string
  provider: 's3'
  description: string
  configured: boolean
  version: number
}

export interface StorageCredentialPayload {
  id: string
  provider: 's3'
  accessKeyId: string
  secretAccessKey: string
  sessionToken: string
  description: string
}

export interface Datasource {
  label: string
  engineType: DatasourceEngineType
  jdbcType: string
  driverClass: string
  jdbcUrl: string
  username: string
  connectionPoolParams: Record<string, string>
  status: DatasourceStatus
  description: string
  credentialStored: boolean
  icebergConfig: IcebergDatasourceConfig | null
}

export interface DatasourcePayload {
  label: string
  engineType: DatasourceEngineType
  jdbcType: string
  driverClass: string
  jdbcUrl: string
  username: string
  plainPassword: string
  connectionPoolParams: Record<string, string>
  status: DatasourceStatus
  description: string
  icebergConfig: IcebergDatasourceConfig | null
}

export interface DatasourceConnectionTestResult {
  success: boolean
  message: string
  latencyMillis: number
  databaseProduct: string
  databaseVersion: string
}

export function listDatasources() {
  return request({
    url: 'api/v1/datasources',
    method: 'get'
  }) as Promise<Datasource[]>
}

export function listDatasourceProfiles() {
  return request({
    url: 'api/v1/datasources/profiles',
    method: 'get'
  }) as Promise<string[]>
}

export function listStorageCredentials() {
  return request({
    url: 'api/v1/datasources/credentials',
    method: 'get'
  }) as Promise<StorageCredential[]>
}

export function createStorageCredential(payload: StorageCredentialPayload) {
  return request({
    url: 'api/v1/datasources/credentials',
    method: 'post',
    data: payload
  }) as Promise<StorageCredential>
}

export function updateStorageCredential(
  id: string,
  payload: StorageCredentialPayload
) {
  return request({
    url: `api/v1/datasources/credentials/${encodeURIComponent(id)}`,
    method: 'put',
    data: payload
  }) as Promise<StorageCredential>
}

export function deleteStorageCredential(id: string) {
  return request({
    url: `api/v1/datasources/credentials/${encodeURIComponent(id)}`,
    method: 'delete'
  })
}

export function createDatasource(payload: DatasourcePayload) {
  return request({
    url: 'api/v1/datasources',
    method: 'post',
    data: payload
  }) as Promise<Datasource>
}

export function updateDatasource(label: string, payload: DatasourcePayload) {
  return request({
    url: `api/v1/datasources/${encodeURIComponent(label)}`,
    method: 'put',
    data: payload
  }) as Promise<Datasource>
}

export function deleteDatasource(label: string) {
  return request({
    url: `api/v1/datasources/${encodeURIComponent(label)}`,
    method: 'delete'
  })
}

export function refreshDatasources() {
  return request({
    url: 'api/v1/datasources/refresh',
    method: 'post'
  })
}

export function testDatasourceConfiguration(payload: DatasourcePayload) {
  return request({
    url: 'api/v1/datasources/test',
    method: 'post',
    data: payload
  }) as Promise<DatasourceConnectionTestResult>
}

export function testSavedDatasource(label: string) {
  return request({
    url: `api/v1/datasources/${encodeURIComponent(label)}/test`,
    method: 'post'
  }) as Promise<DatasourceConnectionTestResult>
}
