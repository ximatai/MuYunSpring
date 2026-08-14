<script setup lang="ts">
import RecordDetailLayout from './RecordDetailLayout.vue';
import ManagementPanelHeader from './ManagementPanelHeader.vue';
import { usePageLayout } from './pageLayoutContext';

defineOptions({ name: 'RecordDetailPanel' });

withDefaults(
  defineProps<{
    title: string;
    subtitle?: string;
  }>(),
  { subtitle: undefined },
);
const pageLayout = usePageLayout();
</script>

<template>
  <RecordDetailLayout
    surface="workspace"
    :title="title"
    :subtitle="subtitle"
    :scrollable-content="pageLayout === 'workspace'"
  >
    <template #header>
      <ManagementPanelHeader :title="title" :subtitle="subtitle">
        <template v-if="$slots.status" #status>
          <slot name="status" />
        </template>
        <template v-if="$slots.actions" #actions>
          <slot name="actions" />
        </template>
      </ManagementPanelHeader>
    </template>
    <slot />
    <template v-if="$slots.operation" #operation>
      <slot name="operation" />
    </template>
  </RecordDetailLayout>
</template>
