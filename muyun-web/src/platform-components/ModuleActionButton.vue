<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { ModuleContext } from '@muyun/web-core';
import { UiActionButton, UiTooltip, type UiIconName } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'ModuleActionButton' });

const props = withDefaults(
  defineProps<{
    context: ModuleContext<unknown>;
    actionCode: string;
    /** Resolves the existing record-level action contract for a selected record. */
    recordId?: string;
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
    recordId: undefined,
  },
);

const emit = defineEmits<{
  click: [event: MouseEvent];
}>();

const actionAvailabilityLoading = ref(false);
let actionAvailabilitySequence = 0;

watch(
  () => [props.recordId, props.actionCode] as const,
  async ([recordId]) => {
    const sequence = ++actionAvailabilitySequence;
    if (!recordId) {
      actionAvailabilityLoading.value = false;
      return;
    }
    actionAvailabilityLoading.value = true;
    try {
      await props.context.recordActions(recordId);
    } catch {
      // The action endpoint remains authoritative; keep the UI safely disabled on a load failure.
    } finally {
      if (sequence === actionAvailabilitySequence) {
        actionAvailabilityLoading.value = false;
      }
    }
  },
  { immediate: true },
);

const action = computed(() => props.context.action(props.actionCode, props.recordId));
const runtimeLoaded = computed(() => props.context.runtime.snapshot() !== undefined);
const authorized = computed(() => action.value?.available === true);
const buttonDisabled = computed(
  () => props.loading || props.disabled || actionAvailabilityLoading.value || !authorized.value,
);
const buttonTitle = computed(() => {
  if (buttonDisabled.value) {
    return actionAvailabilityLoading.value
      ? '正在校验操作可用性'
      : (action.value?.reason ?? props.title ?? action.value?.title);
  }
  return props.title ?? action.value?.title;
});

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
  <UiTooltip v-if="!runtimeLoaded || action || recordId" :title="buttonDisabled ? (buttonTitle ?? '') : ''">
    <span class="module-action-button-tooltip-trigger">
      <UiActionButton
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
    </span>
  </UiTooltip>
</template>

<style scoped>
.module-action-button--icon-only {
  width: 28px;
  min-width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 999px;
}

.module-action-button-tooltip-trigger {
  display: inline-flex;
}
</style>
