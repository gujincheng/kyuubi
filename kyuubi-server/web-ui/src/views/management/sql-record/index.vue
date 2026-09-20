<!--
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
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
  <main class="sql-record-page">
    <section class="page-hero">
      <div>
        <p class="eyebrow">{{ $t('sql_record.eyebrow') }}</p>
        <h1>{{ $t('sql_record.workspace_title') }}</h1>
        <p class="subtitle">{{ $t('sql_record.subtitle') }}</p>
      </div>
      <div class="hero-actions">
        <div class="hero-summary">
          <span class="summary-dot" />
          <span class="summary-value">{{ total }}</span>
          <span class="summary-label">{{ $t('sql_record.total') }}</span>
        </div>
        <el-button class="audit-config-button" @click="openAuditConfiguration">
          <el-icon><Setting /></el-icon>{{ $t('sql_record.audit_config') }}
        </el-button>
      </div>
    </section>

    <el-card class="filter-card" shadow="never">
        <el-form :inline="true" @submit.prevent="search">
          <el-form-item>
            <el-input
              v-model="filters.keyword"
              clearable
              :placeholder="$t('sql_record.keyword')"
              class="keyword-input"
              @keyup.enter="search">
              <template #prefix
                ><el-icon><Search /></el-icon
              ></template>
            </el-input>
          </el-form-item>
          <el-form-item>
            <el-input
              v-model="filters.user"
              clearable
              :placeholder="$t('sql_record.user')"
              @keyup.enter="search" />
          </el-form-item>
          <el-form-item>
            <el-select
              v-model="filters.eventType"
              clearable
              :placeholder="$t('sql_record.all_event_types')"
              class="state-select">
              <el-option
                v-for="item in eventTypeOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-select
              v-model="filters.state"
              clearable
              :placeholder="$t('sql_record.all_states')"
              class="state-select">
              <el-option
                v-for="item in stateOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-date-picker
              v-model="filters.timeRange"
              type="datetimerange"
              clearable
              :start-placeholder="$t('sql_record.start_time')"
              :end-placeholder="$t('sql_record.end_time')"
              :range-separator="$t('sql_record.to')"
              format="MM-DD HH:mm:ss.SSS"
              :default-time="[
                new Date(2000, 1, 1, 0, 0, 0),
                new Date(2000, 1, 1, 23, 59, 59)
              ]"
              class="time-range" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="loading" @click="search">
              <el-icon><Search /></el-icon>{{ $t('sql_record.search') }}
            </el-button>
            <el-button @click="resetFilters">{{
              $t('sql_record.reset')
            }}</el-button>
          </el-form-item>
        </el-form>
    </el-card>

    <el-card class="records-card" shadow="never">
        <div class="card-heading">
          <div>
            <span class="heading-kicker">{{ auditSourceLabel }}</span>
            <h2>{{ $t('sql_record.activity_title') }}</h2>
          </div>
          <div class="live-controls">
            <el-select
              v-model="refreshIntervalSeconds"
              class="refresh-select"
              size="small"
              @change="restartRefreshTimer">
              <el-option
                v-for="option in refreshOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value" />
            </el-select>
            <span v-if="lastUpdated" class="last-updated">
              {{
                $t('sql_record.updated_at', { time: formatTime(lastUpdated) })
              }}
            </span>
            <el-button
              link
              type="primary"
              :loading="exporting"
              @click="exportRecords">
              <el-icon><Download /></el-icon>{{ $t('sql_record.export') }}
            </el-button>
            <el-button
              link
              type="primary"
              :loading="loading"
              @click="loadRecords">
              <el-icon><Refresh /></el-icon>{{ $t('sql_record.refresh') }}
            </el-button>
          </div>
        </div>

        <el-alert
          v-if="loadError"
          class="load-alert"
          :title="loadError"
          type="warning"
          show-icon
          closable
          @close="loadError = ''" />

        <el-alert
          v-if="!auditEnabled"
          class="load-alert"
          :title="$t('sql_record.audit_required')"
          :description="$t('sql_record.audit_required_hint')"
          type="warning"
          show-icon
          :closable="false" />

      <el-table
          v-loading="loading"
          :data="records"
          class="records-table"
          row-key="id">
          <el-table-column :label="$t('sql_record.activity')" min-width="360">
            <template #default="scope">
              <div class="statement-cell">
                <span class="statement-mark" />
                <el-tooltip
                  :content="scope.row.statement"
                  placement="top"
                  :show-after="300">
                  <span class="statement-text">{{
                    statementSummary(scope.row.statement)
                  }}</span>
                </el-tooltip>
                <small>{{ scope.row.id }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="$t('sql_record.event_type')" width="150">
            <template #default="scope">
              <el-tag effect="plain" round>{{ eventTypeLabel(scope.row.eventType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="$t('sql_record.state')" width="130">
            <template #default="scope">
              <el-tag
                :type="stateTagType(scope.row.state)"
                effect="light"
                round>
                {{ stateLabel(scope.row.state) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            prop="user"
            :label="$t('sql_record.user')"
            width="140" />
          <el-table-column
            prop="engineType"
            :label="$t('sql_record.engine')"
            width="120">
            <template #default="scope">{{
              scope.row.engineType || '-'
            }}</template>
          </el-table-column>
          <el-table-column :label="$t('sql_record.created')" width="190">
            <template #default="scope">{{
              formatTime(scope.row.createTime)
            }}</template>
          </el-table-column>
          <el-table-column :label="$t('sql_record.event_count')" width="105">
            <template #default="scope">{{
              scope.row.eventCount
            }}</template>
          </el-table-column>
          <el-table-column :label="$t('sql_record.execution_time')" width="130">
            <template #default="scope">{{ formatDuration(scope.row.duration) }}</template>
          </el-table-column>
          <el-table-column :label="$t('sql_record.diagnosis')" min-width="160">
            <template #default="scope">
              <div class="diagnosis-cell">
                <el-tag
                  v-if="scope.row.error"
                  type="danger"
                  effect="light"
                  round>
                  {{ $t('sql_record.has_error') }}
                </el-tag>
                <span v-else class="diagnosis-ok">
                  {{ diagnosisLabel(scope.row.state) }}
                </span>
                <el-tooltip
                  v-if="scope.row.error"
                  :content="scope.row.error"
                  placement="top"
                  :show-after="300">
                  <span class="diagnosis-message">{{
                    scope.row.error
                  }}</span>
                </el-tooltip>
              </div>
            </template>
          </el-table-column>
          <el-table-column
            fixed="right"
            :label="$t('operation.text')"
            width="100">
            <template #default="scope">
              <el-button link type="primary" @click="showDetail(scope.row)">
                {{ $t('sql_record.detail') }}
              </el-button>
            </template>
          </el-table-column>
      </el-table>

        <el-empty
          v-if="!loading && records.length === 0"
          :description="$t('sql_record.no_activities')">
          <p class="empty-hint">{{ $t('sql_record.no_records_hint') }}</p>
        </el-empty>

        <div v-if="total > 0" class="pagination-row">
          <span>{{ total }} {{ $t('sql_record.total') }}</span>
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            background
            layout="prev, pager, next, sizes"
            :total="total"
            :page-sizes="[10, 20, 50]"
            @current-change="loadRecords"
            @size-change="handleSizeChange" />
        </div>
    </el-card>

    <el-drawer
        v-model="detailVisible"
        :title="$t('sql_record.detail_title')"
        size="min(680px, 92vw)">
        <template v-if="selectedRecord">
          <div class="detail-status">
            <el-tag
              :type="stateTagType(selectedRecord.state)"
              effect="light"
              round>
              {{ stateLabel(selectedRecord.state) }}
            </el-tag>
            <span
              >{{ selectedRecord.user }} ·
              {{ selectedRecord.engineType || 'UNKNOWN' }}</span
            >
          </div>
          <div class="detail-grid">
            <div
              ><label>{{ $t('sql_record.created') }}</label
              ><strong>{{ formatTime(selectedRecord.createTime) }}</strong></div
            >
            <div
              ><label>{{ $t('start_time') }}</label
              ><strong>{{ formatTime(selectedRecord.startTime) }}</strong></div
            >
            <div
              ><label>{{ $t('complete_time') }}</label
              ><strong>{{
                formatTime(selectedRecord.completeTime)
              }}</strong></div
            >
            <div
              ><label>{{ $t('sql_record.execution_time') }}</label
              ><strong>{{
                formatDuration(selectedRecord.duration)
              }}</strong></div
            >
            <div
              ><label>{{ $t('sql_record.event_count') }}</label
              ><strong>{{ selectedRecord.eventCount }}</strong></div
            >
            <div
              ><label>{{ $t('sql_record.total_time') }}</label
              ><strong>{{
                formatDuration(totalDuration(selectedRecord))
              }}</strong></div
            >
            <div
              ><label>{{ $t('sql_record.session') }}</label
              ><strong class="breakable">{{
                selectedRecord.sessionId
              }}</strong></div
            >
          </div>
          <section class="detail-section timing-section">
            <div class="section-heading">
              <label>{{ $t('sql_record.timeline') }}</label>
              <span>{{ durationHint(selectedRecord) }}</span>
            </div>
            <div class="execution-timeline">
              <div class="timeline-item">
                <span class="timeline-dot created" />
                <div>
                  <strong>{{ $t('sql_record.created') }}</strong>
                  <small>{{ formatTime(selectedRecord.createTime) }}</small>
                </div>
              </div>
              <div class="timeline-line" />
              <div class="timeline-item">
                <span
                  class="timeline-dot started"
                  :class="{ muted: selectedRecord.startTime <= 0 }" />
                <div>
                  <strong>{{ $t('start_time') }}</strong>
                  <small>{{ formatTime(selectedRecord.startTime) }}</small>
                </div>
              </div>
              <div class="timeline-line" />
              <div class="timeline-item">
                <span
                  class="timeline-dot completed"
                  :class="{ muted: selectedRecord.completeTime <= 0 }" />
                <div>
                  <strong>{{ $t('complete_time') }}</strong>
                  <small>{{ formatTime(selectedRecord.completeTime) }}</small>
                </div>
              </div>
            </div>
          </section>
          <section class="detail-section audit-trail-section">
            <div class="section-heading">
              <label>{{ $t('sql_record.audit_trail') }}</label>
              <span>{{ $t('sql_record.audit_trail_hint') }}</span>
            </div>
            <el-skeleton v-if="auditTrailLoading" :rows="2" animated />
            <el-alert
              v-else-if="auditTrailError"
              :title="auditTrailError"
              type="warning"
              show-icon
              :closable="false" />
            <el-timeline v-else-if="auditTrail.length" class="audit-timeline">
              <el-timeline-item
                v-for="event in auditTrail"
                :key="event.id"
                :timestamp="formatTime(event.eventTime)"
                placement="top">
                <div class="audit-event">
                  <el-tag effect="light" round>
                    {{ auditStatusLabel(event.status) }}
                  </el-tag>
                  <strong>{{ event.statement || event.eventType }}</strong>
                  <small>{{ event.operationId || event.id }}</small>
                </div>
              </el-timeline-item>
            </el-timeline>
            <el-empty
              v-else
              :image-size="64"
              :description="$t('sql_record.audit_trail_empty')" />
          </section>
          <section class="detail-section">
            <label>{{ $t('sql_record.full_statement') }}</label>
            <pre>{{ selectedRecord.statement }}</pre>
          </section>
          <section class="detail-section error-section">
            <label>{{ $t('sql_record.error') }}</label>
            <pre>{{
              selectedRecord.error || $t('sql_record.no_error_detail')
            }}</pre>
          </section>
        </template>
    </el-drawer>
    <EventAudit ref="auditConfiguration" configuration-only />
  </main>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
  import { useI18n } from 'vue-i18n'
  import { useRoute } from 'vue-router'
  import { ElMessage } from 'element-plus'
  import { Download, Refresh, Search, Setting } from '@element-plus/icons-vue'
  import { format } from 'date-fns'
  import {
    getNativeAuditActivities,
    getNativeAuditEvents,
    type NativeAuditActivity,
    type NativeAuditActivityQuery,
    type NativeAuditEvent
  } from '@/api/audit'
  import { millTransfer } from '@/utils/unit'
  import EventAudit from '@/views/management/event-audit/index.vue'

  const { t } = useI18n()
  const route = useRoute()
  const loading = ref(false)
  const records = ref<NativeAuditActivity[]>([])
  const total = ref(0)
  const auditEnabled = ref(true)
  const auditSource = ref('')
  const page = ref(1)
  const pageSize = ref(20)
  const detailVisible = ref(false)
  const selectedRecord = ref<NativeAuditActivity | null>(null)
  const auditConfiguration = ref<InstanceType<typeof EventAudit> | null>(null)
  const refreshIntervalSeconds = ref(30)
  const lastUpdated = ref<Date | null>(null)
  const loadError = ref('')
  const exporting = ref(false)
  const auditTrail = ref<NativeAuditEvent[]>([])
  const auditTrailLoading = ref(false)
  const auditTrailError = ref('')
  let refreshTimer: number | undefined
  let refreshInFlight = false
  const filters = reactive({
    keyword: '',
    user: '',
    eventType: '',
    state: typeof route.query.state === 'string' ? route.query.state : '',
    timeRange: undefined as [Date, Date] | undefined
  })

  const stateOptions = computed(() => [
    { value: 'RUNNING_STATE', label: t('sql_record.running') },
    { value: 'FINISHED_STATE', label: t('sql_record.finished') },
    { value: 'ERROR_STATE', label: t('sql_record.error_state') },
    { value: 'CANCELED_STATE', label: t('sql_record.canceled') },
    { value: 'CLOSED_STATE', label: t('sql_record.closed') },
    { value: 'PENDING_STATE', label: t('sql_record.waiting') }
  ])

  const eventTypeOptions = computed(() => [
    { value: 'kyuubi_operation', label: t('sql_record.operation_event') },
    { value: 'kyuubi_session', label: t('sql_record.session_event') },
    { value: 'kyuubi_server_info', label: t('sql_record.server_event') },
    { value: 'sql_blocked', label: t('sql_record.blocked_sql_event') }
  ])

  const refreshOptions = computed(() => [
    { label: t('sql_record.refresh_5s'), value: 5 },
    { label: t('sql_record.refresh_10s'), value: 10 },
    { label: t('sql_record.refresh_30s'), value: 30 },
    { label: t('sql_record.refresh_off'), value: 0 }
  ])

  const auditSourceLabel = computed(() =>
    t('sql_record.audit_source', { source: auditSource.value || '-' })
  )

  const query = (): NativeAuditActivityQuery => ({
    page: page.value,
    pageSize: pageSize.value,
    keyword: filters.keyword || undefined,
    user: filters.user || undefined,
    eventType: filters.eventType || undefined,
    status: filters.state || undefined,
    from: filters.timeRange?.[0]?.getTime(),
    to: filters.timeRange?.[1]?.getTime()
  })

  const loadRecords = async () => {
    if (refreshInFlight) return
    refreshInFlight = true
    loading.value = true
    try {
      const result = await getNativeAuditActivities(query())
      records.value = result.records || []
      total.value = result.total || 0
      auditEnabled.value = result.auditEnabled !== false
      auditSource.value = result.source || ''
      lastUpdated.value = new Date()
      loadError.value = ''
    } catch (error) {
      loadError.value =
        error instanceof Error && error.message
          ? error.message
          : t('sql_record.loading_failed')
      ElMessage.error(t('sql_record.loading_failed'))
    } finally {
      loading.value = false
      refreshInFlight = false
    }
  }

  const restartRefreshTimer = () => {
    if (refreshTimer) window.clearInterval(refreshTimer)
    refreshTimer = undefined
    if (refreshIntervalSeconds.value > 0) {
      refreshTimer = window.setInterval(
        loadRecords,
        refreshIntervalSeconds.value * 1000
      )
    }
  }

  const csvValue = (value: unknown): string =>
    `"${String(value ?? '')
      .replace(/"/g, '""')
      .replace(/[\r\n]+/g, ' ')}"`

  const exportRecords = async () => {
    exporting.value = true
    try {
      const result = await getNativeAuditActivities({
        ...query(),
        page: 1,
        pageSize: 200
      })
      const header = [
        'id',
        'statement',
        'eventType',
        'eventTypes',
        'user',
        'sessionId',
        'operationId',
        'engineType',
        'state',
        'createTime',
        'startTime',
        'completeTime',
        'duration',
        'error',
        'eventCount'
      ]
      const rows = result.records.map((item) =>
        [
          item.id,
          item.statement,
          item.eventType,
          item.eventTypes.join('|'),
          item.user,
          item.sessionId,
          item.operationId,
          item.engineType,
          item.state,
          item.createTime,
          item.startTime,
          item.completeTime,
          item.duration,
          item.error,
          item.eventCount
        ]
          .map(csvValue)
          .join(',')
      )
      const blob = new Blob(
        [`\uFEFF${[header.map(csvValue).join(','), ...rows].join('\r\n')}`],
        { type: 'text/csv;charset=utf-8' }
      )
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `query-audit-${format(new Date(), 'yyyyMMdd-HHmmss')}.csv`
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
      if (result.total > result.records.length) {
        ElMessage.warning(t('sql_record.export_limit'))
      }
    } catch {
      ElMessage.error(t('sql_record.export_failed'))
    } finally {
      exporting.value = false
    }
  }

  const search = () => {
    page.value = 1
    loadRecords()
  }

  const resetFilters = () => {
    filters.keyword = ''
    filters.user = ''
    filters.eventType = ''
    filters.state = ''
    filters.timeRange = undefined
    search()
  }

  const handleSizeChange = (size: number) => {
    pageSize.value = size
    page.value = 1
    loadRecords()
  }

  const formatTime = (value: number | Date) => {
    if (value instanceof Date) return format(value, 'HH:mm:ss.SSS')
    if (!value || value <= 0) return '-'
    return format(new Date(value), 'MM-dd HH:mm:ss.SSS')
  }

  const formatDuration = (value: number) => {
    if (value === undefined || value === null || value < 0) return '-'
    return value < 1000 ? `${value} ms` : millTransfer(value)
  }

  const totalDuration = (record: NativeAuditActivity) => {
    if (!record.createTime || record.createTime < 0) return -1
    const endTime = record.completeTime > 0 ? record.completeTime : Date.now()
    return Math.max(0, endTime - record.createTime)
  }

  const diagnosisLabel = (state: string) => {
    if (state === 'RUNNING_STATE') return t('sql_record.in_progress')
    if (state === 'PENDING_STATE') return t('sql_record.queued')
    return t('sql_record.no_error')
  }

  const durationHint = (record: NativeAuditActivity) =>
    record.completeTime > 0
      ? t('sql_record.duration_complete')
      : t('sql_record.duration_in_progress')

  const stateLabel = (state: string) => {
    const option = stateOptions.value.find((item) => item.value === state)
    return option?.label || state.replace('_STATE', '')
  }

  const stateTagType = (state: string) => {
    if (state === 'ERROR_STATE') return 'danger'
    if (state === 'CANCELED_STATE') return 'warning'
    if (state === 'RUNNING_STATE') return 'primary'
    return 'success'
  }

  const auditStatusLabel = (status: string) =>
    status ? status.replace(/_STATE$/, '') : '-'

  const statementSummary = (statement: string) =>
    statement.length > 240 ? `${statement.slice(0, 237)}...` : statement || '-'

  const eventTypeLabel = (eventType: string) => {
    const option = eventTypeOptions.value.find((item) => item.value === eventType)
    return option?.label || eventType.replace(/^kyuubi_/, '').replaceAll('_', ' ')
  }

  const loadAuditTrail = async (record: NativeAuditActivity) => {
    auditTrailLoading.value = true
    auditTrailError.value = ''
    auditTrail.value = []
    try {
      const result = await getNativeAuditEvents({
        eventType: record.eventType,
        operationId: record.operationId || undefined,
        sessionId: record.sessionId || undefined,
        from: Math.max(0, record.createTime - 60_000),
        to:
          (record.completeTime > 0 ? record.completeTime : Date.now()) + 60_000,
        limit: 100
      })
      auditTrail.value = result.records || []
    } catch {
      auditTrailError.value = t('sql_record.audit_trail_failed')
    } finally {
      auditTrailLoading.value = false
    }
  }

  const showDetail = async (record: NativeAuditActivity) => {
    selectedRecord.value = record
    detailVisible.value = true
    await loadAuditTrail(record)
  }

  const openAuditConfiguration = () => auditConfiguration.value?.openConfiguration()

  onMounted(() => {
    loadRecords()
    restartRefreshTimer()
  })

  onBeforeUnmount(() => {
    if (refreshTimer) window.clearInterval(refreshTimer)
  })
</script>

<style lang="scss" scoped>
  .sql-record-page {
    min-height: 100%;
    padding: 24px 28px 32px;
    background: linear-gradient(180deg, #f7f9ff 0, #f5f7fb 40%, #f4f6fa 100%);
    color: #17213b;
  }

  .page-hero,
  .card-heading,
  .pagination-row,
  .detail-status {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .page-hero {
    margin-bottom: 20px;
  }
  .hero-actions {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 12px;
  }
  .workspace-switch {
    display: inline-flex;
    min-width: 232px;
    flex-wrap: nowrap;
    padding: 4px;
    border: 1px solid #e3e7f1;
    border-radius: 14px;
    background: rgba(255, 255, 255, 0.82);
    box-shadow: 0 8px 24px rgba(48, 58, 89, 0.06);
  }
  .workspace-switch :deep(.el-radio-button__inner) {
    min-width: 112px;
    padding: 9px 16px;
    border: 0;
    border-radius: 10px;
    background: transparent;
    box-shadow: none;
    color: #707b94;
    font-weight: 650;
  }
  .workspace-switch :deep(.el-radio-button:first-child .el-radio-button__inner),
  .workspace-switch :deep(.el-radio-button:last-child .el-radio-button__inner) {
    border-radius: 10px;
  }
  .workspace-switch
    :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
    background: linear-gradient(135deg, #7356ff, #5f45ec);
    box-shadow: 0 6px 15px rgba(105, 77, 245, 0.24);
    color: #fff;
  }
  .eyebrow,
  .heading-kicker {
    margin: 0 0 7px;
    color: #7356ff;
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.14em;
  }
  h1 {
    margin: 0;
    font-size: clamp(24px, 3vw, 34px);
    letter-spacing: -0.035em;
  }
  h2 {
    margin: 0;
    font-size: 18px;
  }
  .subtitle {
    margin: 8px 0 0;
    color: #7e8aa8;
    font-size: 13px;
  }
  .hero-summary {
    display: flex;
    align-items: baseline;
    gap: 8px;
    padding: 12px 16px;
    border: 1px solid #e5e8f3;
    border-radius: 14px;
    background: rgba(255, 255, 255, 0.72);
  }
  .summary-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #5cdb9a;
    box-shadow: 0 0 0 4px #e4faef;
  }
  .summary-value {
    font-size: 25px;
    font-weight: 800;
  }
  .summary-label {
    color: #8993aa;
    font-size: 12px;
  }
  .filter-card,
  .records-card {
    margin-bottom: 18px;
    border: 1px solid #e7eaf3;
    border-radius: 18px;
    background: rgba(255, 255, 255, 0.88);
  }
  .filter-card :deep(.el-card__body) {
    padding: 18px 20px 4px;
  }
  .filter-card :deep(.el-form-item) {
    margin-bottom: 14px;
  }
  .keyword-input {
    width: min(300px, 26vw);
  }
  .state-select {
    width: 170px;
  }
  .time-range {
    width: 370px;
  }
  .records-card :deep(.el-card__body) {
    padding: 22px 22px 16px;
  }
  .card-heading {
    margin-bottom: 18px;
    gap: 16px;
    flex-wrap: wrap;
  }
  .live-controls {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 10px;
    flex-wrap: wrap;
  }
  .refresh-select {
    width: 112px;
  }
  .last-updated {
    color: #8b95ac;
    font-size: 12px;
  }
  .load-alert {
    margin-bottom: 16px;
  }
  .heading-kicker {
    margin-bottom: 4px;
    color: #9aa5c0;
    font-size: 10px;
  }
  .records-table {
    --el-table-border-color: #eef0f5;
    --el-table-header-bg-color: #fafbfe;
  }
  .statement-cell {
    display: flex;
    flex-direction: column;
    min-width: 0;
    gap: 3px;
    padding: 2px 0;
  }
  .statement-text {
    overflow: hidden;
    color: #293653;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .statement-cell small {
    overflow: hidden;
    color: #9aa4bb;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 10px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .statement-mark {
    position: absolute;
    width: 3px;
    height: 30px;
    margin-left: -12px;
    border-radius: 3px;
    background: #7356ff;
  }
  .diagnosis-cell {
    display: flex;
    align-items: center;
    min-width: 0;
    gap: 7px;
  }
  .diagnosis-ok {
    color: #74819e;
    font-size: 12px;
  }
  .diagnosis-message {
    overflow: hidden;
    color: #c44c51;
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .pagination-row {
    margin-top: 18px;
    color: #8b95ac;
    font-size: 12px;
  }
  .empty-hint {
    margin: -18px 0 0;
    color: #a2abc0;
    font-size: 12px;
  }
  .detail-status {
    justify-content: flex-start;
    gap: 12px;
    margin-bottom: 22px;
    color: #8b95ac;
    font-size: 13px;
  }
  .detail-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px;
    margin-bottom: 24px;
  }
  .detail-grid > div {
    padding: 14px;
    border: 1px solid #edf0f6;
    border-radius: 12px;
    background: #fafbfe;
  }
  .detail-grid label,
  .detail-section label {
    display: block;
    margin-bottom: 6px;
    color: #9aa5ba;
    font-size: 11px;
  }
  .detail-grid strong {
    color: #273451;
    font-size: 13px;
  }
  .breakable {
    word-break: break-all;
  }
  .detail-section {
    margin-top: 18px;
  }
  .timing-section {
    padding: 16px;
    border: 1px solid #edf0f6;
    border-radius: 14px;
    background: linear-gradient(135deg, #fbfcff, #f5f7ff);
  }
  .audit-trail-section {
    padding: 16px;
    border: 1px solid #edf0f6;
    border-radius: 14px;
    background: #fbfcff;
  }
  .audit-timeline {
    margin: 14px 0 -14px;
    padding-left: 4px;
  }
  .audit-event {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    align-items: center;
    gap: 7px 10px;
  }
  .audit-event strong {
    overflow: hidden;
    color: #33415e;
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .audit-event small {
    overflow: hidden;
    grid-column: 2;
    color: #97a1b7;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 10px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .audit-trail-section :deep(.el-empty) {
    padding: 12px 0 0;
  }
  .section-heading {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
  }
  .section-heading > span {
    color: #9aa5ba;
    font-size: 11px;
  }
  .execution-timeline {
    display: grid;
    grid-template-columns: 1fr auto 1fr auto 1fr auto 1fr;
    align-items: start;
    margin-top: 12px;
  }
  .timeline-item {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    min-width: 0;
  }
  .timeline-item > div {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 3px;
  }
  .timeline-item strong {
    color: #33415e;
    font-size: 12px;
  }
  .timeline-item small {
    color: #8995af;
    font-size: 11px;
  }
  .timeline-dot {
    flex: 0 0 auto;
    width: 9px;
    height: 9px;
    margin-top: 3px;
    border: 2px solid #fff;
    border-radius: 50%;
    box-shadow: 0 0 0 2px #7356ff;
    background: #7356ff;
  }
  .timeline-dot.waiting {
    box-shadow: 0 0 0 2px #f0b34b;
    background: #f0b34b;
  }
  .timeline-dot.started {
    box-shadow: 0 0 0 2px #4ca8df;
    background: #4ca8df;
  }
  .timeline-dot.completed {
    box-shadow: 0 0 0 2px #5cdb9a;
    background: #5cdb9a;
  }
  .timeline-dot.muted {
    box-shadow: 0 0 0 2px #c9cfdd;
    background: #c9cfdd;
  }
  .timeline-line {
    width: 100%;
    height: 1px;
    margin: 7px 10px 0;
    background: #dfe4f1;
  }
  pre {
    overflow: auto;
    margin: 0;
    padding: 14px;
    border-radius: 12px;
    background: #f6f7fb;
    color: #33415e;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
    line-height: 1.7;
    white-space: pre-wrap;
    word-break: break-word;
  }
  .error-section pre {
    background: #fff2f1;
    color: #c44c51;
  }
  @media (max-width: 900px) {
    .sql-record-page {
      padding: 18px 14px 24px;
    }
    .page-hero {
      align-items: flex-start;
      gap: 12px;
      flex-direction: column;
    }
    .hero-actions {
      width: 100%;
      align-items: stretch;
      flex-direction: column;
    }
    .workspace-switch {
      display: flex;
      min-width: 0;
    }
    .workspace-switch :deep(.el-radio-button) {
      flex: 1;
    }
    .workspace-switch :deep(.el-radio-button__inner) {
      width: 100%;
      min-width: 0;
    }
    .keyword-input {
      width: 100%;
    }
    .filter-card :deep(.el-form-item) {
      width: 100%;
    }
    .filter-card :deep(.el-input),
    .filter-card :deep(.el-select),
    .filter-card :deep(.el-date-editor) {
      width: 100%;
    }
    .records-card {
      overflow: hidden;
    }
    .live-controls {
      width: 100%;
      justify-content: flex-start;
    }
    .execution-timeline {
      grid-template-columns: 1fr;
      gap: 10px;
    }
    .timeline-line {
      width: 1px;
      height: 10px;
      margin: -2px 0 -2px 4px;
    }
  }
</style>
