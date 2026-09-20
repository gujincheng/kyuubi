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
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    CircleCheck,
    Connection,
    DataBoard,
    Delete,
    Edit,
    Key,
    Plus,
    Refresh,
    Search,
    SwitchButton,
    WarningFilled,
    View
  } from '@element-plus/icons-vue'
  import { useI18n } from 'vue-i18n'
  import {
    createDatasource,
    createStorageCredential,
    deleteDatasource,
    deleteStorageCredential,
    listDatasourceProfiles,
    listDatasources,
    listStorageCredentials,
    refreshDatasources,
    testDatasourceConfiguration,
    testSavedDatasource,
    updateDatasource,
    updateStorageCredential,
    type Datasource,
    type DatasourceConnectionTestResult,
    type DatasourceEngineType,
    type DatasourcePayload,
    type DatasourceStatus,
    type StorageCredential,
    type StorageCredentialPayload
  } from '@/api/datasource'

  interface DatasourcePreset {
    value: string
    label: string
    driverClass: string
    url: string
    tone: string
  }

  const { t } = useI18n()
  const text = (key: string, params?: Record<string, unknown>) =>
    t(`datasource_manager.${key}`, params ?? {})

  const presets: DatasourcePreset[] = [
    {
      value: 'starrocks',
      label: 'StarRocks',
      driverClass: 'com.mysql.cj.jdbc.Driver',
      url: 'jdbc:mysql://127.0.0.1:9030/database',
      tone: '#6857f5'
    },
    {
      value: 'mysql',
      label: 'MySQL',
      driverClass: 'com.mysql.cj.jdbc.Driver',
      url: 'jdbc:mysql://127.0.0.1:3306/database',
      tone: '#1d8cf8'
    },
    {
      value: 'postgresql',
      label: 'PostgreSQL',
      driverClass: 'org.postgresql.Driver',
      url: 'jdbc:postgresql://127.0.0.1:5432/database',
      tone: '#2c6aa0'
    },
    {
      value: 'oracle',
      label: 'Oracle',
      driverClass: 'oracle.jdbc.OracleDriver',
      url: 'jdbc:oracle:thin:@127.0.0.1:1521/service',
      tone: '#ed3e3e'
    }
  ]

  const poolFields = [
    { key: 'maximumPoolSize', label: text('maximum_pool'), placeholder: '10' },
    { key: 'minimumIdle', label: text('minimum_idle'), placeholder: '1' },
    {
      key: 'connectionTimeout',
      label: text('connection_timeout'),
      placeholder: '30000'
    },
    {
      key: 'idleTimeout',
      label: text('idle_timeout'),
      placeholder: '600000'
    },
    {
      key: 'maxLifetime',
      label: text('max_lifetime'),
      placeholder: '1800000'
    },
    {
      key: 'validationTimeout',
      label: text('validation_timeout'),
      placeholder: '5000'
    }
  ] as const

  const loading = ref(false)
  const refreshing = ref(false)
  const saving = ref(false)
  const testing = ref('')
  const error = ref('')
  const updatedAt = ref(0)
  const datasources = ref<Datasource[]>([])
  const sessionProfiles = ref<string[]>([])
  const storageCredentials = ref<StorageCredential[]>([])
  const keyword = ref('')
  const statusFilter = ref<'ALL' | DatasourceStatus>('ALL')
  const typeFilter = ref('ALL')
  const dialogVisible = ref(false)
  const detailVisible = ref(false)
  const editing = ref(false)
  const activeDatasource = ref<Datasource | null>(null)
  const draftTestResult = ref<DatasourceConnectionTestResult | null>(null)
  const formRef = ref<FormInstance>()
  const credentialDialogVisible = ref(false)
  const credentialEditing = ref(false)
  const credentialSaving = ref(false)
  const credentialForm = reactive<StorageCredentialPayload>({
    id: '',
    provider: 's3',
    accessKeyId: '',
    secretAccessKey: '',
    sessionToken: '',
    description: ''
  })

  const emptyForm = (): DatasourcePayload => ({
    label: '',
    engineType: 'jdbc',
    jdbcType: 'starrocks',
    driverClass: 'com.mysql.cj.jdbc.Driver',
    jdbcUrl: 'jdbc:mysql://127.0.0.1:9030/database',
    username: '',
    plainPassword: '',
    connectionPoolParams: {},
    status: 'ENABLED',
    description: '',
    icebergConfig: {
      catalogName: 'lake',
      catalogType: 'hive',
      uri: '',
      warehouse: '',
      s3Endpoint: '',
      s3PathStyleAccess: true,
      s3SslEnabled: false,
      sessionProfile: '',
      credentialRef: ''
    }
  })

  const form = reactive<DatasourcePayload>(emptyForm())

  const rules: FormRules<DatasourcePayload> = {
    label: [
      { required: true, message: text('label_required'), trigger: 'blur' },
      {
        pattern: /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/,
        message: text('label_format'),
        trigger: 'blur'
      }
    ],
    jdbcType: [
      { required: true, message: text('jdbc_type_required'), trigger: 'change' }
    ],
    driverClass: [
      { required: true, message: text('driver_required'), trigger: 'blur' }
    ],
    jdbcUrl: [
      { required: true, message: text('url_required'), trigger: 'blur' },
      {
        pattern: /^jdbc:/,
        message: text('url_format'),
        trigger: 'blur'
      }
    ]
  }

  const sourceType = (item: Datasource) =>
    item.engineType === 'spark' ? 'iceberg' : item.jdbcType

  const sourceAddress = (item: Datasource) =>
    item.engineType === 'spark' ? (item.icebergConfig?.uri ?? '') : item.jdbcUrl

  const sourceTypes = computed(() =>
    [...new Set(datasources.value.map(sourceType))].sort()
  )

  const summary = computed(() => ({
    total: datasources.value.length,
    enabled: datasources.value.filter((item) => item.status === 'ENABLED')
      .length,
    disabled: datasources.value.filter((item) => item.status === 'DISABLED')
      .length,
    types: sourceTypes.value.length
  }))

  const filteredDatasources = computed(() => {
    const query = keyword.value.trim().toLowerCase()
    return datasources.value.filter((item) => {
      const matchesKeyword =
        !query ||
        [
          item.label,
          sourceType(item),
          sourceAddress(item),
          item.username,
          item.icebergConfig?.catalogName ?? '',
          item.icebergConfig?.warehouse ?? '',
          item.description
        ]
          .join(' ')
          .toLowerCase()
          .includes(query)
      const matchesStatus =
        statusFilter.value === 'ALL' || item.status === statusFilter.value
      const matchesType =
        typeFilter.value === 'ALL' || sourceType(item) === typeFilter.value
      return matchesKeyword && matchesStatus && matchesType
    })
  })

  const formatTime = (timestamp: number) =>
    timestamp ? new Date(timestamp).toLocaleTimeString() : '--'

  const typeColor = (source: Datasource | string) => {
    const type = typeof source === 'string' ? source : sourceType(source)
    if (type === 'iceberg') return '#6d5dfc'
    return presets.find((item) => item.value === type)?.tone ?? '#68748a'
  }

  const loadProfiles = async () => {
    try {
      sessionProfiles.value = await listDatasourceProfiles()
    } catch {
      sessionProfiles.value = []
    }
  }

  const loadStorageCredentials = async () => {
    try {
      storageCredentials.value = await listStorageCredentials()
    } catch {
      storageCredentials.value = []
    }
  }

  const loadDatasources = async () => {
    loading.value = true
    error.value = ''
    try {
      const [items] = await Promise.all([
        listDatasources(),
        loadProfiles(),
        loadStorageCredentials()
      ])
      datasources.value = items
      updatedAt.value = Date.now()
    } catch (err) {
      error.value = err instanceof Error ? err.message : text('load_failed')
    } finally {
      loading.value = false
    }
  }

  const reloadFromStore = async () => {
    refreshing.value = true
    try {
      await refreshDatasources()
      await loadDatasources()
      ElMessage.success(text('refresh_success'))
    } catch (err) {
      ElMessage.error(
        err instanceof Error ? err.message : text('refresh_failed')
      )
    } finally {
      refreshing.value = false
    }
  }

  const resetForm = () => {
    Object.assign(form, emptyForm())
    draftTestResult.value = null
    formRef.value?.clearValidate?.()
  }

  const openCreate = () => {
    editing.value = false
    resetForm()
    dialogVisible.value = true
  }

  const openEdit = (datasource: Datasource) => {
    editing.value = true
    Object.assign(form, {
      label: datasource.label,
      engineType: datasource.engineType,
      jdbcType: datasource.jdbcType,
      driverClass: datasource.driverClass,
      jdbcUrl: datasource.jdbcUrl,
      username: datasource.username,
      plainPassword: '',
      connectionPoolParams: { ...datasource.connectionPoolParams },
      status: datasource.status,
      description: datasource.description,
      icebergConfig: datasource.icebergConfig
        ? { ...datasource.icebergConfig }
        : emptyForm().icebergConfig
    })
    draftTestResult.value = null
    dialogVisible.value = true
  }

  const resetCredentialForm = () => {
    Object.assign(credentialForm, {
      id: '',
      provider: 's3',
      accessKeyId: '',
      secretAccessKey: '',
      sessionToken: '',
      description: ''
    })
  }

  const openCredentialCreate = () => {
    credentialEditing.value = false
    resetCredentialForm()
    credentialDialogVisible.value = true
  }

  const openCredentialEdit = (credential: StorageCredential) => {
    credentialEditing.value = true
    Object.assign(credentialForm, {
      id: credential.id,
      provider: credential.provider,
      accessKeyId: '',
      secretAccessKey: '',
      sessionToken: '',
      description: credential.description
    })
    credentialDialogVisible.value = true
  }

  const saveCredential = async () => {
    if (!credentialForm.id.trim()) {
      ElMessage.warning(text('credential_id_required'))
      return
    }
    if (
      !credentialEditing.value &&
      (!credentialForm.accessKeyId || !credentialForm.secretAccessKey)
    ) {
      ElMessage.warning(text('credential_secret_required'))
      return
    }
    credentialSaving.value = true
    try {
      const payload = {
        ...credentialForm,
        id: credentialForm.id.trim(),
        description: credentialForm.description.trim()
      }
      if (credentialEditing.value) {
        await updateStorageCredential(payload.id, payload)
      } else {
        await createStorageCredential(payload)
      }
      credentialDialogVisible.value = false
      await loadStorageCredentials()
      ElMessage.success(text('credential_saved'))
    } catch (err) {
      ElMessage.error(err instanceof Error ? err.message : text('save_failed'))
    } finally {
      credentialSaving.value = false
    }
  }

  const removeCredential = async (credential: StorageCredential) => {
    try {
      await ElMessageBox.confirm(
        text('credential_delete_confirm', { id: credential.id }),
        text('credential_delete_title'),
        { type: 'warning' }
      )
      await deleteStorageCredential(credential.id)
      await loadStorageCredentials()
      ElMessage.success(text('credential_deleted'))
    } catch (err: any) {
      if (err !== 'cancel' && err !== 'close') {
        ElMessage.error(
          err instanceof Error ? err.message : text('delete_failed')
        )
      }
    }
  }

  const selectPreset = (preset: DatasourcePreset) => {
    form.jdbcType = preset.value
    form.driverClass = preset.driverClass
    form.jdbcUrl = preset.url
    draftTestResult.value = null
  }

  const selectEngine = (engineType: DatasourceEngineType) => {
    form.engineType = engineType
    draftTestResult.value = null
    formRef.value?.clearValidate?.()
  }

  const normalizedPayload = (): DatasourcePayload => {
    const jdbc = form.engineType === 'jdbc'
    return {
      ...form,
      label: form.label.trim(),
      jdbcType: jdbc ? form.jdbcType.trim().toLowerCase() : '',
      driverClass: jdbc ? form.driverClass.trim() : '',
      jdbcUrl: jdbc ? form.jdbcUrl.trim() : '',
      username: jdbc ? form.username.trim() : '',
      plainPassword: jdbc ? form.plainPassword : '',
      connectionPoolParams: jdbc
        ? Object.fromEntries(
            Object.entries(form.connectionPoolParams).filter(
              ([, value]) => String(value).trim() !== ''
            )
          )
        : {},
      description: form.description.trim(),
      icebergConfig:
        !jdbc && form.icebergConfig
          ? {
              ...form.icebergConfig,
              catalogName: form.icebergConfig.catalogName.trim(),
              uri: form.icebergConfig.uri.trim(),
              warehouse: form.icebergConfig.warehouse.trim(),
              s3Endpoint: form.icebergConfig.s3Endpoint.trim(),
              sessionProfile: form.icebergConfig.sessionProfile.trim(),
              credentialRef: form.icebergConfig.credentialRef.trim()
            }
          : null
    }
  }

  const validateEngineConfig = () => {
    if (form.engineType === 'jdbc') return true
    const iceberg = form.icebergConfig
    if (!iceberg?.catalogName || !iceberg.uri || !iceberg.warehouse) {
      ElMessage.warning(text('iceberg_required'))
      return false
    }
    return true
  }

  const testDraft = async () => {
    if (
      !(await formRef.value?.validate().catch(() => false)) ||
      !validateEngineConfig()
    )
      return
    testing.value = 'draft'
    draftTestResult.value = null
    try {
      const result = await testDatasourceConfiguration(normalizedPayload())
      draftTestResult.value = result
      ElMessage.success(
        text('test_success_message', { latency: result.latencyMillis })
      )
    } catch (err: any) {
      const failure = err?.response?.data
      const message =
        failure?.message ??
        (err instanceof Error ? err.message : text('test_failed'))
      draftTestResult.value = {
        success: false,
        message,
        latencyMillis: failure?.latencyMillis ?? 0,
        databaseProduct: failure?.databaseProduct ?? '',
        databaseVersion: failure?.databaseVersion ?? ''
      }
      ElMessage.error(message)
    } finally {
      testing.value = ''
    }
  }

  const save = async () => {
    if (
      !(await formRef.value?.validate().catch(() => false)) ||
      !validateEngineConfig()
    )
      return
    saving.value = true
    try {
      const payload = normalizedPayload()
      if (editing.value) {
        await updateDatasource(payload.label, payload)
      } else {
        await createDatasource(payload)
      }
      dialogVisible.value = false
      await loadDatasources()
      ElMessage.success(
        text(editing.value ? 'update_success' : 'create_success')
      )
    } catch (err) {
      ElMessage.error(err instanceof Error ? err.message : text('save_failed'))
    } finally {
      saving.value = false
    }
  }

  const testSaved = async (datasource: Datasource) => {
    testing.value = datasource.label
    try {
      const result = await testSavedDatasource(datasource.label)
      ElMessage.success(
        text('saved_test_success', {
          product: result.databaseProduct,
          latency: result.latencyMillis
        })
      )
    } catch (err) {
      ElMessage.error(err instanceof Error ? err.message : text('test_failed'))
    } finally {
      testing.value = ''
    }
  }

  const toggleStatus = async (datasource: Datasource) => {
    const nextStatus: DatasourceStatus =
      datasource.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
    try {
      await updateDatasource(datasource.label, {
        label: datasource.label,
        engineType: datasource.engineType,
        jdbcType: datasource.jdbcType,
        driverClass: datasource.driverClass,
        jdbcUrl: datasource.jdbcUrl,
        username: datasource.username,
        plainPassword: '',
        connectionPoolParams: datasource.connectionPoolParams,
        status: nextStatus,
        description: datasource.description,
        icebergConfig: datasource.icebergConfig
      })
      await loadDatasources()
      ElMessage.success(
        text(nextStatus === 'ENABLED' ? 'enable_success' : 'disable_success')
      )
    } catch (err) {
      ElMessage.error(
        err instanceof Error ? err.message : text('update_failed')
      )
    }
  }

  const remove = async (datasource: Datasource) => {
    try {
      await ElMessageBox.confirm(
        text('delete_confirm', { label: datasource.label }),
        text('delete_title'),
        { type: 'warning' }
      )
      await deleteDatasource(datasource.label)
      await loadDatasources()
      ElMessage.success(text('delete_success'))
    } catch (err: any) {
      if (err !== 'cancel' && err !== 'close') {
        ElMessage.error(
          err instanceof Error ? err.message : text('delete_failed')
        )
      }
    }
  }

  const showDetail = (datasource: Datasource) => {
    activeDatasource.value = datasource
    detailVisible.value = true
  }

  onMounted(loadDatasources)

  defineExpose({
    datasources,
    filteredDatasources,
    summary,
    form,
    loadDatasources,
    openCreate,
    openCredentialCreate,
    openCredentialEdit,
    saveCredential,
    removeCredential,
    openEdit,
    save,
    testDraft,
    testSaved,
    toggleStatus,
    remove,
    selectEngine
  })
</script>

<template>
  <div class="datasource-page">
    <section class="page-hero">
      <div class="hero-copy">
        <span class="eyebrow">{{ text('eyebrow') }}</span>
        <h1>{{ text('title') }}</h1>
        <p>{{ text('subtitle') }}</p>
      </div>
      <div class="hero-actions">
        <span v-if="updatedAt" class="updated-at">
          {{ text('updated_at', { time: formatTime(updatedAt) }) }}
        </span>
        <el-button
          :icon="Refresh"
          :loading="refreshing"
          @click="reloadFromStore">
          {{ text('refresh_store') }}
        </el-button>
        <el-button :icon="Key" @click="openCredentialCreate">
          {{ text('manage_credentials') }}
        </el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">
          {{ text('add') }}
        </el-button>
      </div>
    </section>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      class="error-alert" />

    <section class="summary-grid">
      <article class="summary-card summary-total">
        <div class="summary-icon"><DataBoard /></div>
        <div
          ><span>{{ text('total') }}</span
          ><strong>{{ summary.total }}</strong></div
        >
        <small>{{ text('total_hint') }}</small>
      </article>
      <article class="summary-card summary-enabled">
        <div class="summary-icon"><CircleCheck /></div>
        <div
          ><span>{{ text('enabled') }}</span
          ><strong>{{ summary.enabled }}</strong></div
        >
        <small>{{ text('enabled_hint') }}</small>
      </article>
      <article class="summary-card summary-disabled">
        <div class="summary-icon"><SwitchButton /></div>
        <div
          ><span>{{ text('disabled') }}</span
          ><strong>{{ summary.disabled }}</strong></div
        >
        <small>{{ text('disabled_hint') }}</small>
      </article>
      <article class="summary-card summary-types">
        <div class="summary-icon"><Connection /></div>
        <div
          ><span>{{ text('types') }}</span
          ><strong>{{ summary.types }}</strong></div
        >
        <small>{{ text('types_hint') }}</small>
      </article>
    </section>

    <section class="credential-panel">
      <div class="credential-panel-heading">
        <div>
          <span class="panel-kicker">{{ text('credential_eyebrow') }}</span>
          <h2>{{ text('credential_panel_title') }}</h2>
          <p>{{ text('credential_panel_hint') }}</p>
        </div>
        <el-button type="primary" plain :icon="Plus" @click="openCredentialCreate">
          {{ text('credential_add') }}
        </el-button>
      </div>
      <div v-if="storageCredentials.length" class="credential-list">
        <article
          v-for="credential in storageCredentials"
          :key="credential.id"
          class="credential-card">
          <div class="credential-card-icon"><Key /></div>
          <div class="credential-card-copy">
            <strong>{{ credential.id }}</strong>
            <span>{{ credential.description || text('credential_no_description') }}</span>
          </div>
          <el-tag type="success" effect="light">{{ text('credential_configured') }}</el-tag>
          <div class="credential-card-actions">
            <el-button link @click="openCredentialEdit(credential)">{{ text('edit') }}</el-button>
            <el-button link type="danger" @click="removeCredential(credential)">{{ text('delete') }}</el-button>
          </div>
        </article>
      </div>
      <el-empty v-else :description="text('credential_empty_list')" :image-size="56" />
    </section>

    <section class="workspace-card">
      <div class="workspace-heading">
        <div>
          <span class="panel-kicker">{{ text('workspace_eyebrow') }}</span>
          <h2>{{ text('workspace_title') }}</h2>
        </div>
        <div class="filters">
          <el-input
            v-model="keyword"
            :prefix-icon="Search"
            :placeholder="text('search_placeholder')"
            clearable />
          <el-select v-model="statusFilter" class="status-filter">
            <el-option :label="text('all_statuses')" value="ALL" />
            <el-option :label="text('enabled')" value="ENABLED" />
            <el-option :label="text('disabled')" value="DISABLED" />
          </el-select>
          <el-select v-model="typeFilter" class="type-filter">
            <el-option :label="text('all_types')" value="ALL" />
            <el-option
              v-for="source in sourceTypes"
              :key="source"
              :label="source === 'iceberg' ? 'Iceberg' : source"
              :value="source" />
          </el-select>
        </div>
      </div>

      <div v-loading="loading" class="datasource-grid">
        <article
          v-for="datasource in filteredDatasources"
          :key="datasource.label"
          class="datasource-card"
          :class="{ disabled: datasource.status === 'DISABLED' }">
          <div
            class="card-accent"
            :style="{ background: typeColor(datasource) }" />
          <header class="card-header">
            <div
              class="database-badge"
              :style="{ '--badge-color': typeColor(datasource) }">
              {{ sourceType(datasource).slice(0, 2).toUpperCase() }}
            </div>
            <div class="card-identity">
              <strong>{{ datasource.label }}</strong>
              <span>
                {{
                  datasource.engineType === 'spark'
                    ? 'Apache Iceberg · Spark SQL'
                    : `${datasource.jdbcType} · JDBC`
                }}
              </span>
            </div>
            <el-tag
              :type="datasource.status === 'ENABLED' ? 'success' : 'info'"
              effect="light"
              round>
              {{
                text(datasource.status === 'ENABLED' ? 'enabled' : 'disabled')
              }}
            </el-tag>
          </header>

          <p class="description">
            {{ datasource.description || text('no_description') }}
          </p>

          <div class="connection-surface">
            <span>{{
              text(
                datasource.engineType === 'spark' ? 'metastore_uri' : 'jdbc_url'
              )
            }}</span>
            <code :title="sourceAddress(datasource)">{{
              sourceAddress(datasource)
            }}</code>
          </div>

          <div class="metadata-row">
            <div>
              <span>{{
                text(
                  datasource.engineType === 'spark'
                    ? 'catalog_name'
                    : 'username'
                )
              }}</span>
              <strong>{{
                datasource.engineType === 'spark'
                  ? datasource.icebergConfig?.catalogName
                  : datasource.username || text('anonymous')
              }}</strong>
            </div>
            <div>
              <span>{{
                text(
                  datasource.engineType === 'spark'
                    ? 'session_profile'
                    : 'credential'
                )
              }}</span>
              <strong class="credential">
                <Key v-if="datasource.engineType === 'jdbc'" />
                {{
                  datasource.engineType === 'spark'
                    ? datasource.icebergConfig?.sessionProfile ||
                      text('not_configured')
                    : datasource.credentialStored
                      ? text('credential_stored')
                      : text('credential_empty')
                }}
              </strong>
            </div>
            <div>
              <span>{{
                text(
                  datasource.engineType === 'spark' ? 'catalog_type' : 'pool'
                )
              }}</span>
              <strong>{{
                datasource.engineType === 'spark'
                  ? 'Hive'
                  : Object.keys(datasource.connectionPoolParams).length
              }}</strong>
            </div>
          </div>

          <footer class="card-actions">
            <el-button
              class="test-button"
              :icon="Connection"
              :loading="testing === datasource.label"
              @click="testSaved(datasource)">
              {{ text('test_connection') }}
            </el-button>
            <div class="secondary-actions">
              <el-tooltip :content="text('detail')">
                <el-button
                  circle
                  :icon="View"
                  @click="showDetail(datasource)" />
              </el-tooltip>
              <el-tooltip :content="text('edit')">
                <el-button circle :icon="Edit" @click="openEdit(datasource)" />
              </el-tooltip>
              <el-tooltip
                :content="
                  text(datasource.status === 'ENABLED' ? 'disable' : 'enable')
                ">
                <el-button
                  :aria-label="
                    text(datasource.status === 'ENABLED' ? 'disable' : 'enable')
                  "
                  circle
                  plain
                  class="status-toggle"
                  :class="
                    datasource.status === 'ENABLED'
                      ? 'status-toggle-disable'
                      : 'status-toggle-enable'
                  "
                  :type="
                    datasource.status === 'ENABLED' ? 'warning' : 'success'
                  "
                  :icon="SwitchButton"
                  @click="toggleStatus(datasource)" />
              </el-tooltip>
              <el-tooltip :content="text('delete')">
                <el-button
                  circle
                  type="danger"
                  plain
                  :icon="Delete"
                  @click="remove(datasource)" />
              </el-tooltip>
            </div>
          </footer>
        </article>

        <button class="create-card" type="button" @click="openCreate">
          <span><Plus /></span>
          <strong>{{ text('add') }}</strong>
          <small>{{ text('add_hint') }}</small>
        </button>
      </div>

      <el-empty
        v-if="!loading && !filteredDatasources.length && datasources.length"
        :description="text('no_matches')" />
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="text(editing ? 'edit_title' : 'create_title')"
      width="760px"
      class="datasource-dialog"
      destroy-on-close>
      <div class="dialog-intro">
        <div class="dialog-icon"><Connection /></div>
        <div>
          <strong>{{
            text(
              form.engineType === 'spark'
                ? 'iceberg_dialog_heading'
                : 'dialog_heading'
            )
          }}</strong>
          <span>{{ text('dialog_hint') }}</span>
        </div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <section class="form-section">
          <div class="section-heading">
            <span>01</span>
            <div
              ><strong>{{ text('engine_type') }}</strong
              ><small>{{ text('engine_type_hint') }}</small></div
            >
          </div>
          <div class="engine-grid">
            <button
              type="button"
              class="engine-button"
              :class="{ active: form.engineType === 'jdbc' }"
              @click="selectEngine('jdbc')">
              <span class="engine-icon">DB</span>
              <span>
                <strong>JDBC</strong>
                <small>{{ text('jdbc_engine_hint') }}</small>
              </span>
            </button>
            <button
              type="button"
              class="engine-button iceberg"
              :class="{ active: form.engineType === 'spark' }"
              @click="selectEngine('spark')">
              <span class="engine-icon">IC</span>
              <span>
                <strong>Apache Iceberg</strong>
                <small>{{ text('iceberg_engine_hint') }}</small>
              </span>
            </button>
          </div>
          <div
            v-if="form.engineType === 'jdbc'"
            class="preset-grid nested-presets">
            <button
              v-for="preset in presets"
              :key="preset.value"
              type="button"
              :class="[
                'preset-button',
                { active: form.jdbcType === preset.value }
              ]"
              :style="{ '--preset-color': preset.tone }"
              @click="selectPreset(preset)">
              <span>{{ preset.label.slice(0, 2).toUpperCase() }}</span>
              {{ preset.label }}
            </button>
          </div>
        </section>

        <section class="form-section">
          <div class="section-heading">
            <span>02</span>
            <div
              ><strong>{{
                text(
                  form.engineType === 'spark'
                    ? 'iceberg_config'
                    : 'basic_config'
                )
              }}</strong
              ><small>{{
                text(
                  form.engineType === 'spark'
                    ? 'iceberg_config_hint'
                    : 'basic_config_hint'
                )
              }}</small></div
            >
          </div>
          <div class="form-grid">
            <el-form-item :label="text('label')" prop="label">
              <el-input
                v-model="form.label"
                :disabled="editing"
                :placeholder="text('label_placeholder')" />
            </el-form-item>
            <el-form-item
              v-if="form.engineType === 'jdbc'"
              :label="text('jdbc_type')"
              prop="jdbcType">
              <el-input v-model="form.jdbcType" />
            </el-form-item>
            <el-form-item
              v-if="form.engineType === 'jdbc'"
              class="full-row"
              :label="text('driver_class')"
              prop="driverClass">
              <el-input v-model="form.driverClass" />
            </el-form-item>
            <el-form-item
              v-if="form.engineType === 'jdbc'"
              class="full-row"
              :label="text('jdbc_url')"
              prop="jdbcUrl">
              <el-input v-model="form.jdbcUrl" />
              <small class="field-hint">{{ text('url_secret_hint') }}</small>
            </el-form-item>
            <el-form-item
              v-if="form.engineType === 'jdbc'"
              :label="text('username')">
              <el-input v-model="form.username" autocomplete="off" />
            </el-form-item>
            <el-form-item
              v-if="form.engineType === 'jdbc'"
              :label="text('password')">
              <el-input
                v-model="form.plainPassword"
                type="password"
                show-password
                autocomplete="new-password"
                :placeholder="
                  editing ? text('password_keep') : text('password_placeholder')
                " />
              <small v-if="editing" class="field-hint">{{
                text('password_keep_hint')
              }}</small>
            </el-form-item>
            <template v-if="form.engineType === 'spark' && form.icebergConfig">
              <el-form-item
                :label="text('catalog_name')"
                prop="icebergConfig.catalogName"
                :rules="[
                  {
                    required: true,
                    message: text('catalog_name_required'),
                    trigger: 'blur'
                  }
                ]">
                <el-input
                  v-model="form.icebergConfig.catalogName"
                  placeholder="lake" />
              </el-form-item>
              <el-form-item :label="text('catalog_type')">
                <el-select v-model="form.icebergConfig.catalogType" disabled>
                  <el-option label="Hive Metastore" value="hive" />
                </el-select>
              </el-form-item>
              <el-form-item
                class="full-row"
                :label="text('metastore_uri')"
                prop="icebergConfig.uri"
                :rules="[
                  {
                    required: true,
                    message: text('metastore_required'),
                    trigger: 'blur'
                  }
                ]">
                <el-input
                  v-model="form.icebergConfig.uri"
                  placeholder="thrift://metastore.example.com:9083" />
              </el-form-item>
              <el-form-item
                class="full-row"
                :label="text('warehouse')"
                prop="icebergConfig.warehouse"
                :rules="[
                  {
                    required: true,
                    message: text('warehouse_required'),
                    trigger: 'blur'
                  }
                ]">
                <el-input
                  v-model="form.icebergConfig.warehouse"
                  placeholder="s3a://iceberg/warehouse" />
              </el-form-item>
              <el-form-item :label="text('s3_endpoint')">
                <el-input
                  v-model="form.icebergConfig.s3Endpoint"
                  placeholder="http://s3.example.com:9000" />
              </el-form-item>
              <el-form-item :label="text('session_profile')">
                <el-select
                  v-model="form.icebergConfig.sessionProfile"
                  clearable
                  :placeholder="text('session_profile_placeholder')">
                  <el-option
                    v-for="profile in sessionProfiles"
                    :key="profile"
                    :label="profile"
                    :value="profile" />
                </el-select>
                <small class="field-hint">{{
                  text('session_profile_hint')
                }}</small>
              </el-form-item>
              <el-form-item :label="text('storage_credential')">
                <el-select
                  v-model="form.icebergConfig.credentialRef"
                  clearable
                  :placeholder="text('credential_runtime_placeholder')">
                  <el-option
                    v-for="credential in storageCredentials"
                    :key="credential.id"
                    :label="credential.id"
                    :value="credential.id" />
                </el-select>
                <small class="field-hint">{{
                  text('storage_credential_hint')
                }}</small>
              </el-form-item>
              <div class="full-row iceberg-options">
                <div>
                  <strong>{{ text('s3_path_style') }}</strong>
                  <small>{{ text('s3_path_style_hint') }}</small>
                </div>
                <el-switch v-model="form.icebergConfig.s3PathStyleAccess" />
                <div>
                  <strong>{{ text('s3_ssl') }}</strong>
                  <small>{{ text('s3_ssl_hint') }}</small>
                </div>
                <el-switch v-model="form.icebergConfig.s3SslEnabled" />
              </div>
            </template>
            <el-form-item class="full-row" :label="text('description')">
              <el-input
                v-model="form.description"
                type="textarea"
                :rows="2"
                maxlength="2000"
                show-word-limit />
            </el-form-item>
          </div>
        </section>

        <section
          v-if="form.engineType === 'jdbc'"
          class="form-section pool-section">
          <div class="section-heading">
            <span>03</span>
            <div
              ><strong>{{ text('pool_config') }}</strong
              ><small>{{ text('pool_config_hint') }}</small></div
            >
          </div>
          <div class="pool-grid">
            <el-form-item
              v-for="field in poolFields"
              :key="field.key"
              :label="field.label">
              <el-input
                v-model="form.connectionPoolParams[field.key]"
                inputmode="numeric"
                :placeholder="field.placeholder" />
            </el-form-item>
          </div>
        </section>

        <section class="form-section state-section">
          <div>
            <strong>{{ text('enable_after_save') }}</strong>
            <small>{{ text('enable_after_save_hint') }}</small>
          </div>
          <el-switch
            v-model="form.status"
            active-value="ENABLED"
            inactive-value="DISABLED"
            inline-prompt
            :active-text="text('enabled')"
            :inactive-text="text('disabled')" />
        </section>
      </el-form>

      <div
        v-if="draftTestResult"
        class="test-result"
        :class="draftTestResult.success ? 'success' : 'failure'">
        <CircleCheck v-if="draftTestResult.success" />
        <WarningFilled v-else />
        <div>
          <strong>
            {{
              text(
                draftTestResult.success
                  ? 'connection_available'
                  : 'connection_unavailable'
              )
            }}
          </strong>
          <span>
            <template v-if="draftTestResult.success">
              {{ draftTestResult.databaseProduct }}
              {{ draftTestResult.databaseVersion }} ·
              {{ draftTestResult.latencyMillis }} ms
            </template>
            <template v-else>{{ draftTestResult.message }}</template>
          </span>
        </div>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <el-button
            class="draft-test-button"
            :icon="Connection"
            :loading="testing === 'draft'"
            @click="testDraft">
            {{ text('test_before_save') }}
          </el-button>
          <div>
            <el-button @click="dialogVisible = false">{{
              text('cancel')
            }}</el-button>
            <el-button type="primary" :loading="saving" @click="save">
              {{ text(editing ? 'save_changes' : 'create') }}
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <el-drawer
      v-model="detailVisible"
      :title="text('detail_title')"
      size="520px"
      class="datasource-drawer">
      <template v-if="activeDatasource">
        <div class="detail-hero">
          <div
            class="database-badge large"
            :style="{ '--badge-color': typeColor(activeDatasource) }">
            {{ sourceType(activeDatasource).slice(0, 2).toUpperCase() }}
          </div>
          <div>
            <h3>{{ activeDatasource.label }}</h3>
            <span>{{
              activeDatasource.engineType === 'spark'
                ? 'Apache Iceberg · Spark SQL'
                : `${activeDatasource.jdbcType} · JDBC`
            }}</span>
          </div>
          <el-tag
            :type="activeDatasource.status === 'ENABLED' ? 'success' : 'info'">
            {{
              text(
                activeDatasource.status === 'ENABLED' ? 'enabled' : 'disabled'
              )
            }}
          </el-tag>
        </div>
        <div class="detail-section">
          <span class="detail-title">{{ text('connection_info') }}</span>
          <dl>
            <template v-if="activeDatasource.engineType === 'jdbc'">
              <div>
                <dt>{{ text('jdbc_url') }}</dt>
                <dd>{{ activeDatasource.jdbcUrl }}</dd>
              </div>
              <div>
                <dt>{{ text('driver_class') }}</dt>
                <dd>{{ activeDatasource.driverClass }}</dd>
              </div>
              <div>
                <dt>{{ text('username') }}</dt>
                <dd>{{ activeDatasource.username || text('anonymous') }}</dd>
              </div>
              <div>
                <dt>{{ text('credential') }}</dt>
                <dd>{{
                  activeDatasource.credentialStored
                    ? text('credential_stored')
                    : text('credential_empty')
                }}</dd>
              </div>
            </template>
            <template v-else-if="activeDatasource.icebergConfig">
              <div>
                <dt>{{ text('catalog_name') }}</dt>
                <dd>{{ activeDatasource.icebergConfig.catalogName }}</dd>
              </div>
              <div>
                <dt>{{ text('metastore_uri') }}</dt>
                <dd>{{ activeDatasource.icebergConfig.uri }}</dd>
              </div>
              <div>
                <dt>{{ text('warehouse') }}</dt>
                <dd>{{ activeDatasource.icebergConfig.warehouse }}</dd>
              </div>
              <div>
                <dt>{{ text('s3_endpoint') }}</dt>
                <dd>{{
                  activeDatasource.icebergConfig.s3Endpoint ||
                  text('not_configured')
                }}</dd>
              </div>
              <div>
                <dt>{{ text('session_profile') }}</dt>
                <dd>{{
                  activeDatasource.icebergConfig.sessionProfile ||
                  text('not_configured')
                }}</dd>
              </div>
              <div>
                <dt>{{ text('storage_credential') }}</dt>
                <dd>{{
                  activeDatasource.icebergConfig.credentialRef ||
                  text('credential_runtime')
                }}</dd>
              </div>
            </template>
          </dl>
        </div>
        <div
          v-if="activeDatasource.engineType === 'jdbc'"
          class="detail-section">
          <span class="detail-title">{{ text('pool_config') }}</span>
          <div
            v-if="Object.keys(activeDatasource.connectionPoolParams).length"
            class="pool-tags">
            <el-tag
              v-for="(value, key) in activeDatasource.connectionPoolParams"
              :key="key"
              effect="plain">
              {{ key }} = {{ value }}
            </el-tag>
          </div>
          <p v-else class="empty-copy">{{ text('default_pool') }}</p>
        </div>
        <div class="detail-section">
          <span class="detail-title">{{ text('description') }}</span>
          <p class="empty-copy">{{
            activeDatasource.description || text('no_description')
          }}</p>
        </div>
      </template>
    </el-drawer>

    <el-dialog
      v-model="credentialDialogVisible"
      :title="
        text(
          credentialEditing
            ? 'credential_edit_title'
            : 'credential_create_title'
        )
      "
      width="520px"
      destroy-on-close>
      <el-alert
        :title="text('credential_dialog_hint')"
        type="info"
        show-icon
        :closable="false"
        class="credential-alert" />
      <el-form label-position="top">
        <el-form-item :label="text('credential_id')" required>
          <el-input v-model="credentialForm.id" :disabled="credentialEditing" />
        </el-form-item>
        <el-form-item
          :label="text('access_key_id')"
          :required="!credentialEditing">
          <el-input
            v-model="credentialForm.accessKeyId"
            autocomplete="new-password" />
        </el-form-item>
        <el-form-item
          :label="text('secret_access_key')"
          :required="!credentialEditing">
          <el-input
            v-model="credentialForm.secretAccessKey"
            type="password"
            show-password
            autocomplete="new-password" />
        </el-form-item>
        <el-form-item :label="text('session_token')">
          <el-input
            v-model="credentialForm.sessionToken"
            type="password"
            show-password
            autocomplete="new-password" />
        </el-form-item>
        <el-form-item :label="text('description')">
          <el-input v-model="credentialForm.description" maxlength="1024" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="credentialDialogVisible = false">{{
          text('cancel')
        }}</el-button>
        <el-button type="primary" :loading="credentialSaving" @click="saveCredential">
          {{ text('credential_save') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
  .datasource-page {
    min-height: 100%;
    padding: 22px 24px 36px;
    color: #1e293b;
    background:
      radial-gradient(
        circle at 9% 2%,
        rgba(108, 92, 231, 0.08),
        transparent 25%
      ),
      radial-gradient(
        circle at 96% 12%,
        rgba(29, 140, 248, 0.06),
        transparent 24%
      ),
      #f6f8fc;
  }

  :deep(.datasource-dialog) {
    max-height: 90vh;
    margin-top: 5vh;
    overflow: hidden;

    .el-dialog__body {
      max-height: calc(90vh - 132px);
      padding-top: 8px;
      overflow-y: auto;
    }

    .el-dialog__footer {
      position: relative;
      z-index: 1;
      background: #fff;
      border-top: 1px solid #edf0f6;
    }
  }

  .page-hero {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 24px;
    margin-bottom: 18px;
  }

  .eyebrow,
  .panel-kicker {
    color: #6d5dfc;
    font-size: 11px;
    font-weight: 750;
    letter-spacing: 0.12em;
    text-transform: uppercase;
  }

  h1,
  h2,
  h3,
  p {
    margin: 0;
  }

  .hero-copy h1 {
    margin-top: 5px;
    font-size: 27px;
    line-height: 1.25;
    letter-spacing: -0.02em;
  }

  .hero-copy p {
    margin-top: 6px;
    color: #708097;
    font-size: 14px;
  }

  .hero-actions {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .updated-at {
    color: #94a0b2;
    font-size: 12px;
  }

  .error-alert {
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
    display: grid;
    grid-template-columns: 46px 1fr;
    gap: 12px;
    min-height: 95px;
    padding: 17px 18px;
    overflow: hidden;
    background: rgba(255, 255, 255, 0.9);
    border: 1px solid rgba(222, 228, 240, 0.9);
    border-radius: 16px;
    box-shadow: 0 8px 24px rgba(35, 45, 75, 0.045);

    &::after {
      position: absolute;
      top: -25px;
      right: -25px;
      width: 80px;
      height: 80px;
      content: '';
      background: var(--card-tone);
      border-radius: 50%;
      opacity: 0.08;
    }

    > div:not(.summary-icon) {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
    }

    span {
      color: #708097;
      font-size: 13px;
    }

    strong {
      color: #182238;
      font-size: 25px;
      line-height: 1;
    }

    small {
      grid-column: 2;
      color: #9aa6b7;
      font-size: 11px;
    }
  }

  .summary-icon {
    display: grid;
    grid-row: 1 / span 2;
    place-items: center;
    width: 44px;
    height: 44px;
    color: var(--card-tone);
    background: color-mix(in srgb, var(--card-tone) 11%, white);
    border-radius: 13px;

    svg {
      width: 20px;
    }
  }

  .summary-total {
    --card-tone: #6d5dfc;
  }
  .summary-enabled {
    --card-tone: #1fad72;
  }
  .summary-disabled {
    --card-tone: #f0a128;
  }
  .summary-types {
    --card-tone: #1689df;
  }

  .workspace-card {
    min-height: 460px;
    padding: 20px;
    background: rgba(255, 255, 255, 0.94);
    border: 1px solid #e1e6f0;
    border-radius: 18px;
    box-shadow: 0 12px 36px rgba(31, 42, 75, 0.055);
  }

  .credential-panel {
    padding: 18px 20px;
    margin-bottom: 16px;
    background: rgba(255, 255, 255, 0.94);
    border: 1px solid #e1e6f0;
    border-radius: 18px;
    box-shadow: 0 10px 30px rgba(31, 42, 75, 0.045);
  }

  .credential-panel-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 18px;
    padding-bottom: 14px;
    border-bottom: 1px solid #edf0f6;

    h2 {
      margin-top: 4px;
      font-size: 17px;
    }

    p {
      margin-top: 4px;
      color: #8793a7;
      font-size: 12px;
    }
  }

  .credential-list {
    display: grid;
    gap: 8px;
    padding-top: 12px;
  }

  .credential-card {
    display: flex;
    align-items: center;
    gap: 11px;
    padding: 10px 12px;
    background: #fbfcff;
    border: 1px solid #edf0f6;
    border-radius: 11px;
  }

  .credential-card-icon {
    display: grid;
    flex: 0 0 auto;
    place-items: center;
    width: 32px;
    height: 32px;
    color: #6d5dfc;
    background: #eeebff;
    border-radius: 9px;
  }

  .credential-card-copy {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;

    strong {
      color: #26324a;
      font-size: 13px;
    }

    span {
      margin-top: 2px;
      overflow: hidden;
      color: #8b98ac;
      font-size: 11px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .credential-card-actions {
    display: flex;
    gap: 4px;
  }

  .workspace-heading {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 18px;
    padding-bottom: 16px;
    border-bottom: 1px solid #edf0f6;

    h2 {
      margin-top: 4px;
      font-size: 18px;
    }
  }

  .filters {
    display: grid;
    grid-template-columns: minmax(210px, 1fr) 130px 140px;
    gap: 8px;
    width: min(620px, 60%);
  }

  .datasource-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(280px, 1fr));
    gap: 14px;
    min-height: 280px;
    padding-top: 18px;
  }

  .datasource-card,
  .create-card {
    min-height: 292px;
    border-radius: 16px;
  }

  .datasource-card {
    position: relative;
    padding: 18px;
    overflow: hidden;
    background: #fff;
    border: 1px solid #e3e8f2;
    box-shadow: 0 8px 24px rgba(42, 54, 92, 0.055);
    transition: 0.2s ease;

    &:hover {
      border-color: #cec8ff;
      box-shadow: 0 13px 28px rgba(79, 64, 190, 0.1);
      transform: translateY(-2px);
    }

    &.disabled {
      background: #fbfcfe;
    }
  }

  .card-accent {
    position: absolute;
    inset: 0 auto 0 0;
    width: 3px;
  }

  .card-header {
    display: flex;
    align-items: center;
    gap: 11px;
  }

  .database-badge {
    display: grid;
    flex: 0 0 auto;
    place-items: center;
    width: 42px;
    height: 42px;
    color: var(--badge-color);
    font-size: 13px;
    font-weight: 800;
    background: color-mix(in srgb, var(--badge-color) 11%, white);
    border: 1px solid color-mix(in srgb, var(--badge-color) 18%, white);
    border-radius: 12px;

    &.large {
      width: 54px;
      height: 54px;
      font-size: 16px;
    }
  }

  .card-identity {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;

    strong {
      overflow: hidden;
      color: #182238;
      font-size: 15px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    span {
      margin-top: 3px;
      color: #929fb1;
      font-size: 11px;
      text-transform: uppercase;
    }
  }

  .description {
    height: 36px;
    margin: 14px 0 11px;
    overflow: hidden;
    color: #68778d;
    font-size: 12px;
    line-height: 18px;
  }

  .connection-surface {
    padding: 11px 12px;
    background: #f6f8fc;
    border: 1px solid #edf0f6;
    border-radius: 11px;

    span {
      display: block;
      margin-bottom: 5px;
      color: #9aa6b7;
      font-size: 10px;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    code {
      display: block;
      overflow: hidden;
      color: #35415a;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 11px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .metadata-row {
    display: grid;
    grid-template-columns: 1fr 1fr 62px;
    gap: 10px;
    padding: 13px 1px;

    > div {
      min-width: 0;
    }

    span {
      display: block;
      margin-bottom: 3px;
      color: #9aa6b7;
      font-size: 10px;
    }

    strong {
      display: flex;
      align-items: center;
      gap: 4px;
      overflow: hidden;
      color: #49566d;
      font-size: 11px;
      text-overflow: ellipsis;
      white-space: nowrap;

      svg {
        width: 12px;
        color: #6d5dfc;
      }
    }
  }

  .card-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-top: 12px;
    border-top: 1px solid #edf0f6;
  }

  .test-button {
    color: #5948e7;
    font-weight: 650;
    background: #f4f2ff;
    border-color: #ddd8ff;
  }

  .secondary-actions {
    display: flex;
    gap: 4px;
  }

  .status-toggle {
    transition:
      color 0.2s ease,
      background-color 0.2s ease,
      border-color 0.2s ease,
      box-shadow 0.2s ease;

    &:hover {
      box-shadow: 0 4px 10px rgba(42, 54, 92, 0.12);
      transform: translateY(-1px);
    }

    &.status-toggle-enable {
      --el-button-text-color: #16835b;
      --el-button-bg-color: #ecfdf5;
      --el-button-border-color: #a7ebcb;
      --el-button-hover-text-color: #0f6b49;
      --el-button-hover-bg-color: #d8fae9;
      --el-button-hover-border-color: #73dbaa;
    }

    &.status-toggle-disable {
      --el-button-text-color: #b76b13;
      --el-button-bg-color: #fff8e8;
      --el-button-border-color: #f1d18b;
      --el-button-hover-text-color: #94530a;
      --el-button-hover-bg-color: #fff1c9;
      --el-button-hover-border-color: #e9bb58;
    }
  }

  .create-card {
    display: flex;
    align-items: center;
    justify-content: center;
    flex-direction: column;
    color: #7d89a0;
    background: linear-gradient(145deg, #fafaff, #f6f8fc);
    border: 1px dashed #c8c4f4;
    cursor: pointer;
    transition: 0.2s ease;

    > span {
      display: grid;
      place-items: center;
      width: 46px;
      height: 46px;
      margin-bottom: 12px;
      color: #fff;
      background: linear-gradient(135deg, #7c6cff, #5948e7);
      border-radius: 14px;
      box-shadow: 0 8px 18px rgba(103, 87, 238, 0.24);

      svg {
        width: 20px;
      }
    }

    strong {
      color: #3d4760;
      font-size: 14px;
    }
    small {
      margin-top: 5px;
      color: #9aa6b7;
    }

    &:hover {
      color: #5948e7;
      background: #f8f7ff;
      border-color: #897cff;
      transform: translateY(-2px);
    }
  }

  .dialog-intro {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 13px 15px;
    margin: -6px 0 18px;
    background: linear-gradient(100deg, #f4f2ff, #f7faff);
    border: 1px solid #e3dfff;
    border-radius: 12px;

    .dialog-icon {
      display: grid;
      place-items: center;
      width: 38px;
      height: 38px;
      color: #fff;
      background: #6d5dfc;
      border-radius: 11px;
      svg {
        width: 19px;
      }
    }

    > div:last-child {
      display: flex;
      flex-direction: column;
      strong {
        color: #26324a;
        font-size: 13px;
      }
      span {
        margin-top: 3px;
        color: #78869d;
        font-size: 11px;
      }
    }
  }

  .form-section {
    padding: 16px;
    margin-bottom: 13px;
    background: #fbfcfe;
    border: 1px solid #e7ebf3;
    border-radius: 13px;
  }

  .section-heading {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    margin-bottom: 14px;

    > span {
      display: grid;
      place-items: center;
      width: 27px;
      height: 27px;
      color: #6959ef;
      font-size: 10px;
      font-weight: 800;
      background: #eeebff;
      border-radius: 8px;
    }

    > div {
      display: flex;
      flex-direction: column;
      strong {
        color: #29354c;
        font-size: 13px;
      }
      small {
        margin-top: 2px;
        color: #929eb0;
        font-size: 10px;
      }
    }
  }

  .preset-grid {
    display: grid;
    grid-template-columns: repeat(6, 1fr);
    gap: 7px;
  }

  .engine-grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }

  .engine-button {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 13px;
    color: #65728a;
    text-align: left;
    background: #fff;
    border: 1px solid #dfe5ef;
    border-radius: 12px;
    cursor: pointer;
    transition: 0.18s ease;

    > span:last-child {
      display: flex;
      flex-direction: column;
    }

    strong {
      color: #26324a;
      font-size: 13px;
    }

    small {
      margin-top: 3px;
      color: #8b98ac;
      font-size: 10px;
    }

    .engine-icon {
      display: grid;
      flex: 0 0 auto;
      place-items: center;
      width: 38px;
      height: 38px;
      color: #2879d0;
      font-size: 10px;
      font-weight: 800;
      background: #eaf4ff;
      border-radius: 11px;
    }

    &.iceberg .engine-icon {
      color: #6959ef;
      background: #eeebff;
    }

    &.active {
      background: #f8f7ff;
      border-color: #8476f7;
      box-shadow: 0 6px 16px rgba(96, 78, 218, 0.11);
    }
  }

  .nested-presets {
    padding-top: 12px;
    margin-top: 12px;
    border-top: 1px solid #eceff5;
  }

  .preset-button {
    display: flex;
    align-items: center;
    justify-content: center;
    flex-direction: column;
    gap: 6px;
    min-height: 68px;
    color: #67748b;
    font-size: 10px;
    background: #fff;
    border: 1px solid #e1e6ef;
    border-radius: 10px;
    cursor: pointer;

    > span {
      display: grid;
      place-items: center;
      width: 28px;
      height: 28px;
      color: var(--preset-color);
      font-size: 9px;
      font-weight: 800;
      background: color-mix(in srgb, var(--preset-color) 11%, white);
      border-radius: 8px;
    }

    &.active {
      color: var(--preset-color);
      font-weight: 700;
      background: color-mix(in srgb, var(--preset-color) 5%, white);
      border-color: color-mix(in srgb, var(--preset-color) 52%, white);
      box-shadow: 0 5px 14px
        color-mix(in srgb, var(--preset-color) 12%, transparent);
    }
  }

  .form-grid,
  .pool-grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 0 12px;
  }

  .pool-grid {
    grid-template-columns: repeat(3, 1fr);
  }
  .full-row {
    grid-column: 1 / -1;
  }

  .iceberg-options {
    display: grid;
    grid-template-columns: 1fr auto 1fr auto;
    gap: 12px;
    align-items: center;
    padding: 12px;
    margin-bottom: 16px;
    background: #f3f5fa;
    border-radius: 10px;

    > div {
      display: flex;
      flex-direction: column;
    }

    strong {
      color: #344057;
      font-size: 11px;
    }

    small {
      margin-top: 2px;
      color: #8f9bae;
      font-size: 9px;
    }
  }

  .field-hint {
    display: block;
    margin-top: 4px;
    color: #98a4b6;
    font-size: 10px;
    line-height: 1.4;
  }

  .state-section {
    display: flex;
    align-items: center;
    justify-content: space-between;

    > div {
      display: flex;
      flex-direction: column;
      strong {
        color: #29354c;
        font-size: 13px;
      }
      small {
        margin-top: 3px;
        color: #929eb0;
        font-size: 10px;
      }
    }
  }

  .test-result {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 11px 13px;
    color: #187f53;
    background: #effaf5;
    border: 1px solid #cdeedf;
    border-radius: 11px;

    > svg {
      width: 20px;
    }
    > div {
      display: flex;
      flex-direction: column;
    }
    strong {
      font-size: 12px;
    }
    span {
      margin-top: 2px;
      color: #5b8d77;
      font-size: 10px;
    }

    &.failure {
      color: #c53d4d;
      background: #fff4f5;
      border-color: #ffd8dd;

      span {
        color: #a95f68;
      }
    }
  }

  .dialog-footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .draft-test-button {
    color: #5948e7;
    background: #f4f2ff;
    border-color: #dcd6ff;
  }

  .detail-hero {
    display: flex;
    align-items: center;
    gap: 13px;
    padding: 17px;
    margin-bottom: 18px;
    background: linear-gradient(120deg, #f4f2ff, #f8faff);
    border: 1px solid #e3dfff;
    border-radius: 14px;

    > div:nth-child(2) {
      flex: 1;
      h3 {
        color: #202c42;
        font-size: 17px;
      }
      span {
        color: #8290a5;
        font-size: 11px;
      }
    }
  }

  .detail-section {
    padding: 17px 0;
    border-bottom: 1px solid #ebeff5;
  }

  .detail-title {
    display: block;
    margin-bottom: 12px;
    color: #66758c;
    font-size: 11px;
    font-weight: 750;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  dl {
    margin: 0;
    > div {
      display: grid;
      grid-template-columns: 110px 1fr;
      gap: 12px;
      padding: 9px 0;
    }
    dt {
      color: #98a4b5;
      font-size: 11px;
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
      color: #344057;
      font-size: 12px;
    }
  }

  .pool-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 7px;
  }
  .empty-copy {
    color: #77859a;
    font-size: 12px;
    line-height: 1.7;
  }

  @media (max-width: 1220px) {
    .datasource-grid {
      grid-template-columns: repeat(2, minmax(280px, 1fr));
    }
    .summary-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (max-width: 820px) {
    .datasource-page {
      padding: 16px;
    }
    .page-hero,
    .workspace-heading {
      align-items: stretch;
      flex-direction: column;
    }
    .hero-actions {
      flex-wrap: wrap;
    }
    .filters {
      width: 100%;
      grid-template-columns: 1fr;
    }
    .datasource-grid,
    .summary-grid {
      grid-template-columns: 1fr;
    }
    .preset-grid {
      grid-template-columns: repeat(3, 1fr);
    }
    .engine-grid,
    .form-grid,
    .pool-grid {
      grid-template-columns: 1fr;
    }
    .iceberg-options {
      grid-template-columns: 1fr auto;
    }
  }
</style>
