<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, toRaw, watch } from 'vue';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import {
  createQueryScopedTreeModuleContext,
  ManagementExplorerColumn,
  ManagementWorkspace,
  listDetailWorkspaceMinWidth,
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
  DrawerTitleActions,
  RecordPanelButton,
  RecordPanelState,
  RecordQueryListPanel,
  RecycleBinModeButton,
  RecordStatusSwitch,
  StaticManagementLayout,
  TreeRecordExplorer,
  confirmAction,
  parentRecordConstraints,
  applyReferenceDependencyClears,
  presentPlatformError,
  recordPickerModeOf,
  providePageLayout,
  resolveRecordFormFields,
  useRecycleBinExplorerMode,
  UiModal,
  type RecordFormFieldPickerConfig,
  type RecordPickerRecord,
  type CrudRecordListBase,
  type RecordExplorerItemDescriptor,
  type RecordActionItem,
  type RecordQueryListCellComponent,
  type StandardCrudRowActionKey,
  type QueryListRecord,
  type RecordFormRecord,
} from '@muyun/platform-components';
import type {
  StandardModulePageDescriptor,
  MenuPageMode,
  ResolvedDetailRelationDescriptor,
  ResolvedFormComputeRuleDescriptor,
  ResolvedModuleUiDescriptor,
  ResolvedPageTreeResourceDescriptor,
  ResolvedPageTextDescriptor,
  ResolvedPageListRelationExpansionDescriptor,
  ResolvedViewDescriptor,
  RecordInlineAction,
  RouteQueryValue,
} from '@muyun/web-contracts';
import { hasExecutableDetailRelationQueryContract } from '@muyun/web-contracts';
import { FormulaRuntime } from '../formula/FormulaRuntime';
import {
  createModuleContext,
  createReferenceResolveClient,
  createStaticResourceTreeClient,
  userPreferences,
  useModuleContext,
  withHttpHeaders,
  type ModuleContext,
  type ModuleRecordActionAvailability,
  type ModuleTreeClient,
} from '@muyun/web-core';
import { canMutateModuleDetail } from './moduleDetailStateModel';
import {
  createReadonlyCardRecordSnapshot,
  resolveModulePageEnhancement,
  type ModulePageActionContribution,
  type ModulePageActionContext,
  type ModulePageActionStateContext,
  type ModulePageBatchActionContribution,
  type ModulePageColumnContribution,
  type ModulePageDetailDrawer,
  type ModulePageDetailSection,
  type ModulePageDrawer,
  type ModulePageCardAssistantContext,
  type ModulePageFormContribution,
  type ModulePageFormFieldPolicy,
  type ModulePageNavigatorEnhancement,
  type ModulePageNavigatorExtensionContext,
  type ModulePageNavigatorSelection,
  type ModulePageSelectionPresentation,
  type ModulePageListRowExpansionContext,
  type ModulePageRecordActionContribution,
  type ModulePageWorkspaceView,
} from './modulePageEnhancements';
import { useModulePageNavigation } from './modulePageNavigation';
import { modulePageListRefreshRegistry } from './modulePageListRefresh';
import {
  normalizeDetailSurfacePreference,
  restoreDetailSurfacePreference,
  saveDetailSurfacePreference,
  type DetailSurfacePreference,
} from './detailSurfacePreference';
import { normalizeListPageSize, restoreListPageSize, saveListPageSize } from './listPageSizePreference';
import ModuleRecordDetailActions from './ModuleRecordDetailActions.vue';
import ModulePageDetailRelations from './ModulePageDetailRelations.vue';
import ModulePageListExpansionSurface from './ModulePageListExpansionSurface.vue';
import ModulePageRecordContent from './ModulePageRecordContent.vue';
import NavigatorManagementEditor from './NavigatorManagementEditor.vue';
import PageNavigatorExplorer from './PageNavigatorExplorer.vue';
import { shouldHideSingleResultNavigator } from './navigatorVisibility';
import { type RecordDetailTransitionOptions, useRecordDetailController } from './recordDetailController';
import {
  externalPageContextCriteriaKeys,
  requiredNavigatorListScopeCriteriaKeys,
  resolvePageContextTargetValues,
  reuseEquivalentQueryValues,
} from './pageContextRuntime';
import { FormComputeCoordinator } from './formComputeCoordinator';
import { useModulePageBootstrap } from './composables/useModulePageBootstrap';
import { useNavigatorRuntime, type NavigatorLevelRuntime } from './composables/useNavigatorRuntime';
import { useModulePageActions } from './composables/useModulePageActions';
import { useRecordEditingSession } from './composables/useRecordEditingSession';
import { useModulePageListSession } from './composables/useModulePageListSession';
import { useModulePageDetailActionRuntime } from './composables/useModulePageDetailActionRuntime';
import { useModulePageDetailExtensionRuntime } from './composables/useModulePageDetailExtensionRuntime';

/**
 * Descriptor-driven CRUD runner for every standard platform module page.
 */
defineOptions({ name: 'ModulePageHost' });

const props = defineProps<{
  descriptor: StandardModulePageDescriptor;
}>();

const currentUser = useCurrentUserContext();
const baseContext = useModuleContext<QueryListRecord>({
  moduleAlias: props.descriptor.target.moduleAlias,
});
const disabledStandardActions = ref<readonly string[]>([]);
// Resolve the module-wide contribution before creating any module transport.
// A selection-aware page must attach its initial opaque selection even to the
// first standard runtime request; view-specific contributions still reconcile
// below once the descriptor is loaded.
const initialPageEnhancement = resolveModulePageEnhancement(
  props.descriptor.target.moduleAlias,
  undefined,
  props.descriptor.menuId,
);
// Enhancements may carry Vue component constructors. Keep this policy shallow so
// the host observes policy replacement without proxying application-owned components.
const navigatorEntryPolicy = shallowRef<ModulePageNavigatorEnhancement>(
  initialPageEnhancement?.navigator ?? {},
);
const navigatorExtensionSelection = ref(initialSelectionOf(navigatorEntryPolicy.value.extension?.selection));
const navigatorExtensionPresentation = ref(
  initialPresentationOf(navigatorEntryPolicy.value.extension?.selection),
);
const resolvedSelectionFormDefaults = ref<Record<string, unknown>>({});
let resolvedSelectionFormDefaultsRequest: Promise<void> | undefined;
// TREE_MANAGEMENT may declare a child resource as its main tree.  Keep the
// page module as the authorization/runtime owner and switch only the standard
// CRUD/tree transport after the last navigator range is selected.
const activeTreeResourceClient = ref<ModuleTreeClient<QueryListRecord>>();
const treeReloadKey = ref(0);
const selectedTreeRecord = ref<QueryListRecord>();
const moduleRequestPrefix = `/${props.descriptor.target.moduleAlias}`;
const pageContextHeader = ref<string>();
const pageSelectionHeader = computed(() => {
  const selection = navigatorExtensionSelection.value;
  return selection ? JSON.stringify(selection) : undefined;
});
const rawContext = createModuleContext<QueryListRecord>({
  moduleAlias: props.descriptor.target.moduleAlias,
  http: withHttpHeaders(
    baseContext.http,
    () => ({
      'X-MuYun-Menu-Id': props.descriptor.menuId,
      'X-MuYun-Page-Context': pageContextHeader.value,
      'X-MuYun-Page-Selection': pageSelectionHeader.value,
    }),
    (request) =>
      request.path === moduleRequestPrefix ||
      request.path.startsWith(`${moduleRequestPrefix}/`) ||
      request.path === `/platform.module/${props.descriptor.target.moduleAlias}/context`,
  ),
});
const context: ModuleContext<QueryListRecord> = {
  ...rawContext,
  crud: {
    querySchema: (options) => (activeTreeResourceClient.value ?? rawContext.crud).querySchema(options),
    query(request) {
      if (activeTreeResourceClient.value) return activeTreeResourceClient.value.query(request);
      const conditions = emptyNavigatorListScope.value ?? [];
      return rawContext.crud.query({
        ...request,
        conditions: [...(request?.conditions ?? []), ...conditions],
      });
    },
    view: (id) => (activeTreeResourceClient.value ?? rawContext.crud).view(id),
    insert: (record) => (activeTreeResourceClient.value ?? rawContext.crud).insert(record),
    update: (id, record) => (activeTreeResourceClient.value ?? rawContext.crud).update(id, record),
    delete: (id, request) => (activeTreeResourceClient.value ?? rawContext.crud).delete(id, request),
    enable: (id, request) => (activeTreeResourceClient.value ?? rawContext.crud).enable(id, request),
    disable: (id, request) => (activeTreeResourceClient.value ?? rawContext.crud).disable(id, request),
  },
  abilities: {
    ...rawContext.abilities,
    crud: () => activeTreeResourceClient.value ?? rawContext.crud,
    tree: () => activeTreeResourceClient.value ?? rawContext.abilities.tree(),
    enable: () => activeTreeResourceClient.value ?? rawContext.abilities.enable(),
    tryCrud: () => activeTreeResourceClient.value ?? rawContext.abilities.tryCrud(),
    tryTree: () => activeTreeResourceClient.value ?? rawContext.abilities.tryTree(),
    tryEnable: () => activeTreeResourceClient.value ?? rawContext.abilities.tryEnable(),
  },
  can(actionCode, recordId) {
    const resolvedActionCode = treeResourceActionCode(actionCode);
    if (disabledStandardActions.value.includes(resolvedActionCode)) {
      return false;
    }
    return rawContext.can(resolvedActionCode, recordId);
  },
  action: (actionCode, recordId) => rawContext.action(treeResourceActionCode(actionCode), recordId),
  runtimeAction: (actionCode) => rawContext.runtimeAction(treeResourceActionCode(actionCode)),
  recordActions: async (recordId) => treeResourceRecordActions(await rawContext.recordActions(recordId)),
  ...(rawContext.recordActionsBatch
    ? {
        recordActionsBatch: async (recordIds: string[]) =>
          (await rawContext.recordActionsBatch!(recordIds)).map(treeResourceRecordActions),
      }
    : {}),
  recordActionsSnapshot: (recordId) => {
    const availability = rawContext.recordActionsSnapshot(recordId);
    return availability ? treeResourceRecordActions(availability) : undefined;
  },
};

/** Resource records inherit only their contribution's record-action decisions. */
function treeResourceRecordActions(
  availability: ModuleRecordActionAvailability,
): ModuleRecordActionAvailability {
  const resource = treeResource.value?.resource;
  if (!resource) return availability;
  const prefix = `${resource}_`;
  return {
    ...availability,
    actions: availability.actions
      .filter((action) => action.actionCode.startsWith(prefix))
      .map((action) => ({ ...action, actionCode: action.actionCode.slice(prefix.length) })),
  };
}

function treeResourceActionCode(actionCode: string) {
  const resource = treeResource.value?.resource;
  return resource &&
    ['create', 'view', 'update', 'delete', 'query', 'tree', 'sort', 'enable', 'disable'].includes(actionCode)
    ? `${resource}_${actionCode}`
    : actionCode;
}
const modulePageNavigation = useModulePageNavigation();
const { presentActionSuccess, runEnhancementAction } = useModulePageActions();
const detail = useRecordDetailController<QueryListRecord>();
const {
  invalidatePendingRequests,
  openRecord: loadRecord,
  openRecycleBinRecord,
} = useRecordEditingSession(context, detail, () => {
  detailRelationReloadKey.value += 1;
});
function openRecord(
  record: QueryListRecord,
  mode: 'edit' | 'view',
  options: RecordDetailTransitionOptions = {},
) {
  return loadRecord(
    record,
    mode,
    options,
    mode === 'view' && enhancementDetailDrawer.value?.loadRecord === false,
  );
}
const {
  record: selectedRecord,
  draft: editingRecord,
  mode: editorMode,
  open: detailOpen,
  saving,
  formSessionKey,
  togglingEnabled,
  loading: detailLoading,
  loadFailed: detailLoadFailed,
} = detail;
// RecordFormFields owns parser and renderer diagnostics. Persist only its
// validity fact here; the host remains responsible for the save boundary.
const mainFormValid = ref(true);
const relationDraftValid = ref(true);
const formValidationRequestKey = ref(0);
const localEditFormValid = ref(true);
function updateMainFormValidity(validity: { valid: boolean }) {
  mainFormValid.value = validity.valid;
}
function updateEmbeddedChildren(relationField: string, records: QueryListRecord[]) {
  if (!editingRecord.value) return;
  if (JSON.stringify(editingRecord.value[relationField] ?? []) === JSON.stringify(records)) return;
  editingRecord.value = { ...editingRecord.value, [relationField]: records };
}
function updateRelationDraftValidity(valid: boolean) {
  relationDraftValid.value = valid;
}
function updateLocalEditFormValidity(validity: { valid: boolean }) {
  localEditFormValid.value = validity.valid;
}
watch([() => editingRecord.value?.id, formSessionKey], () => {
  mainFormValid.value = true;
  relationDraftValid.value = true;
});
watch(editorMode, (mode) => {
  if (mode !== 'edit') {
    relationDraftValid.value = true;
  }
});
const { pageBootstrap, pageBootstrapError, loadPageBootstrap } = useModulePageBootstrap(
  context,
  () => props.descriptor.menuId,
);
const {
  formFields,
  detailDisplayFields,
  runtimeUiDescriptor,
  runtimePage,
  runtimePageResolved,
  treeModule,
  navigatorLevels,
  pageContextBindings,
  selectedNavigatorRecords,
  navigatorSingleResultKeys,
  navigatorDismissedSelectionKeys,
  setNavigatorEntrySelection,
  navigatorEntrySelectionPendingFor,
  resolveNavigatorEntrySelection,
  isCurrentNavigatorEntrySelection,
  loadRuntimeForm,
} = useNavigatorRuntime(context, baseContext.http);
const navigatorEntryReloadKeys = ref<Record<string, number>>({});
watch(
  () => props.descriptor.params,
  (params) => applyNavigatorEntrySelectionChange(setNavigatorEntrySelection(params)),
  { immediate: true, deep: true },
);
const treeResource = computed<ResolvedPageTreeResourceDescriptor | undefined>(
  () => runtimePage.value?.treeResource,
);
// The list-ready condition and its request header must observe one synchronous
// navigator snapshot.  A pre-flush watcher leaves a reactive-tick gap in which
// a required navigator has selected a record but the next list request still
// carries no page context.
watch(
  selectedNavigatorRecords,
  (records) => {
    const values = Object.entries(records).flatMap(([key, record]) =>
      record?.id == null ? [] : [[key, String(record.id)] as const],
    );
    pageContextHeader.value = values.length === 0 ? undefined : JSON.stringify(Object.fromEntries(values));
  },
  { deep: true, immediate: true, flush: 'sync' },
);
const treeResourceScopeRecord = computed(() => {
  const resource = treeResource.value;
  return resource ? selectedNavigatorRecords.value[resource.scopeNavigatorKey] : undefined;
});
const treeResourceScopeReady = computed(() => {
  const resource = treeResource.value;
  if (!resource) return true;
  const scopeRecord = treeResourceScopeRecord.value;
  if (scopeRecord?.id == null) return false;
  return (
    !resource.scopeRecordField ||
    String(scopeRecord[resource.scopeRecordField] ?? '') === resource.scopeRecordEquals
  );
});
const mainTreeScopeReady = computed(() =>
  treeResource.value ? treeResourceScopeReady.value : navigatorListScopeReady.value,
);
watch(
  [treeResource, treeResourceScopeRecord, treeResourceScopeReady],
  ([resource, scopeRecord, scopeReady], [previousResource, previousScopeRecord, previousScopeReady]) => {
    const scopeId = scopeRecord?.id == null ? undefined : String(scopeRecord.id);
    activeTreeResourceClient.value =
      resource && scopeId && scopeReady
        ? createStaticResourceTreeClient<QueryListRecord>(
            context.http,
            `/${context.moduleAlias}/tree-resources/${encodeURIComponent(resource.resource)}/${encodeURIComponent(scopeId)}`,
          )
        : undefined;

    const scopeChanged =
      previousResource !== undefined &&
      (resource?.resource !== previousResource?.resource ||
        scopeId !== (previousScopeRecord?.id == null ? undefined : String(previousScopeRecord.id)) ||
        scopeReady !== previousScopeReady);
    if (!scopeChanged) return;

    // The tree explorer keeps a stable context object. A navigator scope switch
    // must therefore explicitly invalidate its old resource tree and detail;
    // otherwise a former scope's node is read through the newly scoped client.
    invalidatePendingRequests();
    treeReloadKey.value += 1;
    selectedTreeRecord.value = undefined;
    detail.close();
    selectedRecord.value = undefined;
    editingRecord.value = undefined;
  },
  { immediate: true },
);
const detailRelationReloadKey = ref(0);
const {
  drawer: enhancementDrawer,
  drawerOpen: enhancementDrawerOpen,
  sectionContext: detailSectionContext,
  recordViewContext,
  openDrawer: openEnhancementDrawer,
  closeDrawer: closeEnhancementDrawer,
  disposeDrawer: disposeEnhancementDrawer,
} = useModulePageDetailExtensionRuntime({
  module: context,
  scope: () => modulePageActionStateContext().scope,
  refreshList,
  reload: reloadModulePage,
  closeDetail,
});
const treeSearchKeyword = ref('');
const flatManagementSearchKeyword = ref('');
const flatManagementReloadKey = ref(0);
const {
  listMode,
  reloadKey,
  cardAssistantRecords,
  handleLoaded,
  handleFlatManagementLoaded: loadFlatManagementRecords,
  setCardAssistantRecords,
  resetFlatManagementSelection,
  handleListModeChange,
  handleRecycleBinRestore,
  selectListDetailRecord: selectListDetail,
  selectStandaloneListRecord,
  openListRecord,
} = useModulePageListSession({
  selectedRecord,
  saving,
  resetDetail: () => {
    detail.close();
    editingRecord.value = undefined;
  },
  invalidateDetailLoad: invalidatePendingRequests,
  resetTreeSelection: () => {
    selectedTreeRecord.value = undefined;
  },
  openRecord: (record) => void openRecordView(record),
  openRecycleBinRecord: (record) => void openRecycleBinRecord(record),
});
const navigatorManagementDetail = useRecordDetailController<QueryListRecord>();
const navigatorManagementLevel = ref<NavigatorLevelRuntime>();
const navigatorManagementTogglingEnabled = ref(false);
const navigatorManagementFormValid = ref(true);
const navigatorManagementFormValidationRequestKey = ref(0);
let navigatorManagementSession = 0;
const scopeSearchKeyword = ref('');
const scopeReloadKey = ref(0);
const narrowDetailSurface = ref(false);
const detailSurfacePreference = ref<DetailSurfacePreference | undefined>(
  normalizeDetailSurfacePreference(
    userPreferences.get(`module-page.detail-surface.${context.moduleAlias}`, undefined),
  ),
);
const listPageSize = ref(
  normalizeListPageSize(userPreferences.get(`module-page.list-page-size.${context.moduleAlias}`, 20)),
);
const workspaceElement = ref<HTMLElement>();
let unregisterListRefresh: (() => void) | undefined;
let workspaceResizeObserver: ResizeObserver | undefined;
let removeWorkspaceResizeFallback: (() => void) | undefined;
let detailSurfacePreferenceRestoreRevision = 0;
let detailSurfacePreferenceWrite = Promise.resolve();
let listPageSizePreferenceRestoreRevision = 0;
let listPageSizePreferenceWrite = Promise.resolve();

type NavigatorRecord = { id?: string; version?: number };

const navigatorManagementFormFields = computed(() => {
  const level = navigatorManagementLevel.value;
  if (!level) return resolveRecordFormFields(undefined);
  return resolveRecordFormFields(
    level.context.runtime.snapshot()?.uiDescriptor,
    undefined,
    level.descriptor.management?.editorSurface,
  );
});
const navigatorManagementPageEnhancement = computed(() => {
  const level = navigatorManagementLevel.value;
  return level ? resolveModulePageEnhancement(level.context.moduleAlias) : undefined;
});
const navigatorManagementFormContributions = computed<readonly ModulePageFormContribution[]>(
  () => navigatorManagementPageEnhancement.value?.form?.contributions ?? [],
);
const navigatorManagementFormFieldPolicies = computed<readonly ModulePageFormFieldPolicy[]>(
  () => navigatorManagementPageEnhancement.value?.form?.fieldPolicies ?? [],
);
const navigatorManagementPickerConfigs = computed<Record<string, RecordFormFieldPickerConfig>>(() => {
  const level = navigatorManagementLevel.value;
  if (!level) return {};
  const configs: Record<string, RecordFormFieldPickerConfig> = {};
  for (const field of navigatorManagementFormFields.value.values()) {
    const reference = field.reference;
    if (!reference) continue;
    configs[field.fieldRef.fieldName] = {
      context: createModuleContext({ http: baseContext.http, moduleAlias: reference.targetModuleAlias }),
      mode: recordPickerModeOf(reference.pickerMode),
      allowClear: !field.required?.constant,
    };
  }
  if (level.tree && navigatorManagementFormFields.value.has('parentId')) {
    configs.parentId = {
      context: level.context,
      mode: 'tree',
      placeholder: '根目录留空',
      allowClear: true,
      // The navigator source itself may be constrained by an upstream selection.
      // Its reference-tree endpoint receives that scope in the query body, so the
      // picker can resolve a just-selected parent back to its display title.
      loadTree: async () => {
        const tree = level.context.abilities.tryTree();
        if (!tree) return [];
        return (
          await tree.tree({
            externalQueryValues: navigatorExplorerQueryValues(level.descriptor.key),
          })
        ).records;
      },
      constraints: parentRecordConstraints(
        navigatorManagementDetail.draft.value?.id == null
          ? undefined
          : String(navigatorManagementDetail.draft.value.id),
      ),
    };
  }
  return configs;
});
const navigatorManagementTitle = computed(() => {
  const level = navigatorManagementLevel.value;
  if (!level) return '管理范围';
  return navigatorManagementDetail.mode.value === 'create'
    ? `新建${level.descriptor.title}`
    : `编辑${level.descriptor.title}`;
});

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
const {
  detailPageActions,
  localEditOpen,
  localEditSaving,
  localEditBlock,
  localEditDraft,
  localEditFields,
  handleConfiguredAction,
  submitLocalEdit,
} = useModulePageDetailActionRuntime({
  context,
  pageBootstrap,
  selectedRecord,
  editorMode,
  detail,
  refreshList,
  localEditValid: localEditFormValid,
  presentSuccess: presentModuleActionSuccess,
  presentError: (cause, source) => presentPlatformError(cause, { source, phase: 'action' }),
});
watch(localEditOpen, () => {
  // The modal may be re-opened for a different record after an invalid edit.
  // Its mounted form immediately recomputes the current validity afterwards.
  localEditFormValid.value = true;
});
/** Only server-issued executable contracts may mount a relation-list runner. */
const executableDetailRelations = computed<ResolvedDetailRelationDescriptor[]>(() =>
  [
    ...(runtimeUiDescriptor.value?.detailRelations ?? []),
    ...(pageBootstrap.value?.resolvedConfig.associationBlocks ?? []).flatMap((block) =>
      block.relation ? [block.relation] : [],
    ),
  ].filter(
    (relation, index, values) =>
      (Boolean(relation.embeddedField) || hasExecutableDetailRelationQueryContract(relation)) &&
      values.findIndex(
        (candidate) =>
          candidate.sourceModuleAlias === relation.sourceModuleAlias && candidate.code === relation.code,
      ) === index,
  ),
);
const listRelationExpansions = computed(() => {
  const expansions = runtimeUiDescriptor.value?.page?.list?.relationExpansions ?? [];
  return expansions
    .map((expansion) => {
      const relation = executableDetailRelations.value.find(
        (candidate) => candidate.code === expansion.relationCode && candidate.embeddedField,
      );
      return relation ? { expansion, relation } : undefined;
    })
    .filter(
      (
        value,
      ): value is {
        expansion: ResolvedPageListRelationExpansionDescriptor;
        relation: ResolvedDetailRelationDescriptor;
      } => value != null,
    );
});
const expandedListRowKeys = ref<string[]>([]);
const descriptorRelationExpansionEnabled = computed(() => listRelationExpansions.value.length > 0);

function updateListRowExpansion(record: QueryListRecord, expanded: boolean) {
  const id = record.id == null ? undefined : String(record.id);
  if (!id) return;
  expandedListRowKeys.value = expanded
    ? [...new Set([...expandedListRowKeys.value, id])]
    : expandedListRowKeys.value.filter((value) => value !== id);
}

function listRowExpansionContext(
  record: QueryListRecord,
  expanded: boolean,
): ModulePageListRowExpansionContext {
  return {
    module: context,
    record: createReadonlyCardRecordSnapshot(record) as QueryListRecord,
    expanded,
    refreshList,
  };
}
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
const showDetailSystemInfo = computed(() => runtimePage.value?.detail?.showSystemInfo !== false);
// A tree domain owns the explorer; TREE_MANAGEMENT owns the matching detail surface.
// Keep the capability fallback for older static modules that have not yet declared a page root.
const treeManagementPage = computed(() => runtimePage.value?.template === 'TREE_MANAGEMENT');
// TREE_MANAGEMENT keeps the resource detail in its right card even when the page module itself
// is only the navigator host and the actual tree arrives through a treeResource contribution.
const persistentTreeDetail = computed(() => treeManagementPage.value || treeModule.value);
const listDetailMinimumWidth = computed(() => listDetailWorkspaceMinWidth(navigatorExplorerCount.value));
const visibleNavigatorLevels = computed(() =>
  navigatorEntryPolicy.value.hidden
    ? []
    : navigatorLevels.value.filter((level) => {
        // `loaded` is the authoritative result cardinality. Selection may be committed in the
        // same reactive turn, so do not make visibility depend on a second snapshot of it.
        const autoHidden = shouldHideSingleResultNavigator(
          level.descriptor,
          navigatorSingleResultKeys.value.includes(level.descriptor.key),
          currentUser?.value?.tenantId,
        );
        return !autoHidden;
      }),
);
const navigatorExtension = computed(() => navigatorEntryPolicy.value.extension);
const navigatorExplorerCount = computed(
  () => visibleNavigatorLevels.value.length + (navigatorExtension.value ? 1 : 0),
);
const navigatorExtensionContext = computed<ModulePageNavigatorExtensionContext>(() => ({
  moduleAlias: context.moduleAlias,
  selectionKey: navigatorExtensionSelection.value?.key,
  selectSelectionKey: selectNavigatorExtensionSelection,
  refreshList,
  reload: reloadModulePage,
}));
function initializeNavigatorExtensionSelection(selection: ModulePageNavigatorSelection | undefined) {
  if (!selection) {
    navigatorExtensionSelection.value = undefined;
    navigatorExtensionPresentation.value = undefined;
    return;
  }
  const current = navigatorExtensionSelection.value;
  if (current?.kind === selection.kind && current.key) return;
  navigatorExtensionSelection.value = initialSelectionOf(selection);
  navigatorExtensionPresentation.value = initialPresentationOf(selection);
}
function initialSelectionOf(selection: ModulePageNavigatorSelection | undefined) {
  const key = selection?.initialKey?.(currentUser?.value);
  return selection && key ? { kind: selection.kind, key } : undefined;
}
function initialPresentationOf(selection: ModulePageNavigatorSelection | undefined) {
  return selection?.initialPresentation?.(currentUser?.value);
}
function selectNavigatorExtensionSelection(key: string, presentation?: ModulePageSelectionPresentation) {
  const selection = navigatorExtension.value?.selection;
  const normalizedKey = key.trim();
  if (!selection || !normalizedKey) {
    throw new Error('导航扩展未声明可信选择或选择键为空');
  }
  const current = navigatorExtensionSelection.value;
  if (current?.kind === selection.kind && current.key === normalizedKey) {
    navigatorExtensionPresentation.value = presentation;
    return;
  }
  navigatorExtensionSelection.value = { kind: selection.kind, key: normalizedKey };
  navigatorExtensionPresentation.value = presentation;
  void loadResolvedSelectionFormDefaults();
  refreshList();
}
async function loadResolvedSelectionFormDefaults() {
  const selection = navigatorExtensionSelection.value;
  const hasResolvedSelectionDefaults = pageContextBindings.value.some(
    (binding) => binding.source === 'RESOLVED_SELECTION' && binding.target === 'FORM_DEFAULT',
  );
  if (!selection || !hasResolvedSelectionDefaults) {
    resolvedSelectionFormDefaults.value = {};
    return;
  }
  const requestedKey = `${selection.kind}:${selection.key}`;
  let request: Promise<void>;
  request = context.http
    .request<Record<string, unknown>>({ path: `${moduleRequestPrefix}/page-context/form-defaults` })
    .then((defaults) => {
      const current = navigatorExtensionSelection.value;
      if (current && `${current.kind}:${current.key}` === requestedKey) {
        resolvedSelectionFormDefaults.value = defaults ?? {};
      }
    })
    .finally(() => {
      if (resolvedSelectionFormDefaultsRequest === request) {
        resolvedSelectionFormDefaultsRequest = undefined;
      }
    });
  resolvedSelectionFormDefaultsRequest = request;
  return request;
}
function pageText(descriptor: ResolvedPageTextDescriptor | undefined): string | undefined {
  if (!descriptor) return undefined;
  if (descriptor.text != null) return descriptor.text;
  return new FormulaRuntime().evaluatePageText(descriptor.program, {
    'selection.label': navigatorExtensionPresentation.value?.label ?? '',
    'selection.secondaryLabel': navigatorExtensionPresentation.value?.secondaryLabel ?? '',
  });
}
function navigatorLevelIndex(slotIndex: number) {
  return navigatorExtension.value ? slotIndex - 1 : slotIndex;
}
function navigatorLevelAt(slotIndex: number) {
  return visibleNavigatorLevels.value[navigatorLevelIndex(slotIndex)];
}
function isLockedNavigator(levelKey: string): boolean {
  return navigatorEntryPolicy.value.lockedEntry?.navigatorKey === levelKey;
}
const navigatorScopeUnavailableDescription = computed(
  () => navigatorEntryPolicy.value.lockedEntry?.unavailableDescription ?? '请选择模块',
);
// A hidden locked navigator has no visual explorer to emit `loaded`. Resolve
// its addressed record directly through the same REFERENCE query contract so
// the page can become ready without ever exposing a scope picker.
watch(
  [navigatorLevels, () => props.descriptor.params, () => navigatorEntryPolicy.value.lockedEntry],
  () => {
    navigatorLevels.value.forEach((level) => {
      if (!isLockedNavigator(level.descriptor.key) || !navigatorEntrySelectionPendingFor(level)) return;
      resolveLockedNavigatorEntry(level);
    });
  },
  { immediate: true, deep: true },
);
const detailSurfaceUsesDrawer = computed(
  () => narrowDetailSurface.value || detailSurfacePreference.value === 'drawer',
);
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
        secondaryField: explorer.secondaryField,
      };
});
const recordLabel = computed(
  () =>
    runtimePage.value?.treeResource?.title ??
    (flatManagementPage.value
      ? (flatManagementContent.value?.recordLabel ?? '记录')
      : (runtimePage.value?.explorer?.recordLabel ?? '记录')),
);
const modulePageTitle = computed(
  () =>
    pageText(runtimePage.value?.list?.title) ??
    runtimePage.value?.treeResource?.title ??
    runtimePage.value?.explorer?.title ??
    props.descriptor.title ??
    recordLabel.value,
);
const modulePageSubtitle = computed(() => pageText(runtimePage.value?.list?.subtitle));
const treePanelTitle = computed(() => runtimePage.value?.treeResource?.title ?? modulePageTitle.value);
const listSearchPlaceholder = computed(
  () => runtimePage.value?.list?.searchPlaceholder ?? `搜索${recordLabel.value}`,
);
const listEmptyDescription = computed(
  () =>
    runtimePage.value?.treeResource?.emptyDescription ??
    runtimePage.value?.explorer?.emptyDescription ??
    `暂无${recordLabel.value}`,
);
const detailEmptyDescription = computed(
  () =>
    runtimePage.value?.treeResource?.emptyDescription ??
    runtimePage.value?.detail?.emptyDescription ??
    `请选择${recordLabel.value}，或新建${recordLabel.value}`,
);
const mainTreeScopeDescription = computed(() => {
  const resource = treeResource.value;
  if (!resource || !treeResourceScopeRecord.value?.id) return '请先选择导航范围';
  if (resource.scopeRecordField) return '当前导航范围不支持维护此资源';
  return '请先选择导航范围';
});
const treeResourceScopeContext = computed(() => {
  const resource = treeResource.value;
  return resource ? navigatorScopeContext([resource.scopeNavigatorKey]) : undefined;
});
function navigatorScopeContext(keys: readonly string[]): string | undefined {
  const keySet = new Set(keys);
  const values = navigatorLevels.value.flatMap((level) => {
    if (!keySet.has(level.descriptor.key)) return [];
    const record = selectedNavigatorRecords.value[level.descriptor.key];
    const title = recordTitle(record);
    if (!record || !title) return [];
    const secondaryField = level.descriptor.secondaryField;
    const secondary = secondaryField == null ? undefined : record[secondaryField];
    const displayTitle = secondary && String(secondary) !== title ? `${title}（${secondary}）` : title;
    return [`${level.descriptor.title}：${displayTitle}`];
  });
  return values.length === 0 ? undefined : values.join(' · ');
}
function navigatorPanelScopeContext(levelKey: string): string | undefined {
  return navigatorScopeContext(
    pageContextBindings.value
      .filter(
        (binding) => binding.target === 'NAVIGATOR_QUERY' && binding.targetNavigatorLevelKey === levelKey,
      )
      .map((binding) => binding.sourceKey),
  );
}
const mainTreeScopeContext = computed(() => {
  if (treeResource.value) return treeResourceScopeContext.value;
  return navigatorScopeContext(
    pageContextBindings.value
      .filter((binding) => binding.target === 'LIST_QUERY' && binding.source === 'NAVIGATOR')
      .map((binding) => binding.sourceKey),
  );
});
const treeRootTitle = computed(
  () => formFields.value.get('parentId')?.treeRootTitle ?? `根${recordLabel.value}`,
);
const pageEnhancement = computed(() =>
  resolveModulePageEnhancement(context.moduleAlias, activeListView.value?.viewCode, props.descriptor.menuId),
);
const formContributions = computed(() => pageEnhancement.value?.form?.contributions ?? []);
const formFieldPolicies = computed(() => pageEnhancement.value?.form?.fieldPolicies ?? []);
let disposePageEnhancement: (() => void) | undefined;
watch(
  pageEnhancement,
  (enhancement) => {
    disposePageEnhancement?.();
    disabledStandardActions.value = enhancement?.standardActions?.disabled ?? [];
    navigatorEntryPolicy.value = enhancement?.navigator ?? {};
    initializeNavigatorExtensionSelection(navigatorEntryPolicy.value.extension?.selection);
    const dispose = enhancement?.activate?.({ module: context });
    disposePageEnhancement = typeof dispose === 'function' ? dispose : undefined;
  },
  { immediate: true },
);
watch(
  () => currentUser?.value,
  () => initializeNavigatorExtensionSelection(navigatorEntryPolicy.value.extension?.selection),
  { immediate: true },
);
watch([navigatorExtensionSelection, pageContextBindings], () => void loadResolvedSelectionFormDefaults(), {
  immediate: true,
  deep: true,
});
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
const enhancementRowExpansion = computed(() => pageEnhancement.value?.list?.rowExpansion);
const persistentListQueryControls = computed(() => runtimePage.value?.list?.persistentQueryControls ?? []);
const listQuerySummaries = computed(() => runtimePage.value?.list?.querySummaries ?? []);
const listRowExpansionEnabled = computed(
  () => descriptorRelationExpansionEnabled.value || enhancementRowExpansion.value !== undefined,
);
const enhancementDetailActions = computed<ModulePageRecordActionContribution[]>(() => {
  const record = selectedRecord.value;
  return (pageEnhancement.value?.detail?.actions ?? []).map(({ state, ...action }) => ({
    ...action,
    ...(record ? state?.(record) : { visible: false }),
  }));
});
const enhancementDetailSections = computed<ModulePageDetailSection[]>(
  () => pageEnhancement.value?.detail?.sections ?? [],
);
const enhancementDetailDrawer = computed<ModulePageDetailDrawer | undefined>(
  () => pageEnhancement.value?.recordView?.drawer,
);
const enhancementCardAssistant = computed(() => pageEnhancement.value?.card?.assistant);
const cardAssistantContext = computed<ModulePageCardAssistantContext | undefined>(() => {
  if (!enhancementCardAssistant.value) return undefined;
  const record = editingRecord.value ?? selectedRecord.value;
  return {
    module: context,
    mode: editorMode.value,
    ...(record ? { record: createReadonlyCardRecordSnapshot(toRaw(record) as Record<string, unknown>) } : {}),
    loadedRecords: cardAssistantRecords.value.map((item) =>
      createReadonlyCardRecordSnapshot(toRaw(item) as Record<string, unknown>),
    ),
    formSessionKey: formSessionKey.value,
    saving: saving.value,
    loading: detailLoading.value,
    loadFailed: detailLoadFailed.value,
  };
});
function hasCardAssistantAt(boundary: 'inside' | 'outside', position: 'top' | 'bottom') {
  const placement = enhancementCardAssistant.value?.placement;
  return Boolean(
    cardAssistantContext.value && placement?.boundary === boundary && placement.position === position,
  );
}
const detailWorkspaceView = computed<ModulePageWorkspaceView | undefined>(() => {
  const type = runtimePage.value?.detail.workspaceView?.type;
  if (!type) return undefined;
  return pageEnhancement.value?.workspaceViews?.find((view) => view.type === type);
});
const detailWorkspaceAvailable = computed(() =>
  Boolean(
    modulePageNavigation &&
    detailWorkspaceView.value &&
    selectedRecord.value?.id != null &&
    editorMode.value === 'view' &&
    !detailLoading.value &&
    !detailLoadFailed.value &&
    !recycleBinDetailActive.value,
  ),
);
const listDetailCardPage = computed(
  () => runtimePage.value?.template === 'LIST_DETAIL_CARD' && !enhancementDetailDrawer.value,
);
const constrainedManagementPage = computed(
  () => flatManagementPage.value || listDetailCardPage.value || treeManagementPage.value || treeModule.value,
);
const standardCrudRowActionKeys = computed<StandardCrudRowActionKey[]>(() =>
  enhancementDetailDrawer.value ? ['view'] : ['view', 'edit', 'delete'],
);
const pageBootstrapRequired = computed(() => Boolean(props.descriptor.menuId));
const pageReady = computed(
  () =>
    (!pageBootstrapRequired.value || pageBootstrap.value !== undefined) &&
    (!isListPage.value || runtimePageResolved.value),
);
const unsupportedPageModeText = computed(() => `${pageMode.value}入口暂未接入模块页面运行器`);
// Management templates own the workbench's available height. Their explorer
// and detail panes scroll internally instead of leaving a content-sized panel
// in the tab's document flow.
providePageLayout(
  computed(() =>
    constrainedManagementPage.value || visibleNavigatorLevels.value.length > 0
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
  return navigatorEntryPolicy.value.hidden ? undefined : navigatorLevels.value[0]?.context;
});
const pageContextSourceValues = computed(() => ({
  NAVIGATOR: Object.fromEntries(
    navigatorLevels.value.flatMap((level) => {
      const id = selectedNavigatorRecords.value[level.descriptor.key]?.id;
      return id == null ? [] : [[level.descriptor.key, id]];
    }),
  ),
  SESSION: {
    userId: currentUser?.value?.userId,
    tenantId: currentUser?.value?.tenantId,
    organizationId: currentUser?.value?.organizationId,
  },
  RESOLVED_SELECTION: resolvedSelectionFormDefaults.value,
}));
const emptyNavigatorListScope = computed(() =>
  navigatorEntryPolicy.value.emptyListScope?.({
    currentUser: currentUser?.value,
    selectedNavigatorRecords: selectedNavigatorRecords.value,
  }),
);
const navigatorListQueryValues = computed<Record<string, unknown> | undefined>(() => {
  if (navigatorEntryPolicy.value.bypassListScope || emptyNavigatorListScope.value !== undefined) {
    return undefined;
  }
  // SESSION values are resolved by the server; never echo them in a list request.
  return resolvePageContextTargetValues(pageContextBindings.value, 'LIST_QUERY', {
    NAVIGATOR: pageContextSourceValues.value.NAVIGATOR,
  });
});
const navigatorListCriteriaKeys = computed(() =>
  navigatorEntryPolicy.value.bypassListScope || emptyNavigatorListScope.value !== undefined
    ? []
    : externalPageContextCriteriaKeys(pageContextBindings.value, 'LIST_QUERY'),
);
const requiredNavigatorListScopeKeys = computed(() =>
  navigatorEntryPolicy.value.bypassListScope || emptyNavigatorListScope.value !== undefined
    ? []
    : requiredNavigatorListScopeCriteriaKeys(pageContextBindings.value),
);
/**
 * Tree endpoints may require a navigator-provided scope. Do not issue an
 * unscoped request while asynchronous navigator selection is still settling.
 */
const navigatorListScopeReady = computed(() =>
  requiredNavigatorListScopeKeys.value.every((key) => navigatorListQueryValues.value?.[key] != null),
);
const navigatorCreateDefaults = computed<Record<string, unknown>>(() => {
  const defaults =
    resolvePageContextTargetValues(
      pageContextBindings.value,
      'FORM_DEFAULT',
      pageContextSourceValues.value,
    ) ?? {};
  const resource = runtimePage.value?.treeResource;
  const scopeId = treeResourceScopeRecord.value?.id;
  return resource && scopeId != null ? { ...defaults, [resource.scopeField]: scopeId } : defaults;
});
const pickerQueryValuesByField = computed<Record<string, Record<string, RouteQueryValue>>>(() => {
  const values: Record<string, Record<string, RouteQueryValue>> = {};
  const sourceValues = pageContextSourceValues.value as Record<string, Record<string, unknown>>;
  for (const binding of pageContextBindings.value) {
    if (binding.target !== 'PICKER_QUERY' || !binding.targetPickerFieldKey) continue;
    const value = sourceValues[binding.source]?.[binding.sourceKey];
    if (
      value == null ||
      (typeof value !== 'string' && typeof value !== 'number' && typeof value !== 'boolean')
    )
      continue;
    (values[binding.targetPickerFieldKey] ??= {})[binding.targetKey] = value;
  }
  return values;
});
const pickerQueryFieldNames = computed(
  () =>
    new Set(
      pageContextBindings.value
        .filter((binding) => binding.target === 'PICKER_QUERY' && binding.targetPickerFieldKey)
        .map((binding) => binding.targetPickerFieldKey!),
    ),
);
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
  return selectedRecordActionAvailable(record.enabled === false ? 'enable' : 'disable');
});
const toggleEnabledDisabledReason = computed(() => {
  const record = selectedRecord.value;
  if (!record?.id || canToggleEnabled.value) return undefined;
  const actionCode = record.enabled === false ? 'enable' : 'disable';
  return context
    .recordActionsSnapshot(String(record.id))
    ?.actions.find((action) => action.actionCode === actionCode)?.reason;
});

function selectedRecordActionAvailable(actionCode: string) {
  const recordId = selectedRecord.value?.id;
  if (recordId == null) return false;
  const availability = context.recordActionsSnapshot(String(recordId));
  const recordAction = availability?.actions.find((action) => action.actionCode === actionCode);
  return recordAction ? recordAction.available : context.can(actionCode) === true;
}
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
        disabled: saving.value,
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
/**
 * A detail contribution belongs to the standard record-view lifecycle.  Flat
 * management must not surface it while the platform is editing, creating, or
 * showing a retained record, even when the previous selected record remains
 * in memory.
 */
function flatManagementAllowsDetailEnhancement() {
  return (
    editorMode.value === 'view' &&
    !flatManagementRecycleBin.active.value &&
    listMode.value !== 'recycleBin' &&
    selectedRecord.value != null
  );
}
const flatManagementEnhancementActions = computed<ModulePageRecordActionContribution[]>(() =>
  flatManagementAllowsDetailEnhancement() ? enhancementDetailActions.value : [],
);
const flatManagementDetailActions = computed<RecordActionItem[]>(() => [
  ...flatManagementActions.value,
  ...flatManagementEnhancementActions.value,
  ...detailPageActions.value,
]);
const recycleBinDetailActive = computed(
  () => flatManagementRecycleBin.active.value || listMode.value === 'recycleBin',
);
const detailRelationsAvailable = computed(() => {
  const selectedId = selectedRecord.value?.id;
  const editingId = editingRecord.value?.id;
  return (
    detailOpen.value &&
    !recycleBinDetailActive.value &&
    !detailLoading.value &&
    !detailLoadFailed.value &&
    (editorMode.value === 'create' ||
      (editingId != null && selectedId != null && String(selectedId) === String(editingId)))
  );
});
const treeParentPickerConfigs = computed<Record<string, RecordFormFieldPickerConfig>>(() => {
  if (!persistentTreeDetail.value || !formFields.value.has('parentId')) {
    return {} as Record<string, RecordFormFieldPickerConfig>;
  }
  const hasPickerQueryScope = pickerQueryFieldNames.value.has('parentId');
  return {
    parentId: {
      context: treeResource.value
        ? context
        : hasPickerQueryScope
          ? createQueryScopedTreeModuleContext(context, {
              queryValues: () => pickerQueryValuesByField.value.parentId,
              treePath: `/${context.moduleAlias}/tree`,
            })
          : context,
      mode: 'tree',
      placeholder: `${treeRootTitle.value}留空`,
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
    const pickerFieldName = field.fieldRef.fieldName;
    if (configs[pickerFieldName]) {
      continue;
    }
    const pickerContext = createModuleContext<RecordPickerRecord>({
      http: baseContext.http,
      moduleAlias: reference.targetModuleAlias,
      runtimeAccess: 'REFERENCE',
    });
    const hasPickerQueryScope = pickerQueryFieldNames.value.has(pickerFieldName);
    const usesSourceReferenceResolver = reference.candidateDelivery === 'SOURCE_FIELD';
    const sourceReferencePickerConfig: Pick<
      RecordFormFieldPickerConfig,
      'loadOptions' | 'loadTree' | 'resolveOptions'
    > = {};
    if (usesSourceReferenceResolver) {
      const referenceResolver = createReferenceResolveClient(
        context.http,
        context.moduleAlias,
        reference.resolvePath,
      );
      const pickerRecord = (item: {
        id: string;
        title?: string;
        projections?: Record<string, unknown>;
        affectPatch?: Record<string, unknown>;
      }): RecordPickerRecord => ({
        id: item.id,
        title: item.title,
        ...(item.projections ?? {}),
        // Keep resolver projections distinct from the draft-shaped convenience fields above:
        // RecordFormFields exposes only descriptor-declared paths to WEB_UI formulas.
        projections: item.projections,
        affectPatch: item.affectPatch,
      });
      sourceReferencePickerConfig.loadOptions = async (keyword: string) => {
        const response = await referenceResolver.resolve(pickerFieldName, {
          mode: 'QUERY',
          fuzzy: keyword || undefined,
          page: { pageNum: 1, pageSize: 50 },
          formValues: { ...(editingRecord.value ?? {}) },
          source: editingRecord.value?.id == null ? undefined : { recordId: String(editingRecord.value.id) },
        });
        return response.options.map(pickerRecord);
      };
      sourceReferencePickerConfig.loadTree = async () => {
        const response = await referenceResolver.resolve(pickerFieldName, {
          mode: 'TREE',
          formValues: { ...(editingRecord.value ?? {}) },
          source: editingRecord.value?.id == null ? undefined : { recordId: String(editingRecord.value.id) },
        });
        return response.tree ?? [];
      };
      sourceReferencePickerConfig.resolveOptions = async (values: string[]) => {
        const response = await referenceResolver.resolve(pickerFieldName, {
          mode: 'TRANSLATE',
          values,
          formValues: { ...(editingRecord.value ?? {}) },
          source: editingRecord.value?.id == null ? undefined : { recordId: String(editingRecord.value.id) },
        });
        return response.results.flatMap((result) => (result.item ? [pickerRecord(result.item)] : []));
      };
    }
    configs[pickerFieldName] = {
      context: hasPickerQueryScope
        ? createQueryScopedTreeModuleContext(pickerContext, {
            queryValues: () => pickerQueryValuesByField.value[pickerFieldName],
            treePath: `/${reference.targetModuleAlias}/tree`,
          })
        : pickerContext,
      mode: recordPickerModeOf(reference.pickerMode),
      allowClear: !field.required?.constant,
      ...sourceReferencePickerConfig,
    };
  }
  return configs;
});

onMounted(async () => {
  void restoreDetailSurfaceMode();
  void restoreListPageSizePreference();
  await loadPageBootstrap();
  try {
    await loadRuntimeForm(
      isListPage,
      () => Boolean(pageEnhancement.value?.recordView),
      () => {
        pageBootstrapError.value = `模块页面增强 ${pageEnhancement.value?.id ?? 'unknown'} 的业务查看呈现仅支持普通列表模块，不支持树模块`;
      },
    );
  } catch (cause) {
    pageBootstrapError.value = cause instanceof Error ? cause.message : '页面运行时加载失败';
  }
  // The workspace is gated by page readiness, so its element is only present
  // after both descriptors have settled.
  await nextTick();
  observeWorkspaceWidth();
  updateDetailSurfaceForWorkspaceWidth();
  if (isListPage.value && !pageBootstrapError.value) {
    unregisterListRefresh = modulePageListRefreshRegistry.register(context.moduleAlias, refreshList);
  }
});

async function restoreDetailSurfaceMode() {
  const revision = ++detailSurfacePreferenceRestoreRevision;
  try {
    const restored = await restoreDetailSurfacePreference(userPreferences, context.moduleAlias);
    if (revision === detailSurfacePreferenceRestoreRevision) {
      detailSurfacePreference.value = restored;
    }
  } catch {
    // The local preference already initialized the runner; presentation must not block on optional persistence.
  }
}

function useDrawerDetailSurface() {
  setDetailSurfacePreference('drawer');
}

function usePinnedDetailSurface() {
  setDetailSurfacePreference('pinned');
}

function setDetailSurfacePreference(preference: DetailSurfacePreference) {
  detailSurfacePreference.value = preference;
  detailSurfacePreferenceWrite = detailSurfacePreferenceWrite
    .catch(() => undefined)
    .then(() => saveDetailSurfacePreference(userPreferences, context.moduleAlias, preference));
  void detailSurfacePreferenceWrite.catch(() => undefined);
}

async function restoreListPageSizePreference() {
  const revision = ++listPageSizePreferenceRestoreRevision;
  try {
    const restored = await restoreListPageSize(userPreferences, context.moduleAlias, listPageSize.value);
    if (revision === listPageSizePreferenceRestoreRevision) {
      listPageSize.value = restored;
    }
  } catch {
    // The local value keeps pagination usable if optional account persistence is unavailable.
  }
}

function setListPageSize(pageSize: number) {
  // A user selection wins over an in-flight optional backend restoration.
  listPageSizePreferenceRestoreRevision += 1;
  const preference = normalizeListPageSize(pageSize, listPageSize.value);
  listPageSize.value = preference;
  listPageSizePreferenceWrite = listPageSizePreferenceWrite
    .catch(() => undefined)
    .then(() => saveListPageSize(userPreferences, context.moduleAlias, preference));
  void listPageSizePreferenceWrite.catch(() => undefined);
}

onUnmounted(() => {
  disposePageEnhancement?.();
  workspaceResizeObserver?.disconnect();
  removeWorkspaceResizeFallback?.();
  unregisterListRefresh?.();
  unregisterListRefresh = undefined;
});

function observeWorkspaceWidth() {
  if (typeof ResizeObserver !== 'undefined' && workspaceElement.value) {
    workspaceResizeObserver = new ResizeObserver(() => updateDetailSurfaceForWorkspaceWidth());
    workspaceResizeObserver.observe(workspaceElement.value);
    updateDetailSurfaceForWorkspaceWidth();
    return;
  }
  if (typeof window === 'undefined') return;
  const onResize = () => updateDetailSurfaceForWorkspaceWidth();
  window.addEventListener('resize', onResize);
  removeWorkspaceResizeFallback = () => window.removeEventListener('resize', onResize);
  onResize();
}

function updateDetailSurfaceForWorkspaceWidth() {
  const workspaceWidth = workspaceElement.value?.getBoundingClientRect().width;
  // A detached/hidden host has no meaningful layout width. Defer its decision
  // until ResizeObserver (or the resize fallback) receives a real measurement.
  if (workspaceWidth == null || workspaceWidth <= 0) return;
  narrowDetailSurface.value = workspaceWidth < listDetailMinimumWidth.value;
}

function handleFlatManagementLoaded(records: QueryListRecord[]) {
  loadFlatManagementRecords(records, flatManagementRecycleBin.active.value);
}

function flatManagementItemOf(record: CrudRecordListBase): RecordExplorerItemDescriptor {
  const secondaryField = flatManagementContent.value?.secondaryField;
  const secondaryValue =
    secondaryField == null ? undefined : (record as unknown as Record<string, unknown>)[secondaryField];
  return {
    title:
      record.title ??
      record.alias ??
      record.code ??
      record.id ??
      flatManagementContent.value?.fallbackTitle ??
      '未命名记录',
    // The descriptor deliberately controls whether a compact explorer has a
    // subtitle. Do not fall back to the record ID: it turns an omitted
    // secondary field into accidental technical noise for business pages.
    secondary:
      secondaryValue == null || String(secondaryValue).trim() === '' ? undefined : String(secondaryValue),
    muted: record.enabled === false,
  };
}

function openFlatManagementRecord(record: QueryListRecord) {
  if (flatManagementRecycleBin.active.value) {
    void openRecycleBinRecord(record);
    return;
  }
  void openRecordView(record);
}

function handleFlatManagementAction(action: RecordActionItem) {
  const record = selectedRecord.value;
  const contribution = flatManagementEnhancementActions.value.find((item) => item.key === action.key);
  if (record && contribution) {
    void runEnhancementAction(contribution, { ...modulePageActionContext(record), record });
    return;
  }
  if (detailPageActions.value.some((item) => item.key === action.key)) {
    handleConfiguredAction(action);
    return;
  }
  if (action.key === 'cancel') {
    closeTreeCardEditor();
    return;
  }
  if (action.key === 'save') {
    void saveRecord();
    return;
  }
  if (action.key === 'edit' && selectedRecord.value) {
    void editRecord(selectedRecord.value, 'restore-view');
    return;
  }
  if (action.key === 'delete' && selectedRecord.value) {
    void deleteRecord(selectedRecord.value);
  }
}

function selectListDetailRecord(record: QueryListRecord) {
  selectListDetail(record, detailSurfaceUsesDrawer.value);
}

/**
 * A selection takes effect at every navigator level: it immediately constrains
 * the list and clears only selections that depend on it.
 */
function selectNavigatorRecord(levelKey: string, record: { id?: string }, source: 'user' | 'entry' = 'user') {
  if (!navigatorLevels.value.some((level) => level.descriptor.key === levelKey)) return;
  if (source !== 'entry' && isLockedNavigator(levelKey)) return;
  const previous = selectedNavigatorRecords.value[levelKey];
  const next = { ...selectedNavigatorRecords.value };
  const clearing = previous?.id === record.id;
  next[levelKey] = clearing ? undefined : (record as QueryListRecord);
  const descendantKeys = navigatorDescendantKeys(levelKey);
  for (const descendantKey of descendantKeys) {
    next[descendantKey] = undefined;
  }
  selectedNavigatorRecords.value = next;
  navigatorDismissedSelectionKeys.value = clearing
    ? [...new Set([...navigatorDismissedSelectionKeys.value, levelKey])]
    : navigatorDismissedSelectionKeys.value.filter((key) => key !== levelKey && !descendantKeys.has(key));
  clearSelectionForScopeChange();
}

function clearNavigatorRecord(levelKey: string) {
  if (isLockedNavigator(levelKey)) return;
  const selected = selectedNavigatorRecords.value[levelKey];
  if (!selected) return;
  navigatorDismissedSelectionKeys.value = [...new Set([...navigatorDismissedSelectionKeys.value, levelKey])];
  const next = { ...selectedNavigatorRecords.value, [levelKey]: undefined };
  for (const descendantKey of navigatorDescendantKeys(levelKey)) {
    next[descendantKey] = undefined;
  }
  selectedNavigatorRecords.value = next;
  clearSelectionForScopeChange();
}

function handleNavigatorLoaded(level: NavigatorLevelRuntime, records: Array<{ id?: string }>) {
  preloadNavigatorRecordActions(level, records);
  const key = level.descriptor.key;
  const single = records.length === 1 && records[0]?.id != null;
  const alreadyMarkedSingle = navigatorSingleResultKeys.value.includes(key);
  // Explorer `loaded` events are also emitted after a parent layout update. Keep
  // the collection identity when cardinality has not changed; otherwise a
  // single-record child tree can be needlessly re-mounted and reloaded.
  if (single !== alreadyMarkedSingle) {
    navigatorSingleResultKeys.value = single
      ? [...navigatorSingleResultKeys.value, key]
      : navigatorSingleResultKeys.value.filter((candidate) => candidate !== key);
  }
  if (navigatorEntrySelectionPendingFor(level)) {
    resolveNavigatorEntryFromRecords(level, records);
    return;
  }
  selectAutomaticNavigatorRecord(level, records);
}

function resolveLockedNavigatorEntry(level: NavigatorLevelRuntime) {
  resolveNavigatorEntryFromRecords(level, []);
}

function resolveNavigatorEntryFromRecords(level: NavigatorLevelRuntime, records: Array<{ id?: string }>) {
  const key = level.descriptor.key;
  const scopeIdentity = navigatorEntryScopeIdentity(key);
  void resolveNavigatorEntrySelection(level, records, navigatorExplorerQueryValues(key)).then(
    (resolution) => {
      // A source scope may change while the exact REFERENCE query is in flight.
      // Do not revive its old selection into the new context.
      if (
        !resolution ||
        !isCurrentNavigatorEntrySelection(level, resolution) ||
        scopeIdentity !== navigatorEntryScopeIdentity(key)
      )
        return;
      if (resolution.record && selectedNavigatorRecords.value[key]?.id == null) {
        selectNavigatorRecord(key, resolution.record, 'entry');
        return;
      }
      selectAutomaticNavigatorRecord(level, records);
    },
  );
}

function selectAutomaticNavigatorRecord(level: NavigatorLevelRuntime, records: Array<{ id?: string }>) {
  if (isLockedNavigator(level.descriptor.key)) return;
  const key = level.descriptor.key;
  const single = records.length === 1 && records[0]?.id != null;
  const selectsSingleResult =
    single &&
    level.descriptor.singleResultPolicy !== undefined &&
    level.descriptor.singleResultPolicy !== 'NONE';
  const selectsFirstRecord =
    records[0]?.id != null && level.descriptor.initialSelectionPolicy === 'FIRST_RECORD';
  if (
    (selectsSingleResult || selectsFirstRecord) &&
    selectedNavigatorRecords.value[key]?.id == null &&
    !navigatorDismissedSelectionKeys.value.includes(key)
  ) {
    selectNavigatorRecord(key, records[0]);
  }
}

function navigatorEntryScopeIdentity(levelKey: string): string {
  const values = navigatorExplorerQueryValues(levelKey);
  return JSON.stringify(
    Object.entries(values ?? {}).sort(([first], [second]) => first.localeCompare(second)),
  );
}

/** Applies an entry replacement only to the navigator source(s) it addresses. */
function applyNavigatorEntrySelectionChange(
  change:
    | {
        previous?: { moduleAlias: string };
        current?: { moduleAlias: string };
      }
    | undefined,
) {
  if (!change) return;
  const aliases = new Set(
    [change.previous?.moduleAlias, change.current?.moduleAlias].filter(
      (alias): alias is string => alias != null,
    ),
  );
  const targetKeys = navigatorLevels.value
    .filter((level) => aliases.has(level.descriptor.sourceModuleAlias))
    .map((level) => level.descriptor.key);
  if (targetKeys.length === 0) return;

  const affectedKeys = new Set(targetKeys);
  targetKeys.forEach((key) =>
    navigatorDescendantKeys(key).forEach((descendant) => affectedKeys.add(descendant)),
  );
  const next = { ...selectedNavigatorRecords.value };
  const clearsSelection = [...affectedKeys].some((key) => next[key] !== undefined);
  affectedKeys.forEach((key) => {
    next[key] = undefined;
  });
  selectedNavigatorRecords.value = next;
  navigatorDismissedSelectionKeys.value = navigatorDismissedSelectionKeys.value.filter(
    (key) => !affectedKeys.has(key),
  );
  if (clearsSelection) clearSelectionForScopeChange();
  navigatorEntryReloadKeys.value = {
    ...navigatorEntryReloadKeys.value,
    ...Object.fromEntries(targetKeys.map((key) => [key, (navigatorEntryReloadKeys.value[key] ?? 0) + 1])),
  };
}

function navigatorReloadKey(levelKey: string): number {
  return scopeReloadKey.value + (navigatorEntryReloadKeys.value[levelKey] ?? 0);
}

function preloadNavigatorRecordActions(level: NavigatorLevelRuntime, records: Array<{ id?: string }>) {
  if (!navigatorManagementAvailable(level)) return;
  const recordIds = records.flatMap((record) => (record.id == null ? [] : [String(record.id)]));
  if (recordIds.length === 0) return;
  void level.context.recordActionsBatch?.(recordIds).catch(() => {
    // Inline actions remain safely disabled until a later refresh resolves availability.
  });
}

function navigatorDescendantKeys(levelKey: string): Set<string> {
  const descendants = new Set<string>();
  const pending = [levelKey];
  while (pending.length > 0) {
    const parent = pending.pop();
    const level = navigatorLevels.value.find((candidate) => candidate.descriptor.key === parent);
    for (const binding of pageContextBindings.value) {
      if (
        binding.source === 'NAVIGATOR' &&
        binding.sourceKey === level?.descriptor.key &&
        binding.target === 'NAVIGATOR_QUERY' &&
        binding.targetNavigatorLevelKey != null &&
        !descendants.has(binding.targetNavigatorLevelKey)
      ) {
        descendants.add(binding.targetNavigatorLevelKey);
        pending.push(binding.targetNavigatorLevelKey);
      }
    }
  }
  return descendants;
}

const navigatorExplorerQueryValueCache = new Map<string, Record<string, unknown> | undefined>();
const navigatorExplorerQueryValuesByLevel = computed(() => {
  const activeLevelKeys = new Set<string>();
  const values = new Map<string, Record<string, unknown> | undefined>();
  for (const level of navigatorLevels.value) {
    const levelKey = level.descriptor.key;
    activeLevelKeys.add(levelKey);
    const resolved = resolvePageContextTargetValues(
      pageContextBindings.value,
      'NAVIGATOR_QUERY',
      pageContextSourceValues.value,
      levelKey,
    );
    const stable = reuseEquivalentQueryValues(navigatorExplorerQueryValueCache.get(levelKey), resolved);
    navigatorExplorerQueryValueCache.set(levelKey, stable);
    values.set(levelKey, stable);
  }
  for (const levelKey of navigatorExplorerQueryValueCache.keys()) {
    if (!activeLevelKeys.has(levelKey)) navigatorExplorerQueryValueCache.delete(levelKey);
  }
  return values;
});

function navigatorExplorerQueryValues(levelKey: string): Record<string, unknown> | undefined {
  return navigatorExplorerQueryValuesByLevel.value.get(levelKey);
}

/**
 * A manageable navigator is itself a scoped source. Its incoming navigator
 * query bindings must be settled before creation, just like the page list's
 * declared scope. This is intentionally descriptor-driven: no business page
 * needs to name its parent navigator or duplicate the readiness rule.
 */
function navigatorManagementCriteriaKeys(levelKey: string): string[] {
  return externalPageContextCriteriaKeys(pageContextBindings.value, 'NAVIGATOR_QUERY', levelKey);
}

function navigatorManagementScopeReady(level: NavigatorLevelRuntime): boolean {
  const criteriaKeys = navigatorManagementCriteriaKeys(level.descriptor.key);
  const values = navigatorExplorerQueryValues(level.descriptor.key);
  return criteriaKeys.every((key) => values?.[key] != null);
}

function navigatorManagementScopeDisabledReason(level: NavigatorLevelRuntime): string | undefined {
  return navigatorManagementScopeReady(level) ? undefined : '请先完成上游范围选择';
}

function navigatorManagementAvailable(level: NavigatorLevelRuntime) {
  return level.descriptor.management !== undefined;
}

function navigatorInlineActions(level: NavigatorLevelRuntime, record: NavigatorRecord): RecordInlineAction[] {
  if (!navigatorManagementAvailable(level)) return [];
  const actions: RecordInlineAction[] = [];
  if (level.tree && level.context.can('create') === true) {
    actions.push({ key: 'create-child', title: '新建子项', iconName: 'plus' });
  }
  if (level.context.can('update') === true) {
    actions.push(
      navigatorRecordAction(level, record, 'edit', 'update', `编辑${level.descriptor.title}`, 'edit'),
    );
  }
  if (level.context.can('delete') === true) {
    actions.push(
      navigatorRecordAction(level, record, 'delete', 'delete', `删除${level.descriptor.title}`, 'delete'),
    );
  }
  return actions;
}

function navigatorRecordAction(
  level: NavigatorLevelRuntime,
  record: NavigatorRecord,
  key: string,
  actionCode: string,
  title: string,
  iconName: 'edit' | 'delete',
): RecordInlineAction {
  const recordId = record.id == null ? undefined : String(record.id);
  if (!recordId) {
    return {
      key,
      actionCode,
      title,
      iconName,
      danger: key === 'delete',
      disabled: true,
      disabledReason: '记录标识缺失',
    };
  }
  const decision = level.context
    .recordActionsSnapshot(recordId)
    ?.actions.find((candidate) => candidate.actionCode === actionCode);
  if (!decision) {
    return {
      key,
      actionCode,
      title,
      iconName,
      danger: key === 'delete',
      disabled: true,
      disabledReason: '正在校验操作可用性',
    };
  }
  return {
    key,
    actionCode,
    title,
    iconName,
    danger: key === 'delete',
    disabled: !decision.available,
    disabledReason: decision.reason,
  };
}

function createNavigatorRecord(level: NavigatorLevelRuntime, parentId?: string) {
  if (
    !navigatorManagementAvailable(level) ||
    !navigatorManagementScopeReady(level) ||
    level.context.can('create') !== true
  )
    return;
  navigatorManagementSession += 1;
  navigatorManagementTogglingEnabled.value = false;
  navigatorManagementFormValid.value = true;
  navigatorManagementLevel.value = level;
  // Incoming navigator bindings constrain this source and must also establish
  // its ownership fields when creating a new source record (for example,
  // tenantId on a tenant-scoped category). Tree child creation adds parentId.
  const sessionFormDefaults = resolvePageContextTargetValues(
    pageContextBindings.value.filter(
      (binding) => binding.source === 'SESSION' && binding.target === 'FORM_DEFAULT',
    ),
    'FORM_DEFAULT',
    pageContextSourceValues.value,
  );
  const defaults = {
    ...(sessionFormDefaults ?? {}),
    ...(navigatorExplorerQueryValues(level.descriptor.key) ?? {}),
    ...(parentId ? { parentId } : {}),
  };
  navigatorManagementDetail.beginCreate(defaults);
  const draft = navigatorManagementDetail.draft.value;
  if (draft) {
    const computeRules =
      formComputeRulesOf(
        level.context.runtime.snapshot()?.uiDescriptor,
        level.descriptor.management?.editorSurface,
      ) ?? [];
    navigatorManagementDetail.draft.value = applyFormComputeAfterChanges(
      draft,
      Object.keys(defaults),
      computeRules,
    );
  }
}

const navigatorManagementEnabledVisible = computed(() => {
  const level = navigatorManagementLevel.value;
  const record = navigatorManagementDetail.draft.value;
  return Boolean(
    level &&
    record?.id != null &&
    navigatorManagementDetail.mode.value === 'edit' &&
    level.context.abilities.hasEnable() === true,
  );
});

function navigatorManagementEnabledActionAvailable(actionCode: 'enable' | 'disable'): boolean {
  const level = navigatorManagementLevel.value;
  const recordId = navigatorManagementDetail.draft.value?.id;
  if (!level || recordId == null) return false;
  const recordAction = level.context
    .recordActionsSnapshot(String(recordId))
    ?.actions.find((action) => action.actionCode === actionCode);
  return recordAction?.available === true;
}

const navigatorManagementEnabledDisabled = computed(() => {
  const record = navigatorManagementDetail.draft.value;
  if (
    !navigatorManagementEnabledVisible.value ||
    !record?.id ||
    typeof record.version !== 'number' ||
    navigatorManagementDetail.loading.value ||
    navigatorManagementDetail.loadFailed.value ||
    navigatorManagementDetail.saving.value ||
    navigatorManagementTogglingEnabled.value
  ) {
    return true;
  }
  return !navigatorManagementEnabledActionAvailable(record.enabled === false ? 'enable' : 'disable');
});

const navigatorManagementEnabledDisabledReason = computed(() => {
  const record = navigatorManagementDetail.draft.value;
  if (!record?.id || !navigatorManagementEnabledDisabled.value) return undefined;
  const actionCode = record.enabled === false ? 'enable' : 'disable';
  return navigatorManagementLevel.value?.context
    .recordActionsSnapshot(String(record.id))
    ?.actions.find((action) => action.actionCode === actionCode)?.reason;
});

async function toggleNavigatorManagementEnabled(enabled: boolean) {
  const level = navigatorManagementLevel.value;
  const record = navigatorManagementDetail.draft.value;
  const id = record?.id == null ? undefined : String(record.id);
  const version = typeof record?.version === 'number' ? record.version : undefined;
  if (!level || !record || !id || version === undefined || navigatorManagementEnabledDisabled.value) return;

  const session = navigatorManagementSession;
  const pendingDraft = { ...record };
  navigatorManagementTogglingEnabled.value = true;
  try {
    const result = enabled
      ? await level.context.crud.enable(id, { version })
      : await level.context.crud.disable(id, { version });
    level.context.invalidateRecordActions?.([id]);
    const refreshed = await level.context.crud.view(id);
    await level.context.recordActions(id).catch(() => undefined);
    if (session !== navigatorManagementSession || navigatorManagementLevel.value !== level) return;
    // Enabling is an independent, versioned mutation. Keep unsaved editor
    // fields intact while accepting the authoritative enabled/version values.
    navigatorManagementDetail.record.value = refreshed;
    navigatorManagementDetail.draft.value = {
      ...refreshed,
      ...pendingDraft,
      enabled: refreshed.enabled,
      version: refreshed.version,
    };
    navigatorManagementDetail.formSessionKey.value += 1;
    scopeReloadKey.value += 1;
    await presentModuleActionSuccess(result, enabled ? '已启用' : '已停用');
  } catch (cause) {
    presentPlatformError(cause, { source: 'navigator-management', phase: 'action' });
  } finally {
    if (session === navigatorManagementSession) navigatorManagementTogglingEnabled.value = false;
  }
}

function updateNavigatorManagementDraft(
  fieldName: string,
  value: import('@muyun/platform-components').RecordFormFieldValue,
) {
  const draft = navigatorManagementDetail.draft.value;
  if (!draft) return;
  const level = navigatorManagementLevel.value;
  navigatorManagementDetail.draft.value = applyFormComputeAfterChange(
    applyReferenceDependencyClears(draft, fieldName, value, navigatorManagementFormFields.value),
    fieldName,
    formComputeRulesOf(
      level?.context.runtime.snapshot()?.uiDescriptor,
      level?.descriptor.management?.editorSurface,
    ),
  );
}

async function editNavigatorRecord(level: NavigatorLevelRuntime, record: NavigatorRecord) {
  const id = record.id == null ? undefined : String(record.id);
  if (!navigatorManagementAvailable(level) || !id || level.context.can('update') !== true) return;
  const session = ++navigatorManagementSession;
  navigatorManagementTogglingEnabled.value = false;
  navigatorManagementFormValid.value = true;
  navigatorManagementLevel.value = level;
  navigatorManagementDetail.beginLoad(record as QueryListRecord, 'edit');
  try {
    const loaded = await level.context.crud.view(id);
    await level.context.recordActions(id).catch(() => undefined);
    if (session === navigatorManagementSession && navigatorManagementLevel.value === level) {
      navigatorManagementDetail.resolveLoad(loaded);
    }
  } catch {
    if (session === navigatorManagementSession && navigatorManagementLevel.value === level) {
      navigatorManagementDetail.failLoad();
    }
  } finally {
    if (session === navigatorManagementSession && navigatorManagementLevel.value === level) {
      navigatorManagementDetail.finishLoad();
    }
  }
}

async function handleNavigatorInlineAction(
  level: NavigatorLevelRuntime,
  action: RecordInlineAction,
  record: NavigatorRecord,
) {
  if (action.key === 'create-child') {
    createNavigatorRecord(level, record.id == null ? undefined : String(record.id));
  } else if (action.key === 'edit') {
    await editNavigatorRecord(level, record);
  } else if (action.key === 'delete') {
    await deleteNavigatorRecord(level, record);
  }
}

async function saveNavigatorRecord() {
  const level = navigatorManagementLevel.value;
  const record = navigatorManagementDetail.draft.value;
  if (!level || !record || navigatorManagementDetail.saving.value) return;
  if (!navigatorManagementFormValid.value) {
    navigatorManagementFormValidationRequestKey.value += 1;
    return;
  }
  const creating = navigatorManagementDetail.mode.value === 'create';
  if (level.context.can(creating ? 'create' : 'update') !== true) return;
  navigatorManagementDetail.saving.value = true;
  try {
    const id = record.id == null ? undefined : String(record.id);
    const result =
      !creating && id ? await level.context.crud.update(id, record) : await level.context.crud.insert(record);
    const savedId = result.record.id == null ? undefined : String(result.record.id);
    let persistedRecord = result.record;
    let refreshFailure: unknown;
    if (savedId) {
      try {
        persistedRecord = await level.context.crud.view(savedId);
      } catch (cause) {
        refreshFailure = cause;
      }
    }
    if (savedId) {
      level.context.invalidateRecordActions?.([savedId]);
      void level.context.recordActions(savedId).catch(() => undefined);
    }
    navigatorManagementDetail.applySaved(persistedRecord);
    scopeReloadKey.value += 1;
    await presentModuleActionSuccess(result, '保存成功');
    // This is an in-panel, single-record editing session. Once persistence succeeds,
    // returning to the navigator keeps the workspace focused and avoids stale drafts.
    closeNavigatorManagementEditor();
    if (refreshFailure) {
      presentPlatformError(refreshFailure, { source: 'navigator-management', phase: 'load' });
    }
  } catch (cause) {
    presentPlatformError(cause, { source: 'navigator-management', phase: 'action' });
  } finally {
    navigatorManagementDetail.saving.value = false;
  }
}

async function deleteNavigatorRecord(level: NavigatorLevelRuntime, record: NavigatorRecord) {
  const id = record.id == null ? undefined : String(record.id);
  const version = typeof record.version === 'number' ? record.version : undefined;
  if (!id || version === undefined || level.context.can('delete') !== true) return;
  try {
    if (
      !(await confirmAction({
        title: `删除${level.descriptor.title}`,
        content: `确认删除该${level.descriptor.title}？`,
        okText: '删除',
        danger: true,
      }))
    )
      return;
    const result = await level.context.crud.delete(id, { version });
    level.context.invalidateRecordActions?.([id]);
    if (selectedNavigatorRecords.value[level.descriptor.key]?.id === id) {
      selectNavigatorRecord(level.descriptor.key, { id });
    }
    scopeReloadKey.value += 1;
    await presentModuleActionSuccess(result, '删除成功');
  } catch (cause) {
    presentPlatformError(cause, { source: 'navigator-management', phase: 'action' });
  }
}

/** A scope selection immediately constrains the list; its former detail may no longer be in range. */
function clearSelectionForScopeChange() {
  closeNavigatorManagementEditor();
  invalidatePendingRequests();
  detailLoading.value = false;
  detailLoadFailed.value = false;
  detailOpen.value = false;
  editorMode.value = 'view';
  selectedRecord.value = undefined;
  editingRecord.value = undefined;
  selectedTreeRecord.value = undefined;
}

function closeNavigatorManagementEditor() {
  navigatorManagementSession += 1;
  navigatorManagementTogglingEnabled.value = false;
  navigatorManagementFormValid.value = true;
  navigatorManagementDetail.close();
  navigatorManagementLevel.value = undefined;
}

function selectTreeRecord(record: unknown) {
  selectedTreeRecord.value = record as QueryListRecord;
  void openRecord(selectedTreeRecord.value, 'view');
}

function clearTreeRecordSelection() {
  if (saving.value) return;
  invalidatePendingRequests();
  selectedTreeRecord.value = undefined;
  detail.close();
  selectedRecord.value = undefined;
  editingRecord.value = undefined;
}

function handleTreeLoaded(records: unknown[]) {
  setCardAssistantRecords(records as QueryListRecord[]);
  if (selectedTreeRecord.value || editorMode.value !== 'view') return;
  const firstRecord = records.at(0);
  if (firstRecord) selectTreeRecord(firstRecord);
}

function updateDraftField(
  fieldName: string,
  value: import('@muyun/platform-components').RecordFormFieldValue,
) {
  if (!editingRecord.value) {
    return;
  }
  editingRecord.value = applyFormComputeAfterChange(
    applyReferenceDependencyClears(editingRecord.value, fieldName, value, formFields.value),
    fieldName,
    formComputeRulesOf(context.runtime.snapshot()?.uiDescriptor),
  );
}

/**
 * Rules are attached to the resolved FORM view, not to a page/template. This
 * keeps the same calculation semantics for main details and managed
 * navigators while local-edit action forms remain isolated until they publish
 * their own signed FORM descriptor.
 */
function formComputeRulesOf(
  uiDescriptor: ResolvedModuleUiDescriptor | undefined,
  editorSurface?: string,
): readonly ResolvedFormComputeRuleDescriptor[] | undefined {
  const view = formViewOf(uiDescriptor, editorSurface);
  return view?.formComputeRules ?? [];
}

function formViewOf(
  uiDescriptor: ResolvedModuleUiDescriptor | undefined,
  editorSurface?: string,
): ResolvedViewDescriptor | undefined {
  if (editorSurface) {
    return uiDescriptor?.editorSurfaces?.find((surface) => surface.key === editorSurface)?.editor;
  }
  return uiDescriptor?.page?.detail.editor ?? uiDescriptor?.defaultEditor;
}

function applyFormComputeAfterChange(
  draft: RecordFormRecord,
  fieldName: string,
  rules: readonly ResolvedFormComputeRuleDescriptor[] | undefined,
): RecordFormRecord {
  return applyFormComputeAfterChanges(draft, [fieldName], rules);
}

function applyFormComputeAfterChanges(
  draft: RecordFormRecord,
  changedFields: readonly string[],
  rules: readonly ResolvedFormComputeRuleDescriptor[] | undefined,
): RecordFormRecord {
  return new FormComputeCoordinator(rules).applyAfterChange(draft, changedFields);
}

async function createRecord(parentId?: string) {
  if (context.can('create') !== true) return;
  await (resolvedSelectionFormDefaultsRequest ?? loadResolvedSelectionFormDefaults());
  invalidatePendingRequests();
  const defaults = { ...navigatorCreateDefaults.value, ...(parentId ? { parentId } : {}) };
  // Only a tree's persistent detail card has a meaningful record to restore.
  // A list drawer creates an independent draft: cancelling it must close the
  // drawer rather than reopen the row that happened to be selected.
  detail.beginCreate(defaults, { cancelDestination: persistentTreeDetail.value ? 'restore-view' : 'close' });
  if (editingRecord.value) {
    editingRecord.value = applyFormComputeAfterChanges(
      editingRecord.value,
      Object.keys(defaults),
      formComputeRulesOf(context.runtime.snapshot()?.uiDescriptor),
    );
  }
}

function createRootRecord() {
  createRecord();
}

function createChildRecord() {
  const parentId = selectedRecord.value?.id == null ? undefined : String(selectedRecord.value.id);
  if (parentId) createRecord(parentId);
}

async function editRecord(record: QueryListRecord, cancelDestination: 'close' | 'restore-view' = 'close') {
  if (context.can('update') !== true) return;
  if (selectedRecord.value?.id === record.id && detail.beginEdit({ cancelDestination })) return;
  await openRecord(record, 'edit', { cancelDestination });
}

async function saveRecord() {
  const record = editingRecord.value;
  if (!record) return;
  if (!mainFormValid.value || !relationDraftValid.value) {
    formValidationRequestKey.value += 1;
    return;
  }
  if (editorMode.value === 'create' ? context.can('create') !== true : context.can('update') !== true) {
    return;
  }
  if (
    !canMutateModuleDetail({
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
    const savedId = result.record.id == null ? undefined : String(result.record.id);
    // Mutation output is an acknowledgement, not a guaranteed editable projection. Reload the
    // canonical view (including managed children and display-enriched fields) before retaining it.
    let persistedRecord = result.record;
    let refreshFailure: unknown;
    if (savedId) {
      try {
        persistedRecord = await context.crud.view(savedId);
      } catch (cause) {
        refreshFailure = cause;
      }
    }
    if (savedId) {
      context.invalidateRecordActions?.([savedId]);
      void context.recordActions(savedId).catch(() => undefined);
    }
    selectedRecord.value = persistedRecord;
    if (persistentTreeDetail.value) {
      selectedTreeRecord.value = persistedRecord;
    }
    detail.applySaved(persistedRecord);
    relationDraftValid.value = true;
    detailRelationReloadKey.value += 1;
    refreshList();
    formSessionKey.value += 1;
    await presentModuleActionSuccess(result, '保存成功');
    if (refreshFailure) {
      presentPlatformError(refreshFailure, { source: 'module-action', phase: 'load' });
    }
  } catch (cause) {
    presentPlatformError(cause, { source: 'module-action', phase: 'action' });
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
    context.invalidateRecordActions?.([id]);
    if (selectedRecord.value?.id === id) {
      detail.clearDeleted();
      selectedTreeRecord.value = undefined;
    }
    refreshList();
    await presentModuleActionSuccess(result, '删除成功');
  } catch (cause) {
    presentPlatformError(cause, { source: 'module-action', phase: 'action' });
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
    context.invalidateRecordActions?.([id]);
    const refreshed = await context.crud.view(id);
    detail.resolveLoad(refreshed);
    refreshList();
    await presentModuleActionSuccess(result, enabling ? '已启用' : '已停用');
  } catch (cause) {
    presentPlatformError(cause, { source: 'module-action', phase: 'action' });
  } finally {
    togglingEnabled.value = false;
  }
}

function presentModuleActionSuccess(result: unknown, fallbackMessage: string, source = 'module-action') {
  return presentActionSuccess(result, fallbackMessage, source);
}

function handleListAction(action: { key?: string }) {
  if (action.key === 'create') {
    createRecord();
    return;
  }
  const contribution = enhancementActionContributions.value.find((item) => item.key === action.key);
  if (contribution) {
    void runEnhancementAction(contribution, modulePageActionContext());
  }
}

function handleRowAction(action: { key?: string }, record: QueryListRecord) {
  if (action.key === 'view') {
    void openRecordView(record);
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
    void runEnhancementAction(contribution, { ...modulePageActionContext(record), record });
  }
}

/** The sole dispatch point for standard view actions, double-clicks and list-detail selection. */
async function openRecordView(record: QueryListRecord) {
  const viewActionCode = pageEnhancement.value?.recordView?.authorizationActionCode;
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
  if (detailPageActions.value.some((item) => item.key === action.key)) {
    handleConfiguredAction(action);
    return;
  }
  const record = selectedRecord.value;
  const contribution = enhancementDetailActions.value.find((item) => item.key === action.key);
  if (record && contribution) {
    void runEnhancementAction(contribution, { ...modulePageActionContext(record), record });
  }
}

function handleBatchAction(action: { key?: string }, records: QueryListRecord[], clearSelection: () => void) {
  const contribution = enhancementBatchActions.value.find((item) => item.key === action.key);
  if (contribution) {
    void runEnhancementAction(contribution, { ...modulePageActionContext(), records, clearSelection });
  }
}

/** Opens the declared detail workspace independently of the current card/drawer surface. */
function openDetailWorkspaceView() {
  const view = detailWorkspaceView.value;
  const record = selectedRecord.value;
  const recordId = record?.id == null ? undefined : String(record.id);
  if (!view || !recordId || !detailWorkspaceAvailable.value || !modulePageNavigation) return;
  modulePageNavigation.openWorkspaceTab(view, { recordId }, recordTitle(record) ?? undefined);
}

function modulePageActionContext(record?: QueryListRecord): ModulePageActionContext {
  return {
    module: context,
    scope: modulePageActionStateContext().scope,
    refreshList,
    reload: reloadModulePage,
    openDrawer: (definition: ModulePageDrawer) => openEnhancementDrawer(definition, record),
    openWorkspaceTab: (view, input) => {
      if (!modulePageNavigation) {
        throw new Error('模块页面工作视图需要 Workbench 导航承载');
      }
      modulePageNavigation.openWorkspaceTab(view, input);
    },
    openPage: (descriptor) => {
      if (!modulePageNavigation) {
        throw new Error('模块页面跳转需要 Workbench 导航承载');
      }
      modulePageNavigation.openPage(descriptor);
    },
  };
}

function modulePageActionStateContext(): ModulePageActionStateContext {
  if (navigatorEntryPolicy.value.hidden) {
    return { module: context };
  }
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

function reloadModulePage() {
  refreshList();
  if (!persistentTreeDetail.value) {
    treeReloadKey.value += 1;
  }
}

/**
 * Public, state-preserving list refresh for business-owned triggers.
 * RecordQueryListPanel observes reloadKey and only re-runs loadRecords().
 */
function refreshList() {
  if (persistentTreeDetail.value) {
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
  invalidatePendingRequests();
  detail.close();
}

/** Returns to a detail only when the state machine retained that surface. */
async function cancelDetailEditing() {
  if (saving.value) return;
  invalidatePendingRequests();
  detail.cancelEdit();
  if (!detailOpen.value) return;
  const record = selectedRecord.value;
  if (record?.id != null) {
    await loadRecord(record, 'view');
  }
}

async function closeTreeCardEditor() {
  if (saving.value) return;
  invalidatePendingRequests();
  // Tree management uses a persistent card rather than a drawer, but its
  // cancellation semantics are the same as every other detail surface.
  // In particular a create started from a selected tree node must return to
  // that node's loaded detail instead of leaving the card empty.
  detail.cancelEdit();
  const record = selectedRecord.value;
  if (record?.id != null) {
    await loadRecord(record, 'view');
    selectedTreeRecord.value = selectedRecord.value;
  }
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
  <section v-if="pageBootstrapError" class="module-unsupported">
    <RecordPanelState class="module-bootstrap-error" :description="pageBootstrapError" />
  </section>
  <section v-else-if="!pageReady" class="module-unsupported">
    <RecordPanelState loading loading-tip="加载页面入口" description="" />
  </section>
  <section
    v-else-if="isListPage"
    ref="workspaceElement"
    class="module-workspace"
    :class="{
      'module-workspace--management': constrainedManagementPage,
    }"
  >
    <StaticManagementLayout
      v-if="flatManagementPage"
      class="module-flat-management-workspace"
      :explorer-title="
        flatManagementRecycleBin.active.value ? '回收站' : (flatManagementContent?.explorerTitle ?? title)
      "
      :refresh-title="`刷新${flatManagementRecycleBin.active.value ? '回收站' : (flatManagementContent?.explorerTitle ?? title)}`"
      :explorer-search-keyword="flatManagementSearchKeyword"
      :explorer-search-placeholder="flatManagementContent?.explorerSearchPlaceholder"
      :explorer-searchable="!flatManagementRecycleBin.active.value"
      :mode="editorMode"
      :detail-title="detailTitle"
      :navigator-count="navigatorExplorerCount"
      @update:explorer-search-keyword="flatManagementSearchKeyword = $event"
      @refresh="flatManagementRecycleBin.refresh"
    >
      <template #navigator="{ index }">
        <component
          :is="navigatorExtension.component"
          v-if="navigatorExtension && index === 0"
          :context="navigatorExtensionContext"
        />
        <RecordExplorerPanel
          v-else-if="navigatorLevelAt(index)"
          :title="navigatorLevelAt(index)!.descriptor.title"
          :refresh-title="`刷新${navigatorLevelAt(index)!.descriptor.title}${navigatorLevelAt(index)!.tree ? '树' : '列表'}`"
          :search-keyword="scopeSearchKeyword"
          :search-placeholder="navigatorLevelAt(index)!.descriptor.searchPlaceholder"
          @update:search-keyword="scopeSearchKeyword = $event"
          @refresh="scopeReloadKey += 1"
        >
          <template v-if="navigatorManagementAvailable(navigatorLevelAt(index)!)" #actions>
            <ModuleActionButton
              :context="navigatorLevelAt(index)!.context"
              action-code="create"
              icon-only
              :disabled="!navigatorManagementScopeReady(navigatorLevelAt(index)!)"
              :title="
                navigatorManagementScopeDisabledReason(navigatorLevelAt(index)!) ??
                `新建${navigatorLevelAt(index)!.descriptor.title}`
              "
              @click="createNavigatorRecord(navigatorLevelAt(index)!)"
            />
          </template>
          <TreeRecordExplorer
            v-if="navigatorLevelAt(index)!.tree"
            :context="navigatorLevelAt(index)!.context"
            :selected-id="
              selectedNavigatorRecords[navigatorLevelAt(index)!.descriptor.key]?.id == null
                ? undefined
                : String(selectedNavigatorRecords[navigatorLevelAt(index)!.descriptor.key]?.id)
            "
            :reload-key="navigatorReloadKey(navigatorLevelAt(index)!.descriptor.key)"
            :keyword="scopeSearchKeyword"
            :external-query-values="navigatorExplorerQueryValues(navigatorLevelAt(index)!.descriptor.key)"
            search-mode="none"
            :empty-description="`暂无${navigatorLevelAt(index)!.descriptor.title}`"
            :actions-of="(record) => navigatorInlineActions(navigatorLevelAt(index)!, record)"
            @loaded="handleNavigatorLoaded(navigatorLevelAt(index)!, $event)"
            @select="selectNavigatorRecord(navigatorLevelAt(index)!.descriptor.key, $event)"
            @deselect="clearNavigatorRecord(navigatorLevelAt(index)!.descriptor.key)"
            @action="
              (action, record) => handleNavigatorInlineAction(navigatorLevelAt(index)!, action, record)
            "
          />
          <CrudRecordListExplorer
            v-else
            :context="navigatorLevelAt(index)!.context"
            :selected-id="
              selectedNavigatorRecords[navigatorLevelAt(index)!.descriptor.key]?.id == null
                ? undefined
                : String(selectedNavigatorRecords[navigatorLevelAt(index)!.descriptor.key]?.id)
            "
            :reload-key="navigatorReloadKey(navigatorLevelAt(index)!.descriptor.key)"
            :keyword="scopeSearchKeyword"
            :external-query-values="navigatorExplorerQueryValues(navigatorLevelAt(index)!.descriptor.key)"
            :empty-description="`暂无${navigatorLevelAt(index)!.descriptor.title}`"
            :actions-of="(record) => navigatorInlineActions(navigatorLevelAt(index)!, record)"
            @loaded="handleNavigatorLoaded(navigatorLevelAt(index)!, $event)"
            @select="selectNavigatorRecord(navigatorLevelAt(index)!.descriptor.key, $event)"
            @deselect="clearNavigatorRecord(navigatorLevelAt(index)!.descriptor.key)"
            @action="
              (action, record) => handleNavigatorInlineAction(navigatorLevelAt(index)!, action, record)
            "
          />
          <template #editor>
            <NavigatorManagementEditor
              :open="
                navigatorManagementLevel?.descriptor.key === navigatorLevelAt(index)!.descriptor.key &&
                navigatorManagementDetail.open.value
              "
              :title="navigatorManagementTitle"
              :saving="navigatorManagementDetail.saving.value"
              :loading="navigatorManagementDetail.loading.value"
              :load-failed="navigatorManagementDetail.loadFailed.value"
              :draft="navigatorManagementDetail.draft.value as RecordFormRecord"
              :fields="navigatorManagementFormFields"
              :mode="navigatorManagementDetail.mode.value"
              :form-session-key="navigatorManagementDetail.formSessionKey.value"
              :validation-request-key="navigatorManagementFormValidationRequestKey"
              :context="navigatorLevelAt(index)!.context"
              :picker-configs="navigatorManagementPickerConfigs"
              :contributions="navigatorManagementFormContributions"
              :field-policies="navigatorManagementFormFieldPolicies"
              :show-enabled="navigatorManagementEnabledVisible"
              :enabled="navigatorManagementDetail.draft.value?.enabled !== false"
              :enabled-disabled="navigatorManagementEnabledDisabled"
              :enabled-disabled-reason="navigatorManagementEnabledDisabledReason"
              :enabled-loading="navigatorManagementTogglingEnabled"
              @close="closeNavigatorManagementEditor"
              @save="saveNavigatorRecord"
              @toggle-enabled="toggleNavigatorManagementEnabled"
              @update-field="updateNavigatorManagementDraft"
              @validity-change="navigatorManagementFormValid = $event.valid"
            />
          </template>
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
          v-if="navigatorListScopeReady"
          :context="context"
          :selected-id="selectedRecord?.id == null ? undefined : String(selectedRecord.id)"
          :reload-key="flatManagementRecycleBin.reloadKey.value"
          :mode="flatManagementRecycleBin.mode.value"
          :keyword="flatManagementSearchKeyword"
          :external-query-values="navigatorListQueryValues"
          :empty-description="
            flatManagementRecycleBin.active.value ? '回收站为空' : flatManagementContent?.emptyDescription
          "
          :fallback-title="flatManagementContent?.fallbackTitle"
          :item-of="flatManagementItemOf"
          @recycle-bin-summary="flatManagementRecycleBin.updateSummary"
          @loaded="(records) => handleFlatManagementLoaded(records as QueryListRecord[])"
          @restored="refreshList"
          @select="(record) => openFlatManagementRecord(record as QueryListRecord)"
          @deselect="resetFlatManagementSelection"
        />
        <RecordPanelState v-else :description="navigatorScopeUnavailableDescription" />
      </template>
      <template v-if="flatManagementRecycleBin.buttonVisible.value" #explorer-footer>
        <RecycleBinModeButton
          :active="flatManagementRecycleBin.active.value"
          :has-records="flatManagementRecycleBin.hasRecords.value"
          :count="flatManagementRecycleBin.total.value"
          @click="flatManagementRecycleBin.toggle"
        />
      </template>
      <template v-if="hasCardAssistantAt('outside', 'top')" #detail-outside-top>
        <aside class="module-card-assistant module-card-assistant--outside">
          <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
        </aside>
      </template>
      <template v-if="hasCardAssistantAt('inside', 'top')" #detail-content-top>
        <aside class="module-card-assistant">
          <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
        </aside>
      </template>
      <template #detail-actions>
        <RecordPanelButton
          v-if="detailWorkspaceAvailable"
          type="text"
          icon-name="open-in-new"
          title="在新标签页打开"
          aria-label="在新标签页打开"
          @click="openDetailWorkspaceView"
        />
        <RecordActionBar
          :context="context"
          :record-id="
            editorMode === 'create' || selectedRecord?.id == null ? undefined : String(selectedRecord.id)
          "
          :actions="flatManagementDetailActions"
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
          :disabled-reason="toggleEnabledDisabledReason"
          :loading="togglingEnabled"
          :show-label="false"
          @change="toggleEnabled"
        />
      </template>
      <RecordPanelState
        v-if="!selectedRecord && editorMode === 'view'"
        :description="flatManagementContent?.detailEmptyDescription ?? detailEmptyDescription"
      />
      <RecordPanelState v-else-if="detailLoading" loading loading-tip="加载记录详情" description="" />
      <RecordPanelState v-else-if="detailLoadFailed" description="详情加载失败，请重新选择记录" />
      <ModulePageRecordContent
        v-else-if="editingRecord"
        :context="context"
        :cross-module-http="baseContext.http"
        :mode="editorMode"
        :record="editingRecord"
        :selected-record="selectedRecord"
        :detail-display-fields="detailDisplayFields"
        :form-fields="formFields"
        :form-session-key="formSessionKey"
        :validation-request-key="formValidationRequestKey"
        :picker-configs="referencePickerConfigs"
        :saving="saving"
        :ui-descriptor="runtimeUiDescriptor"
        :relations="executableDetailRelations"
        :relations-available="detailRelationsAvailable"
        :relation-reload-key="detailRelationReloadKey"
        :show-system-info="showDetailSystemInfo"
        :extension-sections="enhancementDetailSections"
        :detail-section-context="detailSectionContext"
        :form-contributions="formContributions"
        :form-field-policies="formFieldPolicies"
        @update:field="updateDraftField"
        @validity-change="updateMainFormValidity"
        @children-change="updateEmbeddedChildren"
        @relations-validity-change="updateRelationDraftValidity"
      />
      <template v-if="hasCardAssistantAt('inside', 'bottom')" #detail-content-bottom>
        <aside class="module-card-assistant">
          <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
        </aside>
      </template>
      <template v-if="hasCardAssistantAt('outside', 'bottom')" #detail-outside-bottom>
        <aside class="module-card-assistant module-card-assistant--outside">
          <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
        </aside>
      </template>
    </StaticManagementLayout>

    <ManagementWorkspace
      v-else-if="listDetailCardPage"
      class="module-list-detail-workspace"
      :explorer-count="navigatorExplorerCount"
      :detail-surface="!detailSurfaceUsesDrawer"
      :list-surface="detailSurfaceUsesDrawer"
    >
      <ManagementExplorerColumn v-if="navigatorExtension" :key="navigatorExtension.key">
        <component :is="navigatorExtension.component" :context="navigatorExtensionContext" />
      </ManagementExplorerColumn>
      <ManagementExplorerColumn v-for="level in visibleNavigatorLevels" :key="level.descriptor.key">
        <RecordExplorerPanel
          :title="level.descriptor.title"
          :refresh-title="`刷新${level.descriptor.title}${level.tree ? '树' : '列表'}`"
          :search-keyword="scopeSearchKeyword"
          :search-placeholder="level.descriptor.searchPlaceholder"
          @update:search-keyword="scopeSearchKeyword = $event"
          @refresh="scopeReloadKey += 1"
        >
          <template v-if="navigatorManagementAvailable(level)" #actions>
            <ModuleActionButton
              :context="level.context"
              action-code="create"
              icon-only
              :disabled="!navigatorManagementScopeReady(level)"
              :title="navigatorManagementScopeDisabledReason(level) ?? `新建${level.descriptor.title}`"
              @click="createNavigatorRecord(level)"
            />
          </template>
          <TreeRecordExplorer
            v-if="level.tree"
            :context="level.context"
            :selected-id="
              selectedNavigatorRecords[level.descriptor.key]?.id == null
                ? undefined
                : String(selectedNavigatorRecords[level.descriptor.key]?.id)
            "
            :reload-key="navigatorReloadKey(level.descriptor.key)"
            :keyword="scopeSearchKeyword"
            :external-query-values="navigatorExplorerQueryValues(level.descriptor.key)"
            :navigator-host-module-alias="context.moduleAlias"
            search-mode="none"
            :empty-description="`暂无${level.descriptor.title}`"
            :actions-of="(record) => navigatorInlineActions(level, record)"
            @loaded="handleNavigatorLoaded(level, $event)"
            @select="selectNavigatorRecord(level.descriptor.key, $event)"
            @deselect="clearNavigatorRecord(level.descriptor.key)"
            @action="(action, record) => handleNavigatorInlineAction(level, action, record)"
          />
          <CrudRecordListExplorer
            v-else
            :context="level.context"
            :selected-id="
              selectedNavigatorRecords[level.descriptor.key]?.id == null
                ? undefined
                : String(selectedNavigatorRecords[level.descriptor.key]?.id)
            "
            :reload-key="navigatorReloadKey(level.descriptor.key)"
            :keyword="scopeSearchKeyword"
            :external-query-values="navigatorExplorerQueryValues(level.descriptor.key)"
            :navigator-host-module-alias="context.moduleAlias"
            :empty-description="`暂无${level.descriptor.title}`"
            :actions-of="(record) => navigatorInlineActions(level, record)"
            @loaded="handleNavigatorLoaded(level, $event)"
            @select="selectNavigatorRecord(level.descriptor.key, $event)"
            @deselect="clearNavigatorRecord(level.descriptor.key)"
            @action="(action, record) => handleNavigatorInlineAction(level, action, record)"
          />
          <template #editor>
            <NavigatorManagementEditor
              :open="
                navigatorManagementLevel?.descriptor.key === level.descriptor.key &&
                navigatorManagementDetail.open.value
              "
              :title="navigatorManagementTitle"
              :saving="navigatorManagementDetail.saving.value"
              :loading="navigatorManagementDetail.loading.value"
              :load-failed="navigatorManagementDetail.loadFailed.value"
              :draft="navigatorManagementDetail.draft.value as RecordFormRecord"
              :fields="navigatorManagementFormFields"
              :mode="navigatorManagementDetail.mode.value"
              :form-session-key="navigatorManagementDetail.formSessionKey.value"
              :validation-request-key="navigatorManagementFormValidationRequestKey"
              :context="level.context"
              :picker-configs="navigatorManagementPickerConfigs"
              :contributions="navigatorManagementFormContributions"
              :field-policies="navigatorManagementFormFieldPolicies"
              :show-enabled="navigatorManagementEnabledVisible"
              :enabled="navigatorManagementDetail.draft.value?.enabled !== false"
              :enabled-disabled="navigatorManagementEnabledDisabled"
              :enabled-disabled-reason="navigatorManagementEnabledDisabledReason"
              :enabled-loading="navigatorManagementTogglingEnabled"
              @close="closeNavigatorManagementEditor"
              @save="saveNavigatorRecord"
              @toggle-enabled="toggleNavigatorManagementEnabled"
              @update-field="updateNavigatorManagementDraft"
              @validity-change="navigatorManagementFormValid = $event.valid"
            />
          </template>
        </RecordExplorerPanel>
      </ManagementExplorerColumn>
      <RecordQueryListPanel
        class="module-list"
        :class="{ 'module-list--row-expansion': listRowExpansionEnabled }"
        :context="context"
        :title="modulePageTitle"
        :subtitle="modulePageSubtitle"
        :selected-key="selectedRecord?.id"
        :expanded-row-keys="expandedListRowKeys"
        :reload-key="reloadKey"
        :standard-crud-actions="true"
        :standard-crud-row-actions="true"
        :standard-crud-row-action-keys="standardCrudRowActionKeys"
        :extra-actions="enhancementActions"
        :additional-columns="enhancementColumns"
        :cell-components="enhancementCellComponents"
        :extra-row-actions-of="enhancementRowActionsFor"
        :action-column-width="pageEnhancement?.list?.actionColumnWidth"
        :batch-actions="enhancementBatchActions"
        :ui-config-id="listUiConfigId"
        :query-template-id="listQueryTemplateId"
        :page-size="listPageSize"
        :ready="pageReady && navigatorListScopeReady"
        :external-query-values="navigatorListQueryValues"
        :persistent-query-controls="persistentListQueryControls"
        :query-summaries="listQuerySummaries"
        :required-external-criteria-keys="navigatorListCriteriaKeys"
        :mode="listMode"
        :quick-search-placeholder="listSearchPlaceholder"
        :empty-description="listEmptyDescription"
        @loaded="handleLoaded"
        @mode-change="handleListModeChange"
        @page-size-change="setListPageSize"
        @restored="handleRecycleBinRestore"
        @select="selectListDetailRecord"
        @row-dblclick="openListRecord"
        @action="handleListAction"
        @row-action="handleRowAction"
        @row-expand="updateListRowExpansion"
        @batch-action="
          (action, records, _event, clearSelection) => handleBatchAction(action, records, clearSelection)
        "
      >
        <template v-if="listRowExpansionEnabled" #expandedRow="{ record }">
          <ModulePageListExpansionSurface
            :source-context="context"
            :cross-module-http="baseContext.http"
            :ui-descriptor="runtimeUiDescriptor!"
            :record="record"
            :relation-entries="listRelationExpansions"
            :extension="enhancementRowExpansion"
            :extension-context="enhancementRowExpansion ? listRowExpansionContext(record, true) : undefined"
          />
        </template>
      </RecordQueryListPanel>

      <RecordDetailPanel v-if="!detailSurfaceUsesDrawer" class="module-list-detail-card" :title="detailTitle">
        <template v-if="hasCardAssistantAt('outside', 'top')" #outside-top>
          <aside class="module-card-assistant module-card-assistant--outside">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
        <template v-if="hasCardAssistantAt('inside', 'top')" #content-top>
          <aside class="module-card-assistant">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
        <template #title-prefix>
          <RecordPanelButton
            class="detail-surface-mode-button"
            type="text"
            icon-name="pin-off"
            title="改为抽屉展示"
            aria-label="改为抽屉展示"
            @click="useDrawerDetailSurface"
          />
        </template>
        <template #actions>
          <RecordPanelButton
            v-if="detailWorkspaceAvailable"
            type="text"
            icon-name="open-in-new"
            title="在新标签页打开"
            aria-label="在新标签页打开"
            @click="openDetailWorkspaceView"
          />
          <ModuleRecordDetailActions
            :context="context"
            :record="selectedRecord"
            :mode="editorMode"
            :saving="saving"
            :detail-loading="detailLoading"
            :detail-load-failed="detailLoadFailed"
            :recycle-bin-active="recycleBinDetailActive"
            :actions="enhancementDetailActions"
            :configured-actions="detailPageActions"
            @cancel="cancelDetailEditing"
            @save="saveRecord"
            @edit="selectedRecord && editRecord(selectedRecord, 'restore-view')"
            @delete="selectedRecord && deleteRecord(selectedRecord)"
            @detail-action="handleDetailAction"
          />
        </template>
        <template #status>
          <RecordStatusSwitch
            v-if="!recycleBinDetailActive && editorMode === 'view' && selectedRecord"
            :enabled="selectedRecord.enabled !== false"
            :disabled="!canToggleEnabled"
            :disabled-reason="toggleEnabledDisabledReason"
            :loading="togglingEnabled"
            :show-label="false"
            @change="toggleEnabled"
          />
        </template>

        <RecordPanelState
          v-if="!selectedRecord && editorMode === 'view'"
          :description="detailEmptyDescription"
        />
        <RecordPanelState v-else-if="detailLoading" loading loading-tip="加载记录详情" description="" />
        <RecordPanelState v-else-if="detailLoadFailed" description="详情加载失败，请重新选择记录" />
        <ModulePageRecordContent
          v-else-if="editingRecord"
          :context="context"
          :cross-module-http="baseContext.http"
          :mode="editorMode"
          :record="editingRecord"
          :selected-record="selectedRecord"
          :detail-display-fields="detailDisplayFields"
          :form-fields="formFields"
          :form-session-key="formSessionKey"
          :validation-request-key="formValidationRequestKey"
          :picker-configs="referencePickerConfigs"
          :saving="saving"
          :ui-descriptor="runtimeUiDescriptor"
          :relations="executableDetailRelations"
          :relations-available="detailRelationsAvailable"
          :relation-reload-key="detailRelationReloadKey"
          :show-system-info="showDetailSystemInfo"
          :extension-sections="enhancementDetailSections"
          :detail-section-context="detailSectionContext"
          :form-contributions="formContributions"
          :form-field-policies="formFieldPolicies"
          @update:field="updateDraftField"
          @validity-change="updateMainFormValidity"
          @children-change="updateEmbeddedChildren"
          @relations-validity-change="updateRelationDraftValidity"
        />
        <template v-if="hasCardAssistantAt('inside', 'bottom')" #content-bottom>
          <aside class="module-card-assistant">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
        <template v-if="hasCardAssistantAt('outside', 'bottom')" #outside-bottom>
          <aside class="module-card-assistant module-card-assistant--outside">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
      </RecordDetailPanel>
    </ManagementWorkspace>

    <ManagementWorkspace
      v-else-if="treeManagementPage || treeModule"
      class="module-tree-workspace"
      :explorer-count="navigatorExplorerCount + 1"
    >
      <ManagementExplorerColumn v-if="navigatorExtension" :key="navigatorExtension.key">
        <component :is="navigatorExtension.component" :context="navigatorExtensionContext" />
      </ManagementExplorerColumn>
      <ManagementExplorerColumn v-for="level in visibleNavigatorLevels" :key="level.descriptor.key">
        <PageNavigatorExplorer
          :level="level"
          :selected-id="
            selectedNavigatorRecords[level.descriptor.key]?.id == null
              ? undefined
              : String(selectedNavigatorRecords[level.descriptor.key]?.id)
          "
          :reload-key="navigatorReloadKey(level.descriptor.key)"
          :keyword="scopeSearchKeyword"
          :external-query-values="navigatorExplorerQueryValues(level.descriptor.key)"
          :navigator-host-module-alias="context.moduleAlias"
          :ready="navigatorManagementScopeReady(level)"
          :create-disabled="!navigatorManagementScopeReady(level)"
          :create-disabled-reason="navigatorManagementScopeDisabledReason(level)"
          :scope-subtitle="navigatorPanelScopeContext(level.descriptor.key)"
          :actions-of="(record) => navigatorInlineActions(level, record)"
          @update:keyword="scopeSearchKeyword = $event"
          @refresh="scopeReloadKey += 1"
          @create="createNavigatorRecord(level)"
          @loaded="handleNavigatorLoaded(level, $event)"
          @select="selectNavigatorRecord(level.descriptor.key, $event)"
          @deselect="clearNavigatorRecord(level.descriptor.key)"
          @action="(action, record) => handleNavigatorInlineAction(level, action, record)"
        >
          <template #editor>
            <NavigatorManagementEditor
              :open="
                navigatorManagementLevel?.descriptor.key === level.descriptor.key &&
                navigatorManagementDetail.open.value
              "
              :title="navigatorManagementTitle"
              :saving="navigatorManagementDetail.saving.value"
              :loading="navigatorManagementDetail.loading.value"
              :load-failed="navigatorManagementDetail.loadFailed.value"
              :draft="navigatorManagementDetail.draft.value as RecordFormRecord"
              :fields="navigatorManagementFormFields"
              :mode="navigatorManagementDetail.mode.value"
              :form-session-key="navigatorManagementDetail.formSessionKey.value"
              :validation-request-key="navigatorManagementFormValidationRequestKey"
              :context="level.context"
              :picker-configs="navigatorManagementPickerConfigs"
              :contributions="navigatorManagementFormContributions"
              :field-policies="navigatorManagementFormFieldPolicies"
              :show-enabled="navigatorManagementEnabledVisible"
              :enabled="navigatorManagementDetail.draft.value?.enabled !== false"
              :enabled-disabled="navigatorManagementEnabledDisabled"
              :enabled-disabled-reason="navigatorManagementEnabledDisabledReason"
              :enabled-loading="navigatorManagementTogglingEnabled"
              @close="closeNavigatorManagementEditor"
              @save="saveNavigatorRecord"
              @toggle-enabled="toggleNavigatorManagementEnabled"
              @update-field="updateNavigatorManagementDraft"
              @validity-change="navigatorManagementFormValid = $event.valid"
            />
          </template>
        </PageNavigatorExplorer>
      </ManagementExplorerColumn>
      <ManagementExplorerColumn>
        <RecordExplorerPanel
          :title="treePanelTitle"
          :subtitle="mainTreeScopeContext"
          :refresh-title="`刷新${treePanelTitle}`"
          :search-keyword="treeSearchKeyword"
          :search-placeholder="listSearchPlaceholder"
          @update:search-keyword="treeSearchKeyword = $event"
          @refresh="treeReloadKey += 1"
        >
          <template #actions>
            <ModuleActionButton
              v-if="mainTreeScopeReady"
              class="record-panel-create-button"
              :context="context"
              action-code="create"
              icon-only
              :title="runtimePage?.treeResource?.createTitle ?? `新建${treeRootTitle}`"
              @click="createRootRecord"
            />
          </template>
          <TreeRecordExplorer
            v-if="mainTreeScopeReady"
            :context="context"
            :selected-id="selectedTreeRecord?.id == null ? undefined : String(selectedTreeRecord.id)"
            :reload-key="treeReloadKey"
            :keyword="treeSearchKeyword"
            :external-query-values="runtimePage?.treeResource ? undefined : navigatorListQueryValues"
            search-mode="none"
            search-trigger="external"
            :empty-description="listEmptyDescription"
            @select="selectTreeRecord"
            @deselect="clearTreeRecordSelection"
            @loaded="handleTreeLoaded"
          />
          <RecordPanelState v-else :description="mainTreeScopeDescription" />
        </RecordExplorerPanel>
      </ManagementExplorerColumn>

      <RecordDetailPanel class="module-tree-card" :title="detailTitle">
        <template v-if="hasCardAssistantAt('outside', 'top')" #outside-top>
          <aside class="module-card-assistant module-card-assistant--outside">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
        <template v-if="hasCardAssistantAt('inside', 'top')" #content-top>
          <aside class="module-card-assistant">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
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
            <RecordPanelButton
              v-if="detailWorkspaceAvailable"
              type="text"
              icon-name="open-in-new"
              title="在新标签页打开"
              aria-label="在新标签页打开"
              @click="openDetailWorkspaceView"
            />
            <RecordActionBar
              v-if="
                selectedRecord?.id != null &&
                (enhancementDetailActions.length > 0 || detailPageActions.length > 0)
              "
              :context="context"
              :record-id="String(selectedRecord.id)"
              :actions="[...enhancementDetailActions, ...detailPageActions]"
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
              :record-id="selectedRecord?.id == null ? undefined : String(selectedRecord.id)"
              :disabled="!selectedRecord"
              @click="selectedRecord && editRecord(selectedRecord, 'restore-view')"
            >
              编辑
            </ModuleActionButton>
            <ModuleActionButton
              :context="context"
              action-code="delete"
              :record-id="selectedRecord?.id == null ? undefined : String(selectedRecord.id)"
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
            :disabled-reason="toggleEnabledDisabledReason"
            :loading="togglingEnabled"
            :show-label="false"
            @change="toggleEnabled"
          />
        </template>

        <RecordPanelState
          v-if="!selectedRecord && editorMode === 'view'"
          :description="detailEmptyDescription"
        />
        <RecordPanelState v-else-if="detailLoading" loading loading-tip="加载记录详情" description="" />
        <RecordPanelState v-else-if="detailLoadFailed" description="详情加载失败，请重新选择记录" />
        <template v-else-if="editingRecord">
          <template v-if="editorMode === 'view'">
            <RecordDetailFields
              :record="editingRecord as RecordFormRecord"
              :fields="detailDisplayFields"
              :option-context="context"
              :file-transfer-context="context"
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
          <div v-else class="module-form">
            <RecordFormFields
              :record="editingRecord as RecordFormRecord"
              :fields="formFields"
              :form-session-key="formSessionKey"
              :validation-request-key="formValidationRequestKey"
              :option-context="context"
              :file-transfer-context="context"
              :picker-configs="referencePickerConfigs"
              :exclude-field-names="['enabled']"
              @update:field="updateDraftField"
              @validity-change="updateMainFormValidity"
            />
          </div>
          <ModulePageDetailRelations
            v-if="runtimeUiDescriptor && detailRelationsAvailable"
            :source-context="context"
            :cross-module-http="baseContext.http"
            :ui-descriptor="runtimeUiDescriptor"
            :relations="executableDetailRelations"
            :parent-record="(editorMode === 'view' ? selectedRecord : editingRecord) as QueryListRecord"
            :mutation-enabled="editorMode !== 'view'"
            :reload-key="detailRelationReloadKey"
            :validation-request-key="formValidationRequestKey"
            @children-change="updateEmbeddedChildren"
            @validity-change="updateRelationDraftValidity"
          />
          <RecordMetaSection
            v-if="editorMode !== 'create' && showDetailSystemInfo"
            :record="editingRecord"
            show-sort-order
          />
        </template>
        <template v-if="hasCardAssistantAt('inside', 'bottom')" #content-bottom>
          <aside class="module-card-assistant">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
        <template v-if="hasCardAssistantAt('outside', 'bottom')" #outside-bottom>
          <aside class="module-card-assistant module-card-assistant--outside">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
      </RecordDetailPanel>
    </ManagementWorkspace>

    <RecordQueryListPanel
      v-else
      class="module-list"
      :class="{ 'module-list--row-expansion': listRowExpansionEnabled }"
      :context="context"
      :title="modulePageTitle"
      :subtitle="modulePageSubtitle"
      :selected-key="selectedRecord?.id"
      :expanded-row-keys="expandedListRowKeys"
      :reload-key="reloadKey"
      :standard-crud-actions="true"
      :standard-crud-row-actions="true"
      :standard-crud-row-action-keys="standardCrudRowActionKeys"
      :extra-actions="enhancementActions"
      :additional-columns="enhancementColumns"
      :cell-components="enhancementCellComponents"
      :extra-row-actions-of="enhancementRowActionsFor"
      :action-column-width="pageEnhancement?.list?.actionColumnWidth"
      :batch-actions="enhancementBatchActions"
      :ui-config-id="listUiConfigId"
      :query-template-id="listQueryTemplateId"
      :page-size="listPageSize"
      :ready="pageReady && navigatorListScopeReady"
      :external-query-values="navigatorListQueryValues"
      :required-external-criteria-keys="navigatorListCriteriaKeys"
      :mode="listMode"
      :persistent-query-controls="persistentListQueryControls"
      :query-summaries="listQuerySummaries"
      :quick-search-placeholder="listSearchPlaceholder"
      :empty-description="listEmptyDescription"
      @loaded="handleLoaded"
      @mode-change="handleListModeChange"
      @page-size-change="setListPageSize"
      @restored="handleRecycleBinRestore"
      @select="selectStandaloneListRecord"
      @row-dblclick="openListRecord"
      @action="handleListAction"
      @row-action="handleRowAction"
      @row-expand="updateListRowExpansion"
      @batch-action="
        (action, records, _event, clearSelection) => handleBatchAction(action, records, clearSelection)
      "
    >
      <template v-if="listRowExpansionEnabled" #expandedRow="{ record }">
        <ModulePageListExpansionSurface
          :source-context="context"
          :cross-module-http="baseContext.http"
          :ui-descriptor="runtimeUiDescriptor!"
          :record="record"
          :relation-entries="listRelationExpansions"
          :extension="enhancementRowExpansion"
          :extension-context="enhancementRowExpansion ? listRowExpansionContext(record, true) : undefined"
        />
      </template>
    </RecordQueryListPanel>

    <RecordModeDrawer
      v-if="!persistentTreeDetail && !flatManagementPage && (!listDetailCardPage || detailSurfaceUsesDrawer)"
      :open="detailOpen"
      :title="detailTitle"
      render-mode="inline"
      :width="enhancementDetailDrawer?.width"
      :mode="editorMode"
      :loading="detailLoading"
      :load-failed="detailLoadFailed"
      @close="closeDetail"
      @retry="retryLoadDetail"
    >
      <template v-if="detailWorkspaceAvailable" #header-actions>
        <RecordPanelButton
          type="text"
          icon-name="open-in-new"
          title="在新标签页打开"
          aria-label="在新标签页打开"
          @click="openDetailWorkspaceView"
        />
      </template>
      <template v-if="listDetailCardPage && !narrowDetailSurface" #title-prefix>
        <RecordPanelButton
          class="detail-surface-mode-button"
          type="text"
          icon-name="pin"
          title="固定到右侧展示"
          aria-label="固定到右侧展示"
          @click="usePinnedDetailSurface"
        />
      </template>
      <template #status>
        <RecordStatusSwitch
          v-if="
            !recycleBinDetailActive && !enhancementDetailDrawer && editorMode === 'view' && selectedRecord
          "
          :enabled="selectedRecord.enabled !== false"
          :disabled="!canToggleEnabled"
          :disabled-reason="toggleEnabledDisabledReason"
          :loading="togglingEnabled"
          :show-label="false"
          @change="toggleEnabled"
        />
      </template>
      <template v-if="!enhancementDetailDrawer || enhancementDetailActions.length > 0" #operation>
        <ModuleRecordDetailActions
          :context="context"
          :record="selectedRecord"
          :mode="editorMode"
          :saving="saving"
          :detail-loading="detailLoading"
          :detail-load-failed="detailLoadFailed"
          :recycle-bin-active="recycleBinDetailActive"
          :actions="enhancementDetailActions"
          :configured-actions="detailPageActions"
          :show-standard-view-actions="!enhancementDetailDrawer"
          @cancel="cancelDetailEditing"
          @save="saveRecord"
          @edit="selectedRecord && editRecord(selectedRecord, 'restore-view')"
          @delete="selectedRecord && deleteRecord(selectedRecord)"
          @detail-action="handleDetailAction"
        />
      </template>
      <template #view>
        <template v-if="editingRecord">
          <component
            :is="enhancementDetailDrawer.component"
            v-if="enhancementDetailDrawer"
            :context="recordViewContext(editingRecord)"
          />
          <ModulePageRecordContent
            v-else
            :context="context"
            :cross-module-http="baseContext.http"
            :mode="editorMode"
            :record="editingRecord"
            :selected-record="selectedRecord"
            :detail-display-fields="detailDisplayFields"
            :form-fields="formFields"
            :form-session-key="formSessionKey"
            :validation-request-key="formValidationRequestKey"
            :picker-configs="referencePickerConfigs"
            :saving="saving"
            :ui-descriptor="runtimeUiDescriptor"
            :relations="executableDetailRelations"
            :relations-available="detailRelationsAvailable"
            :relation-reload-key="detailRelationReloadKey"
            :show-system-info="showDetailSystemInfo"
            :extension-sections="enhancementDetailSections"
            :detail-section-context="detailSectionContext"
            :form-contributions="formContributions"
            :form-field-policies="formFieldPolicies"
            @update:field="updateDraftField"
            @validity-change="updateMainFormValidity"
            @children-change="updateEmbeddedChildren"
            @relations-validity-change="updateRelationDraftValidity"
          />
        </template>
      </template>
      <template #form>
        <ModulePageRecordContent
          v-if="editingRecord"
          :context="context"
          :cross-module-http="baseContext.http"
          :mode="editorMode"
          :record="editingRecord"
          :selected-record="selectedRecord"
          :detail-display-fields="detailDisplayFields"
          :form-fields="formFields"
          :form-session-key="formSessionKey"
          :validation-request-key="formValidationRequestKey"
          :picker-configs="referencePickerConfigs"
          :saving="saving"
          :ui-descriptor="runtimeUiDescriptor"
          :relations="executableDetailRelations"
          :relations-available="detailRelationsAvailable"
          :relation-reload-key="detailRelationReloadKey"
          :show-system-info="showDetailSystemInfo"
          :extension-sections="enhancementDetailSections"
          :detail-section-context="detailSectionContext"
          :form-contributions="formContributions"
          :form-field-policies="formFieldPolicies"
          @update:field="updateDraftField"
          @validity-change="updateMainFormValidity"
          @children-change="updateEmbeddedChildren"
          @relations-validity-change="updateRelationDraftValidity"
        />
      </template>
    </RecordModeDrawer>

    <RecordDetailDrawer
      v-if="enhancementDrawer"
      :open="enhancementDrawerOpen"
      :title="enhancementDrawer.definition.title"
      :subtitle="enhancementDrawer.subtitle"
      render-mode="inline"
      :width="enhancementDrawer.definition.width"
      @close="closeEnhancementDrawer"
      @after-close="disposeEnhancementDrawer"
    >
      <template v-if="enhancementDrawer.titleActions.length" #title-actions>
        <DrawerTitleActions :actions="enhancementDrawer.titleActions" />
      </template>
      <template v-if="enhancementDrawer.operation?.summary" #operation-summary>
        {{ enhancementDrawer.operation.summary }}
      </template>
      <template v-if="enhancementDrawer.operation" #operation>
        <DrawerTitleActions :actions="enhancementDrawer.operation.actions" />
      </template>
      <component :is="enhancementDrawer.definition.component" :context="enhancementDrawer.context" />
    </RecordDetailDrawer>
  </section>
  <section v-else class="module-unsupported">
    <h2>{{ title }}</h2>
    <p>{{ unsupportedPageModeText }}</p>
  </section>
  <UiModal
    :open="localEditOpen"
    :title="localEditBlock?.title ?? '局部编辑'"
    confirm-text="保存"
    :width="localEditBlock?.width ?? 640"
    :confirm-loading="localEditSaving"
    @confirm="submitLocalEdit"
    @cancel="localEditOpen = false"
  >
    <RecordFormFields
      v-if="localEditDraft"
      :record="localEditDraft"
      :fields="localEditFields"
      :option-context="context"
      :file-transfer-context="context"
      :disabled="localEditSaving"
      @update:field="(fieldName, value) => (localEditDraft![fieldName] = value)"
      @validity-change="updateLocalEditFormValidity"
    />
  </UiModal>
</template>

<style scoped>
.module-workspace {
  position: relative;
  min-width: 0;
  min-height: calc(100vh - 116px);
}

/* All desktop management templates share one fixed workbench boundary. */
.module-workspace--management {
  height: 100%;
  min-height: 0;
}

.module-list {
  min-width: 0;
}

/* The platform owns the expanded-row table boundary; its shared surface owns visual hierarchy. */
.module-list--row-expansion :deep(.ant-table-tbody > tr.ant-table-expanded-row > td) {
  padding: 0 !important;
  background: var(--muyun-surface) !important;
  border-bottom-color: var(--muyun-border-subtle);
}

/* Ant Design adds fixed-row compensation around expanded content. The platform
 * surface owns spacing instead, so relation and extension content align alike. */
.module-list--row-expansion :deep(.ant-table-expanded-row-fixed) {
  margin: 0 !important;
  padding: 0 !important;
}

.module-list--row-expansion :deep(.ant-table-tbody > tr.ant-table-expanded-row:hover > td) {
  background: var(--muyun-surface) !important;
}

.module-tree-workspace {
  height: 100%;
  min-height: 0;
}

.module-list-detail-card {
  min-width: var(--muyun-management-detail-min-width);
}

/* Title-level layout toggles are compact controls, not primary panel actions. */
.detail-surface-mode-button {
  width: 24px;
  min-width: 24px;
  height: 24px;
  padding: 0;
}

.detail-surface-mode-button :deep(.ui-icon) {
  font-size: 16px;
}

.module-tree-card {
  min-width: 0;
}

.module-tree-workspace :deep(.record-panel-create-button) {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 999px;
}

.module-scope-editor-panel {
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

.module-scope-editor-drawer-enter-active,
.module-scope-editor-drawer-leave-active {
  transition:
    transform 0.18s ease,
    opacity 0.18s ease;
}

.module-scope-editor-drawer-enter-from,
.module-scope-editor-drawer-leave-to {
  opacity: 0;
  transform: translateY(100%);
}

.module-scope-editor-header,
.module-scope-editor-actions {
  display: flex;
  align-items: center;
}

.module-scope-editor-header {
  justify-content: space-between;
  gap: 10px;
}

.module-scope-editor-header h3 {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--muyun-text);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.module-scope-editor-actions {
  flex: 0 0 auto;
  gap: 8px;
}

.module-scope-editor-form {
  grid-template-columns: minmax(0, 1fr);
}

.module-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 12px;
  row-gap: 16px;
  --muyun-record-form-label-gap: 8px;
}

.module-card-assistant {
  min-width: 0;
}

.module-unsupported {
  display: grid;
  align-content: center;
  justify-items: center;
  min-height: calc(100vh - 116px);
  color: var(--muyun-text-muted);
  text-align: center;
}

.module-unsupported h2 {
  margin: 0 0 8px;
  color: var(--muyun-text);
  font-size: 18px;
  font-weight: 600;
}

.module-unsupported p {
  margin: 0;
  font-size: 13px;
}

@media (max-width: 720px) {
  .module-workspace--management {
    height: calc(100vh - 116px);
    min-height: calc(100vh - 116px);
  }

  .module-tree-workspace {
    height: auto;
    min-height: 0;
  }

  .module-form {
    grid-template-columns: 1fr;
  }
}
</style>
