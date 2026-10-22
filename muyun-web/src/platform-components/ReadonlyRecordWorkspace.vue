<script setup lang="ts" generic="TRecord extends Record<string, unknown>">
import { computed, ref, watch } from 'vue';
import {
  presentationLabel,
  businessContentLanguageContext,
  normalizeError,
  useLanguageContext,
  userFacingErrorMessage,
  useRecordReadSession,
  type RecordReadQuery,
  type RecordReadView,
} from '@muyun/web-core';
import { UiEmpty, UiError, UiSpin } from '@muyun/vue-ui-antdv';
import type { Option, QueryCriteriaGroup, QuerySchemaField, WebSort } from '@muyun/web-contracts';
import QueryCriteriaComposer from './QueryCriteriaComposer.vue';
import RecordQueryControls from './RecordQueryControls.vue';
import RecordQueryFieldControls from './RecordQueryFieldControls.vue';
import { isValueLessQueryOperator } from './queryCriteriaDraft';
import { criteriaLeafCount, conjoinQueryCriteria, queryCriteriaComplexityMessage } from './queryCriteria';
import type { ReferencePickerValidity, ReferencePickerConfig } from './referencePickerModel';
import ManagementWorkspace from './ManagementWorkspace.vue';
import RecordQueryListSurface from './RecordQueryListSurface.vue';
import RecordQueryListCell from './RecordQueryListCell.vue';
import RecordAdaptiveDetailPanel from './RecordAdaptiveDetailPanel.vue';
import RecordDetailFields from './RecordDetailFields.vue';
import { resolveRecordQueryListColumns } from './recordQueryListColumnModel';
import { resolveRecordDetailFields } from './recordFormFieldModel';
import { useWorkspaceDetailSurface } from './useWorkspaceDetailSurface';
import { listDetailWorkspaceMinWidth } from './managementWorkspaceLayout';
import { providePageLayout } from './pageLayoutContext';

defineOptions({ name: 'ReadonlyRecordWorkspace' });
providePageLayout('workspace');
const props = withDefaults(
  defineProps<{
    view: RecordReadView<TRecord>;
    query?: RecordReadQuery;
    pageSize?: number;
    quickSearch?: boolean;
    /** Source-authorized option projections; no implicit target CRUD/dictionary requests. */
    queryOptionsByField?: Record<string, Option[]>;
    referencePickerOf?: (field: QuerySchemaField) => ReferencePickerConfig | undefined;
  }>(),
  {
    query: undefined,
    pageSize: 20,
    quickSearch: true,
    queryOptionsByField: () => ({}),
    referencePickerOf: undefined,
  },
);
const language = useLanguageContext();
const reading = businessContentLanguageContext(language);
const host = ref<HTMLElement>();
const narrow = useWorkspaceDetailSurface(host, listDetailWorkspaceMinWidth(0));
const interaction = ref<{
  criteria?: QueryCriteriaGroup;
  sorts?: WebSort[];
  persistentValues?: Record<string, unknown[]>;
}>({});
const persistentDraft = ref<Record<string, unknown[]>>({});
const persistentValidity = ref<Record<string, ReferencePickerValidity>>({});
const fieldResetKey = ref(0);
const criteria = computed({
  get: () => interaction.value.criteria,
  set: (criteria) => {
    commitInteraction({ ...interaction.value, criteria });
  },
});
const sorts = computed({
  get: () => interaction.value.sorts,
  set: (sorts) => {
    commitInteraction({ ...interaction.value, sorts });
  },
});
const expanded = ref(false);
const composerMounted = ref(false);
const validation = ref('');
const identity = computed(() =>
  JSON.stringify([props.view.sourceId, props.view.viewCode, props.view.scopeKey]),
);
const schema = computed(() => props.view.querySchema);
const composition = computed(() => schema.value?.criteriaComposition ?? 'TREE');
const queryFields = computed(() =>
  (schema.value?.fields ?? [])
    .filter((field) => !field.reference || !!props.referencePickerOf?.(field))
    .map((field) => ({
      ...field,
      title: presentationLabel(field.title ?? field.name, field.titleText, language),
    })),
);
const persistentFields = computed(() => queryFields.value.filter((field) => field.persistentControl));
const persistentValid = computed(() => Object.values(persistentValidity.value).every((value) => value.valid));
function persistentConditionsFor(valuesById: Record<string, unknown[]> | undefined) {
  return persistentFields.value.flatMap((field) => {
    const control = field.persistentControl!;
    const values = valuesById?.[control.id] ?? control.defaultValues ?? [];
    return values.length || isValueLessQueryOperator(control.operator)
      ? [{ kind: 'CONDITION' as const, fieldName: field.name, operator: control.operator, values }]
      : [];
  });
}
const persistentConditions = computed(() => persistentConditionsFor(interaction.value.persistentValues));
const persistentControls = computed(() =>
  persistentFields.value.map((field) => {
    const control = field.persistentControl!;
    return {
      ...control,
      title: presentationLabel(control.title, control.titleText, language),
      field,
      values: persistentDraft.value[control.id] ?? control.defaultValues ?? [],
      options: props.queryOptionsByField[field.name],
      referencePicker: props.referencePickerOf?.(field),
    };
  }),
);
function applyPersistentFields() {
  if (!persistentValid.value || loading.value) return;
  commitInteraction({
    ...interaction.value,
    persistentValues: Object.fromEntries(
      persistentControls.value.map((control) => [control.id, [...control.values]]),
    ),
  });
}
function resetPersistentFields() {
  persistentDraft.value = {};
  persistentValidity.value = {};
  fieldResetKey.value++;
  commitInteraction({ ...interaction.value, persistentValues: undefined });
}
const activeQuery = computed<RecordReadQuery>(() => ({
  ...props.query,
  criteria: conjoinQueryCriteria([props.query?.criteria, ...persistentConditions.value, criteria.value]),
  sorts:
    sorts.value ??
    props.query?.sorts ??
    schema.value?.defaultSorts.map((sort) => ({ ...sort, desc: sort.desc ?? false })),
  quickSearchFields: schema.value?.quickSearch.fields ?? props.query?.quickSearchFields,
}));
function commitInteraction(next: typeof interaction.value) {
  const previous = JSON.stringify(activeQuery.value);
  const issue = queryCriteriaComplexityMessage(
    conjoinQueryCriteria([
      props.query?.criteria,
      ...persistentConditionsFor(next.persistentValues),
      next.criteria,
    ]),
    language,
  );
  validation.value = issue ?? '';
  if (issue) return;
  interaction.value = next;
  // Reapplying the current filters is an explicit query, even on a later page.
  if (JSON.stringify(activeQuery.value) === previous) void session.goPage(1);
}
const queryCapabilities = computed(() =>
  JSON.stringify({
    composition: composition.value,
    fields: schema.value?.fields.map((field) => ({
      name: field.name,
      valueType: field.valueType,
      operators: field.operators,
      sortable: field.sortable,
      reference: field.reference,
      persistentControl: field.persistentControl && {
        id: field.persistentControl.id,
        operator: field.persistentControl.operator,
        defaultValues: field.persistentControl.defaultValues,
      },
    })),
    quickSearch: schema.value && {
      enabled: schema.value.quickSearch.enabled,
      fields: schema.value.quickSearch.fields,
    },
    defaultSorts: schema.value?.defaultSorts,
  }),
);
watch(
  [identity, queryCapabilities],
  () => {
    interaction.value = {};
    persistentDraft.value = {};
    persistentValidity.value = {};
    fieldResetKey.value++;
    expanded.value = false;
    composerMounted.value = false;
    validation.value = '';
  },
  { flush: 'sync' },
);
function toggleFilters() {
  composerMounted.value = true;
  expanded.value = !expanded.value;
}
function applyCriteria(value: QueryCriteriaGroup | undefined) {
  validation.value = '';
  criteria.value = value;
}
const session = useRecordReadSession({
  view: () => props.view,
  query: activeQuery,
  pageSize: props.pageSize,
  contentLanguage: () => reading.languageTag.value,
});
const {
  result,
  loading,
  error,
  selected,
  detail,
  detailLoading,
  detailError,
  pageNum,
  pageSize: activePageSize,
} = session;
watch(
  queryCapabilities,
  () => {
    void session.search('');
  },
  { flush: 'sync' },
);
const keyword = ref('');
watch(
  () => props.pageSize,
  (value) => void session.resizePage(value),
);
watch(session.quickSearch, (value) => {
  keyword.value = value;
});
watch(
  identity,
  () => {
    keyword.value = '';
  },
  { flush: 'sync' },
);
const list = computed(() => props.view.descriptor.page?.list);
const columns = computed(() => resolveRecordQueryListColumns(list.value?.fields));
const fields = computed(() =>
  props.view.descriptor.page?.detail?.display ? resolveRecordDetailFields(props.view.descriptor) : new Map(),
);
const docked = computed(() => !narrow.value);
const title = computed(() => list.value?.title?.text ?? list.value?.fields.title ?? props.view.viewCode);
const detailTitle = computed(() =>
  selected.value ? (props.view.detail?.title(selected.value) ?? title.value) : title.value,
);
const pages = computed(() => result.value?.pages ?? 0);
function errorMessage(cause: unknown) {
  return userFacingErrorMessage(normalizeError(cause), language);
}
defineExpose({ refresh: session.refresh, closeDetail: session.closeDetail });
</script>

<template>
  <section ref="host" class="readonly-record-workspace" :aria-label="title">
    <ManagementWorkspace
      :explorer-count="0"
      :detail-surface="!!selected && docked"
      :list-surface="!selected || !docked"
    >
      <RecordQueryListSurface
        :title="title"
        :subtitle="list?.subtitle?.text"
        title-action-icon="reload"
        :title-action-title="language.t('platform.read-view.refresh', '刷新')"
        :title-action-disabled="loading"
        :quick-search-visible="quickSearch && (schema?.quickSearch.enabled ?? true)"
        :quick-search-value="keyword"
        :quick-search-placeholder="list?.searchPlaceholder"
        :quick-search-disabled="loading"
        :columns="columns"
        :rows="result?.records ?? []"
        :row-key="(row) => view.recordKey(row as TRecord)"
        :selected-row-key="selected ? view.recordKey(selected) : undefined"
        :clickable-rows="!!view.detail"
        :table-visible="!loading && !error && !!result?.records.length"
        pageable
        :total="result?.total ?? 0"
        :total-known="result?.totalKnown ?? true"
        :page-num="pageNum"
        :page-size="activePageSize"
        :pages="pages"
        :pagination-disabled="loading || !result"
        @title-action="session.refresh"
        @update:quick-search-value="keyword = $event"
        @quick-search="session.search"
        @page-change="session.goPage"
        @page-size-change="session.resizePage"
        @row-click="session.select($event as TRecord)"
      >
        <template #persistentQueries>
          <RecordQueryFieldControls
            :controls="persistentControls"
            :reset-key="`${identity}:${fieldResetKey}`"
            :disabled="loading"
            :valid="persistentValid"
            @update:values="(id, values) => (persistentDraft = { ...persistentDraft, [id]: values })"
            @validity="(id, validity) => (persistentValidity = { ...persistentValidity, [id]: validity })"
            @apply="applyPersistentFields"
            @reset="resetPersistentFields"
          />
          <slot name="queries" />
        </template>
        <template #queryControls>
          <RecordQueryControls
            :sort-visible="!!schema"
            :sort-fields="schema?.fields.filter((field) => field.sortable) ?? []"
            :sorts="sorts"
            :filter-visible="
              composition !== 'NONE' &&
              queryFields.some((field) => composition !== 'FLAT_AND' || !field.persistentControl)
            "
            :filter-title="
              composition === 'FLAT_AND'
                ? language.t('platform.screen.RecordQueryListPanel.more-filters', '更多筛选')
                : language.t('platform.screen.RecordQueryListPanel.advanced-filters', '高级筛选')
            "
            :expanded="expanded"
            :count="criteriaLeafCount(criteria)"
            :disabled="loading"
            @sort-change="sorts = $event"
            @toggle="toggleFilters"
          />
        </template>
        <template v-if="composerMounted" #conditions>
          <section v-show="expanded">
            <QueryCriteriaComposer
              :key="identity"
              :fields="queryFields"
              :conjuncts="[...(props.query?.criteria ? [props.query.criteria] : []), ...persistentConditions]"
              :excluded-field-names="
                composition === 'FLAT_AND' ? persistentFields.map((field) => field.name) : []
              "
              :option-items-by-field="queryOptionsByField"
              :reference-contexts="{}"
              :reference-picker-of="referencePickerOf"
              :disabled="loading"
              :composition="composition === 'FLAT_AND' ? 'FLAT_AND' : 'TREE'"
              @apply="applyCriteria"
              @clear="applyCriteria(undefined)"
              @validation="validation = $event"
            />
          </section>
        </template>
        <template #operations><slot name="operations" :refresh="session.refresh" /></template>
        <template #beforeTable>
          <UiError v-if="validation" :message="validation" />
          <UiError v-if="error" :message="errorMessage(error)" />
          <UiSpin v-else-if="loading" />
          <UiEmpty
            v-else-if="!result?.records.length"
            :description="language.t('platform.read-view.empty', '暂无符合条件的记录')"
          />
        </template>
        <template #cell="{ column, record }">
          <slot
            name="cell"
            :column="columns.find((item) => item.key === column.key)!"
            :record="record as TRecord"
          >
            <RecordQueryListCell
              :column="columns.find((item) => item.key === column.key)!"
              :record="record"
              :reading="result?.businessContent?.readings[view.recordKey(record as TRecord)]?.[column.key]"
            />
          </slot>
        </template>
      </RecordQueryListSurface>
      <RecordAdaptiveDetailPanel
        :open="!!selected"
        :docked="docked"
        :title="detailTitle"
        dismissal="dismissible"
        @close="session.closeDetail"
      >
        <UiError v-if="detailError" :message="errorMessage(detailError)" />
        <UiSpin v-else-if="detailLoading" />
        <template v-else-if="selected && detail">
          <RecordDetailFields :record="detail" :fields="fields" />
          <slot name="detail" :record="selected" :detail="detail" :refresh="session.refresh" />
        </template>
        <template v-if="selected && detail && !detailLoading && !detailError" #operation>
          <slot
            name="detailOperations"
            :record="selected"
            :detail="detail"
            :refresh="session.refresh"
            :close="session.closeDetail"
          />
        </template>
      </RecordAdaptiveDetailPanel>
    </ManagementWorkspace>
  </section>
</template>

<style scoped>
.readonly-record-workspace {
  height: 100%;
  min-height: 0;
}
.readonly-record-workspace :deep(.record-adaptive-detail__dock) {
  min-width: 100%;
  height: 100%;
  box-sizing: border-box;
}
</style>
