<script setup lang="ts">
import { computed, onMounted, ref, type Component, watch } from 'vue';
import {
  confirmAction,
  UiButton,
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
  ResolvedViewDescriptor,
  ResolvedViewFieldDescriptor,
  WebQueryCondition,
  WebQueryRequest,
  WebSort,
  RecycleBinItem,
} from '@muyun/web-contracts';
import {
  canQueryRecycleBin,
  hasRecycleBinAbility,
  normalizeError,
  type ModuleContext,
} from '@muyun/web-core';
import { presentPlatformError, presentPlatformMessage } from './platformErrorFeedback';
import DateTimeText from './DateTimeText.vue';
import FileSizeText from './FileSizeText.vue';
import RecordActionBar from './RecordActionBar.vue';
import RecycleBinModeButton from './RecycleBinModeButton.vue';
import RecordStatusTag from './RecordStatusTag.vue';
import RecordTagList from './RecordTagList.vue';
import { resolveRecordBooleanStatusValue } from './recordFormFieldModel';
import {
  mergeRecordActions,
  resolveRecordActions,
  type RecordActionItem,
  type ResolvedRecordActionItem,
} from './recordActionBarModel';
import { useRecycleBinState } from './recycleBinState';

defineOptions({ name: 'RecordQueryListPanel' });

export type QueryListRecord = Record<string, unknown> & { id?: string; enabled?: boolean };
export type RecordQueryListMode = 'normal' | 'recycleBin';

export interface RecordQueryListColumn {
  key: string;
  title: string;
  type?: 'text' | 'enabledStatus' | 'booleanStatus' | 'tagList' | 'datetime' | 'fileSize' | 'colorPicker';
  booleanStatus?: ResolvedViewFieldDescriptor['booleanStatus'];
  width?: string;
  align?: 'left' | 'center' | 'right';
  titleField?: string;
  /** Maximum visible lines for text cells. Defaults to one line. */
  maxDisplayLines?: number;
  render?: (record: QueryListRecord) => string;
}

export interface RecordQueryListCellComponent {
  key: string;
  component: Component;
}

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
    rowActionsOf?: (record: QueryListRecord) => RecordActionItem[];
    extraRowActionsOf?: (record: QueryListRecord) => RecordActionItem[];
    rowActionStateOf?: (
      record: QueryListRecord,
      action: RecordActionItem,
    ) => Partial<RecordActionItem> | undefined;
    rowActionsTitle?: string;
    /** Width of the fixed right-side action column. Defaults to the platform compact width. */
    actionColumnWidth?: string | number;
    cellRenderers?: Record<string, (record: QueryListRecord) => string>;
    rowKey?: string;
    selectedKey?: string;
    expandedRowKeys?: string[];
    reloadKey?: number;
    refreshTitle?: string;
    pageSize?: number;
    uiConfigId?: string;
    queryTemplateId?: string;
    ready?: boolean;
    externalQueryValues?: Record<string, unknown>;
    /** Descriptor-owned external criteria that must be exposed by the query schema. */
    requiredExternalCriteriaKeys?: string[];
    quickSearchPlaceholder?: string;
    emptyDescription?: string;
    waitingDescription?: string;
    mode?: RecordQueryListMode;
  }>(),
  {
    rowKey: 'id',
    columns: () => [],
    additionalColumns: () => [],
    cellComponents: () => [],
    actions: () => [],
    extraActions: () => [],
    batchActions: () => [],
    standardCrudActions: false,
    standardCrudRowActions: false,
    rowActionsOf: undefined,
    extraRowActionsOf: undefined,
    rowActionStateOf: undefined,
    rowActionsTitle: '操作',
    actionColumnWidth: 92,
    cellRenderers: () => ({}),
    selectedKey: undefined,
    expandedRowKeys: () => [],
    reloadKey: undefined,
    refreshTitle: undefined,
    pageSize: 20,
    uiConfigId: undefined,
    queryTemplateId: undefined,
    ready: true,
    externalQueryValues: undefined,
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
const runtimeViews = ref<ResolvedViewDescriptor[]>([]);
const descriptorLoadError = ref(false);
const quickSearchKeyword = ref('');
const appliedQuickSearch = ref('');
const conditionsExpanded = ref(false);
const conditionSeq = ref(0);
const conditionDrafts = ref<ConditionDraft[]>([]);
const activeConditions = ref<WebQueryCondition[]>([]);
const selectedRowKeys = ref<UiDataTableKey[]>([]);
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
const quickSearchEnabled = computed(() => schema.value?.quickSearch.enabled === true);
const quickSearchDisabled = computed(() => !queryReady.value || !quickSearchEnabled.value);
const queryActionsDisabled = computed(() => !queryReady.value);
const conditionsDisabled = computed(() => !queryReady.value || queryFields.value.length === 0);
const panelActions = computed<RecordActionItem[]>(() => {
  if (props.mode === 'recycleBin') {
    return [];
  }
  let base: RecordActionItem[];
  if (props.actions && props.actions.length > 0) {
    base = props.actions;
  } else if (!props.standardCrudActions) {
    base = [];
  } else {
    base = [
      {
        key: 'create',
        actionCode: 'create',
        title: '新建',
        primary: true,
        disabled: !queryReady.value,
      },
    ];
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
    (props.mode === 'recycleBin' &&
      (props.context.can('recycleBinRestore') === true || props.context.can('recycleBinPurge') === true)) ||
    props.rowActionsOf !== undefined ||
    props.standardCrudRowActions ||
    props.extraRowActionsOf !== undefined ||
    Boolean(slots.rowActions),
);
const hasExpandedRow = computed(() => props.expandedRowKeys.length > 0 || Boolean(slots.expandedRow));
const rows = computed<QueryListRow[]>(() => records.value.map(resolveRow));
const tableColumns = computed<RecordQueryListColumn[]>(() => {
  const base =
    props.columns && props.columns.length > 0
      ? recycleBinColumns(props.columns)
      : recycleBinColumns(columnsFromRuntimeListView(runtimeViews.value, props.uiConfigId));
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
const pageSizeOptions: Option[] = [
  { label: '10 条/页', value: 10 },
  { label: '20 条/页', value: 20 },
  { label: '50 条/页', value: 50 },
];
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
  () => [props.uiConfigId, props.queryTemplateId, props.ready],
  ([, , ready]) => {
    pageNum.value = 1;
    if (ready) {
      void loadSchemaAndRecords();
      return;
    }
    records.value = [];
    total.value = 0;
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
  () => props.externalQueryValues,
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
    runtimeViews.value = await loadRuntimeViews();
    const nextSchema = await props.context.crud.querySchema({
      uiConfigId: props.uiConfigId,
      queryTemplateId: props.queryTemplateId,
    });
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
    emit('loaded', []);
    presentPlatformError(cause, { source: 'record-query-list-panel', phase: 'load' });
  } finally {
    if (requestSeq === schemaRequestSeq) {
      loading.value = false;
    }
  }
}

async function loadRuntimeViews(): Promise<ResolvedViewDescriptor[]> {
  if (props.columns && props.columns.length > 0) {
    return [];
  }
  try {
    const runtimeContext = await props.context.runtime.ready;
    return runtimeContext.uiDescriptor?.views ?? [];
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
    selectedRowKeys.value = selectedRowKeys.value.filter((key) =>
      response.records.some((record) => recordKey(record) === String(key)),
    );
    total.value = response.total;
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
  if (props.externalQueryValues && Object.keys(props.externalQueryValues).length > 0) {
    request.externalQueryValues = props.externalQueryValues;
  }
  return request;
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
  const configuredActions = rowActions(record).map((action) => rowActionWithState(record, action));
  const actions = resolveRecordActions(props.context, configuredActions);
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

function rowActions(record: QueryListRecord): RecordActionItem[] {
  if (props.mode === 'recycleBin') {
    const item = recycleBinItems.get(recordKey(record));
    if (!item) return [];
    return [
      ...(props.context.can('recycleBinRestore') === true
        ? [{ key: 'restore', actionCode: 'recycleBinRestore', title: '恢复', disabled: !item.restorable }]
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
  return [
    { key: 'view', title: '查看' },
    { key: 'edit', actionCode: 'update', title: '修改', iconName: 'edit' },
    { key: 'delete', actionCode: 'delete', title: '删除', iconName: 'delete', danger: true },
  ];
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
  if (props.mode === 'recycleBin') return;
  emit('select', row.record);
}

function handleTableRowDblclick(row: QueryListRow, event: MouseEvent) {
  if (props.mode === 'recycleBin') return;
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

function cellValue(record: QueryListRecord, column: RecordQueryListColumn) {
  return (
    column.render?.(record) ??
    props.cellRenderers[column.key]?.(record) ??
    displayRecordFieldValue(record, column.key, column.titleField)
  );
}

function dateTimeCellValue(record: QueryListRecord, column: RecordQueryListColumn) {
  const value: unknown =
    column.render?.(record) ?? props.cellRenderers[column.key]?.(record) ?? record[column.key];
  if (value === null || value === undefined) {
    return undefined;
  }
  if (value instanceof Date || typeof value === 'string' || typeof value === 'number') {
    return value;
  }
  return String(value);
}

function fileSizeCellValue(record: QueryListRecord, column: RecordQueryListColumn) {
  const value: unknown =
    column.render?.(record) ?? props.cellRenderers[column.key]?.(record) ?? record[column.key];
  return typeof value === 'number' || typeof value === 'string' || typeof value === 'bigint'
    ? value
    : undefined;
}

function displayRecordFieldValue(record: QueryListRecord, fieldName: string, titleField?: string) {
  const titleValue = record[titleField ?? `${fieldName}Title`];
  if (typeof titleValue === 'string' && titleValue.trim()) {
    return titleValue;
  }
  const value = record[fieldName];
  if (typeof value === 'boolean') {
    return value ? '是' : '否';
  }
  return String(value ?? '');
}

function statusCellValue(record: QueryListRecord, column: RecordQueryListColumn | undefined) {
  if (column?.type === 'booleanStatus') {
    return resolveRecordBooleanStatusValue(record[column.key]);
  }
  return record[column?.key ?? ''] !== false;
}

function columnsFromRuntimeListView(
  views: ResolvedViewDescriptor[] | undefined,
  uiConfigId?: string,
): RecordQueryListColumn[] {
  const view =
    views?.find((item) => item.viewKind === 'LIST' && item.sourceUiConfigId === uiConfigId) ??
    views?.find((item) => item.viewKind === 'LIST' && item.viewCode === 'default_list') ??
    views?.find((item) => item.viewKind === 'LIST');
  if (!view) {
    return [];
  }
  return view.fields
    .filter((field) => field.visible?.constant !== false)
    .map((field) => {
      const queryField = fieldByName(field.fieldRef.fieldName);
      return {
        key: field.fieldRef.fieldName,
        title: field.label ?? field.fieldRef.fieldName,
        type:
          field.uiType === 'enabledStatus'
            ? 'enabledStatus'
            : field.uiType === 'booleanStatus' && field.booleanStatus
              ? 'booleanStatus'
              : field.uiType === 'tagList'
                ? 'tagList'
                : field.uiType === 'colorPicker'
                  ? 'colorPicker'
                  : field.valuePresentation === 'FILE_SIZE'
                    ? 'fileSize'
                    : isDateTimeValueType(field.valueType ?? queryField?.valueType)
                      ? 'datetime'
                      : 'text',
        width: field.width,
        align: columnAlign(field.align),
        titleField: field.option?.titleField ?? queryField?.optionTitleField,
        booleanStatus: field.booleanStatus,
        maxDisplayLines: field.maxDisplayLines,
      };
    });
}

function isDateTimeValueType(valueType: string | undefined) {
  return valueType === 'TIMESTAMP' || valueType === 'ZONED_TIMESTAMP' || valueType === 'INSTANT';
}

function columnAlign(align: string | undefined): RecordQueryListColumn['align'] {
  return align === 'center' || align === 'right' ? align : 'left';
}

function goPage(nextPage: number) {
  pageNum.value = Math.min(Math.max(1, nextPage), pages.value);
  void loadRecords();
}

function handlePageSizeChange(value: OptionValue | OptionValueList | null) {
  const pageSizeValue = singleOptionValue(value);
  pageSize.value =
    typeof pageSizeValue === 'number' ? pageSizeValue : Number(pageSizeValue ?? props.pageSize);
  pageNum.value = 1;
  void loadRecords();
}

function singleOptionValue(value: OptionValue | OptionValueList | null) {
  return Array.isArray(value) ? undefined : value;
}

defineExpose({ clearSelection, refresh });
</script>

<template>
  <main class="record-query-list-panel">
    <header class="record-query-list-header">
      <UiButton
        class="record-query-list-title"
        icon-name="reload"
        icon-position="end"
        type="text"
        :disabled="queryActionsDisabled"
        :title="refreshTitle ?? `刷新${title}`"
        @click="refresh"
      >
        <span>{{ title }}</span>
      </UiButton>
      <div class="record-query-list-actions">
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
        <UiSearchInput
          :value="quickSearchKeyword"
          class="record-query-list-search"
          :disabled="quickSearchDisabled"
          :placeholder="quickSearchPlaceholder"
          @update:value="handleQuickSearchInput"
          @search="submitQuickSearch"
        />
        <UiButton
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
    </header>

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
          <RecordStatusTag
            v-else-if="
              ['enabledStatus', 'booleanStatus'].includes(
                tableColumns.find((item) => item.key === column.key)?.type ?? '',
              )
            "
            :enabled="
              statusCellValue(
                (record as QueryListRow).record,
                tableColumns.find((item) => item.key === column.key),
              )
            "
            :enabled-label="tableColumns.find((item) => item.key === column.key)?.booleanStatus?.trueLabel"
            :disabled-label="tableColumns.find((item) => item.key === column.key)?.booleanStatus?.falseLabel"
            :enabled-tone="tableColumns.find((item) => item.key === column.key)?.booleanStatus?.trueTone"
            :disabled-tone="tableColumns.find((item) => item.key === column.key)?.booleanStatus?.falseTone"
          />
          <RecordTagList
            v-else-if="tableColumns.find((item) => item.key === column.key)?.type === 'tagList'"
            :items="(record as QueryListRow).record[column.key]"
          />
          <DateTimeText
            v-else-if="tableColumns.find((item) => item.key === column.key)?.type === 'datetime'"
            :value="
              dateTimeCellValue(
                (record as QueryListRow).record,
                tableColumns.find((item) => item.key === column.key)!,
              )
            "
          />
          <FileSizeText
            v-else-if="tableColumns.find((item) => item.key === column.key)?.type === 'fileSize'"
            :value="
              fileSizeCellValue(
                (record as QueryListRow).record,
                tableColumns.find((item) => item.key === column.key)!,
              )
            "
          />
          <span
            v-else-if="tableColumns.find((item) => item.key === column.key)?.type === 'colorPicker'"
            class="record-query-list-color"
          >
            <i
              :style="{ backgroundColor: String((record as QueryListRow).record[column.key] ?? '') }"
              aria-hidden="true"
            />
            {{
              cellValue(
                (record as QueryListRow).record,
                tableColumns.find((item) => item.key === column.key)!,
              )
            }}
          </span>
          <span
            v-else
            class="record-query-list-text"
            :style="{
              '--record-query-list-max-lines': String(
                tableColumns.find((item) => item.key === column.key)?.maxDisplayLines ?? 1,
              ),
            }"
            :title="
              cellValue(
                (record as QueryListRow).record,
                tableColumns.find((item) => item.key === column.key)!,
              )
            "
            >{{
              cellValue(
                (record as QueryListRow).record,
                tableColumns.find((item) => item.key === column.key)!,
              )
            }}</span
          >
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

    <footer class="record-query-list-pagination">
      <RecycleBinModeButton
        v-if="recycleBinEnabled && (mode === 'recycleBin' || canQueryRecycleBinAvailable)"
        :active="mode === 'recycleBin'"
        :has-records="recycleBinHasRecords"
        :count="recycleBinState.summaryTotal.value"
        @click="emit('modeChange', mode === 'normal' ? 'recycleBin' : 'normal')"
      />
      <div class="record-query-list-pagination-controls">
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

.record-query-list-header {
  grid-area: header;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.record-query-list-title {
  flex: 0 0 auto;
  margin: -4px 0 -4px -6px;
  padding: 4px 6px;
  color: var(--muyun-text);
  font-size: 16px;
  font-weight: 700;
}

.record-query-list-title :deep(.ui-button-trailing-icon) {
  width: 0;
  margin-inline-start: 0;
  margin-inline-end: 0;
  color: var(--muyun-text-muted);
  opacity: 0;
  transition:
    width 0.16s ease,
    margin 0.16s ease,
    opacity 0.16s ease;
}

.record-query-list-title:hover :deep(.ui-button-trailing-icon),
.record-query-list-title:focus-visible :deep(.ui-button-trailing-icon) {
  width: 14px;
  margin-inline-start: 6px;
  opacity: 1;
}

.record-query-list-actions,
.record-query-condition-actions,
.record-query-list-pagination,
.record-query-list-pagination-controls {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.record-query-list-actions {
  flex: 1 1 auto;
  justify-content: flex-end;
  flex-wrap: wrap;
}

.record-query-list-search {
  width: clamp(150px, 20vw, 220px);
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

.record-query-list-color {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.record-query-list-color i {
  width: 14px;
  height: 14px;
  border: 1px solid rgb(15 23 42 / 18%);
  border-radius: 50%;
}

.record-query-list-text {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: var(--record-query-list-max-lines);
  line-clamp: var(--record-query-list-max-lines);
  white-space: normal;
  word-break: break-word;
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

@media (max-width: 900px) {
  .record-query-list-header,
  .record-query-list-actions,
  .record-query-list-pagination,
  .record-query-list-pagination-controls {
    display: grid;
    grid-template-columns: 1fr;
    justify-items: stretch;
  }

  .record-query-list-search,
  .record-query-list-page-size {
    width: 100%;
  }

  .record-query-condition-row {
    grid-template-columns: 1fr;
  }
}
</style>
