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

interface SessionProfile {
  name: string
  fileName: string
  propertyCount: number
  modifiedTime: number
  properties: Record<string, string>
}

interface UserDefaults {
  user: string
  properties: Record<string, string>
}

interface AccessPolicies {
  unlimitedUsers: string[]
  denyUsers: string[]
  denyIps: string[]
}

interface AdminPolicies {
  profiles: SessionProfile[]
  userDefaults: UserDefaults[]
  access: AccessPolicies
}

interface UserDefaultsUpdate {
  user: string
  properties: Record<string, string>
  delete?: boolean
}

interface SessionProfileUpdate {
  name: string
  properties: Record<string, string>
  delete?: boolean
}

interface AdminPoliciesUpdate {
  userDefaults?: UserDefaultsUpdate[]
  profiles?: SessionProfileUpdate[]
  access?: AccessPolicies
}

type PolicyRefreshDomain =
  'user_defaults_conf' | 'unlimited_users' | 'deny_users' | 'deny_ips'

export function getAdminPolicies(): Promise<AdminPolicies> {
  return request({
    url: 'api/v1/admin/policies',
    method: 'get'
  }) as Promise<AdminPolicies>
}

export function updateAdminPolicies(
  payload: AdminPoliciesUpdate
): Promise<AdminPolicies> {
  return request({
    url: 'api/v1/admin/policies',
    method: 'put',
    data: payload
  }) as Promise<AdminPolicies>
}

export function refreshPolicy(domain: PolicyRefreshDomain): Promise<unknown> {
  return request({
    url: `api/v1/admin/refresh/${domain}`,
    method: 'post'
  })
}

export type {
  AccessPolicies,
  AdminPolicies,
  AdminPoliciesUpdate,
  PolicyRefreshDomain,
  SessionProfileUpdate,
  SessionProfile,
  UserDefaults,
  UserDefaultsUpdate
}
