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

<script setup lang="ts">
  import { computed, onMounted, reactive, ref, watch } from 'vue'
  import { useRoute, useRouter } from 'vue-router'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    activateManagedAuthentication,
    deactivateManagedAuthentication,
    deleteIdentityBinding,
    deleteIdentityProvider,
    getAdminAccess,
    getIdentitySubjects,
    saveIdentityBinding,
    saveIdentityProvider,
    testIdentityProvider,
    type AdminAccess,
    type IdentityBinding,
    type IdentityProvider,
    type IdentitySubject
  } from '@/api/access'
  import {
    getAdminPolicies,
    updateAdminPolicies,
    type AdminPolicies,
    type SessionProfile
  } from '@/api/policy'
  import { getAdminPermissions, type AdminPermissions } from '@/api/permissions'

  type Pair = { key: string; value: string }

  const route = useRoute()
  const router = useRouter()
  const loading = ref(false)
  const saving = ref(false)
  const loadError = ref('')
  const updatedAt = ref(0)
  const requestedTab = String(route.query.tab || 'sources')
  const activeTab = ref(
    requestedTab === 'roles' ? 'authorizations' : requestedTab
  )
  const access = ref<AdminAccess>({
    providers: [],
    bindings: [],
    authentication: {
      active: false,
      restartRequired: false,
      className: '',
      message: ''
    }
  })
  const policies = ref<AdminPolicies>({
    profiles: [],
    userDefaults: [],
    access: { unlimitedUsers: [], denyUsers: [], denyIps: [] }
  })
  const permissions = ref<AdminPermissions>({
    currentUser: '',
    roles: [],
    assignments: []
  })
  const providerDialog = ref(false)
  const providerOriginalId = ref('')
  const directoryDialog = ref(false)
  const bindingDialog = ref(false)
  const bindingDetailDrawer = ref(false)
  const selectedBinding = ref<IdentityBinding>()
  const activationDialog = ref(false)
  const profileDialog = ref(false)
  const selectedProviderId = ref('')
  const subjectQuery = ref('')
  const subjectLoading = ref(false)
  const subjects = ref<IdentitySubject[]>([])
  const authorizationQuery = ref('')
  const authorizationProvider = ref('')
  const authorizationRole = ref('')
  const authorizationStatus = ref('')
  const newDenyIp = ref('')
  const bindingDefaults = ref<Pair[]>([])
  const profileProperties = ref<Pair[]>([])
  const profileOriginalName = ref('')
  const testResults = reactive<
    Record<string, { ok: boolean; message: string }>
  >({})

  const emptyProvider = (): IdentityProvider => ({
    id: '',
    name: '',
    providerType: 'LDAP',
    enabled: true,
    endpoint: '',
    baseDn: '',
    bindDn: '',
    secretEnvironment: '',
    userFilter: '(objectClass=person)',
    groupFilter: '(objectClass=groupOfNames)',
    userNameAttribute: 'uid',
    displayNameAttribute: 'cn',
    emailAttribute: 'mail',
    groupNameAttribute: 'cn',
    userDnPattern: '',
    usersPath: '/users',
    groupsPath: '/groups',
    authenticationPath: '/authenticate',
    idField: 'id',
    userNameField: 'username',
    displayNameField: 'displayName',
    emailField: 'email',
    groupNameField: 'name',
    connectTimeoutMillis: 5000,
    readTimeoutMillis: 5000
  })
  const providerForm = reactive<IdentityProvider>(emptyProvider())
  const bindingForm = reactive<Partial<IdentityBinding>>({
    id: '',
    providerId: '',
    subjectId: '',
    subjectName: '',
    subjectType: 'USER',
    access: 'ENABLED',
    role: '',
    profile: '',
    quotaExempt: false,
    userDefaults: {}
  })
  const profileForm = reactive({ name: '' })

  const providers = computed(() => access.value.providers)
  const enabledProviders = computed(() =>
    providers.value.filter((item) => item.provider.enabled)
  )
  const bindings = computed(() => access.value.bindings)
  const activationAdministrators = computed(() => {
    const enabledProviderIds = new Set(
      enabledProviders.value.map((item) => item.provider.id)
    )
    return bindings.value.filter(
      (binding) =>
        enabledProviderIds.has(binding.providerId) &&
        binding.subjectType === 'USER' &&
        binding.access === 'ENABLED' &&
        binding.role === 'platform-admin'
    )
  })
  const boundCount = computed(() => bindings.value.length)
  const deniedCount = computed(
    () => bindings.value.filter((item) => item.access === 'DENIED').length
  )
  const filteredBindings = computed(() => {
    const query = authorizationQuery.value.trim().toLowerCase()
    return bindings.value.filter((binding) => {
      const matchesQuery =
        !query || binding.subjectName.toLowerCase().includes(query)
      const matchesProvider =
        !authorizationProvider.value ||
        binding.providerId === authorizationProvider.value
      const matchesRole =
        !authorizationRole.value ||
        (authorizationRole.value === '__normal__'
          ? !binding.role
          : binding.role === authorizationRole.value)
      const matchesStatus =
        !authorizationStatus.value ||
        binding.access === authorizationStatus.value
      return matchesQuery && matchesProvider && matchesRole && matchesStatus
    })
  })
  const enabledBindingCount = computed(
    () => bindings.value.filter((item) => item.access === 'ENABLED').length
  )
  const providerName = (id: string) =>
    providers.value.find((item) => item.provider.id === id)?.provider.name || id
  const bindingFor = (subject: IdentitySubject) =>
    bindings.value.find(
      (item) =>
        item.providerId === subject.providerId && item.subjectId === subject.id
    )
  const roleLabel = (role: string) =>
    permissions.value.roles.find((item) => item.name === role)?.label ||
    role ||
    '普通用户'
  const roleDescription = (role: string) => {
    if (!role)
      return '无平台管理权限，数据查询权限由计算引擎和数据权限系统决定。'
    if (role === 'viewer')
      return '可以查看平台管理信息，不能执行修改、关闭或删除操作。'
    if (role === 'platform-admin') return '拥有 Kyuubi 平台的全部管理权限。'
    return (
      permissions.value.roles.find((item) => item.name === role)?.description ||
      '使用该角色配置的平台管理权限。'
    )
  }
  const bindingEffect = (binding: IdentityBinding) => {
    if (binding.access === 'DENIED') return '已拒绝访问'
    if (access.value.authentication.active) return '允许登录'
    if (access.value.authentication.restartRequired) return '重启后按新配置生效'
    return '企业认证启用后生效'
  }

  watch(activeTab, (tab) => router.replace({ query: { ...route.query, tab } }))
  watch(selectedProviderId, () => {
    subjects.value = []
  })

  const loadAll = async () => {
    loading.value = true
    loadError.value = ''
    try {
      const [accessData, policyData, permissionData] = await Promise.all([
        getAdminAccess(),
        getAdminPolicies(),
        getAdminPermissions()
      ])
      access.value = accessData
      policies.value = policyData
      permissions.value = permissionData
      if (
        !selectedProviderId.value ||
        !accessData.providers.some(
          (item) => item.provider.id === selectedProviderId.value
        )
      ) {
        selectedProviderId.value = accessData.providers[0]?.provider.id || ''
      }
      updatedAt.value = Date.now()
    } catch (error) {
      loadError.value =
        error instanceof Error ? error.message : '加载访问管理数据失败'
    } finally {
      loading.value = false
    }
  }

  const openProvider = (provider?: IdentityProvider) => {
    providerOriginalId.value = provider?.id || ''
    Object.assign(providerForm, emptyProvider(), provider || {})
    providerDialog.value = true
  }

  const saveProvider = async () => {
    if (
      !providerForm.id.trim() ||
      !providerForm.name.trim() ||
      !providerForm.endpoint.trim()
    ) {
      ElMessage.warning('请填写标识、名称和服务地址')
      return
    }
    saving.value = true
    try {
      access.value = await saveIdentityProvider({ ...providerForm })
      selectedProviderId.value = providerForm.id
      providerDialog.value = false
      ElMessage.success('身份源已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '身份源保存失败')
    } finally {
      saving.value = false
    }
  }

  const testProvider = async (id: string) => {
    testResults[id] = { ok: false, message: '正在测试…' }
    try {
      const result = await testIdentityProvider(id)
      testResults[id] = {
        ok: true,
        message: `连接正常 · ${result.latencyMillis} ms`
      }
      ElMessage.success(`${providerName(id)} 连接正常`)
    } catch (error) {
      const message = error instanceof Error ? error.message : '连接失败'
      testResults[id] = {
        ok: false,
        message
      }
      ElMessage.error(`${providerName(id)} 连接失败：${message}`)
    }
  }

  const removeProvider = async (id: string) => {
    try {
      await ElMessageBox.confirm(
        '删除后将无法再从该身份源查询用户。已有绑定需先解除。',
        '删除身份源',
        { type: 'warning' }
      )
      access.value = await deleteIdentityProvider(id)
      ElMessage.success('身份源已删除')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close')
        ElMessage.error(error instanceof Error ? error.message : '删除失败')
    }
  }

  const querySubjects = async () => {
    if (!selectedProviderId.value) return
    subjectLoading.value = true
    try {
      subjects.value = (
        await getIdentitySubjects(
          selectedProviderId.value,
          'USER',
          subjectQuery.value
        )
      ).subjects
    } catch (error) {
      ElMessage.error(
        error instanceof Error ? error.message : '身份目录查询失败'
      )
    } finally {
      subjectLoading.value = false
    }
  }

  const objectToPairs = (value: Record<string, string> = {}) =>
    Object.entries(value).map(([key, item]) => ({ key, value: item }))
  const pairsToObject = (pairs: Pair[]) =>
    Object.fromEntries(
      pairs
        .filter((item) => item.key.trim())
        .map((item) => [item.key.trim(), item.value])
    )
  const openBinding = (subject: IdentitySubject, binding?: IdentityBinding) => {
    Object.assign(bindingForm, {
      id: binding?.id || '',
      providerId: subject.providerId,
      subjectId: subject.id,
      subjectName: subject.name,
      subjectType: 'USER',
      access: binding?.access || 'ENABLED',
      role: binding?.role || '',
      profile: binding?.profile || '',
      quotaExempt: binding?.quotaExempt || false,
      userDefaults: binding?.userDefaults || {}
    })
    bindingDefaults.value = objectToPairs(binding?.userDefaults)
    bindingDialog.value = true
  }

  const editBinding = (binding: IdentityBinding) =>
    openBinding(
      {
        providerId: binding.providerId,
        id: binding.subjectId,
        name: binding.subjectName,
        displayName: binding.subjectName,
        email: '',
        subjectType: 'USER',
        groups: [],
        members: []
      },
      binding
    )

  const openDirectory = async () => {
    if (!enabledProviders.value.length) {
      ElMessage.warning('请先配置并启用至少一个 LDAP 或 IAM 身份源')
      return
    }
    if (
      !selectedProviderId.value ||
      !enabledProviders.value.some(
        (item) => item.provider.id === selectedProviderId.value
      )
    ) {
      selectedProviderId.value = enabledProviders.value[0].provider.id
    }
    directoryDialog.value = true
    await querySubjects()
  }

  const viewBinding = (binding: IdentityBinding) => {
    selectedBinding.value = binding
    bindingDetailDrawer.value = true
  }

  const editSelectedBinding = () => {
    if (!selectedBinding.value) return
    bindingDetailDrawer.value = false
    editBinding(selectedBinding.value)
  }

  const resetAuthorizationFilters = () => {
    authorizationQuery.value = ''
    authorizationProvider.value = ''
    authorizationRole.value = ''
    authorizationStatus.value = ''
  }

  const saveBinding = async () => {
    saving.value = true
    try {
      bindingForm.userDefaults = pairsToObject(bindingDefaults.value)
      access.value = await saveIdentityBinding({ ...bindingForm })
      bindingDialog.value = false
      await Promise.all([reloadPolicies(), reloadPermissions()])
      ElMessage.success('访问授权已生效')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '授权保存失败')
    } finally {
      saving.value = false
    }
  }

  const removeBinding = async (binding: IdentityBinding) => {
    try {
      await ElMessageBox.confirm(
        `仅解除 ${binding.subjectName} 与 Kyuubi 的本地授权，不会删除外部账号。`,
        '解除授权',
        { type: 'warning' }
      )
      access.value = await deleteIdentityBinding(binding.id)
      await Promise.all([reloadPolicies(), reloadPermissions()])
      ElMessage.success('本地授权已解除')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close')
        ElMessage.error(error instanceof Error ? error.message : '解除失败')
    }
  }

  const reloadPolicies = async () => {
    policies.value = await getAdminPolicies()
  }
  const reloadPermissions = async () => {
    permissions.value = await getAdminPermissions()
  }
  const saveDenyIps = async () => {
    saving.value = true
    try {
      policies.value = await updateAdminPolicies({
        access: policies.value.access
      })
      ElMessage.success('IP 访问规则已生效')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存失败')
    } finally {
      saving.value = false
    }
  }
  const addDenyIp = () => {
    const value = newDenyIp.value.trim()
    if (value && !policies.value.access.denyIps.includes(value))
      policies.value.access.denyIps.push(value)
    newDenyIp.value = ''
  }

  const openActivation = () => {
    activationDialog.value = true
  }
  const activate = async () => {
    if (!enabledProviders.value.length) {
      ElMessage.warning('请先配置并启用至少一个 LDAP 或 IAM 身份源')
      return
    }
    if (!activationAdministrators.value.length) {
      ElMessage.warning(
        '请先在“授权详情”中绑定用户，并分配平台管理员角色后再启用认证'
      )
      return
    }
    saving.value = true
    try {
      access.value.authentication = await activateManagedAuthentication()
      activationDialog.value = false
      ElMessage.success('配置已保存，请重启 Kyuubi Server 生效')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '启用失败')
    } finally {
      saving.value = false
    }
  }

  const deactivate = async () => {
    try {
      await ElMessageBox.confirm(
        '停用后，Kyuubi 将恢复当前认证配置中的非托管认证方式。已配置的 LDAP/IAM 身份源、访问授权和会话模板不会被删除，重启 Server 后生效。',
        '停用企业身份认证',
        {
          type: 'warning',
          confirmButtonText: '确认停用',
          cancelButtonText: '取消'
        }
      )
      saving.value = true
      access.value.authentication = await deactivateManagedAuthentication()
      ElMessage.success('停用配置已保存，请重启 Kyuubi Server 生效')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close')
        ElMessage.error(error instanceof Error ? error.message : '停用失败')
    } finally {
      saving.value = false
    }
  }

  const openProfile = (profile?: SessionProfile) => {
    profileForm.name = profile?.name || ''
    profileOriginalName.value = profile?.name || ''
    profileProperties.value = objectToPairs(profile?.properties)
    if (!profileProperties.value.length)
      profileProperties.value.push({ key: '', value: '' })
    profileDialog.value = true
  }
  const saveProfile = async () => {
    if (!profileForm.name.trim()) return ElMessage.warning('请输入模板名称')
    saving.value = true
    try {
      policies.value = await updateAdminPolicies({
        profiles: [
          {
            name: profileForm.name.trim(),
            properties: pairsToObject(profileProperties.value)
          }
        ]
      })
      profileDialog.value = false
      ElMessage.success('会话模板已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '模板保存失败')
    } finally {
      saving.value = false
    }
  }
  const removeProfile = async (profile: SessionProfile) => {
    try {
      await ElMessageBox.confirm(
        `确定删除会话模板 ${profile.name}？`,
        '删除模板',
        { type: 'warning' }
      )
      policies.value = await updateAdminPolicies({
        profiles: [{ name: profile.name, properties: {}, delete: true }]
      })
      ElMessage.success('会话模板已删除')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close')
        ElMessage.error(error instanceof Error ? error.message : '删除失败')
    }
  }

  onMounted(() => {
    if (!['sources', 'authorizations', 'profiles'].includes(activeTab.value))
      activeTab.value = 'sources'
    if (requestedTab === 'roles')
      router.replace({ query: { ...route.query, tab: 'authorizations' } })
    loadAll()
  })

  defineExpose({
    access,
    policies,
    permissions,
    subjects,
    filteredBindings,
    directoryDialog,
    bindingDialog,
    bindingDetailDrawer,
    selectedBinding,
    authorizationQuery,
    authorizationProvider,
    authorizationRole,
    authorizationStatus,
    providerForm,
    providerOriginalId,
    bindingForm,
    bindingDefaults,
    activationAdministrators,
    loadAll,
    openProvider,
    saveProvider,
    querySubjects,
    openDirectory,
    openBinding,
    viewBinding,
    editSelectedBinding,
    resetAuthorizationFilters,
    saveBinding,
    removeBinding,
    saveDenyIps,
    openActivation,
    activate,
    deactivate,
    openProfile,
    saveProfile,
    bindingFor
  })
</script>

<template>
  <main class="access-page">
    <section class="access-hero">
      <div class="hero-copy">
        <span class="eyebrow">IDENTITY & ACCESS</span>
        <h1>访问管理</h1>
        <p
          >统一接入企业身份源，将外部账号映射为 Kyuubi
          的访问权限、角色与会话策略。</p
        >
      </div>
      <div class="hero-state">
        <span :class="['pulse', { active: access.authentication.active }]" />
        <div
          ><strong>{{
            access.authentication.active
              ? '企业身份认证已启用'
              : access.authentication.restartRequired
                ? '企业身份认证待重启生效'
                : '企业身份认证未启用'
          }}</strong
          ><small>{{
            access.authentication.active
              ? '仅允许已绑定且允许访问的用户登录 Kyuubi Server'
              : access.authentication.restartRequired
                ? '配置已保存，重启 Kyuubi Server 后切换认证方式'
                : '当前沿用 Kyuubi 原有认证方式，LDAP/IAM 仅用于目录与授权配置'
          }}</small></div
        >
        <el-button
          v-if="
            !access.authentication.active &&
            !access.authentication.restartRequired
          "
          type="primary"
          :disabled="!enabledProviders.length"
          @click="openActivation"
          >启用企业认证</el-button
        >
        <el-button
          v-else
          type="danger"
          plain
          :loading="saving"
          @click="deactivate"
          >停用企业认证</el-button
        >
      </div>
    </section>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      show-icon
      :closable="false" />

    <section class="metric-row">
      <article
        ><span class="metric-icon violet">⌘</span
        ><div
          ><small>身份源</small><strong>{{ providers.length }}</strong
          ><em>{{ enabledProviders.length }} 个已启用</em></div
        ></article
      >
      <article
        ><span class="metric-icon blue">人</span
        ><div
          ><small>已授权账号</small><strong>{{ boundCount }}</strong
          ><em>账号仍由外部系统维护</em></div
        ></article
      >
      <article
        ><span class="metric-icon amber">盾</span
        ><div
          ><small>已拒绝访问</small><strong>{{ deniedCount }}</strong
          ><em>实时同步到 Kyuubi</em></div
        ></article
      >
      <article
        ><span class="metric-icon green">层</span
        ><div
          ><small>会话模板</small><strong>{{ policies.profiles.length }}</strong
          ><em>可按账号自动应用</em></div
        ></article
      >
    </section>

    <section v-loading="loading" class="workspace">
      <div class="workspace-head">
        <el-tabs v-model="activeTab" class="access-tabs">
          <el-tab-pane label="认证接入" name="sources" />
          <el-tab-pane label="授权详情" name="authorizations" />
          <el-tab-pane label="会话模板" name="profiles" />
        </el-tabs>
        <div class="refresh-state"
          ><span v-if="updatedAt"
            >更新于 {{ new Date(updatedAt).toLocaleTimeString() }}</span
          ><el-button icon="Refresh" @click="loadAll">刷新</el-button></div
        >
      </div>

      <div v-if="activeTab === 'sources'" class="tab-content">
        <div class="section-heading"
          ><div
            ><span>AUTHENTICATION SOURCES</span><h2>企业身份源</h2
            ><p
              >接入 LDAP 目录或公司
              IAM。密码和令牌只从服务端环境变量读取，不会写入页面或配置文件。</p
            ></div
          ><el-button type="primary" icon="Plus" @click="openProvider()"
            >添加身份源</el-button
          ></div
        >
        <div v-if="providers.length" class="provider-grid">
          <article
            v-for="item in providers"
            :key="item.provider.id"
            class="provider-card">
            <div class="provider-top"
              ><span
                :class="[
                  'provider-logo',
                  item.provider.providerType.toLowerCase()
                ]"
                >{{ item.provider.providerType === 'LDAP' ? 'L' : 'I' }}</span
              ><div
                ><h3>{{ item.provider.name }}</h3
                ><p>{{ item.provider.id }}</p></div
              ><el-tag
                :type="item.provider.enabled ? 'success' : 'info'"
                effect="light"
                >{{ item.provider.enabled ? '已启用' : '已停用' }}</el-tag
              ></div
            >
            <div class="endpoint"
              ><span>服务地址</span
              ><code>{{ item.provider.endpoint }}</code></div
            >
            <div class="provider-meta"
              ><span>{{ item.provider.providerType }}</span
              ><span>{{
                item.provider.providerType === 'LDAP'
                  ? item.provider.baseDn
                  : item.provider.usersPath
              }}</span
              ><span :class="{ warning: !item.secretConfigured }">{{
                item.secretConfigured ? '凭据就绪' : '缺少环境变量'
              }}</span></div
            >
            <div
              v-if="testResults[item.provider.id]"
              :class="['test-result', { ok: testResults[item.provider.id].ok }]"
              >{{ testResults[item.provider.id].message }}</div
            >
            <div class="card-actions"
              ><el-button
                class="test-connection-button"
                icon="Connection"
                :loading="
                  testResults[item.provider.id]?.message === '正在测试…'
                "
                @click="testProvider(item.provider.id)"
                >测试连接</el-button
              ><el-button link @click="openProvider(item.provider)"
                >编辑</el-button
              ><el-button
                link
                type="danger"
                @click="removeProvider(item.provider.id)"
                >删除</el-button
              ></div
            >
          </article>
        </div>
        <div v-else class="empty-panel"
          ><span>⌘</span><h3>还没有身份源</h3
          ><p>添加 LDAP 或 IAM 后，即可查询企业账号并授予 Kyuubi 访问权限。</p
          ><el-button type="primary" @click="openProvider()"
            >添加第一个身份源</el-button
          ></div
        >
      </div>

      <div v-if="activeTab === 'authorizations'" class="tab-content">
        <div class="section-heading"
          ><div
            ><span>ACCESS DETAILS</span><h2>授权详情</h2
            ><p
              >集中查看和维护已经绑定到 Kyuubi
              的企业账号、访问状态、管理员角色与会话配置。</p
            ></div
          ><el-button
            class="add-authorization-button"
            type="primary"
            icon="Plus"
            @click="openDirectory"
            >添加授权</el-button
          ></div
        >

        <div
          :class="[
            'authorization-state',
            { active: access.authentication.active }
          ]">
          <span>{{ access.authentication.active ? '✓' : 'i' }}</span>
          <div>
            <strong>{{
              access.authentication.active
                ? '企业身份认证已启用，授权状态正在参与登录校验'
                : '企业身份认证未启用，授权配置已保存但暂不限制现有登录'
            }}</strong>
            <small>{{
              access.authentication.active
                ? '允许访问的绑定账号可以通过身份源认证；拒绝访问或未绑定账号将被拦截。'
                : '启用企业身份认证并重启 Kyuubi Server 后，仅已绑定且允许访问的账号可以登录。'
            }}</small>
          </div>
        </div>

        <div class="authorization-summary">
          <span
            ><strong>{{ bindings.length }}</strong
            >全部授权</span
          >
          <span class="allowed"
            ><strong>{{ enabledBindingCount }}</strong
            >允许访问</span
          >
          <span class="denied"
            ><strong>{{ deniedCount }}</strong
            >拒绝访问</span
          >
        </div>

        <div class="authorization-toolbar">
          <el-input
            v-model="authorizationQuery"
            clearable
            placeholder="搜索已授权账号"
            ><template #prefix>⌕</template></el-input
          >
          <el-select
            v-model="authorizationProvider"
            clearable
            placeholder="全部身份源"
            ><el-option
              v-for="item in providers"
              :key="item.provider.id"
              :label="item.provider.name"
              :value="item.provider.id"
          /></el-select>
          <el-select
            v-model="authorizationRole"
            clearable
            placeholder="全部角色">
            <el-option label="普通用户" value="__normal__" />
            <el-option
              v-for="role in permissions.roles"
              :key="role.name"
              :label="role.label"
              :value="role.name" />
          </el-select>
          <el-select
            v-model="authorizationStatus"
            clearable
            placeholder="全部状态">
            <el-option label="允许访问" value="ENABLED" />
            <el-option label="拒绝访问" value="DENIED" />
          </el-select>
          <el-button @click="resetAuthorizationFilters">重置</el-button>
        </div>

        <el-table
          :data="filteredBindings"
          class="authorization-table"
          empty-text="暂无符合条件的授权账号">
          <el-table-column label="账号" min-width="190"
            ><template #default="scope"
              ><div class="identity-cell"
                ><span>{{
                  scope.row.subjectName.slice(0, 1).toUpperCase()
                }}</span
                ><div
                  ><strong>{{ scope.row.subjectName }}</strong
                  ><small>{{ scope.row.subjectId }}</small></div
                ></div
              ></template
            ></el-table-column
          >
          <el-table-column label="身份源" min-width="140"
            ><template #default="scope">{{
              providerName(scope.row.providerId)
            }}</template></el-table-column
          >
          <el-table-column label="访问状态" width="120"
            ><template #default="scope"
              ><el-tag
                size="small"
                :type="scope.row.access === 'DENIED' ? 'danger' : 'success'"
                >{{
                  scope.row.access === 'DENIED' ? '拒绝访问' : '允许访问'
                }}</el-tag
              ></template
            ></el-table-column
          >
          <el-table-column label="管理员角色" min-width="130"
            ><template #default="scope"
              ><span :class="['role-badge', { ordinary: !scope.row.role }]">{{
                roleLabel(scope.row.role)
              }}</span></template
            ></el-table-column
          >
          <el-table-column label="会话模板" min-width="130"
            ><template #default="scope">{{
              scope.row.profile || '未绑定'
            }}</template></el-table-column
          >
          <el-table-column label="当前效果" min-width="160"
            ><template #default="scope"
              ><span
                :class="[
                  'effect-state',
                  { active: access.authentication.active }
                ]"
                >{{ bindingEffect(scope.row) }}</span
              ></template
            ></el-table-column
          >
          <el-table-column label="操作" width="230" fixed="right"
            ><template #default="scope"
              ><el-button link type="primary" @click="viewBinding(scope.row)"
                >查看详情</el-button
              ><el-button link type="primary" @click="editBinding(scope.row)"
                >编辑授权</el-button
              ><el-button link type="danger" @click="removeBinding(scope.row)"
                >解除</el-button
              ></template
            ></el-table-column
          >
        </el-table>

        <section class="role-guide">
          <div class="role-guide-head">
            <div
              ><h3>管理员角色说明</h3
              ><p
                >角色只控制管理页面和管理接口，不决定数据库、表或列的数据权限。</p
              ></div
            >
          </div>
          <div class="role-guide-grid">
            <article>
              <span>普</span
              ><div
                ><strong>普通用户</strong><p>{{ roleDescription('') }}</p></div
              >
            </article>
            <article v-for="role in permissions.roles" :key="role.name">
              <span>{{ role.label.slice(0, 1) }}</span>
              <div
                ><strong>{{ role.label }}</strong
                ><p>{{ roleDescription(role.name) }}</p></div
              >
            </article>
          </div>
        </section>

        <div class="authorization-secondary">
          <section class="sub-panel ip-panel"
            ><div class="sub-head"
              ><div
                ><h3>IP 拒绝名单</h3
                ><p>阻止指定来源 IP 建立 Kyuubi 会话</p></div
              ><el-button
                type="primary"
                plain
                :loading="saving"
                @click="saveDenyIps"
                >保存并生效</el-button
              ></div
            ><div class="tag-editor"
              ><el-input
                v-model="newDenyIp"
                placeholder="例如 10.20.30.40"
                @keyup.enter="addDenyIp" /><el-button @click="addDenyIp"
                >添加</el-button
              ></div
            ><div class="ip-tags"
              ><el-tag
                v-for="ip in policies.access.denyIps"
                :key="ip"
                closable
                type="danger"
                effect="light"
                @close="
                  policies.access.denyIps = policies.access.denyIps.filter(
                    (item) => item !== ip
                  )
                "
                >{{ ip }}</el-tag
              ><span v-if="!policies.access.denyIps.length" class="muted"
                >未限制任何 IP</span
              ></div
            ></section
          >
        </div>
      </div>

      <div v-if="activeTab === 'profiles'" class="tab-content">
        <div class="section-heading"
          ><div
            ><span>SESSION PROFILES</span><h2>会话模板</h2
            ><p
              >集中维护 Session Profile，并在授权详情中按账号自动设置
              <code>kyuubi.session.conf.profile</code>。</p
            ></div
          ><el-button type="primary" icon="Plus" @click="openProfile()"
            >新建模板</el-button
          ></div
        >
        <div class="profile-explainer">
          <span class="profile-explainer-icon">S</span>
          <div class="profile-explainer-copy">
            <strong>Session Profile 是一组可复用的会话默认配置</strong>
            <p>
              将模板绑定给 LDAP / IAM 账号后，用户每次建立 Kyuubi Session
              时都会自动加载对应的 Spark、Flink 或 Trino
              参数，无需客户端重复填写。它只管理会话配置，不负责账号认证和角色权限。
            </p>
          </div>
          <div class="profile-flow">
            <span>定义参数</span><i>→</i><span>绑定账号</span><i>→</i
            ><span>建连时自动应用</span>
          </div>
        </div>
        <el-table
          :data="policies.profiles"
          class="profile-table"
          empty-text="暂无会话模板"
          ><el-table-column label="模板" min-width="220"
            ><template #default="scope"
              ><div class="profile-name"
                ><span>层</span
                ><div
                  ><strong>{{ scope.row.name }}</strong
                  ><small>{{ scope.row.fileName }}</small></div
                ></div
              ></template
            ></el-table-column
          ><el-table-column
            prop="propertyCount"
            label="配置项"
            width="110" /><el-table-column label="最近修改" width="210"
            ><template #default="scope">{{
              new Date(scope.row.modifiedTime).toLocaleString()
            }}</template></el-table-column
          ><el-table-column label="配置预览" min-width="300"
            ><template #default="scope"
              ><el-tag
                v-for="(_, key) in scope.row.properties"
                :key="key"
                size="small"
                effect="plain"
                >{{ key }}</el-tag
              ></template
            ></el-table-column
          ><el-table-column label="操作" width="150"
            ><template #default="scope"
              ><el-button link type="primary" @click="openProfile(scope.row)"
                >编辑</el-button
              ><el-button link type="danger" @click="removeProfile(scope.row)"
                >删除</el-button
              ></template
            ></el-table-column
          ></el-table
        >
      </div>
    </section>

    <el-dialog
      v-model="providerDialog"
      :title="providerOriginalId ? '配置身份源' : '添加身份源'"
      width="680px"
      class="access-dialog">
      <el-form label-position="top"
        ><div class="form-grid"
          ><el-form-item label="类型"
            ><el-segmented
              v-model="providerForm.providerType"
              :options="['LDAP', 'IAM']" /></el-form-item
          ><el-form-item label="启用"
            ><el-switch v-model="providerForm.enabled" /></el-form-item
          ><el-form-item label="唯一标识"
            ><el-input
              v-model="providerForm.id"
              :disabled="!!providerOriginalId"
              placeholder="corp-ldap" /></el-form-item
          ><el-form-item label="显示名称"
            ><el-input
              v-model="providerForm.name"
              placeholder="公司 LDAP" /></el-form-item
          ><el-form-item class="full" label="服务地址"
            ><el-input
              v-model="providerForm.endpoint"
              :placeholder="
                providerForm.providerType === 'LDAP'
                  ? 'ldaps://ldap.company.com:636'
                  : 'https://iam.company.com/api'
              " /></el-form-item
          ><el-form-item
            v-if="providerForm.providerType === 'LDAP'"
            class="full"
            label="Base DN"
            ><el-input
              v-model="providerForm.baseDn"
              placeholder="dc=company,dc=com" /></el-form-item
          ><el-form-item
            v-if="providerForm.providerType === 'LDAP'"
            label="Bind DN（可选）"
            ><el-input v-model="providerForm.bindDn" /></el-form-item
          ><el-form-item label="密钥环境变量（可选）"
            ><el-input
              v-model="providerForm.secretEnvironment"
              placeholder="KYUUBI_LDAP_PASSWORD" /></el-form-item
          ><template v-if="providerForm.providerType === 'LDAP'"
            ><el-form-item label="用户过滤器"
              ><el-input v-model="providerForm.userFilter" /></el-form-item
            ><el-form-item label="用户 DN 模板"
              ><el-input
                v-model="providerForm.userDnPattern"
                placeholder="uid={0},ou=users,dc=company,dc=com" /></el-form-item
            ><el-form-item label="用户名属性"
              ><el-input
                v-model="providerForm.userNameAttribute" /></el-form-item
            ><el-form-item label="显示名属性"
              ><el-input
                v-model="providerForm.displayNameAttribute" /></el-form-item
            ><el-form-item label="邮箱属性"
              ><el-input v-model="providerForm.emailAttribute" /></el-form-item
            ><el-form-item label="组名属性"
              ><el-input
                v-model="
                  providerForm.groupNameAttribute
                " /></el-form-item></template
          ><template v-else
            ><el-form-item label="用户接口"
              ><el-input v-model="providerForm.usersPath" /></el-form-item
            ><el-form-item label="用户组接口"
              ><el-input v-model="providerForm.groupsPath" /></el-form-item
            ><el-form-item label="认证接口"
              ><el-input
                v-model="providerForm.authenticationPath" /></el-form-item
            ><el-form-item label="用户名字段"
              ><el-input
                v-model="
                  providerForm.userNameField
                " /></el-form-item></template></div
      ></el-form>
      <template #footer
        ><el-button @click="providerDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="saveProvider"
          >保存身份源</el-button
        ></template
      >
    </el-dialog>

    <el-dialog
      v-model="directoryDialog"
      title="添加账号授权"
      width="920px"
      class="access-dialog directory-dialog">
      <el-alert
        title="账号来自企业身份源。选择账号后，仅在 Kyuubi 中保存访问状态、管理员角色和会话配置。"
        type="info"
        show-icon
        :closable="false" />
      <div class="directory-toolbar dialog-toolbar">
        <el-select
          v-model="selectedProviderId"
          placeholder="选择身份源"
          style="width: 230px">
          <el-option
            v-for="item in enabledProviders"
            :key="item.provider.id"
            :label="item.provider.name"
            :value="item.provider.id" />
        </el-select>
        <el-input
          v-model="subjectQuery"
          clearable
          placeholder="搜索账号、姓名或邮箱"
          @keyup.enter="querySubjects">
          <template #prefix>⌕</template>
        </el-input>
        <el-button
          type="primary"
          :loading="subjectLoading"
          :disabled="!selectedProviderId"
          @click="querySubjects">
          查询目录
        </el-button>
      </div>
      <el-table
        v-loading="subjectLoading"
        :data="subjects"
        class="directory-table"
        max-height="430"
        empty-text="没有查询到账号">
        <el-table-column label="企业账号" min-width="220">
          <template #default="scope">
            <div class="identity-cell">
              <span>{{ scope.row.name.slice(0, 1).toUpperCase() }}</span>
              <div>
                <strong>{{ scope.row.displayName || scope.row.name }}</strong>
                <small>{{ scope.row.name }}</small>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="220" />
        <el-table-column label="所属用户组" min-width="180">
          <template #default="scope">
            <span class="muted">{{ scope.row.groups.join(', ') || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="授权状态" width="130">
          <template #default="scope">
            <el-tag
              v-if="bindingFor(scope.row)"
              size="small"
              :type="
                bindingFor(scope.row)?.access === 'DENIED'
                  ? 'danger'
                  : 'success'
              ">
              {{
                bindingFor(scope.row)?.access === 'DENIED'
                  ? '拒绝访问'
                  : '已授权'
              }}
            </el-tag>
            <span v-else class="muted">未授权</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="scope">
            <el-button
              class="grant-access-button"
              @click="openBinding(scope.row, bindingFor(scope.row))">
              {{ bindingFor(scope.row) ? '编辑授权' : '授予访问' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="directoryDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="bindingDialog"
      title="Kyuubi 访问授权"
      width="640px"
      class="access-dialog"
      ><div class="selected-identity"
        ><span>人</span
        ><div
          ><strong>{{ bindingForm.subjectName }}</strong
          ><small>{{ providerName(bindingForm.providerId || '') }}</small></div
        ></div
      ><el-form label-position="top"
        ><div class="form-grid"
          ><el-form-item label="访问状态"
            ><el-radio-group v-model="bindingForm.access"
              ><el-radio-button value="ENABLED">允许访问</el-radio-button
              ><el-radio-button value="DENIED"
                >拒绝访问</el-radio-button
              ></el-radio-group
            ></el-form-item
          ><el-form-item label="管理员角色"
            ><el-select
              v-model="bindingForm.role"
              clearable
              placeholder="普通用户（无管理权限）"
              ><el-option
                v-for="role in permissions.roles"
                :key="role.name"
                :label="role.label"
                :value="role.name" /></el-select
            ><p class="field-help">{{
              roleDescription(bindingForm.role || '')
            }}</p></el-form-item
          ><el-form-item label="会话模板"
            ><el-select
              v-model="bindingForm.profile"
              clearable
              placeholder="不自动应用"
              ><el-option
                v-for="profile in policies.profiles"
                :key="profile.name"
                :label="profile.name"
                :value="profile.name" /></el-select></el-form-item
          ><el-form-item label="连接配额"
            ><el-switch
              v-model="bindingForm.quotaExempt"
              active-text="不受连接数限制" /></el-form-item></div
        ><div class="pair-heading"
          ><div
            ><strong>用户默认配置</strong
            ><small>连接建立时自动注入，可覆盖模板中的默认值</small></div
          ><el-button
            class="add-property-button"
            icon="Plus"
            @click="bindingDefaults.push({ key: '', value: '' })"
            >新增配置项</el-button
          ></div
        ><div
          v-for="(pair, index) in bindingDefaults"
          :key="index"
          class="pair-row"
          ><el-input
            v-model="pair.key"
            placeholder="spark.sql.shuffle.partitions" /><el-input
            v-model="pair.value"
            placeholder="配置值" /><el-button
            icon="Delete"
            circle
            @click="bindingDefaults.splice(index, 1)" /></div></el-form
      ><template #footer
        ><el-button @click="bindingDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="saveBinding"
          >保存并立即生效</el-button
        ></template
      ></el-dialog
    >

    <el-drawer
      v-model="bindingDetailDrawer"
      title="账号授权详情"
      size="480px"
      class="binding-detail-drawer">
      <div v-if="selectedBinding" class="binding-detail">
        <div class="detail-identity">
          <span>{{
            selectedBinding.subjectName.slice(0, 1).toUpperCase()
          }}</span>
          <div>
            <strong>{{ selectedBinding.subjectName }}</strong>
            <small>{{ providerName(selectedBinding.providerId) }}</small>
          </div>
          <el-tag
            :type="selectedBinding.access === 'DENIED' ? 'danger' : 'success'">
            {{ selectedBinding.access === 'DENIED' ? '拒绝访问' : '允许访问' }}
          </el-tag>
        </div>
        <section class="detail-section">
          <h3>账号与授权</h3>
          <dl>
            <div
              ><dt>外部账号标识</dt
              ><dd>{{ selectedBinding.subjectId }}</dd></div
            >
            <div
              ><dt>身份源</dt
              ><dd>{{ providerName(selectedBinding.providerId) }}</dd></div
            >
            <div
              ><dt>管理员角色</dt
              ><dd>{{ roleLabel(selectedBinding.role) }}</dd></div
            >
            <div
              ><dt>当前效果</dt
              ><dd>{{ bindingEffect(selectedBinding) }}</dd></div
            >
          </dl>
          <p class="detail-note">{{ roleDescription(selectedBinding.role) }}</p>
        </section>
        <section class="detail-section">
          <h3>会话配置</h3>
          <dl>
            <div
              ><dt>会话模板</dt
              ><dd>{{ selectedBinding.profile || '未绑定' }}</dd></div
            >
            <div
              ><dt>连接配额</dt
              ><dd>{{
                selectedBinding.quotaExempt ? '不受连接数限制' : '遵循系统限制'
              }}</dd></div
            >
          </dl>
          <div
            v-if="Object.keys(selectedBinding.userDefaults).length"
            class="default-configs">
            <div
              v-for="(value, key) in selectedBinding.userDefaults"
              :key="key">
              <code>{{ key }}</code
              ><span>{{ value }}</span>
            </div>
          </div>
          <p v-else class="muted">未配置账号级会话默认参数</p>
        </section>
      </div>
      <template #footer>
        <el-button @click="bindingDetailDrawer = false">关闭</el-button>
        <el-button type="primary" @click="editSelectedBinding"
          >编辑授权</el-button
        >
      </template>
    </el-drawer>

    <el-dialog
      v-model="activationDialog"
      title="启用企业身份认证"
      width="520px"
      class="access-dialog"
      ><el-alert
        title="启用操作不会要求或保存任何 LDAP/IAM 用户密码。重启 Server 后，用户首次登录时再由身份源验证账号密码。"
        type="info"
        show-icon
        :closable="false" />
      <div class="activation-checks">
        <section
          :class="['activation-check', { ready: enabledProviders.length }]">
          <span>{{ enabledProviders.length ? '✓' : '!' }}</span>
          <div
            ><strong>已启用身份源</strong
            ><small>{{
              enabledProviders.length
                ? `${enabledProviders.length} 个 LDAP/IAM 身份源可用`
                : '请先配置并启用至少一个身份源'
            }}</small></div
          >
        </section>
        <section
          :class="[
            'activation-check',
            { ready: activationAdministrators.length }
          ]">
          <span>{{ activationAdministrators.length ? '✓' : '!' }}</span>
          <div
            ><strong>平台管理员</strong
            ><small>{{
              activationAdministrators.length
                ? `${activationAdministrators.length} 个账号将在启用后保留管理权限`
                : '请先绑定并启用至少一个平台管理员'
            }}</small></div
          >
        </section>
      </div>
      <div v-if="activationAdministrators.length" class="activation-admins">
        <div class="activation-admins-head"
          ><strong>可登录的平台管理员</strong
          ><small>系统根据授权列表自动识别，无需手动选择</small></div
        >
        <div class="activation-admin-list">
          <span v-for="binding in activationAdministrators" :key="binding.id"
            ><b>{{ binding.subjectName }}</b
            ><small>{{ providerName(binding.providerId) }}</small></span
          >
        </div>
      </div>
      <el-alert
        v-else
        title="无法启用企业身份认证"
        description="“授权详情”中没有已绑定、允许访问且角色为平台管理员的用户。"
        type="warning"
        show-icon
        :closable="false" />
      <template #footer
        ><el-button @click="activationDialog = false">取消</el-button
        ><el-button
          type="primary"
          :disabled="
            !enabledProviders.length || !activationAdministrators.length
          "
          :loading="saving"
          @click="activate"
          >确认启用</el-button
        ></template
      ></el-dialog
    >

    <el-dialog
      v-model="profileDialog"
      :title="profileOriginalName ? '编辑会话模板' : '新建会话模板'"
      width="680px"
      class="access-dialog"
      ><el-form label-position="top"
        ><el-form-item label="模板名称"
          ><el-input
            v-model="profileForm.name"
            :disabled="!!profileOriginalName"
            placeholder="analyst" /></el-form-item
        ><div class="pair-heading"
          ><div
            ><strong>模板配置</strong
            ><small>支持 Spark、Flink、Trino 等引擎会话配置</small></div
          ><el-button
            class="add-property-button"
            icon="Plus"
            @click="profileProperties.push({ key: '', value: '' })"
            >新增配置项</el-button
          ></div
        ><div
          v-for="(pair, index) in profileProperties"
          :key="index"
          class="pair-row"
          ><el-input
            v-model="pair.key"
            placeholder="spark.sql.adaptive.enabled" /><el-input
            v-model="pair.value"
            placeholder="true" /><el-button
            icon="Delete"
            circle
            @click="profileProperties.splice(index, 1)" /></div></el-form
      ><template #footer
        ><el-button @click="profileDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="saveProfile"
          >保存模板</el-button
        ></template
      ></el-dialog
    >
  </main>
</template>

<style scoped lang="scss">
  .access-page {
    min-height: 100%;
    padding: 24px;
    color: #172033;
    background:
      radial-gradient(
        circle at 90% -10%,
        rgba(112, 82, 255, 0.11),
        transparent 32%
      ),
      #f5f7fb;
  }
  .access-hero {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 32px;
    padding: 30px 34px;
    border: 1px solid rgba(103, 80, 214, 0.12);
    border-radius: 22px;
    background: linear-gradient(120deg, #fff 0%, #faf9ff 55%, #f2f6ff 100%);
    box-shadow: 0 16px 45px rgba(34, 43, 80, 0.08);
  }
  .hero-copy .eyebrow,
  .section-heading span {
    color: #7357e8;
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.12em;
  }
  h1 {
    margin: 7px 0 8px;
    font-size: 28px;
    letter-spacing: -0.03em;
  }
  .hero-copy p,
  .section-heading p {
    margin: 0;
    color: #667085;
  }
  .hero-state {
    min-width: 410px;
    display: flex;
    align-items: center;
    gap: 13px;
    padding: 15px 16px;
    border: 1px solid #e7e9f3;
    border-radius: 15px;
    background: rgba(255, 255, 255, 0.8);
  }
  .hero-state div {
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: 3px;
  }
  .hero-state small {
    color: #8790a4;
    max-width: 240px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .pulse {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: #f0a126;
    box-shadow: 0 0 0 5px rgba(240, 161, 38, 0.13);
  }
  .pulse.active {
    background: #22b573;
    box-shadow: 0 0 0 5px rgba(34, 181, 115, 0.13);
  }
  .metric-row {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 16px;
    margin: 18px 0;
  }
  .metric-row article {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 18px;
    border: 1px solid #e9ebf2;
    border-radius: 16px;
    background: #fff;
    box-shadow: 0 8px 24px rgba(31, 41, 72, 0.045);
  }
  .metric-row div {
    display: grid;
    grid-template-columns: auto auto;
    column-gap: 10px;
    align-items: baseline;
  }
  .metric-row small {
    color: #6d7588;
    font-weight: 650;
  }
  .metric-row strong {
    grid-row: span 2;
    font-size: 27px;
  }
  .metric-row em {
    color: #9aa1b1;
    font-size: 11px;
    font-style: normal;
  }
  .metric-icon {
    display: grid;
    place-items: center;
    width: 42px;
    height: 42px;
    border-radius: 13px;
    font-weight: 800;
  }
  .metric-icon.violet {
    color: #6c4ff8;
    background: #eeeaff;
  }
  .metric-icon.blue {
    color: #2d73e8;
    background: #eaf3ff;
  }
  .metric-icon.amber {
    color: #d68612;
    background: #fff3dc;
  }
  .metric-icon.green {
    color: #159a69;
    background: #e5f8f0;
  }
  .workspace {
    min-height: 560px;
    border: 1px solid #e7e9f1;
    border-radius: 19px;
    background: #fff;
    box-shadow: 0 14px 40px rgba(29, 37, 65, 0.06);
    overflow: hidden;
  }
  .workspace-head {
    height: 66px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 24px;
    border-bottom: 1px solid #eceef5;
  }
  .access-tabs {
    width: 520px;
  }
  :deep(.access-tabs .el-tabs__header) {
    margin: 0;
  }
  :deep(.access-tabs .el-tabs__nav-wrap::after) {
    display: none;
  }
  :deep(.access-tabs .el-tabs__item) {
    height: 65px;
    font-weight: 650;
  }
  .refresh-state {
    display: flex;
    align-items: center;
    gap: 14px;
    color: #98a0b1;
    font-size: 12px;
  }
  .tab-content {
    padding: 26px;
  }
  .section-heading {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    margin-bottom: 22px;
  }
  .section-heading h2 {
    margin: 6px 0;
    font-size: 20px;
  }
  .section-heading p {
    max-width: 760px;
    line-height: 1.6;
  }
  .provider-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(280px, 1fr));
    gap: 16px;
  }
  .provider-card {
    padding: 19px;
    border: 1px solid #e5e8f1;
    border-radius: 16px;
    transition: 0.2s ease;
  }
  .provider-card:hover {
    transform: translateY(-2px);
    border-color: #cfc7fa;
    box-shadow: 0 12px 28px rgba(58, 47, 120, 0.09);
  }
  .provider-top {
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .provider-top div {
    flex: 1;
  }
  .provider-top h3 {
    margin: 0 0 3px;
    font-size: 15px;
  }
  .provider-top p {
    margin: 0;
    color: #9299aa;
    font-size: 12px;
  }
  .provider-logo {
    display: grid;
    place-items: center;
    width: 42px;
    height: 42px;
    border-radius: 13px;
    color: #fff;
    font-weight: 800;
  }
  .provider-logo.ldap {
    background: linear-gradient(135deg, #7458ff, #9d79ff);
  }
  .provider-logo.iam {
    background: linear-gradient(135deg, #267be9, #41a5f5);
  }
  .endpoint {
    margin: 17px 0 13px;
    padding: 11px 12px;
    background: #f7f8fb;
    border-radius: 10px;
  }
  .endpoint span {
    display: block;
    margin-bottom: 5px;
    color: #99a1b2;
    font-size: 10px;
  }
  .endpoint code {
    display: block;
    overflow: hidden;
    color: #36405a;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .provider-meta {
    display: flex;
    gap: 7px;
    flex-wrap: wrap;
  }
  .provider-meta span {
    padding: 4px 8px;
    border-radius: 6px;
    color: #667085;
    background: #f1f3f7;
    font-size: 10px;
    max-width: 150px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .provider-meta .warning {
    color: #c56f11;
    background: #fff1d8;
  }
  .card-actions {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 2px;
    margin-top: 15px;
    padding-top: 12px;
    border-top: 1px solid #eff0f5;
  }
  :deep(.test-connection-button.el-button) {
    height: 30px;
    padding: 0 9px;
    border: none;
    border-radius: 7px;
    color: #6854c4;
    background: transparent;
    box-shadow: none;
    font-size: 12px;
    font-weight: 600;
    transition: 0.2s ease;
  }
  :deep(.test-connection-button.el-button:hover),
  :deep(.test-connection-button.el-button:focus) {
    color: #6854c4;
    background: #f8f6ff;
    box-shadow: none;
  }
  .test-result {
    margin-top: 10px;
    color: #c34052;
    font-size: 11px;
  }
  .test-result.ok {
    color: #168a61;
  }
  :deep(.grant-access-button.el-button) {
    height: 30px;
    padding: 0 12px;
    border: 1px solid #d9d0ff;
    border-radius: 8px;
    color: #5b45d6;
    background: #f5f2ff;
    box-shadow: 0 2px 6px rgba(91, 69, 214, 0.08);
    font-size: 12px;
    font-weight: 600;
    transition: 0.2s ease;
  }
  :deep(.grant-access-button.el-button:hover),
  :deep(.grant-access-button.el-button:focus) {
    border-color: #b9a9ff;
    color: #4934bd;
    background: #ebe5ff;
    box-shadow: 0 4px 10px rgba(91, 69, 214, 0.14);
  }
  .empty-panel {
    display: grid;
    place-items: center;
    padding: 65px;
    border: 1px dashed #d8dbea;
    border-radius: 16px;
    background: #fafbfe;
  }
  .empty-panel > span {
    color: #7558eb;
    font-size: 34px;
  }
  .empty-panel h3 {
    margin: 10px 0 4px;
  }
  .empty-panel p {
    margin: 0 0 18px;
    color: #8b93a5;
  }
  .directory-toolbar {
    display: flex;
    gap: 10px;
    padding: 14px;
    border: 1px solid #e8eaf2;
    border-radius: 13px;
    background: #f8f9fc;
  }
  .directory-toolbar .el-input {
    flex: 1;
  }
  .directory-table {
    margin-top: 14px;
    border: 1px solid #eef0f5;
    border-radius: 12px;
    overflow: hidden;
  }
  .identity-cell,
  .profile-name,
  .selected-identity {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .identity-cell > span,
  .profile-name > span,
  .selected-identity > span {
    display: grid;
    place-items: center;
    width: 32px;
    height: 32px;
    border-radius: 9px;
    color: #6c53db;
    background: #eeeaff;
    font-size: 12px;
  }
  .identity-cell div,
  .profile-name div,
  .selected-identity div {
    display: flex;
    flex-direction: column;
  }
  .identity-cell small,
  .profile-name small,
  .selected-identity small {
    color: #9aa1b0;
  }
  .muted {
    color: #9299aa;
    font-size: 12px;
  }
  .sub-panel {
    padding: 18px;
    border: 1px solid #e8eaf1;
    border-radius: 14px;
  }
  .sub-head {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    margin-bottom: 14px;
  }
  .sub-head h3 {
    margin: 0 0 4px;
    font-size: 15px;
  }
  .sub-head p {
    margin: 0;
    color: #9299aa;
    font-size: 12px;
  }
  .tag-editor {
    display: flex;
    gap: 8px;
  }
  .ip-tags {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    margin-top: 14px;
  }
  .authorization-state {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 16px;
    padding: 14px 16px;
    border: 1px solid #eadfbf;
    border-radius: 13px;
    background: linear-gradient(110deg, #fffaf0, #fffdf8);
  }
  .authorization-state > span {
    display: grid;
    flex: 0 0 30px;
    width: 30px;
    height: 30px;
    place-items: center;
    border-radius: 9px;
    color: #a9680e;
    background: #ffedc8;
    font-weight: 800;
  }
  .authorization-state > div {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }
  .authorization-state strong {
    color: #594a2c;
    font-size: 13px;
  }
  .authorization-state small {
    color: #8a7b60;
    line-height: 1.5;
  }
  .authorization-state.active {
    border-color: #cfe9dc;
    background: linear-gradient(110deg, #f2fbf6, #fbfefc);
  }
  .authorization-state.active > span {
    color: #11744e;
    background: #d9f3e6;
  }
  .authorization-state.active strong {
    color: #285b47;
  }
  .authorization-state.active small {
    color: #688579;
  }
  .authorization-summary {
    display: flex;
    align-items: center;
    gap: 24px;
    margin-bottom: 12px;
    color: #7b8497;
    font-size: 12px;
  }
  .authorization-summary span {
    display: inline-flex;
    align-items: baseline;
    gap: 6px;
  }
  .authorization-summary strong {
    color: #2c3650;
    font-size: 18px;
  }
  .authorization-summary .allowed strong {
    color: #16835b;
  }
  .authorization-summary .denied strong {
    color: #c34958;
  }
  .authorization-toolbar {
    display: grid;
    grid-template-columns: minmax(220px, 1fr) 180px 170px 150px auto;
    gap: 10px;
    padding: 12px;
    border: 1px solid #e7e9f1;
    border-bottom: 0;
    border-radius: 13px 13px 0 0;
    background: #fafbfe;
  }
  .authorization-table {
    border: 1px solid #e7e9f1;
    border-radius: 0 0 13px 13px;
    overflow: hidden;
  }
  .role-badge {
    display: inline-flex;
    padding: 5px 9px;
    border-radius: 7px;
    color: #5541bd;
    background: #f0edff;
    font-size: 11px;
    font-weight: 650;
  }
  .role-badge.ordinary {
    color: #687386;
    background: #f0f2f5;
  }
  .effect-state {
    color: #8a6a20;
    font-size: 12px;
    font-weight: 600;
  }
  .effect-state.active {
    color: #16835b;
  }
  .role-guide {
    margin-top: 18px;
    padding: 18px;
    border: 1px solid #e5e1f8;
    border-radius: 14px;
    background: linear-gradient(120deg, #faf8ff, #f8faff);
  }
  .role-guide-head h3 {
    margin: 0 0 4px;
    color: #302b4d;
    font-size: 14px;
  }
  .role-guide-head p {
    margin: 0;
    color: #7c7892;
    font-size: 12px;
  }
  .role-guide-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
    gap: 10px;
    margin-top: 14px;
  }
  .role-guide-grid article {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    padding: 12px;
    border: 1px solid rgba(111, 86, 221, 0.1);
    border-radius: 11px;
    background: rgba(255, 255, 255, 0.78);
  }
  .role-guide-grid article > span {
    display: grid;
    flex: 0 0 30px;
    width: 30px;
    height: 30px;
    place-items: center;
    border-radius: 9px;
    color: #654fc9;
    background: #ece7ff;
    font-size: 11px;
    font-weight: 800;
  }
  .role-guide-grid strong {
    color: #3d375c;
    font-size: 12px;
  }
  .role-guide-grid p {
    margin: 3px 0 0;
    color: #858096;
    font-size: 11px;
    line-height: 1.5;
  }
  .authorization-secondary {
    margin-top: 18px;
  }
  .ip-panel {
    background: #fff;
  }
  .dialog-toolbar {
    margin-top: 16px;
  }
  .field-help {
    width: 100%;
    margin: 6px 0 0;
    color: #858da0;
    font-size: 11px;
    line-height: 1.5;
  }
  .binding-detail {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
  .detail-identity {
    display: grid;
    grid-template-columns: 46px minmax(0, 1fr) auto;
    align-items: center;
    gap: 12px;
    padding: 16px;
    border: 1px solid #e4dffc;
    border-radius: 14px;
    background: linear-gradient(120deg, #f7f4ff, #fbfcff);
  }
  .detail-identity > span {
    display: grid;
    width: 46px;
    height: 46px;
    place-items: center;
    border-radius: 13px;
    color: #fff;
    background: linear-gradient(135deg, #6649e8, #9478ff);
    box-shadow: 0 8px 18px rgba(102, 73, 232, 0.2);
    font-weight: 800;
  }
  .detail-identity > div {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 3px;
  }
  .detail-identity strong {
    color: #292443;
  }
  .detail-identity small {
    color: #88839b;
  }
  .detail-section {
    padding: 17px;
    border: 1px solid #e9ebf2;
    border-radius: 13px;
    background: #fff;
  }
  .detail-section h3 {
    margin: 0 0 13px;
    font-size: 14px;
  }
  .detail-section dl {
    margin: 0;
  }
  .detail-section dl > div {
    display: grid;
    grid-template-columns: 120px minmax(0, 1fr);
    gap: 12px;
    padding: 9px 0;
    border-bottom: 1px solid #f0f1f5;
  }
  .detail-section dl > div:last-child {
    border-bottom: 0;
  }
  .detail-section dt {
    color: #8c94a5;
    font-size: 12px;
  }
  .detail-section dd {
    margin: 0;
    color: #35405a;
    font-size: 12px;
    overflow-wrap: anywhere;
  }
  .detail-note {
    margin: 10px 0 0;
    padding: 10px 11px;
    border-radius: 9px;
    color: #655a82;
    background: #f6f3ff;
    font-size: 11px;
    line-height: 1.55;
  }
  .default-configs {
    margin-top: 12px;
    border: 1px solid #eceef3;
    border-radius: 9px;
    overflow: hidden;
  }
  .default-configs > div {
    display: grid;
    grid-template-columns: minmax(150px, 1fr) minmax(80px, 0.65fr);
    gap: 10px;
    padding: 9px 10px;
    border-bottom: 1px solid #eceef3;
    font-size: 11px;
  }
  .default-configs > div:last-child {
    border-bottom: 0;
  }
  .default-configs code {
    color: #5a45bd;
    overflow-wrap: anywhere;
  }
  .profile-table {
    border: 1px solid #eceef3;
    border-radius: 13px;
    overflow: hidden;
  }
  .profile-explainer {
    display: grid;
    grid-template-columns: 46px minmax(280px, 1fr) auto;
    align-items: center;
    gap: 15px;
    margin: -2px 0 20px;
    padding: 17px 18px;
    border: 1px solid #ded8fb;
    border-radius: 14px;
    background: linear-gradient(115deg, #f7f4ff 0%, #f5f8ff 100%);
  }
  .profile-explainer-icon {
    display: grid;
    place-items: center;
    width: 46px;
    height: 46px;
    border-radius: 14px;
    color: #fff;
    background: linear-gradient(135deg, #6849eb, #9276ff);
    box-shadow: 0 8px 20px rgba(104, 73, 235, 0.22);
    font-weight: 800;
  }
  .profile-explainer-copy {
    min-width: 0;
  }
  .profile-explainer-copy strong {
    color: #342a64;
    font-size: 14px;
  }
  .profile-explainer-copy p {
    margin: 5px 0 0;
    color: #706b85;
    font-size: 12px;
    line-height: 1.65;
  }
  .profile-flow {
    display: flex;
    align-items: center;
    gap: 7px;
    white-space: nowrap;
  }
  .profile-flow span {
    padding: 6px 9px;
    border: 1px solid rgba(110, 83, 225, 0.13);
    border-radius: 8px;
    color: #6553aa;
    background: rgba(255, 255, 255, 0.8);
    font-size: 11px;
    font-weight: 650;
  }
  .profile-flow i {
    color: #a59bcf;
    font-size: 11px;
    font-style: normal;
  }
  .profile-table .el-tag {
    margin: 2px 4px 2px 0;
  }
  .form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0 16px;
  }
  .form-grid .full {
    grid-column: 1 / -1;
  }
  .selected-identity {
    margin-bottom: 17px;
    padding: 13px;
    border-radius: 12px;
    background: #f6f4ff;
  }
  .pair-heading {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin: 8px 0 10px;
  }
  .pair-heading div {
    display: flex;
    flex-direction: column;
  }
  .pair-heading small {
    color: #9299aa;
  }
  :deep(.add-property-button.el-button) {
    height: 34px;
    padding: 0 15px;
    border: 0;
    border-radius: 9px;
    color: #fff;
    background: linear-gradient(135deg, #6548ea, #8264f5);
    box-shadow: 0 7px 16px rgba(101, 72, 234, 0.2);
    font-weight: 650;
    transition: 0.2s ease;
  }
  :deep(.add-property-button.el-button:hover),
  :deep(.add-property-button.el-button:focus) {
    color: #fff;
    background: linear-gradient(135deg, #5c3fe0, #7656ee);
    box-shadow: 0 9px 20px rgba(101, 72, 234, 0.28);
    transform: translateY(-1px);
  }
  .pair-row {
    display: grid;
    grid-template-columns: 1.1fr 0.9fr 34px;
    gap: 8px;
    margin-bottom: 8px;
  }
  .activation-checks {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
    margin: 18px 0 12px;
  }
  .activation-check {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 13px;
    border: 1px solid #f0d9d5;
    border-radius: 11px;
    background: #fff8f6;
  }
  .activation-check.ready {
    border-color: #d1ebdf;
    background: #f3fbf7;
  }
  .activation-check > span {
    display: grid;
    flex: 0 0 26px;
    width: 26px;
    height: 26px;
    place-items: center;
    border-radius: 8px;
    color: #c34c3e;
    background: #fee7e2;
    font-size: 12px;
    font-weight: 800;
  }
  .activation-check.ready > span {
    color: #14815a;
    background: #dff5ea;
  }
  .activation-check div {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 3px;
  }
  .activation-check strong {
    font-size: 12px;
  }
  .activation-check small,
  .activation-admins-head small,
  .activation-admin-list small {
    color: #7c869b;
    font-size: 11px;
    line-height: 1.45;
  }
  .activation-admins {
    margin-bottom: 4px;
    padding: 14px;
    border: 1px solid #e5e8f1;
    border-radius: 11px;
    background: #fafbfe;
  }
  .activation-admins-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .activation-admins-head strong {
    font-size: 12px;
  }
  .activation-admin-list {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 11px;
  }
  .activation-admin-list > span {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    padding: 7px 9px;
    border: 1px solid #ddd5ff;
    border-radius: 8px;
    background: #f6f3ff;
  }
  .activation-admin-list b {
    color: #4f3ab6;
    font-size: 12px;
  }
  @media (max-width: 1200px) {
    .provider-grid {
      grid-template-columns: repeat(2, 1fr);
    }
    .metric-row {
      grid-template-columns: repeat(2, 1fr);
    }
    .hero-state {
      min-width: 340px;
    }
  }
  @media (max-width: 800px) {
    .access-page {
      padding: 12px;
    }
    .access-hero {
      align-items: flex-start;
      flex-direction: column;
      padding: 22px;
    }
    .hero-state {
      min-width: 0;
      width: 100%;
      box-sizing: border-box;
    }
    .activation-checks {
      grid-template-columns: 1fr;
    }
    .metric-row,
    .provider-grid,
    .role-guide-grid {
      grid-template-columns: 1fr;
    }
    .authorization-toolbar {
      grid-template-columns: 1fr;
      border-bottom: 1px solid #e7e9f1;
      border-radius: 13px;
    }
    .authorization-table {
      margin-top: 10px;
      border-radius: 13px;
    }
    .profile-explainer {
      grid-template-columns: 46px 1fr;
    }
    .profile-flow {
      grid-column: 1 / -1;
      flex-wrap: wrap;
    }
    .workspace-head,
    .directory-toolbar {
      height: auto;
      align-items: stretch;
      flex-direction: column;
    }
    .refresh-state {
      padding-bottom: 10px;
    }
    .access-tabs {
      width: 100%;
    }
    .tab-content {
      padding: 18px;
    }
  }
</style>
