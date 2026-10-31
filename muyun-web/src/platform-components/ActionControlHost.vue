<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, toRaw, watch } from 'vue';
import type { ActionControl, ActionControlContext } from './actionControl';
import { useRecordSurfaceActivity } from './recordSurfaceActivity';

const props = withDefaults(
  defineProps<{
    control: ActionControl;
    actionKey: string;
    title: string;
    disabled?: boolean;
    disabledReason?: string;
    loading?: boolean;
  }>(),
  { disabled: false, disabledReason: undefined, loading: false },
);
const surfaceActive = useRecordSurfaceActivity();
const usable = computed(
  () => surfaceActive.value && props.control.active && !props.disabled && !props.loading,
);
const controller = shallowRef(new AbortController());
watch(
  [
    () => props.actionKey,
    () => props.control.component,
    () => props.control.moduleAlias,
    () => props.control.scopeKey,
    () => props.control.record.id,
    () => props.control.sessionKey,
    () => usable.value,
  ],
  () => {
    controller.value.abort();
    const next = new AbortController();
    if (!usable.value) next.abort();
    controller.value = next;
  },
  { immediate: true, flush: 'sync' },
);
const context = computed<Readonly<ActionControlContext>>(() => {
  const signal = controller.value.signal;
  return Object.freeze({
    actionKey: props.actionKey,
    title: props.title,
    moduleAlias: props.control.moduleAlias,
    record: props.control.record,
    sessionKey: props.control.sessionKey,
    active: usable.value,
    disabled: Boolean(props.disabled) || !usable.value,
    disabledReason: props.disabledReason,
    loading: Boolean(props.loading),
    signal,
    refresh: () => {
      if (!signal.aborted && usable.value) props.control.refresh();
    },
  });
});
const component = computed(() => toRaw(props.control.component));
onBeforeUnmount(() => controller.value.abort());
</script>

<template>
  <component :is="component" :context="context" />
</template>
