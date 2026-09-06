<script setup lang="ts">
import { computed, onBeforeUnmount, onBeforeUpdate, onUpdated, ref, watch } from 'vue';
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
import {
  type CompositionPlacementSource,
  type CompositionPlacementTarget,
  type PageCompositionStructure,
  type CompositionContainer,
} from './pageCompositionPlacement';
import { usePageCompositionPreviewDrag, type PreviewPlacementEntry } from './usePageCompositionPreviewDrag';

defineOptions({ name: 'PageCompositionDescriptorPreview' });

type PreviewMode = 'list' | 'query' | 'detail' | 'edit';
type PreviewSlot = 'list' | 'form';

const props = defineProps<{
  descriptor: ResolvedModuleUiDescriptor;
  moduleAlias: string;
  mode: PreviewMode;
  selectedFieldName?: string;
  /** Allows the active preview surface to receive a compatible metadata payload. */
  acceptExternalDrop?: boolean;
  structure?: PageCompositionStructure;
  placementDisabled?: boolean;
}>();

const emit = defineEmits<{
  selectField: [slot: PreviewSlot, fieldName: string];
  configureField: [slot: PreviewSlot, fieldName: string];
  'placement-drop': [source: CompositionPlacementSource, target: CompositionPlacementTarget];
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
const structure = computed<PageCompositionStructure>(() => {
  if (props.structure) return props.structure;
  const field = (name: string) => ({ id: name, fieldName: name, title: name });
  const groups = props.descriptor.page?.detail?.editor?.formGroups ?? [];
  const grouped = new Set(groups.flatMap((group) => group.fields.map((field) => field.fieldName)));
  return {
    list: listColumns.value.map((column) => field(column.key)),
    form: [...new Set([...formFieldNames.value, ...detailFieldNames.value])]
      .filter((name) => !grouped.has(name))
      .map(field),
    groups: groups.map((group) => ({
      id: group.groupCode,
      groupCode: group.groupCode,
      title: group.title,
      fields: group.fields.map((ref) => field(ref.fieldName)),
    })),
    relations: detailRelations.value.map((relation) => ({
      id: relation.code,
      relationCode: relation.code,
      title: relation.title ?? relation.code,
      fields: (relation.listProjection?.fields ?? []).map((ref) => field(ref.fieldName)),
    })),
  };
});
const formSections = computed(() => [
  {
    key: 'form',
    title: '表单字段',
    container: { kind: 'form' } as CompositionContainer,
    fields: structure.value.form,
  },
  ...structure.value.groups.map((group) => ({
    key: `group:${group.id}`,
    title: group.title,
    subtitle: group.subtitle,
    container: { kind: 'group', groupId: group.id } as CompositionContainer,
    fields: group.fields,
  })),
]);
// Section geometry is owned by the designer; controls and values still use the runtime renderers.
const ungroupedFormFields = computed(
  () => new Map([...formFields.value].map(([name, field]) => [name, { ...field, formGroup: undefined }])),
);
const placementEntries = computed(() => {
  const entries = new Map<string, PreviewPlacementEntry>();
  const mode = props.mode;
  for (const field of structure.value.list) {
    const entry: PreviewPlacementEntry = {
      title: field.properties?.label ?? field.title,
      container: { kind: 'list' },
      nodeId: field.id,
      axis: 'x',
    };
    entries.set(`list:header:${field.fieldName}`, entry);
    entries.set(`list:field:${field.fieldName}`, entry);
  }
  entries.set('list:end', { title: '列表末尾', container: { kind: 'list' } });
  for (const section of formSections.value) {
    entries.set(`${mode}:container:${section.key}`, { title: section.title, container: section.container });
    for (const field of section.fields)
      entries.set(`${mode}:field:${field.fieldName}`, {
        title: field.properties?.label ?? field.title,
        container: section.container,
        nodeId: field.id,
      });
    if (section.container.kind === 'group')
      entries.set(`${mode}:${section.key}`, {
        title: section.title,
        container: { kind: 'groups' },
        nodeId: section.container.groupId,
        inside: section.container,
      });
  }
  for (const relation of structure.value.relations) {
    entries.set(`${mode}:relation:${relation.relationCode}`, {
      title: relation.title,
      container: { kind: 'relations' },
      nodeId: relation.id,
      inside: { kind: 'relation', relationId: relation.id },
    });
    entries.set(`${mode}:relation:${relation.relationCode}:end`, {
      title: `${relation.title}末列`,
      container: { kind: 'relation', relationId: relation.id },
    });
    for (const field of relation.fields)
      for (const part of ['header', 'field'])
        entries.set(`${mode}:relation:${relation.relationCode}:${part}:${field.fieldName}`, {
          title: field.title,
          container: { kind: 'relation', relationId: relation.id },
          nodeId: field.id,
          axis: 'x',
        });
  }
  entries.set('relations:end', { title: '子表区域末尾', container: { kind: 'relations' } });
  return entries;
});
const { handleProps, feedback } = usePageCompositionPreviewDrag(
  previewRoot,
  placementEntries,
  structure,
  computed(() => !!props.acceptExternalDrop && !props.placementDisabled),
  (source, target) => emit('placement-drop', source, target),
);

watch(
  () => props.descriptor,
  () => {
    // Preserve sample input while a new descriptor updates the placement; samples never enter the draft.
    const sample = previewRecord([...formFields.value.values()]);
    formRecord.value = retainPreviewValues(sample, formRecord.value);
    relationEditorRecords.value = Object.fromEntries(
      detailRelations.value.map((relation) => {
        const sample = relationRecord(relation);
        const previous = relationEditorRecords.value[relation.code]?.find((row) => row.id === sample.id);
        return [relation.code, [retainPreviewValues(sample, previous)]];
      }),
    );
  },
  { immediate: true },
);

function retainPreviewValues(sample: UiDataTableRecord, previous?: UiDataTableRecord): UiDataTableRecord {
  return Object.fromEntries(
    Object.entries(sample).map(([key, value]) => [
      key,
      previous && Object.hasOwn(previous, key) ? previous[key] : value,
    ]),
  );
}

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

function layoutKeyOf(element: HTMLElement) {
  return element.dataset.pageCompositionLayoutKey;
}

// Hover feedback and sample input update the component too, but do not change its composition.
const layoutInputs = computed(() => [
  props.mode,
  listColumns.value,
  detailFields.value,
  formFields.value,
  formSections.value,
  detailRelations.value,
]);
let renderedLayout = layoutInputs.value;
let layoutChanged = false;
const layoutAnimations = new Map<HTMLElement, Animation>();
function cancelLayoutAnimations() {
  layoutAnimations.forEach((animation) => animation.cancel());
  layoutAnimations.clear();
}
onBeforeUnmount(cancelLayoutAnimations);

onBeforeUpdate(() => {
  layoutChanged = renderedLayout !== layoutInputs.value;
  renderedLayout = layoutInputs.value;
  if (!layoutChanged) return;
  previousLayout.clear();
  previewRoot.value
    ?.querySelectorAll<HTMLElement>('[data-page-composition-layout-key]')
    .forEach((element) => {
      const key = layoutKeyOf(element);
      if (key) previousLayout.set(key, element.getBoundingClientRect());
    });
  // Capture the current visual position before removing a superseded composition animation.
  cancelLayoutAnimations();
});

onUpdated(() => {
  if (!layoutChanged) return;
  layoutChanged = false;
  if (typeof window === 'undefined' || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches)
    return;
  // Vue has patched the children here. Start FLIP in this same update, so a new tree order is never
  // exposed as stationary for a frame before its delayed animation starts underneath a second drag.
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
});

function animateLayoutElement(element: HTMLElement, x: number, y: number) {
  if (typeof element.animate === 'function') {
    const animation = element.animate(
      [
        { transform: `translate(${x}px, ${y}px)`, opacity: 0.72 },
        { transform: 'translate(0, 0)', opacity: 1 },
      ],
      { duration: 300, easing: 'cubic-bezier(0.2, 0, 0, 1)' },
    );
    if (animation) layoutAnimations.set(element, animation);
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
    data-testid="page-composer-list-preview"
    tabindex="0"
    data-composer-drop-target="list"
  >
    <div
      v-if="feedback"
      class="page-composer-drop-indicator"
      :class="{ 'page-composer-drop-indicator--rejected': feedback.rejected }"
      :style="{
        left: `${feedback.left}px`,
        top: `${feedback.top}px`,
        width: `${feedback.width}px`,
        height: `${feedback.height}px`,
      }"
      role="status"
    >
      <span>{{ feedback.title }}</span>
    </div>
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
        <span
          class="page-composer-column-heading"
          :data-page-composition-layout-key="`list:header:${column.key}`"
          :data-ui-drop-key="`list:header:${column.key}`"
          tabindex="0"
        >
          <span v-if="acceptExternalDrop" v-bind="handleProps(`list:header:${column.key}`, column.title)"
            >⠿</span
          >{{ column.title }}
        </span>
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
    <div
      v-if="acceptExternalDrop"
      class="page-composer-drop-zone"
      data-composer-target="list:end"
      data-ui-drop-key="list:end"
      tabindex="0"
    >
      拖到此处添加末列
    </div>
  </section>

  <section
    v-else-if="mode === 'query'"
    ref="previewRoot"
    class="page-composition-descriptor-preview"
    data-testid="page-composer-query-preview"
    tabindex="0"
    data-composer-drop-target="list"
  >
    <div
      v-if="feedback"
      class="page-composer-drop-indicator"
      :class="{ 'page-composer-drop-indicator--rejected': feedback.rejected }"
      :style="{
        left: `${feedback.left}px`,
        top: `${feedback.top}px`,
        width: `${feedback.width}px`,
        height: `${feedback.height}px`,
      }"
      role="status"
    >
      <span>{{ feedback.title }}</span>
    </div>
    <label class="page-composition-descriptor-preview__quick-search">
      <span>快速查询</span>
      <UiInput type="search" :value="''" :placeholder="listSearchPlaceholder" aria-label="快速查询" />
    </label>
  </section>

  <section
    v-else-if="mode === 'detail'"
    ref="previewRoot"
    class="page-composition-descriptor-preview"
    data-testid="page-composer-detail-preview"
    tabindex="0"
    data-composer-drop-target="form"
  >
    <div
      v-if="feedback"
      class="page-composer-drop-indicator"
      :class="{ 'page-composer-drop-indicator--rejected': feedback.rejected }"
      :style="{
        left: `${feedback.left}px`,
        top: `${feedback.top}px`,
        width: `${feedback.width}px`,
        height: `${feedback.height}px`,
      }"
      role="status"
    >
      <span>{{ feedback.title }}</span>
    </div>
    <UiEmpty v-if="isDetailEmpty" description="当前草稿尚未配置详情字段或关联子表" />
    <section v-for="section in formSections" :key="section.key" class="page-composer-form-section">
      <header
        v-if="section.container.kind === 'group'"
        :data-composer-target="`detail:${section.key}`"
        :data-ui-drop-key="`detail:${section.key}`"
        tabindex="0"
      >
        <span v-if="acceptExternalDrop" v-bind="handleProps(`detail:${section.key}`, section.title)">⠿</span
        ><strong>{{ section.title }}</strong
        ><small>{{ 'subtitle' in section ? section.subtitle : '' }}</small>
      </header>
      <div class="page-composer-detail-section">
        <RecordDetailFields
          interaction-mode="selectable"
          :record="detailRecord"
          :fields="detailFields"
          :field-names="
            section.fields.map((field) => field.fieldName).filter((name) => detailFields.has(name))
          "
          :selected-field-name="selectedDetailFieldName"
          layout-transition-prefix="detail"
          @select="(name) => emit('selectField', 'form', name)"
          @configure="(name) => emit('configureField', 'form', name)"
        >
          <template #field-actions="{ field }">
            <span
              v-if="acceptExternalDrop"
              v-bind="handleProps(`detail:field:${field.fieldName}`, field.label)"
              :data-ui-drop-key="`detail:field:${field.fieldName}`"
              >⠿</span
            >
          </template>
        </RecordDetailFields>
      </div>
      <div
        v-if="acceptExternalDrop"
        class="page-composer-drop-zone"
        :data-composer-target="`detail:container:${section.key}`"
        :data-ui-drop-key="`detail:container:${section.key}`"
        tabindex="0"
      >
        {{ section.fields.length ? '拖到此处追加字段' : '拖入字段' }}
      </div>
    </section>
    <section
      v-for="relation in detailRelations"
      :key="relation.code"
      class="page-composition-descriptor-preview__relation"
    >
      <header
        :data-composer-target="`detail:relation:${relation.code}`"
        :data-ui-drop-key="`detail:relation:${relation.code}`"
        tabindex="0"
      >
        <strong>{{ relation.title ?? relation.code }}</strong>
        <span
          v-if="acceptExternalDrop"
          v-bind="handleProps(`detail:relation:${relation.code}`, relation.title ?? relation.code)"
          >⠿</span
        >
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
            <span
              class="page-composer-column-heading"
              :data-page-composition-layout-key="`detail:relation:${relation.code}:header:${column.key}`"
              :data-ui-drop-key="`detail:relation:${relation.code}:header:${column.key}`"
              tabindex="0"
            >
              <span
                v-if="acceptExternalDrop"
                v-bind="handleProps(`detail:relation:${relation.code}:header:${column.key}`, column.title)"
                >⠿</span
              >
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
      <div
        v-if="acceptExternalDrop"
        class="page-composer-drop-zone"
        :data-composer-target="`detail:relation:${relation.code}:end`"
        :data-ui-drop-key="`detail:relation:${relation.code}:end`"
        tabindex="0"
      >
        拖入此子表的字段
      </div>
      <UiEmpty v-if="!relation.listProjection?.fields?.length" description="尚未选择子表展示字段" />
    </section>
    <div
      v-if="acceptExternalDrop"
      class="page-composer-drop-zone"
      data-composer-target="relations:end"
      data-ui-drop-key="relations:end"
      tabindex="0"
    >
      拖入子表
    </div>
  </section>

  <section
    v-else
    ref="previewRoot"
    class="page-composition-descriptor-preview"
    data-testid="page-composer-edit-preview"
    tabindex="0"
    data-composer-drop-target="form"
  >
    <div
      v-if="feedback"
      class="page-composer-drop-indicator"
      :class="{ 'page-composer-drop-indicator--rejected': feedback.rejected }"
      :style="{
        left: `${feedback.left}px`,
        top: `${feedback.top}px`,
        width: `${feedback.width}px`,
        height: `${feedback.height}px`,
      }"
      role="status"
    >
      <span>{{ feedback.title }}</span>
    </div>
    <UiEmpty v-if="isEditEmpty" description="当前草稿尚未配置编辑字段或关联子表" />
    <section v-for="section in formSections" :key="section.key" class="page-composer-form-section">
      <header
        v-if="section.container.kind === 'group'"
        :data-composer-target="`edit:${section.key}`"
        :data-ui-drop-key="`edit:${section.key}`"
        tabindex="0"
      >
        <span v-if="acceptExternalDrop" v-bind="handleProps(`edit:${section.key}`, section.title)">⠿</span
        ><strong>{{ section.title }}</strong
        ><small>{{ 'subtitle' in section ? section.subtitle : '' }}</small>
      </header>
      <div class="page-composition-descriptor-preview__form">
        <RecordFormFields
          :record="formRecord"
          :fields="ungroupedFormFields"
          :field-names="section.fields.map((field) => field.fieldName).filter((name) => formFields.has(name))"
          :form-session-key="`page-composer:${moduleAlias}`"
          layout-transition-prefix="edit"
          @update:field="updateFormField"
        >
          <template #field-actions="{ field }">
            <span
              v-if="acceptExternalDrop"
              v-bind="handleProps(`edit:field:${field.fieldName}`, field.label)"
              :data-ui-drop-key="`edit:field:${field.fieldName}`"
              >⠿</span
            >
          </template>
        </RecordFormFields>
      </div>
      <div
        v-if="acceptExternalDrop"
        class="page-composer-drop-zone"
        :data-composer-target="`edit:container:${section.key}`"
        :data-ui-drop-key="`edit:container:${section.key}`"
        tabindex="0"
      >
        {{ section.fields.length ? '拖到此处追加字段' : '拖入字段' }}
      </div>
    </section>
    <section
      v-for="relation in detailRelations"
      :key="relation.code"
      class="page-composition-descriptor-preview__relation page-composition-descriptor-preview__relation--editor"
    >
      <header
        :data-composer-target="`edit:relation:${relation.code}`"
        :data-ui-drop-key="`edit:relation:${relation.code}`"
        tabindex="0"
      >
        <strong>{{ relation.title ?? relation.code }}</strong>
        <span
          v-if="acceptExternalDrop"
          v-bind="handleProps(`edit:relation:${relation.code}`, relation.title ?? relation.code)"
          >⠿</span
        >
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
            <span
              class="page-composer-column-heading"
              :data-page-composition-layout-key="`edit:relation:${relation.code}:header:${column.key}`"
              :data-ui-drop-key="`edit:relation:${relation.code}:header:${column.key}`"
              tabindex="0"
            >
              <span
                v-if="acceptExternalDrop"
                v-bind="handleProps(`edit:relation:${relation.code}:header:${column.key}`, column.title)"
                >⠿</span
              >
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
      <div
        v-if="acceptExternalDrop"
        class="page-composer-drop-zone"
        :data-composer-target="`edit:relation:${relation.code}:end`"
        :data-ui-drop-key="`edit:relation:${relation.code}:end`"
        tabindex="0"
      >
        拖入此子表的字段
      </div>
      <UiEmpty v-if="!relation.listProjection?.fields?.length" description="尚未选择子表展示字段" />
    </section>
    <div
      v-if="acceptExternalDrop"
      class="page-composer-drop-zone"
      data-composer-target="relations:end"
      data-ui-drop-key="relations:end"
      tabindex="0"
    >
      拖入子表
    </div>
  </section>
</template>

<style scoped>
.page-composition-descriptor-preview {
  position: relative;
  display: grid;
  align-content: start;
  gap: 16px;
  min-height: 280px;
  margin-top: 12px;
  padding: 16px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
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

<style scoped>
.page-composer-drop-zone {
  min-height: 28px;
  padding: 5px 8px;
  border: 1px dashed var(--muyun-border);
  color: var(--muyun-text-muted);
  font-size: 12px;
  border-radius: 4px;
}
.page-composer-form-section {
  min-width: 0;
  display: grid;
  gap: 10px;
}
.page-composer-form-section > header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.page-composer-form-section small {
  color: var(--muyun-text-muted);
}
.page-composer-column-heading {
  display: block;
  min-height: 30px;
  padding: 6px;
}
:deep(.page-composer-drag-handle) {
  display: inline-block;
  float: right;
  margin-left: 6px;
  padding: 0 4px;
  color: var(--muyun-text-muted);
  cursor: grab;
  user-select: none;
}
:deep(.page-composer-drag-handle:hover),
:deep(.page-composer-drag-handle:focus-visible) {
  color: var(--muyun-primary);
  background: var(--muyun-hover);
}
.page-composer-drop-indicator {
  position: absolute;
  z-index: 5;
  border: 2px solid var(--muyun-primary);
  background: color-mix(in srgb, var(--muyun-primary) 8%, transparent);
  pointer-events: none;
}
.page-composer-drop-indicator > span {
  position: absolute;
  top: -23px;
  left: 0;
  padding: 1px 6px;
  white-space: nowrap;
  font-size: 12px;
  color: white;
  background: var(--muyun-primary);
}
.page-composer-drop-indicator--rejected {
  border-color: var(--muyun-danger-base);
}
.page-composer-drop-indicator--rejected > span {
  background: var(--muyun-danger-base);
}
</style>
