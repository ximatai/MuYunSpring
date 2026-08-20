import { assert, it } from 'vitest';
import { recordPickerModeOf, resolveRecordPickerMode } from '@/platform-components/recordPickerModel.ts';

it('resolveRecordPickerMode falls back to list when tree ability is unavailable', () => {
  assert.equal(resolveRecordPickerMode('tree', true), 'tree');
  assert.equal(resolveRecordPickerMode('tree', false), 'list');
  assert.equal(resolveRecordPickerMode('list', true), 'list');
  assert.equal(resolveRecordPickerMode('auto', true), 'tree');
  assert.equal(resolveRecordPickerMode('auto', false), 'list');
});

it('maps resolved reference picker modes and preserves AUTO only as a legacy fallback', () => {
  assert.equal(recordPickerModeOf('LIST'), 'list');
  assert.equal(recordPickerModeOf('TREE'), 'tree');
  assert.equal(recordPickerModeOf('AUTO'), 'auto');
  assert.equal(recordPickerModeOf(), 'auto');
});
