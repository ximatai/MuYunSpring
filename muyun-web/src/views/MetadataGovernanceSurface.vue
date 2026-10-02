<script setup lang="ts">
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
import { useModuleContext, useAssistantSurfaceHost, createAssistantTurnRequester } from '@muyun/web-core';
import { moduleRuntimeActivationRefreshKey } from './moduleRuntimeActivation';
import { createMetadataEditorSession, type MetadataEditorSession } from './metadataEditorSession';
import { useMetadataWorkspace } from './metadataWorkspace';
import { createMetadataGovernanceAssistantSurface } from './metadataGovernanceAssistantSurface';
import {
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
const fieldPlanActive = binding('fieldPlanActive');
const fieldPlanEntries = binding('fieldPlanEntries');
const sorting = binding('sorting');
const mainMetadataDraft = binding('mainMetadataDraft');
const fieldDraft = binding('fieldDraft');
const fieldPropertyDraft = binding('fieldPropertyDraft');
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
  '元数据',
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
    <ManagementExplorerColumn title="元数据" collapsible :has-selection="Boolean(selectedTreeKey)">
      <RecordExplorerPanel
        title="元数据"
        :searchable="false"
        :collapse-action="false"
        :refresh-disabled="saving || fieldPlanActive"
        @refresh="loadWorkspace"
      >
        <template #actions>
          <UiButton
            v-if="!state.fieldEditorOpen.value"
            icon-name="swap-vertical"
            icon-only
            size="small"
            type="text"
            :title="sorting ? '结束排序' : '调整排序'"
            :aria-label="sorting ? '结束排序' : '调整排序'"
            :selected="sorting"
            :disabled="saving || loading || fieldPlanActive"
            @click="toggleSorting"
          />
          <label class="metadata-system-fields-toggle">
            <span>系统字段</span>
            <UiSwitch
              v-model:checked="showSystemFields"
              size="small"
              :title="showSystemFields ? '隐藏系统字段' : '显示系统字段'"
              :aria-label="showSystemFields ? '隐藏系统字段' : '显示系统字段'"
            />
          </label>
        </template>
        <UiSpin v-if="loading && metadataTreeNodes.length === 0" tip="加载元数据" />
        <UiTree
          v-else
          v-model:expanded-keys="expandedTreeKeys"
          :nodes="metadataTreeNodes"
          :selected-key="selectedTreeKey"
          :draggable="sorting && !saving && !loading && !state.fieldEditorOpen.value"
          :can-drag="canDragMetadataNode"
          :allow-drop="allowMetadataModelDrop"
          @select="selectMetadataTreeNode"
          @drop="handleMetadataModelDrop"
        />
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <RecordDetailPanel
      v-if="state.selectedMetadata.value && state.selectedRelation.value"
      class="module-tree-card"
      :title="
        creatingChildMetadata
          ? '新增子元数据'
          : state.fieldEditorOpen.value && !fieldDraft.id
            ? `新增${fieldDraft.title || metadataFieldPropertyLabel(fieldPropertyEditorKind)}`
            : selectedNodeIsField
              ? (selectedField?.title ?? '字段')
              : (state.selectedMetadata.value.title ?? '元数据')
      "
      :subtitle="
        creatingChildMetadata
          ? `所属元数据：${state.selectedMetadata.value.title}`
          : state.fieldEditorOpen.value
            ? state.selectedMetadata.value.title
            : selectedNodeIsField
              ? selectedField?.fieldName
              : state.selectedMetadata.value.alias
      "
    >
      <template v-if="session.dirty.value" #status>
        <span class="metadata-edit-status" role="status">未保存 · 刷新工作区或切换身份后不会恢复</span>
      </template>
      <template #actions>
        <template v-if="state.fieldEditorOpen.value">
          <UiActionButton :disabled="saving" @click="cancelNodeEditor">取消</UiActionButton>
          <UiActionButton emphasis="primary" :loading="saving" @click="stageFieldDraft">
            {{ fieldPlanActive ? '保留修改' : creatingChildMetadata ? '创建' : '保存' }}
          </UiActionButton>
        </template>
        <template v-else-if="fieldPlanActive">
          <UiDropdown v-slot="{ toggle }" :items="fieldCreationItems" @select="startCreateChildNode">
            <UiActionButton :disabled="saving || loading" @click.stop="toggle">＋ 字段</UiActionButton>
          </UiDropdown>
          <UiActionButton :disabled="saving" @click="cancelFieldPlan">放弃更改</UiActionButton>
          <UiActionButton
            emphasis="primary"
            :loading="saving"
            :disabled="!fieldPlanEntries.length"
            @click="previewAndApply('字段更改')"
            >预检并保存</UiActionButton
          >
        </template>
        <template v-else-if="!selectedNodeIsField">
          <UiDropdown v-slot="{ toggle }" :items="fieldCreationItems" @select="startCreateChildNode">
            <UiActionButton :disabled="saving || loading" @click.stop="toggle">＋ 字段</UiActionButton>
          </UiDropdown>
          <UiActionButton :disabled="saving || loading || sorting" @click="startFieldPlan"
            >批量添加字段</UiActionButton
          >
          <UiActionButton :disabled="saving || loading" @click="startCreateChildMetadataNode"
            >＋ 子元数据</UiActionButton
          >
        </template>
        <UiActionButton
          v-else
          :disabled="saving || !fieldEditableInSession(selectedField!)"
          :title="fieldProtectionReason(selectedField!)"
          @click="startEditField(selectedField!, fieldPropertyOf(selectedField!))"
          >编辑</UiActionButton
        >
        <UiActionButton
          v-if="!state.fieldEditorOpen.value && !fieldPlanActive"
          intent="danger"
          :disabled="saving || (selectedNodeIsField ? !fieldEditableInSession(selectedField!) : false)"
          :title="selectedNodeIsField ? fieldProtectionReason(selectedField!) : undefined"
          @click="deleteSelectedNode"
        >
          删除
        </UiActionButton>
      </template>

      <p v-if="!state.fieldEditorOpen.value && !fieldPlanActive" class="metadata-page-guidance">
        字段保存后，如需在业务页面展示或录入，请到“页面配置”编排并保存生效。
      </p>
      <section v-if="state.fieldEditorOpen.value" class="metadata-inline-editor">
        <div class="metadata-editor-mode">
          <UiRadioGroup v-model:value="editorMode" :options="editorModeOptions" size="small" />
        </div>
        <RecordFormGrid @submit.prevent="stageFieldDraft">
          <template v-if="!fieldDraft.id && childNodeType === 'CHILD_METADATA'">
            <label v-if="editorMode === 'ADVANCED' || Boolean(fieldDraft.id)">
              <RecordFieldLabel required>子元数据标识（alias）</RecordFieldLabel>
              <UiInput
                :value="childMetadataDraft.alias"
                required
                placeholder="例如 exam_participant"
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
              <RecordFieldLabel required>子元数据名称</RecordFieldLabel>
              <UiInput v-model:value="childMetadataDraft.title" required placeholder="例如 参考学生" />
              <span
                v-if="childValidationAttempted && !childMetadataDraft.title.trim()"
                class="metadata-field-error"
                role="alert"
                >请填写子元数据名称</span
              >
              <span v-if="editorMode === 'SIMPLE' && childMetadataDraft.alias" class="metadata-alias-hint"
                >标识：{{ childMetadataDraft.alias }}</span
              >
            </label>
            <label v-if="editorMode === 'ADVANCED'">
              <span>Schema（可选）</span>
              <UiInput v-model:value="childMetadataDraft.schemaName" placeholder="默认 public" />
            </label>
            <label v-if="editorMode === 'ADVANCED'">
              <span>物理表名（可选）</span>
              <UiInput v-model:value="childMetadataDraft.tableName" placeholder="默认按应用和 alias 生成" />
            </label>
          </template>
          <template v-else>
            <template v-if="fieldPropertyEditorKind === 'MODULE_REFERENCE'">
              <label>
                <RecordFieldLabel required>目标模块</RecordFieldLabel>
                <UiSelect
                  :value="fieldPropertyDraft.referenceConfig!.targetModuleAlias"
                  :options="referenceModuleOptions"
                  show-search
                  :filter-option="false"
                  :loading="choicesLoading"
                  style="width: 100%"
                  placeholder="选择目标模块"
                  @search="referenceSearch = $event"
                  @update:value="updateReferenceTargetModuleAlias"
                />
              </label>
            </template>
            <label v-else-if="fieldPropertyEditorKind === 'DICTIONARY'">
              <RecordFieldLabel required>目标字典</RecordFieldLabel>
              <UiSelect
                :value="dictionaryValue"
                :options="dictionaryOptions"
                show-search
                :filter-option="false"
                :loading="choicesLoading"
                style="width: 100%"
                placeholder="选择目标字典"
                @search="dictionarySearch = $event"
                @update:value="updateDictionary"
              />
            </label>
            <label>
              <span>显示名称</span>
              <UiInput
                :value="fieldDraft.title"
                placeholder="例如 客户名称"
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
              <RecordFieldLabel required>字段名称</RecordFieldLabel>
              <UiInput
                :value="fieldDraft.fieldName"
                @update:value="updateFieldName"
                :disabled="Boolean(fieldDraft.id)"
                placeholder="例如 customerName"
              />
            </label>
            <label v-if="editorMode === 'ADVANCED'">
              <RecordFieldLabel required>物理列名</RecordFieldLabel>
              <UiInput
                :value="fieldDraft.columnName"
                @update:value="updateColumnName"
                :disabled="Boolean(fieldDraft.id)"
                placeholder="例如 customer_name"
              />
            </label>
            <label v-if="fieldPropertyEditorKind === 'BASIC'">
              <RecordFieldLabel required>存储字段规格</RecordFieldLabel>
              <UiSelect
                v-model:value="fieldDraft.fieldSpecAlias"
                :options="editableFieldSpecOptions"
                placeholder="选择字段规格"
                style="width: 100%"
              />
            </label>
            <section v-if="fieldPropertyEditorKind === 'MODULE_REFERENCE'" class="record-form-full-row">
              <h3>选择后回填</h3>
              <p>
                选择引用记录时，将指定值填入当前表单。保存后的值独立保留，后续重新打开或保存不会随来源改动。
              </p>
              <div
                v-for="(mapping, index) in referenceAffectMappings"
                :key="index"
                class="orchestration-form-grid"
              >
                <label
                  ><span>来源字段</span
                  ><UiSelect
                    :value="mapping.split(':')[0] || undefined"
                    :options="referenceAffectSourceOptions"
                    placeholder="选择来源字段"
                    @update:value="updateReferenceAffect(index, 0, $event)"
                /></label>
                <label
                  ><span>填入字段</span
                  ><UiSelect
                    :value="mapping.split(':')[1] || undefined"
                    :options="referenceAffectTargetOptions"
                    placeholder="选择当前已保存字段"
                    @update:value="updateReferenceAffect(index, 1, $event)"
                /></label>
                <UiButton @click="removeReferenceAffect(index)">移除回填</UiButton>
              </div>
              <UiButton
                :disabled="referenceAffectMappings.length >= 8 || referenceTargetFieldCatalogLoading"
                @click="addReferenceAffect"
                >添加回填</UiButton
              >
            </section>
            <template v-if="editorMode === 'ADVANCED' && fieldPropertyEditorKind === 'MODULE_REFERENCE'">
              <div class="orchestration-form-grid record-form-full-row">
                <label
                  ><span>匹配键字段</span
                  ><UiSelect
                    v-model:value="fieldPropertyDraft.referenceConfig!.targetKeyField"
                    :options="referenceKeyFieldOptions"
                    :loading="referenceTargetFieldCatalogLoading"
                    :disabled="Boolean(referenceTargetFieldCatalogError)"
                    placeholder="请选择目标键字段"
                    style="width: 100%"
                /></label>
                <label
                  ><span>目标展示字段</span
                  ><UiSelect
                    v-model:value="fieldPropertyDraft.referenceConfig!.targetLabelField"
                    :options="referenceLabelFieldOptions"
                    :loading="referenceTargetFieldCatalogLoading"
                    :disabled="Boolean(referenceTargetFieldCatalogError)"
                    placeholder="请选择目标展示字段"
                    style="width: 100%"
                /></label>
              </div>
              <div class="orchestration-form-grid record-form-full-row">
                <label
                  ><span>被引用记录删除时</span
                  ><UiSelect
                    v-model:value="fieldPropertyDraft.referenceConfig!.targetUnavailablePolicy"
                    :options="[
                      { value: 'PRESERVE_HISTORY', label: '保留当前记录及原引用' },
                      { value: 'RESTRICT', label: '有引用时阻止删除目标' },
                      { value: 'CASCADE_DELETE', label: '同时删除引用它的当前记录' },
                    ]"
                    style="width: 100%"
                /></label>
              </div>
              <label class="record-form-full-row">
                <UiCheckbox v-model:checked="fieldPropertyDraft.referenceConfig!.requireEnabled">
                  每次保存时要求被引用记录存在且已启用
                </UiCheckbox>
              </label>
              <label class="record-form-full-row"
                ><span title="读取时带出，不写入业务字段">关联展示字段</span
                ><UiTextArea
                  v-model:value="projectionMappingsText"
                  :rows="3"
                  placeholder="每行一项，例如 title:subjectCategoryIdTitle"
              /></label>
            </template>
            <template v-else-if="editorMode === 'ADVANCED' && fieldPropertyEditorKind === 'DICTIONARY'">
              <label
                ><span>选择方式</span
                ><UiSelect
                  v-model:value="fieldPropertyDraft.dictionaryConfig!.selectionMode"
                  :options="[
                    { value: 'SINGLE', label: '单选' },
                    { value: 'MULTIPLE', label: '多选' },
                  ]"
                  style="width: 100%"
              /></label>
            </template>
            <div class="orchestration-form-flags record-form-full-row">
              <UiCheckbox v-model:checked="fieldDraft.required">必填</UiCheckbox>
              <UiCheckbox v-model:checked="fieldDraft.uniqueField">唯一</UiCheckbox>
            </div>
            <div v-if="editorMode === 'ADVANCED'" class="orchestration-form-flags record-form-full-row">
              <UiCheckbox v-model:checked="fieldDraft.indexed">建立索引</UiCheckbox>
              <UiCheckbox v-model:checked="fieldDraft.sortableField">排序字段</UiCheckbox>
              <UiCheckbox v-model:checked="fieldDraft.titleField">标题字段</UiCheckbox>
              <UiCheckbox v-model:checked="fieldDraft.enabled">启用字段</UiCheckbox>
            </div>
          </template>
        </RecordFormGrid>
      </section>

      <section v-else-if="fieldPlanActive" class="metadata-node-summary" data-testid="metadata-field-plan">
        <RecordContentSectionHeading
          title="待新增字段"
          subtitle="可继续添加、修改或移除，完成后统一预检并确认保存。"
        />
        <article v-for="field in fieldPlanEntries" :key="field.fieldName" class="field-node-card">
          <strong>{{ field.title }}</strong>
          <p>
            {{ metadataFieldPropertyLabel(fieldPropertyOf(field).kind) }} ·
            {{ fieldSpecDisplayLabel(field.fieldSpecAlias, state.fieldSpecs.value) }} ·
            {{ field.required ? '必填' : '非必填' }}
          </p>
          <p v-if="fieldPropertyOf(field).referenceConfig">
            引用：{{ fieldPropertyOf(field).referenceConfig?.targetModuleAlias }}
          </p>
          <p v-if="fieldPropertyOf(field).dictionaryConfig">
            字典：{{ fieldPropertyOf(field).dictionaryConfig?.dictionaryApplicationAlias }}.{{
              fieldPropertyOf(field).dictionaryConfig?.dictionaryCategoryAlias
            }}
          </p>
          <div class="metadata-toolbar__actions">
            <UiActionButton :disabled="saving" @click="editPlanField(field)">修改</UiActionButton>
            <UiActionButton :disabled="saving" @click="removePlanField(field)">移除</UiActionButton>
          </div>
        </article>
        <p v-if="!fieldPlanEntries.length">点击“＋ 字段”开始添加。当前没有需要保存的字段。</p>
      </section>
      <section v-else-if="!selectedNodeIsField" class="metadata-node-summary">
        <RecordContentSectionHeading title="元数据信息" />
        <RecordDetailFields
          :record="{
            entityRole: selectedRelationIsMain ? '主元数据' : '子元数据',
            physicalTable: state.selectedMetadata.value.tableName || '物理表由平台生成',
            parentMetadata: selectedRelationIsMain
              ? undefined
              : state.selectedRelation.value.parentMetadataId || '未绑定',
          }"
          :field-names="
            selectedRelationIsMain
              ? ['entityRole', 'physicalTable']
              : ['entityRole', 'physicalTable', 'parentMetadata']
          "
          :fallback="{
            entityRole: { label: '元数据角色' },
            physicalTable: { label: '物理表' },
            parentMetadata: { label: '父元数据' },
          }"
        />
      </section>

      <section v-else-if="!state.fieldEditorOpen.value" class="field-node-card">
        <RecordContentSectionHeading
          title="字段事实"
          :subtitle="fieldProtectionReason(selectedField!) || '业务字段，可在元数据编辑会话中调整。'"
        />
        <RecordDetailFields
          :record="{
            fieldName: selectedField?.fieldName,
            columnName: selectedField?.columnName || '—',
            fieldSpec: fieldSpecDisplayLabel(selectedField?.fieldSpecAlias, state.fieldSpecs.value),
            property: metadataFieldPropertyLabel(fieldPropertyOf(selectedField!).kind),
            constraints: `${selectedField?.required ? '必填' : '非必填'}${selectedField?.uniqueField ? ' · 唯一' : ''}${selectedField?.indexed ? ' · 索引' : ''}`,
            ownership: fieldSourceOf(selectedField!),
          }"
          :field-names="['fieldName', 'columnName', 'fieldSpec', 'property', 'constraints', 'ownership']"
          :fallback="{
            fieldName: { label: '字段名' },
            columnName: { label: '物理列' },
            fieldSpec: { label: '字段规格' },
            property: { label: '数据属性' },
            constraints: { label: '约束' },
            ownership: { label: '治理归属' },
          }"
        />
      </section>
    </RecordDetailPanel>
    <RecordDetailPanel
      v-else
      :title="state.mainEditorOpen.value ? '新建主元数据' : '元数据'"
      :subtitle="state.mainEditorOpen.value ? (moduleTitle ?? moduleAlias) : undefined"
    >
      <template v-if="state.mainEditorOpen.value" #status>
        <UiRadioGroup v-model:value="editorMode" :options="editorModeOptions" size="small" />
      </template>
      <template #actions>
        <template v-if="state.mainEditorOpen.value">
          <UiActionButton :disabled="saving" @click="cancelNodeEditor">取消</UiActionButton>
          <UiActionButton emphasis="primary" :loading="saving" @click="createMainMetadata">
            保存
          </UiActionButton>
        </template>
        <UiActionButton
          v-else
          emphasis="primary"
          :disabled="loading || saving"
          @click="startCreateMainMetadata"
        >
          新建主元数据
        </UiActionButton>
      </template>
      <section v-if="state.mainEditorOpen.value" class="metadata-inline-editor">
        <p role="status">本步先创建数据结构。确认保存后，再配置要记录的字段和明细；本步不录入业务记录。</p>
        <RecordFormGrid @submit.prevent="createMainMetadata">
          <label v-if="editorMode === 'ADVANCED'">
            <RecordFieldLabel required>元数据标识（alias）</RecordFieldLabel>
            <UiInput v-model:value="mainMetadataDraft.alias" required placeholder="例如 customer" />
          </label>
          <label>
            <RecordFieldLabel required>元数据名称</RecordFieldLabel>
            <UiInput v-model:value="mainMetadataDraft.title" required placeholder="例如 客户" />
          </label>
          <label v-if="editorMode === 'ADVANCED'">
            <span>Schema（可选）</span>
            <UiInput v-model:value="mainMetadataDraft.schemaName" placeholder="默认 public" />
          </label>
          <label v-if="editorMode === 'ADVANCED'">
            <span>物理表名（可选）</span>
            <UiInput v-model:value="mainMetadataDraft.tableName" placeholder="默认按应用和 alias 生成" />
          </label>
        </RecordFormGrid>
      </section>
      <UiEmpty v-else description="从右上角“新建主元数据”开始配置元数据" />
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
