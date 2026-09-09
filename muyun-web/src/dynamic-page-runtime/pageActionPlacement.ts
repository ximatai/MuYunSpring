import { pageActionIntent, pageActionEntryTitle, pageActionEntryVisible } from '@muyun/web-core';
import type { RecordActionItem } from '@muyun/platform-components';
import type { ResolvedPageActionDescriptor } from '@muyun/web-contracts';

/** Placements choose presentation; execution must already have a platform workflow or issued action block. */
export function resolvePlacedPageActions(
  placements: ResolvedPageActionDescriptor[],
  anchor: ResolvedPageActionDescriptor['anchor'],
  actionOf: (code: string) =>
    | {
        actionCode: string;
        title?: string;
        actionLevel?: string;
        category?: string;
        executorType?: string;
        formSupported?: boolean;
        bindingPending?: boolean;
      }
    | undefined,
  detailActions: RecordActionItem[],
  mode: 'create' | 'edit' | 'view',
  managed = false,
  record?: { enabled?: unknown },
): RecordActionItem[] {
  return placements.flatMap((placement) => {
    if (placement.anchor !== anchor) return [];
    const intent = pageActionIntent(placement.actionCode, anchor);
    const invoke = placement.operation === 'INVOKE';
    if (managed && ((!intent && !invoke) || !pageActionEntryVisible(placement, mode))) return [];
    const action = actionOf(placement.actionCode);
    if (!action || action.bindingPending) return [];
    if (anchor === 'PAGE' && !['LIST', 'ANY'].includes(action.actionLevel ?? '')) return [];
    if (anchor === 'DETAIL' && !['RECORD', 'ANY'].includes(action.actionLevel ?? '')) return [];
    if (
      anchor === 'FORM' &&
      (mode === 'view' ||
        (action.actionCode !== (mode === 'create' ? 'create' : 'update') &&
          !(invoke && placement.invocation?.input === 'FORM_RECORD')))
    )
      return [];
    const supported = invoke
      ? Boolean(placement.invocation)
      : (anchor === 'FORM' && action.actionCode === (mode === 'create' ? 'create' : 'update')) ||
        (anchor === 'PAGE'
          ? ['create', 'query'].includes(action.actionCode)
          : ['update', 'delete', 'enable', 'disable', 'managePermissions'].includes(action.actionCode) ||
            detailActions.some((candidate) => candidate.actionCode === action.actionCode));
    const statusReason =
      managed && anchor === 'DETAIL' && record
        ? action.actionCode === 'enable' && record.enabled !== false
          ? '当前记录已启用'
          : action.actionCode === 'disable' && record.enabled === false
            ? '当前记录已停用'
            : undefined
        : undefined;
    if (statusReason) return [];
    return [
      {
        key: `page-placement:${anchor}:${action.actionCode}`,
        actionCode: action.actionCode,
        title: managed
          ? (placement.title ??
            (invoke ? (action.title ?? action.actionCode) : pageActionEntryTitle(placement)))
          : (action.title ?? action.actionCode),
        danger: action.actionCode === 'delete',
        actionLevel:
          managed && anchor === 'FORM'
            ? 'primary'
            : action.actionCode === 'delete'
              ? 'secondary'
              : 'standard',
        disabled: !supported || Boolean(statusReason),
        disabledReason: supported ? statusReason : '该动作尚未提供当前区域的执行入口',
      },
    ];
  });
}
