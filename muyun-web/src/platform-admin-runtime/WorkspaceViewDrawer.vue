<script setup lang="ts">
import { computed } from 'vue';
import { RecordDetailDrawer, type DrawerPromotion } from '@muyun/platform-components';
import type { WorkspaceDrawerProfile } from './workspaceViewContract';

defineOptions({ name: 'WorkspaceViewDrawer' });

const props = withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    container: HTMLElement | null;
    subtitle?: string;
    profile?: WorkspaceDrawerProfile;
    promotion?: DrawerPromotion;
  }>(),
  {
    subtitle: undefined,
    profile: 'detail',
    promotion: undefined,
  },
);

const emit = defineEmits<{ close: [] }>();

// CSS min() keeps wide work drawers inside the workbench on narrow screens;
// standard details retain the compact, form-oriented capacity.
const width = computed(() => (props.profile === 'wide-work' ? 'min(600px, 100vw)' : 520));
</script>

<template>
  <RecordDetailDrawer
    :open="open"
    :title="title"
    :render-mode="container ? 'inline' : 'portal'"
    :subtitle="subtitle"
    :width="width"
    :promotion="promotion"
    @close="emit('close')"
  >
    <template v-if="$slots.operation" #operation>
      <slot name="operation" />
    </template>
    <slot />
  </RecordDetailDrawer>
</template>
