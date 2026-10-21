<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';
import type { WorkflowDefinition } from '@muyun/web-contracts';
import { normalizeError, userFacingErrorMessage } from '@muyun/web-core';
import {
  createWorkspaceViewDescriptor,
  useWorkbenchNavigation,
  useWorkspaceViewUnsavedState,
} from '@muyun/platform-workbench';
import {
  RecordDetailDrawer,
  DrawerTitleActions,
  type DrawerTitleAction,
  RecordQueryListSurface,
  RecordQueryListCell,
  RecordPanelButton,
  presentPlatformError,
  workflowTitle,
} from '@muyun/platform-components';
import {
  UiActionButton,
  UiButton,
  UiError,
  UiSwitch,
  UiTooltip,
  type UiDataTableColumn,
  type UiDataTableRecord,
  confirmAction,
} from '@muyun/vue-ui-antdv';
import { useWorkflowConfigurationContext } from '../../views/useWorkflowConfigurationContext';
import WorkflowDefinitionSettings from '../../views/WorkflowDefinitionSettings.vue';
import { workflowConfigurationWorkspaceView } from '../../views/workflowConfigurationWorkspaceView';
import { usePageDataChange } from '../pageRealtime';

const props = defineProps<{ context: ModulePageDrawerContext }>();
const moduleAlias = String(props.context.record?.alias ?? props.context.record?.id ?? '');
const moduleTitle = String(props.context.record?.title ?? moduleAlias);
const configuration = useWorkflowConfigurationContext(moduleAlias);
const { client, context: definitionContext, language } = configuration;
const navigation = useWorkbenchNavigation();
const definitions = ref<WorkflowDefinition[]>([]);
const selected = ref<WorkflowDefinition>();
const editing = ref(false),
  creating = ref(false),
  busy = ref(false),
  dirty = ref(false);
const keyword = ref(''),
  sorting = ref(false),
  error = ref<() => string>();
const title = ref(''),
  approvalEnabled = ref(true),
  organizationId = ref(''),
  matchExpression = ref(''),
  fallback = ref(false);
const canSort = computed(() => definitionContext.can?.('sort') === true);
const valid = computed(() => !!title.value.trim());
let alive = true;
onBeforeUnmount(() => {
  alive = false;
});

useWorkspaceViewUnsavedState(
  () => language.t('platform.screen.WorkflowConfigurationView.approval-workflow', '审批流程'),
  () => dirty.value,
  () => busy.value,
);
watch(
  [title, approvalEnabled, organizationId, matchExpression, fallback],
  () => {
    if (editing.value) dirty.value = true;
  },
  { flush: 'sync' },
);
const editorTitle = computed(() =>
  language.t(
    creating.value
      ? 'platform.screen.WorkflowConfigurationView.new-workflow'
      : 'platform.workflow-manager.edit',
    creating.value ? '新建流程' : '流程设置',
  ),
);
const editorActions = computed<DrawerTitleAction[]>(() => [
  { key: 'cancel', label: language.t('platform.action.cancel', '取消'), disabled: busy.value, run: cancel },
  {
    key: 'save',
    label: language.t(
      creating.value
        ? 'platform.screen.WorkflowConfigurationView.create-workflow-draft'
        : 'platform.action.save',
      creating.value ? '创建流程草稿' : '保存',
    ),
    emphasis: 'primary',
    disabled: busy.value || !valid.value,
    loading: busy.value,
    run: save,
  },
]);
props.context.setSubtitle(moduleTitle);
props.context.setOperation(undefined);
watch(busy, () => props.context.setCloseBlocked(busy.value), { immediate: true });
async function run(operation: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = undefined;
  try {
    await operation();
  } catch (cause) {
    if (alive) error.value = () => userFacingErrorMessage(normalizeError(cause), language);
  } finally {
    if (alive) busy.value = false;
  }
}
async function reload() {
  if (editing.value || busy.value) return;
  await run(async () => {
    const records = await client.query();
    if (alive) {
      definitions.value = records;
      await loadAvailability(records);
    }
  });
}
async function loadAvailability(records: WorkflowDefinition[]) {
  definitionContext.invalidateRecordActions?.(records.map((record) => record.id));
  if (definitionContext.recordActionsBatch)
    await definitionContext.recordActionsBatch(records.map((record) => record.id));
  else if (definitionContext.recordActions)
    await Promise.all(records.map((record) => definitionContext.recordActions(record.id)));
}
onMounted(() => {
  void reload();
});
onActivated(() => {
  void reload();
});
usePageDataChange({
  moduleAlias: 'platform.workflow.definition',
  handler: async () => {
    await reload();
  },
});
function edit(record?: WorkflowDefinition) {
  if (busy.value || editing.value) return;
  sorting.value = false;
  selected.value = record;
  title.value = record?.title ?? '';
  approvalEnabled.value = record?.approvalEnabled ?? true;
  organizationId.value = record?.organizationId ?? '';
  matchExpression.value = record?.matchExpression ?? '';
  fallback.value = record?.defaultDefinition ?? false;
  creating.value = !record;
  dirty.value = false;
  editing.value = true;
}
async function confirmDiscard() {
  if (busy.value) return false;
  if (
    dirty.value &&
    !(await confirmAction(
      {
        title: language.t('platform.workflow-config.discard-confirm', '放弃未保存的流程设置？'),
        danger: true,
        okText: language.t('platform.workflow-config.discard', '放弃修改'),
      },
      language,
    ))
  )
    return false;
  dirty.value = false;
  return true;
}
props.context.setBeforeClose(confirmDiscard);
async function cancel() {
  if (!(await confirmDiscard())) return;
  editing.value = false;
  await reload();
}
async function save() {
  if (!editing.value || !valid.value) return;
  await run(async () => {
    const settings = {
      title: title.value.trim(),
      organizationId: organizationId.value || null,
      matchExpression: matchExpression.value || null,
      defaultDefinition: fallback.value,
    };
    const saved = creating.value
      ? await client.create({
          ...settings,
          approvalEnabled: approvalEnabled.value,
          enabled: true,
        })
      : await client.saveSelection(selected.value!.id, { ...settings, version: selected.value!.version });
    if (!alive) return;
    // Commit the created identity locally before any follow-up read, so a failed refresh cannot repeat insert.
    definitions.value = creating.value
      ? [...definitions.value, saved]
      : definitions.value.map((item) => (item.id === saved.id ? saved : item));
    selected.value = saved;
    editing.value = false;
    dirty.value = false;
    keyword.value = '';
    props.context.refreshDetailExtensions();
    await loadAvailability([saved]);
  });
}
function openDesign(record: WorkflowDefinition) {
  if (busy.value || editing.value) return;
  if (!navigation) {
    presentPlatformError(
      new Error(language.t('platform.workflow-manager.navigation-unavailable', '工作台导航暂不可用')),
      { source: 'workflow-design', phase: 'action' },
    );
    return;
  }
  navigation.openPage(
    createWorkspaceViewDescriptor(workflowConfigurationWorkspaceView, {
      moduleAlias,
      moduleTitle,
      definitionId: record.id,
      definitionTitle: record.title ?? record.alias ?? record.id,
    }),
  );
}
const columns = computed<UiDataTableColumn[]>(() => [
  {
    key: 'title',
    title: language.t('platform.screen.WorkflowConfigurationView.workflow-name', '流程名称'),
    width: 150,
  },
  { key: 'definitionStatus', title: language.t('platform.workflow-manager.state', '流程状态'), width: 85 },
  {
    key: 'currentVersionNo',
    title: language.t('platform.workflow-manager.published-version', '发布版本'),
    width: 75,
  },
  {
    key: 'matchExpression',
    title: language.t('platform.screen.WorkflowSelectionFields.match-condition', '匹配条件'),
    width: 120,
  },
  { key: 'active', title: language.t('platform.workflow-manager.active', '启用状态'), width: 90 },
]);
const rows = computed<UiDataTableRecord[]>(() =>
  definitions.value
    .filter(
      (record) =>
        !keyword.value.trim() ||
        [record.title, record.matchExpression, workflowTitle(record.definitionStatus, language)].some(
          (value) =>
            String(value ?? '')
              .toLocaleLowerCase()
              .includes(keyword.value.trim().toLocaleLowerCase()),
        ),
    )
    .map((record) => ({ ...record })),
);
function canChangeStatus(record: UiDataTableRecord) {
  return (
    record.definitionStatus === 'published' ||
    (record.definitionStatus === 'disabled' && record.currentVersionNo != null)
  );
}
function canRun(actionCode: string, record: UiDataTableRecord) {
  return !busy.value && !editing.value && definitionContext.can?.(actionCode, String(record.id)) === true;
}
function sortRows(event: {
  source: UiDataTableRecord;
  target: UiDataTableRecord;
  position: 'before' | 'after';
}) {
  if (event.source.tenantId !== event.target.tenantId) return;
  return sort({
    dragRecord: event.source,
    dropRecord: event.target,
    position: event.position === 'before' ? -1 : 1,
  });
}
async function rowAction(command: string, item: { id?: unknown }) {
  const record = definitions.value.find((record) => record.id === item.id);
  if (!record) return;
  if (busy.value || editing.value) return;
  if (command === 'design') {
    openDesign(record);
    return;
  }
  if (command === 'settings') {
    if (!canRun('configureWorkflowSelection', record)) return;
    edit(record);
    return;
  }
  if (command !== 'enable' && command !== 'disable' && command !== 'archive') return;
  if (!canRun(`${command}WorkflowDefinition`, record)) return;
  if (command === 'enable' && !canChangeStatus(record)) return;
  if (
    !(await confirmAction(
      {
        title: language.t(
          command === 'archive'
            ? 'platform.workflow-config.archive-confirm'
            : command === 'enable'
              ? 'platform.workflow-config.enable-confirm'
              : 'platform.workflow-config.disable-confirm',
          command === 'archive' ? '归档此流程？' : command === 'enable' ? '启用此流程？' : '停用此流程？',
        ),
        content: language.t(
          'platform.workflow-config.future-only',
          '此操作影响后续新申请，已有实例继续使用冻结版本。',
        ),
        danger: command !== 'enable',
      },
      language,
    ))
  )
    return;
  await run(async () => {
    const saved = await client.changeStatus(record.id, command, record.version);
    if (alive) {
      definitions.value = definitions.value.map((item) => (item.id === saved.id ? saved : item));
      await loadAvailability([saved]);
    }
  });
}
async function sort(event: { dragRecord: { id?: unknown }; dropRecord: { id?: unknown }; position: -1 | 1 }) {
  if (
    !sorting.value ||
    !canSort.value ||
    busy.value ||
    editing.value ||
    keyword.value.trim() ||
    !event.dragRecord.id ||
    !event.dropRecord.id
  )
    return;
  await run(async () => {
    await client.sort(String(event.dragRecord.id), String(event.dropRecord.id), event.position);
    const records = await client.query();
    if (alive) {
      definitions.value = records;
      await loadAvailability(records);
    }
  });
}
</script>
<template>
  <RecordDetailDrawer
    v-if="editing"
    :open="editing"
    width="standard"
    :title="editorTitle"
    :subtitle="moduleTitle"
    :before-close="confirmDiscard"
    @close="cancel"
  >
    <UiError v-if="error" :message="error()" />
    <WorkflowDefinitionSettings
      @submit="save"
      :configuration="configuration"
      :creating="creating"
      :busy="busy"
      v-model:title="title"
      v-model:approval-enabled="approvalEnabled"
      v-model:organization-id="organizationId"
      v-model:match-expression="matchExpression"
      v-model:fallback="fallback"
    />
    <template #operation><DrawerTitleActions :actions="editorActions" /></template>
  </RecordDetailDrawer>
  <UiError v-if="error && !editing" :message="error()" />
  <RecordQueryListSurface
    embedded
    :title="language.t('platform.screen.WorkflowConfigurationView.approval-workflow', '审批流程')"
    :title-action-disabled="busy || editing"
    title-action-icon="reload"
    :title-action-title="language.t('platform.screen.CrudRecordListExplorer.reload', '重新加载')"
    :fill-height="false"
    :columns="columns"
    :rows="rows"
    :loading="busy"
    :table-visible="editing || !error || definitions.length > 0"
    :empty-description="
      language.t('platform.workflow-config.no-definitions', '暂无流程，点击“新建流程”开始配置')
    "
    :quick-search-visible="true"
    :quick-search-value="keyword"
    :quick-search-disabled="busy || editing"
    :row-draggable="sorting && !busy && !editing && canSort && !keyword.trim()"
    :row-drag-handle-title="language.t('platform.workflow-config.reorder', '调整流程顺序：越靠上越优先')"
    :show-action-column="true"
    :action-column-width="170"
    @update:quick-search-value="keyword = $event"
    @title-action="reload"
    @row-drop="sortRows"
  >
    <template #operations>
      <RecordPanelButton
        v-if="canSort"
        icon-name="swap-vertical"
        icon-only
        size="small"
        type="text"
        :selected="sorting"
        :disabled="busy || editing || Boolean(keyword.trim())"
        :title="language.t('platform.workflow-config.reorder', '调整流程顺序：越靠上越优先')"
        @click="sorting = !sorting"
      />
      <UiActionButton
        icon-name="plus"
        :title="language.t('platform.screen.WorkflowConfigurationView.new-workflow', '新建流程')"
        :disabled="busy || editing || definitionContext.can?.('create') !== true"
        @click="edit()"
      />
    </template>
    <template #beforeTable>
      <UiButton v-if="error && !editing" @click="reload">{{
        language.t('platform.screen.CrudRecordListExplorer.reload', '重新加载')
      }}</UiButton>
    </template>
    <template #cell="{ column, record, value }">
      <span v-if="column.key === 'definitionStatus'">{{ workflowTitle(String(value ?? ''), language) }}</span>
      <span v-else-if="column.key === 'currentVersionNo'">{{ value == null ? '—' : `v${value}` }}</span>
      <RecordQueryListCell
        v-else-if="column.key === 'matchExpression'"
        :column="{ ...column, width: String(column.width), maxDisplayLines: 2 }"
        :record="{
          ...record,
          matchExpression: record.defaultDefinition
            ? language.t('platform.workflow-manager.fallback', '兜底流程')
            : value || language.t('platform.workflow-manager.all-records', '全部记录'),
        }"
      />
      <UiTooltip
        v-else-if="column.key === 'active'"
        :title="
          canChangeStatus(record)
            ? language.t(
                'platform.workflow-config.future-only',
                '此操作影响后续新申请，已有实例继续使用冻结版本。',
              )
            : language.t('platform.workflow-manager.publish-to-enable', '请进入编排发布流程后启用')
        "
      >
        <span @click.stop>
          <UiSwitch
            :aria-label="language.t('platform.workflow-manager.active', '启用状态') + '：' + record.title"
            :checked="record.definitionStatus === 'published' && record.enabled !== false"
            :disabled="
              !canChangeStatus(record) ||
              !canRun(
                record.definitionStatus === 'published'
                  ? 'disableWorkflowDefinition'
                  : 'enableWorkflowDefinition',
                record,
              )
            "
            @change="rowAction($event ? 'enable' : 'disable', record)"
          />
        </span>
      </UiTooltip>
      <RecordQueryListCell
        v-else
        :column="{ ...column, width: String(column.width), maxDisplayLines: 2 }"
        :record="record"
      />
    </template>
    <template #rowActions="{ record }">
      <UiButton type="link" size="small" :disabled="busy || editing" @click="rowAction('design', record)">{{
        language.t('platform.workflow-manager.design', '编排')
      }}</UiButton>
      <UiButton
        type="link"
        size="small"
        :disabled="!canRun('configureWorkflowSelection', record)"
        @click="rowAction('settings', record)"
        >{{ language.t('platform.workflow-manager.settings', '设置') }}</UiButton
      >
      <UiButton
        type="link"
        size="small"
        danger
        :disabled="record.definitionStatus === 'archived' || !canRun('archiveWorkflowDefinition', record)"
        @click="rowAction('archive', record)"
        >{{ language.t('platform.screen.WorkflowConfigurationView.archive', '归档') }}</UiButton
      >
    </template>
  </RecordQueryListSurface>
</template>
