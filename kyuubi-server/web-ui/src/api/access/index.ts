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

type IdentityProviderType = 'LDAP' | 'IAM'
type IdentitySubjectType = 'USER' | 'GROUP'
type BindingAccess = 'ENABLED' | 'DENIED'

interface IdentityProvider {
  id: string
  name: string
  providerType: IdentityProviderType
  enabled: boolean
  endpoint: string
  baseDn: string
  bindDn: string
  secretEnvironment: string
  userFilter: string
  groupFilter: string
  userNameAttribute: string
  displayNameAttribute: string
  emailAttribute: string
  groupNameAttribute: string
  userDnPattern: string
  usersPath: string
  groupsPath: string
  authenticationPath: string
  idField: string
  userNameField: string
  displayNameField: string
  emailField: string
  groupNameField: string
  connectTimeoutMillis: number
  readTimeoutMillis: number
}

interface IdentityProviderView {
  provider: IdentityProvider
  secretConfigured: boolean
}

interface IdentityBinding {
  id: string
  providerId: string
  subjectId: string
  subjectName: string
  subjectType: IdentitySubjectType
  access: BindingAccess
  role: string
  profile: string
  quotaExempt: boolean
  userDefaults: Record<string, string>
}

interface ManagedAuthenticationStatus {
  active: boolean
  restartRequired: boolean
  className: string
  message: string
}

interface AdminAccess {
  providers: IdentityProviderView[]
  bindings: IdentityBinding[]
  authentication: ManagedAuthenticationStatus
}

interface IdentitySubject {
  providerId: string
  id: string
  name: string
  displayName: string
  email: string
  subjectType: IdentitySubjectType
  groups: string[]
  members: string[]
}

interface IdentitySubjects {
  providerId: string
  subjects: IdentitySubject[]
  generatedAt: number
}

interface IdentityProviderTestResult {
  success: boolean
  message: string
  latencyMillis: number
  subjectCount: number
}

export function getAdminAccess(): Promise<AdminAccess> {
  return request({
    url: 'api/v1/admin/access',
    method: 'get'
  }) as Promise<AdminAccess>
}

export function saveIdentityProvider(
  provider: IdentityProvider
): Promise<AdminAccess> {
  return request({
    url: 'api/v1/admin/access/providers',
    method: 'put',
    data: provider
  }) as Promise<AdminAccess>
}

export function deleteIdentityProvider(id: string): Promise<AdminAccess> {
  return request({
    url: `api/v1/admin/access/providers/${encodeURIComponent(id)}`,
    method: 'delete'
  }) as Promise<AdminAccess>
}

export function testIdentityProvider(
  id: string
): Promise<IdentityProviderTestResult> {
  return request({
    url: `api/v1/admin/access/providers/${encodeURIComponent(id)}/test`,
    method: 'post'
  }) as Promise<IdentityProviderTestResult>
}

export function getIdentitySubjects(
  providerId: string,
  subjectType: IdentitySubjectType,
  query = ''
): Promise<IdentitySubjects> {
  return request({
    url: `api/v1/admin/access/providers/${encodeURIComponent(providerId)}/subjects`,
    method: 'get',
    params: { subjectType, query }
  }) as Promise<IdentitySubjects>
}

export function saveIdentityBinding(
  binding: Partial<IdentityBinding>
): Promise<AdminAccess> {
  return request({
    url: 'api/v1/admin/access/bindings',
    method: 'put',
    data: binding
  }) as Promise<AdminAccess>
}

export function deleteIdentityBinding(id: string): Promise<AdminAccess> {
  return request({
    url: `api/v1/admin/access/bindings/${encodeURIComponent(id)}`,
    method: 'delete'
  }) as Promise<AdminAccess>
}

export function activateManagedAuthentication(): Promise<ManagedAuthenticationStatus> {
  return request({
    url: 'api/v1/admin/access/activate',
    method: 'post'
  }) as Promise<ManagedAuthenticationStatus>
}

export function deactivateManagedAuthentication(): Promise<ManagedAuthenticationStatus> {
  return request({
    url: 'api/v1/admin/access/deactivate',
    method: 'post'
  }) as Promise<ManagedAuthenticationStatus>
}

export type {
  AdminAccess,
  BindingAccess,
  IdentityBinding,
  IdentityProvider,
  IdentityProviderTestResult,
  IdentityProviderType,
  IdentitySubject,
  IdentitySubjects,
  IdentitySubjectType,
  ManagedAuthenticationStatus
}
