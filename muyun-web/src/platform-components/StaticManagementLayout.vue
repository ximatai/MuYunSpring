<script setup lang="ts">
import RecordDetailPanel from './RecordDetailPanel.vue';
import RecordExplorerPanel from './RecordExplorerPanel.vue';
import ManagementExplorerColumn from './ManagementExplorerColumn.vue';
import ManagementWorkspace from './ManagementWorkspace.vue';

defineOptions({ name: 'StaticManagementLayout' });

withDefaults(
  defineProps<{
    explorerTitle: string;
    refreshTitle: string;
    mode: 'view' | 'edit' | 'create';
    detailTitle: string;
    mutedMessage?: string;
    explorerSearchKeyword?: string;
    explorerSearchPlaceholder?: string;
    explorerSearchable?: boolean;
    navigatorCount?: number;
  }>(),
  {
    mutedMessage: undefined,
    explorerSearchKeyword: '',
    explorerSearchPlaceholder: '搜索名称、编码或 ID',
    explorerSearchable: true,
    navigatorCount: 0,
  },
);
const emit = defineEmits<{
  refresh: [];
  'update:explorerSearchKeyword': [keyword: string];
}>();
</script>

<template>
  <ManagementWorkspace class="static-management-page" :explorer-count="navigatorCount + 1">
    <ManagementExplorerColumn v-for="index in navigatorCount" :key="index">
      <slot name="navigator" :index="index - 1" />
    </ManagementExplorerColumn>
    <ManagementExplorerColumn>
      <RecordExplorerPanel
        class="static-management-sidebar"
        :title="explorerTitle"
        :refresh-title="refreshTitle"
        :search-keyword="explorerSearchKeyword"
        :search-placeholder="explorerSearchPlaceholder"
        :searchable="explorerSearchable"
        @update:search-keyword="emit('update:explorerSearchKeyword', $event)"
        @refresh="emit('refresh')"
      >
        <template #actions>
          <slot name="explorer-actions" />
        </template>
        <slot name="explorer" />
        <template v-if="$slots['explorer-footer']" #footer>
          <slot name="explorer-footer" />
        </template>
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <RecordDetailPanel class="static-management-card" :title="detailTitle">
      <template #status>
        <slot name="detail-status" />
      </template>
      <template #actions>
        <slot name="detail-actions" />
      </template>

      <div v-if="mutedMessage" class="message muted">{{ mutedMessage }}</div>

      <slot />
    </RecordDetailPanel>
  </ManagementWorkspace>
</template>

<style scoped>
.static-management-page {
  --muyun-management-explorer-width: 280px;
  --muyun-management-detail-min-width: 560px;
}

.static-management-sidebar,
.static-management-card {
  min-width: 0;
}

.static-management-sidebar {
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  background: var(--muyun-surface);
  min-height: 0;
}

.static-management-sidebar :deep(.record-explorer-panel-actions) {
  display: flex;
  align-items: center;
}

.static-management-sidebar :deep(.record-panel-create-button) {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 999px;
}

.message {
  padding: 9px 10px;
  border-radius: 6px;
  font-size: 13px;
}

.message.muted {
  border: 1px solid var(--muyun-border);
  background: var(--muyun-hover-subtle);
  color: var(--muyun-text-muted);
}

:deep(.static-record-form) {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 14px;
}

:deep(.static-record-form label) {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-body);
  font-size: 13px;
}

@media (max-width: 900px) {
  :deep(.static-record-form) {
    grid-template-columns: 1fr;
  }
}
</style>
