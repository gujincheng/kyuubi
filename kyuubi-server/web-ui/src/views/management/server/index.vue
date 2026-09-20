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
  <section class="server-page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">{{ $t('management.workspace') }}</div>
        <h1>{{ $t('management.servers_title') }}</h1>
        <p>{{ $t('management.servers_subtitle') }}</p>
      </div>
      <div class="heading-actions">
        <el-tag :type="loadError ? 'danger' : 'success'" effect="light">{{
          loadError ? $t('management.data_error') : $t('management.live_data')
        }}</el-tag>
        <span v-if="updatedAt" class="updated-at">{{
          $t('management.updated_at', { time: format(updatedAt, 'HH:mm:ss') })
        }}</span>
        <el-button :loading="loading" icon="Refresh" @click="loadServers()">{{
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
        <el-input
          v-model="hostFilter"
          clearable
          :placeholder="$t('management.filter_host')"
          class="filter-host"
          @keyup.enter="loadServers()" />
        <el-select
          v-model="statusFilter"
          clearable
          :placeholder="$t('management.filter_state')"
          class="filter-status"
          @change="loadServers()">
          <el-option :label="$t('management.server_running')" value="Running" />
          <el-option :label="$t('management.server_unknown')" value="UNKNOWN" />
        </el-select>
        <el-select
          v-model="refreshSeconds"
          :placeholder="$t('management.refresh_interval')"
          class="refresh-select">
          <el-option :label="$t('management.refresh_off')" :value="0" />
          <el-option :label="$t('management.refresh_10s')" :value="10" />
          <el-option :label="$t('management.refresh_30s')" :value="30" />
        </el-select>
        <el-button type="primary" icon="Search" @click="loadServers()">{{
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
        :data="filteredRecords"
        row-key="nodeName"
        class="server-table"
        empty-text="">
        <el-table-column :label="$t('management.server_status')" width="130"
          ><template #default="scope"
            ><el-tag
              :type="statusType(scope.row.status)"
              effect="light"
              round
              >{{ statusLabel(scope.row.status) }}</el-tag
            ></template
          ></el-table-column
        >
        <el-table-column :label="$t('server_ip')" min-width="190"
          ><template #default="scope"
            ><el-link type="primary" @click="showDetail(scope.row)"
              >{{ scope.row.host }}:{{ scope.row.port }}</el-link
            ></template
          ></el-table-column
        >
        <el-table-column :label="$t('kyuubi_instance')" min-width="220"
          ><template #default="scope">{{
            scope.row.instance || '-'
          }}</template></el-table-column
        >
        <el-table-column :label="$t('management.namespace')" min-width="160"
          ><template #default="scope">{{
            scope.row.namespace || '-'
          }}</template></el-table-column
        >
        <el-table-column :label="$t('version')" width="120"
          ><template #default="scope">{{
            scope.row.attributes?.version || '-'
          }}</template></el-table-column
        >
        <el-table-column :label="$t('management.node_name')" min-width="300"
          ><template #default="scope"
            ><span class="node-name">{{
              scope.row.nodeName || '-'
            }}</span></template
          ></el-table-column
        >
        <el-table-column fixed="right" :label="$t('operation.text')" width="100"
          ><template #default="scope"
            ><el-button link type="primary" @click="showDetail(scope.row)">{{
              $t('management.details')
            }}</el-button></template
          ></el-table-column
        >
        <template #empty
          ><div class="empty-state"
            ><div class="empty-icon">◌</div
            ><strong>{{ $t('management.no_servers') }}</strong
            ><span>{{ $t('management.no_servers_hint') }}</span></div
          ></template
        >
      </el-table>
    </el-card>

    <el-drawer
      v-model="detailVisible"
      :title="$t('management.server_details')"
      size="460px">
      <template v-if="selectedServer">
        <div class="detail-hero"
          ><div class="detail-avatar">S</div
          ><div
            ><strong>{{ selectedServer.host }}:{{ selectedServer.port }}</strong
            ><span
              >{{ statusLabel(selectedServer.status) }} ·
              {{ selectedServer.attributes?.version || '-' }}</span
            ></div
          ></div
        >
        <el-descriptions :column="1" border class="detail-list">
          <el-descriptions-item :label="$t('server_ip')"
            >{{ selectedServer.host }}:{{
              selectedServer.port
            }}</el-descriptions-item
          >
          <el-descriptions-item :label="$t('kyuubi_instance')">{{
            selectedServer.instance || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('management.namespace')">{{
            selectedServer.namespace || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('management.node_name')">{{
            selectedServer.nodeName || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('version')">{{
            selectedServer.attributes?.version || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('management.server_status')">{{
            statusLabel(selectedServer.status)
          }}</el-descriptions-item>
        </el-descriptions>
        <div class="attribute-block"
          ><span>{{ $t('management.server_attributes') }}</span
          ><div
            v-for="(value, key) in selectedServer.attributes"
            :key="key"
            class="attribute-row"
            ><code>{{ key }}</code
            ><span>{{ value }}</span></div
          ></div
        >
      </template>
    </el-drawer>
  </section>
</template>

<script lang="ts" setup>
  import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
  import { format } from 'date-fns'
  import { useI18n } from 'vue-i18n'
  import { getAllServer } from '@/api/server'
  import { ServerData } from '@/api/server/types'

  const { t } = useI18n()
  const records = ref<ServerData[]>([])
  const loading = ref(false)
  const loadError = ref('')
  const updatedAt = ref<Date | null>(null)
  const refreshSeconds = ref(0)
  const hostFilter = ref('')
  const statusFilter = ref('')
  const detailVisible = ref(false)
  const selectedServer = ref<ServerData | null>(null)
  let refreshTimer: number | undefined

  const filteredRecords = computed(() =>
    records.value.filter(
      (record) =>
        (!hostFilter.value ||
          `${record.host} ${record.instance}`
            .toLowerCase()
            .includes(hostFilter.value.toLowerCase())) &&
        (!statusFilter.value || record.status === statusFilter.value)
    )
  )
  const runningCount = computed(
    () => records.value.filter((record) => record.status === 'Running').length
  )
  const versions = computed(
    () =>
      new Set(
        records.value
          .map((record) => record.attributes?.version)
          .filter(Boolean)
      ).size
  )
  const hosts = computed(
    () =>
      new Set(records.value.map((record) => record.host).filter(Boolean)).size
  )
  const summaryItems = computed(() => [
    {
      label: t('management.total_servers'),
      value: records.value.length,
      hint: t('management.servers_discovered_hint'),
      color: 'accent-purple'
    },
    {
      label: t('management.running_servers'),
      value: runningCount.value,
      hint: t('management.running_servers_hint'),
      color: 'accent-blue'
    },
    {
      label: t('management.server_versions'),
      value: versions.value,
      hint: t('management.server_versions_hint'),
      color: 'accent-cyan'
    },
    {
      label: t('management.server_hosts'),
      value: hosts.value,
      hint: t('management.server_hosts_hint'),
      color: 'accent-amber'
    }
  ])
  const errorMessage = (error: unknown) =>
    error instanceof Error ? error.message : t('management.load_failed')

  const loadServers = async () => {
    loading.value = true
    try {
      records.value = await getAllServer()
      loadError.value = ''
      updatedAt.value = new Date()
    } catch (error) {
      loadError.value = errorMessage(error)
    } finally {
      loading.value = false
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
        loadServers,
        refreshSeconds.value * 1000
      )
  }
  const resetFilters = () => {
    hostFilter.value = ''
    statusFilter.value = ''
    loadServers()
  }
  const statusLabel = (status?: string) =>
    status === 'Running'
      ? t('management.server_running')
      : t('management.server_unknown')
  const statusType = (status?: string) =>
    status === 'Running' ? 'success' : 'warning'
  const showDetail = (server: ServerData) => {
    selectedServer.value = server
    detailVisible.value = true
  }
  watch(refreshSeconds, setupRefreshTimer)
  onMounted(() => {
    loadServers()
    setupRefreshTimer()
  })
  onBeforeUnmount(clearRefreshTimer)
  defineExpose({
    records,
    filteredRecords,
    hostFilter,
    statusFilter,
    refreshSeconds,
    runningCount,
    versions,
    hosts,
    loadServers,
    resetFilters,
    statusLabel,
    showDetail
  })
</script>

<style scoped lang="scss">
  .server-page {
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
  .filter-host {
    width: 260px;
  }
  .filter-status {
    width: 170px;
  }
  .refresh-select {
    width: 160px;
    margin-left: auto;
  }
  .server-table :deep(.el-table__header th) {
    height: 44px;
    color: #76839b;
    background: #f8faff;
    font-size: 12px;
  }
  .server-table :deep(.el-table__row td) {
    padding: 14px 0;
  }
  .node-name {
    font:
      12px ui-monospace,
      SFMono-Regular,
      Menlo,
      monospace;
    color: #55637d;
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
  .attribute-block {
    margin-top: 20px;
  }
  .attribute-block > span {
    color: #77849b;
    font-size: 12px;
  }
  .attribute-row {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    padding: 9px 0;
    border-bottom: 1px solid #eef1f6;
    font-size: 12px;
  }
  .attribute-row code {
    color: #7254ff;
  }
  .attribute-row span {
    color: #43516b;
    word-break: break-all;
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
    .filter-host,
    .filter-status,
    .refresh-select {
      width: 100%;
    }
    .heading-actions {
      align-items: flex-start;
      flex-wrap: wrap;
    }
  }
</style>
