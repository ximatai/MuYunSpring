import type { RecordActionItem } from '@muyun/platform-components';
import type { ResolvedPageActionDescriptor } from '@muyun/web-contracts';

/** Placements choose presentation; execution must already have a platform workflow or issued action block. */
export function resolvePlacedPageActions(
  placements: ResolvedPageActionDescriptor[],
  anchor: ResolvedPageActionDescriptor['anchor'],
  actionOf: (code: string) => { actionCode: string; title?: string; actionLevel?: string } | undefined,
  detailActions: RecordActionItem[],
  mode: 'create' | 'edit' | 'view',
): RecordActionItem[] {
  return placements.flatMap((placement) => {
    if (placement.anchor !== anchor) return [];
    const action = actionOf(placement.actionCode);
    if (!action) return [];
    if (anchor === 'PAGE' && !['LIST', 'ANY'].includes(action.actionLevel ?? '')) return [];
    if (anchor === 'DETAIL' && !['RECORD', 'ANY'].includes(action.actionLevel ?? '')) return [];
    if (
      anchor === 'FORM' &&
      (mode === 'view' || action.actionCode !== (mode === 'create' ? 'create' : 'update'))
    )
      return [];
    const supported =
      anchor === 'FORM' ||
      (anchor === 'PAGE'
        ? ['create', 'query'].includes(action.actionCode)
        : ['update', 'delete', 'enable', 'disable'].includes(action.actionCode) ||
          detailActions.some((candidate) => candidate.actionCode === action.actionCode));
    return [
      {
        key: `page-placement:${anchor}:${action.actionCode}`,
        actionCode: action.actionCode,
        title: action.title ?? action.actionCode,
        actionLevel: action.actionCode === 'delete' ? 'secondary' : 'standard',
        disabled: !supported,
        disabledReason: supported ? undefined : '该动作尚未提供当前区域的执行入口',
      },
    ];
  });
}
