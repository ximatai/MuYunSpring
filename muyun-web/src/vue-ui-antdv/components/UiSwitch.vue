<script setup lang="ts">
import { computed } from 'vue';
import { Switch as ASwitch } from 'ant-design-vue';

defineOptions({ name: 'UiSwitch', inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    checked?: boolean;
    disabled?: boolean;
    loading?: boolean;
    size?: 'default' | 'small';
    checkedText?: string;
    uncheckedText?: string;
  }>(),
  {
    checked: false,
    disabled: false,
    loading: false,
    size: 'default',
    checkedText: undefined,
    uncheckedText: undefined,
  },
);

const hasText = computed(() => Boolean(props.checkedText || props.uncheckedText));

const emit = defineEmits<{
  'update:checked': [checked: boolean];
  change: [checked: boolean];
}>();

function handleChange(checked: unknown) {
  const normalized = checked === true;
  emit('update:checked', normalized);
  emit('change', normalized);
}
</script>

<template>
  <ASwitch
    v-if="hasText"
    :checked="props.checked"
    :disabled="props.disabled"
    :loading="props.loading"
    :size="props.size"
    :class="[$attrs.class, { 'ui-switch--icon-only': !hasText }]"
    :style="$attrs.style"
    @change="handleChange"
  >
    <template v-if="props.checkedText" #checkedChildren>{{ props.checkedText }}</template>
    <template v-if="props.uncheckedText" #unCheckedChildren>{{ props.uncheckedText }}</template>
  </ASwitch>
  <ASwitch
    v-else
    :checked="props.checked"
    :disabled="props.disabled"
    :loading="props.loading"
    :size="props.size"
    :class="[$attrs.class, 'ui-switch--icon-only']"
    :style="$attrs.style"
    @change="handleChange"
  />
</template>

<style>
.ui-switch--icon-only.ant-switch {
  width: 44px;
}
</style>
