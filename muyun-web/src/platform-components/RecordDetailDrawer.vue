<script setup lang="ts">
import { watch } from 'vue';
import { Drawer as ADrawer } from 'ant-design-vue';
import { UiActionButton } from '@muyun/vue-ui-antdv';
import RecordDetailLayout from './RecordDetailLayout.vue';
import type { DrawerPromotion } from './drawerPromotion';

defineOptions({ name: 'RecordDetailDrawer' });

const props = withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    /** The owning page root. Drawers must never infer a workbench-level container. */
    container: HTMLElement | null;
    subtitle?: string;
    width?: number | string;
    closeOnOutside?: boolean;
    closeTitle?: string;
    promotion?: DrawerPromotion;
  }>(),
  {
    subtitle: undefined,
    width: 520,
    closeOnOutside: false,
    closeTitle: '关闭',
    promotion: undefined,
  },
);

defineSlots<{
  status(): unknown;
  default(): unknown;
  operation(): unknown;
}>();

const emit = defineEmits<{
  close: [];
}>();

watch(
  () => [props.open, props.container] as const,
  ([open, container]) => {
    if (open && !container && import.meta.env.DEV) {
      console.error('[RecordDetailDrawer] 打开抽屉前必须传入所属页面的根 DOM 容器。');
    }
  },
  { immediate: true },
);
</script>

<template>
  <ADrawer
    v-if="container"
    :open="open"
    placement="right"
    :width="width"
    :get-container="container"
    :close-on-outside="closeOnOutside"
    :mask="closeOnOutside"
    :mask-closable="closeOnOutside"
    :mask-style="{ background: 'transparent' }"
    :keyboard="closeOnOutside"
    :closable="false"
    :header-style="{ display: 'none' }"
    :body-style="{ height: '100%', padding: 0 }"
    :root-style="{ position: 'absolute', inset: 0, zIndex: 6 }"
    @close="emit('close')"
  >
    <RecordDetailLayout surface="drawer" :title="title" :subtitle="subtitle" scrollable-content>
      <template #status>
        <slot name="status" />
      </template>
      <template #title-actions>
        <UiActionButton
          v-if="promotion"
          emphasis="quiet"
          icon-name="export"
          :title="promotion.title ?? '固定为页签'"
          @click="promotion.promote()"
        />
      </template>
      <template #actions>
        <UiActionButton emphasis="quiet" icon-name="close" :title="closeTitle" @click="emit('close')" />
      </template>
      <slot />
      <template v-if="$slots.operation" #operation>
        <slot name="operation" />
      </template>
    </RecordDetailLayout>
  </ADrawer>
</template>
