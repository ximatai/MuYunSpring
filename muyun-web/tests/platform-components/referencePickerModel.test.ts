import { expect, it } from 'vitest';
import {
  normalizeReferencePickerIds,
  referencePickerSummary,
  referencePickerValue,
} from '@/platform-components/referencePickerModel';

it('keeps persisted IDs separate from display projections and normalizes cardinality', () => {
  expect(normalizeReferencePickerIds(['one', 'one', 'two'], true)).toEqual(['one', 'two']);
  expect(normalizeReferencePickerIds(['one', 'two'], false)).toEqual(['one']);
  expect(referencePickerValue(['one'], false)).toBe('one');
  expect(referencePickerValue(['one', 'two'], true)).toEqual(['one', 'two']);
  expect(
    referencePickerSummary({ id: 'one', title: '第一条', projections: { code: 'A-01' }, unavailable: true }),
  ).toEqual({ id: 'one', title: '第一条', subtitle: undefined, unavailable: true });
});
