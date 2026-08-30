<script setup lang="ts">
import { computed } from 'vue';
import {
  RecordDetailFields,
  resolveRecordDetailFields,
  resolveRecordQueryListColumns,
} from '@muyun/platform-components';
import { UiDataTable, UiEmpty, type UiDataTableColumn, type UiDataTableRecord } from '@muyun/vue-ui-antdv';
import type { ResolvedModuleUiDescriptor, ResolvedViewFieldDescriptor } from '@muyun/web-contracts';

defineOptions({ name: 'PageCompositionDescriptorPreview' });

type PreviewMode = 'list' | 'card' | 'detail';
type PreviewSlot = 'list' | 'form';

const props = defineProps<{
  descriptor: ResolvedModuleUiDescriptor;
  mode: PreviewMode;
  selectedFieldName?: string;
}>();

const emit = defineEmits<{
  selectField: [slot: PreviewSlot, fieldName: string];
  configureField: [slot: PreviewSlot, fieldName: string];
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
const listRecord = computed<UiDataTableRecord>(() =>
  previewRecord(props.descriptor.page?.list?.fields.fields ?? []),
);
const detailFields = computed(() => resolveRecordDetailFields(props.descriptor));
const detailFieldNames = computed(() => [...detailFields.value.keys()]);
const detailRecord = computed<UiDataTableRecord>(() => previewRecord([...detailFields.value.values()]));
const isListEmpty = computed(() => listColumns.value.length === 0);
const isDetailEmpty = computed(() => detailFieldNames.value.length === 0);

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
  if (field.uiType === 'tagList') return ['示例标签 A', '示例标签 B'];
  return '示例内容';
}

function isSelected(slot: PreviewSlot, fieldName: string) {
  return props.selectedFieldName === `${slot}:${fieldName}`;
}

function fieldFromDetailEvent(event: MouseEvent): string | undefined {
  const target = event.target;
  const container = event.currentTarget;
  if (!(target instanceof Element) || !(container instanceof HTMLElement)) return undefined;
  const fieldElement = target.closest('.record-detail-field');
  if (!fieldElement || !container.contains(fieldElement)) return undefined;
  const index = Array.from(container.querySelectorAll('.record-detail-field')).indexOf(fieldElement);
  return index >= 0 ? detailFieldNames.value[index] : undefined;
}

function selectDetailField(event: MouseEvent, configure = false) {
  const fieldName = fieldFromDetailEvent(event);
  if (!fieldName) return;
  if (configure) emit('configureField', 'form', fieldName);
  else emit('selectField', 'form', fieldName);
}
</script>

<template>
  <section
    v-if="mode === 'list'"
    class="page-composition-descriptor-preview"
    data-testid="page-composer-list-preview"
  >
    <header class="page-composition-descriptor-preview__toolbar">
      <strong>列表布局</strong><span>由当前草稿的服务端解析结果驱动</span>
    </header>
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
      <template #cell="{ column, value }">
        <button
          class="page-composition-descriptor-preview__field"
          :class="{ 'page-composition-descriptor-preview__field--selected': isSelected('list', column.key) }"
          type="button"
          :title="`配置${column.title}`"
          @click="emit('selectField', 'list', column.key)"
          @dblclick="emit('configureField', 'list', column.key)"
        >
          {{ value ?? '-' }}
        </button>
      </template>
    </UiDataTable>
  </section>

  <section
    v-else-if="mode === 'card'"
    class="page-composition-descriptor-preview"
    data-testid="page-composer-card-preview"
  >
    <header class="page-composition-descriptor-preview__toolbar">
      <strong>列表卡片</strong><span>派生自列表字段，不产生独立编排配置</span>
    </header>
    <UiEmpty v-if="isListEmpty" description="当前草稿尚未配置列表字段" />
    <div v-else class="page-composition-descriptor-preview__cards">
      <article v-for="index in 2" :key="index" class="page-composition-descriptor-preview__card">
        <header>
          <strong>示例记录 {{ index }}</strong
          ><span>列表卡片</span>
        </header>
        <dl>
          <div
            v-for="column in listColumns"
            :key="column.key"
            class="page-composition-descriptor-preview__card-field"
            :class="{
              'page-composition-descriptor-preview__card-field--selected': isSelected('list', column.key),
            }"
            role="button"
            tabindex="0"
            :title="`配置${column.title}`"
            @click="emit('selectField', 'list', column.key)"
            @dblclick="emit('configureField', 'list', column.key)"
            @keydown.enter="emit('configureField', 'list', column.key)"
            @keydown.space.prevent="emit('configureField', 'list', column.key)"
          >
            <dt>{{ column.title }}</dt>
            <dd>{{ listRecord[column.key] ?? '-' }}</dd>
          </div>
        </dl>
      </article>
    </div>
  </section>

  <section v-else class="page-composition-descriptor-preview" data-testid="page-composer-detail-preview">
    <header class="page-composition-descriptor-preview__toolbar">
      <strong>详情预览</strong><span>字段与布局由当前草稿的服务端解析结果驱动</span>
    </header>
    <UiEmpty v-if="isDetailEmpty" description="当前草稿尚未配置详情字段" />
    <div
      v-else
      class="page-composition-descriptor-preview__detail"
      @click="selectDetailField($event)"
      @dblclick="selectDetailField($event, true)"
    >
      <RecordDetailFields :record="detailRecord" :field-names="detailFieldNames" :fields="detailFields" />
    </div>
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

.page-composition-descriptor-preview__toolbar,
.page-composition-descriptor-preview__card > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.page-composition-descriptor-preview__toolbar span,
.page-composition-descriptor-preview__card > header span {
  color: var(--muyun-text-muted);
  font-size: 12px;
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
.page-composition-descriptor-preview__field--selected,
.page-composition-descriptor-preview__card-field:hover,
.page-composition-descriptor-preview__card-field:focus-visible,
.page-composition-descriptor-preview__card-field--selected {
  outline: 2px solid var(--muyun-primary);
  outline-offset: -2px;
  background: var(--muyun-primary-surface, var(--muyun-hover));
}

.page-composition-descriptor-preview__cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
}

.page-composition-descriptor-preview__card {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  background: var(--muyun-surface);
}

.page-composition-descriptor-preview__card dl {
  display: grid;
  gap: 8px 12px;
  margin: 0;
}

.page-composition-descriptor-preview__card-field {
  display: grid;
  grid-template-columns: minmax(76px, auto) minmax(0, 1fr);
  gap: 8px 12px;
  padding: 4px;
  border-radius: 4px;
  cursor: pointer;
  outline: 1px solid transparent;
}

.page-composition-descriptor-preview__card-field dt {
  color: var(--muyun-text-muted);
}

.page-composition-descriptor-preview__card-field dd {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-composition-descriptor-preview__detail {
  cursor: pointer;
}

.page-composition-descriptor-preview__detail :deep(.record-detail-field) {
  padding: 6px;
  border-radius: 4px;
  outline: 1px solid transparent;
}

.page-composition-descriptor-preview__detail :deep(.record-detail-field:hover) {
  outline-color: var(--muyun-primary);
  background: var(--muyun-primary-surface, var(--muyun-hover));
}
</style>
