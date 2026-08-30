<script setup lang="ts">
import { computed, provide, reactive } from 'vue';
import {
  collapsedExplorerTabHeight,
  MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT,
  MANAGEMENT_WORKSPACE_LAYOUT,
} from './managementWorkspaceLayout';
import {
  MANAGEMENT_WORKSPACE_CONTEXT,
  type ManagementWorkspaceExplorerRegistration,
} from './managementWorkspaceContext';
import { usePageLayout } from './pageLayoutContext';

defineOptions({ name: 'ManagementWorkspace' });

const props = withDefaults(
  defineProps<{
    /**
     * Stable workspace geometry for specialized platform workbenches.
     * `composer` is a positional preset: exactly two explorer children must
     * precede the primary canvas child.
     */
    layout?: 'default' | 'composer';
    /** Number of explorer columns shown before the detail workspace. */
    explorerCount?: number;
    /** Whether the final workspace is a list plus an independently sized detail surface. */
    detailSurface?: boolean;
    /** Whether the final workspace is a list without a persistent detail surface. */
    listSurface?: boolean;
  }>(),
  {
    layout: 'default',
    explorerCount: 1,
    detailSurface: false,
    listSurface: false,
  },
);

const explorerCount = computed(() => Math.max(0, Math.trunc(props.explorerCount)));
type ExplorerState = ManagementWorkspaceExplorerRegistration & { collapsed: boolean; order: number };

const explorers = reactive<Record<string, ExplorerState>>({});
let nextExplorerOrder = 0;
const collapsedExplorers = computed(() =>
  Object.values(explorers)
    .filter((explorer) => explorer.collapsed)
    .sort((left, right) => left.order - right.order),
);
const collapsedExplorerCount = computed(() => collapsedExplorers.value.length);
const hasCollapsedExplorers = computed(() => collapsedExplorerCount.value > 0);
const expandedExplorerCount = computed(() => Math.max(0, explorerCount.value - collapsedExplorerCount.value));
const hasExplorer = computed(() => expandedExplorerCount.value > 0);
const effectiveExplorerCount = computed(() => expandedExplorerCount.value);

function registerExplorer(registration: ManagementWorkspaceExplorerRegistration) {
  const current = explorers[registration.id];
  if (current) {
    current.title = registration.title;
    current.hasSelection = registration.hasSelection;
    return;
  }
  explorers[registration.id] = { ...registration, collapsed: false, order: nextExplorerOrder++ };
}

function unregisterExplorer(id: string) {
  delete explorers[id];
}

function isExplorerCollapsed(id: string) {
  return explorers[id]?.collapsed === true;
}

function toggleExplorer(id: string) {
  const explorer = explorers[id];
  if (explorer) explorer.collapsed = !explorer.collapsed;
}

function collapsedExplorerOffset(id: string) {
  let offset = 0;
  for (const explorer of collapsedExplorers.value) {
    if (explorer.id === id) return offset;
    offset += collapsedExplorerTabHeight(explorer.title) + MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT.tabStackGap;
  }
  return 0;
}

provide(MANAGEMENT_WORKSPACE_CONTEXT, {
  registerExplorer,
  unregisterExplorer,
  isExplorerCollapsed,
  toggleExplorer,
  collapsedExplorerOffset,
  hasCollapsedExplorers,
});

const pageLayout = usePageLayout();
</script>

<template>
  <section
    class="management-workspace"
    :class="{
      'management-workspace--constrained': pageLayout === 'workspace',
      'management-workspace--composer': layout === 'composer',
      'management-workspace--detail-surface': detailSurface,
      'management-workspace--list-surface': listSurface,
      'management-workspace--without-explorer': !hasExplorer,
      'management-workspace--with-collapsed-explorer': hasCollapsedExplorers,
    }"
  >
    <div
      class="management-workspace__grid"
      :style="{
        '--muyun-management-explorer-count': String(effectiveExplorerCount),
        '--muyun-management-collapsed-rail-width': hasCollapsedExplorers
          ? `${MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT.railWidth}px`
          : '0px',
        '--muyun-management-collapsed-rail-offset': hasCollapsedExplorers
          ? `calc(${MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT.railWidth}px + ${MANAGEMENT_WORKSPACE_LAYOUT.columnGap}px)`
          : '0px',
        '--muyun-management-collapsed-tab-padding-block-start': `${MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT.tabPaddingBlockStart}px`,
        '--muyun-management-collapsed-tab-padding-block-end': `${MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT.tabPaddingBlockEnd}px`,
        '--muyun-management-collapsed-tab-title-font-size': `${MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT.titleFontSize}px`,
        '--muyun-management-collapsed-tab-title-line-height': `${MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT.titleLineHeight}px`,
        '--muyun-management-collapsed-tab-icon-size': `${MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT.iconSize}px`,
        '--muyun-management-collapsed-tab-icon-hit-area': `${MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT.iconHitArea}px`,
        '--muyun-management-collapsed-tab-content-gap': `${MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT.tabContentGap}px`,
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
  position: relative;
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
  box-sizing: border-box;
  padding-left: var(--muyun-management-collapsed-rail-offset, 0px);
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

/* Composer is deliberately positional: explorer, explorer, then canvas. It is
   not a generic three-column layout. Keep the canvas flexible: it is the
   primary editing surface and must never be forced outside a workbench. */
.management-workspace--composer .management-workspace__grid {
  grid-template-columns: minmax(180px, 0.8fr) minmax(220px, 1fr) minmax(0, 2fr);
}

.management-workspace--composer .management-workspace__grid > :nth-child(3) {
  min-width: 0;
}

@media (max-width: 1180px) {
  .management-workspace--composer .management-workspace__grid {
    grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
    grid-template-rows: minmax(160px, 0.75fr) minmax(0, 1.25fr);
    gap: 8px;
  }

  .management-workspace--composer .management-workspace__grid > :nth-child(3) {
    grid-column: 1 / -1;
  }
}

@media (max-width: 760px) {
  .management-workspace--composer .management-workspace__grid {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: minmax(120px, 0.45fr) minmax(120px, 0.55fr) minmax(0, 1.2fr);
  }

  .management-workspace--composer .management-workspace__grid > :nth-child(3) {
    grid-column: auto;
  }
}

/* Keep an explorer and its list usable inside a narrow workbench pane.  The
   desktop minimum widths are intentionally generous, but must not force the
   host to horizontally clip its primary navigation at tablet widths. */
@media (max-width: 980px) {
  .management-workspace--list-surface .management-workspace__grid {
    grid-template-columns:
      repeat(var(--muyun-management-explorer-count), minmax(0, 1fr))
      minmax(0, 1.5fr);
    gap: 8px;
  }
}

@media (max-width: 760px) {
  .management-workspace--list-surface .management-workspace__grid {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(200px, 0.7fr) minmax(320px, 1fr);
  }
}
</style>
