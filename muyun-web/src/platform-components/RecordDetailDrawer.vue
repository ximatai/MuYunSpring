<script setup lang="ts">
import { computed, inject, watch } from 'vue';
import {
  UiActionButton,
  UiSidePanel,
  mayCloseUiDrawer,
  type UiDrawerCloseGuard,
  type UiDrawerDismissal,
  type UiDrawerDismissalOptions,
  type UiDrawerWidth,
  type UiSidePanelScope,
} from '@muyun/vue-ui-antdv';
import { sidePanelHostKey } from '../vue-ui-antdv/components/sidePanelHost';
import RecordDetailLayout from './RecordDetailLayout.vue';
import type { DrawerPromotion } from './drawerPromotion';

defineOptions({ name: 'RecordDetailDrawer' });

const props = withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    /** `inline` keeps slot anchors in the owning workspace; `portal` uses the standard side-panel host. */
    renderMode?: 'inline' | 'portal';
    subtitle?: string;
    width?: UiDrawerWidth;
    scope?: UiSidePanelScope;
    dismissal?: UiDrawerDismissal;
    /** @deprecated Use `dismissal` and `beforeClose`. */
    closeOnOutside?: boolean;
    beforeClose?: UiDrawerCloseGuard;
    closeTitle?: string;
    promotion?: DrawerPromotion;
  }>(),
  {
    renderMode: 'portal',
    subtitle: undefined,
    width: 'standard',
    scope: 'tab',
    dismissal: undefined,
    closeOnOutside: false,
    beforeClose: undefined,
    closeTitle: '关闭',
    promotion: undefined,
  },
);

defineSlots<{
  status(): unknown;
  'title-prefix'(): unknown;
  'title-actions'(): unknown;
  'header-actions'(): unknown;
  'operation-summary'(): unknown;
  default(): unknown;
  operation(): unknown;
}>();

const emit = defineEmits<{
  close: [];
  /** The drawer transition has finished and its slot content may be released. */
  afterClose: [];
}>();

const sidePanelHost = inject(sidePanelHostKey, undefined);
const hasDrawerContainer = computed(() => props.scope === 'viewport' || Boolean(sidePanelHost?.value));
const dismissalOptions = computed<UiDrawerDismissalOptions>(() => ({
  dismissal: props.dismissal,
  closeOnOutside: props.closeOnOutside,
  beforeClose: props.beforeClose,
}));
async function requestClose(reason: 'close-button' | 'outside') {
  if (await mayCloseUiDrawer(dismissalOptions.value, reason)) {
    emit('close');
  }
}

watch(
  () => [props.open, props.renderMode, hasDrawerContainer.value] as const,
  ([open, renderMode, hasContainer]) => {
    if (open && renderMode === 'portal' && !hasContainer && import.meta.env.DEV) {
      console.error('[RecordDetailDrawer] portal 模式打开抽屉前必须存在活动侧栏宿主。');
    }
  },
  { immediate: true },
);
</script>

<template>
  <UiSidePanel
    v-if="renderMode === 'inline' || hasDrawerContainer"
    :render-mode="renderMode"
    :open="open"
    :width="width"
    :scope="scope"
    :dismissal="dismissal"
    :close-on-outside="closeOnOutside"
    :before-close="beforeClose"
    @close="emit('close')"
    @after-close="emit('afterClose')"
  >
    <RecordDetailLayout surface="drawer" :title="title" :subtitle="subtitle" scrollable-content>
      <template v-if="$slots['title-prefix']" #title-prefix>
        <slot name="title-prefix" />
      </template>
      <template #status>
        <slot name="status" />
      </template>
      <template #title-actions>
        <slot name="title-actions" />
        <UiActionButton
          v-if="promotion"
          emphasis="quiet"
          icon-name="export"
          :title="promotion.title ?? '固定为页签'"
          @click="promotion.promote()"
        />
      </template>
      <template #actions>
        <slot name="header-actions" />
        <UiActionButton
          emphasis="quiet"
          icon-name="close"
          :title="closeTitle"
          @click="requestClose('close-button')"
        />
      </template>
      <slot />
      <template v-if="$slots['operation-summary']" #operation-summary>
        <slot name="operation-summary" />
      </template>
      <template v-if="$slots.operation" #operation>
        <slot name="operation" />
      </template>
    </RecordDetailLayout>
  </UiSidePanel>
</template>
