<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue'
  import { useI18n } from 'vue-i18n'
  import {
    getAuditRecords,
    type AuditRecord,
    type AuditRecordPage
  } from '@/api/audit'

  const { t } = useI18n()
  const loading = ref(false)
  const loadError = ref('')
  const auditPage = ref<AuditRecordPage | null>(null)
  const user = ref('')
  const method = ref('')
  const status = ref('')
  const timeRange = ref<[Date, Date] | null>(null)
  const lastUpdated = ref(0)

  const records = computed(() => auditPage.value?.records ?? [])
  const total = computed(() => auditPage.value?.total ?? 0)
  const successful = computed(
    () => records.value.filter((record) => record.status < 400).length
  )
  const failed = computed(
    () => records.value.filter((record) => record.status >= 400).length
  )
  const configurationText = (key: string, params?: Record<string, unknown>) =>
    t(`management.audit_${key}`, params ?? {})

  const loadAudit = async () => {
    loading.value = true
    loadError.value = ''
    try {
      auditPage.value = await getAuditRecords({
        user: user.value || undefined,
        method: method.value || undefined,
        status: status.value ? Number(status.value) : undefined,
        from: timeRange.value?.[0].getTime(),
        to: timeRange.value?.[1].getTime(),
        limit: 100
      })
      lastUpdated.value = Date.now()
    } catch (error) {
      loadError.value =
        error instanceof Error
          ? error.message
          : configurationText('load_failed')
    } finally {
      loading.value = false
    }
  }

  const resetFilters = () => {
    user.value = ''
    method.value = ''
    status.value = ''
    timeRange.value = null
    loadAudit()
  }

  const formatTime = (timestamp: number) =>
    timestamp ? new Date(timestamp).toLocaleString() : '--'
  const statusType = (code: number) => {
    if (code >= 500) return 'danger'
    if (code >= 400) return 'warning'
    return 'success'
  }
  const recordLabel = (record: AuditRecord) =>
    record.action
      ? `${record.action} · ${record.method} ${record.uri}`
      : `${record.method} ${record.uri}`

  onMounted(loadAudit)

  defineExpose({
    auditPage,
    records,
    total,
    loadAudit,
    resetFilters,
    formatTime,
    recordLabel
  })
</script>

<template>
  <main class="audit-page">
    <section class="page-hero">
      <div>
        <p class="eyebrow">{{ configurationText('eyebrow') }}</p>
        <h1>{{ configurationText('title') }}</h1>
        <p class="subtitle">{{ configurationText('subtitle') }}</p>
      </div>
      <div class="hero-actions">
        <span v-if="lastUpdated" class="updated-at">{{
          configurationText('updated_at', { time: formatTime(lastUpdated) })
        }}</span>
        <el-button type="primary" :loading="loading" @click="loadAudit">{{
          configurationText('refresh')
        }}</el-button>
      </div>
    </section>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      show-icon
      :closable="false"
      class="load-alert" />

    <section class="summary-grid">
      <article class="summary-card accent-indigo"
        ><span>{{ configurationText('total') }}</span
        ><strong>{{ total }}</strong
        ><small>{{ configurationText('total_hint') }}</small></article
      >
      <article class="summary-card accent-green"
        ><span>{{ configurationText('successful') }}</span
        ><strong>{{ successful }}</strong
        ><small>{{ configurationText('successful_hint') }}</small></article
      >
      <article class="summary-card accent-amber"
        ><span>{{ configurationText('failed') }}</span
        ><strong>{{ failed }}</strong
        ><small>{{ configurationText('failed_hint') }}</small></article
      >
      <article class="summary-card accent-cyan"
        ><span>{{ configurationText('retention') }}</span
        ><strong>24h</strong
        ><small>{{ configurationText('retention_hint') }}</small></article
      >
    </section>

    <el-card class="filter-card" shadow="never">
      <div class="filter-heading">
        <div
          ><span class="panel-kicker">{{
            configurationText('filter_eyebrow')
          }}</span
          ><h2>{{ configurationText('filter_title') }}</h2></div
        >
        <span class="read-only-badge">{{
          configurationText('read_only')
        }}</span>
      </div>
      <div class="filter-grid">
        <el-input
          v-model="user"
          clearable
          :placeholder="configurationText('user')" />
        <el-select
          v-model="method"
          clearable
          :placeholder="configurationText('method')">
          <el-option label="GET" value="GET" /><el-option
            label="POST"
            value="POST" /><el-option label="DELETE" value="DELETE" />
        </el-select>
        <el-select
          v-model="status"
          clearable
          :placeholder="configurationText('status')">
          <el-option :label="configurationText('success_status')" value="200" />
          <el-option :label="configurationText('client_error')" value="400" />
          <el-option :label="configurationText('server_error')" value="500" />
        </el-select>
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          clearable
          :start-placeholder="configurationText('from')"
          :end-placeholder="configurationText('to')"
          class="time-range" />
        <div class="filter-actions"
          ><el-button type="primary" :loading="loading" @click="loadAudit">{{
            configurationText('apply')
          }}</el-button
          ><el-button @click="resetFilters">{{
            configurationText('reset')
          }}</el-button></div
        >
      </div>
    </el-card>

    <el-card class="records-card" shadow="never">
      <div class="card-heading"
        ><div
          ><span class="panel-kicker">{{
            configurationText('table_eyebrow')
          }}</span
          ><h2>{{ configurationText('table_title') }}</h2></div
        ><span class="record-count"
          >{{ total }} {{ configurationText('records') }}</span
        ></div
      >
      <el-table
        v-loading="loading"
        :data="records"
        stripe
        row-key="timestamp"
        class="audit-table">
        <el-table-column :label="configurationText('time')" width="178"
          ><template #default="scope">{{
            formatTime(scope.row.timestamp)
          }}</template></el-table-column
        >
        <el-table-column :label="configurationText('request')" min-width="320"
          ><template #default="scope"
            ><div class="request-cell"
              ><strong>{{ recordLabel(scope.row) }}</strong
              ><small v-if="scope.row.query">{{ scope.row.query }}</small></div
            ></template
          ></el-table-column
        >
        <el-table-column
          prop="user"
          :label="configurationText('user')"
          width="140" />
        <el-table-column
          prop="ip"
          :label="configurationText('ip')"
          width="150" />
        <el-table-column :label="configurationText('status')" width="110"
          ><template #default="scope"
            ><el-tag
              :type="statusType(scope.row.status)"
              effect="light"
              round
              >{{ scope.row.status }}</el-tag
            ></template
          ></el-table-column
        >
        <el-table-column
          prop="authType"
          :label="configurationText('auth')"
          width="120" />
      </el-table>
      <el-empty
        v-if="!loading && records.length === 0"
        :description="configurationText('empty')"
        ><p class="empty-hint">{{
          configurationText('empty_hint')
        }}</p></el-empty
      >
      <div class="table-footer">{{ configurationText('footer') }}</div>
    </el-card>
  </main>
</template>

<style scoped lang="scss">
  .audit-page {
    padding: 28px 32px 44px;
    color: #172033;
  }
  .page-hero,
  .filter-heading,
  .card-heading {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 20px;
  }
  .page-hero {
    margin-bottom: 24px;
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
    margin: 8px 0;
    font-size: 30px;
    letter-spacing: -0.03em;
  }
  h2 {
    margin: 6px 0 0;
    font-size: 18px;
  }
  .subtitle {
    margin: 0;
    color: #6b778d;
    font-size: 14px;
  }
  .hero-actions {
    display: flex;
    align-items: center;
    gap: 14px;
  }
  .updated-at,
  .table-footer {
    color: #8b95a8;
    font-size: 12px;
  }
  .load-alert {
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
    font-size: 25px;
  }
  .accent-indigo {
    color: #6757e8;
  }
  .accent-green {
    color: #43ad72;
  }
  .accent-amber {
    color: #db9a32;
  }
  .accent-cyan {
    color: #1ba4be;
  }
  .filter-card,
  .records-card {
    border: 1px solid #e7eaf2;
    border-radius: 16px;
    box-shadow: 0 12px 32px rgba(36, 47, 76, 0.05);
    margin-bottom: 18px;
  }
  .filter-card :deep(.el-card__body),
  .records-card :deep(.el-card__body) {
    padding: 20px 22px;
  }
  .filter-heading {
    margin-bottom: 18px;
  }
  .read-only-badge,
  .record-count {
    color: #57956c;
    padding: 6px 10px;
    border: 1px solid #ccebd5;
    border-radius: 999px;
    background: #f2fbf5;
    font-size: 12px;
  }
  .filter-grid {
    display: grid;
    grid-template-columns: 1.2fr 0.7fr 0.7fr 1.6fr auto;
    gap: 12px;
    align-items: center;
  }
  .time-range {
    width: 100%;
  }
  .filter-actions {
    display: flex;
    gap: 8px;
  }
  .records-card :deep(.el-table) {
    margin-top: 20px;
  }
  .request-cell strong,
  .request-cell small {
    display: block;
  }
  .request-cell strong {
    color: #27324a;
    font-size: 13px;
  }
  .request-cell small {
    margin-top: 4px;
    color: #929caf;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .table-footer {
    margin-top: 14px;
  }
  .empty-hint {
    color: #929caf;
    font-size: 12px;
  }
  @media (max-width: 1100px) {
    .summary-grid {
      grid-template-columns: repeat(2, 1fr);
    }
    .filter-grid {
      grid-template-columns: repeat(2, 1fr);
    }
    .filter-actions {
      justify-content: flex-end;
    }
  }
  @media (max-width: 700px) {
    .audit-page {
      padding: 20px 16px 32px;
    }
    .page-hero,
    .filter-heading,
    .card-heading {
      flex-direction: column;
    }
    .hero-actions {
      width: 100%;
      justify-content: space-between;
    }
    .summary-grid,
    .filter-grid {
      grid-template-columns: 1fr;
    }
    .filter-actions {
      justify-content: flex-start;
    }
  }
</style>
