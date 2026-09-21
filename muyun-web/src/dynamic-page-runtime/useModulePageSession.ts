import { formActionResult, hasFormActionRecordPatch } from './formActionResult';
import { useInputValidationActionStatus } from './inputValidationActionStatus';
import { invokePageAction } from './pageActionInvocation';
import { resolvePlacedPageActions } from './pageActionPlacement';
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, toRaw, watch } from 'vue';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import {
  createQueryScopedTreeModuleContext,
  createQueryReferencePickerProvider,
  listDetailWorkspaceMinWidth,
  createReferenceRecordDetailBrowser,
  confirmAction,
  parentRecordConstraints,
  applyReferenceDependencyClears,
  presentPlatformError,
  presentPlatformMessage,
  recordDraftFingerprint,
  recordPickerModeOf,
  resolveRecordFormFields,
  useRecycleBinExplorerMode,
  type RecordFormFieldPickerConfig,
  type RecordPickerRecord,
  type ReferencePickerProvider,
  type ScopedTreePickerCandidate,
  type CrudRecordListBase,
  type RecordExplorerItemDescriptor,
  type RecordActionItem,
  type RecordQueryListCellComponent,
  type RecordQueryListQueryController,
  type RecordTreeQueryController,
  type ReferenceRecordDetailMutation,
  type StandardCrudRowActionKey,
  type QueryListRecord,
  type RecordFormRecord,
  type ReferencePickerCandidate,
} from '@muyun/platform-components';
import type {
  StandardModulePageDescriptor,
  MenuPageMode,
  ResolvedDetailRelationDescriptor,
  ResolvedFormComputeRuleDescriptor,
  ResolvedFormValidationRuleDescriptor,
  ResolvedModuleUiDescriptor,
  ResolvedPageTreeResourceDescriptor,
  ResolvedPageTextDescriptor,
  ResolvedPageListRelationExpansionDescriptor,
  ResolvedReferenceFieldDescriptor,
  ResolvedViewDescriptor,
  RecordInlineAction,
  RouteQueryValue,
} from '@muyun/web-contracts';
import { hasExecutableDetailRelationQueryContract } from '@muyun/web-contracts';
import { FormulaRuntime } from '../formula/FormulaRuntime';
import {
  AppError,
  createModuleContext,
  createReferenceResolveClient,
  createStaticResourceTreeClient,
  userPreferences,
  useModuleContext,
  withHttpHeaders,
  type HttpClient,
  type ModuleContext,
  type ModuleRecordActionAvailability,
  type ModuleTreeClient,
} from '@muyun/web-core';
import { canMutateModuleDetail } from './moduleDetailStateModel';
import {
  assistantEditableRecordIds,
  assistantEditCancelDestination,
  hasAvailableRecordUpdate,
} from './assistantRecordEditorPolicy';
import { recordMutationPayload } from './recordMutationPayload';
import { createSourceReferencePickerConfigAssembler } from './sourceReferencePickerConfig';
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

import { shouldHideSingleResultNavigator } from './navigatorVisibility';
import { type RecordDetailTransitionOptions, useRecordDetailController } from './recordDetailController';
import {
  externalPageContextCriteriaKeys,
  requiredNavigatorListScopeCriteriaKeys,
  resolvePageContextTargetValues,
  reuseEquivalentQueryValues,
} from './pageContextRuntime';
import { FormComputeCoordinator } from './formComputeCoordinator';
import {
  aggregateChildRelationCodes,
  aggregateChildTriggers,
  childAggregateRows as resolveChildAggregateRows,
} from './childAggregateContext';
import { FormValidationCoordinator } from './formValidationCoordinator';
import { useModulePageBootstrap } from './composables/useModulePageBootstrap';
import {
  useNavigatorRuntime,
  type NavigatorLevelRuntime,
  type NavigatorSortViewState,
} from './composables/useNavigatorRuntime';
import { useModulePageActions } from './composables/useModulePageActions';
import { useRecordEditingSession } from './composables/useRecordEditingSession';
import { useModulePageListSession } from './composables/useModulePageListSession';
import { useModulePageDetailActionRuntime } from './composables/useModulePageDetailActionRuntime';
import { useModulePageDetailExtensionRuntime } from './composables/useModulePageDetailExtensionRuntime';
import { applyReferenceRecordProjection } from './referenceRecordProjection';
import type { ShallowUnwrapRef } from 'vue';
import type { TenantScopeController } from './useTenantScopeController';

/** Business sessions report only tenant policy and consume only layout count.
 * Tenant selection and explorer interaction remain page-lifetime controls. */
type ModulePageSessionTenantPolicy = Pick<
  TenantScopeController,
  'tenantScopeExplorerCount' | 'tenantScopeFixedByIdentity' | 'setRequired'
>;

export interface ModulePageSessionProps {
  descriptor: StandardModulePageDescriptor;
  requireConfiguredPage?: boolean;
  /** Renders one target record through the standard detail lifecycle without a menu/list session. */
  recordOnly?: { recordId: string; renderMode?: 'inline' | 'portal'; scope?: 'tab' | 'viewport' };
  /** Frozen selection for one disposable business session. */
  tenantScope?: QueryListRecord;
  http?: HttpClient;
  tenantController: ModulePageSessionTenantPolicy;
}

export interface ModulePageSessionEvents {
  (event: 'interaction-state-change', state: { editing: boolean; busy: boolean; dirty?: boolean }): void;
  (
    event: 'record-only-change',
    mutation: { type: 'saved' | 'deleted' | 'unavailable'; record?: QueryListRecord },
  ): void;
  (event: 'record-only-close'): void;
}

export function useModulePageSession(
  props: ModulePageSessionProps,
  emit: ModulePageSessionEvents,
  initialized: () => void,
) {
  let disposed = false;
  onUnmounted(() => {
    disposed = true;
  });
  const currentUser = useCurrentUserContext();
  const baseContext = useModuleContext<QueryListRecord>({
    moduleAlias: props.descriptor.target.moduleAlias,
  });
  const moduleRequestPrefix = `/${props.descriptor.target.moduleAlias}`;
  /** The outer Host freezes this value together with the business client. */
  const tenantScopeId = computed(() => (props.tenantScope?.id == null ? '' : String(props.tenantScope.id)));
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
  const navigatorExtensionSelection = ref(
    initialSelectionOf(navigatorEntryPolicy.value.extension?.selection),
  );
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
  const mainTreeSorting = ref(false);
  const selectedTreeRecord = ref<QueryListRecord>();
  const pageContextHeader = ref<string>();
  const pageSelectionHeader = computed(() => {
    const selection = navigatorExtensionSelection.value;
    return selection ? JSON.stringify(selection) : undefined;
  });
  function isHostScopedRequest(path: string): boolean {
    // Reference runtimes are discovery/control traffic. A source module may share
    // the `platform.module` path prefix with the host itself, so the generic
    // prefix check alone would accidentally attach the host menu identity.
    if (path.endsWith('/reference-context')) return false;
    return (
      path === moduleRequestPrefix ||
      path.startsWith(`${moduleRequestPrefix}/`) ||
      path === `/platform.module/${props.descriptor.target.moduleAlias}/context`
    );
  }
  const rawContext = props.recordOnly
    ? baseContext
    : createModuleContext<QueryListRecord>({
        moduleAlias: props.descriptor.target.moduleAlias,
        http: withHttpHeaders(
          props.http ?? baseContext.http,
          () => ({
            'X-MuYun-Menu-Id': props.descriptor.menuId,
            'X-MuYun-Page-Context': pageContextHeader.value,
            'X-MuYun-Page-Selection': pageSelectionHeader.value,
          }),
          (request) => isHostScopedRequest(request.path),
        ),
      });
  const referenceRecordDetailBrowser = createReferenceRecordDetailBrowser(rawContext.http);
  onUnmounted(referenceRecordDetailBrowser.dispose);

  const { tenantScopeExplorerCount, tenantScopeFixedByIdentity } = props.tenantController;
  const tenantScopeRequired = computed(() => rawContext.runtime.snapshot()?.tenantRequired === true);
  const tenantScopeReady = computed(() => !tenantScopeRequired.value || Boolean(tenantScopeId.value));
  const tenantScopeSubtitle = computed(() => {
    if (tenantScopeFixedByIdentity.value) return undefined;
    const title = props.tenantScope?.title ?? props.tenantScope?.name ?? tenantScopeId.value;
    return tenantScopeReady.value && title ? `租户：${title}` : undefined;
  });

  const context: ModuleContext<QueryListRecord> = {
    ...rawContext,
    crud: {
      async querySchema(options) {
        const schema = await (activeTreeResourceClient.value ?? rawContext.crud).querySchema(options);
        const fields = runtimePage.value?.quickSearchFields;
        return fields == null
          ? schema
          : {
              ...schema,
              quickSearch: {
                ...schema.quickSearch,
                enabled: fields.length > 0,
                fields,
                fieldSchemas: schema.quickSearch.fieldSchemas.filter((field) => fields.includes(field.name)),
              },
            };
      },
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
      ['create', 'view', 'update', 'delete', 'query', 'tree', 'sort', 'enable', 'disable'].includes(
        actionCode,
      )
      ? `${resource}_${actionCode}`
      : actionCode;
  }
  const modulePageNavigation = useModulePageNavigation();
  const { presentActionSuccess, runEnhancementAction } = useModulePageActions();
  const detail = useRecordDetailController<QueryListRecord>();
  const {
    invalidatePendingRequests,
    commitLoadedRecord,
    openRecord: loadRecord,
    openRecycleBinRecord,
    settlePendingRecord,
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
    isDirty: detailDirty,
    formSessionKey,
    togglingEnabled,
    loading: detailLoading,
    loadFailed: detailLoadFailed,
  } = detail;
  const assistantContextRevision = ref(0);
  const assistantInteractionRevision = ref(0);
  const markAssistantUserInteraction = () => {
    assistantInteractionRevision.value += 1;
  };
  const listQueryController = shallowRef<RecordQueryListQueryController>();
  const treeQueryController = shallowRef<RecordTreeQueryController>();
  function bindListQueryController(controller: RecordQueryListQueryController | undefined) {
    if (listQueryController.value === controller) return;
    listQueryController.value = controller;
    assistantContextRevision.value += 1;
  }
  function bindTreeQueryController(controller: RecordTreeQueryController | undefined) {
    if (treeQueryController.value === controller) return;
    treeQueryController.value = controller;
    assistantContextRevision.value += 1;
  }
  watch(
    () => selectedRecord.value?.version,
    () => {
      assistantContextRevision.value += 1;
    },
    { flush: 'sync' },
  );
  watch(
    [() => selectedRecord.value?.id, editingRecord, editorMode, formSessionKey],
    () => {
      assistantContextRevision.value += 1;
    },
    { deep: true, flush: 'sync' },
  );
  // RecordFormFields owns parser and renderer diagnostics. Persist only its
  // validity fact here; the host remains responsible for the save boundary.
  const deleting = ref(false);
  const activeDetailActionKey = ref<string>();
  const inputValidationStatus = useInputValidationActionStatus();
  const detailEnhancementRunning = ref(false);
  const detailActionBusy = computed(
    () => saving.value || deleting.value || togglingEnabled.value || detailEnhancementRunning.value,
  );
  const referenceRecordDetailInteraction = ref({ editing: false, busy: false, dirty: false });
  const mainFormValid = ref(true);
  const relationDraftValid = ref(true);
  const incompleteAggregateChildRelations = ref(new Set<string>());
  const formValidationRequestKey = ref(0);
  const localEditFormValid = ref(true);
  const recordOnlyAuthorizing = ref(false);
  let recordOnlySession = 0;
  function updateMainFormValidity(validity: { valid: boolean }) {
    mainFormValid.value = validity.valid;
  }
  function updateEmbeddedChildren(relationField: string, records: QueryListRecord[]) {
    if (!editingRecord.value) return;
    if (JSON.stringify(editingRecord.value[relationField] ?? []) === JSON.stringify(records)) return;
    const next = { ...editingRecord.value, [relationField]: records };
    const descriptor = context.runtime.snapshot()?.uiDescriptor;
    if (editingRecord.value.id != null && !Object.hasOwn(editingRecord.value, relationField)) {
      incompleteAggregateChildRelations.value = new Set([
        ...incompleteAggregateChildRelations.value,
        ...aggregateChildRelationCodes(relationField, descriptor),
      ]);
    }
    const rules = formComputeRulesOf(descriptor);
    editingRecord.value = applyFormComputeAfterChanges(
      next,
      aggregateChildTriggers(relationField, descriptor, rules),
      rules,
    );
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
    incompleteAggregateChildRelations.value = new Set();
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
    loadRuntimeDescriptor,
    loadRuntimeForm,
  } = useNavigatorRuntime(context, rawContext.http);
  const navigatorEntryReloadKeys = ref<Record<string, number>>({});
  const navigatorSortingKeys = ref<Record<string, boolean>>({});
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
  const flatManagementSorting = ref(false);
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
    openListRecord: openListRecordSurface,
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
    openRecycleBinRecord: (record) => {
      markAssistantUserInteraction();
      void openRecycleBinRecord(record);
    },
    markUserInteraction: markAssistantUserInteraction,
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
  /**
   * A record-only Host is rendered inside the reference-detail browser. Keep that Host mounted
   * while its drawer closes; otherwise clearing the browser's active record would unmount the
   * drawer before Ant Design can play its leave transition.
   */
  const recordOnlyClosePending = ref(false);
  const workspaceElement = ref<HTMLElement>();
  let unregisterListRefresh: (() => void) | undefined;
  let workspaceResizeObserver: ResizeObserver | undefined;
  let removeWorkspaceResizeFallback: (() => void) | undefined;
  let detailSurfacePreferenceRestoreRevision = 0;
  let detailSurfacePreferenceWrite = Promise.resolve();
  let listPageSizePreferenceRestoreRevision = 0;
  let listPageSizePreferenceWrite = Promise.resolve();

  type NavigatorRecord = { id?: string; version?: number };

  const sourceReferencePickerConfigFor = createSourceReferencePickerConfigAssembler();
  const targetReferencePickerProviders = new Map<string, ReferencePickerProvider>();

  /**
   * Target-navigator references normally retain the compact legacy picker. A descriptor may
   * explicitly request dialog or dropdown presentation; both use the target's REFERENCE
   * navigator, never ordinary target CRUD.
   */
  function targetReferencePickerConfig(
    reference: ResolvedReferenceFieldDescriptor,
    presentation: 'dialog' | 'dropdown' | undefined,
  ): Pick<RecordFormFieldPickerConfig, 'provider' | 'reloadKey'> {
    if (reference.candidateDelivery !== 'TARGET_NAVIGATOR' || !presentation) return {};
    // `titleField` belongs to the source record's read projection.  A target navigator only
    // knows the target record, so it must use its stable title/code fallback instead of treating
    // that source field as a target candidate label.
    const providerKey = `${reference.targetModuleAlias}:${reference.cardinality}`;
    let provider = targetReferencePickerProviders.get(providerKey);
    if (!provider) {
      provider = createQueryReferencePickerProvider({
        http: rawContext.http,
        reference: {
          targetModuleAlias: reference.targetModuleAlias,
          cardinality: reference.cardinality,
        },
      });
      targetReferencePickerProviders.set(providerKey, provider);
    }
    return { provider, reloadKey: `target-reference:${providerKey}` };
  }

  function referencePickerPresentationOf(field: {
    fieldControl?: { properties?: Readonly<Record<string, string>> };
  }): 'dialog' | 'dropdown' | undefined {
    const presentation = field.fieldControl?.properties?.presentation;
    if (presentation === 'DIALOG') return 'dialog';
    if (presentation === 'DROPDOWN') return 'dropdown';
    return undefined;
  }

  /**
   * The compact picker remains the default for source-owned references.  Only the two IAM trees
   * with an explicit form-field scope gain the optional expanded, lazy tree affordance here.
   */
  function sourceReferencePickerConfigWithScopedTree(
    options: Parameters<typeof sourceReferencePickerConfigFor>[0],
  ): Pick<
    RecordFormFieldPickerConfig,
    'provider' | 'reloadKey' | 'loadOptions' | 'loadTree' | 'resolveOptions' | 'scopedTree'
  > {
    const { reference, pickerFieldName, referenceResolver, formValues, source } = options;
    const scopedTree = scopedTreeScopeOf(reference);
    const config: Pick<
      RecordFormFieldPickerConfig,
      'provider' | 'reloadKey' | 'loadOptions' | 'loadTree' | 'resolveOptions' | 'scopedTree'
    > = sourceReferencePickerConfigFor({ ...options, legacyTreeLoaders: Boolean(scopedTree) });
    if (!scopedTree) return config;

    const scopeReady = () => {
      const sourceValue = formValues()[scopedTree.sourceField];
      return typeof sourceValue === 'string' && sourceValue.trim() !== '';
    };
    const candidate = (item: {
      id: string;
      title?: string;
      hasChildren?: boolean;
    }): ScopedTreePickerCandidate => ({
      id: item.id,
      title: item.title ?? item.id,
      // Older source resolvers omit this field, so keep those items expandable until their first
      // lazy load. Newer TREE_CHILDREN responses let the UI avoid a needless empty request.
      isLeaf: item.hasChildren === false,
    });
    const page = (
      response: { options: Array<{ id: string; title?: string }>; total: number; limit: number },
      pageNum: number,
    ) => {
      const pageSize = response.limit || 50;
      const nextPage = pageNum * pageSize < response.total ? String(pageNum + 1) : undefined;
      return {
        records: response.options.map(candidate),
        hasMore: nextPage != null,
        ...(nextPage ? { nextCursor: nextPage } : {}),
      };
    };
    const pageNumber = (cursor: string | undefined) => {
      const parsed = Number(cursor);
      return Number.isInteger(parsed) && parsed > 0 ? parsed : 1;
    };
    config.scopedTree = {
      title: scopedTree.title,
      disabled: !scopeReady(),
      unavailableMessage: scopeReady() ? undefined : scopedTree.unavailableMessage,
      provider: {
        loadRoot: async ({ keyword }) => {
          if (!scopeReady()) return { records: [] };
          if (keyword) {
            const response = await referenceResolver().resolve(pickerFieldName, {
              mode: 'QUERY',
              fuzzy: keyword,
              page: { pageNum: 1, pageSize: 50 },
              formValues: formValues(),
              source: source(),
            });
            return { records: response.options.map((item) => ({ ...candidate(item), isLeaf: true })) };
          }
          const response = await referenceResolver().resolve(pickerFieldName, {
            mode: 'TREE_CHILDREN',
            page: { pageNum: 1, pageSize: 50 },
            formValues: formValues(),
            source: source(),
          });
          return page(response, 1);
        },
        loadChildren: async ({ parent, cursor }) => {
          if (!scopeReady()) return { records: [] };
          const pageNum = pageNumber(cursor);
          const response = await referenceResolver().resolve(pickerFieldName, {
            mode: 'TREE_CHILDREN',
            parentId: parent.id,
            page: { pageNum, pageSize: 50 },
            formValues: formValues(),
            source: source(),
          });
          return page(response, pageNum);
        },
        resolve: async (ids) => {
          if (!scopeReady()) return [];
          const response = await referenceResolver().resolve(pickerFieldName, {
            mode: 'TRANSLATE',
            values: ids,
            formValues: formValues(),
            source: source(),
          });
          return response.results.flatMap((result) => (result.item ? [candidate(result.item)] : []));
        },
      },
    };
    return config;
  }

  function scopedTreeScopeOf(reference: ResolvedReferenceFieldDescriptor) {
    const scopes = {
      'iam.department': {
        sourceField: 'organizationId',
        title: '选择所属部门',
        unavailableMessage: '请先选择所属机构，再展开选择部门',
      },
      'iam.organization': {
        sourceField: 'tenantId',
        title: '选择适用机构',
        unavailableMessage: '请先选择适用租户，再展开选择机构',
      },
    } as const;
    const scope = scopes[reference.targetModuleAlias as keyof typeof scopes];
    if (!scope) return undefined;
    const dependencies = reference.candidateDependencies ?? [];
    return dependencies.length === 0 ||
      dependencies.some((dependency) => dependency.sourceField === scope.sourceField)
      ? scope
      : undefined;
  }

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
      const pickerFieldName = field.fieldRef.fieldName;
      const usesSourceReferenceResolver = reference.candidateDelivery === 'SOURCE_FIELD';
      const sourceReferencePickerConfig = usesSourceReferenceResolver
        ? sourceReferencePickerConfigWithScopedTree({
            reference,
            providerScopeKey: `navigator:${level.descriptor.key}`,
            sourceModuleAlias: level.context.moduleAlias,
            pickerFieldName,
            referenceResolver: () =>
              createReferenceResolveClient(
                level.context.http,
                level.context.moduleAlias,
                reference.resolvePath,
              ),
            formValues: () => ({ ...(navigatorManagementDetail.draft.value ?? {}) }),
            reloadRecord: () => navigatorManagementDetail.draft.value,
            source: () =>
              navigatorManagementDetail.draft.value?.id == null
                ? undefined
                : { recordId: String(navigatorManagementDetail.draft.value.id) },
          })
        : {};
      const targetReferencePicker = targetReferencePickerConfig(
        reference,
        referencePickerPresentationOf(field),
      );
      configs[pickerFieldName] = {
        context: createModuleContext({ http: rawContext.http, moduleAlias: reference.targetModuleAlias }),
        mode: recordPickerModeOf(reference.pickerMode),
        allowClear: !field.required?.constant,
        ...sourceReferencePickerConfig,
        ...targetReferencePicker,
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
        constraints: navigatorParentConstraints(
          level,
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
    localEditDirty,
    localEditFields,
    handleConfiguredAction,
    dismissLocalEdit,
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
    reportRefreshFailure: (cause, source) => reportDetailRefreshFailure(cause, source),
    recordChanged: (record) => {
      if (props.recordOnly) emit('record-only-change', { type: 'saved', record });
    },
  });
  const interactionBusy = computed(
    () =>
      detailActionBusy.value ||
      recordOnlyAuthorizing.value ||
      localEditSaving.value ||
      navigatorManagementDetail.saving.value ||
      navigatorManagementTogglingEnabled.value ||
      referenceRecordDetailInteraction.value.busy,
  );
  const interactionEditing = computed(
    () =>
      Boolean(detailOpen.value && editorMode.value !== 'view') ||
      Boolean(navigatorManagementDetail.open.value && navigatorManagementDetail.mode.value !== 'view') ||
      localEditOpen.value ||
      referenceRecordDetailInteraction.value.editing,
  );
  const sessionDirty = computed(
    () =>
      detailDirty.value ||
      navigatorManagementDetail.isDirty.value ||
      localEditDirty.value ||
      referenceRecordDetailInteraction.value.dirty,
  );
  function updateReferenceRecordDetailInteraction(state: {
    editing: boolean;
    busy: boolean;
    dirty?: boolean;
  }) {
    referenceRecordDetailInteraction.value = { ...state, dirty: state.dirty === true };
  }
  // Hosts may protect reload/close without inspecting the runtime's private form drafts.
  watch(
    () => ({
      editing: interactionEditing.value,
      busy: interactionBusy.value,
      dirty: sessionDirty.value,
    }),
    (state) => emit('interaction-state-change', state),
    { immediate: true, flush: 'sync' },
  );

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
  const listDetailMinimumWidth = computed(() =>
    listDetailWorkspaceMinWidth(navigatorExplorerCount.value + tenantScopeExplorerCount.value),
  );
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
    const request: Promise<void> = context.http
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
  function navigatorTreeParentPolicy(level: NavigatorLevelRuntime) {
    return resolveModulePageEnhancement(level.context.moduleAlias)?.navigator?.treeParentPolicy;
  }
  function navigatorParentConstraints(level: NavigatorLevelRuntime, currentId?: string) {
    const constraints = parentRecordConstraints<RecordPickerRecord>(currentId);
    const policy = navigatorTreeParentPolicy(level);
    if (policy) {
      constraints.push({
        code: 'tree-parent-policy',
        message: policy.rejectionMessage,
        test: (record) => policy.canUseAsParent(record as Readonly<Record<string, unknown>>),
      });
    }
    return constraints;
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
    () =>
      runtimePage.value?.list?.searchPlaceholder ??
      runtimePage.value?.explorer?.searchPlaceholder ??
      `搜索${recordLabel.value}`,
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
    const upstream = navigatorScopeContext(
      pageContextBindings.value
        .filter(
          (binding) => binding.target === 'NAVIGATOR_QUERY' && binding.targetNavigatorLevelKey === levelKey,
        )
        .map((binding) => binding.sourceKey),
    );
    return (
      [tenantScopeSubtitle.value, upstream].filter((value): value is string => Boolean(value)).join(' · ') ||
      undefined
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
    resolveModulePageEnhancement(
      context.moduleAlias,
      activeListView.value?.viewCode,
      props.descriptor.menuId,
    ),
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
  const managedPageActions = computed(() => runtimePage.value?.managedActions === true);
  const placedPageActions = computed<RecordActionItem[]>(() => placedActionsAt('PAGE'));
  const explorerRefreshAction = computed(() =>
    placedPageActions.value.find((action) => placedOperation(action.key) === 'REFRESH'),
  );
  const explorerCreateAction = computed(() =>
    placedPageActions.value.find((action) => placedOperation(action.key) === 'OPEN_CREATE'),
  );
  const explorerExtraActions = computed(() =>
    placedPageActions.value.filter(
      (action) => action !== explorerRefreshAction.value && action !== explorerCreateAction.value,
    ),
  );
  const placedDetailActions = computed<RecordActionItem[]>(() => placedActionsAt('DETAIL'));
  const placedFormActions = computed<RecordActionItem[]>(() => placedActionsAt('FORM'));
  const statusSwitchAction = computed(() => {
    const operation = selectedRecord.value?.enabled === false ? 'ENABLE' : 'DISABLE';
    return placedDetailActions.value.find((action) => placedOperation(action.key) === operation);
  });
  const placedDetailButtons = computed(() =>
    managedPageActions.value && !enhancementDetailDrawer.value
      ? placedDetailActions.value.filter(
          (action) => !['ENABLE', 'DISABLE'].includes(placedOperation(action.key) ?? ''),
        )
      : placedDetailActions.value,
  );

  function placedActionsAt(anchor: 'PAGE' | 'DETAIL' | 'FORM') {
    const actions = resolvePlacedPageActions(
      runtimePage.value?.actions ?? [],
      anchor,
      (code) => context.runtimeAction(code),
      detailPageActions.value,
      editorMode.value,
      managedPageActions.value,
      selectedRecord.value ?? undefined,
    );
    if (anchor === 'DETAIL') {
      const recordId = selectedRecord.value?.id == null ? undefined : String(selectedRecord.value.id);
      const recordFingerprint =
        selectedRecord.value == null ? undefined : recordDraftFingerprint(selectedRecord.value);
      return actions.map((action) => {
        const placement = placedAction(action.key);
        if (!action.key || placement?.statusMode !== 'INPUT_VALIDATION') return action;
        return {
          ...action,
          loading: action.key === activeDetailActionKey.value,
          iconName: inputValidationStatus.isRecordValidated(action.key, recordId, recordFingerprint)
            ? ('check' as const)
            : ('reload' as const),
        };
      });
    }
    if (anchor !== 'FORM') return actions;
    const draft = editingRecord.value;
    const draftFingerprint = draft == null ? undefined : recordDraftFingerprint(draft);
    return actions.map((action) => {
      const placement = placedAction(action.key);
      if (!action.key || placement?.statusMode !== 'INPUT_VALIDATION') return action;
      return {
        ...action,
        iconName: inputValidationStatus.isDraftValidated(action.key, draftFingerprint)
          ? ('check' as const)
          : ('reload' as const),
      };
    });
  }
  const enhancementRowExpansion = computed(() => pageEnhancement.value?.list?.rowExpansion);
  const persistentListQueryControls = computed(() => runtimePage.value?.list?.persistentQueryControls ?? []);
  const listQuerySummaries = computed(() => runtimePage.value?.list?.querySummaries ?? []);
  const listRowExpansionEnabled = computed(
    () => descriptorRelationExpansionEnabled.value || enhancementRowExpansion.value !== undefined,
  );
  const enhancementDetailActions = computed<ModulePageRecordActionContribution[]>(() => {
    const record = selectedRecord.value;
    return (pageEnhancement.value?.detail?.actions ?? []).map(({ state, ...action }) => {
      const resolvedState = record ? state?.(record) : { visible: false };
      return {
        ...action,
        ...resolvedState,
        disabled: detailActionBusy.value || action.disabled === true || resolvedState?.disabled === true,
      };
    });
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
      ...(record
        ? { record: createReadonlyCardRecordSnapshot(toRaw(record) as Record<string, unknown>) }
        : {}),
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
    () =>
      flatManagementPage.value || listDetailCardPage.value || treeManagementPage.value || treeModule.value,
  );
  const standardCrudRowActionKeys = computed<StandardCrudRowActionKey[]>(() =>
    enhancementDetailDrawer.value ? ['view'] : ['view', 'edit', 'delete'],
  );
  const pageBootstrapRequired = computed(() => !props.recordOnly && Boolean(props.descriptor.menuId));
  const pageReady = computed(
    () =>
      (!pageBootstrapRequired.value || pageBootstrap.value !== undefined) &&
      (!isListPage.value || runtimePageResolved.value),
  );
  const unsupportedPageModeText = computed(() => `${pageMode.value}入口暂未接入模块页面运行器`);
  // Management templates own the workbench's available height. Their explorer
  // and detail panes scroll internally instead of leaving a content-sized panel
  // in the tab's document flow.
  const pageLayout = computed(() =>
    constrainedManagementPage.value || visibleNavigatorLevels.value.length > 0
      ? 'workspace'
      : props.descriptor.layout,
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
      tenantId: tenantScopeId.value || currentUser?.value?.tenantId,
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
  const navigatorListScopeReady = computed(
    () =>
      tenantScopeReady.value &&
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
    const tenantDefault =
      tenantScopeRequired.value && tenantScopeId.value ? { tenantId: tenantScopeId.value } : {};
    return resource && scopeId != null
      ? { ...tenantDefault, ...defaults, [resource.scopeField]: scopeId }
      : { ...tenantDefault, ...defaults };
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
  const showStatusSwitch = computed(
    () =>
      context.abilities.hasEnable() === true &&
      typeof selectedRecord.value?.enabled === 'boolean' &&
      (!managedPageActions.value || Boolean(statusSwitchAction.value)),
  );
  watch(
    () => [showStatusSwitch.value, selectedRecord.value?.id, selectedRecord.value?.version] as const,
    ([visible, id]) => {
      if (visible && id != null) {
        void context
          .recordActions(String(id))
          .catch((cause) => presentPlatformError(cause, { source: 'module-status', phase: 'authorization' }));
      }
    },
  );
  const canToggleEnabled = computed(() => {
    const record = selectedRecord.value;
    if (
      !showStatusSwitch.value ||
      recycleBinDetailActive.value ||
      !record?.id ||
      editorMode.value !== 'view' ||
      detailLoading.value ||
      detailLoadFailed.value ||
      detailActionBusy.value
    ) {
      return false;
    }
    if (managedPageActions.value && (!statusSwitchAction.value || statusSwitchAction.value.disabled))
      return false;
    return (
      context
        .recordActionsSnapshot(String(record.id))
        ?.actions.find((action) => action.actionCode === (record.enabled ? 'disable' : 'enable'))
        ?.available === true
    );
  });
  const toggleEnabledDisabledReason = computed(() => {
    const record = selectedRecord.value;
    if (!record?.id || canToggleEnabled.value) return undefined;
    if (statusSwitchAction.value?.disabled) return statusSwitchAction.value.disabledReason;
    const actionCode = record.enabled === false ? 'enable' : 'disable';
    return (
      context
        .recordActionsSnapshot(String(record.id))
        ?.actions.find((action) => action.actionCode === actionCode)?.reason ?? '当前记录不允许执行此操作'
    );
  });

  const flatManagementRecycleBin = useRecycleBinExplorerMode<QueryListRecord>({
    context,
    listReloadKey: flatManagementReloadKey,
    searchKeyword: flatManagementSearchKeyword,
    canChange: () => !detailActionBusy.value,
    resetSelection: resetFlatManagementSelection,
  });
  const flatManagementActions = computed<RecordActionItem[]>(() => {
    if (flatManagementRecycleBin.active.value) return [];
    if (managedPageActions.value)
      return editorMode.value === 'view'
        ? []
        : [{ key: 'cancel', title: '取消', disabled: detailActionBusy.value }];
    if (editorMode.value !== 'view') {
      return [
        { key: 'cancel', title: '取消', disabled: detailActionBusy.value },
        {
          key: 'save',
          actionCode: editorMode.value === 'create' ? 'create' : 'update',
          title: saving.value && activeDetailActionKey.value === 'save' ? '保存中' : '保存',
          loading: saving.value && activeDetailActionKey.value === 'save',
          disabled: detailActionBusy.value,
          primary: true,
        },
      ];
    }
    return [
      {
        key: 'edit',
        actionCode: 'update',
        title: '编辑',
        disabled: !selectedRecord.value || detailActionBusy.value,
      },
      {
        key: 'delete',
        actionCode: 'delete',
        title: '删除',
        disabled: !selectedRecord.value || detailActionBusy.value,
        loading: activeDetailActionKey.value === 'delete',
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
    ...(flatManagementAllowsDetailEnhancement() ? placedDetailButtons.value : []),
    ...(!flatManagementRecycleBin.active.value && editorMode.value !== 'view'
      ? placedFormActions.value.map((action) => ({
          ...action,
          disabled:
            detailActionBusy.value || detailLoading.value || detailLoadFailed.value || action.disabled,
          loading: action.loading || action.key === activeDetailActionKey.value,
        }))
      : []),
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
        http: rawContext.http,
        moduleAlias: reference.targetModuleAlias,
        runtimeAccess: 'REFERENCE',
      });
      const hasPickerQueryScope = pickerQueryFieldNames.value.has(pickerFieldName);
      const usesSourceReferenceResolver = reference.candidateDelivery === 'SOURCE_FIELD';
      const sourceReferencePickerConfig: Pick<
        RecordFormFieldPickerConfig,
        'provider' | 'reloadKey' | 'loadOptions' | 'loadTree' | 'resolveOptions' | 'scopedTree'
      > = {};
      if (usesSourceReferenceResolver) {
        Object.assign(
          sourceReferencePickerConfig,
          sourceReferencePickerConfigWithScopedTree({
            reference,
            providerScopeKey: 'main',
            sourceModuleAlias: context.moduleAlias,
            pickerFieldName,
            referenceResolver: () =>
              createReferenceResolveClient(context.http, context.moduleAlias, reference.resolvePath),
            formValues: () => ({ ...(editingRecord.value ?? {}) }),
            reloadRecord: () => editingRecord.value,
            source: () =>
              editingRecord.value?.id == null ? undefined : { recordId: String(editingRecord.value.id) },
          }),
        );
      }
      const targetReferencePicker = targetReferencePickerConfig(
        reference,
        referencePickerPresentationOf(field),
      );
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
        ...targetReferencePicker,
      };
    }
    return configs;
  });

  onMounted(async () => {
    if (props.recordOnly) {
      try {
        await loadRuntimeDescriptor();
        await openRecord({ id: props.recordOnly.recordId }, 'view');
      } catch (cause) {
        pageBootstrapError.value = cause instanceof Error ? cause.message : '页面运行时加载失败';
      }
      if (!disposed) initialized();
      return;
    }
    void restoreDetailSurfaceMode();
    void restoreListPageSizePreference();
    await loadPageBootstrap();
    let resolvedTenantRequirement: boolean | undefined;
    try {
      await loadRuntimeForm(
        isListPage,
        () => Boolean(pageEnhancement.value?.recordView),
        () => {
          pageBootstrapError.value = `模块页面增强 ${pageEnhancement.value?.id ?? 'unknown'} 的业务查看呈现仅支持普通列表模块，不支持树模块`;
        },
      );
      resolvedTenantRequirement = rawContext.runtime.snapshot()?.tenantRequired === true;
    } catch (cause) {
      pageBootstrapError.value = cause instanceof Error ? cause.message : '页面运行时加载失败';
    }
    if (disposed) return;
    if (resolvedTenantRequirement !== undefined)
      props.tenantController.setRequired(resolvedTenantRequirement);
    initialized();
    // The workspace is gated by page readiness, so its element is only present
    // after both descriptors have settled.
    await nextTick();
    if (disposed) return;
    observeWorkspaceWidth();
    updateDetailSurfaceForWorkspaceWidth();
    if (isListPage.value && !pageBootstrapError.value) {
      unregisterListRefresh = modulePageListRefreshRegistry.register(context.moduleAlias, refreshList);
    }
  });

  onUnmounted(() => {
    recordOnlySession += 1;
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

  function explorerRecordText(record: object, field: string | undefined): string | undefined {
    if (!field) return undefined;
    const value = (record as Record<string, unknown>)[field];
    return value == null || String(value).trim() === '' ? undefined : String(value);
  }
  function matchesPageQuickSearch(record: object, keyword: string): boolean {
    const fields = runtimePage.value?.quickSearchFields;
    if (fields == null)
      return `${mainTreeTitle(record)} ${mainTreeSecondary(record) ?? ''}`
        .toLowerCase()
        .includes(keyword.toLowerCase());
    return fields.some((field) =>
      (explorerRecordText(record, field) ?? '').toLowerCase().includes(keyword.toLowerCase()),
    );
  }
  function mainTreeTitle(record: object): string {
    return explorerRecordText(record, runtimePage.value?.explorer?.titleField ?? 'title') ?? '未命名记录';
  }
  function mainTreeSecondary(record: object): string | undefined {
    return explorerRecordText(record, runtimePage.value?.explorer?.secondaryField);
  }

  function flatManagementItemOf(record: CrudRecordListBase): RecordExplorerItemDescriptor {
    const secondaryField = flatManagementContent.value?.secondaryField;
    const secondaryValue =
      secondaryField == null ? undefined : (record as unknown as Record<string, unknown>)[secondaryField];
    return {
      title:
        explorerRecordText(record, runtimePage.value?.explorer?.titleField) ??
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
    markAssistantUserInteraction();
    if (flatManagementRecycleBin.active.value) {
      void openRecycleBinRecord(record);
      return;
    }
    void openRecordView(record);
  }

  function handleFlatManagementAction(action: RecordActionItem) {
    markAssistantUserInteraction();
    if (placedFormActions.value.some((item) => item.key === action.key)) {
      handlePlacedFormAction(action);
      return;
    }
    if (placedDetailActions.value.some((item) => item.key === action.key)) {
      void runPlacedRecordAction(action);
      return;
    }
    const record = selectedRecord.value;
    const contribution = flatManagementEnhancementActions.value.find((item) => item.key === action.key);
    if (record && contribution) {
      void runDetailEnhancementAction(contribution, record);
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

  async function runDetailEnhancementAction(
    contribution: ModulePageRecordActionContribution,
    record: QueryListRecord,
  ) {
    if (detailActionBusy.value) return;
    detailEnhancementRunning.value = true;
    try {
      const succeeded = await runEnhancementAction(contribution, {
        ...modulePageActionContext(record),
        record,
      });
      if (succeeded && props.recordOnly) await reportRecordOnlyRefresh(record);
    } finally {
      detailEnhancementRunning.value = false;
    }
  }

  async function reportRecordOnlyRefresh(record: QueryListRecord) {
    const recordId = record.id == null ? undefined : String(record.id);
    if (!props.recordOnly || !recordId) return;
    const refresh = await reloadDetailAfterMutation(recordId);
    if (refresh.failure) reportDetailRefreshFailure(refresh.failure, 'module-record-only');
  }

  async function selectListDetailRecord(record: QueryListRecord) {
    if (selectedRecord.value?.id !== record.id && !(await mayLeaveDetailSession())) return;
    if (selectedRecord.value?.id === record.id) return;
    assistantInteractionRevision.value += 1;
    selectListDetail(record, detailSurfaceUsesDrawer.value);
  }

  async function openListRecord(record: QueryListRecord) {
    if (selectedRecord.value?.id !== record.id && !(await mayLeaveDetailSession())) return;
    assistantInteractionRevision.value += 1;
    openListRecordSurface(record);
  }

  /**
   * A selection takes effect at every navigator level: it immediately constrains
   * the list and clears only selections that depend on it.
   */
  function selectNavigatorRecord(
    levelKey: string,
    record: { id?: string },
    source: 'user' | 'assistant' | 'entry' = 'user',
  ) {
    if (!navigatorLevels.value.some((level) => level.descriptor.key === levelKey)) return;
    if (source !== 'entry' && isLockedNavigator(levelKey)) return;
    const previous = selectedNavigatorRecords.value[levelKey];
    const clearing = previous?.id != null && String(previous.id) === String(record.id);
    if (clearing && source !== 'user') return;
    if (source === 'user') assistantInteractionRevision.value += 1;
    const next = { ...selectedNavigatorRecords.value };
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
    assistantInteractionRevision.value += 1;
    navigatorDismissedSelectionKeys.value = [
      ...new Set([...navigatorDismissedSelectionKeys.value, levelKey]),
    ];
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
      selectNavigatorRecord(key, records[0], 'entry');
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

  function navigatorSorting(level: NavigatorLevelRuntime): boolean {
    return navigatorSortingKeys.value[level.descriptor.key] === true;
  }

  function navigatorSortState(level: NavigatorLevelRuntime): NavigatorSortViewState {
    const active = navigatorSorting(level);
    const disabledReason = scopeSearchKeyword.value.trim()
      ? '清空搜索后可调整排序'
      : navigatorManagementScopeDisabledReason(level);
    return {
      ...level.sort,
      active: active && level.sort.enabled && disabledReason === undefined,
      enabled: level.sort.enabled && disabledReason === undefined,
      disabledReason,
    };
  }

  function toggleNavigatorSorting(level: NavigatorLevelRuntime) {
    const key = level.descriptor.key;
    navigatorSortingKeys.value = {
      ...navigatorSortingKeys.value,
      [key]: !navigatorSorting(level),
    };
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
    if (!tenantScopeReady.value) return false;
    const criteriaKeys = navigatorManagementCriteriaKeys(level.descriptor.key);
    const values = navigatorExplorerQueryValues(level.descriptor.key);
    return criteriaKeys.every((key) => values?.[key] != null);
  }

  function navigatorManagementScopeDisabledReason(level: NavigatorLevelRuntime): string | undefined {
    if (!tenantScopeReady.value) return '请先选择业务租户';
    return navigatorManagementScopeReady(level) ? undefined : '请先完成上游范围选择';
  }

  function navigatorManagementAvailable(level: NavigatorLevelRuntime) {
    return level.descriptor.management != null;
  }

  function navigatorInlineActions(
    level: NavigatorLevelRuntime,
    record: NavigatorRecord,
  ): RecordInlineAction[] {
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
    markAssistantUserInteraction();
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
      ...(tenantScopeRequired.value && tenantScopeId.value ? { tenantId: tenantScopeId.value } : {}),
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
      navigatorManagementDetail.draft.value = new FormComputeCoordinator(computeRules).applyOnCreate(draft);
    }
  }

  const navigatorManagementEnabledVisible = computed(() => {
    const level = navigatorManagementLevel.value;
    const record = navigatorManagementDetail.draft.value;
    return Boolean(
      level &&
      navigatorManagementAvailable(level) &&
      record?.id != null &&
      navigatorManagementDetail.mode.value === 'edit' &&
      level.context.abilities.hasEnable() === true,
    );
  });

  function navigatorManagementEnabledActionAvailable(actionCode: 'enable' | 'disable'): boolean {
    const level = navigatorManagementLevel.value;
    const recordId = navigatorManagementDetail.draft.value?.id;
    if (!level || !navigatorManagementAvailable(level) || recordId == null) return false;
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
    if (
      !level ||
      !navigatorManagementAvailable(level) ||
      !record ||
      !id ||
      version === undefined ||
      navigatorManagementEnabledDisabled.value
    )
      return;

    markAssistantUserInteraction();
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
    markAssistantUserInteraction();
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
    markAssistantUserInteraction();
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
    if (!navigatorManagementAvailable(level)) return;
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
    const draft = navigatorManagementDetail.draft.value;
    if (!level || !navigatorManagementAvailable(level) || !draft || navigatorManagementDetail.saving.value)
      return;
    if (!navigatorManagementFormValid.value) {
      navigatorManagementFormValidationRequestKey.value += 1;
      return;
    }
    if (
      !passesFormValidation(
        draft,
        formValidationRulesOf(
          level.context.runtime.snapshot()?.uiDescriptor,
          level.descriptor.management?.editorSurface,
        ),
      )
    )
      return;
    const creating = navigatorManagementDetail.mode.value === 'create';
    if (level.context.can(creating ? 'create' : 'update') !== true) return;
    markAssistantUserInteraction();
    navigatorManagementDetail.saving.value = true;
    try {
      const record = recordMutationPayload(draft, navigatorManagementFormFields.value.values());
      const id = record.id == null ? undefined : String(record.id);
      const result =
        !creating && id
          ? await level.context.crud.update(id, record)
          : await level.context.crud.insert(record);
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
      closeNavigatorManagementEditor('background');
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
    if (
      !navigatorManagementAvailable(level) ||
      !id ||
      version === undefined ||
      level.context.can('delete') !== true
    )
      return;
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
      markAssistantUserInteraction();
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
    closeNavigatorManagementEditor('background');
    invalidatePendingRequests();
    detailLoading.value = false;
    detailLoadFailed.value = false;
    detailOpen.value = false;
    editorMode.value = 'view';
    selectedRecord.value = undefined;
    editingRecord.value = undefined;
    selectedTreeRecord.value = undefined;
  }

  function closeNavigatorManagementEditor(source: 'user' | 'background' = 'user') {
    if (source === 'user') markAssistantUserInteraction();
    navigatorManagementSession += 1;
    navigatorManagementTogglingEnabled.value = false;
    navigatorManagementFormValid.value = true;
    navigatorManagementDetail.close();
    navigatorManagementLevel.value = undefined;
  }

  function selectTreeRecord(record: unknown, source: 'user' | 'assistant' | 'background' = 'user') {
    const next = record as QueryListRecord;
    if (selectedTreeRecord.value?.id != null && String(selectedTreeRecord.value.id) === String(next.id))
      return;
    if (source === 'user') assistantInteractionRevision.value += 1;
    selectedTreeRecord.value = next;
    void openRecord(selectedTreeRecord.value, 'view');
  }

  function clearTreeRecordSelection() {
    if (saving.value) return;
    if (!selectedTreeRecord.value && !selectedRecord.value) return;
    assistantInteractionRevision.value += 1;
    invalidatePendingRequests();
    selectedTreeRecord.value = undefined;
    detail.close();
    selectedRecord.value = undefined;
    editingRecord.value = undefined;
  }

  function handleTreeSorted() {
    if (selectedTreeRecord.value && editorMode.value === 'view' && !saving.value) {
      void openRecord(selectedTreeRecord.value, 'view');
    }
  }

  function handleTreeLoaded(records: unknown[]) {
    setCardAssistantRecords(records as QueryListRecord[]);
    if (selectedTreeRecord.value || editorMode.value !== 'view') return;
    const firstRecord = records.at(0);
    if (firstRecord) selectTreeRecord(firstRecord, 'background');
  }

  function updateDraftField(
    fieldName: string,
    value: import('@muyun/platform-components').RecordFormFieldValue,
  ) {
    updateDraftFields([{ fieldName, value }], 'user');
  }

  function updateDraftFields(
    changes: Array<{
      fieldName: string;
      value: import('@muyun/platform-components').RecordFormFieldValue;
    }>,
    source: 'user' | 'assistant' = 'user',
  ) {
    if (!editingRecord.value || changes.length === 0) return;
    if (source === 'user') assistantInteractionRevision.value += 1;
    const rules = formComputeRulesOf(context.runtime.snapshot()?.uiDescriptor);
    let next = editingRecord.value;
    for (const { fieldName, value } of changes) {
      next = applyReferenceDependencyClears(next, fieldName, value, formFields.value);
    }
    editingRecord.value = applyFormComputeAfterChanges(
      next,
      changes.map(({ fieldName }) => fieldName),
      rules,
    );
  }

  function updateDraftReference(
    fieldName: string,
    candidate: ReferencePickerCandidate,
    source: 'user' | 'assistant' = 'user',
  ) {
    const changes: Array<{
      fieldName: string;
      value: import('@muyun/platform-components').RecordFormFieldValue;
    }> = [{ fieldName, value: candidate.id }];
    for (const [patchField, patchValue] of Object.entries(candidate.affectPatch ?? {})) {
      if (patchField !== fieldName) {
        changes.push({
          fieldName: patchField,
          value: patchValue as import('@muyun/platform-components').RecordFormFieldValue,
        });
      }
    }
    updateDraftFields(changes, source);
  }

  /**
   * Rules are attached to the resolved FORM view, not to a page/template. This
   * keeps the same calculation semantics for main details and managed
   * navigators while local-edit action forms remain isolated until they publish
   * their own server-issued FORM descriptor.
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

  function formValidationRulesOf(
    uiDescriptor: ResolvedModuleUiDescriptor | undefined,
    editorSurface?: string,
  ): readonly ResolvedFormValidationRuleDescriptor[] | undefined {
    return formViewOf(uiDescriptor, editorSurface)?.formValidationRules ?? [];
  }

  function passesFormValidation(
    draft: RecordFormRecord,
    rules: readonly ResolvedFormValidationRuleDescriptor[] | undefined,
  ): boolean {
    const failure = new FormValidationCoordinator(rules).validate(draft);
    if (!failure) return true;
    presentPlatformMessage(failure.message, { source: 'module-formula-validation', phase: 'validation' });
    return false;
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
    return new FormComputeCoordinator(rules).applyAfterChange(
      draft,
      changedFields,
      resolveChildAggregateRows(
        draft,
        context.runtime.snapshot()?.uiDescriptor,
        incompleteAggregateChildRelations.value,
      ),
    );
  }

  async function createRecord(parentId?: string) {
    if (context.can('create') !== true) return false;
    await (resolvedSelectionFormDefaultsRequest ?? loadResolvedSelectionFormDefaults());
    const defaults = { ...navigatorCreateDefaults.value, ...(parentId ? { parentId } : {}) };
    const created = commitCreateRecord(defaults);
    if (created) assistantInteractionRevision.value += 1;
    return created;
  }

  function commitCreateRecord(defaults: QueryListRecord) {
    invalidatePendingRequests();
    // Only a tree's persistent detail card has a meaningful record to restore.
    // A list drawer creates an independent draft: cancelling it must close the
    // drawer rather than reopen the row that happened to be selected.
    detail.beginCreate(defaults, {
      cancelDestination: persistentTreeDetail.value ? 'restore-view' : 'close',
    });
    if (editingRecord.value) {
      editingRecord.value = new FormComputeCoordinator(
        formComputeRulesOf(context.runtime.snapshot()?.uiDescriptor),
      ).applyOnCreate(
        editingRecord.value,
        resolveChildAggregateRows(
          editingRecord.value,
          context.runtime.snapshot()?.uiDescriptor,
          incompleteAggregateChildRelations.value,
        ),
      );
    }
    return editorMode.value === 'create' && Boolean(editingRecord.value);
  }

  function createRootRecord() {
    return createRecord();
  }

  function assistantRecordCreationReady() {
    return (
      pageReady.value &&
      !interactionBusy.value &&
      (treeResource.value ? mainTreeScopeReady.value : navigatorListScopeReady.value)
    );
  }

  async function prepareAssistantCreate() {
    if (editorMode.value !== 'view') throw new Error('A form draft is already active');
    if (context.can('create') !== true || !assistantRecordCreationReady()) {
      throw new Error('Record creation is unavailable');
    }
    await (resolvedSelectionFormDefaultsRequest ?? loadResolvedSelectionFormDefaults());
    const defaults = { ...navigatorCreateDefaults.value };
    return () => {
      if (!assistantRecordCreationReady() || !commitCreateRecord(defaults)) {
        throw new Error('Record creation is unavailable');
      }
      return assistantEditorState();
    };
  }

  /**
   * Exposes only the navigator scopes that the mounted page currently lets a user change.
   * The assistant must not derive this from raw navigator descriptors because visibility,
   * locked-entry policy and an active draft all belong to the page session.
   */
  function assistantNavigatorScopes() {
    if (editorMode.value !== 'view' || interactionBusy.value || detailDirty.value) return [];
    return visibleNavigatorLevels.value.filter(
      (level) => !isLockedNavigator(level.descriptor.key) && navigatorManagementScopeReady(level),
    );
  }

  function assistantNavigatorScopeRevision(levelKey: string) {
    return JSON.stringify({
      tenantId: tenantScopeId.value,
      selections: navigatorLevels.value.map((level) => [
        level.descriptor.key,
        selectedNavigatorRecords.value[level.descriptor.key]?.id ?? null,
      ]),
      queryValues: navigatorExplorerQueryValues(levelKey) ?? null,
    });
  }

  function applyAssistantNavigatorSelection(
    levelKey: string,
    record: QueryListRecord,
    expectedRevision: string,
  ) {
    const level = assistantNavigatorScopes().find((candidate) => candidate.descriptor.key === levelKey);
    if (
      !level ||
      record.id == null ||
      assistantNavigatorScopeRevision(levelKey) !== expectedRevision ||
      String(selectedNavigatorRecords.value[levelKey]?.id ?? '') === String(record.id)
    ) {
      return false;
    }
    selectNavigatorRecord(levelKey, record, 'assistant');
    return String(selectedNavigatorRecords.value[levelKey]?.id ?? '') === String(record.id);
  }

  async function settleAssistantPageState(signal: AbortSignal) {
    throwIfAssistantSettlementAborted(signal);
    await nextTick();
    for (let attempt = 0; attempt < 10; attempt += 1) {
      throwIfAssistantSettlementAborted(signal);
      const contextRevision = assistantContextRevision.value;
      const controller = listQueryController.value;
      const treeController = treeQueryController.value;
      await controller?.settle(signal);
      await nextTick();
      await treeController?.settle(signal);
      await nextTick();
      await settlePendingRecord(signal);
      await nextTick();
      throwIfAssistantSettlementAborted(signal);
      if (
        controller !== listQueryController.value ||
        treeController !== treeQueryController.value ||
        contextRevision !== assistantContextRevision.value
      )
        continue;
      const status = controller?.snapshot().status;
      if (
        !detailLoading.value &&
        (!navigatorListScopeReady.value || (status !== 'waiting' && status !== 'loading'))
      )
        return;
    }
    throw new Error('Assistant page state did not settle on a stable page session');
  }

  function throwIfAssistantSettlementAborted(signal: AbortSignal) {
    if (signal.aborted) throw new DOMException('Assistant invocation was cancelled', 'AbortError');
  }

  function createChildRecord() {
    const parentId = selectedRecord.value?.id == null ? undefined : String(selectedRecord.value.id);
    if (parentId) createRecord(parentId);
  }

  /** Keeps target authorization checks inside the mounted reference-detail session. */
  async function recordOnlyActionAvailable(recordId: string, actionCode: string): Promise<boolean> {
    if (!props.recordOnly) return true;
    const session = recordOnlySession;
    recordOnlyAuthorizing.value = true;
    try {
      const availability = await context.recordActions(recordId);
      return (
        session === recordOnlySession &&
        detailOpen.value &&
        availability.actions.some((action) => action.actionCode === actionCode && action.available)
      );
    } catch (cause) {
      if (session === recordOnlySession) {
        presentPlatformError(cause, { source: 'module-record-only', phase: 'authorization' });
      }
      return false;
    } finally {
      if (session === recordOnlySession) recordOnlyAuthorizing.value = false;
    }
  }

  async function editRecord(record: QueryListRecord, cancelDestination: 'close' | 'restore-view' = 'close') {
    if (context.can('update') !== true) return;
    if (props.recordOnly) {
      const recordId = record.id == null ? undefined : String(record.id);
      if (!recordId) return;
      if (!(await recordOnlyActionAvailable(recordId, 'update'))) return;
    }
    if (selectedRecord.value?.id === record.id && detail.beginEdit({ cancelDestination })) {
      assistantInteractionRevision.value += 1;
      return;
    }
    await openRecord(record, 'edit', { cancelDestination });
    if (editorMode.value === 'edit') assistantInteractionRevision.value += 1;
  }

  async function prepareAssistantEdit(recordId: string) {
    if (editorMode.value !== 'view' || detailLoading.value) throw new Error('A form draft is already active');
    const normalizedId = recordId.trim();
    const querySnapshot = listQueryController.value?.snapshot();
    if (querySnapshot?.mode === 'recycleBin') {
      throw new Error('Record editing is unavailable in recycle bin mode');
    }
    const visibleIds = new Set(assistantEditableRecordIds(selectedRecord.value?.id, querySnapshot));
    if (!normalizedId || !visibleIds.has(normalizedId)) {
      throw new Error(`Record is not available on the current page: ${recordId}`);
    }
    const selected = selectedRecord.value;
    if (context.can('update') !== true) throw new Error('Record editing is unavailable');
    if (!(await assistantRecordUpdateAvailable(normalizedId))) {
      throw new Error('Record editing is unavailable');
    }
    const loaded =
      selected?.id != null && String(selected.id) === normalizedId
        ? selected
        : await context.crud.view(normalizedId);
    return () => {
      commitLoadedRecord(loaded, 'edit', {
        cancelDestination: assistantEditCancelDestination(detailOpen.value, selected?.id, normalizedId),
      });
      return assistantEditorState();
    };
  }

  async function assistantRecordUpdateAvailable(recordId: string) {
    try {
      const availability = await context.recordActions(recordId);
      return hasAvailableRecordUpdate(availability);
    } catch (cause) {
      presentPlatformError(cause, { source: 'module-assistant', phase: 'authorization' });
      return false;
    }
  }

  function assistantEditorState() {
    return {
      editorMode: editorMode.value,
      recordId: editingRecord.value?.id == null ? undefined : String(editingRecord.value.id),
      editable: Boolean(editingRecord.value),
      dirty: detailDirty.value,
    };
  }

  async function saveRecord(actionKey = 'save') {
    const draft = editingRecord.value;
    if (!draft) return;
    if (!mainFormValid.value || !relationDraftValid.value) {
      formValidationRequestKey.value += 1;
      return;
    }
    if (!passesFormValidation(draft, formValidationRulesOf(context.runtime.snapshot()?.uiDescriptor))) return;
    if (editorMode.value === 'create' ? context.can('create') !== true : context.can('update') !== true) {
      return;
    }
    if (props.recordOnly && editorMode.value === 'edit') {
      const recordId = draft.id == null ? undefined : String(draft.id);
      if (!recordId) return;
      if (!(await recordOnlyActionAvailable(recordId, 'update'))) return;
    }
    if (
      !canMutateModuleDetail({
        hasRecord: true,
        saving: detailActionBusy.value,
        loading: detailLoading.value,
        loadFailed: detailLoadFailed.value,
      })
    ) {
      return;
    }
    assistantInteractionRevision.value += 1;
    saving.value = true;
    activeDetailActionKey.value = actionKey;
    try {
      const record = recordMutationPayload(draft, formFields.value.values());
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
        inputValidationStatus.invalidateRecord(savedId);
        void context.recordActions(savedId).catch(() => undefined);
      }
      if (props.recordOnly && refreshFailure && isRecordOnlyAccessLoss(refreshFailure)) {
        refreshList();
        await presentModuleActionSuccess(result, '保存成功');
        reportDetailRefreshFailure(refreshFailure, 'module-action');
        return;
      }
      selectedRecord.value = persistedRecord;
      if (persistentTreeDetail.value) {
        selectedTreeRecord.value = persistedRecord;
      }
      detail.applySaved(persistedRecord);
      if (props.recordOnly) {
        emit('record-only-change', { type: 'saved', record: persistedRecord });
      }
      relationDraftValid.value = true;
      detailRelationReloadKey.value += 1;
      refreshList();
      formSessionKey.value += 1;
      await presentModuleActionSuccess(result, '保存成功');
      if (refreshFailure) reportDetailRefreshFailure(refreshFailure, 'module-action');
    } catch (cause) {
      presentPlatformError(cause, { source: 'module-action', phase: 'action' });
    } finally {
      activeDetailActionKey.value = undefined;
      saving.value = false;
    }
  }

  async function deleteRecord(record: QueryListRecord, actionKey = 'delete') {
    const id = record.id == null ? undefined : String(record.id);
    const version = typeof record.version === 'number' ? record.version : undefined;
    if (!id || version === undefined || detailActionBusy.value) return;
    if (props.recordOnly) {
      if (!(await recordOnlyActionAvailable(id, 'delete'))) return;
    }
    deleting.value = true;
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
      assistantInteractionRevision.value += 1;
      activeDetailActionKey.value = actionKey;
      const result = await context.crud.delete(id, { version });
      context.invalidateRecordActions?.([id]);
      if (selectedRecord.value?.id === id) {
        detail.clearDeleted();
        selectedTreeRecord.value = undefined;
      }
      if (props.recordOnly) emit('record-only-change', { type: 'deleted' });
      refreshList();
      await presentModuleActionSuccess(result, '删除成功');
    } catch (cause) {
      presentPlatformError(cause, { source: 'module-action', phase: 'action' });
    } finally {
      activeDetailActionKey.value = undefined;
      deleting.value = false;
    }
  }

  async function toggleEnabled() {
    const record = selectedRecord.value;
    const id = record?.id == null ? undefined : String(record.id);
    const version = typeof record?.version === 'number' ? record.version : undefined;
    if (!record || !id || version === undefined || !canToggleEnabled.value) return;
    if (props.recordOnly) {
      const actionCode = record.enabled === false ? 'enable' : 'disable';
      if (!(await recordOnlyActionAvailable(id, actionCode))) return;
    }

    assistantInteractionRevision.value += 1;
    togglingEnabled.value = true;
    try {
      const enabling = record.enabled === false;
      const result = enabling
        ? await context.crud.enable(id, { version })
        : await context.crud.disable(id, { version });
      context.invalidateRecordActions?.([id]);
      const refresh = await reloadDetailAfterMutation(id);
      refreshList();
      await presentModuleActionSuccess(result, enabling ? '已启用' : '已停用');
      if (refresh.failure) reportDetailRefreshFailure(refresh.failure, 'module-action');
    } catch (cause) {
      presentPlatformError(cause, { source: 'module-action', phase: 'action' });
    } finally {
      togglingEnabled.value = false;
    }
  }

  function isRecordOnlyAccessLoss(cause: unknown) {
    return cause instanceof AppError && (cause.status === 403 || cause.status === 404);
  }

  async function reloadDetailAfterMutation(
    recordId: string,
  ): Promise<{ record?: QueryListRecord; failure?: unknown }> {
    try {
      const refreshed = await context.crud.view(recordId);
      detail.resolveLoad(refreshed);
      if (props.recordOnly) emit('record-only-change', { type: 'saved', record: refreshed });
      return { record: refreshed };
    } catch (failure) {
      return { failure };
    }
  }

  function reportDetailRefreshFailure(cause: unknown, source: string) {
    if (props.recordOnly && isRecordOnlyAccessLoss(cause)) {
      detail.clearDeleted();
      emit('record-only-change', { type: 'unavailable' });
      return;
    }
    presentPlatformError(cause, { source, phase: 'load' });
  }

  function presentModuleActionSuccess(result: unknown, fallbackMessage: string, source = 'module-action') {
    return presentActionSuccess(result, fallbackMessage, source);
  }

  function handleListAction(action: { key?: string }) {
    markAssistantUserInteraction();
    if (action.key === 'create') {
      createRecord();
      return;
    }
    const contribution = enhancementActionContributions.value.find((item) => item.key === action.key);
    if (contribution) {
      void runEnhancementAction(contribution, modulePageActionContext());
      return;
    }
    if (placedPageActions.value.some((item) => item.key === action.key)) handlePlacedPageAction(action);
  }

  function placedOperation(key: string | undefined) {
    return placedAction(key)?.operation;
  }

  function placedAction(key: string | undefined) {
    return runtimePage.value?.actions?.find(
      (entry) => `page-placement:${entry.anchor}:${entry.actionCode}` === key,
    );
  }

  function handlePlacedPageAction(action: { key?: string; actionCode?: string }) {
    if (saving.value || !placedPageActions.value.some((item) => item.key === action.key && !item.disabled))
      return;
    markAssistantUserInteraction();
    if (managedPageActions.value) {
      const operation = placedOperation(action.key);
      if (operation === 'OPEN_CREATE') createRecord();
      else if (operation === 'REFRESH') refreshList();
      else if (operation === 'INVOKE') void invokePlacedAction(action.key);
      return;
    }
    if (action.actionCode === 'create') {
      createRecord();
      return;
    }
    if (action.actionCode === 'query') refreshList();
  }

  function handleRowAction(action: { key?: string }, record: QueryListRecord) {
    markAssistantUserInteraction();
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
    markAssistantUserInteraction();
    const viewActionCode = pageEnhancement.value?.recordView?.authorizationActionCode;
    const recordId = record.id == null ? undefined : String(record.id);
    if (viewActionCode && recordId) {
      try {
        const availability = await context.recordActions(recordId);
        if (
          !availability.actions.some((action) => action.actionCode === viewActionCode && action.available)
        ) {
          return;
        }
      } catch (cause) {
        presentPlatformError(cause, { source: 'module-page-view', phase: 'authorization' });
        return;
      }
    }
    await openRecord(record, 'view');
  }

  const permissionsOpen = ref(false);
  async function permissionsChanged() {
    markAssistantUserInteraction();
    const record = selectedRecord.value;
    if (record?.id) {
      const recordId = String(record.id);
      // A permission mutation changes the record-scoped action contract even when the
      // current user still has VIEW. Refresh it eagerly so the detail and list do
      // not keep offering mutations granted by the previous assignment.
      context.invalidateRecordActions?.([recordId]);
      void context.recordActions(recordId).catch(() => undefined);
    }
    refreshList();
    if (!record?.id) return;
    await loadRecord(record, 'view', {}, false, (cause) => {
      if (
        cause instanceof AppError &&
        (cause.status === 403 ||
          cause.status === 404 ||
          cause.code === 'ACCESS_DENIED' ||
          cause.code === 'RESOURCE_NOT_FOUND')
      ) {
        clearSelectionForScopeChange();
      } else {
        presentPlatformError(cause, { source: 'record-permissions', phase: 'load' });
      }
    });
  }

  function handleDetailAction(action: { key?: string }) {
    markAssistantUserInteraction();
    if (detailPageActions.value.some((item) => item.key === action.key)) {
      handleConfiguredAction(action);
      return;
    }
    const record = selectedRecord.value;
    const contribution = enhancementDetailActions.value.find((item) => item.key === action.key);
    if (record && contribution) {
      void runDetailEnhancementAction(contribution, record);
      return;
    }
    if (placedDetailActions.value.some((item) => item.key === action.key)) void runPlacedRecordAction(action);
  }

  async function runPlacedRecordAction(action: { key?: string; actionCode?: string }) {
    const record = selectedRecord.value;
    const recordId = record?.id == null ? undefined : String(record.id);
    const actionCode =
      managedPageActions.value && placedOperation(action.key) !== 'INVOKE'
        ? (
            {
              OPEN_EDIT: 'update',
              DELETE: 'delete',
              ENABLE: 'enable',
              DISABLE: 'disable',
              MANAGE_PERMISSIONS: 'managePermissions',
            } as Record<string, string>
          )[placedOperation(action.key) ?? '']
        : action.actionCode;
    if (
      !record ||
      !recordId ||
      !actionCode ||
      editorMode.value !== 'view' ||
      !placedDetailActions.value.some((item) => item.key === action.key && !item.disabled)
    )
      return;
    if (actionCode === 'managePermissions') {
      permissionsOpen.value = true;
      return;
    }
    if (actionCode === 'update') {
      void editRecord(record, 'restore-view');
      return;
    }
    if (actionCode === 'delete') {
      void deleteRecord(record, action.key);
      return;
    }
    if (actionCode === 'enable' || actionCode === 'disable') {
      if ((actionCode === 'enable') === (record.enabled === false)) await toggleEnabled();
      return;
    }
    if (placedOperation(action.key) === 'INVOKE') await invokePlacedAction(action.key, recordId);
    else {
      const configured = detailPageActions.value.find((item) => item.actionCode === actionCode);
      if (configured) handleConfiguredAction(configured);
    }
  }

  async function invokePlacedAction(key: string | undefined, recordId?: string) {
    if (detailActionBusy.value) return;
    assistantInteractionRevision.value += 1;
    saving.value = true;
    activeDetailActionKey.value = key;
    try {
      const placement = placedAction(key);
      const formContext = placement?.anchor === 'FORM';
      const draft = formContext && !recordId && editingRecord.value ? toRaw(editingRecord.value) : undefined;
      const draftFingerprint = draft == null ? undefined : recordDraftFingerprint(draft);
      const recordFingerprint =
        recordId && selectedRecord.value && String(selectedRecord.value.id) === recordId
          ? recordDraftFingerprint(selectedRecord.value)
          : undefined;
      const validationAttempt =
        key && placement?.statusMode === 'INPUT_VALIDATION'
          ? recordId && recordFingerprint
            ? inputValidationStatus.begin(key, { kind: 'record', recordId, fingerprint: recordFingerprint })
            : draftFingerprint
              ? inputValidationStatus.begin(key, { kind: 'draft', fingerprint: draftFingerprint })
              : undefined
          : undefined;
      const result = await invokePageAction(context.http, placement?.invocation, { recordId, record: draft });
      let refreshFailure: unknown;
      if (recordId) {
        const refresh = await reloadDetailAfterMutation(recordId);
        refreshFailure = refresh.failure;
        if (validationAttempt?.target.kind === 'record' && refresh.record) {
          inputValidationStatus.succeed(validationAttempt, recordDraftFingerprint(refresh.record));
        }
      } else if (formContext && editingRecord.value) {
        // Form actions may either calculate fields or run a read-only draft diagnostic.
        // Only the former declares the record-patch protocol.
        if (hasFormActionRecordPatch(result)) {
          const { recordPatch } = formActionResult(result);
          editingRecord.value = { ...editingRecord.value, ...recordPatch };
        }
        if (validationAttempt?.target.kind === 'draft') {
          inputValidationStatus.succeed(validationAttempt);
        }
      }
      if (!formContext) refreshList();
      await presentModuleActionSuccess(result, '操作成功');
      if (refreshFailure) reportDetailRefreshFailure(refreshFailure, 'module-page-action');
    } catch (cause) {
      presentPlatformError(cause, { source: 'module-page-action', phase: 'action' });
    } finally {
      activeDetailActionKey.value = undefined;
      saving.value = false;
    }
  }

  function handlePlacedFormAction(action: { key?: string; actionCode?: string }) {
    if (
      detailActionBusy.value ||
      !placedFormActions.value.some((item) => item.key === action.key && !item.disabled)
    )
      return;
    markAssistantUserInteraction();
    const operation = placedOperation(action.key);
    const customFormInvoke =
      operation === 'INVOKE' && action.actionCode !== 'create' && action.actionCode !== 'update';
    if (
      managedPageActions.value &&
      !customFormInvoke &&
      operation !== (editorMode.value === 'create' ? 'SUBMIT_CREATE' : 'SUBMIT_UPDATE')
    )
      return;
    if (action.actionCode === 'create' || action.actionCode === 'update') {
      void saveRecord(action.key);
      return;
    }
    if (customFormInvoke && action.actionCode) {
      void invokePlacedAction(action.key);
      return;
    }
    void runPlacedRecordAction(action);
  }

  function handleBatchAction(
    action: { key?: string },
    records: QueryListRecord[],
    clearSelection: () => void,
  ) {
    const contribution = enhancementBatchActions.value.find((item) => item.key === action.key);
    if (contribution) {
      markAssistantUserInteraction();
      void runEnhancementAction(contribution, { ...modulePageActionContext(), records, clearSelection });
    }
  }

  /** Opens the declared detail workspace independently of the current card/drawer surface. */
  function openDetailWorkspaceView() {
    const view = detailWorkspaceView.value;
    const record = selectedRecord.value;
    const recordId = record?.id == null ? undefined : String(record.id);
    if (!view || !recordId || !detailWorkspaceAvailable.value || !modulePageNavigation) return;
    markAssistantUserInteraction();
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

  /** Refreshes a source view after a target mutation without replaying an in-progress source form. */
  function handleReferenceRecordChange(mutation: ReferenceRecordDetailMutation) {
    refreshList();
    const source = selectedRecord.value;
    if (!source?.id) return;
    if (editorMode.value === 'view') {
      void openRecord(source, 'view');
      return;
    }
    const draft = editingRecord.value;
    if (editorMode.value !== 'edit' || !draft) return;
    const projectedDraft = applyReferenceRecordProjection(draft, formFields.value, mutation);
    if (projectedDraft !== draft) editingRecord.value = projectedDraft;
  }

  function closeDetail() {
    if (detailActionBusy.value) return;
    assistantInteractionRevision.value += 1;
    invalidatePendingRequests();
    detail.close();
  }

  /**
   * Drawer gestures do not own a record draft.  They ask this session whether
   * it is safe to leave, so the same draft can later be guarded for card
   * selection and independent workbench tabs as well.
   */
  async function mayLeaveDetailSession() {
    if (detailActionBusy.value) return false;
    if (!detailDirty.value) return true;
    return confirmAction({
      title: '放弃未保存更改',
      content: '当前记录存在未保存的更改，关闭后将丢失。是否继续？',
      okText: '放弃更改',
      danger: true,
    });
  }

  function confirmDetailDrawerClose() {
    return mayLeaveDetailSession();
  }

  /** The reference browser owns the record-only session and releases this Host on close. */
  function closeRecordOnlyDetail() {
    if (interactionBusy.value || recordOnlyClosePending.value) return;
    assistantInteractionRevision.value += 1;
    recordOnlyClosePending.value = true;
    detail.close();
  }

  function finishRecordOnlyDetailClose() {
    if (!props.recordOnly || !recordOnlyClosePending.value) return;
    recordOnlyClosePending.value = false;
    emit('record-only-close');
  }

  /** Returns to a detail only when the state machine retained that surface. */
  async function cancelDetailEditing() {
    if (saving.value) return;
    assistantInteractionRevision.value += 1;
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
    assistantInteractionRevision.value += 1;
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
      markAssistantUserInteraction();
      void openRecycleBinRecord(record);
      return;
    }
    void openRecord(record, editorMode.value);
  }

  function recordTitle(record: QueryListRecord | undefined) {
    const titleValue = record?.title ?? record?.name ?? record?.code ?? record?.id;
    return titleValue == null ? undefined : String(titleValue);
  }

  return {
    props,
    runtimePageResolved,
    runtimePage,
    pageBootstrapError,
    pageReady,
    isListPage,
    constrainedManagementPage,
    flatManagementPage,
    managedPageActions,
    flatManagementRecycleBin,
    flatManagementContent,
    title,
    flatManagementSearchKeyword,
    editorMode,
    detailTitle,
    navigatorExplorerCount,
    navigatorExtension,
    navigatorExtensionContext,
    navigatorLevelAt,
    selectedNavigatorRecords,
    navigatorReloadKey,
    scopeSearchKeyword,
    navigatorExplorerQueryValues,
    context,
    navigatorManagementScopeReady,
    navigatorManagementScopeDisabledReason,
    navigatorPanelScopeContext,
    navigatorTreeParentPolicy,
    navigatorInlineActions,
    navigatorSortState,
    scopeReloadKey,
    createNavigatorRecord,
    handleNavigatorLoaded,
    selectNavigatorRecord,
    clearNavigatorRecord,
    handleNavigatorInlineAction,
    toggleNavigatorSorting,
    navigatorManagementLevel,
    navigatorManagementDetail,
    navigatorManagementTitle,
    navigatorManagementFormFields,
    navigatorManagementFormValidationRequestKey,
    navigatorManagementPickerConfigs,
    navigatorManagementFormContributions,
    navigatorManagementFormFieldPolicies,
    navigatorManagementEnabledVisible,
    navigatorManagementEnabledDisabled,
    navigatorManagementEnabledDisabledReason,
    navigatorManagementTogglingEnabled,
    closeNavigatorManagementEditor,
    saveNavigatorRecord,
    toggleNavigatorManagementEnabled,
    updateNavigatorManagementDraft,
    navigatorManagementFormValid,
    navigatorListScopeReady,
    selectedRecord,
    flatManagementSorting,
    navigatorListQueryValues,
    flatManagementItemOf,
    matchesPageQuickSearch,
    handleFlatManagementLoaded,
    refreshList,
    openFlatManagementRecord,
    resetFlatManagementSelection,
    navigatorScopeUnavailableDescription,
    detailWorkspaceAvailable,
    openDetailWorkspaceView,
    flatManagementDetailActions,
    handleFlatManagementAction,
    editingRecord,
    saving,
    detailActionBusy,
    activeDetailActionKey,
    detailDirty,
    sessionDirty,
    updateDraftField,
    updateDraftFields,
    updateDraftReference,
    showStatusSwitch,
    canToggleEnabled,
    toggleEnabledDisabledReason,
    togglingEnabled,
    toggleEnabled,
    detailEmptyDescription,
    detailLoading,
    detailLoadFailed,
    rawContext,
    detailDisplayFields,
    formFields,
    formSessionKey,
    assistantContextRevision,
    assistantInteractionRevision,
    listQueryController,
    treeQueryController,
    bindListQueryController,
    bindTreeQueryController,
    formValidationRequestKey,
    referencePickerConfigs,
    runtimeUiDescriptor,
    executableDetailRelations,
    detailRelationsAvailable,
    detailRelationReloadKey,
    showDetailSystemInfo,
    enhancementDetailSections,
    detailSectionContext,
    formContributions,
    formFieldPolicies,
    updateMainFormValidity,
    updateEmbeddedChildren,
    updateRelationDraftValidity,
    placedPageActions,
    handlePlacedPageAction,
    createRootRecord,
    assistantRecordCreationReady,
    prepareAssistantCreate,
    prepareAssistantEdit,
    assistantNavigatorScopes,
    assistantNavigatorScopeRevision,
    applyAssistantNavigatorSelection,
    settleAssistantPageState,
    hasCardAssistantAt,
    enhancementCardAssistant,
    cardAssistantContext,
    listDetailCardPage,
    detailSurfaceUsesDrawer,
    visibleNavigatorLevels,
    listRowExpansionEnabled,
    modulePageTitle,
    modulePageSubtitle,
    expandedListRowKeys,
    reloadKey,
    listMode,
    standardCrudRowActionKeys,
    enhancementActions,
    enhancementColumns,
    enhancementCellComponents,
    enhancementRowActionsFor,
    pageEnhancement,
    enhancementBatchActions,
    listUiConfigId,
    listQueryTemplateId,
    listPageSize,
    persistentListQueryControls,
    listQuerySummaries,
    navigatorListCriteriaKeys,
    listSearchPlaceholder,
    listEmptyDescription,
    handleLoaded,
    handleListModeChange,
    setListPageSize,
    handleRecycleBinRestore,
    selectListDetailRecord,
    openListRecord,
    handleListAction,
    handleRowAction,
    updateListRowExpansion,
    handleBatchAction,
    listRelationExpansions,
    enhancementRowExpansion,
    listRowExpansionContext,
    useDrawerDetailSurface,
    recycleBinDetailActive,
    enhancementDetailActions,
    detailPageActions,
    placedDetailButtons,
    placedFormActions,
    cancelDetailEditing,
    saveRecord,
    editRecord,
    deleteRecord,
    handleDetailAction,
    handlePlacedFormAction,
    treeManagementPage,
    treeModule,
    treePanelTitle,
    explorerRefreshAction,
    mainTreeScopeContext,
    treeSearchKeyword,
    treeReloadKey,
    explorerExtraActions,
    mainTreeScopeReady,
    mainTreeSorting,
    explorerCreateAction,
    treeRootTitle,
    selectedTreeRecord,
    mainTreeTitle,
    mainTreeSecondary,
    selectTreeRecord,
    clearTreeRecordSelection,
    handleTreeLoaded,
    handleTreeSorted,
    mainTreeScopeDescription,
    closeTreeCardEditor,
    createChildRecord,
    selectStandaloneListRecord,
    unsupportedPageModeText,
    workspaceElement,
    persistentTreeDetail,
    detailOpen,
    enhancementDetailDrawer,
    closeRecordOnlyDetail,
    closeDetail,
    confirmDetailDrawerClose,
    finishRecordOnlyDetailClose,
    retryLoadDetail,
    recordViewContext,
    narrowDetailSurface,
    usePinnedDetailSurface,
    referenceRecordDetailBrowser,
    handleReferenceRecordChange,
    referenceRecordDetailInteraction,
    updateReferenceRecordDetailInteraction,
    permissionsOpen,
    permissionsChanged,
    enhancementDrawer,
    enhancementDrawerOpen,
    closeEnhancementDrawer,
    disposeEnhancementDrawer,
    localEditOpen,
    localEditBlock,
    localEditSaving,
    submitLocalEdit,
    dismissLocalEdit,
    localEditDraft,
    localEditFields,
    updateLocalEditFormValidity,
    pageLayout,
  };
}

export type ModulePageSessionView = ShallowUnwrapRef<ReturnType<typeof useModulePageSession>>;
