import { shallowRef } from 'vue';
import type { ResolvedModuleUiDescriptor } from '@muyun/web-contracts';
import { normalizeError, type AppError } from '../errors';
import type { HttpClient } from '../http';
import type { ModuleAbilityCode } from './abilityCodes';

export interface ModuleRuntimeAction {
  actionCode: string;
  permissionActionCode?: string;
  title?: string;
  actionLevel?: 'DEFAULT' | 'LIST' | 'RECORD' | 'BATCH' | 'ANY';
  category?: string;
  accessMode?: 'AUTH_REQUIRED' | 'LOGIN_REQUIRED' | 'ANONYMOUS_ALLOWED';
  actionAuth?: boolean;
  dataAuth?: boolean;
  defaultGrantPolicy?: string;
  executorType?: string;
  executorKey?: string;
  authorized: boolean;
  authorizationDecision?: string;
}

export interface ModuleActionState {
  actionCode: string;
  available: boolean;
  reason?: string;
  authorized?: boolean;
  recordId?: string;
  definition?: ModuleRuntimeAction;
  actionLevel?: ModuleRuntimeAction['actionLevel'];
  title?: string;
}

export interface ModuleRecordActionAvailability {
  recordId: string;
  actions: ModuleRecordActionDecision[];
}

export interface ModuleRecordActionDecision {
  actionCode: string;
  available: boolean;
  reason?: string;
}

export interface ModuleRuntimeContext {
  moduleAlias: string;
  title?: string;
  moduleKind?: 'STATIC' | 'DYNAMIC';
  entryType?: 'module' | 'route' | 'link';
  entryRoute?: string;
  entryExternalUrl?: string;
  mainEntityAlias?: string;
  capabilities: string[];
  /** Persisted fields that define the server-authoritative sort partition. */
  sortPartitionFields?: string[];
  abilities?: string[];
  actions: ModuleRuntimeAction[];
  uiDescriptor?: ResolvedModuleUiDescriptor;
}

export interface ModuleRuntimeContextState {
  ready: Promise<ModuleRuntimeContext>;
  load(): Promise<ModuleRuntimeContext>;
  snapshot(): ModuleRuntimeContext | undefined;
  error(): AppError | undefined;
  hasAbility(ability: ModuleAbilityCode | string): boolean | undefined;
  action(actionCode: string, recordId?: string): ModuleActionState | undefined;
  runtimeAction(actionCode: string): ModuleRuntimeAction | undefined;
  can(actionCode: string, recordId?: string): boolean | undefined;
  recordActions(recordId: string): Promise<ModuleRecordActionAvailability>;
  recordActionsBatch?(recordIds: string[]): Promise<ModuleRecordActionAvailability[]>;
  recordActionsSnapshot(recordId: string): ModuleRecordActionAvailability | undefined;
  invalidateRecordActions?(recordIds?: string[]): void;
}

export function createModuleRuntimeContextState(
  http: HttpClient,
  moduleAlias: string,
  access: 'MENU' | 'REFERENCE' = 'MENU',
): ModuleRuntimeContextState {
  const current = shallowRef<ModuleRuntimeContext>();
  const currentError = shallowRef<AppError>();
  const recordActionSnapshots = shallowRef(new Map<string, ModuleRecordActionAvailability>());
  const recordActionLoading = new Map<string, Promise<ModuleRecordActionAvailability>>();
  const recordActionRevisions = new Map<string, number>();
  let loading: Promise<ModuleRuntimeContext> | undefined;
  const load = () => {
    loading ??= http
      .request<ModuleRuntimeContext>({
        path: `/platform.module/${encodeURIComponent(moduleAlias)}/${access === 'REFERENCE' ? 'reference-context' : 'context'}`,
      })
      .then((context) => {
        current.value = context;
        currentError.value = undefined;
        return context;
      })
      .catch((cause) => {
        currentError.value = normalizeError(cause);
        loading = undefined;
        throw cause;
      });
    return loading;
  };
  const recordActions = (recordId: string) => {
    const normalizedRecordId = requireRecordId(recordId);
    const existing = recordActionLoading.get(normalizedRecordId);
    if (existing) {
      return existing;
    }
    const revision = recordActionRevisions.get(normalizedRecordId) ?? 0;
    const request = http
      .request<ModuleRecordActionAvailability>({
        path: `/${encodeURIComponent(moduleAlias)}/actions/${encodeURIComponent(normalizedRecordId)}`,
      })
      .then((availability) => {
        if ((recordActionRevisions.get(normalizedRecordId) ?? 0) === revision) {
          const next = new Map(recordActionSnapshots.value);
          next.set(normalizedRecordId, availability);
          recordActionSnapshots.value = next;
        }
        if (recordActionLoading.get(normalizedRecordId) === request) {
          recordActionLoading.delete(normalizedRecordId);
        }
        return availability;
      })
      .catch((cause) => {
        if (recordActionLoading.get(normalizedRecordId) === request) {
          recordActionLoading.delete(normalizedRecordId);
        }
        throw cause;
      });
    recordActionLoading.set(normalizedRecordId, request);
    return request;
  };
  const recordActionsBatch = async (recordIds: string[]) => {
    const normalizedRecordIds = [...new Set(recordIds.map(requireRecordId))];
    if (normalizedRecordIds.length === 0) {
      return [];
    }
    const resolved = new Map<string, ModuleRecordActionAvailability>();
    const pending = new Map<string, Promise<ModuleRecordActionAvailability>>();
    const missing: string[] = [];
    for (const recordId of normalizedRecordIds) {
      const snapshot = recordActionSnapshots.value.get(recordId);
      if (snapshot) {
        resolved.set(recordId, snapshot);
        continue;
      }
      const loading = recordActionLoading.get(recordId);
      if (loading) {
        pending.set(recordId, loading);
        continue;
      }
      missing.push(recordId);
    }
    for (const recordIdsChunk of chunks(missing, 100)) {
      const revisions = new Map(
        recordIdsChunk.map((recordId) => [recordId, recordActionRevisions.get(recordId) ?? 0]),
      );
      const request = http
        .request<ModuleRecordActionAvailability[]>({
          method: 'POST',
          path: `/${encodeURIComponent(moduleAlias)}/actions/availability`,
          body: { recordIds: recordIdsChunk },
        })
        .then((availability) => {
          const byRecordId = new Map(availability.map((item) => [item.recordId, item]));
          if (byRecordId.size !== recordIdsChunk.length || recordIdsChunk.some((id) => !byRecordId.has(id))) {
            throw new Error('Record action availability response is incomplete');
          }
          const next = new Map(recordActionSnapshots.value);
          for (const item of availability) {
            if ((recordActionRevisions.get(item.recordId) ?? 0) === revisions.get(item.recordId)) {
              next.set(item.recordId, item);
            }
          }
          recordActionSnapshots.value = next;
          return byRecordId;
        });
      for (const recordId of recordIdsChunk) {
        const itemRequest = request.then((availability) => availability.get(recordId)!);
        recordActionLoading.set(recordId, itemRequest);
        pending.set(recordId, itemRequest);
        itemRequest
          .finally(() => {
            if (recordActionLoading.get(recordId) === itemRequest) {
              recordActionLoading.delete(recordId);
            }
          })
          .catch(() => {
            // The requesting explorer receives the failure through its own promise.
          });
      }
    }
    for (const [recordId, request] of pending) {
      resolved.set(recordId, await request);
    }
    return normalizedRecordIds.map((recordId) => resolved.get(recordId)!);
  };
  const invalidateRecordActions = (recordIds?: string[]) => {
    if (!recordIds) {
      for (const recordId of new Set([
        ...recordActionSnapshots.value.keys(),
        ...recordActionLoading.keys(),
      ])) {
        recordActionRevisions.set(recordId, (recordActionRevisions.get(recordId) ?? 0) + 1);
      }
      recordActionLoading.clear();
      recordActionSnapshots.value = new Map();
      return;
    }
    const next = new Map(recordActionSnapshots.value);
    for (const recordId of recordIds) {
      const normalizedRecordId = requireRecordId(recordId);
      recordActionRevisions.set(normalizedRecordId, (recordActionRevisions.get(normalizedRecordId) ?? 0) + 1);
      recordActionLoading.delete(normalizedRecordId);
      next.delete(normalizedRecordId);
    }
    recordActionSnapshots.value = next;
  };
  const ready = load();
  ready.catch(() => {
    // Keep background context loading from becoming an unhandled rejection.
  });
  return {
    ready,
    load,
    snapshot: () => current.value,
    error: () => currentError.value,
    hasAbility: (ability) => {
      const context = current.value;
      if (!context) {
        return undefined;
      }
      return runtimeAbilityCodes(context).includes(ability);
    },
    action: (actionCode, recordId) =>
      actionState(current.value, recordActionSnapshots.value, actionCode, recordId),
    runtimeAction: (actionCode) => runtimeAction(current.value, actionCode),
    can: (actionCode, recordId) => {
      return actionState(current.value, recordActionSnapshots.value, actionCode, recordId)?.available;
    },
    recordActions,
    recordActionsBatch,
    recordActionsSnapshot: (recordId) =>
      recordId == null || !recordId.trim() ? undefined : recordActionSnapshots.value.get(recordId.trim()),
    invalidateRecordActions,
  };
}

export function isRuntimeAbilityAvailable(
  context: ModuleRuntimeContext | undefined,
  ability: ModuleAbilityCode,
) {
  if (!context) {
    return false;
  }
  return runtimeAbilityCodes(context).includes(ability);
}

function runtimeAbilityCodes(context: ModuleRuntimeContext): string[] {
  return context.abilities ?? context.capabilities.map(abilityCodeOfCapability);
}

function chunks<T>(items: T[], size: number): T[][] {
  const result: T[][] = [];
  for (let index = 0; index < items.length; index += size) {
    result.push(items.slice(index, index + size));
  }
  return result;
}

function actionState(
  context: ModuleRuntimeContext | undefined,
  records: Map<string, ModuleRecordActionAvailability>,
  actionCode: string,
  recordId?: string,
): ModuleActionState | undefined {
  const normalizedActionCode = requireActionCode(actionCode);
  const definition = runtimeAction(context, normalizedActionCode);
  const normalizedRecordId = recordId == null || !recordId.trim() ? undefined : recordId.trim();
  if (normalizedRecordId) {
    if (!definition) {
      return undefined;
    }
    const recordDecision = records
      .get(normalizedRecordId)
      ?.actions.find((action) => action.actionCode === normalizedActionCode);
    if (!recordDecision) {
      return undefined;
    }
    return {
      actionCode: normalizedActionCode,
      available: recordDecision.available,
      reason: recordDecision.reason,
      authorized: definition?.authorized,
      recordId: normalizedRecordId,
      definition,
      actionLevel: definition?.actionLevel,
      title: definition?.title,
    };
  }
  if (!definition) {
    return undefined;
  }
  return {
    actionCode: normalizedActionCode,
    available: definition.authorized,
    reason: definition.authorized ? undefined : definition.authorizationDecision,
    authorized: definition.authorized,
    definition,
    actionLevel: definition.actionLevel,
    title: definition.title,
  };
}

function runtimeAction(context: ModuleRuntimeContext | undefined, actionCode: string) {
  const normalizedActionCode = requireActionCode(actionCode);
  return context?.actions.find((action) => action.actionCode === normalizedActionCode);
}

function abilityCodeOfCapability(capability: string) {
  return capability.toLowerCase().replace(/_([a-z])/g, (_match, char: string) => char.toUpperCase());
}

function requireRecordId(recordId: string) {
  if (recordId == null || !recordId.trim()) {
    throw new Error('recordId must not be blank');
  }
  return recordId.trim();
}

function requireActionCode(actionCode: string) {
  if (actionCode == null || !actionCode.trim()) {
    throw new Error('actionCode must not be blank');
  }
  return actionCode.trim();
}
