<script setup lang="ts">
import { computed, inject, type CSSProperties } from 'vue';
import { Drawer as ADrawer } from 'ant-design-vue';
import { sidePanelHostKey, type UiSidePanelScope } from './sidePanelHost';

defineOptions({ name: 'UiSidePanel', inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    open: boolean;
    width?: number | string;
    closeOnOutside?: boolean;
    scope?: UiSidePanelScope;
  }>(),
  {
    width: 520,
    closeOnOutside: false,
    scope: 'tab',
  },
);

const sidePanelHost = inject(sidePanelHostKey, undefined);
const container = computed(() => {
  if (props.scope === 'viewport') {
    return typeof document === 'undefined' ? false : document.body;
  }
  return sidePanelHost?.value ?? false;
});
const rootStyle = computed<CSSProperties>(() =>
  props.scope === 'viewport'
    ? { position: 'fixed', inset: 0, zIndex: 6 }
    : { position: 'absolute', inset: 0, zIndex: 6 },
);

const emit = defineEmits<{
  close: [];
}>();
</script>

<template>
  <ADrawer
    :open="open"
    placement="right"
    :width="width"
    :get-container="container"
    :mask="closeOnOutside"
    :mask-closable="closeOnOutside"
    :mask-style="{ background: 'transparent' }"
    :keyboard="closeOnOutside"
    :closable="false"
    :header-style="{ display: 'none' }"
    :body-style="{ height: '100%', padding: 0 }"
    :root-style="rootStyle"
    :class="$attrs.class"
    :style="$attrs.style"
    @close="emit('close')"
  >
    <slot />
  </ADrawer>
</template>
