import { ref, type Ref } from 'vue';
import {
  resolveRecordDetailFields,
  resolveRecordFormFields,
  type QueryListRecord,
  type RecordFormFieldDescriptor,
} from '@muyun/platform-components';
import type {
  ResolvedModuleUiDescriptor,
  ResolvedModulePageDescriptor,
  ResolvedPageContextBindingDescriptor,
  ResolvedPageNavigatorLevelDescriptor,
  RouteQueryValue,
} from '@muyun/web-contracts';
import { createModuleContext, type HttpClient, type ModuleContext } from '@muyun/web-core';
import { navigatorEntrySelectionOf, type NavigatorEntrySelection } from '../navigatorEntrySelection';

/** Runtime state that belongs to the page/navigator session rather than to a Vue host. */
export interface NavigatorLevelRuntime {
  descriptor: ResolvedPageNavigatorLevelDescriptor;
  context: ModuleContext<QueryListRecord>;
  tree: boolean;
}

export interface NavigatorEntrySelectionChange {
  previous?: NavigatorEntrySelection;
  current?: NavigatorEntrySelection;
}

interface NavigatorEntrySelectionResolution {
  identity: string;
  revision: number;
  record?: QueryListRecord;
}

/**
 * Resolves the standard runtime descriptor once and owns all navigator source
 * contexts. The host only binds the returned state to visual surfaces; this
 * keeps list, detail and tree templates on the same navigator session.
 */
export function useNavigatorRuntime(
  context: ModuleContext<QueryListRecord>,
  crossModuleHttp: HttpClient = context.http,
) {
  const formFields = ref<Map<string, RecordFormFieldDescriptor>>(resolveRecordFormFields(undefined));
  const detailDisplayFields = ref(resolveRecordDetailFields(undefined));
  const runtimePage = ref<ResolvedModulePageDescriptor>();
  const runtimeUiDescriptor = ref<ResolvedModuleUiDescriptor>();
  /** True once the host's own descriptor has settled, including modules without a page DSL. */
  const runtimePageResolved = ref(false);
  const treeModule = ref(false);
  const navigatorLevels = ref<NavigatorLevelRuntime[]>([]);
  const pageContextBindings = ref<ResolvedPageContextBindingDescriptor[]>([]);
  const selectedNavigatorRecords = ref<Record<string, QueryListRecord | undefined>>({});
  const navigatorSingleResultKeys = ref<string[]>([]);
  const navigatorDismissedSelectionKeys = ref<string[]>([]);
  const navigatorEntrySelection = ref<NavigatorEntrySelection>();
  const settledNavigatorEntrySelectionIdentity = ref<string>();
  const navigatorEntrySelectionResolutions = new Map<string, Promise<NavigatorEntrySelectionResolution>>();
  let navigatorEntrySelectionRevision = 0;

  /**
   * Replaces the initial-selection request for this host session. It is
   * deliberately consumed once: a later navigator refresh must not undo a
   * user's explicit deselection or context switch.
   */
  function setNavigatorEntrySelection(
    params: Record<string, RouteQueryValue> | undefined,
  ): NavigatorEntrySelectionChange | undefined {
    const next = navigatorEntrySelectionOf(params);
    if (next?.identity === navigatorEntrySelection.value?.identity) return;
    const previous = navigatorEntrySelection.value;
    navigatorEntrySelectionRevision += 1;
    navigatorEntrySelection.value = next;
    settledNavigatorEntrySelectionIdentity.value = undefined;
    navigatorEntrySelectionResolutions.clear();
    return { previous, current: next };
  }

  /**
   * Reports whether this source owns a still-unconsumed controlled-entry
   * selection. The host uses this to defer FIRST_RECORD until the explicit
   * entry lookup is settled.
   */
  function navigatorEntrySelectionPendingFor(level: NavigatorLevelRuntime): boolean {
    return navigatorEntrySelectionFor(level) !== undefined;
  }

  /**
   * Resolves a controlled-entry selection through the same REFERENCE query
   * transport as the navigator itself. A fixed-size explorer page is a visual
   * convenience, not an authorization or completeness boundary: an id outside
   * that page is queried exactly with the server's REFERENCE action and data
   * scope still in force. No direct record view is attempted.
   */
  function resolveNavigatorEntrySelection(
    level: NavigatorLevelRuntime,
    records: readonly { id?: string }[],
    externalQueryValues?: Record<string, unknown>,
  ): Promise<NavigatorEntrySelectionResolution | undefined> {
    const requested = navigatorEntrySelectionFor(level);
    if (!requested) return Promise.resolve(undefined);
    const resolutionKey = `${requested.identity}\u0000${level.descriptor.key}`;
    const existing = navigatorEntrySelectionResolutions.get(resolutionKey);
    if (existing) return existing;
    const resolution = resolveNavigatorEntryRecord(
      level,
      records,
      requested,
      externalQueryValues,
      navigatorEntrySelectionRevision,
    );
    navigatorEntrySelectionResolutions.set(resolutionKey, resolution);
    return resolution;
  }

  /** Guards asynchronous exact-reference results against a replaced entry request. */
  function isCurrentNavigatorEntrySelection(
    level: NavigatorLevelRuntime,
    resolution: NavigatorEntrySelectionResolution,
  ): boolean {
    return (
      navigatorEntrySelectionRevision === resolution.revision &&
      navigatorEntrySelection.value?.identity === resolution.identity &&
      navigatorEntrySelectionMatchesLevel(level, resolution.identity)
    );
  }

  function navigatorEntrySelectionFor(level: NavigatorLevelRuntime): NavigatorEntrySelection | undefined {
    const requested = navigatorEntrySelection.value;
    if (!requested || settledNavigatorEntrySelectionIdentity.value === requested.identity) return undefined;
    const matchingLevels = navigatorLevels.value.filter(
      (candidate) => candidate.descriptor.sourceModuleAlias === requested.moduleAlias,
    );
    if (matchingLevels.length !== 1) {
      // Repeated source aliases cannot be resolved from a module alias alone.
      // Settle the request rather than selecting an arbitrary navigator level.
      if (matchingLevels.length > 1) settledNavigatorEntrySelectionIdentity.value = requested.identity;
      return undefined;
    }
    return navigatorEntrySelectionMatchesLevel(level, requested.identity) ? requested : undefined;
  }

  function navigatorEntrySelectionMatchesLevel(level: NavigatorLevelRuntime, identity: string): boolean {
    const requested = navigatorEntrySelection.value;
    if (requested?.identity !== identity) return false;
    const matchingLevels = navigatorLevels.value.filter(
      (candidate) => candidate.descriptor.sourceModuleAlias === requested.moduleAlias,
    );
    return matchingLevels.length === 1 && matchingLevels[0]?.descriptor.key === level.descriptor.key;
  }

  async function resolveNavigatorEntryRecord(
    level: NavigatorLevelRuntime,
    records: readonly { id?: string }[],
    requested: NavigatorEntrySelection,
    externalQueryValues: Record<string, unknown> | undefined,
    revision: number,
  ): Promise<NavigatorEntrySelectionResolution> {
    let record: QueryListRecord | undefined;
    try {
      const loaded = records.find((record) => record.id != null && String(record.id) === requested.recordId);
      if (loaded) {
        record = loaded as QueryListRecord;
      } else {
        const response = await level.context.crud.query({
          page: { pageNum: 1, pageSize: 1 },
          conditions: [{ fieldName: 'id', operator: 'EQ', values: [requested.recordId] }],
          ...(externalQueryValues && Object.keys(externalQueryValues).length > 0
            ? { externalQueryValues }
            : {}),
        });
        record = response.records.find((item) => item.id != null && String(item.id) === requested.recordId);
      }
    } catch {
      // An invalid or inaccessible address has the same safe outcome as no
      // matching record. The ordinary navigator remains available to the user.
    } finally {
      if (
        navigatorEntrySelectionRevision === revision &&
        navigatorEntrySelection.value?.identity === requested.identity
      ) {
        settledNavigatorEntrySelectionIdentity.value = requested.identity;
      }
    }
    return { identity: requested.identity, revision, record };
  }

  async function loadRuntimeForm(
    isListPage: Ref<boolean>,
    hasBusinessRecordView: () => boolean,
    fail: (message: string) => void,
  ) {
    if (!isListPage.value) return;
    const runtimeContext = await context.runtime.ready;
    runtimeUiDescriptor.value = runtimeContext.uiDescriptor;
    runtimePage.value = runtimeContext.uiDescriptor?.page;
    runtimePageResolved.value = true;
    treeModule.value = context.abilities.hasTree() === true;
    if (treeModule.value && hasBusinessRecordView()) {
      fail('模块页面增强的业务查看呈现仅支持普通列表模块，不支持树模块');
      return;
    }
    const descriptors = runtimePage.value?.navigator?.levels ?? [];
    pageContextBindings.value = runtimePage.value?.navigator?.contextBindings ?? [];
    selectedNavigatorRecords.value = {};
    navigatorSingleResultKeys.value = [];
    navigatorDismissedSelectionKeys.value = [];
    navigatorEntrySelectionRevision += 1;
    settledNavigatorEntrySelectionIdentity.value = undefined;
    navigatorEntrySelectionResolutions.clear();
    navigatorLevels.value = await Promise.all(
      descriptors.map(async (descriptor) => {
        const navigatorContext = createModuleContext<QueryListRecord>({
          http: crossModuleHttp,
          moduleAlias: descriptor.sourceModuleAlias,
          runtimeAccess: 'REFERENCE',
          navigatorReference: {
            hostModuleAlias: context.moduleAlias,
            targetLevelKey: descriptor.key,
          },
        });
        await navigatorContext.runtime.ready;
        const requiredCapability = descriptor.kind === 'TREE' ? 'REFERENCE_TREE' : 'REFERENCE_QUERY';
        const sourceCapabilities = navigatorContext.runtime.snapshot()?.navigatorSourceCapabilities;
        if (sourceCapabilities !== undefined && !sourceCapabilities.includes(requiredCapability)) {
          throw new Error(
            `导航源能力不可用：层级 ${descriptor.key} 引用模块 ${descriptor.sourceModuleAlias}，缺少 ${requiredCapability}`,
          );
        }
        return {
          descriptor,
          context: navigatorContext,
          tree:
            descriptor.kind === 'TREE' &&
            (sourceCapabilities?.includes('REFERENCE_TREE') ?? navigatorContext.abilities.hasTree() === true),
        };
      }),
    );
    const treeResource = runtimePage.value?.treeResource?.resource;
    formFields.value = resolveRecordFormFields(runtimeContext.uiDescriptor, treeResource);
    detailDisplayFields.value = resolveRecordDetailFields(runtimeContext.uiDescriptor, treeResource);
  }

  return {
    formFields,
    detailDisplayFields,
    runtimePage,
    runtimeUiDescriptor,
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
  };
}
