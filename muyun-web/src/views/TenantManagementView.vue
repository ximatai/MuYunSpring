<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  CrudRecordListExplorer,
  type CrudRecordListBase,
  ModuleActionButton,
  RecordDetailDrawer,
  RecordActionBar,
  RecordMetaSection,
  SingleImageFileReferenceField,
  presentPlatformError,
  RecordStatusSwitch,
  RecycleBinModeButton,
  StaticManagementLayout,
  useRecycleBinExplorerMode,
  createSoftDeletedConflictErrorHandler,
  type RecordActionItem,
  type RecordExplorerItemDescriptor,
} from '@muyun/platform-components';
import type {
  Application,
  ResolvedFileReferenceFieldDescriptor,
  Tenant,
  TenantApplication,
  WebPageResponse,
} from '@muyun/web-contracts';
import { useModuleContext } from '@muyun/web-core';
import { confirmAction, UiButton, UiDataTable, UiInput, UiSelect } from '@muyun/vue-ui-antdv';
import type { UiDataTableColumn, UiDataTableRecord } from '@muyun/vue-ui-antdv';
import { createTenantManagementState } from './tenantManagementState';
import { resolveRecordFormFields } from '../platform-components/recordFormFieldModel';

defineOptions({ name: 'TenantManagementView' });

const tenantContext = useModuleContext<Tenant>();
const applicationContext = useModuleContext<Application>({ moduleAlias: 'platform.application' });
const explorerSearchKeyword = ref('');
const applications = ref<Application[]>([]);
const applicationsLoading = ref(false);
const tenantApplications = ref<TenantApplication[]>([]);
const tenantApplicationsLoading = ref(false);
const applicationConfigurationOpen = ref(false);
const applicationConfigurationSaving = ref(false);
const configuredApplicationAliases = ref<Set<string>>(new Set());
const tenantFormFields = ref(resolveRecordFormFields(undefined));
let tenantApplicationsLoadVersion = 0;
const {
  selected,
  draft,
  mode,
  reloadKey,
  saving,
  cardTitle,
  readonly,
  aliasReadonly,
  canDelete,
  canEnable,
  handleListLoaded,
  handleReadonlyListLoaded,
  handleSelect,
  startCreate: startCrudCreate,
  startEdit,
  cancelEdit,
  save,
  toggleEnabled,
  removeSelected,
} = createTenantManagementState(tenantContext, confirmAction, {
  actionErrorHandlers: [
    createSoftDeletedConflictErrorHandler({
      resourceLabel: '租户',
      onNavigateToRecycleBin: () => switchToRecycleBin(),
    }),
  ],
});
const recycleBinExplorer = useRecycleBinExplorerMode({
  context: tenantContext,
  listReloadKey: reloadKey,
  searchKeyword: explorerSearchKeyword,
  resetSelection: resetTenantSelection,
});

const enabledReadonly = computed(() => false);
const tenantFormDisabled = computed(() => recycleBinExplorer.active.value || readonly.value);
const logoWithTitle = computed(() => draft.value.workbenchBrandMode !== 'logoOnly');
const workbenchBrandMode = computed({
  get: () => draft.value.workbenchBrandMode ?? 'logoWithTitle',
  set: (value: 'logoOnly' | 'logoWithTitle' | null) => {
    draft.value.workbenchBrandMode = value ?? 'logoWithTitle';
  },
});
const lightLogoDefinition = computed<ResolvedFileReferenceFieldDescriptor | undefined>(
  () => tenantFormFields.value.get('lightLogoAssetId')?.fileReference,
);
const darkLogoDefinition = computed<ResolvedFileReferenceFieldDescriptor | undefined>(
  () => tenantFormFields.value.get('darkLogoAssetId')?.fileReference,
);
const brandModeOptions = [
  { value: 'logoOnly', label: '纯 Logo' },
  { value: 'logoWithTitle', label: 'Logo + 标题' },
];
const tenantApplicationColumns: UiDataTableColumn[] = [{ key: 'applicationAlias', title: '已开通应用' }];
const tenantApplicationRows = computed(() => tenantApplications.value as unknown as UiDataTableRecord[]);
const applicationRows = computed(() => applications.value as unknown as UiDataTableRecord[]);
const applicationConfigurationSelection = computed(() => ({
  selectedRowKeys: [...configuredApplicationAliases.value],
  preserveSelectedRowKeys: true,
  disabledOf: (record: UiDataTableRecord) => applicationConfigurationSaving.value || record.alias === 'iam',
  onChange: (keys: (string | number)[]) => {
    configuredApplicationAliases.value = new Set(keys.map((key) => String(key)));
  },
}));
const applicationConfigurationColumns: UiDataTableColumn[] = [
  { key: 'title', title: '应用名称', width: 260 },
  { key: 'alias', title: '应用 alias', width: 220 },
];

watch(
  [() => selected.value?.id, () => recycleBinExplorer.mode.value] as const,
  ([tenantId, currentViewMode]) => {
    applicationConfigurationOpen.value = false;
    configuredApplicationAliases.value = new Set();
    if (currentViewMode === 'normal') {
      void loadTenantApplications(tenantId);
    } else {
      tenantApplications.value = [];
    }
  },
  { immediate: true },
);
onMounted(() => {
  void loadApplications();
  void loadTenantFormFields();
});

const cardActions = computed<RecordActionItem[]>(() => {
  if (recycleBinExplorer.active.value) {
    return [];
  }
  if (mode.value !== 'view') {
    return [
      { key: 'cancel', title: '取消', disabled: saving.value },
      {
        key: 'save',
        actionCode: mode.value === 'create' ? 'create' : 'update',
        title: saving.value ? '保存中' : '保存',
        loading: saving.value,
        primary: true,
      },
    ];
  }
  return [
    { key: 'edit', actionCode: 'update', title: '编辑', disabled: !selected.value },
    {
      key: 'delete',
      actionCode: 'delete',
      title: '删除',
      disabled: !selected.value || !canDelete.value,
      loading: saving.value,
      danger: true,
    },
  ];
});

function tenantItemOf(record: CrudRecordListBase): RecordExplorerItemDescriptor {
  return {
    title: record.title ?? record.alias ?? record.id ?? '未命名租户',
    secondary: record.alias ?? record.id,
    muted: recycleBinExplorer.active.value || record.enabled === false,
  };
}

function handleLoaded(records: CrudRecordListBase[]) {
  const tenants = records as Tenant[];
  if (recycleBinExplorer.active.value) {
    handleReadonlyListLoaded(tenants);
    return;
  }
  handleListLoaded(tenants);
}

function handleTenantSelect(record: CrudRecordListBase) {
  handleSelect(record as Tenant);
}

function startCreate() {
  startCrudCreate();
}

async function handleCardAction(action: RecordActionItem) {
  if (action.key === 'edit') return startEdit();
  if (action.key === 'delete') return removeSelected();
  if (action.key === 'cancel') return cancelEdit();
  if (action.key === 'save') await save();
}

async function handleFormSubmit() {
  if (!recycleBinExplorer.active.value) {
    await save();
  }
}

async function loadTenantFormFields() {
  try {
    const runtime = await tenantContext.runtime.ready;
    tenantFormFields.value = resolveRecordFormFields(runtime.uiDescriptor);
  } catch (cause) {
    presentPlatformError(cause, { source: 'tenant-management-form', phase: 'load' });
  }
}

function tenantLogoUploadHint() {
  return logoWithTitle.value
    ? 'Logo + 标题模式仅支持正方形图片（建议 128 × 128 px，最大 512 KB）'
    : '纯 Logo 模式支持横向或正方形图片（最大 512 KB）';
}

async function validateTenantLogo(file: File) {
  if (!logoWithTitle.value) return undefined;
  if (!file.type.startsWith('image/') || typeof Image === 'undefined') {
    return 'Logo + 标题模式需要可读取的正方形图片。';
  }
  try {
    const { width, height } = await imageDimensionsOf(file);
    const ratio = width / height;
    if (ratio >= 0.9 && ratio <= 1.1) {
      return undefined;
    }
    return `“${file.name}”为 ${width} × ${height} px；Logo + 标题模式仅允许上传正方形 Logo。`;
  } catch {
    return '无法读取图片尺寸，请选择正方形 PNG、JPG 或 GIF 图片。';
  }
}

function imageDimensionsOf(file: File) {
  const objectUrl = URL.createObjectURL(file);
  return new Promise<{ width: number; height: number }>((resolve, reject) => {
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(objectUrl);
      resolve({ width: image.naturalWidth, height: image.naturalHeight });
    };
    image.onerror = () => {
      URL.revokeObjectURL(objectUrl);
      reject(new Error('无法读取图片尺寸'));
    };
    image.src = objectUrl;
  });
}

async function loadApplications() {
  applicationsLoading.value = true;
  try {
    await applicationContext.runtime.ready;
    const response = await applicationContext.abilities.crud().query({ page: { pageNum: 1, pageSize: 200 } });
    applications.value = response.records.filter((application) => application.enabled !== false);
  } catch (cause) {
    presentPlatformError(cause, { source: 'tenant-management-applications', phase: 'load' });
  } finally {
    applicationsLoading.value = false;
  }
}

async function loadTenantApplications(tenantId?: string) {
  const loadVersion = ++tenantApplicationsLoadVersion;
  if (!tenantId) {
    tenantApplications.value = [];
    return;
  }
  tenantApplicationsLoading.value = true;
  try {
    const response = await tenantContext.http.request<WebPageResponse<TenantApplication>>({
      method: 'POST',
      path: `${tenantApplicationsPath(tenantId)}/query`,
      body: { page: { pageNum: 1, pageSize: 200 } },
    });
    if (loadVersion === tenantApplicationsLoadVersion && selected.value?.id === tenantId) {
      tenantApplications.value = response.records;
    }
  } catch (cause) {
    presentPlatformError(cause, { source: 'tenant-management-applications', phase: 'load' });
  } finally {
    if (loadVersion === tenantApplicationsLoadVersion) tenantApplicationsLoading.value = false;
  }
}

async function openApplicationConfiguration() {
  const tenantId = selected.value?.id;
  if (!tenantId) return;
  await Promise.all([loadApplications(), loadTenantApplications(tenantId)]);
  const activeApplicationAliases = new Set<string>();
  for (const application of applications.value) {
    if (application.alias) activeApplicationAliases.add(application.alias);
  }
  configuredApplicationAliases.value = new Set([
    'iam',
    ...tenantApplications.value
      .map((application) => application.applicationAlias)
      .filter((applicationAlias): applicationAlias is string => {
        return typeof applicationAlias === 'string' && activeApplicationAliases.has(applicationAlias);
      }),
  ]);
  applicationConfigurationOpen.value = true;
}

async function saveApplicationConfiguration() {
  const tenantId = selected.value?.id;
  if (!tenantId) return;
  applicationConfigurationSaving.value = true;
  try {
    await tenantContext.http.request<{ records: string[] }>({
      method: 'POST',
      path: `${tenantApplicationsPath(tenantId)}/configure`,
      body: { applicationAliases: [...configuredApplicationAliases.value] },
    });
    await loadTenantApplications(tenantId);
    applicationConfigurationOpen.value = false;
  } catch (cause) {
    presentPlatformError(cause, { source: 'tenant-management-applications', phase: 'action' });
  } finally {
    applicationConfigurationSaving.value = false;
  }
}

function closeApplicationConfiguration() {
  if (!applicationConfigurationSaving.value) applicationConfigurationOpen.value = false;
}

function tenantApplicationsPath(tenantId: string) {
  return `/iam.tenant/${encodeURIComponent(tenantId)}/applications`;
}

function switchToRecycleBin() {
  recycleBinExplorer.enter();
}

function resetTenantSelection() {
  selected.value = undefined;
  draft.value = { alias: '', title: '', enabled: true };
  mode.value = 'view';
  tenantApplications.value = [];
}
</script>

<template>
  <StaticManagementLayout
    v-model:explorer-search-keyword="explorerSearchKeyword"
    :explorer-title="recycleBinExplorer.active.value ? '回收站' : '租户列表'"
    :refresh-title="recycleBinExplorer.active.value ? '刷新回收站' : '刷新租户列表'"
    explorer-search-placeholder="搜索租户名称、alias 或 ID"
    :explorer-searchable="!recycleBinExplorer.active.value"
    :mode="mode"
    :detail-title="cardTitle"
    @refresh="recycleBinExplorer.refresh"
  >
    <template #explorer-actions>
      <ModuleActionButton
        v-if="!recycleBinExplorer.active.value"
        class="record-panel-create-button"
        :context="tenantContext"
        action-code="create"
        title="新建租户"
        icon-only
        @click="startCreate"
      />
    </template>
    <template #explorer-footer>
      <RecycleBinModeButton
        v-if="recycleBinExplorer.buttonVisible.value"
        :active="recycleBinExplorer.active.value"
        :has-records="recycleBinExplorer.hasRecords.value"
        :count="recycleBinExplorer.total.value"
        @click="recycleBinExplorer.toggle"
      />
    </template>
    <template #explorer>
      <CrudRecordListExplorer
        :context="tenantContext"
        :selected-id="selected?.id"
        :reload-key="recycleBinExplorer.reloadKey.value"
        :mode="recycleBinExplorer.mode.value"
        :keyword="explorerSearchKeyword"
        :empty-description="recycleBinExplorer.active.value ? '回收站为空' : '暂无租户'"
        :loading-tip="recycleBinExplorer.active.value ? '加载回收站' : '加载租户列表'"
        fallback-title="未命名租户"
        :item-of="tenantItemOf"
        @recycle-bin-summary="recycleBinExplorer.updateSummary"
        @select="handleTenantSelect"
        @loaded="handleLoaded"
      />
    </template>
    <template #detail-actions>
      <RecordActionBar
        v-if="!recycleBinExplorer.active.value"
        :context="tenantContext"
        :actions="cardActions"
        @action="handleCardAction"
      />
    </template>
    <template #detail-status>
      <template v-if="!recycleBinExplorer.active.value">
        <RecordStatusSwitch
          v-if="mode !== 'view'"
          :enabled="draft.enabled"
          :disabled="enabledReadonly"
          :show-label="false"
          @change="draft.enabled = $event"
        />
        <RecordStatusSwitch
          v-else-if="selected"
          :enabled="selected.enabled"
          :disabled="saving || !canEnable"
          :loading="saving"
          :show-label="false"
          @change="toggleEnabled"
        />
      </template>
    </template>

    <form class="static-record-form" @submit.prevent="handleFormSubmit">
      <div class="tenant-form-grid">
        <label>
          <span>租户 alias</span>
          <UiInput v-model:value="draft.alias" :disabled="tenantFormDisabled || aliasReadonly" />
        </label>
        <label>
          <span>租户名称</span>
          <UiInput v-model:value="draft.title" :disabled="tenantFormDisabled" />
        </label>
      </div>

      <section class="tenant-branding">
        <div class="tenant-branding__heading">
          <h3>主标题 UI 个性化配置</h3>
          <p>选择一种明确的品牌组合，避免横向 Logo 与标题文字在紧凑侧栏中相互挤压。</p>
        </div>
        <div class="tenant-form-grid">
          <label>
            <span>工作台品牌展示方式</span>
            <UiSelect
              v-model:value="workbenchBrandMode"
              :options="brandModeOptions"
              :disabled="tenantFormDisabled"
              :allow-clear="false"
            />
          </label>
          <p class="tenant-branding__mode-hint">
            {{ logoWithTitle ? '使用正方形图标搭配主标题。' : '仅展示 Logo，不显示主标题和副标题。' }}
          </p>
          <template v-if="logoWithTitle">
            <label>
              <span>主标题</span>
              <UiInput v-model:value="draft.workbenchTitle" :disabled="tenantFormDisabled" />
            </label>
            <label>
              <span>副标题（可选）</span>
              <UiInput v-model:value="draft.workbenchSubtitle" :disabled="tenantFormDisabled" />
            </label>
          </template>
        </div>
        <div class="tenant-branding__logos">
          <SingleImageFileReferenceField
            v-if="lightLogoDefinition"
            label="展示 Logo（默认）"
            :value="draft.lightLogoAssetId"
            :record="draft"
            :context="tenantContext"
            :definition="lightLogoDefinition"
            :disabled="tenantFormDisabled"
            :form-session-key="`${mode}:${selected?.id ?? draft.alias ?? ''}`"
            :upload-hint="tenantLogoUploadHint()"
            :upload-validation="validateTenantLogo"
            @update:value="draft.lightLogoAssetId = $event"
          />
          <SingleImageFileReferenceField
            v-if="darkLogoDefinition"
            label="展示 Logo（暗色模式）"
            :value="draft.darkLogoAssetId"
            :record="draft"
            :context="tenantContext"
            :definition="darkLogoDefinition"
            :disabled="tenantFormDisabled"
            :form-session-key="`${mode}:${selected?.id ?? draft.alias ?? ''}`"
            :upload-hint="tenantLogoUploadHint()"
            :upload-validation="validateTenantLogo"
            @update:value="draft.darkLogoAssetId = $event"
          />
        </div>
      </section>
    </form>

    <section
      v-if="!recycleBinExplorer.active.value && selected && mode === 'view'"
      class="tenant-applications"
    >
      <div class="tenant-applications-header">
        <div>
          <h3>已开通应用</h3>
          <p>应用是否可用以“是否开通”为准，不再维护租户侧启停状态。</p>
        </div>
        <UiButton type="primary" :loading="applicationsLoading" @click="openApplicationConfiguration">
          配置应用
        </UiButton>
      </div>
      <UiDataTable
        :columns="tenantApplicationColumns"
        :rows="tenantApplicationRows"
        :loading="tenantApplicationsLoading"
        :pagination="false"
        empty-description="暂未开通应用"
      />
    </section>
    <RecordMetaSection :record="draft" show-sort-order />
  </StaticManagementLayout>

  <RecordDetailDrawer
    :open="applicationConfigurationOpen"
    title="配置应用"
    close-title="取消"
    @close="closeApplicationConfiguration"
  >
    <template #operation>
      <UiButton :disabled="applicationConfigurationSaving" @click="closeApplicationConfiguration">
        取消
      </UiButton>
      <UiButton
        type="primary"
        :loading="applicationConfigurationSaving"
        @click="saveApplicationConfiguration"
      >
        确认
      </UiButton>
    </template>
    <section class="tenant-application-configuration">
      <p>勾选表示向当前租户开通应用；取消勾选将移除该租户的应用开通记录。</p>
      <UiDataTable
        :columns="applicationConfigurationColumns"
        :rows="applicationRows"
        :loading="applicationsLoading"
        :pagination="false"
        :selection="applicationConfigurationSelection"
        :row-key="(record) => String(record.alias ?? record.id ?? '')"
        empty-description="暂无可配置应用"
      />
    </section>
  </RecordDetailDrawer>
</template>

<style scoped>
.tenant-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
.static-record-form > .tenant-form-grid,
.static-record-form > .tenant-branding {
  grid-column: 1 / -1;
}
.tenant-form-grid > label {
  display: grid;
  gap: 7px;
  min-width: 0;
}
.tenant-form-grid > label > span {
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.tenant-branding {
  display: grid;
  gap: 16px;
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px solid var(--muyun-border);
}
.tenant-branding__heading {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px 14px;
}
.tenant-branding__heading h3,
.tenant-branding__heading p,
.tenant-branding__mode-hint {
  margin: 0;
}
.tenant-branding__heading h3 {
  font-size: 15px;
}
.tenant-branding__heading p,
.tenant-branding__mode-hint {
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.tenant-branding__mode-hint {
  align-self: end;
  padding-bottom: 7px;
}
.tenant-branding__logos {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.tenant-applications {
  display: grid;
  gap: 12px;
  margin-top: 20px;
}

.tenant-applications-header {
  display: flex;
  align-items: center;
  gap: 10px;
}
.tenant-applications-header {
  justify-content: space-between;
}
.tenant-applications h3,
.tenant-applications p {
  margin: 0;
}
.tenant-applications p {
  margin-top: 4px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.tenant-application-configuration {
  display: grid;
  gap: 12px;
}
.tenant-application-configuration p {
  margin: 0;
  color: var(--muyun-text-muted);
}
@media (max-width: 720px) {
  .tenant-form-grid,
  .tenant-branding__logos {
    grid-template-columns: 1fr;
  }
}
</style>
