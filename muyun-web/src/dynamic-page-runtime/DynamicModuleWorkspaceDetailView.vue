<script setup lang="ts">
import {
  useLanguageContext,
  createModuleContext,
  refreshWorkflowRecordActions,
  useModuleContext,
} from '@muyun/web-core';
import { computed, ref, watch } from 'vue';

import {
  confirmAction,
  handlePlatformActionSuccess,
  presentPlatformError,
  recordPickerModeOf,
  RecordDetailExtensionSection,
  createReferenceRecordDetailBrowser,
  provideReferenceRecordDetailBrowser,
  DrawerTitleActions,
  RecordDetailFields,
  WorkflowRecordPanel,
  RecordDetailPanel,
  RecordMetaSection,
  RecordDetailDrawer,
  RecordPanelState,
  RecordStatusSwitch,
  resolveRecordFormFields,
  type QueryListRecord,
  type RecordFormFieldPickerConfig,
  type RecordFormFieldValue,
  type ReferenceRecordDetailMutation,
} from '@muyun/platform-components';
import RecordFormSurface from './RecordFormSurface.vue';
import { refreshModulePageList } from './modulePageListRefresh';
import {
  resolveModulePageEnhancement,
  type ModulePageActionContext,
  type ModulePageDrawer,
  type ModulePageRecordActionContribution,
} from './modulePageEnhancements';
import { useModulePageNavigation } from './modulePageNavigation';
import { useModulePageUnsavedState } from './modulePageUnsavedState';
import ModuleRecordDetailActions from './ModuleRecordDetailActions.vue';
import ModuleReferenceRecordDetailBrowser from './ModuleReferenceRecordDetailBrowser.vue';
import { useModulePageDetailExtensionRuntime } from './composables/useModulePageDetailExtensionRuntime';
import { useRecordDetailController } from './recordDetailController';
import {
  useModuleRecordDataChanges,
  moduleContentPresentationDependencies,
} from './useModuleRecordDataChanges';
import { applyReferenceRecordProjection } from './referenceRecordProjection';

const language = useLanguageContext();

defineOptions({ name: 'DynamicModuleWorkspaceDetailView' });

const props = defineProps<{ recordId: string }>();
const emit = defineEmits<{ 'close-workspace': [] }>();
const context = useModuleContext<QueryListRecord>();
const referenceRecordDetailBrowser = createReferenceRecordDetailBrowser(context.http, language);
provideReferenceRecordDetailBrowser(referenceRecordDetailBrowser);
const modulePageNavigation = useModulePageNavigation();
const detail = useRecordDetailController<QueryListRecord>();
const { record, draft, mode, formSessionKey, isDirty, loading, loadFailed, saving, togglingEnabled } = detail;
const workflowInteraction = ref({ editing: false, busy: false, dirty: false });
const referenceRecordDetailInteraction = ref({ editing: false, busy: false, dirty: false });
useModulePageUnsavedState(
  () => language.t('platform.screen.useModulePageSession.record-details', '记录详情'),
  () => isDirty.value || referenceRecordDetailInteraction.value.dirty,
  () => saving.value || togglingEnabled.value || referenceRecordDetailInteraction.value.busy,
);
const fields = ref(resolveRecordFormFields(undefined));
const {
  drawer: enhancementDrawer,
  drawerOpen: enhancementDrawerOpen,
  sectionContext,
  openDrawer: openEnhancementDrawer,
  closeDrawer: closeEnhancementDrawer,
  disposeDrawer: disposeEnhancementDrawer,
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
const formContributions = computed(() => pageEnhancement.value?.form?.contributions ?? []);
const formFieldPolicies = computed(() => pageEnhancement.value?.form?.fieldPolicies ?? []);
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
const showStatusSwitch = computed(() => {
  const value = record.value;
  if (context.abilities.hasEnable() !== true || typeof value?.enabled !== 'boolean') return false;
  const page = context.runtime.snapshot()?.uiDescriptor?.page;
  return (
    page?.managedActions !== true ||
    Boolean(
      page.actions?.some(
        (action) => action.anchor === 'DETAIL' && action.operation === (value.enabled ? 'DISABLE' : 'ENABLE'),
      ),
    )
  );
});
watch(
  () => [showStatusSwitch.value, record.value?.id, record.value?.version] as const,
  ([visible, id]) => {
    if (visible && id != null) {
      void context
        .recordActions(String(id))
        .catch((cause) =>
          presentPlatformError(cause, { language, source: 'module-status', phase: 'authorization' }),
        );
    }
  },
);
const canToggleEnabled = computed(() => {
  const value = record.value;
  if (
    !showStatusSwitch.value ||
    !value?.id ||
    mode.value !== 'view' ||
    loading.value ||
    loadFailed.value ||
    workflowInteraction.value.editing ||
    workflowInteraction.value.busy ||
    togglingEnabled.value
  ) {
    return false;
  }
  const actionCode = value.enabled === false ? 'enable' : 'disable';
  const availability = context.recordActionsSnapshot(String(value.id));
  const action = availability?.actions.find((item) => item.actionCode === actionCode);
  return action?.available === true;
});
const toggleEnabledDisabledReason = computed(() => {
  const value = record.value;
  if (!value?.id || canToggleEnabled.value) return undefined;
  const actionCode = value.enabled === false ? 'enable' : 'disable';
  return context.action(actionCode, String(value.id))?.reason;
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
    presentPlatformError(cause, { language, source: 'module-workspace-detail', phase: 'load' });
  } finally {
    if (revision === loadRevision) detail.finishLoad();
  }
}

useModuleRecordDataChanges({
  moduleAlias: context.moduleAlias,
  presentationModules: () => moduleContentPresentationDependencies(context.runtime.snapshot()?.uiDescriptor),
  recordId: () => props.recordId,
  blocked: () =>
    mode.value !== 'view' ||
    loading.value ||
    saving.value ||
    togglingEnabled.value ||
    workflowInteraction.value.editing ||
    workflowInteraction.value.busy ||
    referenceRecordDetailInteraction.value.editing ||
    referenceRecordDetailInteraction.value.busy,
  invalidate: (ids) => context.invalidateRecordActions?.(ids),
  refreshList: () => undefined,
  refreshRecord: async (id, isCurrent) => {
    await refreshWorkflowRecordActions(context, id).catch((cause) =>
      presentPlatformError(cause, { language, source: 'module-data-change', phase: 'authorization' }),
    );
    if (!isCurrent()) return false;
    await loadRecord();
    return true;
  },
  onError: (cause) => presentPlatformError(cause, { language, source: 'module-data-change', phase: 'load' }),
});

async function handleWorkflowChanged() {
  await refreshWorkflowRecordActions(context, props.recordId).catch((cause) =>
    presentPlatformError(cause, { language, source: 'module-workflow-change', phase: 'authorization' }),
  );
  refreshModulePageList(context.moduleAlias);
  await loadRecord();
}

function editRecord() {
  if (
    saving.value ||
    workflowInteraction.value.editing ||
    workflowInteraction.value.busy ||
    context.can('update') !== true
  )
    return;
  detail.beginEdit();
}

async function cancelEditing() {
  if (saving.value || !(await confirmDiscardEditing())) return;
  detail.cancelEdit();
  if (!detail.open.value) return;
  const current = record.value;
  const id = current?.id == null ? undefined : String(current.id);
  if (!current || !id) return;
  detail.beginLoad(current, 'view');
  try {
    detail.resolveLoad(await context.crud.view(id));
  } catch (cause) {
    detail.failLoad();
    presentPlatformError(cause, { language, source: 'module-workspace-detail', phase: 'load' });
  } finally {
    detail.finishLoad();
  }
}

async function confirmDiscardEditing() {
  if (!isDirty.value) return true;
  return confirmAction(
    {
      title: language.t(
        'platform.screen.DynamicModuleWorkspaceDetailView.discard-unsaved-changes',
        '放弃未保存的修改',
      ),
      content: language.t(
        'platform.screen.DynamicModuleWorkspaceDetailView.this-record-has-unsaved-changes-that-will-be-lost-continue',
        '当前记录存在未保存的修改，取消后将丢失。是否继续？',
      ),
      okText: language.t('platform.screen.DynamicModuleWorkspaceDetailView.discard-changes', '放弃修改'),
      danger: true,
    },
    language,
  );
}

function updateDraftField(fieldName: string, value: RecordFormFieldValue) {
  if (!draft.value) return;
  draft.value = { ...draft.value, [fieldName]: value };
}

function updateReferenceRecordDetailInteraction(state: { editing: boolean; busy: boolean; dirty?: boolean }) {
  referenceRecordDetailInteraction.value = {
    editing: state.editing,
    busy: state.busy,
    dirty: state.dirty === true,
  };
}

function handleReferenceRecordChange(mutation: ReferenceRecordDetailMutation) {
  refreshModulePageList(context.moduleAlias);
  const source = record.value;
  if (!source?.id) return;
  if (mode.value === 'view') {
    void loadRecord();
    return;
  }
  const currentDraft = draft.value;
  if (mode.value !== 'edit' || !currentDraft) return;
  const projectedDraft = applyReferenceRecordProjection(currentDraft, fields.value, mutation);
  if (projectedDraft !== currentDraft) draft.value = projectedDraft;
}

async function saveRecord() {
  const current = record.value;
  const next = draft.value;
  const id = current?.id == null ? undefined : String(current.id);
  if (!current || !next || !id || saving.value || context.can('update') !== true) return;
  saving.value = true;
  try {
    const result = await context.crud.update(id, next);
    let persistedRecord = result.record;
    let refreshFailure: unknown;
    try {
      persistedRecord = await context.crud.view(id);
    } catch (cause) {
      refreshFailure = cause;
    }
    detail.applySaved(persistedRecord);
    refreshModulePageList(context.moduleAlias);
    await handlePlatformActionSuccess(result, {
      source: 'module-workspace-detail',
      phase: 'action',
      fallbackMessage: language.t(
        'platform.screen.DynamicModuleWorkspaceDetailView.saved-successfully',
        '保存成功',
      ),
    });
    if (refreshFailure) {
      presentPlatformError(refreshFailure, { language, source: 'module-workspace-detail', phase: 'load' });
    }
  } catch (cause) {
    presentPlatformError(cause, { language, source: 'module-workspace-detail', phase: 'action' });
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
      !(await confirmAction(
        {
          title: language.t('platform.screen.DynamicModuleWorkspaceDetailView.delete-record', '删除记录'),
          content: language.t(
            'platform.screen.DynamicModuleWorkspaceDetailView.delete-value',
            '确认删除「{value1}」？',
            { value1: title.value },
          ),
          okText: language.t('platform.action.delete', '删除'),
          danger: true,
        },
        language,
      ))
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
      fallbackMessage: language.t(
        'platform.screen.DynamicModuleWorkspaceDetailView.deleted-successfully',
        '删除成功',
      ),
    });
    emit('close-workspace');
  } catch (cause) {
    presentPlatformError(cause, { language, source: 'module-workspace-detail', phase: 'action' });
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
    presentPlatformError(cause, {
      language,
      source: `module-workspace-detail:${contribution.key}`,
      phase: 'action',
    });
  }
}

function modulePageActionContext(record?: QueryListRecord): ModulePageActionContext {
  return {
    module: context,
    refreshList: () => refreshModulePageList(context.moduleAlias),
    reload: loadRecord,
    openDrawer: (definition: ModulePageDrawer) => openEnhancementDrawer(definition, record),
    openWorkspaceTab: (view, input) => {
      if (!modulePageNavigation)
        throw new Error(
          language.t(
            'platform.screen.DynamicModuleWorkspaceDetailView.a-module-page-work-view-requires-workbench-navigation',
            '模块页面工作视图需要 Workbench 导航承载',
          ),
        );
      modulePageNavigation.openWorkspaceTab(view, input);
    },
    openPage: (descriptor) => {
      if (!modulePageNavigation)
        throw new Error(
          language.t(
            'platform.screen.DynamicModuleWorkspaceDetailView.module-page-navigation-requires-workbench-navigation',
            '模块页面跳转需要 Workbench 导航承载',
          ),
        );
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
      fallbackMessage: enabling
        ? language.t('platform.screen.DynamicModuleWorkspaceDetailView.enabled', '已启用')
        : language.t('platform.workflow.disabled', '已停用'),
    });
  } catch (cause) {
    presentPlatformError(cause, { language, source: 'module-workspace-detail', phase: 'action' });
  } finally {
    togglingEnabled.value = false;
  }
}
</script>

<template>
  <section class="dynamic-module-workspace-detail">
    <RecordDetailPanel :title="title">
      <template #actions>
        <ModuleRecordDetailActions
          :context="context"
          :record="record"
          :mode="mode"
          :saving="saving || workflowInteraction.editing || workflowInteraction.busy"
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
          v-if="showStatusSwitch && mode === 'view' && record"
          :enabled="record.enabled !== false"
          :disabled="!canToggleEnabled || workflowInteraction.editing || workflowInteraction.busy"
          :disabled-reason="toggleEnabledDisabledReason"
          :loading="togglingEnabled"
          :show-label="false"
          @change="toggleEnabled"
        />
      </template>

      <RecordPanelState
        v-if="loading"
        loading
        :loading-tip="
          language.t(
            'platform.screen.DynamicModuleWorkspaceDetailView.loading-record-details',
            '加载记录详情',
          )
        "
        description=""
      />
      <RecordPanelState
        v-else-if="loadFailed"
        :description="
          language.t(
            'platform.screen.DynamicModuleWorkspaceDetailView.this-record-no-longer-exists-or-you-no-longer-have',
            '记录已不存在，或你已无权查看。',
          )
        "
      />
      <RecordPanelState
        v-else-if="!record"
        :description="
          language.t(
            'platform.screen.DynamicModuleWorkspaceDetailView.this-record-has-been-deleted',
            '记录已删除。',
          )
        "
      />
      <template v-else-if="draft">
        <RecordDetailFields
          v-if="mode === 'view'"
          :record="draft"
          :fields="fields"
          :option-context="context"
          :file-transfer-context="context"
          :exclude-field-names="['enabled']"
        />
        <div v-else class="dynamic-module-workspace-detail__form">
          <RecordFormSurface
            :record="draft"
            :fields="fields"
            :mode="mode"
            :form-session-key="formSessionKey"
            :option-context="context"
            :file-transfer-context="context"
            :picker-configs="referencePickerConfigs"
            :disabled="saving"
            :exclude-field-names="['enabled']"
            :contributions="formContributions"
            :field-policies="formFieldPolicies"
            @update:field="updateDraftField"
          />
        </div>
        <template v-if="mode === 'view'">
          <WorkflowRecordPanel
            @interaction-change="workflowInteraction = $event"
            v-if="
              record.id &&
              (context.abilities.has('workflow') === true || context.abilities.has('approval') === true)
            "
            :context="context"
            :record-id="String(record.id)"
            @changed="handleWorkflowChanged"
            @edit="editRecord"
          />
          <RecordDetailExtensionSection
            v-for="section in detailSections"
            :key="section.key"
            :title="section.titleKey ? language.t(section.titleKey, section.title) : section.title"
            :subtitle="section.subtitle"
          >
            <template v-if="section.subtitleComponent" #subtitle>
              <component :is="section.subtitleComponent" :context="sectionContext(record)" />
            </template>
            <component :is="section.component" :context="sectionContext(record)" />
          </RecordDetailExtensionSection>
        </template>
        <RecordMetaSection v-if="showSystemInfo" :record="draft" show-sort-order />
      </template>
    </RecordDetailPanel>
    <RecordDetailDrawer
      v-if="enhancementDrawer"
      :open="enhancementDrawerOpen"
      :title="
        enhancementDrawer.definition.titleKey
          ? language.t(enhancementDrawer.definition.titleKey, enhancementDrawer.definition.title)
          : enhancementDrawer.definition.title
      "
      :width="enhancementDrawer.definition.width"
      :subtitle="enhancementDrawer.subtitle"
      render-mode="inline"
      @close="closeEnhancementDrawer"
      @after-close="disposeEnhancementDrawer"
    >
      <template v-if="enhancementDrawer.titleActions.length" #title-actions>
        <DrawerTitleActions :actions="enhancementDrawer.titleActions" />
      </template>
      <template v-if="enhancementDrawer.operation?.summary" #operation-summary>
        {{ enhancementDrawer.operation.summary }}
      </template>
      <template v-if="enhancementDrawer.operation" #operation>
        <DrawerTitleActions :actions="enhancementDrawer.operation.actions" />
      </template>
      <component :is="enhancementDrawer.definition.component" :context="enhancementDrawer.context" />
    </RecordDetailDrawer>
    <ModuleReferenceRecordDetailBrowser
      :browser="referenceRecordDetailBrowser"
      render-mode="inline"
      scope="tab"
      @record-change="handleReferenceRecordChange"
      @interaction-state-change="updateReferenceRecordDetailInteraction($event)"
    />
  </section>
</template>

<style scoped>
.dynamic-module-workspace-detail {
  position: relative;
  min-height: 100%;
}

.dynamic-module-workspace-detail__form {
  display: grid;
  gap: 12px;
}
</style>
