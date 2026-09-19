import type { RecordQueryListQuerySnapshot } from '@muyun/platform-components';
import type { ModuleRecordActionAvailability } from '@muyun/web-core';

export function hasActiveRecordEditor(mode: string, draft: unknown) {
  return (mode === 'create' || mode === 'edit') && Boolean(draft);
}

export function assistantEditableRecordIds(
  selectedRecordId: unknown,
  querySnapshot: RecordQueryListQuerySnapshot | undefined,
) {
  if (querySnapshot?.mode === 'recycleBin') return [];
  return [
    ...(selectedRecordId == null ? [] : [String(selectedRecordId)]),
    ...(querySnapshot?.rows.flatMap((row) => (row.id ? [row.id] : [])) ?? []),
  ].filter((id, index, ids) => ids.indexOf(id) === index);
}

export function hasAvailableRecordUpdate(availability: ModuleRecordActionAvailability) {
  return availability.actions.some((action) => action.actionCode === 'update' && action.available);
}

export function assistantEditCancelDestination(
  detailOpen: boolean,
  selectedRecordId: unknown,
  targetRecordId: string,
) {
  return detailOpen && selectedRecordId != null && String(selectedRecordId) === targetRecordId
    ? ('restore-view' as const)
    : ('close' as const);
}
