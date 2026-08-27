<script setup lang="ts">
import DrawerOperationBar from './DrawerOperationBar.vue';

defineOptions({ name: 'RecordDetailLayout' });

withDefaults(
  defineProps<{
    /** Main business identity; workbench tabs consume only this title. */
    title: string;
    /** Optional contextual description rendered by the platform detail header. */
    subtitle?: string;
    /** Keeps the title and operation regions fixed while detail content scrolls. */
    scrollableContent?: boolean;
    /** Internal composition surface selected by the hosting platform component. */
    surface?: 'workspace' | 'drawer';
  }>(),
  {
    subtitle: undefined,
    scrollableContent: false,
    surface: 'workspace',
  },
);
</script>

<template>
  <main
    class="record-detail-layout"
    :class="[
      `record-detail-layout--${surface}`,
      {
        'record-detail-layout--scrollable': scrollableContent,
        'record-detail-layout--scrollable-with-operation': scrollableContent && Boolean($slots.operation),
      },
    ]"
  >
    <slot name="header">
      <header class="record-detail-layout-header">
        <div class="record-detail-layout-title-group">
          <slot name="title-prefix" />
          <div class="record-detail-layout-title-copy">
            <h2>{{ title }}</h2>
            <p v-if="subtitle">{{ subtitle }}</p>
          </div>
          <slot name="status" />
          <slot name="title-actions" />
        </div>
        <div v-if="$slots.actions" class="record-detail-layout-actions">
          <slot name="actions" />
        </div>
      </header>
    </slot>
    <div v-if="scrollableContent" class="record-detail-layout-content">
      <div v-if="$slots['content-top']" class="record-detail-layout-content-extension">
        <slot name="content-top" />
      </div>
      <slot />
      <div
        v-if="$slots['content-bottom']"
        class="record-detail-layout-content-extension record-detail-layout-content-extension--bottom"
      >
        <slot name="content-bottom" />
      </div>
    </div>
    <template v-else>
      <div v-if="$slots['content-top']" class="record-detail-layout-content-extension">
        <slot name="content-top" />
      </div>
      <slot />
      <div
        v-if="$slots['content-bottom']"
        class="record-detail-layout-content-extension record-detail-layout-content-extension--bottom"
      >
        <slot name="content-bottom" />
      </div>
    </template>
    <div v-if="$slots.operation" class="record-detail-layout-operation">
      <DrawerOperationBar>
        <template v-if="$slots['operation-summary']" #summary>
          <slot name="operation-summary" />
        </template>
        <slot name="operation" />
      </DrawerOperationBar>
    </div>
  </main>
</template>

<style scoped>
.record-detail-layout {
  --muyun-detail-section-inner-gap: 8px;
  --muyun-detail-section-block-gap: 16px;
  --muyun-content-section-heading-color: var(--muyun-text);
  --muyun-content-section-heading-font-size: 14px;
  --muyun-content-section-heading-font-weight: 600;
  --muyun-content-section-heading-line-height: 20px;
  --muyun-content-section-subtitle-color: var(--muyun-text-muted);
  --muyun-content-section-subtitle-font-size: 12px;
  --muyun-content-section-subtitle-line-height: 18px;
  --muyun-detail-relation-header-font-size: 12px;
  --muyun-detail-relation-body-font-size: 12px;
  --muyun-detail-relation-header-row-height: 34px;
  --muyun-detail-relation-body-row-height: 30px;

  display: grid;
  align-content: start;
  gap: 12px;
  min-width: 0;
  min-height: 0;
  padding: 14px;
  background: var(--muyun-surface);
}

.record-detail-layout--workspace {
  gap: var(--muyun-management-panel-content-gap, 8px);
  padding: var(--muyun-management-panel-padding-block, 10px)
    var(--muyun-management-panel-padding-inline, 12px);
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
}

.record-detail-layout--drawer {
  height: 100%;
}

/* Drawers are a single surface: the divider establishes hierarchy without a nested card. */
.record-detail-layout--drawer .record-detail-layout-header {
  margin: -14px -14px 0;
  padding: 14px 14px 12px;
  border-bottom: 1px solid var(--muyun-border);
}

.record-detail-layout--scrollable {
  grid-template-rows: auto minmax(0, 1fr);
  overflow: hidden;
}

.record-detail-layout--scrollable-with-operation {
  grid-template-rows: auto minmax(0, 1fr) auto;
}

.record-detail-layout-content {
  min-height: 0;
  overflow: auto;
}

.record-detail-layout-content-extension--bottom {
  margin-top: var(--muyun-detail-content-extension-gap, 16px);
}

.record-detail-layout-content-extension:not(.record-detail-layout-content-extension--bottom) {
  margin-bottom: var(--muyun-detail-content-extension-gap, 16px);
}

.record-detail-layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
  min-height: 32px;
}

.record-detail-layout-title-group {
  display: inline-flex;
  flex: 1 1 auto;
  align-items: center;
  gap: 8px;
  min-width: 0;
  min-height: 32px;
}

.record-detail-layout-title-copy {
  min-width: 0;
}

.record-detail-layout-title-copy h2 {
  margin: 0;
  overflow: hidden;
  color: var(--muyun-text);
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-detail-layout-title-copy p {
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--muyun-text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-detail-layout-actions {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  min-width: 0;
  min-height: 32px;
  max-width: 100%;
}

.record-detail-layout-actions :deep(.record-action-bar) {
  justify-content: flex-end;
}

.record-detail-layout-operation {
  margin: 0 -14px -14px;
  padding: 12px 14px;
  border-top: 1px solid var(--muyun-border);
  background: var(--muyun-surface);
}

.record-detail-layout--workspace .record-detail-layout-operation {
  margin: 0 calc(-1 * var(--muyun-management-panel-padding-inline, 12px))
    calc(-1 * var(--muyun-management-panel-padding-block, 10px));
  padding-inline: var(--muyun-management-panel-padding-inline, 12px);
}

@media (max-width: 720px) {
  .record-detail-layout-header {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
  }

  .record-detail-layout-actions {
    width: 100%;
  }
}
</style>
