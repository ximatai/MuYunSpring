<script lang="ts">
export type {
  QueryListRecord,
  RecordQueryListCellComponent,
  RecordQueryListColumn,
  RecordQueryListMode,
  StandardCrudRowActionKey,
} from './recordQueryListColumnModel';
</script>

<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from 'vue';
import { confirmAction, UiButton, UiCheckbox, UiDropdown, UiEmpty, UiSpin } from '@muyun/vue-ui-antdv';
import type {
  UiDataTableColumn,
  UiDataTableKey,
  UiDataTableSelection,
  UiDropdownItem,
} from '@muyun/vue-ui-antdv';
import type {
  Option,
  QueryCriteriaGroup,
  QueryOperator,
  QueryCriteriaCondition,
  QuerySchema,
  ResolvedPageListExternalPersistentQueryControlDescriptor,
  ResolvedPageListFieldPersistentQueryControlDescriptor,
  ResolvedPageListPersistentQueryControlDescriptor,
  ResolvedViewDescriptor,
  WebQueryRequest,
  WebSort,
  RecycleBinItem,
  ResolvedPageListQuerySummaryDescriptor,
  WebListQuerySummaryItem,
} from '@muyun/web-contracts';
import {
  canQueryRecycleBin,
  createModuleContext,
  hasRecycleBinAbility,
  normalizeError,
  type ModuleContext,
} from '@muyun/web-core';
import { presentPlatformError, presentPlatformMessage } from './platformErrorFeedback';
import { WORKSPACE_NAVIGATION_DISABLED } from './managementWorkspaceContext';
import RecordActionBar from './RecordActionBar.vue';
import RecordQueryListCell from './RecordQueryListCell.vue';
import RecordQueryListSurface from './RecordQueryListSurface.vue';
import QueryGroupedSummary from './QueryGroupedSummary.vue';
import QueryCriteriaComposer from './QueryCriteriaComposer.vue';
import QueryValueEditor from './QueryValueEditor.vue';
import type { RecordPickerRecord } from './recordPickerConstraints';
import RecycleBinModeButton from './RecycleBinModeButton.vue';
import {
  mergeRecordActions,
  resolveRecordActions,
  type RecordActionItem,
  type ResolvedRecordActionItem,
} from './recordActionBarModel';
import { recycleBinRestoreUnavailableReason, useRecycleBinState } from './recycleBinState';
import {
  resolveRecordQueryListColumns,
  type QueryListRecord,
  type RecordQueryListCellComponent,
  type RecordQueryListColumn,
  type RecordQueryListMode,
  type StandardCrudRowActionKey,
} from './recordQueryListColumnModel';
import { reconcileSelectedKeys } from './selectionRefresh';
import { loadOptionFieldItems } from './optionFieldOptionCache';

const navigationDisabled = inject(
  WORKSPACE_NAVIGATION_DISABLED,
  computed(() => false),
);

defineOptions({ name: 'RecordQueryListPanel' });

interface QueryListRow {
  [key: string]: unknown;
  key: string;
  record: QueryListRecord;
  primaryActions: ResolvedRecordActionItem[];
  secondaryActions: ResolvedRecordActionItem[];
  dropdownItems: UiDropdownItem[];
}

const props = withDefaults(
  defineProps<{
    context: ModuleContext<QueryListRecord>;
    title: string;
    /** Descriptor-owned secondary copy in the main content header. */
    subtitle?: string;
    columns?: RecordQueryListColumn[];
    /** Columns appended to, or anchored around, the descriptor-owned list fields. */
    additionalColumns?: Array<RecordQueryListColumn & { before?: string; after?: string }>;
    /** Vue cell components are deliberately constrained to cells; they do not own the table shell. */
    cellComponents?: RecordQueryListCellComponent[];
    actions?: RecordActionItem[];
    extraActions?: RecordActionItem[];
    batchActions?: RecordActionItem[];
    standardCrudActions?: boolean;
    standardCrudRowActions?: boolean;
    /** Limits built-in row operations without replacing the platform list interaction. */
    standardCrudRowActionKeys?: StandardCrudRowActionKey[];
    /** Optional authorization-code overrides for platform built-in row operations. */
    standardCrudRowActionCodes?: Partial<Record<StandardCrudRowActionKey, string>>;
    rowActionsOf?: (record: QueryListRecord) => RecordActionItem[];
    extraRowActionsOf?: (record: QueryListRecord) => RecordActionItem[];
    rowActionStateOf?: (
      record: QueryListRecord,
      action: RecordActionItem,
    ) => Partial<RecordActionItem> | undefined;
    rowActionsTitle?: string;
    /** Allows an embedding lifecycle to suppress an otherwise-declared row action slot. */
    rowActionsVisible?: boolean;
    /** Width of the fixed right-side action column. Defaults to the platform compact width. */
    actionColumnWidth?: string | number;
    cellRenderers?: Record<string, (record: QueryListRecord) => string>;
    rowKey?: string;
    selectedKey?: string;
    expandedRowKeys?: string[];
    reloadKey?: number;
    refreshTitle?: string;
    refreshable?: boolean;
    /** Embedded section hosts may own the visible heading while this panel keeps an icon-only refresh. */
    showTitle?: boolean;
    /** Lets a parent lifecycle suppress the complete operation toolbar in read mode. */
    headerVisible?: boolean;
    /** Recycle-bin navigation is operational chrome and may be hidden by an embedding lifecycle. */
    showRecycleBin?: boolean;
    /** Removes the standalone card shell when a section already owns the visual boundary. */
    embedded?: boolean;
    pageSize?: number;
    pageSizeOptions?: number[];
    uiConfigId?: string;
    queryTemplateId?: string;
    /** A source-owned query schema avoids forcing embedded relation lists through target-module access. */
    querySchema?: QuerySchema;
    /** Read-only relation runners may deliberately suppress ad-hoc query controls. */
    queryable?: boolean;
    /** A relation query can intentionally be a bounded, non-pageable result. */
    pageable?: boolean;
    ready?: boolean;
    externalQueryValues?: Record<string, unknown>;
    /** Descriptor-owned controls rendered after quick search and before advanced filtering. */
    persistentQueryControls?: ResolvedPageListPersistentQueryControlDescriptor[];
    /** Descriptor-owned result-set facts rendered at the left of the list footer. */
    querySummaries?: ResolvedPageListQuerySummaryDescriptor[];
    /** Descriptor-owned external criteria that must be exposed by the query schema. */
    requiredExternalCriteriaKeys?: string[];
    quickSearchPlaceholder?: string;
    emptyDescription?: string;
    waitingDescription?: string;
    mode?: RecordQueryListMode;
  }>(),
  {
    rowKey: 'id',
    subtitle: undefined,
    columns: () => [],
    additionalColumns: () => [],
    cellComponents: () => [],
    actions: () => [],
    extraActions: () => [],
    batchActions: () => [],
    standardCrudActions: false,
    refreshable: true,
    standardCrudRowActions: false,
    standardCrudRowActionKeys: () => ['view', 'edit', 'delete'],
    standardCrudRowActionCodes: () => ({}),
    rowActionsOf: undefined,
    extraRowActionsOf: undefined,
    rowActionStateOf: undefined,
    rowActionsTitle: '操作',
    rowActionsVisible: true,
    actionColumnWidth: 92,
    cellRenderers: () => ({}),
    selectedKey: undefined,
    expandedRowKeys: () => [],
    reloadKey: undefined,
    refreshTitle: undefined,
    showTitle: true,
    headerVisible: true,
    showRecycleBin: true,
    embedded: false,
    pageSize: 20,
    pageSizeOptions: () => [10, 20, 50],
    uiConfigId: undefined,
    queryTemplateId: undefined,
    querySchema: undefined,
    queryable: true,
    pageable: true,
    ready: true,
    externalQueryValues: undefined,
    querySummaries: () => [],
    requiredExternalCriteriaKeys: () => [],
    quickSearchPlaceholder: '搜索',
    emptyDescription: '暂无记录',
    waitingDescription: '请选择查询范围',
    mode: 'normal',
  },
);

const emit = defineEmits<{
  select: [record: QueryListRecord];
  rowDblclick: [record: QueryListRecord, event: MouseEvent];
  loaded: [records: QueryListRecord[]];
  action: [action: RecordActionItem, event: MouseEvent];
  batchAction: [
    action: RecordActionItem,
    records: QueryListRecord[],
    event: MouseEvent,
    clearSelection: () => void,
  ];
  rowAction: [action: ResolvedRecordActionItem, record: QueryListRecord, event?: MouseEvent];
  rowExpand: [record: QueryListRecord, expanded: boolean];
  modeChange: [mode: RecordQueryListMode];
  /** Lets a page runner persist this presentation preference without owning pagination state. */
  pageSizeChange: [pageSize: number];
  restored: [];
  /** The exact standard request that produced the currently displayed records. */
  queried: [request: WebQueryRequest];
}>();
const slots = defineSlots<{
  toolbarActions?: (props: { refresh: () => void }) => unknown;
  cell?: (props: { column: RecordQueryListColumn; record: QueryListRecord }) => unknown;
  rowActions?: (props: { record: QueryListRecord }) => unknown;
  expandedRow?: (props: { record: QueryListRecord; rowKey: string }) => unknown;
}>();

const loading = ref(false);
const schema = ref<QuerySchema>();
const records = ref<QueryListRecord[]>([]);
const recycleBinItems = new Map<string, RecycleBinItem<QueryListRecord>>();
const recycleBinState = useRecycleBinState({ context: () => props.context });
const total = ref(0);
const totalKnown = ref(true);
const pageNum = ref(1);
const pageSize = ref(props.pageSize);
const runtimeListView = ref<ResolvedViewDescriptor>();
const descriptorLoadError = ref(false);
const recordsLoadError = ref<string>();
const quickSearchKeyword = ref('');
const appliedQuickSearch = ref('');
const conditionsExpanded = ref(false);
const activeCriteria = ref<QueryCriteriaGroup>();
const criteriaDraftPending = ref(false);
const criteriaComposerResetKey = ref(0);
const selectedRowKeys = ref<UiDataTableKey[]>([]);
const persistentExternalQueryValues = ref<Record<string, boolean>>({});
const persistentFieldDraftValues = ref<Record<string, unknown[]>>({});
const appliedPersistentFieldValues = ref<Record<string, unknown[]>>({});
const querySummaryValues = ref<WebListQuerySummaryItem[]>([]);
const optionItemsByField = ref<Record<string, import('@muyun/web-contracts').OptionItemDescriptor[]>>({});
const queryOptionItemsByField = ref<Record<string, import('@muyun/web-contracts').OptionItemDescriptor[]>>(
  {},
);
let schemaRequestSeq = 0;
let recordsRequestSeq = 0;

const pages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)));
const queryReady = computed(() => props.ready);
const queryFields = computed(() => schema.value?.fields ?? []);
const queryOptionOptions = computed<Record<string, Option[]>>(() =>
  Object.fromEntries(
    Object.entries(queryOptionItemsByField.value).map(([fieldName, items]) => [
      fieldName,
      items.map((item) => ({ label: item.title, value: item.code, disabled: !item.enabled })),
    ]),
  ),
);
const schemaPersistentQueryControls = computed<ResolvedPageListPersistentQueryControlDescriptor[]>(() =>
  queryFields.value.flatMap((field) => {
    const control = field.persistentControl;
    return control
      ? [
          {
            source: 'FIELD' as const,
            id: control.id,
            title: control.title,
            fieldName: field.name,
            operator: control.operator,
            defaultValues: control.defaultValues ?? [],
          },
        ]
      : [];
  }),
);
/** Page descriptors may customize the control area; field contracts are the universal default. */
const persistentQueryControls = computed(
  () => props.persistentQueryControls ?? schemaPersistentQueryControls.value,
);
const conditionCount = computed(() => criteriaLeafCount(activeCriteria.value));
const persistentExternalQueryControls = computed(() =>
  persistentQueryControls.value.filter(isExternalPersistentQueryControl),
);
const persistentFieldQueryControls = computed(() =>
  persistentQueryControls.value.filter(isFieldPersistentQueryControl),
);
const queryReferenceContexts = computed(() =>
  Object.fromEntries(
    queryFields.value.flatMap((field) => {
      const targetModuleAlias = field.reference?.targetModuleAlias;
      return targetModuleAlias
        ? [
            [
              targetModuleAlias,
              createModuleContext<RecordPickerRecord>({
                moduleAlias: targetModuleAlias,
                http: props.context.http,
                runtimeAccess: 'REFERENCE',
              }),
            ],
          ]
        : [];
    }),
  ),
);
const recycleBinHasRecords = computed<boolean | undefined>(() => {
  const total = recycleBinState.summaryTotal.value;
  return total === undefined ? undefined : total > 0;
});
const recycleBinEnabled = computed(() => hasRecycleBinAbility(props.context));
const canQueryRecycleBinAvailable = computed(() => canQueryRecycleBin(props.context));
const quickSearchEnabled = computed(() => props.queryable && schema.value?.quickSearch.enabled === true);
const quickSearchDisabled = computed(() => !queryReady.value || !quickSearchEnabled.value);
const queryActionsDisabled = computed(() => !queryReady.value);
const criteriaComposition = computed(() => schema.value?.criteriaComposition ?? 'TREE');
const advancedCriteriaExcludedFieldNames = computed(() =>
  criteriaComposition.value === 'FLAT_AND'
    ? persistentFieldQueryControls.value.map((control) => control.fieldName)
    : [],
);
const advancedCriteriaFields = computed(() =>
  queryFields.value.filter((field) => !advancedCriteriaExcludedFieldNames.value.includes(field.name)),
);
const advancedCriteriaVisible = computed(
  () =>
    props.queryable &&
    criteriaComposition.value !== 'NONE' &&
    (criteriaComposition.value !== 'FLAT_AND' || advancedCriteriaFields.value.length > 0),
);
const conditionsDisabled = computed(
  () =>
    !props.queryable ||
    !queryReady.value ||
    queryFields.value.length === 0 ||
    criteriaComposition.value === 'NONE',
);
const criteriaControlTitle = computed(() =>
  criteriaComposition.value === 'FLAT_AND' ? '更多筛选' : '高级筛选',
);
const persistentFieldDraftPending = computed(() =>
  persistentFieldQueryControls.value.some(
    (control) =>
      !sameQueryValues(
        persistentFieldDraftValue(control),
        appliedPersistentFieldValues.value[control.id] ?? [],
      ),
  ),
);
const hasUnappliedQueryDraft = computed(
  () => criteriaDraftPending.value || persistentFieldDraftPending.value,
);
const effectiveExternalQueryValues = computed(() => ({
  ...persistentExternalQueryValues.value,
  ...(props.externalQueryValues ?? {}),
}));
const panelActions = computed<RecordActionItem[]>(() => {
  if (props.mode === 'recycleBin') {
    return [];
  }
  let base: RecordActionItem[];
  if (props.actions && props.actions.length > 0) {
    base = props.actions;
  } else if (!props.standardCrudActions) {
    base = [];
  } else if (props.context.can('create') === true) {
    base = [
      {
        key: 'create',
        actionCode: 'create',
        title: '新建',
        primary: true,
        disabled: !queryReady.value,
      },
    ];
  } else {
    base = [];
  }
  return mergeRecordActions(base, props.extraActions);
});
const batchActionItems = computed<RecordActionItem[]>(() =>
  props.batchActions.map((action) => ({
    ...action,
    disabled: action.disabled === true || selectedRowKeys.value.length === 0,
  })),
);
const selection = computed<UiDataTableSelection | undefined>(() =>
  props.batchActions.length > 0
    ? {
        selectedRowKeys: selectedRowKeys.value,
        preserveSelectedRowKeys: false,
        onChange: (keys) => {
          selectedRowKeys.value = keys;
        },
      }
    : undefined,
);
const hasRowActions = computed(
  () =>
    props.rowActionsVisible &&
    ((props.mode === 'recycleBin' &&
      (props.context.can('recycleBinRestore') === true || props.context.can('recycleBinPurge') === true)) ||
      props.rowActionsOf !== undefined ||
      (props.standardCrudRowActions && standardCrudRowActionsOf().length > 0) ||
      props.extraRowActionsOf !== undefined ||
      Boolean(slots.rowActions)),
);
const hasExpandedRow = computed(() => props.expandedRowKeys.length > 0 || Boolean(slots.expandedRow));
const rows = computed<QueryListRow[]>(() => records.value.map(resolveRow));
const tableColumns = computed<RecordQueryListColumn[]>(() => {
  const base =
    props.columns && props.columns.length > 0
      ? recycleBinColumns(props.columns)
      : recycleBinColumns(resolveRecordQueryListColumns(runtimeListView.value, queryFields.value));
  return mergeColumns(base, props.additionalColumns).map((column) => ({
    ...column,
    optionItems: optionItemsByField.value[column.key] ?? column.optionItems,
  }));
});
const dataTableColumns = computed<UiDataTableColumn[]>(() =>
  tableColumns.value.map((column) => ({
    key: column.key,
    title: column.title,
    width: column.width,
    align: column.align,
  })),
);
onMounted(() => {
  void loadSchemaAndRecords();
});

async function loadListOptionItems() {
  for (const column of tableColumns.value) {
    if (!column.optionBinding || optionItemsByField.value[column.key]) continue;
    try {
      optionItemsByField.value = {
        ...optionItemsByField.value,
        [column.key]: await loadOptionFieldItems(props.context, column.key, column.optionEntityAlias),
      };
    } catch {
      // Keep the persisted value visible while an optional title source is unavailable.
    }
  }
}

async function loadPersistentQueryOptionItems() {
  for (const field of queryFields.value) {
    if (!field.optionBinding || queryOptionItemsByField.value[field.name]) continue;
    try {
      queryOptionItemsByField.value = {
        ...queryOptionItemsByField.value,
        [field.name]: await loadOptionFieldItems(props.context, field.name),
      };
    } catch {
      // A query remains executable when an optional catalog is temporarily unavailable.
    }
  }
}

watch(tableColumns, () => void loadListOptionItems(), { immediate: true });

watch(
  () => props.reloadKey,
  () => refresh(),
);

watch(
  () => props.context,
  () => loadSchemaAndRecords(),
);

watch(
  () => [props.uiConfigId, props.queryTemplateId, props.querySchema, props.ready],
  ([, , , ready]) => {
    pageNum.value = 1;
    if (ready) {
      void loadSchemaAndRecords();
      return;
    }
    records.value = [];
    total.value = 0;
    totalKnown.value = true;
    querySummaryValues.value = [];
    emit('loaded', []);
  },
);

watch(
  () => props.mode,
  () => {
    pageNum.value = 1;
    void loadRecords();
  },
);

watch(
  persistentQueryControls,
  (controls) => {
    const externalDefaults = Object.fromEntries(
      controls
        .filter(isExternalPersistentQueryControl)
        .map((control) => [control.externalCriteriaKey, control.defaultValue]),
    );
    if (!sameBooleanRecord(persistentExternalQueryValues.value, externalDefaults)) {
      persistentExternalQueryValues.value = externalDefaults;
    }
    const defaults = Object.fromEntries(
      controls
        .filter(isFieldPersistentQueryControl)
        .map((control) => [control.id, [...control.defaultValues]]),
    );
    persistentFieldDraftValues.value = defaults;
    appliedPersistentFieldValues.value = defaults;
  },
  { immediate: true },
);

function sameBooleanRecord(left: Record<string, boolean>, right: Record<string, boolean>) {
  const leftEntries = Object.entries(left);
  return (
    leftEntries.length === Object.keys(right).length &&
    leftEntries.every(([key, value]) => right[key] === value)
  );
}

watch([queryFields, persistentFieldQueryControls], () => void loadPersistentQueryOptionItems(), {
  immediate: true,
});

watch(
  effectiveExternalQueryValues,
  () => {
    pageNum.value = 1;
    void loadRecords();
  },
  { deep: true },
);

watch(
  () => props.pageSize,
  (value) => {
    pageSize.value = value;
  },
);

async function loadSchemaAndRecords() {
  if (!queryReady.value) {
    return;
  }
  const requestSeq = ++schemaRequestSeq;
  loading.value = true;
  descriptorLoadError.value = false;
  recordsLoadError.value = undefined;
  try {
    runtimeListView.value = await loadRuntimeListView();
    const nextSchema =
      props.querySchema ??
      (await props.context.crud.querySchema({
        uiConfigId: props.uiConfigId,
        queryTemplateId: props.queryTemplateId,
      }));
    if (requestSeq !== schemaRequestSeq) {
      return;
    }
    schema.value = nextSchema;
    if (
      props.requiredExternalCriteriaKeys.some(
        (key) => !nextSchema.externalCriteria.some((criteria) => criteria.key === key),
      )
    ) {
      descriptorLoadError.value = true;
      records.value = [];
      total.value = 0;
      totalKnown.value = true;
      querySummaryValues.value = [];
      emit('loaded', []);
      return;
    }
    activeCriteria.value = undefined;
    criteriaDraftPending.value = false;
    conditionsExpanded.value = false;
    criteriaComposerResetKey.value += 1;
    await loadRecords(false);
  } catch (cause) {
    if (requestSeq !== schemaRequestSeq) {
      return;
    }
    if (isUnsupportedQuerySchemaError(cause)) {
      schema.value = emptyQuerySchema(props.context.moduleAlias);
      if (props.requiredExternalCriteriaKeys.length > 0) {
        descriptorLoadError.value = true;
        records.value = [];
        total.value = 0;
        querySummaryValues.value = [];
        emit('loaded', []);
        return;
      }
      activeCriteria.value = undefined;
      criteriaDraftPending.value = false;
      conditionsExpanded.value = false;
      criteriaComposerResetKey.value += 1;
      await loadRecords(false);
      return;
    }
    schema.value = undefined;
    records.value = [];
    total.value = 0;
    totalKnown.value = true;
    querySummaryValues.value = [];
    emit('loaded', []);
    recordsLoadError.value = normalizeError(cause).message;
    presentPlatformError(cause, { source: 'record-query-list-panel', phase: 'load' });
  } finally {
    if (requestSeq === schemaRequestSeq) {
      loading.value = false;
    }
  }
}

async function loadRuntimeListView(): Promise<ResolvedViewDescriptor | undefined> {
  if (props.columns && props.columns.length > 0) {
    return undefined;
  }
  try {
    const runtimeContext = await props.context.runtime.ready;
    return runtimeContext.uiDescriptor?.page?.list?.fields;
  } catch (cause) {
    descriptorLoadError.value = true;
    throw cause;
  }
}

async function loadRecords(updateLoading = true) {
  const requestSeq = ++recordsRequestSeq;
  if (!queryReady.value) {
    records.value = [];
    total.value = 0;
    totalKnown.value = true;
    querySummaryValues.value = [];
    emit('loaded', []);
    if (updateLoading) {
      loading.value = false;
    }
    return;
  }
  if (props.mode === 'recycleBin') {
    if (updateLoading) loading.value = true;
    try {
      await recycleBinState.load(buildQueryRequest());
      if (requestSeq !== recordsRequestSeq) return;
      recycleBinItems.clear();
      records.value = recycleBinState.items.value.map((item) => {
        const record = { ...item.record, deletedAt: item.deletedAt };
        const key = recordKey(record);
        recycleBinItems.set(key, item);
        return record;
      });
      total.value = recycleBinState.total.value;
      totalKnown.value = true;
      querySummaryValues.value = [];
      pageNum.value = recycleBinState.pageNum.value;
      pageSize.value = recycleBinState.pageSize.value;
      emit('loaded', records.value);
    } finally {
      if (updateLoading && requestSeq === recordsRequestSeq) loading.value = false;
    }
    return;
  }
  if (updateLoading) {
    loading.value = true;
  }
  try {
    recordsLoadError.value = undefined;
    const request = buildQueryRequest();
    const response = await props.context.crud.query(request);
    if (requestSeq !== recordsRequestSeq) {
      return;
    }
    records.value = response.records;
    preloadRecordActionAvailability(response.records);
    selectedRowKeys.value = reconcileSelectedKeys(
      selectedRowKeys.value,
      response.records.map((record) => recordKey(record)),
    );
    total.value = response.total;
    totalKnown.value = response.totalKnown;
    querySummaryValues.value = response.summaries ?? [];
    pageNum.value = response.pageNum;
    pageSize.value = response.pageSize;
    emit('loaded', response.records);
    emit('queried', request);
    refreshRecycleBinSummary();
  } catch (cause) {
    if (requestSeq !== recordsRequestSeq) {
      return;
    }
    records.value = [];
    total.value = 0;
    totalKnown.value = true;
    querySummaryValues.value = [];
    emit('loaded', []);
    recordsLoadError.value = normalizeError(cause).message;
    presentPlatformError(cause, { source: 'record-query-list-panel', phase: 'load' });
  } finally {
    if (updateLoading && requestSeq === recordsRequestSeq) {
      loading.value = false;
    }
  }
}

function refreshRecycleBinSummary() {
  if (canQueryRecycleBinAvailable.value) {
    void recycleBinState.refreshSummary();
  }
}

function buildQueryRequest(): WebQueryRequest {
  const quickSearch = appliedQuickSearch.value.trim();
  const request: WebQueryRequest = {
    page: { pageNum: pageNum.value, pageSize: pageSize.value },
    sorts: defaultSorts(),
  };
  const criteriaChildren = [...persistentFieldCriteria(), ...activeCriteriaChildren()];
  if (criteriaChildren.length > 0) {
    request.criteria = { kind: 'GROUP', operator: 'AND', children: criteriaChildren };
  }
  if (!props.pageable) {
    delete request.page;
  }
  if (props.uiConfigId) {
    request.uiConfigId = props.uiConfigId;
  }
  if (props.queryTemplateId) {
    request.queryTemplateId = props.queryTemplateId;
  }
  if (quickSearch && quickSearchEnabled.value) {
    request.quickSearch = quickSearch;
    request.quickSearchFields = schema.value?.quickSearch.fields ?? [];
  }
  if (Object.keys(effectiveExternalQueryValues.value).length > 0) {
    request.externalQueryValues = effectiveExternalQueryValues.value;
  }
  return request;
}

function persistentQueryValue(control: ResolvedPageListExternalPersistentQueryControlDescriptor) {
  return persistentExternalQueryValues.value[control.externalCriteriaKey] ?? control.defaultValue;
}

function summaryValue(key: string): string {
  const value = querySummaryValues.value.find((item) => item.key === key)?.value;
  if (value === undefined || value === null) return '—';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

function updatePersistentQueryValue(
  control: ResolvedPageListExternalPersistentQueryControlDescriptor,
  value: boolean,
) {
  persistentExternalQueryValues.value = {
    ...persistentExternalQueryValues.value,
    [control.externalCriteriaKey]: value,
  };
}

function persistentFieldCriteria(): QueryCriteriaCondition[] {
  return persistentFieldQueryControls.value.flatMap((control) => {
    const values = appliedPersistentFieldValues.value[control.id] ?? [];
    return values.length === 0 && !isValueLessQueryOperator(control.operator)
      ? []
      : [{ kind: 'CONDITION', fieldName: control.fieldName, operator: control.operator, values }];
  });
}

function activeCriteriaChildren(): Array<QueryCriteriaCondition | QueryCriteriaGroup> {
  if (!activeCriteria.value) return [];
  return criteriaComposition.value === 'FLAT_AND' ? activeCriteria.value.children : [activeCriteria.value];
}

function isValueLessQueryOperator(operator: QueryOperator) {
  return ['NULL', 'NOT_NULL', 'EMPTY', 'NOT_EMPTY'].includes(operator);
}

function persistentFieldDraftValue(control: ResolvedPageListFieldPersistentQueryControlDescriptor) {
  return persistentFieldDraftValues.value[control.id] ?? [];
}

function persistentFieldOptions(control: ResolvedPageListFieldPersistentQueryControlDescriptor): Option[] {
  return (queryOptionItemsByField.value[control.fieldName] ?? []).map((item) => ({
    label: item.title,
    value: item.code,
    disabled: !item.enabled,
  }));
}

function persistentReferenceContext(control: ResolvedPageListFieldPersistentQueryControlDescriptor) {
  const targetModuleAlias = fieldByName(control.fieldName)?.reference?.targetModuleAlias;
  return targetModuleAlias ? queryReferenceContexts.value[targetModuleAlias] : undefined;
}

function updatePersistentFieldDraftValue(
  control: ResolvedPageListFieldPersistentQueryControlDescriptor,
  values: unknown[],
) {
  persistentFieldDraftValues.value = { ...persistentFieldDraftValues.value, [control.id]: values };
}

function sameQueryValues(left: unknown[], right: unknown[]) {
  return left.length === right.length && left.every((value, index) => Object.is(value, right[index]));
}

function applyPersistentFieldQueries() {
  appliedPersistentFieldValues.value = Object.fromEntries(
    persistentFieldQueryControls.value.map((control) => [control.id, persistentFieldDraftValue(control)]),
  );
  pageNum.value = 1;
  void loadRecords();
}

function resetPersistentFieldQueries() {
  const defaults = Object.fromEntries(
    persistentFieldQueryControls.value.map((control) => [control.id, [...control.defaultValues]]),
  );
  persistentFieldDraftValues.value = defaults;
  appliedPersistentFieldValues.value = defaults;
  pageNum.value = 1;
  void loadRecords();
}

function isExternalPersistentQueryControl(
  control: ResolvedPageListPersistentQueryControlDescriptor,
): control is ResolvedPageListExternalPersistentQueryControlDescriptor {
  return control.source === 'EXTERNAL';
}

function isFieldPersistentQueryControl(
  control: ResolvedPageListPersistentQueryControlDescriptor,
): control is ResolvedPageListFieldPersistentQueryControlDescriptor {
  return control.source === 'FIELD';
}

function defaultSorts(): WebSort[] {
  return (schema.value?.defaultSorts ?? []).map((sort) => ({
    field: sort.field,
    desc: sort.desc,
  }));
}

function emptyQuerySchema(scopeName: string): QuerySchema {
  return {
    scopeName,
    quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
    fields: [],
    externalCriteria: [],
    defaultSorts: [],
  };
}

function isUnsupportedQuerySchemaError(cause: unknown) {
  const error = normalizeError(cause);
  return error.message.includes('query schema is not supported by');
}

function refresh() {
  void loadRecords();
}

function handleAction(action: RecordActionItem, event: MouseEvent) {
  emit('action', action, event);
}

function handleBatchAction(action: RecordActionItem, event: MouseEvent) {
  const selectedRecords = records.value.filter((record) =>
    selectedRowKeys.value.some((key) => String(key) === recordKey(record)),
  );
  if (selectedRecords.length === 0) return;
  emit('batchAction', action, selectedRecords, event, clearSelection);
}

function clearSelection() {
  selectedRowKeys.value = [];
}

function resolveRow(record: QueryListRecord): QueryListRow {
  const recordId = recordActionRecordId(record);
  const configuredActions = rowActions(record)
    .map((action) => rowActionWithState(record, action))
    .map((action) => recordActionAvailabilityState(action, recordId));
  const actions = resolveRecordActions(props.context, configuredActions, false, recordId);
  const primaryActions = actions.filter((action, index) => index === 0 || action.pinned === true);
  const secondaryActions = actions.filter((action, index) => index !== 0 && action.pinned !== true);
  return {
    key: recordKey(record),
    record,
    primaryActions,
    secondaryActions,
    dropdownItems: secondaryActions.map(rowActionDropdownItem),
  };
}

function recordActionRecordId(record: QueryListRecord) {
  const id = record.id;
  return typeof id === 'string' && id.trim() ? id.trim() : undefined;
}

function recordActionAvailabilityState(
  action: RecordActionItem,
  recordId: string | undefined,
): RecordActionItem {
  if (!recordId || !action.actionCode || props.context.runtime.snapshot() === undefined) {
    return action;
  }
  if (props.context.recordActionsSnapshot(recordId) === undefined) {
    return {
      ...action,
      disabled: true,
      disabledReason: action.disabledReason ?? '正在校验操作可用性',
    };
  }
  return action;
}

function preloadRecordActionAvailability(records: QueryListRecord[]) {
  const recordIds = records.flatMap((record) => {
    const id = recordActionRecordId(record);
    return id && rowActions(record).some((action) => action.actionCode != null) ? [id] : [];
  });
  if (recordIds.length === 0 || props.context.recordActionsBatch == null) return;
  void props.context.recordActionsBatch(recordIds).catch(() => {
    // Keep row mutations disabled when availability cannot be resolved. The
    // command endpoint remains authoritative if the UI later retries.
  });
}

function rowActions(record: QueryListRecord): RecordActionItem[] {
  if (props.mode === 'recycleBin') {
    const item = recycleBinItems.get(recordKey(record));
    if (!item) return [];
    return [
      ...(props.context.can('recycleBinRestore') === true
        ? [
            {
              key: 'restore',
              actionCode: 'recycleBinRestore',
              title: '恢复',
              disabled: !item.restorable,
              disabledReason: recycleBinRestoreUnavailableReason(item),
            },
          ]
        : []),
      ...(item.purgeable && props.context.can('recycleBinPurge') === true
        ? [{ key: 'purge', actionCode: 'recycleBinPurge', title: '彻底删除', danger: true }]
        : []),
    ];
  }
  const baseActions = props.rowActionsOf
    ? props.rowActionsOf(record)
    : props.standardCrudRowActions
      ? standardCrudRowActionsOf()
      : [];
  return mergeRecordActions(baseActions, props.extraRowActionsOf?.(record) ?? []);
}

function rowActionWithState(record: QueryListRecord, action: RecordActionItem): RecordActionItem {
  const state = props.rowActionStateOf?.(record, action);
  return state ? { ...action, ...state } : action;
}

function standardCrudRowActionsOf(): RecordActionItem[] {
  const actions: Array<RecordActionItem & { key: StandardCrudRowActionKey }> = [
    { key: 'view', actionCode: props.standardCrudRowActionCodes.view ?? 'view', title: '查看' },
    {
      key: 'edit',
      actionCode: props.standardCrudRowActionCodes.edit ?? 'update',
      title: '修改',
      iconName: 'edit',
    },
    {
      key: 'delete',
      actionCode: props.standardCrudRowActionCodes.delete ?? 'delete',
      title: '删除',
      iconName: 'delete',
      danger: true,
    },
  ];
  return actions.filter(
    (action) =>
      props.standardCrudRowActionKeys.includes(action.key) &&
      action.actionCode != null &&
      props.context.can(action.actionCode) === true,
  );
}

function rowActionDropdownItem(action: ResolvedRecordActionItem): UiDropdownItem {
  return {
    key: action.key,
    title: action.title,
    disabled: action.disabled,
    danger: action.danger,
  };
}

async function handlePrimaryRowAction(
  row: QueryListRow,
  action: ResolvedRecordActionItem,
  event: MouseEvent,
) {
  if (action.disabled) {
    return;
  }
  if (await handleRecycleBinAction(row, action)) return;
  emit('rowAction', action, row.record, event);
}

async function handleSecondaryRowAction(row: QueryListRow, key: string) {
  const action = row.secondaryActions.find((item) => item.key === key);
  if (!action || action.disabled) {
    return;
  }
  if (await handleRecycleBinAction(row, action)) return;
  emit('rowAction', action, row.record);
}

async function handleRecycleBinAction(row: QueryListRow, action: ResolvedRecordActionItem) {
  if (props.mode !== 'recycleBin') return false;
  const item = recycleBinItems.get(row.key);
  if (!item) return true;
  const title = recycleBinState.recordTitleOf(item);
  if (action.key === 'restore') {
    const confirmed = await confirmAction({
      title: '恢复记录',
      content: `确认恢复「${title}」？`,
      okText: '恢复',
    });
    if (confirmed && (await recycleBinState.restore(item, false))) {
      emit('restored');
      await loadRecords();
    }
    return true;
  }
  if (action.key === 'purge') {
    const confirmed = await confirmAction({
      title: '彻底删除',
      content: `彻底删除后数据不可恢复。确认彻底删除「${title}」？`,
      okText: '彻底删除',
      danger: true,
      requiredText: title,
    });
    if (confirmed && (await recycleBinState.purge(item, false))) {
      await loadRecords();
    }
    return true;
  }
  return false;
}

function recycleBinColumns(columns: RecordQueryListColumn[]) {
  if (props.mode !== 'recycleBin' || columns.some((column) => column.key === 'deletedAt')) return columns;
  return [...columns, { key: 'deletedAt', title: '删除时间', type: 'datetime' as const, width: '170px' }];
}

function mergeColumns(
  baseColumns: RecordQueryListColumn[],
  additions: Array<RecordQueryListColumn & { before?: string; after?: string }>,
) {
  const merged = [...baseColumns];
  for (const column of additions) {
    if (merged.some((item) => item.key === column.key)) {
      throw new Error(`列表列重复：${column.key}`);
    }
    const beforeIndex = column.before ? merged.findIndex((item) => item.key === column.before) : -1;
    if (beforeIndex >= 0) {
      merged.splice(beforeIndex, 0, column);
      continue;
    }
    const afterIndex = column.after ? merged.findIndex((item) => item.key === column.after) : -1;
    if (afterIndex >= 0) {
      merged.splice(afterIndex + 1, 0, column);
      continue;
    }
    merged.push(column);
  }
  return merged;
}

function cellComponentFor(key: string) {
  return props.cellComponents.find((cell) => cell.key === key)?.component;
}

function handleTableRowClick(row: QueryListRow) {
  emit('select', row.record);
}

function handleTableRowDblclick(row: QueryListRow, event: MouseEvent) {
  emit('rowDblclick', row.record, event);
}

function handleTableRowExpand(row: QueryListRow, expanded: boolean) {
  emit('rowExpand', row.record, expanded);
}

function submitQuickSearch(value = quickSearchKeyword.value) {
  quickSearchKeyword.value = value;
  appliedQuickSearch.value = value;
  pageNum.value = 1;
  void loadRecords();
}

function handleQuickSearchInput(value: string) {
  quickSearchKeyword.value = value;
}

function toggleConditions() {
  if (conditionsDisabled.value) {
    return;
  }
  conditionsExpanded.value = !conditionsExpanded.value;
}

function applyCriteria(criteria: QueryCriteriaGroup | undefined) {
  activeCriteria.value = criteria;
  criteriaDraftPending.value = false;
  pageNum.value = 1;
  void loadRecords();
}

function clearCriteria() {
  activeCriteria.value = undefined;
  criteriaDraftPending.value = false;
  pageNum.value = 1;
  void loadRecords();
}

function fieldByName(fieldName?: string) {
  return queryFields.value.find((field) => field.name === fieldName);
}

function criteriaLeafCount(criteria: QueryCriteriaGroup | undefined): number {
  if (!criteria) return 0;
  return criteria.children.reduce(
    (count, child) => count + (child.kind === 'CONDITION' ? 1 : criteriaLeafCount(child)),
    0,
  );
}

function recordKey(record: QueryListRecord) {
  return String(record[props.rowKey] ?? record.id ?? '');
}

function goPage(nextPage: number) {
  pageNum.value = Math.min(Math.max(1, nextPage), pages.value);
  void loadRecords();
}

function handlePageSizeChange(nextPageSize: number) {
  pageSize.value = nextPageSize;
  emit('pageSizeChange', nextPageSize);
  pageNum.value = 1;
  void loadRecords();
}

defineExpose({ clearSelection, refresh });
</script>

<template>
  <RecordQueryListSurface
    class="record-query-list-panel"
    :inert="navigationDisabled || undefined"
    :aria-disabled="navigationDisabled || undefined"
    :embedded="embedded"
    :chrome-free="!headerVisible && !pageable && !showRecycleBin"
    :header-visible="headerVisible"
    :show-title="showTitle"
    :title="title"
    :subtitle="subtitle"
    :title-action-icon="showTitle && refreshable ? 'reload' : undefined"
    :title-action-title="showTitle ? (refreshTitle ?? `刷新${title}`) : undefined"
    :title-action-disabled="queryActionsDisabled"
    :quick-search-visible="quickSearchEnabled"
    :quick-search-value="quickSearchKeyword"
    :quick-search-placeholder="quickSearchPlaceholder"
    :quick-search-disabled="quickSearchDisabled"
    :columns="dataTableColumns"
    :rows="rows"
    :row-key="(row) => String((row as QueryListRow).key ?? '')"
    :selection="selection"
    :selected-row-key="selectedKey"
    :expanded-row-keys="expandedRowKeys"
    clickable-rows
    fill-height
    horizontal-scroll
    :row-muted="(row) => (row as QueryListRow).record.enabled === false"
    :show-action-column="hasRowActions"
    :action-column-title="rowActionsTitle"
    :action-column-width="actionColumnWidth"
    :table-visible="!loading && queryReady && !descriptorLoadError && !recordsLoadError && records.length > 0"
    :pageable="pageable"
    :total="total"
    :total-known="totalKnown"
    :page-num="pageNum"
    :pages="pages"
    :page-size="pageSize"
    :page-size-options="pageSizeOptions"
    :pagination-disabled="queryActionsDisabled"
    @title-action="refresh"
    @update:quick-search-value="handleQuickSearchInput"
    @quick-search="submitQuickSearch"
    @row-click="handleTableRowClick($event as QueryListRow)"
    @row-dblclick="(row, event) => handleTableRowDblclick(row as QueryListRow, event)"
    @row-expand="(row, expanded) => handleTableRowExpand(row as QueryListRow, expanded)"
    @page-change="goPage"
    @page-size-change="handlePageSizeChange"
  >
    <template #operations>
      <UiButton
        v-if="!showTitle"
        type="text"
        icon-name="reload"
        :disabled="queryActionsDisabled"
        :aria-label="refreshTitle ?? `刷新${title}`"
        :title="refreshTitle ?? `刷新${title}`"
        @click="refresh"
      />
      <RecordActionBar
        v-if="panelActions.length > 0"
        :context="context"
        :actions="panelActions"
        @action="handleAction"
      />
      <RecordActionBar
        v-if="batchActionItems.length > 0"
        :context="context"
        :actions="batchActionItems"
        size="compact"
        @action="(action, event) => handleBatchAction(action, event)"
      />
      <slot name="toolbarActions" :refresh="refresh" />
    </template>
    <template #persistentQueries>
      <UiCheckbox
        v-for="control in persistentExternalQueryControls"
        :key="control.id"
        class="record-query-list-persistent-query-control"
        :checked="persistentQueryValue(control)"
        :disabled="queryActionsDisabled"
        @change="updatePersistentQueryValue(control, $event)"
      >
        {{ control.title }}
      </UiCheckbox>
      <div
        v-for="control in persistentFieldQueryControls"
        :key="control.id"
        class="record-query-list-persistent-field-control"
      >
        <span class="record-query-list-persistent-field-label">{{ control.title }}</span>
        <QueryValueEditor
          v-if="fieldByName(control.fieldName)"
          :field="fieldByName(control.fieldName)!"
          :operator="control.operator"
          :values="persistentFieldDraftValue(control)"
          :options="persistentFieldOptions(control)"
          :reference-context="persistentReferenceContext(control)"
          :disabled="queryActionsDisabled"
          @submit="applyPersistentFieldQueries"
          @update:values="updatePersistentFieldDraftValue(control, $event)"
        />
        <span v-else class="record-query-list-persistent-field-error">字段不可用</span>
      </div>
      <div v-if="persistentFieldQueryControls.length > 0" class="record-query-list-persistent-field-actions">
        <UiButton type="primary" :disabled="queryActionsDisabled" @click="applyPersistentFieldQueries">
          查询
        </UiButton>
        <UiButton type="text" :disabled="queryActionsDisabled" @click="resetPersistentFieldQueries">
          重置
        </UiButton>
      </div>
    </template>
    <template #queryControls>
      <UiButton
        v-if="advancedCriteriaVisible"
        class="record-query-list-advanced"
        :class="{ 'is-selected': conditionsExpanded }"
        type="text"
        icon-name="filter"
        :disabled="conditionsDisabled"
        @click="toggleConditions"
      >
        {{ criteriaControlTitle }}<span v-if="conditionCount"> {{ conditionCount }}</span>
      </UiButton>
      <span v-if="conditionCount" class="record-query-list-query-state" role="status">
        已应用 {{ conditionCount }} 条筛选
      </span>
      <span v-if="hasUnappliedQueryDraft" class="record-query-list-query-state is-pending" role="status">
        筛选草稿尚未应用
      </span>
    </template>

    <template #conditions>
      <section v-if="conditionsExpanded" class="record-query-conditions">
        <QueryCriteriaComposer
          :key="criteriaComposerResetKey"
          :fields="queryFields"
          :excluded-field-names="advancedCriteriaExcludedFieldNames"
          :option-items-by-field="queryOptionOptions"
          :reference-contexts="queryReferenceContexts"
          :disabled="conditionsDisabled"
          :composition="criteriaComposition === 'FLAT_AND' ? 'FLAT_AND' : 'TREE'"
          @apply="applyCriteria"
          @clear="clearCriteria"
          @draft-change="criteriaDraftPending = $event"
          @validation="presentPlatformMessage($event, { phase: 'validation' })"
        />
      </section>
    </template>

    <template #beforeTable>
      <UiSpin v-if="loading" tip="加载列表" />
      <UiEmpty v-else-if="!queryReady" :description="waitingDescription" />
      <div v-else-if="descriptorLoadError || recordsLoadError" role="alert">
        {{ descriptorLoadError ? '列表声明加载失败，请稍后重试' : recordsLoadError }}
        <UiButton @click="loadSchemaAndRecords()">重试</UiButton>
      </div>
      <UiEmpty v-else-if="records.length === 0" :description="emptyDescription" />
    </template>
    <template #cell="{ column, record }">
      <component
        :is="cellComponentFor(column.key)"
        v-if="cellComponentFor(column.key)"
        :record="(record as QueryListRow).record"
        :column="tableColumns.find((item) => item.key === column.key)"
      />
      <slot
        v-else-if="$slots.cell"
        name="cell"
        :column="tableColumns.find((item) => item.key === column.key)!"
        :record="(record as QueryListRow).record"
      />
      <RecordQueryListCell
        v-else
        :record="(record as QueryListRow).record"
        :column="tableColumns.find((item) => item.key === column.key)!"
        :cell-renderers="cellRenderers"
      />
    </template>
    <template #rowActions="{ record }">
      <div
        class="record-query-list-row-actions"
        :style="{
          width: typeof actionColumnWidth === 'number' ? `${actionColumnWidth}px` : actionColumnWidth,
        }"
        @click.stop
        @dblclick.stop
      >
        <slot name="rowActions" :record="(record as QueryListRow).record" />
        <div class="record-query-list-primary-actions">
          <UiButton
            v-for="action in (record as QueryListRow).primaryActions"
            :key="action.key"
            class="record-query-list-primary-action"
            type="text"
            :disabled="action.disabled"
            :icon-name="action.iconName"
            :title="action.disabled ? (action.disabledReason ?? action.reason ?? action.title) : action.title"
            @click="handlePrimaryRowAction(record as QueryListRow, action, $event)"
          >
            {{ action.title }}
          </UiButton>
        </div>
        <UiDropdown
          v-if="(record as QueryListRow).secondaryActions.length > 0"
          :items="(record as QueryListRow).dropdownItems"
          trigger="hover"
          @select="handleSecondaryRowAction(record as QueryListRow, $event)"
        >
          <UiButton
            class="record-query-list-more-action"
            type="text"
            icon-name="down"
            title="更多"
            aria-label="更多"
          />
        </UiDropdown>
      </div>
    </template>
    <template v-if="hasExpandedRow" #expandedRow="{ record }">
      <slot
        name="expandedRow"
        :record="(record as QueryListRow).record"
        :row-key="String(record.key ?? '')"
      />
    </template>

    <template
      v-if="(showRecycleBin && recycleBinEnabled) || (mode !== 'recycleBin' && querySummaries.length > 0)"
      #footer
    >
      <RecycleBinModeButton
        v-if="showRecycleBin && recycleBinEnabled && (mode === 'recycleBin' || canQueryRecycleBinAvailable)"
        :active="mode === 'recycleBin'"
        :has-records="recycleBinHasRecords"
        :count="recycleBinState.summaryTotal.value"
        @click="emit('modeChange', mode === 'normal' ? 'recycleBin' : 'normal')"
      />
      <div v-if="mode !== 'recycleBin' && querySummaries.length > 0" class="record-query-list-summaries">
        <span v-for="summary in querySummaries" :key="summary.key" class="record-query-list-summary">
          <span v-if="summary.source !== 'GROUPED'" class="record-query-list-summary-title">{{
            summary.title
          }}</span>
          <QueryGroupedSummary
            v-if="summary.source === 'GROUPED'"
            :key="`${context.moduleAlias}:${summary.key}`"
            :loading="loading"
            :error="Boolean(descriptorLoadError || recordsLoadError)"
            :ready="queryReady"
            :title="summary.title"
            :group-by-title="summary.groupByTitle"
            :sum-field-title="summary.sumFieldTitle"
            :value="querySummaryValues.find((item) => item.key === summary.key)?.value"
          />
          <span v-else class="record-query-list-summary-value">{{ summaryValue(summary.key) }}</span>
        </span>
      </div>
    </template>
  </RecordQueryListSurface>
</template>

<style scoped>
.record-query-list-panel[inert] {
  opacity: 0.55;
}

.record-query-condition-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.record-query-list-summaries {
  display: inline-flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
  max-width: 100%;
  flex-wrap: wrap;
}

.record-query-list-summary {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  min-width: 0;
  color: var(--muyun-text-muted);
  font-size: 13px;
  white-space: nowrap;
}

.record-query-list-summary-value {
  color: var(--muyun-text);
  font-weight: 400;
}

:deep(.record-query-list-persistent-query-control.ant-checkbox-wrapper) {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  margin-inline-end: 0;
  color: var(--muyun-text-muted);
  font-size: 13px;
  white-space: nowrap;
}

.record-query-list-persistent-field-control {
  display: inline-grid;
  grid-template-columns: auto minmax(180px, 1fr);
  align-items: center;
  gap: 6px;
  min-width: min(360px, 100%);
}

.record-query-list-persistent-field-label {
  color: var(--muyun-text-muted);
  font-size: 13px;
  white-space: nowrap;
}

.record-query-list-persistent-field-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.record-query-list-persistent-field-error {
  color: var(--muyun-danger);
  font-size: 13px;
}

.record-query-list-query-state {
  color: var(--muyun-text-muted);
  font-size: 13px;
  white-space: nowrap;
}

.record-query-list-query-state.is-pending {
  color: var(--muyun-warning);
}

:deep(.record-query-list-advanced.is-selected.ant-btn) {
  border: 1px solid var(--muyun-theme-border);
  background: var(--muyun-selected);
  color: var(--muyun-theme-base);
}

:deep(.record-query-list-advanced.is-selected.ant-btn:hover) {
  border-color: var(--muyun-theme-hover);
  background: var(--muyun-theme-focus);
  color: var(--muyun-theme-base);
}

.record-query-conditions {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  background: var(--muyun-hover-subtle);
}

.record-query-list-row-actions {
  position: relative;
  display: flex;
  align-items: center;
  width: 92px;
  white-space: nowrap;
}

.record-query-list-primary-actions {
  display: flex;
  width: 100%;
  justify-content: center;
  min-width: 0;
}

.record-query-list-row-actions :deep(.ui-dropdown) {
  position: absolute;
  right: 0;
}

.record-query-list-row-actions :deep(.ant-btn) {
  min-width: 0;
  height: 24px;
  padding: 0 4px;
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.record-query-list-row-actions :deep(.ant-btn:hover),
.record-query-list-row-actions :deep(.ant-btn:focus-visible) {
  color: var(--muyun-primary);
}

.record-query-list-primary-action :deep(.ant-btn-icon) {
  display: none;
}

.record-query-list-more-action {
  width: 24px;
  opacity: 0;
  transition: opacity 0.14s ease;
}

.record-query-list-panel :deep(.ant-table-tbody > tr:hover .record-query-list-more-action),
.record-query-list-row-actions:focus-within .record-query-list-more-action {
  opacity: 1;
}

@media (max-width: 680px) {
  :deep(.query-criteria-condition-row) {
    grid-template-columns: 1fr;
  }
}
</style>
