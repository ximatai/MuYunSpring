<script setup lang="ts">
import { computed } from 'vue';
import { RecordDetailDrawer, type DrawerPromotion } from '@muyun/platform-components';
import type { UiDrawerCloseGuard, UiDrawerDismissal } from '@muyun/vue-ui-antdv';
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
    dismissal?: UiDrawerDismissal;
    beforeClose?: UiDrawerCloseGuard;
  }>(),
  {
    subtitle: undefined,
    profile: 'detail',
    promotion: undefined,
    dismissal: undefined,
    beforeClose: undefined,
  },
);

const emit = defineEmits<{ close: [] }>();

const width = computed(() => (props.profile === 'wide-work' ? 'wide' : 'standard'));
</script>

<template>
  <RecordDetailDrawer
    :open="open"
    :title="title"
    :render-mode="container ? 'inline' : 'portal'"
    :subtitle="subtitle"
    :width="width"
    :promotion="promotion"
    :dismissal="dismissal"
    :before-close="beforeClose"
    @close="emit('close')"
  >
    <template v-if="$slots.operation" #operation>
      <slot name="operation" />
    </template>
    <slot />
  </RecordDetailDrawer>
</template>
