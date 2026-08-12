<script setup lang="ts">
import {
  RecordDetailFields,
  RecordExternalChangeNotice,
  RecordFormFields,
  RecordMetaSection,
  type RecordFormFieldFallback,
  type RecordFormFieldPickerConfig,
  type RecordFormFieldValue,
  type RecordFormRecord,
} from '@muyun/platform-components';
import { UiButton, UiError, UiInput, UiSpin } from '@muyun/vue-ui-antdv';
import type { Department, Employee, EmployeeAccount, UserAccount } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';

defineOptions({ name: 'EmployeeDetailContent' });

const props = defineProps<{
  mode: 'view' | 'create' | 'edit';
  draft: Partial<Employee>;
  selectedEmployee?: Employee;
  selectedOrganization?: { id?: string; title?: string };
  detailDepartment?: Department;
  loading: boolean;
  loadFailed: boolean;
  formDisabled: boolean;
  showContent: boolean;
  fields: Map<string, import('../platform-components/recordFormFieldModel').RecordFormFieldDescriptor>;
  fallback: Record<string, RecordFormFieldFallback>;
  pickerConfigs: Record<string, RecordFormFieldPickerConfig>;
  displayOf: (fieldName: string, value: unknown) => string | number | boolean | undefined | null;
  visibleOf: (fieldName: string) => boolean;
  labelOf: (fieldName: string) => string;
  requiredOf: (fieldName: string) => boolean;
  externallyChanged: boolean;
  account?: EmployeeAccount;
  accountUser?: UserAccount;
  loadingAccounts: boolean;
  accountsLoadFailed: boolean;
  savingAccount: boolean;
  canManageAccounts: boolean;
  showAccountProvisionForm: boolean;
  accountProvisionDraft: Partial<UserAccount>;
  accountUserTitle: string;
  accountUserDescription: string;
  accountStatusTitle: string;
  optionContext: ModuleContext<unknown>;
}>();

const emit = defineEmits<{
  retry: [];
  save: [];
  reloadExternal: [];
  dismissExternal: [];
  updateField: [fieldName: string, value: RecordFormFieldValue];
  retryAccounts: [];
  startAccountProvision: [];
  cancelAccountProvision: [];
  provisionAccount: [];
  removeAccount: [];
  updateAccountProvisionField: [fieldName: 'username' | 'password', value: string];
}>();
</script>

<template>
  <UiSpin v-if="props.loading" class="employee-detail-state" tip="加载职员详情" />
  <div v-else-if="props.loadFailed" class="employee-detail-state">
    <UiError title="详情加载失败" message="无法加载职员详情，请重试" />
    <UiButton type="primary" icon-name="reload" @click="emit('retry')">重试</UiButton>
  </div>

  <template v-else-if="props.showContent">
    <template v-if="props.mode === 'view'">
      <RecordDetailFields
        :record="props.draft as RecordFormRecord"
        :fields="props.fields"
        :fallback="props.fallback"
        :picker-configs="props.pickerConfigs"
        :display-of="props.displayOf"
      />

      <section class="employee-account-section">
        <div class="employee-account-header">
          <strong>登录账号</strong>
          <UiButton
            v-if="!props.account && !props.showAccountProvisionForm"
            type="primary"
            icon-name="plus"
            :disabled="!props.canManageAccounts"
            @click="emit('startAccountProvision')"
          >
            设置账号
          </UiButton>
        </div>
        <UiSpin v-if="props.loadingAccounts" class="employee-account-state" tip="加载账号绑定" />
        <div v-else-if="props.accountsLoadFailed" class="employee-account-state">
          <UiError title="账号绑定加载失败" message="无法加载职员账号绑定，请重试" />
          <UiButton icon-name="reload" @click="emit('retryAccounts')">重试</UiButton>
        </div>
        <form
          v-else-if="props.showAccountProvisionForm"
          class="employee-account-form"
          @submit.prevent="emit('provisionAccount')"
        >
          <label>
            <span>账号</span>
            <UiInput
              :value="props.accountProvisionDraft.username"
              placeholder="请输入登录账号"
              :disabled="props.savingAccount"
              @update:value="emit('updateAccountProvisionField', 'username', $event)"
            />
          </label>
          <label>
            <span>初始密码</span>
            <UiInput
              :value="props.accountProvisionDraft.password"
              type="password"
              placeholder="请输入初始密码"
              :disabled="props.savingAccount"
              @update:value="emit('updateAccountProvisionField', 'password', $event)"
            />
          </label>
          <div class="employee-account-form-actions">
            <UiButton :disabled="props.savingAccount" @click="emit('cancelAccountProvision')">取消</UiButton>
            <UiButton
              type="primary"
              html-type="submit"
              icon-name="plus"
              :loading="props.savingAccount"
              :disabled="!props.canManageAccounts"
            >
              创建账号并绑定
            </UiButton>
          </div>
        </form>
        <div v-else-if="!props.account" class="employee-account-empty">
          <span>未设置登录账号</span>
          <small>可从职员档案生成账号并自动完成一对一绑定。</small>
        </div>
        <div v-else class="employee-account-card">
          <div>
            <strong>{{ props.accountUserTitle }}</strong>
            <span>{{ props.accountUserDescription }}</span>
          </div>
          <span class="employee-account-status">{{ props.accountStatusTitle }}</span>
          <UiButton
            danger
            icon-name="delete"
            :disabled="props.savingAccount || !props.canManageAccounts"
            @click="emit('removeAccount')"
          >
            移除账户
          </UiButton>
        </div>
      </section>

      <RecordMetaSection :record="props.draft" show-sort-order />
    </template>

    <template v-else>
      <RecordExternalChangeNotice
        v-if="props.externallyChanged"
        @reload="emit('reloadExternal')"
        @dismiss="emit('dismissExternal')"
      />
      <form class="employee-form" @submit.prevent="emit('save')">
        <label v-if="props.visibleOf('organizationId')">
          <span class="employee-form-label">
            {{ props.labelOf('organizationId') }}
            <strong v-if="props.requiredOf('organizationId')" aria-hidden="true">*</strong>
          </span>
          <UiInput
            :value="props.selectedOrganization?.title ?? props.selectedOrganization?.id ?? '-'"
            disabled
          />
        </label>
        <RecordFormFields
          :record="props.draft as RecordFormRecord"
          :fields="props.fields"
          :exclude-field-names="['organizationId']"
          :fallback="props.fallback"
          :picker-configs="props.pickerConfigs"
          :disabled="props.formDisabled"
          :option-context="props.optionContext"
          @update:field="(fieldName, value) => emit('updateField', fieldName, value)"
        />
      </form>
    </template>
  </template>
</template>

<style scoped>
.employee-form {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}
.employee-form > label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.employee-form-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.employee-form-label strong {
  color: var(--muyun-danger-base);
  font-weight: 600;
}
.employee-detail-state {
  display: grid;
  place-items: center;
  gap: 12px;
  min-height: 180px;
}
.employee-account-section {
  display: grid;
  gap: 12px;
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--muyun-border);
}
.employee-account-header {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.employee-account-state,
.employee-account-empty {
  display: grid;
  place-items: center;
  gap: 10px;
  min-height: 140px;
  color: var(--muyun-text-muted);
}
.employee-account-empty small {
  font-size: 12px;
}
.employee-account-form {
  display: grid;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
}
.employee-account-form label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.employee-account-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.employee-account-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
}
.employee-account-card div {
  display: grid;
  min-width: 0;
  gap: 3px;
}
.employee-account-card strong,
.employee-account-card span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.employee-account-card span {
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.employee-account-status {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--muyun-hover-subtle);
}
@media (max-width: 900px) {
  .employee-account-card {
    grid-template-columns: 1fr;
    align-items: start;
  }
  .employee-account-form-actions,
  .employee-account-header {
    justify-content: flex-start;
    flex-wrap: wrap;
  }
}
</style>
