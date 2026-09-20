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
  <main class="overview-page">
    <section class="page-heading">
      <div>
        <div class="eyebrow">{{ $t('overview.workspace') }}</div>
        <h1>{{ $t('overview.title') }}</h1>
        <p>{{ $t('overview.subtitle') }}</p>
      </div>
      <div class="heading-actions">
        <el-tag class="live-tag" :type="healthTagType" effect="light" round>
          <span class="live-dot" :class="`status-${healthStatus}`" />
          {{ healthLabel }}
        </el-tag>
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
        <span v-if="updatedAt" class="updated-at">
          {{ $t('overview.updated_at', { time: formatTime(updatedAt) }) }}
        </span>
        <el-button
          class="refresh-button"
          text
          circle
          :loading="loading"
          :aria-label="$t('refresh')"
          @click="refresh">
          <el-icon><Refresh /></el-icon>
        </el-button>
      </div>
    </section>

    <el-alert
      v-if="errorMessage"
      class="error-alert"
      :title="errorMessage"
      type="error"
      show-icon
      closable
      @close="errorMessage = ''" />

    <el-alert
      v-if="summary && summary.health.issues.length > 0"
      class="health-alert"
      :title="healthAlertTitle"
      :type="summary.health.status === 'CRITICAL' ? 'error' : 'warning'"
      show-icon>
      <template #default>
        <span v-for="issue in visibleHealthIssues" :key="issue.code">
          {{ issue.message }}（{{ issue.metric }}:
          {{ formatIssueValue(issue) }}）
        </span>
      </template>
    </el-alert>

    <el-skeleton
      v-if="loading && !summary"
      class="overview-skeleton"
      :rows="8"
      animated />

    <template v-else>
      <section v-if="summary" class="metrics-grid">
        <article
          v-for="card in metricCards"
          :key="card.label"
          class="metric-card"
          :class="`tone-${card.tone}`">
          <div class="metric-card-topline">
            <span class="metric-label">{{ card.label }}</span>
            <span class="metric-icon">
              <el-icon><component :is="card.icon" /></el-icon>
            </span>
          </div>
          <div class="metric-value">
            {{
              card.percent
                ? formatPercent(card.value)
                : formatNumber(card.value)
            }}
            <span v-if="card.unit" class="metric-unit">{{ card.unit }}</span>
          </div>
          <div class="metric-hint">{{ card.hint }}</div>
        </article>
      </section>

      <section v-if="summary" class="health-grid">
        <article class="panel health-panel">
          <div class="panel-heading compact">
            <div>
              <div class="panel-kicker">{{ $t('overview.health_group') }}</div>
              <h2>{{ $t('overview.service_health') }}</h2>
            </div>
            <span class="panel-note">{{ freshnessLabel }}</span>
          </div>
          <div class="health-summary">
            <strong :class="`health-value status-${healthStatus}`">
              {{ healthLabel }}
            </strong>
            <span>{{ $t('overview.health_hint') }}</span>
          </div>
          <div class="health-issue-count">
            <span>{{ $t('overview.health_issues') }}</span>
            <strong>{{ formatNumber(summary.health.issues.length) }}</strong>
          </div>
        </article>

        <article class="panel health-panel">
          <div class="panel-heading compact">
            <div>
              <div class="panel-kicker">{{ $t('overview.access_group') }}</div>
              <h2>{{ $t('overview.api_health') }}</h2>
            </div>
          </div>
          <div class="health-stat-grid">
            <div>
              <span>{{ $t('overview.api_active_requests') }}</span>
              <strong>{{
                formatNumber(summary.accessHealth.activeRequests)
              }}</strong>
            </div>
            <div>
              <span>{{ $t('overview.api_p95') }}</span>
              <strong>{{
                formatDuration(summary.accessHealth.requestP95Ms)
              }}</strong>
            </div>
            <div>
              <span>{{ $t('overview.api_requests') }}</span>
              <strong>{{
                formatNumber(summary.accessHealth.requestCount)
              }}</strong>
            </div>
            <div>
              <span>{{ $t('overview.api_failure_rate') }}</span>
              <strong>{{
                formatPercent(summary.accessHealth.failureRate)
              }}</strong>
            </div>
          </div>
        </article>

        <article class="panel health-panel">
          <div class="panel-heading compact">
            <div>
              <div class="panel-kicker">{{ $t('overview.jvm_group') }}</div>
              <h2>{{ $t('overview.jvm_health') }}</h2>
            </div>
          </div>
          <div class="health-stat-grid">
            <div>
              <span>{{ $t('overview.heap_usage') }}</span>
              <strong>{{
                formatPercent(summary.runtimeHealth.heapUsage * 100)
              }}</strong>
            </div>
            <div>
              <span>{{ $t('overview.jvm_threads') }}</span>
              <strong>{{
                formatNumber(summary.runtimeHealth.threadCount)
              }}</strong>
            </div>
            <div>
              <span>{{ $t('overview.jvm_deadlocks') }}</span>
              <strong
                :class="{
                  'danger-value': summary.runtimeHealth.deadlockCount > 0
                }">
                {{ formatNumber(summary.runtimeHealth.deadlockCount) }}
              </strong>
            </div>
            <div>
              <span>{{ $t('overview.gc_time') }}</span>
              <strong>{{
                formatDuration(summary.runtimeHealth.gcTimeMs)
              }}</strong>
            </div>
          </div>
        </article>

        <article class="panel health-panel">
          <div class="panel-heading compact">
            <div>
              <div class="panel-kicker">{{
                $t('overview.metadata_group')
              }}</div>
              <h2>{{ $t('overview.metadata_health') }}</h2>
            </div>
          </div>
          <div class="health-stat-grid">
            <div>
              <span>{{ $t('overview.metadata_requests') }}</span>
              <strong>{{ formatNumber(summary.metadataHealth.total) }}</strong>
            </div>
            <div>
              <span>{{ $t('overview.metadata_failed') }}</span>
              <strong
                :class="{ 'danger-value': summary.metadataHealth.failed > 0 }">
                {{ formatNumber(summary.metadataHealth.failed) }}
              </strong>
            </div>
            <div>
              <span>{{ $t('overview.metadata_retrying') }}</span>
              <strong>{{
                formatNumber(summary.metadataHealth.retrying)
              }}</strong>
            </div>
            <div>
              <span>{{ $t('overview.metadata_failure_rate') }}</span>
              <strong>{{
                formatPercent(summary.metadataHealth.failureRate)
              }}</strong>
            </div>
          </div>
        </article>
      </section>

      <section class="content-grid">
        <article class="panel trend-panel">
          <div class="panel-heading">
            <div>
              <div class="panel-kicker">{{ $t('overview.activity') }}</div>
              <h2>{{ $t('overview.sql_frequency') }}</h2>
              <p>{{ $t('overview.sql_frequency_desc') }}</p>
            </div>
            <div class="trend-actions">
              <el-tag
                class="trend-status"
                :type="trendStatusType"
                effect="plain"
                round>
                {{ trendStatusLabel }}
              </el-tag>
              <el-button link type="primary" @click="openSqlRecords()">
                {{ $t('overview.view_records') }}
                <el-icon><ArrowRight /></el-icon>
              </el-button>
              <el-button-group class="range-switch">
                <el-button
                  v-for="range in ranges"
                  :key="range"
                  :type="selectedRange === range ? 'primary' : 'default'"
                  :loading="trendLoading && selectedRange === range"
                  @click="changeRange(range)">
                  {{ range.toUpperCase() }}
                </el-button>
              </el-button-group>
            </div>
          </div>

          <el-alert
            v-if="trendErrorMessage"
            class="trend-error-alert"
            :title="trendErrorMessage"
            type="warning"
            show-icon />

          <div ref="chartWrapElement" class="chart-wrap">
            <svg
              v-if="chartCoordinates.length > 0"
              class="trend-chart"
              :viewBox="`0 0 ${chartFrame.width} ${chartFrame.height}`"
              preserveAspectRatio="xMinYMin meet"
              role="img"
              :aria-label="$t('overview.sql_frequency')">
              <defs>
                <linearGradient id="trend-fill" x1="0" x2="0" y1="0" y2="1">
                  <stop offset="0%" stop-color="#7c5cff" stop-opacity="0.28" />
                  <stop
                    offset="100%"
                    stop-color="#7c5cff"
                    stop-opacity="0.02" />
                </linearGradient>
              </defs>
              <g v-for="guide in chartGuides" :key="guide.value">
                <line
                  class="chart-guide"
                  :x1="chartFrame.left - 8"
                  :y1="guide.y"
                  :x2="chartFrame.left + chartFrame.plotWidth"
                  :y2="guide.y" />
                <text
                  class="chart-axis-label chart-y-label"
                  :x="chartFrame.left - 10"
                  :y="guide.y + 4">
                  {{ formatNumber(guide.value) }}
                </text>
              </g>
              <g v-for="label in chartXAxisLabels" :key="label.timestamp">
                <text
                  class="chart-axis-label chart-x-label"
                  :x="label.x"
                  :y="chartFrame.baseline + 24"
                  :text-anchor="label.anchor">
                  {{ label.label }}
                </text>
              </g>
              <path class="chart-area" :d="areaPath" />
              <path class="chart-line" :d="linePath" />
              <path class="failure-line" :d="failureLinePath" />
              <line
                v-if="activeChartTooltip"
                class="chart-crosshair"
                :x1="activeChartTooltip.guideX"
                :y1="chartFrame.top"
                :x2="activeChartTooltip.guideX"
                :y2="chartFrame.baseline" />
              <circle
                v-if="activeChartTooltip"
                class="chart-active-point execution-active-point"
                :cx="activeChartTooltip.x"
                :cy="activeChartTooltip.executionY"
                r="6" />
              <circle
                v-if="activeChartTooltip"
                class="chart-active-point failure-active-point"
                :cx="activeChartTooltip.x"
                :cy="activeChartTooltip.failureY"
                r="4.5" />
              <rect
                class="chart-hover-overlay"
                :x="chartFrame.left"
                :y="chartFrame.top"
                :width="chartFrame.plotWidth"
                :height="chartFrame.plotHeight"
                @mousemove="updateChartTooltip"
                @mouseleave="activeChartTooltip = null" />
            </svg>
            <div
              v-if="activeChartTooltip"
              class="chart-tooltip"
              :style="chartTooltipStyle">
              <strong>{{
                formatChartTimestamp(activeChartTooltip.timestamp)
              }}</strong>
              <span class="chart-tooltip-row">
                <i class="chart-tooltip-swatch executions" />
                {{ $t('overview.chart_executions') }}：
                <b>{{ formatNumber(activeChartTooltip.executions) }}</b>
              </span>
              <span class="chart-tooltip-row">
                <i class="chart-tooltip-swatch failures" />
                {{ $t('overview.chart_failures') }}：
                <b>{{ formatNumber(activeChartTooltip.failures) }}</b>
              </span>
            </div>
            <div v-if="chartCoordinates.length === 0" class="empty-chart">
              <div class="empty-chart-icon"
                ><el-icon><DataAnalysis /></el-icon
              ></div>
              <strong>{{ trendEmptyTitle }}</strong>
              <span>{{ trendEmptyDescription }}</span>
            </div>
          </div>
          <div class="chart-footer">
            <span
              ><i class="legend-dot" />{{ $t('overview.sql_executions') }}</span
            >
            <span class="failure-legend"
              ><i class="legend-dot" />{{ $t('overview.sql_failures') }}</span
            >
            <span>{{ $t('overview.process_local_hint') }}</span>
          </div>
        </article>

        <article v-if="summary" class="panel pool-panel">
          <div class="panel-heading compact">
            <div>
              <div class="panel-kicker">{{ $t('overview.runtime') }}</div>
              <h2>{{ $t('overview.executor_pool') }}</h2>
            </div>
            <el-button
              link
              type="primary"
              @click="openSqlRecords('RUNNING_STATE')">
              {{ $t('overview.view_active_records') }}
              <el-icon><ArrowRight /></el-icon>
            </el-button>
          </div>
          <div class="pool-primary-stats">
            <div class="pool-primary-stat is-running">
              <div class="pool-primary-label">
                <span class="pool-status-dot" />
                {{ $t('overview.running') }}
              </div>
              <strong>{{ formatNumber(summary.execPool.active) }}</strong>
              <span>{{ $t('overview.running_queue_hint') }}</span>
            </div>
            <div class="pool-primary-stat is-waiting">
              <div class="pool-primary-label">
                <span class="pool-status-dot" />
                {{ $t('overview.waiting') }}
              </div>
              <strong>{{ formatNumber(summary.execPool.waiting) }}</strong>
              <span>{{ $t('overview.waiting_queue_hint') }}</span>
            </div>
          </div>
          <div class="pool-help">
            <span class="pool-help-icon" aria-hidden="true">i</span>
            <span>{{ $t('overview.queue_metric_hint') }}</span>
          </div>
          <div class="pool-stats">
            <div>
              <span class="stat-label">{{ $t('overview.pool_size') }}</span>
              <strong>{{ formatNumber(summary.execPool.size) }}</strong>
            </div>
            <div>
              <span class="stat-label">{{ $t('overview.pool_alive') }}</span>
              <strong>{{ formatNumber(summary.execPool.alive) }}</strong>
            </div>
            <div>
              <span class="stat-label">{{ $t('overview.batch_backlog') }}</span>
              <strong>{{
                formatDuration(summary.batchOperations.pendingMaxElapse)
              }}</strong>
            </div>
            <div>
              <span class="stat-label">{{
                $t('overview.batch_failure_rate')
              }}</span>
              <strong>{{
                formatPercent(summary.batchOperations.failureRate)
              }}</strong>
            </div>
          </div>
        </article>
      </section>

      <section v-if="summary" class="panel engine-panel">
        <div class="panel-heading compact">
          <div>
            <div class="panel-kicker">{{ $t('overview.engines') }}</div>
            <h2>{{ $t('overview.engine_launches') }}</h2>
          </div>
          <span class="panel-note">{{ $t('overview.since_start') }}</span>
        </div>
        <div class="engine-health-grid">
          <div>
            <span>{{ $t('overview.engine_launching') }}</span>
            <strong>{{ formatNumber(summary.engineHealth.launching) }}</strong>
          </div>
          <div>
            <span>{{ $t('overview.engine_waiting') }}</span>
            <strong>{{ formatNumber(summary.engineHealth.waiting) }}</strong>
          </div>
          <div>
            <span>{{ $t('overview.engine_failed') }}</span>
            <strong class="danger-value">{{
              formatNumber(summary.engineHealth.failed)
            }}</strong>
          </div>
          <div>
            <span>{{ $t('overview.engine_timeout') }}</span>
            <strong class="warning-value">{{
              formatNumber(summary.engineHealth.timeout)
            }}</strong>
          </div>
          <div>
            <span>{{ $t('overview.engine_p95') }}</span>
            <strong>{{
              formatDuration(summary.engineHealth.startupLatency.p95)
            }}</strong>
          </div>
        </div>
        <div class="engine-grid">
          <div
            v-for="engine in engineRows"
            :key="engine.engineType"
            class="engine-row">
            <div class="engine-row-heading">
              <span class="engine-name">{{
                engineLabel(engine.engineType)
              }}</span>
              <strong>{{ formatNumber(engine.launchCount) }}</strong>
            </div>
            <div class="engine-track">
              <span
                :class="`engine-bar engine-${engine.engineType.toLowerCase()}`"
                :style="{ width: `${engineBarWidth(engine.launchCount)}%` }" />
            </div>
          </div>
        </div>
      </section>
    </template>
  </main>
</template>

<script setup lang="ts">
  import {
    computed,
    onBeforeUnmount,
    onMounted,
    reactive,
    ref,
    watch
  } from 'vue'
  import { useI18n } from 'vue-i18n'
  import { useRouter } from 'vue-router'
  import {
    getOverviewSummary,
    getOverviewTrend,
    IOverviewEngine,
    IOverviewHealthIssue,
    IOverviewSummary,
    IOverviewTrend
  } from '@/api/overview'

  type Range = '1h' | '1d' | '7d'
  type CardTone = 'violet' | 'blue' | 'green' | 'orange' | 'cyan' | 'pink'
  type ChartCoordinate = {
    x: number
    y: number
    timestamp: number
    value: number
  }
  type ChartHoverTarget = {
    x: number
    timestamp: number
    executions: number
    failures: number
  }
  type ChartHoverState = ChartHoverTarget & {
    guideX: number
    executionY: number
    failureY: number
  }

  const { t } = useI18n()
  const router = useRouter()
  const ranges: Range[] = ['1h', '1d', '7d']
  const selectedRange = ref<Range>('1h')
  const summary = ref<IOverviewSummary | null>(null)
  const trend = ref<IOverviewTrend | null>(null)
  const loading = ref(true)
  const trendLoading = ref(false)
  const errorMessage = ref('')
  const trendErrorMessage = ref('')
  const updatedAt = ref<Date | null>(null)
  const refreshIntervalSeconds = ref(30)
  const refreshOptions = computed(() => [
    { label: t('overview.refresh_5s'), value: 5 },
    { label: t('overview.refresh_10s'), value: 10 },
    { label: t('overview.refresh_30s'), value: 30 },
    { label: t('overview.refresh_off'), value: 0 }
  ])
  let refreshTimer: number | undefined
  let refreshInFlight = false
  const activeChartTooltip = ref<ChartHoverState | null>(null)
  const chartWrapElement = ref<HTMLElement | null>(null)
  let chartResizeObserver: ResizeObserver | undefined
  const chartFrame = reactive({
    width: 760,
    height: 208,
    left: 44,
    right: 10,
    top: 8,
    bottom: 34,
    plotWidth: 706,
    plotHeight: 166,
    baseline: 174
  })

  function openSqlRecords(state?: string): void {
    router.push({
      path: '/management/sql-record',
      query: state ? { state } : undefined
    })
  }

  function resizeChart(width: number): void {
    if (!Number.isFinite(width) || width <= 0) return
    chartFrame.width = width
    chartFrame.plotWidth = Math.max(
      chartFrame.width - chartFrame.left - chartFrame.right,
      1
    )
    activeChartTooltip.value = null
  }

  function resizeChartToContainer(): void {
    if (chartWrapElement.value) resizeChart(chartWrapElement.value.clientWidth)
  }

  watch(chartWrapElement, (element) => {
    chartResizeObserver?.disconnect()
    chartResizeObserver = undefined
    if (!element) return

    resizeChart(element.clientWidth)
    if (typeof ResizeObserver !== 'undefined') {
      chartResizeObserver = new ResizeObserver(([entry]) => {
        if (entry) resizeChart(entry.contentRect.width)
      })
      chartResizeObserver.observe(element)
    }
  })

  const healthStatus = computed(() => summary.value?.health.status || 'UNKNOWN')
  const healthTagType = computed(() => {
    if (healthStatus.value === 'CRITICAL') return 'danger'
    if (healthStatus.value === 'WARNING') return 'warning'
    if (healthStatus.value === 'NORMAL') return 'success'
    return 'info'
  })
  const healthLabel = computed(() => {
    const labels: Record<string, string> = {
      NORMAL: t('overview.health_normal'),
      WARNING: t('overview.health_warning'),
      CRITICAL: t('overview.health_critical'),
      UNKNOWN: t('overview.health_unknown')
    }
    return labels[healthStatus.value] || labels.UNKNOWN
  })
  const visibleHealthIssues = computed(() =>
    (summary.value?.health.issues || []).slice(0, 3)
  )
  const healthAlertTitle = computed(() =>
    t('overview.health_alert', {
      count: summary.value?.health.issues.length || 0
    })
  )
  const freshnessLabel = computed(() => {
    if (!summary.value) return t('overview.freshness_unknown')
    if (summary.value.dataStatus.status === 'STALE') {
      return t('overview.freshness_stale')
    }
    if (summary.value.dataStatus.status === 'INITIALIZING') {
      return t('overview.freshness_initializing')
    }
    return t('overview.freshness_ok', {
      seconds: Math.max(0, Math.round(summary.value.dataStatus.ageMs / 1000))
    })
  })
  const trendEmptyTitle = computed(() =>
    summary.value?.dataStatus.status === 'INITIALIZING'
      ? t('overview.trend_collecting')
      : t('overview.no_trend_data')
  )
  const trendEmptyDescription = computed(() =>
    summary.value?.dataStatus.status === 'INITIALIZING'
      ? t('overview.trend_collecting_desc')
      : t('overview.no_trend_data_desc')
  )
  const trendStatusLabel = computed(() => {
    const status = summary.value?.dataStatus.status
    if (status === 'INITIALIZING')
      return t('overview.trend_status_initializing')
    if (status === 'STALE') return t('overview.trend_status_stale')
    return t('overview.trend_status_ready')
  })
  const trendStatusType = computed(() => {
    const status = summary.value?.dataStatus.status
    if (status === 'STALE') return 'warning'
    if (status === 'INITIALIZING') return 'info'
    return 'success'
  })

  const metricCards = computed(() => {
    if (!summary.value) return []
    return [
      {
        label: t('overview.server_started'),
        value: summary.value.serverStartCount,
        percent: false,
        hint: t('overview.live_servers', {
          count: summary.value.liveServerCount
        }),
        icon: 'Monitor',
        tone: 'violet' as CardTone
      },
      {
        label: t('overview.active_users'),
        value: summary.value.activeUserCount,
        percent: false,
        hint: t('overview.currently_connected'),
        icon: 'User',
        tone: 'blue' as CardTone
      },
      {
        label: t('overview.active_sessions'),
        value: summary.value.activeSessionCount,
        percent: false,
        hint: t('overview.session_hint'),
        icon: 'Connection',
        tone: 'green' as CardTone
      },
      {
        label: t('overview.active_operations'),
        value: summary.value.operations.open,
        percent: false,
        hint: t('overview.active_operations_hint'),
        icon: 'Operation',
        tone: 'orange' as CardTone
      },
      {
        label: t('overview.running_operations'),
        value: summary.value.operations.running,
        percent: false,
        hint: t('overview.running_operations_hint'),
        icon: 'VideoPlay',
        tone: 'cyan' as CardTone
      },
      {
        label: t('overview.failure_rate'),
        value: summary.value.operations.failureRate,
        percent: true,
        hint: t('overview.failure_rate_hint'),
        icon: 'Warning',
        tone: 'pink' as CardTone
      },
      {
        label: t('overview.sql_p95'),
        value: summary.value.operations.latency.p95,
        unit: 'ms',
        percent: false,
        hint: t('overview.sql_p95_hint'),
        icon: 'Stopwatch',
        tone: 'violet' as CardTone
      },
      {
        label: t('overview.engine_launching'),
        value: summary.value.engineHealth.launching,
        percent: false,
        hint: t('overview.engine_launching_hint'),
        icon: 'Cpu',
        tone: 'blue' as CardTone
      }
    ]
  })

  const engineRows = computed<IOverviewEngine[]>(() => {
    const types = [
      'JDBC',
      'SPARK_SQL',
      'FLINK_SQL',
      'TRINO',
      'HIVE_SQL',
      'DATA_AGENT'
    ]
    return types.map((engineType) => {
      const engine = summary.value?.engines.find(
        (item) => item.engineType === engineType
      )
      return engine || { engineType, launchCount: 0 }
    })
  })

  const maxEngineLaunches = computed(() =>
    Math.max(...engineRows.value.map((engine) => engine.launchCount), 1)
  )

  const chartTimestamps = computed(() =>
    Array.from(
      new Set(
        [
          ...(trend.value?.points || []),
          ...(trend.value?.failedPoints || [])
        ].map((point) => point.timestamp)
      )
    ).sort((left, right) => left - right)
  )

  const chartRangeMs: Record<Range, number> = {
    '1h': 60 * 60 * 1000,
    '1d': 24 * 60 * 60 * 1000,
    '7d': 7 * 24 * 60 * 60 * 1000
  }

  const chartDomain = computed(() => {
    const timestamps = chartTimestamps.value
    const intervalMs = trend.value?.intervalMs || 60 * 1000
    if (timestamps.length >= 2) {
      return {
        start: timestamps[0],
        end: Math.max(
          timestamps[timestamps.length - 1] + intervalMs,
          timestamps[0] + intervalMs
        )
      }
    }
    if (timestamps.length === 1) {
      return {
        start: timestamps[0] - intervalMs,
        end: timestamps[0] + intervalMs
      }
    }
    const now = updatedAt.value?.getTime() || Date.now()
    return {
      start: now - chartRangeMs[selectedRange.value],
      end: now
    }
  })

  const chartMaxValue = computed(() =>
    getNiceChartMax(
      Math.max(
        ...(trend.value?.points || []).map((point) => point.value),
        ...(trend.value?.failedPoints || []).map((point) => point.value),
        1
      )
    )
  )

  function getNiceChartMax(value: number): number {
    if (value <= 1) return 1
    const magnitude = 10 ** Math.floor(Math.log10(value))
    const normalized = value / magnitude
    const step =
      normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10
    return step * magnitude
  }

  function chartX(timestamp: number): number {
    const { start, end } = chartDomain.value
    const boundedTimestamp = Math.min(Math.max(timestamp, start), end)
    return (
      chartFrame.left +
      ((boundedTimestamp - start) / (end - start)) * chartFrame.plotWidth
    )
  }

  function chartY(value: number): number {
    return (
      chartFrame.baseline -
      (value / chartMaxValue.value) * chartFrame.plotHeight
    )
  }

  const chartCoordinates = computed<ChartCoordinate[]>(() =>
    (trend.value?.points || []).map((point) => ({
      x: chartX(point.timestamp),
      y: chartY(point.value),
      timestamp: point.timestamp,
      value: point.value
    }))
  )

  const failureChartCoordinates = computed<ChartCoordinate[]>(() =>
    (trend.value?.failedPoints || []).map((point) => ({
      x: chartX(point.timestamp),
      y: chartY(point.value),
      timestamp: point.timestamp,
      value: point.value
    }))
  )

  const chartHoverTargets = computed<ChartHoverTarget[]>(() =>
    chartTimestamps.value.map((timestamp) => {
      const executions =
        trend.value?.points.find((point) => point.timestamp === timestamp)
          ?.value || 0
      const failures =
        trend.value?.failedPoints?.find(
          (point) => point.timestamp === timestamp
        )?.value || 0
      return {
        x: chartX(timestamp),
        timestamp,
        executions,
        failures
      }
    })
  )

  const chartGuides = computed(() => {
    const middleValue =
      chartMaxValue.value >= 2 ? Math.ceil(chartMaxValue.value / 2) : 0
    const values = Array.from(new Set([chartMaxValue.value, middleValue, 0]))
    return values.map((value) => ({ value, y: chartY(value) }))
  })

  const chartXAxisLabels = computed(() => {
    const { start, end } = chartDomain.value
    const count = Math.max(
      2,
      Math.min(5, Math.floor(chartFrame.plotWidth / 140) + 1)
    )
    return Array.from({ length: count }, (_, index) => {
      const timestamp = start + ((end - start) * index) / (count - 1)
      return {
        timestamp,
        x: chartX(timestamp),
        label: formatChartTimestamp(timestamp),
        anchor: index === 0 ? 'start' : index === count - 1 ? 'end' : 'middle'
      }
    })
  })

  const chartTooltipStyle = computed(() => {
    if (!activeChartTooltip.value) return {}
    const x = Math.min(
      Math.max((activeChartTooltip.value.guideX / chartFrame.width) * 100, 12),
      88
    )
    const rawY =
      (Math.min(
        activeChartTooltip.value.executionY,
        activeChartTooltip.value.failureY
      ) /
        chartFrame.height) *
      100
    const y = Math.min(Math.max(rawY, 24), 86)
    return { left: `${x}%`, top: `${y}%` }
  })

  function buildStepPath(points: ChartCoordinate[]): string {
    return points
      .map((point, index) => {
        if (index === 0) return `M ${point.x} ${point.y}`
        return `H ${point.x} V ${point.y}`
      })
      .join(' ')
  }

  const linePath = computed(() => buildStepPath(chartCoordinates.value))

  const areaPath = computed(() => {
    if (chartCoordinates.value.length === 0) return ''
    const first = chartCoordinates.value[0]
    const last = chartCoordinates.value[chartCoordinates.value.length - 1]
    return (
      `${linePath.value} L ${last.x} ${chartFrame.baseline} ` +
      `L ${first.x} ${chartFrame.baseline} Z`
    )
  })

  const failureLinePath = computed(() =>
    buildStepPath(failureChartCoordinates.value)
  )

  function updateChartTooltip(event: MouseEvent): void {
    const overlay = event.currentTarget as SVGRectElement
    const bounds = overlay.getBoundingClientRect()
    const ratio = Math.min(
      Math.max((event.clientX - bounds.left) / bounds.width, 0),
      1
    )
    const guideX = chartFrame.left + ratio * chartFrame.plotWidth
    const nearest = chartHoverTargets.value.reduce<ChartHoverTarget | null>(
      (current, target) =>
        !current || Math.abs(target.x - guideX) < Math.abs(current.x - guideX)
          ? target
          : current,
      null
    )
    activeChartTooltip.value = nearest
      ? {
          ...nearest,
          guideX,
          executionY: chartY(nearest.executions),
          failureY: chartY(nearest.failures)
        }
      : null
  }

  function formatNumber(value: number): string {
    return new Intl.NumberFormat().format(value)
  }

  function formatPercent(value: number): string {
    return `${value.toFixed(2)}%`
  }

  function formatDuration(value: number): string {
    if (!value) return '0 ms'
    if (value < 1000) return `${Math.round(value)} ms`
    return `${(value / 1000).toFixed(1)} s`
  }

  function formatTime(value: Date): string {
    return value.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  }

  function formatChartTimestamp(timestamp: number): string {
    const date = new Date(timestamp)
    const options: Intl.DateTimeFormatOptions =
      selectedRange.value === '7d'
        ? { month: '2-digit', day: '2-digit' }
        : {
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit'
          }
    return new Intl.DateTimeFormat(undefined, options).format(date)
  }

  function formatIssueValue(issue: IOverviewHealthIssue): string {
    if (issue.code.includes('RATE')) return formatPercent(issue.value)
    if (issue.code.includes('LATENCY')) return formatDuration(issue.value)
    return formatNumber(issue.value)
  }

  function engineLabel(engineType: string): string {
    const labels: Record<string, string> = {
      JDBC: 'JDBC',
      SPARK_SQL: 'Spark SQL',
      FLINK_SQL: 'Flink SQL',
      TRINO: 'Trino',
      HIVE_SQL: 'Hive SQL',
      DATA_AGENT: 'Data Agent'
    }
    return labels[engineType] || engineType
  }

  function engineBarWidth(value: number): number {
    return Math.max(
      value === 0 ? 0 : 8,
      Math.round((value / maxEngineLaunches.value) * 100)
    )
  }

  async function refresh(): Promise<void> {
    if (refreshInFlight) return
    refreshInFlight = true
    loading.value = !summary.value
    errorMessage.value = ''
    trendErrorMessage.value = ''
    try {
      const [summaryResult, trendResult] = await Promise.allSettled([
        getOverviewSummary(),
        getOverviewTrend(selectedRange.value)
      ])
      if (summaryResult.status === 'fulfilled') {
        summary.value = summaryResult.value
      } else {
        errorMessage.value = formatLoadError(summaryResult.reason)
      }
      if (trendResult.status === 'fulfilled') {
        trend.value = trendResult.value
      } else {
        trendErrorMessage.value = formatLoadError(
          trendResult.reason,
          t('overview.trend_load_failed')
        )
      }
      if (
        summaryResult.status === 'fulfilled' ||
        trendResult.status === 'fulfilled'
      ) {
        updatedAt.value = new Date()
      }
    } finally {
      loading.value = false
      refreshInFlight = false
    }
  }

  function restartRefreshTimer(): void {
    if (refreshTimer) window.clearInterval(refreshTimer)
    refreshTimer = undefined
    if (refreshIntervalSeconds.value > 0) {
      refreshTimer = window.setInterval(
        refresh,
        refreshIntervalSeconds.value * 1000
      )
    }
  }

  async function changeRange(range: Range): Promise<void> {
    if (range === selectedRange.value && trend.value) return
    trendLoading.value = true
    try {
      trend.value = await getOverviewTrend(range)
      selectedRange.value = range
      trendErrorMessage.value = ''
    } catch (error) {
      trendErrorMessage.value = formatLoadError(
        error,
        t('overview.trend_load_failed')
      )
    } finally {
      trendLoading.value = false
    }
  }

  function formatLoadError(
    error: unknown,
    fallback = t('overview.load_failed')
  ): string {
    return error instanceof Error && error.message ? error.message : fallback
  }

  onMounted(() => {
    window.addEventListener('resize', resizeChartToContainer)
    refresh()
    restartRefreshTimer()
  })

  onBeforeUnmount(() => {
    if (refreshTimer) window.clearInterval(refreshTimer)
    window.removeEventListener('resize', resizeChartToContainer)
    chartResizeObserver?.disconnect()
  })
</script>

<style lang="scss" scoped>
  .overview-page {
    --overview-text: #202b40;
    --overview-muted: #64748b;
    --overview-border: #e5e9f0;
    min-height: calc(100vh - 104px);
    padding: 16px 12px 28px;
    color: var(--overview-text);
    background: linear-gradient(135deg, #f5f3fc 0%, #f7f8fb 32%, #f4f7fc 100%);
    font-variant-numeric: tabular-nums;
    container: overview / inline-size;
  }

  .page-heading,
  .panel-heading,
  .metric-card-topline,
  .engine-row-heading,
  .heading-actions,
  .pool-stats,
  .chart-footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .page-heading {
    gap: 20px;
    margin: 0 0 24px;
  }

  .page-heading > div:first-child {
    padding-left: 14px;
    border-left: 3px solid #7c5cff;
  }

  .eyebrow,
  .panel-kicker {
    color: #7154d8;
    font-size: 11px;
    font-weight: 500;
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }

  h1,
  h2,
  p {
    margin: 0;
  }

  h1 {
    margin-top: 5px;
    color: #172033;
    font-size: 24px;
    font-weight: 600;
    letter-spacing: -0.025em;
  }

  .page-heading p,
  .panel-heading p {
    margin-top: 7px;
    color: #7b8497;
    font-size: 13px;
  }

  .heading-actions {
    flex-shrink: 0;
    gap: 12px;
    flex-wrap: wrap;
  }
  .refresh-select {
    width: 92px;
  }
  .refresh-select :deep(.el-input__wrapper) {
    border-radius: 10px;
    box-shadow: 0 0 0 1px #e5e8ef inset;
  }
  .live-tag {
    border: 0;
    font-weight: 700;
  }

  .live-dot,
  .healthy-badge span {
    display: inline-block;
    width: 7px;
    height: 7px;
    margin-right: 7px;
    border-radius: 50%;
    background: #21c77a;
    box-shadow: 0 0 0 4px rgb(33 199 122 / 13%);
  }
  .live-dot.status-WARNING {
    background: #e6a23c;
    box-shadow: 0 0 0 4px rgb(230 162 60 / 13%);
  }
  .live-dot.status-CRITICAL {
    background: #f56c6c;
    box-shadow: 0 0 0 4px rgb(245 108 108 / 13%);
  }
  .live-dot.status-UNKNOWN {
    background: #909399;
    box-shadow: 0 0 0 4px rgb(144 147 153 / 13%);
  }

  .updated-at,
  .panel-note {
    color: var(--overview-muted);
    font-size: 12px;
  }
  .refresh-button {
    color: #536079;
    font-size: 18px;
  }
  .error-alert,
  .health-alert,
  .trend-error-alert,
  .overview-skeleton {
    margin-bottom: 18px;
  }
  .health-alert :deep(.el-alert__content) {
    display: flex;
    flex-wrap: wrap;
    gap: 5px 16px;
  }

  .metrics-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 1px;
    margin-bottom: 20px;
    overflow: hidden;
    border: 1px solid var(--overview-border);
    border-radius: 12px;
    background: var(--overview-border);
  }

  .health-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 16px;
    margin-bottom: 20px;
  }
  .health-panel {
    min-width: 0;
  }
  .health-summary {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    margin-top: 20px;
    flex-direction: column;
    gap: 6px;
  }
  .health-value {
    font-size: 22px;
    font-weight: 600;
    letter-spacing: -0.025em;
  }
  .health-value.status-NORMAL {
    color: #19b979;
  }
  .health-value.status-WARNING {
    color: #d58a28;
  }
  .health-value.status-CRITICAL {
    color: #d85668;
  }
  .health-value.status-UNKNOWN {
    color: #8a94a7;
  }
  .health-summary span,
  .health-issue-count span,
  .health-stat-grid span {
    color: var(--overview-muted);
    font-size: 12px;
  }
  .health-issue-count {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 14px;
    padding-top: 10px;
    border-top: 1px solid #edf0f6;
  }
  .health-issue-count strong {
    color: #38445a;
    font-size: 18px;
  }
  .health-stat-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px 12px;
    margin-top: 20px;
  }
  .health-stat-grid > div {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 5px;
  }
  .health-stat-grid strong {
    color: #38445a;
    font-size: 18px;
    font-weight: 600;
  }
  .health-stat-grid .danger-value {
    color: #d85668;
  }

  .metric-card,
  .panel {
    border: 1px solid var(--overview-border);
    border-radius: 12px;
    background: #fff;
  }

  .metric-card {
    position: relative;
    min-width: 0;
    padding: 18px;
    overflow: hidden;
    border: 0;
    border-radius: 0;
    background: linear-gradient(160deg, var(--accent-soft), #fff 68%), #fff;
  }

  .tone-violet {
    --accent: #7c5cff;
    --accent-soft: rgb(124 92 255 / 10%);
  }
  .tone-blue {
    --accent: #3182f6;
    --accent-soft: rgb(49 130 246 / 10%);
  }
  .tone-green {
    --accent: #19b979;
    --accent-soft: rgb(25 185 121 / 10%);
  }
  .tone-orange {
    --accent: #f29b38;
    --accent-soft: rgb(242 155 56 / 11%);
  }
  .tone-cyan {
    --accent: #09a9c4;
    --accent-soft: rgb(9 169 196 / 10%);
  }
  .tone-pink {
    --accent: #e65f9b;
    --accent-soft: rgb(230 95 155 / 10%);
  }

  .metric-label {
    color: #526078;
    font-size: 12px;
    font-weight: 500;
  }

  .metric-icon {
    z-index: 1;
    display: grid;
    width: 28px;
    height: 28px;
    place-items: center;
    border-radius: 8px;
    color: var(--accent);
    background: var(--accent-soft);
    font-size: 16px;
  }

  .metric-value {
    position: relative;
    z-index: 1;
    margin-top: 12px;
    color: #172033;
    font-size: 28px;
    font-weight: 600;
    letter-spacing: -0.035em;
    line-height: 1.2;
  }

  .metric-unit {
    margin-left: 4px;
    color: var(--overview-muted);
    font-size: 12px;
    font-weight: 400;
    letter-spacing: normal;
  }

  .metric-hint {
    position: relative;
    z-index: 1;
    margin-top: 8px;
    color: #738096;
    font-size: 11px;
  }

  .content-grid {
    display: grid;
    grid-template-columns: minmax(0, 2fr) minmax(320px, 1fr);
    gap: 16px;
    margin-bottom: 20px;
  }

  .panel {
    --panel-accent: #7154d8;
    min-width: 0;
    padding: 20px;
    box-shadow: 0 2px 8px rgb(42 51 83 / 3%);
  }
  .panel-kicker {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--panel-accent);
  }
  .panel-kicker::before {
    width: 3px;
    height: 10px;
    border-radius: 2px;
    background: currentColor;
    content: '';
  }
  .health-panel:nth-child(1) {
    --panel-accent: #258a70;
    background: linear-gradient(145deg, #f1faf6, #fff 60%);
  }
  .health-panel:nth-child(2) {
    --panel-accent: #3f79cc;
    background: linear-gradient(145deg, #f2f7ff, #fff 60%);
  }
  .health-panel:nth-child(3) {
    --panel-accent: #7154d8;
    background: linear-gradient(145deg, #f6f3fe, #fff 60%);
  }
  .health-panel:nth-child(4) {
    --panel-accent: #23869b;
    background: linear-gradient(145deg, #f0f9fb, #fff 60%);
  }
  .pool-panel {
    --panel-accent: #3f79cc;
  }
  .panel-heading {
    align-items: flex-start;
    gap: 20px;
  }
  .panel-heading.compact {
    align-items: flex-start;
    flex-wrap: wrap;
    gap: 8px;
  }
  .trend-actions {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 10px;
  }
  .trend-status {
    flex-shrink: 0;
    margin-top: 1px;
    border: 0;
    font-size: 11px;
    font-weight: 600;
  }
  h2 {
    margin-top: 5px;
    color: #202a3d;
    font-size: 16px;
    font-weight: 600;
    letter-spacing: -0.015em;
  }
  .range-switch {
    flex-shrink: 0;
    display: inline-flex;
    padding: 3px;
    gap: 2px;
    border-radius: 8px;
    background: #f2f4f8;
  }

  .range-switch :deep(.el-button) {
    min-width: 42px;
    height: 28px;
    margin: 0;
    padding: 0 12px;
    border: 0;
    border-radius: 5px;
    background: transparent;
    color: #66748c;
    font-size: 12px;
    font-weight: 500;
  }

  .range-switch :deep(.el-button--primary) {
    color: #fff;
    background: #7656e8;
    box-shadow: 0 2px 5px rgb(118 86 232 / 20%);
  }

  .chart-wrap {
    position: relative;
    min-height: 220px;
    margin-top: 16px;
  }

  .trend-chart {
    display: block;
    width: 100%;
    height: 208px;
  }
  .chart-guide {
    stroke: #e7ebf3;
    stroke-width: 1;
  }
  .chart-axis-label {
    fill: #738096;
    font-size: 11px;
    font-weight: 400;
  }
  .chart-y-label {
    text-anchor: end;
  }
  .chart-x-label {
    font-variant-numeric: tabular-nums;
  }
  .chart-crosshair {
    stroke: #a9b3c5;
    stroke-dasharray: 4 4;
    stroke-width: 1;
  }
  .chart-active-point {
    fill: #fff;
    stroke: #6548ed;
    stroke-width: 3;
  }
  .failure-active-point {
    stroke: #e65f72;
    stroke-width: 2.5;
  }
  .chart-area {
    fill: url(#trend-fill);
  }
  .chart-line {
    fill: none;
    stroke: #7c5cff;
    stroke-linecap: round;
    stroke-linejoin: round;
    stroke-width: 2;
  }
  .failure-line {
    fill: none;
    stroke: #e65f72;
    stroke-linecap: round;
    stroke-linejoin: round;
    stroke-width: 2;
    stroke-dasharray: 5 5;
  }
  .chart-hover-overlay {
    fill: transparent;
    cursor: crosshair;
    pointer-events: all;
  }
  .chart-tooltip {
    position: absolute;
    z-index: 2;
    display: flex;
    min-width: 150px;
    padding: 10px 12px;
    transform: translate(-50%, -112%);
    flex-direction: column;
    gap: 5px;
    border: 1px solid rgb(255 255 255 / 12%);
    border-radius: 8px;
    color: #c9d1df;
    background: rgb(27 35 52 / 96%);
    box-shadow: 0 10px 26px rgb(21 29 45 / 25%);
    font-size: 11px;
    line-height: 1.35;
    pointer-events: none;
  }
  .chart-tooltip strong {
    color: #fff;
    font-size: 12px;
  }
  .chart-tooltip-row {
    display: grid;
    grid-template-columns: 8px 1fr auto;
    align-items: center;
    gap: 7px;
  }
  .chart-tooltip-row b {
    color: #fff;
    font-weight: 700;
  }
  .chart-tooltip-swatch {
    width: 7px;
    height: 7px;
    border-radius: 50%;
  }
  .chart-tooltip-swatch.executions {
    background: #7c5cff;
  }
  .chart-tooltip-swatch.failures {
    background: #e65f72;
  }

  .empty-chart {
    display: flex;
    height: 192px;
    align-items: center;
    justify-content: center;
    flex-direction: column;
    gap: 8px;
    color: #8d97a9;
  }

  .empty-chart-icon {
    display: grid;
    width: 42px;
    height: 42px;
    place-items: center;
    border-radius: 14px;
    color: #7c5cff;
    background: #f0edff;
    font-size: 22px;
  }
  .empty-chart strong {
    color: #59657a;
    font-size: 13px;
  }
  .empty-chart span {
    font-size: 12px;
  }
  .chart-footer {
    margin-top: 10px;
    justify-content: flex-start;
    flex-wrap: wrap;
    gap: 8px 20px;
    color: #738096;
    font-size: 11px;
  }
  .chart-footer > span:last-child {
    margin-left: auto;
  }
  .chart-footer span:first-child {
    display: flex;
    align-items: center;
    gap: 7px;
    color: #69758b;
    font-weight: 600;
  }
  .failure-legend {
    display: flex;
    align-items: center;
    gap: 7px;
    color: #c95b6a;
    font-weight: 600;
  }
  .failure-legend .legend-dot {
    background: #e65f72;
  }
  .legend-dot {
    display: inline-block;
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #7c5cff;
  }

  .healthy-badge {
    display: flex;
    align-items: center;
    color: #1eae71;
    font-size: 11px;
    font-weight: 700;
  }
  .pool-primary-stats {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
    margin-top: 20px;
  }
  .pool-primary-stat {
    min-width: 0;
    padding: 14px;
    border-radius: 8px;
    background: #f7f8fb;
  }
  .pool-primary-stat.is-running {
    background: #f0f5ff;
  }
  .pool-primary-stat.is-waiting {
    background: #fff7ec;
  }
  .pool-primary-label {
    display: flex;
    align-items: center;
    gap: 7px;
    color: #7c879b;
    font-size: 12px;
    font-weight: 700;
  }
  .pool-status-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #5c8dff;
  }
  .is-waiting .pool-status-dot {
    background: #f2a65a;
  }
  .pool-primary-stat strong {
    display: block;
    margin-top: 10px;
    color: #202a3d;
    font-size: 28px;
    font-weight: 600;
    letter-spacing: -0.05em;
    line-height: 1;
  }
  .pool-primary-stat > span:last-child {
    display: block;
    margin-top: 8px;
    color: var(--overview-muted);
    font-size: 11px;
    line-height: 1.5;
  }
  .pool-help {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    margin: 12px 0 16px;
    color: var(--overview-muted);
    font-size: 11px;
    line-height: 1.5;
  }
  .pool-help-icon {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    width: 15px;
    height: 15px;
    border: 1px solid #b8c0d0;
    border-radius: 50%;
    color: #8a94a7;
    font-size: 10px;
    font-weight: 800;
  }
  .pool-stats {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px;
    padding-top: 14px;
    border-top: 1px solid var(--overview-border);
  }
  .pool-stats > div {
    display: flex;
    flex: 1 1 70px;
    min-width: 0;
    flex-direction: column;
    gap: 6px;
  }
  .stat-label {
    color: var(--overview-muted);
    font-size: 12px;
  }
  .pool-stats strong {
    color: #38445a;
    font-size: 16px;
    font-weight: 600;
  }

  .engine-panel {
    padding-bottom: 25px;
  }
  .engine-health-grid {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 10px;
    margin-top: 18px;
    padding: 16px 0;
    border-top: 1px solid var(--overview-border);
    border-bottom: 1px solid var(--overview-border);
  }
  .engine-health-grid > div {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 6px;
  }
  .engine-health-grid span {
    color: var(--overview-muted);
    font-size: 12px;
  }
  .engine-health-grid strong {
    color: #38445a;
    font-size: 18px;
    font-weight: 600;
  }
  .engine-health-grid .danger-value {
    color: #d85668;
  }
  .engine-health-grid .warning-value {
    color: #d58a28;
  }
  .engine-grid {
    display: grid;
    grid-template-columns: repeat(6, minmax(0, 1fr));
    gap: 20px;
    margin-top: 18px;
  }
  .engine-row-heading {
    margin-bottom: 9px;
  }
  .engine-name {
    color: #69758b;
    font-size: 12px;
    font-weight: 700;
  }
  .engine-row-heading strong {
    color: #263147;
    font-size: 14px;
  }
  .engine-track {
    height: 4px;
    overflow: hidden;
    border-radius: 8px;
    background: #eef1f6;
  }
  .engine-bar {
    display: block;
    height: 100%;
    border-radius: inherit;
    background: #7c5cff;
    transition: width 0.35s ease;
  }
  @media (min-width: 1600px) {
    .metrics-grid {
      grid-template-columns: repeat(8, minmax(0, 1fr));
    }
  }

  @media (max-width: 1200px) {
    .metrics-grid {
      grid-template-columns: repeat(4, minmax(0, 1fr));
    }
    .health-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
  @media (max-width: 1100px) {
    .page-heading {
      align-items: flex-start;
      flex-direction: column;
    }
    .content-grid {
      grid-template-columns: 1fr;
    }
    .health-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .engine-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .engine-health-grid {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
  }
  @media (max-width: 560px) {
    .overview-page {
      padding: 0 0 20px;
    }
    .metrics-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 9px;
    }
    .health-grid {
      grid-template-columns: 1fr;
    }
    .metric-card {
      padding: 13px;
    }
    .metric-value {
      font-size: 25px;
    }
    .panel {
      padding: 16px;
    }
    .panel-heading {
      align-items: flex-start;
      flex-direction: column;
    }
    .trend-actions {
      width: 100%;
      align-items: stretch;
      flex-direction: column;
    }
    .trend-status {
      align-self: flex-start;
    }
    .range-switch {
      align-self: stretch;
    }
    .range-switch :deep(.el-button) {
      flex: 1;
    }
    .engine-grid {
      grid-template-columns: 1fr;
    }
    .engine-health-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .heading-actions {
      width: 100%;
      justify-content: flex-start;
    }
    .chart-footer > span:last-child {
      width: 100%;
      margin-left: 0;
    }
  }

  @container overview (max-width: 660px) {
    .metrics-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .health-grid {
      grid-template-columns: 1fr;
    }
    .engine-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .panel-heading {
      flex-wrap: wrap;
      gap: 12px;
    }
    .chart-footer > span:last-child {
      width: 100%;
      margin-left: 0;
    }
  }

  @container overview (max-width: 420px) {
    .panel {
      padding: 16px;
    }
    .metric-card {
      padding: 14px;
    }
    .metric-hint {
      overflow-wrap: anywhere;
    }
    .engine-health-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
</style>
