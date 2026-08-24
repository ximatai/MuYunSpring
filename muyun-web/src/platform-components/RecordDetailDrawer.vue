<script setup lang="ts">
import { computed, inject, watch } from 'vue';
import { Drawer as ADrawer } from 'ant-design-vue';
import { UiActionButton, UiSidePanel, type UiSidePanelScope } from '@muyun/vue-ui-antdv';
import { sidePanelHostKey } from '../vue-ui-antdv/components/sidePanelHost';
import RecordDetailLayout from './RecordDetailLayout.vue';
import type { DrawerPromotion } from './drawerPromotion';

defineOptions({ name: 'RecordDetailDrawer' });

const props = withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    /** Explicit page container for a locally scoped drawer. Omitted uses the active tab host. */
    container?: HTMLElement | null;
    subtitle?: string;
    width?: number | string;
    scope?: UiSidePanelScope;
    closeOnOutside?: boolean;
    closeTitle?: string;
    promotion?: DrawerPromotion;
  }>(),
  {
    container: undefined,
    subtitle: undefined,
    width: 520,
    scope: 'tab',
    closeOnOutside: false,
    closeTitle: '关闭',
    promotion: undefined,
  },
);

defineSlots<{
  status(): unknown;
  'title-prefix'(): unknown;
  'title-actions'(): unknown;
  'header-actions'(): unknown;
  default(): unknown;
  operation(): unknown;
}>();

const emit = defineEmits<{
  close: [];
  /** A portal drawer must remain mounted until its closing transition releases its DOM nodes. */
  afterClose: [];
}>();

const sidePanelHost = inject(sidePanelHostKey, undefined);
const hasDrawerContainer = computed(
  () => Boolean(props.container) || props.scope === 'viewport' || Boolean(sidePanelHost?.value),
);

function handleAfterVisibleChange(visible: boolean) {
  if (!visible) emit('afterClose');
}

watch(
  () => [props.open, hasDrawerContainer.value] as const,
  ([open, hasContainer]) => {
    if (open && !hasContainer && import.meta.env.DEV) {
      console.error('[RecordDetailDrawer] 打开抽屉前必须传入所属页面的根 DOM 容器。');
    }
  },
  { immediate: true },
);
</script>

<template>
  <UiSidePanel
    v-if="!container && hasDrawerContainer"
    :open="open"
    :width="width"
    :scope="scope"
    :close-on-outside="closeOnOutside"
    @close="emit('close')"
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
        <UiActionButton emphasis="quiet" icon-name="close" :title="closeTitle" @click="emit('close')" />
      </template>
      <slot />
      <template v-if="$slots.operation" #operation>
        <slot name="operation" />
      </template>
    </RecordDetailLayout>
  </UiSidePanel>
  <!-- The host already renders this drawer inside its scoped workspace. Keeping
       the drawer inline avoids moving Vue's slot anchors into a Teleport target. -->
  <ADrawer
    v-else-if="container"
    :open="open"
    placement="right"
    :width="width"
    :get-container="false"
    :mask="closeOnOutside"
    :mask-closable="closeOnOutside"
    :mask-style="{ background: 'transparent' }"
    :keyboard="closeOnOutside"
    :closable="false"
    :header-style="{ display: 'none' }"
    :body-style="{ height: '100%', padding: 0 }"
    :root-style="{ position: 'absolute', inset: 0, zIndex: 6 }"
    @close="emit('close')"
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
        <UiActionButton emphasis="quiet" icon-name="close" :title="closeTitle" @click="emit('close')" />
      </template>
      <slot />
      <template v-if="$slots.operation" #operation><slot name="operation" /></template>
    </RecordDetailLayout>
  </ADrawer>
</template>
