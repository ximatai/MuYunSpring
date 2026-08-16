<script setup lang="ts">
import { UiActionButton, UiSidePanel, type UiSidePanelScope } from '@muyun/vue-ui-antdv';
import RecordDetailLayout from './RecordDetailLayout.vue';
import type { DrawerPromotion } from './drawerPromotion';

defineOptions({ name: 'RecordDetailDrawer' });

withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    subtitle?: string;
    width?: number | string;
    scope?: UiSidePanelScope;
    closeOnOutside?: boolean;
    closeTitle?: string;
    promotion?: DrawerPromotion;
  }>(),
  {
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
}>();
</script>

<template>
  <UiSidePanel
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
</template>
