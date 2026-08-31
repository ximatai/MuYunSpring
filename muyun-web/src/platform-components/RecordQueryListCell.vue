<script setup lang="ts">
import { computed } from 'vue';
import DateTimeText from './DateTimeText.vue';
import FileSizeText from './FileSizeText.vue';
import RecordStatusTag from './RecordStatusTag.vue';
import RecordTagList from './RecordTagList.vue';
import { resolveRecordBooleanStatusValue } from './recordFormFieldModel';
import type { QueryListRecord, RecordQueryListColumn } from './recordQueryListColumnModel';

defineOptions({ name: 'RecordQueryListCell' });

const props = withDefaults(
  defineProps<{
    record: QueryListRecord;
    column: RecordQueryListColumn;
    /** Legacy per-column renderers remain an extension of the standard list cell, not of a table shell. */
    cellRenderers?: Record<string, (record: QueryListRecord) => string>;
  }>(),
  { cellRenderers: () => ({}) },
);

const renderedValue = computed<unknown>(
  () => props.column.render?.(props.record) ?? props.cellRenderers[props.column.key]?.(props.record),
);
const displayValue = computed(() => {
  if (renderedValue.value !== undefined) return renderedValue.value;
  return displayRecordFieldValue(props.record, props.column.key, props.column.titleField);
});
const dateTimeValue = computed(() =>
  scalarPresentationValue(renderedValue.value ?? props.record[props.column.key]),
);
const fileSizeValue = computed(() =>
  fileSizePresentationValue(renderedValue.value ?? props.record[props.column.key]),
);
const statusValue = computed(() =>
  props.column.type === 'booleanStatus'
    ? resolveRecordBooleanStatusValue(props.record[props.column.key])
    : props.record[props.column.key] !== false,
);

function displayRecordFieldValue(record: QueryListRecord, fieldName: string, titleField?: string) {
  const titleFields = [titleField, `${fieldName}Title`].filter(
    (value, index, fields): value is string => Boolean(value) && fields.indexOf(value) === index,
  );
  for (const candidate of titleFields) {
    const titleValue = record[candidate];
    if (typeof titleValue === 'string' && titleValue.trim()) return titleValue;
  }
  const value = record[fieldName];
  const optionTitles = optionTitlesOf(value, props.column.optionItems);
  if (optionTitles.length > 0) return optionTitles.join('、');
  if (typeof value === 'boolean') return value ? '是' : '否';
  return String(value ?? '');
}

/**
 * Dictionary/reference selections may be transported as an array or as the JSON-set column
 * representation.  Keep this at the shared list-cell boundary so normal lists, cards and
 * managed relation tables render the same titles.
 */
function optionTitlesOf(value: unknown, optionItems: typeof props.column.optionItems): string[] {
  if (!optionItems?.length) return [];
  const titles: string[] = [];
  for (const code of selectionCodesOf(value)) {
    const option = optionItems.find((item) => item.code === code);
    titles.push(option?.title ?? code);
  }
  return titles;
}

function selectionCodesOf(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(String).filter(Boolean);
  if (typeof value !== 'string') return value == null ? [] : [String(value)];
  const trimmed = value.trim();
  if (!trimmed) return [];
  if (trimmed.startsWith('[')) {
    try {
      const parsed: unknown = JSON.parse(trimmed);
      if (Array.isArray(parsed)) return parsed.map(String).filter(Boolean);
    } catch {
      // It is a scalar string beginning with "["; leave the persisted value visible.
    }
  }
  return [value];
}

function scalarPresentationValue(value: unknown) {
  if (value === null || value === undefined) return undefined;
  if (value instanceof Date || typeof value === 'string' || typeof value === 'number') return value;
  return String(value);
}

function fileSizePresentationValue(value: unknown) {
  return typeof value === 'number' || typeof value === 'string' || typeof value === 'bigint'
    ? value
    : undefined;
}
</script>

<template>
  <RecordStatusTag
    v-if="column.type === 'enabledStatus' || column.type === 'booleanStatus'"
    :enabled="statusValue"
    :enabled-label="column.booleanStatus?.trueLabel"
    :disabled-label="column.booleanStatus?.falseLabel"
    :enabled-tone="column.booleanStatus?.trueTone"
    :disabled-tone="column.booleanStatus?.falseTone"
  />
  <RecordTagList v-else-if="column.type === 'tagList'" :items="record[column.key]" />
  <DateTimeText v-else-if="column.type === 'datetime'" :value="dateTimeValue" />
  <FileSizeText v-else-if="column.type === 'fileSize'" :value="fileSizeValue" />
  <span v-else-if="column.type === 'colorPicker'" class="record-query-list-color">
    <i :style="{ backgroundColor: String(record[column.key] ?? '') }" aria-hidden="true" />
    {{ displayValue }}
  </span>
  <span
    v-else
    class="record-query-list-text"
    :style="{ '--record-query-list-max-lines': String(column.maxDisplayLines ?? 1) }"
    :title="String(displayValue)"
    >{{ displayValue }}</span
  >
</template>

<style scoped>
.record-query-list-color {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.record-query-list-color i {
  width: 14px;
  height: 14px;
  border: 1px solid rgb(15 23 42 / 18%);
  border-radius: 50%;
}

.record-query-list-text {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: var(--record-query-list-max-lines);
  line-clamp: var(--record-query-list-max-lines);
  white-space: normal;
  word-break: break-word;
}
</style>
