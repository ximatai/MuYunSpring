<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { createModuleContext, useModuleContext } from '@muyun/web-core';
import {
  confirmAction,
  handlePlatformActionSuccess,
  presentPlatformError,
  recordPickerModeOf,
  RecordDetailExtensionSection,
  DrawerTitleActions,
  RecordDetailFields,
  RecordDetailPanel,
  RecordFormFields,
  RecordMetaSection,
  RecordModeDrawer,
  RecordPanelState,
  RecordStatusSwitch,
  resolveRecordFormFields,
  type QueryListRecord,
  type RecordFormFieldPickerConfig,
  type RecordFormFieldValue,
} from '@muyun/platform-components';
import { refreshModulePageList } from './modulePageListRefresh';
import {
  resolveModulePageEnhancement,
  type ModulePageActionContext,
  type ModulePageDrawer,
  type ModulePageRecordActionContribution,
} from './modulePageEnhancements';
import { useModulePageNavigation } from './modulePageNavigation';
import ModuleRecordDetailActions from './ModuleRecordDetailActions.vue';
import { useModulePageDetailExtensionRuntime } from './composables/useModulePageDetailExtensionRuntime';
import { useRecordDetailController } from './recordDetailController';

defineOptions({ name: 'DynamicModuleWorkspaceDetailView' });

const props = defineProps<{ recordId: string }>();
const emit = defineEmits<{ 'close-workspace': [] }>();
const context = useModuleContext<QueryListRecord>();
const modulePageNavigation = useModulePageNavigation();
const detail = useRecordDetailController<QueryListRecord>();
const { record, draft, mode, formSessionKey, loading, loadFailed, saving, togglingEnabled } = detail;
const fields = ref(resolveRecordFormFields(undefined));
const {
  drawer: enhancementDrawer,
  sectionContext,
  openDrawer: openEnhancementDrawer,
  closeDrawer: closeEnhancementDrawer,
} = useModulePageDetailExtensionRuntime({
  module: context,
  scope: () => undefined,
  refreshList: () => refreshModulePageList(context.moduleAlias),
  reload: loadRecord,
  closeDetail: () => undefined,
});
let loadRevision = 0;

const title = computed(() => {
  const value = record.value;
  return String(value?.title ?? value?.code ?? props.recordId);
});
const pageEnhancement = computed(() => resolveModulePageEnhancement(context.moduleAlias));
const detailSections = computed(() => pageEnhancement.value?.detail?.sections ?? []);
const showSystemInfo = ref(true);
const detailActions = computed<ModulePageRecordActionContribution[]>(() => {
  const current = record.value;
  return (pageEnhancement.value?.detail?.actions ?? []).map(({ state, ...action }) => ({
    ...action,
    ...(current ? state?.(current) : { visible: false }),
  }));
});
const referencePickerConfigs = computed<Record<string, RecordFormFieldPickerConfig>>(() => {
  const configs: Record<string, RecordFormFieldPickerConfig> = {};
  for (const field of fields.value.values()) {
    if (!field.reference) continue;
    configs[field.fieldRef.fieldName] = {
      context: createModuleContext({ http: context.http, moduleAlias: field.reference.targetModuleAlias }),
      mode: recordPickerModeOf(field.reference.pickerMode),
      allowClear: !field.required?.constant,
    };
  }
  return configs;
});
const canToggleEnabled = computed(() => {
  const value = record.value;
  if (!value?.id || mode.value !== 'view' || loading.value || loadFailed.value || togglingEnabled.value) {
    return false;
  }
  const actionCode = value.enabled === false ? 'enable' : 'disable';
  const availability = context.recordActionsSnapshot(String(value.id));
  const action = availability?.actions.find((item) => item.actionCode === actionCode);
  return action ? action.available : context.can(actionCode) === true;
});
const toggleEnabledDisabledReason = computed(() => {
  const value = record.value;
  if (!value?.id || canToggleEnabled.value) return undefined;
  const actionCode = value.enabled === false ? 'enable' : 'disable';
  return context
    .recordActionsSnapshot(String(value.id))
    ?.actions.find((item) => item.actionCode === actionCode)?.reason;
});

watch(
  () => props.recordId,
  () => void loadRecord(),
  { immediate: true },
);

async function loadRecord() {
  const revision = ++loadRevision;
  detail.beginLoad({ id: props.recordId }, 'view');
  try {
    const runtime = await context.runtime.ready;
    const resolved = await context.crud.view(props.recordId);
    if (revision !== loadRevision) return;
    fields.value = resolveRecordFormFields(runtime.uiDescriptor);
    showSystemInfo.value = runtime.uiDescriptor?.page?.detail.showSystemInfo !== false;
    detail.resolveLoad(resolved);
  } catch (cause) {
    if (revision !== loadRevision) return;
    detail.failLoad();
    presentPlatformError(cause, { source: 'module-workspace-detail', phase: 'load' });
  } finally {
    if (revision === loadRevision) detail.finishLoad();
  }
}

function editRecord() {
  if (context.can('update') !== true) return;
  detail.beginEdit();
}

function cancelEditing() {
  if (saving.value) return;
  detail.cancelEdit();
}

function updateDraftField(fieldName: string, value: RecordFormFieldValue) {
  if (!draft.value) return;
  draft.value = { ...draft.value, [fieldName]: value };
}

async function saveRecord() {
  const current = record.value;
  const next = draft.value;
  const id = current?.id == null ? undefined : String(current.id);
  if (!current || !next || !id || saving.value || context.can('update') !== true) return;
  saving.value = true;
  try {
    const result = await context.crud.update(id, next);
    detail.applySaved(result.record);
    refreshModulePageList(context.moduleAlias);
    await handlePlatformActionSuccess(result, {
      source: 'module-workspace-detail',
      phase: 'action',
      fallbackMessage: '保存成功',
    });
  } catch (cause) {
    presentPlatformError(cause, { source: 'module-workspace-detail', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function deleteRecord() {
  const current = record.value;
  const id = current?.id == null ? undefined : String(current.id);
  const version = typeof current?.version === 'number' ? current.version : undefined;
  if (!current || !id || version === undefined || saving.value) return;
  try {
    if (
      !(await confirmAction({
        title: '删除记录',
        content: `确认删除「${title.value}」？`,
        okText: '删除',
        danger: true,
      }))
    ) {
      return;
    }
    saving.value = true;
    const result = await context.crud.delete(id, { version });
    detail.clearDeleted();
    refreshModulePageList(context.moduleAlias);
    await handlePlatformActionSuccess(result, {
      source: 'module-workspace-detail',
      phase: 'action',
      fallbackMessage: '删除成功',
    });
    emit('close-workspace');
  } catch (cause) {
    presentPlatformError(cause, { source: 'module-workspace-detail', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

function handleDetailAction(action: { key?: string }) {
  const current = record.value;
  const contribution = detailActions.value.find((item) => item.key === action.key);
  if (!current || !contribution) return;
  void executeDetailAction(contribution, { ...modulePageActionContext(current), record: current });
}

async function executeDetailAction<TContext>(
  contribution: { key: string; run(context: TContext): void | Promise<void> },
  actionContext: TContext,
) {
  try {
    await contribution.run(actionContext);
  } catch (cause) {
    presentPlatformError(cause, { source: `module-workspace-detail:${contribution.key}`, phase: 'action' });
  }
}

function modulePageActionContext(record?: QueryListRecord): ModulePageActionContext {
  return {
    module: context,
    refreshList: () => refreshModulePageList(context.moduleAlias),
    reload: loadRecord,
    openDrawer: (definition: ModulePageDrawer) => openEnhancementDrawer(definition, record),
    openWorkspaceTab: (view, input) => {
      if (!modulePageNavigation) throw new Error('模块页面工作视图需要 Workbench 导航承载');
      modulePageNavigation.openWorkspaceTab(view, input);
    },
    openPage: (descriptor) => {
      if (!modulePageNavigation) throw new Error('模块页面跳转需要 Workbench 导航承载');
      modulePageNavigation.openPage(descriptor);
    },
  };
}

async function toggleEnabled() {
  const current = record.value;
  const id = current?.id == null ? undefined : String(current.id);
  const version = typeof current?.version === 'number' ? current.version : undefined;
  if (!current || !id || version === undefined || !canToggleEnabled.value) return;
  togglingEnabled.value = true;
  try {
    const enabling = current.enabled === false;
    const result = enabling
      ? await context.crud.enable(id, { version })
      : await context.crud.disable(id, { version });
    await loadRecord();
    refreshModulePageList(context.moduleAlias);
    await handlePlatformActionSuccess(result, {
      source: 'module-workspace-detail',
      phase: 'action',
      fallbackMessage: enabling ? '已启用' : '已停用',
    });
  } catch (cause) {
    presentPlatformError(cause, { source: 'module-workspace-detail', phase: 'action' });
  } finally {
    togglingEnabled.value = false;
  }
}
</script>

<template>
  <RecordDetailPanel class="dynamic-module-workspace-detail" :title="title">
    <template #actions>
      <ModuleRecordDetailActions
        :context="context"
        :record="record"
        :mode="mode"
        :saving="saving"
        :detail-loading="loading"
        :detail-load-failed="loadFailed"
        :actions="detailActions"
        @cancel="cancelEditing"
        @save="saveRecord"
        @edit="editRecord"
        @delete="deleteRecord"
        @detail-action="handleDetailAction"
      />
    </template>
    <template #status>
      <RecordStatusSwitch
        v-if="mode === 'view' && record"
        :enabled="record.enabled !== false"
        :disabled="!canToggleEnabled"
        :disabled-reason="toggleEnabledDisabledReason"
        :loading="togglingEnabled"
        :show-label="false"
        @change="toggleEnabled"
      />
    </template>

    <RecordPanelState v-if="loading" loading loading-tip="加载记录详情" description="" />
    <RecordPanelState v-else-if="loadFailed" description="记录已不存在，或你已无权查看。" />
    <RecordPanelState v-else-if="!record" description="记录已删除。" />
    <template v-else-if="draft">
      <RecordDetailFields
        v-if="mode === 'view'"
        :record="draft"
        :fields="fields"
        :file-transfer-context="context"
        :exclude-field-names="['enabled']"
      />
      <div v-else class="dynamic-module-workspace-detail__form">
        <RecordFormFields
          :record="draft"
          :fields="fields"
          :form-session-key="formSessionKey"
          :option-context="context"
          :picker-configs="referencePickerConfigs"
          :disabled="saving"
          :exclude-field-names="['enabled']"
          @update:field="updateDraftField"
        />
      </div>
      <template v-if="mode === 'view'">
        <RecordDetailExtensionSection
          v-for="section in detailSections"
          :key="section.key"
          :title="section.title"
        >
          <component :is="section.component" :context="sectionContext(record)" />
        </RecordDetailExtensionSection>
      </template>
      <RecordMetaSection v-if="showSystemInfo" :record="draft" show-sort-order />
    </template>
  </RecordDetailPanel>
  <RecordModeDrawer
    v-if="enhancementDrawer"
    :open="true"
    :title="enhancementDrawer.definition.title"
    :width="enhancementDrawer.definition.width"
    mode="view"
    @close="closeEnhancementDrawer"
  >
    <template v-if="enhancementDrawer.titleActions.length" #title-actions>
      <DrawerTitleActions :actions="enhancementDrawer.titleActions" />
    </template>
    <component :is="enhancementDrawer.definition.component" :context="enhancementDrawer.context" />
  </RecordModeDrawer>
</template>

<style scoped>
.dynamic-module-workspace-detail {
  min-height: 100%;
}

.dynamic-module-workspace-detail__form {
  display: grid;
  gap: 12px;
}
</style>
