<script setup lang="ts">
import { RecordExplorerPanel } from '@muyun/platform-components';
import { UiEmpty, UiSpin, UiSwitch, UiTree, type UiTreeNode } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'MetadataSourceTree', inheritAttrs: false });
withDefaults(
  defineProps<{
    nodes: UiTreeNode[];
    title?: string;
    embedded?: boolean;
    searchKeyword?: string;
    showSystemFields?: boolean;
    searchable?: boolean;
    refreshDisabled?: boolean;
    loading?: boolean;
    unavailable?: boolean;
    unavailableDescription?: string;
  }>(),
  {
    title: '可用字段',
    embedded: false,
    searchKeyword: '',
    showSystemFields: false,
    searchable: true,
    refreshDisabled: false,
    loading: false,
    unavailable: false,
    unavailableDescription: '暂无可用元数据',
  },
);
const emit = defineEmits<{
  'update:searchKeyword': [value: string];
  'update:showSystemFields': [value: boolean];
  refresh: [];
}>();
</script>

<template>
  <RecordExplorerPanel
    :title="title"
    :embedded="embedded"
    :search-keyword="searchKeyword"
    search-placeholder="搜索字段"
    :searchable="searchable"
    :refresh-disabled="refreshDisabled"
    @update:search-keyword="emit('update:searchKeyword', $event)"
    @refresh="emit('refresh')"
  >
    <template v-if="searchable" #utility-actions>
      <label class="metadata-source-tree__system-fields">
        <span>系统字段</span>
        <UiSwitch
          :checked="showSystemFields"
          size="small"
          :title="showSystemFields ? '隐藏系统字段' : '显示系统字段'"
          :aria-label="showSystemFields ? '隐藏系统字段' : '显示系统字段'"
          @update:checked="emit('update:showSystemFields', $event)"
        />
      </label>
    </template>
    <UiSpin v-if="loading" tip="加载元数据" />
    <UiEmpty v-else-if="unavailable" :description="unavailableDescription" />
    <div v-else class="metadata-tree" :data-testid="$attrs['data-testid']">
      <UiTree :nodes="nodes" v-bind="$attrs" :drag-operations="['copy']" :allow-drop="() => false" />
      <slot />
    </div>
  </RecordExplorerPanel>
</template>

<style scoped>
.metadata-source-tree__system-fields {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 12px;
  white-space: nowrap;
}
.metadata-tree {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
  overflow: hidden;
}
.metadata-tree > :deep(.ui-tree) {
  flex: 1 1 auto;
}
.metadata-tree :deep(.ant-tree) {
  flex: 0 0 auto;
  min-height: 0;
  overflow: visible;
}
.metadata-tree :deep(.ant-tree-indent-unit) {
  width: 16px;
}
.metadata-tree :deep(.ui-record-explorer-item-title) {
  flex-shrink: 0;
}
.metadata-tree :deep(.ui-record-explorer-item-secondary) {
  flex-shrink: 4;
}
</style>
