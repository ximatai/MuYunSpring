<script setup lang="ts">
import { useId } from 'vue';
import { UiSelect } from '@muyun/vue-ui-antdv';
import type { Option, OptionValue, OptionValueList } from '@muyun/web-contracts';

defineOptions({ name: 'RecordQueryEnumFilter' });

withDefaults(
  defineProps<{
    title: string;
    value?: OptionValue | null;
    options: Option[];
    disabled?: boolean;
  }>(),
  {
    value: null,
    disabled: false,
  },
);

const emit = defineEmits<{
  'update:value': [value: OptionValue | null];
}>();

const selectId = useId();

function updateValue(value: OptionValue | OptionValueList | null) {
  emit('update:value', Array.isArray(value) ? null : value);
}
</script>

<template>
  <div class="record-query-enum-filter">
    <label class="record-query-enum-filter__label" :for="selectId">{{ title }}</label>
    <UiSelect
      :id="selectId"
      class="record-query-enum-filter__select"
      :aria-label="title"
      :value="value"
      :options="options"
      :allow-clear="false"
      :disabled="disabled"
      @update:value="updateValue"
    />
  </div>
</template>

<style scoped>
.record-query-enum-filter {
  display: grid;
  grid-template-columns: auto minmax(0, var(--muyun-record-query-enum-filter-width, 120px));
  align-items: center;
  flex: 0 1 auto;
  gap: 8px;
  min-width: 0;
  max-width: 100%;
  white-space: nowrap;
}

.record-query-enum-filter__label {
  color: var(--muyun-text-muted);
  font-size: 13px;
  font-weight: 400;
  line-height: 20px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.record-query-enum-filter__select {
  width: 100%;
  min-width: 0;
}
</style>
