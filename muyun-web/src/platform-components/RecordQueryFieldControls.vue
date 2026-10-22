<script setup lang="ts">
import { useLanguageContext, type ModuleContext } from '@muyun/web-core';
import { UiButton } from '@muyun/vue-ui-antdv';
import type { Option, QueryOperator, QuerySchemaField } from '@muyun/web-contracts';
import QueryValueEditor from './QueryValueEditor.vue';
import type { RecordPickerRecord } from './recordPickerConstraints';
import type { ReferencePickerConfig, ReferencePickerValidity } from './referencePickerModel';

defineOptions({ name: 'RecordQueryFieldControls' });
defineProps<{
  controls: Array<{
    id: string;
    title: string;
    field?: QuerySchemaField;
    operator: QueryOperator;
    values: unknown[];
    options?: Option[];
    referenceContext?: ModuleContext<RecordPickerRecord>;
    referencePicker?: ReferencePickerConfig;
  }>;
  resetKey: string | number;
  disabled: boolean;
  valid: boolean;
}>();
const emit = defineEmits<{
  'update:values': [id: string, values: unknown[]];
  validity: [id: string, validity: ReferencePickerValidity];
  apply: [];
  reset: [];
}>();
const language = useLanguageContext();
</script>

<template>
  <div v-for="control in controls" :key="control.id" class="record-query-list-persistent-field-control">
    <span class="record-query-list-persistent-field-label">{{ control.title }}</span>
    <QueryValueEditor
      v-if="control.field"
      :key="`${control.id}:${resetKey}`"
      :field="control.field"
      :operator="control.operator"
      :values="control.values"
      :options="control.options"
      :reference-context="control.referenceContext"
      :reference-picker="control.referencePicker"
      :disabled="disabled"
      @submit="emit('apply')"
      @update:values="emit('update:values', control.id, $event)"
      @validity-change="emit('validity', control.id, $event)"
    />
    <span v-else class="record-query-list-persistent-field-error">{{
      language.t('platform.screen.RecordQueryListPanel.field-unavailable', '字段不可用')
    }}</span>
  </div>
  <div v-if="controls.length" class="record-query-list-persistent-field-actions">
    <UiButton type="primary" :disabled="disabled || !valid" @click="emit('apply')">{{
      language.t('platform.screen.RecordQueryListPanel.search', '查询')
    }}</UiButton>
    <UiButton type="text" :disabled="disabled" @click="emit('reset')">{{
      language.t('platform.screen.RecordQueryListPanel.reset', '重置')
    }}</UiButton>
  </div>
</template>

<style scoped>
.record-query-list-persistent-field-control {
  display: inline-grid;
  grid-template-columns: auto minmax(180px, 1fr);
  align-items: center;
  gap: 6px;
  min-width: min(360px, 100%);
}

.record-query-list-persistent-field-label {
  color: var(--muyun-text-muted);
  font-size: 13px;
  white-space: nowrap;
}

.record-query-list-persistent-field-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.record-query-list-persistent-field-error {
  color: var(--muyun-danger);
  font-size: 13px;
}
</style>
