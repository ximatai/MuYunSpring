<script setup lang="ts">
import { computed, inject, ref, type CSSProperties } from 'vue';
import { Drawer as ADrawer } from 'ant-design-vue';
import { resolveUiDrawerWidth, type UiDrawerWidth } from '../drawerWidth';
import {
  allowsUiDrawerOutsideDismissal,
  mayCloseUiDrawer,
  type UiDrawerCloseGuard,
  type UiDrawerDismissal,
  type UiDrawerDismissalOptions,
} from '../drawerDismissal';
import { useUiBlockingOverlayVisibility } from '../blockingOverlays';
import { sidePanelHostKey, type UiSidePanelScope } from './sidePanelHost';

defineOptions({ name: 'UiSidePanel', inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    open: boolean;
    /** Inline surfaces stay in their owning workspace. */
    renderMode?: 'inline' | 'portal';
    width?: UiDrawerWidth;
    dismissal?: UiDrawerDismissal;
    /** @deprecated Use `dismissal` and `beforeClose`. */
    closeOnOutside?: boolean;
    beforeClose?: UiDrawerCloseGuard;
    scope?: UiSidePanelScope;
  }>(),
  {
    width: 'standard',
    renderMode: 'portal',
    dismissal: undefined,
    closeOnOutside: false,
    beforeClose: undefined,
    scope: 'tab',
  },
);

const sidePanelHost = inject(sidePanelHostKey, undefined);
const container = computed(() => {
  if (props.renderMode === 'inline') return false;
  if (props.scope === 'viewport') {
    return typeof document === 'undefined' ? false : document.body;
  }
  return sidePanelHost?.value ?? false;
});
const rootStyle = computed<CSSProperties>(() =>
  props.renderMode !== 'inline' && props.scope === 'viewport'
    ? { position: 'fixed', inset: 0, zIndex: 6 }
    : { position: 'absolute', inset: 0, zIndex: 6 },
);
const resolvedWidth = computed(() => {
  const width = resolveUiDrawerWidth(props.width);
  return props.renderMode === 'inline' ? `min(${width}px, calc(100% - 32px))` : width;
});
const overlayContent = ref<HTMLElement>();
const updateOverlayVisibility = useUiBlockingOverlayVisibility(() => props.open, overlayContent);
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
  updateOverlayVisibility(visible);
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
    <div ref="overlayContent" style="height: 100%"><slot /></div>
  </ADrawer>
</template>
