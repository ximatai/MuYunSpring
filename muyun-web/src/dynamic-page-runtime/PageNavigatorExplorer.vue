<script setup lang="ts">
import {
  CrudRecordListExplorer,
  ModuleActionButton,
  RecordExplorerPanel,
  RecordPanelButton,
  RecordPanelState,
  TreeRecordExplorer,
  type QueryListRecord,
} from '@muyun/platform-components';
import { computed, ref } from 'vue';
import type { RecordInlineAction, ResolvedPageNavigatorLevelDescriptor } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import { navigatorItemOf, type NavigatorItemRecord } from './pageNavigatorItemModel';

defineOptions({ name: 'PageNavigatorExplorer' });

type NavigatorLevelRuntime = {
  descriptor: ResolvedPageNavigatorLevelDescriptor;
  context: ModuleContext<QueryListRecord>;
  tree: boolean;
  sortingDisabled: boolean;
};

const props = defineProps<{
  level: NavigatorLevelRuntime;
  selectedId?: string;
  reloadKey: number;
  keyword: string;
  externalQueryValues?: Record<string, unknown>;
  navigatorHostModuleAlias: string;
  /** Whether this navigator's upstream selection scope is available. */
  ready?: boolean;
  /** The navigator's declared upstream query scope is not settled yet. */
  createDisabled?: boolean;
  createDisabledReason?: string;
  /** Human-readable context supplied by an upstream navigator selection. */
  scopeSubtitle?: string;
  actionsOf?: (record: { id?: string }) => RecordInlineAction[];
}>();

const emit = defineEmits<{
  'update:keyword': [value: string];
  refresh: [];
  create: [];
  select: [record: QueryListRecord];
  deselect: [];
  loaded: [records: QueryListRecord[]];
  action: [action: RecordInlineAction, record: QueryListRecord];
}>();

const sorting = ref(false);
const managementAvailable = computed(() => props.level.descriptor.management != null);
const sortingAvailable = computed(
  () => !props.level.sortingDisabled && managementAvailable.value && props.level.context.can('sort') === true,
);

function itemOf(record: NavigatorItemRecord) {
  return navigatorItemOf(
    record,
    props.level.descriptor.secondaryField,
    managementAvailable.value,
    props.actionsOf,
  );
}
</script>

<template>
  <RecordExplorerPanel
    :class="{ 'page-navigator-explorer--readonly': !managementAvailable }"
    :title="level.descriptor.title"
    :subtitle="scopeSubtitle"
    :refresh-title="`刷新${level.descriptor.title}${level.tree ? '树' : '列表'}`"
    :search-keyword="keyword"
    :search-placeholder="level.descriptor.searchPlaceholder"
    @update:search-keyword="emit('update:keyword', $event)"
    @refresh="emit('refresh')"
  >
    <template v-if="sortingAvailable" #utility-actions>
      <RecordPanelButton
        icon-name="swap-vertical"
        icon-only
        size="small"
        type="text"
        :selected="sorting"
        :disabled="Boolean(keyword.trim())"
        :title="keyword.trim() ? '清空搜索后可调整排序' : sorting ? '结束排序' : '调整排序'"
        :aria-label="sorting ? '结束排序' : '调整排序'"
        @click="sorting = !sorting"
      />
    </template>
    <template v-if="managementAvailable" #actions>
      <ModuleActionButton
        :context="level.context"
        action-code="create"
        icon-only
        :disabled="createDisabled"
        :title="createDisabled ? createDisabledReason : `新建${level.descriptor.title}`"
        @click="emit('create')"
      />
    </template>
    <TreeRecordExplorer
      v-if="ready !== false && level.tree"
      :context="level.context"
      :selected-id="selectedId"
      :reload-key="reloadKey"
      :keyword="keyword"
      :external-query-values="externalQueryValues"
      :navigator-host-module-alias="navigatorHostModuleAlias"
      :navigator-target-level-key="level.descriptor.key"
      search-mode="none"
      :empty-description="`暂无${level.descriptor.title}`"
      :item-of="itemOf"
      :actions-of="managementAvailable ? actionsOf : undefined"
      :sorting="sorting"
      @loaded="emit('loaded', $event as QueryListRecord[])"
      @select="emit('select', $event as QueryListRecord)"
      @deselect="emit('deselect')"
      @action="(action, record) => emit('action', action, record as QueryListRecord)"
    />
    <CrudRecordListExplorer
      v-else-if="ready !== false"
      :context="level.context"
      :selected-id="selectedId"
      :reload-key="reloadKey"
      :keyword="keyword"
      :external-query-values="externalQueryValues"
      :navigator-host-module-alias="navigatorHostModuleAlias"
      :navigator-target-level-key="level.descriptor.key"
      :empty-description="`暂无${level.descriptor.title}`"
      :item-of="itemOf"
      :actions-of="managementAvailable ? actionsOf : undefined"
      :sorting="sorting"
      @loaded="emit('loaded', $event as QueryListRecord[])"
      @select="emit('select', $event as QueryListRecord)"
      @deselect="emit('deselect')"
      @action="(action, record) => emit('action', action, record as QueryListRecord)"
    />
    <RecordPanelState v-else description="请先选择导航范围" />
    <template #editor><slot name="editor" /></template>
  </RecordExplorerPanel>
</template>

<style scoped>
/* A read-only navigator is a range selector, never a record-management list. */
.page-navigator-explorer--readonly :deep(.ui-record-explorer-item-actions) {
  display: none;
}
</style>
