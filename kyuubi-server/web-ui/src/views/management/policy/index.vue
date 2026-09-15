<!--
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
-->

<template>
  <section class="policy-page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">{{ $t('management.workspace') }}</div>
        <h1>{{ $t('management.policy_title') }}</h1>
        <p>{{ $t('management.policy_subtitle') }}</p>
      </div>
      <div class="heading-actions">
        <el-tag :type="loadError ? 'danger' : 'success'" effect="light">
          {{
            loadError ? $t('management.data_error') : $t('management.live_data')
          }}
        </el-tag>
        <span v-if="updatedAt" class="updated-at">
          {{
            $t('management.updated_at', { time: format(updatedAt, 'HH:mm:ss') })
          }}
        </span>
        <el-button :loading="loading" icon="Refresh" @click="loadPolicies">
          {{ $t('refresh') }}
        </el-button>
      </div>
    </div>
    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      show-icon
      closable
      class="load-alert"
      @close="loadError = ''" />

    <div class="summary-grid">
      <el-card
        v-for="item in summaryItems"
        :key="item.label"
        :class="['summary-card', item.color]"
        shadow="never">
        <span class="summary-label">{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <span class="summary-hint">{{ item.hint }}</span>
      </el-card>
    </div>

    <el-card class="source-card" shadow="never">
      <div class="source-icon">⚙</div>
      <div class="source-copy">
        <strong>{{ $t('management.policy_source_title') }}</strong>
        <span>{{ $t('management.policy_source_hint') }}</span>
      </div>
      <el-button
        class="source-action"
        :loading="refreshingAll"
        @click="refreshAll">
        {{ $t('management.refresh_all_policies') }}
      </el-button>
    </el-card>

    <el-card class="content-card" shadow="never">
      <el-tabs v-model="activeTab">
        <el-tab-pane :label="$t('management.access_policies')" name="access">
          <div class="tab-toolbar">
            <span>{{ $t('management.policy_edit_hint') }}</span>
            <el-button
              type="primary"
              :loading="updating"
              @click="saveAccessPolicies">
              {{ $t('management.save_changes') }}
            </el-button>
          </div>
          <div class="access-grid">
            <article
              v-for="list in accessLists"
              :key="list.key"
              class="policy-list-card">
              <div class="list-heading">
                <div
                  ><span class="list-dot" :class="list.color" /><strong>{{
                    list.title
                  }}</strong
                  ><small>{{ list.hint }}</small></div
                >
                <el-button
                  link
                  icon="Refresh"
                  :loading="refreshingDomain === list.domain"
                  @click="refreshDomain(list.domain)"
                  >{{ $t('management.reload') }}</el-button
                >
              </div>
              <div class="list-editor">
                <el-input
                  v-model="newAccessValues[list.key]"
                  size="small"
                  :placeholder="$t('management.policy_value_placeholder')"
                  @keyup.enter="addAccessValue(list.key)" />
                <el-button
                  size="small"
                  type="primary"
                  plain
                  @click="addAccessValue(list.key)">
                  {{ $t('management.add') }}
                </el-button>
              </div>
              <div v-if="list.values.length" class="tag-list">
                <el-tag
                  v-for="value in list.values"
                  :key="value"
                  :type="list.tagType"
                  effect="light"
                  closable
                  @close="removeAccessValue(list.key, value)"
                  >{{ value }}</el-tag
                >
              </div>
              <div v-else class="list-empty">{{
                $t('management.policy_list_empty')
              }}</div>
            </article>
          </div>
        </el-tab-pane>
        <el-tab-pane :label="$t('management.profiles')" name="profiles">
          <div class="tab-toolbar">
            <span>{{ $t('management.profile_edit_hint') }}</span>
            <el-button type="primary" @click="openProfileEditor()">
              {{ $t('management.add_profile') }}
            </el-button>
          </div>
          <el-table
            v-loading="loading"
            :data="policies.profiles"
            row-key="fileName"
            class="policy-table"
            empty-text="">
            <el-table-column
              prop="name"
              :label="$t('management.profile_name')"
              min-width="220" />
            <el-table-column
              prop="fileName"
              :label="$t('management.profile_file')"
              min-width="270" />
            <el-table-column
              :label="$t('management.property_count')"
              width="150"
              ><template #default="scope">{{
                scope.row.propertyCount
              }}</template></el-table-column
            >
            <el-table-column :label="$t('management.modified_time')" width="210"
              ><template #default="scope">{{
                formatTime(scope.row.modifiedTime)
              }}</template></el-table-column
            >
            <el-table-column :label="$t('management.actions')" width="190">
              <template #default="scope">
                <el-button
                  link
                  type="primary"
                  @click="openProfileEditor(scope.row)">
                  {{ $t('management.edit') }}
                </el-button>
                <el-button
                  link
                  type="danger"
                  @click="deleteProfile(scope.row.name)">
                  {{ $t('management.delete') }}
                </el-button>
              </template>
            </el-table-column>
            <template #empty
              ><div class="table-empty"
                ><strong>{{ $t('management.no_profiles') }}</strong
                ><span>{{ $t('management.no_profiles_hint') }}</span></div
              ></template
            >
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="$t('management.user_defaults')" name="defaults">
          <div class="tab-toolbar">
            <span>{{ $t('management.user_defaults_edit_hint') }}</span>
            <el-button type="primary" @click="openUserDefaultsEditor()">
              {{ $t('management.add_user_defaults') }}
            </el-button>
          </div>
          <el-table
            v-loading="loading"
            :data="policies.userDefaults"
            row-key="user"
            class="policy-table"
            empty-text="">
            <el-table-column type="expand" width="55"
              ><template #default="scope"
                ><div class="properties"
                  ><div
                    v-for="(value, key) in scope.row.properties"
                    :key="key"
                    class="property-row"
                    ><code>{{ key }}</code
                    ><span>{{ value }}</span></div
                  ></div
                ></template
              ></el-table-column
            >
            <el-table-column prop="user" :label="$t('user')" min-width="220" />
            <el-table-column
              :label="$t('management.property_count')"
              width="180"
              ><template #default="scope">{{
                Object.keys(scope.row.properties || {}).length
              }}</template></el-table-column
            >
            <el-table-column :label="$t('management.actions')" width="190">
              <template #default="scope">
                <el-button
                  link
                  type="primary"
                  @click="openUserDefaultsEditor(scope.row)">
                  {{ $t('management.edit') }}
                </el-button>
                <el-button
                  link
                  type="danger"
                  @click="deleteUserDefaults(scope.row.user)">
                  {{ $t('management.delete') }}
                </el-button>
              </template>
            </el-table-column>
            <template #empty
              ><div class="table-empty"
                ><strong>{{ $t('management.no_user_defaults') }}</strong
                ><span>{{ $t('management.no_user_defaults_hint') }}</span></div
              ></template
            >
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog
      v-model="editorVisible"
      :title="editorTitle"
      width="560px"
      destroy-on-close>
      <el-form label-position="top">
        <el-form-item
          :label="
            editorType === 'profile'
              ? $t('management.profile_name')
              : $t('user')
          ">
          <el-input v-model="editorName" :disabled="editorMode === 'edit'" />
        </el-form-item>
        <el-form-item :label="$t('management.properties_text')">
          <el-input
            v-model="editorPropertiesText"
            type="textarea"
            :rows="9"
            :placeholder="$t('management.properties_placeholder')" />
        </el-form-item>
        <p class="dialog-hint">{{ $t('management.properties_hint') }}</p>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">{{ $t('cancel') }}</el-button>
        <el-button type="primary" :loading="updating" @click="saveEditor">
          {{ $t('management.save_changes') }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script lang="ts" setup>
  import { computed, onMounted, reactive, ref } from 'vue'
  import { format } from 'date-fns'
  import { ElMessage } from 'element-plus'
  import { useI18n } from 'vue-i18n'
  import {
    AdminPolicies,
    AdminPoliciesUpdate,
    PolicyRefreshDomain,
    getAdminPolicies,
    refreshPolicy,
    updateAdminPolicies
  } from '@/api/policy'

  const { t } = useI18n()
  const loading = ref(false)
  const refreshingAll = ref(false)
  const refreshingDomain = ref<PolicyRefreshDomain | ''>('')
  const updating = ref(false)
  const loadError = ref('')
  const updatedAt = ref<Date | null>(null)
  const activeTab = ref('access')
  const newAccessValues = reactive<Record<string, string>>({
    unlimited: '',
    'deny-users': '',
    'deny-ips': ''
  })
  const editorVisible = ref(false)
  const editorType = ref<'profile' | 'userDefaults'>('profile')
  const editorMode = ref<'create' | 'edit'>('create')
  const editorName = ref('')
  const editorPropertiesText = ref('')
  const policies = reactive<AdminPolicies>({
    profiles: [],
    userDefaults: [],
    access: { unlimitedUsers: [], denyUsers: [], denyIps: [] }
  })

  const summaryItems = computed(() => [
    {
      label: t('management.profiles'),
      value: policies.profiles.length,
      hint: t('management.profiles_hint'),
      color: 'accent-purple'
    },
    {
      label: t('management.user_defaults'),
      value: policies.userDefaults.length,
      hint: t('management.user_defaults_hint'),
      color: 'accent-blue'
    },
    {
      label: t('management.unlimited_users'),
      value: policies.access.unlimitedUsers.length,
      hint: t('management.unlimited_users_hint'),
      color: 'accent-cyan'
    },
    {
      label: t('management.blocked_entries'),
      value: policies.access.denyUsers.length + policies.access.denyIps.length,
      hint: t('management.blocked_entries_hint'),
      color: 'accent-rose'
    }
  ])
  const accessLists = computed(() => [
    {
      key: 'unlimited',
      domain: 'unlimited_users' as PolicyRefreshDomain,
      values: policies.access.unlimitedUsers,
      title: t('management.unlimited_users'),
      hint: t('management.unlimited_users_hint'),
      color: 'cyan',
      tagType: 'success' as const
    },
    {
      key: 'deny-users',
      domain: 'deny_users' as PolicyRefreshDomain,
      values: policies.access.denyUsers,
      title: t('management.deny_users'),
      hint: t('management.deny_users_hint'),
      color: 'rose',
      tagType: 'danger' as const
    },
    {
      key: 'deny-ips',
      domain: 'deny_ips' as PolicyRefreshDomain,
      values: policies.access.denyIps,
      title: t('management.deny_ips'),
      hint: t('management.deny_ips_hint'),
      color: 'amber',
      tagType: 'warning' as const
    }
  ])

  const errorMessage = (error: unknown) =>
    error instanceof Error ? error.message : t('management.load_failed')
  const formatTime = (timestamp?: number | null) =>
    timestamp && timestamp > 0 ? format(timestamp, 'yyyy-MM-dd HH:mm:ss') : '-'

  const editorTitle = computed(() =>
    editorType.value === 'profile'
      ? editorMode.value === 'create'
        ? t('management.add_profile')
        : t('management.edit_profile')
      : editorMode.value === 'create'
        ? t('management.add_user_defaults')
        : t('management.edit_user_defaults')
  )
  const parseProperties = (): Record<string, string> => {
    const properties: Record<string, string> = {}
    for (const line of editorPropertiesText.value.split(/\r?\n/)) {
      const value = line.trim()
      if (!value) continue
      const separator = value.indexOf('=')
      if (separator <= 0) throw new Error(t('management.properties_invalid'))
      const key = value.slice(0, separator).trim()
      const propertyValue = value.slice(separator + 1).trim()
      if (!key || Object.prototype.hasOwnProperty.call(properties, key)) {
        throw new Error(t('management.properties_invalid'))
      }
      properties[key] = propertyValue
    }
    return properties
  }
  const propertiesText = (properties: Record<string, string>) =>
    Object.entries(properties || {})
      .map(([key, value]) => `${key}=${value}`)
      .join('\n')

  const loadPolicies = async () => {
    loading.value = true
    try {
      const result = await getAdminPolicies()
      policies.profiles = result.profiles || []
      policies.userDefaults = result.userDefaults || []
      policies.access = result.access || {
        unlimitedUsers: [],
        denyUsers: [],
        denyIps: []
      }
      loadError.value = ''
      updatedAt.value = new Date()
    } catch (error) {
      loadError.value = errorMessage(error)
    } finally {
      loading.value = false
    }
  }
  const openProfileEditor = (profile?: AdminPolicies['profiles'][number]) => {
    editorType.value = 'profile'
    editorMode.value = profile ? 'edit' : 'create'
    editorName.value = profile?.name || ''
    editorPropertiesText.value = propertiesText(profile?.properties || {})
    editorVisible.value = true
  }
  const openUserDefaultsEditor = (
    userDefaults?: AdminPolicies['userDefaults'][number]
  ) => {
    editorType.value = 'userDefaults'
    editorMode.value = userDefaults ? 'edit' : 'create'
    editorName.value = userDefaults?.user || ''
    editorPropertiesText.value = propertiesText(userDefaults?.properties || {})
    editorVisible.value = true
  }
  const saveEditor = async () => {
    try {
      if (!editorName.value.trim())
        throw new Error(t('management.name_required'))
      const properties = parseProperties()
      const payload: AdminPoliciesUpdate =
        editorType.value === 'profile'
          ? { profiles: [{ name: editorName.value.trim(), properties }] }
          : { userDefaults: [{ user: editorName.value.trim(), properties }] }
      updating.value = true
      await updateAdminPolicies(payload)
      editorVisible.value = false
      await loadPolicies()
      ElMessage({ message: t('management.policy_saved'), type: 'success' })
    } catch (error) {
      ElMessage({ message: errorMessage(error), type: 'error' })
    } finally {
      updating.value = false
    }
  }
  const deletePolicy = async (payload: AdminPoliciesUpdate) => {
    updating.value = true
    try {
      await updateAdminPolicies(payload)
      await loadPolicies()
      ElMessage({ message: t('management.policy_deleted'), type: 'success' })
    } catch (error) {
      ElMessage({ message: errorMessage(error), type: 'error' })
    } finally {
      updating.value = false
    }
  }
  const deleteProfile = (name: string) =>
    deletePolicy({ profiles: [{ name, properties: {}, delete: true }] })
  const deleteUserDefaults = (user: string) =>
    deletePolicy({ userDefaults: [{ user, properties: {}, delete: true }] })
  const addAccessValue = (key: string) => {
    const value = newAccessValues[key].trim()
    if (!value) return
    const target =
      key === 'unlimited'
        ? policies.access.unlimitedUsers
        : key === 'deny-users'
          ? policies.access.denyUsers
          : policies.access.denyIps
    if (!target.includes(value)) target.push(value)
    newAccessValues[key] = ''
  }
  const removeAccessValue = (key: string, value: string) => {
    const target =
      key === 'unlimited'
        ? policies.access.unlimitedUsers
        : key === 'deny-users'
          ? policies.access.denyUsers
          : policies.access.denyIps
    const index = target.indexOf(value)
    if (index >= 0) target.splice(index, 1)
  }
  const saveAccessPolicies = async () => {
    updating.value = true
    try {
      await updateAdminPolicies({ access: policies.access })
      await loadPolicies()
      ElMessage({ message: t('management.policy_saved'), type: 'success' })
    } catch (error) {
      ElMessage({ message: errorMessage(error), type: 'error' })
    } finally {
      updating.value = false
    }
  }
  const refreshDomain = async (domain: PolicyRefreshDomain) => {
    refreshingDomain.value = domain
    try {
      await refreshPolicy(domain)
      await loadPolicies()
      ElMessage({ message: t('management.policy_reloaded'), type: 'success' })
    } catch (error) {
      ElMessage({ message: errorMessage(error), type: 'error' })
    } finally {
      refreshingDomain.value = ''
    }
  }
  const refreshAll = async () => {
    refreshingAll.value = true
    try {
      await Promise.all(
        (
          [
            'user_defaults_conf',
            'unlimited_users',
            'deny_users',
            'deny_ips'
          ] as PolicyRefreshDomain[]
        ).map((domain) => refreshPolicy(domain))
      )
      await loadPolicies()
      ElMessage({ message: t('management.policy_reloaded'), type: 'success' })
    } catch (error) {
      ElMessage({ message: errorMessage(error), type: 'error' })
    } finally {
      refreshingAll.value = false
    }
  }
  onMounted(loadPolicies)
  defineExpose({
    policies,
    activeTab,
    loadPolicies,
    refreshDomain,
    refreshAll,
    formatTime,
    openProfileEditor,
    openUserDefaultsEditor,
    saveEditor,
    saveAccessPolicies,
    addAccessValue,
    removeAccessValue
  })
</script>

<style scoped lang="scss">
  .policy-page {
    min-height: 100%;
    padding: 4px 2px 24px;
    color: #17233f;
  }
  .page-heading {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 20px;
    margin-bottom: 20px;
  }
  .eyebrow {
    color: #7254ff;
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.16em;
    text-transform: uppercase;
  }
  h1 {
    margin: 6px 0 4px;
    font-size: 28px;
    letter-spacing: -0.03em;
  }
  .page-heading p {
    margin: 0;
    color: #71809a;
    font-size: 13px;
  }
  .heading-actions {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .updated-at {
    color: #8794aa;
    font-size: 12px;
  }
  .load-alert {
    margin-bottom: 16px;
  }
  .summary-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 14px;
    margin-bottom: 16px;
  }
  .summary-card {
    position: relative;
    overflow: hidden;
    border: 1px solid #e7ebf4;
    border-radius: 16px;
    background: linear-gradient(145deg, #fff, #f8faff);
  }
  .summary-card::after {
    position: absolute;
    right: -24px;
    bottom: -28px;
    width: 92px;
    height: 92px;
    border-radius: 50%;
    background: currentColor;
    content: '';
    opacity: 0.08;
  }
  .accent-purple {
    color: #7254ff;
  }
  .accent-blue {
    color: #3182f6;
  }
  .accent-cyan {
    color: #19a9a1;
  }
  .accent-rose {
    color: #e45d78;
  }
  .summary-label,
  .summary-hint {
    display: block;
    color: #71809a;
    font-size: 12px;
  }
  .summary-card strong {
    display: block;
    margin: 8px 0 4px;
    color: currentColor;
    font-size: 30px;
    line-height: 1;
  }
  .source-card {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 16px;
    border: 1px solid #e7ebf4;
    border-radius: 16px;
  }
  .source-icon {
    display: grid;
    width: 38px;
    height: 38px;
    place-items: center;
    border-radius: 12px;
    color: #7254ff;
    background: #f0ecff;
    font-size: 18px;
  }
  .source-copy strong,
  .source-copy span {
    display: block;
  }
  .source-copy span {
    margin-top: 3px;
    color: #8794aa;
    font-size: 12px;
  }
  .source-action {
    margin-left: auto;
  }
  .content-card {
    border: 1px solid #e7ebf4;
    border-radius: 16px;
  }
  .access-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 14px;
  }
  .policy-list-card {
    min-height: 180px;
    padding: 18px;
    border: 1px solid #edf0f6;
    border-radius: 14px;
    background: #fbfcff;
  }
  .list-heading {
    display: flex;
    justify-content: space-between;
    gap: 10px;
  }
  .list-heading > div {
    position: relative;
    padding-left: 14px;
  }
  .list-heading strong,
  .list-heading small {
    display: block;
  }
  .list-heading small {
    margin-top: 5px;
    color: #8794aa;
    font-size: 11px;
  }
  .list-dot {
    position: absolute;
    top: 4px;
    left: 0;
    width: 7px;
    height: 7px;
    border-radius: 50%;
  }
  .list-dot.cyan {
    background: #19a9a1;
  }
  .list-dot.rose {
    background: #e45d78;
  }
  .list-dot.amber {
    background: #d99b24;
  }
  .tag-list {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 24px;
  }
  .list-empty {
    margin-top: 32px;
    color: #a0aabd;
    font-size: 12px;
    text-align: center;
  }
  .policy-table :deep(.el-table__header th) {
    height: 44px;
    color: #76839b;
    background: #f8faff;
    font-size: 12px;
  }
  .table-empty {
    display: flex;
    align-items: center;
    flex-direction: column;
    padding: 42px 0;
    color: #8794aa;
  }
  .table-empty span {
    margin-top: 6px;
    font-size: 12px;
  }
  .properties {
    padding: 8px 36px;
  }
  .property-row {
    display: flex;
    justify-content: space-between;
    gap: 18px;
    padding: 8px 0;
    border-bottom: 1px solid #eef1f6;
    font-size: 12px;
  }
  .property-row code {
    color: #7254ff;
  }
  .property-row span {
    color: #43516b;
    word-break: break-all;
  }
  @media (max-width: 1000px) {
    .summary-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .page-heading {
      align-items: flex-start;
      flex-direction: column;
    }
    .heading-actions {
      width: 100%;
    }
    .access-grid {
      grid-template-columns: 1fr;
    }
  }
  @media (max-width: 600px) {
    .summary-grid {
      grid-template-columns: 1fr;
    }
    .source-card {
      align-items: flex-start;
      flex-wrap: wrap;
    }
    .source-action {
      width: 100%;
      margin-left: 0;
    }
  }
</style>
