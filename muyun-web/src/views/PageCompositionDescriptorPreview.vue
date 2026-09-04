<script setup lang="ts">
import { computed, onBeforeUpdate, onUpdated, ref, watch } from 'vue';
import {
  RecordFormFields,
  RecordDetailFields,
  RecordQueryListCell,
  resolveRecordDetailFields,
  resolveRecordFormFields,
  resolveRecordQueryListColumns,
} from '@muyun/platform-components';
import {
  UiDataTable,
  UiEmpty,
  UiInput,
  type UiDataTableColumn,
  type UiDataTableRecord,
} from '@muyun/vue-ui-antdv';
import type {
  ResolvedDetailRelationDescriptor,
  ResolvedModuleUiDescriptor,
  ResolvedViewFieldDescriptor,
} from '@muyun/web-contracts';
import type { QueryListRecord, RecordFormFieldValue, RecordFormRecord } from '@muyun/platform-components';

defineOptions({ name: 'PageCompositionDescriptorPreview' });

type PreviewMode = 'list' | 'query' | 'detail' | 'edit';
export type PageCompositionPreviewDropTarget = 'list' | 'form';
type PreviewSlot = PageCompositionPreviewDropTarget;

const props = defineProps<{
  descriptor: ResolvedModuleUiDescriptor;
  moduleAlias: string;
  mode: PreviewMode;
  selectedFieldName?: string;
  /** Allows the active preview surface to receive a compatible metadata payload. */
  acceptExternalDrop?: boolean;
}>();

const emit = defineEmits<{
  selectField: [slot: PreviewSlot, fieldName: string];
  configureField: [slot: PreviewSlot, fieldName: string];
  'metadata-drop': [target: PreviewSlot, nativeEvent: DragEvent];
}>();

const listColumns = computed(() => resolveRecordQueryListColumns(props.descriptor.page?.list?.fields));
const dataTableColumns = computed<UiDataTableColumn[]>(() =>
  listColumns.value.map((column) => ({
    key: column.key,
    title: column.title,
    width: column.width,
    align: column.align,
  })),
);
const listRecord = computed<QueryListRecord>(() =>
  previewRecord(props.descriptor.page?.list?.fields.fields ?? []),
);
const listSearchPlaceholder = computed(() => props.descriptor.page?.list?.searchPlaceholder);
const detailFields = computed(() => resolveRecordDetailFields(props.descriptor));
const detailFieldNames = computed(() => [...detailFields.value.keys()]);
const detailRecord = computed<UiDataTableRecord>(() => previewRecord([...detailFields.value.values()]));
const detailRelations = computed(() => props.descriptor.detailRelations ?? []);
const formFields = computed(() => resolveRecordFormFields(props.descriptor));
const formFieldNames = computed(() => [...formFields.value.keys()]);
const formRecord = ref<RecordFormRecord>(previewRecord([]));
const relationEditorRecords = ref<Record<string, UiDataTableRecord[]>>({});
const previewRoot = ref<HTMLElement>();
const previousLayout = new Map<string, DOMRect>();
const selectedDetailFieldName = computed(() =>
  props.selectedFieldName?.startsWith('form:') ? props.selectedFieldName.slice('form:'.length) : undefined,
);
const isListEmpty = computed(() => listColumns.value.length === 0);
const isDetailEmpty = computed(
  () => detailFieldNames.value.length === 0 && detailRelations.value.length === 0,
);
const isFormEmpty = computed(() => formFieldNames.value.length === 0);
const isEditEmpty = computed(() => isFormEmpty.value && detailRelations.value.length === 0);
const previewDropTarget = computed<PreviewSlot>(() =>
  props.mode === 'list' || props.mode === 'query' ? 'list' : 'form',
);
const externalDragOver = ref(false);

watch(
  () => props.descriptor,
  () => {
    // The runtime form stays interactive, but a new server-resolved descriptor starts from a
    // fresh representative record exactly like an editor opening a different revision.
    formRecord.value = previewRecord([...formFields.value.values()]);
    relationEditorRecords.value = Object.fromEntries(
      detailRelations.value.map((relation) => [relation.code, [relationRecord(relation)]]),
    );
  },
  { immediate: true },
);

function previewRecord(fields: readonly ResolvedViewFieldDescriptor[]): UiDataTableRecord {
  return {
    id: 'page-composition-preview-record',
    ...Object.fromEntries(fields.map((field) => [field.fieldRef.fieldName, previewValue(field)])),
  };
}

function previewValue(field: ResolvedViewFieldDescriptor): unknown {
  if (field.uiType === 'enabledStatus' || field.uiType === 'booleanStatus' || field.uiType === 'switch') {
    return true;
  }
  if (field.valuePresentation === 'FILE_SIZE') return 1024 * 256;
  if (field.valueType === 'INTEGER' || field.valueType === 'LONG' || field.valueType === 'DECIMAL')
    return 128;
  if (field.valueType === 'TIMESTAMP' || field.valueType === 'ZONED_TIMESTAMP') {
    return '2026-08-30T09:30:00+08:00';
  }
  if (field.uiType === 'tagList') {
    return [
      { id: 'tag-a', title: '示例标签 A', color: '#1677FF' },
      { id: 'tag-b', title: '示例标签 B', color: '#52C41A' },
    ];
  }
  if (field.fieldControl?.rendererType === 'COLOR_PICKER' || field.uiType === 'colorPicker') {
    return '#1677FF';
  }
  return '示例内容';
}

function relationColumns(relation: ResolvedDetailRelationDescriptor): UiDataTableColumn[] {
  return (relation.listProjection?.fields ?? []).map((field) => ({
    key: field.fieldName,
    title: field.title ?? field.fieldName,
    ...(field.width ? { width: field.width } : {}),
    ...(field.align === 'left' || field.align === 'center' || field.align === 'right'
      ? { align: field.align }
      : {}),
  }));
}

function relationRecord(relation: ResolvedDetailRelationDescriptor): UiDataTableRecord {
  return {
    id: `page-composition-relation-preview:${relation.code}`,
    ...Object.fromEntries(
      (relation.listProjection?.fields ?? []).map((field) => [
        field.fieldName,
        relationPreviewValue(field.fieldName, field.title),
      ]),
    ),
  };
}

function relationEditorRows(relation: ResolvedDetailRelationDescriptor) {
  return relationEditorRecords.value[relation.code] ?? [];
}

function updateRelationEditorField(
  relation: ResolvedDetailRelationDescriptor,
  rowId: unknown,
  fieldName: string,
  value: string,
) {
  relationEditorRecords.value = {
    ...relationEditorRecords.value,
    [relation.code]: relationEditorRows(relation).map((row) =>
      row.id === rowId ? { ...row, [fieldName]: value } : row,
    ),
  };
}

function relationPreviewValue(fieldName: string, title?: string) {
  if (/(score|grade|amount|count|number)$/i.test(fieldName)) return 96;
  return `示例${title ?? fieldName}`;
}

function isSelected(slot: PreviewSlot, fieldName: string) {
  return props.selectedFieldName === `${slot}:${fieldName}`;
}

function updateFormField(fieldName: string, value: RecordFormFieldValue) {
  formRecord.value = { ...formRecord.value, [fieldName]: value };
}

function handleExternalDragOver(event: DragEvent) {
  if (!props.acceptExternalDrop) return;
  event.preventDefault();
  externalDragOver.value = true;
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
}

function handleExternalDragLeave(event: DragEvent) {
  if (event.relatedTarget instanceof Node && (event.currentTarget as Element).contains(event.relatedTarget)) {
    return;
  }
  externalDragOver.value = false;
}

function handleExternalDrop(event: DragEvent) {
  if (!props.acceptExternalDrop) return;
  event.preventDefault();
  externalDragOver.value = false;
  emit('metadata-drop', previewDropTarget.value, event);
}

function layoutKeyOf(element: HTMLElement) {
  return element.dataset.pageCompositionLayoutKey;
}

onBeforeUpdate(() => {
  previousLayout.clear();
  previewRoot.value
    ?.querySelectorAll<HTMLElement>('[data-page-composition-layout-key]')
    .forEach((element) => {
      const key = layoutKeyOf(element);
      if (key) previousLayout.set(key, element.getBoundingClientRect());
    });
});

onUpdated(() => {
  if (typeof window === 'undefined' || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches)
    return;
  const animateLayout = () => {
    previewRoot.value
      ?.querySelectorAll<HTMLElement>('[data-page-composition-layout-key]')
      .forEach((element) => {
        const key = layoutKeyOf(element);
        const previous = key ? previousLayout.get(key) : undefined;
        if (!previous) return;
        const current = element.getBoundingClientRect();
        const x = previous.left - current.left;
        const y = previous.top - current.top;
        if (Math.abs(x) < 1 && Math.abs(y) < 1) return;
        animateLayoutElement(element, x, y);
      });
  };
  if (typeof window.requestAnimationFrame === 'function') window.requestAnimationFrame(animateLayout);
  else animateLayout();
});

function animateLayoutElement(element: HTMLElement, x: number, y: number) {
  if (typeof element.animate === 'function') {
    element.animate(
      [
        { transform: `translate(${x}px, ${y}px)`, opacity: 0.72 },
        { transform: 'translate(0, 0)', opacity: 1 },
      ],
      { duration: 300, easing: 'cubic-bezier(0.2, 0, 0, 1)' },
    );
    return;
  }
  const originalTransition = element.style.transition;
  element.style.transition = 'none';
  element.style.transform = `translate(${x}px, ${y}px)`;
  element.style.opacity = '0.72';
  void element.offsetWidth;
  element.style.transition =
    'transform 300ms cubic-bezier(0.2, 0, 0, 1), opacity 300ms cubic-bezier(0.2, 0, 0, 1)';
  element.style.transform = 'translate(0, 0)';
  element.style.opacity = '1';
  window.setTimeout(() => {
    element.style.transition = originalTransition;
    element.style.transform = '';
    element.style.opacity = '';
  }, 320);
}
</script>

<template>
  <section
    v-if="mode === 'list'"
    ref="previewRoot"
    class="page-composition-descriptor-preview"
    :class="{ 'page-composition-descriptor-preview--drag-over': externalDragOver }"
    data-testid="page-composer-list-preview"
    data-composer-drop-target="list"
    @dragover="handleExternalDragOver"
    @dragleave="handleExternalDragLeave"
    @drop="handleExternalDrop"
  >
    <header class="page-composition-descriptor-preview__toolbar">
      <strong>列表预览</strong>
    </header>
    <label class="page-composition-descriptor-preview__quick-search">
      <span>快速查询</span>
      <UiInput
        type="search"
        :value="''"
        :placeholder="listSearchPlaceholder"
        disabled
        aria-label="快速查询（模板内置）"
      />
    </label>
    <UiEmpty v-if="isListEmpty" description="当前草稿尚未配置列表字段" />
    <UiDataTable
      v-else
      class="page-composition-descriptor-preview__table"
      :columns="dataTableColumns"
      :rows="[listRecord]"
      row-key="id"
      :pagination="false"
      horizontal-scroll
    >
      <template #header="{ column }">
        <span :data-page-composition-layout-key="`list:header:${column.key}`">{{ column.title }}</span>
      </template>
      <template #cell="{ column }">
        <button
          class="page-composition-descriptor-preview__field"
          :class="{ 'page-composition-descriptor-preview__field--selected': isSelected('list', column.key) }"
          type="button"
          :title="`配置${column.title}`"
          :data-page-composition-layout-key="`list:field:${column.key}`"
          @click="emit('selectField', 'list', column.key)"
          @dblclick="emit('configureField', 'list', column.key)"
          @keydown.space.prevent="emit('configureField', 'list', column.key)"
        >
          <RecordQueryListCell
            :record="listRecord"
            :column="listColumns.find((item) => item.key === column.key)!"
          />
        </button>
      </template>
    </UiDataTable>
  </section>

  <section
    v-else-if="mode === 'query'"
    ref="previewRoot"
    class="page-composition-descriptor-preview"
    :class="{ 'page-composition-descriptor-preview--drag-over': externalDragOver }"
    data-testid="page-composer-query-preview"
    data-composer-drop-target="list"
    @dragover="handleExternalDragOver"
    @dragleave="handleExternalDragLeave"
    @drop="handleExternalDrop"
  >
    <header class="page-composition-descriptor-preview__toolbar">
      <strong>查询预览</strong>
    </header>
    <label class="page-composition-descriptor-preview__quick-search">
      <span>快速查询</span>
      <UiInput type="search" :value="''" :placeholder="listSearchPlaceholder" aria-label="快速查询" />
    </label>
  </section>

  <section
    v-else-if="mode === 'detail'"
    ref="previewRoot"
    class="page-composition-descriptor-preview"
    :class="{ 'page-composition-descriptor-preview--drag-over': externalDragOver }"
    data-testid="page-composer-detail-preview"
    data-composer-drop-target="form"
    @dragover="handleExternalDragOver"
    @dragleave="handleExternalDragLeave"
    @drop="handleExternalDrop"
  >
    <header class="page-composition-descriptor-preview__toolbar">
      <strong>详情预览</strong>
    </header>
    <UiEmpty v-if="isDetailEmpty" description="当前草稿尚未配置详情字段或关联子表" />
    <RecordDetailFields
      v-if="detailFieldNames.length"
      interaction-mode="selectable"
      :record="detailRecord"
      :field-names="detailFieldNames"
      :fields="detailFields"
      :selected-field-name="selectedDetailFieldName"
      layout-transition-prefix="detail"
      @select="(fieldName) => emit('selectField', 'form', fieldName)"
      @configure="(fieldName) => emit('configureField', 'form', fieldName)"
    />
    <section
      v-for="relation in detailRelations"
      :key="relation.code"
      class="page-composition-descriptor-preview__relation"
    >
      <header>
        <strong>{{ relation.title ?? relation.code }}</strong>
      </header>
      <div class="page-composition-descriptor-preview__relation-columns">
        <UiDataTable
          v-if="relation.listProjection?.fields?.length"
          class="page-composition-descriptor-preview__relation-table"
          :columns="relationColumns(relation)"
          :rows="[relationRecord(relation)]"
          row-key="id"
          :pagination="false"
          horizontal-scroll
        >
          <template #header="{ column }">
            <span :data-page-composition-layout-key="`detail:relation:${relation.code}:header:${column.key}`">
              {{ column.title }}
            </span>
          </template>
          <template #cell="{ column, record }">
            <span :data-page-composition-layout-key="`detail:relation:${relation.code}:field:${column.key}`">
              {{ record[column.key] }}
            </span>
          </template>
        </UiDataTable>
      </div>
      <UiEmpty v-if="!relation.listProjection?.fields?.length" description="尚未选择子表展示字段" />
    </section>
  </section>

  <section
    v-else
    ref="previewRoot"
    class="page-composition-descriptor-preview"
    :class="{ 'page-composition-descriptor-preview--drag-over': externalDragOver }"
    data-testid="page-composer-edit-preview"
    data-composer-drop-target="form"
    @dragover="handleExternalDragOver"
    @dragleave="handleExternalDragLeave"
    @drop="handleExternalDrop"
  >
    <header class="page-composition-descriptor-preview__toolbar">
      <strong>编辑预览</strong>
    </header>
    <UiEmpty v-if="isEditEmpty" description="当前草稿尚未配置编辑字段或关联子表" />
    <RecordFormFields
      v-if="!isFormEmpty"
      class="page-composition-descriptor-preview__form"
      :record="formRecord"
      :fields="formFields"
      :field-names="formFieldNames"
      :form-session-key="`page-composer:${moduleAlias}`"
      layout-transition-prefix="edit"
      @update:field="updateFormField"
    />
    <section
      v-for="relation in detailRelations"
      :key="relation.code"
      class="page-composition-descriptor-preview__relation page-composition-descriptor-preview__relation--editor"
    >
      <header>
        <strong>{{ relation.title ?? relation.code }}</strong>
      </header>
      <div class="page-composition-descriptor-preview__relation-columns">
        <UiDataTable
          v-if="relation.listProjection?.fields?.length"
          class="page-composition-descriptor-preview__relation-table"
          :columns="relationColumns(relation)"
          :rows="relationEditorRows(relation)"
          row-key="id"
          :pagination="false"
          horizontal-scroll
        >
          <template #header="{ column }">
            <span :data-page-composition-layout-key="`edit:relation:${relation.code}:header:${column.key}`">
              {{ column.title }}
            </span>
          </template>
          <template #cell="{ column, record }">
            <div :data-page-composition-layout-key="`edit:relation:${relation.code}:field:${column.key}`">
              <UiInput
                :value="String(record[column.key] ?? '')"
                :aria-label="`${relation.title ?? relation.code}：${column.title}`"
                @update:value="(value) => updateRelationEditorField(relation, record.id, column.key, value)"
              />
            </div>
          </template>
          <template #empty>
            <UiEmpty description="还没有子表记录，可新增一行预览" />
          </template>
        </UiDataTable>
      </div>
      <p
        v-if="relation.listProjection?.fields?.length"
        class="page-composition-descriptor-preview__relation-note"
      >
        可直接编辑示例值以检查编辑态。
      </p>
      <UiEmpty v-if="!relation.listProjection?.fields?.length" description="尚未选择子表展示字段" />
    </section>
  </section>
</template>

<style scoped>
.page-composition-descriptor-preview {
  display: grid;
  gap: 16px;
  min-height: 280px;
  margin-top: 12px;
  padding: 16px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
}

.page-composition-descriptor-preview--drag-over {
  border-color: var(--muyun-primary);
  background: color-mix(in srgb, var(--muyun-primary) 5%, var(--muyun-surface));
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--muyun-primary) 18%, transparent);
}

.page-composition-descriptor-preview__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.page-composition-descriptor-preview__toolbar span {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.page-composition-descriptor-preview__quick-search {
  display: grid;
  grid-template-columns: auto minmax(180px, 320px);
  gap: 10px;
  align-items: center;
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.page-composition-descriptor-preview__runtime-note {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.page-composition-descriptor-preview__form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 12px;
  --muyun-record-form-label-gap: 8px;
}

@media (max-width: 900px) {
  .page-composition-descriptor-preview__form {
    grid-template-columns: 1fr;
  }
}

.page-composition-descriptor-preview__table :deep(.ant-table-cell) {
  padding: 0;
}

.page-composition-descriptor-preview__field {
  display: block;
  width: 100%;
  min-height: 42px;
  padding: 10px 12px;
  overflow: hidden;
  color: inherit;
  font: inherit;
  text-align: inherit;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  background: transparent;
  border: 0;
  outline: 1px solid transparent;
  outline-offset: -1px;
}

.page-composition-descriptor-preview__field:hover,
.page-composition-descriptor-preview__field:focus-visible,
.page-composition-descriptor-preview__field--selected {
  outline: 2px solid var(--muyun-primary);
  outline-offset: -2px;
  background: var(--muyun-primary-surface, var(--muyun-hover));
}

.page-composition-descriptor-preview__relation {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  background: var(--muyun-surface-muted);
}

.page-composition-descriptor-preview__relation--editor {
  margin-top: 4px;
}

.page-composition-descriptor-preview__relation > header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.page-composition-descriptor-preview__relation > header span {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.page-composition-descriptor-preview__relation-columns {
  min-width: 0;
}

.page-composition-descriptor-preview__relation-table :deep(.ant-table-cell) {
  white-space: nowrap;
}

.page-composition-descriptor-preview__relation-table :deep([data-page-composition-layout-key]) {
  display: block;
  min-width: 0;
  transform-origin: center left;
  will-change: transform, opacity;
}

.page-composition-descriptor-preview__relation--editor
  .page-composition-descriptor-preview__relation-table
  :deep(.ant-table-cell) {
  padding: 6px;
}

.page-composition-descriptor-preview__relation-note {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
</style>
