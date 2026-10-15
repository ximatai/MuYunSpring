<script setup lang="ts">
import {
  useLanguageContext,
  useModuleContext,
  useAssistantSurfaceHost,
  createAssistantTurnRequester,
} from '@muyun/web-core';
import {
  computed,
  inject,
  isRef,
  onActivated,
  onDeactivated,
  onMounted,
  onUnmounted,
  type Ref,
  watch,
} from 'vue';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';

import { moduleRuntimeActivationRefreshKey } from './moduleRuntimeActivation';
import { createMetadataEditorSession, type MetadataEditorSession } from './metadataEditorSession';
import { useMetadataWorkspace } from './metadataWorkspace';
import { createMetadataGovernanceAssistantSurface } from './metadataGovernanceAssistantSurface';
import {
  presentPlatformError,
  ManagementExplorerColumn,
  ManagementWorkspace,
  RecordDetailPanel,
  RecordDetailFields,
  RecordContentSectionHeading,
  RecordFormGrid,
  RecordFieldLabel,
  RecordExplorerPanel,
} from '@muyun/platform-components';
import {
  UiActionButton,
  UiButton,
  UiCheckbox,
  UiEmpty,
  UiDropdown,
  UiInput,
  UiRadioGroup,
  UiSelect,
  UiSpin,
  UiSwitch,
  UiTextArea,
  UiTree,
} from '@muyun/vue-ui-antdv';

const language = useLanguageContext();

defineOptions({ name: 'MetadataGovernanceSurface' });
const props = defineProps<{ moduleAlias: string; moduleTitle?: string; title?: string }>();
const context = useModuleContext({ moduleAlias: 'platform.module' });
const refreshActivation = inject(moduleRuntimeActivationRefreshKey, undefined);
const workspace = useMetadataWorkspace();
const local = new Map<string, MetadataEditorSession>();
const session = computed(() => {
  if (workspace) return workspace.session(props.moduleAlias, props.moduleTitle || props.title);
  let current = local.get(props.moduleAlias);
  if (!current) {
    current = createMetadataEditorSession(context.http, {
      language,
      moduleAlias: props.moduleAlias,
      moduleTitle: props.moduleTitle || props.title,
      valid: () => props.moduleAlias === current?.moduleAlias,
      confirmationScope: () => active,
      refreshActivation,
    });
    local.set(props.moduleAlias, current);
  }
  return current;
});
type EditorValue<K extends keyof MetadataEditorSession['view']> =
  MetadataEditorSession['view'][K] extends Ref<infer V> ? V : MetadataEditorSession['view'][K];
function binding<K extends keyof MetadataEditorSession['view']>(key: K) {
  return computed<EditorValue<K>>({
    get: () => {
      const target = session.value.view[key];
      return (isRef(target) ? target.value : target) as EditorValue<K>;
    },
    set: (value) => {
      const target = session.value.view[key];
      if (isRef(target)) (target as Ref<EditorValue<K>>).value = value;
    },
  });
}
const state = binding('state');
const capabilityItems = binding('capabilityItems');
const selectCapability = binding('selectCapability');
const fieldPlanActive = binding('fieldPlanActive');
const fieldPlanEntries = binding('fieldPlanEntries');
const sorting = binding('sorting');
const mainMetadataDraft = binding('mainMetadataDraft');
const fieldDraft = binding('fieldDraft');
const fieldPropertyDraft = binding('fieldPropertyDraft');
const fixedDefaultEditable = binding('fixedDefaultEditable');
const fixedDefaultReadValue = binding('fixedDefaultReadValue');
const fixedDefaultValue = computed({
  get: () =>
    fieldPropertyDraft.value.fixedDefault
      ? (fieldPropertyDraft.value.fixedDefault.value ?? '')
      : (fixedDefaultReadValue.value ?? ''),
  set: (value: string) => {
    if (!fixedDefaultEditable.value) return;
    fieldPropertyDraft.value.fixedDefault = {
      ...fieldPropertyDraft.value.fixedDefault,
      value: value.trim() ? value : null,
    };
  },
});
const loading = binding('loading');
const saving = binding('saving');
const showSystemFields = binding('showSystemFields');
const selectedTreeKey = binding('selectedTreeKey');
const expandedTreeKeys = binding('expandedTreeKeys');
const childNodeType = binding('childNodeType');
const editorMode = binding('editorMode');
const editorModeOptions = binding('editorModeOptions');
const childMetadataDraft = binding('childMetadataDraft');
const childValidationAttempted = binding('childValidationAttempted');
const creatingChildMetadata = binding('creatingChildMetadata');
const updateChildAlias = binding('updateChildAlias');
const childAliasError = binding('childAliasError');
const referenceTargetFieldCatalogLoading = binding('referenceTargetFieldCatalogLoading');
const referenceAffectMappings = binding('referenceAffectMappings');
const referenceAffectSourceOptions = binding('referenceAffectSourceOptions');
const referenceAffectTargetOptions = binding('referenceAffectTargetOptions');
const updateReferenceAffect = binding('updateReferenceAffect');
const addReferenceAffect = binding('addReferenceAffect');
const removeReferenceAffect = binding('removeReferenceAffect');
const referenceTargetFieldCatalogError = binding('referenceTargetFieldCatalogError');
const selectedField = binding('selectedField');
const selectedNodeIsField = binding('selectedNodeIsField');
const metadataTreeNodes = binding('metadataTreeNodes');
const selectedRelationIsMain = binding('selectedRelationIsMain');
const fieldPropertyEditorKind = binding('fieldPropertyEditorKind');
const editableFieldSpecOptions = binding('editableFieldSpecOptions');
const fieldCreationItems = binding('fieldCreationItems');
const referenceSearch = binding('referenceSearch');
const dictionarySearch = binding('dictionarySearch');
const choicesLoading = binding('choicesLoading');
const choicesError = binding('choicesError');
const referenceModuleOptions = binding('referenceModuleOptions');
const dictionaryValue = binding('dictionaryValue');
const dictionaryOptions = binding('dictionaryOptions');
const updateFieldTitle = binding('updateFieldTitle');
const updateDictionary = binding('updateDictionary');
const updateFieldName = binding('updateFieldName');
const updateColumnName = binding('updateColumnName');
const projectionMappingsText = binding('projectionMappingsText');
const referenceKeyFieldOptions = binding('referenceKeyFieldOptions');
const referenceLabelFieldOptions = binding('referenceLabelFieldOptions');
const referenceTargetFieldCatalogProblem = binding('referenceTargetFieldCatalogProblem');
const updateReferenceTargetModuleAlias = binding('updateReferenceTargetModuleAlias');
const loadWorkspace = binding('loadWorkspace');
async function readCurrentMetadata() {
  try {
    await session.value.readCurrent();
  } catch (cause) {
    presentPlatformError(cause, { language, source: 'metadata-orchestration', phase: 'load' });
  }
}
const selectMetadataTreeNode = binding('selectMetadataTreeNode');
const editPlanField = binding('editPlanField');
const removePlanField = binding('removePlanField');
const cancelFieldPlan = binding('cancelFieldPlan');
const startFieldPlan = binding('startFieldPlan');
const startCreateMainMetadata = binding('startCreateMainMetadata');
const startCreateChildNode = binding('startCreateChildNode');
const startCreateChildMetadataNode = binding('startCreateChildMetadataNode');
const startEditField = binding('startEditField');
const cancelNodeEditor = binding('cancelNodeEditor');
const toggleSorting = binding('toggleSorting');
const previewAndApply = binding('previewAndApply');
const stageFieldDraft = binding('stageFieldDraft');
const deleteSelectedNode = binding('deleteSelectedNode');
const createMainMetadata = binding('createMainMetadata');
const fieldPropertyOf = binding('fieldPropertyOf');
const fieldSourceOf = binding('fieldSourceOf');
const fieldEditableInSession = binding('fieldEditableInSession');
const fieldProtectionReason = binding('fieldProtectionReason');
const canDragMetadataNode = binding('canDragMetadataNode');
const allowMetadataModelDrop = binding('allowMetadataModelDrop');
const handleMetadataModelDrop = binding('handleMetadataModelDrop');
const metadataFieldPropertyLabel = binding('metadataFieldPropertyLabel');
const fieldSpecDisplayLabel = binding('fieldSpecDisplayLabel');
useWorkspaceViewUnsavedState(
  () => language.t('platform.governance.tab.metadata', '元数据'),
  () => !workspace && session.value.dirty.value,
  () => session.value.saving.value,
);
const assistantHost = useAssistantSurfaceHost();
let active = true;
let unregister: (() => void) | undefined;
function syncAssistant() {
  unregister?.();
  unregister = undefined;
  if (!active) return;
  if (workspace) {
    workspace.showEditor(session.value);
    workspace.focus(session.value);
    return;
  }
  if (
    !assistantHost ||
    !session.value.workspaceReady.value ||
    session.value.loading.value ||
    session.value.saving.value
  )
    return;
  const page = assistantHost.activePageInstanceKey();
  if (!page) return;
  unregister = assistantHost.registry.register({
    pageInstanceKey: page,
    contextRevision: () => `${props.moduleAlias}:${session.value.contextRevision.value}`,
    surface: createMetadataGovernanceAssistantSurface(
      session.value.adapter,
      createAssistantTurnRequester(context.http),
      () => assistantHost.capabilities?.() ?? [],
    ),
  });
}
watch(
  session,
  (current, previous) => {
    if (previous) workspace?.hideEditor(previous);
    previous?.invalidateConfirmations();
    syncAssistant();
    void current.ensureLoaded().catch(() => {});
  },
  { immediate: true },
);
watch(
  () => [session.value.loading.value, session.value.workspaceReady.value, session.value.saving.value],
  syncAssistant,
  { flush: 'post' },
);
function activate() {
  active = true;
  syncAssistant();
}
function deactivate() {
  workspace?.hideEditor(session.value);
  active = false;
  unregister?.();
  unregister = undefined;
}
onMounted(activate);
onActivated(activate);
onDeactivated(deactivate);
onUnmounted(() => {
  deactivate();
  local.forEach((value) => value.dispose());
});
</script>

<template>
  <ManagementWorkspace
    class="metadata-model-workspace"
    layout="default"
    :explorer-count="1"
    :editing="state.fieldEditorOpen.value || state.mainEditorOpen.value"
    :sorting-request="saving && sorting && !state.fieldEditorOpen.value"
    list-surface
  >
    <ManagementExplorerColumn
      :title="language.t('platform.governance.tab.metadata', '元数据')"
      collapsible
      :has-selection="Boolean(selectedTreeKey)"
    >
      <RecordExplorerPanel
        :title="language.t('platform.governance.tab.metadata', '元数据')"
        :searchable="false"
        :collapse-action="false"
        :refresh-disabled="
          saving ||
          fieldPlanActive ||
          session.submissionStatus.value === 'unknown' ||
          session.committedNeedsReload.value
        "
        @refresh="loadWorkspace"
      >
        <template #actions>
          <UiButton
            v-if="!state.fieldEditorOpen.value"
            icon-name="swap-vertical"
            icon-only
            size="small"
            type="text"
            :title="
              sorting
                ? language.t('platform.screen.ModulePageHostRuntime.finish-reordering', '结束排序')
                : language.t('platform.screen.ModulePageHostRuntime.reorder', '调整排序')
            "
            :aria-label="
              sorting
                ? language.t('platform.screen.ModulePageHostRuntime.finish-reordering', '结束排序')
                : language.t('platform.screen.ModulePageHostRuntime.reorder', '调整排序')
            "
            :selected="sorting"
            :disabled="
              saving ||
              loading ||
              fieldPlanActive ||
              session.submissionStatus.value === 'unknown' ||
              session.committedNeedsReload.value
            "
            @click="toggleSorting"
          />
          <label class="metadata-system-fields-toggle">
            <span>{{
              language.t('platform.screen.MetadataGovernanceSurface.system-fields', '系统字段')
            }}</span>
            <UiSwitch
              v-model:checked="showSystemFields"
              size="small"
              :title="
                showSystemFields
                  ? language.t('platform.screen.MetadataGovernanceSurface.hide-system-fields', '隐藏系统字段')
                  : language.t('platform.screen.MetadataGovernanceSurface.show-system-fields', '显示系统字段')
              "
              :aria-label="
                showSystemFields
                  ? language.t('platform.screen.MetadataGovernanceSurface.hide-system-fields', '隐藏系统字段')
                  : language.t('platform.screen.MetadataGovernanceSurface.show-system-fields', '显示系统字段')
              "
            />
          </label>
        </template>
        <UiSpin
          v-if="loading && metadataTreeNodes.length === 0"
          :tip="language.t('platform.screen.MetadataGovernanceSurface.loading-metadata', '加载元数据')"
        />
        <UiTree
          v-else
          v-model:expanded-keys="expandedTreeKeys"
          :nodes="metadataTreeNodes"
          :selected-key="selectedTreeKey"
          :draggable="
            sorting &&
            !saving &&
            !loading &&
            !state.fieldEditorOpen.value &&
            session.submissionStatus.value !== 'unknown' &&
            !session.committedNeedsReload.value
          "
          :can-drag="canDragMetadataNode"
          :allow-drop="allowMetadataModelDrop"
          @select="selectMetadataTreeNode"
          @drop="handleMetadataModelDrop"
        />
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <div
      v-if="session.submissionStatus.value === 'unknown' || session.committedNeedsReload.value"
      role="status"
    >
      <p>
        {{
          session.submissionStatus.value === 'unknown'
            ? language.t(
                'platform.screen.MetadataGovernanceSurface.the-previous-metadata-submission-result-is-unknown-the-candidate-has',
                '原元数据提交结果未知，候选已保留；请先读取当前配置，不要重复保存。',
              )
            : language.t(
                'platform.screen.MetadataGovernanceSurface.metadata-was-saved-but-activation-or-page-synchronization-failed-read',
                '元数据已保存，但生效状态或页面同步失败；请读取当前配置，不要重复保存。',
              )
        }}
      </p>
      <UiButton :disabled="saving || loading || session.readingCurrent.value" @click="readCurrentMetadata">{{
        language.t(
          'platform.screen.MetadataGovernanceSurface.read-current-configuration-and-retain-candidate',
          '读取当前配置并保留候选',
        )
      }}</UiButton>
    </div>
    <RecordDetailPanel
      v-else-if="state.selectedMetadata.value && state.selectedRelation.value"
      class="module-tree-card"
      :title="
        creatingChildMetadata
          ? language.t('platform.screen.MetadataGovernanceSurface.add-child-metadata', '新增子元数据')
          : state.fieldEditorOpen.value && !fieldDraft.id
            ? language.t('platform.screen.ManagedDetailRelationSurface.add-value', '新增{value1}', {
                value1: fieldDraft.title || metadataFieldPropertyLabel(fieldPropertyEditorKind),
              })
            : selectedNodeIsField
              ? (selectedField?.title ?? language.t('platform.screen.QueryCriteriaGroupEditor.field', '字段'))
              : (state.selectedMetadata.value.title ??
                language.t('platform.governance.tab.metadata', '元数据'))
      "
      :subtitle="
        creatingChildMetadata
          ? language.t(
              'platform.screen.MetadataGovernanceSurface.owner-metadata-value',
              '所属元数据：{value1}',
              { value1: state.selectedMetadata.value.title },
            )
          : state.fieldEditorOpen.value
            ? state.selectedMetadata.value.title
            : selectedNodeIsField
              ? selectedField?.fieldName
              : state.selectedMetadata.value.alias
      "
    >
      <template v-if="session.dirty.value || session.submissionStatus.value === 'current-read'" #status>
        <span v-if="session.submissionStatus.value === 'current-read'" role="status">{{
          language.t(
            'platform.screen.MetadataGovernanceSurface.the-previous-submission-result-is-still-unknown-current-configuration-was',
            '原提交结果仍未知；已读取当前配置，后续保存须重新审阅。',
          )
        }}</span>
        <span v-if="session.dirty.value" class="metadata-edit-status" role="status">{{
          language.t(
            'platform.screen.MetadataGovernanceSurface.unsaved-lost-on-workspace-refresh-or-identity-change',
            '未保存 · 刷新工作区或切换身份后不会恢复',
          )
        }}</span>
      </template>
      <template #actions>
        <template v-if="state.fieldEditorOpen.value">
          <UiActionButton :disabled="saving" @click="cancelNodeEditor">{{
            language.t('platform.action.cancel', '取消')
          }}</UiActionButton>
          <UiActionButton emphasis="primary" :loading="saving" @click="stageFieldDraft">
            {{
              fieldPlanActive
                ? language.t('platform.screen.MetadataGovernanceSurface.keep-changes', '保留修改')
                : creatingChildMetadata
                  ? language.t('platform.screen.MetadataGovernanceSurface.create', '创建')
                  : language.t('platform.action.save', '保存')
            }}
          </UiActionButton>
        </template>
        <template v-else-if="fieldPlanActive">
          <UiDropdown v-slot="{ toggle }" :items="fieldCreationItems" @select="startCreateChildNode">
            <UiActionButton :disabled="saving || loading" @click.stop="toggle">{{
              language.t('platform.screen.MetadataGovernanceSurface.field', '＋ 字段')
            }}</UiActionButton>
          </UiDropdown>
          <UiActionButton :disabled="saving" @click="cancelFieldPlan">{{
            language.t('platform.screen.useModulePageSession.discard-changes', '放弃更改')
          }}</UiActionButton>
          <UiActionButton
            emphasis="primary"
            :loading="saving"
            :disabled="!session.dirty.value"
            @click="
              previewAndApply(
                language.t('platform.screen.MetadataGovernanceSurface.configuration-changes', '配置更改'),
              )
            "
            >{{
              language.t('platform.screen.MetadataGovernanceSurface.precheck-and-save', '预检并保存')
            }}</UiActionButton
          >
        </template>
        <template v-else-if="!selectedNodeIsField">
          <UiDropdown v-slot="{ toggle }" :items="fieldCreationItems" @select="startCreateChildNode">
            <UiActionButton :disabled="saving || loading" @click.stop="toggle">{{
              language.t('platform.screen.MetadataGovernanceSurface.field', '＋ 字段')
            }}</UiActionButton>
          </UiDropdown>
          <UiActionButton :disabled="saving || loading || sorting" @click="startFieldPlan">{{
            language.t('platform.screen.MetadataGovernanceSurface.add-fields-in-a-batch', '批量添加字段')
          }}</UiActionButton>
          <UiActionButton :disabled="saving || loading" @click="startCreateChildMetadataNode">{{
            language.t('platform.screen.MetadataGovernanceSurface.child-metadata', '＋ 子元数据')
          }}</UiActionButton>
        </template>
        <UiActionButton
          v-else
          :disabled="saving || !fieldEditableInSession(selectedField!)"
          :title="fieldProtectionReason(selectedField!)"
          @click="startEditField(selectedField!, fieldPropertyOf(selectedField!))"
          >{{ language.t('platform.action.edit', '编辑') }}</UiActionButton
        >
        <UiActionButton
          v-if="!state.fieldEditorOpen.value && !fieldPlanActive"
          intent="danger"
          :disabled="saving || (selectedNodeIsField ? !fieldEditableInSession(selectedField!) : false)"
          :title="selectedNodeIsField ? fieldProtectionReason(selectedField!) : undefined"
          @click="deleteSelectedNode"
        >
          {{ language.t('platform.action.delete', '删除') }}
        </UiActionButton>
      </template>

      <p v-if="!state.fieldEditorOpen.value && !fieldPlanActive" class="metadata-page-guidance">
        {{
          language.t(
            'platform.screen.MetadataGovernanceSurface.after-saving-fields-arrange-and-publish-them-in-page-configuration',
            '字段保存后，如需在业务页面展示或录入，请到“页面配置”编排并保存生效。',
          )
        }}
      </p>
      <section
        v-if="!state.fieldEditorOpen.value && !selectedNodeIsField && capabilityItems.length"
        class="metadata-capabilities"
      >
        <RecordContentSectionHeading
          :title="language.t('platform.screen.MetadataGovernanceSurface.core-capabilities', '基础能力')"
        />
        <label v-for="capability in capabilityItems" :key="capability.capability">
          <UiCheckbox
            :checked="capability.selected"
            :disabled="saving || loading || sorting || capability.enabled || !capability.configurable"
            @update:checked="selectCapability(capability.capability, $event)"
          >
            {{ capability.title
            }}{{
              capability.enabled
                ? language.t('platform.screen.MetadataGovernanceSurface.enabled', '（已启用）')
                : ''
            }}
          </UiCheckbox>
          <span>{{ capability.configurable ? capability.defaultDescription : capability.reason }}</span>
        </label>
      </section>
      <section v-if="state.fieldEditorOpen.value" class="metadata-inline-editor">
        <div class="metadata-editor-mode">
          <UiRadioGroup v-model:value="editorMode" :options="editorModeOptions" size="small" />
        </div>
        <RecordFormGrid @submit.prevent="stageFieldDraft">
          <template v-if="!fieldDraft.id && childNodeType === 'CHILD_METADATA'">
            <label v-if="editorMode === 'ADVANCED' || Boolean(fieldDraft.id)">
              <RecordFieldLabel required>{{
                language.t(
                  'platform.screen.MetadataGovernanceSurface.child-metadata-alias',
                  '子元数据标识（alias）',
                )
              }}</RecordFieldLabel>
              <UiInput
                :value="childMetadataDraft.alias"
                required
                :placeholder="
                  language.t(
                    'platform.screen.MetadataGovernanceSurface.for-example-exam-participant',
                    '例如 exam_participant',
                  )
                "
                @update:value="updateChildAlias"
              />
              <span
                v-if="childValidationAttempted && childAliasError"
                class="metadata-field-error"
                role="alert"
                >{{ childAliasError }}</span
              >
            </label>
            <label>
              <RecordFieldLabel required>{{
                language.t('platform.screen.MetadataGovernanceSurface.child-metadata-name', '子元数据名称')
              }}</RecordFieldLabel>
              <UiInput
                v-model:value="childMetadataDraft.title"
                required
                :placeholder="
                  language.t(
                    'platform.screen.MetadataGovernanceSurface.for-example-exam-participant-label',
                    '例如 参考学生',
                  )
                "
              />
              <span
                v-if="childValidationAttempted && !childMetadataDraft.title.trim()"
                class="metadata-field-error"
                role="alert"
                >{{
                  language.t(
                    'platform.screen.MetadataGovernanceSurface.enter-a-child-metadata-name',
                    '请填写子元数据名称',
                  )
                }}</span
              >
              <span v-if="editorMode === 'SIMPLE' && childMetadataDraft.alias" class="metadata-alias-hint"
                >{{ language.t('platform.screen.MetadataGovernanceSurface.alias', '标识：')
                }}{{ childMetadataDraft.alias }}</span
              >
            </label>
            <label v-if="editorMode === 'ADVANCED'">
              <span>{{
                language.t('platform.screen.MetadataGovernanceSurface.schema-optional', 'Schema（可选）')
              }}</span>
              <UiInput
                v-model:value="childMetadataDraft.schemaName"
                :placeholder="
                  language.t('platform.screen.MetadataGovernanceSurface.default-public', '默认 public')
                "
              />
            </label>
            <label v-if="editorMode === 'ADVANCED'">
              <span>{{
                language.t(
                  'platform.screen.MetadataGovernanceSurface.physical-table-name-optional',
                  '物理表名（可选）',
                )
              }}</span>
              <UiInput
                v-model:value="childMetadataDraft.tableName"
                :placeholder="
                  language.t(
                    'platform.screen.MetadataGovernanceSurface.generated-from-the-application-and-alias-by-default',
                    '默认按应用和 alias 生成',
                  )
                "
              />
            </label>
          </template>
          <template v-else>
            <template v-if="fieldPropertyEditorKind === 'MODULE_REFERENCE'">
              <label>
                <RecordFieldLabel required>{{
                  language.t('platform.workflow-task-editor.target-module', '目标模块')
                }}</RecordFieldLabel>
                <UiSelect
                  :value="fieldPropertyDraft.referenceConfig!.targetModuleAlias"
                  :options="referenceModuleOptions"
                  show-search
                  :filter-option="false"
                  :loading="choicesLoading"
                  style="width: 100%"
                  :placeholder="
                    language.t(
                      'platform.screen.MetadataGovernanceSurface.select-target-module',
                      '选择目标模块',
                    )
                  "
                  @search="referenceSearch = $event"
                  @update:value="updateReferenceTargetModuleAlias"
                />
              </label>
            </template>
            <label v-else-if="fieldPropertyEditorKind === 'DICTIONARY'">
              <RecordFieldLabel required>{{
                language.t('platform.screen.MetadataGovernanceSurface.target-dictionary', '目标字典')
              }}</RecordFieldLabel>
              <UiSelect
                :value="dictionaryValue"
                :options="dictionaryOptions"
                show-search
                :filter-option="false"
                :loading="choicesLoading"
                style="width: 100%"
                :placeholder="
                  language.t(
                    'platform.screen.MetadataGovernanceSurface.select-target-dictionary',
                    '选择目标字典',
                  )
                "
                @search="dictionarySearch = $event"
                @update:value="updateDictionary"
              />
            </label>
            <label>
              <span>{{
                language.t('platform.screen.MetadataGovernanceSurface.display-name', '显示名称')
              }}</span>
              <UiInput
                :value="fieldDraft.title"
                :placeholder="
                  language.t(
                    'platform.screen.MetadataGovernanceSurface.for-example-customer-name',
                    '例如 客户名称',
                  )
                "
                @update:value="updateFieldTitle"
              />
            </label>
            <p
              v-if="referenceTargetFieldCatalogProblem || choicesError"
              role="alert"
              class="field-property-error record-form-full-row"
            >
              {{ referenceTargetFieldCatalogProblem || choicesError }}
            </p>
            <label v-if="editorMode === 'ADVANCED' || Boolean(fieldDraft.id)">
              <RecordFieldLabel required>{{
                language.t('platform.screen.MetadataGovernanceSurface.field-name', '字段名称')
              }}</RecordFieldLabel>
              <UiInput
                :value="fieldDraft.fieldName"
                @update:value="updateFieldName"
                :disabled="Boolean(fieldDraft.id)"
                :placeholder="
                  language.t(
                    'platform.screen.MetadataGovernanceSurface.for-example-customername',
                    '例如 customerName',
                  )
                "
              />
            </label>
            <label v-if="editorMode === 'ADVANCED'">
              <RecordFieldLabel required>{{
                language.t('platform.screen.MetadataGovernanceSurface.physical-column-name', '物理列名')
              }}</RecordFieldLabel>
              <UiInput
                :value="fieldDraft.columnName"
                @update:value="updateColumnName"
                :disabled="Boolean(fieldDraft.id)"
                :placeholder="
                  language.t(
                    'platform.screen.MetadataGovernanceSurface.for-example-customer-name-label',
                    '例如 customer_name',
                  )
                "
              />
            </label>
            <label v-if="fieldPropertyEditorKind === 'BASIC'">
              <RecordFieldLabel required>{{
                language.t(
                  'platform.screen.MetadataGovernanceSurface.storage-field-specification',
                  '存储字段规格',
                )
              }}</RecordFieldLabel>
              <UiSelect
                v-model:value="fieldDraft.fieldSpecAlias"
                :options="editableFieldSpecOptions"
                :placeholder="
                  language.t(
                    'platform.screen.MetadataGovernanceSurface.select-field-specification',
                    '选择字段规格',
                  )
                "
                style="width: 100%"
              />
            </label>
            <section v-if="fieldPropertyEditorKind === 'MODULE_REFERENCE'" class="record-form-full-row">
              <h3>
                {{
                  language.t(
                    'platform.screen.MetadataGovernanceSurface.fill-values-on-selection',
                    '选择后回填',
                  )
                }}
              </h3>
              <p>
                {{
                  language.t(
                    'platform.screen.MetadataGovernanceSurface.when-selecting-a-referenced-record-copy-specified-values-into-this',
                    '选择引用记录时，将指定值填入当前表单。保存后的值独立保留，后续重新打开或保存不会随来源改动。',
                  )
                }}
              </p>
              <div
                v-for="(mapping, index) in referenceAffectMappings"
                :key="index"
                class="orchestration-form-grid"
              >
                <label
                  ><span>{{
                    language.t('platform.screen.MetadataGovernanceSurface.source-field', '来源字段')
                  }}</span
                  ><UiSelect
                    :value="mapping.split(':')[0] || undefined"
                    :options="referenceAffectSourceOptions"
                    :placeholder="
                      language.t(
                        'platform.screen.MetadataGovernanceSurface.select-source-field',
                        '选择来源字段',
                      )
                    "
                    @update:value="updateReferenceAffect(index, 0, $event)"
                /></label>
                <label
                  ><span>{{
                    language.t('platform.screen.MetadataGovernanceSurface.destination-field', '填入字段')
                  }}</span
                  ><UiSelect
                    :value="mapping.split(':')[1] || undefined"
                    :options="referenceAffectTargetOptions"
                    :placeholder="
                      language.t(
                        'platform.screen.MetadataGovernanceSurface.select-an-existing-saved-field',
                        '选择当前已保存字段',
                      )
                    "
                    @update:value="updateReferenceAffect(index, 1, $event)"
                /></label>
                <UiButton @click="removeReferenceAffect(index)">{{
                  language.t('platform.screen.MetadataGovernanceSurface.remove-value-mapping', '移除回填')
                }}</UiButton>
              </div>
              <UiButton
                :disabled="referenceAffectMappings.length >= 8 || referenceTargetFieldCatalogLoading"
                @click="addReferenceAffect"
                >{{
                  language.t('platform.screen.MetadataGovernanceSurface.add-value-mapping', '添加回填')
                }}</UiButton
              >
            </section>
            <template v-if="editorMode === 'ADVANCED' && fieldPropertyEditorKind === 'MODULE_REFERENCE'">
              <div class="orchestration-form-grid record-form-full-row">
                <label
                  ><span>{{
                    language.t('platform.screen.MetadataGovernanceSurface.matching-key-field', '匹配键字段')
                  }}</span
                  ><UiSelect
                    v-model:value="fieldPropertyDraft.referenceConfig!.targetKeyField"
                    :options="referenceKeyFieldOptions"
                    :loading="referenceTargetFieldCatalogLoading"
                    :disabled="Boolean(referenceTargetFieldCatalogError)"
                    :placeholder="
                      language.t(
                        'platform.screen.MetadataGovernanceSurface.select-target-key-field',
                        '请选择目标键字段',
                      )
                    "
                    style="width: 100%"
                /></label>
                <label
                  ><span>{{
                    language.t(
                      'platform.screen.MetadataGovernanceSurface.target-display-field',
                      '目标展示字段',
                    )
                  }}</span
                  ><UiSelect
                    v-model:value="fieldPropertyDraft.referenceConfig!.targetLabelField"
                    :options="referenceLabelFieldOptions"
                    :loading="referenceTargetFieldCatalogLoading"
                    :disabled="Boolean(referenceTargetFieldCatalogError)"
                    :placeholder="
                      language.t(
                        'platform.screen.MetadataGovernanceSurface.select-target-display-field',
                        '请选择目标展示字段',
                      )
                    "
                    style="width: 100%"
                /></label>
              </div>
              <div class="orchestration-form-grid record-form-full-row">
                <label
                  ><span>{{
                    language.t(
                      'platform.screen.MetadataGovernanceSurface.when-a-referenced-record-is-deleted',
                      '被引用记录删除时',
                    )
                  }}</span
                  ><UiSelect
                    v-model:value="fieldPropertyDraft.referenceConfig!.targetUnavailablePolicy"
                    :options="[
                      {
                        value: 'PRESERVE_HISTORY',
                        label: language.t(
                          'platform.screen.MetadataGovernanceSurface.keep-the-current-record-and-its-original-reference',
                          '保留当前记录及原引用',
                        ),
                      },
                      {
                        value: 'RESTRICT',
                        label: language.t(
                          'platform.screen.MetadataGovernanceSurface.prevent-deletion-of-the-referenced-record',
                          '有引用时阻止删除目标',
                        ),
                      },
                      {
                        value: 'CASCADE_DELETE',
                        label: language.t(
                          'platform.screen.MetadataGovernanceSurface.also-delete-the-current-record-that-references-it',
                          '同时删除引用它的当前记录',
                        ),
                      },
                    ]"
                    style="width: 100%"
                /></label>
              </div>
              <label class="record-form-full-row">
                <UiCheckbox v-model:checked="fieldPropertyDraft.referenceConfig!.requireEnabled">
                  {{
                    language.t(
                      'platform.screen.MetadataGovernanceSurface.require-the-referenced-record-to-exist-and-be-enabled-on',
                      '每次保存时要求被引用记录存在且已启用',
                    )
                  }}
                </UiCheckbox>
              </label>
              <label class="record-form-full-row"
                ><span
                  :title="
                    language.t(
                      'platform.screen.MetadataGovernanceSurface.read-for-display-without-writing-to-a-business-field',
                      '读取时带出，不写入业务字段',
                    )
                  "
                  >{{
                    language.t(
                      'platform.screen.MetadataGovernanceSurface.referenced-display-fields',
                      '关联展示字段',
                    )
                  }}</span
                ><UiTextArea
                  v-model:value="projectionMappingsText"
                  :rows="3"
                  :placeholder="
                    language.t(
                      'platform.screen.MetadataGovernanceSurface.one-per-line-for-example-title-subjectcategoryidtitle',
                      '每行一项，例如 title:subjectCategoryIdTitle',
                    )
                  "
              /></label>
            </template>
            <template v-else-if="editorMode === 'ADVANCED' && fieldPropertyEditorKind === 'DICTIONARY'">
              <label
                ><span>{{
                  language.t('platform.screen.MetadataGovernanceSurface.selection-mode', '选择方式')
                }}</span
                ><UiSelect
                  v-model:value="fieldPropertyDraft.dictionaryConfig!.selectionMode"
                  :options="[
                    {
                      value: 'SINGLE',
                      label: language.t('platform.screen.MetadataGovernanceSurface.single-selection', '单选'),
                    },
                    {
                      value: 'MULTIPLE',
                      label: language.t(
                        'platform.screen.MetadataGovernanceSurface.multiple-selection',
                        '多选',
                      ),
                    },
                  ]"
                  style="width: 100%"
              /></label>
            </template>
            <label v-if="fieldPropertyEditorKind === 'BASIC' || fieldPropertyEditorKind === 'DICTIONARY'">
              <span>{{
                language.t('platform.screen.MetadataGovernanceSurface.fixed-default-value', '固定默认值')
              }}</span>
              <UiInput
                v-model:value="fixedDefaultValue"
                :disabled="!fixedDefaultEditable"
                :maxlength="512"
                :placeholder="
                  language.t(
                    'platform.screen.MetadataGovernanceSurface.used-when-omitted-for-example-1-existing-data-is-unchanged',
                    '未填写时使用，例如 1；不修改已有数据',
                  )
                "
              />
            </label>
            <div class="orchestration-form-flags record-form-full-row">
              <UiCheckbox v-model:checked="fieldDraft.required">{{
                language.t('platform.form.required-marker', '必填')
              }}</UiCheckbox>
              <UiCheckbox v-model:checked="fieldDraft.uniqueField">{{
                language.t('platform.screen.MetadataGovernanceSurface.unique', '唯一')
              }}</UiCheckbox>
            </div>
            <div v-if="editorMode === 'ADVANCED'" class="orchestration-form-flags record-form-full-row">
              <UiCheckbox v-model:checked="fieldDraft.indexed">{{
                language.t('platform.screen.MetadataGovernanceSurface.create-index', '建立索引')
              }}</UiCheckbox>
              <UiCheckbox v-model:checked="fieldDraft.sortableField">{{
                language.t('platform.screen.MetadataGovernanceSurface.sort-field', '排序字段')
              }}</UiCheckbox>
              <UiCheckbox v-model:checked="fieldDraft.titleField">{{
                language.t('platform.screen.MetadataGovernanceSurface.title-field', '标题字段')
              }}</UiCheckbox>
              <UiCheckbox v-model:checked="fieldDraft.enabled">{{
                language.t('platform.screen.MetadataGovernanceSurface.enable-field', '启用字段')
              }}</UiCheckbox>
            </div>
          </template>
        </RecordFormGrid>
      </section>

      <section v-else-if="fieldPlanActive" class="metadata-node-summary" data-testid="metadata-field-plan">
        <RecordContentSectionHeading
          :title="language.t('platform.screen.MetadataGovernanceSurface.fields-to-add', '待新增字段')"
          :subtitle="
            language.t(
              'platform.screen.MetadataGovernanceSurface.continue-adding-changing-or-removing-fields-then-precheck-and-confirm',
              '可继续添加、修改或移除，完成后统一预检并确认保存。',
            )
          "
        />
        <article v-for="field in fieldPlanEntries" :key="field.fieldName" class="field-node-card">
          <strong>{{ field.title }}</strong>
          <p>
            {{ metadataFieldPropertyLabel(fieldPropertyOf(field).kind) }} ·
            {{ fieldSpecDisplayLabel(field.fieldSpecAlias, state.fieldSpecs.value, language) }} ·
            {{
              field.required
                ? language.t('platform.form.required-marker', '必填')
                : language.t('platform.screen.MetadataGovernanceSurface.optional', '非必填')
            }}
          </p>
          <p v-if="fieldPropertyOf(field).referenceConfig">
            {{ language.t('platform.screen.MetadataGovernanceSurface.reference', '引用：')
            }}{{ fieldPropertyOf(field).referenceConfig?.targetModuleAlias }}
          </p>
          <p v-if="fieldPropertyOf(field).dictionaryConfig">
            {{ language.t('platform.screen.MetadataGovernanceSurface.dictionary', '字典：')
            }}{{ fieldPropertyOf(field).dictionaryConfig?.dictionaryApplicationAlias }}.{{
              fieldPropertyOf(field).dictionaryConfig?.dictionaryCategoryAlias
            }}
          </p>
          <div class="metadata-toolbar__actions">
            <UiActionButton :disabled="saving" @click="editPlanField(field)">{{
              language.t('platform.screen.RecordQueryListPanel.edit', '修改')
            }}</UiActionButton>
            <UiActionButton :disabled="saving" @click="removePlanField(field)">{{
              language.t('platform.screen.RecordPermissionDialog.remove', '移除')
            }}</UiActionButton>
          </div>
        </article>
        <p v-if="!fieldPlanEntries.length">
          {{
            language.t(
              'platform.screen.MetadataGovernanceSurface.no-new-business-fields-selected-core-capabilities-will-also-be',
              '当前没有新增业务字段。已选择的基础能力会在本次确认中一并保存。',
            )
          }}
        </p>
      </section>
      <section v-else-if="!selectedNodeIsField" class="metadata-node-summary">
        <RecordContentSectionHeading
          :title="language.t('platform.screen.MetadataGovernanceSurface.metadata-information', '元数据信息')"
        />
        <RecordDetailFields
          :record="{
            entityRole: selectedRelationIsMain
              ? language.t('platform.screen.MetadataGovernanceSurface.main-metadata', '主元数据')
              : language.t('platform.screen.MetadataGovernanceSurface.child-metadata-label', '子元数据'),
            physicalTable:
              state.selectedMetadata.value.tableName ||
              language.t(
                'platform.screen.MetadataGovernanceSurface.the-platform-generates-the-physical-table',
                '物理表由平台生成',
              ),
            parentMetadata: selectedRelationIsMain
              ? undefined
              : state.selectedRelation.value.parentMetadataId ||
                language.t('platform.screen.MetadataGovernanceSurface.unbound', '未绑定'),
          }"
          :field-names="
            selectedRelationIsMain
              ? ['entityRole', 'physicalTable']
              : ['entityRole', 'physicalTable', 'parentMetadata']
          "
          :fallback="{
            entityRole: {
              label: language.t('platform.screen.MetadataGovernanceSurface.metadata-role', '元数据角色'),
            },
            physicalTable: {
              label: language.t('platform.screen.MetadataGovernanceSurface.physical-table', '物理表'),
            },
            parentMetadata: {
              label: language.t('platform.screen.MetadataGovernanceSurface.parent-metadata', '父元数据'),
            },
          }"
        />
      </section>

      <section v-else-if="!state.fieldEditorOpen.value" class="field-node-card">
        <RecordContentSectionHeading
          :title="language.t('platform.screen.MetadataGovernanceSurface.field-facts', '字段事实')"
          :subtitle="
            fieldProtectionReason(selectedField!) ||
            language.t(
              'platform.screen.MetadataGovernanceSurface.business-field-editable-in-the-metadata-session',
              '业务字段，可在元数据编辑会话中调整。',
            )
          "
        />
        <RecordDetailFields
          :record="{
            fieldName: selectedField?.fieldName,
            columnName: selectedField?.columnName || '—',
            fieldSpec: fieldSpecDisplayLabel(selectedField?.fieldSpecAlias, state.fieldSpecs.value, language),
            property: metadataFieldPropertyLabel(fieldPropertyOf(selectedField!).kind),
            constraints: `${selectedField?.required ? language.t('platform.form.required-marker', '必填') : language.t('platform.screen.MetadataGovernanceSurface.optional', '非必填')}${selectedField?.uniqueField ? language.t('platform.screen.MetadataGovernanceSurface.unique-label', ' · 唯一') : ''}${selectedField?.indexed ? language.t('platform.screen.MetadataGovernanceSurface.indexed', ' · 索引') : ''}`,
            ownership: fieldSourceOf(selectedField!),
          }"
          :field-names="['fieldName', 'columnName', 'fieldSpec', 'property', 'constraints', 'ownership']"
          :fallback="{
            fieldName: {
              label: language.t('platform.screen.MetadataGovernanceSurface.field-name-label', '字段名'),
            },
            columnName: {
              label: language.t('platform.screen.MetadataGovernanceSurface.physical-column', '物理列'),
            },
            fieldSpec: {
              label: language.t('platform.screen.MetadataGovernanceSurface.field-specification', '字段规格'),
            },
            property: {
              label: language.t('platform.screen.MetadataGovernanceSurface.data-property', '数据属性'),
            },
            constraints: {
              label: language.t('platform.screen.MetadataGovernanceSurface.constraints', '约束'),
            },
            ownership: {
              label: language.t('platform.screen.MetadataGovernanceSurface.governance-owner', '治理归属'),
            },
          }"
        />
      </section>
    </RecordDetailPanel>
    <RecordDetailPanel
      v-else
      :title="
        state.mainEditorOpen.value
          ? language.t('platform.screen.MetadataGovernanceSurface.create-main-metadata', '新建主元数据')
          : language.t('platform.governance.tab.metadata', '元数据')
      "
      :subtitle="state.mainEditorOpen.value ? (moduleTitle ?? moduleAlias) : undefined"
    >
      <template v-if="state.mainEditorOpen.value" #status>
        <UiRadioGroup v-model:value="editorMode" :options="editorModeOptions" size="small" />
      </template>
      <template #actions>
        <template v-if="state.mainEditorOpen.value">
          <UiActionButton :disabled="saving" @click="cancelNodeEditor">{{
            language.t('platform.action.cancel', '取消')
          }}</UiActionButton>
          <UiActionButton emphasis="primary" :loading="saving" @click="createMainMetadata">
            {{ language.t('platform.action.save', '保存') }}
          </UiActionButton>
        </template>
        <UiActionButton
          v-else
          emphasis="primary"
          :disabled="loading || saving"
          @click="startCreateMainMetadata"
        >
          {{ language.t('platform.screen.MetadataGovernanceSurface.create-main-metadata', '新建主元数据') }}
        </UiActionButton>
      </template>
      <section v-if="state.mainEditorOpen.value" class="metadata-inline-editor">
        <p role="status">
          {{
            language.t(
              'platform.screen.MetadataGovernanceSurface.create-the-data-structure-first-after-confirming-the-save-configure',
              '本步先创建数据结构。确认保存后，再配置要记录的字段和明细；本步不录入业务记录。',
            )
          }}
        </p>
        <RecordFormGrid @submit.prevent="createMainMetadata">
          <label v-if="editorMode === 'ADVANCED'">
            <RecordFieldLabel required>{{
              language.t('platform.screen.MetadataGovernanceSurface.metadata-alias', '元数据标识（alias）')
            }}</RecordFieldLabel>
            <UiInput
              v-model:value="mainMetadataDraft.alias"
              required
              :placeholder="
                language.t('platform.screen.MetadataGovernanceSurface.for-example-customer', '例如 customer')
              "
            />
          </label>
          <label>
            <RecordFieldLabel required>{{
              language.t('platform.screen.MetadataGovernanceSurface.metadata-name', '元数据名称')
            }}</RecordFieldLabel>
            <UiInput
              v-model:value="mainMetadataDraft.title"
              required
              :placeholder="
                language.t(
                  'platform.screen.MetadataGovernanceSurface.for-example-customer-label',
                  '例如 客户',
                )
              "
            />
          </label>
          <label v-if="editorMode === 'ADVANCED'">
            <span>{{
              language.t('platform.screen.MetadataGovernanceSurface.schema-optional', 'Schema（可选）')
            }}</span>
            <UiInput
              v-model:value="mainMetadataDraft.schemaName"
              :placeholder="
                language.t('platform.screen.MetadataGovernanceSurface.default-public', '默认 public')
              "
            />
          </label>
          <label v-if="editorMode === 'ADVANCED'">
            <span>{{
              language.t(
                'platform.screen.MetadataGovernanceSurface.physical-table-name-optional',
                '物理表名（可选）',
              )
            }}</span>
            <UiInput
              v-model:value="mainMetadataDraft.tableName"
              :placeholder="
                language.t(
                  'platform.screen.MetadataGovernanceSurface.generated-from-the-application-and-alias-by-default',
                  '默认按应用和 alias 生成',
                )
              "
            />
          </label>
        </RecordFormGrid>
      </section>
      <UiEmpty
        v-else
        :description="
          language.t(
            'platform.screen.MetadataGovernanceSurface.start-with-create-main-metadata-in-the-upper-right',
            '从右上角“新建主元数据”开始配置元数据',
          )
        "
      />
    </RecordDetailPanel>
  </ManagementWorkspace>
</template>

<style scoped>
.metadata-editor-mode {
  margin-bottom: 12px;
}
/* The metadata editor is a form, so it does not need the workspace table minimum width. */
.metadata-model-workspace :deep(.management-workspace__grid) {
  grid-template-columns:
    repeat(var(--muyun-management-explorer-count), var(--muyun-management-explorer-width))
    minmax(0, 1fr);
}
@media (max-width: 760px) {
  .metadata-model-workspace :deep(.management-workspace__grid) {
    grid-template-columns: 1fr;
  }
}

.metadata-field-error {
  color: var(--muyun-danger-base);
}
.metadata-alias-hint {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.metadata-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
}

.metadata-toolbar__identity {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.metadata-toolbar__identity strong {
  overflow: hidden;
  color: var(--muyun-text);
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-toolbar__identity span {
  overflow: hidden;
  color: var(--muyun-text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-toolbar > :first-child {
  min-width: 0;
}

.metadata-toolbar__actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.metadata-system-fields-toggle {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 12px;
  white-space: nowrap;
}

.metadata-edit-status {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  color: var(--muyun-color-primary);
  font-size: 12px;
}

.metadata-edit-status span {
  color: var(--muyun-text-muted);
}

.metadata-page-guidance {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.metadata-node-summary,
.field-node-card {
  display: grid;
  gap: 10px;
  padding: 2px 0;
}

.metadata-capabilities {
  display: grid;
  gap: 10px;
  margin-bottom: 16px;
}

.metadata-capabilities > label {
  display: grid;
  gap: 4px;
}

.metadata-capabilities > label > span {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.metadata-inline-editor {
  display: grid;
  min-width: 0;
  padding: 0;
}

.record-form-full-row {
  grid-column: 1 / -1;
}

.orchestration-form-flags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  align-items: center;
  font-size: 13px;
}

.orchestration-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.field-property-heading {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--muyun-info-soft);
  color: var(--muyun-text-body);
}

.field-property-heading span {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.field-property-binding {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.field-property-binding strong {
  color: var(--muyun-text-body);
}

.field-property-note {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.field-property-error {
  margin: -6px 0 0;
  color: var(--muyun-danger-base);
  font-size: 12px;
}
</style>
