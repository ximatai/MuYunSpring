<script setup lang="ts">
import { computed, ref } from 'vue';
import { TreeSelect as ATreeSelect } from 'ant-design-vue';
import type { OptionValue, OptionValueList } from '@muyun/web-contracts';

defineOptions({ name: 'UiTreeSelect', inheritAttrs: false });

export interface UiTreeSelectNode {
  value: OptionValue;
  title: string;
  disabled?: boolean;
  children?: UiTreeSelectNode[];
}

const props = withDefaults(
  defineProps<{
    value?: OptionValue | OptionValueList | null;
    treeData: UiTreeSelectNode[];
    mode?: 'multiple';
    placeholder?: string;
    disabled?: boolean;
    allowClear?: boolean;
    showSearch?: boolean;
    /** Controlled search draft for callers that must resolve typed text on blur. */
    searchValue?: string;
    filterTreeNode?: boolean;
    loading?: boolean;
    /** Marks an unresolved free-text draft without changing the selected option value. */
    unmatched?: boolean;
  }>(),
  {
    value: undefined,
    mode: undefined,
    placeholder: undefined,
    disabled: false,
    allowClear: true,
    showSearch: false,
    searchValue: undefined,
    filterTreeNode: true,
    loading: false,
    unmatched: false,
  },
);

const emit = defineEmits<{
  'update:value': [value: OptionValue | OptionValueList | null];
  'update:searchValue': [value: string];
  search: [keyword: string];
  blur: [event: FocusEvent];
  dblclick: [event: MouseEvent];
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

function forwardBlur(event: FocusEvent) {
  const shell = event.currentTarget;
  const nextFocus = event.relatedTarget;
  if (shell instanceof Element && nextFocus instanceof Node && shell.contains(nextFocus)) return;
  emit('blur', event);
}

const searchListeners = computed(() =>
  props.showSearch ? { search: (keyword: string) => emit('search', keyword) } : {},
);
const dropdownOpen = ref<boolean>();

function handleDoubleClick(event: MouseEvent) {
  dropdownOpen.value = false;
  emit('dblclick', event);
}
</script>

<template>
  <div
    :class="$slots.suffixAction ? 'ui-tree-select-action-shell' : 'ui-tree-select-pass-through'"
    @focusout="forwardBlur"
  >
    <ATreeSelect
      :value="value ?? undefined"
      :tree-data="treeData"
      :multiple="mode === 'multiple'"
      :allow-clear="allowClear"
      :show-search="showSearch"
      :search-value="searchValue"
      :filter-tree-node="filterTreeNode"
      :placeholder="placeholder"
      :disabled="disabled"
      :loading="loading"
      tree-default-expand-all
      :class="[
        $attrs.class,
        { 'ui-tree-select--suffix-action': !!$slots.suffixAction, 'ui-tree-select--unmatched': unmatched },
      ]"
      :style="$attrs.style"
      :open="dropdownOpen"
      :show-arrow="!$slots.suffixAction"
      :aria-invalid="unmatched || undefined"
      @dropdown-visible-change="dropdownOpen = $event"
      @update:value="normalize"
      @update:search-value="emit('update:searchValue', $event)"
      @dblclick="handleDoubleClick"
      v-on="searchListeners"
    />
    <span
      v-if="$slots.suffixAction"
      class="ui-tree-select-suffix-action"
      @mousedown.prevent.stop
      @click.stop="dropdownOpen = false"
    >
      <slot name="suffixAction" />
    </span>
  </div>
</template>

<style scoped>
.ui-tree-select-pass-through {
  display: contents;
}
.ui-tree-select-action-shell {
  position: relative;
  width: 100%;
}
.ui-tree-select--suffix-action :deep(.ant-select-clear) {
  right: 36px;
}
.ui-tree-select--suffix-action :deep(.ant-select-selector) {
  padding-right: 60px !important;
}
.ui-tree-select-suffix-action {
  position: absolute;
  top: 50%;
  right: 6px;
  transform: translateY(-50%);
  display: inline-flex;
  align-items: center;
}
.ui-tree-select--unmatched :deep(.ant-select-selection-search-input) {
  color: transparent !important;
  caret-color: var(--muyun-text);
}
</style>
