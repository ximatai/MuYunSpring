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
  danger?: boolean;
  iconName?: UiIconName;
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
    const actionState = action.actionCode ? context.action(action.actionCode, recordId) : undefined;
    if (action.actionCode && !actionState && actionIsConfirmedMissing(context, action.actionCode, recordId)) {
      return [];
    }
    const authorized = action.actionCode ? actionState?.available === true : true;
    const loading = action.loading ?? defaultLoading;
    return [
      {
        ...action,
        key: action.key ?? action.actionCode ?? `action-${index}`,
        iconName: action.iconName ?? defaultActionIcon(action),
        authorized,
        reason: actionState?.reason,
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
