<script setup lang="ts">
import { computed, h } from 'vue';
import { Button as AButton, InputSearch as AInputSearch } from 'ant-design-vue';
import { SearchOutlined } from '@ant-design/icons-vue';

defineOptions({ name: 'UiSearchInput', inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    value?: string;
    placeholder?: string;
    disabled?: boolean;
    loading?: boolean;
    searchText?: string;
    /** Uses a compact icon action for browse/filter entry points while preserving standard search semantics. */
    searchIconOnly?: boolean;
    /** The displayed text represents an associated record rather than a search draft. */
    linked?: boolean;
    unmatched?: boolean;
  }>(),
  {
    value: '',
    placeholder: undefined,
    disabled: false,
    loading: false,
    searchText: undefined,
    searchIconOnly: false,
    linked: false,
    unmatched: false,
  },
);

const enterButton = computed(() =>
  props.searchIconOnly
    ? h(
        AButton,
        {
          type: 'primary',
          tabindex: -1,
          disabled: props.disabled,
          loading: props.loading,
          'aria-label': '搜索并选择',
        },
        { default: () => h(SearchOutlined) },
      )
    : (props.searchText ?? false),
);

const emit = defineEmits<{
  'update:value': [value: string];
  search: [value: string, source?: 'input' | 'clear'];
  blur: [event: FocusEvent];
  change: [event: Event];
  dblclick: [event: MouseEvent];
}>();

function handleSearch(value: string, event?: Event, info?: { source?: 'input' | 'clear' }) {
  // Ant Design Vue 4 reports clearing as a click with a cloned input target; button clicks
  // and Enter are searches even when the text is empty. Keep this adapter detail out of pickers.
  const source =
    info?.source ?? (event?.type === 'click' && event.target instanceof HTMLInputElement ? 'clear' : 'input');
  emit('search', value, source);
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return;
  event.preventDefault();
  emit('update:value', '');
  emit('search', '', 'clear');
}
</script>

<template>
  <AInputSearch
    :value="value"
    :placeholder="placeholder"
    :disabled="disabled"
    :loading="loading"
    :enter-button="enterButton"
    allow-clear
    :class="[
      $attrs.class,
      {
        'ui-search-input--integrated': searchIconOnly,
        'ui-search-input--linked': linked,
        'ui-search-input--unmatched': unmatched,
      },
    ]"
    :style="$attrs.style"
    :aria-invalid="unmatched || undefined"
    @update:value="emit('update:value', $event)"
    @search="handleSearch"
    @blur="emit('blur', $event)"
    @change="emit('change', $event)"
    @keydown="handleKeydown"
    @dblclick="emit('dblclick', $event)"
  />
</template>

<style scoped>
.ui-search-input--linked :deep(input.ant-input:not(:disabled)) {
  color: var(--muyun-theme-base);
}
.ui-search-input--unmatched :deep(input.ant-input:not(:disabled)) {
  color: var(--muyun-warning-soft-text);
  text-decoration: line-through;
}
.ui-search-input--integrated :deep(.ant-input-group) {
  display: flex;
  align-items: stretch;
  border-radius: 4px;
}
.ui-search-input--integrated :deep(.ant-input-group:focus-within) {
  box-shadow: 0 0 0 2px var(--muyun-theme-focus);
}
.ui-search-input--integrated :deep(.ant-input-affix-wrapper) {
  flex: 1;
  min-width: 0;
  width: 0;
  border-inline-end-width: 0;
  box-shadow: none;
}
.ui-search-input--integrated :deep(.ant-input-group > .ant-input-group-addon:last-child) {
  display: flex;
  align-items: stretch;
  width: auto;
  left: 0;
}
.ui-search-input--integrated :deep(.ant-input-search-button) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: auto;
  box-shadow: none;
}
.ui-search-input--integrated :deep(.ant-input-search-button:not(:disabled)) {
  color: var(--muyun-theme-base);
  background: var(--muyun-theme-soft);
  border-color: var(--muyun-theme-border);
}
.ui-search-input--integrated :deep(.ant-input-search-button:not(:disabled):hover),
.ui-search-input--integrated :deep(.ant-input-search-button:not(:disabled):active) {
  background: var(--muyun-theme-focus);
  border-color: var(--muyun-theme-base);
}
</style>
