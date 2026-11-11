<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import RecordReferencePicker from './RecordReferencePicker.vue';
import type { RecordReferencePickerSource } from './recordReferencePickerProvider';
import type { RecordPickerRecord } from './recordPickerConstraints';

defineOptions({ name: 'RecordMultiPicker' });
const language = useLanguageContext();
const props = withDefaults(
  defineProps<
    RecordReferencePickerSource & {
      value?: string[];
      reloadKey?: string | number;
      placeholder?: string;
      invalid?: boolean;
      disabled?: boolean;
      allowClear?: boolean;
    }
  >(),
  { allowClear: true },
);
const emit = defineEmits<{
  'update:value': [value: string[]];
  select: [value: RecordPickerRecord[]];
  'selection-resolved': [value: RecordPickerRecord[]];
}>();
function updateValue(value: string | string[] | undefined) {
  emit('update:value', Array.isArray(value) ? value : value ? [value] : []);
}
</script>

<template>
  <RecordReferencePicker
    v-bind="props"
    :multiple="true"
    :placeholder="placeholder ?? language.t('platform.screen.RecordMultiPicker.placeholder', '请选择')"
    @update:value="updateValue"
    @select="(records) => emit('select', records)"
    @selection-resolved="(records) => emit('selection-resolved', records)"
  />
</template>
