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
  <section class="engine-page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">{{ $t('management.workspace') }}</div>
        <h1>{{ $t('management.engines_title') }}</h1>
        <p>{{ $t('management.engines_subtitle') }}</p>
      </div>
      <div class="heading-actions">
        <el-tag :type="loadError ? 'danger' : 'success'" effect="light">{{
          loadError ? $t('management.data_error') : $t('management.live_data')
        }}</el-tag>
        <span v-if="updatedAt" class="updated-at">{{
          $t('management.updated_at', { time: format(updatedAt, 'HH:mm:ss') })
        }}</span>
        <el-button :loading="loading" icon="Refresh" @click="loadEngines()">{{
          $t('refresh')
        }}</el-button>
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

    <el-card class="filter-card" shadow="never">
      <div class="filter-row">
        <el-select
          v-model="searchParam.type"
          clearable
          :placeholder="$t('engine_type')"
          class="filter-type">
          <el-option
            v-for="item in getEngineType()"
            :key="item"
            :label="item"
            :value="item" />
        </el-select>
        <el-select
          v-model="searchParam.sharelevel"
          clearable
          :placeholder="$t('share_level')"
          class="filter-share">
          <el-option
            v-for="item in getShareLevel()"
            :key="item"
            :label="item"
            :value="item" />
        </el-select>
        <el-input
          v-model="searchParam['hive.server2.proxy.user']"
          clearable
          :placeholder="$t('user')"
          class="filter-user"
          @keyup.enter="loadEngines()" />
        <el-select
          v-model="refreshSeconds"
          :placeholder="$t('management.refresh_interval')"
          class="refresh-select">
          <el-option :label="$t('management.refresh_off')" :value="0" />
          <el-option :label="$t('management.refresh_10s')" :value="10" />
          <el-option :label="$t('management.refresh_30s')" :value="30" />
        </el-select>
        <el-button type="primary" icon="Search" @click="loadEngines()">{{
          $t('management.search')
        }}</el-button>
        <el-button @click="resetFilters">{{
          $t('management.reset')
        }}</el-button>
      </div>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table
        v-loading="loading"
        :data="records"
        row-key="namespace"
        class="engine-table"
        empty-text="">
        <el-table-column :label="$t('management.engine_status')" width="130">
          <template #default="scope"
            ><el-tag :type="statusType(scope.row)" effect="light" round>{{
              statusLabel(scope.row)
            }}</el-tag></template
          >
        </el-table-column>
        <el-table-column :label="$t('engine_id')" min-width="220"
          ><template #default="scope"
            ><el-link type="primary" @click="showDetail(scope.row)"
              ><span class="engine-id">{{ engineId(scope.row) }}</span></el-link
            ></template
          ></el-table-column
        >
        <el-table-column :label="$t('engine_type')" width="150"
          ><template #default="scope"
            ><el-tag size="small" effect="plain">{{
              scope.row.engineType || '-'
            }}</el-tag></template
          ></el-table-column
        >
        <el-table-column prop="user" :label="$t('user')" width="140" />
        <el-table-column
          prop="sharelevel"
          :label="$t('share_level')"
          width="130" />
        <el-table-column :label="$t('engine_address')" min-width="250"
          ><template #default="scope">{{
            scope.row.instance || '-'
          }}</template></el-table-column
        >
        <el-table-column :label="$t('version')" width="110"
          ><template #default="scope">{{
            scope.row.version || '-'
          }}</template></el-table-column
        >
        <el-table-column
          fixed="right"
          :label="$t('operation.text')"
          width="150">
          <template #default="scope">
            <el-space>
              <el-button
                link
                type="primary"
                :disabled="!engineUrl(scope.row)"
                @click="openEngineUI(engineUrl(scope.row))"
                >{{ $t('management.open_engine_ui') }}</el-button
              >
              <el-popconfirm
                :title="$t('management.remove_engine_confirm')"
                @confirm="removeEngine(scope.row)"
                ><template #reference
                  ><el-button link type="danger">{{
                    $t('management.remove')
                  }}</el-button></template
                ></el-popconfirm
              >
            </el-space>
          </template>
        </el-table-column>
        <template #empty
          ><div class="empty-state"
            ><div class="empty-icon">◌</div
            ><strong>{{ $t('management.no_engines') }}</strong
            ><span>{{ $t('management.no_engines_hint') }}</span></div
          ></template
        >
      </el-table>
    </el-card>

    <el-drawer
      v-model="detailVisible"
      :title="$t('management.engine_details')"
      size="460px">
      <template v-if="selectedEngine">
        <div class="detail-hero"
          ><div class="detail-avatar">{{
            (selectedEngine.engineType || 'E').slice(0, 1)
          }}</div
          ><div
            ><strong>{{ engineId(selectedEngine) }}</strong
            ><span
              >{{ selectedEngine.engineType || '-' }} ·
              {{ statusLabel(selectedEngine) }}</span
            ></div
          ></div
        >
        <el-descriptions :column="1" border class="detail-list">
          <el-descriptions-item :label="$t('engine_type')">{{
            selectedEngine.engineType || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('user')">{{
            selectedEngine.user || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('share_level')">{{
            selectedEngine.sharelevel || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('engine_address')">{{
            selectedEngine.instance || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('kyuubi_instance')">{{
            selectedEngine.namespace || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('version')">{{
            selectedEngine.version || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('engine_ui')">{{
            engineUrl(selectedEngine) || '-'
          }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>
  </section>
</template>

<script lang="ts" setup>
  import {
    computed,
    onBeforeUnmount,
    onMounted,
    reactive,
    ref,
    watch
  } from 'vue'
  import { format } from 'date-fns'
  import { ElMessage } from 'element-plus'
  import { useI18n } from 'vue-i18n'
  import { deleteEngine, getAllEngines } from '@/api/engine'
  import { EngineData, IEngineSearch } from '@/api/engine/types'
  import { getWebUIConfig } from '@/api/server'
  import { IWebUIConfig } from '@/api/server/types'
  import { getEngineUIUrl } from '@/utils/engine-ui'
  import { getEngineType, getShareLevel } from '@/utils/engine'

  const { t } = useI18n()
  const records = ref<EngineData[]>([])
  const loading = ref(false)
  const loadError = ref('')
  const updatedAt = ref<Date | null>(null)
  const detailVisible = ref(false)
  const selectedEngine = ref<EngineData | null>(null)
  const refreshSeconds = ref(0)
  const searchParam = reactive<IEngineSearch>({
    type: 'SPARK_SQL',
    sharelevel: 'USER',
    'hive.server2.proxy.user': 'anonymous'
  })
  const engineUIProxyConfig = reactive<IWebUIConfig>({
    engineUIProxyEnabled: false
  })
  let refreshTimer: number | undefined

  const onlineCount = computed(
    () => records.value.filter((record) => !!record.instance).length
  )
  const engineTypes = computed(
    () =>
      new Set(records.value.map((record) => record.engineType).filter(Boolean))
        .size
  )
  const engineUsers = computed(
    () =>
      new Set(records.value.map((record) => record.user).filter(Boolean)).size
  )
  const summaryItems = computed(() => [
    {
      label: t('management.total_engines'),
      value: records.value.length,
      hint: t('management.engines_live_hint'),
      color: 'accent-purple'
    },
    {
      label: t('management.online_engines'),
      value: onlineCount.value,
      hint: t('management.online_engines_hint'),
      color: 'accent-blue'
    },
    {
      label: t('management.engine_types'),
      value: engineTypes.value,
      hint: t('management.engine_types_hint'),
      color: 'accent-cyan'
    },
    {
      label: t('management.engine_users'),
      value: engineUsers.value,
      hint: t('management.engine_users_hint'),
      color: 'accent-amber'
    }
  ])
  const errorMessage = (error: unknown) =>
    error instanceof Error ? error.message : t('management.load_failed')

  const loadEngines = async (options: { silent?: boolean } = {}) => {
    if (!options.silent) loading.value = true
    try {
      records.value = await getAllEngines({ ...searchParam })
      loadError.value = ''
      updatedAt.value = new Date()
    } catch (error) {
      loadError.value = errorMessage(error)
    } finally {
      if (!options.silent) loading.value = false
    }
  }
  const clearRefreshTimer = () => {
    if (refreshTimer !== undefined) {
      window.clearInterval(refreshTimer)
      refreshTimer = undefined
    }
  }
  const setupRefreshTimer = () => {
    clearRefreshTimer()
    if (refreshSeconds.value > 0)
      refreshTimer = window.setInterval(
        () => loadEngines({ silent: true }),
        refreshSeconds.value * 1000
      )
  }
  const resetFilters = () => {
    searchParam.type = 'SPARK_SQL'
    searchParam.sharelevel = 'USER'
    searchParam['hive.server2.proxy.user'] = 'anonymous'
    loadEngines()
  }
  const engineId = (engine: EngineData) =>
    engine.attributes?.['kyuubi.engine.id'] || engine.instance || '-'
  const engineUrl = (engine: EngineData) =>
    engine.attributes?.['kyuubi.engine.url'] || ''
  const statusLabel = (engine: EngineData) =>
    engine.instance
      ? t('management.engine_online')
      : t('management.engine_unknown')
  const statusType = (engine: EngineData) =>
    engine.instance ? 'success' : 'warning'
  const showDetail = (engine: EngineData) => {
    selectedEngine.value = engine
    detailVisible.value = true
  }
  const openEngineUI = (url: string) => {
    if (url)
      window.open(getEngineUIUrl(url, engineUIProxyConfig.engineUIProxyEnabled))
  }
  const removeEngine = async (engine: EngineData) => {
    try {
      await deleteEngine({
        type: engine.engineType,
        sharelevel: engine.sharelevel,
        'hive.server2.proxy.user': engine.user,
        subdomain: engine.subdomain
      })
      ElMessage({
        message: t('message.delete_succeeded', {
          name: t('management.engine')
        }),
        type: 'success'
      })
      await loadEngines()
    } catch (error) {
      ElMessage({ message: errorMessage(error), type: 'error' })
    }
  }
  watch(refreshSeconds, setupRefreshTimer)
  onMounted(() => {
    loadEngines()
    setupRefreshTimer()
    getWebUIConfig()
      .then((config) => {
        engineUIProxyConfig.engineUIProxyEnabled = config.engineUIProxyEnabled
      })
      .catch(() => {})
  })
  onBeforeUnmount(clearRefreshTimer)
  defineExpose({
    records,
    searchParam,
    refreshSeconds,
    onlineCount,
    engineTypes,
    engineUsers,
    loadEngines,
    resetFilters,
    engineId,
    engineUrl,
    statusLabel,
    openEngineUI,
    removeEngine,
    showDetail,
    engineUIProxyConfig
  })
</script>

<style scoped lang="scss">
  .engine-page {
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
  .heading-actions,
  .filter-row {
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
  .accent-amber {
    color: #d99b24;
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
  .filter-card,
  .table-card {
    border: 1px solid #e7ebf4;
    border-radius: 16px;
  }
  .filter-card {
    margin-bottom: 14px;
  }
  .filter-type {
    width: 190px;
  }
  .filter-share {
    width: 160px;
  }
  .filter-user {
    width: 190px;
  }
  .refresh-select {
    width: 160px;
    margin-left: auto;
  }
  .engine-table :deep(.el-table__header th) {
    height: 44px;
    color: #76839b;
    background: #f8faff;
    font-size: 12px;
  }
  .engine-table :deep(.el-table__row td) {
    padding: 14px 0;
  }
  .engine-id {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
  }
  .empty-state {
    display: flex;
    align-items: center;
    flex-direction: column;
    padding: 54px 0;
    color: #8794aa;
  }
  .empty-icon {
    margin-bottom: 10px;
    color: #7254ff;
    font-size: 34px;
  }
  .empty-state strong {
    color: #43516b;
  }
  .empty-state span {
    margin-top: 6px;
    font-size: 12px;
  }
  .detail-hero {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 20px;
  }
  .detail-avatar {
    display: grid;
    width: 42px;
    height: 42px;
    place-items: center;
    border-radius: 13px;
    color: #fff;
    background: linear-gradient(135deg, #7254ff, #aa8cff);
    font-size: 12px;
    font-weight: 800;
  }
  .detail-hero strong,
  .detail-hero span {
    display: block;
  }
  .detail-hero span {
    margin-top: 4px;
    color: #8390a7;
    font-size: 12px;
  }
  .detail-list :deep(.el-descriptions__label) {
    width: 130px;
    color: #77849b;
  }
  @media (max-width: 1200px) {
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
    .filter-row {
      flex-wrap: wrap;
    }
    .refresh-select {
      margin-left: 0;
    }
  }
  @media (max-width: 700px) {
    .summary-grid {
      grid-template-columns: 1fr;
    }
    .filter-type,
    .filter-share,
    .filter-user,
    .refresh-select {
      width: 100%;
    }
    .heading-actions {
      align-items: flex-start;
      flex-wrap: wrap;
    }
  }
</style>
