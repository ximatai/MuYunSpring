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
  recordActionsSnapshot(recordId: string): ModuleRecordActionAvailability | undefined;
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
    const request = http
      .request<ModuleRecordActionAvailability>({
        path: `/${encodeURIComponent(moduleAlias)}/actions/${encodeURIComponent(normalizedRecordId)}`,
      })
      .then((availability) => {
        const next = new Map(recordActionSnapshots.value);
        next.set(normalizedRecordId, availability);
        recordActionSnapshots.value = next;
        recordActionLoading.delete(normalizedRecordId);
        return availability;
      })
      .catch((cause) => {
        recordActionLoading.delete(normalizedRecordId);
        throw cause;
      });
    recordActionLoading.set(normalizedRecordId, request);
    return request;
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
    recordActionsSnapshot: (recordId) =>
      recordId == null || !recordId.trim() ? undefined : recordActionSnapshots.value.get(recordId.trim()),
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
