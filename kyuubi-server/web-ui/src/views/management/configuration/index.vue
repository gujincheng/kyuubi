<!--
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
-->

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue'
  import { ElMessage } from 'element-plus'
  import { useI18n } from 'vue-i18n'
  import {
    getAdminConfiguration,
    refreshConfiguration,
    type AdminConfiguration
  } from '@/api/configuration'

  const { t } = useI18n()
  const loading = ref(false)
  const refreshing = ref<string | null>(null)
  const error = ref('')
  const updatedAt = ref(0)
  const configuration = ref<AdminConfiguration | null>(null)
  const query = ref('')
  const category = ref('all')
  const configurationText = (key: string, params?: Record<string, unknown>) =>
    t(`sql_record.configuration_${key}`, params ?? {})

  const filteredEntries = computed(() => {
    const entries = configuration.value?.entries ?? []
    const keyword = query.value.trim().toLowerCase()
    return entries.filter((entry) => {
      const matchesCategory =
        category.value === 'all' || entry.category === category.value
      const matchesKeyword =
        !keyword ||
        `${entry.key} ${entry.value}`.toLowerCase().includes(keyword)
      return matchesCategory && matchesKeyword
    })
  })

  const loadConfiguration = async () => {
    loading.value = true
    error.value = ''
    try {
      const data = await getAdminConfiguration()
      configuration.value = data
      updatedAt.value = Date.now()
    } catch (err) {
      error.value =
        err instanceof Error ? err.message : configurationText('load_failed')
    } finally {
      loading.value = false
    }
  }

  const reload = async (id: string, endpoint: string) => {
    refreshing.value = id
    try {
      await refreshConfiguration(endpoint)
      await loadConfiguration()
      ElMessage.success(configurationText('reload_success'))
    } catch (err) {
      ElMessage.error(
        err instanceof Error ? err.message : configurationText('reload_failed')
      )
    } finally {
      refreshing.value = null
    }
  }

  const formatTime = (timestamp: number) =>
    timestamp ? new Date(timestamp).toLocaleTimeString() : '--'

  onMounted(loadConfiguration)

  defineExpose({
    configuration,
    filteredEntries,
    loadConfiguration,
    reload,
    formatTime
  })
</script>

<template>
  <div class="configuration-page">
    <section class="page-hero">
      <div>
        <div class="eyebrow">{{ configurationText('eyebrow') }}</div>
        <h1>{{ configurationText('title') }}</h1>
        <p>{{ configurationText('subtitle') }}</p>
      </div>
      <div class="hero-actions">
        <span v-if="updatedAt" class="updated-at">{{
          configurationText('updated_at', { time: formatTime(updatedAt) })
        }}</span>
        <el-button :loading="loading" @click="loadConfiguration">{{
          t('refresh')
        }}</el-button>
      </div>
    </section>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      class="error-alert" />

    <section v-if="configuration" class="summary-grid">
      <article class="summary-card accent-indigo">
        <span>{{ configurationText('security_mode') }}</span>
        <strong>{{
          configuration.securityEnabled
            ? configurationText('enabled')
            : configurationText('disabled')
        }}</strong>
        <small>{{
          configuration.authenticationMethods.join(', ') ||
          configurationText('not_configured')
        }}</small>
      </article>
      <article class="summary-card accent-cyan">
        <span>{{ configurationText('current_user') }}</span>
        <strong>{{ configuration.currentUser }}</strong>
        <small>{{ configurationText('current_user_hint') }}</small>
      </article>
      <article class="summary-card accent-violet">
        <span>{{ configurationText('administrators') }}</span>
        <strong>{{ configuration.administrators.length }}</strong>
        <small>{{ configurationText('administrator_hint') }}</small>
      </article>
      <article class="summary-card accent-amber">
        <span>{{ configurationText('entries') }}</span>
        <strong>{{ configuration.entries.length }}</strong>
        <small>{{ configurationText('entries_hint') }}</small>
      </article>
    </section>

    <section v-if="configuration" class="content-grid">
      <el-card class="panel reload-panel" shadow="never">
        <template #header>
          <div class="panel-heading">
            <div>
              <span class="panel-kicker">{{
                configurationText('operations_eyebrow')
              }}</span>
              <h2>{{ configurationText('reload_title') }}</h2>
            </div>
            <el-tag type="warning" effect="plain">{{
              configurationText('file_driven')
            }}</el-tag>
          </div>
        </template>
        <p class="panel-description">{{
          configurationText('reload_description')
        }}</p>
        <div class="reload-list">
          <div
            v-for="action in configuration.reloads"
            :key="action.id"
            class="reload-item">
            <div class="reload-icon">↻</div>
            <div class="reload-copy">
              <strong>{{ action.label }}</strong>
              <span>{{ action.endpoint }}</span>
            </div>
            <el-button
              size="small"
              :loading="refreshing === action.id"
              @click="reload(action.id, action.endpoint)">
              {{ configurationText('reload') }}
            </el-button>
          </div>
        </div>
      </el-card>

      <el-card class="panel access-panel" shadow="never">
        <template #header>
          <div class="panel-heading">
            <div>
              <span class="panel-kicker">{{
                configurationText('permission_eyebrow')
              }}</span>
              <h2>{{ configurationText('permission_title') }}</h2>
            </div>
            <span class="status-dot"
              ><i />{{ configurationText('runtime_snapshot') }}</span
            >
          </div>
        </template>
        <div class="admin-list">
          <div
            v-for="administrator in configuration.administrators"
            :key="administrator"
            class="admin-item">
            <span class="avatar">{{
              administrator.slice(0, 1).toUpperCase()
            }}</span>
            <span>{{ administrator }}</span>
            <el-tag size="small" type="success" effect="plain">{{
              configurationText('admin_role')
            }}</el-tag>
          </div>
        </div>
      </el-card>
    </section>

    <el-card v-if="configuration" class="panel config-panel" shadow="never">
      <template #header>
        <div class="table-heading">
          <div>
            <span class="panel-kicker">{{
              configurationText('snapshot_eyebrow')
            }}</span>
            <h2>{{ configurationText('snapshot_title') }}</h2>
          </div>
          <div class="table-tools">
            <el-select
              v-model="category"
              :placeholder="configurationText('category')"
              size="small"
              class="category-select">
              <el-option
                :label="configurationText('all_categories')"
                value="all" />
              <el-option
                v-for="item in configuration.categories"
                :key="item.name"
                :label="`${item.name} (${item.entryCount})`"
                :value="item.name" />
            </el-select>
            <el-input
              v-model="query"
              :placeholder="configurationText('search_placeholder')"
              clearable
              size="small"
              class="search-input" />
          </div>
        </div>
      </template>
      <el-table
        :data="filteredEntries"
        stripe
        height="420"
        empty-text="暂无配置项">
        <el-table-column
          prop="key"
          :label="configurationText('key')"
          min-width="310"
          show-overflow-tooltip />
        <el-table-column
          prop="category"
          :label="configurationText('category')"
          width="180" />
        <el-table-column
          :label="configurationText('value')"
          min-width="260"
          show-overflow-tooltip>
          <template #default="scope">
            <span
              :class="['config-value', { sensitive: scope.row.sensitive }]"
              >{{ scope.row.value }}</span
            >
          </template>
        </el-table-column>
        <el-table-column :label="configurationText('source')" width="130">
          <template #default
            ><el-tag size="small" effect="plain">{{
              configurationText('runtime')
            }}</el-tag></template
          >
        </el-table-column>
      </el-table>
      <div class="table-footer">{{ configurationText('read_only_hint') }}</div>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
  .configuration-page {
    padding: 28px 32px 44px;
    color: #172033;
  }
  .page-hero {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 24px;
    margin-bottom: 26px;
  }
  .eyebrow,
  .panel-kicker {
    color: #6757e8;
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.14em;
    text-transform: uppercase;
  }
  h1 {
    margin: 8px 0 8px;
    font-size: 30px;
    letter-spacing: -0.03em;
  }
  .page-hero p,
  .panel-description {
    color: #6b778d;
    margin: 0;
    font-size: 14px;
  }
  .hero-actions {
    display: flex;
    align-items: center;
    gap: 14px;
  }
  .updated-at {
    color: #8792a6;
    font-size: 12px;
  }
  .error-alert {
    margin-bottom: 18px;
  }
  .summary-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 14px;
    margin-bottom: 18px;
  }
  .summary-card {
    position: relative;
    overflow: hidden;
    padding: 20px;
    border: 1px solid #e6eaf3;
    border-radius: 16px;
    background: linear-gradient(145deg, #fff, #f8f9fd);
    box-shadow: 0 10px 28px rgba(37, 46, 74, 0.06);
  }
  .summary-card::after {
    content: '';
    position: absolute;
    right: -26px;
    top: -34px;
    width: 100px;
    height: 100px;
    border-radius: 50%;
    background: currentColor;
    opacity: 0.08;
  }
  .summary-card span,
  .summary-card small {
    display: block;
    color: #768198;
    font-size: 12px;
  }
  .summary-card strong {
    display: block;
    margin: 10px 0 6px;
    color: #1d2740;
    font-size: 23px;
  }
  .accent-indigo {
    color: #6757e8;
  }
  .accent-cyan {
    color: #1ba4be;
  }
  .accent-violet {
    color: #9a5de8;
  }
  .accent-amber {
    color: #e49a2f;
  }
  .content-grid {
    display: grid;
    grid-template-columns: 1.25fr 0.75fr;
    gap: 18px;
    margin-bottom: 18px;
  }
  .panel {
    border: 1px solid #e7eaf2;
    border-radius: 16px;
    box-shadow: 0 12px 32px rgba(36, 47, 76, 0.05);
  }
  .panel :deep(.el-card__header) {
    padding: 20px 22px 14px;
    border-bottom: 1px solid #eff1f6;
  }
  .panel :deep(.el-card__body) {
    padding: 18px 22px 20px;
  }
  .panel-heading,
  .table-heading {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 16px;
  }
  h2 {
    margin: 6px 0 0;
    font-size: 18px;
    letter-spacing: -0.02em;
  }
  .reload-list {
    display: grid;
    gap: 10px;
  }
  .reload-item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px;
    border: 1px solid #edf0f6;
    border-radius: 12px;
    transition: 0.2s;
  }
  .reload-item:hover {
    border-color: #cfc9ff;
    background: #fbfaff;
  }
  .reload-icon {
    display: grid;
    place-items: center;
    width: 34px;
    height: 34px;
    border-radius: 10px;
    color: #6757e8;
    background: #f0eeff;
    font-size: 20px;
  }
  .reload-copy {
    flex: 1;
    min-width: 0;
  }
  .reload-copy strong,
  .reload-copy span {
    display: block;
  }
  .reload-copy strong {
    font-size: 13px;
  }
  .reload-copy span {
    margin-top: 4px;
    color: #9aa3b4;
    font-size: 11px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .status-dot {
    display: flex;
    align-items: center;
    gap: 7px;
    color: #57956c;
    font-size: 12px;
  }
  .status-dot i {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #57bf79;
    box-shadow: 0 0 0 4px #e6f7eb;
  }
  .admin-list {
    display: grid;
    gap: 10px;
  }
  .admin-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px;
    background: #f8f9fc;
    border-radius: 10px;
    font-size: 13px;
  }
  .admin-item .el-tag {
    margin-left: auto;
  }
  .avatar {
    display: grid;
    place-items: center;
    width: 30px;
    height: 30px;
    border-radius: 50%;
    color: #fff;
    background: linear-gradient(135deg, #6b5bea, #a469ed);
    font-size: 12px;
    font-weight: 800;
  }
  .table-tools {
    display: flex;
    gap: 10px;
  }
  .category-select {
    width: 190px;
  }
  .search-input {
    width: 220px;
  }
  .config-value.sensitive {
    color: #a15fdb;
    letter-spacing: 0.08em;
  }
  .table-footer {
    margin-top: 14px;
    color: #9099aa;
    font-size: 12px;
  }
  @media (max-width: 1100px) {
    .summary-grid {
      grid-template-columns: repeat(2, 1fr);
    }
    .content-grid {
      grid-template-columns: 1fr;
    }
  }
  @media (max-width: 700px) {
    .configuration-page {
      padding: 20px 16px 32px;
    }
    .page-hero,
    .table-heading {
      flex-direction: column;
    }
    .hero-actions,
    .table-tools {
      width: 100%;
      flex-wrap: wrap;
    }
    .category-select,
    .search-input {
      width: 100%;
    }
    .summary-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
