<script setup lang="ts">
import { computed } from 'vue';
import type { ModuleContext } from '@muyun/web-core';
import { UiActionButton, type UiIconName } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'ModuleActionButton' });

const props = withDefaults(
  defineProps<{
    context: ModuleContext<unknown>;
    actionCode: string;
    type?: 'button' | 'submit' | 'reset';
    disabled?: boolean;
    loading?: boolean;
    primary?: boolean;
    danger?: boolean;
    title?: string;
    iconName?: UiIconName;
    iconOnly?: boolean;
  }>(),
  {
    type: 'button',
    disabled: false,
    loading: false,
    primary: false,
    danger: false,
    title: undefined,
    iconName: undefined,
    iconOnly: false,
  },
);

const emit = defineEmits<{
  click: [event: MouseEvent];
}>();

const action = computed(() => props.context.action(props.actionCode));
const runtimeLoaded = computed(() => props.context.runtime.snapshot() !== undefined);
const authorized = computed(() => action.value?.available === true);
const buttonDisabled = computed(() => props.loading || props.disabled || !authorized.value);
const buttonTitle = computed(() => props.title ?? action.value?.title);

function handleClick(event: MouseEvent) {
  if (buttonDisabled.value) {
    event.preventDefault();
    return;
  }
  emit('click', event);
}

function defaultIconName(actionCode: string): UiIconName | undefined {
  const operation = actionCode.split('_').at(-1) ?? actionCode;
  if (operation === 'create') {
    return 'plus';
  }
  if (operation === 'update') {
    return 'edit';
  }
  if (operation === 'delete') {
    return 'delete';
  }
  if (operation === 'enable' || operation === 'disable') {
    return 'power';
  }
  return undefined;
}
</script>

<template>
  <UiActionButton
    v-if="!runtimeLoaded || action"
    :submit="type === 'submit'"
    :emphasis="primary ? 'primary' : 'secondary'"
    :disabled="buttonDisabled"
    :loading="loading"
    :intent="danger ? 'danger' : 'normal'"
    :icon-name="iconName ?? defaultIconName(actionCode)"
    :title="buttonTitle"
    :class="{ 'module-action-button--icon-only': iconOnly }"
    @click="handleClick"
  >
    <template v-if="!iconOnly">
      <slot>{{ action?.title ?? actionCode }}</slot>
    </template>
  </UiActionButton>
</template>

<style scoped>
.module-action-button--icon-only {
  width: 28px;
  min-width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 999px;
}
</style>
