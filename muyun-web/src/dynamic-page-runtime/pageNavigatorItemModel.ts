import type { RecordExplorerItemDescriptor } from '@muyun/platform-components';
import type { RecordInlineAction } from '@muyun/web-contracts';

export interface NavigatorItemRecord {
  id?: string;
  title?: string;
  name?: string;
  code?: string;
  alias?: string;
}

/** Page navigators expose only the actions explicitly declared by the page. */
export function navigatorItemOf(
  record: NavigatorItemRecord,
  secondaryField: string | undefined,
  manageable: boolean,
  actionsOf: ((record: { id?: string }) => RecordInlineAction[]) | undefined,
): RecordExplorerItemDescriptor {
  const title = String(record.title ?? record.name ?? record.code ?? record.id ?? '未命名记录');
  const secondary =
    secondaryField == null
      ? (record.code ?? record.alias)
      : (record as Record<string, unknown>)[secondaryField];
  return {
    title,
    ...(secondary && secondary !== title ? { secondary: String(secondary) } : {}),
    actions: manageable ? (actionsOf?.(record) ?? []) : [],
  };
}
