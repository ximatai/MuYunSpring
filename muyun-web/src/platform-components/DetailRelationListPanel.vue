<script setup lang="ts">
import { computed } from 'vue';
import type {
  ResolvedDetailRelationDescriptor,
  WebPageResponse,
  WebQueryRequest,
} from '@muyun/web-contracts';
import { hasExecutableDetailRelationQueryContract } from '@muyun/web-contracts';
import { normalizeModulePageResponse, type ModuleContext } from '@muyun/web-core';
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
 * The source context remains the sole authorization context. The server-issued relation contract
 * contains both the query route and schema, so the browser never loads target-module context or
 * query schema merely to render an embedded relation.
 */
const relationContext = computed<ModuleContext<QueryListRecord> | undefined>(() => {
  const relation = executableRelation.value;
  const contract = queryContract.value;
  const recordId = props.recordId;
  if (!relation || !contract || !recordId) return undefined;
  const queryPath =
    contract.managedGateway === true
      ? `/${props.sourceContext.moduleAlias}/view/${encodeURIComponent(recordId)}/relations/${encodeURIComponent(relation.code)}/query`
      : relationQueryPath(contract.queryPath, recordId);
  return {
    ...props.sourceContext,
    moduleAlias: relation.targetModuleAlias,
    crud: {
      ...props.sourceContext.crud,
      query: (request?: WebQueryRequest) =>
        props.sourceContext.http.request<WebPageResponse<QueryListRecord>>({
          method: 'POST',
          path: queryPath,
          body: request,
        }).then(normalizeModulePageResponse),
    },
  };
});

function relationQueryPath(pathTemplate: string | undefined, recordId: string) {
  if (!pathTemplate) return '';
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
    :show-title="false"
    :header-visible="false"
    :show-recycle-bin="false"
    embedded
    :columns="columns"
    :reload-key="reloadKey"
    :ui-config-id="queryContract.targetUiConfigId"
    :query-template-id="queryContract.queryTemplateId"
    :query-schema="queryContract.querySchema"
    :ready="ready"
    :queryable="queryContract.queryable"
    :pageable="queryContract.pageable"
    empty-description="暂无关联记录"
  />
</template>
