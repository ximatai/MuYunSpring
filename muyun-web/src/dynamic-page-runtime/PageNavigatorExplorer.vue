<script setup lang="ts">
import {
  CrudRecordListExplorer,
  ModuleActionButton,
  RecordExplorerPanel,
  TreeRecordExplorer,
  type QueryListRecord,
} from '@muyun/platform-components';
import type { RecordInlineAction, ResolvedPageNavigatorLevelDescriptor } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';

defineOptions({ name: 'PageNavigatorExplorer' });

type NavigatorLevelRuntime = {
  descriptor: ResolvedPageNavigatorLevelDescriptor;
  context: ModuleContext<QueryListRecord>;
  tree: boolean;
};

const props = defineProps<{
  level: NavigatorLevelRuntime;
  selectedId?: string;
  reloadKey: number;
  keyword: string;
  externalQueryValues?: Record<string, unknown>;
  actionsOf?: () => RecordInlineAction[];
}>();

const emit = defineEmits<{
  'update:keyword': [value: string];
  refresh: [];
  create: [];
  select: [record: QueryListRecord];
  loaded: [records: QueryListRecord[]];
  action: [action: RecordInlineAction, record: QueryListRecord];
}>();
</script>

<template>
  <RecordExplorerPanel
    :title="level.descriptor.title"
    :refresh-title="`刷新${level.descriptor.title}${level.tree ? '树' : '列表'}`"
    :search-keyword="keyword"
    :search-placeholder="level.descriptor.searchPlaceholder"
    @update:search-keyword="emit('update:keyword', $event)"
    @refresh="emit('refresh')"
  >
    <template v-if="level.descriptor.management" #actions>
      <ModuleActionButton
        :context="level.context"
        action-code="create"
        icon-only
        :title="`新建${level.descriptor.title}`"
        @click="emit('create')"
      />
    </template>
    <TreeRecordExplorer
      v-if="level.tree"
      :context="level.context"
      :selected-id="selectedId"
      :reload-key="reloadKey"
      :keyword="keyword"
      :external-query-values="externalQueryValues"
      search-mode="none"
      :empty-description="`暂无${level.descriptor.title}`"
      :actions-of="actionsOf"
      @loaded="emit('loaded', $event as QueryListRecord[])"
      @select="emit('select', $event as QueryListRecord)"
      @action="(action, record) => emit('action', action, record as QueryListRecord)"
    />
    <CrudRecordListExplorer
      v-else
      :context="level.context"
      :selected-id="selectedId"
      :reload-key="reloadKey"
      :keyword="keyword"
      :external-query-values="externalQueryValues"
      :empty-description="`暂无${level.descriptor.title}`"
      :actions-of="actionsOf"
      @loaded="emit('loaded', $event as QueryListRecord[])"
      @select="emit('select', $event as QueryListRecord)"
      @action="(action, record) => emit('action', action, record as QueryListRecord)"
    />
    <template #editor><slot name="editor" /></template>
  </RecordExplorerPanel>
</template>
