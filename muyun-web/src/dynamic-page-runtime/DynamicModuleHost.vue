<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import {
  ManagementExplorerColumn,
  ManagementWorkspace,
  CrudRecordListExplorer,
  ModuleActionButton,
  RecordDetailPanel,
  RecordActionBar,
  RecordDetailExtensionSection,
  RecordDetailFields,
  RecordExplorerPanel,
  RecordFormFields,
  RecordMetaSection,
  RecordModeDrawer,
  RecordDetailDrawer,
  RecordPanelButton,
  RecordPanelState,
  RecordQueryListPanel,
  RecycleBinModeButton,
  RecordStatusSwitch,
  StaticManagementLayout,
  TreeRecordExplorer,
  confirmAction,
  handlePlatformActionSuccess,
  parentRecordConstraints,
  presentPlatformError,
  providePageLayout,
  resolveRecordFormFields,
  useRecycleBinExplorerMode,
  type RecordFormFieldPickerConfig,
  type CrudRecordListBase,
  type RecordExplorerItemDescriptor,
  type RecordQueryListMode,
  type RecordActionItem,
  type RecordQueryListCellComponent,
  type StandardCrudRowActionKey,
  type QueryListRecord,
  type RecordFormRecord,
} from '@muyun/platform-components';
import type {
  DynamicModulePageDescriptor,
  MenuPageMode,
  PageBootstrap,
  ResolvedModulePageDescriptor,
  ResolvedPageNavigatorLevelDescriptor,
} from '@muyun/web-contracts';
import {
  createModuleContext,
  createPageBootstrapClient,
  useModuleContext,
  type ModuleContext,
} from '@muyun/web-core';
import {
  canMutateDynamicModuleDetail,
  shouldCommitDynamicModuleDetailRequest,
} from './dynamicModuleDetailStateModel';
import {
  resolveModulePageEnhancement,
  type ModulePageActionContribution,
  type ModulePageActionContext,
  type ModulePageActionStateContext,
  type ModulePageBatchActionContribution,
  type ModulePageColumnContribution,
  type ModulePageDetailDrawer,
  type ModulePageDetailSection,
  type ModulePageDetailSectionContext,
  type ModulePageDrawer,
  type ModulePageDrawerContext,
  type ModulePageRecordActionContribution,
} from './modulePageEnhancements';
import { useModulePageNavigation } from './modulePageNavigation';
import { modulePageListRefreshRegistry } from './modulePageListRefresh';

/**
 * Descriptor-driven CRUD runner shared by static and dynamic modules.
 *
 * The `DynamicModuleHost` name remains the compatibility counterpart of the
 * persisted `dynamic-module-host` page descriptor. It does not imply a
 * dynamic-only UI path.
 */
defineOptions({ name: 'DynamicModuleHost' });

const props = defineProps<{
  descriptor: DynamicModulePageDescriptor;
}>();

const context = useModuleContext<QueryListRecord>({
  moduleAlias: props.descriptor.target.moduleAlias,
});
const modulePageNavigation = useModulePageNavigation();
const selectedRecord = ref<QueryListRecord>();
const editingRecord = ref<QueryListRecord>();
const editorMode = ref<'create' | 'edit' | 'view'>('view');
const detailOpen = ref(false);
const formFields = ref(resolveRecordFormFields(undefined));
const runtimePage = ref<ResolvedModulePageDescriptor>();
const listMode = ref<RecordQueryListMode>('normal');
const reloadKey = ref(0);
const treeReloadKey = ref(0);
const selectedTreeRecord = ref<QueryListRecord>();
const treeSearchKeyword = ref('');
const flatManagementSearchKeyword = ref('');
const flatManagementReloadKey = ref(0);
const treeModule = ref(false);
const navigatorLevels = ref<NavigatorLevelRuntime[]>([]);
const selectedNavigatorRecords = ref<Record<string, QueryListRecord | undefined>>({});
const scopeSearchKeyword = ref('');
const scopeReloadKey = ref(0);
const saving = ref(false);
const formSessionKey = ref(0);
const togglingEnabled = ref(false);
const detailLoading = ref(false);
const detailLoadFailed = ref(false);
const narrowDetailSurface = ref(false);
const enhancementDrawer = ref<{
  definition: ModulePageDrawer;
  context: ModulePageDrawerContext;
}>();
let detailLoadSequence = 0;
let unregisterListRefresh: (() => void) | undefined;
let narrowDetailSurfaceQuery: MediaQueryList | undefined;
let updateNarrowDetailSurface: (() => void) | undefined;

interface NavigatorLevelRuntime {
  descriptor: ResolvedPageNavigatorLevelDescriptor;
  context: ModuleContext<QueryListRecord>;
  tree: boolean;
}

const title = computed(
  () => props.descriptor.title ?? context.runtime.snapshot()?.title ?? context.moduleAlias,
);
const detailTitle = computed(() => {
  if (editorMode.value === 'create') {
    return flatManagementPage.value
      ? (flatManagementContent.value?.createTitle ?? `新建${title.value}`)
      : `新建${title.value}`;
  }
  return recordTitle(editingRecord.value ?? selectedRecord.value) ?? '记录详情';
});
const pageBootstrap = ref<PageBootstrap>();
const pageBootstrapError = ref<string>();
const configuredPageMode = computed<MenuPageMode>(() => props.descriptor.target.pageMode ?? 'LIST');
const pageMode = computed<MenuPageMode>(
  () => pageBootstrap.value?.entry.pageMode ?? configuredPageMode.value,
);
const isListPage = computed(() => pageMode.value === 'LIST');
const configuredListUiConfigId = computed(() =>
  isListPage.value ? props.descriptor.target.defaultUiConfigId : undefined,
);
const listUiConfigId = computed(
  () => pageBootstrap.value?.entry.defaultUiConfigId ?? configuredListUiConfigId.value,
);
const listQueryTemplateId = computed(
  () => pageBootstrap.value?.entry.defaultQueryTemplateId ?? props.descriptor.target.defaultQueryTemplateId,
);
const activeListView = computed(() => {
  return runtimePage.value?.list?.fields;
});
const flatManagementPage = computed(() => runtimePage.value?.template === 'FLAT_MANAGEMENT');
const flatManagementContent = computed(() => {
  const explorer = runtimePage.value?.explorer;
  const detail = runtimePage.value?.detail;
  return explorer == null || detail == null
    ? undefined
    : {
        explorerTitle: explorer.title,
        explorerSearchPlaceholder: explorer.searchPlaceholder,
        emptyDescription: explorer.emptyDescription,
        detailEmptyDescription: detail.emptyDescription,
        createTitle: detail.createTitle,
        recordLabel: explorer.recordLabel,
        fallbackTitle: explorer.fallbackTitle,
      };
});
const recordLabel = computed(() =>
  flatManagementPage.value ? (flatManagementContent.value?.recordLabel ?? '记录') : '记录',
);
const pageEnhancement = computed(() =>
  resolveModulePageEnhancement(context.moduleAlias, activeListView.value?.viewCode),
);
const enhancementActionContributions = computed<ModulePageActionContribution[]>(
  () => pageEnhancement.value?.list?.actions ?? [],
);
const enhancementActions = computed<ModulePageActionContribution[]>(() =>
  enhancementActionContributions.value.map(({ state, authorization, ...action }) => {
    const stateContext = modulePageActionStateContext();
    const scopeRecordId = stateContext.scope?.record?.id;
    return {
      ...action,
      ...state?.(stateContext),
      ...(authorization === 'scope-record' && primaryNavigatorContext.value
        ? {
            authorizationContext: primaryNavigatorContext.value,
            authorizationRecordId: scopeRecordId == null ? undefined : String(scopeRecordId),
          }
        : {}),
    };
  }),
);
const enhancementColumns = computed<ModulePageColumnContribution[]>(
  () => pageEnhancement.value?.list?.columns ?? [],
);
const enhancementCellComponents = computed<RecordQueryListCellComponent[]>(() => [
  ...enhancementColumns.value.map((column) => ({ key: column.key, component: column.cell })),
  ...(pageEnhancement.value?.list?.cellComponents ?? []).map((cell) => ({
    key: cell.key,
    component: cell.cell,
  })),
]);
const enhancementRowActions = computed<ModulePageRecordActionContribution[]>(
  () => pageEnhancement.value?.list?.rowActions ?? [],
);
function enhancementRowActionsFor(record: QueryListRecord) {
  return enhancementRowActions.value.map(({ state, ...action }) => ({ ...action, ...state?.(record) }));
}
const enhancementBatchActions = computed<ModulePageBatchActionContribution[]>(
  () => pageEnhancement.value?.list?.batchActions ?? [],
);
const enhancementDetailActions = computed<ModulePageRecordActionContribution[]>(
  () => pageEnhancement.value?.detail?.actions ?? [],
);
const enhancementDetailSections = computed<ModulePageDetailSection[]>(
  () => pageEnhancement.value?.detail?.sections ?? [],
);
const enhancementDetailDrawer = computed<ModulePageDetailDrawer | undefined>(
  () => pageEnhancement.value?.detail?.drawer,
);
const listDetailCardPage = computed(
  () => runtimePage.value?.template === 'LIST_DETAIL_CARD' && !enhancementDetailDrawer.value,
);
const standardCrudRowActionKeys = computed<StandardCrudRowActionKey[]>(() =>
  enhancementDetailDrawer.value ? ['view'] : ['view', 'edit', 'delete'],
);
const standardCrudRowActionCodes = computed<Partial<Record<StandardCrudRowActionKey, string>>>(() => ({
  view: pageEnhancement.value?.list?.viewActionCode ?? 'view',
}));
const pageBootstrapRequired = computed(() => Boolean(props.descriptor.menuId));
const pageReady = computed(() => !pageBootstrapRequired.value || pageBootstrap.value !== undefined);
const unsupportedPageModeText = computed(() => `动态${pageMode.value}入口暂未接入运行器`);
// Tree modules are discovered from runtime metadata. Once discovered, their
// explorer/detail panes own the constrained work area instead of extending the
// workbench tab's document flow.
providePageLayout(
  computed(() =>
    treeModule.value ||
    navigatorLevels.value.length > 0 ||
    flatManagementPage.value ||
    listDetailCardPage.value
      ? 'workspace'
      : props.descriptor.layout,
  ),
);
/**
 * Page enhancements may use the leading navigator as their business scope.
 * The selection itself remains the shared multi-level navigator state; do not
 * introduce a separate scope selection for actions or drawers.
 */
const primaryNavigatorContext = computed<ModuleContext<QueryListRecord> | undefined>(() => {
  return navigatorLevels.value[0]?.context;
});
const navigatorListQueryValues = computed<Record<string, unknown> | undefined>(() => {
  const values: Record<string, unknown> = {};
  for (const level of navigatorLevels.value) {
    const id = selectedNavigatorRecords.value[level.descriptor.key]?.id;
    if (id == null) continue;
    for (const binding of level.descriptor.queryBindings) {
      values[binding.queryCriteriaKey] = id;
    }
  }
  return Object.keys(values).length > 0 ? values : undefined;
});
const navigatorListCriteriaKeys = computed(() =>
  navigatorLevels.value.flatMap((level) =>
    level.descriptor.queryBindings.map((binding) => binding.queryCriteriaKey),
  ),
);
const navigatorCreateDefaults = computed<Record<string, unknown>>(() => {
  const values: Record<string, unknown> = {};
  for (const level of navigatorLevels.value) {
    const id = selectedNavigatorRecords.value[level.descriptor.key]?.id;
    if (id == null) continue;
    for (const binding of level.descriptor.queryBindings) {
      values[binding.field] = id;
    }
  }
  return values;
});
const canToggleEnabled = computed(() => {
  const record = selectedRecord.value;
  if (
    recycleBinDetailActive.value ||
    !record?.id ||
    editorMode.value !== 'view' ||
    detailLoading.value ||
    detailLoadFailed.value ||
    togglingEnabled.value
  ) {
    return false;
  }
  return context.can(record.enabled === false ? 'enable' : 'disable') === true;
});
const flatManagementRecycleBin = useRecycleBinExplorerMode<QueryListRecord>({
  context,
  listReloadKey: flatManagementReloadKey,
  searchKeyword: flatManagementSearchKeyword,
  canChange: () => !saving.value,
  resetSelection: resetFlatManagementSelection,
});
const flatManagementActions = computed<RecordActionItem[]>(() => {
  if (flatManagementRecycleBin.active.value) return [];
  if (editorMode.value !== 'view') {
    return [
      { key: 'cancel', title: '取消', disabled: saving.value },
      {
        key: 'save',
        actionCode: editorMode.value === 'create' ? 'create' : 'update',
        title: saving.value ? '保存中' : '保存',
        loading: saving.value,
        primary: true,
      },
    ];
  }
  return [
    { key: 'edit', actionCode: 'update', title: '编辑', disabled: !selectedRecord.value },
    {
      key: 'delete',
      actionCode: 'delete',
      title: '删除',
      disabled: !selectedRecord.value,
      loading: saving.value,
      danger: true,
    },
  ];
});
const recycleBinDetailActive = computed(
  () => flatManagementRecycleBin.active.value || listMode.value === 'recycleBin',
);
const treeParentPickerConfigs = computed<Record<string, RecordFormFieldPickerConfig>>(() => {
  if (!treeModule.value || !formFields.value.has('parentId')) {
    return {} as Record<string, RecordFormFieldPickerConfig>;
  }
  return {
    parentId: {
      context,
      mode: 'tree',
      placeholder: '根标签留空',
      allowClear: true,
      constraints: parentRecordConstraints(
        editingRecord.value?.id == null ? undefined : String(editingRecord.value.id),
      ),
    },
  };
});
const referencePickerConfigs = computed<Record<string, RecordFormFieldPickerConfig>>(() => {
  const configs: Record<string, RecordFormFieldPickerConfig> = { ...treeParentPickerConfigs.value };
  for (const field of formFields.value.values()) {
    const reference = field.reference;
    if (!reference) {
      continue;
    }
    configs[field.fieldRef.fieldName] = {
      context: createModuleContext({ http: context.http, moduleAlias: reference.targetModuleAlias }),
      mode: 'tree',
      allowClear: !field.required?.constant,
    };
  }
  return configs;
});

onMounted(async () => {
  if (typeof window.matchMedia === 'function') {
    narrowDetailSurfaceQuery = window.matchMedia('(max-width: 719px)');
    updateNarrowDetailSurface = () => {
      narrowDetailSurface.value = narrowDetailSurfaceQuery?.matches === true;
    };
    updateNarrowDetailSurface();
    narrowDetailSurfaceQuery.addEventListener('change', updateNarrowDetailSurface);
  }
  await loadPageBootstrap();
  await loadRuntimeForm();
  if (isListPage.value && !pageBootstrapError.value) {
    unregisterListRefresh = modulePageListRefreshRegistry.register(context.moduleAlias, refreshList);
  }
});

onUnmounted(() => {
  if (updateNarrowDetailSurface) {
    narrowDetailSurfaceQuery?.removeEventListener('change', updateNarrowDetailSurface);
  }
  unregisterListRefresh?.();
  unregisterListRefresh = undefined;
});

async function loadPageBootstrap() {
  const menuId = props.descriptor.menuId;
  if (!menuId) return;
  try {
    const bootstrap = await createPageBootstrapClient(context.http).byMenu(menuId);
    if (bootstrap.entry.moduleAlias !== context.moduleAlias) {
      throw new Error(
        `Menu ${menuId} resolves ${bootstrap.entry.moduleAlias}, expected ${context.moduleAlias}`,
      );
    }
    pageBootstrap.value = bootstrap;
    pageBootstrapError.value = undefined;
  } catch (cause) {
    pageBootstrapError.value = cause instanceof Error ? cause.message : '页面入口加载失败';
  }
}

async function loadRuntimeForm() {
  if (!isListPage.value) {
    return;
  }
  const runtimeContext = await context.runtime.ready;
  runtimePage.value = runtimeContext.uiDescriptor?.page;
  treeModule.value = context.abilities.hasTree() === true;
  const enhancement = pageEnhancement.value;
  if (treeModule.value && (enhancement?.detail?.drawer || enhancement?.list?.viewActionCode)) {
    pageBootstrapError.value = `模块页面增强 ${enhancement.id} 的业务详情抽屉和查看动作仅支持普通列表模块，不支持树模块`;
    return;
  }
  const resolvedNavigatorLevels = runtimePage.value?.navigator?.levels ?? [];
  selectedNavigatorRecords.value = {};
  navigatorLevels.value = await Promise.all(
    resolvedNavigatorLevels.map(async (descriptor) => {
      const navigatorContext = createModuleContext<QueryListRecord>({
        http: context.http,
        moduleAlias: descriptor.sourceModuleAlias,
      });
      await navigatorContext.runtime.ready;
      return {
        descriptor,
        context: navigatorContext,
        tree: descriptor.kind === 'TREE' && navigatorContext.abilities.hasTree() === true,
      };
    }),
  );
  formFields.value = resolveRecordFormFields(runtimeContext.uiDescriptor);
}

function handleLoaded(records: QueryListRecord[]) {
  if (selectedRecord.value) {
    selectedRecord.value =
      records.find((record) => record.id === selectedRecord.value?.id) ?? selectedRecord.value;
    // The list is intentionally a compact projection. Do not overwrite an open
    // detail snapshot with it after a refresh (for example, enable/disable), or
    // form-only/read-side fields disappear from the drawer. `openRecord` and
    // `toggleEnabled` refresh the authoritative detail through CRUD view.
  }
}

function resetFlatManagementSelection() {
  detailLoadSequence += 1;
  detailLoading.value = false;
  detailLoadFailed.value = false;
  detailOpen.value = false;
  editorMode.value = 'view';
  selectedRecord.value = undefined;
  editingRecord.value = undefined;
}

function handleFlatManagementLoaded(records: QueryListRecord[]) {
  if (!flatManagementRecycleBin.active.value) handleLoaded(records);
}

function flatManagementItemOf(record: CrudRecordListBase): RecordExplorerItemDescriptor {
  return {
    title:
      record.title ??
      record.alias ??
      record.code ??
      record.id ??
      flatManagementContent.value?.fallbackTitle ??
      '未命名记录',
    secondary: record.alias ?? record.code ?? record.id,
    muted: record.enabled === false,
  };
}

function openFlatManagementRecord(record: QueryListRecord) {
  if (flatManagementRecycleBin.active.value) {
    void openRecycleBinRecord(record);
    return;
  }
  void openViewRecord(record);
}

function handleFlatManagementAction(action: RecordActionItem) {
  if (action.key === 'cancel') {
    closeTreeCardEditor();
    return;
  }
  if (action.key === 'save') {
    void saveRecord();
    return;
  }
  if (action.key === 'edit' && selectedRecord.value) {
    void editRecord(selectedRecord.value);
    return;
  }
  if (action.key === 'delete' && selectedRecord.value) {
    void deleteRecord(selectedRecord.value);
  }
}

function handleListModeChange(mode: RecordQueryListMode) {
  if (saving.value || listMode.value === mode) return;
  listMode.value = mode;
  detailLoadSequence += 1;
  detailLoading.value = false;
  detailLoadFailed.value = false;
  detailOpen.value = false;
  editorMode.value = 'view';
  selectedRecord.value = undefined;
  editingRecord.value = undefined;
  selectedTreeRecord.value = undefined;
}

function handleRecycleBinRestore() {
  reloadKey.value += 1;
}

function selectRecord(record: QueryListRecord) {
  selectedRecord.value = record;
}

/** A LIST_DETAIL_CARD selection is the detail navigation event, not merely row highlighting. */
function selectListDetailRecord(record: QueryListRecord) {
  selectRecord(record);
  if (listMode.value === 'recycleBin') {
    void openRecycleBinRecord(record);
    return;
  }
  void openViewRecord(record);
}

function selectStandaloneListRecord(record: QueryListRecord) {
  selectRecord(record);
  if (listMode.value === 'recycleBin') void openRecycleBinRecord(record);
}

function openListRecord(record: QueryListRecord) {
  if (listMode.value === 'recycleBin') {
    void openRecycleBinRecord(record);
    return;
  }
  void openViewRecord(record);
}

/**
 * A selection takes effect at every navigator level: it immediately constrains
 * the list and clears only selections that depend on it.
 */
function selectNavigatorRecord(levelKey: string, record: { id?: string }) {
  if (!navigatorLevels.value.some((level) => level.descriptor.key === levelKey)) return;
  const previous = selectedNavigatorRecords.value[levelKey];
  const next = { ...selectedNavigatorRecords.value };
  next[levelKey] = previous?.id === record.id ? undefined : (record as QueryListRecord);
  for (const descendantKey of navigatorDescendantKeys(levelKey)) {
    next[descendantKey] = undefined;
  }
  selectedNavigatorRecords.value = next;
  clearSelectionForScopeChange();
}

function navigatorDescendantKeys(levelKey: string): Set<string> {
  const descendants = new Set<string>();
  const pending = [levelKey];
  while (pending.length > 0) {
    const parent = pending.pop();
    const level = navigatorLevels.value.find((candidate) => candidate.descriptor.key === parent);
    for (const binding of level?.descriptor.childBindings ?? []) {
      if (!descendants.has(binding.childLevelKey)) {
        descendants.add(binding.childLevelKey);
        pending.push(binding.childLevelKey);
      }
    }
  }
  return descendants;
}

function navigatorExplorerQueryValues(levelKey: string): Record<string, unknown> | undefined {
  const values: Record<string, unknown> = {};
  for (const level of navigatorLevels.value) {
    const id = selectedNavigatorRecords.value[level.descriptor.key]?.id;
    if (id == null) continue;
    for (const binding of level.descriptor.childBindings) {
      if (binding.childLevelKey === levelKey) values[binding.childQueryCriteriaKey] = id;
    }
  }
  return Object.keys(values).length > 0 ? values : undefined;
}

/** A scope selection immediately constrains the list; its former detail may no longer be in range. */
function clearSelectionForScopeChange() {
  detailLoadSequence += 1;
  detailLoading.value = false;
  detailLoadFailed.value = false;
  detailOpen.value = false;
  editorMode.value = 'view';
  selectedRecord.value = undefined;
  editingRecord.value = undefined;
}

function selectTreeRecord(record: unknown) {
  selectedTreeRecord.value = record as QueryListRecord;
  void openRecord(selectedTreeRecord.value, 'view');
}

function handleTreeLoaded(records: unknown[]) {
  if (selectedTreeRecord.value || editorMode.value !== 'view') return;
  const firstRecord = records.at(0);
  if (firstRecord) selectTreeRecord(firstRecord);
}

async function openRecord(record: QueryListRecord, mode: 'edit' | 'view') {
  const id = record.id == null ? undefined : String(record.id);
  if (!id) return;
  const requestSequence = ++detailLoadSequence;
  formSessionKey.value += 1;
  selectedRecord.value = record;
  editingRecord.value = undefined;
  editorMode.value = mode;
  detailOpen.value = true;
  detailLoading.value = true;
  detailLoadFailed.value = false;
  if (mode === 'view' && enhancementDetailDrawer.value?.loadRecord === false) {
    editingRecord.value = record;
    detailLoading.value = false;
    return;
  }
  try {
    const detail = await context.crud.view(id);
    if (
      !shouldCommitDynamicModuleDetailRequest({ activeRequestSequence: detailLoadSequence, requestSequence })
    )
      return;
    editingRecord.value = detail;
    selectedRecord.value = detail;
  } catch {
    if (
      !shouldCommitDynamicModuleDetailRequest({ activeRequestSequence: detailLoadSequence, requestSequence })
    )
      return;
    editingRecord.value = undefined;
    detailLoadFailed.value = true;
  } finally {
    if (
      shouldCommitDynamicModuleDetailRequest({ activeRequestSequence: detailLoadSequence, requestSequence })
    ) {
      detailLoading.value = false;
    }
  }
}

/**
 * A deleted record is intentionally unreadable through CRUD `/view/{id}`. Its card therefore loads through the
 * retained-read endpoint, which shares the recycle-bin query permission and data scope with the list.
 */
async function openRecycleBinRecord(record: QueryListRecord) {
  const id = record.id == null ? undefined : String(record.id);
  if (!id) return;
  const requestSequence = ++detailLoadSequence;
  formSessionKey.value += 1;
  selectedRecord.value = record;
  editingRecord.value = undefined;
  editorMode.value = 'view';
  detailOpen.value = true;
  detailLoading.value = true;
  detailLoadFailed.value = false;
  try {
    const detail = await context.http.request<QueryListRecord>({
      method: 'GET',
      path: `/${context.moduleAlias}/recycle-bin/view/${encodeURIComponent(id)}`,
    });
    if (
      !shouldCommitDynamicModuleDetailRequest({ activeRequestSequence: detailLoadSequence, requestSequence })
    ) {
      return;
    }
    editingRecord.value = detail;
    selectedRecord.value = detail;
  } catch {
    if (
      !shouldCommitDynamicModuleDetailRequest({ activeRequestSequence: detailLoadSequence, requestSequence })
    ) {
      return;
    }
    editingRecord.value = undefined;
    detailLoadFailed.value = true;
  } finally {
    if (
      shouldCommitDynamicModuleDetailRequest({ activeRequestSequence: detailLoadSequence, requestSequence })
    ) {
      detailLoading.value = false;
    }
  }
}

function updateDraftField(
  fieldName: string,
  value: import('@muyun/platform-components').RecordFormFieldValue,
) {
  if (!editingRecord.value) {
    return;
  }
  editingRecord.value = {
    ...editingRecord.value,
    [fieldName]: value,
  };
}

function createRecord(parentId?: string) {
  detailLoadSequence += 1;
  detailLoading.value = false;
  detailLoadFailed.value = false;
  formSessionKey.value += 1;
  editingRecord.value = parentId ? { parentId } : { ...navigatorCreateDefaults.value };
  editorMode.value = 'create';
  detailOpen.value = true;
}

function createRootRecord() {
  createRecord();
}

function createChildRecord() {
  const parentId = selectedRecord.value?.id == null ? undefined : String(selectedRecord.value.id);
  if (parentId) createRecord(parentId);
}

async function editRecord(record: QueryListRecord) {
  await openRecord(record, 'edit');
}

async function saveRecord() {
  const record = editingRecord.value;
  if (!record) return;
  if (
    !canMutateDynamicModuleDetail({
      hasRecord: true,
      saving: saving.value,
      loading: detailLoading.value,
      loadFailed: detailLoadFailed.value,
    })
  ) {
    return;
  }
  saving.value = true;
  try {
    const id = record.id == null ? undefined : String(record.id);
    const result =
      editorMode.value === 'edit' && id
        ? await context.crud.update(id, record)
        : await context.crud.insert(record);
    selectedRecord.value = result.record;
    if (treeModule.value) {
      selectedTreeRecord.value = result.record;
    }
    editingRecord.value = result.record;
    editorMode.value = 'view';
    refreshList();
    formSessionKey.value += 1;
    await presentDynamicModuleActionSuccess(result, '保存成功');
  } catch (cause) {
    presentPlatformError(cause, { source: 'dynamic-module-action', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function deleteRecord(record: QueryListRecord) {
  const id = record.id == null ? undefined : String(record.id);
  const version = typeof record.version === 'number' ? record.version : undefined;
  if (!id || version === undefined) return;
  try {
    if (
      !(await confirmAction({
        title: `删除${recordLabel.value}`,
        content: `确认删除「${recordTitle(record) ?? id}」？`,
        okText: '删除',
        danger: true,
      }))
    ) {
      return;
    }
    const result = await context.crud.delete(id, { version });
    if (selectedRecord.value?.id === id) {
      selectedRecord.value = undefined;
      editingRecord.value = undefined;
      selectedTreeRecord.value = undefined;
    }
    refreshList();
    await presentDynamicModuleActionSuccess(result, '删除成功');
  } catch (cause) {
    presentPlatformError(cause, { source: 'dynamic-module-action', phase: 'action' });
  }
}

async function toggleEnabled() {
  const record = selectedRecord.value;
  const id = record?.id == null ? undefined : String(record.id);
  const version = typeof record?.version === 'number' ? record.version : undefined;
  if (!record || !id || version === undefined || !canToggleEnabled.value) return;

  togglingEnabled.value = true;
  try {
    const enabling = record.enabled === false;
    const result = enabling
      ? await context.crud.enable(id, { version })
      : await context.crud.disable(id, { version });
    const refreshed = await context.crud.view(id);
    selectedRecord.value = refreshed;
    editingRecord.value = refreshed;
    refreshList();
    await presentDynamicModuleActionSuccess(result, enabling ? '已启用' : '已停用');
  } catch (cause) {
    presentPlatformError(cause, { source: 'dynamic-module-action', phase: 'action' });
  } finally {
    togglingEnabled.value = false;
  }
}

function presentDynamicModuleActionSuccess(
  result: unknown,
  fallbackMessage: string,
  source = 'dynamic-module-action',
) {
  return handlePlatformActionSuccess(result, {
    source,
    phase: 'action',
    fallbackMessage,
  });
}

function handleListAction(action: { key?: string }) {
  if (action.key === 'create') {
    createRecord();
    return;
  }
  const contribution = enhancementActionContributions.value.find((item) => item.key === action.key);
  if (contribution) {
    void executeEnhancementAction(contribution, modulePageActionContext());
  }
}

function handleRowAction(action: { key?: string }, record: QueryListRecord) {
  if (action.key === 'view') {
    void openViewRecord(record);
    return;
  }
  if (action.key === 'edit') {
    void editRecord(record);
    return;
  }
  if (action.key === 'delete') {
    void deleteRecord(record);
    return;
  }
  const contribution = enhancementRowActions.value.find((item) => item.key === action.key);
  if (contribution) {
    void executeEnhancementAction(contribution, { ...modulePageActionContext(record), record });
  }
}

async function openViewRecord(record: QueryListRecord) {
  const viewActionCode = pageEnhancement.value?.list?.viewActionCode;
  const recordId = record.id == null ? undefined : String(record.id);
  if (viewActionCode && recordId) {
    try {
      const availability = await context.recordActions(recordId);
      if (!availability.actions.some((action) => action.actionCode === viewActionCode && action.available)) {
        return;
      }
    } catch (cause) {
      presentPlatformError(cause, { source: 'module-page-view', phase: 'authorization' });
      return;
    }
  }
  await openRecord(record, 'view');
}

function handleDetailAction(action: { key?: string }) {
  const record = selectedRecord.value;
  const contribution = enhancementDetailActions.value.find((item) => item.key === action.key);
  if (record && contribution) {
    void executeEnhancementAction(contribution, { ...modulePageActionContext(record), record });
  }
}

function handleBatchAction(action: { key?: string }, records: QueryListRecord[], clearSelection: () => void) {
  const contribution = enhancementBatchActions.value.find((item) => item.key === action.key);
  if (contribution) {
    void executeEnhancementAction(contribution, { ...modulePageActionContext(), records, clearSelection });
  }
}

async function executeEnhancementAction<TContext>(
  contribution: { key: string; run(context: TContext): void | Promise<void> },
  actionContext: TContext,
) {
  try {
    await contribution.run(actionContext);
  } catch (cause) {
    presentPlatformError(cause, { source: `module-page-enhancement:${contribution.key}`, phase: 'action' });
  }
}

function detailSectionContext(record: QueryListRecord): ModulePageDetailSectionContext {
  return { module: context, record, refreshList, reload: reloadModulePage };
}

function detailDrawerContext(record: QueryListRecord): ModulePageDrawerContext {
  return {
    module: context,
    record,
    scope: modulePageActionStateContext().scope,
    refreshList,
    close: closeDetail,
    reload: reloadModulePage,
  };
}

function modulePageActionContext(record?: QueryListRecord): ModulePageActionContext {
  return {
    module: context,
    scope: modulePageActionStateContext().scope,
    refreshList,
    reload: reloadModulePage,
    openDrawer: (definition: ModulePageDrawer) => {
      const drawerContext: ModulePageDrawerContext = {
        module: context,
        record,
        scope: modulePageActionStateContext().scope,
        refreshList,
        close: closeEnhancementDrawer,
        reload: reloadModulePage,
      };
      enhancementDrawer.value = { definition, context: drawerContext };
    },
    openWorkspaceTab: (view, input) => {
      if (!modulePageNavigation) {
        throw new Error('模块页面工作视图需要 Workbench 导航承载');
      }
      modulePageNavigation.openWorkspaceTab(view, input);
    },
  };
}

function modulePageActionStateContext(): ModulePageActionStateContext {
  const primaryNavigator = navigatorLevels.value[0];
  return primaryNavigator
    ? {
        module: context,
        scope: {
          moduleAlias: primaryNavigator.descriptor.sourceModuleAlias,
          record: selectedNavigatorRecords.value[primaryNavigator.descriptor.key],
        },
      }
    : { module: context };
}

function closeEnhancementDrawer() {
  enhancementDrawer.value = undefined;
}

function reloadModulePage() {
  refreshList();
  if (!treeModule.value) {
    treeReloadKey.value += 1;
  }
}

/**
 * Public, state-preserving list refresh for business-owned triggers.
 * RecordQueryListPanel observes reloadKey and only re-runs loadRecords().
 */
function refreshList() {
  if (treeModule.value) {
    treeReloadKey.value += 1;
    return;
  }
  if (flatManagementPage.value) {
    flatManagementRecycleBin.refresh();
    return;
  }
  reloadKey.value += 1;
}

defineExpose({ refreshList });

function closeDetail() {
  if (saving.value) return;
  detailLoadSequence += 1;
  detailLoading.value = false;
  detailLoadFailed.value = false;
  formSessionKey.value += 1;
  detailOpen.value = false;
  editorMode.value = 'view';
  editingRecord.value = selectedRecord.value;
}

function closeTreeCardEditor() {
  if (saving.value) return;
  detailLoadSequence += 1;
  detailLoading.value = false;
  detailLoadFailed.value = false;
  formSessionKey.value += 1;
  detailOpen.value = false;
  editorMode.value = 'view';
  editingRecord.value = selectedRecord.value;
}

function retryLoadDetail() {
  const record = selectedRecord.value;
  if (!record || editorMode.value === 'create') return;
  if (flatManagementRecycleBin.active.value || listMode.value === 'recycleBin') {
    void openRecycleBinRecord(record);
    return;
  }
  void openRecord(record, editorMode.value);
}

function recordTitle(record: QueryListRecord | undefined) {
  const titleValue = record?.title ?? record?.name ?? record?.code ?? record?.id;
  return titleValue == null ? undefined : String(titleValue);
}
</script>

<template>
  <section v-if="pageBootstrapError" class="dynamic-module-unsupported">
    <RecordPanelState class="dynamic-module-bootstrap-error" :description="pageBootstrapError" />
  </section>
  <section v-else-if="!pageReady" class="dynamic-module-unsupported">
    <RecordPanelState loading loading-tip="加载页面入口" description="" />
  </section>
  <section
    v-else-if="isListPage"
    class="dynamic-module-workspace"
    :class="{
      'dynamic-module-workspace--tree': treeModule,
      'dynamic-module-workspace--flat-management': flatManagementPage,
    }"
  >
    <StaticManagementLayout
      v-if="flatManagementPage"
      class="dynamic-flat-management-workspace"
      :explorer-title="
        flatManagementRecycleBin.active.value ? '回收站' : (flatManagementContent?.explorerTitle ?? title)
      "
      :refresh-title="`刷新${flatManagementRecycleBin.active.value ? '回收站' : (flatManagementContent?.explorerTitle ?? title)}`"
      :explorer-search-keyword="flatManagementSearchKeyword"
      :explorer-search-placeholder="flatManagementContent?.explorerSearchPlaceholder"
      :explorer-searchable="!flatManagementRecycleBin.active.value"
      :mode="editorMode"
      :detail-title="detailTitle"
      :navigator-count="navigatorLevels.length"
      @update:explorer-search-keyword="flatManagementSearchKeyword = $event"
      @refresh="flatManagementRecycleBin.refresh"
    >
      <template #navigator="{ index }">
        <RecordExplorerPanel
          v-if="navigatorLevels[index]"
          :title="navigatorLevels[index].descriptor.title"
          :refresh-title="`刷新${navigatorLevels[index].descriptor.title}${navigatorLevels[index].tree ? '树' : '列表'}`"
          :search-keyword="scopeSearchKeyword"
          :search-placeholder="navigatorLevels[index].descriptor.searchPlaceholder"
          @update:search-keyword="scopeSearchKeyword = $event"
          @refresh="scopeReloadKey += 1"
        >
          <TreeRecordExplorer
            v-if="navigatorLevels[index].tree"
            :context="navigatorLevels[index].context"
            :selected-id="
              selectedNavigatorRecords[navigatorLevels[index].descriptor.key]?.id == null
                ? undefined
                : String(selectedNavigatorRecords[navigatorLevels[index].descriptor.key]?.id)
            "
            :reload-key="scopeReloadKey"
            :keyword="scopeSearchKeyword"
            :external-query-values="navigatorExplorerQueryValues(navigatorLevels[index].descriptor.key)"
            search-mode="none"
            :empty-description="`暂无${navigatorLevels[index].descriptor.title}`"
            @select="selectNavigatorRecord(navigatorLevels[index].descriptor.key, $event)"
          />
          <CrudRecordListExplorer
            v-else
            :context="navigatorLevels[index].context"
            :selected-id="
              selectedNavigatorRecords[navigatorLevels[index].descriptor.key]?.id == null
                ? undefined
                : String(selectedNavigatorRecords[navigatorLevels[index].descriptor.key]?.id)
            "
            :reload-key="scopeReloadKey"
            :keyword="scopeSearchKeyword"
            :external-query-values="navigatorExplorerQueryValues(navigatorLevels[index].descriptor.key)"
            :empty-description="`暂无${navigatorLevels[index].descriptor.title}`"
            @select="selectNavigatorRecord(navigatorLevels[index].descriptor.key, $event)"
          />
        </RecordExplorerPanel>
      </template>
      <template v-if="!flatManagementRecycleBin.active.value" #explorer-actions>
        <ModuleActionButton
          class="record-panel-create-button"
          :context="context"
          action-code="create"
          icon-only
          :title="flatManagementContent?.createTitle"
          @click="createRootRecord"
        />
      </template>
      <template #explorer>
        <CrudRecordListExplorer
          :context="context"
          :selected-id="selectedRecord?.id == null ? undefined : String(selectedRecord.id)"
          :reload-key="flatManagementRecycleBin.reloadKey.value"
          :mode="flatManagementRecycleBin.mode.value"
          :keyword="flatManagementSearchKeyword"
          :empty-description="
            flatManagementRecycleBin.active.value ? '回收站为空' : flatManagementContent?.emptyDescription
          "
          :fallback-title="flatManagementContent?.fallbackTitle"
          :item-of="flatManagementItemOf"
          @recycle-bin-summary="flatManagementRecycleBin.updateSummary"
          @loaded="(records) => handleFlatManagementLoaded(records as QueryListRecord[])"
          @restored="refreshList"
          @select="(record) => openFlatManagementRecord(record as QueryListRecord)"
        />
      </template>
      <template v-if="flatManagementRecycleBin.buttonVisible.value" #explorer-footer>
        <RecycleBinModeButton
          :active="flatManagementRecycleBin.active.value"
          :has-records="flatManagementRecycleBin.hasRecords.value"
          :count="flatManagementRecycleBin.total.value"
          @click="flatManagementRecycleBin.toggle"
        />
      </template>
      <template #detail-actions>
        <RecordActionBar
          :context="context"
          :record-id="
            editorMode === 'create' || selectedRecord?.id == null ? undefined : String(selectedRecord.id)
          "
          :actions="flatManagementActions"
          @action="handleFlatManagementAction"
        />
      </template>
      <template #detail-status>
        <RecordStatusSwitch
          v-if="!flatManagementRecycleBin.active.value && editorMode !== 'view' && editingRecord"
          :enabled="editingRecord.enabled !== false"
          :disabled="saving"
          :show-label="false"
          @change="updateDraftField('enabled', $event)"
        />
        <RecordStatusSwitch
          v-else-if="!flatManagementRecycleBin.active.value && selectedRecord"
          :enabled="selectedRecord.enabled !== false"
          :disabled="!canToggleEnabled"
          :loading="togglingEnabled"
          :show-label="false"
          @change="toggleEnabled"
        />
      </template>
      <RecordPanelState
        v-if="!selectedRecord && editorMode === 'view'"
        :description="flatManagementContent?.detailEmptyDescription ?? '请选择记录，或新建记录'"
      />
      <RecordPanelState v-else-if="detailLoading" loading loading-tip="加载记录详情" description="" />
      <RecordPanelState v-else-if="detailLoadFailed" description="详情加载失败，请重新选择记录" />
      <template v-else-if="editingRecord">
        <RecordDetailFields
          v-if="editorMode === 'view'"
          :record="editingRecord as RecordFormRecord"
          :fields="formFields"
          :exclude-field-names="['enabled']"
        />
        <div v-else class="dynamic-form">
          <RecordFormFields
            :record="editingRecord as RecordFormRecord"
            :fields="formFields"
            :form-session-key="formSessionKey"
            :option-context="context"
            :picker-configs="referencePickerConfigs"
            :disabled="saving"
            :exclude-field-names="['enabled']"
            @update:field="updateDraftField"
          />
        </div>
        <template v-if="editorMode === 'view'">
          <RecordDetailExtensionSection
            v-for="section in enhancementDetailSections"
            :key="section.key"
            :title="section.title"
          >
            <component :is="section.component" :context="detailSectionContext(editingRecord)" />
          </RecordDetailExtensionSection>
        </template>
        <RecordMetaSection v-if="editorMode !== 'create'" :record="editingRecord" show-sort-order />
      </template>
    </StaticManagementLayout>

    <ManagementWorkspace
      v-else-if="listDetailCardPage && !narrowDetailSurface"
      class="dynamic-list-detail-workspace"
      :explorer-count="navigatorLevels.length"
      detail-surface
    >
      <ManagementExplorerColumn v-for="level in navigatorLevels" :key="level.descriptor.key">
        <RecordExplorerPanel
          :title="level.descriptor.title"
          :refresh-title="`刷新${level.descriptor.title}${level.tree ? '树' : '列表'}`"
          :search-keyword="scopeSearchKeyword"
          :search-placeholder="level.descriptor.searchPlaceholder"
          @update:search-keyword="scopeSearchKeyword = $event"
          @refresh="scopeReloadKey += 1"
        >
          <TreeRecordExplorer
            v-if="level.tree"
            :context="level.context"
            :selected-id="
              selectedNavigatorRecords[level.descriptor.key]?.id == null
                ? undefined
                : String(selectedNavigatorRecords[level.descriptor.key]?.id)
            "
            :reload-key="scopeReloadKey"
            :keyword="scopeSearchKeyword"
            :external-query-values="navigatorExplorerQueryValues(level.descriptor.key)"
            search-mode="none"
            :empty-description="`暂无${level.descriptor.title}`"
            @select="selectNavigatorRecord(level.descriptor.key, $event)"
          />
          <CrudRecordListExplorer
            v-else
            :context="level.context"
            :selected-id="
              selectedNavigatorRecords[level.descriptor.key]?.id == null
                ? undefined
                : String(selectedNavigatorRecords[level.descriptor.key]?.id)
            "
            :reload-key="scopeReloadKey"
            :keyword="scopeSearchKeyword"
            :external-query-values="navigatorExplorerQueryValues(level.descriptor.key)"
            :empty-description="`暂无${level.descriptor.title}`"
            @select="selectNavigatorRecord(level.descriptor.key, $event)"
          />
        </RecordExplorerPanel>
      </ManagementExplorerColumn>
      <RecordQueryListPanel
        class="dynamic-list"
        :context="context"
        :title="title"
        :selected-key="selectedRecord?.id"
        :reload-key="reloadKey"
        :standard-crud-actions="true"
        :standard-crud-row-actions="true"
        :standard-crud-row-action-keys="standardCrudRowActionKeys"
        :standard-crud-row-action-codes="standardCrudRowActionCodes"
        :extra-actions="enhancementActions"
        :additional-columns="enhancementColumns"
        :cell-components="enhancementCellComponents"
        :extra-row-actions-of="enhancementRowActionsFor"
        :action-column-width="pageEnhancement?.list?.actionColumnWidth"
        :batch-actions="enhancementBatchActions"
        :ui-config-id="listUiConfigId"
        :query-template-id="listQueryTemplateId"
        :ready="pageReady"
        :external-query-values="navigatorListQueryValues"
        :required-external-criteria-keys="navigatorListCriteriaKeys"
        :mode="listMode"
        quick-search-placeholder="搜索动态记录"
        empty-description="暂无动态记录"
        @loaded="handleLoaded"
        @mode-change="handleListModeChange"
        @restored="handleRecycleBinRestore"
        @select="selectListDetailRecord"
        @row-dblclick="openListRecord"
        @action="handleListAction"
        @row-action="handleRowAction"
        @batch-action="
          (action, records, _event, clearSelection) => handleBatchAction(action, records, clearSelection)
        "
      />

      <RecordDetailPanel class="dynamic-list-detail-card" :title="detailTitle">
        <template #actions>
          <template v-if="editorMode !== 'view'">
            <RecordPanelButton :disabled="saving" @click="closeDetail">取消</RecordPanelButton>
            <RecordPanelButton
              type="primary"
              :loading="saving"
              :disabled="detailLoading || detailLoadFailed"
              @click="saveRecord"
            >
              {{ saving ? '保存中' : '保存' }}
            </RecordPanelButton>
          </template>
          <template v-else-if="!recycleBinDetailActive">
            <RecordActionBar
              v-if="selectedRecord?.id != null && enhancementDetailActions.length > 0"
              :context="context"
              :record-id="String(selectedRecord.id)"
              :actions="enhancementDetailActions"
              @action="handleDetailAction"
            />
            <ModuleActionButton :context="context" action-code="create" @click="createRootRecord">
              新建
            </ModuleActionButton>
            <ModuleActionButton
              :context="context"
              action-code="update"
              :disabled="!selectedRecord"
              @click="selectedRecord && editRecord(selectedRecord)"
            >
              编辑
            </ModuleActionButton>
            <ModuleActionButton
              :context="context"
              action-code="delete"
              :loading="saving"
              danger
              :disabled="!selectedRecord"
              @click="selectedRecord && deleteRecord(selectedRecord)"
            >
              删除
            </ModuleActionButton>
          </template>
        </template>
        <template #status>
          <RecordStatusSwitch
            v-if="!recycleBinDetailActive && editorMode === 'view' && selectedRecord"
            :enabled="selectedRecord.enabled !== false"
            :disabled="!canToggleEnabled"
            :loading="togglingEnabled"
            :show-label="false"
            @change="toggleEnabled"
          />
        </template>

        <RecordPanelState
          v-if="!selectedRecord && editorMode === 'view'"
          :description="runtimePage?.detail?.emptyDescription ?? '请选择记录，或新建记录'"
        />
        <RecordPanelState v-else-if="detailLoading" loading loading-tip="加载记录详情" description="" />
        <RecordPanelState v-else-if="detailLoadFailed" description="详情加载失败，请重新选择记录" />
        <template v-else-if="editingRecord">
          <template v-if="editorMode === 'view'">
            <RecordDetailFields
              :record="editingRecord as RecordFormRecord"
              :fields="formFields"
              :exclude-field-names="['enabled']"
            />
            <RecordDetailExtensionSection
              v-for="section in enhancementDetailSections"
              :key="section.key"
              :title="section.title"
            >
              <component :is="section.component" :context="detailSectionContext(editingRecord)" />
            </RecordDetailExtensionSection>
          </template>
          <div v-else class="dynamic-form">
            <RecordFormFields
              :record="editingRecord as RecordFormRecord"
              :fields="formFields"
              :form-session-key="formSessionKey"
              :option-context="context"
              :picker-configs="referencePickerConfigs"
              :disabled="saving"
              :exclude-field-names="['enabled']"
              @update:field="updateDraftField"
            />
          </div>
          <RecordMetaSection v-if="editorMode !== 'create'" :record="editingRecord" show-sort-order />
        </template>
      </RecordDetailPanel>
    </ManagementWorkspace>

    <ManagementWorkspace v-else-if="treeModule" class="dynamic-tree-workspace">
      <ManagementExplorerColumn>
        <RecordExplorerPanel
          :title="`${title}树`"
          :refresh-title="`刷新${title}树`"
          :search-keyword="treeSearchKeyword"
          search-placeholder="搜索树节点"
          @update:search-keyword="treeSearchKeyword = $event"
          @refresh="treeReloadKey += 1"
        >
          <template #actions>
            <ModuleActionButton
              class="record-panel-create-button"
              :context="context"
              action-code="create"
              icon-only
              title="新建根节点"
              @click="createRootRecord"
            />
          </template>
          <TreeRecordExplorer
            :context="context"
            :selected-id="selectedTreeRecord?.id == null ? undefined : String(selectedTreeRecord.id)"
            :reload-key="treeReloadKey"
            :keyword="treeSearchKeyword"
            search-mode="none"
            search-trigger="external"
            empty-description="暂无记录"
            @select="selectTreeRecord"
            @loaded="handleTreeLoaded"
          />
        </RecordExplorerPanel>
      </ManagementExplorerColumn>

      <RecordDetailPanel class="dynamic-tree-card" :title="detailTitle">
        <template #actions>
          <template v-if="editorMode !== 'view'">
            <RecordPanelButton :disabled="saving" @click="closeTreeCardEditor">取消</RecordPanelButton>
            <RecordPanelButton
              type="primary"
              :loading="saving"
              :disabled="
                detailLoading ||
                detailLoadFailed ||
                context.can(editorMode === 'create' ? 'create' : 'update') !== true
              "
              @click="saveRecord"
            >
              {{ saving ? '保存中' : '保存' }}
            </RecordPanelButton>
          </template>
          <template v-else>
            <RecordActionBar
              v-if="selectedRecord?.id != null && enhancementDetailActions.length > 0"
              :context="context"
              :record-id="String(selectedRecord.id)"
              :actions="enhancementDetailActions"
              @action="handleDetailAction"
            />
            <ModuleActionButton
              :context="context"
              action-code="create"
              :disabled="!selectedRecord"
              @click="createChildRecord"
            >
              新建子项
            </ModuleActionButton>
            <ModuleActionButton
              :context="context"
              action-code="update"
              :disabled="!selectedRecord"
              @click="selectedRecord && editRecord(selectedRecord)"
            >
              编辑
            </ModuleActionButton>
            <ModuleActionButton
              :context="context"
              action-code="delete"
              :loading="saving"
              danger
              :disabled="!selectedRecord"
              @click="selectedRecord && deleteRecord(selectedRecord)"
            >
              删除
            </ModuleActionButton>
          </template>
        </template>
        <template #status>
          <RecordStatusSwitch
            v-if="editorMode === 'view' && selectedRecord"
            :enabled="selectedRecord.enabled !== false"
            :disabled="!canToggleEnabled"
            :loading="togglingEnabled"
            :show-label="false"
            @change="toggleEnabled"
          />
        </template>

        <RecordPanelState
          v-if="!selectedRecord && editorMode === 'view'"
          description="请选择标签，或新建根标签"
        />
        <RecordPanelState v-else-if="detailLoading" loading loading-tip="加载记录详情" description="" />
        <RecordPanelState v-else-if="detailLoadFailed" description="详情加载失败，请重新选择标签" />
        <template v-else-if="editingRecord">
          <template v-if="editorMode === 'view'">
            <RecordDetailFields
              :record="editingRecord as RecordFormRecord"
              :fields="formFields"
              :exclude-field-names="['enabled']"
            />
            <RecordDetailExtensionSection
              v-for="section in enhancementDetailSections"
              :key="section.key"
              :title="section.title"
            >
              <component :is="section.component" :context="detailSectionContext(editingRecord)" />
            </RecordDetailExtensionSection>
          </template>
          <div v-else class="dynamic-form">
            <RecordFormFields
              :record="editingRecord as RecordFormRecord"
              :fields="formFields"
              :form-session-key="formSessionKey"
              :option-context="context"
              :picker-configs="referencePickerConfigs"
              :exclude-field-names="['enabled']"
              @update:field="updateDraftField"
            />
          </div>
          <RecordMetaSection v-if="editorMode !== 'create'" :record="editingRecord" show-sort-order />
        </template>
      </RecordDetailPanel>
    </ManagementWorkspace>

    <RecordQueryListPanel
      v-else
      class="dynamic-list"
      :context="context"
      :title="title"
      :selected-key="selectedRecord?.id"
      :reload-key="reloadKey"
      :standard-crud-actions="true"
      :standard-crud-row-actions="true"
      :standard-crud-row-action-keys="standardCrudRowActionKeys"
      :standard-crud-row-action-codes="standardCrudRowActionCodes"
      :extra-actions="enhancementActions"
      :additional-columns="enhancementColumns"
      :cell-components="enhancementCellComponents"
      :extra-row-actions-of="enhancementRowActionsFor"
      :action-column-width="pageEnhancement?.list?.actionColumnWidth"
      :batch-actions="enhancementBatchActions"
      :ui-config-id="listUiConfigId"
      :query-template-id="listQueryTemplateId"
      :ready="pageReady"
      :mode="listMode"
      quick-search-placeholder="搜索动态记录"
      empty-description="暂无动态记录"
      @loaded="handleLoaded"
      @mode-change="handleListModeChange"
      @restored="handleRecycleBinRestore"
      @select="selectStandaloneListRecord"
      @row-dblclick="openListRecord"
      @action="handleListAction"
      @row-action="handleRowAction"
      @batch-action="
        (action, records, _event, clearSelection) => handleBatchAction(action, records, clearSelection)
      "
    />

    <RecordModeDrawer
      v-if="!treeModule && !flatManagementPage && (!listDetailCardPage || narrowDetailSurface)"
      :open="detailOpen"
      :title="detailTitle"
      :width="enhancementDetailDrawer?.width"
      :mode="editorMode"
      :loading="detailLoading"
      :load-failed="detailLoadFailed"
      :edit-available="
        !recycleBinDetailActive &&
        !enhancementDetailDrawer &&
        Boolean(selectedRecord) &&
        !detailLoading &&
        !detailLoadFailed &&
        editorMode === 'view'
      "
      :save-available="
        !recycleBinDetailActive && !detailLoading && !detailLoadFailed && editorMode !== 'view'
      "
      :saving="saving"
      @close="closeDetail"
      @retry="retryLoadDetail"
      @edit="selectedRecord && editRecord(selectedRecord)"
      @save="saveRecord"
    >
      <template #status>
        <RecordStatusSwitch
          v-if="
            !recycleBinDetailActive && !enhancementDetailDrawer && editorMode === 'view' && selectedRecord
          "
          :enabled="selectedRecord.enabled !== false"
          :disabled="!canToggleEnabled"
          :loading="togglingEnabled"
          :show-label="false"
          @change="toggleEnabled"
        />
      </template>
      <template #viewOperation>
        <RecordActionBar
          v-if="!recycleBinDetailActive && selectedRecord?.id != null && enhancementDetailActions.length > 0"
          :context="context"
          :record-id="String(selectedRecord.id)"
          :actions="enhancementDetailActions"
          @action="handleDetailAction"
        />
      </template>
      <template #view>
        <template v-if="editingRecord">
          <component
            :is="enhancementDetailDrawer.component"
            v-if="enhancementDetailDrawer"
            :context="detailDrawerContext(editingRecord)"
          />
          <template v-else>
            <RecordDetailFields
              :record="editingRecord as RecordFormRecord"
              :fields="formFields"
              :exclude-field-names="['enabled']"
            />
            <RecordDetailExtensionSection
              v-for="section in enhancementDetailSections"
              :key="section.key"
              :title="section.title"
            >
              <component :is="section.component" :context="detailSectionContext(editingRecord)" />
            </RecordDetailExtensionSection>
          </template>
        </template>
      </template>
      <template #form>
        <div v-if="editingRecord" class="dynamic-form">
          <RecordFormFields
            :record="editingRecord as RecordFormRecord"
            :fields="formFields"
            :form-session-key="formSessionKey"
            :option-context="context"
            :picker-configs="referencePickerConfigs"
            :exclude-field-names="['enabled']"
            @update:field="updateDraftField"
          />
        </div>
      </template>
    </RecordModeDrawer>

    <RecordDetailDrawer
      v-if="enhancementDrawer"
      :open="true"
      :title="enhancementDrawer.definition.title"
      :width="enhancementDrawer.definition.width"
      @close="closeEnhancementDrawer"
    >
      <component :is="enhancementDrawer.definition.component" :context="enhancementDrawer.context" />
    </RecordDetailDrawer>
  </section>
  <section v-else class="dynamic-module-unsupported">
    <h2>{{ title }}</h2>
    <p>{{ unsupportedPageModeText }}</p>
  </section>
</template>

<style scoped>
.dynamic-module-workspace {
  min-width: 0;
  min-height: calc(100vh - 116px);
}

/*
 * Tree metadata is loaded at runtime, so this boundary cannot be declared by
 * the menu descriptor. Keep the workbench tab fixed and let the explorer and
 * detail panels manage their own vertical scroll areas.
 */
.dynamic-module-workspace--tree {
  height: 100%;
  min-height: 0;
}

/* Tree and flat-management templates both own a fixed workbench area. */
.dynamic-module-workspace--flat-management {
  height: 100%;
  min-height: 0;
}

.dynamic-list {
  min-width: 0;
}

.dynamic-tree-workspace {
  height: 100%;
  min-height: 0;
}

.dynamic-list-detail-card {
  min-width: var(--muyun-management-detail-min-width);
}

.dynamic-tree-card {
  min-width: 0;
}

.dynamic-tree-workspace :deep(.record-panel-create-button) {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 999px;
}

.dynamic-scope-editor-panel {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 3;
  display: grid;
  align-content: start;
  gap: 12px;
  max-height: min(420px, 68%);
  min-height: 0;
  padding: 12px;
  border: 1px solid var(--muyun-border);
  border-top-color: var(--muyun-border-subtle);
  border-radius: 8px 8px 0 0;
  background: var(--muyun-surface);
  box-shadow:
    0 -1px 0 rgb(15 23 42 / 4%),
    0 -12px 28px rgb(15 23 42 / 12%);
  overflow: auto;
}

.dynamic-scope-editor-drawer-enter-active,
.dynamic-scope-editor-drawer-leave-active {
  transition:
    transform 0.18s ease,
    opacity 0.18s ease;
}

.dynamic-scope-editor-drawer-enter-from,
.dynamic-scope-editor-drawer-leave-to {
  opacity: 0;
  transform: translateY(100%);
}

.dynamic-scope-editor-header,
.dynamic-scope-editor-actions {
  display: flex;
  align-items: center;
}

.dynamic-scope-editor-header {
  justify-content: space-between;
  gap: 10px;
}

.dynamic-scope-editor-header h3 {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--muyun-text);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dynamic-scope-editor-actions {
  flex: 0 0 auto;
  gap: 8px;
}

.dynamic-scope-editor-form {
  grid-template-columns: minmax(0, 1fr);
}

.dynamic-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 12px;
  row-gap: 16px;
  --muyun-record-form-label-gap: 8px;
}

.dynamic-module-unsupported {
  display: grid;
  align-content: center;
  justify-items: center;
  min-height: calc(100vh - 116px);
  color: var(--muyun-text-muted);
  text-align: center;
}

.dynamic-module-unsupported h2 {
  margin: 0 0 8px;
  color: var(--muyun-text);
  font-size: 18px;
  font-weight: 600;
}

.dynamic-module-unsupported p {
  margin: 0;
  font-size: 13px;
}

@media (max-width: 720px) {
  .dynamic-module-workspace--tree {
    height: auto;
    min-height: calc(100vh - 116px);
  }

  .dynamic-module-workspace--flat-management {
    height: auto;
    min-height: calc(100vh - 116px);
  }

  .dynamic-tree-workspace {
    height: auto;
    min-height: 0;
  }

  .dynamic-form {
    grid-template-columns: 1fr;
  }
}
</style>
