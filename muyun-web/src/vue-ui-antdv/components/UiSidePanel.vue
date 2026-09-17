<script setup lang="ts">
import { computed, inject, type CSSProperties } from 'vue';
import { Drawer as ADrawer } from 'ant-design-vue';
import { resolveUiDrawerWidth, type UiDrawerWidth } from '../drawerWidth';
import {
  allowsUiDrawerOutsideDismissal,
  mayCloseUiDrawer,
  type UiDrawerCloseGuard,
  type UiDrawerDismissal,
  type UiDrawerDismissalOptions,
} from '../drawerDismissal';
import { sidePanelHostKey, type UiSidePanelScope } from './sidePanelHost';

defineOptions({ name: 'UiSidePanel', inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    open: boolean;
    width?: UiDrawerWidth;
    dismissal?: UiDrawerDismissal;
    /** @deprecated Use `dismissal` and `beforeClose`. */
    closeOnOutside?: boolean;
    beforeClose?: UiDrawerCloseGuard;
    scope?: UiSidePanelScope;
  }>(),
  {
    width: 'standard',
    dismissal: undefined,
    closeOnOutside: false,
    beforeClose: undefined,
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
const resolvedWidth = computed(() => resolveUiDrawerWidth(props.width));
const dismissalOptions = computed<UiDrawerDismissalOptions>(() => ({
  dismissal: props.dismissal,
  closeOnOutside: props.closeOnOutside,
  beforeClose: props.beforeClose,
}));
const outsideDismissalAllowed = computed(() => allowsUiDrawerOutsideDismissal(dismissalOptions.value));

const emit = defineEmits<{
  close: [];
  /** The panel transition has finished and its slot content may be released. */
  afterClose: [];
}>();

function handleAfterVisibleChange(visible: boolean) {
  if (!visible) emit('afterClose');
}

async function requestOutsideClose() {
  if (await mayCloseUiDrawer(dismissalOptions.value, 'outside')) {
    emit('close');
  }
}
</script>

<template>
  <ADrawer
    :open="open"
    placement="right"
    :width="resolvedWidth"
    :get-container="container"
    :mask="outsideDismissalAllowed"
    :mask-closable="outsideDismissalAllowed"
    :mask-style="{ background: 'transparent' }"
    :keyboard="outsideDismissalAllowed"
    :closable="false"
    :header-style="{ display: 'none' }"
    :body-style="{ height: '100%', padding: 0 }"
    :root-style="rootStyle"
    :class="$attrs.class"
    :style="$attrs.style"
    @close="requestOutsideClose"
    @after-open-change="handleAfterVisibleChange"
  >
    <slot />
  </ADrawer>
</template>
