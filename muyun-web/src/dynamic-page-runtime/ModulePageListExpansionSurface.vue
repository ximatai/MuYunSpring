<script setup lang="ts">
import { RecordListExpansionSurface, type QueryListRecord } from '@muyun/platform-components';
import type {
  ResolvedDetailRelationDescriptor,
  ResolvedModuleUiDescriptor,
  ResolvedPageListRelationExpansionDescriptor,
} from '@muyun/web-contracts';
import type { HttpClient, ModuleContext } from '@muyun/web-core';
import type { ModulePageListRowExpansion, ModulePageListRowExpansionContext } from './modulePageEnhancements';
import ModulePageListRelationExpansions from './ModulePageListRelationExpansions.vue';

defineOptions({ name: 'ModulePageListExpansionSurface' });

type RelationExpansionEntry = {
  expansion: ResolvedPageListRelationExpansionDescriptor;
  relation: ResolvedDetailRelationDescriptor;
};

defineProps<{
  sourceContext: ModuleContext<QueryListRecord>;
  crossModuleHttp?: HttpClient;
  uiDescriptor: ResolvedModuleUiDescriptor;
  record: QueryListRecord;
  relationEntries: readonly RelationExpansionEntry[];
  extension?: ModulePageListRowExpansion;
  extensionContext?: ModulePageListRowExpansionContext;
}>();
</script>

<template>
  <RecordListExpansionSurface>
    <ModulePageListRelationExpansions
      v-if="relationEntries.length > 0"
      :source-context="sourceContext"
      :cross-module-http="crossModuleHttp"
      :ui-descriptor="uiDescriptor"
      :record="record"
      :entries="relationEntries"
    />
    <component :is="extension?.component" v-if="extension && extensionContext" :context="extensionContext" />
  </RecordListExpansionSurface>
</template>
