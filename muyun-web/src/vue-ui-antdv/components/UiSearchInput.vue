<script setup lang="ts">
import { InputSearch as AInputSearch } from 'ant-design-vue';

defineOptions({ name: 'UiSearchInput', inheritAttrs: false });

withDefaults(
  defineProps<{
    value?: string;
    placeholder?: string;
    disabled?: boolean;
    loading?: boolean;
    searchText?: string;
    /** Uses a compact icon action for browse/filter entry points while preserving standard search semantics. */
    searchIconOnly?: boolean;
  }>(),
  {
    value: '',
    placeholder: undefined,
    disabled: false,
    loading: false,
    searchText: undefined,
    searchIconOnly: false,
  },
);

const emit = defineEmits<{
  'update:value': [value: string];
  search: [value: string, source?: 'input' | 'clear'];
}>();

function handleSearch(value: string, _event?: Event, info?: { source?: 'input' | 'clear' }) {
  emit('search', value, info?.source);
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return;
  event.preventDefault();
  emit('update:value', '');
  emit('search', '');
}
</script>

<template>
  <AInputSearch
    :value="value"
    :placeholder="placeholder"
    :disabled="disabled"
    :loading="loading"
    :enter-button="searchIconOnly ? true : (searchText ?? false)"
    allow-clear
    :class="$attrs.class"
    :style="$attrs.style"
    @update:value="emit('update:value', $event)"
    @search="handleSearch"
    @keydown="handleKeydown"
  />
</template>
