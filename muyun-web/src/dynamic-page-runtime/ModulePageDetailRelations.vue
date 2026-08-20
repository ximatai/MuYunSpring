<script setup lang="ts">
import { computed } from 'vue';
import {
  DetailRelationListPanel,
  RecordDetailExtensionSection,
  type QueryListRecord,
} from '@muyun/platform-components';
import type { ResolvedDetailRelationDescriptor, ResolvedModuleUiDescriptor } from '@muyun/web-contracts';
import { hasExecutableDetailRelationQueryContract } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import ManagedDetailRelationSurface from './ManagedDetailRelationSurface.vue';

defineOptions({ name: 'ModulePageDetailRelations' });

const props = defineProps<{
  sourceContext: ModuleContext<QueryListRecord>;
  uiDescriptor: ResolvedModuleUiDescriptor;
  relations: ResolvedDetailRelationDescriptor[];
  parentRecord: QueryListRecord;
  parentDirty?: boolean;
  reloadKey?: number;
}>();

const visibleRelations = computed(() =>
  props.relations.filter((relation) => {
    if (!hasExecutableDetailRelationQueryContract(relation)) return false;
    const constraint = relation.parentConstraint;
    return (
      constraint == null ||
      String(props.parentRecord[constraint.fieldName] ?? '') === constraint.expectedValue
    );
  }),
);
</script>

<template>
  <RecordDetailExtensionSection
    v-for="relation in visibleRelations"
    :key="`relation:${relation.code}`"
    :title="relation.title ?? relation.code"
  >
    <ManagedDetailRelationSurface
      v-if="relation.queryContract?.managedGateway"
      :source-context="sourceContext"
      :ui-descriptor="uiDescriptor"
      :relation="relation"
      :parent-record="parentRecord"
      :parent-dirty="parentDirty"
      :reload-key="reloadKey"
    />
    <DetailRelationListPanel
      v-else
      :source-context="sourceContext"
      :relation="relation"
      :record-id="parentRecord.id == null ? undefined : String(parentRecord.id)"
      :reload-key="reloadKey"
    />
  </RecordDetailExtensionSection>
</template>
