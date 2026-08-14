<script setup lang="ts">
import { computed } from 'vue';
import { usePageLayout } from './pageLayoutContext';

defineOptions({ name: 'ManagementWorkspace' });

const props = withDefaults(
  defineProps<{
    /** Number of explorer columns shown before the detail workspace. */
    explorerCount?: 1 | 2 | 3;
  }>(),
  {
    explorerCount: 1,
  },
);

const workspaceClass = computed(() => `management-workspace--${props.explorerCount}-explorer`);
const pageLayout = usePageLayout();
</script>

<template>
  <section
    class="management-workspace"
    :class="[workspaceClass, { 'management-workspace--constrained': pageLayout === 'workspace' }]"
  >
    <slot />
  </section>
</template>

<style scoped>
.management-workspace {
  --muyun-management-explorer-width: 280px;
  --muyun-management-detail-min-width: 560px;
  --muyun-management-column-gap: 12px;
  --muyun-management-panel-padding-block: 10px;
  --muyun-management-panel-padding-inline: 12px;
  --muyun-management-panel-header-height: 30px;
  --muyun-management-panel-header-gap: 8px;
  --muyun-management-panel-title-font-size: 16px;
  --muyun-management-panel-title-line-height: 22px;
  --muyun-management-panel-content-gap: 8px;

  display: grid;
  align-items: start;
  gap: var(--muyun-management-column-gap);
  min-height: 100%;
}

.management-workspace--constrained {
  height: 100%;
  min-height: 0;
  align-items: stretch;
}

.management-workspace--1-explorer {
  grid-template-columns: var(--muyun-management-explorer-width) minmax(
      var(--muyun-management-detail-min-width),
      1fr
    );
  min-width: calc(
    var(--muyun-management-explorer-width) + var(--muyun-management-detail-min-width) +
      var(--muyun-management-column-gap)
  );
}

.management-workspace--2-explorer {
  grid-template-columns: repeat(2, var(--muyun-management-explorer-width)) minmax(
      var(--muyun-management-detail-min-width),
      1fr
    );
  min-width: calc(
    var(--muyun-management-explorer-width) + var(--muyun-management-explorer-width) +
      var(--muyun-management-detail-min-width) + var(--muyun-management-column-gap) +
      var(--muyun-management-column-gap)
  );
}

.management-workspace--3-explorer {
  grid-template-columns: repeat(3, var(--muyun-management-explorer-width)) minmax(
      var(--muyun-management-detail-min-width),
      1fr
    );
  min-width: calc(
    var(--muyun-management-explorer-width) + var(--muyun-management-explorer-width) +
      var(--muyun-management-explorer-width) + var(--muyun-management-detail-min-width) +
      var(--muyun-management-column-gap) + var(--muyun-management-column-gap) +
      var(--muyun-management-column-gap)
  );
}

@media (max-width: 980px) {
  .management-workspace,
  .management-workspace--1-explorer,
  .management-workspace--2-explorer,
  .management-workspace--3-explorer {
    grid-template-columns: minmax(0, 1fr);
    min-width: 0;
  }
}
</style>
