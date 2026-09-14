import { expect, it } from 'vitest';
import { normalizeUserAccountIds, userPickerValue } from '@/platform-components/userPickerModel';

it('keeps the user account ID value contract distinct for single and multiple pickers', () => {
  expect(normalizeUserAccountIds(['account-1', 'account-1', 'account-2'], true)).toEqual([
    'account-1',
    'account-2',
  ]);
  expect(normalizeUserAccountIds(['account-1', 'account-2'], false)).toEqual(['account-1']);
  expect(userPickerValue(['account-1'], false)).toBe('account-1');
  expect(userPickerValue(['account-1', 'account-2'], true)).toEqual(['account-1', 'account-2']);
});
