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
  import { computed, onMounted, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { useI18n } from 'vue-i18n'
  import {
    getAdminPermissions,
    updateAdminPermissions,
    type AdminPermissions,
    type PermissionAssignment,
    type PermissionRole
  } from '@/api/permissions'

  const { t } = useI18n()
  const permissions = ref<AdminPermissions | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const error = ref('')
  const updatedAt = ref(0)
  const editorVisible = ref(false)
  const editorUser = ref('')
  const editorRole = ref('viewer')
  const activeRole = ref('')

  const text = (key: string, params?: Record<string, unknown>) =>
    t(`management.permissions_${key}`, params ?? {})

  const roles = computed(() => permissions.value?.roles ?? [])
  const assignments = computed(() => permissions.value?.assignments ?? [])
  const assignedUsers = computed(
    () => new Set(assignments.value.map((item) => item.user))
  )
  const selectedRole = computed<PermissionRole | undefined>(
    () =>
      roles.value.find((role) => role.name === activeRole.value) ??
      roles.value[0]
  )
  const roleAssignmentCount = (roleName: string) =>
    assignments.value.filter((item) => item.role === roleName).length

  const roleLabel = (roleName: string) =>
    roles.value.find((role) => role.name === roleName)?.label ?? roleName

  const loadPermissions = async () => {
    loading.value = true
    error.value = ''
    try {
      permissions.value = await getAdminPermissions()
      if (!activeRole.value && permissions.value.roles.length) {
        activeRole.value = permissions.value.roles[0].name
      }
      updatedAt.value = Date.now()
    } catch (err) {
      error.value = err instanceof Error ? err.message : text('load_failed')
    } finally {
      loading.value = false
    }
  }

  const openEditor = (assignment?: PermissionAssignment) => {
    editorUser.value = assignment?.user ?? ''
    editorRole.value = assignment?.role ?? roles.value[0]?.name ?? 'viewer'
    editorVisible.value = true
  }

  const saveAssignment = async () => {
    const user = editorUser.value.trim()
    if (!user) {
      ElMessage.warning(text('user_required'))
      return
    }
    const nextAssignments = assignments.value.filter(
      (item) => item.user !== user
    )
    nextAssignments.push({ user, role: editorRole.value })
    saving.value = true
    try {
      permissions.value = await updateAdminPermissions(nextAssignments)
      editorVisible.value = false
      updatedAt.value = Date.now()
      ElMessage.success(text('saved'))
    } catch (err) {
      ElMessage.error(err instanceof Error ? err.message : text('save_failed'))
    } finally {
      saving.value = false
    }
  }

  const removeAssignment = async (assignment: PermissionAssignment) => {
    try {
      await ElMessageBox.confirm(
        text('remove_confirm', { user: assignment.user }),
        text('remove_title'),
        { type: 'warning' }
      )
    } catch {
      return
    }
    saving.value = true
    try {
      permissions.value = await updateAdminPermissions(
        assignments.value.filter((item) => item.user !== assignment.user)
      )
      updatedAt.value = Date.now()
      ElMessage.success(text('removed'))
    } catch (err) {
      ElMessage.error(err instanceof Error ? err.message : text('save_failed'))
    } finally {
      saving.value = false
    }
  }

  const formatTime = (timestamp: number) =>
    timestamp ? new Date(timestamp).toLocaleTimeString() : '--'

  onMounted(loadPermissions)

  defineExpose({
    permissions,
    assignments,
    assignedUsers,
    editorUser,
    editorRole,
    loadPermissions,
    openEditor,
    saveAssignment,
    removeAssignment,
    roleAssignmentCount,
    roleLabel,
    formatTime
  })
</script>

<template>
  <div class="permissions-page">
    <section class="page-hero">
      <div>
        <div class="eyebrow">{{ text('eyebrow') }}</div>
        <h1>{{ text('title') }}</h1>
        <p>{{ text('subtitle') }}</p>
      </div>
      <div class="hero-actions">
        <span v-if="updatedAt" class="updated-at">
          {{ text('updated_at', { time: formatTime(updatedAt) }) }}
        </span>
        <el-button :loading="loading" icon="Refresh" @click="loadPermissions">
          {{ t('refresh') }}
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

    <section v-if="permissions" class="summary-grid">
      <article class="summary-card accent-purple">
        <span>{{ text('assigned_users') }}</span>
        <strong>{{ assignments.length }}</strong>
        <small>{{ text('assigned_users_hint') }}</small>
      </article>
      <article class="summary-card accent-blue">
        <span>{{ text('available_roles') }}</span>
        <strong>{{ roles.length }}</strong>
        <small>{{ text('available_roles_hint') }}</small>
      </article>
      <article class="summary-card accent-cyan">
        <span>{{ text('current_user') }}</span>
        <strong>{{ permissions.currentUser }}</strong>
        <small>{{ text('current_user_hint') }}</small>
      </article>
      <article class="summary-card accent-amber">
        <span>{{ text('storage') }}</span>
        <strong>JSON</strong>
        <small>{{ text('storage_hint') }}</small>
      </article>
    </section>

    <section v-if="permissions" class="role-grid">
      <button
        v-for="role in roles"
        :key="role.name"
        type="button"
        :class="['role-card', { active: selectedRole?.name === role.name }]"
        @click="activeRole = role.name">
        <span class="role-mark">{{ role.label.slice(0, 1) }}</span>
        <span class="role-copy">
          <strong>{{ role.label }}</strong>
          <small>{{ role.description }}</small>
        </span>
        <el-tag size="small" effect="plain">{{
          roleAssignmentCount(role.name)
        }}</el-tag>
      </button>
    </section>

    <section v-if="permissions" class="main-grid">
      <el-card class="panel assignments-panel" shadow="never">
        <template #header>
          <div class="panel-heading">
            <div>
              <span class="panel-kicker">{{ text('assignment_eyebrow') }}</span>
              <h2>{{ text('assignment_title') }}</h2>
            </div>
            <el-button type="primary" @click="openEditor()">
              {{ text('add_assignment') }}
            </el-button>
          </div>
        </template>
        <div class="assignment-note">
          <span class="note-icon">✓</span>
          <span>{{ text('assignment_hint') }}</span>
        </div>
        <el-table
          v-loading="loading || saving"
          :data="assignments"
          row-key="user"
          class="assignment-table">
          <el-table-column prop="user" :label="text('user')" min-width="180">
            <template #default="scope">
              <div class="user-cell">
                <span class="avatar">{{
                  scope.row.user.slice(0, 1).toUpperCase()
                }}</span>
                <strong>{{ scope.row.user }}</strong>
                <el-tag
                  v-if="scope.row.user === permissions.currentUser"
                  size="small"
                  type="success"
                  effect="plain">
                  {{ text('current') }}
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="text('role')" min-width="160">
            <template #default="scope">
              <el-tag effect="light">{{ roleLabel(scope.row.role) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="text('actions')" width="170" align="right">
            <template #default="scope">
              <el-button link type="primary" @click="openEditor(scope.row)">{{
                t('edit')
              }}</el-button>
              <el-button
                link
                type="danger"
                @click="removeAssignment(scope.row)"
                >{{ t('delete') }}</el-button
              >
            </template>
          </el-table-column>
          <template #empty>
            <div class="table-empty">
              <strong>{{ text('empty') }}</strong>
              <span>{{ text('empty_hint') }}</span>
            </div>
          </template>
        </el-table>
      </el-card>

      <el-card class="panel matrix-panel" shadow="never">
        <template #header>
          <div class="panel-heading compact">
            <div>
              <span class="panel-kicker">{{ text('matrix_eyebrow') }}</span>
              <h2>{{ selectedRole?.label }}</h2>
            </div>
            <span class="permission-count">{{
              selectedRole?.permissions.length ?? 0
            }}</span>
          </div>
        </template>
        <p class="panel-description">{{ selectedRole?.description }}</p>
        <div class="permission-list">
          <div
            v-for="item in selectedRole?.permissions"
            :key="item.resource"
            class="permission-row">
            <strong>{{ item.resource }}</strong>
            <div class="operation-list">
              <el-tag
                v-for="operation in item.operations"
                :key="operation"
                size="small"
                effect="plain">
                {{ operation }}
              </el-tag>
            </div>
          </div>
        </div>
      </el-card>
    </section>

    <el-dialog
      v-model="editorVisible"
      :title="text('editor_title')"
      width="430px">
      <el-form label-position="top" @submit.prevent="saveAssignment">
        <el-form-item :label="text('user')">
          <el-input
            v-model="editorUser"
            :placeholder="text('user_placeholder')" />
        </el-form-item>
        <el-form-item :label="text('role')">
          <el-select v-model="editorRole" class="full-width">
            <el-option
              v-for="role in roles"
              :key="role.name"
              :label="role.label"
              :value="role.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">{{ t('cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveAssignment">{{
          t('save')
        }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
  .permissions-page {
    min-height: 100%;
    padding: 4px 2px 24px;
    color: #17233f;
  }
  .page-hero,
  .panel-heading,
  .hero-actions,
  .user-cell,
  .assignment-note {
    display: flex;
    align-items: center;
  }
  .page-hero {
    align-items: flex-end;
    justify-content: space-between;
    gap: 20px;
    margin-bottom: 20px;
  }
  .eyebrow,
  .panel-kicker {
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
  .page-hero p,
  .panel-description {
    margin: 0;
    color: #71809a;
    font-size: 13px;
  }
  .hero-actions {
    gap: 10px;
  }
  .updated-at {
    color: #8794aa;
    font-size: 12px;
  }
  .error-alert {
    margin-bottom: 16px;
  }
  .summary-grid,
  .role-grid {
    display: grid;
    gap: 14px;
    margin-bottom: 16px;
  }
  .summary-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
  .summary-card {
    position: relative;
    overflow: hidden;
    padding: 20px;
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
  .summary-card span,
  .summary-card small {
    display: block;
    color: #71809a;
    font-size: 12px;
  }
  .summary-card strong {
    display: block;
    margin: 8px 0 4px;
    color: currentColor;
    font-size: 28px;
    line-height: 1;
  }
  .role-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
  .role-card {
    display: flex;
    align-items: center;
    gap: 12px;
    min-height: 92px;
    padding: 14px;
    border: 1px solid #e7ebf4;
    border-radius: 16px;
    color: #17233f;
    background: #fff;
    cursor: pointer;
    text-align: left;
    transition: 0.2s ease;
  }
  .role-card:hover,
  .role-card.active {
    border-color: #9b87ff;
    box-shadow: 0 10px 28px rgba(114, 84, 255, 0.12);
    transform: translateY(-2px);
  }
  .role-mark,
  .avatar {
    display: grid;
    flex: 0 0 auto;
    place-items: center;
    width: 38px;
    height: 38px;
    border-radius: 12px;
    color: #7254ff;
    background: #f0ecff;
    font-weight: 800;
  }
  .role-copy {
    min-width: 0;
    flex: 1;
  }
  .role-copy strong,
  .role-copy small {
    display: block;
  }
  .role-copy small {
    overflow: hidden;
    margin-top: 4px;
    color: #8794aa;
    font-size: 11px;
    line-height: 1.4;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .main-grid {
    display: grid;
    grid-template-columns: minmax(0, 1.25fr) minmax(330px, 0.75fr);
    gap: 16px;
  }
  .panel {
    border: 1px solid #e7ebf4;
    border-radius: 16px;
  }
  .panel-heading {
    justify-content: space-between;
    gap: 12px;
  }
  .panel-heading.compact {
    align-items: flex-start;
  }
  h2 {
    margin: 6px 0 0;
    font-size: 18px;
  }
  .assignment-note {
    gap: 10px;
    margin-bottom: 14px;
    padding: 11px 13px;
    border-radius: 10px;
    color: #5b6b85;
    background: #f7f8ff;
    font-size: 12px;
  }
  .note-icon {
    display: grid;
    width: 22px;
    height: 22px;
    place-items: center;
    border-radius: 50%;
    color: #fff;
    background: #7254ff;
    font-size: 12px;
  }
  .user-cell {
    gap: 10px;
  }
  .user-cell .avatar {
    width: 30px;
    height: 30px;
    border-radius: 9px;
    font-size: 12px;
  }
  .assignment-table :deep(.el-table__header th) {
    height: 44px;
    color: #76839b;
    background: #f8faff;
    font-size: 12px;
  }
  .table-empty {
    display: flex;
    align-items: center;
    flex-direction: column;
    padding: 42px 0;
    color: #8794aa;
  }
  .table-empty span {
    margin-top: 6px;
    font-size: 12px;
  }
  .permission-count {
    display: grid;
    width: 36px;
    height: 36px;
    place-items: center;
    border-radius: 12px;
    color: #7254ff;
    background: #f0ecff;
    font-weight: 800;
  }
  .permission-list {
    margin-top: 18px;
  }
  .permission-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 13px 0;
    border-bottom: 1px solid #eef1f6;
    font-size: 12px;
  }
  .permission-row strong {
    color: #43516b;
  }
  .operation-list {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 5px;
  }
  .full-width {
    width: 100%;
  }
  @media (max-width: 1100px) {
    .summary-grid,
    .role-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .main-grid {
      grid-template-columns: 1fr;
    }
  }
  @media (max-width: 650px) {
    .summary-grid,
    .role-grid {
      grid-template-columns: 1fr;
    }
    .page-hero {
      align-items: flex-start;
      flex-direction: column;
    }
    .hero-actions {
      width: 100%;
      justify-content: space-between;
    }
    .permission-row {
      align-items: flex-start;
      flex-direction: column;
    }
    .operation-list {
      justify-content: flex-start;
    }
  }
</style>
