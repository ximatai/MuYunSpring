<script setup lang="ts">
import { computed } from 'vue';
import { MANAGEMENT_WORKSPACE_LAYOUT } from './managementWorkspaceLayout';
import { usePageLayout } from './pageLayoutContext';

defineOptions({ name: 'ManagementWorkspace' });

const props = withDefaults(
  defineProps<{
    /** Number of explorer columns shown before the detail workspace. */
    explorerCount?: number;
    /** Whether the final workspace is a list plus an independently sized detail surface. */
    detailSurface?: boolean;
    /** Whether the final workspace is a list without a persistent detail surface. */
    listSurface?: boolean;
  }>(),
  {
    explorerCount: 1,
    detailSurface: false,
    listSurface: false,
  },
);

const explorerCount = computed(() => Math.max(0, Math.trunc(props.explorerCount)));
const hasExplorer = computed(() => explorerCount.value > 0);
const pageLayout = usePageLayout();
</script>

<template>
  <section
    class="management-workspace"
    :class="{
      'management-workspace--constrained': pageLayout === 'workspace',
      'management-workspace--detail-surface': detailSurface,
      'management-workspace--list-surface': listSurface,
      'management-workspace--without-explorer': !hasExplorer,
    }"
  >
    <div
      class="management-workspace__grid"
      :style="{
        '--muyun-management-explorer-count': String(explorerCount),
        '--muyun-management-explorer-width': `${MANAGEMENT_WORKSPACE_LAYOUT.explorerWidth}px`,
        '--muyun-management-list-min-width': `${MANAGEMENT_WORKSPACE_LAYOUT.listMinWidth}px`,
        '--muyun-management-detail-min-width': `${MANAGEMENT_WORKSPACE_LAYOUT.detailMinWidth}px`,
        '--muyun-management-column-gap': `${MANAGEMENT_WORKSPACE_LAYOUT.columnGap}px`,
      }"
    >
      <slot />
    </div>
  </section>
</template>

<style scoped>
.management-workspace {
  --muyun-management-explorer-width: 280px;
  --muyun-management-list-min-width: 720px;
  --muyun-management-detail-min-width: 560px;
  --muyun-management-detail-preferred-width: 440px;
  --muyun-management-column-gap: 12px;
  --muyun-management-panel-padding-block: 10px;
  --muyun-management-panel-padding-inline: 14px;
  --muyun-management-panel-header-height: 30px;
  --muyun-management-panel-header-gap: 8px;
  --muyun-management-panel-title-font-size: 16px;
  --muyun-management-panel-title-line-height: 22px;
  --muyun-management-panel-content-gap: 8px;

  min-width: 0;
  min-height: 100%;
  overflow-x: auto;
}

.management-workspace--constrained {
  height: 100%;
  min-height: 0;
  overflow-y: hidden;
}

.management-workspace__grid {
  display: grid;
  grid-template-columns:
    repeat(var(--muyun-management-explorer-count), var(--muyun-management-explorer-width))
    minmax(var(--muyun-management-detail-min-width), 1fr);
  align-items: start;
  gap: var(--muyun-management-column-gap);
  /* Keep the workspace constrained by its host; wide tables scroll inside their panel instead of
     contributing an unbounded max-content width to the outer grid. */
  width: 100%;
  min-width: 100%;
  min-height: 100%;
}

.management-workspace--constrained .management-workspace__grid {
  height: 100%;
  min-height: 0;
  align-items: stretch;
}

.management-workspace--detail-surface .management-workspace__grid {
  grid-template-columns:
    repeat(var(--muyun-management-explorer-count), var(--muyun-management-explorer-width))
    minmax(var(--muyun-management-list-min-width), 1fr)
    minmax(var(--muyun-management-detail-min-width), var(--muyun-management-detail-preferred-width));
}

.management-workspace--list-surface .management-workspace__grid {
  grid-template-columns:
    repeat(var(--muyun-management-explorer-count), var(--muyun-management-explorer-width))
    minmax(var(--muyun-management-list-min-width), 1fr);
}

.management-workspace--without-explorer .management-workspace__grid {
  grid-template-columns: minmax(var(--muyun-management-detail-min-width), 1fr);
}

.management-workspace--without-explorer.management-workspace--detail-surface .management-workspace__grid {
  grid-template-columns:
    minmax(var(--muyun-management-list-min-width), 1fr)
    minmax(var(--muyun-management-detail-min-width), var(--muyun-management-detail-preferred-width));
}
</style>
