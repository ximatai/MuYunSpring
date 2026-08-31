<script setup lang="ts">
import { computed, ref, toRaw, watch } from 'vue';
import {
  ModuleActionButton,
  RecordFormFields,
  RecordQueryListPanel,
  UiModal,
  confirmAction,
  presentPlatformError,
  recordPickerModeOf,
  resolveRecordFormFields,
  type QueryListRecord,
  type RecordFormFieldPickerConfig,
  type RecordFormFieldValue,
  type RecordFormRecord,
  type RecordPickerRecord,
  type RecordQueryListColumn,
} from '@muyun/platform-components';
import type { ResolvedDetailRelationDescriptor, ResolvedModuleUiDescriptor } from '@muyun/web-contracts';
import {
  createManagedDetailRelationClient,
  createModuleContext,
  type HttpClient,
  type ModuleContext,
} from '@muyun/web-core';
import { useManagedDetailRelationRuntime } from './composables/useManagedDetailRelationRuntime';

defineOptions({ name: 'ManagedDetailRelationSurface' });

const props = defineProps<{
  sourceContext: ModuleContext<QueryListRecord>;
  crossModuleHttp?: HttpClient;
  uiDescriptor: ResolvedModuleUiDescriptor;
  relation: ResolvedDetailRelationDescriptor;
  parentRecord: QueryListRecord;
  mutationEnabled?: boolean;
  reloadKey?: number;
}>();

const relationRef = computed(() => props.relation);
const parentId = computed(() => (props.parentRecord.id == null ? undefined : String(props.parentRecord.id)));
const parentPersisted = computed(() => Boolean(parentId.value));
const mutationEnabled = computed(() => props.mutationEnabled === true);
const runtime = useManagedDetailRelationRuntime<QueryListRecord>({
  relation: relationRef,
  parentId,
  parentPersisted,
  mutationEnabled,
  can: (actionCode) => props.sourceContext.can(actionCode) === true,
  clientOf(id, relationCode) {
    return createManagedDetailRelationClient(props.sourceContext.http, {
      parentModuleAlias: props.sourceContext.moduleAlias,
      parentId: id,
      relationCode,
    });
  },
});

const formFields = computed(() =>
  resolveRecordFormFields(props.uiDescriptor, props.relation.targetEntityAlias),
);
const pickerConfigs = computed<Record<string, RecordFormFieldPickerConfig>>(() => {
  const configs: Record<string, RecordFormFieldPickerConfig> = {};
  for (const field of formFields.value.values()) {
    const reference = field.reference;
    if (!reference) continue;
    configs[field.fieldRef.fieldName] = {
      context: createModuleContext<RecordPickerRecord>({
        http: props.crossModuleHttp ?? props.sourceContext.http,
        moduleAlias: reference.targetModuleAlias,
      }),
      mode: recordPickerModeOf(reference.pickerMode),
      allowClear: !field.required?.constant,
    };
  }
  return configs;
});
const columns = computed<RecordQueryListColumn[]>(() =>
  (props.relation.queryContract?.listProjection?.fields ?? []).map((field) => {
    const queryField = props.relation.queryContract?.querySchema?.fields.find(
      (candidate) => candidate.name === field.fieldName,
    );
    const formField = formFields.value.get(field.fieldName);
    return {
      key: field.fieldName,
      title: field.title ?? field.fieldName,
      width: field.width == null ? undefined : `${field.width}px`,
      align:
        field.align === 'left' || field.align === 'center' || field.align === 'right' ? field.align : undefined,
      titleField: queryField?.optionTitleField,
      optionBinding: formField?.option ? true : undefined,
      optionEntityAlias: formField?.option ? props.relation.targetEntityAlias : undefined,
      maxDisplayLines: field.maxDisplayLines,
    };
  }),
);
const relationContext = computed<ModuleContext<QueryListRecord>>(() => ({
  ...props.sourceContext,
  crud: { ...props.sourceContext.crud, query: runtime.query },
}));
const listReloadKey = computed(() => (props.reloadKey ?? 0) + runtime.reloadKey.value);
const mutation = computed(() => runtime.mutable.value);
const createAllowed = computed(() =>
  Boolean(
    mutation.value?.createAllowed &&
    mutation.value.createActionCode &&
    props.sourceContext.can(mutation.value.createActionCode) === true,
  ),
);
const updateAllowed = computed(() =>
  Boolean(
    mutation.value?.updateAllowed &&
    mutation.value.updateActionCode &&
    props.sourceContext.can(mutation.value.updateActionCode) === true,
  ),
);
const deleteAllowed = computed(() =>
  Boolean(
    mutation.value?.deleteAllowed &&
    mutation.value.deleteActionCode &&
    props.sourceContext.can(mutation.value.deleteActionCode) === true,
  ),
);

const draft = ref<QueryListRecord>();
const formValid = ref(true);
const formSessionKey = ref(0);
const editorOpen = computed(() => draft.value != null);
const editorTitle = computed(() =>
  draft.value?.id == null
    ? `新增${props.relation.title ?? '子记录'}`
    : `编辑${props.relation.title ?? '子记录'}`,
);

function startCreate() {
  if (!createAllowed.value || runtime.busy.value) return;
  formSessionKey.value += 1;
  formValid.value = true;
  draft.value = {};
}

function startEdit(record: QueryListRecord) {
  if (!updateAllowed.value || runtime.busy.value) return;
  formSessionKey.value += 1;
  formValid.value = true;
  draft.value = structuredClone(toRaw(record));
}

function updateField(fieldName: string, value: RecordFormFieldValue) {
  if (draft.value) draft.value = { ...draft.value, [fieldName]: value };
}

function closeEditor() {
  if (runtime.saving.value) return;
  draft.value = undefined;
}

async function save() {
  const record = draft.value;
  if (!record || !formValid.value || runtime.busy.value) return;
  try {
    const completed =
      record.id == null ? await runtime.create(record) : await runtime.update(String(record.id), record);
    if (completed) draft.value = undefined;
  } catch (cause) {
    presentPlatformError(cause, { source: 'managed-detail-relation-dialog', phase: 'action' });
  }
}

async function remove(record: QueryListRecord) {
  if (!deleteAllowed.value || runtime.busy.value || record.id == null || record.version == null) return;
  const confirmed = await confirmAction({
    title: `删除${props.relation.title ?? '子记录'}`,
    content: '确认删除当前记录？',
    danger: true,
  });
  if (!confirmed) return;
  try {
    await runtime.remove(String(record.id), Number(record.version));
  } catch (cause) {
    presentPlatformError(cause, { source: 'managed-detail-relation', phase: 'action' });
  }
}

watch(
  () => [props.relation.code, parentId.value],
  () => {
    formSessionKey.value += 1;
    formValid.value = true;
    draft.value = undefined;
  },
);
</script>

<template>
  <RecordQueryListPanel
    class="managed-detail-relation-view"
    :context="relationContext"
    :title="relation.title ?? relation.code"
    :show-title="false"
    :header-visible="mutationEnabled"
    :show-recycle-bin="mutationEnabled"
    :row-actions-visible="mutationEnabled"
    embedded
    :columns="columns"
    :reload-key="listReloadKey"
    :query-schema="relation.queryContract?.querySchema"
    :queryable="relation.queryContract?.queryable ?? false"
    :pageable="relation.queryContract?.pageable ?? true"
    :page-size="relation.queryContract?.pageSize ?? 20"
    :page-size-options="relation.queryContract?.pageSizeOptions ?? [10, 20, 50]"
    :ready="runtime.executable.value != null"
    empty-description="暂无关联记录"
  >
    <template #toolbarActions>
      <ModuleActionButton
        v-if="mutation?.createAllowed && mutation.createActionCode"
        :context="sourceContext"
        :action-code="mutation.createActionCode"
        :disabled="!createAllowed || runtime.busy.value"
        title="新增"
        @click="startCreate"
      >
        新增
      </ModuleActionButton>
    </template>
    <template #rowActions="{ record }">
      <ModuleActionButton
        v-if="mutation?.updateAllowed && mutation.updateActionCode"
        :context="sourceContext"
        :action-code="mutation.updateActionCode"
        :disabled="!updateAllowed || runtime.busy.value"
        title="编辑"
        @click="startEdit(record)"
      >
        编辑
      </ModuleActionButton>
      <ModuleActionButton
        v-if="mutation?.deleteAllowed && mutation.deleteActionCode"
        :context="sourceContext"
        :action-code="mutation.deleteActionCode"
        :disabled="!deleteAllowed || runtime.busy.value || record.version == null"
        danger
        title="删除"
        @click="remove(record)"
      >
        删除
      </ModuleActionButton>
    </template>
  </RecordQueryListPanel>

  <UiModal
    :open="editorOpen"
    :title="editorTitle"
    confirm-text="保存"
    :confirm-loading="runtime.saving.value"
    :confirm-disabled="!formValid || runtime.saving.value"
    :closable="!runtime.saving.value"
    @confirm="save"
    @cancel="closeEditor"
  >
    <RecordFormFields
      v-if="draft"
      :record="draft as RecordFormRecord"
      :fields="formFields"
      :form-session-key="formSessionKey"
      :option-context="sourceContext"
      :option-entity-alias="relation.targetEntityAlias"
      :picker-configs="pickerConfigs"
      :disabled="runtime.saving.value"
      @update:field="updateField"
      @validity-change="formValid = $event.valid"
    />
  </UiModal>
</template>

<style scoped>
.managed-detail-relation-view {
  height: auto;
}

.managed-detail-relation-view :deep(.record-query-list-body),
.managed-detail-relation-view :deep(.record-query-list-table),
.managed-detail-relation-view :deep(.ant-spin-nested-loading),
.managed-detail-relation-view :deep(.ant-spin-container),
.managed-detail-relation-view :deep(.ant-table),
.managed-detail-relation-view :deep(.ant-table-container) {
  height: auto;
}

.managed-detail-relation-view :deep(.ant-table-thead > tr > th) {
  font-size: var(--muyun-detail-relation-header-font-size, 12px);
  height: var(--muyun-detail-relation-header-row-height, 30px);
  overflow: hidden;
  padding-block: 0 !important;
  padding-inline: 4px !important;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.managed-detail-relation-view :deep(.ant-table-tbody > tr > td) {
  font-size: var(--muyun-detail-relation-body-font-size, 12px);
  height: var(--muyun-detail-relation-body-row-height, 30px);
  padding-block: 0 !important;
  padding-inline: 4px !important;
}
</style>
