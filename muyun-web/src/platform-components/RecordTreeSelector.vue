<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed, ref, watch } from 'vue';
import { UiEmpty, UiTree, type UiTreeNode } from '@muyun/vue-ui-antdv';

const language = useLanguageContext();

export interface RecordTreeSelectorRecord {
  id: string;
  parentId?: string;
  title?: string;
  secondary?: string;
  enabled?: boolean;
}

defineOptions({ name: 'RecordTreeSelector' });

const props = withDefaults(
  defineProps<{
    records: RecordTreeSelectorRecord[];
    selectedId?: string;
    emptyDescription?: string;
  }>(),
  { selectedId: undefined, emptyDescription: undefined },
);

const resolvedEmptyDescription = computed(
  () =>
    props.emptyDescription ?? language.t('platform.screen.RecordTreeSelector.emptyDescription', '暂无记录'),
);
const emit = defineEmits<{ select: [record: RecordTreeSelectorRecord] }>();
const expandedKeys = ref<string[]>([]);
const recordById = computed(() => new Map(props.records.map((record) => [record.id, record])));
const nodes = computed(() => {
  const children = new Map<string | undefined, RecordTreeSelectorRecord[]>();
  for (const record of props.records) {
    const parentId = record.parentId && recordById.value.has(record.parentId) ? record.parentId : undefined;
    children.set(parentId, [...(children.get(parentId) ?? []), record]);
  }
  const build = (parentId?: string): UiTreeNode[] =>
    (children.get(parentId) ?? []).map((record) => ({
      key: record.id,
      title: record.title ?? record.id,
      secondary: record.secondary,
      muted: record.enabled === false,
      children: build(record.id),
    }));
  return build();
});
watch(
  nodes,
  (value) => {
    expandedKeys.value = value.map((node) => node.key);
  },
  { immediate: true },
);
function select(node: UiTreeNode) {
  const record = recordById.value.get(node.key);
  if (record) emit('select', record);
}
</script>

<template>
  <div class="record-tree-selector">
    <UiEmpty v-if="nodes.length === 0" :description="resolvedEmptyDescription" />
    <UiTree
      v-else
      :nodes="nodes"
      :selected-key="selectedId"
      :expanded-keys="expandedKeys"
      @update:expanded-keys="expandedKeys = $event"
      @select="select"
    />
  </div>
</template>

<style scoped>
.record-tree-selector {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.record-tree-selector > :deep(.ui-empty) {
  flex: 1 1 auto;
}

.record-tree-selector :deep(.ant-tree) {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
}
</style>
