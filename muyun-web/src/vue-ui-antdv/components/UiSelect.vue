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
    placeholder?: string;
    disabled?: boolean;
    allowClear?: boolean;
    showSearch?: boolean;
    filterOption?: boolean;
    loading?: boolean;
    id?: string;
    ariaLabel?: string;
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
    id: undefined,
    ariaLabel: undefined,
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

const dropdownOpen = ref<boolean>();

const searchListeners = computed(() =>
  props.showSearch ? { search: (keyword: string) => emit('search', keyword) } : {},
);
</script>

<template>
  <div :class="$slots.suffixAction ? 'ui-select-action-shell' : 'ui-select-pass-through'">
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
      :show-arrow="!$slots.suffixAction"
      :id="id"
      :aria-label="ariaLabel"
      :class="[$attrs.class, { 'ui-select--suffix-action': !!$slots.suffixAction }]"
      :open="dropdownOpen"
      @dropdown-visible-change="dropdownOpen = $event"
      :style="$attrs.style"
      v-on="searchListeners"
      @update:value="normalize"
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
</style>
