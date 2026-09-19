import { describe, expect, it } from 'vitest';
import {
  assistantEditableRecordIds,
  assistantEditCancelDestination,
  hasActiveRecordEditor,
  hasAvailableRecordUpdate,
} from '@/dynamic-page-runtime/assistantRecordEditorPolicy';

describe('assistant record editor policy', () => {
  it('distinguishes a read-only detail draft from an active editor', () => {
    expect(hasActiveRecordEditor('view', { id: 'selected' })).toBe(false);
    expect(hasActiveRecordEditor('edit', { id: 'selected' })).toBe(true);
    expect(hasActiveRecordEditor('create', {})).toBe(true);
  });

  it('offers only selected and normal-list records and excludes recycle-bin records', () => {
    const normal = {
      mode: 'normal' as const,
      status: 'ready' as const,
      quickSearchEnabled: false,
      quickSearchFields: [],
      pageNum: 1,
      pageSize: 20,
      total: 1,
      totalKnown: true,
      rows: [{ id: 'row-1', cells: [] }],
      truncated: false,
    };
    expect(assistantEditableRecordIds('selected', normal)).toEqual(['selected', 'row-1']);
    expect(assistantEditableRecordIds('deleted', { ...normal, mode: 'recycleBin' })).toEqual([]);
  });

  it('requires the record-level update action to be available', () => {
    expect(
      hasAvailableRecordUpdate({
        recordId: 'record-1',
        actions: [{ actionCode: 'update', available: false, reason: 'locked' }],
      }),
    ).toBe(false);
    expect(
      hasAvailableRecordUpdate({
        recordId: 'record-1',
        actions: [{ actionCode: 'update', available: true }],
      }),
    ).toBe(true);
  });

  it('restores only a detail that is currently open for the edited record', () => {
    expect(assistantEditCancelDestination(true, 'record-1', 'record-1')).toBe('restore-view');
    expect(assistantEditCancelDestination(false, 'record-1', 'record-1')).toBe('close');
    expect(assistantEditCancelDestination(true, 'record-1', 'record-2')).toBe('close');
  });
});
