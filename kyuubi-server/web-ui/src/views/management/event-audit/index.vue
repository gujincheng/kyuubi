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
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessage } from 'element-plus'
  import { Refresh, Search, Setting } from '@element-plus/icons-vue'
  import {
    getNativeAuditConfig,
    getNativeAuditEvents,
    testNativeAuditConfig,
    updateNativeAuditConfig,
    type ManagedAuditConfig,
    type ManagedAuditConfigView,
    type NativeAuditEvent
  } from '@/api/audit'

  const props = withDefaults(
    defineProps<{ embedded?: boolean; configurationOnly?: boolean }>(),
    { embedded: false, configurationOnly: false }
  )
  const loading = ref(false)
  const saving = ref(false)
  const testing = ref(false)
  const configVisible = ref(false)
  const configView = ref<ManagedAuditConfigView | null>(null)
  const records = ref<NativeAuditEvent[]>([])
  const total = ref(0)
  const lastUpdated = ref<Date | null>(null)
  const loadError = ref('')
  type AuditRange = '24h' | '7d' | '30d' | 'custom'
  const query = reactive<{
    eventType: string
    user: string
    status: string
    range: AuditRange
    customRange: string[]
  }>({
    eventType: '',
    user: '',
    status: '',
    range: '7d',
    customRange: []
  })
  const detail = ref<NativeAuditEvent | null>(null)
  const detailVisible = ref(false)
  const form = reactive<ManagedAuditConfig>({
    enabled: false,
    mode: 'JSON',
    jsonPath: 'file:///opt/kyuubi/data/events',
    retentionDays: 7,
    kafka: {
      bootstrapServers: '',
      topic: '',
      securityProtocol: 'PLAINTEXT',
      saslMechanism: 'PLAIN',
      username: '',
      password: '',
      truststoreLocation: '',
      truststorePassword: ''
    }
  })

  const statusClass = computed(() =>
    configView.value?.enabled
      ? configView.value.healthy
        ? 'healthy'
        : 'error'
      : 'disabled'
  )

  const applyView = (view: ManagedAuditConfigView) => {
    configView.value = view
    form.enabled = view.enabled
    form.mode = view.mode
    form.jsonPath = view.jsonPath
    form.retentionDays = view.retentionDays
    Object.assign(form.kafka, view.kafka, {
      password: '',
      truststorePassword: ''
    })
  }

  const loadConfig = async () => applyView(await getNativeAuditConfig())

  const getDateQuery = () => {
    const now = Date.now()
    if (query.range === 'custom') {
      if (query.customRange.length !== 2) return {}
      const from = new Date(`${query.customRange[0]}T00:00:00`).getTime()
      const to = new Date(`${query.customRange[1]}T23:59:59.999`).getTime()
      if (!Number.isFinite(from) || !Number.isFinite(to) || from > to) {
        return null
      }
      return { from, to }
    }
    const days = query.range === '24h' ? 1 : query.range === '30d' ? 30 : 7
    return { from: now - days * 24 * 60 * 60 * 1000, to: now }
  }

  const loadEvents = async () => {
    const dateQuery = getDateQuery()
    if (dateQuery === null) {
      ElMessage.warning('自定义时间范围无效，请重新选择')
      return
    }
    loading.value = true
    try {
      const page = await getNativeAuditEvents({
        eventType: query.eventType || undefined,
        user: query.user || undefined,
        status: query.status || undefined,
        ...dateQuery,
        limit: 200
      })
      records.value = page.records
      total.value = page.total
      lastUpdated.value = new Date()
      loadError.value = ''
    } catch (error) {
      loadError.value =
        error instanceof Error ? error.message : '审计记录加载失败'
      ElMessage.error(loadError.value)
    } finally {
      loading.value = false
    }
  }

  const testConfig = async () => {
    testing.value = true
    try {
      const result = await testNativeAuditConfig(form)
      if (result.success) {
        ElMessage.success(result.message)
      } else {
        ElMessage.error(result.message)
      }
    } finally {
      testing.value = false
    }
  }

  const saveConfig = async () => {
    saving.value = true
    try {
      applyView(await updateNativeAuditConfig(form))
      ElMessage.success('审计配置已保存并生效')
      await loadEvents()
    } catch (error) {
      ElMessage.error(
        error instanceof Error ? error.message : '审计配置保存失败'
      )
    } finally {
      saving.value = false
    }
  }

  const openDetail = (row: NativeAuditEvent) => {
    detail.value = row
    detailVisible.value = true
  }
  const resetQuery = () => {
    query.eventType = ''
    query.user = ''
    query.status = ''
    query.range = '7d'
    query.customRange = []
    loadEvents()
  }
  const openConfig = () => {
    configVisible.value = true
  }
  defineExpose({ openConfiguration: openConfig })
  const formatTime = (value: number) =>
    value ? new Date(value).toLocaleString() : '未记录'
  const formatDuration = (value: number) => (value > 0 ? `${value} ms` : '—')
  const formatStatus = (value: string) =>
    value ? value.replace(/_STATE$/, '') : '—'
  const formatNote = (note?: string): string => {
    if (!note) return ''
    const staleSession = note.match(/^stale-session-inferred-closed-(\d+)h$/)
    if (staleSession) {
      return (
        `超过 ${staleSession[1]} 小时未观察到关闭记录，已推断为关闭` +
        '（关闭日志可能丢失、被清理，或会话仍在运行）'
      )
    }
    return note
  }
  const prettyJson = computed(() => {
    if (!detail.value) return ''
    try {
      return JSON.stringify(JSON.parse(detail.value.rawJson), null, 2)
    } catch {
      return detail.value.rawJson
    }
  })

  onMounted(async () => {
    try {
      await loadConfig()
      if (!props.configurationOnly) await loadEvents()
    } catch (error) {
      ElMessage.error(
        error instanceof Error ? error.message : 'Audit Log 初始化失败'
      )
    }
  })
</script>

<template>
  <section class="event-audit-page" :class="{ embedded: props.embedded }">
    <template v-if="!props.configurationOnly">
    <header v-if="!props.embedded" class="page-header">
      <div>
        <span class="eyebrow">KYUUBI NATIVE EVENTS</span>
        <h1>事件审计</h1>
        <p>Kyuubi 原生会话、操作和 SQL 拦截事件。</p>
      </div>
    </header>

    <el-card class="filter-card" shadow="never">
      <el-form :inline="true" @submit.prevent="loadEvents">
        <el-form-item>
          <el-radio-group
            v-model="query.range"
            class="range-shortcuts"
            size="small"
            @change="loadEvents">
            <el-radio-button label="24h">24 小时</el-radio-button>
            <el-radio-button label="7d">7 天</el-radio-button>
            <el-radio-button label="30d">30 天</el-radio-button>
            <el-radio-button label="custom">自定义</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="query.range === 'custom'">
          <el-date-picker
            v-model="query.customRange"
            class="range-picker"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期" />
        </el-form-item>
        <el-form-item>
          <el-select
            v-model="query.eventType"
            class="event-filter"
            clearable
            placeholder="全部事件">
            <el-option label="会话事件" value="kyuubi_session" />
            <el-option label="操作事件" value="kyuubi_operation" />
            <el-option label="SQL 拦截" value="sql_blocked" />
            <el-option label="Server 事件" value="kyuubi_server_info" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="query.user"
            class="text-filter"
            clearable
            placeholder="用户"
            @keyup.enter="loadEvents" />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="query.status"
            class="text-filter"
            clearable
            placeholder="状态"
            @keyup.enter="loadEvents" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="loadEvents">
            <el-icon><Search /></el-icon>查询
          </el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="records-card" shadow="never">
      <div class="card-heading">
        <div>
          <span class="heading-kicker">NATIVE EVENT STORE</span>
          <h2>原生审计事件</h2>
        </div>
        <div class="live-controls">
          <div class="runtime-status" :class="statusClass">
            <span class="status-dot" />
            <span>{{
              configView?.enabled ? `${configView.mode} 已启用` : '审计未启用'
            }}</span>
          </div>
          <span v-if="lastUpdated" class="last-updated">
            更新于 {{ formatTime(lastUpdated.getTime()) }}
          </span>
          <el-button link type="primary" :loading="loading" @click="loadEvents">
            <el-icon><Refresh /></el-icon>刷新
          </el-button>
          <el-button class="config-button" @click="openConfig">
            <el-icon><Setting /></el-icon>审计配置
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

      <el-table
        v-loading="loading"
        :data="records"
        class="records-table"
        row-key="id"
        @row-click="openDetail">
        <el-table-column label="事件 / 操作" min-width="330">
          <template #default="scope">
            <div class="event-cell">
              <span class="event-mark" />
              <strong>{{ scope.row.statement || scope.row.eventType }}</strong>
              <small
                >{{ scope.row.eventType }} ·
                {{ scope.row.operationId || scope.row.id }}</small
              >
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="scope">
            <el-tag effect="light" round>{{
              formatStatus(scope.row.status)
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="user" label="用户" width="130">
          <template #default="scope">{{ scope.row.user || '—' }}</template>
        </el-table-column>
        <el-table-column prop="engineType" label="Engine" width="120">
          <template #default="scope">{{
            scope.row.engineType || '—'
          }}</template>
        </el-table-column>
        <el-table-column label="时间" width="175">
          <template #default="scope">{{
            formatTime(scope.row.eventTime)
          }}</template>
        </el-table-column>
        <el-table-column label="数据源" width="140" show-overflow-tooltip>
          <template #default="scope">{{
            scope.row.datasourceLabel || '—'
          }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="110">
          <template #default="scope">{{
            formatDuration(scope.row.duration)
          }}</template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="90">
          <template #default="scope">
            <el-button link type="primary" @click.stop="openDetail(scope.row)"
              >详情</el-button
            >
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!loading && records.length === 0"
        description="当前存储中没有匹配的审计事件" />
      <div class="result-footer">
        <span>{{ total }} 条事件</span>
        <span>查询来源：{{ configView?.mode || '—' }}</span>
      </div>
    </el-card>

    </template>
    <el-drawer
      v-model="configVisible"
      title="原生审计配置"
      size="min(720px, 94vw)">
      <div class="section-title">
        <div><span>STORAGE</span><h2>审计存储方式</h2></div>
        <el-switch
          v-model="form.enabled"
          active-text="启用审计"
          inactive-text="停用审计" />
      </div>
      <el-alert
        class="config-hint"
        type="info"
        :closable="false"
        title="保存后立即切换新事件的写入和查询方式，无需重启 Kyuubi Server。" />
      <div class="mode-cards">
        <button
          type="button"
          :class="{ selected: form.mode === 'JSON' }"
          @click="form.mode = 'JSON'">
          <strong>JSON 文件</strong><small>单实例或共享持久化目录</small>
        </button>
        <button
          type="button"
          :class="{ selected: form.mode === 'KAFKA' }"
          @click="form.mode = 'KAFKA'">
          <strong>Kafka</strong><small>多实例集中采集和长期留存</small>
        </button>
      </div>
      <el-form label-position="top" class="config-form">
        <template v-if="form.mode === 'JSON'">
          <el-form-item label="日志目录">
            <el-input
              v-model="form.jsonPath"
              placeholder="file:///opt/kyuubi/data/events" />
            <small>填写 Kyuubi 容器内的持久化目录，支持 file:// 路径。</small>
          </el-form-item>
          <el-form-item label="查询保留天数">
            <el-input-number
              v-model="form.retentionDays"
              :min="1"
              :max="3650" />
          </el-form-item>
        </template>
        <template v-else>
          <div class="two-columns">
            <el-form-item label="Bootstrap Servers">
              <el-input
                v-model="form.kafka.bootstrapServers"
                placeholder="kafka:9092" />
            </el-form-item>
            <el-form-item label="Topic">
              <el-input v-model="form.kafka.topic" placeholder="kyuubi-audit" />
            </el-form-item>
            <el-form-item label="安全协议">
              <el-select v-model="form.kafka.securityProtocol">
                <el-option
                  v-for="item in [
                    'PLAINTEXT',
                    'SSL',
                    'SASL_PLAINTEXT',
                    'SASL_SSL'
                  ]"
                  :key="item"
                  :label="item"
                  :value="item" />
              </el-select>
            </el-form-item>
            <el-form-item
              v-if="form.kafka.securityProtocol.startsWith('SASL')"
              label="SASL 机制">
              <el-select v-model="form.kafka.saslMechanism">
                <el-option label="PLAIN" value="PLAIN" />
                <el-option label="SCRAM-SHA-256" value="SCRAM-SHA-256" />
                <el-option label="SCRAM-SHA-512" value="SCRAM-SHA-512" />
              </el-select>
            </el-form-item>
            <el-form-item
              v-if="form.kafka.securityProtocol.startsWith('SASL')"
              label="用户名">
              <el-input v-model="form.kafka.username" />
            </el-form-item>
            <el-form-item
              v-if="form.kafka.securityProtocol.startsWith('SASL')"
              label="密码">
              <el-input
                v-model="form.kafka.password"
                type="password"
                show-password
                :placeholder="
                  configView?.passwordConfigured
                    ? '已配置；留空保持不变'
                    : '请输入密码'
                " />
            </el-form-item>
            <el-form-item
              v-if="form.kafka.securityProtocol.includes('SSL')"
              label="Truststore 路径">
              <el-input v-model="form.kafka.truststoreLocation" />
            </el-form-item>
            <el-form-item
              v-if="form.kafka.securityProtocol.includes('SSL')"
              label="Truststore 密码">
              <el-input
                v-model="form.kafka.truststorePassword"
                type="password"
                show-password
                :placeholder="
                  configView?.truststorePasswordConfigured
                    ? '已配置；留空保持不变'
                    : '选填'
                " />
            </el-form-item>
          </div>
        </template>
      </el-form>
      <template #footer>
        <div class="config-actions">
          <el-button @click="configVisible = false">取消</el-button>
          <el-button :loading="testing" @click="testConfig">测试配置</el-button>
          <el-button type="primary" :loading="saving" @click="saveConfig"
            >保存并生效</el-button
          >
        </div>
      </template>
    </el-drawer>

    <el-drawer
      v-model="detailVisible"
      title="审计事件详情"
      size="min(640px, 94vw)">
      <div v-if="detail" class="detail-grid">
        <span>事件时间</span><strong>{{ formatTime(detail.eventTime) }}</strong>
        <span>用户</span><strong>{{ detail.user || '未记录' }}</strong>
        <span>客户端 IP</span><strong>{{ detail.clientIp || '未记录' }}</strong>
        <span>Session ID</span
        ><strong>{{ detail.sessionId || '未记录' }}</strong>
        <span>Operation ID</span
        ><strong>{{ detail.operationId || '未记录' }}</strong>
        <span>Engine</span><strong>{{ detail.engineType || '未记录' }}</strong>
      </div>
      <el-alert
        v-if="detail?.error"
        :title="detail.error"
        type="error"
        :closable="false"
        class="detail-error" />
      <el-alert
        v-if="detail?.note"
        :title="formatNote(detail.note)"
        type="warning"
        :closable="false"
        class="detail-error" />
      <h3>脱敏后的原始事件</h3>
      <pre>{{ prettyJson }}</pre>
    </el-drawer>
  </section>
</template>

<style scoped lang="scss">
  .event-audit-page {
    min-height: 100%;
    padding: 24px 28px 32px;
    color: #17213b;
    background: linear-gradient(180deg, #f7f9ff 0, #f5f7fb 40%, #f4f6fa 100%);
  }
  .event-audit-page.embedded {
    min-height: auto;
    padding: 0;
    background: transparent;
  }
  .page-header {
    margin-bottom: 20px;
  }
  .eyebrow,
  .heading-kicker,
  .section-title span {
    margin: 0 0 7px;
    color: #7356ff;
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.14em;
  }
  h1 {
    margin: 0;
    font-size: 32px;
  }
  h2 {
    margin: 0;
    font-size: 18px;
  }
  .page-header p {
    margin: 8px 0 0;
    color: #7e8aa8;
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
  .records-card :deep(.el-card__body) {
    padding: 22px 22px 16px;
  }
  .card-heading,
  .live-controls,
  .runtime-status,
  .result-footer,
  .section-title,
  .config-actions {
    display: flex;
    align-items: center;
  }
  .card-heading,
  .result-footer,
  .section-title {
    justify-content: space-between;
  }
  .card-heading {
    gap: 16px;
    margin-bottom: 18px;
    flex-wrap: wrap;
  }
  .heading-kicker {
    display: block;
    margin-bottom: 4px;
    color: #9aa5c0;
    font-size: 10px;
  }
  .live-controls {
    justify-content: flex-end;
    gap: 12px;
    flex-wrap: wrap;
  }
  .runtime-status {
    gap: 8px;
    padding: 6px 10px;
    border: 1px solid #e8ebf3;
    border-radius: 999px;
    color: #6f7b94;
    background: #f8f9fc;
    font-size: 12px;
  }
  .last-updated {
    color: #8b95ac;
    font-size: 12px;
  }
  .status-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #9aa4b5;
    box-shadow: 0 0 0 4px #eef1f5;
  }
  .runtime-status.healthy .status-dot {
    background: #5cdb9a;
    box-shadow: 0 0 0 4px #e4faef;
  }
  .runtime-status.error .status-dot {
    background: #e05252;
    box-shadow: 0 0 0 4px #fce8e8;
  }
  .config-button {
    color: #fff;
    border-color: #25314c;
    background: #25314c;
  }
  .config-button:hover {
    color: #fff;
    border-color: #7356ff;
    background: #7356ff;
  }
  .range-shortcuts {
    display: inline-flex;
  }
  .range-shortcuts :deep(.el-radio-button__inner) {
    min-width: 56px;
    padding: 8px 11px;
  }
  .range-picker {
    width: 250px;
  }
  .event-filter {
    width: 155px;
  }
  .text-filter {
    width: 145px;
  }
  .load-alert {
    margin-bottom: 16px;
  }
  .records-table {
    --el-table-border-color: #eef0f5;
    --el-table-header-bg-color: #fafbfe;
  }
  :deep(.el-table__row) {
    cursor: pointer;
  }
  .event-cell {
    position: relative;
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 3px;
    padding: 2px 0;
  }
  .event-cell strong,
  .event-cell small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .event-cell strong {
    color: #293653;
    font-size: 13px;
  }
  .event-cell small {
    color: #9aa4bb;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 10px;
  }
  .event-mark {
    position: absolute;
    width: 3px;
    height: 30px;
    margin-left: -12px;
    border-radius: 3px;
    background: #7356ff;
  }
  .result-footer {
    margin-top: 18px;
    color: #8b95ac;
    font-size: 12px;
  }
  .config-hint {
    margin: 18px 0;
  }
  .mode-cards {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
    margin: 22px 0;
  }
  .mode-cards button {
    padding: 18px;
    text-align: left;
    border: 1px solid #dfe4ed;
    border-radius: 13px;
    background: #fff;
    cursor: pointer;
  }
  .mode-cards button.selected {
    border-color: #7356ff;
    background: #f7f5ff;
    box-shadow: 0 0 0 3px rgba(115, 86, 255, 0.1);
  }
  .mode-cards strong,
  .mode-cards small {
    display: block;
  }
  .mode-cards small {
    margin-top: 6px;
    color: #7e899b;
  }
  .two-columns {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0 14px;
  }
  .config-form small {
    color: #8a95a8;
  }
  .config-actions {
    justify-content: flex-end;
    gap: 10px;
  }
  .detail-grid {
    display: grid;
    grid-template-columns: 120px 1fr;
    gap: 14px;
    padding: 18px;
    border: 1px solid #edf0f6;
    border-radius: 14px;
    background: #fafbfe;
  }
  .detail-grid span {
    color: #8290a5;
  }
  .detail-grid strong {
    overflow-wrap: anywhere;
  }
  .detail-error {
    margin: 20px 0;
  }
  pre {
    max-height: 430px;
    overflow: auto;
    padding: 16px;
    border-radius: 12px;
    color: #d8def0;
    background: #172033;
    font-size: 12px;
    line-height: 1.6;
  }
  @media (max-width: 900px) {
    .event-audit-page:not(.embedded) {
      padding: 20px 16px;
    }
    .card-heading,
    .live-controls {
      align-items: flex-start;
      flex-direction: column;
    }
    .range-picker,
    .event-filter,
    .text-filter {
      width: 100%;
    }
    .two-columns {
      grid-template-columns: 1fr;
    }
  }
</style>
