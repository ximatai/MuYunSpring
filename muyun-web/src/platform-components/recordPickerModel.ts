import type { ReferencePickerMode } from '@muyun/web-contracts';

export type RecordPickerMode = 'list' | 'tree' | 'auto';
export type ResolvedRecordPickerMode = Exclude<RecordPickerMode, 'auto'>;

export function resolveRecordPickerMode(
  mode: RecordPickerMode,
  treeAvailable: boolean,
): ResolvedRecordPickerMode {
  if (mode === 'list') return 'list';
  return treeAvailable ? 'tree' : 'list';
}

/** AUTO preserves the legacy capability-based choice for descriptors without an explicit picker mode. */
export function recordPickerModeOf(mode?: ReferencePickerMode): RecordPickerMode {
  switch (mode) {
    case 'LIST':
      return 'list';
    case 'TREE':
      return 'tree';
    case 'AUTO':
    default:
      return 'auto';
  }
}
