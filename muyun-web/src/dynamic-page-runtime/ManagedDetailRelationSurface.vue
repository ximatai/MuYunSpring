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
import { createManagedDetailRelationClient, createModuleContext, type ModuleContext } from '@muyun/web-core';
import { useManagedDetailRelationRuntime } from './composables/useManagedDetailRelationRuntime';

defineOptions({ name: 'ManagedDetailRelationSurface' });

const props = defineProps<{
  sourceContext: ModuleContext<QueryListRecord>;
  uiDescriptor: ResolvedModuleUiDescriptor;
  relation: ResolvedDetailRelationDescriptor;
  parentRecord: QueryListRecord;
  parentDirty?: boolean;
  reloadKey?: number;
}>();

const relationRef = computed(() => props.relation);
const parentId = computed(() => (props.parentRecord.id == null ? undefined : String(props.parentRecord.id)));
const parentPersisted = computed(() => Boolean(parentId.value));
const parentDirty = computed(() => props.parentDirty === true);
const runtime = useManagedDetailRelationRuntime<QueryListRecord>({
  relation: relationRef,
  parentId,
  parentPersisted,
  parentDirty,
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
        http: props.sourceContext.http,
        moduleAlias: reference.targetModuleAlias,
      }),
      mode: recordPickerModeOf(reference.pickerMode),
      allowClear: !field.required?.constant,
    };
  }
  return configs;
});
const columns = computed<RecordQueryListColumn[]>(() =>
  (props.relation.queryContract?.listProjection?.fields ?? []).map((field) => ({
    key: field.fieldName,
    title: field.title ?? field.fieldName,
    width: field.width == null ? undefined : `${field.width}px`,
    align:
      field.align === 'left' || field.align === 'center' || field.align === 'right' ? field.align : undefined,
    maxDisplayLines: field.maxDisplayLines,
  })),
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
    :context="relationContext"
    :title="relation.title ?? relation.code"
    :columns="columns"
    :reload-key="listReloadKey"
    :query-schema="relation.queryContract?.querySchema"
    :queryable="relation.queryContract?.queryable ?? false"
    :pageable="relation.queryContract?.pageable ?? true"
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
      :picker-configs="pickerConfigs"
      :disabled="runtime.saving.value"
      @update:field="updateField"
      @validity-change="formValid = $event.valid"
    />
  </UiModal>
</template>
