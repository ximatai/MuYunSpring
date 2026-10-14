<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed } from 'vue';
import type { Option, OptionValue } from '@muyun/web-contracts';
import { UiDropdown, UiIcon, type UiDropdownItem } from '@muyun/vue-ui-antdv';

const language = useLanguageContext();

defineOptions({ name: 'ApplicationScopeSwitcher' });

const props = withDefaults(
  defineProps<{
    value?: OptionValue | null;
    options: Option[];
    disabled?: boolean;
    placeholder?: string;
  }>(),
  {
    value: undefined,
    disabled: false,
    placeholder: undefined,
  },
);

const resolvedPlaceholder = computed(
  () => props.placeholder ?? language.t('platform.screen.ApplicationScopeSwitcher.placeholder', '选择应用'),
);

const emit = defineEmits<{
  'update:value': [value: OptionValue | null];
}>();

const selectedOption = computed(() =>
  props.options.find((option) => String(option.value) === String(props.value ?? '')),
);
const dropdownItems = computed<UiDropdownItem[]>(() =>
  props.options.map((option) => ({
    key: String(option.value),
    title: option.label,
    disabled: option.disabled,
  })),
);
const displayTitle = computed(() => selectedOption.value?.label ?? resolvedPlaceholder.value);
const switcherDisabled = computed(() => props.disabled || props.options.length === 0);

function selectApplication(key: string) {
  const matched = props.options.find((option) => String(option.value) === key);
  if (!matched || matched.disabled) {
    return;
  }
  emit('update:value', matched.value);
}
</script>

<template>
  <UiDropdown
    :items="dropdownItems"
    :selected-key="String(value ?? '')"
    align="start"
    @select="selectApplication"
  >
    <template #default="{ toggle }">
      <button
        class="application-scope-switcher"
        :class="{ empty: !selectedOption }"
        type="button"
        :disabled="switcherDisabled"
        :title="displayTitle"
        @click.stop="toggle"
      >
        <span class="application-scope-switcher-text">{{ displayTitle }}</span>
        <UiIcon name="down" class="application-scope-switcher-icon" />
      </button>
    </template>
  </UiDropdown>
</template>

<style scoped>
.application-scope-switcher {
  display: inline-flex;
  max-width: 128px;
  height: 28px;
  align-items: center;
  gap: 5px;
  min-width: 0;
  padding: 0 7px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: transparent;
  color: var(--muyun-text-body);
  cursor: pointer;
  font: inherit;
  transition:
    background-color 0.12s ease,
    border-color 0.12s ease,
    color 0.12s ease;
}

.application-scope-switcher:hover:not(:disabled),
.application-scope-switcher:focus-visible {
  border-color: var(--muyun-border-subtle);
  background: var(--muyun-hover-subtle);
  color: var(--muyun-text);
  outline: none;
}

.application-scope-switcher:disabled {
  color: var(--muyun-text-muted);
  cursor: not-allowed;
  opacity: 0.72;
}

.application-scope-switcher.empty {
  color: var(--muyun-text-muted);
}

.application-scope-switcher-text {
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.application-scope-switcher-icon {
  flex: 0 0 auto;
  color: var(--muyun-text-muted);
  font-size: 10px;
}
</style>
