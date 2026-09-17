<script setup lang="ts">
import { computed, inject, watch } from 'vue';
import { Drawer as ADrawer } from 'ant-design-vue';
import {
  resolveUiDrawerWidth,
  UiActionButton,
  UiSidePanel,
  allowsUiDrawerOutsideDismissal,
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
const inlineWidth = computed(() => {
  const resolvedWidth = resolveUiDrawerWidth(props.width);
  const requestedWidth = typeof resolvedWidth === 'number' ? `${resolvedWidth}px` : resolvedWidth;
  // Inline drawers share the workspace's containing block. Reserve a visible
  // edge so a wide business surface never starts outside that block.
  return `min(${requestedWidth}, calc(100% - 32px))`;
});
const dismissalOptions = computed<UiDrawerDismissalOptions>(() => ({
  dismissal: props.dismissal,
  closeOnOutside: props.closeOnOutside,
  beforeClose: props.beforeClose,
}));
const outsideDismissalAllowed = computed(() => allowsUiDrawerOutsideDismissal(dismissalOptions.value));

function handleAfterVisibleChange(visible: boolean) {
  if (!visible) emit('afterClose');
}

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
    v-if="renderMode === 'portal' && hasDrawerContainer"
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
  <!-- The host already renders this drawer inside its scoped workspace. Keeping
       the drawer inline avoids moving Vue's slot anchors into a Teleport target. -->
  <ADrawer
    v-else-if="renderMode === 'inline'"
    :open="open"
    placement="right"
    :width="inlineWidth"
    :get-container="false"
    :mask="outsideDismissalAllowed"
    :mask-closable="outsideDismissalAllowed"
    :mask-style="{ background: 'transparent' }"
    :keyboard="outsideDismissalAllowed"
    :closable="false"
    :header-style="{ display: 'none' }"
    :body-style="{ height: '100%', padding: 0 }"
    :root-style="{ position: 'absolute', inset: 0, zIndex: 6 }"
    @close="requestClose('outside')"
    @after-open-change="handleAfterVisibleChange"
  >
    <RecordDetailLayout surface="drawer" :title="title" :subtitle="subtitle" scrollable-content>
      <template v-if="$slots['title-prefix']" #title-prefix><slot name="title-prefix" /></template>
      <template #status><slot name="status" /></template>
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
      <template v-if="$slots.operation" #operation><slot name="operation" /></template>
    </RecordDetailLayout>
  </ADrawer>
</template>
