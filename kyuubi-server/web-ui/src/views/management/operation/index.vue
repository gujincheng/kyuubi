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
  <section class="operation-page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">{{ $t('management.workspace') }}</div>
        <h1>{{ $t('management.operations_title') }}</h1>
        <p>{{ $t('management.operations_subtitle') }}</p>
      </div>
      <div class="heading-actions">
        <el-tag :type="loadError ? 'danger' : 'success'" effect="light">{{
          loadError ? $t('management.data_error') : $t('management.live_data')
        }}</el-tag>
        <span v-if="updatedAt" class="updated-at">{{
          $t('management.updated_at', { time: format(updatedAt, 'HH:mm:ss') })
        }}</span>
        <el-button
          :loading="loading"
          icon="Refresh"
          @click="loadOperations()"
          >{{ $t('refresh') }}</el-button
        >
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
          @keyup.enter="loadOperations()" />
        <el-input
          v-model="filters.sessionHandle"
          clearable
          :placeholder="$t('management.filter_session_id')"
          class="filter-session"
          @keyup.enter="loadOperations()" />
        <el-select
          v-model="filters.state"
          clearable
          :placeholder="$t('management.filter_state')"
          class="filter-state"
          @change="loadOperations()">
          <el-option
            v-for="state in states"
            :key="state"
            :label="stateLabel(state)"
            :value="state" />
        </el-select>
        <el-select
          v-model="refreshSeconds"
          :placeholder="$t('management.refresh_interval')"
          class="refresh-select">
          <el-option :label="$t('management.refresh_off')" :value="0" />
          <el-option :label="$t('management.refresh_10s')" :value="10" />
          <el-option :label="$t('management.refresh_30s')" :value="30" />
        </el-select>
        <el-button type="primary" icon="Search" @click="loadOperations()">{{
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
        class="operation-table"
        empty-text="">
        <el-table-column :label="$t('management.operation_status')" width="130">
          <template #default="scope"
            ><el-tag :type="stateType(scope.row.state)" effect="light" round>{{
              stateLabel(scope.row.state)
            }}</el-tag></template
          >
        </el-table-column>
        <el-table-column :label="$t('operation_id')" min-width="250"
          ><template #default="scope"
            ><el-link type="primary" @click="showDetail(scope.row)">{{
              scope.row.identifier
            }}</el-link></template
          ></el-table-column
        >
        <el-table-column :label="$t('statement')" min-width="260"
          ><template #default="scope"
            ><span class="statement">{{
              scope.row.statement || '-'
            }}</span></template
          ></el-table-column
        >
        <el-table-column prop="sessionUser" :label="$t('user')" width="130" />
        <el-table-column
          :label="$t('management.session_id_short')"
          min-width="190"
          ><template #default="scope">{{
            scope.row.sessionId || '-'
          }}</template></el-table-column
        >
        <el-table-column
          :label="$t('management.operation_duration')"
          width="140"
          ><template #default="scope">{{
            operationDuration(scope.row)
          }}</template></el-table-column
        >
        <el-table-column :label="$t('failure_reason')" min-width="180"
          ><template #default="scope"
            ><span :class="{ 'has-error': scope.row.exception }">{{
              scope.row.exception || '-'
            }}</span></template
          ></el-table-column
        >
        <el-table-column
          fixed="right"
          :label="$t('operation.text')"
          width="150">
          <template #default="scope">
            <el-space>
              <el-button link type="primary" @click="showDetail(scope.row)">{{
                $t('management.details')
              }}</el-button>
              <el-popconfirm
                v-if="!isTerminalState(scope.row.state)"
                :title="$t('operation.cancel_confirm')"
                @confirm="operate(scope.row, 'CANCEL')"
                ><template #reference
                  ><el-button link type="danger">{{
                    $t('operation.cancel')
                  }}</el-button></template
                ></el-popconfirm
              >
              <el-popconfirm
                :title="$t('operation.close_confirm')"
                @confirm="operate(scope.row, 'CLOSE')"
                ><template #reference
                  ><el-button link>{{
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
            ><strong>{{ $t('management.no_operations') }}</strong
            ><span>{{ $t('management.no_operations_hint') }}</span></div
          ></template
        >
      </el-table>
    </el-card>

    <el-drawer
      v-model="detailVisible"
      :title="$t('management.operation_details')"
      size="480px">
      <template v-if="selectedOperation">
        <div class="detail-hero"
          ><div class="detail-avatar">SQL</div
          ><div
            ><strong>{{ stateLabel(selectedOperation.state) }}</strong
            ><span
              >{{ selectedOperation.sessionUser || '-' }} ·
              {{ selectedOperation.sessionType || '-' }}</span
            ></div
          ></div
        >
        <el-descriptions :column="1" border class="detail-list">
          <el-descriptions-item :label="$t('operation_id')">{{
            selectedOperation.identifier
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('session_id')">{{
            selectedOperation.sessionId || '-'
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('create_time')">{{
            formatTime(selectedOperation.createTime)
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('start_time')">{{
            formatTime(selectedOperation.startTime)
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('complete_time')">{{
            formatTime(selectedOperation.completeTime)
          }}</el-descriptions-item>
          <el-descriptions-item :label="$t('management.operation_duration')">{{
            operationDuration(selectedOperation)
          }}</el-descriptions-item>
        </el-descriptions>
        <div class="statement-block"
          ><span>{{ $t('statement') }}</span
          ><pre>{{ selectedOperation.statement || '-' }}</pre>
        </div>
        <el-alert
          v-if="selectedOperation.exception"
          type="error"
          show-icon
          class="detail-alert"
          ><template #title>{{ $t('failure_reason') }}</template
          ><template #default>{{
            selectedOperation.exception
          }}</template></el-alert
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
  import { useRoute } from 'vue-router'
  import {
    actionOnOperation,
    getAllOperations,
    OperationData,
    OperationSearchParams
  } from '@/api/operation'
  import { millTransfer } from '@/utils/unit'

  const { t } = useI18n()
  const route = useRoute()
  const records = ref<OperationData[]>([])
  const loading = ref(false)
  const loadError = ref('')
  const updatedAt = ref<Date | null>(null)
  const detailVisible = ref(false)
  const selectedOperation = ref<OperationData | null>(null)
  const refreshSeconds = ref(0)
  const filters = reactive<
    OperationSearchParams & { user?: string; state?: string }
  >({ user: '', sessionHandle: '', sessionType: '', state: '' })
  let refreshTimer: number | undefined
  const states = [
    'INITIALIZED_STATE',
    'PENDING_STATE',
    'RUNNING_STATE',
    'FINISHED_STATE',
    'ERROR_STATE',
    'CANCELED_STATE',
    'CLOSED_STATE',
    'TIMEDOUT_STATE'
  ]
  const terminalStates = new Set([
    'FINISHED_STATE',
    'CLOSED_STATE',
    'CANCELED_STATE',
    'TIMEOUT_STATE',
    'TIMEDOUT_STATE',
    'ERROR_STATE'
  ])

  const activeCount = computed(
    () =>
      records.value.filter((record) => !isTerminalState(record.state)).length
  )
  const runningCount = computed(
    () =>
      records.value.filter((record) => record.state === 'RUNNING_STATE').length
  )
  const failedCount = computed(
    () =>
      records.value.filter((record) => record.state === 'ERROR_STATE').length
  )
  const summaryItems = computed(() => [
    {
      label: t('management.total_operations'),
      value: records.value.length,
      hint: t('management.operations_live_hint'),
      color: 'accent-purple'
    },
    {
      label: t('management.active_operations'),
      value: activeCount.value,
      hint: t('management.active_operations_hint'),
      color: 'accent-blue'
    },
    {
      label: t('management.running_operations'),
      value: runningCount.value,
      hint: t('management.running_operations_hint'),
      color: 'accent-cyan'
    },
    {
      label: t('management.failed_operations'),
      value: failedCount.value,
      hint: t('management.failed_operations_hint'),
      color: 'accent-rose'
    }
  ])

  function isTerminalState(state?: string) {
    return !!state && terminalStates.has(state)
  }
  const stateLabel = (state?: string) =>
    state
      ? t(
          `management.state_${state.replace('_STATE', '').toLowerCase()}`,
          state.replace('_STATE', '')
        )
      : '-'
  const stateType = (state?: string) =>
    state === 'ERROR_STATE'
      ? 'danger'
      : state === 'RUNNING_STATE'
        ? 'primary'
        : state === 'PENDING_STATE' || state === 'INITIALIZED_STATE'
          ? 'warning'
          : 'success'
  const formatTime = (timestamp?: number | null) =>
    timestamp != null && timestamp > 0
      ? format(timestamp, 'yyyy-MM-dd HH:mm:ss')
      : '-'
  const operationDuration = (operation: OperationData) => {
    if (operation.startTime == null || operation.startTime < 0) return '-'
    const end =
      operation.completeTime != null && operation.completeTime > 0
        ? operation.completeTime
        : Date.now()
    return millTransfer(Math.max(0, end - operation.startTime))
  }
  const errorMessage = (error: unknown) =>
    error instanceof Error ? error.message : t('management.load_failed')

  const loadOperations = async (options: { silent?: boolean } = {}) => {
    if (!options.silent) loading.value = true
    try {
      const result = await getAllOperations({
        users: filters.user?.trim() || undefined,
        sessionHandle: filters.sessionHandle?.trim() || undefined,
        sessionType: filters.sessionType || undefined
      })
      const next = Array.isArray(result) ? result : []
      records.value = filters.state
        ? next.filter((record) => record.state === filters.state)
        : next
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
        () => loadOperations({ silent: true }),
        refreshSeconds.value * 1000
      )
  }
  const resetFilters = () => {
    filters.user = ''
    filters.sessionHandle = ''
    filters.sessionType = ''
    filters.state = ''
    loadOperations()
  }
  const showDetail = (record: OperationData) => {
    selectedOperation.value = record
    detailVisible.value = true
  }
  const operate = async (record: OperationData, action: 'CANCEL' | 'CLOSE') => {
    try {
      await actionOnOperation(record.identifier, { action })
      ElMessage({
        message: t(`message.${action.toLowerCase()}_succeeded`, {
          operationId: record.identifier
        }),
        type: 'success'
      })
      await loadOperations()
    } catch (error) {
      ElMessage({ message: errorMessage(error), type: 'error' })
    }
  }
  watch(refreshSeconds, setupRefreshTimer)
  onMounted(() => {
    const sessionId = route.query.sessionId
    if (typeof sessionId === 'string') filters.sessionHandle = sessionId
    loadOperations()
    setupRefreshTimer()
  })
  onBeforeUnmount(clearRefreshTimer)
  defineExpose({
    records,
    filters,
    refreshSeconds,
    activeCount,
    runningCount,
    failedCount,
    loadOperations,
    resetFilters,
    stateLabel,
    operationDuration,
    showDetail,
    operate,
    isTerminalState
  })
</script>

<style scoped lang="scss">
  .operation-page {
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
  .filter-card,
  .table-card {
    border: 1px solid #e7ebf4;
    border-radius: 16px;
  }
  .filter-card {
    margin-bottom: 14px;
  }
  .filter-user {
    width: 180px;
  }
  .filter-session {
    width: 250px;
  }
  .filter-state {
    width: 180px;
  }
  .refresh-select {
    width: 160px;
    margin-left: auto;
  }
  .operation-table :deep(.el-table__header th) {
    height: 44px;
    color: #76839b;
    background: #f8faff;
    font-size: 12px;
  }
  .operation-table :deep(.el-table__row td) {
    padding: 14px 0;
  }
  .statement {
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
  .has-error {
    color: #d94f67;
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
  .statement-block {
    margin-top: 18px;
  }
  .statement-block > span {
    color: #77849b;
    font-size: 12px;
  }
  pre {
    overflow: auto;
    margin: 8px 0 0;
    padding: 14px;
    border-radius: 10px;
    color: #dce4ff;
    background: #1d2638;
    font:
      12px/1.6 ui-monospace,
      SFMono-Regular,
      Menlo,
      monospace;
    white-space: pre-wrap;
  }
  .detail-alert {
    margin-top: 18px;
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
    .filter-user,
    .filter-session,
    .filter-state,
    .refresh-select {
      width: 100%;
    }
    .heading-actions {
      align-items: flex-start;
      flex-wrap: wrap;
    }
  }
</style>
