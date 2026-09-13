import { expect, it } from 'vitest';
import { scopedTreePickerValue } from '@/platform-components/scopedTreePickerModel';

it('keeps the picker storage contract to one nonblank target ID', () => {
  expect(scopedTreePickerValue(' department-1 ')).toBe('department-1');
  expect(scopedTreePickerValue('   ')).toBeUndefined();
  expect(scopedTreePickerValue(undefined)).toBeUndefined();
});
