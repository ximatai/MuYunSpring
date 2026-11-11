<script setup lang="ts">
import { computed, ref } from 'vue';
import { Select as ASelect } from 'ant-design-vue';
import type { Option, OptionValue, OptionValueList } from '@muyun/web-contracts';

defineOptions({ name: 'UiSelect', inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    value?: OptionValue | OptionValueList | null;
    options: Option[];
    mode?: 'multiple';
    maxTagCount?: number | 'responsive';
    maxTagTextLength?: number;
    /** Optional controlled popup state for compact selectors that browse in a dialog. */
    open?: boolean;
    placeholder?: string;
    disabled?: boolean;
    allowClear?: boolean;
    showSearch?: boolean;
    /** Controlled search draft for callers that must resolve typed text on blur. */
    searchValue?: string;
    filterOption?: boolean;
    loading?: boolean;
    /** Marks an unresolved free-text draft without changing the selected option value. */
    invalid?: boolean;
    unmatched?: boolean;
    id?: string;
    ariaLabel?: string;
  }>(),
  {
    value: undefined,
    mode: undefined,
    maxTagCount: undefined,
    maxTagTextLength: undefined,
    open: undefined,
    placeholder: undefined,
    disabled: false,
    allowClear: true,
    showSearch: false,
    searchValue: undefined,
    filterOption: true,
    loading: false,
    invalid: false,
    unmatched: false,
    id: undefined,
    ariaLabel: undefined,
  },
);

const emit = defineEmits<{
  'update:value': [value: OptionValue | OptionValueList | null];
  'update:searchValue': [value: string];
  search: [keyword: string];
  /** Requests another remote candidate page when the popup reaches its end. */
  'reach-end': [];
  blur: [event: FocusEvent];
  dblclick: [event: MouseEvent];
  keydown: [event: KeyboardEvent];
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

function dropdownScrolled(event: UIEvent) {
  const target = event.target;
  if (target instanceof HTMLElement && target.scrollTop + target.clientHeight >= target.scrollHeight - 8) {
    emit('reach-end');
  }
}

function forwardBlur(event: FocusEvent) {
  const shell = event.currentTarget;
  const nextFocus = event.relatedTarget;
  if (shell instanceof Element && nextFocus instanceof Node && shell.contains(nextFocus)) return;
  emit('blur', event);
}

const dropdownOpen = ref<boolean>();

const searchListeners = computed(() =>
  props.showSearch ? { search: (keyword: string) => emit('search', keyword) } : {},
);

function handleDoubleClick(event: MouseEvent) {
  dropdownOpen.value = false;
  emit('dblclick', event);
}
</script>

<template>
  <div
    :class="$slots.suffixAction ? 'ui-select-action-shell' : 'ui-select-pass-through'"
    @focusout="forwardBlur"
  >
    <ASelect
      :allow-clear="allowClear"
      :id="id"
      :mode="mode"
      :max-tag-count="maxTagCount"
      :max-tag-text-length="maxTagTextLength"
      :value="value ?? undefined"
      :options="options"
      :placeholder="placeholder"
      :disabled="disabled"
      :show-search="showSearch"
      :search-value="searchValue"
      :filter-option="filterOption"
      option-filter-prop="label"
      :loading="loading"
      :show-arrow="!$slots.suffixAction"
      :aria-label="ariaLabel"
      :aria-invalid="invalid || unmatched || undefined"
      :status="invalid || unmatched ? 'error' : undefined"
      :class="[
        $attrs.class,
        {
          'ui-select--suffix-action': !!$slots.suffixAction,
          'ui-select--unmatched': unmatched,
        },
      ]"
      :open="open ?? dropdownOpen"
      :style="$attrs.style"
      @dropdown-visible-change="dropdownOpen = $event"
      @popup-scroll="dropdownScrolled"
      v-on="searchListeners"
      @update:value="normalize"
      @update:search-value="emit('update:searchValue', $event)"
      @dblclick="handleDoubleClick"
      @input-key-down="emit('keydown', $event)"
    />
    <span
      v-if="$slots.suffixAction"
      class="ui-select-suffix-action"
      @mousedown.prevent.stop
      @click.stop="dropdownOpen = false"
    >
      <slot name="suffixAction" />
    </span>
  </div>
</template>

<style scoped>
.ui-select-pass-through {
  display: contents;
}
.ui-select-action-shell {
  position: relative;
  width: 100%;
}
.ui-select--suffix-action {
  width: 100%;
}
.ui-select--suffix-action :deep(.ant-select-clear) {
  right: 36px;
}
.ui-select--suffix-action :deep(.ant-select-selector) {
  padding-right: 60px !important;
}
.ui-select-suffix-action {
  position: absolute;
  top: 50%;
  right: 6px;
  transform: translateY(-50%);
  display: inline-flex;
  align-items: center;
}
.ui-select--unmatched :deep(.ant-select-selection-search-input) {
  color: transparent !important;
  caret-color: var(--muyun-text);
}
</style>
