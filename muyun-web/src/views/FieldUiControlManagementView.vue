<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  CrudRecordListExplorer,
  type CrudRecordListBase,
  ModuleActionButton,
  RecordActionBar,
  RecordMetaSection,
  RecordStatusSwitch,
  RecycleBinModeButton,
  StaticManagementLayout,
  useRecycleBinExplorerMode,
  type RecordActionItem,
  type RecordExplorerItemDescriptor,
} from '@muyun/platform-components';
import type {
  FieldSpec,
  FieldUiControl,
  FieldUiControlBinding,
  FieldUiControlProperty,
  Option,
} from '@muyun/web-contracts';
import { createStaticResourceCrudClient, useModuleContext } from '@muyun/web-core';
import { confirmAction, UiButton, UiDataTable, UiInput, UiModal, UiSelect } from '@muyun/vue-ui-antdv';
import type { UiDataTableColumn, UiDataTableRecord } from '@muyun/vue-ui-antdv';
import { presentPlatformError } from '@muyun/platform-components';
import { createFieldUiControlManagementState } from './fieldUiControlManagementState';

defineOptions({ name: 'FieldUiControlManagementView' });

const fieldUiControlContext = useModuleContext<FieldUiControl>();
const fieldSpecContext = useModuleContext<FieldSpec>({ moduleAlias: 'platform.field_spec' });
const explorerSearchKeyword = ref('');
const fieldSpecs = ref<FieldSpec[]>([]);
const properties = ref<FieldUiControlProperty[]>([]);
const bindings = ref<FieldUiControlBinding[]>([]);
const propertyDraft = ref<FieldUiControlProperty>();
const bindingDraft = ref<FieldUiControlBinding>();
const childSaving = ref(false);
const {
  selected,
  draft,
  mode,
  reloadKey,
  saving,
  cardTitle,
  readonly,
  controlAliasReadonly,
  canEnable,
  canUpdate,
  handleListLoaded,
  handleReadonlyListLoaded,
  handleSelect,
  startCreate,
  startEdit,
  cancelEdit,
  save,
  toggleEnabled,
  removeSelected,
} = createFieldUiControlManagementState(fieldUiControlContext, confirmAction);
const fieldSpecOptions = computed<Option[]>(() =>
  fieldSpecs.value.map((spec) => ({
    label: spec.title ? `${spec.title} (${spec.alias ?? spec.id})` : (spec.alias ?? spec.id ?? '未命名规格'),
    value: spec.alias ?? spec.id ?? '',
    disabled: spec.enabled === false,
  })),
);
const rendererTypeOptions: Option[] = [
  'TEXT',
  'TEXTAREA',
  'NUMBER',
  'SWITCH',
  'DATE',
  'DATETIME',
  'DECIMAL',
  'SELECT',
  'MULTI_SELECT',
  'JSON',
].map((value) => ({ label: value, value }));
const valueShapeOptions: Option[] = [
  { label: '单值', value: 'SCALAR' },
  { label: '集合', value: 'COLLECTION' },
  { label: '复合值', value: 'COMPOSITE' },
];
const queryModeOptions: Option[] = [
  { label: '默认', value: 'DEFAULT' },
  { label: '区间（BETWEEN）', value: 'BETWEEN' },
];
const propertyColumns: UiDataTableColumn[] = [
  { key: 'attributeAlias', title: '属性 alias' },
  { key: 'title', title: '名称' },
  { key: 'valueFieldSpecAlias', title: '值规格' },
  { key: 'defaultValue', title: '默认值' },
];
const bindingColumns: UiDataTableColumn[] = [
  { key: 'valueKey', title: '分量键' },
  { key: 'title', title: '名称' },
  { key: 'valueFieldSpecAlias', title: '字段规格' },
];
const childEditorOpen = computed(() => Boolean(propertyDraft.value || bindingDraft.value));
const childEditorTitle = computed(() => {
  if (propertyDraft.value) return propertyDraft.value.id ? '编辑控件属性' : '新增控件属性';
  return bindingDraft.value?.id ? '编辑字段绑定' : '新增字段绑定';
});
const childEditorValid = computed(() => {
  if (propertyDraft.value)
    return Boolean(propertyDraft.value.attributeAlias?.trim() && propertyDraft.value.title?.trim());
  return Boolean(
    bindingDraft.value?.valueKey?.trim() &&
    bindingDraft.value.title?.trim() &&
    bindingDraft.value.valueFieldSpecAlias,
  );
});
const recycleBinExplorer = useRecycleBinExplorerMode({
  context: fieldUiControlContext,
  listReloadKey: reloadKey,
  searchKeyword: explorerSearchKeyword,
  resetSelection,
});
const canManageChildren = computed(
  () => Boolean(selected.value?.id) && !recycleBinExplorer.active.value && canUpdate.value,
);
const hasUnsavedValueShapeChange = computed(
  () => mode.value === 'edit' && draft.value.valueShape !== selected.value?.valueShape,
);
const canManageBindings = computed(() => canManageChildren.value && !hasUnsavedValueShapeChange.value);

const cardActions = computed<RecordActionItem[]>(() => {
  if (recycleBinExplorer.active.value) return [];
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
      disabled: !selected.value,
      loading: saving.value,
      danger: true,
    },
  ];
});

function fieldUiControlItemOf(record: CrudRecordListBase): RecordExplorerItemDescriptor {
  return {
    title: record.title ?? record.alias ?? record.id ?? '未命名字段 UI 控件',
    secondary: record.alias ?? record.id,
    muted: record.enabled === false,
  };
}

function handleLoaded(records: CrudRecordListBase[]) {
  if (recycleBinExplorer.active.value) {
    handleReadonlyListLoaded(records as FieldUiControl[]);
    return;
  }
  handleListLoaded(records as FieldUiControl[]);
}

function handleFieldUiControlSelect(record: CrudRecordListBase) {
  handleSelect(record as FieldUiControl);
}

function handleCardAction(action: RecordActionItem) {
  if (action.key === 'edit') {
    startEdit();
    return;
  }
  if (action.key === 'delete') {
    void removeSelected();
    return;
  }
  if (action.key === 'cancel') {
    cancelEdit();
    return;
  }
  if (action.key === 'save') {
    void save();
  }
}

function resetSelection() {
  selected.value = undefined;
  draft.value = { alias: '', title: '', enabled: true };
  mode.value = 'view';
}

watch(
  () => selected.value?.alias,
  () => {
    propertyDraft.value = undefined;
    bindingDraft.value = undefined;
    void reloadChildren();
  },
);

onMounted(async () => {
  await fieldSpecContext.runtime.ready;
  const response = await fieldSpecContext.abilities.crud().query({ page: { pageNum: 1, pageSize: 200 } });
  fieldSpecs.value = response.records;
});

function propertyClient() {
  const alias = selected.value?.alias;
  if (!alias) throw new Error('请选择字段 UI 控件');
  return createStaticResourceCrudClient<FieldUiControlProperty>(
    fieldUiControlContext.http,
    `/platform.field_ui_control/${encodeURIComponent(alias)}/properties`,
  );
}

function bindingClient() {
  const alias = selected.value?.alias;
  if (!alias) throw new Error('请选择字段 UI 控件');
  return createStaticResourceCrudClient<FieldUiControlBinding>(
    fieldUiControlContext.http,
    `/platform.field_ui_control/${encodeURIComponent(alias)}/bindings`,
  );
}

async function reloadChildren() {
  if (!selected.value?.alias || recycleBinExplorer.active.value) {
    properties.value = [];
    bindings.value = [];
    return;
  }
  try {
    const [propertyResponse, bindingResponse] = await Promise.all([
      propertyClient().query({ page: { pageNum: 1, pageSize: 200 } }),
      selected.value.valueShape === 'COMPOSITE'
        ? bindingClient().query({ page: { pageNum: 1, pageSize: 200 } })
        : Promise.resolve(undefined),
    ]);
    properties.value = propertyResponse.records;
    bindings.value = bindingResponse?.records ?? [];
  } catch (cause) {
    presentPlatformError(cause, { source: 'field-ui-control-children', phase: 'load' });
  }
}

function startProperty(record?: FieldUiControlProperty) {
  if (!canManageChildren.value) return;
  bindingDraft.value = undefined;
  propertyDraft.value = record
    ? { ...record }
    : { fieldUiControlAlias: selected.value?.alias, attributeAlias: '', title: '' };
}

function startBinding(record?: FieldUiControlBinding) {
  if (!canManageBindings.value) return;
  propertyDraft.value = undefined;
  bindingDraft.value = record
    ? { ...record }
    : { fieldUiControlAlias: selected.value?.alias, valueKey: '', valueFieldSpecAlias: '', title: '' };
}

async function saveProperty() {
  const record = propertyDraft.value;
  if (!record?.attributeAlias?.trim() || !record.title?.trim()) return;
  childSaving.value = true;
  try {
    const client = propertyClient();
    if (record.id)
      await client.update(record.id, {
        ...record,
        attributeAlias: record.attributeAlias.trim(),
        title: record.title.trim(),
      });
    else
      await client.insert({
        ...record,
        attributeAlias: record.attributeAlias.trim(),
        title: record.title.trim(),
      });
    propertyDraft.value = undefined;
    await reloadChildren();
  } catch (cause) {
    presentPlatformError(cause, { source: 'field-ui-control-property', phase: 'action' });
  } finally {
    childSaving.value = false;
  }
}

async function saveBinding() {
  const record = bindingDraft.value;
  if (!record?.valueKey?.trim() || !record.title?.trim() || !record.valueFieldSpecAlias) return;
  childSaving.value = true;
  try {
    const client = bindingClient();
    if (record.id)
      await client.update(record.id, {
        ...record,
        valueKey: record.valueKey.trim(),
        title: record.title.trim(),
      });
    else await client.insert({ ...record, valueKey: record.valueKey.trim(), title: record.title.trim() });
    bindingDraft.value = undefined;
    await reloadChildren();
  } catch (cause) {
    presentPlatformError(cause, { source: 'field-ui-control-binding', phase: 'action' });
  } finally {
    childSaving.value = false;
  }
}

async function deleteProperty(record: FieldUiControlProperty) {
  if (!canManageChildren.value) return;
  if (
    !record.id ||
    !(await confirmAction({ title: '删除控件属性', content: `确认删除 ${record.attributeAlias}？` }))
  )
    return;
  await propertyClient().delete(record.id, { version: record.version ?? 0 });
  await reloadChildren();
}

async function deleteBinding(record: FieldUiControlBinding) {
  if (!canManageBindings.value) return;
  if (
    !record.id ||
    !(await confirmAction({ title: '删除控件绑定', content: `确认删除 ${record.valueKey}？` }))
  )
    return;
  await bindingClient().delete(record.id, { version: record.version ?? 0 });
  await reloadChildren();
}

function cancelChildEditor() {
  propertyDraft.value = undefined;
  bindingDraft.value = undefined;
}

function saveChildEditor() {
  if (propertyDraft.value) {
    void saveProperty();
    return;
  }
  if (bindingDraft.value) void saveBinding();
}
</script>

<template>
  <StaticManagementLayout
    v-model:explorer-search-keyword="explorerSearchKeyword"
    :explorer-title="recycleBinExplorer.active.value ? '回收站' : '字段 UI 控件列表'"
    :refresh-title="recycleBinExplorer.active.value ? '刷新回收站' : '刷新字段 UI 控件列表'"
    explorer-search-placeholder="搜索字段 UI 控件名称或 alias"
    :explorer-searchable="!recycleBinExplorer.active.value"
    :mode="mode"
    :detail-title="cardTitle"
    @refresh="recycleBinExplorer.refresh"
  >
    <template #explorer-actions>
      <ModuleActionButton
        v-if="!recycleBinExplorer.active.value"
        class="record-panel-create-button"
        :context="fieldUiControlContext"
        action-code="create"
        title="新建字段 UI 控件"
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
        :context="fieldUiControlContext"
        :selected-id="selected?.id"
        :reload-key="recycleBinExplorer.reloadKey.value"
        :mode="recycleBinExplorer.mode.value"
        :keyword="explorerSearchKeyword"
        :empty-description="recycleBinExplorer.active.value ? '回收站为空' : '暂无字段 UI 控件'"
        :loading-tip="recycleBinExplorer.active.value ? '加载回收站' : '加载字段 UI 控件列表'"
        fallback-title="未命名字段 UI 控件"
        :item-of="fieldUiControlItemOf"
        @recycle-bin-summary="recycleBinExplorer.updateSummary"
        @select="handleFieldUiControlSelect"
        @loaded="handleLoaded"
      />
    </template>

    <template #detail-actions>
      <RecordActionBar :context="fieldUiControlContext" :actions="cardActions" @action="handleCardAction" />
    </template>
    <template #detail-status>
      <template v-if="!recycleBinExplorer.active.value">
        <RecordStatusSwitch
          v-if="mode !== 'view'"
          :enabled="draft.enabled"
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

    <form class="static-record-form" @submit.prevent="save">
      <label>
        <span>控件 alias</span>
        <UiInput v-model:value="draft.alias" :disabled="controlAliasReadonly" />
      </label>
      <label>
        <span>控件名称</span>
        <UiInput v-model:value="draft.title" :disabled="readonly" />
      </label>
      <label>
        <span>默认字段规格</span>
        <UiSelect
          v-model:value="draft.defaultFieldSpecAlias"
          :options="fieldSpecOptions"
          :disabled="readonly"
        />
      </label>
      <label>
        <span>内置渲染类型</span>
        <UiSelect v-model:value="draft.rendererType" :options="rendererTypeOptions" :disabled="readonly" />
      </label>
      <label>
        <span>值形态</span>
        <UiSelect v-model:value="draft.valueShape" :options="valueShapeOptions" :disabled="readonly" />
      </label>
      <label v-if="draft.valueShape === 'COMPOSITE'">
        <span>主分量键</span>
        <UiInput v-model:value="draft.primaryValueKey" :disabled="readonly" placeholder="例如：start" />
      </label>
      <label>
        <span>查询语义</span>
        <UiSelect v-model:value="draft.queryMode" :options="queryModeOptions" :disabled="readonly" />
      </label>
      <label>
        <span>图标</span>
        <UiInput v-model:value="draft.icon" :disabled="readonly" placeholder="例如：edit" />
      </label>
    </form>

    <section v-if="selected && !recycleBinExplorer.active.value" class="field-ui-control-children">
      <article class="field-ui-control-child-card">
        <div class="field-ui-control-child-header">
          <div>
            <h3>控件属性</h3>
            <p>维护控件的呈现与交互参数。</p>
          </div>
          <UiButton size="small" :disabled="!canManageChildren" @click="startProperty()">新增属性</UiButton>
        </div>
        <UiDataTable
          :columns="propertyColumns"
          :rows="properties as unknown as UiDataTableRecord[]"
          row-key="id"
          size="small"
          show-action-column
        >
          <template #rowActions="{ record }">
            <UiButton
              type="link"
              size="small"
              :disabled="!canManageChildren"
              @click="startProperty(record as unknown as FieldUiControlProperty)"
            >
              编辑
            </UiButton>
            <UiButton
              type="link"
              size="small"
              danger
              :disabled="!canManageChildren"
              @click="deleteProperty(record as unknown as FieldUiControlProperty)"
            >
              删除
            </UiButton>
          </template>
        </UiDataTable>
      </article>

      <article v-if="selected?.valueShape === 'COMPOSITE'" class="field-ui-control-child-card">
        <div class="field-ui-control-child-header">
          <div>
            <h3>字段绑定</h3>
            <p>维护复合控件的值分量与字段规格。</p>
          </div>
          <UiButton size="small" :disabled="!canManageBindings" @click="startBinding()">新增分量</UiButton>
        </div>
        <p v-if="hasUnsavedValueShapeChange" class="field-ui-control-child-notice">
          值形态已修改但尚未保存，请先保存主控件后再维护字段绑定。
        </p>
        <UiDataTable
          :columns="bindingColumns"
          :rows="bindings as unknown as UiDataTableRecord[]"
          row-key="id"
          size="small"
          show-action-column
        >
          <template #rowActions="{ record }">
            <UiButton
              type="link"
              size="small"
              :disabled="!canManageBindings"
              @click="startBinding(record as unknown as FieldUiControlBinding)"
            >
              编辑
            </UiButton>
            <UiButton
              type="link"
              size="small"
              danger
              :disabled="!canManageBindings"
              @click="deleteBinding(record as unknown as FieldUiControlBinding)"
            >
              删除
            </UiButton>
          </template>
        </UiDataTable>
      </article>
    </section>

    <UiModal
      :open="childEditorOpen"
      :title="childEditorTitle"
      confirm-text="保存"
      :confirm-loading="childSaving"
      :confirm-disabled="!childEditorValid"
      @confirm="saveChildEditor"
      @cancel="cancelChildEditor"
    >
      <form v-if="propertyDraft" class="child-editor-form" @submit.prevent="saveProperty">
        <label
          ><span>属性 alias</span
          ><UiInput
            v-model:value="propertyDraft.attributeAlias"
            :disabled="Boolean(propertyDraft.id)"
            style="width: 100%"
        /></label>
        <label
          ><span>属性名称</span><UiInput v-model:value="propertyDraft.title" style="width: 100%"
        /></label>
        <label
          ><span>值字段规格</span
          ><UiSelect
            v-model:value="propertyDraft.valueFieldSpecAlias"
            :options="fieldSpecOptions"
            style="width: 100%"
        /></label>
        <label
          ><span>默认值</span><UiInput v-model:value="propertyDraft.defaultValue" style="width: 100%"
        /></label>
      </form>
      <form v-else-if="bindingDraft" class="child-editor-form" @submit.prevent="saveBinding">
        <label
          ><span>分量键</span
          ><UiInput
            v-model:value="bindingDraft.valueKey"
            :disabled="Boolean(bindingDraft.id)"
            style="width: 100%"
        /></label>
        <label><span>分量名称</span><UiInput v-model:value="bindingDraft.title" style="width: 100%" /></label>
        <label
          ><span>字段规格</span
          ><UiSelect
            v-model:value="bindingDraft.valueFieldSpecAlias"
            :options="fieldSpecOptions"
            style="width: 100%"
        /></label>
      </form>
    </UiModal>

    <RecordMetaSection :record="draft" show-sort-order />
  </StaticManagementLayout>
</template>

<style scoped>
.field-ui-control-children {
  display: grid;
  gap: 12px;
  margin-top: 24px;
}
.field-ui-control-child-card {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  background: var(--muyun-support-surface);
}
.field-ui-control-child-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.field-ui-control-child-header h3 {
  margin: 0;
  font-size: 14px;
}
.field-ui-control-child-header p {
  margin: 4px 0 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.field-ui-control-child-notice {
  margin: 0;
  color: var(--muyun-warning-soft-text);
  font-size: 12px;
}
.child-editor-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
}
.child-editor-form label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-body);
  font-size: 13px;
}
</style>
