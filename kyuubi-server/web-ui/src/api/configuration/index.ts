import request from '@/utils/request'

export interface ConfigurationCategory {
  name: string
  entryCount: number
}

export interface ConfigurationEntry {
  key: string
  value: string
  sensitive: boolean
  category: string
}

export interface ReloadAction {
  id: string
  label: string
  endpoint: string
}

export interface AdminConfiguration {
  currentUser: string
  securityEnabled: boolean
  authenticationMethods: string[]
  administrators: string[]
  categories: ConfigurationCategory[]
  entries: ConfigurationEntry[]
  reloads: ReloadAction[]
}

export function getAdminConfiguration() {
  return request({
    url: 'api/v1/admin/configuration',
    method: 'get'
  }) as Promise<AdminConfiguration>
}

export function refreshConfiguration(endpoint: string) {
  return request({
    url: `api/v1/${endpoint}`,
    method: 'post'
  })
}
