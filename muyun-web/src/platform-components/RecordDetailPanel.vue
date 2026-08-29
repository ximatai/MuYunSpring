<script setup lang="ts">
import RecordDetailLayout from './RecordDetailLayout.vue';
import ManagementPanelHeader from './ManagementPanelHeader.vue';
import { usePageLayout } from './pageLayoutContext';

defineOptions({ name: 'RecordDetailPanel', inheritAttrs: false });

withDefaults(
  defineProps<{
    title: string;
    subtitle?: string;
    showHeader?: boolean;
  }>(),
  { subtitle: undefined, showHeader: true },
);
const pageLayout = usePageLayout();
</script>

<template>
  <section v-bind="$attrs" class="record-detail-panel-region">
    <div v-if="$slots['outside-top']" class="record-detail-panel-outside record-detail-panel-outside--top">
      <slot name="outside-top" />
    </div>
    <RecordDetailLayout
      surface="workspace"
      :title="title"
      :subtitle="subtitle"
      :scrollable-content="pageLayout === 'workspace'"
      :show-header="showHeader"
    >
      <template v-if="showHeader" #header>
        <ManagementPanelHeader :title="title" :subtitle="subtitle">
          <template v-if="$slots['title-prefix']" #title-prefix>
            <slot name="title-prefix" />
          </template>
          <template v-if="$slots.status" #status>
            <slot name="status" />
          </template>
          <template v-if="$slots.actions" #actions>
            <slot name="actions" />
          </template>
        </ManagementPanelHeader>
      </template>
      <template v-if="$slots['content-top']" #content-top>
        <slot name="content-top" />
      </template>
      <slot />
      <template v-if="$slots['content-bottom']" #content-bottom>
        <slot name="content-bottom" />
      </template>
      <template v-if="$slots.operation" #operation>
        <slot name="operation" />
      </template>
    </RecordDetailLayout>
    <div
      v-if="$slots['outside-bottom']"
      class="record-detail-panel-outside record-detail-panel-outside--bottom"
    >
      <slot name="outside-bottom" />
    </div>
  </section>
</template>

<style scoped>
.record-detail-panel-region {
  display: grid;
  grid-template-areas:
    'top'
    'detail'
    'bottom';
  grid-template-rows: auto minmax(0, 1fr) auto;
  /* Outside slots are optional. A grid `gap` would otherwise reserve an
     invisible top row and make the detail card start lower than its explorer. */
  gap: 0;
  height: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.record-detail-panel-outside {
  min-height: 0;
  max-height: min(280px, 40dvh);
  overflow: auto;
}

.record-detail-panel-outside--top {
  grid-area: top;
  margin-bottom: 12px;
}

.record-detail-panel-outside--bottom {
  grid-area: bottom;
  margin-top: 12px;
}

.record-detail-panel-region > :deep(.record-detail-layout) {
  grid-area: detail;
}
</style>
