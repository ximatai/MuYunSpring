<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type {
  ResolvedDetailRelationDescriptor,
  ResolvedModuleUiDescriptor,
  ResolvedPageListRelationExpansionDescriptor,
} from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import { RecordRelationTabs, type QueryListRecord } from '@muyun/platform-components';
import ModulePageListRelationExpansion from './ModulePageListRelationExpansion.vue';

defineOptions({ name: 'ModulePageListRelationExpansions' });

type RelationExpansionEntry = {
  relation: ResolvedDetailRelationDescriptor;
  expansion: ResolvedPageListRelationExpansionDescriptor;
};

const props = defineProps<{
  sourceContext: ModuleContext<QueryListRecord>;
  uiDescriptor: ResolvedModuleUiDescriptor;
  record: QueryListRecord;
  entries: readonly RelationExpansionEntry[];
}>();

const activeRelationCode = ref<string>();
const loadedRelationCodes = ref(new Set<string>());
const multipleRelations = computed(() => props.entries.length > 1);
const loadedEntries = computed(() =>
  props.entries.filter((entry) => loadedRelationCodes.value.has(entry.expansion.relationCode)),
);

function activate(relationCode: string) {
  activeRelationCode.value = relationCode;
  loadedRelationCodes.value = new Set([...loadedRelationCodes.value, relationCode]);
}

watch(
  () => [props.record.id, props.entries.map((entry) => entry.expansion.relationCode).join('|')] as const,
  ([, relationCodes]) => {
    const firstRelationCode = relationCodes.split('|')[0];
    if (!firstRelationCode) {
      activeRelationCode.value = undefined;
      loadedRelationCodes.value = new Set();
      return;
    }
    activate(firstRelationCode);
  },
  { immediate: true },
);
</script>

<template>
  <ModulePageListRelationExpansion
    v-if="entries.length === 1"
    :source-context="sourceContext"
    :ui-descriptor="uiDescriptor"
    :record="record"
    :relation="entries[0].relation"
    :expansion="entries[0].expansion"
  />

  <section v-else-if="multipleRelations" class="module-page-list-relation-expansions">
    <RecordRelationTabs
      :tabs="
        entries.map((entry) => ({
          key: entry.expansion.relationCode,
          title: entry.relation.title ?? entry.expansion.relationCode,
        }))
      "
      :active-key="activeRelationCode"
      @update:active-key="activate"
    />
    <ModulePageListRelationExpansion
      v-for="entry in loadedEntries"
      v-show="entry.expansion.relationCode === activeRelationCode"
      :key="entry.expansion.relationCode"
      :source-context="sourceContext"
      :ui-descriptor="uiDescriptor"
      :record="record"
      :relation="entry.relation"
      :expansion="entry.expansion"
    />
  </section>
</template>

<style scoped>
.module-page-list-relation-expansions {
  min-width: 0;
}

.module-page-list-relation-expansions :deep(.module-page-list-relation-expansion) {
  min-width: 0;
}
</style>
