<script setup lang="ts">
import { computed } from 'vue';
import type {
  ResolvedDetailRelationDescriptor,
  WebPageResponse,
  WebQueryRequest,
} from '@muyun/web-contracts';
import { hasExecutableDetailRelationQueryContract } from '@muyun/web-contracts';
import { createModuleContext, type ModuleContext } from '@muyun/web-core';
import RecordQueryListPanel, {
  type QueryListRecord,
  type RecordQueryListColumn,
} from './RecordQueryListPanel.vue';

defineOptions({ name: 'DetailRelationListPanel' });

const props = defineProps<{
  /** The owning record module: relation query authorization remains on this route. */
  sourceContext: ModuleContext<QueryListRecord>;
  relation: ResolvedDetailRelationDescriptor;
  recordId?: string;
  reloadKey?: number;
}>();

const executableRelation = computed(() =>
  hasExecutableDetailRelationQueryContract(props.relation) ? props.relation : undefined,
);
const queryContract = computed(() => executableRelation.value?.queryContract);
const ready = computed(() => executableRelation.value != null && props.recordId != null);
const columns = computed<RecordQueryListColumn[]>(() =>
  (queryContract.value?.listProjection?.fields ?? []).map((field) => ({
    key: field.fieldName,
    title: field.title ?? field.fieldName,
    width: field.width == null ? undefined : `${field.width}px`,
    align: normalizeAlign(field.align),
    maxDisplayLines: field.maxDisplayLines,
  })),
);

/**
 * The target context supplies only target schema/runtime semantics. Its query client is replaced
 * by the server-issued relation route, so the browser neither infers a relation endpoint nor
 * bypasses the owning record's association/data-scope checks.
 */
const relationContext = computed<ModuleContext<QueryListRecord> | undefined>(() => {
  const relation = executableRelation.value;
  const contract = queryContract.value;
  const recordId = props.recordId;
  if (!relation || !contract || !recordId) return undefined;
  const targetContext = createModuleContext<QueryListRecord>({
    http: props.sourceContext.http,
    moduleAlias: relation.targetModuleAlias,
  });
  const queryPath = relationQueryPath(contract.queryPath, recordId);
  return {
    ...targetContext,
    crud: {
      ...targetContext.crud,
      query: (request?: WebQueryRequest) =>
        props.sourceContext.http.request<WebPageResponse<QueryListRecord>>({
          method: 'POST',
          path: queryPath,
          body: request,
        }),
    },
  };
});

function relationQueryPath(pathTemplate: string, recordId: string) {
  return pathTemplate.replace('{id}', encodeURIComponent(recordId));
}

function normalizeAlign(value: string | undefined): RecordQueryListColumn['align'] {
  return value === 'left' || value === 'center' || value === 'right' ? value : undefined;
}
</script>

<template>
  <RecordQueryListPanel
    v-if="relationContext && queryContract"
    :context="relationContext"
    :title="relation.title ?? relation.code"
    :columns="columns"
    :reload-key="reloadKey"
    :ui-config-id="queryContract.targetUiConfigId"
    :query-template-id="queryContract.queryTemplateId"
    :ready="ready"
    :queryable="queryContract.queryable"
    :pageable="queryContract.pageable"
    empty-description="暂无关联记录"
  />
</template>
