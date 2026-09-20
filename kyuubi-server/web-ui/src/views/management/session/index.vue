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
<template>
  <section class="session-page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">{{ $t('management.workspace') }}</div>
        <h1>{{ $t('management.sessions_title') }}</h1>
        <p>{{ $t('management.sessions_subtitle') }}</p>
      </div>
      <div class="heading-actions">
        <el-tag :type="loadError ? 'danger' : 'success'" effect="light">
          {{
            loadError ? $t('management.data_error') : $t('management.live_data')
          }}
        </el-tag>
        <span v-if="updatedAt" class="updated-at">{{
          $t('management.updated_at', { time: format(updatedAt, 'HH:mm:ss') })
        }}</span>
        <el-button :loading="loading" icon="Refresh" @click="loadSessions()">{{
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
          v-model="filters.user"
          clearable
          :placeholder="$t('management.filter_user')"
          class="filter-user"
          @keyup.enter="loadSessions()" />
        <el-select
          v-model="filters.sessionType"
          clearable
          :placeholder="$t('management.filter_session_type')"
          class="filter-type"
          @change="loadSessions()">
          <el-option
            :label="$t('management.interactive_session')"
            value="INTERACTIVE" />
          <el-option :label="$t('management.batch_session')" value="BATCH" />
        </el-select>
        <el-select
          v-model="refreshSeconds"
          :placeholder="$t('management.refresh_interval')"
          class="refresh-select">
          <el-option :label="$t('management.refresh_off')" :value="0" />
          <el-option :label="$t('management.refresh_10s')" :value="10" />
          <el-option :label="$t('management.refresh_30s')" :value="30" />
        </el-select>
        <el-button type="primary" icon="Search" @click="loadSessions()">{{
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
        row-key="identifier"
        class="session-table"
        empty-text="">
        <el-table-column :label="$t('management.session_status')" width="130">
          <template #default="scope"
            ><el-tag :type="statusType(scope.row)" effect="light" round>{{
              statusLabel(scope.row)
            }}</el-tag></template
          >
        </el-table-column>
        <el-table-column :label="$t('session_id')" min-width="260">
          <template #default="scope">
            <el-link type="primary" @click="showDetail(scope.row)">{{
              scope.row.identifier
            }}</el-link>
            <span class="session-name">{{ scope.row.sessionName || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="user" :label="$t('user')" width="140" />
        <el-table-column :label="$t('management.session_type')" width="140"
          ><template #default="scope"
            ><el-tag size="small" effect="plain">{{
              scope.row.sessionType || '-'
            }}</el-tag></template
          ></el-table-column
        >
        <el-table-column :label="$t('engine_id')" min-width="220"
          ><template #default="scope">{{
            scope.row.engineId || $t('management.unbound')
          }}</template></el-table-column
        >
        <el-table-column :label="$t('management.operations')" width="110"
          ><template #default="scope">{{
            scope.row.totalOperations ?? 0
          }}</template></el-table-column
        >
        <el-table-column :label="$t('management.session_duration')" width="140"
          ><template #default="scope">{{
            formatDuration(scope.row.duration)
          }}</template></el-table-column
        >
        <el-table-column :label="$t('management.last_idle')" width="140"
          ><template #default="scope">{{
            formatDuration(scope.row.idleTime)
          }}</template></el-table-column
        >
        <el-table-column :label="$t('client_ip')" width="150"
          ><template #default="scope">{{
            scope.row.ipAddr || '-'
          }}</template></el-table-column
        >
        <el-table-column
          fixed="right"
          :label="$t('operation.text')"
          width="130">
          <template #default="scope">
            <el-space>
              <el-button link type="primary" @click="showDetail(scope.row)">{{
                $t('management.details')
              }}</el-button>
              <el-popconfirm
                :title="$t('management.close_session_confirm')"
                @confirm="closeSession(scope.row)"
                ><template #reference
                  ><el-button link type="danger">{{
                    $t('operation.close')
                  }}</el-button></template
                ></el-popconfirm
              >
            </el-space>
          </template>
        </el-table-column>
        <template #empty
          ><div class="empty-state"
            ><div class="empty-icon">◌</div
            ><strong>{{ $t('management.no_sessions') }}</strong
            ><span>{{ $t('management.no_sessions_hint') }}</span></div
          ></template
        >
      </el-table>
    </el-card>

    <el-drawer
      v-model="detailVisible"
      :title="$t('management.session_details')"
      size="460px">
      <template v-if="selectedSession">
        <div class="detail-hero"
          ><div class="detail-avatar">{{
            (selectedSession.user || '?').slice(0, 1).toUpperCase()
          }}</div
          ><div
            ><strong>{{ selectedSession.user || '-' }}</strong
            ><span
              >{{ selectedSession.sessionType || '-' }} ·
              {{ statusLabel(selectedSession) }}</span
            ></div
          ></div
        >
        <el-descriptions :column="1" border class="detail-list">
          <el-descriptions-item :label="$t('session_id')">{{
            selectedSession.identifier
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('engine_id')">{{
            selectedSession.engineId || $t('management.unbound')
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('engine_address')">{{
            selectedSession.engineName || selectedSession.engineUrl || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('kyuubi_instance')">{{
            selectedSession.kyuubiInstance || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('client_ip')">{{
            selectedSession.ipAddr || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('create_time')">{{
            formatTime(selectedSession.createTime)
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('management.session_duration')">{{
            formatDuration(selectedSession.duration)
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('management.operations')">{{
            selectedSession.totalOperations ?? 0
          }}</el-descriptions-item>
        </el-descriptions>
        <el-alert
          v-if="selectedSession.exception"
          type="error"
          show-icon
          class="detail-alert"
          ><template #title>{{ $t('failure_reason') }}</template
          ><template #default>{{
            selectedSession.exception
          }}</template></el-alert
        >
        <el-button
          class="detail-action"
          type="primary"
          plain
          @click="jumpToOperations"
          >{{ $t('management.view_operations') }}</el-button
        >
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
  import { useRouter } from 'vue-router'
  import {
    closeSession as closeSessionApi,
    getAllSessions,
    SessionData,
    SessionSearchParams
  } from '@/api/session'
  import { millTransfer } from '@/utils/unit'

  const { t } = useI18n()
  const router = useRouter()
  const records = ref<SessionData[]>([])
  const loading = ref(false)
  const loadError = ref('')
  const updatedAt = ref<Date | null>(null)
  const detailVisible = ref(false)
  const selectedSession = ref<SessionData | null>(null)
  const refreshSeconds = ref(0)
  const filters = reactive<SessionSearchParams>({ user: '', sessionType: '' })
  let refreshTimer: number | undefined

  const activeUsers = computed(
    () =>
      new Set(records.value.map((record) => record.user).filter(Boolean)).size
  )
  const boundEngines = computed(
    () =>
      new Set(records.value.map((record) => record.engineId).filter(Boolean))
        .size
  )
  const totalOperations = computed(() =>
    records.value.reduce(
      (total, record) => total + (record.totalOperations || 0),
      0
    )
  )
  const summaryItems = computed(() => [
    {
      label: t('management.total_sessions'),
      value: records.value.length,
      hint: t('management.sessions_live_hint'),
      color: 'accent-purple'
    },
    {
      label: t('management.active_users'),
      value: activeUsers.value,
      hint: t('management.distinct_users_hint'),
      color: 'accent-blue'
    },
    {
      label: t('management.bound_engines'),
      value: boundEngines.value,
      hint: t('management.bound_engines_hint'),
      color: 'accent-cyan'
    },
    {
      label: t('management.total_operations'),
      value: totalOperations.value,
      hint: t('management.operations_in_sessions'),
      color: 'accent-amber'
    }
  ])

  const errorMessage = (error: unknown) =>
    error instanceof Error ? error.message : t('management.load_failed')

  const loadSessions = async (options: { silent?: boolean } = {}) => {
    if (!options.silent) loading.value = true
    try {
      const result = await getAllSessions({
        users: filters.user?.trim() || undefined,
        sessionType: filters.sessionType || undefined
      })
      records.value = Array.isArray(result) ? result : []
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
        () => loadSessions({ silent: true }),
        refreshSeconds.value * 1000
      )
  }
  const resetFilters = () => {
    filters.user = ''
    filters.sessionType = ''
    loadSessions()
  }
  const statusLabel = (record: SessionData) =>
    record.exception
      ? t('management.session_error')
      : (record.idleTime || 0) > 5 * 60 * 1000
        ? t('management.session_idle')
        : t('management.session_active')
  const statusType = (record: SessionData) =>
    record.exception
      ? 'danger'
      : (record.idleTime || 0) > 5 * 60 * 1000
        ? 'warning'
        : 'success'
  const formatDuration = (duration?: number | null) =>
    duration != null && duration >= 0 ? millTransfer(duration) : '-'
  const formatTime = (timestamp?: number | null) =>
    timestamp != null && timestamp > 0
      ? format(timestamp, 'yyyy-MM-dd HH:mm:ss')
      : '-'
  const showDetail = (record: SessionData) => {
    selectedSession.value = record
    detailVisible.value = true
  }

  const closeSession = async (record: SessionData) => {
    try {
      await closeSessionApi(record.identifier)
      ElMessage({
        message: t('message.close_succeeded', { name: 'session' }),
        type: 'success'
      })
      await loadSessions()
      if (selectedSession.value?.identifier === record.identifier)
        detailVisible.value = false
    } catch (error) {
      ElMessage({ message: errorMessage(error), type: 'error' })
    }
  }
  const jumpToOperations = () => {
    if (selectedSession.value)
      router.push({
        path: '/management/operation',
        query: { sessionId: selectedSession.value.identifier }
      })
  }

  watch(refreshSeconds, setupRefreshTimer)
  onMounted(() => {
    loadSessions()
    setupRefreshTimer()
  })
  onBeforeUnmount(clearRefreshTimer)
  defineExpose({
    records,
    filters,
    refreshSeconds,
    activeUsers,
    boundEngines,
    totalOperations,
    loadSessions,
    resetFilters,
    statusLabel,
    formatDuration,
    showDetail,
    closeSession
  })
</script>

<style scoped lang="scss">
  .session-page {
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
    background: linear-gradient(145deg, #ffffff, #f8faff);
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
  .filter-user {
    width: 260px;
  }
  .filter-type {
    width: 190px;
  }
  .refresh-select {
    width: 160px;
    margin-left: auto;
  }
  .session-table :deep(.el-table__header th) {
    height: 44px;
    color: #76839b;
    background: #f8faff;
    font-size: 12px;
  }
  .session-table :deep(.el-table__row td) {
    padding: 14px 0;
  }
  .session-name {
    display: block;
    margin-top: 4px;
    color: #98a3b7;
    font-size: 11px;
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
  .detail-alert {
    margin-top: 18px;
  }
  .detail-action {
    width: 100%;
    margin-top: 18px;
  }
  @media (max-width: 1100px) {
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
  }
  @media (max-width: 700px) {
    .summary-grid {
      grid-template-columns: 1fr;
    }
    .filter-row {
      align-items: stretch;
      flex-wrap: wrap;
    }
    .filter-user,
    .filter-type,
    .refresh-select {
      width: 100%;
      margin-left: 0;
    }
    .heading-actions {
      align-items: flex-start;
      flex-wrap: wrap;
    }
  }
</style>
