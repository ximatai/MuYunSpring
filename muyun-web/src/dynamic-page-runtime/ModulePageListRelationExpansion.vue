<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type {
  ResolvedDetailRelationDescriptor,
  ResolvedModuleUiDescriptor,
  ResolvedPageListRelationExpansionDescriptor,
  WebListResponse,
} from '@muyun/web-contracts';
import type { HttpClient, ModuleContext } from '@muyun/web-core';
import { RecordPanelState, type QueryListRecord } from '@muyun/platform-components';
import ModulePageDetailRelations from './ModulePageDetailRelations.vue';

defineOptions({ name: 'ModulePageListRelationExpansion' });

const props = defineProps<{
  sourceContext: ModuleContext<QueryListRecord>;
  crossModuleHttp?: HttpClient;
  uiDescriptor: ResolvedModuleUiDescriptor;
  record: QueryListRecord;
  relation: ResolvedDetailRelationDescriptor;
  expansion: ResolvedPageListRelationExpansionDescriptor;
}>();

const loading = ref(false);
const failed = ref(false);
const parentRecord = ref<QueryListRecord>();
let requestSequence = 0;

const rows = computed<QueryListRecord[]>(() => {
  const field = props.relation.embeddedField;
  const value = field == null ? undefined : parentRecord.value?.[field];
  return Array.isArray(value) ? (value as QueryListRecord[]) : [];
});

const displayRelation = computed<ResolvedDetailRelationDescriptor>(() => ({
  ...props.relation,
  listProjection:
    props.relation.listProjection == null
      ? undefined
      : {
          ...props.relation.listProjection,
          fields: props.relation.listProjection.fields.filter((field) =>
            props.expansion.fields.includes(field.fieldName),
          ),
        },
}));

async function load() {
  const id = props.record.id == null ? undefined : String(props.record.id);
  if (!id || !props.relation.embeddedField) {
    parentRecord.value = undefined;
    return;
  }
  const sequence = ++requestSequence;
  loading.value = true;
  failed.value = false;
  try {
    const response = await props.sourceContext.http.request<WebListResponse<QueryListRecord>>({
      path: `/${encodeURIComponent(props.sourceContext.moduleAlias)}/view/${encodeURIComponent(id)}/relations/${encodeURIComponent(props.relation.code)}/expansion`,
    });
    if (sequence === requestSequence) {
      parentRecord.value = { id, [props.relation.embeddedField]: response.records };
    }
  } catch {
    if (sequence === requestSequence) failed.value = true;
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
}

watch(
  () => props.record.id,
  () => void load(),
  { immediate: true },
);
</script>

<template>
  <section class="module-page-list-relation-expansion">
    <RecordPanelState v-if="loading" loading loading-tip="加载关联记录" description="" />
    <RecordPanelState v-else-if="failed" description="关联记录加载失败" />
    <RecordPanelState v-else-if="rows.length === 0" description="暂无关联记录" />
    <ModulePageDetailRelations
      v-else-if="parentRecord"
      :source-context="sourceContext"
      :cross-module-http="crossModuleHttp"
      :ui-descriptor="uiDescriptor"
      :relations="[displayRelation]"
      :parent-record="parentRecord"
      surface="list-expansion"
      :mutation-enabled="false"
    />
  </section>
</template>

<style scoped>
.module-page-list-relation-expansion {
  min-width: 0;
}
</style>
