<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { RecordExplorerPanel } from '@muyun/platform-components';
import { UiEmpty, UiSpin, UiSwitch, UiTree, type UiTreeNode } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'MetadataSourceTree', inheritAttrs: false });
const language = useLanguageContext();
const props = withDefaults(
  defineProps<{
    nodes: UiTreeNode[];
    title?: string;
    embedded?: boolean;
    utilityPlacement?: 'header' | 'toolbar';
    searchKeyword?: string;
    showSystemFields?: boolean;
    searchable?: boolean;
    refreshDisabled?: boolean;
    loading?: boolean;
    unavailable?: boolean;
    unavailableDescription?: string;
  }>(),
  {
    embedded: false,
    utilityPlacement: 'header',
    searchKeyword: '',
    showSystemFields: false,
    searchable: true,
    refreshDisabled: false,
    loading: false,
    unavailable: false,
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
    :title="props.title ?? language.t('platform.screen.MetadataSourceTree.available-fields', '可用字段')"
    :embedded="embedded"
    :utility-placement="utilityPlacement"
    :search-keyword="searchKeyword"
    :search-placeholder="language.t('platform.screen.MetadataSourceTree.search-fields', '搜索字段')"
    :searchable="searchable"
    :refresh-disabled="refreshDisabled"
    @update:search-keyword="emit('update:searchKeyword', $event)"
    @refresh="emit('refresh')"
  >
    <template v-if="$slots.title" #title><slot name="title" /></template>
    <template v-if="searchable" #utility-actions>
      <label class="metadata-source-tree__system-fields">
        <span>{{ language.t('platform.screen.MetadataGovernanceSurface.system-fields', '系统字段') }}</span>
        <UiSwitch
          :checked="showSystemFields"
          size="small"
          :title="
            showSystemFields
              ? language.t('platform.screen.MetadataGovernanceSurface.hide-system-fields', '隐藏系统字段')
              : language.t('platform.screen.MetadataGovernanceSurface.show-system-fields', '显示系统字段')
          "
          :aria-label="
            showSystemFields
              ? language.t('platform.screen.MetadataGovernanceSurface.hide-system-fields', '隐藏系统字段')
              : language.t('platform.screen.MetadataGovernanceSurface.show-system-fields', '显示系统字段')
          "
          @update:checked="emit('update:showSystemFields', $event)"
        />
      </label>
    </template>
    <UiSpin
      v-if="loading"
      :tip="language.t('platform.screen.MetadataGovernanceSurface.loading-metadata', '加载元数据')"
    />
    <UiEmpty
      v-else-if="unavailable"
      :description="
        props.unavailableDescription ??
        language.t('platform.screen.MetadataSourceTree.no-metadata-available', '暂无可用元数据')
      "
    />
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
