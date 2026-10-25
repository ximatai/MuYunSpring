<script setup lang="ts">
import {
  codeOperationErrorMessage,
  useLanguageContext,
  createStaticResourceTreeClient,
  useModuleContext,
} from '@muyun/web-core';
import { computed, onMounted, ref } from 'vue';
import {
  CrudRecordListExplorer,
  ModuleActionButton,
  RecordActionBar,
  RecordPanelButton,
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

const language = useLanguageContext();

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
const sorting = ref(false);
const executorDefinitions = ref<ExecutorDefinition[]>([]);
const management = useFlatCrudManagementState({
  language,
  context: actionContext,
  confirmAction: (options) => confirmAction(options, language),
  emptyDraft: () => emptyActionDraft(props.moduleAlias),
  normalizeDraft: (record) => normalizeActionDraft(record, props.moduleAlias),
  copyRecord: (record) => ({ ...record }),
  titleOf: actionTitleOf,
  get fallbackTitle() {
    return language.t('platform.unsaved-source.ModuleActionManagementView', '模块动作');
  },
  get createTitle() {
    return language.t('platform.screen.ModuleActionManagementView.new-action', '新建动作');
  },
  get requiredMessage() {
    return codeOperationErrorMessage(
      language,
      'platform.screen.ModuleActionManagementView.the-action-code-is-required',
      '动作编码不能为空',
    );
  },
  isValid: (record) => Boolean(record.actionCode?.trim()),
  get recordName() {
    return language.t('platform.governance.tab.actions', '动作');
  },
  get deleteTitle() {
    return language.t('platform.screen.ModuleActionManagementView.delete-action', '删除动作');
  },
  get saveDeniedMessage() {
    return codeOperationErrorMessage(
      language,
      'platform.screen.ModuleActionManagementView.you-do-not-have-permission-to-save-module-actions',
      '当前用户无权保存模块动作',
    );
  },
  get createDeniedMessage() {
    return codeOperationErrorMessage(
      language,
      'platform.screen.ModuleActionManagementView.you-do-not-have-permission-to-create-module-actions',
      '当前用户无权新建模块动作',
    );
  },
  get enableDeniedMessage() {
    return codeOperationErrorMessage(
      language,
      'platform.screen.ModuleActionManagementView.you-do-not-have-permission-to-enable-or-disable-module',
      '当前用户无权变更模块动作启停状态',
    );
  },
  deleteDeniedMessage: () =>
    codeOperationErrorMessage(
      language,
      'platform.screen.ModuleActionManagementView.you-do-not-have-permission-to-delete-module-actions',
      '当前用户无权删除模块动作',
    ),
  canDeleteRecord: (record) => record.systemManaged !== true,
  canEnableRecord: (record) => record.systemManaged !== true,
});
const { selected, draft, mode, reloadKey, saving, cardTitle, canCreate, canEnable } = management;
const hasUnsavedChanges = computed(() => {
  if (mode.value === 'view') return false;
  const baseline = selected.value
    ? normalizeActionDraft(selected.value, props.moduleAlias)
    : emptyActionDraft(props.moduleAlias);
  return JSON.stringify(draft.value) !== JSON.stringify(baseline);
});
useWorkspaceViewUnsavedState(
  () => language.t('platform.unsaved-source.ModuleActionManagementView', '模块动作'),
  () => hasUnsavedChanges.value,
);
const canSort = computed(() => actionContext.can('sort') === true);
const sortDisabled = computed(
  () => mode.value !== 'view' || saving.value || Boolean(searchKeyword.value.trim()),
);
const canCreateManualAction = computed(() => canCreate.value);
const executorOptions = computed(() =>
  executorDefinitions.value.map((executor) => ({
    value: executor.executorKey,
    label: executor.description ? `${executor.title} · ${executor.description}` : executor.title,
  })),
);
const actionLevelOptions = computed(() => {
  const executor = executorDefinitions.value.find((item) => item.executorKey === draft.value.executorKey);
  const levels = executor?.supportedLevels ?? ['LIST', 'RECORD', 'BATCH', 'ANY'];
  return levels.map((value) => ({
    value,
    label: {
      LIST: language.t('platform.page.composition.list', '列表'),
      RECORD: language.t('platform.screen.ModuleActionManagementView.single-record', '单条记录'),
      BATCH: language.t('platform.screen.ModuleActionManagementView.batch', '批量'),
      ANY: language.t('platform.screen.ModuleActionManagementView.any', '任意'),
    }[value],
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
              title: language.t('platform.screen.ModuleActionManagementView.reset-all', '全部重置'),
              disabled: management.saving.value,
            },
          ]
        : []),
      {
        key: 'cancel',
        title: language.t('platform.action.cancel', '取消'),
        disabled: management.saving.value,
      },
      {
        key: 'save',
        actionCode: management.mode.value === 'create' ? 'create' : 'update',
        title: management.saving.value
          ? language.t('platform.action.saving', '保存中')
          : language.t('platform.action.save', '保存'),
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
      title: language.t('platform.action.edit', '编辑'),
      disabled: !selected || !management.canUpdate.value,
    },
    {
      key: 'delete',
      actionCode: 'delete',
      title: language.t('platform.action.delete', '删除'),
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
    tag:
      action.systemManaged === true
        ? language.t('platform.screen.pageCompositionSession.platform-managed', '平台托管')
        : !action.executorKey
          ? language.t('platform.screen.PageCompositionTree.binding-pending', '待绑定')
          : language.t('platform.screen.RoleAccountGrantDrawer.assigned', '已绑定'),
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
    presentPlatformError(cause, { language, source: 'module-action-management', phase: 'load' });
  }
}

function startCreate() {
  management.startCreate();
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
  return (
    action.title?.trim() ||
    action.actionCode?.trim() ||
    action.id ||
    language.t('platform.screen.ModuleActionManagementView.untitled-action', '未命名动作')
  );
}

function actionDetailDisplayValue(fieldName: string, value: unknown) {
  const labels: Record<string, Record<string, string>> = {
    category: {
      STANDARD: language.t('platform.screen.RoleAuthorizationView.standard-action', '标准动作'),
      CUSTOM: language.t('platform.screen.ModuleActionManagementView.custom-action', '自定义动作'),
      DIALOG: language.t('platform.screen.ModuleActionManagementView.dialog-action', '弹窗动作'),
      WORKFLOW: language.t('platform.screen.ModuleActionManagementView.workflow-action', '工作流动作'),
      GENERATE: language.t('platform.screen.ModuleActionManagementView.generation-action', '生成动作'),
    },
    actionLevel: {
      LIST: language.t('platform.page.composition.list', '列表'),
      RECORD: language.t('platform.screen.ModuleActionManagementView.single-record', '单条记录'),
      BATCH: language.t('platform.screen.ModuleActionManagementView.batch', '批量'),
      ANY: language.t('platform.screen.ModuleActionManagementView.any', '任意'),
    },
    accessMode: {
      AUTH_REQUIRED: language.t(
        'platform.screen.ModuleActionManagementView.authorization-required',
        '需要授权',
      ),
      LOGIN_REQUIRED: language.t('platform.screen.ModuleActionManagementView.signed-in-access', '登录可用'),
      ANONYMOUS_ALLOWED: language.t(
        'platform.screen.ModuleActionManagementView.anonymous-access',
        '匿名可用',
      ),
    },
    executorType: {
      STANDARD: language.t('platform.screen.ModuleActionManagementView.platform-standard', '平台标准'),
      SERVICE: language.t('platform.screen.ModuleActionManagementView.custom-service', '服务（二开）'),
      DIALOG: language.t('platform.screen.ModuleActionManagementView.dialog-protocol', '弹窗协议'),
      WORKFLOW: language.t('platform.screen.ModuleActionManagementView.workflow', '工作流'),
      GENERATE: language.t('platform.screen.ModuleActionManagementView.generator', '生成器'),
    },
    defaultGrantPolicy: {
      NONE: language.t('platform.screen.ModuleActionManagementView.no-default-grant', '不默认授予'),
      ANY_LOGIN_USER: language.t(
        'platform.screen.ModuleActionManagementView.all-signed-in-users',
        '所有登录用户',
      ),
      OWNER: language.t('platform.screen.ModuleActionManagementView.record-owner', '记录所有者'),
      ASSIGNEE: language.t('platform.screen.WorkflowWorkbenchView.processors', '办理人'),
      MEMBER: language.t('platform.screen.ModuleActionManagementView.member', '成员'),
    },
    sourceType: {
      STATIC_MODULE: language.t(
        'platform.screen.ModuleActionManagementView.static-module-declaration',
        '静态模块声明',
      ),
      CODE_EXTENSION: language.t('platform.screen.ModuleActionManagementView.code-extension', '代码扩展'),
      DYNAMIC_MODULE: language.t(
        'platform.screen.ModuleActionManagementView.dynamic-module-configuration',
        '动态模块配置',
      ),
      WORKFLOW_DEFINITION: language.t(
        'platform.screen.ModuleActionManagementView.workflow-definition',
        '工作流定义',
      ),
      WORKFLOW_RUNTIME: language.t(
        'platform.screen.ModuleActionManagementView.workflow-runtime',
        '工作流运行时',
      ),
      RECORD_GENERATION_RULE: language.t(
        'platform.screen.ModuleActionManagementView.document-generation-rule',
        '生单规则',
      ),
      DOCUMENT_RELATION: language.t(
        'platform.screen.ModuleActionManagementView.document-relation',
        '单据关系',
      ),
      PRINT_TEMPLATE: language.t('platform.screen.ModuleActionManagementView.print-template', '打印模板'),
    },
  };
  if (fieldName === 'actionAuth')
    return value === true
      ? language.t('platform.screen.WorkflowWorkbenchView.enabled', '启用')
      : language.t('platform.screen.ModuleActionManagementView.not-enabled', '未启用');
  if (fieldName === 'dataAuth')
    return value === true
      ? language.t('platform.screen.WorkflowWorkbenchView.enabled', '启用')
      : language.t('platform.screen.ModuleActionManagementView.not-enabled', '未启用');
  return typeof value === 'string' ? labels[fieldName]?.[value] : undefined;
}
</script>

<template>
  <StaticManagementLayout
    :explorer-title="language.t('platform.unsaved-source.ModuleActionManagementView', '模块动作')"
    :refresh-title="language.t('platform.screen.ModuleActionManagementView.refresh-actions', '刷新动作')"
    :mode="mode"
    :detail-title="cardTitle"
    :explorer-search-keyword="searchKeyword"
    :explorer-search-placeholder="
      language.t(
        'platform.screen.ModuleActionManagementView.search-action-name-or-code',
        '搜索动作名称或编码',
      )
    "
    @update:explorer-search-keyword="searchKeyword = $event"
    @refresh="reloadKey += 1"
  >
    <template #explorer-actions>
      <RecordPanelButton
        v-if="canSort"
        icon-name="swap-vertical"
        icon-only
        size="small"
        type="text"
        :selected="sorting"
        :disabled="sortDisabled"
        :title="
          searchKeyword.trim()
            ? language.t(
                'platform.screen.ModulePageHostRuntime.clear-the-search-to-reorder-records',
                '清空搜索后可调整排序',
              )
            : mode !== 'view'
              ? language.t(
                  'platform.screen.ModuleActionManagementView.finish-editing-before-reordering',
                  '结束编辑后可调整排序',
                )
              : sorting
                ? language.t('platform.screen.ModulePageHostRuntime.finish-reordering', '结束排序')
                : language.t('platform.screen.ModulePageHostRuntime.reorder', '调整排序')
        "
        :aria-label="
          sorting
            ? language.t('platform.screen.ModulePageHostRuntime.finish-reordering', '结束排序')
            : language.t('platform.screen.ModulePageHostRuntime.reorder', '调整排序')
        "
        @click="sorting = !sorting"
      />
      <ModuleActionButton
        class="record-panel-create-button"
        :context="actionContext"
        action-code="create"
        :title="language.t('platform.screen.ModuleActionManagementView.new-action', '新建动作')"
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
        :sorting="sorting && canSort && !sortDisabled"
        :empty-description="
          language.t(
            'platform.screen.ModuleActionManagementView.no-actions-are-available-for-this-module',
            '当前模块暂无动作',
          )
        "
        :loading-tip="
          language.t('platform.screen.RoleAuthorizationView.loading-module-actions', '加载模块动作')
        "
        :fallback-title="
          language.t('platform.screen.ModuleActionManagementView.untitled-action', '未命名动作')
        "
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
      :description="
        moduleKind === 'static'
          ? language.t(
              'platform.screen.ModuleActionManagementView.static-module-actions-are-registered-automatically-from-java-declarations',
              '静态模块动作由 Java 声明自动注册',
            )
          : language.t(
              'platform.screen.ModuleActionManagementView.no-actions-are-available-for-this-module',
              '当前模块暂无动作',
            )
      "
    />
    <template v-else-if="mode === 'view' && selected">
      <RecordDetailFields
        :record="draft as RecordFormRecord"
        :display-of="actionDetailDisplayValue"
        :field-names="actionDetailFieldNames"
        :fallback="{
          actionCode: {
            label: language.t('platform.screen.ModuleActionManagementView.action-code', '动作编码'),
          },
          title: { label: language.t('platform.screen.ModuleActionManagementView.action-name', '动作名称') },
          category: {
            label: language.t('platform.screen.ModuleActionManagementView.action-category', '动作类别'),
          },
          actionLevel: {
            label: language.t('platform.screen.ModuleActionManagementView.execution-level', '执行层级'),
          },
          accessMode: {
            label: language.t('platform.screen.ModuleActionManagementView.access-mode', '访问方式'),
          },
          executorType: {
            label: language.t('platform.screen.ModuleActionManagementView.execution-method', '执行方式'),
          },
          executorKey: {
            label: language.t(
              'platform.screen.ModuleActionManagementView.executor-implementation',
              '执行实现',
            ),
          },
          actionAuth: {
            label: language.t('platform.screen.ModuleActionManagementView.action-authorization', '动作授权'),
          },
          dataAuth: {
            label: language.t('platform.screen.ModuleActionManagementView.data-authorization', '数据授权'),
          },
          defaultGrantPolicy: {
            label: language.t(
              'platform.screen.ModuleActionManagementView.default-grant-policy',
              '默认授予策略',
            ),
          },
          availableExpression: {
            label: language.t(
              'platform.screen.ModuleActionManagementView.availability-condition',
              '可用条件',
            ),
          },
          unavailableMessage: {
            label: language.t('platform.screen.ModuleActionManagementView.unavailable-message', '不可用提示'),
          },
          sourceType: {
            label: language.t('platform.screen.ModuleActionManagementView.registration-source', '注册来源'),
          },
          bindingAlias: {
            label: language.t('platform.screen.ModuleActionManagementView.binding-id', '绑定标识'),
          },
        }"
      />
    </template>
    <form
      v-else-if="mode !== 'create' && selected?.systemManaged"
      class="managed-action-editor"
      @submit.prevent="management.save"
    >
      <section class="action-declaration-section">
        <h3>{{ language.t('platform.screen.ModuleActionManagementView.action-declaration', '动作声明') }}</h3>
        <RecordDetailFields
          :record="draft as RecordFormRecord"
          :display-of="actionDetailDisplayValue"
          :field-names="actionDeclarationFieldNames"
          :fallback="{
            actionCode: {
              label: language.t('platform.screen.ModuleActionManagementView.action-code', '动作编码'),
            },
            title: {
              label: language.t('platform.screen.ModuleActionManagementView.action-name', '动作名称'),
            },
            category: {
              label: language.t('platform.screen.ModuleActionManagementView.action-category', '动作类别'),
            },
            actionLevel: {
              label: language.t('platform.screen.ModuleActionManagementView.execution-level', '执行层级'),
            },
            executorType: {
              label: language.t('platform.screen.ModuleActionManagementView.execution-method', '执行方式'),
            },
            executorKey: {
              label: language.t(
                'platform.screen.ModuleActionManagementView.executor-implementation',
                '执行实现',
              ),
            },
            sourceType: {
              label: language.t('platform.screen.ModuleActionManagementView.registration-source', '注册来源'),
            },
          }"
        />
      </section>
      <section class="permission-governance-section">
        <h3>
          {{ language.t('platform.screen.ModuleActionManagementView.permission-governance', '权限治理') }}
        </h3>
        <label v-if="moduleKind === 'dynamic'" class="checkbox-field">
          <UiCheckbox
            :checked="draft.schedulable === true"
            :disabled="formDisabled || draft.actionLevel !== 'LIST' || draft.formSupported === true"
            @update:checked="updateDraft('schedulable', $event)"
            >{{
              language.t(
                'platform.screen.ModuleActionManagementView.schedulable',
                '允许定时调用（无需表单的模块动作）',
              )
            }}</UiCheckbox
          >
        </label>
        <div class="governance-grid">
          <section class="governance-item">
            <header>
              <div>
                <h4>
                  {{ language.t('platform.screen.ModuleActionManagementView.access-mode', '访问方式') }}
                  <UiTooltip
                    :title="
                      language.t(
                        'platform.screen.ModuleActionManagementView.define-the-identity-requirements-for-invoking-this-action',
                        '决定调用动作前需要满足的身份条件。',
                      )
                    "
                  >
                    <button
                      type="button"
                      class="governance-help"
                      :aria-label="
                        language.t(
                          'platform.screen.ModuleActionManagementView.access-method-description',
                          '访问方式说明',
                        )
                      "
                    >
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
                  >{{ language.t('platform.screen.WorkflowWorkbenchView.reset', '重置') }}</UiButton
                >
              </span>
            </header>
            <UiSelect
              :value="draft.accessModeOverride ?? draft.accessMode"
              :disabled="formDisabled"
              :options="[
                {
                  label: language.t(
                    'platform.screen.ModuleActionManagementView.authorization-required',
                    '需要授权',
                  ),
                  value: 'AUTH_REQUIRED',
                },
                {
                  label: language.t(
                    'platform.screen.ModuleActionManagementView.signed-in-access',
                    '登录可用',
                  ),
                  value: 'LOGIN_REQUIRED',
                },
                {
                  label: language.t(
                    'platform.screen.ModuleActionManagementView.anonymous-access',
                    '匿名可用',
                  ),
                  value: 'ANONYMOUS_ALLOWED',
                },
              ]"
              @update:value="updateDraft('accessModeOverride', $event)"
            />
          </section>
          <section class="governance-item">
            <header>
              <div>
                <h4>
                  {{
                    language.t(
                      'platform.screen.ModuleActionManagementView.default-grant-policy',
                      '默认授予策略',
                    )
                  }}
                  <UiTooltip
                    :title="
                      language.t(
                        'platform.screen.ModuleActionManagementView.define-the-default-grant-rules-when-eligible-users-first-receive',
                        '定义满足条件的用户首次获得动作时的默认规则。',
                      )
                    "
                  >
                    <button
                      type="button"
                      class="governance-help"
                      :aria-label="
                        language.t(
                          'platform.screen.ModuleActionManagementView.default-grant-policy-description',
                          '默认授予策略说明',
                        )
                      "
                    >
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
                  >{{ language.t('platform.screen.WorkflowWorkbenchView.reset', '重置') }}</UiButton
                >
              </span>
            </header>
            <UiSelect
              :value="draft.defaultGrantPolicyOverride ?? draft.defaultGrantPolicy"
              :disabled="formDisabled"
              :options="[
                {
                  label: language.t(
                    'platform.screen.ModuleActionManagementView.no-default-grant',
                    '不默认授予',
                  ),
                  value: 'NONE',
                },
                {
                  label: language.t(
                    'platform.screen.ModuleActionManagementView.all-signed-in-users',
                    '所有登录用户',
                  ),
                  value: 'ANY_LOGIN_USER',
                },
                {
                  label: language.t('platform.screen.ModuleActionManagementView.record-owner', '记录所有者'),
                  value: 'OWNER',
                },
                {
                  label: language.t('platform.screen.WorkflowWorkbenchView.processors', '办理人'),
                  value: 'ASSIGNEE',
                },
                {
                  label: language.t('platform.screen.ModuleActionManagementView.member', '成员'),
                  value: 'MEMBER',
                },
              ]"
              @update:value="updateDraft('defaultGrantPolicyOverride', $event)"
            />
          </section>
          <section class="governance-item">
            <header>
              <div>
                <h4>
                  {{
                    language.t('platform.screen.ModuleActionManagementView.action-authorization', '动作授权')
                  }}
                  <UiTooltip
                    :title="
                      language.t(
                        'platform.screen.ModuleActionManagementView.control-whether-roles-require-an-explicit-grant-for-this-action',
                        '控制角色是否需要被显式授予该动作。',
                      )
                    "
                  >
                    <button
                      type="button"
                      class="governance-help"
                      :aria-label="
                        language.t(
                          'platform.screen.ModuleActionManagementView.action-permission-description',
                          '动作授权说明',
                        )
                      "
                    >
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
                  >{{ language.t('platform.screen.WorkflowWorkbenchView.reset', '重置') }}</UiButton
                >
              </span>
            </header>
            <div class="governance-switch-control">
              <UiSwitch
                :checked="(draft.actionAuthOverride ?? draft.actionAuth) !== false"
                :disabled="formDisabled"
                :checked-text="language.t('platform.screen.ModuleActionManagementView.on', '开')"
                :unchecked-text="language.t('platform.screen.ModuleActionManagementView.off', '关')"
                @update:checked="updateDraft('actionAuthOverride', $event)"
              />
              <strong>{{
                (draft.actionAuthOverride ?? draft.actionAuth) !== false
                  ? language.t('platform.screen.DynamicModuleWorkspaceDetailView.enabled', '已启用')
                  : language.t('platform.screen.ModuleActionManagementView.not-enabled', '未启用')
              }}</strong>
            </div>
          </section>
          <section class="governance-item">
            <header>
              <div>
                <h4>
                  {{
                    language.t('platform.screen.ModuleActionManagementView.data-authorization', '数据授权')
                  }}
                  <UiTooltip
                    :title="
                      language.t(
                        'platform.screen.ModuleActionManagementView.when-enabled-reads-and-execution-are-checked-against-the-data',
                        '启用后，读取和执行会继续按数据范围校验。',
                      )
                    "
                  >
                    <button
                      type="button"
                      class="governance-help"
                      :aria-label="
                        language.t(
                          'platform.screen.ModuleActionManagementView.data-permission-description',
                          '数据授权说明',
                        )
                      "
                    >
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
                  >{{ language.t('platform.screen.WorkflowWorkbenchView.reset', '重置') }}</UiButton
                >
              </span>
            </header>
            <div class="governance-switch-control">
              <UiSwitch
                :checked="(draft.dataAuthOverride ?? draft.dataAuth) === true"
                :disabled="formDisabled"
                :checked-text="language.t('platform.screen.ModuleActionManagementView.on', '开')"
                :unchecked-text="language.t('platform.screen.ModuleActionManagementView.off', '关')"
                @update:checked="updateDraft('dataAuthOverride', $event)"
              />
              <strong>{{
                (draft.dataAuthOverride ?? draft.dataAuth) === true
                  ? language.t('platform.screen.DynamicModuleWorkspaceDetailView.enabled', '已启用')
                  : language.t('platform.screen.ModuleActionManagementView.not-enabled', '未启用')
              }}</strong>
            </div>
          </section>
        </div>
      </section>
    </form>
    <form v-else class="static-record-form" @submit.prevent="management.save">
      <p class="form-hint wide-field">
        {{
          language.t(
            'platform.screen.ModuleActionManagementView.you-can-save-an-action-before-binding-its-executor-unbound',
            '可以先保存动作，再绑定执行能力。待绑定动作不可执行，页面引用后需完成绑定才能发布。',
          )
        }}
      </p>
      <label>
        <span>{{ language.t('platform.screen.ModuleActionManagementView.action-code', '动作编码') }}</span>
        <UiInput
          :value="draft.actionCode"
          :disabled="formDisabled"
          @update:value="updateDraft('actionCode', $event)"
        />
      </label>
      <label>
        <span>{{ language.t('platform.screen.ModuleActionManagementView.action-name', '动作名称') }}</span>
        <UiInput :value="draft.title" :disabled="formDisabled" @update:value="updateDraft('title', $event)" />
      </label>
      <label>
        <span>{{
          language.t('platform.screen.ModuleActionManagementView.execution-level', '执行层级')
        }}</span>
        <UiSelect
          :value="draft.actionLevel"
          :disabled="formDisabled"
          :options="actionLevelOptions"
          @update:value="updateDraft('actionLevel', $event)"
        />
      </label>
      <label>
        <span>{{ language.t('platform.screen.ModuleActionManagementView.access-mode', '访问方式') }}</span>
        <UiSelect
          :value="draft.accessMode"
          :disabled="formDisabled"
          :options="[
            {
              label: language.t(
                'platform.screen.ModuleActionManagementView.authorization-required',
                '需要授权',
              ),
              value: 'AUTH_REQUIRED',
            },
            {
              label: language.t('platform.screen.ModuleActionManagementView.signed-in-access', '登录可用'),
              value: 'LOGIN_REQUIRED',
            },
            {
              label: language.t('platform.screen.ModuleActionManagementView.anonymous-access', '匿名可用'),
              value: 'ANONYMOUS_ALLOWED',
            },
          ]"
          @update:value="updateDraft('accessMode', $event)"
        />
      </label>
      <label v-if="moduleKind === 'dynamic'">
        <span>{{
          language.t(
            'platform.screen.ModuleActionManagementView.executor-can-be-bound-later',
            '执行能力（可稍后绑定）',
          )
        }}</span>
        <UiSelect
          :value="draft.executorKey"
          allow-clear
          :placeholder="language.t('platform.screen.PageCompositionTree.binding-pending', '待绑定')"
          :disabled="formDisabled"
          :options="executorOptions"
          @update:value="updateExecutor($event)"
        />
      </label>
      <p v-if="moduleKind !== 'dynamic'" class="form-hint wide-field">
        {{
          language.t(
            'platform.screen.ModuleActionManagementView.static-modules-bind-an-executor-through-a-code-action-declaration',
            '静态模块通过同编码的代码动作声明绑定执行能力。',
          )
        }}
      </p>
      <label v-if="moduleKind === 'dynamic'" class="checkbox-field">
        <UiCheckbox
          :checked="draft.schedulable === true"
          :disabled="formDisabled || draft.actionLevel !== 'LIST' || draft.formSupported === true"
          @update:checked="updateDraft('schedulable', $event)"
          >{{
            language.t(
              'platform.screen.ModuleActionManagementView.schedulable',
              '允许定时调用（无需表单的模块动作）',
            )
          }}</UiCheckbox
        >
      </label>
      <label class="checkbox-field">
        <UiCheckbox
          :checked="draft.actionAuth !== false"
          :disabled="formDisabled"
          @update:checked="updateDraft('actionAuth', $event)"
          >{{
            language.t('platform.screen.ModuleActionManagementView.enable-action-permissions', '启用动作授权')
          }}</UiCheckbox
        >
      </label>
      <label class="checkbox-field">
        <UiCheckbox
          :checked="draft.dataAuth === true"
          :disabled="formDisabled"
          @update:checked="updateDraft('dataAuth', $event)"
          >{{
            language.t('platform.screen.ModuleActionManagementView.enable-data-permissions', '启用数据授权')
          }}</UiCheckbox
        >
      </label>
      <label>
        <span>{{
          language.t('platform.screen.ModuleActionManagementView.default-grant-policy', '默认授予策略')
        }}</span>
        <UiSelect
          :value="draft.defaultGrantPolicy"
          :disabled="formDisabled"
          :options="[
            {
              label: language.t('platform.screen.ModuleActionManagementView.no-default-grant', '不默认授予'),
              value: 'NONE',
            },
            {
              label: language.t(
                'platform.screen.ModuleActionManagementView.all-signed-in-users',
                '所有登录用户',
              ),
              value: 'ANY_LOGIN_USER',
            },
            {
              label: language.t('platform.screen.ModuleActionManagementView.record-owner', '记录所有者'),
              value: 'OWNER',
            },
            {
              label: language.t('platform.screen.WorkflowWorkbenchView.processors', '办理人'),
              value: 'ASSIGNEE',
            },
            {
              label: language.t('platform.screen.ModuleActionManagementView.member', '成员'),
              value: 'MEMBER',
            },
          ]"
          @update:value="updateDraft('defaultGrantPolicy', $event)"
        />
      </label>
      <label class="wide-field">
        <span>{{
          language.t('platform.screen.ModuleActionManagementView.availability-expression', '可用条件表达式')
        }}</span>
        <UiTextArea
          :value="draft.availableExpression"
          :disabled="formDisabled"
          @update:value="updateDraft('availableExpression', $event)"
        />
      </label>
      <label class="wide-field">
        <span>{{
          language.t('platform.screen.ModuleActionManagementView.unavailable-message', '不可用提示')
        }}</span>
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
