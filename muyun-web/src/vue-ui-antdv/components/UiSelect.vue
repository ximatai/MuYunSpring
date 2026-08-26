<script setup lang="ts">
import { computed } from 'vue';
import { Select as ASelect } from 'ant-design-vue';
import type { Option, OptionValue, OptionValueList } from '@muyun/web-contracts';

defineOptions({ name: 'UiSelect', inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    value?: OptionValue | OptionValueList | null;
    options: Option[];
    mode?: 'multiple';
    placeholder?: string;
    disabled?: boolean;
    allowClear?: boolean;
    showSearch?: boolean;
    filterOption?: boolean;
    loading?: boolean;
  }>(),
  {
    value: undefined,
    mode: undefined,
    placeholder: undefined,
    disabled: false,
    allowClear: true,
    showSearch: false,
    filterOption: true,
    loading: false,
  },
);

const emit = defineEmits<{
  'update:value': [value: OptionValue | OptionValueList | null];
  search: [keyword: string];
}>();

function normalize(value: unknown) {
  if (Array.isArray(value)) {
    emit(
      'update:value',
      value.filter((item): item is OptionValue => typeof item === 'string' || typeof item === 'number'),
    );
    return;
  }
  emit('update:value', typeof value === 'string' || typeof value === 'number' ? value : null);
}

const searchListeners = computed(() =>
  props.showSearch ? { onSearch: (keyword: string) => emit('search', keyword) } : {},
);
</script>

<template>
  <ASelect
    :allow-clear="allowClear"
    :mode="mode"
    :value="value ?? undefined"
    :options="options"
    :placeholder="placeholder"
    :disabled="disabled"
    :show-search="showSearch"
    :filter-option="filterOption"
    :loading="loading"
    :class="$attrs.class"
    :style="$attrs.style"
    v-on="searchListeners"
    @update:value="normalize"
  />
</template>
