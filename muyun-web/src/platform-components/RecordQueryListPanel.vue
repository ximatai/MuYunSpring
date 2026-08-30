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
import { computed, onMounted, ref, watch } from 'vue';
import {
  confirmAction,
  UiButton,
  UiCheckbox,
  UiDataTable,
  UiDropdown,
  UiEmpty,
  UiInput,
  UiSearchInput,
  UiSelect,
  UiSpin,
} from '@muyun/vue-ui-antdv';
import type {
  UiDataTableColumn,
  UiDataTableKey,
  UiDataTableSelection,
  UiDropdownItem,
} from '@muyun/vue-ui-antdv';
import type {
  Option,
  OptionValue,
  OptionValueList,
  QueryOperator,
  QuerySchema,
  QuerySchemaField,
  ResolvedPageListPersistentQueryControlDescriptor,
  ResolvedViewDescriptor,
  WebQueryCondition,
  WebQueryRequest,
  WebSort,
  RecycleBinItem,
  ResolvedPageListQuerySummaryDescriptor,
  WebListQuerySummaryItem,
} from '@muyun/web-contracts';
import {
  canQueryRecycleBin,
  hasRecycleBinAbility,
  normalizeError,
  type ModuleContext,
} from '@muyun/web-core';
import { presentPlatformError, presentPlatformMessage } from './platformErrorFeedback';
import ManagementPanelHeader from './ManagementPanelHeader.vue';
import RecordActionBar from './RecordActionBar.vue';
import RecordQueryListCell from './RecordQueryListCell.vue';
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

defineOptions({ name: 'RecordQueryListPanel' });

interface ConditionDraft {
  key: number;
  fieldName?: string;
  operator?: QueryOperator;
  rawValue: string;
  booleanValue?: OptionValue | null;
}

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
    persistentQueryControls: () => [],
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
const pageNum = ref(1);
const pageSize = ref(props.pageSize);
const runtimeListView = ref<ResolvedViewDescriptor>();
const descriptorLoadError = ref(false);
const quickSearchKeyword = ref('');
const appliedQuickSearch = ref('');
const conditionsExpanded = ref(false);
const conditionSeq = ref(0);
const conditionDrafts = ref<ConditionDraft[]>([]);
const activeConditions = ref<WebQueryCondition[]>([]);
const selectedRowKeys = ref<UiDataTableKey[]>([]);
const persistentQueryValues = ref<Record<string, boolean>>({});
const querySummaryValues = ref<WebListQuerySummaryItem[]>([]);
let schemaRequestSeq = 0;
let recordsRequestSeq = 0;

const pages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)));
const queryReady = computed(() => props.ready);
const queryFields = computed(() => schema.value?.fields ?? []);
const fieldOptions = computed<Option[]>(() =>
  queryFields.value.map((field) => ({
    label: field.title ?? field.name,
    value: field.name,
  })),
);
const conditionCount = computed(() => activeConditions.value.length);
const recycleBinHasRecords = computed<boolean | undefined>(() => {
  const total = recycleBinState.summaryTotal.value;
  return total === undefined ? undefined : total > 0;
});
const recycleBinEnabled = computed(() => hasRecycleBinAbility(props.context));
const canQueryRecycleBinAvailable = computed(() => canQueryRecycleBin(props.context));
const quickSearchEnabled = computed(() => props.queryable && schema.value?.quickSearch.enabled === true);
const quickSearchDisabled = computed(() => !queryReady.value || !quickSearchEnabled.value);
const queryActionsDisabled = computed(() => !queryReady.value);
const conditionsDisabled = computed(
  () => !props.queryable || !queryReady.value || queryFields.value.length === 0,
);
const effectiveExternalQueryValues = computed(() => ({
  ...persistentQueryValues.value,
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
  return mergeColumns(base, props.additionalColumns);
});
const dataTableColumns = computed<UiDataTableColumn[]>(() =>
  tableColumns.value.map((column) => ({
    key: column.key,
    title: column.title,
    width: column.width,
    align: column.align,
  })),
);
const pageSizeOptions = computed<Option[]>(() =>
  props.pageSizeOptions.map((value) => ({ label: `${value} 条/页`, value })),
);
const booleanOptions: Option[] = [
  { label: '是', value: 'true' },
  { label: '否', value: 'false' },
];

onMounted(() => {
  void loadSchemaAndRecords();
});

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
  () => props.persistentQueryControls,
  (controls) => {
    persistentQueryValues.value = Object.fromEntries(
      controls.map((control) => [control.externalCriteriaKey, control.defaultValue]),
    );
  },
  { immediate: true },
);

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
      querySummaryValues.value = [];
      emit('loaded', []);
      return;
    }
    activeConditions.value = [];
    conditionsExpanded.value = false;
    resetConditionDrafts();
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
      activeConditions.value = [];
      conditionsExpanded.value = false;
      resetConditionDrafts();
      await loadRecords(false);
      return;
    }
    schema.value = undefined;
    records.value = [];
    total.value = 0;
    querySummaryValues.value = [];
    emit('loaded', []);
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
    const response = await props.context.crud.query(buildQueryRequest());
    if (requestSeq !== recordsRequestSeq) {
      return;
    }
    records.value = response.records;
    preloadRecordActionAvailability(response.records);
    selectedRowKeys.value = selectedRowKeys.value.filter((key) =>
      response.records.some((record) => recordKey(record) === String(key)),
    );
    total.value = response.total;
    querySummaryValues.value = response.summaries ?? [];
    pageNum.value = response.pageNum;
    pageSize.value = response.pageSize;
    emit('loaded', response.records);
    refreshRecycleBinSummary();
  } catch (cause) {
    if (requestSeq !== recordsRequestSeq) {
      return;
    }
    records.value = [];
    total.value = 0;
    querySummaryValues.value = [];
    emit('loaded', []);
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
    conditions: activeConditions.value,
    sorts: defaultSorts(),
  };
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

function persistentQueryValue(control: ResolvedPageListPersistentQueryControlDescriptor) {
  return persistentQueryValues.value[control.externalCriteriaKey] ?? control.defaultValue;
}

function summaryValue(key: string): string {
  const value = querySummaryValues.value.find((item) => item.key === key)?.value;
  if (value === undefined || value === null) return '—';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

function updatePersistentQueryValue(
  control: ResolvedPageListPersistentQueryControlDescriptor,
  value: boolean,
) {
  persistentQueryValues.value = {
    ...persistentQueryValues.value,
    [control.externalCriteriaKey]: value,
  };
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

function addCondition() {
  if (conditionsDisabled.value) {
    return;
  }
  conditionDrafts.value.push(createConditionDraft());
}

function removeCondition(key: number) {
  conditionDrafts.value = conditionDrafts.value.filter((draft) => draft.key !== key);
  if (conditionDrafts.value.length === 0) {
    conditionDrafts.value.push(createConditionDraft());
  }
}

function applyConditions() {
  if (conditionsDisabled.value) {
    return;
  }
  const validationMessage = validateConditionDrafts();
  if (validationMessage) {
    presentPlatformMessage(validationMessage, { phase: 'validation' });
    return;
  }
  activeConditions.value = conditionDrafts.value.flatMap(conditionOfDraft);
  pageNum.value = 1;
  void loadRecords();
}

function clearConditions() {
  activeConditions.value = [];
  resetConditionDrafts();
  pageNum.value = 1;
  void loadRecords();
}

function resetConditionDrafts() {
  conditionDrafts.value = [createConditionDraft()];
}

function createConditionDraft(): ConditionDraft {
  conditionSeq.value += 1;
  return {
    key: conditionSeq.value,
    fieldName: queryFields.value[0]?.name,
    operator: queryFields.value[0]?.defaultOperator ?? queryFields.value[0]?.operators[0],
    rawValue: '',
    booleanValue: null,
  };
}

function handleFieldChange(draft: ConditionDraft, fieldName: OptionValue | OptionValueList | null) {
  const field = fieldByName(String(singleOptionValue(fieldName) ?? ''));
  draft.fieldName = field?.name;
  draft.operator = field?.defaultOperator ?? field?.operators[0];
  draft.rawValue = '';
  draft.booleanValue = null;
}

function handleOperatorChange(draft: ConditionDraft, operator: OptionValue | OptionValueList | null) {
  draft.operator = String(singleOptionValue(operator) ?? '') as QueryOperator;
}

function handleBooleanValueChange(draft: ConditionDraft, value: OptionValue | OptionValueList | null) {
  draft.booleanValue = singleOptionValue(value) ?? null;
}

function conditionOfDraft(draft: ConditionDraft): WebQueryCondition[] {
  const field = fieldByName(draft.fieldName);
  const operator = draft.operator ?? field?.defaultOperator;
  if (!field || !operator) {
    return [];
  }
  const values = valuesOfDraft(field, operator, draft);
  if (!valueLessOperator(operator) && values.length === 0) {
    return [];
  }
  return [{ fieldName: field.name, operator, values }];
}

function validateConditionDrafts() {
  for (const draft of conditionDrafts.value) {
    const field = fieldByName(draft.fieldName);
    const operator = draft.operator ?? field?.defaultOperator;
    if (!field || !operator || valueLessOperator(operator)) {
      continue;
    }
    if (operator === 'BETWEEN' && valuesOfDraft(field, operator, draft).length !== 2) {
      return `${field.title ?? field.name} 需要填写起始和结束两个值`;
    }
  }
  return undefined;
}

function valuesOfDraft(field: QuerySchemaField, operator: QueryOperator, draft: ConditionDraft): unknown[] {
  if (valueLessOperator(operator)) {
    return [];
  }
  if (field.valueType === 'BOOLEAN') {
    if (draft.booleanValue !== 'true' && draft.booleanValue !== 'false') {
      return [];
    }
    return [draft.booleanValue === 'true'];
  }
  const raw = draft.rawValue.trim();
  if (!raw) {
    return [];
  }
  if (operator === 'IN' || operator === 'NOT_IN' || operator === 'BETWEEN') {
    return raw
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
  }
  return [raw];
}

function valueLessOperator(operator: QueryOperator) {
  return operator === 'NULL' || operator === 'NOT_NULL';
}

function operatorOptions(draft: ConditionDraft): Option[] {
  const field = fieldByName(draft.fieldName);
  return (field?.operators ?? []).map((operator) => ({
    label: operatorLabel(operator),
    value: operator,
  }));
}

function fieldByName(fieldName?: string) {
  return queryFields.value.find((field) => field.name === fieldName);
}

function operatorLabel(operator: QueryOperator) {
  const labels: Record<QueryOperator, string> = {
    EQ: '等于',
    NOT_EQUAL: '不等于',
    LIKE: '包含',
    IN: '属于',
    NOT_IN: '不属于',
    GT: '大于',
    GTE: '大于等于',
    LT: '小于',
    LTE: '小于等于',
    BETWEEN: '介于',
    NULL: '为空',
    NOT_NULL: '不为空',
  };
  return labels[operator] ?? operator;
}

function conditionPlaceholder(draft: ConditionDraft) {
  if (draft.operator === 'BETWEEN') {
    return '起始, 结束';
  }
  if (draft.operator === 'IN' || draft.operator === 'NOT_IN') {
    return '多个值用逗号分隔';
  }
  return '请输入条件值';
}

function recordKey(record: QueryListRecord) {
  return String(record[props.rowKey] ?? record.id ?? '');
}

function goPage(nextPage: number) {
  pageNum.value = Math.min(Math.max(1, nextPage), pages.value);
  void loadRecords();
}

function handlePageSizeChange(value: OptionValue | OptionValueList | null) {
  const pageSizeValue = singleOptionValue(value);
  const nextPageSize =
    typeof pageSizeValue === 'number' ? pageSizeValue : Number(pageSizeValue ?? props.pageSize);
  pageSize.value = nextPageSize;
  emit('pageSizeChange', nextPageSize);
  pageNum.value = 1;
  void loadRecords();
}

function singleOptionValue(value: OptionValue | OptionValueList | null) {
  return Array.isArray(value) ? undefined : value;
}

defineExpose({ clearSelection, refresh });
</script>

<template>
  <main
    class="record-query-list-panel"
    :class="{
      'is-embedded': embedded,
      'is-chrome-free': !headerVisible && !pageable && !showRecycleBin,
    }"
  >
    <ManagementPanelHeader
      v-if="headerVisible"
      class="record-query-list-header"
      :title="showTitle ? title : ''"
      :subtitle="showTitle ? subtitle : undefined"
      :title-action-icon="showTitle ? 'reload' : undefined"
      :title-action-title="showTitle ? (refreshTitle ?? `刷新${title}`) : undefined"
      :title-action-disabled="queryActionsDisabled"
      @title-action="refresh"
    >
      <template #actions>
        <div class="record-query-list-actions">
          <div class="record-query-list-operation-actions">
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
          </div>
          <div class="record-query-list-query-actions">
            <UiSearchInput
              v-if="queryable"
              :value="quickSearchKeyword"
              class="record-query-list-search"
              :disabled="quickSearchDisabled"
              :placeholder="quickSearchPlaceholder"
              @update:value="handleQuickSearchInput"
              @search="submitQuickSearch"
            />
            <UiCheckbox
              v-for="control in persistentQueryControls"
              :key="control.externalCriteriaKey"
              class="record-query-list-persistent-query-control"
              :checked="persistentQueryValue(control)"
              :disabled="queryActionsDisabled"
              @change="updatePersistentQueryValue(control, $event)"
            >
              {{ control.title }}
            </UiCheckbox>
            <UiButton
              v-if="queryable"
              class="record-query-list-advanced"
              :class="{ 'is-selected': conditionsExpanded }"
              type="text"
              icon-name="filter"
              :disabled="conditionsDisabled"
              @click="toggleConditions"
            >
              高级<span v-if="conditionCount"> {{ conditionCount }}</span>
            </UiButton>
          </div>
        </div>
      </template>
    </ManagementPanelHeader>

    <section v-if="conditionsExpanded" class="record-query-conditions">
      <div v-for="draft in conditionDrafts" :key="draft.key" class="record-query-condition-row">
        <UiSelect
          class="record-query-condition-field"
          :value="draft.fieldName"
          :options="fieldOptions"
          placeholder="字段"
          @update:value="handleFieldChange(draft, $event)"
        />
        <UiSelect
          class="record-query-condition-operator"
          :value="draft.operator"
          :options="operatorOptions(draft)"
          placeholder="关系"
          @update:value="handleOperatorChange(draft, $event)"
        />
        <UiSelect
          v-if="fieldByName(draft.fieldName)?.valueType === 'BOOLEAN' && !valueLessOperator(draft.operator!)"
          class="record-query-condition-value"
          :value="draft.booleanValue"
          :options="booleanOptions"
          placeholder="选择"
          @update:value="handleBooleanValueChange(draft, $event)"
        />
        <UiInput
          v-else-if="!valueLessOperator(draft.operator!)"
          v-model:value="draft.rawValue"
          class="record-query-condition-value"
          :placeholder="conditionPlaceholder(draft)"
        />
        <div v-else class="record-query-condition-value muted">无需输入值</div>
        <UiButton type="text" icon-name="delete" danger @click="removeCondition(draft.key)" />
      </div>
      <div class="record-query-condition-actions">
        <UiButton type="dashed" icon-name="plus" :disabled="conditionsDisabled" @click="addCondition">
          添加条件
        </UiButton>
        <UiButton type="primary" :disabled="conditionsDisabled" @click="applyConditions">应用条件</UiButton>
        <UiButton type="text" :disabled="conditionsDisabled" @click="clearConditions">重置</UiButton>
      </div>
    </section>

    <section class="record-query-list-body">
      <UiSpin v-if="loading" tip="加载列表" />
      <UiEmpty v-else-if="!queryReady" :description="waitingDescription" />
      <UiEmpty v-else-if="descriptorLoadError" description="列表声明加载失败，请稍后重试" />
      <UiEmpty v-else-if="records.length === 0" :description="emptyDescription" />
      <UiDataTable
        v-else
        class="record-query-list-table"
        :columns="dataTableColumns"
        :rows="rows"
        :row-key="(row) => String(row.key ?? '')"
        :pagination="false"
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
        @row-click="handleTableRowClick($event as QueryListRow)"
        @row-dblclick="(row, event) => handleTableRowDblclick(row as QueryListRow, event)"
        @row-expand="(row, expanded) => handleTableRowExpand(row as QueryListRow, expanded)"
      >
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
                :title="
                  action.disabled ? (action.disabledReason ?? action.reason ?? action.title) : action.title
                "
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
      </UiDataTable>
    </section>

    <footer v-if="pageable || (showRecycleBin && recycleBinEnabled)" class="record-query-list-pagination">
      <RecycleBinModeButton
        v-if="showRecycleBin && recycleBinEnabled && (mode === 'recycleBin' || canQueryRecycleBinAvailable)"
        :active="mode === 'recycleBin'"
        :has-records="recycleBinHasRecords"
        :count="recycleBinState.summaryTotal.value"
        @click="emit('modeChange', mode === 'normal' ? 'recycleBin' : 'normal')"
      />
      <div v-if="mode !== 'recycleBin' && querySummaries.length > 0" class="record-query-list-summaries">
        <span v-for="summary in querySummaries" :key="summary.key" class="record-query-list-summary">
          <span class="record-query-list-summary-title">{{ summary.title }}</span>
          <span class="record-query-list-summary-value">{{ summaryValue(summary.key) }}</span>
        </span>
      </div>
      <div v-if="pageable" class="record-query-list-pagination-controls">
        <span>共 {{ total }} 条</span>
        <UiSelect
          class="record-query-list-page-size"
          :value="pageSize"
          :options="pageSizeOptions"
          :allow-clear="false"
          :disabled="queryActionsDisabled"
          @update:value="handlePageSizeChange"
        />
        <UiButton
          aria-label="上一页"
          title="上一页"
          icon-name="left"
          :disabled="queryActionsDisabled || pageNum <= 1"
          @click="goPage(pageNum - 1)"
        />
        <span>第 {{ pageNum }} / {{ pages }} 页</span>
        <UiButton
          aria-label="下一页"
          title="下一页"
          icon-name="right"
          :disabled="queryActionsDisabled || pageNum >= pages"
          @click="goPage(pageNum + 1)"
        />
      </div>
    </footer>
  </main>
</template>

<style scoped>
.record-query-list-panel {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  grid-template-areas:
    'header'
    'conditions'
    'body'
    'pagination';
  align-content: stretch;
  gap: var(--muyun-management-panel-content-gap, 8px);
  min-width: 0;
  min-height: 0;
  height: 100%;
  padding: var(--muyun-management-panel-padding-block, 10px)
    var(--muyun-management-panel-padding-inline, 12px);
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  background: var(--muyun-surface);
}

.record-query-list-panel.is-embedded {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.record-query-list-panel.is-chrome-free {
  grid-template-rows: minmax(0, 1fr);
  grid-template-areas: 'body';
  gap: 0;
}

.record-query-list-panel.is-embedded .record-query-list-table {
  border-radius: 0;
}

.record-query-list-header {
  grid-area: header;
}

.record-query-list-actions,
.record-query-list-operation-actions,
.record-query-list-query-actions,
.record-query-condition-actions,
.record-query-list-pagination,
.record-query-list-pagination-controls {
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
  overflow: hidden;
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

.record-query-list-actions {
  flex: 0 1 auto;
  margin-left: auto;
  justify-content: flex-end;
  gap: var(--muyun-management-panel-header-gap, 8px);
}

.record-query-list-operation-actions {
  flex: 0 0 auto;
}

.record-query-list-query-actions {
  flex: 1 1 auto;
  justify-content: flex-end;
  flex-wrap: wrap;
}

.record-query-list-search {
  flex: 0 1 clamp(150px, 20vw, 220px);
  width: clamp(150px, 20vw, 220px);
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
  grid-area: conditions;
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  background: var(--muyun-hover-subtle);
}

.record-query-condition-row {
  display: grid;
  grid-template-columns: minmax(140px, 0.8fr) minmax(120px, 0.6fr) minmax(180px, 1fr) 32px;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.record-query-condition-field,
.record-query-condition-operator,
.record-query-condition-value,
.record-query-list-page-size {
  min-width: 0;
}

.record-query-condition-value.muted {
  height: 32px;
  padding: 5px 11px;
  border: 1px solid var(--muyun-border);
  border-radius: 6px;
  background: var(--muyun-support-surface);
  color: var(--muyun-text-muted);
  font-size: 14px;
}

.record-query-list-body {
  grid-area: body;
  display: grid;
  min-height: 0;
}

.record-query-list-table {
  min-height: 0;
  height: 100%;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  overflow: hidden;
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

.record-query-list-table :deep(.ant-table-tbody > tr:hover) .record-query-list-more-action,
.record-query-list-row-actions:focus-within .record-query-list-more-action {
  opacity: 1;
}

.record-query-list-pagination {
  grid-area: pagination;
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.record-query-list-pagination-controls {
  margin-left: auto;
}

.record-query-list-page-size {
  width: 112px;
}

@media (max-width: 680px) {
  .record-query-list-header {
    flex-direction: column;
    align-items: stretch;
  }

  .record-query-list-actions {
    width: 100%;
    flex-wrap: wrap;
    justify-content: flex-start;
  }

  .record-query-list-query-actions {
    flex: 1 1 100%;
    justify-content: flex-start;
  }

  .record-query-list-search {
    flex: 0 1 220px;
    width: min(220px, 100%);
  }

  .record-query-list-pagination,
  .record-query-list-pagination-controls {
    flex-wrap: wrap;
  }

  .record-query-list-pagination-controls {
    margin-left: auto;
  }

  .record-query-condition-row {
    grid-template-columns: 1fr;
  }
}
</style>
