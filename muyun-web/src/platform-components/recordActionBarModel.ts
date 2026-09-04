import type { ModuleContext } from '@muyun/web-core';
import type { UiIconName } from '@muyun/vue-ui-antdv';

export interface RecordActionItem {
  key?: string;
  actionCode?: string;
  title: string;
  before?: string;
  after?: string;
  visible?: boolean;
  /** Keep the row action visible beside the default row action instead of placing it in “more”. */
  pinned?: boolean;
  disabled?: boolean;
  /** Explains why an otherwise visible action cannot be used. */
  disabledReason?: string;
  loading?: boolean;
  primary?: boolean;
  /** Business importance; the detail shell decides direct display versus “更多”. */
  actionLevel?: 'primary' | 'standard' | 'secondary';
  danger?: boolean;
  iconName?: UiIconName;
  /** Runtime-only authorization source for actions rendered outside their owning module surface. */
  authorizationContext?: ModuleContext<unknown>;
  /** Runtime-only record ID used with authorizationContext. */
  authorizationRecordId?: string;
}

export interface ResolvedRecordActionItem extends RecordActionItem {
  key: string;
  actionCode?: string;
  authorized: boolean;
  reason?: string;
  disabled: boolean;
  loading: boolean;
}

type RecordActionContext = Pick<ModuleContext<unknown>, 'action'> & {
  runtime?: Pick<ModuleContext<unknown>['runtime'], 'snapshot'>;
  recordActionsSnapshot?: ModuleContext<unknown>['recordActionsSnapshot'];
};

export function resolveRecordActions(
  context: RecordActionContext,
  actions: RecordActionItem[],
  defaultLoading = false,
  recordId?: string,
): ResolvedRecordActionItem[] {
  return actions.flatMap((action, index) => {
    if (action.visible === false) {
      return [];
    }
    const authorizationContext = action.authorizationContext ?? context;
    const authorizationRecordId = action.authorizationContext ? action.authorizationRecordId : recordId;
    const awaitingAuthorizationRecord = action.authorizationContext != null && !authorizationRecordId;
    const actionState =
      action.actionCode && !awaitingAuthorizationRecord
        ? authorizationContext.action(action.actionCode, authorizationRecordId)
        : undefined;
    if (
      action.actionCode &&
      actionState == null &&
      !awaitingAuthorizationRecord &&
      actionIsConfirmedMissing(authorizationContext, action.actionCode, authorizationRecordId)
    ) {
      return [];
    }
    const authorized =
      action.actionCode && !awaitingAuthorizationRecord
        ? actionState?.available === true
        : !awaitingAuthorizationRecord;
    const loading = action.loading ?? defaultLoading;
    return [
      {
        ...action,
        key: action.key ?? action.actionCode ?? `action-${index}`,
        iconName: action.iconName ?? defaultActionIcon(action),
        authorized,
        reason: actionState?.reason ?? (awaitingAuthorizationRecord ? '请先选择作用域记录' : undefined),
        disabled: loading || action.disabled === true || !authorized,
        loading,
      },
    ];
  });
}

function actionIsConfirmedMissing(context: RecordActionContext, actionCode: string, recordId?: string) {
  if (!context.runtime?.snapshot()) {
    return false;
  }
  if (!recordId) {
    return true;
  }
  const availability = context.recordActionsSnapshot?.(recordId);
  return availability != null && !availability.actions.some((action) => action.actionCode === actionCode);
}

function defaultActionIcon(action: RecordActionItem): UiIconName | undefined {
  const code = action.actionCode ?? action.key;
  const operation = code?.split('_').at(-1);
  if (operation === 'create') {
    return 'plus';
  }
  if (operation === 'update' || action.key?.includes('edit')) {
    return 'edit';
  }
  if (operation === 'delete') {
    return 'delete';
  }
  if (operation === 'enable' || operation === 'disable' || action.key?.includes('toggle')) {
    return 'power';
  }
  if (action.key?.includes('save')) {
    return 'save';
  }
  if (action.key?.includes('cancel')) {
    return 'close';
  }
  return undefined;
}

export function mergeRecordActions(
  baseActions: RecordActionItem[],
  extraActions: RecordActionItem[],
): RecordActionItem[] {
  const merged = [...baseActions];
  for (const action of extraActions) {
    const beforeIndex = action.before ? findActionIndex(merged, action.before) : -1;
    if (beforeIndex >= 0) {
      merged.splice(beforeIndex, 0, action);
      continue;
    }
    const afterIndex = action.after ? findActionIndex(merged, action.after) : -1;
    if (afterIndex >= 0) {
      merged.splice(afterIndex + 1, 0, action);
      continue;
    }
    merged.push(action);
  }
  return merged;
}

function findActionIndex(actions: RecordActionItem[], key: string) {
  return actions.findIndex((action) => action.key === key || action.actionCode === key);
}
