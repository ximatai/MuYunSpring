import { assert, it } from 'vitest';
import { resolveRecordPickerMode } from '@/platform-components/recordPickerModel.ts';

it('resolveRecordPickerMode falls back to list when tree ability is unavailable', () => {
  assert.equal(resolveRecordPickerMode('tree', true), 'tree');
  assert.equal(resolveRecordPickerMode('tree', false), 'list');
  assert.equal(resolveRecordPickerMode('list', true), 'list');
});
