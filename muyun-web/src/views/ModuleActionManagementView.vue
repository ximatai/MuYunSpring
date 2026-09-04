<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  CrudRecordListExplorer,
  ModuleActionButton,
  RecordActionBar,
  RecordDetailFields,
  RecordMetaSection,
  RecordStatusSwitch,
  StaticManagementLayout,
  createStaticTreeResourceModuleContext,
  presentPlatformError,
  useFlatCrudManagementState,
  type CrudRecordListBase,
  type RecordActionItem,
  type RecordExplorerItemDescriptor,
  type RecordFormRecord,
} from '@muyun/platform-components';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import type { PlatformModuleAction } from '@muyun/web-contracts';
import { createStaticResourceTreeClient, useModuleContext } from '@muyun/web-core';
import {
  confirmAction,
  UiButton,
  UiCheckbox,
  UiEmpty,
  UiIcon,
  UiInput,
  UiSelect,
  UiSwitch,
  UiTextArea,
  UiTooltip,
} from '@muyun/vue-ui-antdv';

defineOptions({ name: 'ModuleActionManagementView' });

const props = defineProps<{
  moduleAlias: string;
  moduleTitle?: string;
  moduleKind?: 'static' | 'dynamic';
  title?: string;
}>();
type ExecutorDefinition = {
  executorKey: string;
  title: string;
  description?: string;
  supportedLevels: Array<'LIST' | 'RECORD' | 'BATCH' | 'ANY'>;
};
const baseContext = useModuleContext<PlatformModuleAction>({ moduleAlias: 'platform.module_action' });
const actionContext = createStaticTreeResourceModuleContext(baseContext, {
  client: createStaticResourceTreeClient<PlatformModuleAction>(
    baseContext.http,
    `/platform.module/${encodeURIComponent(props.moduleAlias)}/actions`,
  ),
});
const searchKeyword = ref('');
const executorDefinitions = ref<ExecutorDefinition[]>([]);
const management = useFlatCrudManagementState({
  context: actionContext,
  confirmAction,
  emptyDraft: () => emptyActionDraft(props.moduleAlias),
  normalizeDraft: (record) => normalizeActionDraft(record, props.moduleAlias),
  copyRecord: (record) => ({ ...record }),
  titleOf: actionTitleOf,
  fallbackTitle: '模块动作',
  createTitle: '新建动作',
  requiredMessage: '动作编码不能为空',
  isValid: (record) => Boolean(record.actionCode?.trim()),
  recordName: '动作',
  deleteTitle: '删除动作',
  saveDeniedMessage: '当前用户无权保存模块动作',
  createDeniedMessage: '当前用户无权新建模块动作',
  enableDeniedMessage: '当前用户无权变更模块动作启停状态',
  deleteDeniedMessage: () => '当前用户无权删除模块动作',
  canDeleteRecord: (record) => record.systemManaged !== true,
  canEnableRecord: (record) => record.systemManaged !== true,
});
const { selected, draft, mode, reloadKey, saving, cardTitle, canCreate, canEnable } = management;
const hasUnsavedChanges = computed(() => {
  if (mode.value === 'view') return false;
  const baseline = selected.value ? normalizeActionDraft(selected.value, props.moduleAlias) : emptyActionDraft(props.moduleAlias);
  return JSON.stringify(draft.value) !== JSON.stringify(baseline);
});
useWorkspaceViewUnsavedState('模块动作', () => hasUnsavedChanges.value);
const canCreateManualAction = computed(
  () => props.moduleKind === 'dynamic' && canCreate.value && executorDefinitions.value.length > 0,
);
const executorOptions = computed(() =>
  executorDefinitions.value.map((executor) => ({
    value: executor.executorKey,
    label: executor.description ? `${executor.title} · ${executor.description}` : executor.title,
  })),
);
const actionLevelOptions = computed(() => {
  const executor = executorDefinitions.value.find((item) => item.executorKey === draft.value.executorKey);
  const levels = executor?.supportedLevels ?? [];
  return levels.map((value) => ({
    value,
    label: { LIST: '列表', RECORD: '单条记录', BATCH: '批量', ANY: '任意' }[value],
  }));
});
const actionDetailFieldNames = computed(() => [
  'actionCode',
  'title',
  'category',
  'actionLevel',
  'accessMode',
  'executorType',
  ...(hasText(draft.value.executorKey) ? ['executorKey'] : []),
  'actionAuth',
  'dataAuth',
  'defaultGrantPolicy',
  ...(hasText(draft.value.availableExpression) ? ['availableExpression'] : []),
  ...(hasText(draft.value.unavailableMessage) ? ['unavailableMessage'] : []),
  ...(draft.value.sourceType ? ['sourceType'] : []),
  ...(hasText(draft.value.bindingAlias) ? ['bindingAlias'] : []),
]);
const actionDeclarationFieldNames = computed(() => [
  'actionCode',
  'title',
  'category',
  'actionLevel',
  'executorType',
  ...(hasText(draft.value.executorKey) ? ['executorKey'] : []),
  ...(draft.value.sourceType ? ['sourceType'] : []),
]);

onMounted(() => void loadExecutorDefinitions());

const readonly = computed(() => management.readonly.value);
const formDisabled = computed(() => readonly.value || management.saving.value);
const cardActions = computed<RecordActionItem[]>(() => {
  if (management.mode.value !== 'view') {
    return [
      ...(management.mode.value === 'edit' &&
      management.selected.value?.systemManaged === true &&
      hasPermissionGovernanceOverride(draft.value)
        ? [
            {
              key: 'reset-permission-governance',
              actionCode: 'update',
              title: '全部重置',
              disabled: management.saving.value,
            },
          ]
        : []),
      { key: 'cancel', title: '取消', disabled: management.saving.value },
      {
        key: 'save',
        actionCode: management.mode.value === 'create' ? 'create' : 'update',
        title: management.saving.value ? '保存中' : '保存',
        primary: true,
        loading: management.saving.value,
      },
    ];
  }
  const selected = management.selected.value;
  return [
    {
      key: 'edit',
      actionCode: 'update',
      title: '编辑',
      disabled: !selected || !management.canUpdate.value,
    },
    {
      key: 'delete',
      actionCode: 'delete',
      title: '删除',
      danger: true,
      loading: management.saving.value,
      disabled: !selected || selected.systemManaged === true || !management.canDelete.value,
    },
  ];
});

function actionItemOf(record: CrudRecordListBase): RecordExplorerItemDescriptor {
  const action = record as PlatformModuleAction;
  return {
    title: actionTitleOf(action),
    secondary: action.actionCode,
    tag: action.systemManaged === true ? '平台托管' : action.category,
    muted: action.enabled === false,
  };
}

function handleAction(action: RecordActionItem) {
  if (action.key === 'edit') management.startEdit();
  if (action.key === 'delete') void management.removeSelected();
  if (action.key === 'reset-permission-governance') resetPermissionGovernance();
  if (action.key === 'cancel') management.cancelEdit();
  if (action.key === 'save') void management.save();
}

function hasPermissionGovernanceOverride(action: PlatformModuleAction) {
  return [
    action.accessModeOverride,
    action.actionAuthOverride,
    action.dataAuthOverride,
    action.defaultGrantPolicyOverride,
  ].some((value) => value != null);
}

function governanceOverridden(
  fieldName: 'accessModeOverride' | 'actionAuthOverride' | 'dataAuthOverride' | 'defaultGrantPolicyOverride',
) {
  return draft.value[fieldName] != null;
}

function resetGovernanceField(
  fieldName: 'accessModeOverride' | 'actionAuthOverride' | 'dataAuthOverride' | 'defaultGrantPolicyOverride',
) {
  updateDraft(fieldName, undefined);
}

function resetPermissionGovernance() {
  management.draft.value = {
    ...management.draft.value,
    accessModeOverride: undefined,
    actionAuthOverride: undefined,
    dataAuthOverride: undefined,
    defaultGrantPolicyOverride: undefined,
  };
}

function updateDraft(field: keyof PlatformModuleAction, value: unknown) {
  management.draft.value = {
    ...management.draft.value,
    [field]: value ?? undefined,
  } as PlatformModuleAction;
}

async function loadExecutorDefinitions() {
  try {
    executorDefinitions.value = await baseContext.http.request<ExecutorDefinition[]>({
      path: '/platform.module/action-executors',
    });
  } catch (cause) {
    executorDefinitions.value = [];
    presentPlatformError(cause, { source: 'module-action-management', phase: 'load' });
  }
}

function startCreate() {
  management.startCreate();
  const executor = executorDefinitions.value[0];
  if (!executor) return;
  management.draft.value = {
    ...management.draft.value,
    executorKey: executor.executorKey,
    actionLevel: executor.supportedLevels[0] ?? 'ANY',
  };
}

function updateExecutor(executorKey: unknown) {
  const value = typeof executorKey === 'string' ? executorKey : undefined;
  const executor = executorDefinitions.value.find((item) => item.executorKey === value);
  updateDraft('executorKey', value);
  if (executor?.supportedLevels.length) {
    updateDraft('actionLevel', executor.supportedLevels[0]);
  }
}

function emptyActionDraft(moduleAlias: string): PlatformModuleAction {
  return {
    moduleAlias,
    actionCode: '',
    title: '',
    category: 'CUSTOM',
    actionLevel: 'ANY',
    accessMode: 'AUTH_REQUIRED',
    actionAuth: true,
    dataAuth: false,
    defaultGrantPolicy: 'NONE',
    executorType: 'SERVICE',
    enabled: true,
    systemManaged: false,
  };
}

function normalizeActionDraft(record: PlatformModuleAction, moduleAlias: string): PlatformModuleAction {
  return {
    ...record,
    moduleAlias,
    actionCode: trimRequired(record.actionCode),
    title: trimOptional(record.title) ?? trimRequired(record.actionCode),
    entityAlias: trimOptional(record.entityAlias),
    permissionActionCode: trimOptional(record.permissionActionCode),
    availableExpression: trimOptional(record.availableExpression),
    unavailableMessage: trimOptional(record.unavailableMessage),
    executorKey: trimOptional(record.executorKey),
  };
}

function trimRequired(value: string | undefined) {
  return value?.trim() ?? '';
}

function trimOptional(value: string | undefined) {
  const normalized = value?.trim();
  return normalized ? normalized : undefined;
}

function hasText(value: string | undefined) {
  return Boolean(value?.trim());
}

function actionTitleOf(action: PlatformModuleAction) {
  return action.title?.trim() || action.actionCode?.trim() || action.id || '未命名动作';
}

function actionDetailDisplayValue(fieldName: string, value: unknown) {
  const labels: Record<string, Record<string, string>> = {
    category: {
      STANDARD: '标准动作',
      CUSTOM: '自定义动作',
      DIALOG: '弹窗动作',
      WORKFLOW: '工作流动作',
      GENERATE: '生成动作',
    },
    actionLevel: { LIST: '列表', RECORD: '单条记录', BATCH: '批量', ANY: '任意' },
    accessMode: { AUTH_REQUIRED: '需要授权', LOGIN_REQUIRED: '登录可用', ANONYMOUS_ALLOWED: '匿名可用' },
    executorType: {
      STANDARD: '平台标准',
      SERVICE: '服务（二开）',
      DIALOG: '弹窗协议',
      WORKFLOW: '工作流',
      GENERATE: '生成器',
    },
    defaultGrantPolicy: {
      NONE: '不默认授予',
      ANY_LOGIN_USER: '所有登录用户',
      OWNER: '记录所有者',
      ASSIGNEE: '办理人',
      MEMBER: '成员',
    },
    sourceType: {
      STATIC_MODULE: '静态模块声明',
      CODE_EXTENSION: '代码扩展',
      DYNAMIC_MODULE: '动态模块配置',
      WORKFLOW_DEFINITION: '工作流定义',
      WORKFLOW_RUNTIME: '工作流运行时',
      RECORD_GENERATION_RULE: '生单规则',
      DOCUMENT_RELATION: '单据关系',
      PRINT_TEMPLATE: '打印模板',
    },
  };
  if (fieldName === 'actionAuth') return value === true ? '启用' : '未启用';
  if (fieldName === 'dataAuth') return value === true ? '启用' : '未启用';
  return typeof value === 'string' ? labels[fieldName]?.[value] : undefined;
}
</script>

<template>
  <StaticManagementLayout
    explorer-title="模块动作"
    refresh-title="刷新动作"
    :mode="mode"
    :detail-title="cardTitle"
    :explorer-search-keyword="searchKeyword"
    explorer-search-placeholder="搜索动作名称或编码"
    @update:explorer-search-keyword="searchKeyword = $event"
    @refresh="reloadKey += 1"
  >
    <template #explorer-actions>
      <ModuleActionButton
        class="record-panel-create-button"
        :context="actionContext"
        action-code="create"
        title="新建动作"
        icon-only
        :disabled="!canCreateManualAction || saving"
        @click="startCreate"
      />
    </template>
    <template #explorer>
      <CrudRecordListExplorer
        :context="actionContext"
        :selected-id="selected?.id"
        :reload-key="reloadKey"
        :keyword="searchKeyword"
        empty-description="当前模块暂无动作"
        loading-tip="加载模块动作"
        fallback-title="未命名动作"
        :item-of="actionItemOf"
        @loaded="management.handleListLoaded($event as PlatformModuleAction[])"
        @select="management.handleSelect($event as PlatformModuleAction)"
      />
    </template>

    <template #detail-status>
      <RecordStatusSwitch
        v-if="mode === 'view' && selected"
        :enabled="selected.enabled"
        :disabled="saving || selected.systemManaged === true || !canEnable"
        :loading="saving"
        :show-label="false"
        @change="management.toggleEnabled"
      />
    </template>
    <template #detail-actions>
      <RecordActionBar :context="actionContext" :actions="cardActions" @action="handleAction" />
    </template>

    <UiEmpty
      v-if="!selected && mode === 'view'"
      :description="moduleKind === 'static' ? '静态模块动作由 Java 声明自动注册' : '当前模块暂无动作'"
    />
    <template v-else-if="mode === 'view' && selected">
      <RecordDetailFields
        :record="draft as RecordFormRecord"
        :display-of="actionDetailDisplayValue"
        :field-names="actionDetailFieldNames"
        :fallback="{
          actionCode: { label: '动作编码' },
          title: { label: '动作名称' },
          category: { label: '动作类别' },
          actionLevel: { label: '执行层级' },
          accessMode: { label: '访问方式' },
          executorType: { label: '执行方式' },
          executorKey: { label: '执行实现' },
          actionAuth: { label: '动作授权' },
          dataAuth: { label: '数据授权' },
          defaultGrantPolicy: { label: '默认授予策略' },
          availableExpression: { label: '可用条件' },
          unavailableMessage: { label: '不可用提示' },
          sourceType: { label: '注册来源' },
          bindingAlias: { label: '绑定标识' },
        }"
      />
    </template>
    <form v-else-if="selected?.systemManaged" class="managed-action-editor" @submit.prevent="management.save">
      <section class="action-declaration-section">
        <h3>动作声明</h3>
        <RecordDetailFields
          :record="draft as RecordFormRecord"
          :display-of="actionDetailDisplayValue"
          :field-names="actionDeclarationFieldNames"
          :fallback="{
            actionCode: { label: '动作编码' },
            title: { label: '动作名称' },
            category: { label: '动作类别' },
            actionLevel: { label: '执行层级' },
            executorType: { label: '执行方式' },
            executorKey: { label: '执行实现' },
            sourceType: { label: '注册来源' },
          }"
        />
      </section>
      <section class="permission-governance-section">
        <h3>权限治理</h3>
        <div class="governance-grid">
          <section class="governance-item">
            <header>
              <div>
                <h4>
                  访问方式
                  <UiTooltip title="决定调用动作前需要满足的身份条件。">
                    <button type="button" class="governance-help" aria-label="访问方式说明">
                      <UiIcon name="help" />
                    </button>
                  </UiTooltip>
                </h4>
              </div>
              <span class="governance-reset-slot">
                <UiButton
                  v-if="governanceOverridden('accessModeOverride')"
                  type="text"
                  size="small"
                  :disabled="formDisabled"
                  @click="resetGovernanceField('accessModeOverride')"
                  >重置</UiButton
                >
              </span>
            </header>
            <UiSelect
              :value="draft.accessModeOverride ?? draft.accessMode"
              :disabled="formDisabled"
              :options="[
                { label: '需要授权', value: 'AUTH_REQUIRED' },
                { label: '登录可用', value: 'LOGIN_REQUIRED' },
                { label: '匿名可用', value: 'ANONYMOUS_ALLOWED' },
              ]"
              @update:value="updateDraft('accessModeOverride', $event)"
            />
          </section>
          <section class="governance-item">
            <header>
              <div>
                <h4>
                  默认授予策略
                  <UiTooltip title="定义满足条件的用户首次获得动作时的默认规则。">
                    <button type="button" class="governance-help" aria-label="默认授予策略说明">
                      <UiIcon name="help" />
                    </button>
                  </UiTooltip>
                </h4>
              </div>
              <span class="governance-reset-slot">
                <UiButton
                  v-if="governanceOverridden('defaultGrantPolicyOverride')"
                  type="text"
                  size="small"
                  :disabled="formDisabled"
                  @click="resetGovernanceField('defaultGrantPolicyOverride')"
                  >重置</UiButton
                >
              </span>
            </header>
            <UiSelect
              :value="draft.defaultGrantPolicyOverride ?? draft.defaultGrantPolicy"
              :disabled="formDisabled"
              :options="[
                { label: '不默认授予', value: 'NONE' },
                { label: '所有登录用户', value: 'ANY_LOGIN_USER' },
                { label: '记录所有者', value: 'OWNER' },
                { label: '办理人', value: 'ASSIGNEE' },
                { label: '成员', value: 'MEMBER' },
              ]"
              @update:value="updateDraft('defaultGrantPolicyOverride', $event)"
            />
          </section>
          <section class="governance-item">
            <header>
              <div>
                <h4>
                  动作授权
                  <UiTooltip title="控制角色是否需要被显式授予该动作。">
                    <button type="button" class="governance-help" aria-label="动作授权说明">
                      <UiIcon name="help" />
                    </button>
                  </UiTooltip>
                </h4>
              </div>
              <span class="governance-reset-slot">
                <UiButton
                  v-if="governanceOverridden('actionAuthOverride')"
                  type="text"
                  size="small"
                  :disabled="formDisabled"
                  @click="resetGovernanceField('actionAuthOverride')"
                  >重置</UiButton
                >
              </span>
            </header>
            <div class="governance-switch-control">
              <UiSwitch
                :checked="(draft.actionAuthOverride ?? draft.actionAuth) !== false"
                :disabled="formDisabled"
                checked-text="开"
                unchecked-text="关"
                @update:checked="updateDraft('actionAuthOverride', $event)"
              />
              <strong>{{
                (draft.actionAuthOverride ?? draft.actionAuth) !== false ? '已启用' : '未启用'
              }}</strong>
            </div>
          </section>
          <section class="governance-item">
            <header>
              <div>
                <h4>
                  数据授权
                  <UiTooltip title="启用后，读取和执行会继续按数据范围校验。">
                    <button type="button" class="governance-help" aria-label="数据授权说明">
                      <UiIcon name="help" />
                    </button>
                  </UiTooltip>
                </h4>
              </div>
              <span class="governance-reset-slot">
                <UiButton
                  v-if="governanceOverridden('dataAuthOverride')"
                  type="text"
                  size="small"
                  :disabled="formDisabled"
                  @click="resetGovernanceField('dataAuthOverride')"
                  >重置</UiButton
                >
              </span>
            </header>
            <div class="governance-switch-control">
              <UiSwitch
                :checked="(draft.dataAuthOverride ?? draft.dataAuth) === true"
                :disabled="formDisabled"
                checked-text="开"
                unchecked-text="关"
                @update:checked="updateDraft('dataAuthOverride', $event)"
              />
              <strong>{{ (draft.dataAuthOverride ?? draft.dataAuth) === true ? '已启用' : '未启用' }}</strong>
            </div>
          </section>
        </div>
      </section>
    </form>
    <form v-else class="static-record-form" @submit.prevent="management.save">
      <p class="form-hint wide-field">
        手工动作只能绑定已部署的二开执行器；平台会通过通用动作接口承接权限、审计和运行态刷新。
      </p>
      <label>
        <span>动作编码</span>
        <UiInput
          :value="draft.actionCode"
          :disabled="formDisabled"
          @update:value="updateDraft('actionCode', $event)"
        />
      </label>
      <label>
        <span>动作名称</span>
        <UiInput :value="draft.title" :disabled="formDisabled" @update:value="updateDraft('title', $event)" />
      </label>
      <label>
        <span>执行层级</span>
        <UiSelect
          :value="draft.actionLevel"
          :disabled="formDisabled"
          :options="actionLevelOptions"
          @update:value="updateDraft('actionLevel', $event)"
        />
      </label>
      <label>
        <span>访问方式</span>
        <UiSelect
          :value="draft.accessMode"
          :disabled="formDisabled"
          :options="[
            { label: '需要授权', value: 'AUTH_REQUIRED' },
            { label: '登录可用', value: 'LOGIN_REQUIRED' },
            { label: '匿名可用', value: 'ANONYMOUS_ALLOWED' },
          ]"
          @update:value="updateDraft('accessMode', $event)"
        />
      </label>
      <label>
        <span>二开执行器</span>
        <UiSelect
          :value="draft.executorKey"
          :disabled="formDisabled"
          :options="executorOptions"
          @update:value="updateExecutor($event)"
        />
      </label>
      <label class="checkbox-field">
        <UiCheckbox
          :checked="draft.actionAuth !== false"
          :disabled="formDisabled"
          @update:checked="updateDraft('actionAuth', $event)"
          >启用动作授权</UiCheckbox
        >
      </label>
      <label class="checkbox-field">
        <UiCheckbox
          :checked="draft.dataAuth === true"
          :disabled="formDisabled"
          @update:checked="updateDraft('dataAuth', $event)"
          >启用数据授权</UiCheckbox
        >
      </label>
      <label>
        <span>默认授予策略</span>
        <UiSelect
          :value="draft.defaultGrantPolicy"
          :disabled="formDisabled"
          :options="[
            { label: '不默认授予', value: 'NONE' },
            { label: '所有登录用户', value: 'ANY_LOGIN_USER' },
            { label: '记录所有者', value: 'OWNER' },
            { label: '办理人', value: 'ASSIGNEE' },
            { label: '成员', value: 'MEMBER' },
          ]"
          @update:value="updateDraft('defaultGrantPolicy', $event)"
        />
      </label>
      <label class="wide-field">
        <span>可用条件表达式</span>
        <UiTextArea
          :value="draft.availableExpression"
          :disabled="formDisabled"
          @update:value="updateDraft('availableExpression', $event)"
        />
      </label>
      <label class="wide-field">
        <span>不可用提示</span>
        <UiTextArea
          :value="draft.unavailableMessage"
          :disabled="formDisabled"
          @update:value="updateDraft('unavailableMessage', $event)"
        />
      </label>
    </form>
    <RecordMetaSection v-if="selected || mode !== 'view'" :record="draft" />
  </StaticManagementLayout>
</template>

<style scoped>
.checkbox-field {
  align-self: start;
}
.wide-field {
  grid-column: 1 / -1;
}
.form-hint {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.managed-action-editor {
  display: grid;
  gap: 24px;
}

.action-declaration-section,
.permission-governance-section {
  display: grid;
  gap: 10px;
}

.action-declaration-section {
  padding-bottom: 2px;
}

.managed-action-editor h3 {
  margin: 0;
  color: var(--muyun-text);
  font-size: 14px;
}

.governance-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 28px;
  row-gap: 20px;
}

.governance-item {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.governance-item header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.governance-item h4 {
  margin: 0;
  color: var(--muyun-text);
  font-size: 13px;
}

.governance-reset-slot {
  display: flex;
  flex: none;
  justify-content: flex-end;
  min-width: 42px;
  min-height: 24px;
}

.governance-help {
  display: inline-flex;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--muyun-text-muted);
  cursor: help;
  font-size: 12px;
  line-height: 1;
  vertical-align: -1px;
}

.governance-switch-control {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
}

.governance-switch-control strong {
  color: var(--muyun-text-body);
  font-size: 13px;
  font-weight: 500;
}

@media (max-width: 680px) {
  .governance-grid {
    grid-template-columns: 1fr;
  }
}
</style>
